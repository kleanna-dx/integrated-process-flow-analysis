package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MaterialBalanceExcelExportIntegrationTest {
    private static final Path SOURCE = Path.of("validation-results/2026-10-16/pw01-05-test-only/PW-01-05-C-PW15020-TEST_ONLY-V5.xlsx");

    @Autowired private MaterialBalanceExcelWorkflowService workflow;
    @Autowired private MaterialBalanceExcelExportService exportService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    @Test
    void calculatesAndWritesOnlyAnIsolatedTestOnlyResultsSheet(@TempDir Path tempDirectory) throws Exception {
        byte[] source = Files.readAllBytes(SOURCE);
        Path output = tempDirectory.resolve("isolated-test-only-results.xlsx");
        String originalHash = sha256(source);
        var request = scenario("excel-export-" + UUID.randomUUID());

        var export = workflow.exportTestOnly(request, source);

        assertThat(export.outputModel().mode()).isEqualTo("TEST_ONLY");
        assertThat(export.outputModel().officialWorkbookCellsModified()).isFalse();
        assertThat(export.outputModel().officialMaterialBalanceUpdated()).isFalse();
        assertThat(export.worksheetPart()).isEqualTo("xl/worksheets/sheet7.xml");
        assertThat(export.outputModel().nodeMappings()).allSatisfy(mapping -> {
            assertThat(mapping.equipmentId()).isNull();
            assertThat(mapping.mappingStatus()).isEqualTo("UNMAPPED_NO_EXPLICIT_EQUIPMENT_ID");
        });

        Files.write(output, export.workbookBytes());
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(originalHash);
        assertThat(originalHash).isEqualTo(MaterialBalanceExcelExportService.EXPECTED_V5_SHA256);

        try (var sourceWorkbook = WorkbookFactory.create(Files.newInputStream(SOURCE));
             var outputWorkbook = WorkbookFactory.create(Files.newInputStream(output))) {
            assertThat(outputWorkbook.getNumberOfSheets()).isEqualTo(sourceWorkbook.getNumberOfSheets() + 1);
            assertThat(outputWorkbook.getSheet("TEST_ONLY Results")).isNotNull();
            var sheet = outputWorkbook.getSheet("TEST_ONLY Results");
            DataFormatter formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(0)))
                    .contains("TEST_ONLY", "공식 물량 미변경");
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(0)))
                    .contains("가상 시험값", "기존 PW-01-05");
            for (var row : sheet) {
                for (var cell : row) {
                    XSSFFont font = ((XSSFCell) cell).getCellStyle().getFont();
                    assertThat(font.getXSSFColor()).as("explicit font color at %s", cell.getAddress())
                            .isNotNull();
                    assertThat(font.getXSSFColor().getARGBHex()).as("explicit black at %s", cell.getAddress())
                            .isEqualToIgnoringCase("FF000000");
                }
            }

            int p2Row = findNodeRow(sheet, formatter, "P2 /");
            assertThat(new BigDecimal(formatter.formatCellValue(sheet.getRow(p2Row).getCell(8))))
                    .isEqualByComparingTo("0.25");
            assertThat(formatter.formatCellValue(sheet.getRow(p2Row).getCell(10))).isEqualTo("잔차 미확정");
            int wasteRow = findNodeRow(sheet, formatter, "W /");
            assertThat(new BigDecimal(formatter.formatCellValue(sheet.getRow(wasteRow).getCell(8))))
                    .isEqualByComparingTo("0.5");
            assertThat(formatter.formatCellValue(sheet.getRow(wasteRow).getCell(12)))
                    .isEqualTo("저장/반출 미확정");
            int outletRow = findNodeRow(sheet, formatter, "D /");
            assertThat(new BigDecimal(formatter.formatCellValue(sheet.getRow(outletRow).getCell(5))))
                    .isEqualByComparingTo("99.25");
            assertThat(new BigDecimal(formatter.formatCellValue(sheet.getRow(outletRow).getCell(8))))
                    .isEqualByComparingTo("99.25");
            assertThat(formatter.formatCellValue(sheet.getRow(outletRow).getCell(11))).isEqualTo("미명시");
            assertThat(formatter.formatCellValue(sheet.getRow(outletRow).getCell(12))).isEqualTo("최종 경계 미확정");

            assertThat(sourceWorkbook.getSheet("PW-01-05").getRow(197).getCell(47).getStringCellValue())
                    .isEqualTo("TEST_ONLY");
            assertThat(outputWorkbook.getSheet("PW-01-05").getRow(197).getCell(47).getStringCellValue())
                    .isEqualTo("TEST_ONLY");
            assertThat(outputWorkbook.getSheet("PW-01-05").getNumMergedRegions())
                    .isEqualTo(sourceWorkbook.getSheet("PW-01-05").getNumMergedRegions());
            assertThat(outputWorkbook.getPrintArea(4)).isEqualTo(sourceWorkbook.getPrintArea(4));
            assertThat(outputWorkbook.getPrintArea(6)).contains("$A$1:$M$");
            assertThat(sheet.getPrintSetup().getLandscape()).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_simulation WHERE simulation_id = ?",
                Integer.class, request.simulationId())).isEqualTo(1);
        assertThat(export.outputModel().transfers()).hasSize(5);
        assertThat(export.outputModel().transfers()).allSatisfy(line -> {
            assertThat(line.included()).isTrue();
            assertThat(line.reconciliationStatus()).isEqualTo("MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED");
            assertThat(line.sourceEquipmentId()).isNull();
            assertThat(line.targetEquipmentId()).isNull();
        });
        assertThat(export.outputModel().balances()).anySatisfy(balance ->
                assertThat(balance.residual()).isEqualByComparingTo("0.25"));
    }

    @Test
    void adminExportEndpointReturnsOnlyTheTestOnlyWorkbookAttachment() throws Exception {
        byte[] source = Files.readAllBytes(SOURCE);
        var request = scenario("api-export-" + UUID.randomUUID());
        MockMultipartFile requestPart = new MockMultipartFile("request", "request.json", "application/json",
                objectMapper.writeValueAsBytes(request));
        MockMultipartFile workbookPart = new MockMultipartFile("workbook", SOURCE.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", source);

        byte[] response = mockMvc.perform(multipart("/api/admin/phase2/material-balance/export")
                        .file(requestPart).file(workbookPart).header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Calculation-Mode", "TEST_ONLY"))
                .andExpect(header().string("X-Official-Material-Balance-Updated", "false"))
                .andReturn().getResponse().getContentAsByteArray();

        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(response))) {
            assertThat(workbook.getSheet("TEST_ONLY Results")).isNotNull();
            assertThat(workbook.getSheet("PW-01-05").getRow(197).getCell(47).getStringCellValue()).isEqualTo("TEST_ONLY");
        }
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(MaterialBalanceExcelExportService.EXPECTED_V5_SHA256);
    }

    @Test
    void refusesToAddResultsToAnyWorkbookOtherThanPreservedV5() throws Exception {
        byte[] v5 = Files.readAllBytes(SOURCE);
        byte[] altered = v5.clone();
        altered[altered.length - 1] ^= 1;
        var request = scenario("bad-source-" + UUID.randomUUID());
        Map<String, Object> calculation = Map.of("mode", "TEST_ONLY", "officialBaselineModified", false,
                "productionMaterialBalanceUpdated", false, "transfers", List.of(), "nodes", List.of());
        var model = exportService.createOutputModel(request, calculation, v5);
        assertThatThrownBy(() -> exportService.addResultsSheet(altered, model))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("different workbook version");
        var rejectedWorkflowRequest = scenario("reject-before-calculate-" + UUID.randomUUID());
        assertThatThrownBy(() -> workflow.exportTestOnly(rejectedWorkflowRequest, altered))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("preserved PW-01-05 TEST_ONLY V5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_simulation WHERE simulation_id = ?",
                Integer.class, rejectedWorkflowRequest.simulationId())).isZero();
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(MaterialBalanceExcelExportService.EXPECTED_V5_SHA256);
    }

    private MaterialBalanceRequest scenario(String simulationId) {
        return new MaterialBalanceRequest(simulationId, "Virtual 100 ton/day balance test", "TEST_ONLY",
                List.of(
                        node("S", "Virtual source", "P-TEST-A", "SOURCE", "100", "WASTEWATER"),
                        node("W", "Virtual waste receiver", "P-TEST-A", "WASTE_SINK", null, null),
                        node("P2", "Virtual receiving process", "P-TEST-B", "PROCESS", null, null),
                        node("P3", "Virtual downstream process", "P-TEST-C", "PROCESS", null, null),
                        node("D", "Virtual final receiver", "P-TEST-C", "OUTLET", null, null)),
                List.of(
                        transfer("waste-0_5", "S", "W", "WASTE_GENERATION", "WASTE", "0.5"),
                        transfer("transfer-2_5", "S", "P2", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5"),
                        transfer("source-downstream-97", "S", "D", "DOWNSTREAM", "WASTEWATER", "97"),
                        transfer("p2-downstream-2_25", "P2", "P3", "DOWNSTREAM", "WASTEWATER", "2.25"),
                        transfer("p3-outflow-2_25", "P3", "D", "DOWNSTREAM", "WASTEWATER", "2.25")));
    }

    private MaterialBalanceRequest.Node node(String id, String label, String process, String role,
                                             String inflow, String flowType) {
        return new MaterialBalanceRequest.Node(id, label, process, null, role,
                inflow == null ? null : new BigDecimal(inflow), flowType, inflow == null ? null : "ton/day");
    }

    private MaterialBalanceRequest.Transfer transfer(String id, String source, String target, String edgeType,
                                                     String flowType, String quantity) {
        return new MaterialBalanceRequest.Transfer(id, "TEST_ONLY", null, source, target, edgeType, flowType,
                new BigDecimal(quantity), "ton/day", "Virtual scenario only; no actual facility mapping");
    }

    private int findNodeRow(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String prefix) {
        for (var row : sheet) {
            var cell = row.getCell(0);
            if (cell != null && formatter.formatCellValue(cell).startsWith(prefix)) return row.getRowNum();
        }
        throw new AssertionError("Could not find output row beginning with " + prefix);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
