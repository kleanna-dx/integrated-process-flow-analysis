package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Fail-closed DrawingML PIPE_CHANGE engine. The only writable path is an explicitly declared synthetic fixture;
 * it does not infer physical piping from drawing geometry or connect a drawing to a live process network.
 */
@Component
public class PipeChangeDrawingEngine {
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String CONNECTION_NOT_VERIFIED = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";
    private final OoxmlPackageInspector inspector;
    private final OoxmlPartEditor parts;

    public PipeChangeDrawingEngine(OoxmlPackageInspector inspector, OoxmlPartEditor parts) {
        this.inspector = Objects.requireNonNull(inspector);
        this.parts = Objects.requireNonNull(parts);
    }

    public Assessment analyze(byte[] workbook, Request request) {
        List<String> blockers = new ArrayList<>();
        if (workbook == null || workbook.length == 0 || request == null) {
            return blocked("UNSUPPORTED", List.of("REQUEST_OR_WORKBOOK_MISSING"), null, null);
        }
        String actualSha = sha256(workbook);
        if (!actualSha.equalsIgnoreCase(nullToEmpty(request.expectedWorkingSha256()))) blockers.add("WORKING_COPY_SHA_MISMATCH");
        if (!request.syntheticFixture() || !"EXPLICIT_SYNTHETIC_VERIFIED".equals(request.mappingEvidence()))
            blockers.add("SYNTHETIC_MAPPING_NOT_EXPLICITLY_VERIFIED");
        if (!CONNECTION_NOT_VERIFIED.equals(request.physicalEvidenceStatus())) blockers.add("PHYSICAL_EVIDENCE_STATUS_MUST_REMAIN_UNVERIFIED");
        if (blank(request.workingDocumentId()) || blank(request.sheetName()) || blank(request.connectorShapeId())
                || blank(request.beforeStartShapeId()) || blank(request.beforeEndShapeId())
                || blank(request.afterStartShapeId()) || blank(request.afterEndShapeId())
                || blank(request.sourceSheetName()) || blank(request.targetSheetName()))
            blockers.add("WORKING_COPY_SHEET_CONNECTOR_AND_ENDPOINT_IDENTIFIERS_REQUIRED");
        if (request.sheetName() != null && (!request.sheetName().equals(request.sourceSheetName())
                || !request.sheetName().equals(request.targetSheetName()))) blockers.add("CROSS_SHEET_ENDPOINTS_UNSUPPORTED");
        if (!blockers.isEmpty()) return assessment("REVIEW_REQUIRED", request, actualSha, null, List.of(), blockers);

        try {
            var sheet = inspector.inspectSheet(workbook, request.sheetName());
            if (sheet.drawingPartName() == null) return assessment("UNSUPPORTED", request, actualSha, null, List.of(), List.of("DRAWING_PART_MISSING"));
            List<WorkbookInventory.DrawingObject> objects = sheet.drawingObjects();
            Map<String, List<WorkbookInventory.DrawingObject>> byId = new LinkedHashMap<>();
            for (var object : objects) {
                if (object.shapeId() != null) byId.computeIfAbsent(object.shapeId(), ignored -> new ArrayList<>()).add(object);
            }
            List<String> duplicateIds = byId.entrySet().stream().filter(entry -> entry.getValue().size() != 1)
                    .map(Map.Entry::getKey).toList();
            if (!duplicateIds.isEmpty()) blockers.add("DUPLICATE_DRAWING_OBJECT_IDS:" + String.join(",", duplicateIds));
            var connectorMatches = byId.getOrDefault(request.connectorShapeId(), List.of());
            if (connectorMatches.size() != 1) blockers.add(connectorMatches.isEmpty()
                    ? "TARGET_CONNECTOR_NOT_FOUND" : "TARGET_CONNECTOR_ID_NOT_UNIQUE");
            var startMatches = byId.getOrDefault(request.beforeStartShapeId(), List.of());
            var endMatches = byId.getOrDefault(request.beforeEndShapeId(), List.of());
            if (startMatches.size() != 1) blockers.add("BEFORE_START_SHAPE_NOT_UNIQUE_OR_MISSING");
            if (endMatches.size() != 1) blockers.add("BEFORE_END_SHAPE_NOT_UNIQUE_OR_MISSING");
            var newStartMatches = byId.getOrDefault(request.afterStartShapeId(), List.of());
            var newEndMatches = byId.getOrDefault(request.afterEndShapeId(), List.of());
            if (newStartMatches.size() != 1) blockers.add("AFTER_START_SHAPE_NOT_UNIQUE_OR_MISSING");
            if (newEndMatches.size() != 1) blockers.add("AFTER_END_SHAPE_NOT_UNIQUE_OR_MISSING");
            if (!blockers.isEmpty()) return assessment("REVIEW_REQUIRED", request, actualSha, sheet.drawingPartName(), objects, blockers);

            var connector = connectorMatches.get(0);
            var beforeStart = startMatches.get(0);
            var beforeEnd = endMatches.get(0);
            var afterStart = newStartMatches.get(0);
            var afterEnd = newEndMatches.get(0);
            if (!"CONNECTOR".equals(connector.objectType())) blockers.add("TARGET_ID_IS_NOT_CONNECTOR");
            if (!"straightConnector1".equals(connector.geometryPreset())) blockers.add("CONNECTOR_GEOMETRY_UNSUPPORTED");
            if (!hasZeroPortIndexes(workbook, sheet.drawingPartName(), request.connectorShapeId()))
                blockers.add("CONNECTOR_PORT_INDEX_UNSUPPORTED");
            if (connector.startConnectedShapeId() == null || connector.endConnectedShapeId() == null)
                blockers.add("CONNECTOR_ENDPOINT_BINDING_MISSING");
            if (!request.beforeStartShapeId().equals(connector.startConnectedShapeId())
                    || !request.beforeEndShapeId().equals(connector.endConnectedShapeId())) blockers.add("CONNECTOR_BEFORE_BINDING_MISMATCH");
            if (!"SHAPE".equals(beforeStart.objectType()) || !"SHAPE".equals(beforeEnd.objectType())
                    || !"SHAPE".equals(afterStart.objectType()) || !"SHAPE".equals(afterEnd.objectType()))
                blockers.add("ENDPOINT_MUST_BE_A_SIMPLE_SHAPE");
            if (!sameSheetAnchor(beforeStart) || !sameSheetAnchor(beforeEnd)
                    || !sameSheetAnchor(afterStart) || !sameSheetAnchor(afterEnd))
                blockers.add("ENDPOINT_ANCHOR_SHAPE_NOT_SUPPORTED");
            if (connector.startColumn() == null || connector.endColumn() == null || connector.startRow() == null
                    || connector.endRow() == null || connector.startAnchorXEmu() == null || connector.endAnchorXEmu() == null
                    || connector.startAnchorYEmu() == null || connector.endAnchorYEmu() == null)
                blockers.add("CONNECTOR_ANCHOR_INCOMPLETE");
            if (request.beforeStartShapeId().equals(request.beforeEndShapeId())
                    || request.afterStartShapeId().equals(request.afterEndShapeId())) blockers.add("SELF_LOOP_NOT_SUPPORTED");
            if (blockers.isEmpty()) {
                Point boundStart = endpointAnchorPoint(beforeStart, workbook, request.sheetName());
                Point boundEnd = endpointAnchorPoint(beforeEnd, workbook, request.sheetName());
                if (!close(connector.startAnchorXEmu(), boundStart.absX())
                        || !close(connector.startAnchorYEmu(), boundStart.absY())
                        || !close(connector.endAnchorXEmu(), boundEnd.absX())
                        || !close(connector.endAnchorYEmu(), boundEnd.absY()))
                    blockers.add("CONNECTOR_ANCHOR_DOES_NOT_MATCH_BOUND_ENDPOINT_GEOMETRY:connector="
                            + connector.startAnchorXEmu() + "," + connector.startAnchorYEmu() + "->"
                            + connector.endAnchorXEmu() + "," + connector.endAnchorYEmu() + ";mapped="
                            + boundStart.absX() + "," + boundStart.absY() + "->" + boundEnd.absX() + "," + boundEnd.absY());
            }

            for (var other : objects) {
                if (other.objectIndex() == connector.objectIndex() || !"CONNECTOR".equals(other.objectType())) continue;
                if (request.connectorShapeId().equals(other.startConnectedShapeId())
                        || request.connectorShapeId().equals(other.endConnectedShapeId()))
                    blockers.add("OTHER_CONNECTOR_DEPENDS_ON_TARGET:" + other.shapeId());
                if (request.afterStartShapeId().equals(other.startConnectedShapeId())
                        && request.afterEndShapeId().equals(other.endConnectedShapeId()))
                    blockers.add("OTHER_CONNECTOR_HAS_REQUESTED_ENDPOINT_PAIR:" + other.shapeId());
                if (touches(other, request.beforeStartShapeId(), request.beforeEndShapeId(),
                        request.afterStartShapeId(), request.afterEndShapeId()))
                    blockers.add("OTHER_CONNECTOR_SHARES_AFFECTED_ENDPOINT:" + other.shapeId());
            }
            if (blockers.isEmpty()) {
                Point proposedStart = endpointAnchorPoint(afterStart, workbook, request.sheetName());
                Point proposedEnd = endpointAnchorPoint(afterEnd, workbook, request.sheetName());
                for (var other : objects) {
                    if (!"SHAPE".equals(other.objectType()) || request.afterStartShapeId().equals(other.shapeId())
                            || request.afterEndShapeId().equals(other.shapeId())) continue;
                    if (lineIntersectsObject(proposedStart, proposedEnd, other))
                        blockers.add("OTHER_SHAPE_INTERFERES_WITH_PROPOSED_CONNECTOR:" + other.shapeId());
                }
            }
            if (!request.sheetName().equals(request.sourceSheetName()) || !request.sheetName().equals(request.targetSheetName()))
                blockers.add("CROSS_SHEET_ENDPOINTS_UNSUPPORTED");
            if (!blockers.isEmpty()) return assessment("REVIEW_REQUIRED", request, actualSha, sheet.drawingPartName(), objects, blockers);

            return assessment("SYNTHETIC_EDITABLE_TEST_ONLY", request, actualSha, sheet.drawingPartName(), objects, List.of());
        } catch (RuntimeException exception) {
            return assessment("UNSUPPORTED", request, actualSha, null, List.of(), List.of("DRAWINGML_ANALYSIS_FAILED:" + exception.getClass().getSimpleName()));
        }
    }

    /**
     * Verifies an explicit, synthetic-only bridge from drawing objects to a PIPE_CHANGE graph edge.
     * It validates endpoints/identity only; it never executes a database-backed calculation or invents a flow.
     */
    public WhatIfMappingComparison verifyWhatIfMapping(Assessment assessment, SyntheticWhatIfMapping mapping,
                                                        com.example.processautomation.material.NetworkWhatIfRequest before,
                                                        com.example.processautomation.material.NetworkWhatIfRequest after) {
        List<String> blockers = new ArrayList<>();
        if (assessment == null || !"SYNTHETIC_EDITABLE_TEST_ONLY".equals(assessment.status())) blockers.add("DRAWING_ASSESSMENT_NOT_EDITABLE_SYNTHETIC");
        if (mapping == null || before == null || after == null) blockers.add("EXPLICIT_MAPPING_AND_BEFORE_AFTER_NETWORKS_REQUIRED");
        if (blockers.isEmpty()) {
            if (!"TEST_ONLY".equals(before.mode()) || !"TEST_ONLY".equals(after.mode())) blockers.add("WHAT_IF_NETWORKS_MUST_BE_TEST_ONLY");
            if (blank(mapping.evidenceId()) || !mapping.evidenceId().startsWith("SYNTHETIC:")) blockers.add("SERVER_VERIFIED_SYNTHETIC_MAPPING_EVIDENCE_REQUIRED");
            if (!Objects.equals(assessment.connectorShapeId(), mapping.connectorShapeId())
                    || !Objects.equals(assessment.beforeStartShapeId(), mapping.beforeStartShapeId())
                    || !Objects.equals(assessment.beforeEndShapeId(), mapping.beforeEndShapeId())
                    || !Objects.equals(assessment.afterStartShapeId(), mapping.afterStartShapeId())
                    || !Objects.equals(assessment.afterEndShapeId(), mapping.afterEndShapeId())) blockers.add("DRAWING_MAPPING_DOES_NOT_MATCH_ASSESSMENT");
            if (!Objects.equals(before.changedConnectionId(), mapping.beforeConnectionId())
                    || !Objects.equals(after.changedConnectionId(), mapping.afterConnectionId())) blockers.add("WHAT_IF_CONNECTION_ID_MISMATCH");
            if (Objects.equals(mapping.beforeConnectionId(), mapping.afterConnectionId()))
                blockers.add("BEFORE_AFTER_CONNECTION_IDS_MUST_BE_DISTINCT");
            var beforeEdges = before.edges().stream().filter(edge -> mapping.beforeConnectionId().equals(edge.connectionId())).toList();
            var afterEdges = after.edges().stream().filter(edge -> mapping.afterConnectionId().equals(edge.connectionId())).toList();
            if (beforeEdges.size() != 1 || afterEdges.size() != 1) blockers.add("WHAT_IF_CONNECTION_ID_NOT_UNIQUE");
            else {
                var beforeEdge = beforeEdges.get(0); var afterEdge = afterEdges.get(0);
                String beforeSource = equipmentFor(before, beforeEdge.sourceNodeId());
                String beforeTarget = equipmentFor(before, beforeEdge.targetNodeId());
                String afterSource = equipmentFor(after, afterEdge.sourceNodeId());
                String afterTarget = equipmentFor(after, afterEdge.targetNodeId());
                if (!uniqueEquipmentNode(before, beforeEdge.sourceNodeId(), mapping.beforeSourceEquipment())
                        || !uniqueEquipmentNode(before, beforeEdge.targetNodeId(), mapping.beforeTargetEquipment())
                        || !uniqueEquipmentNode(after, afterEdge.sourceNodeId(), mapping.afterSourceEquipment())
                        || !uniqueEquipmentNode(after, afterEdge.targetNodeId(), mapping.afterTargetEquipment())
                        || !sameText(beforeSource, mapping.beforeSourceEquipment()) || !sameText(beforeTarget, mapping.beforeTargetEquipment())
                        || !sameText(afterSource, mapping.afterSourceEquipment()) || !sameText(afterTarget, mapping.afterTargetEquipment())
                        || !sameText(beforeEdge.sourceProcess(), mapping.beforeSourceProcess())
                        || !sameText(beforeEdge.targetProcess(), mapping.beforeTargetProcess())
                        || !sameText(afterEdge.sourceProcess(), mapping.afterSourceProcess())
                        || !sameText(afterEdge.targetProcess(), mapping.afterTargetProcess()))
                    blockers.add("DRAWING_TO_NETWORK_ENDPOINT_MAPPING_MISMATCH_OR_AMBIGUOUS");
                if (beforeEdge.quantity() == null || afterEdge.quantity() == null
                        || beforeEdge.quantity().compareTo(afterEdge.quantity()) != 0
                        || beforeEdge.materialClass() != afterEdge.materialClass()
                        || !sameText(beforeEdge.materialType(), afterEdge.materialType())
                        || !sameText(beforeEdge.unit(), afterEdge.unit()) || !sameText(beforeEdge.period(), afterEdge.period())
                        || beforeEdge.connectionType() != afterEdge.connectionType()
                        || beforeEdge.quantityBasis() != afterEdge.quantityBasis())
                    blockers.add("PIPE_CHANGE_MUST_NOT_CHANGE_OR_INFER_FLOW_BASIS");
                if (!beforeEdge.fixed() || !afterEdge.fixed()) blockers.add("PIPE_CHANGE_REQUIRES_EXPLICIT_FIXED_TEST_ONLY_FLOW");
                if (blank(beforeEdge.evidence()) || blank(afterEdge.evidence())) blockers.add("WHAT_IF_EDGE_EVIDENCE_REQUIRED");
            }
            if (!sameUnchangedEdges(before, after, mapping.beforeConnectionId(), mapping.afterConnectionId())) blockers.add("UNRELATED_WHAT_IF_EDGE_CHANGED_OR_MAPPING_AMBIGUOUS");
        }
        boolean preserved = mapping != null && before != null && after != null
                && sameUnchangedEdges(before, after, mapping.beforeConnectionId(), mapping.afterConnectionId());
        String beforeSource = mapping == null ? null : mapping.beforeSourceEquipment();
        String beforeTarget = mapping == null ? null : mapping.beforeTargetEquipment();
        String afterSource = mapping == null ? null : mapping.afterSourceEquipment();
        String afterTarget = mapping == null ? null : mapping.afterTargetEquipment();
        return new WhatIfMappingComparison(blockers.isEmpty() ? "SYNTHETIC_MAPPING_VERIFIED_TEST_ONLY" : "REVIEW_REQUIRED",
                mapping == null ? null : mapping.beforeConnectionId(), mapping == null ? null : mapping.afterConnectionId(),
                beforeSource, beforeTarget, afterSource, afterTarget, preserved, blockers,
                "CALCULATION_UNAVAILABLE", "Mapping validation is not a live material-balance approval.");
    }

    public WhatIfCalculationComparison compareCalculatedWhatIf(Assessment assessment, SyntheticWhatIfMapping mapping,
            com.example.processautomation.material.NetworkWhatIfService.WhatIfReport before,
            com.example.processautomation.material.NetworkWhatIfService.WhatIfReport after) {
        List<String> blockers = new ArrayList<>();
        if (assessment == null || mapping == null || before == null || after == null)
            blockers.add("ASSESSMENT_MAPPING_AND_CALCULATED_REPORTS_REQUIRED");
        if (blockers.isEmpty()) {
            if (!"SYNTHETIC_EDITABLE_TEST_ONLY".equals(assessment.status())
                    || !Objects.equals(assessment.connectorShapeId(), mapping.connectorShapeId())
                    || !Objects.equals(assessment.beforeStartShapeId(), mapping.beforeStartShapeId())
                    || !Objects.equals(assessment.beforeEndShapeId(), mapping.beforeEndShapeId())
                    || !Objects.equals(assessment.afterStartShapeId(), mapping.afterStartShapeId())
                    || !Objects.equals(assessment.afterEndShapeId(), mapping.afterEndShapeId()))
                blockers.add("DRAWING_MAPPING_DOES_NOT_MATCH_ASSESSMENT");
            if (!"TEST_ONLY".equals(before.mode()) || !"TEST_ONLY".equals(after.mode())
                    || !before.simulationId().equals(after.simulationId())) blockers.add("WHAT_IF_REPORT_SCOPE_MISMATCH");
            var beforeEdges = com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(before);
            var afterEdges = com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(after);
            var beforeEdge = uniqueResultEdge(beforeEdges, mapping.beforeConnectionId(), blockers, "BEFORE");
            var afterEdge = uniqueResultEdge(afterEdges, mapping.afterConnectionId(), blockers, "AFTER");
            if (beforeEdge != null && afterEdge != null) {
                if (!sameText(beforeEdge.sourceEquipment(), mapping.beforeSourceEquipment())
                        || !sameText(beforeEdge.targetEquipment(), mapping.beforeTargetEquipment())
                        || !sameText(afterEdge.sourceEquipment(), mapping.afterSourceEquipment())
                        || !sameText(afterEdge.targetEquipment(), mapping.afterTargetEquipment())
                        || !sameNullableText(beforeEdge.sourceProcess(), mapping.beforeSourceProcess())
                        || !sameNullableText(beforeEdge.targetProcess(), mapping.beforeTargetProcess())
                        || !sameNullableText(afterEdge.sourceProcess(), mapping.afterSourceProcess())
                        || !sameNullableText(afterEdge.targetProcess(), mapping.afterTargetProcess()))
                    blockers.add("DRAWING_AND_CALCULATED_ENDPOINTS_DISAGREE");
                if (!sameText(beforeEdge.connectionId(), mapping.beforeConnectionId())
                        || !sameText(afterEdge.connectionId(), mapping.afterConnectionId())
                        || beforeEdge.quantity() == null || afterEdge.quantity() == null
                        || beforeEdge.quantity().compareTo(afterEdge.quantity()) != 0
                        || beforeEdge.materialClass() != afterEdge.materialClass()
                        || !sameText(beforeEdge.materialType(), afterEdge.materialType())
                        || !sameText(beforeEdge.unit(), afterEdge.unit()) || !sameText(beforeEdge.period(), afterEdge.period())
                        || beforeEdge.connectionType() != afterEdge.connectionType()
                        || !beforeEdge.fixed() || !afterEdge.fixed()
                        || beforeEdge.quantityBasis() != com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis.USER_INPUT
                        || afterEdge.quantityBasis() != com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis.USER_INPUT)
                    blockers.add("PIPE_CHANGE_CALCULATED_EDGE_BASIS_DIFFERS");
                if (before.scenarios().stream().anyMatch(scenario -> !scenario.result().impactedEquipmentNumbers()
                        .containsAll(List.of(mapping.beforeSourceEquipment(), mapping.beforeTargetEquipment())))
                        || after.scenarios().stream().anyMatch(scenario -> !scenario.result().impactedEquipmentNumbers()
                        .containsAll(List.of(mapping.afterSourceEquipment(), mapping.afterTargetEquipment()))))
                    blockers.add("WHAT_IF_IMPACTED_EQUIPMENT_DOES_NOT_INCLUDE_MAPPED_ENDPOINTS");
            }
            if (!sameUnchangedResultEdges(beforeEdges, afterEdges, mapping.beforeConnectionId(), mapping.afterConnectionId()))
                blockers.add("UNRELATED_CALCULATED_EDGE_CHANGED");
        }
        boolean endpointsMatch = blockers.stream().noneMatch(item -> item.contains("ENDPOINT") || item.contains("MAPPING"));
        boolean unaffected = before != null && after != null
                && sameUnchangedResultEdges(com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(before),
                        com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(after),
                        mapping == null ? null : mapping.beforeConnectionId(), mapping == null ? null : mapping.afterConnectionId());
        boolean calculated = com.example.processautomation.material.NetworkWhatIfService.fullyCalculated(before)
                && com.example.processautomation.material.NetworkWhatIfService.fullyCalculated(after);
        String status = !blockers.isEmpty() ? "REVIEW_REQUIRED" : calculated
                ? "DRAWING_WHATIF_MATCHED_TEST_ONLY" : "CALCULATION_UNAVAILABLE";
        return new WhatIfCalculationComparison(status, mapping == null ? null : mapping.beforeConnectionId(),
                mapping == null ? null : mapping.afterConnectionId(), mapping == null ? null : mapping.beforeSourceEquipment(), mapping == null ? null : mapping.beforeTargetEquipment(),
                mapping == null ? null : mapping.afterSourceEquipment(), mapping == null ? null : mapping.afterTargetEquipment(),
                endpointsMatch, unaffected, calculated, "CALCULATION_UNAVAILABLE", blockers,
                "Drawing/What-if agreement is synthetic only; it does not establish physical connection evidence or live readiness.");
    }

    private com.example.processautomation.material.NetworkWhatIfService.CalculatedEdgeSnapshot uniqueResultEdge(
            List<com.example.processautomation.material.NetworkWhatIfService.CalculatedEdgeSnapshot> edges,
            String connectionId, List<String> blockers, String label) {
        var matches = edges.stream().filter(edge -> Objects.equals(edge.connectionId(), connectionId)).toList();
        if (matches.size() != 1) { blockers.add(label + "_CALCULATED_EDGE_NOT_UNIQUE"); return null; }
        return matches.get(0);
    }

    private boolean sameUnchangedEdges(com.example.processautomation.material.NetworkWhatIfRequest before,
                                       com.example.processautomation.material.NetworkWhatIfRequest after,
                                       String beforeChangedId, String afterChangedId) {
        if (before == null || after == null || beforeChangedId == null || afterChangedId == null) return false;
        var beforeEdges = before.edges().stream().filter(edge -> !beforeChangedId.equals(edge.connectionId())).toList();
        var afterEdges = after.edges().stream().filter(edge -> !afterChangedId.equals(edge.connectionId())).toList();
        if (beforeEdges.size() != afterEdges.size()) return false;
        Map<String, List<com.example.processautomation.material.NetworkWhatIfRequest.Edge>> beforeById = new LinkedHashMap<>();
        Map<String, List<com.example.processautomation.material.NetworkWhatIfRequest.Edge>> afterById = new LinkedHashMap<>();
        beforeEdges.forEach(edge -> beforeById.computeIfAbsent(edge.connectionId(), ignored -> new ArrayList<>()).add(edge));
        afterEdges.forEach(edge -> afterById.computeIfAbsent(edge.connectionId(), ignored -> new ArrayList<>()).add(edge));
        return beforeById.equals(afterById) && beforeById.values().stream().allMatch(values -> values.size() == 1);
    }

    private boolean sameUnchangedResultEdges(List<com.example.processautomation.material.NetworkWhatIfService.CalculatedEdgeSnapshot> before,
                                             List<com.example.processautomation.material.NetworkWhatIfService.CalculatedEdgeSnapshot> after,
                                             String beforeChangedId, String afterChangedId) {
        if (before == null || after == null || beforeChangedId == null || afterChangedId == null) return false;
        var beforeEdges = before.stream().filter(edge -> !beforeChangedId.equals(edge.connectionId())).toList();
        var afterEdges = after.stream().filter(edge -> !afterChangedId.equals(edge.connectionId())).toList();
        return beforeEdges.size() == afterEdges.size() && beforeEdges.stream().allMatch(edge -> {
            var matches = afterEdges.stream().filter(candidate -> edge.connectionId().equals(candidate.connectionId())).toList();
            if (matches.size() != 1) return false;
            var candidate = matches.get(0);
            boolean sameTopology = sameText(edge.sourceEquipment(), candidate.sourceEquipment())
                    && sameText(edge.targetEquipment(), candidate.targetEquipment())
                    && sameNullableText(edge.sourceProcess(), candidate.sourceProcess())
                    && sameNullableText(edge.targetProcess(), candidate.targetProcess())
                    && edge.materialClass() == candidate.materialClass()
                    && sameText(edge.materialType(), candidate.materialType())
                    && sameText(edge.unit(), candidate.unit()) && edge.connectionType() == candidate.connectionType()
                    && sameText(edge.period(), candidate.period()) && edge.fixed() == candidate.fixed()
                    && edge.quantityBasis() == candidate.quantityBasis() && sameText(edge.evidence(), candidate.evidence());
            return sameTopology && (!edge.fixed() || edge.quantity() != null && candidate.quantity() != null
                    && edge.quantity().compareTo(candidate.quantity()) == 0);
        });
    }

    private String equipmentFor(com.example.processautomation.material.NetworkWhatIfRequest network, String nodeId) {
        return network.nodes().stream().filter(node -> nodeId.equals(node.nodeId()))
                .map(com.example.processautomation.material.NetworkWhatIfRequest.Node::equipmentNumber)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private boolean uniqueEquipmentNode(com.example.processautomation.material.NetworkWhatIfRequest network,
                                        String nodeId, String equipment) {
        return network.nodes().stream().filter(node -> Objects.equals(node.nodeId(), nodeId)
                && Objects.equals(node.equipmentNumber(), equipment)
                && node.nodeType() == com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT).count() == 1
                && network.nodes().stream().filter(node -> Objects.equals(node.equipmentNumber(), equipment)
                && node.nodeType() == com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT).count() == 1;
    }

    private boolean sameText(String first, String second) {
        return first != null && second != null && first.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(second.trim().replaceAll("\\s+", " "));
    }

    private boolean sameNullableText(String first, String second) {
        return first == null || second == null ? first == second : sameText(first, second);
    }

    /** Re-analyzes the current bytes and requires an exact assessment fingerprint match before writing. */
    public Generated generate(byte[] workbook, Request request, Assessment preview) {
        if (preview == null || !"SYNTHETIC_EDITABLE_TEST_ONLY".equals(preview.status()))
            throw new IllegalArgumentException("Only a successful synthetic PIPE_CHANGE assessment may be generated");
        Assessment current = analyze(workbook, request);
        if (!preview.analysisFingerprint().equals(current.analysisFingerprint()))
            throw new IllegalStateException("PIPE_CHANGE preview is stale; analyze the current workbook again");
        if (!"SYNTHETIC_EDITABLE_TEST_ONLY".equals(current.status()))
            throw new IllegalStateException("PIPE_CHANGE is no longer eligible: " + current.blockers());

        String drawingPart = current.drawingPartName();
        byte[] drawingXml = parts.readPart(workbook, drawingPart);
        Document document = parse(drawingXml);
        Element connector = findObject(document, request.connectorShapeId());
        if (connector == null || !"cxnSp".equals(connector.getLocalName()))
            throw new IllegalStateException("Target connector changed after assessment");
        Element startConnection = first(connector, A, "stCxn");
        Element endConnection = first(connector, A, "endCxn");
        if (startConnection == null || endConnection == null) throw new IllegalStateException("Connector binding disappeared");
        startConnection.setAttribute("id", request.afterStartShapeId());
        endConnection.setAttribute("id", request.afterEndShapeId());

        WorkbookInventory.DrawingObject startShape = findDrawingObject(current.objects(), request.afterStartShapeId());
        WorkbookInventory.DrawingObject endShape = findDrawingObject(current.objects(), request.afterEndShapeId());
        Point start = endpointAnchorPoint(startShape, workbook, request.sheetName());
        Point end = endpointAnchorPoint(endShape, workbook, request.sheetName());
        setConnectorGeometry(document, connector, start, end);
        byte[] changedDrawing = serialize(document);
        byte[] output = parts.replacePackagePart(workbook, drawingPart, changedDrawing);
        verifyOutput(workbook, output, request, current, drawingPart);
        return new Generated(output, sha256(output), drawingPart, current.analysisFingerprint(),
                "SYNTHETIC_TEST_ONLY; physical connection remains unverified; no flow was inferred");
    }

    private Assessment assessment(String status, Request request, String sha, String drawingPart,
                                  List<WorkbookInventory.DrawingObject> objects, List<String> blockers) {
        return new Assessment(status, request.sheetName(), request.connectorShapeId(), request.beforeStartShapeId(),
                request.beforeEndShapeId(), request.afterStartShapeId(), request.afterEndShapeId(), sha,
                request.mappingEvidence(), request.physicalEvidenceStatus(), drawingPart, List.copyOf(objects),
                List.copyOf(blockers), fingerprint(status, request, sha, drawingPart, objects, blockers));
    }

    private Assessment blocked(String status, List<String> blockers, String sha, String drawingPart) {
        Request empty = new Request(null, null, null, null, null, null, null, null, null,
                false, null, null, null);
        return assessment(status, empty, sha, drawingPart, List.of(), blockers);
    }

    private String fingerprint(String status, Request request, String sha, String drawingPart,
                               List<WorkbookInventory.DrawingObject> objects, List<String> blockers) {
        StringBuilder text = new StringBuilder();
        for (Object value : List.of(nullSafe(status), nullSafe(request.workingDocumentId()), nullSafe(request.sheetName()),
                nullSafe(request.connectorShapeId()), nullSafe(request.beforeStartShapeId()), nullSafe(request.beforeEndShapeId()),
                nullSafe(request.afterStartShapeId()), nullSafe(request.afterEndShapeId()), nullSafe(sha),
                nullSafe(request.expectedWorkingSha256()), nullSafe(request.mappingEvidence()),
                nullSafe(request.physicalEvidenceStatus()), nullSafe(request.sourceSheetName()),
                nullSafe(request.targetSheetName()), request.syntheticFixture(), nullSafe(drawingPart))) append(text, value);
        objects.stream().sorted(java.util.Comparator.comparingInt(WorkbookInventory.DrawingObject::objectIndex))
                .forEach(object -> append(text, object.toString()));
        blockers.stream().sorted().forEach(value -> append(text, value));
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void verifyOutput(byte[] original, byte[] output, Request request, Assessment assessment, String drawingPart) {
        var inventory = inspector.inspectSheet(output, request.sheetName());
        List<WorkbookInventory.DrawingObject> target = inventory.drawingObjects().stream()
                .filter(object -> request.connectorShapeId().equals(object.shapeId())).toList();
        if (target.size() != 1) throw new IllegalStateException("Generated connector ID is missing or duplicated");
        var connector = target.get(0);
        if (!request.afterStartShapeId().equals(connector.startConnectedShapeId())
                || !request.afterEndShapeId().equals(connector.endConnectedShapeId()))
            throw new IllegalStateException("Generated connector endpoint IDs do not match the requested mapping");
        if (connector.startAnchorXEmu() == null || connector.endAnchorXEmu() == null
                || connector.startAnchorYEmu() == null || connector.endAnchorYEmu() == null
                || (connector.startAnchorXEmu().equals(connector.endAnchorXEmu())
                    && connector.startAnchorYEmu().equals(connector.endAnchorYEmu())))
            throw new IllegalStateException("Generated connector geometry is missing or degenerate");
        Point expectedStart = endpointAnchorPoint(findDrawingObject(inventory.drawingObjects(), request.afterStartShapeId()),
                original, request.sheetName());
        Point expectedEnd = endpointAnchorPoint(findDrawingObject(inventory.drawingObjects(), request.afterEndShapeId()),
                original, request.sheetName());
        if (!close(connector.startAnchorXEmu(), expectedStart.absX())
                || !close(connector.startAnchorYEmu(), expectedStart.absY())
                || !close(connector.endAnchorXEmu(), expectedEnd.absX())
                || !close(connector.endAnchorYEmu(), expectedEnd.absY()))
            throw new IllegalStateException("Generated DrawingML anchor does not meet mapped endpoint geometry");
        for (var before : assessment.objects()) {
            if (request.connectorShapeId().equals(before.shapeId())) continue;
            List<WorkbookInventory.DrawingObject> after = inventory.drawingObjects().stream()
                    .filter(item -> Objects.equals(before.shapeId(), item.shapeId())).toList();
            if (after.size() != 1 || !before.equals(after.get(0)))
                throw new IllegalStateException("An unrelated DrawingML object changed: " + before.shapeId());
        }
        if (!sameNonTargetParts(original, output, drawingPart))
            throw new IllegalStateException("An OOXML package part outside the target drawing changed");
        try (var before = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(original));
             var after = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(output))) {
            if (before.getNumberOfSheets() != after.getNumberOfSheets()) throw new IllegalStateException("Worksheet count changed");
            for (int index = 0; index < before.getNumberOfSheets(); index++) {
                var oldSheet = before.getSheetAt(index);
                var newSheet = after.getSheetAt(index);
                if (!oldSheet.getSheetName().equals(newSheet.getSheetName())
                        || oldSheet.getNumMergedRegions() != newSheet.getNumMergedRegions()
                        || !Objects.equals(before.getPrintArea(index), after.getPrintArea(index)))
                    throw new IllegalStateException("Worksheet or print layout changed");
                for (int merge = 0; merge < oldSheet.getNumMergedRegions(); merge++)
                    if (!oldSheet.getMergedRegion(merge).formatAsString().equals(newSheet.getMergedRegion(merge).formatAsString()))
                        throw new IllegalStateException("Merged cell layout changed");
                if (index != before.getSheetIndex(request.sheetName())) continue;
                for (var row : oldSheet) for (var cell : row) {
                    var currentRow = newSheet.getRow(row.getRowNum());
                    var currentCell = currentRow == null ? null : currentRow.getCell(cell.getColumnIndex());
                    if (currentCell == null || cell.getCellType() != currentCell.getCellType()
                            || !cell.toString().equals(currentCell.toString())
                            || cell.getCellStyle().getIndex() != currentCell.getCellStyle().getIndex())
                        throw new IllegalStateException("Cell value or style changed at " + cell.getAddress());
                }
            }
        } catch (IllegalStateException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("Unable to reopen generated workbook", exception); }
        // A complete ZIP read is also an integrity check (duplicate/malformed entries fail in the part reader/POI pass).
        parts.readPart(output, drawingPart);
    }

    private boolean sameNonTargetParts(byte[] before, byte[] after, String targetPart) {
        Map<String, String> left = partHashes(before);
        Map<String, String> right = partHashes(after);
        left.remove(targetPart); right.remove(targetPart);
        return left.equals(right);
    }

    private Map<String, String> partHashes(byte[] bytes) {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), sha256(input.readAllBytes())); }
            }
            return result;
        } catch (Exception exception) { throw new IllegalStateException("Unable to verify OOXML package parts", exception); }
    }

    private boolean sameSheetAnchor(WorkbookInventory.DrawingObject object) {
        return object.startColumn() != null && object.endColumn() != null && object.startRow() != null && object.endRow() != null
                && object.endColumn() == object.startColumn() + 1 && object.endRow() == object.startRow() + 1
                && zero(object.startColumnOffset()) && zero(object.startRowOffset())
                && zero(object.endColumnOffset()) && zero(object.endRowOffset());
    }

    private boolean zero(Long value) { return value != null && value == 0L; }

    private boolean close(Long actual, Long expected) {
        return actual != null && expected != null && Math.abs(actual - expected) <= 9_525L;
    }

    private boolean touches(WorkbookInventory.DrawingObject object, String... ids) {
        for (String id : ids) if (id != null && (id.equals(object.startConnectedShapeId()) || id.equals(object.endConnectedShapeId()))) return true;
        return false;
    }

    private boolean lineIntersectsObject(Point start, Point end, WorkbookInventory.DrawingObject object) {
        if (object.startAnchorXEmu() == null || object.endAnchorXEmu() == null
                || object.startAnchorYEmu() == null || object.endAnchorYEmu() == null
                || start.absX() == null || end.absX() == null || start.absY() == null || end.absY() == null) return true;
        double x0 = start.absX(), y0 = start.absY(), dx = end.absX() - x0, dy = end.absY() - y0;
        double minX = Math.min(object.startAnchorXEmu(), object.endAnchorXEmu());
        double maxX = Math.max(object.startAnchorXEmu(), object.endAnchorXEmu());
        double minY = Math.min(object.startAnchorYEmu(), object.endAnchorYEmu());
        double maxY = Math.max(object.startAnchorYEmu(), object.endAnchorYEmu());
        // Expand zero-width/height anchors slightly so touching a line or point fails closed.
        if (maxX == minX) { minX -= 1d; maxX += 1d; }
        if (maxY == minY) { minY -= 1d; maxY += 1d; }
        double[] range = {0d, 1d};
        return clip(-dx, x0 - minX, range) && clip(dx, maxX - x0, range)
                && clip(-dy, y0 - minY, range) && clip(dy, maxY - y0, range);
    }

    private boolean clip(double p, double q, double[] range) {
        if (p == 0d) return q >= 0d;
        double t = q / p;
        if (p < 0d) {
            if (t > range[1]) return false;
            if (t > range[0]) range[0] = t;
        } else {
            if (t < range[0]) return false;
            if (t < range[1]) range[1] = t;
        }
        return true;
    }

    private Point endpointAnchorPoint(WorkbookInventory.DrawingObject object, byte[] workbookBytes, String sheetName) {
        if (!sameSheetAnchor(object)) throw new IllegalStateException("Endpoint shape anchor is not in supported one-cell form");
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new IllegalStateException("Endpoint sheet is missing");
            long width = Math.round(sheet.getColumnWidthInPixels(object.startColumn()) * 9525d);
            var row = sheet.getRow(object.startRow());
            long height = Math.round((row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * 12700d);
            long halfWidth = width / 2L;
            long halfHeight = height / 2L;
            return new Point(object.startColumn(), object.startRow(), halfWidth, halfHeight,
                    object.startAnchorXEmu() == null ? null : object.startAnchorXEmu() + halfWidth,
                    object.startAnchorYEmu() == null ? null : object.startAnchorYEmu() + halfHeight);
        } catch (IllegalStateException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("Unable to resolve endpoint shape center", exception); }
    }

    private void setConnectorGeometry(Document document, Element connector, Point start, Point end) {
        if (start.absX() == null || start.absY() == null || end.absX() == null || end.absY() == null)
            throw new IllegalStateException("Endpoint cell coordinates cannot be resolved");
        Element anchor = parentAnchor(connector);
        Element from = direct(anchor, XDR, "from");
        Element to = direct(anchor, XDR, "to");
        if (from == null || to == null) throw new IllegalStateException("Connector must use a two-cell anchor");
        setCoordinate(from, "col", start.column()); setCoordinate(from, "row", start.row());
        setCoordinate(from, "colOff", start.offsetX()); setCoordinate(from, "rowOff", start.offsetY());
        setCoordinate(to, "col", end.column()); setCoordinate(to, "row", end.row());
        setCoordinate(to, "colOff", end.offsetX()); setCoordinate(to, "rowOff", end.offsetY());
        Element xfrm = first(connector, A, "xfrm");
        Element off = xfrm == null ? null : direct(xfrm, A, "off");
        Element ext = xfrm == null ? null : direct(xfrm, A, "ext");
        if (off == null || ext == null) throw new IllegalStateException("Connector transform is incomplete");
        off.setAttribute("x", Long.toString(Math.min(start.absX(), end.absX())));
        off.setAttribute("y", Long.toString(Math.min(start.absY(), end.absY())));
        ext.setAttribute("cx", Long.toString(Math.abs(end.absX() - start.absX())));
        ext.setAttribute("cy", Long.toString(Math.abs(end.absY() - start.absY())));
        if (xfrm.hasAttribute("flipH")) xfrm.setAttribute("flipH", start.absX() > end.absX() ? "1" : "0");
        else if (start.absX() > end.absX()) xfrm.setAttribute("flipH", "1");
        if (xfrm.hasAttribute("flipV")) xfrm.setAttribute("flipV", start.absY() > end.absY() ? "1" : "0");
        else if (start.absY() > end.absY()) xfrm.setAttribute("flipV", "1");
    }

    private void setCoordinate(Element parent, String name, int value) { setCoordinate(parent, name, (long) value); }
    private void setCoordinate(Element parent, String name, long value) {
        Element child = direct(parent, XDR, name);
        if (child == null) { child = parent.getOwnerDocument().createElementNS(XDR, "xdr:" + name); parent.appendChild(child); }
        child.setTextContent(Long.toString(value));
    }

    private Element parentAnchor(Element object) {
        Node node = object.getParentNode();
        while (node instanceof Element element) {
            if (XDR.equals(element.getNamespaceURI()) && "twoCellAnchor".equals(element.getLocalName())) return element;
            node = node.getParentNode();
        }
        return null;
    }

    private Element findObject(Document document, String id) {
        var all = document.getElementsByTagNameNS(XDR, "cxnSp");
        Element match = null;
        for (int index = 0; index < all.getLength(); index++) {
            Element candidate = (Element) all.item(index);
            Element nv = first(candidate, XDR, "cNvPr");
            if (nv != null && id.equals(nv.getAttribute("id"))) {
                if (match != null) return null;
                match = candidate;
            }
        }
        return match;
    }

    private WorkbookInventory.DrawingObject findDrawingObject(List<WorkbookInventory.DrawingObject> objects, String id) {
        return objects.stream().filter(item -> id.equals(item.shapeId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Mapped endpoint shape disappeared"));
    }

    private boolean hasZeroPortIndexes(byte[] workbook, String drawingPart, String connectorId) {
        Document document = parse(parts.readPart(workbook, drawingPart));
        Element connector = findObject(document, connectorId);
        if (connector == null) return false;
        Element start = first(connector, A, "stCxn");
        Element end = first(connector, A, "endCxn");
        return start != null && end != null && "0".equals(start.getAttribute("idx"))
                && "0".equals(end.getAttribute("idx"));
    }

    private Document parse(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception exception) { throw new IllegalStateException("Drawing XML is invalid", exception); }
    }

    private byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer(); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output)); return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException("Unable to serialize changed DrawingML", exception); }
    }

    private Element first(Element parent, String namespace, String local) {
        if (parent == null) return null;
        var nodes = parent.getElementsByTagNameNS(namespace, local);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private Element direct(Element parent, String namespace, String local) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI()) && local.equals(element.getLocalName())) return element;
        return null;
    }

    private void append(StringBuilder target, Object value) {
        String text = String.valueOf(value); target.append(text.length()).append(':').append(text).append('|');
    }
    private String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String sha256(java.io.InputStream input) throws java.io.IOException { return sha256(input.readAllBytes()); }
    private String nullSafe(String value) { return value == null ? "<null>" : value; }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record Point(int column, int row, long offsetX, long offsetY, Long absX, Long absY) { }

    public record Request(String workingDocumentId, String sheetName, String expectedWorkingSha256,
                          String connectorShapeId, String beforeStartShapeId, String beforeEndShapeId,
                          String afterStartShapeId, String afterEndShapeId, String mappingEvidence,
                          boolean syntheticFixture, String physicalEvidenceStatus,
                          String sourceSheetName, String targetSheetName) { }

    public record Assessment(String status, String sheetName, String connectorShapeId,
                             String beforeStartShapeId, String beforeEndShapeId,
                             String afterStartShapeId, String afterEndShapeId,
                             String actualWorkingSha256, String mappingEvidence,
                             String physicalEvidenceStatus, String drawingPartName,
                             List<WorkbookInventory.DrawingObject> objects, List<String> blockers,
                             String analysisFingerprint) { }

    public record SyntheticWhatIfMapping(String beforeConnectionId, String afterConnectionId, String connectorShapeId,
                                         String beforeStartShapeId, String beforeEndShapeId,
                                         String afterStartShapeId, String afterEndShapeId,
                                         String beforeSourceProcess, String beforeSourceEquipment,
                                         String beforeTargetProcess, String beforeTargetEquipment,
                                         String afterSourceProcess, String afterSourceEquipment,
                                         String afterTargetProcess, String afterTargetEquipment,
                                         String evidenceId) { }

    public record WhatIfMappingComparison(String status, String beforeConnectionId, String afterConnectionId,
                                          String beforeSourceEquipment, String beforeTargetEquipment,
                                          String afterSourceEquipment, String afterTargetEquipment,
                                          boolean unchangedEdgesPreserved, List<String> blockers,
                                          String officialReadiness, String interpretation) {
        public WhatIfMappingComparison { blockers = List.copyOf(blockers); }
    }

    public record WhatIfCalculationComparison(String status, String beforeConnectionId, String afterConnectionId,
                                               String beforeSourceEquipment, String beforeTargetEquipment,
                                               String afterSourceEquipment, String afterTargetEquipment,
                                               boolean drawingAndWhatIfEndpointsAgree,
                                               boolean unchangedEdgesPreserved, boolean bothReportsCalculated,
                                               String officialReadiness, List<String> blockers,
                                               String interpretation) {
        public WhatIfCalculationComparison { blockers = List.copyOf(blockers); }
    }

    public record Generated(byte[] bytes, String outputSha256, String changedDrawingPart,
                            String assessmentFingerprint, String safetyNotice) { }
}
