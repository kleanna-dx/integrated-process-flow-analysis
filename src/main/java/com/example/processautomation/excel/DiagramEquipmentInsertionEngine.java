package com.example.processautomation.excel;

import com.example.processautomation.model.MasterEquipment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Template-driven, append-only TEST_ONLY equipment-box editor. It never asserts or draws a process connection. */
@Component
public class DiagramEquipmentInsertionEngine {
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String X = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final Pattern CELL = Pattern.compile("\\$?([A-Z]{1,3})\\$?([1-9][0-9]*)");
    private final OoxmlPartEditor parts;
    private final OoxmlPackageInspector inspector;
    private final EquipmentBoxGeometryLocator boxLocator;

    public DiagramEquipmentInsertionEngine(OoxmlPartEditor parts, OoxmlPackageInspector inspector,
                                           EquipmentBoxGeometryLocator boxLocator) {
        this.parts = parts;
        this.inspector = inspector;
        this.boxLocator = boxLocator;
    }

    public Plan analyze(byte[] bytes, String sheetName, String sourceId, String targetId) {
        return analyze(bytes, sheetName, sourceId, targetId, null, null, null);
    }

    public Plan analyze(byte[] bytes, String sheetName, String sourceId, String targetId,
                        EquipmentBoxProfile profileDefinition, String processCode, String sourceSha256) {
        Objects.requireNonNull(bytes, "workbook bytes");
        requireText(sheetName, "sheet name");
        requireText(sourceId, "source equipment ID");
        requireText(targetId, "target equipment ID");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw blocked("TARGET_SHEET_MISSING", "대상 시트가 없습니다.");
            var sourceBoxes = boxLocator.locateByManagementNo(sheet, sourceId);
            var targetBoxes = boxLocator.locateByManagementNo(sheet, targetId);
            if (sourceBoxes.size() != 1 || targetBoxes.size() != 1) {
                throw blocked("EQUIPMENT_BOX_NOT_UNIQUE", "선행/후행 설비 ID가 각각 단일 설비 박스로 식별되지 않습니다.");
            }
            var source = sourceBoxes.get(0);
            var target = targetBoxes.get(0);
            if (source.firstColumn() != target.firstColumn() || source.lastColumn() != target.lastColumn()) {
                throw blocked("DIAGRAM_COLUMNS_DIFFER", "두 템플릿 박스가 같은 열 범위에 있지 않습니다.");
            }
            if (target.firstRow() <= source.lastRow()) throw blocked("EQUIPMENT_ORDER_INVALID", "설비 박스가 겹치거나 순서가 맞지 않습니다.");
            int gapRows = target.firstRow() - source.lastRow() - 1;
            int height = source.lastRow() - source.firstRow() + 1;
            if (height < 7 || height > 30) throw blocked("TEMPLATE_HEIGHT_UNSUPPORTED", "원본 설비 박스 높이가 지원되는 범위를 벗어납니다.");
            TemplateProfile templateProfile;
            if (profileDefinition == null && processCode == null) {
                templateProfile = extractTemplate(sheet, source, height);
            } else {
                if (profileDefinition == null) throw blocked("PROFILE_MISSING", "공정·시트에 등록된 설비 박스 프로필이 없습니다.");
                if (!profileDefinition.appliesTo(processCode, sheetName, sourceSha256))
                    throw blocked("PROFILE_IDENTITY_MISMATCH", "공정·시트·기준 SHA가 프로필과 일치하지 않습니다.");
                if (!profileDefinition.matchesWorkingCopySha(sha256(bytes)))
                    throw blocked("PROFILE_WORKING_SHA_MISMATCH", "프로필에서 검증된 별도 작업본 SHA와 현재 작업본이 다릅니다.");
                if (!profileDefinition.enabledForGenericAdd())
                    throw blocked("PROFILE_NOT_VERIFIED", "프로필 적용이 허용되지 않습니다: " + profileDefinition.blocker());
                templateProfile = extractTemplate(sheet, source, height, profileDefinition);
            }
            if (!templateProfile.recognized()) throw blocked("TEMPLATE_FIELD_MAP_UNVERIFIED", "원본 박스 필드 mapping이 검증되지 않습니다: " + templateProfile.profileId());
            if (sheet.getSheetName().isBlank() || sheet.getLastRowNum() + height + gapRows > 1_048_575) {
                throw blocked("WORKSHEET_ROW_LIMIT", "행 삽입이 Excel 행 한도를 초과합니다.");
            }
            if (sheet.getPhysicalNumberOfRows() < 1) throw blocked("EMPTY_WORKSHEET", "대상 시트가 비어 있습니다.");
            for (Row row : sheet) for (Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                    throw blocked("FORMULA_SHEET_UNSUPPORTED", "수식이 포함된 시트는 현재 범용 행 이동 대상이 아닙니다.");
                }
            }
            var inventory = inspector.inspect(bytes);
            var drawing = inventory.sheets().stream().filter(item -> sheetName.equals(item.sheetName())).findFirst()
                    .orElseThrow(() -> blocked("SHEET_INVENTORY_MISSING", "대상 시트의 DrawingML inventory가 없습니다."));
            if (drawing.drawingPartName() == null) throw blocked("DRAWINGML_MISSING", "대상 시트에 DrawingML 파트가 없어 안전한 편집을 확인할 수 없습니다.");
            int firstCandidateRow = target.firstRow();
            int lastCandidateRow = firstCandidateRow + height - 1;
            int firstCandidateColumn = source.firstColumn();
            int lastCandidateColumn = source.lastColumn();
            List<String> occupied = occupiedCells(sheet, source.lastRow(), target.firstRow() - 2,
                    firstCandidateColumn, lastCandidateColumn);
            if (!occupied.isEmpty()) throw blocked("CANDIDATE_CELLS_OCCUPIED", "후보 셀 범위에 기존 입력값이 있습니다: " + String.join(",", occupied));
            List<String> mergeConflicts = sheet.getMergedRegions().stream()
                    .filter(range -> overlaps(range, source.lastRow(), target.firstRow() - 2,
                            firstCandidateColumn, lastCandidateColumn))
                    .map(CellRangeAddress::formatAsString).toList();
            if (!mergeConflicts.isEmpty()) throw blocked("CANDIDATE_MERGED_CELLS", "삽입 후보 범위가 기존 병합셀과 겹칩니다: " + String.join(",", mergeConflicts));
            int insertionIndex = firstCandidateRow - 1;
            List<String> mergesCrossingBoundary = sheet.getMergedRegions().stream()
                    .filter(range -> range.getFirstRow() < insertionIndex && range.getLastRow() >= insertionIndex)
                    .map(CellRangeAddress::formatAsString).toList();
            if (!mergesCrossingBoundary.isEmpty()) throw blocked("MERGE_CROSSES_INSERTION_BOUNDARY",
                    "행 삽입 경계를 가로지르는 병합 셀이 있습니다: " + String.join(",", mergesCrossingBoundary));
            List<MergeTemplate> copiedMerges = sourceMerges(sheet, source);
            int reserveRows = Math.min(gapRows, 3);
            if (reserveRows > 0) {
                for (int row = source.lastRow() + 1; row <= source.lastRow() + reserveRows; row++) {
                    if (sheet.getRow(row - 1) == null) throw blocked("GAP_TEMPLATE_MISSING", "원본 박스 아래의 빈 행 템플릿이 없어 행 높이를 안전히 복제할 수 없습니다.");
                }
            }
            String drawingPart = drawing.drawingPartName();
            validateDrawingAnchors(bytes, drawingPart, target.firstRow(), firstCandidateColumn, lastCandidateColumn,
                    source.lastRow(), target.firstRow() - 2);
            String worksheetPart = drawing.worksheetPartName();
            if (worksheetPart == null) throw blocked("WORKSHEET_PART_MISSING", "대상 worksheet OOXML 파트를 확인할 수 없습니다.");
            String printArea = printAreaForSheet(bytes, sheetName);
            if (printArea == null) throw blocked("PRINT_AREA_MISSING", "대상 시트의 인쇄 영역을 확인할 수 없습니다.");
            if (profileDefinition != null && profileDefinition.printArea() != null
                    && !normalizePrintArea(profileDefinition.printArea()).equals(normalizePrintArea(printArea)))
                throw blocked("PROFILE_PRINT_AREA_MISMATCH", "현재 인쇄 영역이 프로필과 다릅니다.");
            int insertRows = height + reserveRows;
            String candidateRange = cellAddress(firstCandidateColumn, firstCandidateRow) + ":"
                    + cellAddress(lastCandidateColumn, lastCandidateRow);
            return new Plan(sheetName, workbook.getSheetIndex(sheet), worksheetPart, drawingPart, source, target,
                    sourceId, targetId, source.firstColumn(), source.lastColumn(), source.firstRow(), source.lastRow(),
                    firstCandidateRow, lastCandidateRow, height, gapRows, reserveRows, insertRows, candidateRange,
                    templateProfile, copiedMerges, "VERIFIED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to analyze template-driven insertion candidate", exception);
        }
    }

    public EditResult insertBoxOnlyTestOnly(byte[] sourceBytes, MasterEquipment equipment, String sheetName,
                                            String sourceId, String targetId, String typeLabel) {
        return insertBoxOnlyTestOnly(sourceBytes, equipment, sheetName, sourceId, targetId, typeLabel, null, null, null);
    }

    public EditResult insertBoxOnlyTestOnly(byte[] sourceBytes, MasterEquipment equipment, String sheetName,
                                            String sourceId, String targetId, String typeLabel,
                                            EquipmentBoxProfile profile, String processCode, String sourceSha256) {
        return insertBoxOnlyTestOnly(sourceBytes, equipment, sheetName, sourceId, targetId, typeLabel,
                profile, processCode, sourceSha256, null);
    }

    public EditResult insertBoxOnlyTestOnly(byte[] sourceBytes, MasterEquipment equipment, String sheetName,
                                            String sourceId, String targetId, String typeLabel,
                                            EquipmentBoxProfile profile, String processCode, String sourceSha256,
                                            String note) {
        Objects.requireNonNull(equipment, "equipment");
        requireText(equipment.permitManagementNo(), "new equipment ID");
        requireText(equipment.facilityName(), "new equipment name");
        requireText(equipment.businessItemNo(), "new business item number");
        requireText(equipment.capacityText(), "new capacity");
        requireText(equipment.quantityText(), "new quantity");
        requireText(typeLabel, "equipment type label");
        if (typeLabel.length() > 128) throw blocked("TYPE_LABEL_TOO_LONG", "설비 유형 표시가 128자를 초과합니다.");
        Plan plan = profile == null
                ? analyze(sourceBytes, sheetName, sourceId, targetId)
                : analyze(sourceBytes, sheetName, sourceId, targetId, profile, processCode, sourceSha256);
        if (containsExactText(sourceBytes, sheetName, equipment.permitManagementNo())) {
            throw blocked("DUPLICATE_EQUIPMENT_ID", "신규 설비번호가 대상 작업본에 이미 존재합니다.");
        }
        if (containsExactText(sourceBytes, sheetName, equipment.businessItemNo())) {
            throw blocked("DUPLICATE_ITEM_NO", "신규 Item No.가 대상 시트에 이미 존재합니다.");
        }
        byte[] worksheet = patchWorksheet(sourceBytes, plan, equipment, typeLabel, note);
        byte[] drawing = patchDrawing(sourceBytes, plan);
        byte[] workbook = patchWorkbook(sourceBytes, plan);
        byte[] result = parts.replacePackageParts(sourceBytes, Map.of(
                plan.worksheetPart(), worksheet,
                plan.drawingPart(), drawing,
                "xl/workbook.xml", workbook));
        validateOutput(sourceBytes, result, equipment, typeLabel, note, plan);
        return new EditResult(result, plan, sha256(sourceBytes), sha256(result), "TEST_ONLY", "REVIEW_REQUIRED",
                "No connector or flow line was created; this output is a layout-only equipment box candidate.");
    }

    private TemplateProfile extractTemplate(org.apache.poi.ss.usermodel.Sheet sheet,
                                             EquipmentBoxGeometryLocator.Box box, int height,
                                             EquipmentBoxProfile profile) {
        if (profile.boxHeight() != height || profile.templateRange() == null
                || !profile.templateRange().startsWith(box.topLeftCell() + ":")
                || !profile.templateRange().endsWith(box.bottomRightCell())) {
            throw blocked("PROFILE_TEMPLATE_GEOMETRY_MISMATCH", "프로필 템플릿 범위 또는 높이가 현재 설비 박스와 다릅니다.");
        }
        Set<String> supported = Set.of("managementNo", "equipmentTypeLabel", "equipmentName",
                "businessItemNo", "capacity", "capacityUnit", "quantity", "note");
        if (!supported.containsAll(profile.fields().keySet()))
            throw blocked("PROFILE_FIELD_UNSUPPORTED", "프로필에 엔진이 처리하지 않는 필드 mapping이 있습니다.");
        for (String required : List.of("managementNo", "equipmentTypeLabel", "equipmentName", "businessItemNo", "capacity", "quantity")) {
            var mapping = profile.fields().get(required);
            if (mapping == null || !mapping.required())
                throw blocked("PROFILE_REQUIRED_FIELD_MISSING", "필수 field mapping이 없거나 required가 아닙니다: " + required);
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int row0 = box.firstRow() - 1;
        int width = box.lastColumn() - box.firstColumn() + 1;
        Map<String, Integer> rowOffsets = new LinkedHashMap<>();
        Map<String, Integer> columnOffsets = new LinkedHashMap<>();
        Map<String, String> cells = new LinkedHashMap<>();
        Map<String, Integer> styles = new LinkedHashMap<>();
        Set<String> addresses = new HashSet<>();
        for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
            String field = entry.getKey();
            EquipmentBoxProfile.FieldCell mapping = entry.getValue();
            if (mapping.rowOffset() < 0 || mapping.rowOffset() >= height || mapping.columnOffset() < 0 || mapping.columnOffset() >= width)
                throw blocked("PROFILE_FIELD_OUTSIDE_TEMPLATE", "필드가 템플릿 범위 밖에 있습니다: " + field);
            int rowIndex = row0 + mapping.rowOffset();
            int column = box.firstColumn() + mapping.columnOffset();
            Row row = sheet.getRow(rowIndex);
            Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
            if (cell == null) throw blocked("PROFILE_FIELD_CELL_MISSING", "프로필 필드 셀이 없습니다: " + field);
            if (mapping.required() && formatter.formatCellValue(cell).isBlank())
                throw blocked("PROFILE_FIELD_CELL_EMPTY", "필수 프로필 필드 셀이 비어 있습니다: " + field);
            if (mapping.expectedStyleId() != null && mapping.expectedStyleId() != cell.getCellStyle().getIndex())
                throw blocked("PROFILE_STYLE_MISMATCH", "필드 셀 스타일이 프로필과 다릅니다: " + field);
            var font = sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt());
            if (mapping.expectedFontName() != null && !mapping.expectedFontName().equals(font.getFontName()))
                throw blocked("PROFILE_FONT_MISMATCH", "필드 글꼴이 프로필과 다릅니다: " + field);
            if (mapping.expectedFontSizePt() != null && mapping.expectedFontSizePt() != font.getFontHeightInPoints())
                throw blocked("PROFILE_FONT_SIZE_MISMATCH", "필드 글꼴 크기가 프로필과 다릅니다: " + field);
            if (mapping.expectedFontColor() != null && "DEFAULT_BLACK".equals(mapping.expectedFontColor())
                    && font.getColor() != org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex()
                    && font.getColor() != org.apache.poi.ss.usermodel.Font.COLOR_NORMAL)
                throw blocked("PROFILE_FONT_COLOR_MISMATCH", "필드 글자색이 기본 검정이 아닙니다: " + field);
            Float rowHeight = profile.rowHeightsPt().get(mapping.rowOffset());
            if (rowHeight != null && Math.abs(sheet.getRow(rowIndex).getHeightInPoints() - rowHeight) > 0.2f)
                throw blocked("PROFILE_ROW_HEIGHT_MISMATCH", "필드 행 높이가 프로필과 다릅니다: " + field);
            String address = cell.getAddress().formatAsString().replace("$", "");
            if (!addresses.add(address)) throw blocked("PROFILE_FIELD_COLLISION", "둘 이상의 필드가 같은 셀을 가리킵니다: " + address);
            rowOffsets.put(field, mapping.rowOffset());
            columnOffsets.put(field, mapping.columnOffset());
            cells.put(field, address);
            styles.put(field, (int) cell.getCellStyle().getIndex());
        }
        Set<String> expectedMerges = Set.copyOf(profile.mergedRanges());
        Set<String> actualMerges = sourceMerges(sheet, box).stream().map(merge ->
                cellAddress(merge.firstColumn(), merge.firstRow() + 1) + ":" + cellAddress(merge.lastColumn(), merge.lastRow() + 1))
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedMerges.equals(actualMerges))
            throw blocked("PROFILE_MERGE_MISMATCH", "원본 박스 병합 구조가 프로필과 다릅니다.");
        for (int offset = 0; offset < height; offset++) {
            Float expected = profile.rowHeightsPt().get(offset);
            Row row = sheet.getRow(row0 + offset);
            if (expected != null && (row == null || Math.abs(row.getHeightInPoints() - expected) > 0.2f))
                throw blocked("PROFILE_ROW_HEIGHT_MISMATCH", "프로필 행 높이가 현재 템플릿과 다릅니다: " + (offset + 1));
        }
        for (Map.Entry<String, Double> column : profile.columnWidths().entrySet()) {
            int index = org.apache.poi.ss.util.CellReference.convertColStringToIndex(column.getKey());
            if (Math.abs(sheet.getColumnWidth(index) / 256d - column.getValue()) > 0.2d)
                throw blocked("PROFILE_COLUMN_WIDTH_MISMATCH", "프로필 열 너비가 현재 시트와 다릅니다: " + column.getKey());
        }
        return new TemplateProfile(true, Map.copyOf(rowOffsets), Map.copyOf(cells), Map.copyOf(styles),
                profile.profileId(), Map.copyOf(columnOffsets), profile.profileVersion());
    }

    private TemplateProfile extractTemplate(org.apache.poi.ss.usermodel.Sheet sheet,
                                             EquipmentBoxGeometryLocator.Box box, int height) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int col = box.firstColumn();
        int row0 = box.firstRow() - 1;
        Map<Integer, String> values = new LinkedHashMap<>();
        for (int offset = 2; offset < height; offset++) {
            Row row = sheet.getRow(row0 + offset);
            Cell cell = row == null ? null : row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (!value.isBlank()) values.put(offset, value);
        }
        Integer quantity = values.entrySet().stream().filter(entry -> entry.getKey() > 1 && isQuantity(entry.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (quantity == null) return new TemplateProfile(false, Map.of(), Map.of(), Map.of(), "QUANTITY_FIELD_NOT_UNIQUE");
        List<Integer> capacities = values.entrySet().stream().filter(entry -> entry.getKey() < quantity
                && isCapacity(entry.getValue())).map(Map.Entry::getKey).toList();
        if (capacities.size() != 1) return new TemplateProfile(false, Map.of(), Map.of(), Map.of(), "CAPACITY_FIELD_NOT_UNIQUE");
        int capacity = capacities.get(0);
        List<Integer> businessItems = values.entrySet().stream().filter(entry -> entry.getKey() < capacity
                && isBusinessItem(entry.getValue())).map(Map.Entry::getKey).toList();
        if (businessItems.size() != 1) return new TemplateProfile(false, Map.of(), Map.of(), Map.of(), "BUSINESS_ITEM_FIELD_NOT_UNIQUE");
        int businessItem = businessItems.get(0);
        int name = businessItem - 1;
        int type = name - 1;
        if (!values.containsKey(name)) return new TemplateProfile(false, Map.of(), Map.of(), Map.of(), "EQUIPMENT_NAME_FIELD_NOT_IN_EXPECTED_ORDER");
        if (!values.containsKey(type)) return new TemplateProfile(false, Map.of(), Map.of(), Map.of(), "EQUIPMENT_TYPE_FIELD_NOT_IN_EXPECTED_ORDER");
        Map<String, Integer> offsets = new LinkedHashMap<>();
        offsets.put("managementNo", 0); offsets.put("equipmentTypeLabel", type); offsets.put("equipmentName", name);
        offsets.put("businessItemNo", businessItem); offsets.put("capacity", capacity); offsets.put("quantity", quantity);
        Map<String, String> cells = new LinkedHashMap<>();
        Map<String, Integer> styles = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : offsets.entrySet()) {
            Row row = sheet.getRow(row0 + entry.getValue());
            Cell cell = row == null ? null : row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) return new TemplateProfile(false, offsets, cells, styles, "FIELD_CELL_MISSING:" + entry.getKey());
            if (formatter.formatCellValue(cell).isBlank()) return new TemplateProfile(false, offsets, cells, styles, "FIELD_CELL_EMPTY:" + entry.getKey());
            cells.put(entry.getKey(), cell.getAddress().formatAsString().replace("$", ""));
            styles.put(entry.getKey(), (int) cell.getCellStyle().getIndex());
        }
        return new TemplateProfile(true, Map.copyOf(offsets), Map.copyOf(cells), Map.copyOf(styles),
                "RELATIVE_FIELDS_INFERRED_FROM_BOX_CONTENT_AND_ORDER");
    }

    private List<String> occupiedCells(org.apache.poi.ss.usermodel.Sheet sheet, int firstRow, int lastRow,
                                        int firstColumn, int lastColumn) {
        if (lastRow < firstRow) return List.of();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<String> occupied = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = firstColumn; c <= lastColumn; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell != null && !formatter.formatCellValue(cell).isBlank()) occupied.add(cell.getAddress().formatAsString());
            }
        }
        return List.copyOf(occupied);
    }

    private List<MergeTemplate> sourceMerges(org.apache.poi.ss.usermodel.Sheet sheet,
                                              EquipmentBoxGeometryLocator.Box box) {
        List<MergeTemplate> merges = new ArrayList<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            boolean intersects = region.getFirstRow() <= box.lastRow() - 1 && region.getLastRow() >= box.firstRow() - 1
                    && region.getFirstColumn() <= box.lastColumn() && region.getLastColumn() >= box.firstColumn();
            if (!intersects) continue;
            if (region.getFirstRow() < box.firstRow() - 1 || region.getLastRow() > box.lastRow() - 1
                    || region.getFirstColumn() < box.firstColumn() || region.getLastColumn() > box.lastColumn()) {
                throw blocked("SOURCE_MERGE_CROSSES_BOX", "원본 템플릿의 병합셀 경계가 박스를 벗어납니다.");
            }
            merges.add(new MergeTemplate(region.getFirstRow(), region.getLastRow(),
                    region.getFirstColumn(), region.getLastColumn()));
        }
        return List.copyOf(merges);
    }

    private void validateDrawingAnchors(byte[] bytes, String drawingPart, int insertionBeforeRow,
                                        int firstColumn, int lastColumn, int firstGapRow, int lastGapRow) {
        Element drawing = parse(parts.readPart(bytes, drawingPart), drawingPart);
        for (Element anchor : children(drawing)) {
            String kind = anchor.getLocalName();
            if (!"twoCellAnchor".equals(kind)) throw blocked("DRAWING_ANCHOR_TYPE_UNSUPPORTED",
                    "twoCellAnchor 이외의 DrawingML 앵커는 현재 이동 검증이 지원되지 않습니다: " + kind);
            Element from = child(anchor, X, "from");
            Element to = child(anchor, X, "to");
            if (from == null || to == null) throw blocked("DRAWING_ANCHOR_COORDINATE_MISSING", "DrawingML 앵커 좌표가 누락되었습니다.");
            int startRow = intChild(from, "row");
            int endRow = intChild(to, "row");
            int startCol = intChild(from, "col");
            int endCol = intChild(to, "col");
            if (startRow < 0 || endRow < 0 || startCol < 0 || endCol < 0) {
                throw blocked("DRAWING_ANCHOR_COORDINATE_INVALID", "DrawingML 앵커 좌표를 읽을 수 없습니다.");
            }
            int top = Math.min(startRow, endRow);
            int bottom = Math.max(startRow, endRow);
            int left = Math.min(startCol, endCol);
            int right = Math.max(startCol, endCol);
            int insertionIndex = insertionBeforeRow - 1;
            if (top < insertionIndex && bottom >= insertionIndex) {
                throw blocked("DRAWING_ANCHOR_CROSSES_INSERTION", "기존 DrawingML 객체가 행 삽입 경계를 가로지릅니다.");
            }
            if (bottom >= firstGapRow && top <= lastGapRow && right >= firstColumn && left <= lastColumn) {
                throw blocked("DRAWING_OBJECT_IN_CANDIDATE", "DrawingML 객체가 설비 삽입 후보 셀 범위와 교차합니다.");
            }
        }
    }

    private byte[] patchWorksheet(byte[] bytes, Plan plan, MasterEquipment equipment, String typeLabel, String note) {
        Element root = parse(parts.readPart(bytes, plan.worksheetPart()), plan.worksheetPart());
        Element sheetData = first(root, S, "sheetData");
        if (sheetData == null) throw blocked("SHEET_DATA_MISSING", "worksheet sheetData가 없습니다.");
        List<Element> existingRows = children(sheetData).stream().filter(element -> "row".equals(element.getLocalName())).toList();
        Map<Integer, Element> rowMap = new HashMap<>();
        for (Element row : existingRows) rowMap.put(Integer.parseInt(row.getAttribute("r")), row);
        List<Element> sourceTemplates = new ArrayList<>();
        for (int row = plan.sourceFirstRow(); row <= plan.sourceLastRow(); row++) {
            Element source = rowMap.get(row);
            if (source == null) throw blocked("SOURCE_TEMPLATE_ROW_MISSING", "원본 박스 행 템플릿이 없습니다: " + row);
            sourceTemplates.add(source);
        }
        List<Element> gapTemplates = new ArrayList<>();
        for (int offset = 0; offset < plan.reserveRows(); offset++) {
            Element gap = rowMap.get(plan.sourceLastRow() + offset + 1);
            if (gap == null) throw blocked("GAP_TEMPLATE_ROW_MISSING", "박스 뒤 빈 행 템플릿이 없습니다.");
            gapTemplates.add(gap);
        }
        for (Element row : existingRows) {
            int oldRow = Integer.parseInt(row.getAttribute("r"));
            if (oldRow >= plan.firstCandidateRow()) shiftRow(row, plan.firstCandidateRow(), plan.insertedRows());
        }
        shiftWorksheetRanges(root, plan.firstCandidateRow(), plan.insertedRows());
        List<Element> insertedRows = new ArrayList<>();
        for (int offset = 0; offset < plan.boxHeight(); offset++) {
            Element clone = (Element) root.getOwnerDocument().importNode(sourceTemplates.get(offset), true);
            int rowNumber = plan.firstCandidateRow() + offset;
            keepOnlyColumns(clone, plan.firstColumn(), plan.lastColumn());
            clearValues(clone);
            setRowNumber(clone, rowNumber);
            insertedRows.add(clone);
        }
        for (int offset = 0; offset < plan.reserveRows(); offset++) {
            Element clone = (Element) root.getOwnerDocument().importNode(gapTemplates.get(offset), true);
            int rowNumber = plan.firstCandidateRow() + plan.boxHeight() + offset;
            keepOnlyColumns(clone, plan.firstColumn(), plan.lastColumn());
            clearValues(clone);
            setRowNumber(clone, rowNumber);
            insertedRows.add(clone);
        }
        Map<String, String> fieldValues = fieldValues(equipment, typeLabel, note, plan.template());
        for (int offset = 0; offset < plan.boxHeight(); offset++) {
            int rowNumber = plan.firstCandidateRow() + offset;
            int rowOffset = offset;
            Element insertedRow = insertedRows.get(offset);
            plan.template().fieldOffsets().forEach((field, mappedRowOffset) -> {
                if (mappedRowOffset != rowOffset) return;
                String value = fieldValues.get(field);
                if (value == null) return;
                int column = plan.firstColumn() + plan.template().fieldColumnOffsets().getOrDefault(field, 0);
                setTextAtCell(insertedRow, column, rowNumber, value);
            });
        }
        for (Element inserted : insertedRows) sheetData.appendChild(inserted);
        replicateMerges(root, plan);
        sortRows(sheetData);
        Element sheetView = first(root, S, "sheetView");
        if (sheetView != null) {
            String focus = cellAddress(plan.firstColumn(), plan.firstCandidateRow());
            sheetView.setAttribute("topLeftCell", focus);
            Element selection = first(sheetView, S, "selection");
            if (selection != null) { selection.setAttribute("activeCell", focus); selection.setAttribute("sqref", focus); }
        }
        return serialize(root.getOwnerDocument());
    }

    private byte[] patchDrawing(byte[] bytes, Plan plan) {
        Element root = parse(parts.readPart(bytes, plan.drawingPart()), plan.drawingPart());
        int insertionIndex = plan.firstCandidateRow() - 1;
        for (Element anchor : children(root)) {
            Element from = child(anchor, X, "from");
            Element to = child(anchor, X, "to");
            if (from == null || to == null) throw blocked("DRAWING_ANCHOR_COORDINATE_MISSING", "DrawingML 앵커 좌표가 없습니다.");
            int start = intChild(from, "row");
            int end = intChild(to, "row");
            if (Math.min(start, end) < insertionIndex && Math.max(start, end) >= insertionIndex) {
                throw blocked("DRAWING_ANCHOR_CROSSES_INSERTION", "DrawingML 앵커가 삽입 경계를 가로지릅니다.");
            }
            if (start >= insertionIndex) setChild(from, "row", Integer.toString(start + plan.insertedRows()));
            if (end >= insertionIndex) setChild(to, "row", Integer.toString(end + plan.insertedRows()));
        }
        return serialize(root.getOwnerDocument());
    }

    private byte[] patchWorkbook(byte[] bytes, Plan plan) {
        Element root = parse(parts.readPart(bytes, "xl/workbook.xml"), "xl/workbook.xml");
        NodeList names = root.getElementsByTagNameNS(S, "definedName");
        boolean printAreaUpdated = false;
        String qualified = "'" + plan.sheetName().replace("'", "''") + "'!";
        for (int i = 0; i < names.getLength(); i++) {
            Element name = (Element) names.item(i);
            if (!"_xlnm.Print_Area".equals(name.getAttribute("name"))) continue;
            String formula = name.getTextContent();
            if (!formula.contains(qualified) && !formula.startsWith(plan.sheetName() + "!")) continue;
            name.setTextContent(shiftFormulaRanges(formula, plan.firstCandidateRow(), plan.insertedRows()));
            printAreaUpdated = true;
        }
        if (!printAreaUpdated) throw blocked("PRINT_AREA_MISSING", "대상 worksheet print area를 수정하지 못했습니다.");
        return serialize(root.getOwnerDocument());
    }

    private void shiftWorksheetRanges(Element root, int beforeRow, int delta) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element element)) continue;
            for (String attribute : List.of("ref", "sqref", "activeCell", "topLeftCell")) {
                if (!element.hasAttribute(attribute)) continue;
                String original = element.getAttribute(attribute);
                String[] values = original.split("\\s+");
                for (int index = 0; index < values.length; index++) values[index] = shiftReference(values[index], beforeRow, delta);
                element.setAttribute(attribute, String.join(" ", values));
            }
            if ("brk".equals(element.getLocalName()) && element.hasAttribute("id")) {
                int row = Integer.parseInt(element.getAttribute("id"));
                if (row + 1 >= beforeRow) element.setAttribute("id", Integer.toString(row + delta));
            }
        }
    }

    private String shiftFormulaRanges(String formula, int beforeRow, int delta) {
        Matcher matcher = Pattern.compile("(\\$?[A-Z]{1,3}\\$?[0-9]+(?::\\$?[A-Z]{1,3}\\$?[0-9]+)?)").matcher(formula);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(shiftReference(matcher.group(), beforeRow, delta)));
        matcher.appendTail(result);
        return result.toString();
    }

    private String shiftReference(String reference, int beforeRow, int delta) {
        String[] endpoints = reference.split(":", -1);
        for (int i = 0; i < endpoints.length; i++) {
            Matcher matcher = CELL.matcher(endpoints[i]);
            if (!matcher.matches()) continue;
            int row = Integer.parseInt(matcher.group(2));
            if (row >= beforeRow) row += delta;
            String col = matcher.group(1);
            String absCol = endpoints[i].startsWith("$") ? "$" : "";
            int dollar = endpoints[i].indexOf('$', absCol.length());
            String absRow = dollar >= 0 ? "$" : "";
            endpoints[i] = absCol + col + absRow + row;
        }
        return String.join(":", endpoints);
    }

    private void replicateMerges(Element root, Plan plan) {
        if (plan.sourceMerges().isEmpty()) return;
        Element merges = first(root, S, "mergeCells");
        if (merges == null) throw blocked("MERGE_CONTAINER_MISSING", "원본 박스에 병합이 있으나 mergeCells 컨테이너가 없습니다.");
        for (MergeTemplate merge : plan.sourceMerges()) {
            int rowDelta = plan.firstCandidateRow() - plan.sourceFirstRow();
            String ref = cellAddress(merge.firstColumn(), merge.firstRow() + rowDelta + 1)
                    + ":" + cellAddress(merge.lastColumn(), merge.lastRow() + rowDelta + 1);
            Element cloned = root.getOwnerDocument().createElementNS(S, prefix(merges) + "mergeCell");
            cloned.setAttribute("ref", ref);
            merges.appendChild(cloned);
        }
        merges.setAttribute("count", Integer.toString(children(merges).size()));
    }

    private void validateOutput(byte[] original, byte[] output, MasterEquipment equipment, String typeLabel,
                                String note, Plan plan) {
        try (Workbook before = WorkbookFactory.create(new ByteArrayInputStream(original));
             Workbook after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldSheet = before.getSheet(plan.sheetName());
            var newSheet = after.getSheet(plan.sheetName());
            if (newSheet == null || oldSheet == null) throw blocked("OUTPUT_REOPEN_FAILED", "생성 XLSX를 다시 열 수 없습니다.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String, String> expected = fieldValues(equipment, typeLabel, note, plan.template());
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                Integer rowOffset = plan.template().fieldOffsets().get(entry.getKey());
                if (rowOffset == null) continue;
                int columnOffset = plan.template().fieldColumnOffsets().getOrDefault(entry.getKey(), 0);
                String moved = cellAddress(plan.firstColumn() + columnOffset, plan.firstCandidateRow() + rowOffset);
                CellReference ref = new CellReference(moved);
                Row row = newSheet.getRow(ref.getRow());
                Cell cell = row == null ? null : row.getCell(ref.getCol(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null || !entry.getValue().equals(formatter.formatCellValue(cell))) {
                    throw blocked("OUTPUT_CELL_MISMATCH", "생성 설비 필드가 재열기 결과와 다릅니다: " + moved);
                }
            }
            if (newSheet.getNumMergedRegions() != oldSheet.getNumMergedRegions() + plan.sourceMerges().size()) {
                throw blocked("OUTPUT_MERGE_COUNT_CHANGED", "의도하지 않은 병합 범위 변경이 있습니다.");
            }
            String printArea = after.getPrintArea(after.getSheetIndex(plan.sheetName()));
            if (printArea == null || printArea.isBlank()) throw blocked("OUTPUT_PRINT_AREA_MISSING", "생성 결과의 인쇄 영역이 없습니다.");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen and verify generated TEST_ONLY workbook", exception);
        }
        verifyPackagePartDelta(original, output, plan);
        WorkbookInventory oldInventory = inspector.inspect(original);
        WorkbookInventory newInventory = inspector.inspect(output);
        if (oldInventory.shapeCount() != newInventory.shapeCount()
                || oldInventory.connectorCount() != newInventory.connectorCount()
                || oldInventory.pictureCount() != newInventory.pictureCount()
                || oldInventory.otherObjectCount() != newInventory.otherObjectCount()) {
            throw blocked("DRAWINGML_OBJECT_COUNT_CHANGED", "설비 박스만 추가해야 하며 DrawingML 객체 개수는 변하지 않아야 합니다.");
        }
        var oldDrawing = oldInventory.sheets().stream().filter(s -> plan.sheetName().equals(s.sheetName())).findFirst().orElseThrow();
        var newDrawing = newInventory.sheets().stream().filter(s -> plan.sheetName().equals(s.sheetName())).findFirst().orElseThrow();
        Map<String, WorkbookInventory.DrawingObject> outputById = new HashMap<>();
        newDrawing.drawingObjects().forEach(object -> outputById.put(object.shapeId(), object));
        for (WorkbookInventory.DrawingObject originalObject : oldDrawing.drawingObjects()) {
            var current = outputById.get(originalObject.shapeId());
            if (current == null || !Objects.equals(current.objectType(), originalObject.objectType())
                    || !Objects.equals(current.objectName(), originalObject.objectName())
                    || !Objects.equals(current.geometryPreset(), originalObject.geometryPreset())
                    || !Objects.equals(current.headArrowType(), originalObject.headArrowType())
                    || !Objects.equals(current.tailArrowType(), originalObject.tailArrowType())
                    || !Objects.equals(current.startConnectedShapeId(), originalObject.startConnectedShapeId())
                    || !Objects.equals(current.endConnectedShapeId(), originalObject.endConnectedShapeId())) {
                throw blocked("DRAWINGML_OBJECT_CHANGED", "기존 DrawingML 객체·화살표·연결 바인딩을 보존하지 못했습니다.");
            }
        }
    }

    private void verifyPackagePartDelta(byte[] original, byte[] output, Plan plan) {
        Map<String, byte[]> before = readPackage(original);
        Map<String, byte[]> after = readPackage(output);
        if (!before.keySet().equals(after.keySet())) throw blocked("PACKAGE_PART_SET_CHANGED", "OOXML 파트 집합이 바뀌었습니다.");
        Set<String> allowed = Set.of(plan.worksheetPart(), plan.drawingPart(), "xl/workbook.xml");
        for (String name : before.keySet()) {
            if (!allowed.contains(name) && !java.util.Arrays.equals(before.get(name), after.get(name))) {
                throw blocked("UNEXPECTED_PACKAGE_PART_CHANGE", "허용되지 않은 OOXML 파트가 변경되었습니다: " + name);
            }
        }
    }

    private Map<String, byte[]> readPackage(byte[] bytes) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to audit OOXML package parts", exception);
        }
        return result;
    }

    private String normalizePrintArea(String value) {
        return value == null ? "" : value.replace("'", "").replace("$", "").replaceAll("\\s+", "");
    }

    private String printAreaForSheet(byte[] bytes, String sheetName) {
        Element root = parse(parts.readPart(bytes, "xl/workbook.xml"), "xl/workbook.xml");
        NodeList names = root.getElementsByTagNameNS(S, "definedName");
        String qualified = "'" + sheetName.replace("'", "''") + "'!";
        for (int i = 0; i < names.getLength(); i++) {
            Element name = (Element) names.item(i);
            if ("_xlnm.Print_Area".equals(name.getAttribute("name"))
                    && (name.getTextContent().contains(qualified) || name.getTextContent().startsWith(sheetName + "!"))) {
                return name.getTextContent();
            }
        }
        return null;
    }

    private boolean containsExactText(byte[] bytes, String sheetName, String value) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Row row : sheet) for (Cell cell : row) if (value.equals(formatter.formatCellValue(cell).trim())) return true;
            return false;
        } catch (Exception exception) { throw new InvalidWorkbookException("Unable to check duplicate equipment ID", exception); }
    }

    private boolean isQuantity(String value) {
        return value.matches("(?i)^[0-9]+(?:[,.][0-9]+)?\\s*(?:ea|대|식|개|기|set|unit|조|라인|본|개소)$");
    }

    private boolean isCapacity(String value) {
        return value.matches("(?i)^[0-9][0-9,.]*\\s*(?:[A-Za-z㎥㎡℃%°]+(?:/[A-Za-z0-9]+)?|)$");
    }

    private boolean isBusinessItem(String value) {
        return value.matches("(?i)^[A-Z0-9][A-Z0-9._/-]{2,63}$") && value.matches(".*[0-9].*");
    }

    private String capacityText(MasterEquipment equipment) {
        String capacity = equipment.capacityText() == null ? "" : equipment.capacityText().trim();
        String unit = equipment.capacityUnit() == null ? "" : equipment.capacityUnit().trim();
        return unit.isBlank() || capacity.contains(unit) ? capacity : capacity + unit;
    }

    private Map<String, String> fieldValues(MasterEquipment equipment, String typeLabel, String note, TemplateProfile template) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("managementNo", equipment.permitManagementNo());
        values.put("equipmentTypeLabel", typeLabel);
        values.put("equipmentName", equipment.facilityName());
        values.put("businessItemNo", equipment.businessItemNo());
        boolean splitUnit = template.fieldOffsets().containsKey("capacityUnit");
        values.put("capacity", splitUnit ? equipment.capacityText().trim() : capacityText(equipment));
        if (splitUnit) values.put("capacityUnit", equipment.capacityUnit() == null ? "" : equipment.capacityUnit().trim());
        values.put("quantity", equipment.quantityText());
        if (note != null && !note.isBlank() && !template.fieldOffsets().containsKey("note"))
            throw blocked("PROFILE_NOTE_NOT_MAPPED", "비고는 현재 프로필에서 매핑되지 않아 입력할 수 없습니다.");
        if (template.fieldOffsets().containsKey("note")) values.put("note", note == null ? "" : note);
        return Map.copyOf(values);
    }

    private void keepOnlyColumns(Element row, int first, int last) {
        for (Element cell : children(row)) if ("c".equals(cell.getLocalName())) {
            int column = new CellReference(cell.getAttribute("r")).getCol();
            if (column < first || column > last) row.removeChild(cell);
        }
    }

    private void clearValues(Element row) {
        for (Element cell : children(row)) if ("c".equals(cell.getLocalName())) {
            while (cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
            cell.removeAttribute("t"); cell.removeAttribute("cm"); cell.removeAttribute("vm");
        }
    }

    private void setTextAtCell(Element row, int column, int rowNumber, String value) {
        String address = cellAddress(column, rowNumber);
        for (Element cell : children(row)) if ("c".equals(cell.getLocalName()) && address.equals(cell.getAttribute("r"))) {
            while (cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
            cell.setAttribute("t", "inlineStr");
            String prefix = prefix(cell);
            Element is = cell.getOwnerDocument().createElementNS(S, prefix + "is");
            Element text = cell.getOwnerDocument().createElementNS(S, prefix + "t");
            text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            text.setTextContent(value);
            is.appendChild(text); cell.appendChild(is); return;
        }
        throw blocked("TEMPLATE_FIELD_STYLE_MISSING", "설비 템플릿에 입력 셀/서식이 없습니다: " + address);
    }

    private void shiftRow(Element row, int beforeRow, int delta) {
        int oldRow = Integer.parseInt(row.getAttribute("r"));
        row.setAttribute("r", Integer.toString(oldRow + delta));
        for (Element cell : children(row)) if ("c".equals(cell.getLocalName())) {
            cell.setAttribute("r", shiftReference(cell.getAttribute("r"), beforeRow, delta));
        }
    }

    private void setRowNumber(Element row, int rowNumber) {
        row.setAttribute("r", Integer.toString(rowNumber));
        for (Element cell : children(row)) if ("c".equals(cell.getLocalName())) {
            Matcher matcher = CELL.matcher(cell.getAttribute("r"));
            if (matcher.matches()) {
                String old = matcher.group(1);
                String absCol = cell.getAttribute("r").startsWith("$") ? "$" : "";
                cell.setAttribute("r", absCol + old + rowNumber);
            }
        }
    }

    private void sortRows(Element sheetData) {
        List<Element> rows = children(sheetData).stream().filter(element -> "row".equals(element.getLocalName()))
                .sorted(Comparator.comparingInt(element -> Integer.parseInt(element.getAttribute("r")))).toList();
        for (Element row : rows) sheetData.appendChild(row);
    }

    private boolean overlaps(CellRangeAddress range, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        return lastRow >= firstRow && range.getLastRow() >= firstRow && range.getFirstRow() <= lastRow
                && range.getLastColumn() >= firstColumn && range.getFirstColumn() <= lastColumn;
    }

    private String cellAddress(int column, int rowOneBased) {
        return new CellReference(rowOneBased - 1, column).formatAsString().replace("$", "");
    }

    private Element parse(byte[] xml, String name) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml))).getDocumentElement();
        } catch (Exception exception) { throw new InvalidWorkbookException("Malformed OOXML part: " + name, exception); }
    }

    private byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception exception) { throw new InvalidWorkbookException("Unable to serialize OOXML part", exception); }
    }

    private List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element element) result.add(element);
        return result;
    }

    private Element first(Element parent, String namespace, String name) {
        for (Element child : children(parent)) if (namespace.equals(child.getNamespaceURI()) && name.equals(child.getLocalName())) return child;
        return null;
    }

    private Element child(Element parent, String namespace, String name) { return parent == null ? null : first(parent, namespace, name); }

    private int intChild(Element parent, String name) {
        Element child = first(parent, X, name);
        try { return child == null ? -1 : Integer.parseInt(child.getTextContent()); }
        catch (NumberFormatException exception) { return -1; }
    }

    private void setChild(Element parent, String name, String value) {
        Element child = first(parent, X, name);
        if (child == null) throw blocked("DRAWING_ANCHOR_COORDINATE_MISSING", "DrawingML 앵커에 " + name + " 값이 없습니다.");
        child.setTextContent(value);
    }

    private String prefix(Element element) { return element.getPrefix() == null ? "" : element.getPrefix() + ":"; }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new InvalidWorkbookException(name + " is required");
        return value;
    }

    private InvalidWorkbookException blocked(String code, String reason) { return new InvalidWorkbookException(code + ": " + reason); }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record TemplateProfile(boolean recognized, Map<String, Integer> fieldOffsets,
                                  Map<String, String> fieldCells, Map<String, Integer> fieldStyles,
                                  String profileId, Map<String, Integer> fieldColumnOffsets,
                                  String profileVersion) {
        public TemplateProfile(boolean recognized, Map<String, Integer> fieldOffsets,
                               Map<String, String> fieldCells, Map<String, Integer> fieldStyles,
                               String profileId) {
            this(recognized, fieldOffsets, fieldCells, fieldStyles, profileId, Map.of(), "INFERRED_LEGACY");
        }
    }
    public record MergeTemplate(int firstRow, int lastRow, int firstColumn, int lastColumn) { }
    public record Plan(String sheetName, int sheetIndex, String worksheetPart, String drawingPart,
                       EquipmentBoxGeometryLocator.Box source,
                       EquipmentBoxGeometryLocator.Box target,
                       String sourceId, String targetId, int firstColumn, int lastColumn,
                       int sourceFirstRow, int sourceLastRow, int firstCandidateRow, int lastCandidateRow,
                       int boxHeight, int originalGapRows, int reserveRows, int insertedRows,
                       String candidateCellRange, TemplateProfile template, List<MergeTemplate> sourceMerges,
                       String editabilityStatus, String connectionEvidenceStatus) { }
    public record EditResult(byte[] workbookBytes, Plan plan, String sourceSha256, String outputSha256,
                             String mode, String relationStatus, String interpretation) { }
}
