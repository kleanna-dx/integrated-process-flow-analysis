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

import com.example.processautomation.material.FlowChangeAuthenticatedWorkflowService;
import com.example.processautomation.material.FlowChangeSyntheticWorkflowService;
import com.example.processautomation.material.UserWorkingCopyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OperatorFlowChangeControllerTest {
    private final OperatorLoginService login = new OperatorLoginService("test-user", "", mock(JdbcTemplate.class));
    private final FlowChangeAuthenticatedWorkflowService workflow = mock(FlowChangeAuthenticatedWorkflowService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OperatorFlowChangeController(login, workflow)).build();
    }

    @Test
    void requiresAuthenticationCsrfAndEnabledFeatureFlags() throws Exception {
        mvc.perform(get("/api/workspace/diagram-editor/flow-change/fixture"))
                .andExpect(status().isUnauthorized());
        verify(workflow, never()).fixtureForUser(any());

        MockHttpSession alice = session("alice");
        when(workflow.fixtureForUser("alice")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/workspace/diagram-editor/flow-change/fixture").session(alice))
                .andExpect(status().isServiceUnavailable());
        verify(workflow).fixtureForUser("alice");

        byte[] body = mapper.writeValueAsBytes(previewRequest("b".repeat(64)));
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/preview").session(alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verify(workflow, never()).preview(any(), any(Long.class), any(), any(), any(), any(), any());
    }

    @Test
    void scopesPreviewGenerateAndDownloadToOneAuthenticatedSessionAndPreservesResponseBytes() throws Exception {
        MockHttpSession alice = session("alice");
        var fixtureBytes = "server fixture xlsx bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var fixture = new FlowChangeSyntheticWorkflowService.FixtureDownload("SERVER_SYNTHETIC_FLOW_FIXTURE_V1",
                sha256(fixtureBytes), fixtureBytes);
        when(workflow.fixtureForUser("alice")).thenReturn(fixture);
        var fixtureResponse = mvc.perform(get("/api/workspace/diagram-editor/flow-change/fixture").session(alice))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fixture-SHA256", fixture.sha256()))
                .andExpect(header().string("X-Test-Only", "true"))
                .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andReturn();
        assertThat(fixtureResponse.getResponse().getContentAsByteArray()).isEqualTo(fixtureBytes);

        var preview = new FlowChangeAuthenticatedWorkflowService.Preview("preview-1", "proposal-1", "alice", 71,
                "P-02", "a".repeat(64), fixture.fixtureId(), fixture.sha256(), "c".repeat(64), "FLOW-10",
                new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("10"), Map.of("status", "CALCULATED"),
                List.of(), false, "TEST_ONLY_REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", false);
        when(workflow.preview(eq("alice"), eq(71L), eq("a".repeat(64)), eq(fixture.fixtureId()),
                eq(fixture.sha256()), eq("FLOW-10"), eq("synthetic review"))).thenReturn(preview);
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/preview").session(alice)
                        .header("X-CSRF-Token", csrf(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(previewRequest(fixture.sha256()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value("preview-1"))
                .andExpect(jsonPath("$.calculatedEdgeQuantity").value(10))
                .andExpect(jsonPath("$.actualReadiness").value("CALCULATION_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        MockHttpSession sameUserDifferentSession = session("alice");
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/generate").session(sameUserDifferentSession)
                        .header("X-CSRF-Token", csrf(sameUserDifferentSession)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/workspace/diagram-editor/flow-change/download/preview-1").session(sameUserDifferentSession))
                .andExpect(status().isNotFound());
        MockHttpSession bob = session("bob");
        mvc.perform(get("/api/workspace/diagram-editor/flow-change/download/preview-1").session(bob))
                .andExpect(status().isNotFound());
        verify(workflow, never()).generate(any(), any());

        var outputBytes = "stored generated xlsx bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String outputSha = sha256(outputBytes);
        var generated = new FlowChangeAuthenticatedWorkflowService.Generated("preview-1", "proposal-1", "alice", 71,
                "P-02", "FLOW-10", new BigDecimal("20"), new BigDecimal("10"), 901,
                "P-02_FLOW_CHANGE_FLOW-10_TEST_ONLY.xlsx", outputSha, fixture.sha256(), "c".repeat(64), List.of(),
                Map.of("status", "CALCULATED"), "TEST_ONLY", "REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", false,
                "Synthetic fixture only.");
        when(workflow.generate(preview, "alice")).thenReturn(generated);
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/generate").session(alice)
                        .header("X-CSRF-Token", csrf(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputSha256").value(outputSha))
                .andExpect(jsonPath("$.actualReadiness").value("CALCULATION_UNAVAILABLE"));
        mvc.perform(post("/api/workspace/diagram-editor/flow-change/generate").session(alice)
                        .header("X-CSRF-Token", csrf(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", "preview-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputId").value(901))
                .andExpect(jsonPath("$.outputSha256").value(outputSha));

        var artifact = new UserWorkingCopyService.StoredArtifact(901, 71,
                "P-02_FLOW_CHANGE_FLOW-10_TEST_ONLY.xlsx", outputSha, outputBytes.length,
                "TEST_ONLY", "REVIEW_REQUIRED", "FLOW_CHANGE", outputBytes);
        when(workflow.download(901, "alice", 71, outputSha)).thenReturn(artifact);
        var downloaded = mvc.perform(get("/api/workspace/diagram-editor/flow-change/download/preview-1").session(alice))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Output-SHA256", outputSha))
                .andExpect(header().string("X-Official-Readiness", "CALCULATION_UNAVAILABLE"))
                .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andReturn();
        byte[] responseBytes = downloaded.getResponse().getContentAsByteArray();
        assertThat(responseBytes).isEqualTo(outputBytes);
        assertThat(sha256(responseBytes)).isEqualTo(outputSha);
        verify(workflow).generate(preview, "alice");
        verify(workflow).download(901, "alice", 71, outputSha);
    }

    private Map<String, Object> previewRequest(String expectedFixtureSha256) {
        return Map.of("workingDocumentId", 71, "expectedWorkingSha256", "a".repeat(64),
                "fixtureId", "SERVER_SYNTHETIC_FLOW_FIXTURE_V1", "expectedFixtureSha256", expectedFixtureSha256,
                "scenarioId", "FLOW-10", "rationale", "synthetic review");
    }

    private MockHttpSession session(String user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, user);
        session.setAttribute("operator.csrf", "csrf-" + user);
        return session;
    }

    private String csrf(MockHttpSession session) {
        return "csrf-" + session.getAttribute(OperatorLoginService.OPERATOR_SESSION_KEY);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
