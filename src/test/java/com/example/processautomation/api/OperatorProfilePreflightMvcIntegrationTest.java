package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.material.DiagramEditWorkingCopyRepository;
import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.example.processautomation.material.NumericWorkingCopyService;
import com.example.processautomation.material.ProfilePreflightService;
import com.example.processautomation.material.ProfilePreflightService.MergeReview;
import com.example.processautomation.material.ProfilePreflightService.PreflightResult;
import com.example.processautomation.material.UserWorkingCopyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Spring MVC DispatcherServlet tests for the application's existing HttpSession authentication contract. */
class OperatorProfilePreflightMvcIntegrationTest {
    private static final String ROUTE = "/api/workspace/diagram-editor/profile-preflight";
    private static final String PROCESS = "P-02";
    private static final String SHEET = "P-02-01(TOP)";
    private static final String MANAGEMENT_NO = "I-PP21005";
    private static final String P02_SOURCE_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String P02_WORKING_SHA = "c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2";
    private static final String TEST_PASSWORD = "pilot09a-only-test-password";
    private static final String TEST_PASSWORD_HASH = OperatorLoginService.hashForTests(
            TEST_PASSWORD, "pilot09a-salt-0123456".getBytes(StandardCharsets.UTF_8), 210_000);

    @Test
    void controllerIsAbsentWhenBothFlagsAreOffOrEitherFlagIsOff() throws Exception {
        assertRouteAbsent(false, false);
        assertRouteAbsent(true, false);
        assertRouteAbsent(false, true);
    }

    @Test
    void bothFlagsEnableMvcRouteAndServerSessionProvidesIdentity() throws Exception {
        try (var context = context(true, true)) {
            ProfilePreflightService service = context.getBean(ProfilePreflightService.class);
            when(service.inspect(71, PROCESS, SHEET, MANAGEMENT_NO, "alice")).thenReturn(blockedResult(
                    71, "P-02", "REVIEW_REQUIRED", "BLOCKED", true, true, "PROFILE_SELECTION_AMBIGUOUS"));

            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            MockHttpSession session = authenticatedSession(mvc, "alice");
            mvc.perform(get(ROUTE).session(session)
                            .param("workingDocumentId", "71")
                            .param("processCode", PROCESS)
                            .param("sheetName", SHEET)
                            .param("managementNo", MANAGEMENT_NO)
                            .param("username", "mallory"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.decisionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.profileSelectionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.modifyAuthorized").value(false));
            verify(service).inspect(71, PROCESS, SHEET, MANAGEMENT_NO, "alice");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot09a.real.mvc", matches = "true")
    void realReadOnlyP02PreflightRunsThroughMvcWithOnlyDataAccessMocked() throws Exception {
        Path sourcePath = Path.of(System.getProperty("pilot09a.p02.source",
                "data/reference/P-02 제지3호기 공정 ★변경허가 REV3.xlsx"));
        Path workingPath = Path.of(System.getProperty("pilot09a.p02.working-copy",
                "../uploaded_files/P-02 제지3호기 공정(수정).xlsx"));
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        byte[] workingBytes = Files.readAllBytes(workingPath);
        assertThat(sha256(sourceBytes)).isEqualTo(P02_SOURCE_SHA);
        assertThat(sha256(workingBytes)).isEqualTo(P02_WORKING_SHA);

        ObjectMapper mapper = new ObjectMapper();
        UserWorkingCopyService userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        when(userCopies.getForUser(81, "alice")).thenReturn(new NumericWorkingCopyService.WorkingCopy(
                81, 711, PROCESS, "P-02-source.xlsx", "P-02-working.xlsx", P02_SOURCE_SHA, P02_WORKING_SHA,
                UserWorkingCopyService.CONVERSION_VERSION, "CREATED_WITH_REVIEW", workingBytes,
                UserWorkingCopyService.COPY_KIND, "alice", 1, null, workingBytes.length, "REVIEW_REQUIRED", null));
        DiagramEditWorkingCopyRepository sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSourceWorkbook(711)).thenReturn(Optional.of(
                new DiagramEditWorkingCopyRepository.LockedSourceWorkbook(
                        711, PROCESS, "PROCESS", P02_SOURCE_SHA, "V1.0", true, sourceBytes)));
        ProfilePreflightService actualService = new ProfilePreflightService(userCopies, sources,
                new EquipmentBoxProfileRepository(mapper), mapper, true);

        try (var context = context(true, true, actualService)) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            MvcResult actual = mvc.perform(validGet(authenticatedSession(mvc, "alice"), 81))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.sourceShaVerified").value(true))
                    .andExpect(jsonPath("$.workingCopyShaVerified").value(true))
                    .andExpect(jsonPath("$.candidateShaVerified").value(true))
                    .andExpect(jsonPath("$.candidateStatus").value("REVIEW_REQUIRED"))
                    .andExpect(jsonPath("$.decisionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.profileSelectionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.modifyAuthorized").value(false))
                    .andReturn();
            assertThat(actual.getResponse().getContentAsString())
                    .doesNotContain("/home/", "uploaded_files", "password", "token", "secret", "absolutePath", "filePath");
            verify(userCopies).getForUser(81, "alice");
            verify(sources).findLockedSourceWorkbook(711);
        }
        assertThat(sha256(Files.readAllBytes(sourcePath))).isEqualTo(P02_SOURCE_SHA);
        assertThat(sha256(Files.readAllBytes(workingPath))).isEqualTo(P02_WORKING_SHA);
    }

    @Test
    void unauthenticatedAndForeignOwnerRequestsAreDeniedBySessionAndOwnerServiceContracts() throws Exception {
        try (var context = context(true, true, "mallory")) {
            ProfilePreflightService service = context.getBean(ProfilePreflightService.class);
            when(service.inspect(72, PROCESS, SHEET, MANAGEMENT_NO, "mallory"))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(validGet(null, 71))
                    .andExpect(status().isUnauthorized());
            verify(service, never()).inspect(71, PROCESS, SHEET, MANAGEMENT_NO, "alice");

            mvc.perform(validGet(authenticatedSession(mvc, "mallory"), 72))
                    .andExpect(status().isNotFound());
            verify(service).inspect(72, PROCESS, SHEET, MANAGEMENT_NO, "mallory");
        }
    }

    @Test
    void authenticatedForeignOwnerIsRejectedByRealPreflightServiceBeforeSourceRead() throws Exception {
        UserWorkingCopyService userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        when(userCopies.getForUser(72, "mallory"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        DiagramEditWorkingCopyRepository sources = mock(DiagramEditWorkingCopyRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ProfilePreflightService actualService = new ProfilePreflightService(userCopies, sources,
                new EquipmentBoxProfileRepository(mapper), mapper, true);

        try (var context = context(true, true, actualService, "mallory")) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(validGet(authenticatedSession(mvc, "mallory"), 72)).andExpect(status().isNotFound());
            verify(userCopies).getForUser(72, "mallory");
            verify(sources, never()).findLockedSourceWorkbook(712);
        }
    }

    @Test
    void invalidAndUnknownWorkingCopyIdsAreRejectedAndNonGetMethodsAreUnavailable() throws Exception {
        try (var context = context(true, true)) {
            ProfilePreflightService service = context.getBean(ProfilePreflightService.class);
            when(service.inspect(9999, PROCESS, SHEET, MANAGEMENT_NO, "alice"))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get(ROUTE).session(session("alice"))
                            .param("workingDocumentId", "not-a-number")
                            .param("processCode", PROCESS).param("sheetName", SHEET)
                            .param("managementNo", MANAGEMENT_NO))
                    .andExpect(status().isBadRequest());
            mvc.perform(validGet(session("alice"), 9999)).andExpect(status().isNotFound());
            mvc.perform(post(ROUTE).session(session("alice"))
                            .param("workingDocumentId", "71").param("processCode", PROCESS)
                            .param("sheetName", SHEET).param("managementNo", MANAGEMENT_NO))
                    .andExpect(status().isMethodNotAllowed());
            verify(service, never()).inspect(71, PROCESS, SHEET, MANAGEMENT_NO, "alice");
        }
    }

    @Test
    void shaMismatchAndReviewRequiredDtosRemainBlockedAndExposeNoPrivateData() throws Exception {
        try (var context = context(true, true)) {
            ProfilePreflightService service = context.getBean(ProfilePreflightService.class);
            when(service.inspect(73, PROCESS, SHEET, MANAGEMENT_NO, "alice"))
                    .thenReturn(blockedResult(73, "P-02", "REVIEW_REQUIRED", "BLOCKED", true, false,
                            "WORKING_COPY_SHA_MISMATCH"));
            when(service.inspect(74, "P-01", "P-01-01(TOP)", "I-PP11001", "alice"))
                    .thenReturn(blockedResult(74, "P-01", "REVIEW_REQUIRED", "BLOCKED", true, true,
                            "REVIEW_REQUIRED_CANDIDATE_NOT_AUTHORIZED"));
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get(ROUTE).session(session("alice"))
                            .param("workingDocumentId", "73").param("processCode", PROCESS)
                            .param("sheetName", SHEET).param("managementNo", MANAGEMENT_NO))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decisionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.workingCopyShaVerified").value(false))
                    .andExpect(jsonPath("$.modifyAuthorized").value(false));

            MvcResult review = mvc.perform(get(ROUTE).session(session("alice"))
                            .param("workingDocumentId", "74").param("processCode", "P-01")
                            .param("sheetName", "P-01-01(TOP)").param("managementNo", "I-PP11001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.candidateStatus").value("REVIEW_REQUIRED"))
                    .andExpect(jsonPath("$.decisionStatus").value("BLOCKED"))
                    .andExpect(jsonPath("$.modifyAuthorized").value(false))
                    .andReturn();
            String body = review.getResponse().getContentAsString();
            assertThat(body).doesNotContain("/home/", "uploaded_files", "password", "token", "secret", "absolutePath", "filePath");
        }
    }

    private void assertRouteAbsent(boolean profileEnabled, boolean copiesEnabled) throws Exception {
        try (var context = context(profileEnabled, copiesEnabled)) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(validGet(session("alice"), 71)).andExpect(status().isNotFound());
        }
    }

    private AnnotationConfigWebApplicationContext context(boolean profileEnabled, boolean copiesEnabled) {
        return context(profileEnabled, copiesEnabled, mock(ProfilePreflightService.class), "alice");
    }

    private AnnotationConfigWebApplicationContext context(boolean profileEnabled, boolean copiesEnabled,
            String loginUsername) {
        return context(profileEnabled, copiesEnabled, mock(ProfilePreflightService.class), loginUsername);
    }

    private AnnotationConfigWebApplicationContext context(boolean profileEnabled, boolean copiesEnabled,
            ProfilePreflightService preflightService) {
        return context(profileEnabled, copiesEnabled, preflightService, "alice");
    }

    private AnnotationConfigWebApplicationContext context(boolean profileEnabled, boolean copiesEnabled,
            ProfilePreflightService preflightService, String loginUsername) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pilot09aFlags", Map.of(
                "app.profile-preflight.enabled", Boolean.toString(profileEnabled),
                "app.user-working-copies.enabled", Boolean.toString(copiesEnabled),
                "pilot09a.login.username", loginUsername,
                "pilot09a.login.password-hash", TEST_PASSWORD_HASH)));
        context.addBeanFactoryPostProcessor(beanFactory ->
                beanFactory.registerSingleton("profilePreflightService", preflightService));
        context.register(MvcTestConfiguration.class, OperatorProfilePreflightController.class,
                OperatorSessionController.class);
        context.refresh();
        return context;
    }

    private MockHttpSession authenticatedSession(MockMvc mvc, String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();
        Object session = result.getRequest().getSession(false);
        assertThat(session).isInstanceOf(MockHttpSession.class);
        return (MockHttpSession) session;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private MockHttpServletRequestBuilder validGet(MockHttpSession session, long id) {
        MockHttpServletRequestBuilder request = get(ROUTE)
                .param("workingDocumentId", Long.toString(id))
                .param("processCode", PROCESS).param("sheetName", SHEET).param("managementNo", MANAGEMENT_NO);
        return session == null ? request : request.session(session);
    }

    private MockHttpSession session(String username) {
        MockHttpSession session = new MockHttpSession();
        if (username != null) session.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, username);
        return session;
    }

    private PreflightResult blockedResult(long workingDocumentId, String process, String candidateStatus, String decision,
            boolean sourceShaVerified, boolean workingCopyShaVerified, String blocker) {
        String sheet = "P-01".equals(process) ? "P-01-01(TOP)" : SHEET;
        String management = "P-01".equals(process) ? "I-PP11001" : MANAGEMENT_NO;
        return new PreflightResult("READ_ONLY_REVIEW_ONLY", workingDocumentId, process, sheet, management,
                "a".repeat(64), "b".repeat(64), sourceShaVerified, workingCopyShaVerified,
                sourceShaVerified && workingCopyShaVerified, true, "candidate-review", candidateStatus,
                "profile-existing", "1.0.0", "VERIFIED", "STRUCTURE_CHECK_PASS", decision, "BLOCKED",
                List.of("candidate-review"), List.of(blocker), false,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", List.of(),
                new MergeReview(List.of(), List.of(), List.of(), false, false), List.of(), List.of(),
                List.of(blocker), List.of(blocker, "MODIFY_NOT_AUTHORIZED_BY_PREFLIGHT"),
                List.of("INDEPENDENT_HUMAN_REVIEW_AND_EXPLICIT_APPROVAL_REQUIRED"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcTestConfiguration {
        @Bean
        OperatorLoginService operatorLoginService(
                @Value("${pilot09a.login.username}") String username,
                @Value("${pilot09a.login.password-hash}") String passwordHash) {
            return new OperatorLoginService(username, passwordHash, mock(JdbcTemplate.class));
        }

        @Bean
        com.example.processautomation.material.FlowMappingReviewService flowMappingReviewService() {
            return mock(com.example.processautomation.material.FlowMappingReviewService.class);
        }
    }
}
