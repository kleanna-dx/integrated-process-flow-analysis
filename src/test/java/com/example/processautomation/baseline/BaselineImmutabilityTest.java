package com.example.processautomation.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class BaselineImmutabilityTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registeredMasterAndProcessDocumentsCannotBeUpdatedOrDeleted() {
        int documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baseline_document WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')",
                Integer.class);
        String originalMasterHash = jdbcTemplate.queryForObject(
                "SELECT source_master_sha256 FROM baseline_release WHERE version_code = 'V1.0'",
                String.class);

        assertThat(documentCount).isEqualTo(16);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE baseline_document SET original_filename = 'replaced.xlsx' WHERE document_role = 'MASTER'"))
                .hasMessageContaining("Baseline source documents are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM baseline_document WHERE document_role = 'PROCESS' AND process_code = 'PW-01'"))
                .hasMessageContaining("Baseline source documents are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE baseline_release SET description = 'replaced' WHERE version_code = 'V1.0'"))
                .hasMessageContaining("Locked baseline releases are immutable");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_master_sha256 FROM baseline_release WHERE version_code = 'V1.0'", String.class))
                .isEqualTo(originalMasterHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baseline_document WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')",
                Integer.class)).isEqualTo(16);
    }
}
