package com.example.processautomation.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.api.ApiAccessGuard;
import com.example.processautomation.api.SelectiveEditExecutionPlanController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SelectiveEditExecutionPlanIntegrationTest {
    @Autowired private SelectiveEditExecutionPlanService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new SelectiveEditExecutionPlanController(new ApiAccessGuard("test-token"), service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void run173ReusesPersistedCandidatesAndOnlyPlan5SelectsPw0105() throws Exception {
        long equipmentBefore = count("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id=1");
        long confirmedBefore = count("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'");
        long reviewBefore = count("SELECT COUNT(*) FROM process_relation WHERE relation_status='REVIEW_REQUIRED'");
        String masterHashBefore = jdbc.queryForObject(
                "SELECT source_master_sha256 FROM baseline_release WHERE id=1", String.class);

        Map<String, Object> reviewOnly = service.create(173, new SelectiveEditExecutionPlanService.Request(
                List.of(173L), List.of()));
        assertThat(reviewOnly.get("changeRunId")).isEqualTo(173L);
        assertThat(reviewOnly.get("changeItemCount")).isEqualTo(1);
        assertThat(reviewOnly.get("affectedDiagramCandidateCount")).isEqualTo(6);
        assertThat(reviewOnly.get("affectedFileCandidateCount")).isEqualTo(1);
        assertThat(reviewOnly.get("officialProcessWorkbookCount")).isEqualTo(15);
        assertThat(reviewOnly.get("officialProcessSheetCount")).isEqualTo(62);
        assertThat(reviewOnly.get("outputTargetFileCount")).isEqualTo(0);
        assertThat(reviewOnly.get("executionPlanReady")).isEqualTo(false);
        assertThat(reviewOnly.get("fileGenerationStarted")).isEqualTo(false);
        assertThat(reviewOnly.get("drawingMlReanalysisPerformed")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) reviewOnly.get("candidateFileGroups");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("candidateSheets"))
                .isEqualTo(List.of("PW-01-01", "PW-01-02", "PW-01-03", "PW-01-04", "PW-01-05", "PW-01-06"));
        assertThat(candidates.get(0).get("includedInOutput")).isEqualTo(false);
        assertThat(reviewOnly.get("unaffectedFilesExcluded")).asList().hasSize(14);

        Map<String, Object> blockedPlan = service.create(173, new SelectiveEditExecutionPlanService.Request(
                List.of(173L), List.of(2L)));
        assertThat(blockedPlan.get("outputTargetFileCount")).isEqualTo(0);
        assertThat(blockedPlan.get("executionPlanReady")).isEqualTo(false);
        assertThat(blockedPlan.get("invalidOrStalePlanReferences")).asList().hasSize(1);

        long selectedStarted = System.nanoTime();
        Map<String, Object> selected = service.create(173, new SelectiveEditExecutionPlanService.Request(
                List.of(173L), List.of(5L)));
        long selectedElapsedMs = (System.nanoTime() - selectedStarted) / 1_000_000;
        System.out.println("RUN173_SELECTIVE_PLAN_TOTAL_MS=" + selectedElapsedMs
                + " TIMING_MS=" + selected.get("timingMs"));
        assertThat(selected.get("outputTargetFileCount")).isEqualTo(1);
        assertThat(selected.get("executionPlanReady")).isEqualTo(true);
        assertThat(selected.get("fileGenerationStarted")).isEqualTo(false);
        assertThat(selected.get("zipGenerationStarted")).isEqualTo(false);
        assertThat(selected.get("officialEditAllowed")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) selected.get("outputTargets");
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).get("existingEditPlanIds")).isEqualTo(List.of(5L));
        assertThat(targets.get(0).get("outputSheets")).isEqualTo(List.of("PW-01-05"));
        assertThat(targets.get(0).get("expectedOutputFilename")).isEqualTo("TEST_ONLY-PW-01-CHANGE-RUN-173.xlsx");
        assertThat(targets.get(0).get("relationStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(targets.get(0).get("priorPlanOutputSha256"))
                .isEqualTo(List.of("e3fba34ec6f272a2067fb5b278ebf84038bf5694f5d2aeed8393456413a06837"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemPlans = (List<Map<String, Object>>) selected.get("itemPlans");
        assertThat(itemPlans).hasSize(1);
        assertThat(itemPlans.get(0)).containsEntry("classification", "A_TEST_ONLY_PLAN_READY")
                .containsEntry("testOnlyPlanId", 5L)
                .containsEntry("officialEditAllowed", false)
                .containsEntry("testOnlyArtifactEligible", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) selected.get("additionalRelationshipReview");
        assertThat(relationships).hasSize(1);
        assertThat(relationships.get(0)).containsEntry("relationStatus", "REVIEW_REQUIRED")
                .containsEntry("approved", false);

        long apiStarted = System.nanoTime();
        Map<String, Object> apiPlan = postPlan(173, List.of(173L), List.of(5L));
        long apiWallMs = (System.nanoTime() - apiStarted) / 1_000_000;
        @SuppressWarnings("unchecked")
        Map<String, Object> apiTimings = (Map<String, Object>) apiPlan.get("timingMs");
        System.out.println("RUN173_HTTP_API_TOTAL_MS=" + apiWallMs + " SERVICE_STAGE_MS=" + apiTimings);
        assertThat(apiPlan.get("outputTargetFileCount")).isEqualTo(1);
        assertThat(apiPlan.get("officialEditAllowed")).isEqualTo(false);
        assertThat(apiPlan.get("fileGenerationStarted")).isEqualTo(false);
        assertThat(apiPlan.get("zipGenerationStarted")).isEqualTo(false);

        assertThat(count("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id=1")).isEqualTo(equipmentBefore);
        assertThat(count("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'")).isEqualTo(confirmedBefore);
        assertThat(count("SELECT COUNT(*) FROM process_relation WHERE relation_status='REVIEW_REQUIRED'")).isEqualTo(reviewBefore);
        assertThat(jdbc.queryForObject("SELECT source_master_sha256 FROM baseline_release WHERE id=1", String.class))
                .isEqualTo(masterHashBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagram_edit_plan WHERE id=5", Long.class)).isEqualTo(1L);
    }

    @Test
    void groupsMultipleTestOnlyItemsByFileAndRejectsCrossRunOrDuplicatePlans() throws Exception {
        long equipmentBefore = count("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id=1");
        long confirmedBefore = count("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'");
        long reviewBefore = count("SELECT COUNT(*) FROM process_relation WHERE relation_status='REVIEW_REQUIRED'");
        String masterHashBefore = jdbc.queryForObject(
                "SELECT source_master_sha256 FROM baseline_release WHERE id=1", String.class);

        long runOne = insertTestOnlyRun("STEP6-3A-MULTI-RUN-ONE.xlsx");
        long runTwo = insertTestOnlyRun("STEP6-3A-MULTI-RUN-TWO.xlsx");
        Map<String, Object> pwDocument = jdbc.queryForMap("""
                SELECT id, process_code, original_filename, sha256 FROM baseline_document
                WHERE baseline_release_id=1 AND document_role='PROCESS' AND process_code='PW-01'
                """);
        Map<String, Object> pDocument = jdbc.queryForMap("""
                SELECT id, process_code, original_filename, sha256 FROM baseline_document
                WHERE baseline_release_id=1 AND document_role='PROCESS' AND process_code='P-01'
                """);

        long pwItemOne = insertChangeItem(runOne, "TEST-PLAN-PW-ONE",
                List.of(candidate(pwDocument, "PW-01-05")));
        long pwItemTwo = insertChangeItem(runOne, "TEST-PLAN-PW-TWO",
                List.of(candidate(pwDocument, "PW-01-06")));
        long pItem = insertChangeItem(runOne, "TEST-PLAN-P-ONE",
                List.of(candidate(pDocument, "P-01-01(TOP)")));
        long reviewItem = insertChangeItem(runOne, "TEST-PLAN-REVIEW",
                List.of(candidate(pwDocument, "PW-01-04")));
        long unresolvedItem = insertChangeItem(runOne, "TEST-PLAN-UNRESOLVED",
                List.of(Map.of("processCode", "PW-02", "processWorkbook", "NOT-IN-V1.xlsx",
                        "sheetName", "PW-02-01", "mappingStatus", "EXACT_IDENTIFIER_CANDIDATE_REVIEW",
                        "matchMethod", "TEST_FIXTURE", "evidence", "unregistered candidate",
                        "automaticMappingSucceeded", false)));

        long pwPlanOne = insertTestOnlyPlan(runOne, pwItemOne, pwDocument, "PW-01-05");
        long pwPlanTwo = insertTestOnlyPlan(runOne, pwItemTwo, pwDocument, "PW-01-06");
        long pPlan = insertTestOnlyPlan(runOne, pItem, pDocument, "P-01-01(TOP)");
        long foreignItem = insertChangeItem(runTwo, "TEST-PLAN-FOREIGN",
                List.of(candidate(pwDocument, "PW-01-05")));
        long foreignPlan = insertTestOnlyPlan(runTwo, foreignItem, pwDocument, "PW-01-05");

        var request = new SelectiveEditExecutionPlanService.Request(
                List.of(pwItemOne, pwItemTwo, pItem, reviewItem, unresolvedItem),
                List.of(pwPlanOne, pwPlanTwo, pPlan));
        Map<String, Object> plan = service.create(runOne, request);
        assertThat(plan.get("changeRunId")).isEqualTo(runOne);
        assertThat(plan.get("changeItemCount")).isEqualTo(5);
        assertThat(plan.get("outputTargetFileCount")).isEqualTo(2);
        assertThat(plan.get("fileGenerationStarted")).isEqualTo(false);
        assertThat(plan.get("zipGenerationStarted")).isEqualTo(false);
        assertThat(plan.get("officialEditAllowed")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) plan.get("outputTargets");
        assertThat(targets).hasSize(2);
        Map<String, Object> pwTarget = targets.stream()
                .filter(target -> "PW-01".equals(target.get("processCode"))).findFirst().orElseThrow();
        assertThat(pwTarget.get("existingEditPlanIds")).isEqualTo(List.of(pwPlanOne, pwPlanTwo));
        assertThat(pwTarget.get("changedEquipment")).isEqualTo(List.of("TEST-PLAN-PW-ONE", "TEST-PLAN-PW-TWO"));
        assertThat(pwTarget.get("outputSheets")).isEqualTo(List.of("PW-01-05", "PW-01-06"));
        assertThat(pwTarget.get("expectedOutputFilename")).isEqualTo("TEST_ONLY-PW-01-CHANGE-RUN-" + runOne + ".xlsx");
        Map<String, Object> pTarget = targets.stream()
                .filter(target -> "P-01".equals(target.get("processCode"))).findFirst().orElseThrow();
        assertThat(pTarget.get("existingEditPlanIds")).isEqualTo(List.of(pPlan));
        assertThat(pTarget.get("outputSheets")).isEqualTo(List.of("P-01-01(TOP)"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemPlans = (List<Map<String, Object>>) plan.get("itemPlans");
        Map<String, Object> reviewResult = itemPlans.stream()
                .filter(item -> reviewItem == ((Number) item.get("changeItemId")).longValue())
                .findFirst().orElseThrow();
        assertThat(reviewResult).containsEntry("classification", "B_REVIEW_REQUIRED")
                .containsEntry("testOnlyArtifactEligible", false);
        Map<String, Object> unresolvedResult = itemPlans.stream()
                .filter(item -> unresolvedItem == ((Number) item.get("changeItemId")).longValue())
                .findFirst().orElseThrow();
        assertThat(unresolvedResult).containsEntry("classification", "C_AUTOMATIC_EDIT_UNAVAILABLE")
                .containsEntry("testOnlyArtifactEligible", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateFiles = (List<Map<String, Object>>) plan.get("candidateFileGroups");
        Map<String, Object> missingCandidate = candidateFiles.stream()
                .filter(file -> "NOT-IN-V1.xlsx".equals(file.get("workbookFilename"))).findFirst().orElseThrow();
        assertThat(missingCandidate.get("includedInOutput")).isEqualTo(false);
        assertThat(plan.get("unaffectedFilesExcluded")).asList().hasSize(13);

        assertThatThrownBy(() -> service.create(runOne,
                new SelectiveEditExecutionPlanService.Request(List.of(foreignItem), List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to this changeRunId");
        assertThatThrownBy(() -> service.create(runOne,
                new SelectiveEditExecutionPlanService.Request(List.of(pwItemOne), List.of(foreignPlan))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to this run");
        assertThatThrownBy(() -> service.create(runOne,
                new SelectiveEditExecutionPlanService.Request(List.of(pwItemOne), List.of(pwPlanOne, pwPlanOne))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("positive and unique");
        assertThatThrownBy(() -> SelectiveExecutionPlanGrouping.byWorkbook(List.of(
                new SelectiveExecutionPlanGrouping.PlannedTarget(runOne, pwItemOne, "TEST-PLAN-PW-ONE", pwPlanOne,
                        12, "PW-01-05", "PW-01.xlsx", "a".repeat(64), "PW-01-05"),
                new SelectiveExecutionPlanGrouping.PlannedTarget(runTwo, foreignItem, "TEST-PLAN-FOREIGN", foreignPlan,
                        12, "PW-01-05", "PW-01.xlsx", "a".repeat(64), "PW-01-05"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot mix changeRunId");

        assertThat(count("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id=1")).isEqualTo(equipmentBefore);
        assertThat(count("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'")).isEqualTo(confirmedBefore);
        assertThat(count("SELECT COUNT(*) FROM process_relation WHERE relation_status='REVIEW_REQUIRED'")).isEqualTo(reviewBefore);
        assertThat(jdbc.queryForObject("SELECT source_master_sha256 FROM baseline_release WHERE id=1", String.class))
                .isEqualTo(masterHashBefore);
    }

    private long insertTestOnlyRun(String filename) {
        Map<String, Object> source = jdbc.queryForMap("""
                SELECT baseline_release_id, source_sha256, source_content FROM change_run WHERE id=173
                """);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_run(baseline_release_id, source_filename, source_sha256, source_content,
                        analysis_mode, total_records, added_count, removed_count, modified_count, moved_count,
                        validation_issue_count, diagram_mapping_status, automatic_mapping_succeeded,
                        affected_diagrams_json, review_required_json)
                    VALUES (?, ?, ?, ?, 'TEST_ONLY', 1337, 5, 0, 0, 0, 0, 'REVIEW_REQUIRED', FALSE, '[]', '[]')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ((Number) source.get("baseline_release_id")).longValue());
            statement.setString(2, filename);
            statement.setString(3, (String) source.get("source_sha256"));
            statement.setBytes(4, (byte[]) source.get("source_content"));
            return statement;
        };
        jdbc.update(creator, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertChangeItem(long runId, String managementNo, List<Map<String, Object>> candidates)
            throws Exception {
        String candidateJson = objectMapper.writeValueAsString(candidates);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_item(change_run_id, change_type, permit_management_no,
                        diagram_mapping_status, automatic_mapping_succeeded, affected_diagrams_json, review_required_json)
                    VALUES (?, 'ADDED', ?, 'REVIEW_REQUIRED', FALSE, ?, '[]')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, runId);
            statement.setString(2, managementNo);
            statement.setString(3, candidateJson);
            return statement;
        };
        jdbc.update(creator, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertTestOnlyPlan(long runId, long itemId, Map<String, Object> document, String sheetName)
            throws Exception {
        String processCode = (String) document.get("process_code");
        String filename = (String) document.get("original_filename");
        String jsonScenario = objectMapper.writeValueAsString(Map.of("enabled", true, "processCode", processCode,
                "workbookFilename", filename, "sheetName", sheetName,
                "predecessorManagementNo", "TEST-PREDECESSOR", "successorManagementNo", "TEST-SUCCESSOR"));
        String jsonPlan = objectMapper.writeValueAsString(Map.of(
                "changeType", "ADDED", "analysisMode", "TEST_ONLY", "relationStatus", "REVIEW_REQUIRED",
                "officialApprovalCreated", false,
                "target", Map.of("baselineDocumentId", document.get("id"), "processCode", processCode,
                        "workbookFilename", filename, "sheetName", sheetName),
                "insertion", Map.of("relationConfirmed", false)));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO diagram_edit_plan(change_run_id, change_item_id, baseline_document_id, analysis_mode,
                        plan_status, automatic_edit_allowed, relation_status, target_process_code,
                        target_sheet_name, target_filename, requested_scenario_json, plan_json, review_required_json)
                    VALUES (?, ?, ?, 'TEST_ONLY', 'TEST_ONLY_READY', TRUE, 'REVIEW_REQUIRED', ?, ?, ?, ?, ?, '[]')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, runId);
            statement.setLong(2, itemId);
            statement.setLong(3, ((Number) document.get("id")).longValue());
            statement.setString(4, processCode);
            statement.setString(5, sheetName);
            statement.setString(6, filename);
            statement.setString(7, jsonScenario);
            statement.setString(8, jsonPlan);
            return statement;
        };
        jdbc.update(creator, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Map<String, Object> candidate(Map<String, Object> document, String sheetName) {
        return Map.of("processCode", document.get("process_code"), "processWorkbook", document.get("original_filename"),
                "sheetName", sheetName, "mappingStatus", "PROCESS_CODE_ONLY_REVIEW", "matchMethod", "PROCESS_CODE",
                "evidence", "TEST_ONLY integration fixture; no approved relationship", "automaticMappingSucceeded", false);
    }

    private Map<String, Object> postPlan(long runId, List<Long> itemIds, List<Long> planIds) throws Exception {
        byte[] request = objectMapper.writeValueAsBytes(
                new SelectiveEditExecutionPlanService.Request(itemIds, planIds));
        var response = mockMvc.perform(post("/api/admin/change-runs/" + runId + "/selective-edit-plan")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json").content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readValue(response, new TypeReference<>() { });
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
