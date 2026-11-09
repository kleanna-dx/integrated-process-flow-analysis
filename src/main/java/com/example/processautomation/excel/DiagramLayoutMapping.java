package com.example.processautomation.excel;

import java.util.List;
import java.util.Map;

/** Immutable structural location evidence. It does not authorize edits or prove physical process connectivity. */
public record DiagramLayoutMapping(
        String diagramId,
        String sourceSha256,
        String processId,
        String sheetName,
        String equipmentId,
        String boxRange,
        String identifierCell,
        Map<String, String> editableFieldCandidates,
        List<String> mergedRanges,
        Map<Integer, Float> rowHeights,
        Map<String, Double> columnWidths,
        List<DrawingAnchor> drawingAnchors,
        List<ConnectorReference> connectorReferences,
        PlacementStatus placementStatus,
        EvidenceStatus evidenceStatus) {

    public DiagramLayoutMapping {
        editableFieldCandidates = editableFieldCandidates == null ? Map.of() : Map.copyOf(editableFieldCandidates);
        mergedRanges = mergedRanges == null ? List.of() : List.copyOf(mergedRanges);
        rowHeights = rowHeights == null ? Map.of() : Map.copyOf(rowHeights);
        columnWidths = columnWidths == null ? Map.of() : Map.copyOf(columnWidths);
        drawingAnchors = drawingAnchors == null ? List.of() : List.copyOf(drawingAnchors);
        connectorReferences = connectorReferences == null ? List.of() : List.copyOf(connectorReferences);
        placementStatus = placementStatus == null ? PlacementStatus.UNKNOWN : placementStatus;
        evidenceStatus = evidenceStatus == null ? EvidenceStatus.UNKNOWN : evidenceStatus;
    }

    public enum PlacementStatus { VERIFIED, REVIEW_REQUIRED, BLOCKED, UNKNOWN }
    public enum EvidenceStatus { STRUCTURE_VERIFIED, STRUCTURAL_CANDIDATE, INSUFFICIENT, UNKNOWN }

    /** Zero-based DrawingML cell coordinates; offsets and EMU extents remain available for audit. */
    public record DrawingAnchor(String objectType, String shapeId, Integer startColumn, Integer startRow,
                                Integer endColumn, Integer endRow, Long startColumnOffset, Long startRowOffset,
                                Long endColumnOffset, Long endRowOffset, Long startAnchorXEmu,
                                Long startAnchorYEmu, Long endAnchorXEmu, Long endAnchorYEmu) { }

    public record ConnectorReference(String connectorId, String startShapeId, String endShapeId,
                                     String headArrowType, String tailArrowType) { }
}
