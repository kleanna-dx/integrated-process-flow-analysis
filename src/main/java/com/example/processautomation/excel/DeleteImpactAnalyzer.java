package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** Profile-bound DELETE assessment. SAFE is deliberately restricted to explicit synthetic-only policies. */
@Component
public class DeleteImpactAnalyzer {
    private static final String CONNECTION_UNVERIFIED = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";
    private static final Set<String> SYNTHETIC_DELETE_POLICIES = Set.of(
            "BOX_ONLY_SYNTHETIC_TEST_ONLY", "BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY");
    private final EquipmentBoxGeometryLocator geometry;
    private final ProcessDiagramStructureAnalyzer structure;
    private final OoxmlPackageInspector inspector;
    private final DiagramBoxChangeEngine boxEngine;

    public DeleteImpactAnalyzer(EquipmentBoxGeometryLocator geometry, ProcessDiagramStructureAnalyzer structure,
                                OoxmlPackageInspector inspector, DiagramBoxChangeEngine boxEngine) {
        this.geometry = geometry;
        this.structure = structure;
        this.inspector = inspector;
        this.boxEngine = boxEngine;
    }

    public Assessment analyze(byte[] bytes, String processCode, String sheetName, String managementNo,
                              EquipmentBoxProfile profile, String sourceSha256, String workingSha256) {
        String actualWorkingSha = sha256(bytes == null ? new byte[0] : bytes);
        List<String> blockers = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (profile == null) {
            return blocked("UNSUPPORTED", processCode, sheetName, managementNo, sourceSha256, workingSha256,
                    null, List.of("PROFILE_MISSING"), List.of(), List.of(), List.of(), List.of(), null, actualWorkingSha);
        }
        if (bytes == null || bytes.length == 0 || blank(processCode) || blank(sheetName) || blank(managementNo)) {
            return blocked("UNSUPPORTED", processCode, sheetName, managementNo, sourceSha256, workingSha256,
                    profile, List.of("DELETE_IDENTITY_OR_WORKBOOK_MISSING"), List.of(), List.of(), List.of(), List.of(), null, actualWorkingSha);
        }
        if (!profile.appliesTo(processCode, sheetName, sourceSha256)) unsupported.add("PROFILE_PROCESS_SHEET_OR_SOURCE_SHA_MISMATCH");
        boolean profileShaMatch = profile.matchesWorkingCopySha(workingSha256);
        boolean workingShaMatch = workingSha256 != null && workingSha256.equalsIgnoreCase(actualWorkingSha) && profileShaMatch;
        if (!workingShaMatch) blockers.add("WORKING_COPY_SHA_MISMATCH");
        if (!"VERIFIED".equals(profile.status())) blockers.add("PROFILE_STATUS_NOT_VERIFIED");
        if (!"SYNTHETIC_TESTED".equals(profile.evidenceLevel())) blockers.add("DELETE_REQUIRES_SYNTHETIC_TEST_EVIDENCE");
        if (!SYNTHETIC_DELETE_POLICIES.contains(profile.deletePolicy())) blockers.add("PROFILE_DELETE_POLICY_NOT_EXPLICITLY_AUTHORIZED");
        if ("BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY".equals(profile.deletePolicy())
                && profile.mergedRanges().isEmpty()) blockers.add("PROFILE_HAS_NO_MERGE_ALLOWLIST");
        Set<String> mappedCellAddresses = new LinkedHashSet<>();
        try {
            var mapping = boxEngine.mapFieldsByProfile(bytes, processCode, sheetName, managementNo,
                    profile, sourceSha256, workingSha256);
            mapping.fields().forEach((field, mapped) -> {
                if (mapped.cellAddress() != null) mappedCellAddresses.add(mapped.cellAddress().toUpperCase(Locale.ROOT));
                if (!"managementNo".equals(field) && !"VERIFIED".equals(mapped.status()))
                    blockers.add("PROFILE_FIELD_NOT_VERIFIED:" + field + ":" + mapped.reason());
            });
        } catch (RuntimeException exception) {
            blockers.add("PROFILE_FIELD_VALIDATION_FAILED:" + safeMessage(exception));
        }

        EquipmentBoxGeometryLocator.Box target;
        List<CellImpact> cells = new ArrayList<>();
        List<String> layoutMetrics = new ArrayList<>();
        List<String> mergesToRemove = new ArrayList<>();
        List<String> mergeConflicts = new ArrayList<>();
        List<WorkbookInventory.DrawingObject> preservedDrawings = new ArrayList<>();
        List<RelatedEquipment> nearbyEquipment = new ArrayList<>();
        String printArea = null;
        String pageSetup = null;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) unsupported.add("TARGET_SHEET_MISSING");
            List<EquipmentBoxGeometryLocator.Box> matches = sheet == null ? List.of() : geometry.locateByManagementNo(sheet, managementNo);
            if (matches.size() != 1) {
                blockers.add("EQUIPMENT_BOX_NOT_UNIQUE");
                target = null;
            } else {
                target = matches.get(0);
                if (target.lastRow() - target.firstRow() + 1 != profile.boxHeight())
                    blockers.add("PROFILE_BOX_HEIGHT_MISMATCH");
                if (profile.fields().get("managementNo") == null
                        || profile.fields().get("managementNo").rowOffset() != 0
                        || profile.fields().get("managementNo").columnOffset() != 0) {
                    blockers.add("PROFILE_MANAGEMENT_ID_ANCHOR_UNVERIFIED");
                } else {
                    var idCell = sheet.getRow(target.firstRow() - 1).getCell(target.firstColumn());
                    if (idCell == null || idCell.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING
                            || !managementNo.equals(new DataFormatter(Locale.ROOT).formatCellValue(idCell).trim())) {
                        blockers.add("TARGET_MANAGEMENT_ID_VALUE_MISMATCH");
                    }
                }
                for (int rowIndex = target.firstRow() - 1; rowIndex < target.lastRow(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    for (int column = target.firstColumn(); column <= target.lastColumn(); column++) {
                        Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                        cells.add(new CellImpact(new CellReference(rowIndex, column).formatAsString().replace("$", ""),
                                cell == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(cell),
                                cell == null ? 0 : cell.getCellStyle().getIndex(),
                                cell == null ? "MISSING" : cell.getCellType().name(),
                                cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA));
                    }
                }
                if (cells.stream().anyMatch(CellImpact::formula)) blockers.add("FORMULA_IN_DELETE_BOX");
                validateProfileRowHeights(profile, target, sheet, layoutMetrics, blockers);
                for (int column = target.firstColumn(); column <= target.lastColumn(); column++)
                    layoutMetrics.add("COLUMN:" + column + ":width256=" + sheet.getColumnWidth(column));
                for (CellImpact cell : cells) {
                    if (!cell.value().isBlank() && !mappedCellAddresses.contains(cell.address().toUpperCase(Locale.ROOT)))
                        blockers.add("UNMAPPED_NONEMPTY_BOX_CELL:" + cell.address());
                }
                assessMergedRanges(profile, sheet.getMergedRegions(), target, mergesToRemove, mergeConflicts, blockers);
                printArea = workbook.getPrintArea(workbook.getSheetIndex(sheetName));
                if (profile.printArea() != null && !profile.printArea().isBlank()
                        && !profile.printArea().equals(printArea)) blockers.add("PROFILE_PRINT_AREA_MISMATCH");
                validateProfileColumnWidths(profile, target, sheet, blockers);
                var setup = sheet.getPrintSetup();
                pageSetup = "orientation=" + (setup.getLandscape() ? "LANDSCAPE" : "PORTRAIT")
                        + ";paperSize=" + setup.getPaperSize() + ";fitWidth=" + setup.getFitWidth()
                        + ";fitHeight=" + setup.getFitHeight() + ";fitToPage=" + sheet.getFitToPage()
                        + ";leftMargin=" + sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin)
                        + ";rightMargin=" + sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin)
                        + ";topMargin=" + sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin)
                        + ";bottomMargin=" + sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin);
            }
        } catch (Exception exception) {
            unsupported.add("WORKBOOK_OR_TARGET_CELLS_UNREADABLE:" + exception.getClass().getSimpleName());
            target = null;
        }

        List<WorkbookInventory.DrawingObject> drawingObjects = List.of();
        try {
            var sheetInventory = inspector.inspectSheet(bytes, sheetName);
            drawingObjects = sheetInventory.drawingObjects();
            if (target != null) for (var object : drawingObjects) {
                if (object.startRow() == null || object.endRow() == null
                        || object.startColumn() == null || object.endColumn() == null) {
                    blockers.add("DRAWINGML_LOCATION_UNKNOWN:" + printable(object.objectName()));
                    continue;
                }
                int top = target.firstRow() - 1;
                int bottom = target.lastRow() - 1;
                int left = target.firstColumn();
                int right = target.lastColumn();
                int objectTop = Math.min(object.startRow(), object.endRow());
                int objectBottom = Math.max(object.startRow(), object.endRow());
                int objectLeft = Math.min(object.startColumn(), object.endColumn());
                int objectRight = Math.max(object.startColumn(), object.endColumn());
                boolean intersects = objectTop <= bottom && objectBottom >= top
                        && objectLeft <= right && objectRight >= left;
                boolean near = objectTop <= bottom + 1 && objectBottom >= top - 1
                        && objectLeft <= right + 1 && objectRight >= left - 1;
                if (intersects || near) {
                    blockers.add(("CONNECTOR".equals(object.objectType()) ? "CONNECTOR" : "DRAWINGML")
                            + (intersects ? "_INTERSECTS_DELETE_BOX:" : "_WITHIN_DELETE_GUARD_BAND:")
                            + printable(object.objectName()));
                } else {
                    preservedDrawings.add(object);
                    if ("CONNECTOR".equals(object.objectType())
                            && (object.startConnectedShapeId() != null || object.endConnectedShapeId() != null)) {
                        blockers.add("BOUND_CONNECTOR_RELATIONSHIP_UNVERIFIED:" + printable(object.objectName()));
                    }
                }
            }
        } catch (RuntimeException exception) {
            unsupported.add("DRAWINGML_INVENTORY_UNAVAILABLE");
        }

        if (target != null) {
            try {
                var report = structure.analyzeSheet(processCode, "DELETE_TEST_ONLY.xlsx", actualWorkingSha, bytes, sheetName);
                for (var candidate : report.equipmentCandidates()) {
                    if (candidate.topLeftCell() == null || candidate.firstRow() == null || candidate.firstColumn() == null
                            || managementNo.equals(candidate.equipmentIdCandidate())) continue;
                    int otherTop = candidate.firstRow() - 1;
                    int otherBottom = candidate.lastRow() - 1;
                    int otherLeft = candidate.firstColumn();
                    int otherRight = candidate.lastColumn();
                    int targetTop = target.firstRow() - 1;
                    int targetBottom = target.lastRow() - 1;
                    int targetLeft = target.firstColumn();
                    int targetRight = target.lastColumn();
                    boolean overlaps = otherTop <= targetBottom && otherBottom >= targetTop
                            && otherLeft <= targetRight && otherRight >= targetLeft;
                    boolean near = otherTop <= targetBottom + 1 && otherBottom >= targetTop - 1
                            && otherLeft <= targetRight + 1 && otherRight >= targetLeft - 1;
                    if (overlaps) blockers.add("OTHER_EQUIPMENT_BOX_OVERLAP:" + candidate.equipmentIdCandidate());
                    if (near) nearbyEquipment.add(new RelatedEquipment(candidate.equipmentIdCandidate(),
                            candidate.topLeftCell() + ":" + candidate.bottomRightCell(),
                            overlaps ? "OVERLAPS_TARGET_BLOCKED" : "ADJACENT_BOX_PRESERVED_NO_ROW_SHIFT"));
                }
            } catch (RuntimeException exception) {
                blockers.add("ADJACENT_EQUIPMENT_SCAN_UNAVAILABLE");
            }
        }

        if (!unsupported.isEmpty()) return assessment("UNSUPPORTED", processCode, sheetName, managementNo,
                sourceSha256, workingSha256, profile, target, cells, mergesToRemove, mergeConflicts,
                preservedDrawings, drawingObjects, nearbyEquipment, layoutMetrics, printArea, pageSetup, unsupported, actualWorkingSha);
        String status = blockers.isEmpty() ? "SAFE_BOX_ONLY_TEST_ONLY" : "REVIEW_REQUIRED";
        if (status.equals("SAFE_BOX_ONLY_TEST_ONLY") && !workingShaMatch) status = "REVIEW_REQUIRED";
        return assessment(status, processCode, sheetName, managementNo, sourceSha256, workingSha256, profile,
                target, cells, mergesToRemove, mergeConflicts, preservedDrawings, drawingObjects,
                nearbyEquipment, layoutMetrics, printArea, pageSetup, blockers, actualWorkingSha);
    }

    private void validateProfileRowHeights(EquipmentBoxProfile profile, EquipmentBoxGeometryLocator.Box target,
                                           org.apache.poi.ss.usermodel.Sheet sheet, List<String> metrics,
                                           List<String> blockers) {
        if (profile.rowHeightsPt().isEmpty()) blockers.add("PROFILE_ROW_HEIGHTS_UNVERIFIED");
        for (int rowIndex = target.firstRow() - 1; rowIndex < target.lastRow(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            float height = row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
            boolean hidden = row != null && row.getZeroHeight();
            int offset = rowIndex - target.firstRow() + 1;
            metrics.add("ROW:" + rowIndex + ":heightPt=" + height + ":hidden=" + hidden);
            Float expected = profile.rowHeightsPt().get(offset);
            if (expected != null && Math.abs(height - expected) > 0.2f)
                blockers.add("PROFILE_ROW_HEIGHT_MISMATCH:" + offset);
            if (hidden) blockers.add("HIDDEN_ROW_IN_DELETE_BOX:" + (rowIndex + 1));
        }
    }

    private void validateProfileColumnWidths(EquipmentBoxProfile profile, EquipmentBoxGeometryLocator.Box target,
                                             org.apache.poi.ss.usermodel.Sheet sheet, List<String> blockers) {
        if (profile.columnWidths().isEmpty()) {
            blockers.add("PROFILE_COLUMN_WIDTHS_UNVERIFIED");
            return;
        }
        CellRangeAddress template;
        try {
            String range = profile.templateRange();
            int bang = range == null ? -1 : range.lastIndexOf('!');
            template = CellRangeAddress.valueOf(bang >= 0 ? range.substring(bang + 1) : range);
        } catch (RuntimeException exception) {
            blockers.add("PROFILE_TEMPLATE_RANGE_INVALID");
            return;
        }
        int delta = target.firstColumn() - template.getFirstColumn();
        for (var expected : profile.columnWidths().entrySet()) {
            int templateColumn = org.apache.poi.ss.util.CellReference.convertColStringToIndex(expected.getKey());
            int targetColumn = templateColumn + delta;
            if (targetColumn < target.firstColumn() || targetColumn > target.lastColumn()
                    || Math.abs(sheet.getColumnWidth(targetColumn) - Math.round(expected.getValue() * 256.0d)) > 1) {
                blockers.add("PROFILE_COLUMN_WIDTH_MISMATCH:" + expected.getKey());
            }
        }
    }

    private void assessMergedRanges(EquipmentBoxProfile profile, List<CellRangeAddress> actual,
                                   EquipmentBoxGeometryLocator.Box target, List<String> remove,
                                   List<String> conflicts, List<String> blockers) {
        int top = target.firstRow() - 1;
        int bottom = target.lastRow() - 1;
        int left = target.firstColumn();
        int right = target.lastColumn();
        List<CellRangeAddress> intersecting = actual.stream().filter(range -> range.getFirstRow() <= bottom
                && range.getLastRow() >= top && range.getFirstColumn() <= right && range.getLastColumn() >= left).toList();
        List<CellRangeAddress> expected = expectedMerges(profile, target, blockers);
        for (CellRangeAddress range : intersecting) {
            boolean contained = range.getFirstRow() >= top && range.getLastRow() <= bottom
                    && range.getFirstColumn() >= left && range.getLastColumn() <= right;
            String address = range.formatAsString();
            if (!contained) {
                conflicts.add(address);
                blockers.add("MERGE_CROSSES_DELETE_BOX_BOUNDARY:" + address);
            } else if (!"BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY".equals(profile.deletePolicy())) {
                conflicts.add(address);
                blockers.add("PROFILE_DOES_NOT_AUTHORIZE_MERGE_DELETION:" + address);
            } else if (expected.stream().noneMatch(expectedRange -> expectedRange.formatAsString().equals(address))) {
                conflicts.add(address);
                blockers.add("UNPROFILED_INTERNAL_MERGE:" + address);
            } else {
                remove.add(address);
            }
        }
        for (CellRangeAddress expectedRange : expected) {
            if (intersecting.stream().noneMatch(actualRange -> actualRange.formatAsString()
                    .equals(expectedRange.formatAsString()))) {
                blockers.add("PROFILE_MERGE_NOT_FOUND:" + expectedRange.formatAsString());
            }
        }
    }

    private List<CellRangeAddress> expectedMerges(EquipmentBoxProfile profile,
                                                   EquipmentBoxGeometryLocator.Box target,
                                                   List<String> blockers) {
        if (profile.mergedRanges().isEmpty()) return List.of();
        CellRangeAddress template;
        try {
            String range = profile.templateRange();
            int bang = range == null ? -1 : range.lastIndexOf('!');
            template = CellRangeAddress.valueOf(bang >= 0 ? range.substring(bang + 1) : range);
        } catch (RuntimeException exception) {
            blockers.add("PROFILE_TEMPLATE_RANGE_INVALID");
            return List.of();
        }
        List<CellRangeAddress> result = new ArrayList<>();
        int deltaRow = target.firstRow() - 1 - template.getFirstRow();
        int deltaColumn = target.firstColumn() - template.getFirstColumn();
        for (String merge : profile.mergedRanges()) {
            try {
                CellRangeAddress range = CellRangeAddress.valueOf(merge);
                CellRangeAddress translated = new CellRangeAddress(range.getFirstRow() + deltaRow,
                        range.getLastRow() + deltaRow, range.getFirstColumn() + deltaColumn,
                        range.getLastColumn() + deltaColumn);
                result.add(translated);
            } catch (RuntimeException exception) {
                blockers.add("PROFILE_MERGE_REFERENCE_INVALID:" + merge);
            }
        }
        return List.copyOf(result);
    }

    private Assessment assessment(String status, String process, String sheet, String managementNo,
                                  String sourceSha, String expectedWorkingSha, EquipmentBoxProfile profile,
                                  EquipmentBoxGeometryLocator.Box target, List<CellImpact> cells,
                                  List<String> mergesToRemove, List<String> mergeConflicts,
                                  List<WorkbookInventory.DrawingObject> preservedDrawings,
                                  List<WorkbookInventory.DrawingObject> allDrawingObjects,
                                  List<RelatedEquipment> nearbyEquipment, List<String> layoutMetrics,
                                  String printArea, String pageSetup,
                                  List<String> blockers, String actualWorkingSha) {
        boolean profileShaMatch = profile != null && profile.appliesTo(process, sheet, sourceSha);
        boolean workingShaMatch = profile != null && profile.matchesWorkingCopySha(expectedWorkingSha)
                && expectedWorkingSha != null && expectedWorkingSha.equalsIgnoreCase(actualWorkingSha);
        return new Assessment(status, status.equals("SAFE_BOX_ONLY_TEST_ONLY"), process, sheet, managementNo,
                target == null ? null : target.topLeftCell(), target == null ? null : target.bottomRightCell(),
                target == null ? null : target.topLeftCell() + ":" + target.bottomRightCell(),
                cells, mergesToRemove, mergeConflicts, preservedDrawings, allDrawingObjects, nearbyEquipment,
                profile == null ? null : profile.profileId(), profile == null ? null : profile.profileVersion(),
                profileFingerprint(profile), profile == null ? null : profile.status(),
                profile == null ? null : profile.evidenceLevel(),
                profile == null ? null : profile.deletePolicy(), profileShaMatch, workingShaMatch,
                sourceSha, expectedWorkingSha, actualWorkingSha, layoutMetrics,
                printArea, pageSetup, true, CONNECTION_UNVERIFIED, List.copyOf(new LinkedHashSet<>(blockers)),
                "SAFE_BOX_ONLY_TEST_ONLY is synthetic-only and does not establish real process connectivity or approve an actual process DELETE.");
    }

    private Assessment blocked(String status, String process, String sheet, String managementNo,
                               String sourceSha, String workingSha, EquipmentBoxProfile profile,
                               List<String> blockers, List<CellImpact> cells, List<String> mergesToRemove,
                               List<String> mergeConflicts, List<WorkbookInventory.DrawingObject> drawings,
                               String printArea, String actualWorkingSha) {
        return assessment(status, process, sheet, managementNo, sourceSha, workingSha, profile, null, cells,
                mergesToRemove, mergeConflicts, drawings, drawings, List.of(), List.of(), printArea, null, blockers, actualWorkingSha);
    }

    private String profileFingerprint(EquipmentBoxProfile profile) {
        if (profile == null) return null;
        StringBuilder canonical = new StringBuilder();
        for (Object value : java.util.Arrays.asList(profile.profileId(), profile.processCode(), profile.sheetName(),
                profile.sourceFilename(), profile.sourceSha256(), profile.verifiedWorkingSha256(), profile.profileVersion(),
                profile.status(), profile.evidenceLevel(), profile.evidence(), profile.templateRange(), profile.boxHeight(),
                profile.fontName(), profile.fontSizePt(), profile.fontColor(), profile.drawingPolicy(),
                profile.connectorPolicy(), profile.insertionPolicy(), profile.printArea(), profile.pageBreakPolicy(),
                profile.blocker(), profile.deletePolicy())) appendToken(canonical, value);
        profile.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            appendToken(canonical, entry.getKey()); appendToken(canonical, entry.getValue());
        });
        profile.mergedRanges().stream().sorted().forEach(value -> appendToken(canonical, value));
        profile.expectedStyleIds().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            appendToken(canonical, entry.getKey()); appendToken(canonical, entry.getValue());
        });
        profile.rowHeightsPt().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            appendToken(canonical, entry.getKey()); appendToken(canonical, entry.getValue());
        });
        profile.columnWidths().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            appendToken(canonical, entry.getKey()); appendToken(canonical, entry.getValue());
        });
        return sha256(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void appendToken(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String printable(String text) { return text == null || text.isBlank() ? "UNKNOWN" : text; }
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(180, message.length()));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record CellImpact(String address, String value, int styleId, String cellType, boolean formula) { }
    public record RelatedEquipment(String managementNo, String cellRange, String impactStatus) { }
    public record Assessment(String status, boolean automaticTestOnlyDeleteAllowed, String processCode,
                             String sheetName, String managementNo, String boxTopLeft, String boxBottomRight,
                             String deleteCellRange, List<CellImpact> cellsToDelete,
                             List<String> mergedRangesToRemove, List<String> mergeConflicts,
                             List<WorkbookInventory.DrawingObject> drawingObjectsToPreserve,
                             List<WorkbookInventory.DrawingObject> drawingObjectsAnalyzed,
                             List<RelatedEquipment> nearbyEquipment, String profileId, String profileVersion,
                             String profileFingerprint, String profileStatus, String profileEvidenceLevel,
                             String deletePolicy,
                             boolean profileShaMatch, boolean workingCopyShaMatch,
                             String sourceSha256, String workingCopySha256,
                             String actualBytesSha256, List<String> preservedLayoutMetrics,
                             String printArea, String pageSetup,
                             boolean materialBalanceReviewRequired, String connectionEvidenceStatus,
                             List<String> blockers, String interpretation) {
        public Assessment {
            cellsToDelete = List.copyOf(cellsToDelete);
            mergedRangesToRemove = List.copyOf(mergedRangesToRemove);
            mergeConflicts = List.copyOf(mergeConflicts);
            drawingObjectsToPreserve = List.copyOf(drawingObjectsToPreserve);
            drawingObjectsAnalyzed = List.copyOf(drawingObjectsAnalyzed);
            nearbyEquipment = List.copyOf(nearbyEquipment);
            preservedLayoutMetrics = List.copyOf(preservedLayoutMetrics);
            blockers = List.copyOf(blockers);
        }

        /** Stable digest of the complete delete decision inputs, independent of JSON/property ordering. */
        public String analysisFingerprint() {
            StringBuilder canonical = new StringBuilder();
            for (Object value : java.util.Arrays.asList(status, automaticTestOnlyDeleteAllowed, processCode,
                    sheetName, managementNo, boxTopLeft, boxBottomRight, deleteCellRange, profileId, profileVersion,
                    profileFingerprint, profileStatus, profileEvidenceLevel, deletePolicy,
                    profileShaMatch, workingCopyShaMatch,
                    sourceSha256, workingCopySha256, actualBytesSha256, printArea, pageSetup,
                    materialBalanceReviewRequired, connectionEvidenceStatus)) append(canonical, value);
            cellsToDelete.stream().sorted(java.util.Comparator.comparing(CellImpact::address)).forEach(cell -> {
                append(canonical, cell.address()); append(canonical, cell.value()); append(canonical, cell.styleId());
                append(canonical, cell.cellType()); append(canonical, cell.formula());
            });
            mergedRangesToRemove.stream().sorted().forEach(value -> append(canonical, value));
            preservedLayoutMetrics.stream().sorted().forEach(value -> append(canonical, value));
            mergeConflicts.stream().sorted().forEach(value -> append(canonical, value));
            drawingObjectsAnalyzed.stream().sorted(java.util.Comparator
                    .comparingInt(WorkbookInventory.DrawingObject::objectIndex)
                    .thenComparing(item -> String.valueOf(item.objectType()))
                    .thenComparing(item -> String.valueOf(item.objectName()))
                    .thenComparing(item -> String.valueOf(item.shapeId())))
                    .forEach(item -> append(canonical, item.toString()));
            nearbyEquipment.stream().sorted(java.util.Comparator
                    .comparing((RelatedEquipment item) -> String.valueOf(item.managementNo()))
                    .thenComparing(item -> String.valueOf(item.cellRange())))
                    .forEach(item -> append(canonical, item.toString()));
            blockers.stream().sorted().forEach(value -> append(canonical, value));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to fingerprint DELETE assessment", exception);
            }
        }

        private static void append(StringBuilder target, Object value) {
            String text = value == null ? "<null>" : String.valueOf(value);
            target.append(text.length()).append(':').append(text).append('|');
        }
    }
}
