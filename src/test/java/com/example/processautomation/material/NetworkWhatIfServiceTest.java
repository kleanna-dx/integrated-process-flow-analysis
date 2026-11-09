package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.processautomation.material.DirectedProcessNetwork.ConnectionType;
import com.example.processautomation.material.DirectedProcessNetwork.MaterialClass;
import com.example.processautomation.material.DirectedProcessNetwork.NodeType;
import com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class NetworkWhatIfServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NetworkWhatIfService service = new NetworkWhatIfService(jdbc, new MaterialBalanceCalculator());

    @Test
    void calculatesIndependentTestOnlyTwentyToTenAndFiftyScenariosAndKeepsActualUnavailable() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        var report = service.calculate(request("2026-10-01/TEST_PERIOD"));

        assertThat(report.mode()).isEqualTo("TEST_ONLY");
        assertThat(report.officialDiagramModified()).isFalse();
        assertThat(report.officialMasterModified()).isFalse();
        assertThat(report.actualReadiness()).containsEntry("status", "CALCULATION_UNAVAILABLE");
        assertThat(report.baseline().status()).isEqualTo("CALCULATED");
        assertThat(report.scenarios()).hasSize(2);
        assertThat(report.scenarios().get(0).result().status()).isEqualTo("CALCULATED");
        assertThat(report.scenarios().get(1).result().status()).isEqualTo("CALCULATED");
        assertThat(edge(report.scenarios().get(0).result(), "P2-X-P1-N").quantity()).isEqualByComparingTo("10");
        assertThat(edge(report.scenarios().get(1).result(), "P2-X-P1-N").quantity()).isEqualByComparingTo("50");
        assertThat(report.scenarios().get(0).result().impactedEquipmentNumbers()).contains("P2-X", "P1-N");
        assertThat(report.scenarios().get(0).result().external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
        });
    }

    @Test
    void testOnlyCalculationIsInMemoryAndUnresolvedCycleNeverAppearsCalculated() {
        var valid = request("2026-10-01/TEST_PERIOD");
        var synthetic = service.calculateTestOnly(valid);
        assertThat(java.lang.reflect.Modifier.isPublic(java.util.Arrays.stream(NetworkWhatIfService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("calculateTestOnly")).findFirst().orElseThrow().getModifiers()))
                .isFalse();
        assertThat(synthetic.mode()).isEqualTo("TEST_ONLY");
        assertThat(synthetic.actualReadiness()).containsEntry("status", "CALCULATION_UNAVAILABLE")
                .containsEntry("reason", "SYNTHETIC_TEST_ONLY_READINESS_NOT_QUERIED");
        org.mockito.Mockito.verifyNoInteractions(jdbc);

        var nodes = new java.util.ArrayList<>(valid.nodes());
        nodes.add(node("CYCLE-A", "SYN-CYCLE-A", "P2", NodeType.PROCESS_EQUIPMENT));
        nodes.add(node("CYCLE-B", "SYN-CYCLE-B", "P2", NodeType.PROCESS_EQUIPMENT));
        var edges = new java.util.ArrayList<>(valid.edges());
        edges.add(new NetworkWhatIfRequest.Edge("SYN-CYCLE-A-B", "CYCLE-A", "CYCLE-B", "P2", "P2",
                MaterialClass.WASTEWATER, "process wastewater", "ton/day", null,
                ConnectionType.INTERNAL_RECYCLE, QuantityBasis.CALCULATED, false, "synthetic cycle", valid.period()));
        edges.add(new NetworkWhatIfRequest.Edge("SYN-CYCLE-B-A", "CYCLE-B", "CYCLE-A", "P2", "P2",
                MaterialClass.WASTEWATER, "process wastewater", "ton/day", null,
                ConnectionType.INTERNAL_RECYCLE, QuantityBasis.CALCULATED, false, "synthetic cycle", valid.period()));
        var cycle = new NetworkWhatIfRequest(valid.mode(), "synthetic-underdetermined-cycle", "Cycle test",
                valid.processCode(), valid.sourceEquipment(), valid.targetEquipment(), valid.materialClass(),
                valid.materialType(), valid.unit(), valid.period(), valid.changedConnectionId(), valid.baseQuantity(),
                nodes, edges, valid.processTerms(), valid.scenarios());
        var unresolved = service.calculateTestOnly(cycle);
        assertThat(unresolved.baseline().status()).isEqualTo("UNDERDETERMINED");
        assertThat(unresolved.actualReadiness()).containsEntry("status", "CALCULATION_UNAVAILABLE");
        assertThat(NetworkWhatIfService.fullyCalculated(unresolved)).isFalse();

        var actualMode = new NetworkWhatIfRequest("PRODUCTION", valid.simulationId(), valid.scenarioName(),
                valid.processCode(), valid.sourceEquipment(), valid.targetEquipment(), valid.materialClass(),
                valid.materialType(), valid.unit(), valid.period(), valid.changedConnectionId(), valid.baseQuantity(),
                valid.nodes(), valid.edges(), valid.processTerms(), valid.scenarios());
        assertThatThrownBy(() -> service.calculateTestOnly(actualMode)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TEST_ONLY");
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void createsAndReopensProposalComparisonTestOnlyWorkbook() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        var beforeRequest = request("2026-10-01/TEST_PERIOD");
        var afterEdges = beforeRequest.edges().stream().map(edge -> edge.connectionId().equals("P2-X-P1-N")
                ? new NetworkWhatIfRequest.Edge(edge.connectionId(), edge.sourceNodeId(), edge.targetNodeId(),
                        edge.sourceProcess(), edge.targetProcess(), edge.materialClass(), edge.materialType(), edge.unit(),
                        new BigDecimal("10.0"), edge.connectionType(), edge.quantityBasis(), true, edge.evidence(), edge.period())
                : edge).toList();
        var afterRequest = new NetworkWhatIfRequest(beforeRequest.mode(), "test-whatif-after", "After synthetic flow",
                beforeRequest.processCode(), beforeRequest.sourceEquipment(), beforeRequest.targetEquipment(),
                beforeRequest.materialClass(), beforeRequest.materialType(), beforeRequest.unit(), beforeRequest.period(),
                beforeRequest.changedConnectionId(), new BigDecimal("10"), beforeRequest.nodes(), afterEdges,
                beforeRequest.processTerms(), beforeRequest.scenarios());
        var before = service.calculate(beforeRequest);
        var after = service.calculate(afterRequest);
        byte[] bytes = new DirectedProcessNetworkWorkbookExporter().createProposalComparisonWorkbook(
                "proposal-synthetic-test", "FLOW_CHANGE", "P2", "TEST_ONLY", "SYN-EQ-1",
                Map.of("connectionId", "P2-X-P1-N", "quantity", 20),
                Map.of("connectionId", "P2-X-P1-N", "quantity", 10),
                Map.of("physicalConnection", "UNVERIFIED"), "CALCULATED_SYNTHETIC", "REVIEW_REQUIRED",
                "REVIEW_REQUIRED", before, after,
                Map.of("processResidualDeltas", List.of(Map.of("processCode", "P2", "materialType", "process wastewater",
                        "unit", "ton/day", "period", beforeRequest.period(), "beforeResidual", 0,
                        "afterResidual", 0, "delta", 0, "status", "SYNTHETIC_COMPARISON")),
                        "impactedEquipmentBefore", List.of("P2-X", "P1-N"), "impactedEquipmentAfter", List.of("P2-X", "P1-N")));
        assertThat(bytes).isNotEmpty();
        Path artifact = Path.of("target/test-artifacts/what-if-proposal-comparison-TEST_ONLY.xlsx");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, bytes);
        int zipEntries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) { zip.transferTo(java.io.OutputStream.nullOutputStream()); zipEntries++; }
        }
        assertThat(zipEntries).isGreaterThan(5);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("제안 요약")).isNotNull();
            assertThat(workbook.getSheet("연결망 전후")).isNotNull();
            assertThat(workbook.getSheet("변경 연결").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("FLOW_CHANGED");
            assertThat(workbook.getSheet("공정 수지")).isNotNull();
            assertThat(workbook.getSheet("잔차 비교")).isNotNull();
            assertThat(workbook.getSheet("전체 외부 수지")).isNotNull();
            assertThat(workbook.getSheet("외부 수지 비교")).isNotNull();
            String reviewSheetText = workbook.getSheet("영향·검토").iterator().hasNext()
                    ? java.util.stream.StreamSupport.stream(workbook.getSheet("영향·검토").spliterator(), false)
                            .flatMap(row -> java.util.stream.StreamSupport.stream(row.spliterator(), false))
                            .map(cell -> cell.toString()).collect(java.util.stream.Collectors.joining(" ")) : "";
            assertThat(reviewSheetText).contains("TEST_ONLY", "CALCULATION_UNAVAILABLE", "REVIEW_REQUIRED");
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                var font = workbook.getFontAt(cell.getCellStyle().getFontIndex());
                assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            }
        }
        assertThat(Files.size(artifact)).isEqualTo(bytes.length);
    }

    @Test
    void rejectsChangedConnectionWhenMeasurementPeriodDoesNotMatch() {
        var request = request("2026-10-01/TEST_PERIOD");
        var badEdge = new NetworkWhatIfRequest.Edge("P2-X-P1-N", "X", "N", "P2", "P1",
                MaterialClass.WASTEWATER, "process wastewater", "ton/day", new BigDecimal("20"),
                ConnectionType.INTERPROCESS_RECYCLE, QuantityBasis.USER_INPUT, true,
                "synthetic evidence", "2026-11-01/OTHER_PERIOD");
        var edges = request.edges().stream().map(edge -> edge.connectionId().equals("P2-X-P1-N") ? badEdge : edge).toList();
        var invalid = new NetworkWhatIfRequest(request.mode(), request.simulationId(), request.scenarioName(),
                request.processCode(), request.sourceEquipment(), request.targetEquipment(), request.materialClass(),
                request.materialType(), request.unit(), request.period(), request.changedConnectionId(),
                request.baseQuantity(), request.nodes(), edges, request.processTerms(), request.scenarios());

        assertThatThrownBy(() -> service.calculate(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("기간");
    }

    @Test
    void rejectsNegativeFlowsAndMissingPeriodsInsteadOfNormalizingThem() {
        var valid = request("2026-10-01/TEST_PERIOD");
        var negativeEdge = new NetworkWhatIfRequest.Edge("P2-X-P1-N", "X", "N", "P2", "P1",
                MaterialClass.WASTEWATER, "process wastewater", "ton/day", new BigDecimal("-1"),
                ConnectionType.INTERPROCESS_RECYCLE, QuantityBasis.USER_INPUT, true,
                "synthetic evidence", "2026-10-01/TEST_PERIOD");
        var negativeEdges = valid.edges().stream().map(edge -> edge.connectionId().equals("P2-X-P1-N")
                ? negativeEdge : edge).toList();
        var negative = new NetworkWhatIfRequest(valid.mode(), valid.simulationId(), valid.scenarioName(),
                valid.processCode(), valid.sourceEquipment(), valid.targetEquipment(), valid.materialClass(),
                valid.materialType(), valid.unit(), valid.period(), valid.changedConnectionId(), new BigDecimal("-1"),
                valid.nodes(), negativeEdges, valid.processTerms(), valid.scenarios());
        assertThatThrownBy(() -> service.calculate(negative)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("0 이상");

        var missingPeriodEdge = new NetworkWhatIfRequest.Edge("P2-X-P1-N", "X", "N", "P2", "P1",
                MaterialClass.WASTEWATER, "process wastewater", "ton/day", new BigDecimal("20"),
                ConnectionType.INTERPROCESS_RECYCLE, QuantityBasis.USER_INPUT, true,
                "synthetic evidence", "");
        var missingPeriodEdges = valid.edges().stream().map(edge -> edge.connectionId().equals("P2-X-P1-N")
                ? missingPeriodEdge : edge).toList();
        var missingPeriod = new NetworkWhatIfRequest(valid.mode(), valid.simulationId(), valid.scenarioName(),
                valid.processCode(), valid.sourceEquipment(), valid.targetEquipment(), valid.materialClass(),
                valid.materialType(), valid.unit(), valid.period(), valid.changedConnectionId(), valid.baseQuantity(),
                valid.nodes(), missingPeriodEdges, valid.processTerms(), valid.scenarios());
        assertThatThrownBy(() -> service.calculate(missingPeriod)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("측정 기간");
    }

    private NetworkWhatIfRequest request(String period) {
        List<NetworkWhatIfRequest.Node> nodes = List.of(
                node("S-P2", null, "P2", NodeType.EXTERNAL_SOURCE),
                node("S-P1", null, "P1", NodeType.EXTERNAL_SOURCE),
                node("X", "P2-X", "P2", NodeType.PROCESS_EQUIPMENT),
                node("N", "P1-N", "P1", NodeType.PROCESS_EQUIPMENT),
                node("OUT-P2", null, "P2", NodeType.EXTERNAL_SINK),
                node("OUT-P1", null, "P1", NodeType.EXTERNAL_SINK));
        List<NetworkWhatIfRequest.Edge> edges = List.of(
                edge("P2-IN", "S-P2", "X", "P2", "P2", "100", ConnectionType.EXTERNAL_INFLOW, period, true),
                edge("P1-IN", "S-P1", "N", "P1", "P1", "100", ConnectionType.EXTERNAL_INFLOW, period, true),
                edge("P2-X-P1-N", "X", "N", "P2", "P1", "20", ConnectionType.INTERPROCESS_RECYCLE, period, true),
                edge("P2-X-P2-OUT", "X", "OUT-P2", "P2", "", null, ConnectionType.EXTERNAL_OUTFLOW, period, false),
                edge("P1-N-P1-OUT", "N", "OUT-P1", "P1", "", null, ConnectionType.EXTERNAL_OUTFLOW, period, false));
        return new NetworkWhatIfRequest("TEST_ONLY", "test-whatif-1", "Synthetic P1/P2 transfer", "P2",
                "P2-X", "P1-N", MaterialClass.WASTEWATER, "process wastewater", "ton/day", period,
                "P2-X-P1-N", new BigDecimal("20"), nodes, edges, List.of(), List.of(
                new NetworkWhatIfRequest.ChangeCase("FLOW-10", "20 to 10", new BigDecimal("10")),
                new NetworkWhatIfRequest.ChangeCase("FLOW-50", "20 to 50", new BigDecimal("50"))));
    }

    private NetworkWhatIfRequest.Node node(String id, String equipment, String process, NodeType type) {
        return new NetworkWhatIfRequest.Node(id, equipment, process, type == NodeType.PROCESS_EQUIPMENT
                ? "PROCESS_EQUIPMENT" : "EXTERNAL_BOUNDARY", type, id, "TEST_ONLY synthetic", "TEST_ONLY");
    }

    private NetworkWhatIfRequest.Edge edge(String id, String source, String target, String sourceProcess,
                                           String targetProcess, String amount, ConnectionType type,
                                           String period, boolean fixed) {
        return new NetworkWhatIfRequest.Edge(id, source, target, sourceProcess, targetProcess,
                MaterialClass.WASTEWATER, "process wastewater", "ton/day",
                amount == null ? null : new BigDecimal(amount), type,
                fixed ? QuantityBasis.USER_INPUT : QuantityBasis.CALCULATED, fixed,
                "synthetic test fixture", period);
    }

    private DirectedProcessNetworkSolver.EdgeResult edge(DirectedProcessNetworkSolver.Result result, String id) {
        return result.edges().stream().filter(value -> value.connectionId().equals(id)).findFirst().orElseThrow();
    }
}
