package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.DiagramBoxChangeEngine;
import com.example.processautomation.material.DiagramModifyService;
import com.example.processautomation.material.UserWorkingCopyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorDiagramModifyControllerProfileRequestTest {
    private static final String USER = "synthetic-modify-user";
    private final OperatorLoginService login = mock(OperatorLoginService.class);
    private final DiagramModifyService modifies = mock(DiagramModifyService.class);
    private final UserWorkingCopyService userWorkingCopies = mock(UserWorkingCopyService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new OperatorDiagramModifyController(login, modifies, userWorkingCopies)).build();
        when(login.requireAuthenticated(any())).thenReturn(USER);
        org.mockito.Mockito.doNothing().when(login).requireCsrf(any(), any(), any());
        when(modifies.preview(any(DiagramModifyService.ModifyInput.class), eq(USER))).thenReturn(preview());
    }

    @Test
    void profileFieldChangesAndObservedValuesReachTheServerPreviewDto() throws Exception {
        Map<String, String> replacements = Map.of("capacity", "140", "capacityUnit", "L/min");
        Map<String, String> expectedCurrentValues = Map.of("capacity", "120", "capacityUnit", "m3/h");
        var request = Map.of("workingDocumentId", 71, "sheetName", "Synthetic-8-row",
                "managementNo", "SYNTH-01", "replacements", replacements,
                "expectedCurrentValues", expectedCurrentValues);

        mvc.perform(post("/api/workspace/diagram-editor/modify/preview")
                        .session(new MockHttpSession()).header("X-CSRF-Token", "synthetic-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileBased").value(true))
                .andExpect(jsonPath("$.beforeValues.capacity").value("120"))
                .andExpect(jsonPath("$.afterValues.capacityUnit").value("L/min"));

        ArgumentCaptor<DiagramModifyService.ModifyInput> input =
                ArgumentCaptor.forClass(DiagramModifyService.ModifyInput.class);
        verify(modifies).preview(input.capture(), eq(USER));
        assertThat(input.getValue().replacements()).containsExactlyInAnyOrderEntriesOf(replacements);
        assertThat(input.getValue().expectedCurrentValues()).containsExactlyInAnyOrderEntriesOf(expectedCurrentValues);
        verify(login).requireCsrf(any(), any(), eq("synthetic-csrf"));
    }

    @Test
    void deletePreviewCarriesFingerprintAndIsolatedSessionCannotGenerateAnotherSessionsPreview() throws Exception {
        when(login.requireAuthenticated(any())).thenAnswer(invocation -> {
            MockHttpSession session = (MockHttpSession) invocation.getArgument(0);
            return session.getAttribute("synthetic-user");
        });
        when(modifies.previewDelete(any(DiagramModifyService.DeleteInput.class), eq("alice")))
                .thenReturn(deletePreview());
        var sessionA = new MockHttpSession();
        sessionA.setAttribute("synthetic-user", "alice");
        var sessionB = new MockHttpSession();
        sessionB.setAttribute("synthetic-user", "bob");
        Map<String, Object> request = Map.of("workingDocumentId", 71, "sheetName", "Synthetic-8-row",
                "managementNo", "SYNTH-01", "expectedWorkingSha256", "a".repeat(64),
                "analysisFingerprint", "b".repeat(64), "rationale", "synthetic test only");
        var previewResponse = mvc.perform(post("/api/workspace/diagram-editor/modify/delete-preview")
                        .session(sessionA).header("X-CSRF-Token", "synthetic-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value("delete-preview"))
                .andExpect(jsonPath("$.analysisFingerprint").value("b".repeat(64)))
                .andReturn();
        assertThat(previewResponse.getResponse().getHeader("Cache-Control")).contains("no-store");

        mvc.perform(post("/api/workspace/diagram-editor/modify/delete-generate")
                        .session(sessionB).header("X-CSRF-Token", "synthetic-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "delete-preview"))))
                .andExpect(status().isNotFound());

        ArgumentCaptor<DiagramModifyService.DeleteInput> input =
                ArgumentCaptor.forClass(DiagramModifyService.DeleteInput.class);
        verify(modifies).previewDelete(input.capture(), eq("alice"));
        assertThat(input.getValue().expectedWorkingSha256()).isEqualTo("a".repeat(64));
        assertThat(input.getValue().analysisFingerprint()).isEqualTo("b".repeat(64));
        verify(modifies, never()).generateDelete(any(), eq("bob"));
        verify(login).requireCsrf(any(), eq(sessionA), eq("synthetic-csrf"));
    }

    private DiagramModifyService.DeletePreview deletePreview() {
        return new DiagramModifyService.DeletePreview("delete-preview", "proposal-1", 71, "SYNTH-DELETE",
                "Synthetic-8-row", "SYNTH-01", "a".repeat(64), "b".repeat(64), null,
                "TEST_ONLY_READY", "CALCULATION_UNAVAILABLE");
    }

    private DiagramModifyService.ModifyPreview preview() {
        var capacity = new DiagramBoxChangeEngine.FieldMapping("capacity", "L6", "120", null,
                "VERIFIED", "PROFILE_CELL_STYLE_VALUE_AND_DRAWING_VERIFIED", "TEXT", 1);
        var unit = new DiagramBoxChangeEngine.FieldMapping("capacityUnit", "M6", "m3/h", null,
                "VERIFIED", "PROFILE_CELL_STYLE_VALUE_AND_DRAWING_VERIFIED", "TEXT", 1);
        var mapping = new DiagramBoxChangeEngine.BoxFieldMapping("SYNTH-01", "Synthetic-8-row", "J1", "AA8",
                Map.of("capacity", capacity, "capacityUnit", unit),
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "SYNTHETIC-PROFILE", "1.0.0", "VERIFIED",
                "SYNTHETIC_TESTED", true, true, List.of("capacity", "capacityUnit"),
                List.of("capacity", "capacityUnit"));
        return new DiagramModifyService.ModifyPreview("synthetic-preview", 71, "SYNTH-MODIFY",
                "Synthetic-8-row", "SYNTH-01", "a".repeat(64), mapping,
                Map.of("capacity", "120", "capacityUnit", "m3/h"),
                Map.of("capacity", "140", "capacityUnit", "L/min"), "TEST_ONLY_READY", "REVIEW_REQUIRED",
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "SYNTHETIC-PROFILE", "1.0.0", "VERIFIED",
                "SYNTHETIC_TESTED", true, true, List.of("capacity", "capacityUnit"), true,
                "Synthetic DTO contract test only.");
    }
}
