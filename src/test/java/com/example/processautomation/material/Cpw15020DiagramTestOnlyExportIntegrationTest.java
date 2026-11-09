package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.example.processautomation.excel.OoxmlPartEditor;
import com.example.processautomation.excel.Pw0105EquipmentInsertionEngine;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Cpw15020DiagramTestOnlyExportIntegrationTest {
    private static final Path SOURCE = Path.of("validation-results/2026-10-16/pw01-05-test-only/PW-01-05-C-PW15020-TEST_ONLY-V5.xlsx");
    private static final Path STEP3 = Path.of("validation-results/2026-10-19-step3/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V1.xlsx");
    private static final Path STEP4 = Path.of("validation-results/2026-10-19-step4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V2.xlsx");
    private static final Path OUTPUT = Path.of("validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.xlsx");

    @Autowired private MaterialBalanceExcelWorkflowService workflow;
    @Autowired private Pw0105EquipmentInsertionEngine layoutEngine;
    @Autowired private OoxmlPartEditor partEditor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    @Test
    void exportsAutoMappedCpw15020TestOnlyFlowSummaryWithoutChangingDiagramParts() throws Exception {
        byte[] source = Files.readAllBytes(SOURCE);
        String sourceHash = sha256(source);
        String step3Hash = sha256(Files.readAllBytes(STEP3));
        String step4Hash = sha256(Files.readAllBytes(STEP4));
        int confirmedBefore = jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class);
        int reviewBefore = jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class);
        var request = scenario("cpw15020-diagram-test-" + UUID.randomUUID());

        var export = workflow.exportCpw15020DiagramTestOnly(request, source);
        assertThat(export.outputModel().mode()).isEqualTo("TEST_ONLY");
        assertThat(export.outputModel().officialWorkbookCellsModified()).isFalse();
        assertThat(export.outputModel().officialMaterialBalanceUpdated()).isFalse();
        assertThat(export.worksheetPart()).isEqualTo("xl/worksheets/sheet7.xml");
        assertThat(export.outputModel().layoutAudit().existingTestOnlyCandidateId()).isEqualTo("C-PW15020");
        assertThat(export.outputModel().layoutAudit().wastewaterLabelCell()).isNotBlank();
        assertThat(export.outputModel().layoutAudit().wasteLabelCell()).isNotBlank();
        assertThat(export.outputModel().nodeMappings()).allSatisfy(mapping -> assertThat(mapping.equipmentId()).isNull());
        assertThat(export.diagramOverlay()).isNotNull();
        assertThat(export.diagramOverlay().addedShapes()).isEqualTo(4);
        assertThat(export.diagramOverlay().addedConnectors()).isEqualTo(2);
        assertThat(export.diagramOverlay().drawingPart()).isEqualTo("xl/drawings/drawing5.xml");

        var up = layoutEngine.analyze(source, new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05",
                "C-PW15009", "ACTIVATED CARBON FILTER", "C-PW15020", "SCREEN FILTER", "여과시설"));
        var down = layoutEngine.analyze(source, new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05",
                "C-PW15020", "SCREEN FILTER", "C-PW15010", "FINAL EFFLUENT TANK", "여과시설"));
        assertThat(export.outputModel().layoutAudit().existingTestOnlyCandidateCellRange())
                .isEqualTo(up.target().topLeftCell() + ":" + up.target().bottomRightCell());
        assertThat(export.outputModel().layoutAudit().downstreamEquipmentCellRange())
                .isEqualTo(down.target().topLeftCell() + ":" + down.target().bottomRightCell());

        Map<String, Object> candidate = nodeResult(export.calculation(), "C-PW15020");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> balances = (List<Map<String, Object>>) candidate.get("balanceByFlowTypeAndUnit");
        assertThat(balances).singleElement().satisfies(balance -> {
            assertThat((BigDecimal) balance.get("inflow")).isEqualByComparingTo("100");
            assertThat((BigDecimal) balance.get("outflow")).isEqualByComparingTo("100");
            assertThat((BigDecimal) balance.get("residualInMinusOut")).isEqualByComparingTo("0");
        });
        Map<String, Object> unassigned = nodeResult(export.calculation(), "UNASSIGNED_WASTEWATER_RECEIVER");
        assertThat(unassigned.get("balanceStatus")).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(unassigned.get("receiverType")).isEqualTo("TEST_ONLY_UNASSIGNED_RECEIVER_NO_ASSUMED_FACILITY");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transfers = (List<Map<String, Object>>) export.calculation().get("transfers");
        assertThat(transfers).hasSize(4).allSatisfy(transfer -> {
            assertThat(transfer.get("relationMode")).isEqualTo("TEST_ONLY");
            assertThat(transfer.get("included")).isEqualTo(true);
        });

        Files.createDirectories(OUTPUT.getParent());
        Files.write(OUTPUT, export.workbookBytes());
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(sourceHash);
        assertThat(sourceHash).isEqualTo(MaterialBalanceExcelExportService.EXPECTED_V5_SHA256);
        assertThat(sha256(Files.readAllBytes(STEP3))).isEqualTo(step3Hash);
        assertThat(sha256(Files.readAllBytes(STEP4))).isEqualTo(step4Hash);

        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var workbook = WorkbookFactory.create(new ByteArrayInputStream(export.workbookBytes()))) {
            assertThat(workbook.getSheet("TEST_ONLY Results")).isNotNull();
            var diagramSheet = workbook.getSheet("PW-01-05");
            var sourceDiagram = sourceWorkbook.getSheet("PW-01-05");
            DataFormatter formatter = new DataFormatter();
            assertThat(workbook.getNumberOfSheets()).isEqualTo(sourceWorkbook.getNumberOfSheets() + 1);
            assertThat(formatter.formatCellValue(diagramSheet.getRow(197).getCell(47))).isEqualTo("TEST_ONLY");
            assertThat(diagramSheet.getNumMergedRegions()).isEqualTo(sourceDiagram.getNumMergedRegions());
            assertThat(workbook.getPrintArea(4)).isEqualTo(sourceWorkbook.getPrintArea(4));
            var annotation = workbook.getSheet("TEST_ONLY Results");
            assertThat(findCellByValue(annotation, formatter, "TEST_ONLY 연결/구획")).isNotNull();
            assertThat(findCellByValue(annotation, formatter, "C-PW15009 → C-PW15020")).isNotNull();
            assertThat(findCellByValue(annotation, formatter, "TEST_ONLY 미지정 수신처 (실설비 미지정)")).isNotNull();
            assertThat(findCellByValue(annotation, formatter, "발생량 (처분·반출처 미확정)")).isNotNull();
            assertThat(findCellByValue(annotation, formatter, "C-PW15020 → C-PW15010")).isNotNull();
            assertThat(findCellByValue(annotation, formatter, "시험 질량수지: 100 = 0.5 + 2.5 + 97 ton/day — 계산값 일치; 미확정 목적지/승인관계는 자동 확정하지 않음")).isNotNull();
            assertThat(numberAtRowWithValue(annotation, formatter, "C-PW15009 → C-PW15020", 2)).isEqualByComparingTo("100");
            assertThat(numberAtRowWithValue(annotation, formatter, "TEST_ONLY 미지정 수신처 (실설비 미지정)", 2)).isEqualByComparingTo("2.5");
            assertThat(numberAtRowWithValue(annotation, formatter, "발생량 (처분·반출처 미확정)", 2)).isEqualByComparingTo("0.5");
            assertThat(numberAtRowWithValue(annotation, formatter, "C-PW15020 → C-PW15010", 2)).isEqualByComparingTo("97");
        }

        assertThat(partEditor.readPart(source, "xl/worksheets/sheet5.xml"))
                .containsExactly(partEditor.readPart(export.workbookBytes(), "xl/worksheets/sheet5.xml"));
        String originalDrawing = new String(partEditor.readPart(source, "xl/drawings/drawing5.xml"), java.nio.charset.StandardCharsets.UTF_8);
        String outputDrawing = new String(partEditor.readPart(export.workbookBytes(), "xl/drawings/drawing5.xml"), java.nio.charset.StandardCharsets.UTF_8);
        int originalRootClose = originalDrawing.lastIndexOf("</xdr:wsDr>");
        assertThat(originalRootClose).isGreaterThan(0);
        assertThat(outputDrawing).startsWith(originalDrawing.substring(0, originalRootClose));
        assertThat(outputDrawing).endsWith("</xdr:wsDr>");
        var inspector = new com.example.processautomation.excel.OoxmlPackageInspector();
        var diagramInventory = inspector.inspect(export.workbookBytes()).sheets().stream()
                .filter(sheet -> "PW-01-05".equals(sheet.sheetName())).findFirst().orElseThrow();
        assertThat(diagramInventory.drawingObjects()).anySatisfy(object -> {
            assertThat(object.objectName()).isEqualTo("TEST_ONLY C-PW15020 inflow 100");
            assertThat(object.textContent()).contains("TEST_ONLY", "유입 100 ton/day");
        });
        assertThat(diagramInventory.drawingObjects()).anySatisfy(object -> {
            assertThat(object.objectName()).isEqualTo("TEST_ONLY C-PW15020 downstream 97");
            assertThat(object.textContent()).contains("TEST_ONLY", "후단 이동 97 ton/day");
        });
        assertThat(diagramInventory.drawingObjects()).anySatisfy(object -> {
            assertThat(object.objectName()).isEqualTo("TEST_ONLY unassigned wastewater 2.5");
            assertThat(object.textContent()).contains("TEST_ONLY", "폐수 이동처 미확정", "2.5 ton/day");
        });
        assertThat(diagramInventory.drawingObjects()).anySatisfy(object -> {
            assertThat(object.objectName()).isEqualTo("TEST_ONLY unassigned waste 0.5");
            assertThat(object.textContent()).contains("TEST_ONLY", "폐기물 이동처 미확정", "0.5 ton/day 발생");
        });
        assertThat(diagramInventory.drawingObjects()).filteredOn(object -> object.objectName() != null
                        && object.objectName().startsWith("TEST_ONLY")
                        && (object.objectName().contains("wastewater branch arrow") || object.objectName().contains("waste branch arrow")))
                .hasSize(2).allSatisfy(object -> assertThat(object.tailArrowType()).isEqualTo("triangle"));
        assertThat(diagramInventory.drawingObjects()).filteredOn(object -> object.objectName() != null
                        && object.objectName().startsWith("TEST_ONLY") && "CONNECTOR".equals(object.objectType()))
                .hasSize(3).allSatisfy(object -> assertThat(object.tailArrowType()).isEqualTo("triangle"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class))
                .isEqualTo(confirmedBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class))
                .isEqualTo(reviewBefore);
    }

    @Test
    void adminEndpointReturnsSeparateDiagramTestOnlyWorkbookWithReviewRequiredStatus() throws Exception {
        byte[] source = Files.readAllBytes(SOURCE);
        var request = scenario("cpw15020-diagram-api-" + UUID.randomUUID());
        MockMultipartFile requestPart = new MockMultipartFile("request", "request.json", "application/json",
                objectMapper.writeValueAsBytes(request));
        MockMultipartFile workbookPart = new MockMultipartFile("workbook", SOURCE.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", source);
        byte[] response = mockMvc.perform(multipart("/api/admin/phase2/material-balance/export-cpw15020-test-only")
                        .file(requestPart).file(workbookPart).header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Calculation-Mode", "TEST_ONLY"))
                .andExpect(header().string("X-Official-Material-Balance-Updated", "false"))
                .andExpect(header().string("X-Official-Diagram-Modified", "false"))
                .andExpect(header().string("X-Test-Only-Diagram-Overlay", "applied"))
                .andExpect(header().string("X-Test-Only-Diagram-Shapes-Added", "4"))
                .andExpect(header().string("X-Test-Only-Diagram-Connectors-Added", "2"))
                .andExpect(header().string("X-Relation-Approval-Status", "REVIEW_REQUIRED"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(response));
             var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            assertThat(workbook.getSheet("TEST_ONLY Results")).isNotNull();
            assertThat(workbook.getSheet("PW-01-05").getNumMergedRegions())
                    .isEqualTo(sourceWorkbook.getSheet("PW-01-05").getNumMergedRegions());
        }
    }

    private MaterialBalanceRequest scenario(String simulationId) {
        String evidence = "Virtual C-PW15020 scenario only; no approved relation or inferred receiver";
        return new MaterialBalanceRequest(simulationId, "C-PW15020 100 ton/day isolated test", "TEST_ONLY",
                List.of(
                        node("C-PW15009", "TEST_ONLY source label", "SOURCE", "100", "WASTEWATER"),
                        node("C-PW15020", "TEST_ONLY candidate equipment", "PROCESS", null, null),
                        node("C-PW15010", "TEST_ONLY downstream equipment", "PROCESS", null, null),
                        node("UNASSIGNED_WASTE_OUTPUT", "TEST_ONLY waste generation", "WASTE_SINK", null, null),
                        node("UNASSIGNED_WASTEWATER_RECEIVER", "TEST_ONLY unassigned receiver", "UNASSIGNED_RECEIVER", null, null)),
                List.of(
                        transfer("cpw15009-cpw15020-in", "C-PW15009", "C-PW15020", "DOWNSTREAM", "WASTEWATER", "100", evidence),
                        transfer("cpw15020-waste-0_5", "C-PW15020", "UNASSIGNED_WASTE_OUTPUT", "WASTE_GENERATION", "WASTE", "0.5", evidence),
                        transfer("cpw15020-unassigned-2_5", "C-PW15020", "UNASSIGNED_WASTEWATER_RECEIVER", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5", evidence),
                        transfer("cpw15020-cpw15010-out", "C-PW15020", "C-PW15010", "DOWNSTREAM", "WASTEWATER", "97", evidence)));
    }

    private MaterialBalanceRequest.Node node(String id, String label, String role, String inflow, String flow) {
        return new MaterialBalanceRequest.Node(id, label, null, null, role,
                inflow == null ? null : new BigDecimal(inflow), flow, inflow == null ? null : "ton/day");
    }

    private MaterialBalanceRequest.Transfer transfer(String id, String source, String target, String edge,
                                                     String flow, String quantity, String evidence) {
        return new MaterialBalanceRequest.Transfer(id, "TEST_ONLY", null, source, target, edge, flow,
                new BigDecimal(quantity), "ton/day", evidence);
    }

    private Map<String, Object> nodeResult(Map<String, Object> calculation, String nodeId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) calculation.get("nodes");
        return nodes.stream().filter(node -> nodeId.equals(node.get("nodeId"))).findFirst()
                .orElseThrow(() -> new AssertionError("missing calculated node " + nodeId));
    }

    private org.apache.poi.ss.usermodel.Cell findCellByValue(org.apache.poi.ss.usermodel.Sheet sheet,
                                                               DataFormatter formatter, String value) {
        for (var row : sheet) for (var cell : row) if (value.equals(formatter.formatCellValue(cell))) return cell;
        return null;
    }

    private BigDecimal numberAtRowWithValue(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter,
                                             String value, int columnIndex) {
        for (var row : sheet) for (var cell : row) {
            if (value.equals(formatter.formatCellValue(cell))) return new BigDecimal(formatter.formatCellValue(row.getCell(columnIndex)));
        }
        throw new AssertionError("missing row value " + value);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
