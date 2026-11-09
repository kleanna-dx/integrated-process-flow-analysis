package com.example.processautomation.material;

import static com.example.processautomation.material.MaterialFlowNetwork.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialFlowNetworkEngineTest {
    private final MaterialFlowNetworkEngine engine = new MaterialFlowNetworkEngine();

    @Test
    void computesEquipmentProcessAndExternalBalancesForThreeEquipmentSeries() {
        MaterialFlowNetwork graph = graph("series", List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P2")), List.of(
                edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW),
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", "100", FlowType.TRANSFER),
                edge("bc", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "C", "100", FlowType.INTERPROCESS_RECYCLE),
                edge("out", EndpointType.EQUIPMENT, "C", EndpointType.EXTERNAL_SINK, "sink", "100", FlowType.OUTFLOW)));
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph);
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.nodeBalances()).allSatisfy(b -> {
            assertThat(b.inflow()).isEqualByComparingTo(b.outflow());
            assertThat(b.residual()).isEqualByComparingTo("0");
        });
        assertThat(result.processBalances()).extracting(MaterialFlowNetworkEngine.ProcessBalance::processCode).contains("P1", "P2");
        assertThat(result.externalTotals().values()).singleElement().satisfies(t -> {
            assertThat(t.inflow()).isEqualByComparingTo("100");
            assertThat(t.outflow()).isEqualByComparingTo("100");
        });
    }

    @Test
    void branchMergeAndUnitStreamsAreNeverSilentlyCombined() {
        Connection in = edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW);
        Connection left = branch("left", "A", "B", "40", "0.4");
        Connection right = branch("right", "A", "C", "60", "0.6");
        Connection mergeLeft = edge("b-d", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "D", "40", FlowType.TRANSFER);
        Connection mergeRight = edge("c-d", EndpointType.EQUIPMENT, "C", EndpointType.EQUIPMENT, "D", "60", FlowType.TRANSFER);
        Connection out = edge("out", EndpointType.EQUIPMENT, "D", EndpointType.EXTERNAL_SINK, "sink", "100", FlowType.OUTFLOW);
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph("branch-merge", List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P1"), eq("D", "P2")),
                List.of(in, left, right, mergeLeft, mergeRight, out)));
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.nodeBalances()).filteredOn(b -> b.nodeId().equals("D")).singleElement()
                .satisfies(b -> assertThat(b.inflow()).isEqualByComparingTo("100"));

        MaterialFlowNetwork mixed = graph("unit-separation", List.of(eq("A", "P1")), List.of(
                edge("kg", EndpointType.EXTERNAL_SOURCE, "x", EndpointType.EQUIPMENT, "A", "1", FlowType.INFLOW, "kg/day"),
                edge("ton", EndpointType.EXTERNAL_SOURCE, "y", EndpointType.EQUIPMENT, "A", "2", FlowType.INFLOW, "ton/day")));
        MaterialFlowNetworkEngine.Result mixedResult = engine.calculate(mixed);
        assertThat(mixedResult.nodeBalances()).hasSize(2);
        assertThat(mixedResult.issues()).anyMatch(issue -> issue.startsWith("MATERIAL_UNIT_MISMATCH_SEPARATED:EQUIPMENT:A"));
    }

    @Test
    void identifiesCycleWithoutInventingUnknownFlowAndExplicitFixedRecycleCalculates() {
        MaterialFlowNetwork cycle = graph("cycle", List.of(eq("A", "P1"), eq("B", "P1")), List.of(
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", "10", FlowType.INTERNAL_RECYCLE),
                edge("ba", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "A", "10", FlowType.INTERNAL_RECYCLE)));
        MaterialFlowNetworkEngine.Result result = engine.calculate(cycle);
        assertThat(result.cycles()).isNotEmpty();
        assertThat(result.edges()).extracting(MaterialFlowNetworkEngine.EdgeBalance::quantity).containsExactly(bd("10"), bd("10"));
        MaterialFlowNetwork unknown = graph("unknown", List.of(eq("A", "P1")), List.of(
                edge("unknown", EndpointType.EQUIPMENT, "A", EndpointType.EXTERNAL_SINK, "sink", null, FlowType.OUTFLOW)));
        assertThat(engine.calculate(unknown).status()).isEqualTo("CALCULATION_UNAVAILABLE");
    }

    @Test
    void detectsInterprocessRecycleCycleWithoutSolvingUnknownFlows() {
        MaterialFlowNetwork graph = graph("interprocess-cycle", List.of(eq("A", "P1"), eq("B", "P2")), List.of(
                edge("p1-p2", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", "12", FlowType.INTERPROCESS_RECYCLE),
                edge("p2-p1", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "A", "12", FlowType.INTERPROCESS_RECYCLE)));
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph);
        assertThat(result.cycles()).contains("EQUIPMENT:A", "EQUIPMENT:B");
        assertThat(result.issues()).anyMatch(issue -> issue.startsWith("CYCLE_PRESENT_EXPLICIT_FLOWS_ONLY:"));
        assertThat(result.edges()).extracting(MaterialFlowNetworkEngine.EdgeBalance::quantity)
                .containsExactly(bd("12"), bd("12"));
    }

    @Test
    void whatIfClonesBaselinePreservesExternalBoundaryAndReportsResidualDeltasAndDirectionalReach() {
        MaterialFlowNetwork base = graph("base", List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P2")), List.of(
                edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW),
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", "100", FlowType.TRANSFER),
                edge("bc", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "C", "20", FlowType.INTERPROCESS_RECYCLE),
                edge("out", EndpointType.EQUIPMENT, "C", EndpointType.EXTERNAL_SINK, "sink", "100", FlowType.OUTFLOW)));
        MaterialFlowNetworkEngine.WhatIfResult report = engine.simulate(base, List.of(
                new Scenario("at-10", "synthetic 20 to 10", ChangeType.FLOW_CHANGE, "bc", bd("10"), null),
                new Scenario("at-50", "synthetic 20 to 50", ChangeType.FLOW_CHANGE, "bc", bd("50"), null),
                new Scenario("same-boundary-different-scale", "synthetic scale-only input change", ChangeType.FLOW_CHANGE, "in", bd("100.00"), null)));
        assertThat(report.baseline().edges()).filteredOn(e -> e.connectionId().equals("bc")).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("20"));
        assertThat(report.scenarios()).hasSize(3).allSatisfy(s -> assertThat(s.externalBoundaryInvariant()).isTrue());
        assertThat(report.scenarios().get(2).result().edges()).filteredOn(e -> e.connectionId().equals("in")).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("100.00"));
        assertThat(report.scenarios().get(0).result().edges()).filteredOn(e -> e.connectionId().equals("bc")).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("10"));
        assertThat(report.scenarios().get(0).residualDeltas().values()).anyMatch(v -> v.compareTo(ZERO) != 0);
        assertThat(report.scenarios().get(0).impactedNodes()).contains("EQUIPMENT:A", "EQUIPMENT:B", "EQUIPMENT:C");
        assertThat(report.scenarios().get(0).upstreamNodes()).contains("EQUIPMENT:A", "EQUIPMENT:B");
        assertThat(report.scenarios().get(0).downstreamNodes()).contains("EQUIPMENT:C");
        assertThat(report.scenarios().get(1).result().edges()).filteredOn(e -> e.connectionId().equals("bc")).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("50"));
        assertThat(base.connections()).filteredOn(e -> e.connectionId().equals("bc")).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("20"));
    }

    @Test
    void rejectsUnverifiedConflictEndpointNegativeAndBranchWithoutAllocation() {
        Connection candidate = new Connection("candidate", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "MISSING",
                FlowType.BRANCH, MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11", bd("1"), "drawing arrow",
                EvidenceStatus.REVIEW_REQUIRED, "not verified", false, null, false);
        MaterialFlowNetworkEngine.Result blocked = engine.calculate(graph("blocked", List.of(eq("A", "P1")), List.of(candidate)));
        assertThat(blocked.calculationAvailable()).isFalse();
        assertThat(blocked.issues()).contains("UNRESOLVED_ENDPOINT:candidate", "UNVERIFIED_CONNECTION_BLOCKED:candidate");
        assertThat(blocked.issues()).anyMatch(issue -> issue.startsWith("BRANCH_ALLOCATION_REQUIRED:EQUIPMENT:A"));
        Connection conflict = new Connection("conflict", EndpointType.EQUIPMENT, "A", EndpointType.EXTERNAL_SINK, "out",
                FlowType.OUTFLOW, MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11", bd("1"),
                "test-input", EvidenceStatus.CONFLICT, "conflicting records", false, null, false);
        assertThat(engine.calculate(graph("conflict", List.of(eq("A", "P1")), List.of(conflict))).issues())
                .contains("CONFLICTING_CONNECTION_BLOCKED:conflict", "UNVERIFIED_CONNECTION_BLOCKED:conflict");
        Connection verifiedEdge = new Connection("verified-edge", EndpointType.EQUIPMENT, "A", EndpointType.EXTERNAL_SINK, "out",
                FlowType.OUTFLOW, MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11", bd("1"),
                "synthetic-proof", EvidenceStatus.VERIFIED, "synthetic verifier fixture", false, null, false);
        assertThat(engine.calculate(graph("unverified-endpoint", List.of(eq("A", "P1")), List.of(verifiedEdge))).issues())
                .contains("UNVERIFIED_ENDPOINT_BLOCKED:verified-edge");
        Connection unknown = new Connection("unknown", EndpointType.EQUIPMENT, "A", EndpointType.EXTERNAL_SINK, "out",
                FlowType.OUTFLOW, MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11", bd("1"),
                "synthetic-input", EvidenceStatus.UNKNOWN, "unverified", false, null, false);
        assertThat(engine.calculate(graph("unknown", List.of(eq("A", "P1")), List.of(unknown))).issues())
                .contains("UNVERIFIED_CONNECTION_BLOCKED:unknown");
        Connection negative = edge("negative", EndpointType.EQUIPMENT, "A", EndpointType.EXTERNAL_SINK, "out", "-1", FlowType.OUTFLOW);
        assertThat(engine.calculate(graph("negative", List.of(eq("A", "P1")), List.of(negative))).issues())
                .contains("NEGATIVE_QUANTITY:negative");
        Connection wrongExternalType = edge("wrong-external-type", EndpointType.EXTERNAL_SOURCE, "feed",
                EndpointType.EQUIPMENT, "A", "1", FlowType.TRANSFER);
        assertThat(engine.calculate(graph("wrong-external-type", List.of(eq("A", "P1")), List.of(wrongExternalType))).issues())
                .contains("EXTERNAL_FLOW_TYPE_MISMATCH:wrong-external-type");
    }

    @Test
    void supportsEdgeAddDeleteAndRejectsDuplicateOrMissingChange() {
        MaterialFlowNetwork base = graph("topology", List.of(eq("A", "P1"), eq("B", "P1")), List.of(
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", "5", FlowType.TRANSFER)));
        Connection added = new Connection("bc", EndpointType.EQUIPMENT, "B", EndpointType.EXTERNAL_SINK, "sink",
                FlowType.OUTFLOW, MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11", bd("5"),
                "synthetic-verified-candidate", EvidenceStatus.VERIFIED, "synthetic candidate only", false, null, false);
        MaterialFlowNetworkEngine.WhatIfResult addedResult = engine.simulate(base, List.of(new Scenario("add", "pipe proposal", ChangeType.EDGE_ADD, null, null, added)));
        assertThat(addedResult.scenarios()).singleElement().satisfies(s -> {
            assertThat(s.result().edges()).hasSize(2);
            assertThat(s.readiness()).isEqualTo("REVIEW_REQUIRED");
            assertThat(s.result().edges()).filteredOn(e -> e.connectionId().equals("bc")).singleElement()
                    .satisfies(edge -> assertThat(edge.evidenceStatus()).isEqualTo(EvidenceStatus.REVIEW_REQUIRED));
        });
        MaterialFlowNetworkEngine.WhatIfResult deleted = engine.simulate(base, List.of(new Scenario("delete", "scenario deletion", ChangeType.EDGE_DELETE, "ab", null, null)));
        assertThat(deleted.scenarios()).singleElement().satisfies(s -> {
            assertThat(s.result().edges()).isEmpty();
            assertThat(s.result().calculationAvailable()).isFalse();
        });
        MaterialFlowNetworkEngine.WhatIfResult missing = engine.simulate(base, List.of(new Scenario("missing", "bad id", ChangeType.FLOW_CHANGE, "nope", bd("1"), null)));
        assertThat(missing.issues()).contains("CHANGED_EDGE_NOT_FOUND:nope");
    }

    @Test
    void distributesExplicitlyAlong60_40_20_30_50AndSinglePathBranches() {
        assertBranchDistribution("60-40", "100", List.of("0.6", "0.4"), List.of("60", "40"));
        assertBranchDistribution("20-30-50", "100", List.of("0.2", "0.3", "0.5"), List.of("20", "30", "50"));
        assertBranchDistribution("single-100", "100", List.of("1"), List.of("100"));
    }

    @Test
    void rejectsIncompleteExcessiveNegativeUnknownAndPartiallyMissingBranchRules() {
        assertThat(branchNetwork("short", "100", List.of("0.6", "0.3"), true).transferRules()).isNotEmpty();
        assertThat(engine.calculate(branchNetwork("short", "100", List.of("0.6", "0.3"), true)).calculationAvailable()).isFalse();
        assertThat(engine.calculate(branchNetwork("over", "100", List.of("0.6", "0.5"), true)).issues())
                .anyMatch(issue -> issue.startsWith("BRANCH_ALLOCATION_SUM_OUT_OF_TOLERANCE:"));
        assertThat(engine.calculate(branchNetwork("negative", "100", List.of("-0.1", "1.1"), true)).issues())
                .anyMatch(issue -> issue.startsWith("BRANCH_ALLOCATION_RATIO_INVALID:"));
        MaterialFlowNetwork missingRule = branchNetwork("missing-rule", "100", List.of("0.6", "0.4"), false);
        assertThat(engine.calculate(missingRule).calculationAvailable()).isFalse();
        MaterialFlowNetwork completeBranch = branchNetwork("partial-rule", "100", List.of("0.6", "0.4"), true);
        MaterialFlowNetwork partialRule = new MaterialFlowNetwork(completeBranch.networkId(), completeBranch.scenarioName(),
                completeBranch.mode(), completeBranch.processes(), completeBranch.equipment(), completeBranch.connections(),
                completeBranch.transferRules().subList(0, 1));
        assertThat(engine.calculate(partialRule).issues()).contains("BRANCH_DISTRIBUTION_RULE_REQUIRED:out-1");
        MaterialFlowNetwork unknownBasis = branchNetwork("unknown-basis", null, List.of("0.6", "0.4"), true);
        assertThat(engine.calculate(unknownBasis).issues()).anyMatch(issue -> issue.contains("UNKNOWN_QUANTITY"));
    }

    @Test
    void rejectsRuleMaterialUnitAndPeriodMismatchAndKeepsMaterialStreamsIndependent() {
        MaterialFlowNetwork base = branchNetwork("mismatch", "100", List.of("0.6", "0.4"), true);
        Connection in = base.connections().get(0);
        Connection left = base.connections().get(1);
        Connection right = base.connections().get(2);
        MaterialFlowNetwork unitMismatch = new MaterialFlowNetwork("unit-mismatch", "synthetic", "TEST_ONLY", base.processes(),
                base.equipment(), List.of(in, streamVariant(left, "kg/day", "2026-11"),
                        streamVariant(right, "kg/day", "2026-11")), base.transferRules());
        assertThat(engine.calculate(unitMismatch).issues()).contains("TRANSFER_STREAM_MISMATCH:out-0:in");
        MaterialFlowNetwork periodMismatch = new MaterialFlowNetwork("period-mismatch", "synthetic", "TEST_ONLY", base.processes(),
                base.equipment(), List.of(in, streamVariant(left, "m3/day", "2026-10"),
                        streamVariant(right, "m3/day", "2026-10")), base.transferRules());
        assertThat(engine.calculate(periodMismatch).issues()).contains("TRANSFER_STREAM_MISMATCH:out-0:in");

        List<Equipment> equipment = List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P1"));
        Connection wwIn = edge("ww-in", EndpointType.EXTERNAL_SOURCE, "w", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW);
        Connection wasteIn = new Connection("waste-in", EndpointType.EXTERNAL_SOURCE, "s", EndpointType.EQUIPMENT, "A", FlowType.INFLOW,
                MaterialClass.WASTE, "sludge", "ton/day", "2026-11", bd("50"), "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED,
                "synthetic only", false, null, true);
        Connection wwB = branch("ww-b", "A", "B", null, "0.7");
        Connection wwC = branch("ww-c", "A", "C", null, "0.3");
        Connection wasteB = new Connection("waste-b", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", FlowType.BRANCH,
                MaterialClass.WASTE, "sludge", "ton/day", "2026-11", null, "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED,
                "synthetic only", true, bd("0.2"), true);
        Connection wasteC = new Connection("waste-c", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "C", FlowType.BRANCH,
                MaterialClass.WASTE, "sludge", "ton/day", "2026-11", null, "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED,
                "synthetic only", true, bd("0.8"), true);
        MaterialFlowNetwork mixed = new MaterialFlowNetwork("material-branches", "synthetic", "TEST_ONLY",
                List.of(new MaterialFlowNetwork.Process("P1", "P1", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)), equipment,
                List.of(wwIn, wasteIn, wwB, wwC, wasteB, wasteC), List.of(
                ratioRule("ww-b", "ww-in", "0.7"), ratioRule("ww-c", "ww-in", "0.3"),
                ratioRule("waste-b", "waste-in", "0.2"), ratioRule("waste-c", "waste-in", "0.8")));
        MaterialFlowNetworkEngine.Result result = engine.calculate(mixed);
        assertThat(result.calculationAvailable()).isTrue();
        List<BigDecimal> wastewater = result.edges().stream().filter(e -> List.of("ww-b", "ww-c").contains(e.connectionId()))
                .map(MaterialFlowNetworkEngine.EdgeBalance::quantity).toList();
        assertThat(wastewater.get(0)).isEqualByComparingTo("70");
        assertThat(wastewater.get(1)).isEqualByComparingTo("30");
        List<BigDecimal> waste = result.edges().stream().filter(e -> List.of("waste-b", "waste-c").contains(e.connectionId()))
                .map(MaterialFlowNetworkEngine.EdgeBalance::quantity).toList();
        assertThat(waste.get(0)).isEqualByComparingTo("10");
        assertThat(waste.get(1)).isEqualByComparingTo("40");
    }

    @Test
    void explicitTransformationRuleCanChangeMaterialOnlyWithEvidenceAndSamePeriod() {
        List<Equipment> equipment = List.of(eq("A", "P1"), eq("B", "P1"));
        Connection feed = edge("feed", EndpointType.EXTERNAL_SOURCE, "source", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW);
        Connection converted = new Connection("converted", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", FlowType.TRANSFER,
                MaterialClass.CHEMICAL, "treated chemical", "kg/day", "2026-11", null, "synthetic-transform",
                EvidenceStatus.REVIEW_REQUIRED, "synthetic transform factor", false, null, true);
        MaterialFlowNetwork graph = new MaterialFlowNetwork("transform", "synthetic", "TEST_ONLY",
                List.of(new MaterialFlowNetwork.Process("P1", "P1", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)),
                equipment, List.of(feed, converted), List.of(new TransferRule("converted", TransferMode.TRANSFORM,
                List.of("feed"), new BigDecimal("0.5"), "synthetic-transform", EvidenceStatus.REVIEW_REQUIRED,
                "synthetic factor 0.5", true)));
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph);
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.edges()).filteredOn(e -> e.connectionId().equals("converted")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("50"));
        MaterialFlowNetwork invalidPeriod = new MaterialFlowNetwork("transform-period", "synthetic", "TEST_ONLY",
                graph.processes(), equipment, List.of(feed, streamVariant(converted, "kg/day", "2026-10")), graph.transferRules());
        assertThat(engine.calculate(invalidPeriod).issues()).contains("TRANSFER_PERIOD_MISMATCH:converted:feed");
        Connection mismatchedBasis = new Connection("other-stream", EndpointType.EXTERNAL_SOURCE, "other",
                EndpointType.EQUIPMENT, "A", FlowType.INFLOW, MaterialClass.WASTE, "sludge", "ton/day", "2026-11",
                bd("1"), "synthetic-transform", EvidenceStatus.REVIEW_REQUIRED, "synthetic basis", false, null, true);
        MaterialFlowNetwork mixedBases = new MaterialFlowNetwork("transform-mixed-bases", "synthetic", "TEST_ONLY",
                graph.processes(), equipment, List.of(feed, mismatchedBasis, converted), List.of(new TransferRule("converted",
                TransferMode.TRANSFORM, List.of("feed", "other-stream"), new BigDecimal("0.5"), "synthetic-transform",
                EvidenceStatus.REVIEW_REQUIRED, "invalid mixed-stream transform basis", true)));
        assertThat(engine.calculate(mixedBases).issues()).contains("TRANSFORM_BASIS_STREAM_MISMATCH:converted:other-stream");
    }

    @Test
    void propagatesFourEquipmentSeriesAcrossProcessesAndBlocksCyclesOrMissingRules() {
        MaterialFlowNetwork network = seriesWithRules();
        MaterialFlowNetworkEngine.Result result = engine.calculate(network);
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.edges()).filteredOn(e -> List.of("ab", "bc", "cd", "out").contains(e.connectionId()))
                .allSatisfy(edge -> assertThat(edge.quantity()).isEqualByComparingTo("100"));

        MaterialFlowNetwork missing = new MaterialFlowNetwork(network.networkId(), network.scenarioName(), network.mode(),
                network.processes(), network.equipment(), network.connections(), network.transferRules().subList(0, 2));
        assertThat(engine.calculate(missing).issues()).anyMatch(issue -> issue.startsWith("UNKNOWN_QUANTITY:"));

        MaterialFlowNetwork cycle = new MaterialFlowNetwork("rule-cycle", "cycle", "TEST_ONLY", network.processes(),
                network.equipment(), List.of(edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", null, FlowType.TRANSFER),
                        edge("ba", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "A", null, FlowType.TRANSFER)),
                List.of(passRule("ab", "ba"), passRule("ba", "ab")));
        assertThat(engine.calculate(cycle).issues()).anyMatch(issue -> issue.startsWith("TRANSFER_RULE_DEPENDENCY_CYCLE_OR_UNKNOWN_BASIS:"));
    }

    @Test
    void propagatesBranchThenMergeAndReportsDownstreamImpactAcrossProcessBoundary() {
        List<Equipment> equipment = List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P2"), eq("D", "P3"));
        List<Connection> edges = List.of(edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW),
                branch("ab", "A", "B", null, "0.6"), branch("ac", "A", "C", null, "0.4"),
                edge("bd", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "D", null, FlowType.TRANSFER),
                edge("cd", EndpointType.EQUIPMENT, "C", EndpointType.EQUIPMENT, "D", null, FlowType.INTERPROCESS_RECYCLE),
                edge("out", EndpointType.EQUIPMENT, "D", EndpointType.EXTERNAL_SINK, "sink", null, FlowType.OUTFLOW));
        List<TransferRule> rules = List.of(ratioRule("ab", "in", "0.6"), ratioRule("ac", "in", "0.4"),
                passRule("bd", "ab"), passRule("cd", "ac"),
                new TransferRule("out", TransferMode.SUM_INPUTS, List.of("bd", "cd"), BigDecimal.ONE,
                        "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED, "sum merged streams", true));
        MaterialFlowNetwork graph = new MaterialFlowNetwork("branch-merge-chain", "synthetic", "TEST_ONLY",
                List.of(new MaterialFlowNetwork.Process("P1", "P1", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED),
                        new MaterialFlowNetwork.Process("P2", "P2", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED),
                        new MaterialFlowNetwork.Process("P3", "P3", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)),
                equipment, edges, rules);
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph);
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.edges()).filteredOn(e -> e.connectionId().equals("out")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("100"));
        assertThat(result.processBalances()).extracting(MaterialFlowNetworkEngine.ProcessBalance::processCode)
                .contains("P1", "P2", "P3");
        MaterialFlowNetworkEngine.WhatIfResult whatIf = engine.simulate(graph, List.of(
                new Scenario("feed-120", "change synthetic feed", ChangeType.FLOW_CHANGE, "in", bd("120"), null)));
        var scenario = whatIf.scenarios().get(0);
        assertThat(scenario.directEdges()).contains("in");
        assertThat(scenario.downstreamEdges()).contains("ab", "ac", "bd", "cd", "out");
        assertThat(scenario.downstreamNodes()).contains("EQUIPMENT:D");
        assertThat(scenario.result().edges()).filteredOn(e -> e.connectionId().equals("out")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("120"));
    }

    @Test
    void whatIfScenariosRecomputeIndependentlyFromBaselineAndAllocationChangesRebalance() {
        MaterialFlowNetwork base = seriesWithRules();
        MaterialFlowNetworkEngine.WhatIfResult report = engine.simulate(base, List.of(
                new Scenario("flow-10", "20 to 10", ChangeType.FLOW_CHANGE, "in", bd("10"), null),
                new Scenario("flow-50", "20 to 50", ChangeType.FLOW_CHANGE, "in", bd("50"), null)));
        assertThat(report.scenarios()).hasSize(2);
        assertThat(report.baseline().edges()).filteredOn(e -> e.connectionId().equals("in")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("100"));
        assertThat(report.scenarios().get(0).result().edges()).filteredOn(e -> e.connectionId().equals("out")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("10"));
        assertThat(report.scenarios().get(1).result().edges()).filteredOn(e -> e.connectionId().equals("out")).singleElement()
                .satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("50"));
        assertThat(report.scenarios()).allSatisfy(s -> assertThat(s.readiness()).isEqualTo("REVIEW_REQUIRED"));
        assertThat(report.scenarios().get(0).externalBoundaryInvariant()).isFalse();
        assertThat(report.scenarios().get(0).result().externalTotals().values()).singleElement().satisfies(total -> {
            assertThat(total.inflow()).isEqualByComparingTo("10");
            assertThat(total.outflow()).isEqualByComparingTo("10");
        });
        MaterialFlowNetwork missingRule = new MaterialFlowNetwork(base.networkId(), base.scenarioName(), base.mode(),
                base.processes(), base.equipment(), base.connections(), base.transferRules().subList(0, 2));
        var blocked = engine.simulate(missingRule, List.of(new Scenario("blocked-propagation", "missing downstream rule",
                ChangeType.FLOW_CHANGE, "in", bd("120"), null))).scenarios().get(0);
        assertThat(blocked.result().calculationAvailable()).isFalse();
        assertThat(blocked.externalBoundaryInvariant()).isFalse();
        assertThat(blocked.uncomputedDownstreamEdges()).contains("ab", "bc", "cd", "out");

        MaterialFlowNetwork branch = branchNetwork("ratio-base", "100", List.of("0.6", "0.4"), true);
        var changedRatios = engine.simulate(branch, List.of(new Scenario("ratio-change", "60:40 to 30:70",
                ChangeType.BRANCH_ALLOCATION_CHANGE, "out-0", null, null,
                List.of(new AllocationChange("out-0", bd("0.3")), new AllocationChange("out-1", bd("0.7"))))));
        assertThat(changedRatios.scenarios()).hasSize(1);
        assertThat(changedRatios.scenarios().get(0).result().edges()).filteredOn(e -> e.connectionId().equals("out-0"))
                .singleElement().satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("30"));
        assertThat(changedRatios.scenarios().get(0).result().edges()).filteredOn(e -> e.connectionId().equals("out-1"))
                .singleElement().satisfies(edge -> assertThat(edge.quantity()).isEqualByComparingTo("70"));
    }

    @Test
    void separatesPreexistingBaselineResidualFromScenarioIncrementAndReportsBoundaryChange() {
        List<Equipment> equipment = List.of(eq("A", "P1"), eq("B", "P1"));
        List<Connection> edges = List.of(edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW),
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", null, FlowType.TRANSFER),
                edge("out", EndpointType.EQUIPMENT, "B", EndpointType.EXTERNAL_SINK, "sink", null, FlowType.OUTFLOW));
        MaterialFlowNetwork base = new MaterialFlowNetwork("baseline-residual", "synthetic", "TEST_ONLY",
                List.of(new MaterialFlowNetwork.Process("P1", "P1", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)),
                equipment, edges, List.of(new TransferRule("ab", TransferMode.TRANSFORM, List.of("in"), new BigDecimal("0.9"),
                        "synthetic-loss-evidence", EvidenceStatus.REVIEW_REQUIRED, "synthetic documented loss", true),
                        passRule("out", "ab")));
        var report = engine.simulate(base, List.of(new Scenario("residual-change", "feed from 100 to 120",
                ChangeType.FLOW_CHANGE, "in", bd("120"), null)));
        var scenario = report.scenarios().get(0);
        assertThat(report.baseline().nodeBalances()).filteredOn(balance -> balance.nodeId().equals("A")).singleElement()
                .satisfies(balance -> assertThat(balance.residual()).isEqualByComparingTo("10"));
        assertThat(scenario.result().nodeBalances()).filteredOn(balance -> balance.nodeId().equals("A")).singleElement()
                .satisfies(balance -> assertThat(balance.residual()).isEqualByComparingTo("12"));
        MaterialFlowNetworkEngine.BalanceKey equipmentA = new MaterialFlowNetworkEngine.BalanceKey("EQUIPMENT:A",
                MaterialClass.WASTEWATER, "wastewater", "m3/day", "2026-11");
        assertThat(scenario.residualDeltas().get(equipmentA)).isEqualByComparingTo("2");
        assertThat(scenario.externalBoundaryInvariant()).isFalse();
        assertThat(scenario.result().externalTotals().values()).singleElement().satisfies(total -> {
            assertThat(total.inflow()).isEqualByComparingTo("120");
            assertThat(total.outflow()).isEqualByComparingTo("108");
        });
    }

    @Test
    void roundingRetainsIndependentBigDecimalBranchesWithoutAssigningRemainderAndExcelContractStaysUnknown() throws Exception {
        MaterialFlowNetwork graph = branchNetwork("rounding", "1", List.of("0.333333333", "0.333333333", "0.333333334"), true);
        var result = engine.calculate(graph);
        assertThat(result.calculationAvailable()).isTrue();
        assertThat(result.edges()).filteredOn(e -> e.connectionId().startsWith("out-"))
                .extracting(MaterialFlowNetworkEngine.EdgeBalance::quantity)
                .containsExactly(bd("0.333333333"), bd("0.333333333"), bd("0.333333334"));
        assertThat(result.allocationChecks()).singleElement().satisfies(check -> {
            assertThat(check.ratioSum()).isEqualByComparingTo("1");
            assertThat(check.unallocatedRatio()).isEqualByComparingTo("0");
        });

        var whatIf = engine.simulate(graph, List.of(new Scenario("excel-contract", "synthetic input", ChangeType.FLOW_CHANGE,
                "in", bd("2"), null)));
        var contract = engine.toExcelChangeContract(graph, whatIf, whatIf.scenarios().get(0));
        assertThat(contract.modificationAllowed()).isFalse();
        assertThat(contract.workbookMappings()).allSatisfy(mapping -> {
            assertThat(mapping.workbook()).isEqualTo("UNKNOWN");
            assertThat(mapping.sheet()).isEqualTo("UNKNOWN");
            assertThat(mapping.cell()).isEqualTo("UNKNOWN");
            assertThat(mapping.shape()).isEqualTo("UNKNOWN");
            assertThat(mapping.editable()).isFalse();
        });
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(contract);
        assertThat(json).contains("scenarioId", "REVIEW_REQUIRED", "UNKNOWN", "modificationAllowed");
    }

    private void assertBranchDistribution(String id, String input, List<String> ratios, List<String> expected) {
        MaterialFlowNetwork graph = branchNetwork(id, input, ratios, true);
        MaterialFlowNetworkEngine.Result result = engine.calculate(graph);
        assertThat(result.calculationAvailable()).isTrue();
        List<BigDecimal> actual = result.edges().stream().filter(e -> e.connectionId().startsWith("out-"))
                .map(MaterialFlowNetworkEngine.EdgeBalance::quantity).toList();
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) assertThat(actual.get(i)).isEqualByComparingTo(expected.get(i));
        assertThat(result.ruleApplications()).hasSize(ratios.size());
    }

    private MaterialFlowNetwork branchNetwork(String id, String input, List<String> ratios, boolean includeRules) {
        List<Equipment> equipment = new ArrayList<>();
        equipment.add(eq("A", "P1"));
        List<Connection> edges = new ArrayList<>();
        edges.add(edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", input, FlowType.INFLOW));
        List<TransferRule> rules = new ArrayList<>();
        for (int i = 0; i < ratios.size(); i++) {
            String target = "T" + i;
            String edgeId = "out-" + i;
            equipment.add(eq(target, "P1"));
            edges.add(branch(edgeId, "A", target, null, ratios.get(i)));
            if (includeRules) rules.add(ratioRule(edgeId, "in", ratios.get(i)));
        }
        List<MaterialFlowNetwork.Process> processes = List.of(new MaterialFlowNetwork.Process("P1", "P1",
                "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED));
        return new MaterialFlowNetwork(id, id, "TEST_ONLY", processes, equipment, edges, rules);
    }

    private MaterialFlowNetwork seriesWithRules() {
        List<Equipment> equipment = List.of(eq("A", "P1"), eq("B", "P1"), eq("C", "P2"), eq("D", "P3"));
        List<Connection> edges = List.of(edge("in", EndpointType.EXTERNAL_SOURCE, "feed", EndpointType.EQUIPMENT, "A", "100", FlowType.INFLOW),
                edge("ab", EndpointType.EQUIPMENT, "A", EndpointType.EQUIPMENT, "B", null, FlowType.TRANSFER),
                edge("bc", EndpointType.EQUIPMENT, "B", EndpointType.EQUIPMENT, "C", null, FlowType.INTERPROCESS_RECYCLE),
                edge("cd", EndpointType.EQUIPMENT, "C", EndpointType.EQUIPMENT, "D", null, FlowType.TRANSFER),
                edge("out", EndpointType.EQUIPMENT, "D", EndpointType.EXTERNAL_SINK, "sink", null, FlowType.OUTFLOW));
        return new MaterialFlowNetwork("series-rules", "synthetic", "TEST_ONLY",
                List.of(new MaterialFlowNetwork.Process("P1", "P1", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED),
                        new MaterialFlowNetwork.Process("P2", "P2", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED),
                        new MaterialFlowNetwork.Process("P3", "P3", "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)),
                equipment, edges, List.of(passRule("ab", "in"), passRule("bc", "ab"), passRule("cd", "bc"), passRule("out", "cd")));
    }

    private Connection streamVariant(Connection edge, String unit, String period) {
        return new Connection(edge.connectionId(), edge.sourceType(), edge.sourceId(), edge.targetType(), edge.targetId(),
                edge.flowType(), edge.materialClass(), edge.materialType(), unit, period, edge.quantity(), edge.dataSource(),
                edge.evidenceStatus(), edge.evidence(), edge.explicitAllocation(), edge.allocationRatio(), edge.scenarioOnly());
    }

    private TransferRule ratioRule(String target, String basis, String ratio) {
        return new TransferRule(target, TransferMode.BRANCH_RATIO, List.of(basis), bd(ratio), "synthetic-fixture",
                EvidenceStatus.REVIEW_REQUIRED, "synthetic allocation rule", true);
    }

    private TransferRule passRule(String target, String basis) {
        return new TransferRule(target, TransferMode.PASS_THROUGH, List.of(basis), BigDecimal.ONE,
                "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED, "synthetic pass-through rule", true);
    }

    private static MaterialFlowNetwork graph(String id, List<Equipment> equipment, List<Connection> edges) {
        List<MaterialFlowNetwork.Process> processes = equipment.stream().map(Equipment::processCode).distinct()
                .map(p -> new MaterialFlowNetwork.Process(p, p, "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED)).toList();
        return new MaterialFlowNetwork(id, id, "TEST_ONLY", processes, equipment, edges);
    }
    private static Equipment eq(String id, String process) { return new Equipment(id, process, id, "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED); }
    private static Connection edge(String id, EndpointType st, String s, EndpointType tt, String t, String q, FlowType type) {
        return edge(id, st, s, tt, t, q, type, "m3/day");
    }
    private static Connection edge(String id, EndpointType st, String s, EndpointType tt, String t, String q, FlowType type, String unit) {
        return new Connection(id, st, s, tt, t, type, MaterialClass.WASTEWATER, "wastewater", unit, "2026-11", q == null ? null : bd(q),
                "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED, "synthetic only", false, null, true);
    }
    private static Connection branch(String id, String s, String t, String quantity, String ratio) {
        return new Connection(id, EndpointType.EQUIPMENT, s, EndpointType.EQUIPMENT, t, FlowType.BRANCH, MaterialClass.WASTEWATER,
                "wastewater", "m3/day", "2026-11", quantity == null ? null : bd(quantity), "synthetic-fixture", EvidenceStatus.REVIEW_REQUIRED,
                "synthetic only", true, bd(ratio), true);
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static final BigDecimal ZERO = BigDecimal.ZERO;
}
