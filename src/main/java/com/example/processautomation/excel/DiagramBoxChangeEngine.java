package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Conservative TEST_ONLY text/box editor. It never edits connectors, drawings, or real process connectivity.
 * Production process mappings must be independently reviewed before this engine can be exposed to users.
 */
@Component
public class DiagramBoxChangeEngine {
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String P = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CONNECTION_UNVERIFIED = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";
    private static final Pattern CELL_REF = Pattern.compile("\\$?([A-Z]{1,3})\\$?([1-9][0-9]*)");
    private final OoxmlPartEditor parts;
    private final OoxmlPackageInspector inspector;
    private final EquipmentBoxGeometryLocator geometry;

    public DiagramBoxChangeEngine(OoxmlPartEditor parts, OoxmlPackageInspector inspector,
                                  EquipmentBoxGeometryLocator geometry) {
        this.parts = parts;
        this.inspector = inspector;
        this.geometry = geometry;
    }

    /** Resolve field cells only when the live workbook contains a unique exact copy of master text. */
    public BoxFieldMapping mapFieldsByMaster(byte[] source, String sheetName, String managementNo,
                                             Map<String, String> masterValues) {
        BoxContext context = inspectBox(source, sheetName, managementNo);
        if (masterValues == null || masterValues.isEmpty() || masterValues.size() > 8) {
            throw blocked("MASTER_FIELD_SET_INVALID", "검증할 master 필드가 필요합니다.");
        }
        Map<String, List<Cell>> matchesByField = new java.util.LinkedHashMap<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            masterValues.forEach((field, expected) -> {
                if (blank(field) || blank(expected)) return;
                List<Cell> matches = new ArrayList<>();
                for (int rowIndex = context.box().firstRow() - 1; rowIndex < context.box().lastRow(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    for (int col = context.box().firstColumn(); col <= context.box().lastColumn(); col++) {
                        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null && expected.equals(formatter.formatCellValue(cell))) matches.add(cell);
                    }
                }
                matchesByField.put(field, matches);
            });
            Map<String, Integer> cellUseCounts = new java.util.HashMap<>();
            matchesByField.values().stream().filter(matches -> matches.size() == 1)
                    .forEach(matches -> cellUseCounts.merge(matches.get(0).getAddress().formatAsString().replace("$", ""), 1, Integer::sum));
            Map<String, FieldMapping> mapped = new java.util.LinkedHashMap<>();
            masterValues.forEach((field, expected) -> {
                List<Cell> matches = matchesByField.getOrDefault(field, List.of());
                if (blank(field) || blank(expected)) {
                    mapped.put(field, new FieldMapping(field, null, null, null, "REVIEW_REQUIRED", "MASTER_VALUE_MISSING"));
                } else if (matches.size() != 1) {
                    mapped.put(field, new FieldMapping(field, null, null, expected, "REVIEW_REQUIRED",
                            matches.isEmpty() ? "MASTER_VALUE_NOT_PRESENT_IN_BOX" : "MASTER_VALUE_NOT_UNIQUE_IN_BOX"));
                } else {
                    Cell cell = matches.get(0);
                    String address = cell.getAddress().formatAsString().replace("$", "");
                    if (address.equalsIgnoreCase(context.box().topLeftCell())) {
                        mapped.put(field, new FieldMapping(field, address, formatter.formatCellValue(cell), expected,
                                "REVIEW_REQUIRED", "MANAGEMENT_ID_FIELD_REQUIRES_GLOBAL_REMAP"));
                    } else if (cellUseCounts.getOrDefault(address, 0) != 1) {
                        mapped.put(field, new FieldMapping(field, address, formatter.formatCellValue(cell), expected,
                                "REVIEW_REQUIRED", "MASTER_FIELDS_COLLIDE_ON_SAME_CELL"));
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        mapped.put(field, new FieldMapping(field, address, formatter.formatCellValue(cell), expected,
                                "REVIEW_REQUIRED", "FORMULA_FIELD_NOT_EDITABLE"));
                    } else {
                        try {
                            requireNotMergedNonAnchor(context.mergedRegions(),
                                    new CellReference(cell.getRowIndex(), cell.getColumnIndex()), address);
                            mapped.put(field, new FieldMapping(field, address, formatter.formatCellValue(cell), expected,
                                    "VERIFIED", "UNIQUE_EXACT_MASTER_VALUE_MATCH"));
                        } catch (InvalidWorkbookException exception) {
                            mapped.put(field, new FieldMapping(field, address, formatter.formatCellValue(cell), expected,
                                    "REVIEW_REQUIRED", "MERGED_FIELD_MAPPING_UNSAFE"));
                        }
                    }
                }
            });
            return new BoxFieldMapping(managementNo, sheetName, context.box().topLeftCell(),
                    context.box().bottomRightCell(), Map.copyOf(mapped), "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to map equipment fields against the master record", exception);
        }
    }

    /** Build editable field mappings only from an exact-SHA, enabled equipment Profile. */
    public BoxFieldMapping mapFieldsByProfile(byte[] source, String processCode, String sheetName, String managementNo,
                                              EquipmentBoxProfile profile, String sourceSha256, String workingSha256) {
        if (profile == null) throw blocked("PROFILE_MISSING", "설비 수정에 사용할 Profile이 없습니다.");
        if (!profile.appliesTo(processCode, sheetName, sourceSha256))
            throw blocked("PROFILE_IDENTITY_MISMATCH", "Profile 공정·시트·기준 SHA가 작업본과 일치하지 않습니다.");
        if (!profile.enabledForGenericAdd() || !("WORKING_COPY_VERIFIED".equals(profile.evidenceLevel())
                || "SYNTHETIC_TESTED".equals(profile.evidenceLevel())))
            throw blocked("PROFILE_NOT_VERIFIED_FOR_MODIFY", "Profile이 실제 작업본 또는 명시적 synthetic fixture에 대해 검증되지 않았습니다.");
        String actualWorkingSha = sha256(source);
        boolean workingShaMatch = profile.matchesWorkingCopySha(workingSha256)
                && workingSha256 != null && workingSha256.equalsIgnoreCase(actualWorkingSha);
        if (!workingShaMatch)
            throw blocked("PROFILE_WORKING_SHA_MISMATCH", "현재 XLSX 바이트와 Profile의 검증 작업본 SHA가 일치하지 않습니다.");
        BoxContext context = inspectBox(source, sheetName, managementNo);
        int detectedBoxHeight = context.box().lastRow() - context.box().firstRow() + 1;
        boolean geometryMatch = detectedBoxHeight == profile.boxHeight()
                && profile.fields().get("managementNo") != null
                && profile.fields().get("managementNo").rowOffset() == 0
                && profile.fields().get("managementNo").columnOffset() == 0;
        Map<String, FieldMapping> mapped = new java.util.LinkedHashMap<>();
        Set<String> allowed = Set.of("managementNo", "equipmentTypeLabel", "equipmentName", "businessItemNo",
                "capacity", "capacityUnit", "quantity", "note");
        if (profile.fields().isEmpty() || !allowed.containsAll(profile.fields().keySet()))
            throw blocked("PROFILE_FIELD_SET_UNSUPPORTED", "Profile 필드 집합이 비어 있거나 현재 MODIFY 엔진에서 지원되지 않습니다.");
        Set<String> seenAddresses = new java.util.HashSet<>();
        var inventory = inspector.inspectSheet(source, sheetName);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
                String field = entry.getKey();
                EquipmentBoxProfile.FieldCell specification = entry.getValue();
                if (specification.rowOffset() < 0 || specification.rowOffset() >= detectedBoxHeight
                        || specification.columnOffset() < 0
                        || context.box().firstColumn() + specification.columnOffset() > context.box().lastColumn()) {
                    mapped.put(field, profileField(field, null, null, "REVIEW_REQUIRED", "PROFILE_FIELD_OUTSIDE_BOX", specification));
                    continue;
                }
                int rowIndex = context.box().firstRow() - 1 + specification.rowOffset();
                int columnIndex = context.box().firstColumn() + specification.columnOffset();
                CellReference reference = new CellReference(rowIndex, columnIndex);
                String address = reference.formatAsString().replace("$", "");
                Row row = sheet.getRow(rowIndex);
                Cell cell = row == null ? null : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                if (!geometryMatch) {
                    mapped.put(field, profileField(field, address, cell == null ? null : formatter.formatCellValue(cell),
                            "REVIEW_REQUIRED", "PROFILE_BOX_GEOMETRY_MISMATCH", specification));
                    continue;
                }
                if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                    mapped.put(field, profileField(field, address, cell == null ? null : formatter.formatCellValue(cell),
                            "REVIEW_REQUIRED", cell == null ? "PROFILE_FIELD_CELL_MISSING" : "FORMULA_FIELD_NOT_EDITABLE", specification));
                    continue;
                }
                if (context.mergedRegions().stream().anyMatch(range -> range.isInRange(rowIndex, columnIndex))) {
                    mapped.put(field, profileField(field, address, formatter.formatCellValue(cell),
                            "REVIEW_REQUIRED", "MERGED_FIELD_OVERLAP", specification));
                    continue;
                }
                String current = formatter.formatCellValue(cell);
                String reason = null;
                String status = "VERIFIED";
                if (!seenAddresses.add(address)) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_FIELD_COLLISION";
                } else if (!specification.required() && current.isBlank()) {
                    status = "REVIEW_REQUIRED"; reason = "OPTIONAL_FIELD_VALUE_ABSENT";
                } else if (current.isBlank()) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_FIELD_VALUE_BLANK";
                } else if (specification.expectedStyleId() != null
                        && specification.expectedStyleId() != cell.getCellStyle().getIndex()) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_STYLE_MISMATCH";
                } else if (specification.expectedFontName() != null
                        && !specification.expectedFontName().equals(sheet.getWorkbook()
                                .getFontAt(cell.getCellStyle().getFontIndexAsInt()).getFontName())) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_FONT_MISMATCH";
                } else if (specification.expectedFontSizePt() != null
                        && specification.expectedFontSizePt() != sheet.getWorkbook()
                                .getFontAt(cell.getCellStyle().getFontIndexAsInt()).getFontHeightInPoints()) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_FONT_SIZE_MISMATCH";
                } else if ("DEFAULT_BLACK".equals(specification.expectedFontColor())
                        && sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt()).getColor()
                                != org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex()
                        && sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt()).getColor()
                                != org.apache.poi.ss.usermodel.Font.COLOR_NORMAL) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_FONT_COLOR_MISMATCH";
                } else if (!Set.of("TEXT", "TEXT_WITH_UNIT").contains(specification.valueMode())) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_VALUE_TYPE_UNSUPPORTED";
                } else if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) {
                    status = "REVIEW_REQUIRED"; reason = "PROFILE_CELL_TYPE_MISMATCH";
                } else if (field.equals("managementNo")) {
                    status = "REVIEW_REQUIRED"; reason = "MANAGEMENT_ID_CHANGE_REQUIRES_GLOBAL_REMAP";
                } else {
                    Float expectedHeight = profile.rowHeightsPt().get(specification.rowOffset());
                    if (expectedHeight != null && Math.abs(row.getHeightInPoints() - expectedHeight) > 0.2f) {
                        status = "REVIEW_REQUIRED"; reason = "PROFILE_ROW_HEIGHT_MISMATCH";
                    }
                    if ("VERIFIED".equals(status)) {
                        for (var drawing : inventory.drawingObjects()) {
                            if (drawing.startRow() == null || drawing.endRow() == null
                                    || drawing.startColumn() == null || drawing.endColumn() == null) {
                                status = "REVIEW_REQUIRED"; reason = "DRAWINGML_LOCATION_UNKNOWN"; break;
                            }
                            if (rowIndex >= Math.min(drawing.startRow(), drawing.endRow())
                                    && rowIndex <= Math.max(drawing.startRow(), drawing.endRow())
                                    && columnIndex >= Math.min(drawing.startColumn(), drawing.endColumn())
                                    && columnIndex <= Math.max(drawing.startColumn(), drawing.endColumn())) {
                                status = "REVIEW_REQUIRED"; reason = "DRAWINGML_FIELD_OVERLAP"; break;
                            }
                        }
                    }
                }
                if ("VERIFIED".equals(status)) {
                    try {
                        requireNotMergedNonAnchor(context.mergedRegions(), reference, address);
                    } catch (InvalidWorkbookException exception) {
                        status = "REVIEW_REQUIRED";
                        reason = "MERGED_FIELD_NOT_EDITABLE";
                    }
                }
                mapped.put(field, profileField(field, address, current, status,
                        reason == null ? "PROFILE_CELL_STYLE_VALUE_AND_DRAWING_VERIFIED" : reason, specification));
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to validate Profile-based MODIFY fields", exception);
        }
        List<String> editable = mapped.values().stream().filter(item -> "VERIFIED".equals(item.status()))
                .map(FieldMapping::field).toList();
        return new BoxFieldMapping(managementNo, sheetName, context.box().topLeftCell(), context.box().bottomRightCell(),
                Map.copyOf(mapped), CONNECTION_UNVERIFIED, profile.profileId(), profile.profileVersion(), profile.status(),
                profile.evidenceLevel(), true, workingShaMatch, profile.fields().keySet().stream().toList(), editable);
    }

    /** Validate Profile field allowlists and change only those exact cells; raw OOXML parts remain preserved. */
    public ProfileModifyResult modifyProfileFieldsTestOnly(byte[] source, String processCode, String sheetName,
                                                            String managementNo, EquipmentBoxProfile profile,
                                                            String sourceSha256, String workingSha256,
                                                            Map<String, String> expectedCurrentValues,
                                                            Map<String, String> replacements) {
        BoxFieldMapping mapping = mapFieldsByProfile(source, processCode, sheetName, managementNo,
                profile, sourceSha256, workingSha256);
        if (replacements == null || replacements.isEmpty() || replacements.size() > mapping.availableFields().size())
            throw blocked("PROFILE_MODIFY_FIELD_SET_INVALID", "검증된 Profile 필드 중 하나 이상을 지정해야 합니다.");
        List<FieldChange> changes = new ArrayList<>();
        Map<String, String> before = new java.util.LinkedHashMap<>();
        Map<String, String> after = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String field = entry.getKey();
            FieldMapping fieldMapping = mapping.fields().get(field);
            String replacement = entry.getValue();
            if (fieldMapping == null) throw blocked("PROFILE_FIELD_UNSUPPORTED", "Profile에 없는 수정 필드입니다: " + field);
            if (!"VERIFIED".equals(fieldMapping.status()) || fieldMapping.cellAddress() == null)
                throw blocked("PROFILE_FIELD_NOT_EDITABLE", "필드가 현재 작업본에서 VERIFIED가 아닙니다: " + field + ":" + fieldMapping.reason());
            String expectedCurrent = expectedCurrentValues == null ? null : expectedCurrentValues.get(field);
            if (expectedCurrent == null || !expectedCurrent.equals(fieldMapping.currentText()))
                throw blocked("STALE_VALUE", "조회 당시 기존값과 현재 작업본의 셀 값이 달라 수정을 차단했습니다: " + field);
            validateReplacement(field, replacement, fieldMapping.valueMode());
            if (fieldMapping.currentText().equals(replacement))
                throw blocked("MODIFY_VALUE_UNCHANGED", "변경값이 기존값과 같습니다: " + field);
            if ("businessItemNo".equals(field) && valueExistsOutsideTarget(source, sheetName, replacement, fieldMapping.cellAddress()))
                throw blocked("DUPLICATE_ITEM_NO", "변경 Item No.가 작업본의 다른 셀에 이미 존재합니다.");
            changes.add(new FieldChange(fieldMapping.cellAddress(), fieldMapping.currentText(), replacement));
            before.put(field, fieldMapping.currentText());
            after.put(field, replacement);
        }
        ChangeResult result = modifyBoxFieldsTestOnly(source, sheetName, managementNo, changes);
        return new ProfileModifyResult(mapping, result, Map.copyOf(before), Map.copyOf(after));
    }

    private FieldMapping profileField(String field, String address, String current, String status, String reason,
                                      EquipmentBoxProfile.FieldCell specification) {
        return new FieldMapping(field, address, current, null, status, reason,
                specification.valueMode(), specification.expectedStyleId());
    }

    private void validateReplacement(String field, String value, String valueMode) {
        int maxLength = switch (field) {
            case "equipmentName" -> 255;
            case "businessItemNo" -> 128;
            case "capacity" -> 128;
            case "capacityUnit" -> 64;
            case "quantity" -> 64;
            case "equipmentTypeLabel" -> 128;
            case "note" -> 255;
            default -> throw blocked("PROFILE_FIELD_NOT_EDITABLE", "이 필드는 자동 수정할 수 없습니다: " + field);
        };
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl))
            throw blocked("MODIFY_VALUE_INVALID", "변경값이 비어 있거나 Profile/스키마 길이 제한을 벗어났습니다: " + field);
        if (!Set.of("TEXT", "TEXT_WITH_UNIT").contains(valueMode))
            throw blocked("PROFILE_VALUE_TYPE_UNSUPPORTED", "실제 확인된 text 기반 필드만 수정할 수 있습니다: " + field);
    }

    private boolean valueExistsOutsideTarget(byte[] source, String targetSheet, String value, String targetAddress) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (var sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                boolean isTarget = targetSheet.equals(sheet.getSheetName())
                        && cell.getAddress().formatAsString().replace("$", "").equalsIgnoreCase(targetAddress);
                if (value.equals(formatter.formatCellValue(cell).trim()) && !isTarget) return true;
            }
            return false;
        } catch (Exception exception) {
            throw blocked("DUPLICATE_VALUE_CHECK_UNAVAILABLE", "작업본 전체의 중복값을 검사할 수 없어 수정 요청을 차단했습니다.");
        }
    }

    /** Inspect cell geometry and nearby visual objects without asserting they represent verified physical flows. */
    public DeletionAssessment assessDeleteImpact(byte[] source, String sheetName, String managementNo) {
        BoxContext context = inspectBox(source, sheetName, managementNo);
        var inventory = inspector.inspectSheet(source, sheetName);
        List<DrawingImpact> drawingImpacts = new ArrayList<>();
        int top = context.box().firstRow() - 1;
        int bottom = context.box().lastRow() - 1;
        int left = context.box().firstColumn();
        int right = context.box().lastColumn();
        for (var object : inventory.drawingObjects()) {
            if (object.startRow() == null || object.endRow() == null
                    || object.startColumn() == null || object.endColumn() == null) {
                drawingImpacts.add(new DrawingImpact(object.shapeId(), object.objectName(), object.objectType(),
                        "UNKNOWN_LOCATION", object.startConnectedShapeId(), object.endConnectedShapeId(),
                        object.headArrowType(), object.tailArrowType(), "REVIEW_REQUIRED_LOCATION_UNKNOWN"));
                continue;
            }
            int objectTop = Math.min(object.startRow(), object.endRow());
            int objectBottom = Math.max(object.startRow(), object.endRow());
            int objectLeft = Math.min(object.startColumn(), object.endColumn());
            int objectRight = Math.max(object.startColumn(), object.endColumn());
            boolean overlaps = objectTop <= bottom && objectBottom >= top
                    && objectLeft <= right && objectRight >= left;
            boolean near = objectTop <= bottom + 1 && objectBottom >= top - 1
                    && objectLeft <= right + 1 && objectRight >= left - 1;
            if (!near) continue;
            drawingImpacts.add(new DrawingImpact(object.shapeId(), object.objectName(), object.objectType(),
                    overlaps ? "INTERSECTS_BOX" : "WITHIN_ONE_CELL_OF_BOX",
                    object.startConnectedShapeId(), object.endConnectedShapeId(),
                    object.headArrowType(), object.tailArrowType(), "VISUAL_OBJECT_ONLY_CONNECTION_NOT_VERIFIED"));
        }
        List<NearbyText> nearbyText = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int firstRow = Math.max(0, top - 2);
            int lastRow = Math.min(sheet.getLastRowNum(), bottom + 2);
            int firstColumn = Math.max(0, left - 2);
            int lastColumn = Math.min(16_383, right + 2);
            for (int rowIndex = firstRow; rowIndex <= lastRow && nearbyText.size() < 80; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (int column = firstColumn; column <= lastColumn && nearbyText.size() < 80; column++) {
                    if (rowIndex >= top && rowIndex <= bottom && column >= left && column <= right) continue;
                    Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null) continue;
                    String value = formatter.formatCellValue(cell).trim();
                    if (!value.isBlank()) nearbyText.add(new NearbyText(cell.getAddress().formatAsString(), value));
                }
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inspect nearby deletion impact text", exception);
        }
        boolean blocked = context.drawingPartName() != null || !nearbyText.isEmpty();
        String status = blocked ? "REVIEW_REQUIRED" : "STRUCTURE_VERIFIED_BOX_ONLY_TEST_ONLY";
        String reason = context.drawingPartName() != null
                ? "DRAWINGML_OR_CONNECTOR_MAY_REMAIN_AFTER_BOX_REMOVAL"
                : !nearbyText.isEmpty() ? "NEARBY_FLOW_OR_PROCESS_TEXT_REQUIRES_IMPACT_REVIEW"
                : "NO_DRAWINGML_OR_NEARBY_LABELS_DETECTED; PHYSICAL_CONNECTION_STILL_UNVERIFIED";
        return new DeletionAssessment(managementNo, sheetName, context.box().topLeftCell(),
                context.box().bottomRightCell(), List.copyOf(drawingImpacts), List.copyOf(nearbyText),
                status, !blocked, CONNECTION_UNVERIFIED, reason);
    }

    /** Update explicitly named cells only when the supplied current values still match. */
    public ChangeResult modifyBoxFieldsTestOnly(byte[] source, String sheetName, String managementNo,
                                                 List<FieldChange> changes) {
        BoxContext context = inspectBox(source, sheetName, managementNo);
        if (changes == null || changes.isEmpty() || changes.size() > 12) {
            throw blocked("FIELD_CHANGE_COUNT_INVALID", "1~12개의 명시적 필드 변경이 필요합니다.");
        }
        Set<String> seen = new java.util.HashSet<>();
        byte[] result = source;
        List<String> cells = new ArrayList<>();
        for (FieldChange change : changes) {
            if (change == null || blank(change.cellAddress()) || change.expectedCurrentText() == null
                    || blank(change.replacementText())) {
                throw blocked("FIELD_CHANGE_INVALID", "대상 셀, 기존값, 변경값은 공란일 수 없습니다.");
            }
            CellReference ref = cellReference(change.cellAddress());
            String address = ref.formatAsString().replace("$", "");
            if (!seen.add(address)) throw blocked("FIELD_CHANGE_DUPLICATE_CELL", "한 셀을 두 번 변경할 수 없습니다: " + address);
            requireInside(context.box(), ref, address);
            if (address.equalsIgnoreCase(context.box().topLeftCell())) {
                throw blocked("MANAGEMENT_ID_CHANGE_REQUIRES_REMAP", "설비 관리번호 변경에는 별도 master remapping 검토가 필요합니다.");
            }
            requireNotMergedNonAnchor(context.mergedRegions(), ref, address);
            requireTextCell(result, sheetName, address, change.expectedCurrentText());
            result = parts.replaceCellText(result, sheetName, address,
                    change.expectedCurrentText(), change.replacementText());
            cells.add(address);
        }
        validatePackageDelta(source, result, context.worksheetPart());
        try (Workbook reopened = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            var sheet = reopened.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int index = 0; index < changes.size(); index++) {
                String address = cells.get(index);
                CellReference ref = new CellReference(address);
                Row row = sheet.getRow(ref.getRow());
                Cell cell = row == null ? null : row.getCell(ref.getCol(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null || !changes.get(index).replacementText().equals(formatter.formatCellValue(cell))) {
                    throw blocked("MODIFY_REOPEN_MISMATCH", "수정 셀 값이 재열기 결과와 일치하지 않습니다: " + address);
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen modified TEST_ONLY workbook", exception);
        }
        return new ChangeResult(result, "MODIFY", managementNo, context.box().topLeftCell(),
                context.box().bottomRightCell(), cells, sha256(source), sha256(result), "TEST_ONLY",
                "STRUCTURE_VERIFIED_TEXT_FIELDS_ONLY", CONNECTION_UNVERIFIED,
                "Only explicitly mapped cell text changed; no DrawingML or connection evidence was modified.");
    }

    /** Delete only after the service has freshly produced an exact-SHA SAFE assessment. */
    public ChangeResult deleteBoxTestOnly(byte[] source, DeleteImpactAnalyzer.Assessment assessment) {
        if (assessment == null || !"SAFE_BOX_ONLY_TEST_ONLY".equals(assessment.status())
                || !assessment.automaticTestOnlyDeleteAllowed()
                || assessment.sheetName() == null || assessment.managementNo() == null
                || source == null || !sha256(source).equalsIgnoreCase(assessment.actualBytesSha256())
                || !sha256(source).equalsIgnoreCase(assessment.workingCopySha256())) {
            throw blocked("DELETE_ASSESSMENT_REQUIRED", "현재 바이트와 일치하는 SAFE_BOX_ONLY_TEST_ONLY 영향 분석이 필요합니다.");
        }
        return deleteBoxTestOnly(source, assessment.sheetName(), assessment.managementNo());
    }

    /** Package-only primitive; application services must use the assessment-guarded overload. */
    ChangeResult deleteBoxTestOnly(byte[] source, String sheetName, String managementNo) {
        BoxContext context = inspectBox(source, sheetName, managementNo);
        var inventory = inspector.inspectSheet(source, sheetName);
        int top = context.box().firstRow() - 1;
        int bottom = context.box().lastRow() - 1;
        int left = context.box().firstColumn();
        int right = context.box().lastColumn();
        for (var object : inventory.drawingObjects()) {
            if (object.startRow() == null || object.endRow() == null
                    || object.startColumn() == null || object.endColumn() == null) {
                throw blocked("DRAWINGML_LOCATION_UNKNOWN", "위치를 확인할 수 없는 DrawingML 객체가 있어 삭제를 차단했습니다.");
            }
            int objectTop = Math.min(object.startRow(), object.endRow());
            int objectBottom = Math.max(object.startRow(), object.endRow());
            int objectLeft = Math.min(object.startColumn(), object.endColumn());
            int objectRight = Math.max(object.startColumn(), object.endColumn());
            boolean intersects = objectTop <= bottom && objectBottom >= top
                    && objectLeft <= right && objectRight >= left;
            boolean near = objectTop <= bottom + 1 && objectBottom >= top - 1
                    && objectLeft <= right + 1 && objectRight >= left - 1;
            if (intersects || near) {
                throw blocked("DRAWINGML_DELETE_REVIEW_REQUIRED",
                        "설비 박스와 교차하거나 인접한 도형/연결선은 삭제 영향 검토가 필요합니다.");
            }
            if ("CONNECTOR".equals(object.objectType())
                    && (object.startConnectedShapeId() != null || object.endConnectedShapeId() != null)) {
                throw blocked("BOUND_CONNECTOR_DELETE_REVIEW_REQUIRED",
                        "shape binding이 있는 Connector 관계는 실제 연결 증거 없이 삭제 영향이 불명확합니다.");
            }
        }
        byte[] worksheetBytes = parts.readPart(source, context.worksheetPart());
        Document document = parse(worksheetBytes, context.worksheetPart());
        Element sheetData = first(document.getDocumentElement(), "sheetData");
        if (sheetData == null) throw blocked("SHEET_DATA_MISSING", "worksheet sheetData가 없습니다.");
        List<String> cleared = new ArrayList<>();
        for (Element row : children(sheetData)) {
            if (!"row".equals(row.getLocalName())) continue;
            for (Element cell : children(row)) {
                if (!"c".equals(cell.getLocalName())) continue;
                CellReference ref = cellReference(cell.getAttribute("r"));
                if (!inside(context.box(), ref)) continue;
                if (first(cell, "f") != null) throw blocked("FORMULA_IN_BOX", "수식 셀이 포함된 박스는 삭제할 수 없습니다.");
                if (!text(cell).isBlank()) cleared.add(ref.formatAsString().replace("$", ""));
                while (cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
                for (String attribute : List.of("s", "t", "cm", "vm", "ph")) cell.removeAttribute(attribute);
            }
        }
        removeContainedMerges(document.getDocumentElement(), context.box());
        byte[] editedSheet = serialize(document);
        byte[] result = parts.replacePackagePart(source, context.worksheetPart(), editedSheet);
        validatePackageDelta(source, result, context.worksheetPart());
        try (Workbook reopened = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            var sheet = reopened.getSheet(sheetName);
            for (int row = context.box().firstRow() - 1; row < context.box().lastRow(); row++) {
                Row targetRow = sheet.getRow(row);
                if (targetRow == null) continue;
                for (int col = context.box().firstColumn(); col <= context.box().lastColumn(); col++) {
                    Cell cell = targetRow.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null && !new DataFormatter(Locale.ROOT).formatCellValue(cell).isBlank()) {
                        throw blocked("DELETE_REOPEN_MISMATCH", "박스 범위에 값이 남았습니다: " + cell.getAddress());
                    }
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen deleted TEST_ONLY box", exception);
        }
        return new ChangeResult(result, "DELETE", managementNo, context.box().topLeftCell(),
                context.box().bottomRightCell(), List.copyOf(cleared), sha256(source), sha256(result), "TEST_ONLY",
                "STRUCTURE_VERIFIED_BOX_CONTENTS_REMOVED", CONNECTION_UNVERIFIED,
                "Box cell values, styles, and internal merges were removed; no row shifting or DrawingML edit occurred.");
    }

    private BoxContext inspectBox(byte[] source, String sheetName, String managementNo) {
        Objects.requireNonNull(source, "workbook bytes");
        if (source.length == 0 || blank(sheetName) || blank(managementNo)) {
            throw blocked("BOX_IDENTITY_REQUIRED", "작업본, 시트명, 설비번호가 필요합니다.");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw blocked("TARGET_SHEET_MISSING", "대상 시트가 없습니다.");
            var matches = geometry.locateByManagementNo(sheet, managementNo);
            if (matches.size() != 1) throw blocked("EQUIPMENT_BOX_NOT_UNIQUE", "설비번호가 단일 셀 기반 박스로 식별되지 않습니다.");
            var box = matches.get(0);
            for (Row row : sheet) for (Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                        && inside(box, new CellReference(cell.getRowIndex(), cell.getColumnIndex()))) {
                    throw blocked("FORMULA_IN_BOX", "수식 셀이 포함된 박스는 변경할 수 없습니다.");
                }
            }
            var inventory = inspector.inspectSheet(source, sheetName);
            String relationshipPart = relationshipPart(inventory.worksheetPartName());
            validateWorksheetRelationships(source, relationshipPart);
            if (inventory.drawingPartName() != null && inventory.drawingObjects().isEmpty()) {
                throw blocked("DRAWING_INVENTORY_EMPTY", "DrawingML 파트는 있으나 객체 inventory가 비어 있어 편집을 차단했습니다.");
            }
            List<org.apache.poi.ss.util.CellRangeAddress> mergedRegions = new ArrayList<>();
            for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
                mergedRegions.add(sheet.getMergedRegion(index));
            }
            return new BoxContext(box, inventory.worksheetPartName(), inventory.drawingPartName(), List.copyOf(mergedRegions));
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inspect target equipment box", exception);
        }
    }

    private void requireTextCell(byte[] source, String sheetName, String address, String expected) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet(sheetName);
            CellReference ref = new CellReference(address);
            Row row = sheet.getRow(ref.getRow());
            Cell cell = row == null ? null : row.getCell(ref.getCol(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                    || !expected.equals(new DataFormatter(Locale.ROOT).formatCellValue(cell))) {
                throw blocked("CURRENT_FIELD_VALUE_MISMATCH", "대상 셀의 기존값이 변경 요청의 기대값과 다릅니다: " + address);
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify current field value", exception);
        }
    }

    private void requireNotMergedNonAnchor(List<org.apache.poi.ss.util.CellRangeAddress> mergedRegions,
                                           CellReference ref, String address) {
        for (var range : mergedRegions) {
            if (range.isInRange(ref.getRow(), ref.getCol())
                    && (range.getFirstRow() != ref.getRow() || range.getFirstColumn() != ref.getCol())) {
                throw blocked("MERGED_FIELD_NOT_ANCHOR", "변경 셀이 병합 범위의 anchor가 아닙니다: " + address);
            }
        }
    }

    private void requireInside(EquipmentBoxGeometryLocator.Box box, CellReference ref, String address) {
        if (!inside(box, ref)) throw blocked("FIELD_OUTSIDE_EQUIPMENT_BOX", "변경 셀이 설비 박스 밖에 있습니다: " + address);
    }

    private boolean inside(EquipmentBoxGeometryLocator.Box box, CellReference ref) {
        return ref.getRow() >= box.firstRow() - 1 && ref.getRow() < box.lastRow()
                && ref.getCol() >= box.firstColumn() && ref.getCol() <= box.lastColumn();
    }

    private void removeContainedMerges(Element root, EquipmentBoxGeometryLocator.Box box) {
        Element merges = first(root, "mergeCells");
        if (merges == null) return;
        List<Element> ranges = children(merges).stream().filter(element -> "mergeCell".equals(element.getLocalName())).toList();
        for (Element merge : ranges) {
            org.apache.poi.ss.util.CellRangeAddress range;
            try { range = org.apache.poi.ss.util.CellRangeAddress.valueOf(merge.getAttribute("ref")); }
            catch (RuntimeException exception) { throw blocked("MERGE_REFERENCE_INVALID", "병합 범위를 안전하게 읽을 수 없습니다."); }
            boolean intersects = range.getFirstRow() < box.lastRow() && range.getLastRow() >= box.firstRow() - 1
                    && range.getFirstColumn() <= box.lastColumn() && range.getLastColumn() >= box.firstColumn();
            if (!intersects) continue;
            boolean contained = range.getFirstRow() >= box.firstRow() - 1 && range.getLastRow() < box.lastRow()
                    && range.getFirstColumn() >= box.firstColumn() && range.getLastColumn() <= box.lastColumn();
            if (!contained) throw blocked("MERGE_CROSSES_BOX_BOUNDARY", "박스 경계를 넘어가는 병합 범위가 있습니다.");
            merges.removeChild(merge);
        }
        int count = children(merges).stream().filter(element -> "mergeCell".equals(element.getLocalName())).toList().size();
        if (count == 0) merges.getParentNode().removeChild(merges);
        else merges.setAttribute("count", Integer.toString(count));
    }

    private void validatePackageDelta(byte[] beforeBytes, byte[] afterBytes, String allowedPart) {
        Map<String, byte[]> before = readParts(beforeBytes);
        Map<String, byte[]> after = readParts(afterBytes);
        if (!before.keySet().equals(after.keySet())) throw blocked("PACKAGE_PART_SET_CHANGED", "OOXML package part 집합이 달라졌습니다.");
        for (String name : before.keySet()) {
            if (!name.equals(allowedPart) && !java.util.Arrays.equals(before.get(name), after.get(name))) {
                throw blocked("UNEXPECTED_PACKAGE_PART_CHANGE", "대상 worksheet 외 OOXML part가 변경되었습니다: " + name);
            }
        }
    }

    private Map<String, byte[]> readParts(byte[] bytes) {
        java.util.LinkedHashMap<String, byte[]> result = new java.util.LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to audit OOXML package", exception);
        }
        return result;
    }

    private boolean packagePartExists(byte[] bytes, String name) { return readParts(bytes).containsKey(name); }

    private void validateWorksheetRelationships(byte[] bytes, String relationshipPart) {
        if (!packagePartExists(bytes, relationshipPart)) return;
        Element root = parse(parts.readPart(bytes, relationshipPart), relationshipPart).getDocumentElement();
        int drawings = 0;
        for (Element relationship : children(root)) {
            if (!P.equals(relationship.getNamespaceURI()) || !"Relationship".equals(relationship.getLocalName())) continue;
            String type = relationship.getAttribute("Type");
            if (type.endsWith("/drawing")) {
                drawings++;
            } else if (!type.endsWith("/printerSettings")) {
                throw blocked("WORKSHEET_RELATIONSHIP_UNSUPPORTED",
                        "대상 worksheet에 편집 영향이 분석되지 않은 관계가 있습니다: " + type);
            }
        }
        if (drawings > 1) throw blocked("MULTIPLE_DRAWING_RELATIONSHIPS", "worksheet에 DrawingML 관계가 여러 개 있어 편집을 차단했습니다.");
    }

    private String relationshipPart(String worksheetPart) {
        int slash = worksheetPart.lastIndexOf('/');
        String directory = worksheetPart.substring(0, slash);
        String filename = worksheetPart.substring(slash + 1);
        return directory + "/_rels/" + filename + ".rels";
    }

    private Document parse(byte[] bytes, String partName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Malformed worksheet OOXML part: " + partName, exception);
        }
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
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to serialize edited worksheet", exception);
        }
    }

    private Element first(Element parent, String localName) {
        for (Element element : children(parent)) if (S.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName())) return element;
        return null;
    }

    private List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.add(element);
        }
        return result;
    }

    private String text(Element cell) {
        Element inline = first(cell, "is");
        Element value = first(cell, "v");
        String raw = inline != null ? inline.getTextContent() : value == null ? "" : value.getTextContent();
        return raw == null ? "" : raw;
    }

    private CellReference cellReference(String address) {
        if (address == null || address.length() > 16 || !CELL_REF.matcher(address).matches()) {
            throw blocked("CELL_REFERENCE_INVALID", "유효한 A1 셀 주소가 아닙니다.");
        }
        try { return new CellReference(address); }
        catch (RuntimeException exception) { throw blocked("CELL_REFERENCE_INVALID", "유효하지 않은 셀 주소입니다."); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private InvalidWorkbookException blocked(String code, String reason) { return new InvalidWorkbookException(code + ": " + reason); }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record BoxContext(EquipmentBoxGeometryLocator.Box box, String worksheetPart, String drawingPartName,
                              List<org.apache.poi.ss.util.CellRangeAddress> mergedRegions) { }

    public record FieldChange(String cellAddress, String expectedCurrentText, String replacementText) { }

    public record DrawingImpact(String objectId, String objectName, String objectType, String proximity,
                                String startConnectedShapeId, String endConnectedShapeId,
                                String headArrowType, String tailArrowType, String evidenceStatus) { }

    public record NearbyText(String cellAddress, String text) { }

    public record DeletionAssessment(String managementNo, String sheetName, String boxTopLeft,
                                     String boxBottomRight, List<DrawingImpact> drawingImpacts,
                                     List<NearbyText> nearbyText, String status,
                                     boolean automaticTestOnlyDeleteAllowed,
                                     String connectionEvidenceStatus, String reason) {
        public DeletionAssessment {
            drawingImpacts = List.copyOf(drawingImpacts);
            nearbyText = List.copyOf(nearbyText);
        }
    }

    public record FieldMapping(String field, String cellAddress, String currentText, String masterText,
                               String status, String reason, String valueMode, Integer expectedStyleId) {
        public FieldMapping(String field, String cellAddress, String currentText, String masterText,
                            String status, String reason) {
            this(field, cellAddress, currentText, masterText, status, reason, null, null);
        }
    }

    public record BoxFieldMapping(String managementNo, String sheetName, String boxTopLeft, String boxBottomRight,
                                  Map<String, FieldMapping> fields, String connectionEvidenceStatus,
                                  String profileId, String profileVersion, String profileStatus,
                                  String profileEvidenceLevel, boolean profileShaMatch,
                                  boolean workingCopyShaMatch, List<String> profileFields,
                                  List<String> availableFields) {
        public BoxFieldMapping(String managementNo, String sheetName, String boxTopLeft, String boxBottomRight,
                               Map<String, FieldMapping> fields, String connectionEvidenceStatus) {
            this(managementNo, sheetName, boxTopLeft, boxBottomRight, fields, connectionEvidenceStatus,
                    null, null, null, null, false, false, List.copyOf(fields.keySet()),
                    fields.values().stream().filter(field -> "VERIFIED".equals(field.status()))
                            .map(FieldMapping::field).toList());
        }
        public BoxFieldMapping {
            fields = Map.copyOf(fields);
            profileFields = profileFields == null ? List.of() : List.copyOf(profileFields);
            availableFields = availableFields == null ? List.of() : List.copyOf(availableFields);
        }
    }

    public record ProfileModifyResult(BoxFieldMapping mapping, ChangeResult change,
                                      Map<String, String> beforeValues, Map<String, String> afterValues) {
        public ProfileModifyResult {
            beforeValues = Map.copyOf(beforeValues);
            afterValues = Map.copyOf(afterValues);
        }
    }

    public record ChangeResult(byte[] workbookBytes, String operation, String managementNo,
                               String boxTopLeft, String boxBottomRight, List<String> affectedCells,
                               String sourceSha256, String outputSha256, String scenarioStatus,
                               String structuralEditStatus, String connectionEvidenceStatus,
                               String interpretation) {
        public ChangeResult { affectedCells = List.copyOf(affectedCells); }
    }
}
