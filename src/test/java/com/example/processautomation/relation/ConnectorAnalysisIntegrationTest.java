package com.example.processautomation.relation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ConnectorAnalysisIntegrationTest {
    @Autowired
    private ConnectorAnalysisService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void analyzesPw01ConnectorsWithoutAutoConfirmingEquipmentRoutes() {
        Map<String, Object> summary = service.analyze("PW-01");

        assertThat(((Number) summary.get("connectorsScannedThisRun")).intValue()).isEqualTo(986);
        assertThat(((Number) summary.get("explicitShapeBindingsThisRun")).intValue()).isZero();
        assertThat(((Number) summary.get("connectorsWithArrowMarkerThisRun")).intValue()).isEqualTo(853);
        assertThat(((Number) summary.get("connectorsWithoutArrowMarkerThisRun")).intValue()).isEqualTo(133);
        assertThat(((Number) summary.get("automaticEquipmentRelationsConfirmedThisRun")).intValue()).isZero();
        assertThat(((Number) summary.get("reviewRequired")).intValue()
                + ((Number) summary.get("unanalyzable")).intValue()).isEqualTo(986);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM drawing_object o
                JOIN workbook_sheet s ON s.id = o.workbook_sheet_id
                JOIN baseline_document d ON d.id = s.baseline_document_id
                WHERE d.process_code = 'PW-01' AND o.object_type = 'CONNECTOR'
                  AND o.transform_x_emu IS NOT NULL AND o.transform_y_emu IS NOT NULL
                  AND o.transform_width_emu IS NOT NULL AND o.transform_height_emu IS NOT NULL
                """, Integer.class)).isEqualTo(986);
    }

    @Test
    void singleConnectorAnalysisIsIdempotentAndKeepsAbsoluteCellAnchorEvidence() {
        Map<String, Object> first = service.analyze("PW-01", "PW-01-05", "149");
        Map<String, Object> second = service.analyze("PW-01", "PW-01-05", "149");

        assertThat(((Number) first.get("connectorsScannedThisRun")).intValue()).isEqualTo(1);
        assertThat(((Number) second.get("connectorsScannedThisRun")).intValue()).isEqualTo(1);
        assertThat(((Number) second.get("reviewRequired")).intValue()).isEqualTo(1);
        assertThat(((Number) second.get("confirmed")).intValue()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM process_relation pr
                JOIN drawing_object connector ON connector.id = pr.connector_drawing_object_id
                JOIN workbook_sheet s ON s.id = pr.workbook_sheet_id
                JOIN baseline_document d ON d.id = pr.process_document_id
                WHERE d.process_code = 'PW-01' AND s.sheet_name = 'PW-01-05'
                  AND connector.shape_id = '149'
                """, Integer.class)).isEqualTo(1);
        String evidence = jdbcTemplate.queryForObject("""
                SELECT pr.evidence_text FROM process_relation pr
                JOIN drawing_object connector ON connector.id = pr.connector_drawing_object_id
                JOIN workbook_sheet s ON s.id = pr.workbook_sheet_id
                WHERE s.sheet_name = 'PW-01-05' AND connector.shape_id = '149'
                """, String.class);
        assertThat(evidence).contains("\"anchorFromCell\":\"D152\"",
                "\"anchorToCell\":\"AH152\"", "\"tailArrowType\":\"triangle\"",
                "\"explicitStartShapeId\":null", "\"explicitEndShapeId\":null");
    }

    @Test
    void administratorCanConfirmAndPersistRelationFromAConnectorCandidate() {
        service.analyze("PW-01");
        long candidateId = jdbcTemplate.queryForObject("""
                SELECT pr.id FROM process_relation pr
                JOIN baseline_document d ON d.id = pr.process_document_id
                WHERE d.process_code = 'PW-01' AND pr.connector_drawing_object_id IS NOT NULL
                ORDER BY pr.id LIMIT 1
                """, Long.class);
        long equipmentId = jdbcTemplate.queryForObject("""
                SELECT id FROM equipment_record WHERE baseline_release_id =
                    (SELECT id FROM baseline_release WHERE version_code = 'V1.0') ORDER BY master_order LIMIT 1
                """, Long.class);

        Map<String, Object> saved = service.createReviewedRelation(candidateId, new RelationReviewRequest(
                equipmentId, null, null, null, "PROCESS", "PROCESS", null, null,
                null, "Integration test: manual confirmation workflow; transaction is rolled back.",
                "CONFIRMED", "test-admin", null, null));

        assertThat(saved.get("relationStatus")).isEqualTo("CONFIRMED");
        assertThat(saved.get("analysisStatus")).isEqualTo("MANUALLY_RESOLVED");
        assertThat(saved.get("reviewerName")).isEqualTo("test-admin");

        Map<String, Object> corrected = service.review(((Number) saved.get("id")).longValue(),
                new RelationReviewRequest(equipmentId, null, null, null, "UNKNOWN", "UNKNOWN", null, null,
                        null, "Correction: return this test edge to manager review; do not confirm.",
                        "REVIEW_REQUIRED", "test-admin", "D152:F152", "AI148:AV154"));
        assertThat(corrected.get("relationStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(corrected.get("analysisStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(service.history(((Number) saved.get("id")).longValue())).hasSize(2)
                .extracting("actionType").containsExactly("RELATION_CREATED", "RELATION_UPDATED");
    }

    @Test
    void managerCanAttachEvidenceToCandidateWithoutApprovingIt() {
        service.analyze("PW-01");
        long relationId = jdbcTemplate.queryForObject("""
                SELECT pr.id FROM process_relation pr
                JOIN drawing_object connector ON connector.id = pr.connector_drawing_object_id
                JOIN workbook_sheet s ON s.id = pr.workbook_sheet_id
                JOIN baseline_document d ON d.id = pr.process_document_id
                WHERE d.process_code = 'PW-01' AND s.sheet_name = 'PW-01-05'
                  AND connector.shape_id = '149'
                """, Long.class);
        long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM equipment_record WHERE permit_management_no = 'C-PW15018'", Long.class);
        long targetId = jdbcTemplate.queryForObject(
                "SELECT id FROM equipment_record WHERE permit_management_no = 'C-PW15007'", Long.class);
        int auditCountBefore = service.history(relationId).size();

        Map<String, Object> candidate = service.addCandidateEvidence(relationId,
                new RelationCandidateEvidenceRequest(sourceId, targetId, null, null,
                        "D150:F152", "AI148:AV154",
                        "PW-01-05 sheet: connector shape 149 from D152 to AH152; tail arrow at target edge. "
                                + "Visual review shows C-PW15018 WW-F-AFT12 pointing to C-PW15007 WW-F-STK06; "
                                + "pending administrator approval.", "test-operator"));

        assertThat(candidate.get("relationStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(candidate.get("analysisStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(candidate.get("candidateEvidence")).asString().contains("shape 149 from D152 to AH152");
        assertThat(candidate.get("connectorEvidence")).asString()
                .contains("\"anchorFromCell\":\"D152\"", "\"anchorToCell\":\"AH152\"",
                        "\"tailArrowType\":\"triangle\"", "\"anchorFromAbsolutePointEmu\"");
        assertThat(candidate.get("candidateEvidenceBy")).isEqualTo("test-operator");
        assertThat(candidate.get("reviewerName")).isNull();
        assertThat(candidate.get("sourceEquipmentId")).isEqualTo(sourceId);
        assertThat(candidate.get("targetEquipmentId")).isEqualTo(targetId);
        var audit = service.history(relationId);
        assertThat(audit).hasSize(auditCountBefore + 1);
        Map<String, Object> latestAudit = audit.get(audit.size() - 1);
        assertThat(latestAudit).containsEntry("actionType", "CANDIDATE_EVIDENCE_ADDED")
                .containsEntry("actorName", "test-operator");
        long auditId = ((Number) latestAudit.get("id")).longValue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE process_relation_review_history SET reviewer_name = 'forged' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM process_relation_review_history WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void administratorReviewCanSaveAnUnconfirmedCandidateAndAuditFields() {
        service.analyze("PW-01");
        long relationId = jdbcTemplate.queryForObject("""
                SELECT pr.id FROM process_relation pr
                JOIN baseline_document d ON d.id = pr.process_document_id
                JOIN drawing_object o ON o.id = pr.connector_drawing_object_id
                WHERE d.process_code = 'PW-01' ORDER BY o.object_index LIMIT 1
                """, Long.class);
        long equipmentId = jdbcTemplate.queryForObject("""
                SELECT id FROM equipment_record WHERE baseline_release_id =
                    (SELECT id FROM baseline_release WHERE version_code = 'V1.0') ORDER BY master_order LIMIT 1
                """, Long.class);

        Map<String, Object> saved = service.review(relationId, new RelationReviewRequest(
                equipmentId, null, null, null, "UNKNOWN", "UNKNOWN", null, null,
                null, "Integration test: administrator review path; not a production relation.",
                "REVIEW_REQUIRED", "test-admin", null, null));

        assertThat(saved.get("relationStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(saved.get("analysisStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(service.history(relationId)).hasSize(1);
        assertThat(saved.get("reviewerName")).isEqualTo("test-admin");
    }
}
