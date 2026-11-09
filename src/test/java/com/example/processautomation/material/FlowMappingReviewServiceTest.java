package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FlowMappingReviewServiceTest {
    @Test
    void qualityFindingsRemainSeparateAndNeverPromoteStringMatchesToPhysicalProof() {
        var row = new Step7MaterialBalanceComparisonService.ConnectionComparison(12, "EQ-1", "EQ-2",
                BigDecimal.TEN, "10", "", "", "", "P-02", "P-02", "", "POSITIVE",
                true, true, false, 2, "", "", "INSUFFICIENT_EVIDENCE", "ID text only");

        assertThat(FlowMappingReviewService.qualityStatuses(row)).containsExactly(
                "STRING_MATCH_ONLY", "DUPLICATE_ENDPOINT_PAIR", "MISSING_MATERIAL_TYPE",
                "MISSING_FLOW_UNIT", "MISSING_PERIOD", "CONNECTOR_BINDING_REQUIRED", "MISSING_EVIDENCE",
                "REVIEW_REQUIRED");
    }

    @Test
    void staleOrIncompleteInventoryNeverReturnsDiagramCandidates() {
        ProcessDiagramInventoryService inventory = mock(ProcessDiagramInventoryService.class);
        when(inventory.mappingReviewCandidates("P-02", "sheet", null, 0, 50)).thenReturn(
                new ProcessDiagramInventoryService.MappingCandidateResults("P-02", "sheet", "fingerprint", 0, 50,
                        0, 0, 0, List.of(), List.of(), List.of(), "STALE_INVENTORY"));
        var service = new FlowMappingReviewService(inventory, null, mock(JdbcTemplate.class), new ObjectMapper(), null);
        assertThatThrownBy(() -> service.candidates("P-02", "sheet", null, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409);

        when(inventory.mappingReviewCandidates("P-02", "incomplete", null, 0, 50)).thenReturn(
                new ProcessDiagramInventoryService.MappingCandidateResults("P-02", "incomplete", "fingerprint", 0, 50,
                        0, 0, 0, List.of(), List.of(), List.of(), "INVENTORY_INCOMPLETE"));
        assertThatThrownBy(() -> service.candidates("P-02", "incomplete", null, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void unmatchedEndpointsStillRequireEvidenceAndReview() {
        var row = new Step7MaterialBalanceComparisonService.ConnectionComparison(13, "EXT", "EQ-2",
                null, "", "chemical", "", "", "", "P-02", "", "BLANK",
                false, true, false, 1, "kg", "2026", "INSUFFICIENT_EVIDENCE", "unverified");

        assertThat(FlowMappingReviewService.qualityStatuses(row)).containsExactly(
                "STRING_MATCH_ONLY", "CONNECTOR_BINDING_REQUIRED", "MISSING_EVIDENCE", "REVIEW_REQUIRED")
                .doesNotContain("MISSING_MATERIAL_TYPE", "MISSING_FLOW_UNIT", "MISSING_PERIOD");
    }
}
