package com.example.processautomation.excel;

import com.example.processautomation.model.MasterEquipment;
import com.example.processautomation.model.ParsedMaster;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import com.example.processautomation.model.ParsedMaster.ParseIssue;

@Component
public class MasterWorkbookParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Map<String, List<String>> REQUIRED_HEADERS = Map.of(
            "permitManagementNo", List.of("인허가관리번호", "인허가번호", "관리번호"),
            "businessItemNo", List.of("사업장itemno", "사업장itemno.", "itemno", "사업장item번호"),
            "middleProcessCode", List.of("중분류번호", "중분류코드", "공정세부번호"),
            "majorProcessCode", List.of("대분류번호", "대분류코드", "공정번호"),
            "facilityName", List.of("시설명", "설비명"));

    public ParsedMaster parse(byte[] workbookBytes) {
        List<MasterEquipment> equipment = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        Set<String> seenPermitNumbers = new HashSet<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            Sheet sheet = workbook.getSheet("마스터시트");
            if (sheet == null) {
                throw new InvalidWorkbookException("The workbook does not contain the required sheet '마스터시트'");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row headerRow = findHeaderRow(sheet, formatter);
            Map<String, Integer> headerIndexes = headerIndexes(headerRow, formatter);
            for (Map.Entry<String, List<String>> requiredHeader : REQUIRED_HEADERS.entrySet()) {
                if (findIndex(headerIndexes, requiredHeader.getValue()) == null) {
                    throw new InvalidWorkbookException("Required master column is missing: " + requiredHeader.getValue());
                }
            }

            int order = 0;
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                int sourceRow = rowIndex + 1;
                Map<String, String> raw = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> header : headerIndexes.entrySet()) {
                    Cell cell = row.getCell(header.getValue(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    raw.put(header.getKey(), cell == null ? "" : ExcelCellText.format(cell, formatter).trim());
                }
                String permitNo = value(raw, "인허가관리번호", "인허가번호", "관리번호");
                if (permitNo.isBlank()) {
                    issues.add(new ParseIssue(sourceRow, "ERROR", "MISSING_PERMIT_ID", "인허가 관리번호",
                            "Non-empty master row has no permit management number; the row was not imported."));
                    continue;
                }
                if (!seenPermitNumbers.add(permitNo)) {
                    issues.add(new ParseIssue(sourceRow, "ERROR", "DUPLICATE_PERMIT_ID", "인허가 관리번호",
                            "Duplicate permit management number: " + permitNo));
                    continue;
                }
                order++;
                MasterEquipment record = new MasterEquipment(
                        sourceRow, order, permitNo,
                        optional(raw, "사업장ItemNo", "사업장ItemNo.", "ItemNo", "사업장Item번호"),
                        optional(raw, "중분류번호", "중분류코드", "공정세부번호"),
                        optional(raw, "대분류번호", "대분류코드", "공정번호"),
                        optional(raw, "공정분류"),
                        optional(raw, "시설명", "설비명"),
                        optional(raw, "인허가명칭", "허가명칭"),
                        optional(raw, "인허가대분류"),
                        optional(raw, "용량"), optional(raw, "용량단위"), optional(raw, "수량"),
                        optional(raw, "일일가동시간시간일", "일일가동시간"),
                        optional(raw, "연간가동일수일년", "연간가동일수"),
                        optional(raw, "용도"),
                        optional(raw, "폐기물종류"), optional(raw, "폐기물량톤일", "폐기물량"),
                        optional(raw, "폐기물이동처", "폐기물이동"),
                        optional(raw, "폐수종류"), optional(raw, "폐수량톤일", "폐수량"),
                        optional(raw, "폐수이동처", "폐수이동량처"),
                        optional(raw, "P&IDNo", "PIDNo", "P&ID번호"),
                        optional(raw, "폐쇄여부"), optional(raw, "서류상현존"), raw);
                equipment.add(record);
                addFlowIssues(record, issues);
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to parse the master workbook", exception);
        }
        if (equipment.isEmpty()) {
            throw new InvalidWorkbookException("The master worksheet contains no equipment records");
        }
        return new ParsedMaster(equipment, issues);
    }

    private Row findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int last = Math.min(sheet.getLastRowNum(), 20);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> headers = headerIndexes(row, formatter);
            if (REQUIRED_HEADERS.values().stream().allMatch(names -> findIndex(headers, names) != null)) {
                return row;
            }
        }
        throw new InvalidWorkbookException("Unable to find a master header row containing permit ID, item, and process columns");
    }

    private Map<String, Integer> headerIndexes(Row row, DataFormatter formatter) {
        Map<String, Integer> indexes = new HashMap<>();
        for (Cell cell : row) {
            String raw = ExcelCellText.format(cell, formatter).trim();
            if (!raw.isBlank()) {
                indexes.put(normalize(raw), cell.getColumnIndex());
            }
        }
        return indexes;
    }

    private Integer findIndex(Map<String, Integer> indexes, List<String> candidates) {
        for (String candidate : candidates) {
            Integer index = indexes.get(normalize(candidate));
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s._()（）·&/\\-]", "");
    }

    private String value(Map<String, String> values, String... candidates) {
        return optional(values, candidates);
    }

    private String optional(Map<String, String> values, String... candidates) {
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (normalize(entry.getKey()).equals(normalized)) {
                    return entry.getValue() == null ? "" : entry.getValue().trim();
                }
            }
        }
        return "";
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!ExcelCellText.format(cell, formatter).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void addFlowIssues(MasterEquipment equipment, List<ParseIssue> issues) {
        addFlowIssue(equipment, "WASTE", equipment.wasteType(), equipment.wasteQuantityText(),
                equipment.wasteDestination(), issues);
        addFlowIssue(equipment, "WASTEWATER", equipment.wastewaterType(), equipment.wastewaterQuantityText(),
                equipment.wastewaterDestination(), issues);
    }

    private void addFlowIssue(MasterEquipment equipment, String type, String material, String amount,
                              String destination, List<ParseIssue> issues) {
        if (material.isBlank() && amount.isBlank() && destination.isBlank()) {
            return;
        }
        BigDecimal quantity = parseQuantity(amount);
        if (!amount.isBlank() && quantity == null) {
            issues.add(new ParseIssue(equipment.sourceRowNumber(), "ERROR", "INVALID_FLOW_QUANTITY",
                    type + " quantity", "Could not parse a numeric quantity from: " + amount));
            return;
        }
        if (quantity != null && quantity.signum() < 0) {
            issues.add(new ParseIssue(equipment.sourceRowNumber(), "ERROR", "NEGATIVE_FLOW_QUANTITY",
                    type + " quantity", "Negative quantity is invalid: " + amount));
            return;
        }
        boolean explicitZero = quantity != null && quantity.compareTo(BigDecimal.ZERO) == 0;
        if ((!amount.isBlank() && destination.isBlank() && !explicitZero)
                || (amount.isBlank() && !destination.isBlank())
                || (amount.isBlank() && !material.isBlank())) {
            issues.add(new ParseIssue(equipment.sourceRowNumber(), "WARNING", "INCOMPLETE_FLOW_INFORMATION",
                    type, "Flow information has a missing quantity or destination; retained for review, not treated as zero."));
        }
    }

    private BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text.replace('\u00a0', ' '));
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group().replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
