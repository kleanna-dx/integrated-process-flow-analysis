package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.OoxmlPartEditor;
import com.example.processautomation.excel.PipeChangeDrawingEngine;
import com.example.processautomation.excel.PipeChangeSyntheticFixtureFactory;
import com.example.processautomation.material.PipeChangeSyntheticWorkflowService;
import com.example.processautomation.material.UserWorkingCopyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OperatorPipeChangeControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OperatorLoginService login = new OperatorLoginService("test-user", "", jdbc);
    private final PipeChangeSyntheticWorkflowService workflow = mock(PipeChangeSyntheticWorkflowService.class);
    private final PipeChangeSyntheticFixtureFactory fixtures = new PipeChangeSyntheticFixtureFactory(
            new OoxmlPackageInspector(), new OoxmlPartEditor());
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OperatorPipeChangeController(login, workflow)).build();
    }

    @Test
    void rejectsAnonymousMissingCsrfAndClientClaimedFixture() throws Exception {
        mvc.perform(get("/api/workspace/diagram-editor/pipe-change/fixture"))
                .andExpect(status().isUnauthorized());
        verify(workflow, never()).fixtureForUser(any());

        MockHttpSession session = session("alice");
        var fixture = fixtures.fixture();
        when(workflow.fixtureForUser("alice")).thenReturn(fixture);
        var fixtureResponse = mvc.perform(get("/api/workspace/diagram-editor/pipe-change/fixture").session(session))
                .andExpect(status().isOk()).andExpect(header().string("X-Test-Only", "true"))
                .andExpect(header().string("X-Source-SHA256", fixture.sha256())).andReturn();
        assertThat(fixtureResponse.getResponse().getContentAsByteArray()).isEqualTo(fixture.bytes());
        mvc.perform(post("/api/workspace/diagram-editor/pipe-change/preview").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(previewRequest())))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/workspace/diagram-editor/pipe-change/preview").session(session)
                        .header("X-CSRF-Token", csrf(session)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("fixtureId", "SYNTHETIC_TEST_ONLY",
                                "workingDocumentId", 71, "expectedWorkingSha256", "a".repeat(64), "rationale", "no spoofing"))))
                .andExpect(status().isConflict());
        verify(workflow, never()).preview(any(), any(Long.class), any(), any());
    }

    @Test
    void previewGenerateAndDownloadAreBoundToAuthenticatedOwnerAndSameSession() throws Exception {
        MockHttpSession alice = session("alice");
        var preview = new PipeChangeSyntheticWorkflowService.Preview("preview-1", "proposal-1", "alice", 71,
                "SYNTHETIC-P", "a".repeat(64), "b".repeat(64), "c".repeat(64), null, null, null,
                "TEST_ONLY_READY", "CALCULATION_UNAVAILABLE", false, "synthetic fixture only");
        when(workflow.preview(eq("alice"), eq(71L), eq("a".repeat(64)), eq("synthetic test"))).thenReturn(preview);

        var previewResult = mvc.perform(post("/api/workspace/diagram-editor/pipe-change/preview").session(alice)
                        .header("X-CSRF-Token", csrf(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(previewRequest())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previewId").value("preview-1"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        assertThat(previewResult.getResponse().getContentAsString()).contains("CALCULATION_UNAVAILABLE");

        MockHttpSession otherSessionSameUser = session("alice");
        mvc.perform(post("/api/workspace/diagram-editor/pipe-change/generate").session(otherSessionSameUser)
                        .header("X-CSRF-Token", csrf(otherSessionSameUser)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/workspace/diagram-editor/pipe-change/generate").session(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isForbidden());
        verify(workflow, never()).generate(any(), any());

        byte[] bytes = "synthetic output bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var generated = new PipeChangeSyntheticWorkflowService.Generated("preview-1", "proposal-1", 901, 71,
                "SYNTHETIC-P", "Synthetic", bytes, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                null, null, "SYNTHETIC_TEST_ONLY", "CALCULATION_UNAVAILABLE", false, "TEST_ONLY");
        when(workflow.generate(preview, "alice")).thenReturn(generated);
        mvc.perform(post("/api/workspace/diagram-editor/pipe-change/generate").session(alice)
                        .header("X-CSRF-Token", csrf(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testOnlyOutputId").value(901))
                .andExpect(jsonPath("$.officialReadiness").value("CALCULATION_UNAVAILABLE"));

        MockHttpSession bob = session("bob");
        mvc.perform(get("/api/workspace/diagram-editor/pipe-change/download/preview-1").session(bob))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/workspace/diagram-editor/pipe-change/download/preview-1").session(otherSessionSameUser))
                .andExpect(status().isNotFound());

        var artifact = new UserWorkingCopyService.StoredArtifact(901, 71, "synthetic-pipe.xlsx", "b".repeat(64),
                bytes.length, "TEST_ONLY", "REVIEW_REQUIRED", "PIPE_CHANGE", bytes);
        when(workflow.download(901, "alice")).thenReturn(artifact);
        var downloaded = mvc.perform(get("/api/workspace/diagram-editor/pipe-change/download/preview-1").session(alice))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Test-Only", "true"))
                .andExpect(header().string("X-Official-Readiness", "CALCULATION_UNAVAILABLE"))
                .andReturn();
        assertThat(downloaded.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        verify(workflow).generate(preview, "alice");
    }

    private Map<String, Object> previewRequest() {
        return Map.of("fixtureId", "SERVER_SYNTHETIC_FIXTURE_V1", "workingDocumentId", 71,
                "expectedWorkingSha256", "a".repeat(64), "rationale", "synthetic test");
    }
    private MockHttpSession session(String user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, user);
        session.setAttribute("operator.csrf", "csrf-" + user);
        return session;
    }
    private String csrf(MockHttpSession session) { return "csrf-" + session.getAttribute(OperatorLoginService.OPERATOR_SESSION_KEY); }
}
