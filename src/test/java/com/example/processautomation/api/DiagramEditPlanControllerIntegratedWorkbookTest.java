package com.example.processautomation.api;

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

class DiagramEditPlanControllerIntegratedWorkbookTest {
    private final DiagramEditPlanService plans = org.mockito.Mockito.mock(DiagramEditPlanService.class);
    private final Step6TestOnlyWorkbookEnrichmentService integrated =
            org.mockito.Mockito.mock(Step6TestOnlyWorkbookEnrichmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new DiagramEditPlanController(new ApiAccessGuard("test-token"), plans, integrated);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void integratedGenerationRequiresAdminTokenBeforePlanOrWorkbookAccess() throws Exception {
        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .contentType("application/json")
                        .content("{\"changeRunId\":193,\"changeItemId\":217,\"includeSyntheticTestValues\":true}"))
                .andExpect(status().isUnauthorized());
        verify(integrated, never()).requireEligiblePlan(193, 217, 33);
        verify(plans, never()).generate(33);
    }

    @Test
    void rejectsMismatchedRunBeforeGeneratingDiagram() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "지원 범위 불일치"))
                .when(integrated).requireEligiblePlan(194, 217, 33);
        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json")
                        .content("{\"changeRunId\":194,\"changeItemId\":217,\"includeSyntheticTestValues\":false}"))
                .andExpect(status().isConflict());
        verify(plans, never()).generate(33);
    }

    @Test
    void generatesVerifiedIntegratedBytesFromSelectedPlanAndExplicitScenario() throws Exception {
        byte[] diagramBytes = new byte[]{'P', 'K', 3, 4};
        byte[] integratedBytes = new byte[]{'P', 'K', 3, 4, 8};
        when(plans.generate(33)).thenReturn(new DiagramEditPlanService.GeneratedWorkbook(
                diagramBytes, "diagram-only.xlsx", "diagram-hash", false));
        when(integrated.createAndStoreIntegratedWorkbook(org.mockito.ArgumentMatchers.eq(193L),
                org.mockito.ArgumentMatchers.eq(217L), org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.any(DiagramEditPlanService.GeneratedWorkbook.class),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new Step6TestOnlyWorkbookEnrichmentService.StoredIntegratedWorkbook(
                        integratedBytes, "TEST_ONLY-integrated.xlsx", "integrated-hash", "SYNTHETIC_TEST_ONLY", 33, false));

        mvc.perform(post("/api/admin/diagram-edit-plans/33/generate-integrated-test-only")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json")
                        .content("{\"changeRunId\":193,\"changeItemId\":217,\"includeSyntheticTestValues\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(33))
                .andExpect(jsonPath("$.changeRunId").value(193))
                .andExpect(jsonPath("$.changeItemId").value(217))
                .andExpect(jsonPath("$.filename").value("TEST_ONLY-integrated.xlsx"))
                .andExpect(jsonPath("$.sha256").value("integrated-hash"))
                .andExpect(jsonPath("$.scenarioKey").value("SYNTHETIC_TEST_ONLY"))
                .andExpect(jsonPath("$.integrated").value(true))
                .andExpect(jsonPath("$.sheetCount").value(8));
        verify(integrated).requireEligiblePlan(193, 217, 33);
        verify(plans).generate(33);
    }

    @Test
    void integratedDownloadRequiresAdminToken() throws Exception {
        mvc.perform(get("/api/admin/diagram-edit-plans/33/download-integrated")
                        .param("includeSyntheticTestValues", "false"))
                .andExpect(status().isUnauthorized());
        verify(integrated, never()).downloadIntegratedWorkbook(33, false);
    }
}
