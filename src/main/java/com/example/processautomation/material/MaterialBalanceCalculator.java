package com.example.processautomation.material;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MaterialBalanceCalculator {
    public DirectedProcessNetworkSolver.Result calculateDirectedNetwork(DirectedProcessNetwork network) {
        return DirectedProcessNetworkSolver.solve(network, null, null, null);
    }

    public DirectedProcessNetworkSolver.Result recalculateDirectedNetwork(DirectedProcessNetwork baseNetwork,
                                                                            String scenarioId,
                                                                            String changedConnectionId,
                                                                            BigDecimal newQuantity) {
        return DirectedProcessNetworkSolver.solve(baseNetwork, scenarioId, changedConnectionId, newQuantity);
    }

    public Calculation calculate(MaterialBalanceRequest request, Map<String, String> transferExclusions) {
        Map<String, MaterialBalanceRequest.Node> nodes = new LinkedHashMap<>();
        for (var node : request.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate node id: " + node.id());
            }
        }
        Set<String> transferIds = new HashSet<>();
        Map<String, List<Edge>> incoming = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        List<Map<String, Object>> transferResults = new ArrayList<>();
        Set<String> excludedAdjacentNodes = new HashSet<>();

        for (var transfer : request.transfers()) {
            if (!transferIds.add(transfer.transferId())) {
                throw new IllegalArgumentException("Duplicate transferId in simulation: " + transfer.transferId());
            }
            String reason = transferExclusions.get(transfer.transferId());
            if (reason == null) reason = validateTransfer(transfer, nodes);
            if (reason != null) {
                if (transfer.sourceId() != null && nodes.containsKey(transfer.sourceId())) excludedAdjacentNodes.add(transfer.sourceId());
                if (transfer.targetId() != null && nodes.containsKey(transfer.targetId())) excludedAdjacentNodes.add(transfer.targetId());
                transferResults.add(Map.of("transferId", transfer.transferId(), "relationMode", safe(transfer.relationMode()),
                        "included", false, "calculationAvailable", false, "reason", reason));
                continue;
            }
            String flowType = normalizeFlowType(transfer.flowType());
            String edgeType = transfer.edgeType().trim().toUpperCase(java.util.Locale.ROOT);
            String sourceFlowType = "WASTE_GENERATION".equals(edgeType) ? "WASTEWATER" : flowType;
            Edge edge = new Edge(transfer.transferId(), transfer.sourceId(), transfer.targetId(),
                    sourceFlowType, flowType, normalizeUnit(transfer.unit()), transfer.quantity(), edgeType,
                    normalizePeriod(transfer.period()));
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
            graph.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target());
            Map<String, Object> transferResult = new LinkedHashMap<>();
            transferResult.put("transferId", edge.id());
            transferResult.put("relationMode", transfer.relationMode().toUpperCase(java.util.Locale.ROOT));
            transferResult.put("sourceId", edge.source()); transferResult.put("targetId", edge.target());
            transferResult.put("edgeType", edge.edgeType());
            transferResult.put("sourceFlowType", edge.sourceFlowType()); transferResult.put("targetFlowType", edge.flowType());
            transferResult.put("quantity", edge.quantity()); transferResult.put("unit", edge.unit());
            transferResult.put("period", edge.period());
            transferResult.put("senderQuantity", edge.quantity());
            transferResult.put("receiverQuantity", transfer.receiverQuantity() == null ? edge.quantity() : transfer.receiverQuantity());
            transferResult.put("reconciliationStatus", transfer.receiverQuantity() == null
                    ? "MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED" : "INDEPENDENT_QUANTITIES_MATCH");
            transferResult.put("included", true); transferResult.put("calculationAvailable", true);
            transferResult.put("countedOncePerEndpoint", true);
            transferResults.add(transferResult);
        }

        List<Map<String, Object>> nodeResults = new ArrayList<>();
        for (var node : nodes.values()) nodeResults.add(balanceNode(node, incoming, outgoing, graph,
                nodes, excludedAdjacentNodes.contains(node.id())));
        nodeResults.sort(Comparator.comparing(result -> result.get("nodeId").toString()));
        return new Calculation(List.copyOf(transferResults), List.copyOf(nodeResults));
    }

    private String validateTransfer(MaterialBalanceRequest.Transfer transfer, Map<String, MaterialBalanceRequest.Node> nodes) {
        if (!"TEST_ONLY".equalsIgnoreCase(transfer.relationMode()) && !"CONFIRMED".equalsIgnoreCase(transfer.relationMode())) {
            return "UNSUPPORTED_RELATION_MODE";
        }
        if (transfer.sourceId() == null || transfer.targetId() == null
                || !nodes.containsKey(transfer.sourceId()) || !nodes.containsKey(transfer.targetId())) return "UNRESOLVED_ENDPOINT";
        if (transfer.quantity() == null || transfer.quantity().signum() < 0) return "MISSING_OR_NEGATIVE_QUANTITY";
        if (transfer.receiverQuantity() != null && transfer.receiverQuantity().signum() < 0) return "MISSING_OR_NEGATIVE_RECEIVER_QUANTITY";
        if (transfer.receiverQuantity() != null && transfer.quantity().compareTo(transfer.receiverQuantity()) != 0) {
            return "SENDER_RECEIVER_QUANTITY_MISMATCH";
        }
        if (blank(transfer.unit()) || blank(transfer.flowType()) || blank(transfer.edgeType())) return "MISSING_FLOW_TYPE_UNIT_OR_EDGE_TYPE";
        if (blank(transfer.period())) return "MEASUREMENT_PERIOD_REQUIRED";
        if (!List.of("WASTEWATER", "WASTE").contains(normalizeFlowType(transfer.flowType()))) return "UNSUPPORTED_FLOW_TYPE";
        if (!List.of("PROCESS", "WASTEWATER_TRANSFER", "WASTE_GENERATION", "BRANCH", "MERGE", "DOWNSTREAM").contains(
                transfer.edgeType().trim().toUpperCase(java.util.Locale.ROOT))) return "UNSUPPORTED_EDGE_TYPE";
        if ("TEST_ONLY".equalsIgnoreCase(transfer.relationMode())) {
            if (transfer.relationId() != null) return "TEST_ONLY_EDGE_MUST_NOT_REFERENCE_APPROVAL";
            if (blank(transfer.evidence())) return "MISSING_TEST_SCENARIO_EVIDENCE";
        } else if (transfer.relationId() == null) {
            return "CONFIRMED_EDGE_MISSING_DATABASE_RELATION_ID";
        }
        return null;
    }

    private Map<String, Object> balanceNode(MaterialBalanceRequest.Node node,
                                            Map<String, List<Edge>> incoming,
                                            Map<String, List<Edge>> outgoing,
                                            Map<String, Set<String>> graph,
                                            Map<String, MaterialBalanceRequest.Node> nodes,
                                            boolean adjacentTransferExcluded) {
        List<Edge> ins = incoming.getOrDefault(node.id(), List.of());
        List<Edge> outs = outgoing.getOrDefault(node.id(), List.of());
        Map<Stream, Totals> totals = new LinkedHashMap<>();
        List<Map<String, Object>> inflowDetails = new ArrayList<>();
        List<Map<String, Object>> outflowDetails = new ArrayList<>();
        List<Map<String, Object>> storageDetails = new ArrayList<>();
        boolean incompleteInitial = false;
        if (node.initialInflow() != null && node.initialInflow().signum() > 0) {
            if (blank(node.initialFlowType()) || blank(node.initialUnit())
                    || !List.of("WASTEWATER", "WASTE").contains(normalizeFlowType(node.initialFlowType()))) {
                incompleteInitial = true;
            } else if (blank(node.initialPeriod())) {
                incompleteInitial = true;
            } else {
                Stream stream = new Stream(normalizeFlowType(node.initialFlowType()), normalizeUnit(node.initialUnit()), normalizePeriod(node.initialPeriod()));
                totals.computeIfAbsent(stream, ignored -> new Totals()).inflow =
                        totals.get(stream).inflow.add(node.initialInflow());
                inflowDetails.add(Map.of("source", "INITIAL_BOUNDARY", "flowType", stream.flowType(),
                        "quantity", node.initialInflow(), "unit", stream.unit(), "period", stream.period()));
            }
        } else if (node.initialInflow() != null && node.initialInflow().signum() < 0) {
            incompleteInitial = true;
        } else if ("SOURCE".equalsIgnoreCase(node.role()) && node.initialInflow() == null) {
            incompleteInitial = true;
        }
        for (Edge edge : ins) {
            Stream stream = new Stream(edge.flowType(), edge.unit(), edge.period());
            Totals total = totals.computeIfAbsent(stream, ignored -> new Totals());
            total.inflow = total.inflow.add(edge.quantity());
            inflowDetails.add(Map.of("transferId", edge.id(), "from", edge.source(), "flowType", stream.flowType(),
                    "quantity", edge.quantity(), "unit", stream.unit(), "period", stream.period()));
        }
        for (Edge edge : outs) {
            Stream stream = new Stream(edge.sourceFlowType(), edge.unit(), edge.period());
            Totals total = totals.computeIfAbsent(stream, ignored -> new Totals());
            total.outflow = total.outflow.add(edge.quantity());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("transferId", edge.id()); detail.put("to", edge.target()); detail.put("edgeType", edge.edgeType());
            detail.put("flowType", stream.flowType()); detail.put("quantity", edge.quantity()); detail.put("unit", stream.unit());
            detail.put("period", stream.period());
            if (!edge.sourceFlowType().equals(edge.flowType())) detail.put("convertedToFlowType", edge.flowType());
            outflowDetails.add(detail);
        }

        boolean badStorage = false;
        if (node.storageChange() != null) {
            if (blank(node.storageFlowType()) || blank(node.storageUnit()) || blank(node.storagePeriod())
                    || !List.of("WASTEWATER", "WASTE").contains(normalizeFlowType(node.storageFlowType()))) {
                badStorage = true;
            } else {
                Stream stream = new Stream(normalizeFlowType(node.storageFlowType()), normalizeUnit(node.storageUnit()), normalizePeriod(node.storagePeriod()));
                Totals total = totals.computeIfAbsent(stream, ignored -> new Totals());
                total.storageChange = total.storageChange.add(node.storageChange());
                storageDetails.add(Map.of("flowType", stream.flowType(), "change", node.storageChange(),
                        "unit", stream.unit(), "period", stream.period(), "signConvention", "POSITIVE_IS_STORAGE_INCREASE"));
            }
        }

        String role = node.role().trim().toUpperCase(java.util.Locale.ROOT);
        boolean terminal = terminalBoundary(role, node.boundaryCondition());
        boolean terminalConflict = terminal && !outs.isEmpty();
        if (terminal && !terminalConflict) {
            Map<Stream, BigDecimal> received = new LinkedHashMap<>();
            for (Edge edge : ins) received.merge(new Stream(edge.flowType(), edge.unit(), edge.period()), edge.quantity(), BigDecimal::add);
            for (var entry : received.entrySet()) {
                Totals total = totals.get(entry.getKey());
                total.outflow = total.outflow.add(entry.getValue());
                outflowDetails.add(Map.of("destination", "EXPLICIT_TERMINAL_BOUNDARY", "boundaryCondition",
                        node.boundaryCondition(), "flowType", entry.getKey().flowType(), "quantity", entry.getValue(),
                        "unit", entry.getKey().unit(), "period", entry.getKey().period()));
            }
        }

        Set<String> units = new LinkedHashSet<>();
        totals.keySet().forEach(stream -> units.add(stream.unit()));
        boolean mixedUnits = units.size() > 1;
        List<String> reasons = new ArrayList<>();
        if (incompleteInitial) reasons.add("INITIAL_INFLOW_REQUIRES_NONNEGATIVE_QUANTITY_FLOW_TYPE_UNIT_AND_PERIOD");
        if (badStorage) reasons.add("STORAGE_CHANGE_REQUIRES_FLOW_TYPE_UNIT_AND_PERIOD");
        if (terminalConflict) reasons.add("TERMINAL_BOUNDARY_CONFLICTS_WITH_OUTGOING_TRANSFER");
        if (mixedUnits) reasons.add("MIXED_UNITS_NO_CONVERSION_APPLIED");
        if (adjacentTransferExcluded) reasons.add("ADJACENT_TRANSFER_EXCLUDED");
        if (totals.isEmpty()) reasons.add("NO_FLOW_DATA_FOR_NODE");
        if ("PROCESS".equals(role)) {
            if (ins.isEmpty()) reasons.add("MISSING_INFLOW_TRANSFER");
            if (outs.isEmpty()) reasons.add("MISSING_OUTFLOW_TRANSFER");
        } else if ("SOURCE".equals(role)) {
            if (outs.isEmpty()) reasons.add("MISSING_OUTFLOW_TRANSFER");
        } else if (List.of("WASTE_SINK", "OUTLET", "WASTEWATER_STORAGE", "WASTE_STORAGE",
                "FINAL_DISCHARGE", "FINAL_OFFSITE").contains(role)) {
            if (ins.isEmpty()) reasons.add("MISSING_INFLOW_TRANSFER");
        } else if ("UNASSIGNED_RECEIVER".equals(role)) {
            reasons.add("UNASSIGNED_TEST_ONLY_DESTINATION_NO_FACILITY_OR_DISPOSITION_ASSUMED");
        } else {
            reasons.add("UNSUPPORTED_NODE_ROLE");
        }

        boolean available = reasons.isEmpty();
        Map<PeriodUnit, Totals> totalsByUnitAndPeriod = new LinkedHashMap<>();
        totals.forEach((stream, amount) -> {
            PeriodUnit key = new PeriodUnit(stream.unit(), stream.period());
            Totals total = totalsByUnitAndPeriod.computeIfAbsent(key, ignored -> new Totals());
            total.inflow = total.inflow.add(amount.inflow);
            total.outflow = total.outflow.add(amount.outflow);
            total.storageChange = total.storageChange.add(amount.storageChange);
        });
        List<Map<String, Object>> unitBalances = totalsByUnitAndPeriod.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<PeriodUnit, Totals> entry) -> entry.getKey().unit())
                        .thenComparing(entry -> entry.getKey().period()))
                .map(entry -> balanceRow(entry.getKey().unit(), entry.getKey().period(), entry.getValue(), available, terminal)).toList();
        List<Map<String, Object>> balances = totals.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Stream, Totals> e) -> e.getKey().flowType())
                        .thenComparing(e -> e.getKey().unit()).thenComparing(e -> e.getKey().period()))
                .map(entry -> {
                    Map<String, Object> row = balanceRow(entry.getKey().unit(), entry.getKey().period(), entry.getValue(), available, terminal);
                    row.put("flowType", entry.getKey().flowType());
                    return row;
                }).toList();

        List<Map<String, Object>> downstream = downstream(node.id(), graph, nodes);
        List<String> unresolvedInputs = unresolvedInputs(role, totals, terminal);
        List<Map<String, Object>> residuals = new ArrayList<>();
        if (available) {
            for (Map<String, Object> balance : balances) {
                BigDecimal residual = (BigDecimal) balance.get("residualInMinusOut");
                if (residual.signum() != 0) {
                    Map<String, Object> unresolved = new LinkedHashMap<>();
                    unresolved.put("flowType", balance.get("flowType")); unresolved.put("unit", balance.get("unit"));
                    unresolved.put("residual", residual); unresolved.put("status", "RESIDUAL_UNRESOLVED");
                    unresolved.put("requiredAdditionalInformation", unresolvedInputs);
                    residuals.add(unresolved);
                }
            }
        }
        boolean nonzeroResidual = !residuals.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.id()); result.put("label", node.label()); result.put("role", node.role());
        result.put("processCode", node.processCode()); result.put("equipmentId", node.equipmentId());
        result.put("receiverType", receiverType(role));
        result.put("receiverDispositionStatus", terminal && !terminalConflict ? "EXPLICIT_TERMINAL_BOUNDARY_APPLIED"
                : available && !nonzeroResidual && !storageDetails.isEmpty() ? "ACCOUNTED_BY_MEASURED_STORAGE_CHANGE"
                : List.of("WASTE_SINK", "WASTE_STORAGE", "WASTEWATER_STORAGE").contains(role)
                ? "UNCONFIRMED_STORAGE_OR_OFFSITE_DISPOSITION"
                : List.of("OUTLET", "FINAL_DISCHARGE", "FINAL_OFFSITE").contains(role)
                ? "UNCONFIRMED_FINAL_BOUNDARY" : "NOT_A_RECEIVER_BOUNDARY");
        result.put("boundaryCondition", node.boundaryCondition());
        result.put("inflows", inflowDetails); result.put("outflows", outflowDetails);
        result.put("storageChanges", storageDetails);
        result.put("totalByUnit", unitBalances);
        result.put("balanceByFlowTypeAndUnit", balances);
        result.put("residualStatus", !available ? "CALCULATION_UNAVAILABLE" : nonzeroResidual ? "RESIDUAL_UNRESOLVED" : "BALANCED");
        result.put("unresolvedResiduals", residuals);
        result.put("balanceStatus", !available ? "CALCULATION_UNAVAILABLE" : nonzeroResidual ? "RESIDUAL_UNRESOLVED" : "CALCULATED");
        result.put("calculationUnavailableReasons", reasons);
        result.put("downstreamNodeIds", downstream.stream().map(row -> row.get("nodeId")).toList());
        result.put("downstreamImpactScope", downstream);
        return result;
    }

    private Map<String, Object> balanceRow(String unit, String period, Totals total, boolean available, boolean terminal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("unit", unit); row.put("period", period); row.put("inflow", total.inflow); row.put("outflow", total.outflow);
        row.put("storageChange", total.storageChange);
        row.put("residualInMinusOutAndStorageChange", available
                ? total.inflow.subtract(total.outflow).subtract(total.storageChange) : null);
        row.put("residualInMinusOut", available
                ? total.inflow.subtract(total.outflow).subtract(total.storageChange) : null);
        row.put("terminalBoundaryApplied", terminal);
        row.put("calculationAvailable", available);
        return row;
    }

    private List<String> unresolvedInputs(String role, Map<Stream, Totals> totals, boolean terminal) {
        if (terminal) return List.of();
        List<String> needed = new ArrayList<>();
        String normalizedRole = role.toUpperCase(java.util.Locale.ROOT);
        if (normalizedRole.contains("STORAGE")) needed.add("MEASURED_STORAGE_CHANGE_OR_OPENING_AND_CLOSING_INVENTORY");
        else needed.add("VERIFY_STORAGE_CHANGE_OR_OPENING_AND_CLOSING_INVENTORY");
        if (normalizedRole.equals("PROCESS")) needed.add("VERIFY_ACTUAL_INTERPROCESS_DESTINATION_AND_RECEIVED_QUANTITY");
        if (normalizedRole.contains("WASTE") || normalizedRole.equals("WASTE_SINK")) {
            needed.add("WASTE_STORAGE_OR_OFFSITE_TRANSFER_QUANTITY_AND_DESTINATION");
        }
        if (normalizedRole.equals("OUTLET") || normalizedRole.equals("FINAL_DISCHARGE")
                || normalizedRole.equals("FINAL_OFFSITE")) {
            needed.add("EXPLICIT_TERMINAL_BOUNDARY_TYPE_AND_DISCHARGE_OR_OFFSITE_EVIDENCE");
        }
        needed.add("UNACCOUNTED_FLOW_DESTINATION_OR_OTHER_VERIFIED_MASS_BALANCE_TERM");
        needed.add("CONFIRM_FLOW_TYPE_UNIT_AND_MEASUREMENT_PERIOD");
        return List.copyOf(needed);
    }

    private String receiverType(String role) {
        return switch (role) {
            case "PROCESS" -> "GENERAL_TREATMENT_EQUIPMENT";
            case "WASTEWATER_STORAGE" -> "WASTEWATER_STORAGE_FACILITY";
            case "WASTE_STORAGE" -> "WASTE_STORAGE_FACILITY";
            case "WASTE_SINK" -> "WASTE_RECEIVER_UNCLASSIFIED_DISPOSITION";
            case "UNASSIGNED_RECEIVER" -> "TEST_ONLY_UNASSIGNED_RECEIVER_NO_ASSUMED_FACILITY";
            case "FINAL_DISCHARGE", "OUTLET" -> "FINAL_DISCHARGE_REQUIRES_EXPLICIT_BOUNDARY";
            case "FINAL_OFFSITE" -> "FINAL_OFFSITE_REQUIRES_EXPLICIT_BOUNDARY";
            default -> "SOURCE_OR_UNCLASSIFIED";
        };
    }

    private boolean terminalBoundary(String role, String boundary) {
        if (boundary == null) return false;
        String value = boundary.trim().toUpperCase(java.util.Locale.ROOT);
        return ("FINAL_DISCHARGE".equals(role) || "OUTLET".equals(role)) && "TERMINAL_DISCHARGE".equals(value)
                || "FINAL_OFFSITE".equals(role) && "TERMINAL_OFFSITE".equals(value);
    }

    private List<Map<String, Object>> downstream(String start, Map<String, Set<String>> graph,
                                                 Map<String, MaterialBalanceRequest.Node> nodes) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(graph.getOrDefault(start, Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (next.equals(start) || !visited.add(next)) continue;
            queue.addAll(graph.getOrDefault(next, Set.of()));
        }
        return visited.stream().sorted().map(id -> {
            MaterialBalanceRequest.Node node = nodes.get(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", id); item.put("label", node.label()); item.put("equipmentId", node.equipmentId());
            item.put("processCode", node.processCode()); item.put("role", node.role());
            return item;
        }).toList();
    }

    private String normalizeUnit(String unit) { return unit.trim().replaceAll("\\s+", " "); }
    private String normalizeFlowType(String type) { return type.trim().toUpperCase(java.util.Locale.ROOT); }
    private String normalizePeriod(String period) { return period.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }

    public record Calculation(List<Map<String, Object>> transfers, List<Map<String, Object>> nodes) { }
    private record Edge(String id, String source, String target, String sourceFlowType, String flowType,
                        String unit, BigDecimal quantity, String edgeType, String period) { }
    private record Stream(String flowType, String unit, String period) { }
    private record PeriodUnit(String unit, String period) { }
    private static final class Totals {
        private BigDecimal inflow = BigDecimal.ZERO;
        private BigDecimal outflow = BigDecimal.ZERO;
        private BigDecimal storageChange = BigDecimal.ZERO;
    }
}
