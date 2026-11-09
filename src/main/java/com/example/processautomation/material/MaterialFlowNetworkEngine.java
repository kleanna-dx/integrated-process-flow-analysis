package com.example.processautomation.material;

import static com.example.processautomation.material.MaterialFlowNetwork.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** DB-free, conservative graph accounting and immutable TEST_ONLY scenario evaluator. */
@Service
public class MaterialFlowNetworkEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000000001");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    public Result calculate(MaterialFlowNetwork network) {
        List<String> issues = validate(network);
        if (!issues.isEmpty()) return unavailable(issues);
        RuleResolution resolution = resolveTransferRules(network);
        if (!resolution.issues().isEmpty()) return unavailable(resolution.issues());
        MaterialFlowNetwork resolved = withResolvedQuantities(network, resolution.quantities());
        return evaluate(resolved, List.of(), resolution.applications(), resolution.allocationChecks());
    }

    public WhatIfResult simulate(MaterialFlowNetwork base, List<Scenario> scenarios) {
        Result baseline = calculate(base);
        if (scenarios == null || scenarios.isEmpty()) {
            return new WhatIfResult(baseline, List.of(), List.of("SCENARIOS_REQUIRED"), false, false, false);
        }
        List<String> scenarioIssues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            if (scenario == null || blank(scenario.scenarioId()) || !ids.add(scenario.scenarioId())) {
                scenarioIssues.add("SCENARIO_ID_MISSING_OR_DUPLICATE");
                continue;
            }
            MaterialFlowNetwork changed = apply(base, scenario, scenarioIssues);
            if (changed == null) continue;
            Result result = calculate(changed);
            List<String> issues = new ArrayList<>(result.issues());
            if (!baseline.calculationAvailable()) issues.add("BASELINE_CALCULATION_UNAVAILABLE");
            Impact impact = impact(base, scenario);
            EdgeImpact edgeImpact = edgeImpact(base, scenario);
            boolean reviewRequired = !result.calculationAvailable() || !"CALCULATED".equals(result.status()) || !issues.isEmpty()
                    || scenario.changeType() == ChangeType.EDGE_DELETE || scenario.changeType() == ChangeType.EDGE_ADD
                    || scenario.changeType() == ChangeType.PIPE_CHANGE
                    || result.edges().stream().anyMatch(edge -> edge.evidenceStatus() != EvidenceStatus.VERIFIED)
                    || base.connections().stream().anyMatch(edge -> edge.evidenceStatus() != EvidenceStatus.VERIFIED || edge.scenarioOnly())
                    || base.transferRules().stream().filter(java.util.Objects::nonNull)
                    .anyMatch(rule -> rule.evidenceStatus() != EvidenceStatus.VERIFIED || rule.scenarioOnly());
            results.add(new ScenarioResult(scenario.scenarioId(), scenario.description(), scenario.changeType(),
                    result, List.copyOf(issues), residualDelta(baseline, result), impact.directNodes(), impact.upstreamNodes(),
                    impact.downstreamNodes(), impact.allNodes(), edgeImpact.directEdges(), edgeImpact.downstreamEdges(),
                    result.calculationAvailable() ? List.of() : edgeImpact.downstreamEdges(),
                    reviewRequired ? "REVIEW_REQUIRED" : "CALCULATED", sameExternalTotals(baseline.externalTotals(), result.externalTotals()), false));
        }
        return new WhatIfResult(baseline, List.copyOf(results), List.copyOf(scenarioIssues),
                baseline.calculationAvailable(), false, false);
    }

    /** Build a downstream Excel-editor handoff. Mapping is deliberately UNKNOWN until independently reviewed. */
    public MaterialFlowExcelChangeContract toExcelChangeContract(MaterialFlowNetwork base, WhatIfResult report,
                                                                  ScenarioResult scenario) {
        if (base == null || report == null || scenario == null) {
            return new MaterialFlowExcelChangeContract("", "REVIEW_REQUIRED", List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), false, List.of("NETWORK_AND_SCENARIO_REQUIRED"),
                    "CALCULATION_UNAVAILABLE", "UNKNOWN");
        }
        Map<String, Equipment> equipment = new HashMap<>();
        base.equipment().forEach(item -> equipment.put(item.equipmentId(), item));
        Map<String, Connection> sourceEdges = new HashMap<>();
        base.connections().forEach(edge -> sourceEdges.put(edge.connectionId(), edge));
        Set<String> affectedIds = new LinkedHashSet<>(scenario.directEdges());
        affectedIds.addAll(scenario.downstreamEdges());
        Map<String, EdgeBalance> before = new HashMap<>(), after = new HashMap<>();
        report.baseline().edges().forEach(edge -> before.put(edge.connectionId(), edge));
        scenario.result().edges().forEach(edge -> after.put(edge.connectionId(), edge));
        List<MaterialFlowExcelChangeContract.EdgeChange> changes = new ArrayList<>();
        List<MaterialFlowExcelChangeContract.WorkbookMapping> mappings = new ArrayList<>();
        Set<String> processes = new LinkedHashSet<>(), equipmentIds = new LinkedHashSet<>();
        for (String id : affectedIds) {
            EdgeBalance next = after.get(id);
            EdgeBalance previous = before.get(id);
            Connection source = sourceEdges.get(id);
            if (source == null && next == null) continue;
            EdgeBalance detail = next != null ? next : previous;
            String sourceProcess = processFor(detail.sourceType(), detail.sourceId(), equipment);
            String targetProcess = processFor(detail.targetType(), detail.targetId(), equipment);
            if (!blank(sourceProcess)) processes.add(sourceProcess);
            if (!blank(targetProcess)) processes.add(targetProcess);
            if (detail.sourceType() == EndpointType.EQUIPMENT) equipmentIds.add(detail.sourceId());
            if (detail.targetType() == EndpointType.EQUIPMENT) equipmentIds.add(detail.targetId());
            String evidenceState = source != null && source.evidenceStatus() == EvidenceStatus.VERIFIED && !source.scenarioOnly()
                    && scenario.result().calculationAvailable() ? "VERIFIED" : "REVIEW_REQUIRED";
            changes.add(new MaterialFlowExcelChangeContract.EdgeChange(id, sourceProcess,
                    detail.sourceType() == EndpointType.EQUIPMENT ? detail.sourceId() : null,
                    targetProcess, detail.targetType() == EndpointType.EQUIPMENT ? detail.targetId() : null,
                    detail.materialClass().name(), detail.materialType(), detail.unit(), detail.period(),
                    previous == null ? null : previous.quantity(), next == null ? null : next.quantity(), evidenceState));
            mappings.add(new MaterialFlowExcelChangeContract.WorkbookMapping(id, "UNKNOWN", "UNKNOWN", "UNKNOWN",
                    "UNKNOWN", "UNKNOWN", "REVIEW_REQUIRED", false));
        }
        List<String> upstreamEquipment = scenario.upstreamNodes().stream().filter(node -> node.startsWith("EQUIPMENT:"))
                .map(node -> node.substring("EQUIPMENT:".length())).sorted().toList();
        List<String> downstreamEquipment = scenario.downstreamNodes().stream().filter(node -> node.startsWith("EQUIPMENT:"))
                .map(node -> node.substring("EQUIPMENT:".length())).sorted().toList();
        List<String> validations = new ArrayList<>(List.of("WORKBOOK_SHEET_CELL_SHAPE_PIPE_MAPPING_REQUIRED",
                "RENDER_AND_MATERIAL_BALANCE_VALIDATION_REQUIRED", "OFFICIAL_FILE_IMMUTABILITY_REQUIRED"));
        if (!scenario.result().calculationAvailable()) validations.add("CALCULATION_UNAVAILABLE_REQUIRES_REVIEW");
        if (!scenario.externalBoundaryInvariant()) validations.add("EXTERNAL_BOUNDARY_CHANGED_OR_INCOMPLETE");
        return new MaterialFlowExcelChangeContract(scenario.scenarioId(), scenario.readiness(), processes.stream().sorted().toList(),
                equipmentIds.stream().sorted().toList(), List.copyOf(changes), upstreamEquipment, downstreamEquipment,
                List.copyOf(mappings), false, List.copyOf(validations), scenario.result().status(),
                scenario.readiness().equals("CALCULATED") ? "TEST_ONLY" : "REVIEW_REQUIRED");
    }

    private static String processFor(EndpointType type, String id, Map<String, Equipment> equipment) {
        if (type == EndpointType.PROCESS) return id;
        if (type == EndpointType.EQUIPMENT && equipment.containsKey(id)) return equipment.get(id).processCode();
        return "";
    }

    private MaterialFlowNetwork apply(MaterialFlowNetwork base, Scenario scenario, List<String> issues) {
        if (base == null || scenario.changeType() == null) {
            issues.add("NETWORK_AND_CHANGE_TYPE_REQUIRED"); return null;
        }
        List<Connection> edges = new ArrayList<>(base.connections());
        List<TransferRule> rules = new ArrayList<>(base.transferRules());
        int index = indexOf(edges, scenario.connectionId());
        switch (scenario.changeType()) {
            case FLOW_CHANGE, INTERNAL_RECYCLE_CHANGE, INTERPROCESS_TRANSFER_CHANGE -> {
                if (index < 0) { issues.add("CHANGED_EDGE_NOT_FOUND:" + scenario.connectionId()); return null; }
                Connection old = edges.get(index);
                if (scenario.newQuantity() == null || scenario.newQuantity().signum() < 0) {
                    issues.add("NONNEGATIVE_NEW_QUANTITY_REQUIRED:" + scenario.connectionId()); return null;
                }
                if (old.flowType() == FlowType.BRANCH) {
                    issues.add("BRANCH_FLOW_CHANGE_REQUIRES_ALLOCATION_CHANGE:" + scenario.connectionId()); return null;
                }
                edges.set(index, copyQuantity(old, scenario.newQuantity()));
                rules.removeIf(rule -> rule.connectionId().equals(scenario.connectionId()));
            }
            case BRANCH_ALLOCATION_CHANGE -> {
                List<AllocationChange> changes = scenario.allocationChanges().isEmpty()
                        ? List.of(new AllocationChange(scenario.connectionId(), scenario.newQuantity()))
                        : scenario.allocationChanges();
                if (changes.stream().anyMatch(change -> change.connectionId() == null || change.allocationRatio() == null
                        || change.allocationRatio().signum() < 0 || change.allocationRatio().compareTo(ONE) > 0)) {
                    issues.add("BRANCH_ALLOCATION_CHANGE_INVALID"); return null;
                }
                for (AllocationChange change : changes) {
                    int branchIndex = indexOf(edges, change.connectionId());
                    if (branchIndex < 0) { issues.add("CHANGED_EDGE_NOT_FOUND:" + change.connectionId()); return null; }
                    Connection old = edges.get(branchIndex);
                    if (old.flowType() != FlowType.BRANCH) { issues.add("ALLOCATION_CHANGE_REQUIRES_BRANCH_EDGE:" + change.connectionId()); return null; }
                    edges.set(branchIndex, copyAllocation(old, change.allocationRatio()));
                    for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                        TransferRule rule = rules.get(ruleIndex);
                        if (rule.connectionId().equals(change.connectionId())) {
                            rules.set(ruleIndex, new TransferRule(rule.connectionId(), rule.mode(), rule.basisConnectionIds(),
                                    change.allocationRatio(), "TEST_ONLY_SCENARIO", EvidenceStatus.REVIEW_REQUIRED,
                                    rule.evidence(), true));
                        }
                    }
                }
            }
            case EDGE_DELETE -> {
                if (index < 0) { issues.add("CHANGED_EDGE_NOT_FOUND:" + scenario.connectionId()); return null; }
                edges.remove(index);
            }
            case EDGE_ADD, PIPE_CHANGE -> {
                Connection added = scenario.replacementOrAddition();
                if (added == null) { issues.add("ADDED_EDGE_REQUIRED"); return null; }
                if (indexOf(edges, added.connectionId()) >= 0) { issues.add("CONNECTION_ID_CONFLICT:" + added.connectionId()); return null; }
                edges.add(asScenario(added));
            }
        }
        return new MaterialFlowNetwork(base.networkId(), scenario.scenarioId(), "TEST_ONLY",
                base.processes(), base.equipment(), edges, rules);
    }

    private RuleResolution resolveTransferRules(MaterialFlowNetwork graph) {
        if (graph.transferRules().isEmpty()) {
            boolean unknown = graph.connections().stream().anyMatch(edge -> edge.quantity() == null);
            return unknown ? new RuleResolution(Map.of(), List.of("UNKNOWN_QUANTITY_WITHOUT_TRANSFER_RULE"), List.of(), List.of())
                    : new RuleResolution(Map.of(), List.of(), List.of(), List.of());
        }
        Map<String, Connection> edges = new LinkedHashMap<>();
        graph.connections().forEach(edge -> edges.put(edge.connectionId(), edge));
        Map<String, TransferRule> rules = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        for (TransferRule rule : graph.transferRules()) {
            if (rule == null || blank(rule.connectionId()) || rule.mode() == null || rule.evidenceStatus() == null
                    || blank(rule.dataSource()) || blank(rule.evidence()) || rule.basisConnectionIds().isEmpty()) {
                issues.add("TRANSFER_RULE_INCOMPLETE");
                continue;
            }
            if (!edges.containsKey(rule.connectionId())) issues.add("TRANSFER_RULE_TARGET_NOT_FOUND:" + rule.connectionId());
            if (rules.putIfAbsent(rule.connectionId(), rule) != null) issues.add("CONFLICTING_TRANSFER_RULES:" + rule.connectionId());
            if (rule.mode() == TransferMode.BRANCH_RATIO && (rule.factor() == null || rule.factor().signum() < 0
                    || rule.factor().compareTo(ONE) > 0)) issues.add("BRANCH_RULE_RATIO_INVALID:" + rule.connectionId());
            if (rule.mode() != TransferMode.BRANCH_RATIO && rule.factor() == null) {
                issues.add("TRANSFER_RULE_FACTOR_REQUIRED:" + rule.connectionId());
            } else if (rule.factor() != null && rule.factor().signum() < 0) {
                issues.add("TRANSFER_RULE_FACTOR_NEGATIVE:" + rule.connectionId());
            }
            if (rule.mode() == TransferMode.PASS_THROUGH && rule.factor() != null
                    && rule.factor().subtract(ONE).abs().compareTo(TOLERANCE) > 0)
                issues.add("PASS_THROUGH_FACTOR_MUST_BE_ONE:" + rule.connectionId());
            if (rule.mode() == TransferMode.SUM_INPUTS && rule.factor() != null
                    && rule.factor().subtract(ONE).abs().compareTo(TOLERANCE) > 0)
                issues.add("SUM_INPUTS_FACTOR_MUST_BE_ONE:" + rule.connectionId());
            if (rule.mode() == TransferMode.SUM_INPUTS && rule.basisConnectionIds().size() < 2)
                issues.add("SUM_INPUTS_REQUIRES_MULTIPLE_BASIS_EDGES:" + rule.connectionId());
            if (!rule.scenarioOnly() && rule.evidenceStatus() != EvidenceStatus.VERIFIED) {
                issues.add("UNVERIFIED_TRANSFER_RULE_BLOCKED:" + rule.connectionId());
            }
            if (!rule.scenarioOnly() && !"TEST_ONLY".equals(graph.mode())) {
                issues.add("NON_TEST_TRANSFER_RULE_NOT_ENABLED:" + rule.connectionId());
            }
            if (new HashSet<>(rule.basisConnectionIds()).size() != rule.basisConnectionIds().size()) {
                issues.add("DUPLICATE_TRANSFER_BASIS:" + rule.connectionId());
            }
        }
        for (TransferRule rule : rules.values()) {
            Connection target = edges.get(rule.connectionId());
            if (target == null) continue;
            Connection firstBasis = null;
            for (String basisId : rule.basisConnectionIds()) {
                Connection basis = edges.get(basisId);
                if (basis == null) { issues.add("TRANSFER_BASIS_NOT_FOUND:" + rule.connectionId() + ":" + basisId); continue; }
                if (firstBasis == null) firstBasis = basis;
                else if (rule.mode() == TransferMode.TRANSFORM && !sameStream(firstBasis, basis))
                    issues.add("TRANSFORM_BASIS_STREAM_MISMATCH:" + rule.connectionId() + ":" + basisId);
                if (rule.mode() == TransferMode.TRANSFORM) {
                    if (!java.util.Objects.equals(target.period(), basis.period()))
                        issues.add("TRANSFER_PERIOD_MISMATCH:" + rule.connectionId() + ":" + basisId);
                } else if (!sameStream(target, basis)) {
                    issues.add("TRANSFER_STREAM_MISMATCH:" + rule.connectionId() + ":" + basisId);
                }
                if (!endpoint(basis.targetType(), basis.targetId()).equals(endpoint(target.sourceType(), target.sourceId()))) {
                    issues.add("TRANSFER_PATH_DISCONTINUITY:" + rule.connectionId() + ":" + basisId);
                }
            }
            if (target.quantity() != null && !rule.scenarioOnly() && target.evidenceStatus() != EvidenceStatus.VERIFIED) {
                issues.add("UNVERIFIED_TRANSFER_TARGET_BLOCKED:" + target.connectionId());
            }
        }
        List<AllocationCheck> allocationChecks = validateBranchRules(graph, edges, rules, issues);
        if (!issues.isEmpty()) return new RuleResolution(Map.of(), List.copyOf(new LinkedHashSet<>(issues)), List.of(), allocationChecks);

        Map<String, BigDecimal> resolved = new LinkedHashMap<>();
        edges.values().stream().filter(edge -> edge.quantity() != null).forEach(edge -> resolved.put(edge.connectionId(), edge.quantity()));
        List<RuleApplication> applications = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>(rules.keySet());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (String targetId : new ArrayList<>(pending)) {
                TransferRule rule = rules.get(targetId);
                Connection target = edges.get(targetId);
                if (rule.basisConnectionIds().stream().anyMatch(id -> !resolved.containsKey(id))) continue;
                BigDecimal basisTotal = rule.basisConnectionIds().stream().map(resolved::get).reduce(ZERO, BigDecimal::add);
                BigDecimal derived = switch (rule.mode()) {
                    case PASS_THROUGH -> basisTotal.multiply(rule.factor(), MC);
                    case SUM_INPUTS -> basisTotal.multiply(rule.factor(), MC);
                    case BRANCH_RATIO -> basisTotal.multiply(rule.factor(), MC);
                    case TRANSFORM -> basisTotal.multiply(rule.factor(), MC);
                };
                derived = derived.round(MC);
                if (derived.signum() < 0) { issues.add("NEGATIVE_DERIVED_QUANTITY:" + targetId); continue; }
                if (target.quantity() != null && target.quantity().subtract(derived).abs().compareTo(TOLERANCE) > 0) {
                    issues.add("DECLARED_QUANTITY_CONFLICTS_WITH_RULE:" + targetId);
                    continue;
                }
                resolved.put(targetId, derived);
                applications.add(new RuleApplication(targetId, rule.mode(), rule.basisConnectionIds(), basisTotal,
                        rule.factor(), derived, rule.dataSource(), rule.evidenceStatus(), rule.evidence()));
                pending.remove(targetId);
                progressed = true;
            }
            if (!progressed) {
                issues.add("TRANSFER_RULE_DEPENDENCY_CYCLE_OR_UNKNOWN_BASIS:" + String.join(",", pending));
                break;
            }
        }
        for (Connection edge : edges.values()) {
            if (!resolved.containsKey(edge.connectionId())) issues.add("UNKNOWN_QUANTITY:" + edge.connectionId());
        }
        if (!issues.isEmpty()) return new RuleResolution(Map.of(), List.copyOf(new LinkedHashSet<>(issues)), List.of(), allocationChecks);
        return new RuleResolution(Map.copyOf(resolved), List.of(), List.copyOf(applications), allocationChecks);
    }

    private static List<AllocationCheck> validateBranchRules(MaterialFlowNetwork graph, Map<String, Connection> edges,
                                                              Map<String, TransferRule> rules, List<String> issues) {
        Map<String, List<Connection>> branches = new LinkedHashMap<>();
        graph.connections().stream().filter(edge -> edge.flowType() == FlowType.BRANCH)
                .forEach(edge -> branches.computeIfAbsent(endpoint(edge.sourceType(), edge.sourceId()) + "|" + streamKey(edge),
                        ignored -> new ArrayList<>()).add(edge));
        List<AllocationCheck> checks = new ArrayList<>();
        for (Map.Entry<String, List<Connection>> entry : branches.entrySet()) {
            List<Connection> branchEdges = entry.getValue();
            BigDecimal ratioSum = ZERO;
            boolean complete = true;
            boolean deriving = branchEdges.stream().anyMatch(edge -> edge.quantity() == null);
            List<String> basisIds = null;
            for (Connection edge : branchEdges) {
                if (!edge.explicitAllocation() || edge.allocationRatio() == null) {
                    issues.add("BRANCH_ALLOCATION_REQUIRED:" + edge.connectionId());
                    complete = false;
                    continue;
                }
                ratioSum = ratioSum.add(edge.allocationRatio());
                TransferRule rule = rules.get(edge.connectionId());
                if (rule == null && deriving) {
                    issues.add("BRANCH_DISTRIBUTION_RULE_REQUIRED:" + edge.connectionId());
                    complete = false;
                } else if (rule != null && rule.mode() != TransferMode.BRANCH_RATIO) {
                    issues.add("BRANCH_DISTRIBUTION_RULE_REQUIRED:" + edge.connectionId());
                    complete = false;
                } else if (rule != null) {
                    if (rule.factor() == null || rule.factor().subtract(edge.allocationRatio()).abs().compareTo(TOLERANCE) > 0) {
                        issues.add("BRANCH_RULE_RATIO_CONFLICT:" + edge.connectionId());
                        complete = false;
                    }
                    if (basisIds == null) basisIds = rule.basisConnectionIds();
                    else if (!basisIds.equals(rule.basisConnectionIds())) {
                        issues.add("BRANCH_BASIS_CONFLICT:" + edge.connectionId());
                        complete = false;
                    }
                }
            }
            BigDecimal difference = ratioSum.subtract(ONE);
            if (difference.abs().compareTo(TOLERANCE) > 0) {
                issues.add("BRANCH_ALLOCATION_SUM_OUT_OF_TOLERANCE:" + entry.getKey() + ":" + ratioSum.toPlainString());
                complete = false;
            }
            if (complete && basisIds != null) {
                for (String basisId : basisIds) {
                    Connection basis = edges.get(basisId);
                    if (basis == null || !endpoint(basis.targetType(), basis.targetId())
                            .equals(endpoint(branchEdges.get(0).sourceType(), branchEdges.get(0).sourceId()))) {
                        issues.add("BRANCH_BASIS_NOT_INTO_SOURCE:" + basisId);
                        complete = false;
                    }
                }
            }
            checks.add(new AllocationCheck(entry.getKey(), ratioSum, difference, TOLERANCE, complete,
                    complete ? "VALID" : "REVIEW_REQUIRED"));
        }
        return List.copyOf(checks);
    }

    private static boolean sameStream(Connection left, Connection right) {
        return left.materialClass() == right.materialClass() && java.util.Objects.equals(left.materialType(), right.materialType())
                && java.util.Objects.equals(left.unit(), right.unit()) && java.util.Objects.equals(left.period(), right.period());
    }
    private static String streamKey(Connection edge) {
        return edge.materialClass() + "|" + edge.materialType() + "|" + edge.unit() + "|" + edge.period();
    }
    private static MaterialFlowNetwork withResolvedQuantities(MaterialFlowNetwork graph, Map<String, BigDecimal> quantities) {
        List<Connection> resolved = graph.connections().stream().map(edge -> quantities.containsKey(edge.connectionId())
                ? new Connection(edge.connectionId(), edge.sourceType(), edge.sourceId(), edge.targetType(), edge.targetId(),
                edge.flowType(), edge.materialClass(), edge.materialType(), edge.unit(), edge.period(), quantities.get(edge.connectionId()),
                edge.dataSource(), edge.evidenceStatus(), edge.evidence(), edge.explicitAllocation(), edge.allocationRatio(), edge.scenarioOnly())
                : edge).toList();
        return new MaterialFlowNetwork(graph.networkId(), graph.scenarioName(), graph.mode(), graph.processes(), graph.equipment(), resolved, graph.transferRules());
    }

    private Result evaluate(MaterialFlowNetwork graph, List<String> validationIssues,
                            List<RuleApplication> ruleApplications, List<AllocationCheck> allocationChecks) {
        Map<String, MaterialFlowNetwork.Process> processes = new HashMap<>();
        graph.processes().forEach(p -> processes.put(p.processCode(), p));
        Map<String, Equipment> equipment = new HashMap<>();
        graph.equipment().forEach(e -> equipment.put(e.equipmentId(), e));
        Map<BalanceKey, Totals> totals = new LinkedHashMap<>();
        Map<String, List<Connection>> in = new HashMap<>(), out = new HashMap<>();
        List<EdgeBalance> edgeResults = new ArrayList<>();
        for (Connection edge : graph.connections()) {
            BalanceKey key = key(edge);
            Totals stream = totals.computeIfAbsent(key, ignored -> new Totals());
            stream.edgeCount++;
            if (edge.quantity() == null) stream.complete = false;
            else {
                if (isExternalSource(edge.sourceType())) stream.externalIn = stream.externalIn.add(edge.quantity());
                if (isExternalSink(edge.targetType())) stream.externalOut = stream.externalOut.add(edge.quantity());
                if (edge.targetType() == EndpointType.EQUIPMENT || edge.targetType() == EndpointType.PROCESS)
                    stream.nodeIn.merge(endpoint(edge.targetType(), edge.targetId()), edge.quantity(), BigDecimal::add);
                if (edge.sourceType() == EndpointType.EQUIPMENT || edge.sourceType() == EndpointType.PROCESS)
                    stream.nodeOut.merge(endpoint(edge.sourceType(), edge.sourceId()), edge.quantity(), BigDecimal::add);
            }
            out.computeIfAbsent(endpoint(edge.sourceType(), edge.sourceId()), ignored -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(endpoint(edge.targetType(), edge.targetId()), ignored -> new ArrayList<>()).add(edge);
            edgeResults.add(new EdgeBalance(edge.connectionId(), edge.sourceType(), edge.sourceId(), edge.targetType(),
                    edge.targetId(), edge.materialClass(), edge.materialType(), edge.unit(), edge.period(), edge.quantity(),
                    edge.evidenceStatus(), edge.quantity() == null ? "UNKNOWN_QUANTITY" : "DECLARED_TEST_ONLY"));
        }
        List<NodeBalance> nodeResults = new ArrayList<>();
        Set<String> nodeKeys = new LinkedHashSet<>();
        in.keySet().forEach(nodeKeys::add); out.keySet().forEach(nodeKeys::add);
        for (String nodeKey : nodeKeys) {
            String id = nodeKey.substring(nodeKey.indexOf(':') + 1);
            EndpointType type = nodeKey.startsWith("EQUIPMENT:") ? EndpointType.EQUIPMENT
                    : nodeKey.startsWith("PROCESS:") ? EndpointType.PROCESS : null;
            if (type == null) continue;
            Set<BalanceKey> streams = new LinkedHashSet<>();
            graph.connections().stream().filter(e -> (endpoint(e.sourceType(), e.sourceId()).equals(nodeKey)
                    || endpoint(e.targetType(), e.targetId()).equals(nodeKey))).map(MaterialFlowNetworkEngine::key).forEach(streams::add);
            for (BalanceKey stream : streams) {
                Totals t = totals.get(stream);
                BigDecimal incoming = t.nodeIn.getOrDefault(nodeKey, ZERO), outgoing = t.nodeOut.getOrDefault(nodeKey, ZERO);
                BigDecimal residual = incoming.subtract(outgoing);
                String label = type == EndpointType.EQUIPMENT
                        ? equipment.containsKey(id) ? equipment.get(id).label() : id
                        : processes.containsKey(id) ? processes.get(id).label() : id;
                nodeResults.add(new NodeBalance(type, id, label, stream, incoming, outgoing, residual,
                        t.complete, status(t.complete, residual)));
            }
        }
        List<ProcessBalance> processResults = new ArrayList<>();
        for (MaterialFlowNetwork.Process process : graph.processes()) {
            Set<BalanceKey> streams = new LinkedHashSet<>();
            graph.connections().stream().filter(e -> endpointProcess(e.sourceType(), e.sourceId(), equipment).equals(process.processCode())
                    || endpointProcess(e.targetType(), e.targetId(), equipment).equals(process.processCode()))
                    .map(MaterialFlowNetworkEngine::key).forEach(streams::add);
            for (BalanceKey stream : streams) {
                BigDecimal pin = ZERO, pout = ZERO;
                boolean complete = true;
                for (Connection edge : graph.connections()) {
                    if (!key(edge).equals(stream)) continue;
                    String sourceProcess = endpointProcess(edge.sourceType(), edge.sourceId(), equipment);
                    String targetProcess = endpointProcess(edge.targetType(), edge.targetId(), equipment);
                    if (process.processCode().equals(targetProcess) && !process.processCode().equals(sourceProcess)) {
                        if (edge.quantity() == null) complete = false; else pin = pin.add(edge.quantity());
                    }
                    if (process.processCode().equals(sourceProcess) && !process.processCode().equals(targetProcess)) {
                        if (edge.quantity() == null) complete = false; else pout = pout.add(edge.quantity());
                    }
                }
                BigDecimal residual = pin.subtract(pout);
                processResults.add(new ProcessBalance(process.processCode(), stream, pin, pout, residual,
                        complete, status(complete, residual)));
            }
        }
        Map<BalanceKey, ExternalTotal> external = new LinkedHashMap<>();
        totals.forEach((key, t) -> external.put(key, new ExternalTotal(t.externalIn, t.externalOut, t.complete)));
        List<String> cycles = cycles(graph.connections());
        List<String> findings = new ArrayList<>(validationIssues);
        findings.addAll(unitMismatchFindings(graph));
        if (!cycles.isEmpty()) findings.add("CYCLE_PRESENT_EXPLICIT_FLOWS_ONLY:" + String.join(",", cycles));
        if (graph.connections().stream().anyMatch(e -> e.flowType() == FlowType.BRANCH
                && (e.allocationRatio() == null || !e.explicitAllocation()))) findings.add("BRANCH_ALLOCATION_NOT_DECLARED");
        String resultStatus = nodeResults.stream().anyMatch(b -> !"BALANCED".equals(b.status()))
                || processResults.stream().anyMatch(b -> !"BALANCED".equals(b.status()))
                ? "RESIDUAL_UNRESOLVED" : "CALCULATED";
        return new Result(graph.networkId(), graph.scenarioName(), "TEST_ONLY", true, resultStatus,
                List.copyOf(edgeResults), List.copyOf(nodeResults), List.copyOf(processResults), Map.copyOf(external),
                cycles, List.copyOf(findings), List.copyOf(ruleApplications), List.copyOf(allocationChecks), false, false);
    }

    private List<String> validate(MaterialFlowNetwork graph) {
        List<String> issues = new ArrayList<>();
        if (graph == null) return List.of("NETWORK_REQUIRED");
        if (blank(graph.networkId()) || !"TEST_ONLY".equals(graph.mode())) issues.add("TEST_ONLY_NETWORK_ID_REQUIRED");
        if (graph.connections().isEmpty()) issues.add("NETWORK_CONNECTIONS_REQUIRED");
        Set<String> processIds = new HashSet<>(), equipmentIds = new HashSet<>(), edgeIds = new HashSet<>();
        Map<String, MaterialFlowNetwork.Process> processesById = new HashMap<>();
        Map<String, Equipment> equipmentById = new HashMap<>();
        for (MaterialFlowNetwork.Process p : graph.processes()) {
            if (p == null || blank(p.processCode()) || !processIds.add(p.processCode())) issues.add("PROCESS_ID_MISSING_OR_DUPLICATE");
            else {
                processesById.put(p.processCode(), p);
                if (p.evidenceStatus() == null || blank(p.sourceDocument())) issues.add("PROCESS_PROVENANCE_INCOMPLETE:" + p.processCode());
            }
        }
        for (Equipment e : graph.equipment()) {
            if (e == null || blank(e.equipmentId()) || !equipmentIds.add(e.equipmentId())) issues.add("EQUIPMENT_ID_MISSING_OR_DUPLICATE:" + (e == null ? "" : e.equipmentId()));
            else {
                equipmentById.put(e.equipmentId(), e);
                if (blank(e.processCode()) || !processIds.contains(e.processCode())) issues.add("EQUIPMENT_PROCESS_UNRESOLVED:" + e.equipmentId());
                if (e.evidenceStatus() == null || blank(e.sourceDocument())) issues.add("EQUIPMENT_PROVENANCE_INCOMPLETE:" + e.equipmentId());
            }
        }
        for (Connection e : graph.connections()) {
            if (e == null || blank(e.connectionId()) || !edgeIds.add(e.connectionId())) { issues.add("CONNECTION_ID_MISSING_OR_DUPLICATE"); continue; }
            if (!endpointExists(e.sourceType(), e.sourceId(), processIds, equipmentIds)
                    || !endpointExists(e.targetType(), e.targetId(), processIds, equipmentIds)) issues.add("UNRESOLVED_ENDPOINT:" + e.connectionId());
            if (e.sourceType() == EndpointType.EXTERNAL_SINK || e.targetType() == EndpointType.EXTERNAL_SOURCE)
                issues.add("EXTERNAL_BOUNDARY_DIRECTION_INVALID:" + e.connectionId());
            if (e.materialClass() == null || blank(e.unit()) || blank(e.materialType()) || blank(e.period())) issues.add("MATERIAL_UNIT_PERIOD_REQUIRED:" + e.connectionId());
            if (e.quantity() != null && e.quantity().signum() < 0) issues.add("NEGATIVE_QUANTITY:" + e.connectionId());
            if (e.flowType() == null || e.evidenceStatus() == null) issues.add("FLOW_TYPE_OR_EVIDENCE_STATUS_REQUIRED:" + e.connectionId());
            if (blank(e.dataSource()) || blank(e.evidence())) issues.add("CONNECTION_PROVENANCE_INCOMPLETE:" + e.connectionId());
            if ((e.sourceType() == EndpointType.EXTERNAL_SOURCE && e.flowType() != FlowType.INFLOW)
                    || (e.targetType() == EndpointType.EXTERNAL_SINK && e.flowType() != FlowType.OUTFLOW)
                    || (e.flowType() == FlowType.INFLOW && e.sourceType() != EndpointType.EXTERNAL_SOURCE)
                    || (e.flowType() == FlowType.OUTFLOW && e.targetType() != EndpointType.EXTERNAL_SINK))
                issues.add("EXTERNAL_FLOW_TYPE_MISMATCH:" + e.connectionId());
            if (e.evidenceStatus() == EvidenceStatus.CONFLICT)
                issues.add("CONFLICTING_CONNECTION_BLOCKED:" + e.connectionId());
            if (!e.scenarioOnly() && e.evidenceStatus() != EvidenceStatus.VERIFIED)
                issues.add("UNVERIFIED_CONNECTION_BLOCKED:" + e.connectionId());
            if (!e.scenarioOnly() && e.evidenceStatus() == EvidenceStatus.VERIFIED
                    && (!verifiedEndpoint(e.sourceType(), e.sourceId(), processesById, equipmentById)
                    || !verifiedEndpoint(e.targetType(), e.targetId(), processesById, equipmentById)))
                issues.add("UNVERIFIED_ENDPOINT_BLOCKED:" + e.connectionId());
            if (e.flowType() == FlowType.BRANCH && e.explicitAllocation()
                    && (e.allocationRatio() == null || e.allocationRatio().signum() < 0 || e.allocationRatio().compareTo(BigDecimal.ONE) > 0))
                issues.add("BRANCH_ALLOCATION_RATIO_INVALID:" + e.connectionId());
        }
        Map<String, List<Connection>> branches = new HashMap<>();
        graph.connections().stream().filter(e -> e.flowType() == FlowType.BRANCH)
                .forEach(e -> branches.computeIfAbsent(endpoint(e.sourceType(), e.sourceId()) + "|" + streamKey(e), ignored -> new ArrayList<>()).add(e));
        Set<String> ruleTargets = new HashSet<>();
        graph.transferRules().stream().filter(java.util.Objects::nonNull).map(TransferRule::connectionId)
                .filter(java.util.Objects::nonNull).forEach(ruleTargets::add);
        for (Connection edge : graph.connections()) {
            if (edge.quantity() == null && !ruleTargets.contains(edge.connectionId())) issues.add("UNKNOWN_QUANTITY:" + edge.connectionId());
        }
        branches.forEach((node, edges) -> {
            if (edges.stream().anyMatch(e -> !e.explicitAllocation() || e.allocationRatio() == null))
                issues.add("BRANCH_ALLOCATION_REQUIRED:" + node);
            edges.stream().filter(e -> e.quantity() == null && !ruleTargets.contains(e.connectionId()))
                    .forEach(e -> issues.add("BRANCH_DISTRIBUTION_RULE_REQUIRED:" + e.connectionId()));
            if (edges.stream().allMatch(e -> e.allocationRatio() != null)) {
                BigDecimal sum = edges.stream().map(Connection::allocationRatio).reduce(ZERO, BigDecimal::add);
                if (sum.subtract(ONE).abs().compareTo(TOLERANCE) > 0) issues.add("BRANCH_ALLOCATION_SUM_OUT_OF_TOLERANCE:" + node);
            }
        });
        return List.copyOf(new LinkedHashSet<>(issues));
    }

    private static Impact impact(MaterialFlowNetwork base, Scenario scenario) {
        if (base == null || scenario == null) return new Impact(List.of(), List.of(), List.of(), List.of());
        Map<String, Connection> byId = new LinkedHashMap<>();
        base.connections().forEach(edge -> byId.put(edge.connectionId(), edge));
        if (scenario.replacementOrAddition() != null) byId.put(scenario.replacementOrAddition().connectionId(), scenario.replacementOrAddition());
        Set<String> changedIds = new LinkedHashSet<>();
        if (scenario.connectionId() != null) changedIds.add(scenario.connectionId());
        scenario.allocationChanges().forEach(change -> changedIds.add(change.connectionId()));
        if (changedIds.isEmpty() && scenario.replacementOrAddition() != null) changedIds.add(scenario.replacementOrAddition().connectionId());
        Map<String, Set<String>> forward = new HashMap<>(), reverse = new HashMap<>();
        for (Connection edge : base.connections()) addArc(edge, forward, reverse);
        if (scenario.changeType() == ChangeType.EDGE_ADD || scenario.changeType() == ChangeType.PIPE_CHANGE) addArc(scenario.replacementOrAddition(), forward, reverse);
        Set<String> direct = new LinkedHashSet<>(), downstream = new LinkedHashSet<>(), upstream = new LinkedHashSet<>();
        for (String id : changedIds) {
            Connection changed = byId.get(id);
            if (changed == null) continue;
            direct.add(endpoint(changed.sourceType(), changed.sourceId()));
            direct.add(endpoint(changed.targetType(), changed.targetId()));
            downstream.addAll(walk(endpoint(changed.targetType(), changed.targetId()), forward));
            upstream.addAll(walk(endpoint(changed.sourceType(), changed.sourceId()), reverse));
        }
        Set<String> all = new LinkedHashSet<>(direct); all.addAll(downstream); all.addAll(upstream);
        return new Impact(direct.stream().sorted().toList(), upstream.stream().sorted().toList(),
                downstream.stream().sorted().toList(), all.stream().sorted().toList());
    }

    private static EdgeImpact edgeImpact(MaterialFlowNetwork base, Scenario scenario) {
        Set<String> directIds = new LinkedHashSet<>();
        if (scenario.connectionId() != null) directIds.add(scenario.connectionId());
        scenario.allocationChanges().forEach(change -> directIds.add(change.connectionId()));
        if (directIds.isEmpty() && scenario.replacementOrAddition() != null) directIds.add(scenario.replacementOrAddition().connectionId());
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (Connection edge : base.connections()) outgoing.computeIfAbsent(endpoint(edge.sourceType(), edge.sourceId()), ignored -> new LinkedHashSet<>()).add(edge.connectionId());
        if (scenario.replacementOrAddition() != null) outgoing.computeIfAbsent(endpoint(scenario.replacementOrAddition().sourceType(), scenario.replacementOrAddition().sourceId()), ignored -> new LinkedHashSet<>()).add(scenario.replacementOrAddition().connectionId());
        Map<String, Connection> byId = new HashMap<>();
        base.connections().forEach(edge -> byId.put(edge.connectionId(), edge));
        if (scenario.replacementOrAddition() != null) byId.put(scenario.replacementOrAddition().connectionId(), scenario.replacementOrAddition());
        Set<String> downstream = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String id : directIds) {
            Connection edge = byId.get(id);
            if (edge != null) queue.add(endpoint(edge.targetType(), edge.targetId()));
        }
        Set<String> visitedNodes = new HashSet<>();
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (!visitedNodes.add(node)) continue;
            for (String edgeId : outgoing.getOrDefault(node, Set.of())) {
                if (!directIds.contains(edgeId)) downstream.add(edgeId);
                Connection edge = byId.get(edgeId);
                if (edge != null) queue.add(endpoint(edge.targetType(), edge.targetId()));
            }
        }
        return new EdgeImpact(directIds.stream().sorted().toList(), downstream.stream().sorted().toList());
    }

    private static boolean sameExternalTotals(Map<BalanceKey, ExternalTotal> baseline,
                                              Map<BalanceKey, ExternalTotal> changed) {
        if (baseline.isEmpty() || changed.isEmpty() || !baseline.keySet().equals(changed.keySet())) return false;
        for (BalanceKey key : baseline.keySet()) {
            ExternalTotal before = baseline.get(key), after = changed.get(key);
            if (!before.complete() || !after.complete()
                    || before.inflow().compareTo(after.inflow()) != 0
                    || before.outflow().compareTo(after.outflow()) != 0) return false;
        }
        return true;
    }

    private static Map<BalanceKey, BigDecimal> residualDelta(Result baseline, Result changed) {
        Map<BalanceKey, BigDecimal> base = new HashMap<>();
        baseline.nodeBalances().forEach(b -> base.put(new BalanceKey(b.nodeType() + ":" + b.nodeId(), b.stream().materialClass(), b.stream().materialType(), b.stream().unit(), b.stream().period()), b.residual()));
        Map<BalanceKey, BigDecimal> current = new HashMap<>();
        changed.nodeBalances().forEach(b -> current.put(new BalanceKey(b.nodeType() + ":" + b.nodeId(), b.stream().materialClass(), b.stream().materialType(), b.stream().unit(), b.stream().period()), b.residual()));
        baseline.processBalances().forEach(b -> base.put(new BalanceKey("PROCESS:" + b.processCode(), b.stream().materialClass(), b.stream().materialType(), b.stream().unit(), b.stream().period()), b.residual()));
        changed.processBalances().forEach(b -> current.put(new BalanceKey("PROCESS:" + b.processCode(), b.stream().materialClass(), b.stream().materialType(), b.stream().unit(), b.stream().period()), b.residual()));
        Set<BalanceKey> keys = new LinkedHashSet<>(base.keySet()); keys.addAll(current.keySet());
        Map<BalanceKey, BigDecimal> deltas = new LinkedHashMap<>();
        keys.forEach(key -> deltas.put(key, current.getOrDefault(key, ZERO).subtract(base.getOrDefault(key, ZERO))));
        return Map.copyOf(deltas);
    }

    private static List<String> unitMismatchFindings(MaterialFlowNetwork graph) {
        Set<String> endpoints = new LinkedHashSet<>();
        graph.connections().forEach(e -> { endpoints.add(endpoint(e.sourceType(), e.sourceId())); endpoints.add(endpoint(e.targetType(), e.targetId())); });
        List<String> issues = new ArrayList<>();
        for (String node : endpoints) {
            Map<String, Set<String>> unitsByMaterial = new HashMap<>();
            graph.connections().stream().filter(e -> endpoint(e.sourceType(), e.sourceId()).equals(node)
                    || endpoint(e.targetType(), e.targetId()).equals(node))
                    .forEach(e -> unitsByMaterial.computeIfAbsent(e.materialClass() + "|" + e.materialType() + "|" + e.period(), ignored -> new HashSet<>()).add(e.unit()));
            unitsByMaterial.forEach((material, units) -> { if (units.size() > 1) issues.add("MATERIAL_UNIT_MISMATCH_SEPARATED:" + node + ":" + material); });
        }
        return issues;
    }

    private static List<String> cycles(List<Connection> edges) {
        Map<String, Set<String>> graph = new HashMap<>();
        edges.forEach(e -> graph.computeIfAbsent(endpoint(e.sourceType(), e.sourceId()), k -> new LinkedHashSet<>())
                .add(endpoint(e.targetType(), e.targetId())));
        Set<String> done = new HashSet<>(), visiting = new LinkedHashSet<>(), found = new LinkedHashSet<>();
        for (String node : graph.keySet()) visit(node, graph, done, visiting, found);
        return found.stream().sorted().toList();
    }
    private static void visit(String node, Map<String, Set<String>> graph, Set<String> done, Set<String> visiting, Set<String> found) {
        if (done.contains(node)) return;
        if (!visiting.add(node)) { addCyclePath(node, visiting, found); return; }
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (visiting.contains(next)) addCyclePath(next, visiting, found);
            else visit(next, graph, done, visiting, found);
        }
        visiting.remove(node); done.add(node);
    }
    private static void addCyclePath(String cycleEntry, Set<String> visiting, Set<String> found) {
        boolean insideCycle = false;
        for (String node : visiting) {
            if (node.equals(cycleEntry)) insideCycle = true;
            if (insideCycle) found.add(node);
        }
    }
    private static Set<String> walk(String start, Map<String, Set<String>> graph) {
        Set<String> seen = new LinkedHashSet<>(); ArrayDeque<String> q = new ArrayDeque<>(); q.add(start);
        while (!q.isEmpty()) { String n = q.removeFirst(); if (seen.add(n)) q.addAll(graph.getOrDefault(n, Set.of())); }
        return seen;
    }
    private static void addArc(Connection e, Map<String, Set<String>> f, Map<String, Set<String>> r) {
        String s = endpoint(e.sourceType(), e.sourceId()), t = endpoint(e.targetType(), e.targetId());
        f.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(t); r.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(s);
    }
    private static int indexOf(List<Connection> edges, String id) { for (int i=0;i<edges.size();i++) if (edges.get(i).connectionId().equals(id)) return i; return -1; }
    private static Connection copyQuantity(Connection e, BigDecimal q) { return new Connection(e.connectionId(), e.sourceType(), e.sourceId(), e.targetType(), e.targetId(), e.flowType(), e.materialClass(), e.materialType(), e.unit(), e.period(), q, "TEST_ONLY_SCENARIO", EvidenceStatus.REVIEW_REQUIRED, e.evidence(), e.explicitAllocation(), e.allocationRatio(), true); }
    private static Connection copyAllocation(Connection e, BigDecimal ratio) { return new Connection(e.connectionId(), e.sourceType(), e.sourceId(), e.targetType(), e.targetId(), e.flowType(), e.materialClass(), e.materialType(), e.unit(), e.period(), null, "TEST_ONLY_SCENARIO", EvidenceStatus.REVIEW_REQUIRED, e.evidence(), true, ratio, true); }
    private static Connection asScenario(Connection e) { return new Connection(e.connectionId(), e.sourceType(), e.sourceId(), e.targetType(), e.targetId(), e.flowType(), e.materialClass(), e.materialType(), e.unit(), e.period(), e.quantity(), "TEST_ONLY_SCENARIO", EvidenceStatus.REVIEW_REQUIRED, e.evidence(), e.explicitAllocation(), e.allocationRatio(), true); }
    private static boolean endpointExists(EndpointType type, String id, Set<String> processes, Set<String> equipment) { if (type == null || blank(id)) return false; return switch(type) { case PROCESS -> processes.contains(id); case EQUIPMENT -> equipment.contains(id); case EXTERNAL_SOURCE, EXTERNAL_SINK -> true; }; }
    private static boolean verifiedEndpoint(EndpointType type, String id, Map<String, MaterialFlowNetwork.Process> processes, Map<String, Equipment> equipment) {
        if (type == null) return false;
        return switch (type) {
            case PROCESS -> processes.containsKey(id) && processes.get(id).evidenceStatus() == EvidenceStatus.VERIFIED;
            case EQUIPMENT -> equipment.containsKey(id) && equipment.get(id).evidenceStatus() == EvidenceStatus.VERIFIED;
            case EXTERNAL_SOURCE, EXTERNAL_SINK -> true;
        };
    }
    private static boolean isExternalSource(EndpointType t) { return t == EndpointType.EXTERNAL_SOURCE; }
    private static boolean isExternalSink(EndpointType t) { return t == EndpointType.EXTERNAL_SINK; }
    private static String endpoint(EndpointType type, String id) { return type + ":" + id; }
    private static String endpointProcess(EndpointType t, String id, Map<String, Equipment> equipment) { return t == EndpointType.PROCESS ? id : t == EndpointType.EQUIPMENT && equipment.containsKey(id) ? equipment.get(id).processCode() : ""; }
    private static BalanceKey key(Connection e) { return new BalanceKey("", e.materialClass(), e.materialType(), e.unit(), e.period()); }
    private static String status(boolean complete, BigDecimal residual) { return !complete ? "CALCULATION_UNAVAILABLE" : residual.abs().compareTo(TOLERANCE) <= 0 ? "BALANCED" : "RESIDUAL_UNRESOLVED"; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static Result unavailable(List<String> issues) { return new Result("", "", "TEST_ONLY", false, "CALCULATION_UNAVAILABLE", List.of(), List.of(), List.of(), Map.of(), List.of(), issues, List.of(), List.of(), false, false); }

    private record Impact(List<String> directNodes, List<String> upstreamNodes, List<String> downstreamNodes, List<String> allNodes) { }
    private record EdgeImpact(List<String> directEdges, List<String> downstreamEdges) { }
    private record RuleResolution(Map<String, BigDecimal> quantities, List<String> issues,
                                  List<RuleApplication> applications, List<AllocationCheck> allocationChecks) { }
    private static final class Totals { BigDecimal externalIn=ZERO, externalOut=ZERO; boolean complete=true; int edgeCount; final Map<String,BigDecimal> nodeIn=new HashMap<>(), nodeOut=new HashMap<>(); }
    public record BalanceKey(String scope, MaterialClass materialClass, String materialType, String unit, String period) { }
    public record EdgeBalance(String connectionId, EndpointType sourceType, String sourceId, EndpointType targetType,
                              String targetId, MaterialClass materialClass, String materialType, String unit, String period, BigDecimal quantity,
                              EvidenceStatus evidenceStatus, String status) { }
    public record NodeBalance(EndpointType nodeType, String nodeId, String label, BalanceKey stream,
                             BigDecimal inflow, BigDecimal outflow, BigDecimal residual,
                             boolean complete, String status) { }
    public record ProcessBalance(String processCode, BalanceKey stream, BigDecimal inflow, BigDecimal outflow,
                                 BigDecimal residual, boolean complete, String status) { }
    public record ExternalTotal(BigDecimal inflow, BigDecimal outflow, boolean complete) { }
    public record RuleApplication(String targetConnectionId, TransferMode mode, List<String> basisConnectionIds,
                                  BigDecimal basisQuantity, BigDecimal factor, BigDecimal calculatedQuantity,
                                  String dataSource, EvidenceStatus evidenceStatus, String evidence) { }
    public record AllocationCheck(String branchKey, BigDecimal ratioSum, BigDecimal unallocatedRatio,
                                  BigDecimal tolerance, boolean valid, String status) { }
    public record Result(String networkId, String scenarioName, String mode, boolean calculationAvailable, String status,
                         List<EdgeBalance> edges, List<NodeBalance> nodeBalances, List<ProcessBalance> processBalances,
                         Map<BalanceKey, ExternalTotal> externalTotals, List<String> cycles, List<String> issues,
                         List<RuleApplication> ruleApplications, List<AllocationCheck> allocationChecks,
                         boolean officialDiagramModified, boolean officialMasterModified) { }
    public record ScenarioResult(String scenarioId, String description, ChangeType changeType, Result result,
                                 List<String> issues, Map<BalanceKey, BigDecimal> residualDeltas, List<String> directNodes,
                                 List<String> upstreamNodes, List<String> downstreamNodes, List<String> impactedNodes,
                                 List<String> directEdges, List<String> downstreamEdges, List<String> uncomputedDownstreamEdges,
                                 String readiness, boolean externalBoundaryInvariant, boolean officialFilesModified) { }
    public record WhatIfResult(Result baseline, List<ScenarioResult> scenarios, List<String> issues,
                               boolean baselineAvailable, boolean officialDiagramModified,
                               boolean officialMasterModified) { }
}
