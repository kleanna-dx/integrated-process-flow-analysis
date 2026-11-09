package com.example.processautomation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.material.ProfilePreflightService;
import com.example.processautomation.material.ProfilePreflightService.MergeReview;
import com.example.processautomation.material.ProfilePreflightService.PreflightResult;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OperatorProfilePreflightControllerTest {
    private static final String ROUTE = "/api/workspace/diagram-editor/profile-preflight";
    private final OperatorLoginService login = mock(OperatorLoginService.class);
    private final ProfilePreflightService service = mock(ProfilePreflightService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OperatorProfilePreflightController(login, service)).build();
        when(login.requireAuthenticated(any())).thenReturn("alice");
        when(service.inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice")).thenReturn(result());
    }

    @Test
    void authenticatedGetReturnsNoStoreReviewOnlyDtoAndUsesCurrentUsername() throws Exception {
        mvc.perform(get(ROUTE).session(new MockHttpSession()).param("workingDocumentId", "71")
                        .param("processCode", "P-02").param("sheetName", "P-02-01(TOP)")
                        .param("managementNo", "I-PP21005"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.applicationStatus").value("READ_ONLY_REVIEW_ONLY"))
                .andExpect(jsonPath("$.decisionStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.modifyAuthorized").value(false))
                .andExpect(jsonPath("$.fields").isArray())
                .andExpect(jsonPath("$.modifyBlockedReasons").isArray());
        verify(login).requireAuthenticated(any(HttpSession.class));
        verify(service).inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforePreflightService() throws Exception {
        when(login.requireAuthenticated(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(get(ROUTE).session(new MockHttpSession()).param("workingDocumentId", "71")
                        .param("processCode", "P-02").param("sheetName", "P-02-01(TOP)")
                        .param("managementNo", "I-PP21005"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");
    }

    @Test
    void routeHasNoPostPreviewOrGenerateHandler() throws Exception {
        mvc.perform(post(ROUTE).session(new MockHttpSession()).param("workingDocumentId", "71")
                        .param("processCode", "P-02").param("sheetName", "P-02-01(TOP)")
                        .param("managementNo", "I-PP21005"))
                .andExpect(status().isMethodNotAllowed());
        verify(service, never()).inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");
    }

    private PreflightResult result() {
        return new PreflightResult("READ_ONLY_REVIEW_ONLY", 71, "P-02", "P-02-01(TOP)", "I-PP21005",
                "a".repeat(64), "b".repeat(64), true, true, true, true,
                "P-02-REVIEW", "REVIEW_REQUIRED", "P-02-01-TOP-V1", "1.0.0", "VERIFIED",
                "STRUCTURE_CHECK_PASS", "BLOCKED", "BLOCKED", List.of("P-02-REVIEW"),
                List.of("PROFILE_SELECTION_AMBIGUOUS"), false,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", List.of(),
                new MergeReview(List.of(), List.of(), List.of(), false, false), List.of(), List.of(),
                List.of("PROFILE_SELECTION_AMBIGUOUS"), List.of("MODIFY_NOT_AUTHORIZED_BY_PREFLIGHT"),
                List.of("INDEPENDENT_HUMAN_REVIEW_AND_EXPLICIT_APPROVAL_REQUIRED"));
    }
}
