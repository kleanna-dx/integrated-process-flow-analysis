package com.example.processautomation.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.change.SelectiveEditExecutionPlanService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SelectiveEditExecutionPlanControllerTest {
    private final SelectiveEditExecutionPlanService service = org.mockito.Mockito.mock(SelectiveEditExecutionPlanService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new SelectiveEditExecutionPlanController(new ApiAccessGuard("test-token"), service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void requiresAdminTokenBeforePlanning() throws Exception {
        mvc.perform(post("/api/admin/change-runs/173/selective-edit-plan").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delegatesSelectedPlanReferencesAndReturnsPlan() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.eq(173L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("changeRunId", 173L, "executionPlanReady", true,
                        "fileGenerationStarted", false, "outputTargetFileCount", 1));
        mvc.perform(post("/api/admin/change-runs/173/selective-edit-plan")
                        .header("X-Admin-Token", "test-token")
                        .contentType("application/json")
                        .content("{\"changeItemIds\":[173],\"testOnlyPlanIds\":[5]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeRunId").value(173))
                .andExpect(jsonPath("$.executionPlanReady").value(true))
                .andExpect(jsonPath("$.fileGenerationStarted").value(false));
        verify(service).create(org.mockito.ArgumentMatchers.eq(173L), org.mockito.ArgumentMatchers.any());
    }
}
