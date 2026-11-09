package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorSetupIntegrationTest {
    private static final String TEST_USERNAME = "first-operator-test";
    private static final String TEST_PASSWORD = "Test-Only-" + java.util.UUID.randomUUID() + "-Passphrase!";
    private static final String TEST_PASSWORD_CONFIRMATION = TEST_PASSWORD;
    private static final String TEST_ADMIN_TOKEN = "test-only-" + java.util.UUID.randomUUID();

    @DynamicPropertySource
    static void noPreconfiguredAccount(DynamicPropertyRegistry registry) {
        registry.add("app.operator.username", () -> "");
        registry.add("app.operator.password-hash", () -> "");
        registry.add("app.baseline.admin-token", () -> TEST_ADMIN_TOKEN);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OperatorSetupService setup;

    @Test
    void approvedBrowserBoundSetupIsOneTimeAndEnablesWorkspaceLogin() throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/operator-setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.requestState").value("NONE"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) statusResult.getRequest().getSession(false);
        String csrf = mapper.readTree(statusResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        assertThat(session).isNotNull();
        assertThat(csrf).isNotBlank();

        mvc.perform(get("/api/admin/operator-setup/requests"))
                .andExpect(status().isUnauthorized());

        MvcResult requestResult = mvc.perform(post("/api/operator-setup/request").session(session)
                        .header("X-CSRF-Token", csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestState").value("PENDING"))
                .andReturn();
        JsonNode requestBody = mapper.readTree(requestResult.getResponse().getContentAsByteArray());
        String requestId = requestBody.path("requestId").asText();
        csrf = requestBody.path("csrfToken").asText();
        assertThat(requestId).isNotBlank();

        MvcResult secondBrowserStatus = mvc.perform(get("/api/operator-setup/status"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession secondBrowser = (MockHttpSession) secondBrowserStatus.getRequest().getSession(false);
        String secondBrowserCsrf = mapper.readTree(secondBrowserStatus.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        mvc.perform(post("/api/operator-setup/request").session(secondBrowser)
                        .header("X-CSRF-Token", secondBrowserCsrf))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_setup_request WHERE state='PENDING' AND active_slot=1", Integer.class)).isEqualTo(1);

        MockHttpSession otherBrowser = new MockHttpSession();
        otherBrowser.setAttribute("operator.csrf", csrf);
        otherBrowser.setAttribute("operator.setup.request-id", requestId);
        mvc.perform(post("/api/operator-setup/complete").session(otherBrowser)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OperatorSetupController.SetupRequest(
                                TEST_USERNAME, TEST_PASSWORD, TEST_PASSWORD_CONFIRMATION))))
                .andExpect(status().isForbidden());

        MvcResult unapproved = mvc.perform(post("/api/operator-setup/complete").session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OperatorSetupController.SetupRequest(
                                TEST_USERNAME, TEST_PASSWORD, TEST_PASSWORD_CONFIRMATION))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(unapproved.getResponse().getContentAsString()).doesNotContain(TEST_PASSWORD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_account", Integer.class)).isZero();

        mvc.perform(get("/api/admin/operator-setup/requests")
                        .header("X-Admin-Token", TEST_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].requestId").value(requestId));
        mvc.perform(post("/api/admin/operator-setup/requests/{requestId}/approve", requestId)
                        .header("X-Admin-Token", TEST_ADMIN_TOKEN))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/operator-setup/requests/{requestId}/approve", requestId)
                        .header("X-Admin-Token", TEST_ADMIN_TOKEN))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/operator-setup/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestState").value("APPROVED"));

        mvc.perform(post("/api/operator-setup/complete").session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OperatorSetupController.SetupRequest(
                                TEST_USERNAME, TEST_PASSWORD, TEST_PASSWORD_CONFIRMATION))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_account WHERE id=1 AND username=?", Integer.class, TEST_USERNAME)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM operator_setup_request WHERE request_id=?", String.class, requestId)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM operator_account WHERE id=1", String.class)).doesNotContain(TEST_PASSWORD);

        MvcResult login = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn();
        MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
        JsonNode documents = mapper.readTree(mvc.perform(get("/api/workspace/process-documents").session(authenticated))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(documents).hasSize(15);
        assertThat(documents.findValuesAsText("processCode")).contains("PW-01");
        mvc.perform(get("/api/workspace/working-copies/36").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDocumentId").value(36))
                .andExpect(jsonPath("$.processCode").value("PW-01"))
                .andExpect(jsonPath("$.conversionVersion").value("PW01-OFFICIAL-NUMERIC-V1"));

        mvc.perform(get("/api/operator-setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.configured").value(true));
        TestTransaction.flagForRollback();
    }

    @Test
    void expiredApprovalCannotBeCompleted() throws Exception {
        MvcResult statusResult = mvc.perform(get("/api/operator-setup/status")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) statusResult.getRequest().getSession(false);
        String csrf = mapper.readTree(statusResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        MvcResult requestResult = mvc.perform(post("/api/operator-setup/request").session(session).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(requestResult.getResponse().getContentAsByteArray());
        String requestId = body.path("requestId").asText();
        csrf = body.path("csrfToken").asText();
        setup.approve(requestId);
        jdbc.update("UPDATE operator_setup_request SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE request_id=?", requestId);
        mvc.perform(get("/api/operator-setup/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestState").value("EXPIRED"));
        mvc.perform(post("/api/operator-setup/complete").session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OperatorSetupController.SetupRequest(
                                TEST_USERNAME, TEST_PASSWORD, TEST_PASSWORD_CONFIRMATION))))
                .andExpect(status().isGone());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_account", Integer.class)).isZero();
        TestTransaction.flagForRollback();
    }
}
