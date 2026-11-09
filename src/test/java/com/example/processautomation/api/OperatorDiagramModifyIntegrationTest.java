package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.material.NumericWorkingCopyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorDiagramModifyIntegrationTest {
    private static final String USER = "diagram-modify-test";
    private static final String PASSWORD = "Diagram-modify-" + UUID.randomUUID() + "-Passphrase!";
    private static final String PASSWORD_HASH = OperatorLoginService.hashForTests(PASSWORD,
            "diagram-modify-test-salt".getBytes(StandardCharsets.UTF_8), 210_000);
    private static final String API = "/api/workspace/diagram-editor/modify";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.operator.username", () -> USER);
        registry.add("app.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NumericWorkingCopyService workingCopies;
    @Autowired private OoxmlPackageInspector inspector;

    @Test
    void p02MasterMappedFieldsCanBeModifiedThroughCsrfProtectedTestOnlyWorkflow() throws Exception {
        String sheet = "P-02-01(TOP)";
        mvc.perform(get(API + "/equipment").param("workingDocumentId", "3")
                        .param("sheetName", sheet).param("managementNo", "I-PP21039"))
                .andExpect(status().isUnauthorized());
        Login login = login();
        MvcResult inspected = mvc.perform(get(API + "/equipment").session(login.session())
                        .param("workingDocumentId", "3").param("sheetName", sheet)
                        .param("managementNo", "I-PP21039"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processCode").value("P-02"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.cellAddress").value("I585"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.masterText").value("WWTK-131-008"))
                .andExpect(jsonPath("$.mapping.fields.capacityWithUnit.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.capacityWithUnit.currentText").value("60㎥"))
                .andExpect(jsonPath("$.mapping.fields.managementNo.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.mapping.fields.permitName.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.mapping.fields.quantity.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.connectionEvidenceStatus").value("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"))
                .andReturn();
        JsonNode profile = mapper.readTree(inspected.getResponse().getContentAsByteArray());
        MvcResult options = mvc.perform(get(API + "/equipment-options").session(login.session())
                        .param("workingDocumentId", "3").param("sheetName", sheet))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(options.getResponse().getContentAsByteArray()).toString())
                .contains("I-PP21039").doesNotContain("I-PW21007");
        String sourceHash = profile.path("workingSha256").asText();
        byte[] originalWorkingCopy = workingCopies.getWorkingCopy(3).bytes();
        Map<String, Object> request = Map.of("workingDocumentId", 3, "sheetName", sheet,
                "managementNo", "I-PP21039", "replacements", Map.of("businessItemNo", "TEST-P02-ITEM-01"));
        mvc.perform(post(API + "/preview").session(login.session()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
        MvcResult previewResult = mvc.perform(post(API + "/preview").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeValues.businessItemNo").value("WWTK-131-008"))
                .andExpect(jsonPath("$.afterValues.businessItemNo").value("TEST-P02-ITEM-01"))
                .andExpect(jsonPath("$.status").value("TEST_ONLY_READY"))
                .andReturn();
        String previewId = mapper.readTree(previewResult.getResponse().getContentAsByteArray()).path("previewId").asText();
        MvcResult generated = mvc.perform(post(API + "/generate").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officialBaselineModified").value(false))
                .andExpect(jsonPath("$.structuralEditStatus").value("STRUCTURE_VERIFIED_TEXT_FIELDS_ONLY"))
                .andExpect(jsonPath("$.connectionEvidenceStatus").value("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"))
                .andReturn();
        JsonNode generatedJson = mapper.readTree(generated.getResponse().getContentAsByteArray());
        MvcResult downloaded = mvc.perform(get(API + "/download/{previewId}", previewId).session(login.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Scenario-Status", "TEST_ONLY"))
                .andReturn();
        byte[] output = downloaded.getResponse().getContentAsByteArray();
        assertThat(sha256(output)).isEqualTo(generatedJson.path("outputSha256").asText());
        assertThat(sha256(originalWorkingCopy)).isEqualTo(sourceHash);
        assertWorkbookChangeOnlyAtCell(originalWorkingCopy, output, sheet, "I585", "TEST-P02-ITEM-01");
        Files.createDirectories(Path.of("target", "diagram-modify-validation"));
        Files.write(Path.of("target", "diagram-modify-validation", "P-02_I-PP21039_TEST_ONLY_MODIFY.xlsx"), output);
        Login otherLogin = login();
        mvc.perform(get(API + "/download/{previewId}", previewId).session(otherLogin.session()))
                .andExpect(status().isNotFound());
        TestTransaction.flagForRollback();
    }

    @Test
    void pw02MasterMappedFieldsAreIndependentAndUnmappedFieldsRemainReviewRequired() throws Exception {
        String sheet = "PW-02-01";
        Login login = login();
        MvcResult inspected = mvc.perform(get(API + "/equipment").session(login.session())
                        .param("workingDocumentId", "8").param("sheetName", sheet)
                        .param("managementNo", "I-PW21007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.cellAddress").value("J149"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.currentText").value("PI-102"))
                .andExpect(jsonPath("$.mapping.fields.equipmentName.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.permitName.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.permitName.cellAddress").value("J147"))
                .andExpect(jsonPath("$.mapping.fields.managementNo.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.mapping.fields.capacityWithUnit.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.capacityWithUnit.currentText").value("100㎥"))
                .andExpect(jsonPath("$.mapping.fields.quantity.status").value("REVIEW_REQUIRED"))
                .andReturn();
        JsonNode profile = mapper.readTree(inspected.getResponse().getContentAsByteArray());
        MvcResult options = mvc.perform(get(API + "/equipment-options").session(login.session())
                        .param("workingDocumentId", "8").param("sheetName", sheet))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(options.getResponse().getContentAsByteArray()).toString())
                .contains("I-PW21007").doesNotContain("I-PP21039");
        byte[] originalWorkingCopy = workingCopies.getWorkingCopy(8).bytes();
        assertThat(sha256(originalWorkingCopy)).isEqualTo(profile.path("workingSha256").asText());
        Map<String, Object> request = Map.of("workingDocumentId", 8, "sheetName", sheet,
                "managementNo", "I-PW21007", "replacements", Map.of("businessItemNo", "TEST-PW02-ITEM-01"));
        MvcResult preview = mvc.perform(post(API + "/preview").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        String previewId = mapper.readTree(preview.getResponse().getContentAsByteArray()).path("previewId").asText();
        mvc.perform(post(API + "/preview").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("workingDocumentId", 8, "sheetName", sheet,
                                "managementNo", "I-PW21007", "replacements", Map.of("quantity", "2")))))
                .andExpect(status().isConflict());
        mvc.perform(post(API + "/preview").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("workingDocumentId", 8, "sheetName", sheet,
                                "managementNo", "I-PW21007", "replacements", Map.of("equipmentName", "REVIEW-ONLY")))))
                .andExpect(status().isConflict());
        MvcResult generated = mvc.perform(post(API + "/generate").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andReturn();
        JsonNode generatedJson = mapper.readTree(generated.getResponse().getContentAsByteArray());
        MvcResult downloaded = mvc.perform(get(API + "/download/{previewId}", previewId).session(login.session()))
                .andExpect(status().isOk()).andReturn();
        byte[] output = downloaded.getResponse().getContentAsByteArray();
        assertThat(sha256(output)).isEqualTo(generatedJson.path("outputSha256").asText());
        assertWorkbookChangeOnlyAtCell(originalWorkingCopy, output, sheet, "J149", "TEST-PW02-ITEM-01");
        Files.createDirectories(Path.of("target", "diagram-modify-validation"));
        Files.write(Path.of("target", "diagram-modify-validation", "PW-02_I-PW21007_TEST_ONLY_MODIFY.xlsx"), output);
        TestTransaction.flagForRollback();
    }

    private Login login() throws Exception {
        MvcResult response = mvc.perform(post("/api/session/login").header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", USER, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new Login((MockHttpSession) response.getRequest().getSession(false),
                mapper.readTree(response.getResponse().getContentAsByteArray()).path("csrfToken").asText());
    }

    private void assertWorkbookChangeOnlyAtCell(byte[] source, byte[] output, String sheetName,
                                                String changedAddress, String expectedValue) throws Exception {
        var sourceInventory = inspector.inspect(source);
        var outputInventory = inspector.inspect(output);
        assertThat(outputInventory.shapeCount()).isEqualTo(sourceInventory.shapeCount());
        assertThat(outputInventory.connectorCount()).isEqualTo(sourceInventory.connectorCount());
        assertThat(outputInventory.pictureCount()).isEqualTo(sourceInventory.pictureCount());
        assertThat(outputInventory.explicitConnectorBindings()).isEqualTo(sourceInventory.explicitConnectorBindings());
        var beforeSheetInventory = sourceInventory.sheets().stream().filter(s -> sheetName.equals(s.sheetName())).findFirst().orElseThrow();
        var afterSheetInventory = outputInventory.sheets().stream().filter(s -> sheetName.equals(s.sheetName())).findFirst().orElseThrow();
        assertThat(afterSheetInventory.drawingObjects()).isEqualTo(beforeSheetInventory.drawingObjects());
        byte[] drawingBefore = new com.example.processautomation.excel.OoxmlPartEditor()
                .readPart(source, beforeSheetInventory.drawingPartName());
        byte[] drawingAfter = new com.example.processautomation.excel.OoxmlPartEditor()
                .readPart(output, afterSheetInventory.drawingPartName());
        assertThat(drawingAfter).containsExactly(drawingBefore);
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(source));
             var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var beforeSheet = before.getSheet(sheetName);
            var afterSheet = after.getSheet(sheetName);
            assertThat(afterSheet.getNumMergedRegions()).isEqualTo(beforeSheet.getNumMergedRegions());
            assertThat(after.getPrintArea(after.getSheetIndex(sheetName)))
                    .isEqualTo(before.getPrintArea(before.getSheetIndex(sheetName)));
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            var reference = new org.apache.poi.ss.util.CellReference(changedAddress);
            for (int row = 0; row <= beforeSheet.getLastRowNum(); row++) {
                var oldRow = beforeSheet.getRow(row);
                var newRow = afterSheet.getRow(row);
                if (oldRow == null && newRow == null) continue;
                int last = Math.max(oldRow == null ? -1 : oldRow.getLastCellNum(), newRow == null ? -1 : newRow.getLastCellNum());
                for (int col = 0; col < last; col++) {
                    var oldCell = oldRow == null ? null : oldRow.getCell(col);
                    var newCell = newRow == null ? null : newRow.getCell(col);
                    if (row == reference.getRow() && col == reference.getCol()) {
                        assertThat(newCell).isNotNull();
                        assertThat(formatter.formatCellValue(newCell)).isEqualTo(expectedValue);
                    } else {
                        assertThat(newCell == null ? "" : formatter.formatCellValue(newCell))
                                .as("unmodified cell %s", new org.apache.poi.ss.util.CellReference(row, col).formatAsString())
                                .isEqualTo(oldCell == null ? "" : formatter.formatCellValue(oldCell));
                        if (oldCell != null && newCell != null) {
                            assertThat(newCell.getCellStyle().getIndex()).isEqualTo(oldCell.getCellStyle().getIndex());
                        }
                    }
                }
            }
        }
        assertZipIntegrity(output);
    }

    private void assertZipIntegrity(byte[] workbook) throws Exception {
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(workbook))) {
            var entry = input.getNextEntry();
            byte[] buffer = new byte[8192];
            while (entry != null) {
                while (input.read(buffer) != -1) { }
                input.closeEntry();
                entry = input.getNextEntry();
            }
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Login(MockHttpSession session, String csrf) { }
}
