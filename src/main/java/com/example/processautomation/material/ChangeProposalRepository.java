package com.example.processautomation.material;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to immutable change-proposal event rows. Updates/deletes are forbidden by database triggers. */
@Repository
public class ChangeProposalRepository {
    private final JdbcTemplate jdbc;

    public ChangeProposalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long append(ChangeProposalService.EventInput event) {
        jdbc.update("""
                INSERT INTO process_change_proposal_event(
                    proposal_id,event_type,change_type,process_code,sheet_name,working_document_id,working_sha256,
                    request_user,equipment_management_no,before_json,after_json,rationale,evidence_json,
                    mapping_status,calculation_status,review_status,test_only_excel_created,output_sha256,
                    validation_result_json,failure_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, event.proposalId(), event.eventType(), event.changeType(), event.processCode(), event.sheetName(),
                event.workingDocumentId(), event.workingSha256(), event.requestUser(), event.managementNo(),
                event.beforeJson(), event.afterJson(), event.rationale(), event.evidenceJson(), event.mappingStatus(),
                event.calculationStatus(), event.reviewStatus(), event.testOnlyExcelCreated(), event.outputSha256(),
                event.validationResultJson(), event.failureReason());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Map<String, Object>> listLatest(String requestUser, int limit) {
        return jdbc.queryForList("""
                SELECT e.* FROM process_change_proposal_event e
                JOIN (SELECT proposal_id, MAX(event_id) AS latest_id
                      FROM process_change_proposal_event WHERE request_user=? GROUP BY proposal_id) latest
                  ON latest.latest_id=e.event_id
                WHERE e.request_user=? ORDER BY e.event_id DESC LIMIT ?
                """, requestUser, requestUser, limit);
    }

    public List<Map<String, Object>> events(String proposalId, String requestUser) {
        return jdbc.queryForList("""
                SELECT * FROM process_change_proposal_event
                WHERE proposal_id=? AND request_user=? ORDER BY event_id ASC
                """, proposalId, requestUser);
    }

    public Map<String, Object> latest(String proposalId, String requestUser) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM process_change_proposal_event
                WHERE proposal_id=? AND request_user=? ORDER BY event_id DESC LIMIT 1
                """, proposalId, requestUser);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> latestWhatIf(String proposalId, String requestUser) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM process_change_proposal_event
                WHERE proposal_id=? AND request_user=? AND event_type='VALIDATION_RESULT'
                ORDER BY event_id DESC LIMIT 1
                """, proposalId, requestUser);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
