package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** Conservative preflight for a preformatted reserved box area. It never moves boxes or edits DrawingML. */
@Component
public class DiagramPlacementPreflight {
    private static final Pattern EQUIPMENT_ID = Pattern.compile(
            "(?i)(?:[A-Z]{1,4}-[A-Z]{1,5}\\d{2,8}|[A-Z]{1,4}-\\d{2,8})(?:[-.][A-Z0-9]{1,8})*");
    private final OoxmlPackageInspector inspector;
    private final EquipmentBoxGeometryLocator geometry;
    private final OoxmlPartEditor parts;

    public DiagramPlacementPreflight(OoxmlPackageInspector inspector, EquipmentBoxGeometryLocator geometry,
                                     OoxmlPartEditor parts) {
        this.inspector = Objects.requireNonNull(inspector);
        this.geometry = Objects.requireNonNull(geometry);
        this.parts = Objects.requireNonNull(parts);
    }

    public Result analyze(byte[] sourceBytes, Request request) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(request, "request");
        String actualSha = sha256(sourceBytes);
        List<String> blockers = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        if (!actualSha.equalsIgnoreCase(request.sourceSha256())) blockers.add("SOURCE_SHA_MISMATCH");
        if (request.mapping() == null) blockers.add("LOCATION_MAPPING_MISSING");
        if (request.mapping() != null) {
            var mapping = request.mapping();
            if (!Objects.equals(mapping.processId(), request.processId())
                    || !Objects.equals(mapping.sheetName(), request.sheetName())) blockers.add("LOCATION_IDENTITY_MISMATCH");
            if (!actualSha.equalsIgnoreCase(mapping.sourceSha256())) blockers.add("MAPPING_SOURCE_SHA_MISMATCH");
            if (mapping.placementStatus() != DiagramLayoutMapping.PlacementStatus.VERIFIED
                    || mapping.evidenceStatus() != DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED) {
                reviews.add("LOCATION_EVIDENCE_NOT_VERIFIED");
            }
        }
        CellRangeAddress target = parseRange(request.targetRange(), blockers);
        if (request.rowsRequired() < 1 || request.columnsRequired() < 1) blockers.add("INVALID_REQUIRED_DIMENSIONS");
        if (target != null && (rowCount(target) != request.rowsRequired()
                || columnCount(target) != request.columnsRequired())) blockers.add("TARGET_DIMENSION_MISMATCH");
        if (request.equipmentId() == null || request.equipmentId().isBlank()) blockers.add("EQUIPMENT_ID_MISSING");
        if (request.equipmentName() == null || request.equipmentName().isBlank()) blockers.add("EQUIPMENT_NAME_MISSING");

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            if (sheet == null) {
                blockers.add("TARGET_SHEET_MISSING");
                return result(actualSha, request, target, blockers, reviews, List.of(), List.of(), false);
            }
            if (target == null) return result(actualSha, request, null, blockers, reviews, List.of(), List.of(), false);
            if (target.getLastRow() >= 1_048_576 || target.getLastColumn() >= 16_384) blockers.add("TARGET_RANGE_OUT_OF_BOUNDS");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> occupied = new ArrayList<>();
            List<String> styledTargets = new ArrayList<>();
            List<String> targetCells = new ArrayList<>();
            for (int rowIndex = target.getFirstRow(); rowIndex <= target.getLastRow(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                for (int column = target.getFirstColumn(); column <= target.getLastColumn(); column++) {
                    Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    String address = new CellReference(rowIndex, column).formatAsString().replace("$", "");
                    targetCells.add(address);
                    if (cell != null && !formatter.formatCellValue(cell).isBlank()) occupied.add(address);
                    if (cell != null && cell.getCellStyle() != null && cell.getCellStyle().getIndex() != 0) styledTargets.add(address);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                        blockers.add("FORMULA_IN_TARGET:" + address);
                }
            }
            if (!occupied.isEmpty()) blockers.add("TARGET_CELLS_OCCUPIED:" + String.join(",", occupied));
            if (styledTargets.size() != targetCells.size()) reviews.add("TARGET_NOT_FULLY_PREFORMATTED");
            List<String> mergeConflicts = sheet.getMergedRegions().stream().filter(range -> intersects(range, target))
                    .map(CellRangeAddress::formatAsString).toList();
            if (!mergeConflicts.isEmpty()) blockers.add("MERGED_RANGE_COLLISION:" + String.join(",", mergeConflicts));

            List<String> duplicateIds = findDuplicateEquipmentIds(sheet, formatter, request.equipmentId());
            if (!duplicateIds.isEmpty()) blockers.add("DUPLICATE_EQUIPMENT_ID:" + String.join(",", duplicateIds));
            List<String> equipmentOverlaps = findExistingEquipmentOverlaps(sheet, target, request.equipmentId(), formatter);
            if (!equipmentOverlaps.isEmpty()) blockers.add("EXISTING_EQUIPMENT_BOX_COLLISION:" + String.join(",", equipmentOverlaps));

            WorkbookInventory.SheetInventory sheetInventory = inspector.inspectSheet(sourceBytes, request.sheetName());
            List<String> drawingConflicts = new ArrayList<>();
            for (WorkbookInventory.DrawingObject object : sheetInventory.drawingObjects()) {
                if (object.startRow() == null || object.endRow() == null || object.startColumn() == null || object.endColumn() == null) {
                    reviews.add("DRAWING_ANCHOR_UNKNOWN:" + object.shapeId());
                } else if (drawingIntersects(object, target)) {
                    drawingConflicts.add(String.valueOf(object.objectType()) + ":" + object.shapeId());
                }
            }
            if (!drawingConflicts.isEmpty()) blockers.add("DRAWINGML_ANCHOR_COLLISION:" + String.join(",", drawingConflicts));

            List<String> templateProblems = verifyTemplate(request, actualSha, workbook, sheet, target);
            blockers.addAll(templateProblems);
            if (sheetInventory.worksheetPartName() == null) blockers.add("WORKSHEET_PART_UNKNOWN");
            if (!sheetInventory.drawingObjects().isEmpty() && sheetInventory.drawingPartName() == null)
                blockers.add("DRAWING_PART_RELATION_UNKNOWN");
            if (!reviews.isEmpty()) reviews = new ArrayList<>(new LinkedHashSet<>(reviews));
            List<String> connectorCrossings = sheetInventory.drawingObjects().stream()
                    .filter(object -> "CONNECTOR".equals(object.objectType()))
                    .filter(object -> object.startRow() == null || object.endRow() == null
                            || object.startColumn() == null || object.endColumn() == null
                            || drawingIntersects(object, target))
                    .map(object -> String.valueOf(object.shapeId())).toList();
            return result(actualSha, request, target, blockers, reviews, drawingConflicts, connectorCrossings,
                    blockers.isEmpty() && reviews.isEmpty());
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to preflight requested diagram placement", exception);
        }
    }

    /**
     * Full-box ADD preflight for a caller-selected, empty cell range. The source Profile is used only as a
     * cell-style/merge template; this check never grants production placement approval or physical connectivity.
     */
    public BoxAddResult analyzeBoxAdd(byte[] sourceBytes, BoxAddRequest request) {
        return analyzeBoxAddInternal(sourceBytes, request, false);
    }

    BoxAddResult analyzeBoxAddForShapeCompositionTestOnly(byte[] sourceBytes, BoxAddRequest request) {
        return analyzeBoxAddInternal(sourceBytes, request, true);
    }

    private BoxAddResult analyzeBoxAddInternal(byte[] sourceBytes, BoxAddRequest request, boolean shapeCompositionTestOnly) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(request, "request");
        String actualSha = sha256(sourceBytes);
        List<String> blockers = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        CellRangeAddress template = parseRange(request.templateRange(), blockers);
        CellRangeAddress target = parseRange(request.targetRange(), blockers);
        if (!actualSha.equalsIgnoreCase(request.sourceSha256())) blockers.add("SOURCE_SHA_MISMATCH");
        if (request.mapping() == null) blockers.add("LOCATION_MAPPING_MISSING");
        if (request.templateProfile() == null) blockers.add("TEMPLATE_PROFILE_MISSING");
        if (request.equipmentId() == null || request.equipmentId().isBlank()) blockers.add("EQUIPMENT_ID_MISSING");
        if (request.equipmentName() == null || request.equipmentName().isBlank()) blockers.add("EQUIPMENT_NAME_MISSING");
        if (template != null && target != null
                && (rowCount(template) != rowCount(target) || columnCount(template) != columnCount(target))) {
            blockers.add("TARGET_DIMENSION_MISMATCH");
        }
        if (template != null && target != null && intersects(template, target)) blockers.add("TEMPLATE_TARGET_OVERLAP");
        if (target != null && (target.getLastRow() >= 1_048_576 || target.getLastColumn() >= 16_384))
            blockers.add("TARGET_RANGE_OUT_OF_BOUNDS");
        if (request.mapping() != null) {
            var mapping = request.mapping();
            if (!Objects.equals(mapping.processId(), request.processId())
                    || !Objects.equals(mapping.sheetName(), request.sheetName())
                    || !Objects.equals(mapping.boxRange(), request.targetRange())) blockers.add("LOCATION_IDENTITY_MISMATCH");
            if (!actualSha.equalsIgnoreCase(mapping.sourceSha256())) blockers.add("MAPPING_SOURCE_SHA_MISMATCH");
            if (mapping.placementStatus() != DiagramLayoutMapping.PlacementStatus.VERIFIED
                    || mapping.evidenceStatus() != DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED)
                reviews.add("LOCATION_EVIDENCE_NOT_VERIFIED");
        }
        EquipmentBoxProfile profile = request.templateProfile();
        if (profile != null) {
            if (!profile.appliesTo(request.processId(), request.sheetName(), request.sourceSha256()))
                blockers.add("TEMPLATE_PROFILE_IDENTITY_MISMATCH");
            if (!profile.matchesWorkingCopySha(actualSha)) blockers.add("TEMPLATE_WORKING_SHA_MISMATCH");
            if (!profile.enabledForGenericAdd()) blockers.add("TEMPLATE_PROFILE_NOT_ENABLED");
            if (template != null && profile.boxHeight() != rowCount(template)) blockers.add("TEMPLATE_HEIGHT_MISMATCH");
            if (profile.templateRange() == null || !profile.templateRange().equalsIgnoreCase(request.templateRange()))
                blockers.add("TEMPLATE_RANGE_MISMATCH");
            Set<String> supported = Set.of("managementNo", "equipmentTypeLabel", "equipmentName",
                    "businessItemNo", "capacity", "capacityUnit", "quantity");
            if (!supported.containsAll(profile.fields().keySet())) blockers.add("PROFILE_FIELD_UNSUPPORTED");
            boolean shapeRequired = profile.drawingPolicy() != null
                    && (profile.drawingPolicy().toUpperCase(Locale.ROOT).contains("SHAPE_REQUIRED")
                    || profile.drawingPolicy().toUpperCase(Locale.ROOT).contains("DRAWINGML_REQUIRED"));
            if (shapeCompositionTestOnly && !"SHAPE_REQUIRED_TEST_ONLY".equalsIgnoreCase(profile.drawingPolicy()))
                blockers.add("PROFILE_SHAPE_POLICY_NOT_APPROVED_FOR_TEST_COMPOSITION");
            if (!shapeCompositionTestOnly && shapeRequired) blockers.add("SHAPE_REQUIRED_BLOCKED");
            if (profile.connectorPolicy() != null && (profile.connectorPolicy().toUpperCase(Locale.ROOT).contains("CONNECTOR_CREATE_REQUIRED")
                    || profile.connectorPolicy().toUpperCase(Locale.ROOT).contains("CONNECTOR_REWRITE_REQUIRED")))
                blockers.add("CONNECTOR_EDIT_REQUIRED_BLOCKED");
            Set<String> fieldOffsets = new LinkedHashSet<>();
            for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
                String offset = entry.getValue().rowOffset() + ":" + entry.getValue().columnOffset();
                if (!fieldOffsets.add(offset)) blockers.add("PROFILE_FIELD_OFFSET_COLLISION:" + offset);
            }
        }

        List<String> targetCells = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            if (sheet == null) {
                blockers.add("TARGET_SHEET_MISSING");
                return boxAddResult(actualSha, request, target, blockers, reviews, List.of(), List.of(), false);
            }
            if (template == null || target == null)
                return boxAddResult(actualSha, request, target, blockers, reviews, List.of(), List.of(), false);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> occupied = new ArrayList<>();
            List<String> templateProblems = new ArrayList<>();
            for (int rowOffset = 0; rowOffset < rowCount(template); rowOffset++) {
                int sourceRowIndex = template.getFirstRow() + rowOffset;
                int targetRowIndex = target.getFirstRow() + rowOffset;
                Row sourceRow = sheet.getRow(sourceRowIndex);
                Row targetRow = sheet.getRow(targetRowIndex);
                float sourceHeight = sourceRow == null ? sheet.getDefaultRowHeightInPoints() : sourceRow.getHeightInPoints();
                float targetHeight = targetRow == null ? sheet.getDefaultRowHeightInPoints() : targetRow.getHeightInPoints();
                Float expectedHeight = profile == null ? null : profile.rowHeightsPt().get(rowOffset);
                if (expectedHeight == null || Math.abs(sourceHeight - expectedHeight) > 0.2f)
                    templateProblems.add("TEMPLATE_ROW_HEIGHT_MISMATCH:" + (rowOffset + 1));
                if (Math.abs(sourceHeight - targetHeight) > 0.2f)
                    blockers.add("TARGET_ROW_HEIGHT_MISMATCH:" + (targetRowIndex + 1));
                for (int columnOffset = 0; columnOffset < columnCount(template); columnOffset++) {
                    int sourceColumn = template.getFirstColumn() + columnOffset;
                    int targetColumn = target.getFirstColumn() + columnOffset;
                    String targetAddress = new CellReference(targetRowIndex, targetColumn).formatAsString().replace("$", "");
                    targetCells.add(targetAddress);
                    Cell sourceCell = sourceRow == null ? null
                            : sourceRow.getCell(sourceColumn, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    Cell targetCell = targetRow == null ? null
                            : targetRow.getCell(targetColumn, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (sourceCell == null || sourceCell.getCellStyle() == null || sourceCell.getCellStyle().getIndex() == 0)
                        templateProblems.add("TEMPLATE_CELL_STYLE_MISSING:" + new CellReference(sourceRowIndex, sourceColumn).formatAsString());
                    if (sourceCell != null && sourceCell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                        templateProblems.add("FORMULA_IN_TEMPLATE:" + sourceCell.getAddress().formatAsString());
                    if (sourceCell != null && !formatter.formatCellValue(sourceCell).isBlank()) {
                        boolean mappedField = false;
                        if (profile != null) for (EquipmentBoxProfile.FieldCell field : profile.fields().values())
                            if (field.rowOffset() == rowOffset && field.columnOffset() == columnOffset) mappedField = true;
                        if (!mappedField) templateProblems.add("UNMAPPED_TEMPLATE_TEXT_REQUIRES_REVIEW:"
                                + new CellReference(sourceRowIndex, sourceColumn).formatAsString());
                    }
                    if (targetCell == null) blockers.add("TARGET_CELL_MISSING:" + targetAddress);
                    if (targetCell != null && targetCell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                        blockers.add("FORMULA_IN_TARGET:" + targetAddress);
                    if (targetCell != null && !formatter.formatCellValue(targetCell).isBlank()) occupied.add(targetAddress);
                    if (sourceColumn < 0 || sourceColumn >= 16_384 || targetColumn < 0 || targetColumn >= 16_384)
                        blockers.add("TARGET_RANGE_OUT_OF_BOUNDS");
                    if (Math.abs(sheet.getColumnWidth(sourceColumn) - sheet.getColumnWidth(targetColumn)) > 51
                            || sheet.isColumnHidden(sourceColumn) != sheet.isColumnHidden(targetColumn))
                        blockers.add("TARGET_COLUMN_GEOMETRY_MISMATCH:" + (columnOffset + 1));
                }
            }
            if (!templateProblems.isEmpty()) blockers.addAll(templateProblems);
            if (!occupied.isEmpty()) blockers.add("TARGET_CELLS_OCCUPIED:" + String.join(",", occupied));
            List<String> mergeConflicts = sheet.getMergedRegions().stream()
                    .filter(range -> intersects(range, target)).map(CellRangeAddress::formatAsString).toList();
            if (!mergeConflicts.isEmpty()) blockers.add("MERGED_RANGE_COLLISION:" + String.join(",", mergeConflicts));
            List<String> sourceMergeRanges = sheet.getMergedRegions().stream().filter(range -> intersects(range, template))
                    .map(CellRangeAddress::formatAsString).toList();
            if (profile != null && !new LinkedHashSet<>(sourceMergeRanges).equals(new LinkedHashSet<>(profile.mergedRanges())))
                blockers.add("TEMPLATE_MERGE_MISMATCH");
            List<String> sourceMergeProblems = sheet.getMergedRegions().stream().filter(range -> intersects(range, template))
                    .filter(range -> range.getFirstRow() < template.getFirstRow() || range.getLastRow() > template.getLastRow()
                            || range.getFirstColumn() < template.getFirstColumn() || range.getLastColumn() > template.getLastColumn())
                    .map(CellRangeAddress::formatAsString).toList();
            if (!sourceMergeProblems.isEmpty()) blockers.add("TEMPLATE_MERGE_CROSSES_BOX:" + String.join(",", sourceMergeProblems));
            if (profile != null) {
                for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
                    var field = entry.getValue();
                    if (field.rowOffset() < 0 || field.rowOffset() >= rowCount(template)
                            || field.columnOffset() < 0 || field.columnOffset() >= columnCount(template)) {
                        blockers.add("PROFILE_FIELD_OUTSIDE_TEMPLATE:" + entry.getKey());
                        continue;
                    }
                    int sourceRowIndex = template.getFirstRow() + field.rowOffset();
                    int sourceColumn = template.getFirstColumn() + field.columnOffset();
                    Row sourceRow = sheet.getRow(sourceRowIndex);
                    Cell sourceCell = sourceRow == null ? null
                            : sourceRow.getCell(sourceColumn, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (sourceCell == null || field.expectedStyleId() != null
                            && sourceCell.getCellStyle().getIndex() != field.expectedStyleId())
                        blockers.add("TEMPLATE_STYLE_MISMATCH:" + entry.getKey());
                    if (sourceCell != null) {
                        var font = workbook.getFontAt(sourceCell.getCellStyle().getFontIndexAsInt());
                        if (field.expectedFontName() != null && !field.expectedFontName().equals(font.getFontName()))
                            blockers.add("TEMPLATE_FONT_MISMATCH:" + entry.getKey());
                        if (field.expectedFontSizePt() != null && field.expectedFontSizePt() != font.getFontHeightInPoints())
                            blockers.add("TEMPLATE_FONT_SIZE_MISMATCH:" + entry.getKey());
                        if (field.expectedFontColor() != null && "DEFAULT_BLACK".equals(field.expectedFontColor())
                                && font.getColor() != org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex()
                                && font.getColor() != org.apache.poi.ss.usermodel.Font.COLOR_NORMAL)
                            blockers.add("TEMPLATE_FONT_COLOR_MISMATCH:" + entry.getKey());
                    }
                    Float expectedHeight = profile.rowHeightsPt().get(field.rowOffset());
                    if (expectedHeight == null) blockers.add("PROFILE_ROW_HEIGHT_MISSING:" + entry.getKey());
                    for (CellRangeAddress merge : sheet.getMergedRegions()) {
                        if (merge.isInRange(sourceRowIndex, sourceColumn)
                                && (merge.getFirstRow() != sourceRowIndex || merge.getFirstColumn() != sourceColumn))
                            blockers.add("PROFILE_FIELD_NOT_MERGE_ANCHOR:" + entry.getKey());
                    }
                }
                for (Map.Entry<String, Double> expectedWidth : profile.columnWidths().entrySet()) {
                    int column = CellReference.convertColStringToIndex(expectedWidth.getKey());
                    if (Math.abs(sheet.getColumnWidth(column) / 256d - expectedWidth.getValue()) > 0.2d)
                        blockers.add("TEMPLATE_COLUMN_WIDTH_MISMATCH:" + expectedWidth.getKey());
                }
            }
            List<String> duplicateIds = findDuplicateEquipmentIds(sheet, formatter, request.equipmentId());
            if (!duplicateIds.isEmpty()) blockers.add("DUPLICATE_EQUIPMENT_ID:" + String.join(",", duplicateIds));
            List<String> overlaps = findExistingEquipmentOverlaps(sheet, target, request.equipmentId(), formatter);
            if (!overlaps.isEmpty()) blockers.add("EXISTING_EQUIPMENT_BOX_COLLISION:" + String.join(",", overlaps));
            var inventory = inspector.inspectSheet(sourceBytes, request.sheetName());
            List<String> drawingConflicts = new ArrayList<>();
            List<String> connectorCrossings = new ArrayList<>();
            for (WorkbookInventory.DrawingObject object : inventory.drawingObjects()) {
                if (object.startRow() == null || object.endRow() == null || object.startColumn() == null || object.endColumn() == null) {
                    drawingConflicts.add("UNKNOWN_ANCHOR:" + object.shapeId());
                    if ("CONNECTOR".equals(object.objectType())) connectorCrossings.add(String.valueOf(object.shapeId()));
                } else if (drawingIntersects(object, target)) {
                    drawingConflicts.add(String.valueOf(object.objectType()) + ":" + object.shapeId());
                    if ("CONNECTOR".equals(object.objectType())) connectorCrossings.add(String.valueOf(object.shapeId()));
                }
            }
            if (!drawingConflicts.isEmpty()) blockers.add("DRAWINGML_ANCHOR_COLLISION:" + String.join(",", drawingConflicts));
            return boxAddResult(actualSha, request, target, blockers, reviews, drawingConflicts,
                    connectorCrossings, blockers.isEmpty() && reviews.isEmpty());
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to preflight selected cell-layout box ADD", exception);
        }
    }

    /** Adds values only into an already styled, blank reserved range. No row insertion, styling, or drawing edit occurs. */
    public EditResult writeReservedRangeValuesTestOnly(byte[] sourceBytes, Request request, Map<String, String> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) throw blocked("FIELD_VALUES_MISSING");
        Result preflight = analyze(sourceBytes, request);
        if (!preflight.testOnlyReady()) throw blocked("PLACEMENT_NOT_TEST_ONLY_READY:" + preflight.status());
        EquipmentBoxProfile profile = request.templateProfile();
        Set<String> allowedFields = profile.fields().keySet();
        if (!allowedFields.containsAll(fieldValues.keySet())) throw blocked("FIELD_NOT_IN_VERIFIED_TEMPLATE");
        CellRangeAddress range = CellRangeAddress.valueOf(request.targetRange());
        Map<String, String> changed = new LinkedHashMap<>();
        byte[] output = sourceBytes;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) throw blocked("FIELD_VALUE_BLANK:" + entry.getKey());
                var offset = profile.fields().get(entry.getKey());
                int row = range.getFirstRow() + offset.rowOffset();
                int column = range.getFirstColumn() + offset.columnOffset();
                if (row > range.getLastRow() || column > range.getLastColumn()) throw blocked("PROFILE_FIELD_OUTSIDE_TARGET");
                Row targetRow = sheet.getRow(row);
                Cell cell = targetRow == null ? null : targetRow.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                        || (offset.expectedStyleId() != null && cell.getCellStyle().getIndex() != offset.expectedStyleId())) {
                    throw blocked("TARGET_FIELD_STRUCTURE_MISMATCH:" + entry.getKey());
                }
                String address = new CellReference(row, column).formatAsString().replace("$", "");
                String expectedText = new DataFormatter(Locale.ROOT).formatCellValue(cell);
                if (!expectedText.isBlank()) throw blocked("TARGET_FIELD_NO_LONGER_BLANK:" + address);
                output = parts.replaceCellText(output, request.sheetName(), address, expectedText, value);
                changed.put(address, entry.getKey());
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to write reserved-range TEST_ONLY values", exception);
        }
        validateOutput(sourceBytes, output, request, changed, fieldValues, preflight.actualSourceSha256());
        return new EditResult(output, preflight.actualSourceSha256(), sha256(output), changed,
                "TEST_ONLY", "REVIEW_REQUIRED", "Reserved preformatted cells only; no physical connection or new shape was created.");
    }

    /** Position-aware MODIFY handoff. The existing Profile engine remains the authorization and edit guard. */
    public DiagramBoxChangeEngine.ProfileModifyResult modifyMappedFieldsTestOnly(
            byte[] sourceBytes, DiagramLayoutMapping mapping, EquipmentBoxProfile profile,
            Map<String, String> expectedCurrentValues, Map<String, String> replacements,
            DiagramBoxChangeEngine changeEngine) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(changeEngine, "changeEngine");
        String actualSha = sha256(sourceBytes);
        if (!actualSha.equalsIgnoreCase(mapping.sourceSha256())) throw blocked("MAPPING_SOURCE_SHA_MISMATCH");
        if (mapping.placementStatus() != DiagramLayoutMapping.PlacementStatus.VERIFIED
                || mapping.evidenceStatus() != DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED)
            throw blocked("LOCATION_NOT_VERIFIED_FOR_MODIFY");
        if (mapping.equipmentId() == null || mapping.sheetName() == null || mapping.processId() == null
                || mapping.identifierCell() == null || mapping.boxRange() == null)
            throw blocked("LOCATION_MAPPING_INCOMPLETE");
        try {
            CellRangeAddress mappedBox = CellRangeAddress.valueOf(mapping.boxRange().replace("$", ""));
            String mappedTopLeft = new CellReference(mappedBox.getFirstRow(), mappedBox.getFirstColumn())
                    .formatAsString().replace("$", "");
            if (!mappedTopLeft.equalsIgnoreCase(mapping.identifierCell().replace("$", "")))
                throw blocked("MAPPING_IDENTIFIER_CELL_MISMATCH");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw blocked("MAPPING_BOX_RANGE_INVALID");
        }
        if (!Objects.equals(profile.processCode(), mapping.processId())
                || !Objects.equals(profile.sheetName(), mapping.sheetName())) throw blocked("PROFILE_LOCATION_IDENTITY_MISMATCH");
        var result = changeEngine.modifyProfileFieldsTestOnly(sourceBytes, mapping.processId(), mapping.sheetName(),
                mapping.equipmentId(), profile, profile.sourceSha256(), actualSha, expectedCurrentValues, replacements);
        String actualBox = result.mapping().boxTopLeft() + ":" + result.mapping().boxBottomRight();
        if (!actualBox.equalsIgnoreCase(mapping.boxRange().replace("$", ""))) throw blocked("MAPPING_BOX_GEOMETRY_MISMATCH");
        if (!result.mapping().boxTopLeft().equalsIgnoreCase(mapping.identifierCell())
                || !result.mapping().managementNo().equals(mapping.equipmentId()))
            throw blocked("MAPPING_IDENTIFIER_IDENTITY_MISMATCH");
        return result;
    }

    private void validateOutput(byte[] before, byte[] after, Request request, Map<String, String> changed,
                                Map<String, String> fieldValues, String sourceSha) {
        if (!sourceSha.equals(sha256(before))) throw blocked("SOURCE_MUTATED");
        try (Workbook oldWorkbook = WorkbookFactory.create(new ByteArrayInputStream(before));
             Workbook newWorkbook = WorkbookFactory.create(new ByteArrayInputStream(after))) {
            var oldSheet = oldWorkbook.getSheet(request.sheetName());
            var newSheet = newWorkbook.getSheet(request.sheetName());
            if (!oldSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList()
                    .equals(newSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList())) throw blocked("MERGED_RANGES_CHANGED");
            int rows = Math.max(oldSheet.getLastRowNum(), newSheet.getLastRowNum());
            for (int r = 0; r <= rows; r++) {
                Row oldRow = oldSheet.getRow(r), newRow = newSheet.getRow(r);
                if (oldRow != null && newRow != null && oldRow.getHeight() != newRow.getHeight()) throw blocked("ROW_HEIGHT_CHANGED");
                int cols = Math.max(oldRow == null ? 0 : oldRow.getLastCellNum(), newRow == null ? 0 : newRow.getLastCellNum());
                for (int c = 0; c < cols; c++) {
                    Cell oldCell = oldRow == null ? null : oldRow.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    Cell newCell = newRow == null ? null : newRow.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    String address = new CellReference(r, c).formatAsString().replace("$", "");
                    if (changed.containsKey(address)) {
                        if (newCell == null || oldCell == null || oldCell.getCellStyle().getIndex() != newCell.getCellStyle().getIndex())
                            throw blocked("EDITED_CELL_STYLE_CHANGED:" + address);
                        continue;
                    }
                    String oldValue = oldCell == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(oldCell);
                    String newValue = newCell == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(newCell);
                    if (!oldValue.equals(newValue)) throw blocked("NON_TARGET_CELL_CHANGED:" + address);
                    if (oldCell != null && newCell != null && oldCell.getCellStyle().getIndex() != newCell.getCellStyle().getIndex())
                        throw blocked("NON_TARGET_STYLE_CHANGED:" + address);
                }
            }
            int lastColumn = 0;
            for (Row row : oldSheet) if (row.getLastCellNum() > lastColumn) lastColumn = row.getLastCellNum();
            for (Row row : newSheet) if (row.getLastCellNum() > lastColumn) lastColumn = row.getLastCellNum();
            for (int column = 0; column < lastColumn; column++)
                if (oldSheet.getColumnWidth(column) != newSheet.getColumnWidth(column)) throw blocked("COLUMN_WIDTH_CHANGED:" + column);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            CellRangeAddress target = CellRangeAddress.valueOf(request.targetRange());
            for (var entry : fieldValues.entrySet()) {
                var field = request.templateProfile().fields().get(entry.getKey());
                Cell actual = newSheet.getRow(target.getFirstRow() + field.rowOffset())
                        .getCell(target.getFirstColumn() + field.columnOffset());
                if (!entry.getValue().equals(formatter.formatCellValue(actual))) throw blocked("OUTPUT_VALUE_MISMATCH:" + entry.getKey());
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("TEST_ONLY output failed POI reopen validation", exception);
        }
        WorkbookInventory oldInventory = inspector.inspect(before), newInventory = inspector.inspect(after);
        if (oldInventory.sheets().size() != newInventory.sheets().size()
                || oldInventory.shapeCount() != newInventory.shapeCount()
                || oldInventory.connectorCount() != newInventory.connectorCount()
                || oldInventory.pictureCount() != newInventory.pictureCount()) throw blocked("DRAWINGML_OR_RELATIONSHIP_INVENTORY_CHANGED");
        for (int index = 0; index < oldInventory.sheets().size(); index++) {
            var oldSheet = oldInventory.sheets().get(index);
            var newSheet = newInventory.sheets().get(index);
            if (!Objects.equals(oldSheet.worksheetPartName(), newSheet.worksheetPartName())
                    || !Objects.equals(oldSheet.drawingPartName(), newSheet.drawingPartName())
                    || !oldSheet.drawingObjects().equals(newSheet.drawingObjects()))
                throw blocked("DRAWINGML_OR_RELATIONSHIP_INVENTORY_CHANGED:" + oldSheet.sheetName());
        }
        var sheetInventory = oldInventory.sheets().stream().filter(item -> request.sheetName().equals(item.sheetName())).findFirst().orElseThrow();
        if (sheetInventory.drawingPartName() != null && !java.util.Arrays.equals(
                parts.readPart(before, sheetInventory.drawingPartName()), parts.readPart(after, sheetInventory.drawingPartName())))
            throw blocked("DRAWINGML_PART_CHANGED");
        if (!sourceSha.equals(sha256(before))) throw blocked("SOURCE_SHA_CHANGED");
    }

    private List<String> verifyTemplate(Request request, String actualSha, Workbook workbook,
                                       org.apache.poi.ss.usermodel.Sheet sheet, CellRangeAddress target) {
        List<String> errors = new ArrayList<>();
        EquipmentBoxProfile profile = request.templateProfile();
        if (profile == null) return List.of("TEMPLATE_PROFILE_MISSING");
        if (!profile.appliesTo(request.processId(), request.sheetName(), request.sourceSha256())) errors.add("TEMPLATE_PROFILE_IDENTITY_MISMATCH");
        if (!profile.matchesWorkingCopySha(actualSha)) errors.add("TEMPLATE_WORKING_SHA_MISMATCH");
        if (!profile.enabledForGenericAdd()) errors.add("TEMPLATE_PROFILE_NOT_ENABLED");
        if (request.mapping() != null && request.mapping().boxRange() != null) {
            try {
                CellRangeAddress mapped = CellRangeAddress.valueOf(request.mapping().boxRange());
                if (rowCount(mapped) != rowCount(target) || columnCount(mapped) != columnCount(target))
                    errors.add("TEMPLATE_DIMENSION_MISMATCH");
            } catch (RuntimeException exception) { errors.add("MAPPING_BOX_RANGE_INVALID"); }
        }
        for (var entry : profile.fields().entrySet()) {
            var field = entry.getValue();
            int row = target.getFirstRow() + field.rowOffset(), col = target.getFirstColumn() + field.columnOffset();
            if (field.rowOffset() < 0 || field.columnOffset() < 0 || row > target.getLastRow() || col > target.getLastColumn()) {
                errors.add("TEMPLATE_FIELD_OUTSIDE_TARGET:" + entry.getKey());
                continue;
            }
            Row targetRow = sheet.getRow(row);
            Cell cell = targetRow == null ? null : targetRow.getCell(col, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
            if (cell == null || field.expectedStyleId() != null && cell.getCellStyle().getIndex() != field.expectedStyleId())
                errors.add("TEMPLATE_STYLE_MISMATCH:" + entry.getKey());
        }
        return errors;
    }

    private List<String> findDuplicateEquipmentIds(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String id) {
        List<String> result = new ArrayList<>();
        for (Row row : sheet) for (Cell cell : row) if (id.equals(formatter.formatCellValue(cell).trim()))
            result.add(cell.getAddress().formatAsString().replace("$", ""));
        return result;
    }

    private List<String> findExistingEquipmentOverlaps(org.apache.poi.ss.usermodel.Sheet sheet,
                                                        CellRangeAddress target, String newId, DataFormatter formatter) {
        Set<String> ids = new LinkedHashSet<>();
        for (Row row : sheet) for (Cell cell : row) {
            String value = formatter.formatCellValue(cell).trim();
            if (!value.equals(newId) && EQUIPMENT_ID.matcher(value).matches()) ids.add(value);
        }
        List<String> overlaps = new ArrayList<>();
        for (String id : ids) for (EquipmentBoxGeometryLocator.Box box : geometry.locateByManagementNo(sheet, id)) {
            if (target.getLastRow() >= box.firstRow() - 1 && target.getFirstRow() <= box.lastRow() - 1
                    && target.getLastColumn() >= box.firstColumn() && target.getFirstColumn() <= box.lastColumn()) overlaps.add(id);
        }
        return overlaps.stream().distinct().toList();
    }

    private boolean drawingIntersects(WorkbookInventory.DrawingObject object, CellRangeAddress target) {
        int top = Math.min(object.startRow(), object.endRow());
        int bottom = Math.max(object.startRow(), object.endRow());
        int left = Math.min(object.startColumn(), object.endColumn());
        int right = Math.max(object.startColumn(), object.endColumn());
        return bottom >= target.getFirstRow() && top <= target.getLastRow()
                && right >= target.getFirstColumn() && left <= target.getLastColumn();
    }

    private int rowCount(CellRangeAddress range) { return range.getLastRow() - range.getFirstRow() + 1; }

    private int columnCount(CellRangeAddress range) { return range.getLastColumn() - range.getFirstColumn() + 1; }

    private boolean intersects(CellRangeAddress left, CellRangeAddress right) {
        return left.getLastRow() >= right.getFirstRow() && left.getFirstRow() <= right.getLastRow()
                && left.getLastColumn() >= right.getFirstColumn() && left.getFirstColumn() <= right.getLastColumn();
    }

    private CellRangeAddress parseRange(String address, List<String> blockers) {
        try {
            if (address == null || address.isBlank() || address.contains("!")) throw new IllegalArgumentException();
            CellRangeAddress range = CellRangeAddress.valueOf(address.replace("$", ""));
            if (range.getFirstRow() < 0 || range.getFirstColumn() < 0 || range.getLastRow() < range.getFirstRow()
                    || range.getLastColumn() < range.getFirstColumn()) throw new IllegalArgumentException();
            return range;
        } catch (RuntimeException exception) {
            blockers.add("TARGET_RANGE_INVALID");
            return null;
        }
    }

    private Result result(String sha, Request request, CellRangeAddress target, List<String> blockers,
                          List<String> reviews, List<String> drawings, List<String> connectors, boolean ready) {
        String status = !blockers.isEmpty() ? "BLOCKED" : !reviews.isEmpty() ? "REVIEW_REQUIRED" : "VERIFIED";
        return new Result(request.processId(), request.sheetName(), request.targetRange(), sha, status,
                List.copyOf(new LinkedHashSet<>(blockers)), List.copyOf(new LinkedHashSet<>(reviews)),
                List.copyOf(drawings), List.copyOf(connectors), ready && target != null,
                "TEST_ONLY layout values only; physical connection, shape creation and operational authorization are not implied");
    }

    private BoxAddResult boxAddResult(String sha, BoxAddRequest request, CellRangeAddress target,
                                      List<String> blockers, List<String> reviews, List<String> drawings,
                                      List<String> connectors, boolean ready) {
        String status = !blockers.isEmpty() ? "BLOCKED" : !reviews.isEmpty() ? "REVIEW_REQUIRED" : "CELL_LAYOUT_BOX_ADD_TEST_ONLY";
        return new BoxAddResult(request.processId(), request.sheetName(), request.templateRange(), request.targetRange(),
                sha, status, List.copyOf(new LinkedHashSet<>(blockers)), List.copyOf(new LinkedHashSet<>(reviews)),
                List.copyOf(drawings), List.copyOf(connectors), ready && target != null,
                "CELL_LAYOUT_BOX_ADD_TEST_ONLY only; existing rows/columns and all DrawingML objects stay in place; no physical connection or production authorization is implied");
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private InvalidWorkbookException blocked(String reason) { return new InvalidWorkbookException(reason); }

    public record Request(String processId, String sheetName, String targetRange, String equipmentId,
                          String equipmentName, int rowsRequired, int columnsRequired, String sourceSha256,
                          DiagramLayoutMapping mapping, EquipmentBoxProfile templateProfile) { }

    public record BoxAddRequest(String processId, String sheetName, String templateRange, String targetRange,
                                String equipmentId, String equipmentName, String sourceSha256,
                                DiagramLayoutMapping mapping, EquipmentBoxProfile templateProfile) { }

    public record BoxAddResult(String processId, String sheetName, String templateRange, String targetRange,
                               String actualSourceSha256, String status, List<String> blockers,
                               List<String> reviewReasons, List<String> drawingConflicts,
                               List<String> connectorCrossings, boolean testOnlyReady, String scope) { }

    public record Result(String processId, String sheetName, String targetRange, String actualSourceSha256,
                         String status, List<String> blockers, List<String> reviewReasons,
                         List<String> drawingConflicts, List<String> connectorCrossings,
                         boolean testOnlyReady, String scope) { }

    public record EditResult(byte[] workbookBytes, String sourceSha256, String outputSha256,
                             Map<String, String> changedCells, String mode, String relationStatus, String note) {
        public EditResult { workbookBytes = workbookBytes.clone(); changedCells = Map.copyOf(changedCells); }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }
}
