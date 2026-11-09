package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

/** Fail-closed TEST_ONLY flow-label editor. Only explicitly bound numeric worksheet cells are writable. */
@Component
public class FlowChangeDrawingEngine {
    private static final Pattern CELL_XML = Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\\\"([A-Z]{1,3}[1-9][0-9]*)\\\")[^>]*>.*?</c>", Pattern.DOTALL);
    private static final Pattern VALUE_XML = Pattern.compile("(<v(?:\\s[^>]*)?>)(.*?)(</v>)", Pattern.DOTALL);
    private static final Pattern CELL_REF = Pattern.compile("\\$?[A-Z]{1,3}\\$?[1-9][0-9]*");
    private final OoxmlPackageInspector inspector;
    private final OoxmlPartEditor parts;

    public FlowChangeDrawingEngine(OoxmlPackageInspector inspector, OoxmlPartEditor parts) {
        this.inspector = inspector;
        this.parts = parts;
    }

    public EditResult editNumericLabelsTestOnly(byte[] source, String processCode, String sheetName,
            String expectedSourceSha256, String edgeId, BigDecimal expectedBefore, BigDecimal after,
            List<FlowLabelBinding> bindings) {
        List<String> blockers = new ArrayList<>();
        if (source == null || source.length == 0 || blank(processCode) || blank(sheetName) || blank(edgeId)
                || expectedBefore == null || after == null || expectedBefore.signum() < 0 || after.signum() < 0
                || bindings == null || bindings.isEmpty()) {
            throw blocked("FLOW_CHANGE_INPUT_INVALID", "유량 변경과 명시적 합성 binding이 필요합니다.");
        }
        if (after.precision() > 15 || !Double.isFinite(after.doubleValue())
                || BigDecimal.valueOf(after.doubleValue()).compareTo(after.stripTrailingZeros()) != 0)
            throw blocked("FLOW_LABEL_EXCEL_NUMERIC_PRECISION_UNSUPPORTED", "Excel numeric cell precision cannot represent this value exactly.");
        String sourceSha = sha256(source);
        if (!sourceSha.equalsIgnoreCase(nullToEmpty(expectedSourceSha256))) blockers.add("WORKING_COPY_SHA_MISMATCH");
        Set<String> targetKeys = new LinkedHashSet<>();
        Set<String> bindingIds = new LinkedHashSet<>();
        for (FlowLabelBinding binding : bindings) {
            if (binding == null || blank(binding.bindingId()) || !bindingIds.add(binding.bindingId())
                    || !"SYNTHETIC_VERIFIED_TEST_ONLY".equals(binding.mappingStatus())
                    || binding.evidenceId() == null || !binding.evidenceId().startsWith("SYNTHETIC:")
                    || binding.materialClass() == null || blank(binding.materialType())
                    || blank(binding.unit()) || blank(binding.period())) {
                blockers.add("FLOW_LABEL_MAPPING_NOT_EXPLICITLY_VERIFIED");
                continue;
            }
            if (!processCode.equals(binding.processCode()) || !sheetName.equals(binding.sheetName())
                    || !edgeId.equals(binding.edgeId())) blockers.add("FLOW_LABEL_BINDING_SCOPE_MISMATCH");
            if (!sourceSha.equalsIgnoreCase(nullToEmpty(binding.sourceSha256()))) blockers.add("FLOW_LABEL_BINDING_SOURCE_SHA_MISMATCH");
            if (binding.expectedBefore() == null || binding.expectedBefore().compareTo(expectedBefore) != 0)
                blockers.add("FLOW_LABEL_BEFORE_VALUE_MISMATCH");
            if (binding.targetType() == FlowLabelBinding.TargetType.DRAWINGML_TEXT)
                blockers.add("DRAWINGML_FLOW_LABEL_UNSUPPORTED");
            if (binding.targetType() == null || blank(binding.cellAddress())) blockers.add("FLOW_LABEL_CELL_TARGET_REQUIRED");
            String key = binding.sheetName() + "!" + normalizeAddress(binding.cellAddress());
            if (!targetKeys.add(key)) blockers.add("DUPLICATE_FLOW_LABEL_TARGET:" + key);
        }
        if (!blockers.isEmpty()) throw blocked(String.join(";", blockers), "유량 표시 매핑 검증에 실패했습니다.");

        WorkbookInventory.SheetInventory sheetInventory = inspector.inspectSheet(source, sheetName);
        if (sheetInventory.worksheetPartName() == null) throw blocked("WORKSHEET_PART_MISSING", "대상 시트 XML을 찾지 못했습니다.");
        byte[] result = source;
        List<LabelChange> changes = new ArrayList<>();
        for (FlowLabelBinding binding : bindings) {
            changes.add(editCell(result, sheetInventory.worksheetPartName(), binding, expectedBefore, after));
            result = replaceCellPart(result, sheetInventory.worksheetPartName(), changes.get(changes.size() - 1));
        }
        validatePreservation(source, result, sheetName, sheetInventory.worksheetPartName(), changes);
        return new EditResult(result, "TEST_ONLY_REVIEW_REQUIRED", "NUMERIC_CELL_LABELS_UPDATED_TEST_ONLY",
                sourceSha, sha256(result), List.copyOf(changes), "CALCULATION_UNAVAILABLE", false,
                "Only explicit synthetic numeric cell bindings changed. No real process mapping or physical connection was asserted.");
    }

    private LabelChange editCell(byte[] currentPackage, String worksheetPart, FlowLabelBinding binding,
                                 BigDecimal expected, BigDecimal after) {
        String address = normalizeAddress(binding.cellAddress());
        if (!CELL_REF.matcher(address).matches()) throw blocked("FLOW_LABEL_CELL_ADDRESS_INVALID", "유량 셀 주소가 잘못됐습니다.");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(currentPackage))) {
            var sheet = workbook.getSheet(binding.sheetName());
            if (sheet == null) throw blocked("FLOW_LABEL_SHEET_MISSING", "유량 표시 시트가 없습니다.");
            var ref = new CellReference(address);
            var row = sheet.getRow(ref.getRow());
            var cell = row == null ? null : row.getCell(ref.getCol(), org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() != CellType.NUMERIC)
                throw blocked("FLOW_LABEL_CELL_MUST_BE_NUMERIC", "문자열·공란·수식 유량 셀은 현재 자동 수정 대상이 아닙니다.");
            boolean merged = false;
            for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                CellRangeAddress range = sheet.getMergedRegion(i);
                if (range.isInRange(ref.getRow(), ref.getCol())) {
                    merged = true;
                    if (range.getFirstRow() != ref.getRow() || range.getFirstColumn() != ref.getCol())
                        throw blocked("MERGED_FLOW_LABEL_MUST_TARGET_TOP_LEFT", "병합 유량 표시는 병합 영역 좌상단 숫자 셀만 지원합니다.");
                }
            }
            if (merged != (binding.targetType() == FlowLabelBinding.TargetType.MERGED_NUMERIC_CELL))
                throw blocked("FLOW_LABEL_MERGE_TYPE_MISMATCH", "binding의 병합 유형과 현재 Excel 병합 구조가 다릅니다.");
            BigDecimal current = BigDecimal.valueOf(cell.getNumericCellValue());
            if (current.compareTo(expected) != 0 || current.compareTo(binding.expectedBefore()) != 0)
                throw blocked("STALE_FLOW_LABEL_VALUE", "유량 셀 기존값이 제안 시점 값과 다릅니다: " + address);
            int style = cell.getCellStyle().getIndex();
            String displayBefore = new DataFormatter(Locale.ROOT).formatCellValue(cell);
            String xml = new String(parts.readPart(currentPackage, worksheetPart), StandardCharsets.UTF_8);
            Matcher matcher = CELL_XML.matcher(xml);
            String targetCell = null;
            while (matcher.find()) if (matcher.group(1).equalsIgnoreCase(address)) {
                if (targetCell != null) throw blocked("FLOW_LABEL_CELL_XML_AMBIGUOUS", "worksheet XML에서 대상 셀이 중복됩니다.");
                targetCell = matcher.group();
            }
            if (targetCell == null || targetCell.contains("<f") || targetCell.matches("(?s).*\\st=\\\"(?:s|str|inlineStr|b|e)\\\".*"))
                throw blocked("FLOW_LABEL_CELL_OOXML_UNSUPPORTED", "대상 셀이 수식 또는 비숫자 OOXML 형식입니다.");
            Matcher valueMatcher = VALUE_XML.matcher(targetCell);
            if (!valueMatcher.find()) throw blocked("FLOW_LABEL_CELL_VALUE_INVALID", "숫자 셀의 OOXML 값이 필요합니다.");
            String rawValue = valueMatcher.group(2);
            if (valueMatcher.find()) throw blocked("FLOW_LABEL_CELL_VALUE_INVALID", "숫자 셀의 단일 OOXML 값이 필요합니다.");
            BigDecimal rawBefore;
            try { rawBefore = new BigDecimal(rawValue); }
            catch (NumberFormatException exception) { throw blocked("FLOW_LABEL_CELL_VALUE_INVALID", "유량 셀 값이 유한한 숫자가 아닙니다."); }
            if (rawBefore.compareTo(expected) != 0) throw blocked("STALE_FLOW_LABEL_VALUE", "OOXML 원시값이 binding의 기존값과 다릅니다.");
            String displayAfter = formatWithStyle(workbook, sheet, ref.getRow(), ref.getCol(), after);
            return new LabelChange(binding.bindingId(), binding.edgeId(), binding.targetType(), binding.sheetName(), address,
                    current, after, binding.unit(), binding.period(), binding.materialClass(), binding.materialType(),
                    style, displayBefore, displayAfter);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to validate the FLOW_CHANGE numeric label", exception);
        }
    }

    private String formatWithStyle(org.apache.poi.ss.usermodel.Workbook workbook, org.apache.poi.ss.usermodel.Sheet sheet,
                                   int rowIndex, int columnIndex, BigDecimal value) {
        var row = sheet.getRow(rowIndex);
        var cell = row.getCell(columnIndex, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        double old = cell.getNumericCellValue();
        try {
            cell.setCellValue(value.doubleValue());
            return new DataFormatter(Locale.ROOT).formatCellValue(cell);
        } finally {
            cell.setCellValue(old);
        }
    }

    private byte[] replaceCellPart(byte[] packageBytes, String worksheetPart, LabelChange change) {
        String xml = new String(parts.readPart(packageBytes, worksheetPart), StandardCharsets.UTF_8);
        Matcher matcher = CELL_XML.matcher(xml);
        StringBuffer updated = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String cell = matcher.group();
            if (matcher.group(1).equalsIgnoreCase(change.cellAddress())) {
                if (replaced) throw blocked("FLOW_LABEL_CELL_XML_AMBIGUOUS", "worksheet XML에서 대상 셀이 중복됩니다.");
                Matcher value = VALUE_XML.matcher(cell);
                if (!value.find()) throw blocked("FLOW_LABEL_CELL_VALUE_INVALID", "OOXML 숫자 값이 없습니다.");
                StringBuilder updatedCell = new StringBuilder(cell.length() + 16);
                value.appendReplacement(updatedCell, Matcher.quoteReplacement(value.group(1)
                        + change.afterQuantity().stripTrailingZeros().toPlainString() + value.group(3)));
                value.appendTail(updatedCell);
                cell = updatedCell.toString();
                replaced = true;
            }
            matcher.appendReplacement(updated, Matcher.quoteReplacement(cell));
        }
        matcher.appendTail(updated);
        if (!replaced) throw blocked("FLOW_LABEL_CELL_NOT_FOUND", "OOXML worksheet에서 유량 셀을 찾지 못했습니다.");
        return parts.replacePackagePart(packageBytes, worksheetPart, updated.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void validatePreservation(byte[] source, byte[] output, String sheetName, String changedPart,
                                      List<LabelChange> changes) {
        try {
            if (!packagePartsEqualExcept(source, output, changedPart))
                throw blocked("FLOW_CHANGE_NON_TARGET_PACKAGE_PART_CHANGED", "대상 worksheet 외 OOXML part 변경이 감지됐습니다.");
            try (var before = WorkbookFactory.create(new ByteArrayInputStream(source));
                 var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
                var oldSheet = before.getSheet(sheetName); var newSheet = after.getSheet(sheetName);
                if (oldSheet.getNumMergedRegions() != newSheet.getNumMergedRegions()
                        || oldSheet.getPrintSetup().getPaperSize() != newSheet.getPrintSetup().getPaperSize()
                        || oldSheet.getPrintSetup().getFitWidth() != newSheet.getPrintSetup().getFitWidth()
                        || oldSheet.getPrintSetup().getFitHeight() != newSheet.getPrintSetup().getFitHeight()
                        || !java.util.Objects.equals(before.getPrintArea(before.getSheetIndex(oldSheet)),
                                after.getPrintArea(after.getSheetIndex(newSheet))))
                    throw blocked("FLOW_CHANGE_WORKSHEET_LAYOUT_CHANGED", "병합 또는 인쇄 설정이 변경됐습니다.");
                for (int i = 0; i < oldSheet.getNumMergedRegions(); i++)
                    if (!oldSheet.getMergedRegion(i).formatAsString().equals(newSheet.getMergedRegion(i).formatAsString()))
                        throw blocked("FLOW_CHANGE_MERGE_CHANGED", "병합 범위가 변경됐습니다.");
                for (LabelChange change : changes) {
                    var ref = new CellReference(change.cellAddress());
                    var oldCell = oldSheet.getRow(ref.getRow()).getCell(ref.getCol());
                    var newCell = newSheet.getRow(ref.getRow()).getCell(ref.getCol());
                    if (newCell.getCellType() != CellType.NUMERIC
                            || BigDecimal.valueOf(newCell.getNumericCellValue()).compareTo(change.afterQuantity()) != 0
                            || oldCell.getCellStyle().getIndex() != newCell.getCellStyle().getIndex())
                        throw blocked("FLOW_CHANGE_REOPEN_OR_STYLE_MISMATCH", "수정된 숫자/서식이 재열기 검증과 다릅니다.");
                }
                int oldFormulas = formulaCount(oldSheet); int newFormulas = formulaCount(newSheet);
                if (oldFormulas != newFormulas) throw blocked("FLOW_CHANGE_FORMULA_COUNT_CHANGED", "수식 개수가 변경됐습니다.");
            }
            var beforeInventory = inspector.inspectSheet(source, sheetName);
            var afterInventory = inspector.inspectSheet(output, sheetName);
            if (!beforeInventory.drawingObjects().equals(afterInventory.drawingObjects()))
                throw blocked("FLOW_CHANGE_DRAWING_OBJECTS_CHANGED", "DrawingML 또는 Connector가 변경됐습니다.");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify FLOW_CHANGE output preservation", exception);
        }
    }

    private int formulaCount(org.apache.poi.ss.usermodel.Sheet sheet) {
        int count = 0;
        for (var row : sheet) for (var cell : row) if (cell.getCellType() == CellType.FORMULA) count++;
        return count;
    }

    private boolean packagePartsEqualExcept(byte[] before, byte[] after, String changedPart) {
        var oldParts = readPackageParts(before); var newParts = readPackageParts(after);
        if (!oldParts.keySet().equals(newParts.keySet())) return false;
        for (String name : oldParts.keySet())
            if (!name.equals(changedPart) && !java.util.Arrays.equals(oldParts.get(name), newParts.get(name))) return false;
        return !java.util.Arrays.equals(oldParts.get(changedPart), newParts.get(changedPart));
    }

    private java.util.Map<String, byte[]> readPackageParts(byte[] bytes) {
        java.util.Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory()) try (var input = zip.getInputStream(entry)) {
                    if (result.putIfAbsent(entry.getName(), input.readAllBytes()) != null)
                        throw blocked("DUPLICATE_OOXML_PART", "OOXML package 내 중복 part가 있습니다.");
                }
            }
            return result;
        } catch (InvalidWorkbookException exception) { throw exception; }
        catch (Exception exception) { throw new InvalidWorkbookException("Unable to compare OOXML package parts", exception); }
    }

    private String normalizeAddress(String address) {
        if (address == null) return "";
        try { return new CellReference(address).formatAsString().replace("$", "").toUpperCase(Locale.ROOT); }
        catch (RuntimeException exception) { return address.trim().toUpperCase(Locale.ROOT); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private InvalidWorkbookException blocked(String code, String message) { return new InvalidWorkbookException(code + ": " + message); }

    public record LabelChange(String bindingId, String edgeId, FlowLabelBinding.TargetType targetType,
                              String sheetName, String cellAddress, BigDecimal beforeQuantity,
                              BigDecimal afterQuantity, String unit, String period,
                              com.example.processautomation.material.DirectedProcessNetwork.MaterialClass materialClass,
                              String materialType, int styleId, String displayBefore, String displayAfter) { }

    public record EditResult(byte[] workbookBytes, String status, String structuralStatus, String sourceSha256,
                             String outputSha256, List<LabelChange> changes, String officialReadiness,
                             boolean officialBaselineModified, String limitation) {
        public EditResult { changes = List.copyOf(changes); }
    }
}
