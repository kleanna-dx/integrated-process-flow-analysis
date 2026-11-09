package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.material.DirectedProcessNetwork.ConnectionType;
import com.example.processautomation.material.DirectedProcessNetwork.Edge;
import com.example.processautomation.material.DirectedProcessNetwork.MaterialClass;
import com.example.processautomation.material.DirectedProcessNetwork.Node;
import com.example.processautomation.material.DirectedProcessNetwork.NodeType;
import com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class DirectedProcessNetworkSolverTest {
    private final MaterialBalanceCalculator calculator = new MaterialBalanceCalculator();
    private final DirectedProcessNetworkWorkbookExporter exporter = new DirectedProcessNetworkWorkbookExporter();

    @Test
    void solvesHighPrecisionDecimalFlowsWithoutConvertingThroughDouble() {
        String exact = "0.1234567890123456789012345678901234";
        DirectedProcessNetwork network = new DirectedProcessNetwork("decimal-precision", "Synthetic exact decimal", "TEST_ONLY",
                List.of(external("IN", NodeType.EXTERNAL_SOURCE), equipment("P", "P1-01", "P1"),
                        external("OUT", NodeType.EXTERNAL_SINK)),
                List.of(edge("feed", "IN", "P", "P1", "P1", exact, true,
                                ConnectionType.EXTERNAL_INFLOW, "Synthetic exact input"),
                        edge("out", "P", "OUT", "P1", "", null, false,
                                ConnectionType.EXTERNAL_OUTFLOW, "Synthetic solved output")), List.of());
        var result = calculator.calculateDirectedNetwork(network);
        assertThat(result.status()).isEqualTo("CALCULATED");
        assertThat(findEdge(result, "out").quantity()).isEqualByComparingTo(exact);
    }

    @Test
    void inconsistentConservationRowsRemainInconsistentInsteadOfForcingAFlow() {
        List<Node> nodes = List.of(external("IN", NodeType.EXTERNAL_SOURCE), equipment("A", "P1-A", "P1"),
                equipment("B", "P1-B", "P1"), external("OUT", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                edge("feed", "IN", "A", "P1", "P1", "10", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic fixed feed"),
                edge("transfer", "A", "B", "P1", "P1", "10", true, ConnectionType.SERIES, "Synthetic fixed transfer"),
                edge("fixed-out", "B", "OUT", "P1", "", "9", true, ConnectionType.EXTERNAL_OUTFLOW, "Synthetic fixed outlet"),
                edge("unknown-extra-out", "A", "OUT", "P1", "", null, false, ConnectionType.EXTERNAL_OUTFLOW, "Synthetic unconstrained outlet"));
        var result = calculator.calculateDirectedNetwork(new DirectedProcessNetwork(
                "inconsistent-equations", "Synthetic inconsistent conservation rows", "TEST_ONLY", nodes, edges, List.of()));
        assertThat(result.status()).isEqualTo("INCONSISTENT");
        assertThat(result.issues()).anyMatch(issue -> issue.startsWith("INCONSISTENT_CONSERVATION_EQUATIONS:"));
        assertThat(findEdge(result, "unknown-extra-out").quantity()).isNull();
    }

    @Test
    void interprocessTransferRecalculatesBothProcessesAndIndependent20_10_50Cases() throws Exception {
        DirectedProcessNetwork base = transferNetwork("scenario-a", "20");
        var at20 = calculator.recalculateDirectedNetwork(base, "scenario-a-20", "P2-X-P1-N", bd("20"));
        var at10 = calculator.recalculateDirectedNetwork(base, "scenario-b-10", "P2-X-P1-N", bd("10"));
        var at50 = calculator.recalculateDirectedNetwork(base, "scenario-b-50", "P2-X-P1-N", bd("50"));

        assertScenarioTransfer(at20, "20", "80", "120");
        assertScenarioTransfer(at10, "10", "90", "110");
        assertScenarioTransfer(at50, "50", "50", "150");
        assertThat(findEdge(at10, "P2-X-P1-N").previousQuantity()).isEqualByComparingTo("20");
        assertThat(findEdge(at50, "P2-X-P1-N").previousQuantity()).isEqualByComparingTo("20");
        assertThat(findEdge(at20, "P2-X-P1-N").quantity()).isEqualByComparingTo("20");
        assertThat(findProcess(at20, "P1").inflow()).isEqualByComparingTo("120");
        assertThat(findProcess(at20, "P2").outflow()).isEqualByComparingTo("100");
        assertThat(at20.external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
            assertThat(balance.residual()).isEqualByComparingTo("0");
        });
        assertThat(at20.impactedEquipmentNumbers()).contains("P2-X", "P1-N");

        byte[] resultBytes = exporter.createResultWorkbook(at20);
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(resultBytes))) {
            assertThat(workbook.getSheet("공정별 수지")).isNotNull();
            assertThat(workbook.getSheet("전체 외부 수지")).isNotNull();
            assertThat(workbook.getSheet("연결 유량").getRow(1).getCell(7).getNumericCellValue()).isEqualTo(100.0);
        }
    }

    @Test
    void excessiveBranchFlowReportsNegativeDownstreamWithoutCorrection() {
        var result = calculator.recalculateDirectedNetwork(transferNetwork("scenario-c-base", "20"),
                "scenario-c-120", "P2-X-P1-N", bd("120"));
        assertThat(result.status()).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(findEdge(result, "P2-X-P2-OUT").quantity()).isEqualByComparingTo("-20");
        assertThat(findEdge(result, "P2-X-P2-OUT").status()).isEqualTo("NEGATIVE_FLOW_CONSTRAINT_VIOLATION");
        assertThat(findEdge(result, "P2-X-P2-OUT").constraintViolation()).isEqualTo("NONNEGATIVE_FLOW_REQUIRED");
        assertThat(result.external().get(0).externalInflow()).isEqualByComparingTo("200");
        assertThat(result.external().get(0).externalOutflow()).isEqualByComparingTo("200");
    }

    @Test
    void fixedRecirculation20And30BalancesBothProcessesAndExternalBoundary() {
        var at20 = calculator.calculateDirectedNetwork(recycleNetwork("recycle-20", "20"));
        var at30 = calculator.recalculateDirectedNetwork(recycleNetwork("recycle-base", "20"),
                "recycle-30", "P2-X-P1-A", bd("30"));
        assertThat(at20.status()).isEqualTo("CALCULATED");
        assertThat(at30.status()).isEqualTo("CALCULATED");
        assertThat(findEdge(at20, "P1-A-P2-X").quantity()).isEqualByComparingTo("20");
        assertThat(findEdge(at30, "P1-A-P2-X").quantity()).isEqualByComparingTo("30");
        assertThat(findEdge(at30, "P1-A-P1-N").quantity()).isEqualByComparingTo("100");
        assertThat(findProcess(at20, "P1").inflow()).isEqualByComparingTo("120");
        assertThat(findProcess(at30, "P1").inflow()).isEqualByComparingTo("130");
        assertThat(findProcess(at20, "P1").outflow()).isEqualByComparingTo("120");
        assertThat(findProcess(at30, "P1").outflow()).isEqualByComparingTo("130");
        assertThat(findProcess(at30, "P2").residual()).isEqualByComparingTo("0");
        assertThat(at30.external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("100");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("100");
            assertThat(balance.status()).isEqualTo("BALANCED");
        });
    }

    @Test
    void internalRecycleChangesWhileExternalInflowsAndOutflowsRemainFixed() {
        List<Node> nodes = List.of(external("IN", NodeType.EXTERNAL_SOURCE), equipment("A", "P1-A", "P1"),
                equipment("B", "P1-B", "P1"), external("OUT", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                edge("P1-IN", "IN", "A", "P1", "P1", "100", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic external feed"),
                edge("A-B", "A", "B", "P1", "P1", null, false, ConnectionType.INTERNAL_RECYCLE, "Synthetic internal recycle leg"),
                edge("B-A", "B", "A", "P1", "P1", "20", true, ConnectionType.INTERNAL_RECYCLE, "Synthetic internal recycle setpoint"),
                edge("P1-OUT", "A", "OUT", "P1", "", null, false, ConnectionType.EXTERNAL_OUTFLOW, "Solved external outlet"));
        var base = new DirectedProcessNetwork("internal-recycle-base", "Synthetic internal recycle", "TEST_ONLY", nodes, edges, List.of());
        var at20 = calculator.calculateDirectedNetwork(base);
        var at30 = calculator.recalculateDirectedNetwork(base, "internal-recycle-30", "B-A", bd("30"));

        assertThat(at20.status()).isEqualTo("CALCULATED");
        assertThat(at30.status()).isEqualTo("CALCULATED");
        assertThat(findEdge(at20, "A-B").quantity()).isEqualByComparingTo("20");
        assertThat(findEdge(at30, "A-B").quantity()).isEqualByComparingTo("30");
        assertThat(findEdge(at20, "P1-OUT").quantity()).isEqualByComparingTo("100");
        assertThat(findEdge(at30, "P1-OUT").quantity()).isEqualByComparingTo("100");
        assertThat(at30.nodes()).allSatisfy(balance -> assertThat(balance.residual()).isEqualByComparingTo("0"));
        assertThat(at30.external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("100");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("100");
            assertThat(balance.residual()).isEqualByComparingTo("0");
        });
    }

    @Test
    void unresolvedEndpointsAndEvidenceAreNotGuessedAndCyclesRequireEnoughConditions() {
        DirectedProcessNetwork missing = new DirectedProcessNetwork("missing-evidence", "Missing evidence", "TEST_ONLY",
                List.of(equipment("X", "P2-X", "P2")),
                List.of(edge("unknown-destination", "X", "NO-SUCH-EQUIPMENT", "P2", "P1", "1", true,
                        ConnectionType.INTERPROCESS_RECYCLE, "")), List.of());
        var unavailable = calculator.calculateDirectedNetwork(missing);
        assertThat(unavailable.status()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(unavailable.issues()).contains("UNRESOLVED_ENDPOINT:unknown-destination",
                "CONNECTION_EVIDENCE_REQUIRED:unknown-destination");
        assertThat(unavailable.edges()).isEmpty();

        DirectedProcessNetwork cycle = new DirectedProcessNetwork("unknown-cycle", "Underdetermined cycle", "TEST_ONLY",
                List.of(equipment("A", "P1-A", "P1"), equipment("B", "P1-B", "P1")),
                List.of(edge("A-B", "A", "B", "P1", "P1", null, false, ConnectionType.INTERNAL_RECYCLE, "Synthetic loop"),
                        edge("B-A", "B", "A", "P1", "P1", null, false, ConnectionType.INTERNAL_RECYCLE, "Synthetic loop")),
                List.of());
        var unresolved = calculator.calculateDirectedNetwork(cycle);
        assertThat(unresolved.status()).isEqualTo("UNDERDETERMINED");
        assertThat(unresolved.edges()).allSatisfy(edge -> assertThat(edge.quantity()).isNull());
        assertThat(unresolved.issues()).anyMatch(issue -> issue.startsWith("ADDITIONAL_FLOW_CONDITION_REQUIRED"));
    }

    @Test
    void separatesWastewaterChemicalsAndWasteByMaterialAndUnit() {
        Node process = equipment("P", "P1-01", "P1");
        List<Node> nodes = List.of(process, external("WW-IN", NodeType.EXTERNAL_SOURCE),
                external("WW-OUT", NodeType.EXTERNAL_SINK), external("CHEM-IN", NodeType.EXTERNAL_SOURCE),
                external("CHEM-OUT", NodeType.EXTERNAL_SINK), external("WASTE-IN", NodeType.EXTERNAL_SOURCE),
                external("WASTE-OUT", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                materialEdge("ww-in", "WW-IN", "P", "10", MaterialClass.WASTEWATER, "process wastewater", "ton/day", ConnectionType.EXTERNAL_INFLOW),
                materialEdge("ww-out", "P", "WW-OUT", "10", MaterialClass.WASTEWATER, "process wastewater", "ton/day", ConnectionType.EXTERNAL_OUTFLOW),
                materialEdge("chem-in", "CHEM-IN", "P", "2", MaterialClass.CHEMICAL, "sodium hydroxide", "kg/day", ConnectionType.EXTERNAL_INFLOW),
                materialEdge("chem-out", "P", "CHEM-OUT", "2", MaterialClass.CHEMICAL, "sodium hydroxide", "kg/day", ConnectionType.EXTERNAL_OUTFLOW),
                materialEdge("waste-in", "WASTE-IN", "P", "0.5", MaterialClass.WASTE, "sludge", "ton/day", ConnectionType.EXTERNAL_INFLOW),
                materialEdge("waste-out", "P", "WASTE-OUT", "0.5", MaterialClass.WASTE, "sludge", "ton/day", ConnectionType.EXTERNAL_OUTFLOW));
        var result = calculator.calculateDirectedNetwork(new DirectedProcessNetwork(
                "material-separation", "Separate materials and units", "TEST_ONLY", nodes, edges, List.of()));
        assertThat(result.status()).isEqualTo("CALCULATED");
        assertThat(result.nodes()).hasSize(3);
        assertThat(result.external()).hasSize(3);
        assertThat(result.external()).extracting(DirectedProcessNetworkSolver.ExternalBalance::unit)
                .containsExactlyInAnyOrder("ton/day", "kg/day", "ton/day");
        assertThat(result.nodes()).allSatisfy(balance -> assertThat(balance.residual()).isEqualByComparingTo("0"));
    }

    @Test
    void separatesStreamsByMeasurementPeriodAndRejectsMissingPeriod() {
        Node process = equipment("P", "P1-01", "P1");
        List<Node> nodes = List.of(external("IN-OCT", NodeType.EXTERNAL_SOURCE), process,
                external("OUT-OCT", NodeType.EXTERNAL_SINK), external("IN-NOV", NodeType.EXTERNAL_SOURCE),
                external("OUT-NOV", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                periodEdge("oct-in", "IN-OCT", "P", "10", ConnectionType.EXTERNAL_INFLOW, "2026-10"),
                periodEdge("oct-out", "P", "OUT-OCT", "10", ConnectionType.EXTERNAL_OUTFLOW, "2026-10"),
                periodEdge("nov-in", "IN-NOV", "P", "25", ConnectionType.EXTERNAL_INFLOW, "2026-11"),
                periodEdge("nov-out", "P", "OUT-NOV", "25", ConnectionType.EXTERNAL_OUTFLOW, "2026-11"));
        var result = calculator.calculateDirectedNetwork(new DirectedProcessNetwork(
                "period-separation", "Separate measurement periods", "TEST_ONLY", nodes, edges, List.of()));
        assertThat(result.status()).isEqualTo("CALCULATED");
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes()).extracting(DirectedProcessNetworkSolver.NodeBalance::period)
                .containsExactlyInAnyOrder("2026-10", "2026-11");
        assertThat(result.external()).hasSize(2);
        assertThat(result.external()).allSatisfy(balance ->
                assertThat(balance.externalInflow()).isEqualByComparingTo(balance.externalOutflow()));

        Edge missingPeriod = new Edge("missing-period", "IN-OCT", "P", "P1", "P1", MaterialClass.WASTEWATER,
                "process wastewater", "ton/day", bd("10"), ConnectionType.EXTERNAL_INFLOW,
                QuantityBasis.MEASURED, true, true, "Synthetic evidence", "TEST_ONLY");
        var unavailable = calculator.calculateDirectedNetwork(new DirectedProcessNetwork(
                "missing-period", "Missing period", "TEST_ONLY", nodes, List.of(missingPeriod), List.of()));
        assertThat(unavailable.status()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(unavailable.issues()).contains("MEASUREMENT_PERIOD_REQUIRED:missing-period");
    }

    @Test
    void appliesOnlyExplicitGenerationAndConsumptionTermsAndReportsResiduals() {
        List<Node> nodes = List.of(external("IN", NodeType.EXTERNAL_SOURCE), equipment("P", "P1-01", "P1"),
                external("OUT", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                edge("feed", "IN", "P", "P1", "P1", "20", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic measured feed"),
                edge("product", "P", "OUT", "P1", "", null, false, ConnectionType.EXTERNAL_OUTFLOW, "Output solved from explicit generation"));
        var generated = calculator.calculateDirectedNetwork(new DirectedProcessNetwork("explicit-generation", "Explicit generation", "TEST_ONLY",
                nodes, edges, List.of(new DirectedProcessNetwork.ProcessTerm("P", "P1", MaterialClass.WASTEWATER,
                        "process wastewater", "ton/day", bd("10"), QuantityBasis.USER_INPUT, "Synthetic generation test", "TEST_ONLY", "2026-10-01/TEST_PERIOD"))));
        assertThat(findEdge(generated, "product").quantity()).isEqualByComparingTo("30");
        assertThat(generated.processes()).singleElement().satisfies(balance -> {
            assertThat(balance.generation()).isEqualByComparingTo("10");
            assertThat(balance.residual()).isEqualByComparingTo("0");
        });

        var imbalanced = calculator.calculateDirectedNetwork(new DirectedProcessNetwork("fixed-imbalance", "Fixed imbalance", "TEST_ONLY",
                nodes, List.of(edge("feed-100", "IN", "P", "P1", "P1", "100", true,
                        ConnectionType.EXTERNAL_INFLOW, "Synthetic fixed in"),
                        edge("out-90", "P", "OUT", "P1", "", "90", true,
                                ConnectionType.EXTERNAL_OUTFLOW, "Synthetic fixed out")), List.of()));
        assertThat(imbalanced.status()).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat(imbalanced.processes()).singleElement().satisfies(balance -> {
            assertThat(balance.residual()).isEqualByComparingTo("10");
            assertThat(balance.status()).isEqualTo("RESIDUAL_UNRESOLVED");
        });
    }

    @Test
    void createsScenarioMatrixWithSuccessfulAndReviewOnlyCases() throws Exception {
        var base = transferNetwork("matrix-base", "20");
        var at20 = calculator.recalculateDirectedNetwork(base, "A-20", "P2-X-P1-N", bd("20"));
        var at10 = calculator.recalculateDirectedNetwork(base, "B-10", "P2-X-P1-N", bd("10"));
        var at50 = calculator.recalculateDirectedNetwork(base, "B-50", "P2-X-P1-N", bd("50"));
        var excessive = calculator.recalculateDirectedNetwork(base, "C-120", "P2-X-P1-N", bd("120"));
        var recycle20 = calculator.calculateDirectedNetwork(recycleNetwork("D-20", "20"));
        var recycle30 = calculator.recalculateDirectedNetwork(recycleNetwork("D-base", "20"), "D-30", "P2-X-P1-A", bd("30"));
        var missing = calculator.calculateDirectedNetwork(new DirectedProcessNetwork("E-missing", "Unresolved link", "TEST_ONLY",
                List.of(equipment("X", "P2-X", "P2")),
                List.of(edge("unknown-destination", "X", "NO-SUCH-EQUIPMENT", "P2", "P1", "1", true,
                        ConnectionType.INTERPROCESS_RECYCLE, "")), List.of()));
        var cycle = calculator.calculateDirectedNetwork(new DirectedProcessNetwork("D-underdetermined", "Unknown cycle", "TEST_ONLY",
                List.of(equipment("A", "P1-A", "P1"), equipment("B", "P1-B", "P1")),
                List.of(edge("A-B", "A", "B", "P1", "P1", null, false, ConnectionType.INTERNAL_RECYCLE, "Synthetic loop"),
                        edge("B-A", "B", "A", "P1", "P1", null, false, ConnectionType.INTERNAL_RECYCLE, "Synthetic loop")), List.of()));
        byte[] bytes = exporter.createScenarioMatrixWorkbook(List.of(at20, at10, at50, excessive, recycle20, recycle30, missing, cycle));
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            var matrix = workbook.getSheet("시나리오 비교");
            assertThat(matrix.getLastRowNum()).isEqualTo(8);
            assertThat(matrix.getRow(1).getCell(2).getStringCellValue()).isEqualTo("CALCULATED");
            assertThat(matrix.getRow(4).getCell(2).getStringCellValue()).isEqualTo("CONSTRAINT_VIOLATION");
            assertThat(matrix.getRow(5).getCell(2).getStringCellValue()).isEqualTo("CALCULATED");
            assertThat(matrix.getRow(6).getCell(2).getStringCellValue()).isEqualTo("CALCULATED");
            assertThat(matrix.getRow(7).getCell(2).getStringCellValue()).isEqualTo("CALCULATION_UNAVAILABLE");
            assertThat(matrix.getRow(8).getCell(2).getStringCellValue()).isEqualTo("UNDERDETERMINED");
            assertThat(workbook.getSheet("연결 유량").getPhysicalNumberOfRows()).isGreaterThan(1);
        }
    }

    @Test
    void createsDetachedInputTemplateWithThreeRequestedDataSheets() throws Exception {
        byte[] template = exporter.createInputTemplate();
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(template))) {
            assertThat(workbook.getSheet("기준 연결망")).isNotNull();
            assertThat(workbook.getSheet("배관·유량변경")).isNotNull();
            assertThat(workbook.getSheet("시뮬레이션 설정")).isNotNull();
            assertThat(workbook.getSheet("작성 안내")).isNotNull();
            assertThat(workbook.getSheet("기준 연결망").getRow(0).getLastCellNum()).isEqualTo((short) 19);
            assertThat(workbook.getSheet("배관·유량변경").getRow(0).getLastCellNum()).isEqualTo((short) 17);
            assertThat(workbook.getSheet("시뮬레이션 설정").getRow(0).getLastCellNum()).isEqualTo((short) 11);
        }
    }

    private void assertScenarioTransfer(DirectedProcessNetworkSolver.Result result,
                                       String moved, String p2Outlet, String p1Outlet) {
        assertThat(result.status()).isEqualTo("CALCULATED");
        assertThat(findEdge(result, "P2-X-P1-N").quantity()).isEqualByComparingTo(moved);
        assertThat(findEdge(result, "P2-X-P2-OUT").quantity()).isEqualByComparingTo(p2Outlet);
        assertThat(findEdge(result, "P1-N-P1-OUT").quantity()).isEqualByComparingTo(p1Outlet);
        assertThat(result.processes()).allSatisfy(process -> assertThat(process.residual()).isEqualByComparingTo("0"));
        assertThat(result.external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
        });
    }

    private DirectedProcessNetwork transferNetwork(String id, String moved) {
        List<Node> nodes = List.of(external("S-P2", NodeType.EXTERNAL_SOURCE), external("S-P1", NodeType.EXTERNAL_SOURCE),
                equipment("X", "P2-X", "P2"), equipment("N", "P1-N", "P1"),
                external("OUT-P2", NodeType.EXTERNAL_SINK), external("OUT-P1", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                edge("P2-IN", "S-P2", "X", "P2", "P2", "100", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic external feed P2"),
                edge("P1-IN", "S-P1", "N", "P1", "P1", "100", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic external feed P1"),
                edge("P2-X-P1-N", "X", "N", "P2", "P1", moved, true, ConnectionType.INTERPROCESS_RECYCLE, "Synthetic interprocess movement"),
                edge("P2-X-P2-OUT", "X", "OUT-P2", "P2", "", null, false, ConnectionType.EXTERNAL_OUTFLOW, "Remainder allocated to P2 downstream outlet"),
                edge("P1-N-P1-OUT", "N", "OUT-P1", "P1", "", null, false, ConnectionType.EXTERNAL_OUTFLOW, "P1 downstream outflow solved by conservation"));
        return new DirectedProcessNetwork(id, "Synthetic P1/P2 transfer", "TEST_ONLY", nodes, edges, List.of());
    }

    private DirectedProcessNetwork recycleNetwork(String id, String recycle) {
        List<Node> nodes = List.of(external("S-P1", NodeType.EXTERNAL_SOURCE), equipment("A", "P1-A", "P1"),
                equipment("N", "P1-N", "P1"), equipment("X", "P2-X", "P2"), external("OUT-P1", NodeType.EXTERNAL_SINK));
        List<Edge> edges = List.of(
                edge("P1-IN", "S-P1", "A", "P1", "P1", "100", true, ConnectionType.EXTERNAL_INFLOW, "Synthetic external feed P1"),
                edge("P1-A-P2-X", "A", "X", "P1", "P2", null, false, ConnectionType.INTERPROCESS_RECYCLE, "Return loop solved from balance"),
                edge("P2-X-P1-A", "X", "A", "P2", "P1", recycle, true, ConnectionType.INTERPROCESS_RECYCLE, "Synthetic recirculation setpoint"),
                edge("P1-A-P1-N", "A", "N", "P1", "P1", null, false, ConnectionType.SERIES, "Series flow solved from conservation"),
                edge("P1-N-OUT", "N", "OUT-P1", "P1", "", "100", true, ConnectionType.EXTERNAL_OUTFLOW, "Fixed synthetic system outlet"));
        return new DirectedProcessNetwork(id, "Synthetic interprocess recycle", "TEST_ONLY", nodes, edges, List.of());
    }

    private Node equipment(String id, String number, String process) {
        return new Node(id, number, process, "PROCESS_EQUIPMENT", NodeType.PROCESS_EQUIPMENT,
                number, "synthetic.xlsx", "P1", "TEST_ONLY");
    }

    private Node external(String id, NodeType type) {
        return new Node(id, null, null, "EXTERNAL_BOUNDARY", type, id, "TEST_ONLY synthetic", "TEST_ONLY", "TEST_ONLY");
    }

    private Edge edge(String id, String source, String target, String sourceProcess, String targetProcess,
                      String amount, boolean fixed, ConnectionType type, String evidence) {
        return materialEdge(id, source, target, amount, MaterialClass.WASTEWATER, "process wastewater", "ton/day", type,
                sourceProcess, targetProcess, fixed, evidence);
    }

    private Edge materialEdge(String id, String source, String target, String amount, MaterialClass materialClass,
                              String materialType, String unit, ConnectionType type) {
        return materialEdge(id, source, target, amount, materialClass, materialType, unit, type,
                "P1", "P1", true, "Synthetic fixed material flow");
    }

    private Edge materialEdge(String id, String source, String target, String amount, MaterialClass materialClass,
                              String materialType, String unit, ConnectionType type,
                              String sourceProcess, String targetProcess, boolean fixed, String evidence) {
        return new Edge(id, source, target, sourceProcess, targetProcess, materialClass,
                materialType, unit, amount == null ? null : bd(amount), type,
                fixed ? QuantityBasis.USER_INPUT : QuantityBasis.CALCULATED, fixed, fixed, evidence, "TEST_ONLY",
                "2026-10-01/TEST_PERIOD");
    }

    private Edge periodEdge(String id, String source, String target, String amount, ConnectionType type, String period) {
        return new Edge(id, source, target, "P1", "P1", MaterialClass.WASTEWATER, "process wastewater", "ton/day",
                bd(amount), type, QuantityBasis.MEASURED, true, true, "Synthetic period-specific evidence", "TEST_ONLY", period);
    }

    private DirectedProcessNetworkSolver.EdgeResult findEdge(DirectedProcessNetworkSolver.Result result, String id) {
        return result.edges().stream().filter(edge -> id.equals(edge.connectionId())).findFirst().orElseThrow();
    }

    private DirectedProcessNetworkSolver.ProcessBalance findProcess(DirectedProcessNetworkSolver.Result result, String process) {
        return result.processes().stream().filter(balance -> process.equals(balance.processCode())).findFirst().orElseThrow();
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
