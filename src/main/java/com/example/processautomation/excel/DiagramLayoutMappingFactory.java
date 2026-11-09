package com.example.processautomation.excel;

import java.util.List;
import java.util.Map;

/** Converts read-only inventory findings into explicitly non-authorizing layout mappings. */
public final class DiagramLayoutMappingFactory {
    private DiagramLayoutMappingFactory() { }

    public static DiagramLayoutMapping fromCandidate(ProcessDiagramStructureAnalyzer.WorkbookReport workbook,
                                                       ProcessDiagramStructureAnalyzer.SheetReport sheet,
                                                       ProcessDiagramStructureAnalyzer.EquipmentCandidate candidate) {
        if (workbook == null || sheet == null || candidate == null) throw new IllegalArgumentException("Inventory evidence is required");
        String range = candidate.topLeftCell() == null || candidate.bottomRightCell() == null
                ? null : candidate.topLeftCell() + ":" + candidate.bottomRightCell();
        Map<Integer, Float> rowHeights = sheet.nonEmptyRows().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ProcessDiagramStructureAnalyzer.RowProfile::rowNumber,
                ProcessDiagramStructureAnalyzer.RowProfile::heightPoints, (left, right) -> left));
        Map<String, Double> columnWidths = sheet.columns().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ProcessDiagramStructureAnalyzer.ColumnProfile::columnLetter,
                column -> (double) column.widthPixels(), (left, right) -> left));
        List<DiagramLayoutMapping.DrawingAnchor> anchors = sheet.drawingObjects().stream().map(object ->
                new DiagramLayoutMapping.DrawingAnchor(object.objectType(), object.shapeId(), object.startColumn(),
                        object.startRow(), object.endColumn(), object.endRow(), object.startColumnOffset(),
                        object.startRowOffset(), object.endColumnOffset(), object.endRowOffset(), object.startAnchorXEmu(),
                        object.startAnchorYEmu(), object.endAnchorXEmu(), object.endAnchorYEmu())).toList();
        List<DiagramLayoutMapping.ConnectorReference> connectors = sheet.drawingObjects().stream()
                .filter(object -> "CONNECTOR".equals(object.objectType())).map(object ->
                        new DiagramLayoutMapping.ConnectorReference(object.shapeId(), object.startConnectedShapeId(),
                                object.endConnectedShapeId(), object.headArrowType(), object.tailArrowType())).toList();
        boolean boxed = range != null && "BOX_CANDIDATE_REVIEW_REQUIRED".equals(candidate.status());
        return new DiagramLayoutMapping(workbook.filename() + "#" + sheet.sheetName(), workbook.sha256(),
                workbook.processCode(), sheet.sheetName(), candidate.equipmentIdCandidate(), range,
                candidate.idTextCells().size() == 1 ? candidate.idTextCells().get(0) : null,
                Map.of(), sheet.mergedRanges(), rowHeights, columnWidths, anchors, connectors,
                boxed ? DiagramLayoutMapping.PlacementStatus.REVIEW_REQUIRED : DiagramLayoutMapping.PlacementStatus.UNKNOWN,
                boxed ? DiagramLayoutMapping.EvidenceStatus.STRUCTURAL_CANDIDATE : DiagramLayoutMapping.EvidenceStatus.UNKNOWN);
    }
}
