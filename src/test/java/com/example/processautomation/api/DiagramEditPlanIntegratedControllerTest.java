package com.example.processautomation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.DiagramEditPlanService;
import com.example.processautomation.material.Step6TestOnlyWorkbookEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class DiagramEditPlanIntegratedControllerTest {
    private final DiagramEditPlanService plans = org.mockito.Mockito.mock(DiagramEditPlanService.class);
    private final Step6TestOnlyWorkbookEnrichmentService integrated =
            org.mockito.Mockito.mock(Step6TestOnlyWorkbookEnrichmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new DiagramEditPlanController(new ApiAccessGuard("test-token"), plans, integrated);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void integratedGenerationAndDownloadRequireAdminToken() throws Exception {
        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .contentType("application/json")
                        .content("{\"changeRunId\":193,\"changeItemId\":217}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/diagram-edit-plans/33/download-integrated"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/diagram-edit-plans/by-run/193/items/217"))
                .andExpect(status().isUnauthorized());
        verify(integrated, never()).requireEligiblePlan(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(plans, never()).generate(org.mockito.ArgumentMatchers.anyLong());
        verify(plans, never()).listPlansForItem(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsMismatchedRunItemBeforeGeneratingDiagram() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "unsupported run/item"))
                .when(integrated).requireEligiblePlan(192, 217, 33);
        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json")
                        .content("{\"changeRunId\":192,\"changeItemId\":217}"))
                .andExpect(status().isConflict());
        verify(integrated).requireEligiblePlan(192, 217, 33);
        verify(plans, never()).generate(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void generationReturnsVerifiedFinalArtifactMetadataOnly() throws Exception {
        when(plans.generate(33)).thenReturn(new DiagramEditPlanService.GeneratedWorkbook(
                new byte[]{'P', 'K'}, "diagram.xlsx", "a".repeat(64), false));
        when(integrated.createAndStoreIntegratedWorkbook(eq(193L), eq(217L), eq(33L), any(), eq(true)))
                .thenReturn(new Step6TestOnlyWorkbookEnrichmentService.StoredIntegratedWorkbook(
                        new byte[]{'P', 'K'}, "integrated.xlsx", "b".repeat(64), "SYNTHETIC_TEST_ONLY", 33, false));
        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json")
                        .content("{\"changeRunId\":193,\"changeItemId\":217,\"includeSyntheticTestValues\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrated").value(true))
                .andExpect(jsonPath("$.planId").value(33))
                .andExpect(jsonPath("$.changeRunId").value(193))
                .andExpect(jsonPath("$.changeItemId").value(217))
                .andExpect(jsonPath("$.filename").value("integrated.xlsx"))
                .andExpect(jsonPath("$.sha256").value("b".repeat(64)))
                .andExpect(jsonPath("$.sheetCount").value(8));
        verify(plans).generate(33);
        verify(integrated).createAndStoreIntegratedWorkbook(eq(193L), eq(217L), eq(33L), any(), eq(true));
    }
}
