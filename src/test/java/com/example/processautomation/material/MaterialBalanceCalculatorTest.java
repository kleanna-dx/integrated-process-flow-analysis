package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialBalanceCalculatorTest {
    private final MaterialBalanceCalculator calculator = new MaterialBalanceCalculator();

    @Test
    void testOnlyMassBalanceBranchesWasteAndInterprocessFlowAndPropagatesDownstreamChange() {
        var request = new MaterialBalanceRequest("demo-100t-001", "Hypothetical branch/waste/interprocess case", "TEST_ONLY",
                List.of(
                        node("A", "Hypothetical source", "P-TEST-A", "SOURCE", "100", "WASTEWATER", "ton/day"),
                        node("W", "Waste sink", "P-TEST-A", "WASTE_SINK", null, null, null),
                        node("P2", "Receiving process", "P-TEST-B", "PROCESS", null, null, null),
                        node("P3", "Downstream process", "P-TEST-C", "PROCESS", null, null, null),
                        node("D", "Final outlet", "P-TEST-C", "OUTLET", null, null, null)),
                List.of(
                        transfer("waste-0_5", "A", "W", "WASTE_GENERATION", "WASTE", "0.5", "ton/day"),
                        transfer("move-2_5", "A", "P2", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5", "ton/day"),
                        transfer("after-change-97", "A", "D", "DOWNSTREAM", "WASTEWATER", "97", "ton/day"),
                        transfer("p2-forward-2_25", "P2", "P3", "DOWNSTREAM", "WASTEWATER", "2.25", "ton/day"),
                        transfer("p3-out-2_25", "P3", "D", "DOWNSTREAM", "WASTEWATER", "2.25", "ton/day")));

        var result = calculator.calculate(request, Map.of());
        Map<String, Object> source = findNode(result.nodes(), "A");
        Map<String, Object> sourceWastewater = findBalance(source, "WASTEWATER", "ton/day");
        Map<String, Object> receivingProcess = findNode(result.nodes(), "P2");
        Map<String, Object> receivingBalance = findBalance(receivingProcess, "WASTEWATER", "ton/day");
        Map<String, Object> wasteSink = findNode(result.nodes(), "W");
        Map<String, Object> wasteBalance = findBalance(wasteSink, "WASTE", "ton/day");
        Map<String, Object> downstreamProcess = findNode(result.nodes(), "P3");

        assertThat(sourceWastewater).containsEntry("inflow", new BigDecimal("100"))
                .containsEntry("outflow", new BigDecimal("100.0"))
                .containsEntry("residualInMinusOut", new BigDecimal("0.0"));
        assertThat(receivingBalance).containsEntry("inflow", new BigDecimal("2.5"))
                .containsEntry("outflow", new BigDecimal("2.25"))
                .containsEntry("residualInMinusOut", new BigDecimal("0.25"));
        assertThat(wasteBalance).containsEntry("inflow", new BigDecimal("0.5"))
                .containsEntry("outflow", new BigDecimal("0"))
                .containsEntry("residualInMinusOut", new BigDecimal("0.5"));
        assertThat(receivingProcess.get("balanceStatus")).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat((List<Map<String, Object>>) receivingProcess.get("unresolvedResiduals"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("residual", new BigDecimal("0.25"))
                        .containsEntry("status", "RESIDUAL_UNRESOLVED"));
        assertThat(wasteSink.get("balanceStatus")).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat(wasteSink.get("receiverDispositionStatus")).isEqualTo("UNCONFIRMED_STORAGE_OR_OFFSITE_DISPOSITION");
        assertThat((List<String>) downstreamProcess.get("downstreamNodeIds")).contains("D");
        assertThat((List<Map<String, Object>>) downstreamProcess.get("downstreamImpactScope")).anySatisfy(row ->
                assertThat(row).containsEntry("nodeId", "D").containsEntry("processCode", "P-TEST-C"));
        assertThat((List<Map<String, Object>>) result.transfers()).allSatisfy(transfer ->
                assertThat(transfer).containsEntry("reconciliationStatus",
                                "MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED")
                        .containsEntry("countedOncePerEndpoint", true));
        assertThat(((List<Map<String, Object>>) source.get("outflows"))).anySatisfy(outflow ->
                assertThat(outflow).containsEntry("transferId", "waste-0_5").containsEntry("quantity", new BigDecimal("0.5"))
                        .containsEntry("convertedToFlowType", "WASTE"));
    }

    @Test
    void mergeAggregatesMultipleInletsButKeepsExplicitPerTransferEvidence() {
        var request = new MaterialBalanceRequest("demo-merge-001", "Hypothetical merge", "TEST_ONLY",
                List.of(node("A", "A", "P1", "SOURCE", "40", "WASTEWATER", "ton/day"),
                        node("B", "B", "P1", "SOURCE", "60", "WASTEWATER", "ton/day"),
                        node("M", "Merge", "P2", "PROCESS", null, null, null),
                        node("D", "Discharge", "P2", "OUTLET", null, null, null)),
                List.of(transfer("branch-a", "A", "M", "MERGE", "WASTEWATER", "40", "ton/day"),
                        transfer("branch-b", "B", "M", "MERGE", "WASTEWATER", "60", "ton/day"),
                        transfer("merge-out", "M", "D", "DOWNSTREAM", "WASTEWATER", "100", "ton/day")));
        var result = calculator.calculate(request, Map.of());
        Map<String, Object> merge = findNode(result.nodes(), "M");
        assertThat(findBalance(merge, "WASTEWATER", "ton/day"))
                .containsEntry("inflow", new BigDecimal("100"))
                .containsEntry("outflow", new BigDecimal("100"))
                .containsEntry("residualInMinusOut", new BigDecimal("0"));
        assertThat((List<?>) merge.get("inflows")).hasSize(2);
    }

    @Test
    void mixedUnitsAndMissingSourceConditionsAreExplicitlyUnavailable() {
        var mixed = new MaterialBalanceRequest("demo-mixed-001", "No unit conversion", "TEST_ONLY",
                List.of(node("A", "A", "P1", "PROCESS", null, null, null),
                        node("B", "B", "P2", "PROCESS", null, null, null),
                        node("C", "C", "P2", "OUTLET", null, null, null)),
                List.of(transfer("in-m3", "A", "B", "PROCESS", "WASTEWATER", "4", "m3/day"),
                        transfer("out-ton", "B", "C", "DOWNSTREAM", "WASTEWATER", "4", "ton/day")));
        var result = calculator.calculate(mixed, Map.of());
        Map<String, Object> node = findNode(result.nodes(), "B");
        assertThat(node.get("balanceStatus")).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat((List<String>) node.get("calculationUnavailableReasons")).contains("MIXED_UNITS_NO_CONVERSION_APPLIED");
        assertThat((List<Map<String, Object>>) node.get("totalByUnit")).allSatisfy(row ->
                assertThat(row.get("residualInMinusOut")).isNull());

        var noInitial = new MaterialBalanceRequest("demo-missing-001", "Missing source quantity", "TEST_ONLY",
                List.of(node("S", "Source", "P0", "SOURCE", null, null, null),
                        node("D", "Destination", "P1", "OUTLET", null, null, null)),
                List.of(transfer("missing-condition", "S", "D", "DOWNSTREAM", "WASTEWATER", "1", "ton/day")));
        var missingResult = calculator.calculate(noInitial, Map.of());
        Map<String, Object> source = findNode(missingResult.nodes(), "S");
        assertThat(source.get("balanceStatus")).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat((List<String>) source.get("calculationUnavailableReasons"))
                .contains("INITIAL_INFLOW_REQUIRES_NONNEGATIVE_QUANTITY_FLOW_TYPE_UNIT_AND_PERIOD");
    }

    @Test
    void duplicateTransferIdsAreRejectedAndCyclesAreTraversedOnce() {
        var duplicate = new MaterialBalanceRequest("demo-dupe-001", "Duplicate id", "TEST_ONLY",
                List.of(node("A", "A", "P1", "SOURCE", "10", "WASTEWATER", "ton/day"),
                        node("B", "B", "P2", "OUTLET", null, null, null)),
                List.of(transfer("move", "A", "B", "DOWNSTREAM", "WASTEWATER", "1", "ton/day"),
                        transfer("move", "A", "B", "DOWNSTREAM", "WASTEWATER", "1", "ton/day")));
        assertThatThrownBy(() -> calculator.calculate(duplicate, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate transferId");
        var cycle = new MaterialBalanceRequest("demo-cycle-001", "Cycle", "TEST_ONLY",
                List.of(node("A", "A", "P1", "SOURCE", "10", "WASTEWATER", "ton/day"),
                        node("B", "B", "P2", "PROCESS", null, null, null)),
                List.of(transfer("a-b", "A", "B", "DOWNSTREAM", "WASTEWATER", "1", "ton/day"),
                        transfer("b-a", "B", "A", "DOWNSTREAM", "WASTEWATER", "1", "ton/day")));
        var cycleResult = calculator.calculate(cycle, Map.of());
        Map<String, Object> a = findNode(cycleResult.nodes(), "A");
        Map<String, Object> b = findNode(cycleResult.nodes(), "B");
        assertThat((List<String>) a.get("downstreamNodeIds")).containsExactly("B");
        assertThat((List<String>) b.get("downstreamNodeIds")).containsExactly("A");
        assertThat(cycleResult.transfers()).hasSize(2);
    }

    @Test
    void senderReceiverQuantityMismatchIsExcludedRatherThanSilentlyCounted() {
        var mismatch = new MaterialBalanceRequest.Transfer("mismatch", "TEST_ONLY", null, "A", "B",
                "WASTEWATER_TRANSFER", "WASTEWATER", new BigDecimal("2.5"), "ton/day",
                "Synthetic independent meter values", new BigDecimal("2.4"), "2026-10/TEST_PERIOD");
        var request = new MaterialBalanceRequest("demo-mismatch", "Mismatched transfer quantities", "TEST_ONLY",
                List.of(node("A", "Source", "P1", "SOURCE", "3", "WASTEWATER", "ton/day"),
                        node("B", "Process", "P2", "PROCESS", null, null, null)), List.of(mismatch));
        var result = calculator.calculate(request, Map.of());
        assertThat(result.transfers()).singleElement().satisfies(row ->
                assertThat(row).containsEntry("included", false).containsEntry("reason", "SENDER_RECEIVER_QUANTITY_MISMATCH"));
    }

    @Test
    void explicitTerminalBoundaryAndStorageChangeAreSeparateBalanceTerms() {
        var terminal = new MaterialBalanceRequest("demo-terminal", "Explicit final discharge", "TEST_ONLY",
                List.of(node("S", "Source", "P1", "SOURCE", "1", "WASTEWATER", "ton/day"),
                        new MaterialBalanceRequest.Node("D", "Final discharge", "P1", null, "FINAL_DISCHARGE",
                                null, null, null, null, null, null, "TERMINAL_DISCHARGE", "", "")),
                List.of(transfer("to-final", "S", "D", "DOWNSTREAM", "WASTEWATER", "1", "ton/day")));
        Map<String, Object> discharge = findNode(calculator.calculate(terminal, Map.of()).nodes(), "D");
        assertThat(discharge.get("balanceStatus")).isEqualTo("CALCULATED");
        assertThat(discharge.get("receiverDispositionStatus")).isEqualTo("EXPLICIT_TERMINAL_BOUNDARY_APPLIED");
        assertThat(findBalance(discharge, "WASTEWATER", "ton/day"))
                .containsEntry("terminalBoundaryApplied", true)
                .containsEntry("residualInMinusOut", BigDecimal.ZERO);

        var stored = new MaterialBalanceRequest("demo-storage", "Measured storage increase", "TEST_ONLY",
                List.of(node("S", "Source", "P1", "SOURCE", "1", "WASTE", "ton/day"),
                        new MaterialBalanceRequest.Node("W", "Waste storage", "PW-02", null, "WASTE_STORAGE",
                                null, null, null, new BigDecimal("1"), "WASTE", "ton/day", null, "", "2026-10/TEST_PERIOD")),
                List.of(transfer("to-storage", "S", "W", "WASTE_GENERATION", "WASTE", "1", "ton/day")));
        Map<String, Object> wasteStore = findNode(calculator.calculate(stored, Map.of()).nodes(), "W");
        assertThat(wasteStore.get("balanceStatus")).isEqualTo("CALCULATED");
        assertThat(wasteStore.get("storageChanges")).asList().hasSize(1);
        assertThat(findBalance(wasteStore, "WASTE", "ton/day"))
                .containsEntry("storageChange", new BigDecimal("1"))
                .containsEntry("residualInMinusOut", BigDecimal.ZERO);
    }

    private MaterialBalanceRequest.Node node(String id, String label, String process, String role,
                                             String inflow, String flowType, String unit) {
        return new MaterialBalanceRequest.Node(id, label, process, null, role,
                inflow == null ? null : new BigDecimal(inflow), flowType, unit, null, null, null, null,
                inflow == null ? "" : "2026-10/TEST_PERIOD", "");
    }

    private MaterialBalanceRequest.Transfer transfer(String id, String from, String to, String edgeType,
                                                     String flowType, String amount, String unit) {
        return new MaterialBalanceRequest.Transfer(id, "TEST_ONLY", null, from, to, edgeType, flowType,
                new BigDecimal(amount), unit, "Synthetic test fixture only", null, "2026-10/TEST_PERIOD");
    }

    private Map<String, Object> findNode(List<Map<String, Object>> nodes, String id) {
        return nodes.stream().filter(node -> id.equals(node.get("nodeId"))).findFirst().orElseThrow();
    }

    private Map<String, Object> findBalance(Map<String, Object> node, String flow, String unit) {
        return ((List<Map<String, Object>>) node.get("balanceByFlowTypeAndUnit")).stream()
                .filter(balance -> flow.equals(balance.get("flowType")) && unit.equals(balance.get("unit")))
                .findFirst().orElseThrow();
    }
}
