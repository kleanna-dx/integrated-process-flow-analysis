package com.example.processautomation.api;

import com.example.processautomation.material.PipeChangeSyntheticWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Session-scoped access to the server-managed synthetic fixture; never accepts client-supplied drawing mappings. */
@RestController
@RequestMapping("/api/workspace/diagram-editor/pipe-change")
public class OperatorPipeChangeController {
    private static final String PREVIEW_KEY = "operator.pipe-change.preview";
    private static final String GENERATED_KEY = "operator.pipe-change.generated";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OperatorLoginService login;
    private final PipeChangeSyntheticWorkflowService workflow;
    public OperatorPipeChangeController(OperatorLoginService login, PipeChangeSyntheticWorkflowService workflow) {
        this.login = login; this.workflow = workflow;
    }

    @GetMapping("/fixture")
    public ResponseEntity<byte[]> downloadFixture(HttpSession session) {
        String user = login.requireAuthenticated(session);
        var fixture = workflow.fixtureForUser(user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("PIPE_CHANGE_SYNTHETIC_FIXTURE_V1.xlsx", StandardCharsets.UTF_8).build().toString())
                .header("X-Source-SHA256", fixture.sha256())
                .header("X-Test-Only", "true")
                .header("X-Official-Baseline-Modified", "false")
                .body(fixture.bytes());
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipeChangeSyntheticWorkflowService.Preview> preview(HttpServletRequest request,
            HttpSession session, @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewRequest body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        if (!"SERVER_SYNTHETIC_FIXTURE_V1".equals(body.fixtureId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only the server-managed synthetic fixture is supported.");
        var preview = workflow.preview(user, body.workingDocumentId(), body.expectedWorkingSha256(), body.rationale());
        @SuppressWarnings("unchecked")
        Map<String, SessionPreview> previews = (Map<String, SessionPreview>) session.getAttribute(PREVIEW_KEY);
        if (previews == null) previews = new LinkedHashMap<>();
        previews.put(preview.previewId(), new SessionPreview(user, preview));
        session.setAttribute(PREVIEW_KEY, previews);
        session.removeAttribute(GENERATED_KEY);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> generate(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewIdRequest body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        SessionPreview scopedPreview = previews(session).get(body.previewId());
        if (scopedPreview == null || !user.equals(scopedPreview.user()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No PIPE_CHANGE preview exists in this login session.");
        var generated = workflow.generate(scopedPreview.preview(), user);
        @SuppressWarnings("unchecked")
        Map<String, SessionGenerated> outputs = (Map<String, SessionGenerated>) session.getAttribute(GENERATED_KEY);
        if (outputs == null) outputs = new LinkedHashMap<>();
        outputs.put(generated.previewId(), new SessionGenerated(user, generated.outputId(), generated));
        session.setAttribute(GENERATED_KEY, outputs);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previewId", generated.previewId());
        metadata.put("testOnlyOutputId", generated.outputId());
        metadata.put("outputSha256", generated.outputSha256());
        metadata.put("sourceFixtureSha256", generated.sourceFixtureSha256());
        metadata.put("assessmentFingerprint", generated.analysisFingerprint());
        metadata.put("mappingComparison", generated.mappingComparison());
        metadata.put("calculationComparison", generated.calculationComparison());
        metadata.put("mode", generated.mode());
        metadata.put("officialReadiness", generated.officialReadiness());
        metadata.put("officialBaselineModified", generated.officialBaselineModified());
        metadata.put("notice", generated.notice());
        metadata.put("downloadUri", "/api/workspace/diagram-editor/pipe-change/download/" + generated.previewId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metadata);
    }

    @GetMapping("/download/{previewId}")
    public ResponseEntity<byte[]> download(HttpSession session, @PathVariable String previewId) {
        String user = login.requireAuthenticated(session);
        SessionGenerated scoped = generated(session).get(previewId);
        if (scoped == null || !user.equals(scoped.user()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No TEST_ONLY PIPE_CHANGE output exists in this login session.");
        var result = workflow.download(scoped.outputId(), user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Output-SHA256", result.outputSha256())
                .header("X-Source-SHA256", scoped.generated().sourceFixtureSha256())
                .header("X-Test-Only", "true")
                .header("X-Official-Baseline-Modified", "false")
                .header("X-Official-Readiness", "CALCULATION_UNAVAILABLE")
                .header("X-Connection-Evidence", "NOT_VERIFIED")
                .body(result.bytes());
    }

    private Map<String, SessionPreview> previews(HttpSession session) {
        Object value = session.getAttribute(PREVIEW_KEY);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked") Map<String, SessionPreview> typed = (Map<String, SessionPreview>) map;
            return typed;
        }
        return Map.of();
    }
    private Map<String, SessionGenerated> generated(HttpSession session) {
        Object value = session.getAttribute(GENERATED_KEY);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked") Map<String, SessionGenerated> typed = (Map<String, SessionGenerated>) map;
            return typed;
        }
        return Map.of();
    }
    public record PreviewRequest(@NotBlank String fixtureId, @Positive long workingDocumentId,
                                 @NotBlank String expectedWorkingSha256, @NotBlank String rationale) { }
    public record PreviewIdRequest(@NotBlank String previewId) { }
    private record SessionPreview(String user, PipeChangeSyntheticWorkflowService.Preview preview) { }
    private record SessionGenerated(String user, long outputId,
                                    PipeChangeSyntheticWorkflowService.Generated generated) { }
}
