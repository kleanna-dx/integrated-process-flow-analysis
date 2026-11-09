package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorChangeProposalIntegrationTest {
    private static final String USER = "proposal-test-operator";
    private static final String PASSWORD = "Proposal-test-" + UUID.randomUUID() + "-Passphrase!";
    private static final String PASSWORD_HASH = OperatorLoginService.hashForTests(PASSWORD,
            "proposal-test-operator-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8), 210_000);
    private static final String API = "/api/workspace/change-proposals";

    @DynamicPropertySource
    static void isolatedDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("PROPOSAL_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("PROPOSAL_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("PROPOSAL_TEST_DB_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.target", () -> "24");
        registry.add("app.operator.username", () -> USER);
        registry.add("app.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private long workingDocumentId;
    private String workingSha;

    @BeforeEach
    void createSyntheticWorkingCopyOnly() throws Exception {
        byte[] workbookBytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("SYNTHETIC-01").createRow(0).createCell(0).setCellValue("TEST_ONLY");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }
        workingSha = sha256(workbookBytes);
        String sourceSha = sha256("synthetic locked source".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO baseline_release(version_code,description,source_master_sha256,locked) VALUES(?,?,?,TRUE)",
                "V1.0", "Isolated synthetic fixture", sha256("synthetic master".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long releaseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "SYNTHETIC-P", "SYNTHETIC-P.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes.length,
                sourceSha, workbookBytes);
        long documentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id,source_sha256,working_sha256,
                    conversion_version,conversion_status,source_filename,working_filename,conversion_report_json,content_bytes)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, documentId, sourceSha, workingSha, "NUMERIC-WORKING-UPLOAD-V1", "CREATED_WITH_REVIEW",
                "SYNTHETIC-P.xlsx", "SYNTHETIC-P_WORKING.xlsx", "{}", workbookBytes);
        workingDocumentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void allFiveProposalTypesAppendEventsAndRemainScopedToTheRequestingUser() throws Exception {
        Login login = login();
        String firstProposalId = null;
        for (String changeType : new String[]{"ADD", "MODIFY", "DELETE", "PIPE_CHANGE", "FLOW_CHANGE"}) {
            var response = mvc.perform(post(API).session(login.session())
                            .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(proposalRequest(changeType))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changeType").value(changeType))
                    .andExpect(jsonPath("$.mappingStatus").value("REVIEW_REQUIRED"))
                    .andExpect(jsonPath("$.calculationStatus").value("CALCULATION_UNAVAILABLE"))
                    .andExpect(jsonPath("$.reviewStatus").value("REVIEW_REQUIRED"))
                    .andExpect(jsonPath("$.events.length()").value(1));
            if ("PIPE_CHANGE".equals(changeType)) {
                response.andExpect(jsonPath("$.beforeValues.targetEquipment").value("SYN-EQ-B"))
                        .andExpect(jsonPath("$.afterValues.targetEquipment").value("SYN-EQ-C"));
            } else if ("FLOW_CHANGE".equals(changeType)) {
                response.andExpect(jsonPath("$.beforeValues.quantity").value(20))
                        .andExpect(jsonPath("$.afterValues.quantity").value(10));
            } else {
                response.andExpect(jsonPath("$.beforeValues.value").value("before"))
                        .andExpect(jsonPath("$.afterValues.value").value("after"));
            }
            MvcResult result = response.andReturn();
            String proposalId = mapper.readTree(result.getResponse().getContentAsByteArray()).path("proposalId").asText();
            if (firstProposalId == null) firstProposalId = proposalId;
        }

        MvcResult listed = mvc.perform(get(API).session(login.session()).param("limit", "100"))
                .andExpect(status().isOk()).andReturn();
        JsonNode proposalsList = mapper.readTree(listed.getResponse().getContentAsByteArray());
        assertThat(proposalsList).hasSize(5);
        assertThat(proposalsList.findValuesAsText("requestUser")).containsOnly(USER);
        mvc.perform(get(API + "/{proposalId}", firstProposalId).session(login.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].eventType").value("PROPOSED"));

        MockHttpSession otherUserSession = new MockHttpSession();
        otherUserSession.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "different-operator");
        otherUserSession.setAttribute("operator.csrf", "different-csrf-token");
        mvc.perform(get(API + "/{proposalId}", firstProposalId).session(otherUserSession))
                .andExpect(status().isNotFound());
        MvcResult otherList = mvc.perform(get(API).session(otherUserSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(otherList.getResponse().getContentAsByteArray())).isEmpty();

        TestTransaction.flagForRollback();
    }

    @Test
    void csrfValidationAndAppendOnlyDatabaseTriggersRejectWritesToHistory() throws Exception {
        Login login = login();
        MvcResult created = mvc.perform(post(API).session(login.session()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(proposalRequest("MODIFY"))))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(403);

        MvcResult saved = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(proposalRequest("MODIFY"))))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(saved.getResponse().getContentAsByteArray()).path("proposalId").asText();
        long eventId = jdbc.queryForObject("SELECT event_id FROM process_change_proposal_event WHERE proposal_id=?", Long.class, proposalId);

        assertThatThrownBy(() -> jdbc.update("UPDATE process_change_proposal_event SET rationale='tampered' WHERE event_id=?", eventId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM process_change_proposal_event WHERE event_id=?", eventId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT rationale FROM process_change_proposal_event WHERE event_id=?", String.class, eventId))
                .isEqualTo("Synthetic test proposal for MODIFY");

        Map<String, Object> invalid = new java.util.LinkedHashMap<>(proposalRequest("ADD"));
        invalid.put("changeType", "UNSUPPORTED");
        mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event", Integer.class)).isEqualTo(1);
        TestTransaction.flagForRollback();
    }

    @Test
    void pipeChangeProposalStoresSyntheticWhatIfComparisonWithoutChangingActualReadiness() throws Exception {
        Login login = login();
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(proposalRequest("PIPE_CHANGE"))))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        Map<String, Object> comparison = syntheticBeforeAfterNetworks();
        mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(comparison)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.latestEventType").value("VALIDATION_RESULT"))
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATED_SYNTHETIC"))
                .andExpect(jsonPath("$.reviewStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.validationResults.calculationKind").value("SYNTHETIC_TEST_ONLY"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.actualReadiness.status").value("CALCULATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.baseline.processes[0].residual").value(0))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.scenarios[0].result.newQuantity").value(10))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.scenarios[1].result.newQuantity").value(50))
                .andExpect(jsonPath("$.validationResults.afterNetwork.scenarios[0].result.impactedEquipmentNumbers").value(
                        org.hamcrest.Matchers.hasItems("SYN-EQ-A", "SYN-EQ-C")))
                .andExpect(jsonPath("$.validationResults.comparison.impactedEquipmentAfter").value(
                        org.hamcrest.Matchers.hasItems("SYN-EQ-A", "SYN-EQ-C")))
                .andExpect(jsonPath("$.validationResults.comparison.processResidualDeltas[0].delta").value(0))
                .andExpect(jsonPath("$.validationResults.comparison.externalBalanceDeltas[0].externalInflowBefore").value(100))
                .andExpect(jsonPath("$.validationResults.comparison.externalBalanceDeltas[0].externalOutflowAfter").value(100));
        TestTransaction.flagForRollback();
    }

    @Test
    void proposalWhatIfExportIsScopedTestOnlyWellFormedAndUsesReadableBlackMalgunGothicStyles() throws Exception {
        Login login = login();
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(proposalRequest("PIPE_CHANGE"))))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(syntheticBeforeAfterNetworks())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validationResults.officialReadiness").value("CALCULATION_UNAVAILABLE"));

        MvcResult downloaded = mvc.perform(get(API + "/{proposalId}/what-if/export", proposalId).session(login.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Test-Only", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Official-Baseline-Modified", "false"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        byte[] bytes = downloaded.getResponse().getContentAsByteArray();
        java.nio.file.Path apiArtifact = java.nio.file.Path.of("target/test-artifacts/proposal-export-api-TEST_ONLY.xlsx");
        java.nio.file.Files.createDirectories(apiArtifact.getParent());
        java.nio.file.Files.write(apiArtifact, bytes);
        assertThat(bytes).isNotEmpty();
        assertThat(downloaded.getResponse().getHeader("X-Output-SHA256")).isEqualTo(sha256(bytes));
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                zip.transferTo(java.io.OutputStream.nullOutputStream());
                zip.closeEntry();
            }
        }
        assertThat(entries).isGreaterThan(5);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("제안 요약");
            assertThat(workbook.getSheet("변경 연결")).isNotNull();
            assertThat(workbook.getSheet("공정 수지")).isNotNull();
            assertThat(workbook.getSheet("잔차 비교")).isNotNull();
            assertThat(workbook.getSheet("영향·검토")).isNotNull();
            String content = workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()
                    + " " + workbook.getSheetAt(0).getRow(9).getCell(1).getStringCellValue()
                    + " " + workbook.getSheet("변경 연결").getRow(1).getCell(1).getStringCellValue();
            assertThat(content).contains("TEST_ONLY", "CALCULATION_UNAVAILABLE", "SYN-A-B");
            assertThat(workbook.getSheet("변경 연결").getLastRowNum()).isGreaterThan(0);
            assertThat(workbook.getSheet("연결망 전후").getLastRowNum()).isGreaterThan(0);
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                var font = workbook.getFontAt(cell.getCellStyle().getFontIndex());
                assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            }
        }

        mvc.perform(post(API + "/{proposalId}/notes", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("note", "Review note after saved calculation", "evidence", Map.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestEventType").value("REVIEW_NOTE"));
        mvc.perform(get(API + "/{proposalId}/what-if/export", proposalId).session(login.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Test-Only", "true"));

        MockHttpSession anotherSession = new MockHttpSession();
        anotherSession.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "different-operator");
        anotherSession.setAttribute("operator.csrf", "different-csrf-token");
        mvc.perform(get(API + "/{proposalId}/what-if/export", proposalId).session(anotherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get(API + "/{proposalId}/what-if/export", proposalId)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/{proposalId}/what-if/export", UUID.randomUUID()).session(login.session()))
                .andExpect(status().isNotFound());
        TestTransaction.flagForRollback();
    }

    @Test
    void flowChangeProposalComparesTwentyToTenAndFiftyScenariosAndRejectsBadBasis() throws Exception {
        Login login = login();
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(proposalRequest("FLOW_CHANGE"))))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        Map<String, Object> comparison = syntheticFlowChangeNetworks();
        MvcResult result = mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(comparison)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATED_SYNTHETIC"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.scenarios[0].result.newQuantity").value(10))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.scenarios[1].result.newQuantity").value(50))
                .andExpect(jsonPath("$.validationResults.afterNetwork.baseline.edges[?(@.connectionId=='SYN-A-B')].quantity").value(
                        org.hamcrest.Matchers.contains(10)))
                .andExpect(jsonPath("$.validationResults.afterNetwork.actualReadiness.status").value("CALCULATION_UNAVAILABLE"))
                .andReturn();
        String flowProposalId = mapper.readTree(result.getResponse().getContentAsByteArray()).path("proposalId").asText();
        MvcResult flowWorkbook = mvc.perform(get(API + "/{proposalId}/what-if/export", flowProposalId).session(login.session()))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(flowWorkbook.getResponse().getContentAsByteArray()))) {
            assertThat(exported.getSheet("변경 연결").getRow(1).getCell(0).getStringCellValue()).isEqualTo("FLOW_CHANGED");
            assertThat(exported.getSheet("잔차 비교").getRow(0).getCell(0).getStringCellValue()).isEqualTo("공정");
            assertThat(exported.getSheet("외부 수지 비교")).isNotNull();
        }
        JsonNode body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("validationResults").path("comparison").path("processResidualDeltas").get(0).path("status").asText())
                .isEqualTo("SYNTHETIC_COMPARISON");
        assertThat(body.path("reviewStatus").asText()).isEqualTo("REVIEW_REQUIRED");

        @SuppressWarnings("unchecked")
        Map<String, Object> badPeriod = mapper.convertValue(comparison, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) badPeriod.get("afterNetwork");
        after.put("period", "2026-12-01/OTHER_PERIOD");
        mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(badPeriod)))
                .andExpect(status().isBadRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> badQuantity = mapper.convertValue(comparison, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) badQuantity.get("beforeNetwork");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> scenarios = (java.util.List<Map<String, Object>>) before.get("scenarios");
        scenarios.get(0).put("newQuantity", -1);
        mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(badQuantity)))
                .andExpect(status().isBadRequest());
        TestTransaction.flagForRollback();
    }

    @Test
    void deleteProposalComparesEquipmentPresenceAndRouteImpactBeforeAndAfter() throws Exception {
        Login login = login();
        Map<String, Object> deleteRequest = new java.util.LinkedHashMap<>(proposalRequest("DELETE"));
        deleteRequest.put("managementNo", "SYN-EQ-B");
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(deleteRequest)))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        MvcResult compared = mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(syntheticBeforeAfterNetworks())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATED_SYNTHETIC"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.afterNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.comparison.impactedEquipmentBefore").value(
                        org.hamcrest.Matchers.hasItems("SYN-EQ-A", "SYN-EQ-B")))
                .andExpect(jsonPath("$.validationResults.comparison.impactedEquipmentAfter").value(
                        org.hamcrest.Matchers.hasItems("SYN-EQ-A", "SYN-EQ-C")))
                .andReturn();
        assertThat(mapper.readTree(compared.getResponse().getContentAsByteArray()).path("reviewStatus").asText())
                .isEqualTo("REVIEW_REQUIRED");
        String deleteProposalId = mapper.readTree(compared.getResponse().getContentAsByteArray()).path("proposalId").asText();
        MvcResult deleteWorkbook = mvc.perform(get(API + "/{proposalId}/what-if/export", deleteProposalId).session(login.session()))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(deleteWorkbook.getResponse().getContentAsByteArray()))) {
            var changes = exported.getSheet("변경 연결");
            assertThat(java.util.stream.StreamSupport.stream(changes.spliterator(), false)
                    .flatMap(row -> java.util.stream.StreamSupport.stream(row.spliterator(), false))
                    .map(cell -> cell.toString()).collect(java.util.stream.Collectors.joining(" ")))
                    .contains("DELETED", "SYN-A-B", "SYN-A-C");
            assertThat(exported.getSheet("영향·검토").getLastRowNum()).isGreaterThan(3);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> invalid = mapper.convertValue(syntheticBeforeAfterNetworks(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> invalidAfter = (Map<String, Object>) invalid.get("afterNetwork");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> afterNodes = (java.util.List<Map<String, Object>>) invalidAfter.get("nodes");
        afterNodes.add(Map.of("nodeId", "DELETE-B-REMAINS", "equipmentNumber", "SYN-EQ-B",
                "processCode", "SYNTHETIC-P", "equipmentType", "PROCESS_EQUIPMENT", "nodeType", "PROCESS_EQUIPMENT",
                "label", "Synthetic B remains after DELETE", "sourceDocument", "TEST_ONLY", "sourceSheet", "TEST_ONLY"));
        mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(invalid)))
                .andExpect(status().isConflict());
        TestTransaction.flagForRollback();
    }

    @Test
    void branchingAndMergeWhatIfBalancesBothSidesOfTheBeforeAfterComparison() throws Exception {
        Login login = login();
        Map<String, Object> flowRequest = new java.util.LinkedHashMap<>(proposalRequest("FLOW_CHANGE"));
        flowRequest.put("beforeValues", flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 40, "SYN-A-B", "BRANCH"));
        flowRequest.put("afterValues", flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 30, "SYN-A-B", "BRANCH"));
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(flowRequest)))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        MvcResult compared = mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(syntheticBranchMergeNetworks())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATED_SYNTHETIC"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.afterNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.comparison.externalBalanceDeltas[0].externalInflowBefore").value(100))
                .andExpect(jsonPath("$.validationResults.comparison.externalBalanceDeltas[0].externalOutflowAfter").value(100))
                .andReturn();
        JsonNode result = mapper.readTree(compared.getResponse().getContentAsByteArray());
        assertThat(result.path("validationResults").path("beforeNetwork").path("baseline").path("nodes"))
                .allSatisfy(node -> assertThat(node.path("residual").decimalValue()).isEqualByComparingTo("0"));
        assertThat(result.path("validationResults").path("afterNetwork").path("baseline").path("nodes"))
                .allSatisfy(node -> assertThat(node.path("residual").decimalValue()).isEqualByComparingTo("0"));
        MvcResult branchWorkbook = mvc.perform(get(API + "/{proposalId}/what-if/export", proposalId).session(login.session()))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(branchWorkbook.getResponse().getContentAsByteArray()))) {
            assertThat(exported.getSheet("연결망 전후").getLastRowNum()).isGreaterThan(10);
            assertThat(exported.getSheet("전체 외부 수지").getLastRowNum()).isGreaterThan(0);
            assertThat(exported.getSheet("외부 수지 비교")).isNotNull();
        }
        TestTransaction.flagForRollback();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> syntheticBranchMergeNetworks() throws Exception {
        Map<String, Object> before = branchMergeNetwork("before", 40);
        Map<String, Object> after = branchMergeNetwork("after", 30);
        return Map.of("beforeNetwork", before, "afterNetwork", after);
    }

    private Map<String, Object> branchMergeNetwork(String phase, int branchFlow) {
        String period = "2026-11-01/TEST_PERIOD";
        java.util.List<Map<String, Object>> nodes = java.util.List.of(
                networkNode("IN", null, "EXTERNAL_SOURCE", "Synthetic feed"),
                networkNode("A", "SYN-EQ-A", "PROCESS_EQUIPMENT", "Synthetic branch source"),
                networkNode("B", "SYN-EQ-B", "PROCESS_EQUIPMENT", "Synthetic branch leg B"),
                networkNode("C", "SYN-EQ-C", "PROCESS_EQUIPMENT", "Synthetic branch leg C"),
                networkNode("D", "SYN-EQ-D", "PROCESS_EQUIPMENT", "Synthetic merge receiver"),
                networkNode("OUT", null, "EXTERNAL_SINK", "Synthetic outlet"));
        java.util.List<Map<String, Object>> edges = java.util.List.of(
                networkEdge("IN-A", "IN", "A", "", "SYNTHETIC-P", 100, true, "EXTERNAL_INFLOW", period),
                networkEdge("SYN-A-B", "A", "B", "SYNTHETIC-P", "SYNTHETIC-P", branchFlow, true, "BRANCH", period),
                networkEdge("A-C", "A", "C", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "BRANCH", period),
                networkEdge("B-D", "B", "D", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "SERIES", period),
                networkEdge("C-D", "C", "D", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "MERGE", period),
                networkEdge("D-OUT", "D", "OUT", "SYNTHETIC-P", "", 100, true, "EXTERNAL_OUTFLOW", period));
        return Map.ofEntries(Map.entry("mode", "TEST_ONLY"), Map.entry("simulationId", "branch-merge-" + phase),
                Map.entry("scenarioName", "Synthetic branch and merge " + phase), Map.entry("processCode", "SYNTHETIC-P"),
                Map.entry("sourceEquipment", "SYN-EQ-A"), Map.entry("targetEquipment", "SYN-EQ-B"),
                Map.entry("materialClass", "WASTEWATER"), Map.entry("materialType", "synthetic water"),
                Map.entry("unit", "ton/day"), Map.entry("period", period), Map.entry("changedConnectionId", "SYN-A-B"),
                Map.entry("baseQuantity", branchFlow), Map.entry("nodes", nodes), Map.entry("edges", edges),
                Map.entry("processTerms", java.util.List.of()),
                Map.entry("scenarios", java.util.List.of(Map.of("scenarioId", phase + "-branch", "description", "branch " + branchFlow,
                        "newQuantity", branchFlow))));
    }

    private Map<String, Object> networkNode(String id, String equipment, String type, String label) {
        return Map.of("nodeId", id, "equipmentNumber", equipment == null ? "" : equipment,
                "processCode", "EXTERNAL_SOURCE".equals(type) || "EXTERNAL_SINK".equals(type) ? "" : "SYNTHETIC-P",
                "equipmentType", "PROCESS_EQUIPMENT".equals(type) ? type : "EXTERNAL_BOUNDARY",
                "nodeType", type, "label", label, "sourceDocument", "TEST_ONLY", "sourceSheet", "TEST_ONLY");
    }

    private Map<String, Object> networkEdge(String id, String source, String target, String sourceProcess,
                                            String targetProcess, Integer quantity, boolean fixed,
                                            String connectionType, String period) {
        Map<String, Object> edge = new java.util.LinkedHashMap<>();
        edge.put("connectionId", id); edge.put("sourceNodeId", source); edge.put("targetNodeId", target);
        edge.put("sourceProcess", sourceProcess); edge.put("targetProcess", targetProcess);
        edge.put("materialClass", "WASTEWATER"); edge.put("materialType", "synthetic water");
        edge.put("unit", "ton/day"); edge.put("quantity", quantity); edge.put("connectionType", connectionType);
        edge.put("quantityBasis", fixed ? "USER_INPUT" : "CALCULATED"); edge.put("fixed", fixed);
        edge.put("evidence", "Synthetic TEST_ONLY branch/merge fixture"); edge.put("period", period);
        return edge;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> syntheticFlowChangeNetworks() throws Exception {
        Map<String, Object> pair = syntheticBeforeAfterNetworks();
        Map<String, Object> before = (Map<String, Object>) pair.get("beforeNetwork");
        Map<String, Object> after = mapper.readValue(mapper.writeValueAsBytes(before),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        after.put("simulationId", "proposal-flow-after-synthetic");
        after.put("scenarioName", "Synthetic flow changes from 20 to 10");
        after.put("baseQuantity", 10);
        for (Map<String, Object> edge : (java.util.List<Map<String, Object>>) after.get("edges")) {
            if ("SYN-A-B".equals(edge.get("connectionId"))) edge.put("quantity", 10);
        }
        return Map.of("beforeNetwork", before, "afterNetwork", after);
    }

    @Test
    void internalRecycleIsSolvedWhenConstrainedAndRemainsUnavailableWhenUnderdetermined() throws Exception {
        Login login = login();
        Map<String, Object> request = new java.util.LinkedHashMap<>(proposalRequest("FLOW_CHANGE"));
        request.put("beforeValues", flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 10, "SYN-A-B", "INTERNAL_RECYCLE"));
        request.put("afterValues", flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 20, "SYN-A-B", "INTERNAL_RECYCLE"));
        MvcResult created = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("proposalId").asText();
        MvcResult solved = mvc.perform(post(API + "/{proposalId}/what-if", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(syntheticRecycleComparison(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATED_SYNTHETIC"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.afterNetwork.baseline.status").value("CALCULATED"))
                .andExpect(jsonPath("$.validationResults.afterNetwork.baseline.edges[?(@.connectionId=='SYN-B-A')].quantity").value(
                        org.hamcrest.Matchers.contains(20.0)))
                .andReturn();
        assertThat(mapper.readTree(solved.getResponse().getContentAsByteArray())
                .path("validationResults").path("afterNetwork").path("actualReadiness").path("status").asText())
                .isEqualTo("CALCULATION_UNAVAILABLE");

        MvcResult underdeterminedProposal = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        String underdeterminedId = mapper.readTree(underdeterminedProposal.getResponse().getContentAsByteArray()).path("proposalId").asText();
        mvc.perform(post(API + "/{proposalId}/what-if", underdeterminedId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(syntheticRecycleComparison(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.validationResults.beforeNetwork.baseline.status").value("UNDERDETERMINED"))
                .andExpect(jsonPath("$.validationResults.officialReadiness").value("CALCULATION_UNAVAILABLE"));
        String underdeterminedProposalId = mapper.readTree(underdeterminedProposal.getResponse().getContentAsByteArray())
                .path("proposalId").asText();
        MvcResult unavailableWorkbook = mvc.perform(get(API + "/{proposalId}/what-if/export", underdeterminedProposalId)
                        .session(login.session()))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(unavailableWorkbook.getResponse().getContentAsByteArray()))) {
            String reviewText = java.util.stream.StreamSupport.stream(exported.getSheet("영향·검토").spliterator(), false)
                    .flatMap(row -> java.util.stream.StreamSupport.stream(row.spliterator(), false))
                    .map(cell -> cell.toString()).collect(java.util.stream.Collectors.joining(" "));
            assertThat(reviewText).contains("UNDERDETERMINED", "CALCULATION_UNAVAILABLE", "TEST_ONLY");
        }
        TestTransaction.flagForRollback();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> syntheticRecycleComparison(boolean underdetermined) throws Exception {
        Map<String, Object> before = recycleNetwork("recycle-before", 10, underdetermined);
        Map<String, Object> after = recycleNetwork("recycle-after", 20, underdetermined);
        return Map.of("beforeNetwork", before, "afterNetwork", after);
    }

    private Map<String, Object> recycleNetwork(String name, int recirculation, boolean underdetermined) {
        String period = "2026-11-01/TEST_PERIOD";
        java.util.List<Map<String, Object>> nodes = new java.util.ArrayList<>(java.util.List.of(
                networkNode("IN", null, "EXTERNAL_SOURCE", "Synthetic source"),
                networkNode("A", "SYN-EQ-A", "PROCESS_EQUIPMENT", "Synthetic recycle A"),
                networkNode("B", "SYN-EQ-B", "PROCESS_EQUIPMENT", "Synthetic recycle B"),
                networkNode("OUT", null, "EXTERNAL_SINK", "Synthetic outlet")));
        java.util.List<Map<String, Object>> edges = new java.util.ArrayList<>(java.util.List.of(
                networkEdge("IN-A", "IN", "A", "", "SYNTHETIC-P", 100, true, "EXTERNAL_INFLOW", period),
                networkEdge("SYN-A-B", "A", "B", "SYNTHETIC-P", "SYNTHETIC-P", recirculation, true, "INTERNAL_RECYCLE", period),
                networkEdge("SYN-B-A", "B", "A", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "INTERNAL_RECYCLE", period),
                networkEdge("A-OUT", "A", "OUT", "SYNTHETIC-P", "", 100, true, "EXTERNAL_OUTFLOW", period)));
        if (underdetermined) {
            nodes.add(networkNode("C", "SYN-EQ-C", "PROCESS_EQUIPMENT", "Unresolved cycle C"));
            nodes.add(networkNode("D", "SYN-EQ-D", "PROCESS_EQUIPMENT", "Unresolved cycle D"));
            edges.add(networkEdge("C-D", "C", "D", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "INTERNAL_RECYCLE", period));
            edges.add(networkEdge("D-C", "D", "C", "SYNTHETIC-P", "SYNTHETIC-P", null, false, "INTERNAL_RECYCLE", period));
        }
        return Map.ofEntries(Map.entry("mode", "TEST_ONLY"), Map.entry("simulationId", name),
                Map.entry("scenarioName", name), Map.entry("processCode", "SYNTHETIC-P"),
                Map.entry("sourceEquipment", "SYN-EQ-A"), Map.entry("targetEquipment", "SYN-EQ-B"),
                Map.entry("materialClass", "WASTEWATER"), Map.entry("materialType", "synthetic water"),
                Map.entry("unit", "ton/day"), Map.entry("period", period), Map.entry("changedConnectionId", "SYN-A-B"),
                Map.entry("baseQuantity", recirculation), Map.entry("nodes", nodes), Map.entry("edges", edges),
                Map.entry("processTerms", java.util.List.of()),
                Map.entry("scenarios", java.util.List.of(Map.of("scenarioId", name + "-unchanged",
                        "description", "Keep the selected synthetic recycle flow", "newQuantity", recirculation))));
    }

    @Test
    void testOnlyOutputEventIsAppendOnlyAndMustMatchCurrentWorkingCopy() throws Exception {
        Login login = login();
        MvcResult saved = mvc.perform(post(API).session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(proposalRequest("MODIFY"))))
                .andExpect(status().isOk()).andReturn();
        String proposalId = mapper.readTree(saved.getResponse().getContentAsByteArray()).path("proposalId").asText();
        Map<String, Object> output = Map.of("workingDocumentId", workingDocumentId, "processCode", "SYNTHETIC-P",
                "sheetName", "SYNTHETIC-01", "workingSha256", workingSha,
                "outputSha256", sha256("synthetic output".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "validationResults", Map.of("fixture", "synthetic", "zipIntegrity", true));
        mvc.perform(post(API + "/{proposalId}/test-only-output", proposalId).session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(output)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.latestEventType").value("TEST_ONLY_OUTPUT"))
                .andExpect(jsonPath("$.calculationStatus").value("CALCULATION_UNAVAILABLE"));
        TestTransaction.flagForRollback();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> syntheticBeforeAfterNetworks() throws Exception {
        String json = """
                {
                  "mode":"TEST_ONLY","simulationId":"proposal-flow-test-20-10-50",
                  "scenarioName":"Synthetic pipe change comparison","processCode":"SYNTHETIC-P",
                  "sourceEquipment":"SYN-EQ-A","targetEquipment":"SYN-EQ-B",
                  "materialClass":"WASTEWATER","materialType":"synthetic water","unit":"ton/day",
                  "period":"2026-11-01/TEST_PERIOD","changedConnectionId":"SYN-A-B","baseQuantity":20,
                  "nodes":[
                    {"nodeId":"SYN-IN","processCode":"SYNTHETIC-P","equipmentType":"EXTERNAL_BOUNDARY","nodeType":"EXTERNAL_SOURCE","label":"Synthetic source","sourceDocument":"TEST_ONLY","sourceSheet":"TEST_ONLY"},
                    {"nodeId":"A","equipmentNumber":"SYN-EQ-A","processCode":"SYNTHETIC-P","equipmentType":"PROCESS_EQUIPMENT","nodeType":"PROCESS_EQUIPMENT","label":"Synthetic A","sourceDocument":"TEST_ONLY","sourceSheet":"TEST_ONLY"},
                    {"nodeId":"B","equipmentNumber":"SYN-EQ-B","processCode":"SYNTHETIC-P","equipmentType":"PROCESS_EQUIPMENT","nodeType":"PROCESS_EQUIPMENT","label":"Synthetic B","sourceDocument":"TEST_ONLY","sourceSheet":"TEST_ONLY"},
                    {"nodeId":"SYN-OUT","processCode":"SYNTHETIC-P","equipmentType":"EXTERNAL_BOUNDARY","nodeType":"EXTERNAL_SINK","label":"Synthetic sink","sourceDocument":"TEST_ONLY","sourceSheet":"TEST_ONLY"}
                  ],
                  "edges":[
                    {"connectionId":"SYN-IN-A","sourceNodeId":"SYN-IN","targetNodeId":"A","sourceProcess":"SYNTHETIC-P","targetProcess":"SYNTHETIC-P","materialClass":"WASTEWATER","materialType":"synthetic water","unit":"ton/day","quantity":100,"connectionType":"EXTERNAL_INFLOW","quantityBasis":"USER_INPUT","fixed":true,"evidence":"Synthetic fixture","period":"2026-11-01/TEST_PERIOD"},
                    {"connectionId":"SYN-A-B","sourceNodeId":"A","targetNodeId":"B","sourceProcess":"SYNTHETIC-P","targetProcess":"SYNTHETIC-P","materialClass":"WASTEWATER","materialType":"synthetic water","unit":"ton/day","quantity":20,"connectionType":"SERIES","quantityBasis":"USER_INPUT","fixed":true,"evidence":"Synthetic fixture","period":"2026-11-01/TEST_PERIOD"},
                    {"connectionId":"SYN-A-OUT","sourceNodeId":"A","targetNodeId":"SYN-OUT","sourceProcess":"SYNTHETIC-P","targetProcess":"SYNTHETIC-P","materialClass":"WASTEWATER","materialType":"synthetic water","unit":"ton/day","connectionType":"EXTERNAL_OUTFLOW","quantity":null,"quantityBasis":"CALCULATED","fixed":false,"evidence":"Synthetic balance","period":"2026-11-01/TEST_PERIOD"},
                    {"connectionId":"SYN-B-OUT","sourceNodeId":"B","targetNodeId":"SYN-OUT","sourceProcess":"SYNTHETIC-P","targetProcess":"SYNTHETIC-P","materialClass":"WASTEWATER","materialType":"synthetic water","unit":"ton/day","quantity":null,"connectionType":"EXTERNAL_OUTFLOW","quantityBasis":"CALCULATED","fixed":false,"evidence":"Synthetic balance","period":"2026-11-01/TEST_PERIOD"}
                  ],
                  "processTerms":[],
                  "scenarios":[{"scenarioId":"FLOW-10","description":"20 to 10","newQuantity":10},{"scenarioId":"FLOW-50","description":"20 to 50","newQuantity":50}]
                }
                """;
        Map<String, Object> before = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        Map<String, Object> after = mapper.readValue(mapper.writeValueAsBytes(before),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        after.put("simulationId", "proposal-pipe-after-synthetic");
        after.put("scenarioName", "Synthetic destination changed to C");
        after.put("targetEquipment", "SYN-EQ-C");
        after.put("changedConnectionId", "SYN-A-C");
        for (Map<String, Object> node : (java.util.List<Map<String, Object>>) after.get("nodes")) {
            if ("B".equals(node.get("nodeId"))) {
                node.put("nodeId", "C");
                node.put("equipmentNumber", "SYN-EQ-C");
                node.put("label", "Synthetic C");
            }
        }
        for (Map<String, Object> edge : (java.util.List<Map<String, Object>>) after.get("edges")) {
            if ("SYN-A-B".equals(edge.get("connectionId"))) {
                edge.put("connectionId", "SYN-A-C");
                edge.put("targetNodeId", "C");
            } else if ("SYN-B-OUT".equals(edge.get("connectionId"))) {
                edge.put("connectionId", "SYN-C-OUT");
                edge.put("sourceNodeId", "C");
            }
        }
        return Map.of("beforeNetwork", before, "afterNetwork", after);
    }

    private Map<String, Object> proposalRequest(String changeType) {
        Map<String, Object> before;
        Map<String, Object> after;
        if ("PIPE_CHANGE".equals(changeType)) {
            before = pipeSnapshot("SYN-EQ-B", "SYN-A-B");
            after = pipeSnapshot("SYN-EQ-C", "SYN-A-C");
        } else if ("FLOW_CHANGE".equals(changeType)) {
            before = flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 20);
            after = flowSnapshot("SYN-EQ-A", "SYN-EQ-B", 10);
        } else {
            before = Map.of("value", "before");
            after = Map.of("value", "after");
        }
        return Map.of("workingDocumentId", workingDocumentId, "processCode", "SYNTHETIC-P",
                "sheetName", "SYNTHETIC-01", "changeType", changeType,
                "managementNo", "SYNTHETIC-EQ-1", "workingSha256", workingSha,
                "beforeValues", before, "afterValues", after,
                "rationale", "Synthetic test proposal for " + changeType,
                "evidence", Map.of("source", "SYNTHETIC_FIXTURE", "status", "UNVERIFIED"));
    }

    private Map<String, Object> flowSnapshot(String source, String target, int quantity) {
        return flowSnapshot(source, target, quantity, "SYN-A-B", "SERIES");
    }

    private Map<String, Object> flowSnapshot(String source, String target, int quantity,
                                             String connectionId, String connectionType) {
        return Map.ofEntries(Map.entry("sourceProcess", "SYNTHETIC-P"), Map.entry("sourceEquipment", source),
                Map.entry("targetProcess", "SYNTHETIC-P"), Map.entry("targetEquipment", target),
                Map.entry("connectionId", connectionId), Map.entry("connectionType", connectionType),
                Map.entry("materialClass", "WASTEWATER"), Map.entry("materialType", "synthetic water"),
                Map.entry("quantity", quantity), Map.entry("unit", "ton/day"),
                Map.entry("period", "2026-11-01/TEST_PERIOD"), Map.entry("branchInfo", "synthetic no branch"),
                Map.entry("recycleInfo", "none"));
    }

    private Map<String, Object> pipeSnapshot(String targetEquipment, String connectionId) {
        return pipeSnapshot(targetEquipment, connectionId, "SERIES");
    }

    private Map<String, Object> pipeSnapshot(String targetEquipment, String connectionId, String connectionType) {
        return Map.ofEntries(Map.entry("sourceProcess", "SYNTHETIC-P"), Map.entry("sourceEquipment", "SYN-EQ-A"),
                Map.entry("targetProcess", "SYNTHETIC-P"), Map.entry("targetEquipment", targetEquipment),
                Map.entry("connectionId", connectionId), Map.entry("connectionType", connectionType),
                Map.entry("materialClass", "WASTEWATER"), Map.entry("materialType", "synthetic water"),
                Map.entry("unit", "ton/day"), Map.entry("period", "2026-11-01/TEST_PERIOD"));
    }

    private Login login() throws Exception {
        MvcResult response = mvc.perform(post("/api/session/login").header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", USER, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(response.getResponse().getContentAsByteArray());
        return new Login((MockHttpSession) response.getRequest().getSession(false), body.path("csrfToken").asText());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required isolated-test setting is missing: " + key);
        return value;
    }

    private record Login(MockHttpSession session, String csrf) { }
}
