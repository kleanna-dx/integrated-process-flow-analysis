package com.example.processautomation.material;

import static com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW;
import static com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW;
import static com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SINK;
import static com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Fails-closed adapter from versioned Step 7-4A reviews to the TEST_ONLY Step 7-1 graph model. */
@Component
public class Step7ReviewNetworkAdapter {
    public PreparedNetwork adapt(List<ReviewEvidence> reviews, List<RouteEvidence> routes, GraphScope scope) {
        List<ReviewEvidence> safeReviews = reviews == null ? List.of() : List.copyOf(reviews);
        List<RouteEvidence> safeRoutes = routes == null ? List.of() : List.copyOf(routes);
        List<String> graphIssues = validateScope(scope);
        Map<String, List<RouteEvidence>> byCandidate = new LinkedHashMap<>();
        for (RouteEvidence route : safeRoutes) {
            byCandidate.computeIfAbsent(safe(route.candidateId()), ignored -> new ArrayList<>()).add(route);
        }
        Set<String> reviewIds = new LinkedHashSet<>();
        List<CandidateAssessment> assessments = new ArrayList<>();
        List<DirectedProcessNetwork.Edge> edges = new ArrayList<>();
        List<EdgeProvenance> provenance = new ArrayList<>();
        Map<String, String> streamUnit = new HashMap<>();
        Map<String, String> streamPeriod = new HashMap<>();
        Map<String, DirectedProcessNetwork.Node> nodes = scope == null
                ? Map.of() : indexNodes(scope.nodes());

        for (ReviewEvidence review : safeReviews) {
            List<String> reasons = new ArrayList<>();
            if (!reviewIds.add(safe(review.candidateId()))) reasons.add("DUPLICATE_CANDIDATE_REVIEW");
            if (scope == null || !scope.expectedCandidateIds().contains(review.candidateId())) reasons.add("OUTSIDE_DECLARED_SCOPE");
            if (!"V1.0".equals(review.baselineVersion()) || !review.baselineLocked()) reasons.add("BASELINE_NOT_LOCKED_V1_0");
            if (scope != null && !scope.snapshotId().equals(review.snapshotId())) reasons.add("SNAPSHOT_MISMATCH");
            if (!review.syntheticFixture() && (review.reviewCaseId() <= 0 || review.reviewHistoryId() <= 0)) reasons.add("CURRENT_REVIEW_HISTORY_MISSING");
            if (review.reviewVersion() <= 0 || review.reviewVersion() != review.historyReviewVersion()) reasons.add("REVIEW_HISTORY_VERSION_STALE");
            if (!review.syntheticFixture() && !safe(review.reviewState()).equals(safe(review.historyReviewState()))) reasons.add("REVIEW_HISTORY_STATE_STALE");
            if (!"EVIDENCE_VERIFIED".equals(review.reviewState())) reasons.add("REVIEW_STATE_NOT_EVIDENCE_VERIFIED:" + safe(review.reviewState()));
            if (!review.physicalConnectionConfirmed()) reasons.add("PHYSICAL_CONNECTION_NOT_CONFIRMED");
            if (review.officialApprovalCreated()) reasons.add("OFFICIAL_APPROVAL_FLAG_FORBIDDEN");
            if (blank(review.sourceEquipment()) || blank(review.targetEquipment())) reasons.add("ENDPOINT_EQUIPMENT_MISSING");
            if (blank(review.sourceProcess()) || blank(review.targetProcess())) reasons.add("PROCESS_MAPPING_MISSING");
            if (blank(review.materialType()) || blank(review.unit()) || blank(review.period())) reasons.add("MATERIAL_UNIT_OR_PERIOD_MISSING");
            if (review.quantity() == null && (!review.syntheticFixture() || routeQuantityMustBeFixed(byCandidate.get(review.candidateId())))) reasons.add("REVIEW_QUANTITY_MISSING");
            if (review.quantity() != null && review.quantity().signum() < 0) reasons.add("NEGATIVE_REVIEW_QUANTITY");
            if (blank(review.evidenceType()) || blank(review.evidenceIdentifier()) || blank(review.evidenceText())) reasons.add("REVIEW_EVIDENCE_INCOMPLETE");
            if (review.sourceRowNumber() < 1 || blank(review.drawingFile()) || blank(review.drawingSheet())) reasons.add("SOURCE_TRACEABILITY_MISSING");

            List<RouteEvidence> candidateRoutes = byCandidate.getOrDefault(review.candidateId(), List.of());
            if (candidateRoutes.isEmpty()) reasons.add("ROUTE_TOPOLOGY_PROFILE_MISSING");
            if (candidateRoutes.size() > 1) reasons.add("MULTIPLE_PATHS_REQUIRE_SEPARATE_REVIEW_CASES");
            if (scope != null && !same(review.period(), scope.period())) reasons.add("REVIEW_PERIOD_DIFFERS_FROM_SCOPE");
            RouteEvidence route = candidateRoutes.size() == 1 ? candidateRoutes.get(0) : null;
            if (route != null) {
                validateRoute(review, route, nodes, reasons);
                if (route.dimension() == null || route.dimension() == FlowDimension.UNKNOWN) reasons.add("UNIT_OR_PERIOD_UNVERIFIED");
                else if (!unitMatchesDimension(route.unit(), route.dimension())) reasons.add("UNIT_DIMENSION_MISMATCH");
                if (gasUnit(route.unit()) && (blank(route.gasReferenceCondition()) || route.gasCondition() == null
                        || route.gasCondition() == GasCondition.NOT_APPLICABLE || route.gasCondition() == GasCondition.UNKNOWN)) {
                    reasons.add("GAS_REFERENCE_CONDITION_REQUIRED");
                }
                String stream = streamKey(route.materialClass(), route.materialType());
                String oldUnit = streamUnit.putIfAbsent(stream, normalizedUnit(route.unit()));
                if (oldUnit != null && !oldUnit.equals(normalizedUnit(route.unit()))) reasons.add("MIXED_UNIT_NO_CONVERSION");
                String oldPeriod = streamPeriod.putIfAbsent(stream, normalizedPeriod(route.period()));
                if (oldPeriod != null && !oldPeriod.equals(normalizedPeriod(route.period()))) reasons.add("MIXED_PERIOD_NO_ALIGNMENT");
            }
            boolean included = reasons.isEmpty() && route != null;
            if (included) {
                String sourceNodeId = route.sourceNodeId();
                String targetNodeId = route.targetNodeId();
                edges.add(new DirectedProcessNetwork.Edge(review.candidateId(), sourceNodeId, targetNodeId,
                        review.sourceProcess(), review.targetProcess(), route.materialClass(), review.materialType(),
                        route.unit(), route.quantity(), route.connectionType(), route.quantityBasis(), true,
                        route.fixed(), evidenceSummary(review, route), "TEST_ONLY", route.period()));
                provenance.add(new EdgeProvenance(review.candidateId(), route.routeId(), review.candidateId(),
                        route.sourceNodeId(), route.targetNodeId(), review.reviewHistoryId(), review.reviewVersion(),
                        review.sourceRowNumber(), review.drawingFile(), review.drawingSheet(), review.sourceCellHint(),
                        review.targetCellHint(), review.connectorCandidateIds(), review.reviewer(), review.reviewState(),
                        review.evidenceType(), review.evidenceIdentifier(), review.evidenceText(), route.pathEvidenceIdentifier(),
                        route.quantityEvidenceIdentifier(), route.connectionType(), route.materialClass(), route.materialType(),
                        route.quantity(), route.unit(), route.period(), route.fixed(), review.sourceEquipment(),
                        review.targetEquipment(), review.sourceProcess(), review.targetProcess()));
            }
            assessments.add(new CandidateAssessment(review.candidateId(), review.reviewState(), included,
                    included ? "INCLUDED_TEST_ONLY" : "EXCLUDED_OR_HELD", List.copyOf(new LinkedHashSet<>(reasons)),
                    review.reviewVersion(), review.reviewHistoryId(), review.sourceRowNumber(), review.drawingFile(),
                    review.drawingSheet(), review.sourceCellHint(), review.targetCellHint(), review.connectorCandidateIds(),
                    review.reviewer(), review.evidenceType(), review.evidenceIdentifier(), review.evidenceText(),
                    review.sourceEquipment(), review.targetEquipment(), review.sourceProcess(), review.targetProcess(), review.materialType(),
                    review.quantity(), review.unit(), review.period()));
        }

        Set<String> extraProfiles = new LinkedHashSet<>(byCandidate.keySet());
        extraProfiles.removeAll(reviewIds);
        if (!extraProfiles.isEmpty()) graphIssues.add("TOPOLOGY_PROFILE_WITHOUT_REVIEW_CASE:" + String.join(",", extraProfiles));
        if (scope == null) graphIssues.add("GRAPH_SCOPE_REQUIRED");
        else {
            Set<String> includedIds = assessments.stream().filter(CandidateAssessment::eligible)
                    .map(CandidateAssessment::candidateId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!includedIds.equals(scope.expectedCandidateIds())) graphIssues.add("EXPECTED_CONNECTION_SCOPE_INCOMPLETE");
            graphIssues.addAll(validateGraphCompleteness(scope, nodes, edges));
        }
        if (assessments.stream().anyMatch(a -> !a.eligible())) graphIssues.add("ONE_OR_MORE_EXPECTED_CONNECTIONS_EXCLUDED");
        if (!graphIssues.isEmpty()) {
            return new PreparedNetwork(scope == null ? "" : scope.snapshotId(), "TEST_ONLY",
                    "CALCULATION_UNAVAILABLE", null, List.copyOf(assessments), List.copyOf(provenance),
                    List.copyOf(new LinkedHashSet<>(graphIssues)), false, List.of());
        }

        List<DirectedProcessNetwork.Node> preparedNodes = scope.nodes().stream().map(node ->
                new DirectedProcessNetwork.Node(node.nodeId(), node.equipmentNumber(), node.processCode(),
                        node.equipmentType(), node.nodeType(), node.label(), node.sourceDocument(), node.sourceSheet(),
                        "TEST_ONLY")).toList();
        DirectedProcessNetwork network = new DirectedProcessNetwork(scope.networkId(), scope.scenarioName(),
                "TEST_ONLY", preparedNodes, edges, scope.processTerms());
        return new PreparedNetwork(scope.snapshotId(), "TEST_ONLY", "READY_FOR_TEST_ONLY_SOLVER", network,
                List.copyOf(assessments), List.copyOf(provenance), List.of(), true, List.copyOf(scope.completenessEvidence()));
    }

    private void validateRoute(ReviewEvidence review, RouteEvidence route,
                               Map<String, DirectedProcessNetwork.Node> nodes, List<String> reasons) {
        if (!review.candidateId().equals(route.candidateId())) reasons.add("ROUTE_CANDIDATE_MISMATCH");
        if (!review.snapshotId().equals(route.snapshotId()) || !"V1.0".equals(route.baselineVersion())
                || !review.candidateId().equals(route.candidateId()) || review.reviewCaseId() != route.reviewCaseId()
                || review.reviewHistoryId() != route.reviewHistoryId() || review.reviewVersion() != route.reviewVersion()) {
            reasons.add("ROUTE_PROFILE_STALE_OR_NOT_BOUND_TO_REVIEW_VERSION");
        }
        if (blank(route.routeId()) || blank(route.pathEvidenceIdentifier()) || blank(route.pathDescription())) reasons.add("PHYSICAL_PATH_EVIDENCE_MISSING");
        boolean unknownCalculatedFlow = review.syntheticFixture() && !route.fixed()
                && route.quantityBasis() == DirectedProcessNetwork.QuantityBasis.CALCULATED
                && route.quantity() == null && review.quantity() == null;
        if (!unknownCalculatedFlow && (route.quantity() == null || review.quantity() == null
                || route.quantity().compareTo(review.quantity()) != 0)) reasons.add("ROUTE_QUANTITY_DIFFERS_FROM_REVIEWED_QUANTITY");
        if (blank(route.quantityEvidenceIdentifier())) reasons.add("QUANTITY_EVIDENCE_MISSING");
        if (route.connectionType() == null) reasons.add("CONNECTION_TYPE_MISSING");
        if (route.materialClass() == null || blank(route.materialType())) reasons.add("MATERIAL_CLASS_OR_TYPE_MISSING");
        if (!safe(review.materialType()).equals(safe(route.materialType()))) reasons.add("REVIEW_MATERIAL_TYPE_MISMATCH");
        if (!same(review.unit(), route.unit())) reasons.add("REVIEW_UNIT_MISMATCH");
        if (!same(review.period(), route.period())) reasons.add("REVIEW_PERIOD_MISMATCH");
        if (!route.directionConfirmed() || !route.topologyConfirmed()) reasons.add("DIRECTION_OR_TOPOLOGY_UNVERIFIED");
        if (route.quantityBasis() == null) reasons.add("QUANTITY_BASIS_MISSING");
        if (route.connectionType() == EXTERNAL_INFLOW && !isNodeType(nodes, route.sourceNodeId(), EXTERNAL_SOURCE)) reasons.add("EXTERNAL_INFLOW_BOUNDARY_MISMATCH");
        if (route.connectionType() == EXTERNAL_OUTFLOW && !isNodeType(nodes, route.targetNodeId(), EXTERNAL_SINK)) reasons.add("EXTERNAL_OUTFLOW_BOUNDARY_MISMATCH");
        if (route.connectionType() != EXTERNAL_INFLOW && isNodeType(nodes, route.sourceNodeId(), EXTERNAL_SOURCE)) reasons.add("EXTERNAL_SOURCE_EDGE_TYPE_MISMATCH");
        if (route.connectionType() != EXTERNAL_OUTFLOW && isNodeType(nodes, route.targetNodeId(), EXTERNAL_SINK)) reasons.add("EXTERNAL_SINK_EDGE_TYPE_MISMATCH");
        DirectedProcessNetwork.Node source = nodes.get(route.sourceNodeId());
        DirectedProcessNetwork.Node target = nodes.get(route.targetNodeId());
        if (source == null || target == null) reasons.add("GRAPH_ENDPOINT_NODE_MISSING");
        else {
            if (source.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT
                    && (!review.sourceEquipment().equals(source.equipmentNumber()) || !review.sourceProcess().equals(source.processCode()))) {
                reasons.add("SOURCE_NODE_DIFFERS_FROM_REVIEW_OR_MASTER");
            }
            if (target.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT
                    && (!review.targetEquipment().equals(target.equipmentNumber()) || !review.targetProcess().equals(target.processCode()))) {
                reasons.add("TARGET_NODE_DIFFERS_FROM_REVIEW_OR_MASTER");
            }
        }
    }

    private boolean routeQuantityMustBeFixed(List<RouteEvidence> routes) {
        if (routes == null || routes.size() != 1) return true;
        RouteEvidence route = routes.get(0);
        return route.fixed() || route.quantityBasis() != DirectedProcessNetwork.QuantityBasis.CALCULATED;
    }

    private List<String> validateScope(GraphScope scope) {
        List<String> issues = new ArrayList<>();
        if (scope == null) return new ArrayList<>(List.of("GRAPH_SCOPE_REQUIRED"));
        if (!"TEST_ONLY".equals(scope.mode())) issues.add("TEST_ONLY_MODE_REQUIRED");
        if (blank(scope.snapshotId()) || blank(scope.networkId()) || blank(scope.scenarioName())) issues.add("SNAPSHOT_OR_NETWORK_ID_REQUIRED");
        if (!"V1.0".equals(scope.baselineVersion()) || !scope.baselineLocked()) issues.add("SCOPE_BASELINE_NOT_LOCKED_V1_0");
        if (scope.expectedCandidateIds().isEmpty()) issues.add("EXPECTED_CONNECTIONS_REQUIRED");
        if (!scope.allRequiredConnectionsDeclared()) issues.add("SCOPE_CONNECTION_COMPLETENESS_UNCONFIRMED");
        if (!scope.boundariesDefined()) issues.add("EXTERNAL_BOUNDARY_SCOPE_UNCONFIRMED");
        if (!scope.branchAllocationsVerified()) issues.add("BRANCH_MERGE_ALLOCATION_UNVERIFIED");
        if (!scope.processTermsComplete()) issues.add("PROCESS_TERMS_COMPLETENESS_UNCONFIRMED");
        if (blank(scope.period())) issues.add("SCOPE_MEASUREMENT_PERIOD_REQUIRED");
        if (scope.nodes().isEmpty()) issues.add("NETWORK_NODES_REQUIRED");
        if (blank(scope.scopeEvidenceIdentifier())) issues.add("SCOPE_EVIDENCE_REQUIRED");
        return issues;
    }

    private List<String> validateGraphCompleteness(GraphScope scope, Map<String, DirectedProcessNetwork.Node> nodes,
                                                   List<DirectedProcessNetwork.Edge> edges) {
        List<String> issues = new ArrayList<>();
        if (nodes.isEmpty()) return List.of("NETWORK_NODES_REQUIRED");
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> equipmentNumbers = new HashSet<>();
        for (var node : scope.nodes()) {
            if (node == null || blank(node.nodeId()) || !nodeIds.add(node.nodeId())) issues.add("NODE_ID_MISSING_OR_DUPLICATE");
            else if (node.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT
                    && (blank(node.equipmentNumber()) || blank(node.processCode()) || !equipmentNumbers.add(node.equipmentNumber()))) {
                issues.add("EQUIPMENT_NUMBER_OR_PROCESS_INVALID:" + node.nodeId());
            }
            if (node != null && (node.nodeType() == EXTERNAL_SOURCE || node.nodeType() == EXTERNAL_SINK)
                    && blank(scope.boundaryEvidence().get(node.nodeId()))) issues.add("BOUNDARY_EVIDENCE_MISSING:" + node.nodeId());
        }
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (var edge : edges) {
            if (!edgeIds.add(edge.connectionId())) issues.add("EDGE_ID_DUPLICATE:" + edge.connectionId());
            if (!nodes.containsKey(edge.sourceNodeId()) || !nodes.containsKey(edge.targetNodeId())) issues.add("EDGE_ENDPOINT_UNRESOLVED:" + edge.connectionId());
            inDegree.merge(edge.targetNodeId(), 1, Integer::sum);
            outDegree.merge(edge.sourceNodeId(), 1, Integer::sum);
            graph.computeIfAbsent(edge.sourceNodeId(), ignored -> new LinkedHashSet<>()).add(edge.targetNodeId());
        }
        for (String nodeId : nodeIds) {
            if (inDegree.getOrDefault(nodeId, 0) > 1 || outDegree.getOrDefault(nodeId, 0) > 1) {
                if (!scope.branchAllocationsVerified()) issues.add("BRANCH_OR_MERGE_FLOW_NOT_VERIFIED:" + nodeId);
            }
        }
        if (hasCycle(nodeIds, graph) && !scope.cycleTopologyVerified()) issues.add("CYCLE_TOPOLOGY_UNVERIFIED");
        if (hasCycle(nodeIds, graph) && !scope.cycleFlowConditionsVerified()) issues.add("CYCLE_FLOW_CONDITIONS_UNVERIFIED");
        for (var term : scope.processTerms()) {
            if (term == null || blank(term.evidence()) || term.materialClass() == null || blank(term.materialType())
                    || blank(term.unit()) || term.netGeneration() == null || term.quantityBasis() == null) {
                issues.add("PROCESS_TERM_INCOMPLETE");
            }
        }
        return issues;
    }

    private boolean hasCycle(Set<String> nodes, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String node : nodes) if (cycleDfs(node, graph, visited, active)) return true;
        return false;
    }

    private boolean cycleDfs(String node, Map<String, Set<String>> graph, Set<String> visited, Set<String> active) {
        if (active.contains(node)) return true;
        if (!visited.add(node)) return false;
        active.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) if (cycleDfs(next, graph, visited, active)) return true;
        active.remove(node);
        return false;
    }

    private Map<String, DirectedProcessNetwork.Node> indexNodes(List<DirectedProcessNetwork.Node> nodes) {
        Map<String, DirectedProcessNetwork.Node> index = new LinkedHashMap<>();
        if (nodes != null) for (var node : nodes) if (node != null && !blank(node.nodeId())) index.putIfAbsent(node.nodeId(), node);
        return index;
    }

    private boolean isNodeType(Map<String, DirectedProcessNetwork.Node> nodes, String id, DirectedProcessNetwork.NodeType type) {
        DirectedProcessNetwork.Node node = nodes.get(id);
        return node != null && node.nodeType() == type;
    }

    private boolean unitMatchesDimension(String unit, FlowDimension dimension) {
        String u = normalizedUnit(unit);
        return switch (dimension) {
            case MASS_PER_TIME -> Set.of("ton/day", "t/day", "ton/month", "t/month", "ton/year", "t/year", "kg/day", "kg/hr", "kg/h", "g/s").contains(u);
            case VOLUME_PER_TIME -> Set.of("l/year", "l/yr", "m3/min", "am3/min", "nm3/min", "sm3/min", "am3/h", "nm3/h", "sm3/h", "m3/day").contains(u);
            case UNKNOWN -> false;
        };
    }

    private boolean gasUnit(String unit) {
        String u = normalizedUnit(unit);
        return u.startsWith("am3/") || u.startsWith("nm3/") || u.startsWith("sm3/");
    }

    private String evidenceSummary(ReviewEvidence review, RouteEvidence route) {
        return "TEST_ONLY; candidate=" + review.candidateId() + "; reviewHistoryId=" + review.reviewHistoryId()
                + "; reviewVersion=" + review.reviewVersion() + "; route=" + route.routeId()
                + "; reviewEvidence=" + review.evidenceIdentifier() + "; pathEvidence=" + route.pathEvidenceIdentifier()
                + "; period=" + review.period() + "; no unit conversion";
    }

    private String streamKey(DirectedProcessNetwork.MaterialClass materialClass, String materialType) {
        return safe(materialClass) + "|" + safe(materialType).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedUnit(String unit) {
        return safe(unit).trim().replace("³", "3").replace("㎥", "m3").replace("ℓ", "l").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String normalizedPeriod(String period) { return safe(period).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private boolean same(String a, String b) { return a != null && b != null && normalizedPeriod(a).equals(normalizedPeriod(b)); }
    private String safe(String value) { return value == null ? "" : value; }
    private String safe(Object value) { return value == null ? "" : value.toString(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public enum FlowDimension { MASS_PER_TIME, VOLUME_PER_TIME, UNKNOWN }
    public enum GasCondition { NOT_APPLICABLE, ACTUAL, NORMAL, STANDARD, UNKNOWN }

    public record ReviewEvidence(String baselineVersion, boolean baselineLocked, String snapshotId,
                                 String candidateId, long reviewCaseId, long reviewHistoryId,
                                 long reviewVersion, long historyReviewVersion, String historyReviewState, int sourceRowNumber,
                                 String drawingFile, String drawingSheet, String sourceCellHint, String targetCellHint,
                                 String connectorCandidateIds, String sourceEquipment, String targetEquipment,
                                 String sourceProcess, String targetProcess,
                                 String reviewState, boolean physicalConnectionConfirmed,
                                 String materialType, BigDecimal quantity, String unit, String period,
                                 String evidenceType, String evidenceIdentifier, String evidenceText,
                                 String reviewer, boolean officialApprovalCreated, boolean syntheticFixture) { }

    public record RouteEvidence(String snapshotId, String baselineVersion, String candidateId,
                                long reviewCaseId, long reviewHistoryId, long reviewVersion,
                                String routeId, String sourceNodeId, String targetNodeId,
                                DirectedProcessNetwork.ConnectionType connectionType,
                                DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                                FlowDimension dimension, GasCondition gasCondition, String gasReferenceCondition,
                                BigDecimal quantity, String quantityEvidenceIdentifier, String unit, String period,
                                DirectedProcessNetwork.QuantityBasis quantityBasis, boolean fixed,
                                String pathDescription, String pathEvidenceIdentifier,
                                boolean directionConfirmed, boolean topologyConfirmed) { }

    public record GraphScope(String mode, String baselineVersion, boolean baselineLocked, String snapshotId,
                             String networkId, String scenarioName, String period,
                             Set<String> expectedCandidateIds, List<DirectedProcessNetwork.Node> nodes,
                             List<DirectedProcessNetwork.ProcessTerm> processTerms,
                             Map<String, String> boundaryEvidence, boolean allRequiredConnectionsDeclared,
                             boolean boundariesDefined, boolean branchAllocationsVerified,
                             boolean cycleTopologyVerified, boolean cycleFlowConditionsVerified,
                             boolean processTermsComplete, String scopeEvidenceIdentifier,
                             List<String> completenessEvidence) {
        public GraphScope {
            expectedCandidateIds = expectedCandidateIds == null ? Set.of() : Set.copyOf(expectedCandidateIds);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            processTerms = processTerms == null ? List.of() : List.copyOf(processTerms);
            boundaryEvidence = boundaryEvidence == null ? Map.of() : Map.copyOf(boundaryEvidence);
            completenessEvidence = completenessEvidence == null ? List.of() : List.copyOf(completenessEvidence);
        }
    }

    public record CandidateAssessment(String candidateId, String reviewState, boolean eligible, String disposition,
                                      List<String> reasons, long reviewVersion, long reviewHistoryId,
                                      int sourceRowNumber, String drawingFile, String drawingSheet,
                                      String sourceCellHint, String targetCellHint, String connectorCandidateIds,
                                      String reviewer, String evidenceType, String evidenceIdentifier, String evidenceText,
                                      String sourceEquipment, String targetEquipment, String sourceProcess, String targetProcess,
                                      String materialType, BigDecimal quantity, String unit, String period) { }

    public record EdgeProvenance(String edgeId, String routeId, String candidateId, String sourceNodeId,
                                 String targetNodeId, long reviewHistoryId, long reviewVersion,
                                 int sourceRowNumber, String drawingFile, String drawingSheet,
                                 String sourceCellHint, String targetCellHint, String connectorCandidateIds,
                                 String reviewer, String reviewState, String evidenceType,
                                 String reviewEvidenceIdentifier, String reviewEvidenceText,
                                 String pathEvidenceIdentifier, String quantityEvidenceIdentifier,
                                 DirectedProcessNetwork.ConnectionType connectionType,
                                 DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                                 BigDecimal quantity, String unit, String period, boolean fixed,
                                 String sourceEquipment, String targetEquipment, String sourceProcess,
                                 String targetProcess) { }

    public record PreparedNetwork(String snapshotId, String mode, String status,
                                  DirectedProcessNetwork network, List<CandidateAssessment> candidates,
                                  List<EdgeProvenance> edgeProvenance, List<String> issues,
                                  boolean calculationAvailable, List<String> completenessEvidence) { }
}
