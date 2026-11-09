package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorWorkspaceSessionIntegrationTest {
    private static final String TEST_USERNAME = "operator-test";
    private static final String TEST_PASSWORD = "Test-Only-" + java.util.UUID.randomUUID() + "-Passphrase!";
    private static final byte[] TEST_SALT = "operator-test-salt".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_PASSWORD_HASH = OperatorLoginService.hashForTests(TEST_PASSWORD, TEST_SALT, 210_000);

    @DynamicPropertySource
    static void loginProperties(DynamicPropertyRegistry registry) {
        registry.add("app.operator.username", () -> TEST_USERNAME);
        registry.add("app.operator.password-hash", () -> TEST_PASSWORD_HASH);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void equipmentTypeFieldIsFreeTextWithoutLegacyDefault() throws Exception {
        String indexHtml = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        assertThat(indexHtml).contains("<input id=\"equipment-type-label\" type=\"text\"")
                .contains("placeholder=\"예: 여과시설, 저장시설, 혼합시설\"")
                .contains("equipmentTypeLabel:byId('equipment-type-label').value,")
                .contains("['설비 유형 표시','기존 도면에 없음',input.equipmentTypeLabel]")
                .doesNotContain("id=\"equipment-type-label\" value=\"여과시설\"");
    }

    @Test
    void loginListsFifteenDocumentsLoadsId36AndKeepsAddFlowSessionScoped() throws Exception {
        mvc.perform(get("/api/workspace/process-documents"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/workspace/process-documents"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login.html"));

        MvcResult login = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String csrf = mapper.readTree(login.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        assertThat(csrf).isNotBlank();
        assertThat(login.getResponse().getContentAsString()).doesNotContain("X-Admin-Token", "BASELINE_ADMIN_TOKEN");
        MvcResult documents = mvc.perform(get("/api/workspace/process-documents").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode documentList = mapper.readTree(documents.getResponse().getContentAsByteArray());
        assertThat(documentList).hasSize(15);
        JsonNode pw01 = null;
        for (JsonNode item : documentList) if ("PW-01".equals(item.path("processCode").asText())) pw01 = item;
        assertThat(pw01).isNotNull();
        assertThat(pw01.path("baselineDocumentId").asLong()).isEqualTo(12L);
        assertThat(pw01.path("workingDocumentId").asLong()).isEqualTo(36L);

        mvc.perform(get("/api/workspace/working-copies/36").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDocumentId").value(36))
                .andExpect(jsonPath("$.processCode").value("PW-01"))
                .andExpect(jsonPath("$.conversionVersion").value("PW01-OFFICIAL-NUMERIC-V1"));
        assertThat(jdbc.queryForObject("SELECT LOWER(working_sha256)=LOWER(SHA2(content_bytes,256)) FROM process_working_document WHERE id=36", Boolean.class))
                .isTrue();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workingDocumentId", 36);
        input.put("sheetName", "PW-01-05");
        input.put("changeType", "ADD");
        input.put("managementNo", "SYNTH-SESSION-20261028-A");
        input.put("equipmentName", "Session Test Filter A");
        input.put("businessItemNo", "SESSION-TEST-ITEM-A");
        input.put("capacity", "123");
        input.put("capacityUnit", "㎥/일");
        input.put("equipmentQuantity", "1대");
        input.put("placementId", "PW01-05-BETWEEN-C-PW15009-C-PW15010");
        input.put("equipmentTypeLabel", "저장시설");
        input.put("routes", java.util.List.of());
        input.put("runSyntheticTestOnlyNetwork", false);
        input.put("syntheticNetworkScopeComplete", false);
        input.put("scopeCompleteAttested", false);

        input.remove("equipmentTypeLabel");
        mvc.perform(post("/api/workspace/change-preview").session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isBadRequest());
        input.put("equipmentTypeLabel", "저장시설");

        for (int index = 0; index < 2; index++) {
            String equipmentType = index == 0 ? "저장시설" : "혼합시설";
            String managementNo = "SYNTH-TYPE-20261029-" + (index == 0 ? "A" : "B");
            input.put("managementNo", managementNo);
            input.put("equipmentName", index == 0 ? "Session Storage Vessel" : "Session Mixing Vessel");
            input.put("businessItemNo", "SESSION-TYPE-ITEM-" + (index == 0 ? "A" : "B"));
            input.put("equipmentTypeLabel", equipmentType);

            mvc.perform(post("/api/workspace/change-preview").session(session)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                    .andExpect(status().isForbidden());
            MvcResult preview = mvc.perform(post("/api/workspace/change-preview").session(session)
                            .header("X-CSRF-Token", csrf)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changeType").value("ADD"))
                    .andExpect(jsonPath("$.automaticEditAllowed").value(true))
                    .andExpect(jsonPath("$.editPlanStatus").value("TEST_ONLY_READY"))
                    .andReturn();
            JsonNode previewJson = mapper.readTree(preview.getResponse().getContentAsByteArray());
            assertThat(previewJson.path("afterSummary").asText()).endsWith("/ " + equipmentType);
            long requestId = previewJson.path("requestId").asLong();

            MockHttpSession otherSession = new MockHttpSession();
            otherSession.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, TEST_USERNAME);
            otherSession.setAttribute("operator.csrf", csrf);
            mvc.perform(post("/api/workspace/requests/{requestId}/generate", requestId).session(otherSession)
                            .header("X-CSRF-Token", csrf))
                    .andExpect(status().isNotFound());

            MvcResult generated = mvc.perform(post("/api/workspace/requests/{requestId}/generate", requestId).session(session)
                            .header("X-CSRF-Token", csrf))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestStatus").value("GENERATED"))
                    .andExpect(jsonPath("$.officialBaselineModified").value(false))
                    .andReturn();
            String expectedHash = mapper.readTree(generated.getResponse().getContentAsByteArray()).path("sha256").asText();

            MvcResult download = mvc.perform(get("/api/workspace/requests/{requestId}/download", requestId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                    .andReturn();
            byte[] outputBytes = download.getResponse().getContentAsByteArray();
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outputBytes));
            assertThat(actualHash).isEqualTo(expectedHash);
            java.nio.file.Path artifactDirectory = java.nio.file.Path.of("target", "equipment-type-validation");
            java.nio.file.Files.createDirectories(artifactDirectory);
            java.nio.file.Files.write(artifactDirectory.resolve(index == 0 ? "storage-facility.xlsx" : "mixing-facility.xlsx"),
                    outputBytes);

            byte[] sourceBytes = jdbc.queryForObject(
                    "SELECT content_bytes FROM process_working_document WHERE id=36", byte[].class);
            try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
                 var outputWorkbook = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
                var sourceSheet = sourceWorkbook.getSheet("PW-01-05");
                var outputSheet = outputWorkbook.getSheet("PW-01-05");
                var formatter = new DataFormatter(java.util.Locale.ROOT);
                var typeCell = outputSheet.getRow(199).getCell(34);
                var sourceTypeCell = sourceSheet.getRow(184).getCell(34);
                assertThat(formatter.formatCellValue(typeCell)).isEqualTo(equipmentType);
                assertThat(typeCell.getCellStyle().getIndex()).isEqualTo(sourceTypeCell.getCellStyle().getIndex());
                var typeFont = outputWorkbook.getFontAt(typeCell.getCellStyle().getFontIndexAsInt());
                assertThat(typeFont.getFontName()).isEqualTo("맑은 고딕");
                assertThat(typeFont.getFontHeightInPoints()).isEqualTo((short) 7);
                assertThat(typeCell.getCellStyle().getAlignment()).isEqualTo(sourceTypeCell.getCellStyle().getAlignment());
            }
        }
        input.put("changeType", "MODIFY");
        input.put("targetManagementNo", "C-PW15020");
        mvc.perform(post("/api/workspace/change-preview").session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_request WHERE id IN (50,51)", Integer.class)).isEqualTo(2);
        TestTransaction.flagForRollback();
    }
}
