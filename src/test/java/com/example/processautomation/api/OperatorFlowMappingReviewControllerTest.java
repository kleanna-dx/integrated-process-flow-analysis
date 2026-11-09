package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.material.FlowMappingReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorFlowMappingReviewControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OperatorLoginService login = new OperatorLoginService("test-user", "", jdbc);
    private final FlowMappingReviewService review = mock(FlowMappingReviewService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OperatorFlowMappingReviewController(login, review)).build();
    }

    @Test
    void overviewAndTemplateRequireAuthenticatedSession() throws Exception {
        mvc.perform(get("/api/workspace/flow-mapping-review/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace/flow-mapping-review/review-template.csv")).andExpect(status().isUnauthorized());
        verify(review, never()).overview();
        mvc.perform(get("/api/workspace/flow-mapping-review/review-template.csv").session(session("alice")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("flow-mapping-review-template.csv")));
    }

    @Test
    void candidatesValidateIdentifiersSearchAndServerPagination() throws Exception {
        MockHttpSession session = session("alice");
        when(review.candidates("P-02", "P-02-01(TOP)", "EQ-1", 2, 25)).thenReturn(
                new com.example.processautomation.material.ProcessDiagramInventoryService.MappingCandidateResults(
                        "P-02", "P-02-01(TOP)", "fingerprint", 2, 25, 1, 0, 0,
                        java.util.List.of(new com.example.processautomation.material.ProcessDiagramInventoryService.EquipmentMappingCandidate(
                                "EQ-1", "탱크", "A1", "D10", "CANDIDATE", "문자열 후보만")),
                        java.util.List.of(), java.util.List.of(), "CANDIDATES_ONLY_NOT_PHYSICAL_MAPPING"));
        mvc.perform(get("/api/workspace/flow-mapping-review/candidates").session(session)
                        .param("processCode", "P-02").param("sheetName", "P-02-01(TOP)")
                        .param("q", "EQ-1").param("page", "2").param("pageSize", "25"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.equipmentCandidates[0].topLeftCell").value("A1"))
                .andExpect(jsonPath("$.status").value("CANDIDATES_ONLY_NOT_PHYSICAL_MAPPING"));
        verify(review).candidates("P-02", "P-02-01(TOP)", "EQ-1", 2, 25);

        mvc.perform(get("/api/workspace/flow-mapping-review/candidates").session(session)
                        .param("processCode", "../../etc/passwd").param("sheetName", "sheet"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace/flow-mapping-review/candidates").session(session)
                        .param("processCode", "P-02").param("sheetName", "x".repeat(129)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace/flow-mapping-review/candidates").session(session)
                        .param("processCode", "P-02").param("sheetName", "sheet").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace/flow-mapping-review/candidates").session(session)
                        .param("processCode", "P-02").param("sheetName", "sheet").param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        verify(review, never()).candidates(eq("../../etc/passwd"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void equipmentOccurrenceLookupRequiresAuthAndAcceptsOnlyBoundedIdentifier() throws Exception {
        mvc.perform(get("/api/workspace/flow-mapping-review/equipment-occurrences").param("equipmentId", "../../secret"))
                .andExpect(status().isUnauthorized());
        MockHttpSession session=session("alice");
        mvc.perform(get("/api/workspace/flow-mapping-review/equipment-occurrences").session(session)
                        .param("equipmentId", "../../secret"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace/flow-mapping-review/equipment-occurrences").session(session)
                        .param("equipmentId", "EQ-1").param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        when(review.equipmentOccurrences("EQ-1", 0, 50)).thenReturn(null);
        mvc.perform(get("/api/workspace/flow-mapping-review/equipment-occurrences").session(session)
                        .param("equipmentId", "EQ-1"))
                .andExpect(status().isOk());
        verify(review).equipmentOccurrences("EQ-1", 0, 50);
    }

    @Test
    void comparisonRequiresCsrfBoundsAndNeverPassesClientFilenameAsPath() throws Exception {
        MockHttpSession session = session("alice");
        MockMultipartFile file = new MockMultipartFile("workbook", "C:\\private\\balance.xlsm",
                "application/vnd.ms-excel.sheet.macroEnabled.12", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison")
                        .file(file).session(session))
                .andExpect(status().isForbidden());
        verify(review, never()).startComparison(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(review.startComparison(org.mockito.ArgumentMatchers.any(), eq("balance.xlsm"), eq("alice"),
                eq(session.getId()), eq(50), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Map.of("analysisId", "analysis-1", "inputRows", 3, "verifiedFlowMappingCount", 0,
                        "officialReadiness", "CALCULATION_UNAVAILABLE", "priorityGroups", java.util.List.of()));
        var result = mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison")
                        .file(file).session(session).header("X-CSRF-Token", "csrf-alice"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.verifiedFlowMappingCount").value(0))
                .andExpect(jsonPath("$.officialReadiness").value("CALCULATION_UNAVAILABLE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("C:\\\\private");
        verify(review).startComparison(org.mockito.ArgumentMatchers.any(), eq("balance.xlsm"), eq("alice"),
                eq(session.getId()), eq(50), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());

        MockMultipartFile badExtension = new MockMultipartFile("workbook", "input.csv", "text/csv", new byte[]{1});
        mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison")
                        .file(badExtension).session(session).header("X-CSRF-Token", "csrf-alice"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void comparisonRowsAndExportsAreSessionScopedAndReadOnly() throws Exception {
        MockHttpSession session = session("alice");
        when(review.comparisonPage("analysis-1", "alice", session.getId(), 1, 25, "EQ", "MISSING_PERIOD"))
                .thenReturn(Map.of("analysisId", "analysis-1", "page", 1, "totalRows", 2));
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/rows")
                        .session(session).param("page", "1").param("pageSize", "25").param("q", "EQ")
                        .param("statusFilter", "MISSING_PERIOD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalRows").value(2));
        verify(review).comparisonPage("analysis-1", "alice", session.getId(), 1, 25, "EQ", "MISSING_PERIOD");

        byte[] csv = "\\uFEFFrow_id\\r\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(review.exportReviewArtifact("analysis-1", "alice", session.getId(), "csv", null, null))
                .thenReturn(new FlowMappingReviewService.ReviewExport(csv, 1, "", ""));
        var downloaded = mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/export")
                        .session(session).param("format", "csv"))
                .andExpect(status().isOk()).andExpect(header().string("X-Review-Only", "true"))
                .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andExpect(header().string("X-Review-Exported-Rows", "1"))
                .andExpect(header().string("X-Review-Status-Filter", ""))
                .andExpect(header().string("X-Review-SHA256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(csv)))).andReturn();
        assertThat(downloaded.getResponse().getContentAsByteArray()).isEqualTo(csv);

        byte[] filteredXlsx = new byte[]{1, 2, 3, 4};
        when(review.exportReviewArtifact("analysis-1", "alice", session.getId(), "xlsx", "DUPLICATE_ENDPOINT_PAIR", "EQ-2"))
                .thenReturn(new FlowMappingReviewService.ReviewExport(filteredXlsx, 2, "DUPLICATE_ENDPOINT_PAIR", "EQ-2"));
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/export")
                        .session(session).param("format", "xlsx").param("statusFilter", "DUPLICATE_ENDPOINT_PAIR").param("q", "EQ-2"))
                .andExpect(status().isOk()).andExpect(header().string("X-Review-Exported-Rows", "2"))
                .andExpect(header().string("X-Review-Status-Filter", "DUPLICATE_ENDPOINT_PAIR"))
                .andExpect(header().string("X-Review-SHA256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(filteredXlsx))));

        MockHttpSession other = session("alice");
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/rows")
                        .session(other)).andExpect(status().isOk());
        verify(review).comparisonPage("analysis-1", "alice", other.getId(), 0, 50, null, null);
    }

    @Test
    void draftTemplateAndImportRequireAuthenticationAndCsrfAndReturnReadOnlyHeaders() throws Exception {
        var unauthenticated = new MockMultipartFile("file", "review.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-template")
                        .param("format", "xlsx"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-import")
                        .file(unauthenticated))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = session("alice");
        mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-import")
                        .file(unauthenticated).session(session))
                .andExpect(status().isForbidden());
        verify(review, never()).importReviewDraft(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        byte[] template = new byte[]{4, 5, 6};
        when(review.exportReviewInputTemplate("analysis-1", "alice", session.getId(), "xlsx"))
                .thenReturn(new FlowMappingReviewService.ReviewExport(template, 1, "", ""));
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-template")
                        .session(session).param("format", "xlsx"))
                .andExpect(status().isOk()).andExpect(header().string("X-Review-Template-Only", "true"))
                .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andExpect(header().string("X-Review-SHA256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(template))));

        var preview = new com.example.processautomation.material.FlowMappingReviewDraft.ImportPreview("preview-1", "a".repeat(64),
                1, 1, 0, 0, 0, java.util.List.of(), false, 0,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "CALCULATION_UNAVAILABLE");
        when(review.importReviewDraft("analysis-1", "alice", session.getId(), "review.xlsx", new byte[]{1, 2, 3}))
                .thenReturn(preview);
        mvc.perform(multipart("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-import")
                        .file(unauthenticated).session(session).header("X-CSRF-Token", "csrf-alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.verifiedFlowMappingCount").value(0))
                .andExpect(jsonPath("$.officialReadiness").value("CALCULATION_UNAVAILABLE"));
        verify(review).importReviewDraft("analysis-1", "alice", session.getId(), "review.xlsx", new byte[]{1, 2, 3});
    }

    @Test
    void draftPreviewAndExportAreBoundToTheAuthenticatedSession() throws Exception {
        MockHttpSession session = session("alice");
        var preview = new com.example.processautomation.material.FlowMappingReviewDraft.ImportPreview("preview-1", "b".repeat(64),
                2, 1, 1, 0, 0, java.util.List.of(), false, 0,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "CALCULATION_UNAVAILABLE");
        when(review.reviewDraftPreview("analysis-1", "preview-1", "alice", session.getId())).thenReturn(preview);
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-preview/preview-1")
                        .session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previewId").value("preview-1"))
                .andExpect(jsonPath("$.persisted").value(false));
        verify(review).reviewDraftPreview("analysis-1", "preview-1", "alice", session.getId());

        byte[] artifact = new byte[]{8, 9, 10};
        when(review.exportReviewDraft("analysis-1", "preview-1", "alice", session.getId(), "xlsx"))
                .thenReturn(new FlowMappingReviewService.ReviewExport(artifact, 2, "IMPORT_REVIEW_DRAFT", ""));
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-preview/preview-1/export")
                        .session(session).param("format", "xlsx"))
                .andExpect(status().isOk()).andExpect(header().string("X-Review-Draft-Only", "true"))
                .andExpect(header().string("X-Review-Persisted", "false"))
                .andExpect(header().string("X-Review-Verified-Flow-Mappings", "0"))
                .andExpect(header().string("X-Review-Readiness", "CALCULATION_UNAVAILABLE"))
                .andExpect(header().string("X-Review-SHA256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(artifact))));

        MockHttpSession otherSession = session("alice");
        mvc.perform(get("/api/workspace/flow-mapping-review/material-balance-comparison/analysis-1/draft-preview/preview-1")
                        .session(otherSession))
                .andExpect(status().isOk());
        verify(review).reviewDraftPreview("analysis-1", "preview-1", "alice", otherSession.getId());
    }

    private MockHttpSession session(String user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, user);
        session.setAttribute("operator.csrf", "csrf-" + user);
        return session;
    }
}
