package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MaterialBalancePersistenceIntegrationTest {
    @Autowired private MaterialBalanceService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void testOnlyDemoPersistsTheSpecifiedVirtualMassBalanceAndDownstreamEffect() {
        String suffix = UUID.randomUUID().toString();
        MaterialBalanceRequest request = new MaterialBalanceRequest("demo-" + suffix,
                "Hypothetical 100 t/day water-waste-transfer scenario", "TEST_ONLY",
                List.of(
                        new MaterialBalanceRequest.Node("S", "Virtual source", "P-TEST-A", null, "SOURCE",
                                new BigDecimal("100"), "WASTEWATER", "ton/day", null, null, null, null,
                                "2026-10/TEST_PERIOD", ""),
                        new MaterialBalanceRequest.Node("W", "Virtual waste sink", "P-TEST-A", null, "WASTE_SINK", null, null, null),
                        new MaterialBalanceRequest.Node("P2", "Virtual receiving process", "P-TEST-B", null, "PROCESS", null, null, null),
                        new MaterialBalanceRequest.Node("P3", "Virtual downstream process", "P-TEST-C", null, "PROCESS", null, null, null),
                        new MaterialBalanceRequest.Node("D", "Virtual outlet", "P-TEST-C", null, "OUTLET", null, null, null)),
                List.of(
                        demoTransfer("waste-0_5-" + suffix, "S", "W", "WASTE_GENERATION", "WASTE", "0.5"),
                        demoTransfer("interprocess-2_5-" + suffix, "S", "P2", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5"),
                        demoTransfer("outflow-97-" + suffix, "S", "D", "DOWNSTREAM", "WASTEWATER", "97"),
                        demoTransfer("changed-downstream-2_25-" + suffix, "P2", "P3", "DOWNSTREAM", "WASTEWATER", "2.25"),
                        demoTransfer("p3-out-2_25-" + suffix, "P3", "D", "DOWNSTREAM", "WASTEWATER", "2.25")));

        Map<String, Object> result = service.calculate(request);
        Map<String, Object> source = findNode(result, "S");
        Map<String, Object> receiver = findNode(result, "P2");
        Map<String, Object> wasteSink = findNode(result, "W");
        assertThat(result).containsEntry("mode", "TEST_ONLY").containsEntry("officialBaselineModified", false)
                .containsEntry("productionMaterialBalanceUpdated", false).containsEntry("calculationStatus", "PARTIAL_OR_UNAVAILABLE");
        assertBalance(source, "WASTEWATER", "100", "100.0", "0.0");
        assertBalance(receiver, "WASTEWATER", "2.5", "2.25", "0.25");
        assertBalance(wasteSink, "WASTE", "0.5", "0", "0.5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_transfer WHERE simulation_db_id = "
                + "(SELECT id FROM material_balance_simulation WHERE simulation_id = ?)", Integer.class,
                request.simulationId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_transfer WHERE simulation_db_id = "
                + "(SELECT id FROM material_balance_simulation WHERE simulation_id = ?) AND measurement_period = ?",
                Integer.class, request.simulationId(), "2026-10/TEST_PERIOD")).isEqualTo(5);
    }

    @Test
    void testOnlySimulationIsPersistedIdempotentlyWithoutChangingBaselineOrEquipment() {
        String suffix = UUID.randomUUID().toString();
        MaterialBalanceRequest request = request("sim-" + suffix, "transfer-" + suffix, "TEST_ONLY", null);
        int baselineBefore = jdbc.queryForObject("SELECT COUNT(*) FROM baseline_document", Integer.class);
        int equipmentBefore = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class);
        Map<String, Object> first = service.calculate(request);
        String storedBeforeReplay = jdbc.queryForObject(
                "SELECT result_json FROM material_balance_simulation WHERE simulation_id = ?",
                String.class, request.simulationId());
        Map<String, Object> replay = service.calculate(request);
        String storedAfterReplay = jdbc.queryForObject(
                "SELECT result_json FROM material_balance_simulation WHERE simulation_id = ?",
                String.class, request.simulationId());

        assertThat(first).containsEntry("mode", "TEST_ONLY").containsEntry("officialBaselineModified", false)
                .containsEntry("productionMaterialBalanceUpdated", false);
        assertThat(replay).containsEntry("simulationId", request.simulationId()).containsEntry("mode", "TEST_ONLY");
        assertThat(storedAfterReplay).isEqualTo(storedBeforeReplay);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_simulation WHERE simulation_id = ?",
                Integer.class, request.simulationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_transfer WHERE transfer_id = ?",
                Integer.class, request.transfers().get(0).transferId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM baseline_document", Integer.class)).isEqualTo(baselineBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class)).isEqualTo(equipmentBefore);
    }

    @Test
    void transferIdCanBeReusedAcrossIndependentSimulationsButNotWithinOneSimulation() {
        String suffix = UUID.randomUUID().toString();
        String transferId = "reusable-route-" + suffix;
        MaterialBalanceRequest first = request("sim-a-" + suffix, transferId, "TEST_ONLY", null);
        MaterialBalanceRequest independent = request("sim-b-" + suffix, transferId, "TEST_ONLY", null);
        service.calculate(first);
        service.calculate(independent);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_transfer WHERE transfer_id = ?",
                Integer.class, transferId)).isEqualTo(2);

        MaterialBalanceRequest duplicateInside = new MaterialBalanceRequest("sim-c-" + suffix, "Duplicate edges",
                "TEST_ONLY", first.nodes(), List.of(first.transfers().get(0), first.transfers().get(0)));
        assertThatThrownBy(() -> service.calculate(duplicateInside)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transferId in simulation");
    }

    @Test
    void independentlyReportedReceiverQuantityAndMismatchArePersistedAsExcludedEvidence() {
        String suffix = UUID.randomUUID().toString();
        var source = new MaterialBalanceRequest.Node("source", "Hypothetical source", "P-TEST-1", null,
                "SOURCE", new BigDecimal("3"), "WASTEWATER", "ton/day");
        var receiver = new MaterialBalanceRequest.Node("receiver", "Hypothetical receiver", "P-TEST-2", null,
                "PROCESS", null, null, null);
        var transfer = new MaterialBalanceRequest.Transfer("mismatch-" + suffix, "TEST_ONLY", null,
                "source", "receiver", "WASTEWATER_TRANSFER", "WASTEWATER", new BigDecimal("2.5"),
                "ton/day", "Synthetic independent readings", new BigDecimal("2.4"), "2026-10/TEST_PERIOD");
        var request = new MaterialBalanceRequest("sim-receiver-mismatch-" + suffix,
                "Independent receiver meter mismatch", "TEST_ONLY", List.of(source, receiver), List.of(transfer));

        Map<String, Object> result = service.calculate(request);
        assertThat(((List<Map<String, Object>>) result.get("transfers")).get(0))
                .containsEntry("included", false).containsEntry("reason", "SENDER_RECEIVER_QUANTITY_MISMATCH");
        assertThat(jdbc.queryForMap("SELECT quantity, receiver_quantity, included FROM material_balance_transfer WHERE transfer_id = ?",
                transfer.transferId())).containsEntry("quantity", new BigDecimal("2.500000"))
                .containsEntry("receiver_quantity", new BigDecimal("2.400000")).containsEntry("included", false);
    }

    @Test
    void requestCannotPromoteReviewRequiredRelationByClaimingConfirmed() {
        Long relationId = jdbc.queryForObject("""
                SELECT id FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'
                ORDER BY id LIMIT 1
                """, Long.class);
        String suffix = UUID.randomUUID().toString();
        MaterialBalanceRequest request = request("sim-confirm-check-" + suffix, "transfer-confirm-check-" + suffix,
                "CONFIRMED", relationId);

        Map<String, Object> result = service.calculate(request);
        List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");
        assertThat(transfers).singleElement().satisfies(transfer -> {
            assertThat(transfer).containsEntry("included", false)
                    .containsEntry("reason", "DATABASE_RELATION_NOT_APPROVED");
        });
        assertThat(jdbc.queryForObject("SELECT relation_status FROM process_relation WHERE id = ?",
                String.class, relationId)).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void nonexistentConfirmedRelationIsRecordedAsExcludedWithoutBreakingTestSimulation() {
        String suffix = UUID.randomUUID().toString();
        long nonexistentId = Long.MAX_VALUE;
        MaterialBalanceRequest request = request("sim-missing-relation-" + suffix,
                "transfer-missing-relation-" + suffix, "CONFIRMED", nonexistentId);

        Map<String, Object> result = service.calculate(request);
        Map<?, ?> transferResult = (Map<?, ?>) ((List<?>) result.get("transfers")).get(0);
        assertThat(transferResult.get("included")).isEqualTo(false);
        assertThat(transferResult.get("reason")).isEqualTo("CONFIRMED_RELATION_NOT_FOUND_IN_DATABASE");
        assertThat(jdbc.queryForObject("""
                SELECT relation_id FROM material_balance_transfer WHERE transfer_id = ?
                """, Long.class, request.transfers().get(0).transferId())).isNull();
    }

    @Test
    void simulationIdCannotBeReusedForDifferentPayload() {
        String suffix = UUID.randomUUID().toString();
        String simulationId = "sim-payload-" + suffix;
        service.calculate(request(simulationId, "transfer-payload-a-" + suffix, "TEST_ONLY", null));
        MaterialBalanceRequest different = request(simulationId, "transfer-payload-b-" + suffix, "TEST_ONLY", null);
        assertThatThrownBy(() -> service.calculate(different)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("simulationId already exists with a different request payload");
    }

    @Test
    void missingPeriodIsUnavailableAndPersistedAsNullWithoutInference() {
        String suffix = UUID.randomUUID().toString();
        var legacyTransfer = new MaterialBalanceRequest.Transfer("legacy-period-" + suffix, "TEST_ONLY", null,
                "source", "outlet", "DOWNSTREAM", "WASTEWATER", new BigDecimal("10"), "ton/day",
                "Legacy-compatible payload without a period");
        var source = new MaterialBalanceRequest.Node("source", "Synthetic source", "P-TEST-1", null,
                "SOURCE", new BigDecimal("10"), "WASTEWATER", "ton/day");
        var outlet = new MaterialBalanceRequest.Node("outlet", "Synthetic outlet", "P-TEST-2", null,
                "OUTLET", null, null, null);
        var request = new MaterialBalanceRequest("sim-period-" + suffix, "Missing measurement period",
                "TEST_ONLY", List.of(source, outlet), List.of(legacyTransfer));

        Map<String, Object> result = service.calculate(request);
        assertThat(((List<Map<String, Object>>) result.get("transfers")).get(0))
                .containsEntry("included", false).containsEntry("reason", "MEASUREMENT_PERIOD_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT measurement_period FROM material_balance_transfer WHERE transfer_id = ?",
                String.class, legacyTransfer.transferId())).isNull();
    }

    private Map<String, Object> findNode(Map<String, Object> result, String id) {
        return ((List<Map<String, Object>>) result.get("nodes")).stream()
                .filter(node -> id.equals(node.get("nodeId"))).findFirst().orElseThrow();
    }

    private void assertBalance(Map<String, Object> node, String flowType, String inflow, String outflow, String residual) {
        Map<String, Object> balance = ((List<Map<String, Object>>) node.get("balanceByFlowTypeAndUnit")).stream()
                .filter(row -> flowType.equals(row.get("flowType")) && "ton/day".equals(row.get("unit")))
                .findFirst().orElseThrow();
        assertThat(balance).containsEntry("inflow", new BigDecimal(inflow))
                .containsEntry("outflow", new BigDecimal(outflow))
                .containsEntry("residualInMinusOut", new BigDecimal(residual));
    }

    private MaterialBalanceRequest.Transfer demoTransfer(String id, String source, String target, String edgeType,
                                                         String flowType, String quantity) {
        return new MaterialBalanceRequest.Transfer(id, "TEST_ONLY", null, source, target, edgeType, flowType,
                new BigDecimal(quantity), "ton/day", "Hypothetical test quantity only; no official data",
                null, "2026-10/TEST_PERIOD");
    }

    private MaterialBalanceRequest request(String simulationId, String transferId, String relationMode, Long relationId) {
        var source = new MaterialBalanceRequest.Node("source", "Hypothetical source", "P-TEST-1", null,
                "SOURCE", new BigDecimal("10"), "WASTEWATER", "ton/day", null, null, null, null,
                "2026-10/TEST_PERIOD", "");
        var outlet = new MaterialBalanceRequest.Node("outlet", "Hypothetical outlet", "P-TEST-2", null,
                "OUTLET", null, null, null);
        return new MaterialBalanceRequest(simulationId, "Synthetic persistence test", "TEST_ONLY",
                List.of(source, outlet), List.of(new MaterialBalanceRequest.Transfer(transferId, relationMode,
                relationId, "source", "outlet", "DOWNSTREAM", "WASTEWATER", new BigDecimal("10"),
                "ton/day", "Synthetic only; no approved route inferred", null, "2026-10/TEST_PERIOD")));
    }
}
