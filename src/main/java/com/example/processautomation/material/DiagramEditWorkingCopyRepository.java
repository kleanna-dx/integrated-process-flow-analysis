package com.example.processautomation.material;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only checks that a separately stored working copy belongs to the locked V1.0 source document. */
@Repository
public class DiagramEditWorkingCopyRepository {
    private final JdbcTemplate jdbc;

    public DiagramEditWorkingCopyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SourceIdentity findLockedSource(long baselineDocumentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.process_code, d.document_role, d.sha256, b.version_code, b.locked
                FROM baseline_document d
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.id=?
                """, baselineDocumentId);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new SourceIdentity(((Number) row.get("id")).longValue(), String.valueOf(row.get("process_code")),
                String.valueOf(row.get("document_role")), String.valueOf(row.get("sha256")),
                String.valueOf(row.get("version_code")), Boolean.TRUE.equals(row.get("locked")));
    }

    @Transactional(readOnly = true)
    public Optional<LockedSourceWorkbook> findLockedSourceWorkbook(long baselineDocumentId) {
        List<LockedSourceWorkbook> rows = jdbc.query("""
                SELECT d.id, d.process_code, d.document_role, d.sha256, b.version_code, b.locked, d.content_bytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.id=? AND d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                """, (rs, rowNum) -> new LockedSourceWorkbook(rs.getLong("id"), rs.getString("process_code"),
                rs.getString("document_role"), rs.getString("sha256"), rs.getString("version_code"),
                rs.getBoolean("locked"), rs.getBytes("content_bytes")), baselineDocumentId);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    public record SourceIdentity(long baselineDocumentId, String processCode, String documentRole,
                                 String sourceSha256, String baselineVersion, boolean locked) { }

    public record LockedSourceWorkbook(long baselineDocumentId, String processCode, String documentRole,
                                       String sourceSha256, String baselineVersion, boolean locked, byte[] bytes) {
        public LockedSourceWorkbook {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
}
