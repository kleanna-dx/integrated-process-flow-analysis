package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** Read-only structural inventory for official process diagrams; every proposed location stays review-only. */
@Component
public class ProcessDiagramStructureAnalyzer {
    private static final Pattern EQUIPMENT_ID = Pattern.compile(
            "(?i)\\b(?:[A-Z]{1,4}-[A-Z]{1,5}\\d{2,8}|[A-Z]{1,4}-\\d{2,8})(?:[-.][A-Z0-9]{1,8})*\\b");
    private final OoxmlPackageInspector packageInspector;
    private final Pw0105EquipmentInsertionEngine boxLocator;

    public ProcessDiagramStructureAnalyzer(OoxmlPackageInspector packageInspector,
                                          Pw0105EquipmentInsertionEngine boxLocator) {
        this.packageInspector = packageInspector;
        this.boxLocator = boxLocator;
    }

    public WorkbookReport analyze(String processCode, String filename, String expectedSha256, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return analyzeInternal(processCode, filename, expectedSha256, bytes, null);
    }

    /** Analyze one requested worksheet while keeping package-wide drawing summary metadata. */
    public WorkbookReport analyzeSheet(String processCode, String filename, String expectedSha256,
                                       byte[] bytes, String sheetName) {
        Objects.requireNonNull(sheetName, "sheetName");
        if (sheetName.isBlank()) throw new IllegalArgumentException("sheetName must not be blank");
        Objects.requireNonNull(bytes, "bytes");
        return analyzeInternal(processCode, filename, expectedSha256, bytes, sheetName);
    }

    private WorkbookReport analyzeInternal(String processCode, String filename, String expectedSha256,
                                           byte[] bytes, String onlySheetName) {
        String sha256 = sha256(bytes);
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new InvalidWorkbookException("Official workbook hash mismatch for " + filename);
        }
        Map<String, WorkbookInventory.SheetInventory> drawingBySheet = new LinkedHashMap<>();
        WorkbookInventory inventory;
        if (onlySheetName == null) {
            inventory = packageInspector.inspect(bytes);
            inventory.sheets().forEach(sheet -> drawingBySheet.put(sheet.sheetName(), sheet));
        } else {
            var targetSheet = packageInspector.inspectSheet(bytes, onlySheetName);
            drawingBySheet.put(onlySheetName, targetSheet);
            inventory = new WorkbookInventory(List.of(targetSheet), targetSheet.drawingPartName() == null ? 0 : 1,
                    (int) targetSheet.drawingObjects().stream().filter(object -> "SHAPE".equals(object.objectType())).count(),
                    (int) targetSheet.drawingObjects().stream().filter(object -> "CONNECTOR".equals(object.objectType())).count(),
                    (int) targetSheet.drawingObjects().stream().filter(object -> "PICTURE".equals(object.objectType())).count(),
                    (int) targetSheet.drawingObjects().stream().filter(object -> !List.of("SHAPE", "CONNECTOR", "PICTURE")
                            .contains(object.objectType())).count(),
                    (int) targetSheet.drawingObjects().stream().filter(object -> object.startConnectedShapeId() != null
                            || object.endConnectedShapeId() != null).count());
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (onlySheetName != null && workbook.getSheet(onlySheetName) == null) {
                throw new InvalidWorkbookException("Target worksheet is missing: " + onlySheetName);
            }
            List<SheetReport> sheets = new ArrayList<>();
            List<EquipmentCandidate> equipment = new ArrayList<>();
            List<LocationCandidate> locations = new ArrayList<>();
            int nonEmptyCellCount = 0;
            int mergedRangeCount = 0;
            int formulaCellCount = 0;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                if (onlySheetName != null && !onlySheetName.equals(sheet.getSheetName())) continue;
                WorkbookInventory.SheetInventory drawing = drawingBySheet.get(sheet.getSheetName());
                SheetScan scan = scanSheet(sheet, index, drawing, processCode, workbook.getSheetVisibility(index).name());
                sheets.add(scan.report());
                equipment.addAll(scan.equipment());
                locations.addAll(scan.locations());
                nonEmptyCellCount += scan.report().nonEmptyCellCount();
                mergedRangeCount += scan.report().mergedRanges().size();
                formulaCellCount += scan.report().formulaCellCount();
            }
            return new WorkbookReport(processCode, filename, sha256, bytes.length,
                    onlySheetName == null ? workbook.getNumberOfSheets() : sheets.size(),
                    nonEmptyCellCount, mergedRangeCount, formulaCellCount, inventory.drawingPartCount(),
                    inventory.shapeCount(), inventory.connectorCount(), inventory.pictureCount(),
                    inventory.otherObjectCount(), inventory.explicitConnectorBindings(),
                    List.copyOf(sheets), List.copyOf(equipment), List.copyOf(locations),
                    "READ_ONLY_STRUCTURE_ANALYSIS; no editability or physical process connection is inferred");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inventory official workbook " + filename, exception);
        }
    }

    private SheetScan scanSheet(Sheet sheet, int sheetIndex, WorkbookInventory.SheetInventory drawing,
                                String processCode, String visibility) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = -1;
        int maxCol = -1;
        int nonEmpty = 0;
        int formulas = 0;
        int styled = 0;
        int customHeightRows = 0;
        int hiddenRows = 0;
        Set<Integer> usedColumns = new LinkedHashSet<>();
        Map<Integer, Integer> styleCounts = new TreeMap<>();
        List<RowProfile> rowProfiles = new ArrayList<>();
        Map<String, List<String>> equipmentCellAddresses = new LinkedHashMap<>();

        for (Row row : sheet) {
            int rowNonEmpty = 0;
            int rowMinColumn = Integer.MAX_VALUE;
            int rowMaxColumn = -1;
            for (Cell cell : row) {
                String value = ExcelCellText.format(cell, formatter);
                if (value == null || value.isBlank()) continue;
                nonEmpty++;
                rowNonEmpty++;
                int rowNumber = row.getRowNum() + 1;
                int column = cell.getColumnIndex();
                minRow = Math.min(minRow, rowNumber);
                maxRow = Math.max(maxRow, rowNumber);
                minCol = Math.min(minCol, column);
                maxCol = Math.max(maxCol, column);
                rowMinColumn = Math.min(rowMinColumn, column);
                rowMaxColumn = Math.max(rowMaxColumn, column);
                usedColumns.add(column);
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) formulas++;
                int style = cell.getCellStyle() == null ? 0 : cell.getCellStyle().getIndex();
                if (style != 0) styled++;
                styleCounts.merge(style, 1, Integer::sum);
                String displayed = value.trim();
                Matcher matcher = EQUIPMENT_ID.matcher(displayed);
                if (matcher.matches()) {
                    String candidate = displayed.toUpperCase(Locale.ROOT);
                    equipmentCellAddresses.computeIfAbsent(candidate, ignored -> new ArrayList<>())
                            .add(cell.getAddress().formatAsString());
                }
            }
            if (rowNonEmpty > 0) {
                float height = row.getHeightInPoints();
                if (row.getZeroHeight()) hiddenRows++;
                if (row.getZeroHeight() || Math.abs(height - sheet.getDefaultRowHeightInPoints()) > 0.01f) customHeightRows++;
                rowProfiles.add(new RowProfile(row.getRowNum() + 1, rowMinColumn, rowMaxColumn,
                        rowNonEmpty, height, row.getZeroHeight()));
            }
        }

        List<String> mergedRanges = sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList();
        List<ColumnProfile> columnProfiles = collectColumnProfiles(sheet, usedColumns);
        WorkbookInventory.SheetInventory safeDrawing = drawing == null
                ? new WorkbookInventory.SheetInventory(sheetIndex + 1, sheet.getSheetName(), "visible", 0, 0,
                        null, null, List.of(), List.of()) : drawing;
        List<EquipmentCandidate> equipment = mapEquipment(sheet, equipmentCellAddresses, mergedRanges, formatter);
        List<LocationCandidate> locations = mapLocations(sheet, equipment, safeDrawing, mergedRanges, processCode);
        String usedRange = minRow == Integer.MAX_VALUE ? "EMPTY"
                : new CellReference(minRow - 1, minCol).formatAsString().replace("$", "") + ":"
                + new CellReference(maxRow - 1, maxCol).formatAsString().replace("$", "");
        SheetReport report = new SheetReport(sheetIndex + 1, sheet.getSheetName(), visibility,
                usedRange, nonEmpty, sheet.getPhysicalNumberOfRows(), maxRow, maxCol,
                formulas, styled, Map.copyOf(styleCounts), List.copyOf(mergedRanges),
                customHeightRows, hiddenRows, columnProfiles, List.copyOf(rowProfiles),
                safeDrawing.drawingPartName(), safeDrawing.drawingObjects().size(),
                (int) safeDrawing.drawingObjects().stream().filter(object -> "SHAPE".equals(object.objectType())).count(),
                (int) safeDrawing.drawingObjects().stream().filter(object -> "CONNECTOR".equals(object.objectType())).count(),
                (int) safeDrawing.drawingObjects().stream().filter(object -> "PICTURE".equals(object.objectType())).count(),
                (int) safeDrawing.drawingObjects().stream().filter(object -> object.startConnectedShapeId() != null
                        || object.endConnectedShapeId() != null).count(), safeDrawing.drawingObjects().stream()
                        .map(this::drawingSummary).toList(), equipment.size(), locations.size());
        return new SheetScan(report, equipment, locations);
    }

    private List<ColumnProfile> collectColumnProfiles(Sheet sheet, Set<Integer> usedColumns) {
        Set<Integer> indexes = new java.util.TreeSet<>(usedColumns);
        int lastUsed = indexes.isEmpty() ? -1 : indexes.stream().mapToInt(Integer::intValue).max().orElse(-1);
        for (int column = 0; column <= lastUsed; column++) {
            int width = sheet.getColumnWidth(column);
            if (sheet.isColumnHidden(column) || width != sheet.getDefaultColumnWidth() * 256) indexes.add(column);
        }
        List<ColumnProfile> profiles = new ArrayList<>();
        for (int column : indexes) {
            profiles.add(new ColumnProfile(column, CellReference.convertNumToColString(column),
                    sheet.getColumnWidth(column), sheet.getColumnWidthInPixels(column), sheet.isColumnHidden(column)));
        }
        return List.copyOf(profiles);
    }

    private List<EquipmentCandidate> mapEquipment(Sheet sheet, Map<String, List<String>> identifiers,
                                                   List<String> mergedRanges, DataFormatter formatter) {
        List<EquipmentCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : identifiers.entrySet()) {
            List<Pw0105EquipmentInsertionEngine.EquipmentBox> boxes;
            try {
                boxes = boxLocator.locateExistingBoxesByManagementNo(sheet, entry.getKey());
            } catch (RuntimeException ignored) {
                boxes = List.of();
            }
            if (boxes.size() == 1) {
                var box = boxes.get(0);
                CellReference top = new CellReference(box.topLeftCell());
                CellReference bottom = new CellReference(box.bottomRightCell());
                String facilityName = readCell(sheet, box.facilityNameRow() - 1, top.getCol(), formatter);
                Map<Integer, Integer> boxStyles = new TreeMap<>();
                for (int rowIndex = top.getRow(); rowIndex <= bottom.getRow(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    for (int col = top.getCol(); col <= bottom.getCol(); col++) {
                        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null) boxStyles.merge((int) cell.getCellStyle().getIndex(), 1, Integer::sum);
                    }
                }
                List<String> overlappingMerges = mergedRanges.stream().filter(range -> overlaps(range, top, bottom)).toList();
                result.add(new EquipmentCandidate(sheet.getSheetName(), entry.getKey(), entry.getValue(),
                        box.topLeftCell(), box.bottomRightCell(), facilityName, box.firstColumn(), box.firstRow(),
                        box.lastColumn(), box.lastRow(), Map.copyOf(boxStyles), overlappingMerges,
                        "BOX_CANDIDATE_REVIEW_REQUIRED", "Pattern match and style geometry only; no master ID or physical connection is asserted"));
            } else {
                result.add(new EquipmentCandidate(sheet.getSheetName(), entry.getKey(), entry.getValue(),
                        null, null, "", null, null, null, null, Map.of(), List.of(),
                        boxes.isEmpty() ? "TEXT_ONLY_REVIEW_REQUIRED" : "AMBIGUOUS_BOX_REVIEW_REQUIRED",
                        boxes.isEmpty() ? "Equipment-like text was found but no box met the current geometric heuristic"
                                : "More than one box matched the same identifier; a unique target is not established"));
            }
        }
        return List.copyOf(result);
    }

    private List<LocationCandidate> mapLocations(Sheet sheet, List<EquipmentCandidate> equipment,
                                                  WorkbookInventory.SheetInventory drawing,
                                                  List<String> mergedRanges, String processCode) {
        List<EquipmentCandidate> boxes = equipment.stream().filter(candidate -> candidate.topLeftCell() != null)
                .sorted(Comparator.comparing(EquipmentCandidate::firstColumn)
                        .thenComparing(EquipmentCandidate::lastColumn).thenComparing(EquipmentCandidate::firstRow))
                .toList();
        List<LocationCandidate> result = new ArrayList<>();
        for (int index = 1; index < boxes.size(); index++) {
            EquipmentCandidate source = boxes.get(index - 1);
            EquipmentCandidate target = boxes.get(index);
            if (!Objects.equals(source.firstColumn(), target.firstColumn())
                    || !Objects.equals(source.lastColumn(), target.lastColumn())) continue;
            if (target.firstRow() < source.lastRow()) continue;
            int gapRows = target.firstRow() - source.lastRow() - 1;
            String candidateRange = new CellReference(Math.max(0, source.lastRow()), source.firstColumn()).formatAsString()
                    .replace("$", "") + ":" + new CellReference(Math.max(0, target.firstRow() - 2), target.lastColumn())
                    .formatAsString().replace("$", "");
            List<String> mergeOverlaps = mergedRanges.stream().filter(range -> overlapsRange(range,
                    Math.max(0, source.lastRow()), Math.max(0, target.firstRow() - 2),
                    source.firstColumn(), source.lastColumn())).toList();
            int drawingObjectsInBand = (int) drawing.drawingObjects().stream().filter(object -> object.startRow() != null
                    && object.endRow() != null && object.startColumn() != null && object.endColumn() != null
                    && spansBand(object, source.lastRow(), target.firstRow() - 1,
                            source.firstColumn(), source.lastColumn())).count();
            Set<String> reasons = new LinkedHashSet<>();
            reasons.add("VISUAL_POSITION_IS_NOT_PHYSICAL_PIPELINE_EVIDENCE");
            reasons.add("EXACT_LABEL_AND_MASTER_FIELD_MAPPING_REQUIRES_REVIEW");
            if (gapRows < 0) reasons.add("EQUIPMENT_BOXES_OVERLAP_OR_TOUCH");
            if (!mergeOverlaps.isEmpty()) reasons.add("MERGED_CELLS_OVERLAP_CANDIDATE_BAND");
            if (drawing.drawingPartName() == null) reasons.add("DRAWINGML_PART_MISSING");
            if (drawingObjectsInBand > 0) reasons.add("DRAWINGML_OBJECTS_CROSS_CANDIDATE_BAND");
            result.add(new LocationCandidate(locationId(processCode, sheet.getSheetName(), source.equipmentIdCandidate(), target.equipmentIdCandidate()),
                    processCode, sheet.getSheetName(), source.equipmentIdCandidate(), target.equipmentIdCandidate(), candidateRange,
                    gapRows, source.boxHeight(), drawing.drawingPartName(),
                    (int) drawing.drawingObjects().stream().filter(object -> "CONNECTOR".equals(object.objectType())).count(),
                    (int) drawing.drawingObjects().stream().filter(object -> object.startConnectedShapeId() != null
                            || object.endConnectedShapeId() != null).count(), drawingObjectsInBand, mergeOverlaps,
                    false, "REVIEW_REQUIRED", List.copyOf(reasons),
                    mergeOverlaps.isEmpty() && drawingObjectsInBand == 0 ? "REVIEW_REQUIRED" : "UNSUPPORTED",
                    "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE",
                    mergeOverlaps.isEmpty() && drawingObjectsInBand == 0
                            ? "ENGINE_TEMPLATE_AND_BOUNDARY_ANALYSIS_REQUIRED"
                            : "MERGED_OR_DRAWING_OBJECTS_OVERLAP_CANDIDATE"));
        }
        return List.copyOf(result);
    }

    private DrawingSummary drawingSummary(WorkbookInventory.DrawingObject object) {
        return new DrawingSummary(object.objectIndex(), object.objectType(), object.shapeId(), object.objectName(),
                object.textContent(), object.geometryPreset(), object.startColumn(), object.startRow(),
                object.startColumnOffset(), object.startRowOffset(), object.endColumn(), object.endRow(),
                object.endColumnOffset(), object.endRowOffset(), object.startConnectedShapeId(),
                object.endConnectedShapeId(), object.headArrowType(), object.tailArrowType(),
                object.transformXEmu(), object.transformYEmu(), object.transformWidthEmu(), object.transformHeightEmu(),
                object.startAnchorXEmu(), object.startAnchorYEmu(), object.endAnchorXEmu(), object.endAnchorYEmu());
    }

    private boolean spansBand(WorkbookInventory.DrawingObject object, int sourceRow, int targetRow,
                              int firstColumn, int lastColumn) {
        int top = Math.min(object.startRow(), object.endRow());
        int bottom = Math.max(object.startRow(), object.endRow());
        int left = Math.min(object.startColumn(), object.endColumn());
        int right = Math.max(object.startColumn(), object.endColumn());
        return bottom >= sourceRow && top <= targetRow && right >= firstColumn && left <= lastColumn;
    }

    private boolean overlaps(String range, CellReference top, CellReference bottom) {
        try {
            CellRangeAddress merged = CellRangeAddress.valueOf(range);
            return merged.getLastRow() >= top.getRow() && merged.getFirstRow() <= bottom.getRow()
                    && merged.getLastColumn() >= top.getCol() && merged.getFirstColumn() <= bottom.getCol();
        } catch (RuntimeException ignored) { return false; }
    }

    private boolean overlapsRange(String range, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        try {
            CellRangeAddress merged = CellRangeAddress.valueOf(range);
            return merged.getLastRow() >= firstRow && merged.getFirstRow() <= lastRow
                    && merged.getLastColumn() >= firstColumn && merged.getFirstColumn() <= lastColumn;
        } catch (RuntimeException ignored) { return false; }
    }

    private String readCell(Sheet sheet, int rowIndex, int colIndex, DataFormatter formatter) {
        if (rowIndex < 0) return "";
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private String locationId(String process, String sheet, String source, String target) {
        return safeId(process) + "|" + safeId(sheet) + "|" + safeId(source) + "->" + safeId(target);
    }

    private String safeId(String value) { return value == null ? "UNKNOWN" : value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record SheetScan(SheetReport report, List<EquipmentCandidate> equipment,
                             List<LocationCandidate> locations) { }

    public record WorkbookReport(String processCode, String filename, String sha256, int byteSize, int sheetCount,
                                 int nonEmptyCellCount, int mergedRangeCount, int formulaCellCount,
                                 int drawingPartCount, int shapeCount, int connectorCount, int pictureCount,
                                 int otherObjectCount, int explicitConnectorBindings, List<SheetReport> sheets,
                                 List<EquipmentCandidate> equipmentCandidates, List<LocationCandidate> locationCandidates,
                                 String interpretationPolicy) { }

    public record SheetReport(int sheetIndex, String sheetName, String visibility, String usedRange,
                              int nonEmptyCellCount, int physicalRowCount, int usedLastRow, int usedLastColumn,
                              int formulaCellCount, int styledNonEmptyCellCount, Map<Integer, Integer> styleUsage,
                              List<String> mergedRanges, int customHeightRows, int hiddenRows,
                              List<ColumnProfile> columns, List<RowProfile> nonEmptyRows, String drawingPart,
                              int drawingObjectCount, int shapeCount, int connectorCount, int pictureCount,
                              int explicitBindingObjectCount, List<DrawingSummary> drawingObjects,
                              int equipmentIdentifierCandidates, int locationCandidates) { }

    public record DrawingSummary(int objectIndex, String objectType, String shapeId, String objectName,
                                 String textContent, String geometryPreset, Integer startColumn, Integer startRow,
                                 Long startColumnOffset, Long startRowOffset, Integer endColumn, Integer endRow,
                                 Long endColumnOffset, Long endRowOffset, String startConnectedShapeId,
                                 String endConnectedShapeId, String headArrowType, String tailArrowType,
                                 Long transformXEmu, Long transformYEmu, Long transformWidthEmu, Long transformHeightEmu,
                                 Long startAnchorXEmu, Long startAnchorYEmu, Long endAnchorXEmu, Long endAnchorYEmu) { }

    public record ColumnProfile(int zeroBasedIndex, String columnLetter, int widthUnits,
                                float widthPixels, boolean hidden) { }

    public record RowProfile(int rowNumber, int firstNonEmptyColumn, int lastNonEmptyColumn,
                             int nonEmptyCellCount, float heightPoints, boolean hidden) { }

    public record EquipmentCandidate(String sheetName, String equipmentIdCandidate, List<String> idTextCells,
                                      String topLeftCell, String bottomRightCell, String facilityNameCandidate,
                                      Integer firstColumn, Integer firstRow, Integer lastColumn, Integer lastRow,
                                      Map<Integer, Integer> styleUsage, List<String> overlappingMergedRanges,
                                      String status, String reason) {
        public int boxHeight() { return firstRow == null || lastRow == null ? 0 : lastRow - firstRow + 1; }
    }

    public record LocationCandidate(String locationId, String processCode, String sheetName,
                                    String precedingEquipmentCandidate, String followingEquipmentCandidate,
                                    String candidateCellRange, int gapRows, int sourceBoxHeight,
                                    String drawingPart, int connectorCount, int explicitConnectorBindings,
                                    int drawingObjectsCrossingBand, List<String> overlappingMergedRanges,
                                    boolean automaticEditAllowed, String status, List<String> reviewReasons,
                                    String editabilityStatus, String connectionEvidenceStatus, String editabilityReason) { }
}
