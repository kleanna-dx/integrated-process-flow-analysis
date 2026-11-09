package com.example.processautomation.api;

import com.example.processautomation.material.FlowChangeAuthenticatedWorkflowService;
import com.example.processautomation.material.FlowChangeSyntheticWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated session-scoped FLOW_CHANGE API, limited to the byte-identical server-owned fixture. */
@RestController
@RequestMapping("/api/workspace/diagram-editor/flow-change")
public class OperatorFlowChangeController {
    private static final String PREVIEWS_KEY = "operator.flow-change.previews";
    private static final String GENERATED_KEY = "operator.flow-change.generated";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OperatorLoginService login;
    private final FlowChangeAuthenticatedWorkflowService workflow;

    public OperatorFlowChangeController(OperatorLoginService login,
                                        FlowChangeAuthenticatedWorkflowService workflow) {
        this.login = login;
        this.workflow = workflow;
    }

    @GetMapping("/fixture")
    public ResponseEntity<byte[]> downloadFixture(HttpSession session) {
        String username = login.requireAuthenticated(session);
        FlowChangeSyntheticWorkflowService.FixtureDownload fixture = workflow.fixtureForUser(username);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("P-02_FLOW_CHANGE_SYNTHETIC_FIXTURE_V1.xlsx", StandardCharsets.UTF_8).build().toString())
                .header("X-Fixture-ID", fixture.fixtureId())
                .header("X-Fixture-SHA256", fixture.sha256())
                .header("X-Test-Only", "true")
                .header("X-Official-Baseline-Modified", "false")
                .body(fixture.bytes());
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResponse> preview(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        var preview = workflow.preview(username, body.workingDocumentId(), body.expectedWorkingSha256(),
                body.fixtureId(), body.expectedFixtureSha256(), body.scenarioId(), body.rationale());
        Map<String, SessionPreview> previews = previews(session);
        previews.put(preview.previewId(), new SessionPreview(username, preview));
        trim(previews, 12);
        session.setAttribute(PREVIEWS_KEY, previews);
        Map<String, SessionGenerated> generated = generated(session);
        generated.remove(preview.previewId());
        session.setAttribute(GENERATED_KEY, generated);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(preview));
    }

    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GeneratedResponse> generate(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewIdRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        FlowChangeAuthenticatedWorkflowService.Generated output;
        synchronized (session) {
            SessionPreview scoped = previews(session).get(body.previewId());
            if (scoped == null || !username.equals(scoped.username()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No FLOW_CHANGE preview exists in this login session.");
            Map<String, SessionGenerated> generated = generated(session);
            SessionGenerated existing = generated.get(body.previewId());
            if (existing != null && username.equals(existing.username())) output = existing.output();
            else {
                output = workflow.generate(scoped.preview(), username);
                generated.put(output.previewId(), new SessionGenerated(username, output));
                trim(generated, 12);
                session.setAttribute(GENERATED_KEY, generated);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(output));
    }

    @GetMapping("/download/{previewId}")
    public ResponseEntity<byte[]> download(HttpSession session, @PathVariable String previewId) {
        String username = login.requireAuthenticated(session);
        SessionGenerated scoped = generated(session).get(previewId);
        if (scoped == null || !username.equals(scoped.username()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No FLOW_CHANGE output exists in this login session.");
        var output = scoped.output();
        var artifact = workflow.download(output.outputId(), username, output.workingDocumentId(), output.outputSha256());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(artifact.bytes().length))
                .header("X-Output-SHA256", artifact.outputSha256())
                .header("X-Fixture-SHA256", output.fixtureSha256())
                .header("X-Test-Only", "true")
                .header("X-Review-Status", artifact.reviewStatus())
                .header("X-Official-Readiness", "CALCULATION_UNAVAILABLE")
                .header("X-Official-Baseline-Modified", "false")
                .header("X-Connection-Evidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE")
                .body(artifact.bytes());
    }

    private PreviewResponse toResponse(FlowChangeAuthenticatedWorkflowService.Preview preview) {
        return new PreviewResponse(preview.previewId(), preview.proposalId(), preview.workingDocumentId(),
                preview.processCode(), preview.workingSha256(), preview.fixtureId(), preview.fixtureSha256(),
                preview.analysisFingerprint(), preview.scenarioId(), preview.beforeQuantity(),
                preview.requestedQuantity(), preview.calculatedEdgeQuantity(), preview.calculationSummary(),
                preview.labelChanges(), preview.noOp(), preview.reviewStatus(), preview.actualReadiness(),
                preview.officialBaselineModified(), preview.noOp()
                ? "No-op: the fixture already has this flow; no proposal artifact will be generated."
                : "/api/workspace/diagram-editor/flow-change/download/" + preview.previewId());
    }

    private GeneratedResponse toResponse(FlowChangeAuthenticatedWorkflowService.Generated output) {
        return new GeneratedResponse(output.previewId(), output.proposalId(), output.workingDocumentId(),
                output.processCode(), output.scenarioId(), output.beforeQuantity(), output.generatedQuantity(),
                output.outputId(), output.filename(), output.outputSha256(), output.fixtureSha256(),
                output.analysisFingerprint(), output.labelChanges(), output.calculationSummary(), output.mode(),
                output.reviewStatus(), output.officialReadiness(), output.officialBaselineModified(),
                "/api/workspace/diagram-editor/flow-change/download/" + output.previewId(), output.notice());
    }

    @SuppressWarnings("unchecked")
    private Map<String, SessionPreview> previews(HttpSession session) {
        Object value = session.getAttribute(PREVIEWS_KEY);
        return value instanceof Map<?, ?> ? (Map<String, SessionPreview>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, SessionGenerated> generated(HttpSession session) {
        Object value = session.getAttribute(GENERATED_KEY);
        return value instanceof Map<?, ?> ? (Map<String, SessionGenerated>) value : new LinkedHashMap<>();
    }

    private <T> void trim(Map<String, T> values, int maximum) {
        while (values.size() > maximum) values.remove(values.keySet().iterator().next());
    }

    public record PreviewRequest(@Positive long workingDocumentId,
                                 @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String expectedWorkingSha256,
                                 @NotBlank String fixtureId,
                                 @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String expectedFixtureSha256,
                                 @NotBlank @Pattern(regexp = "FLOW-(20|10|50)") String scenarioId,
                                 @NotBlank String rationale) { }
    public record PreviewIdRequest(@NotBlank String previewId) { }
    public record PreviewResponse(String previewId, String proposalId, long workingDocumentId,
                                  String processCode, String workingSha256, String fixtureId, String fixtureSha256,
                                  String analysisFingerprint, String scenarioId, java.math.BigDecimal beforeQuantity,
                                  java.math.BigDecimal requestedQuantity, java.math.BigDecimal calculatedEdgeQuantity,
                                  Map<String, Object> calculation, java.util.List<com.example.processautomation.excel.FlowChangeDrawingEngine.LabelChange> labelChanges,
                                  boolean noOp, String reviewStatus, String actualReadiness,
                                  boolean officialBaselineModified, String downloadUri) { }
    public record GeneratedResponse(String previewId, String proposalId, long workingDocumentId,
                                    String processCode, String scenarioId, java.math.BigDecimal beforeQuantity,
                                    java.math.BigDecimal generatedQuantity, long outputId, String filename,
                                    String outputSha256, String fixtureSha256, String analysisFingerprint,
                                    java.util.List<com.example.processautomation.excel.FlowChangeDrawingEngine.LabelChange> labelChanges,
                                    Map<String, Object> calculation, String mode, String reviewStatus,
                                    String actualReadiness, boolean officialBaselineModified,
                                    String downloadUri, String notice) { }
    private record SessionPreview(String username, FlowChangeAuthenticatedWorkflowService.Preview preview) { }
    private record SessionGenerated(String username, FlowChangeAuthenticatedWorkflowService.Generated output) { }
}
