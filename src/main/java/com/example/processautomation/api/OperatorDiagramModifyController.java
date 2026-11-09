package com.example.processautomation.api;

import com.example.processautomation.material.DiagramModifyService;
import com.example.processautomation.material.UserWorkingCopyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Session-isolated, CSRF-protected TEST_ONLY modify workflow for exact master-linked fields. */
@RestController
@RequestMapping("/api/workspace/diagram-editor/modify")
public class OperatorDiagramModifyController {
    private static final String PREVIEWS = "operator.diagram-modify.previews";
    private static final String GENERATED = "operator.diagram-modify.generated";
    private static final String DELETE_PREVIEWS = "operator.diagram-delete.previews";
    private static final String DELETE_GENERATED = "operator.diagram-delete.generated";
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OperatorLoginService login;
    private final DiagramModifyService modifies;
    private final UserWorkingCopyService userWorkingCopies;

    public OperatorDiagramModifyController(OperatorLoginService login, DiagramModifyService modifies,
                                           UserWorkingCopyService userWorkingCopies) {
        this.login = login;
        this.modifies = modifies;
        this.userWorkingCopies = userWorkingCopies;
    }

    @GetMapping("/delete-impact")
    public ResponseEntity<com.example.processautomation.excel.DeleteImpactAnalyzer.Assessment> deleteImpact(
            HttpSession session, @RequestParam @Positive long workingDocumentId,
            @RequestParam @NotBlank String sheetName, @RequestParam @NotBlank String managementNo) {
        String username = login.requireAuthenticated(session);
        return noStore(modifies.assessDelete(workingDocumentId, sheetName, managementNo, username));
    }

    @PostMapping(path = "/delete-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagramModifyService.DeletePreview> deletePreview(HttpServletRequest request,
            HttpSession session, @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody DeletePreviewRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        var preview = modifies.previewDelete(new DiagramModifyService.DeleteInput(body.workingDocumentId(),
                body.sheetName(), body.managementNo(), body.expectedWorkingSha256(),
                body.analysisFingerprint(), body.rationale()), username);
        @SuppressWarnings("unchecked")
        Map<String, DiagramModifyService.DeletePreview> previews =
                (Map<String, DiagramModifyService.DeletePreview>) session.getAttribute(DELETE_PREVIEWS);
        if (previews == null) previews = new java.util.LinkedHashMap<>();
        previews.put(preview.previewId(), preview);
        session.setAttribute(DELETE_PREVIEWS, previews);
        session.removeAttribute(DELETE_GENERATED);
        return noStore(preview);
    }

    @PostMapping(path = "/delete-generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deleteGenerate(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewIdRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        @SuppressWarnings("unchecked")
        Map<String, DiagramModifyService.DeletePreview> previews =
                (Map<String, DiagramModifyService.DeletePreview>) session.getAttribute(DELETE_PREVIEWS);
        var preview = previews == null ? null : previews.get(body.previewId());
        if (preview == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No DELETE preview exists in this login session.");
        var generated = modifies.generateDelete(preview, username);
        session.setAttribute(DELETE_GENERATED, generated);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("previewId", generated.previewId());
        metadata.put("proposalId", generated.proposalId());
        metadata.put("testOnlyOutputId", generated.outputId());
        metadata.put("workingDocumentId", generated.workingDocumentId());
        metadata.put("processCode", generated.processCode());
        metadata.put("sheetName", generated.sheetName());
        metadata.put("managementNo", generated.managementNo());
        metadata.put("boxTopLeft", generated.boxTopLeft());
        metadata.put("boxBottomRight", generated.boxBottomRight());
        metadata.put("affectedCells", generated.affectedCells());
        metadata.put("sourceSha256", generated.sourceSha256());
        metadata.put("outputSha256", generated.outputSha256());
        metadata.put("analysisFingerprint", generated.analysisFingerprint());
        metadata.put("mode", generated.mode());
        metadata.put("artifactKind", generated.artifactKind());
        metadata.put("reviewStatus", generated.reviewStatus());
        metadata.put("connectionEvidenceStatus", generated.connectionEvidenceStatus());
        metadata.put("officialReadiness", generated.officialReadiness());
        metadata.put("officialBaselineModified", false);
        metadata.put("filename", safe(generated.processCode()) + "_" + safe(generated.sheetName())
                + "_TEST_ONLY_DELETE_" + safe(generated.managementNo()) + ".xlsx");
        return noStore(metadata);
    }

    @GetMapping("/delete-download/{previewId}")
    public ResponseEntity<byte[]> deleteDownload(HttpSession session, @PathVariable String previewId) {
        login.requireAuthenticated(session);
        Object value = session.getAttribute(DELETE_GENERATED);
        if (!(value instanceof DiagramModifyService.DeleteGenerated generated)
                || !generated.previewId().equals(previewId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No TEST_ONLY DELETE output exists in this login session.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(
                safe(generated.processCode()) + "_" + safe(generated.sheetName()) + "_TEST_ONLY_DELETE_"
                        + safe(generated.managementNo()) + ".xlsx", StandardCharsets.UTF_8).build());
        headers.setContentLength(generated.workbookBytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", generated.outputSha256());
        headers.set("X-Source-SHA256", generated.sourceSha256());
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Connection-Evidence", "NOT_VERIFIED");
        headers.set("X-Official-Readiness", "CALCULATION_UNAVAILABLE");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(generated.workbookBytes());
    }

    @GetMapping("/equipment-options")
    public ResponseEntity<java.util.List<DiagramModifyService.ModifyEquipmentOption>> equipmentOptions(HttpSession session,
            @RequestParam @Positive long workingDocumentId, @RequestParam @NotBlank String sheetName) {
        String username = login.requireAuthenticated(session);
        return noStore(modifies.listVerifiedPilotEquipment(workingDocumentId, sheetName, username));
    }

    @GetMapping("/equipment")
    public ResponseEntity<DiagramModifyService.EquipmentProfile> inspect(HttpSession session,
            @RequestParam @Positive long workingDocumentId, @RequestParam @NotBlank String sheetName,
            @RequestParam @NotBlank String managementNo) {
        String username = login.requireAuthenticated(session);
        return noStore(modifies.inspect(workingDocumentId, sheetName, managementNo, username));
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagramModifyService.ModifyPreview> preview(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody ModifyPreviewRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        var preview = modifies.preview(new DiagramModifyService.ModifyInput(body.workingDocumentId(),
                body.sheetName(), body.managementNo(), body.replacements(), body.expectedCurrentValues()), username);
        @SuppressWarnings("unchecked")
        Map<String, DiagramModifyService.ModifyPreview> previews =
                (Map<String, DiagramModifyService.ModifyPreview>) session.getAttribute(PREVIEWS);
        if (previews == null) previews = new java.util.LinkedHashMap<>();
        previews.put(preview.previewId(), preview);
        session.setAttribute(PREVIEWS, previews);
        session.removeAttribute(GENERATED);
        return noStore(preview);
    }

    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> generate(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewIdRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        @SuppressWarnings("unchecked")
        Map<String, DiagramModifyService.ModifyPreview> previews =
                (Map<String, DiagramModifyService.ModifyPreview>) session.getAttribute(PREVIEWS);
        var preview = previews == null ? null : previews.get(body.previewId());
        if (preview == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No modify preview exists in this login session.");
        var generated = modifies.generate(preview, username);
        previews.remove(body.previewId());
        session.setAttribute(PREVIEWS, previews);
        session.setAttribute(GENERATED, generated);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("previewId", generated.previewId());
        if (generated.proposalId() != null) metadata.put("proposalId", generated.proposalId());
        if (generated.testOnlyOutputId() != null) metadata.put("testOnlyOutputId", generated.testOnlyOutputId());
        metadata.put("workingDocumentId", generated.workingDocumentId());
        metadata.put("processCode", generated.processCode());
        metadata.put("sheetName", generated.sheetName());
        metadata.put("managementNo", generated.managementNo());
        metadata.put("boxTopLeft", generated.boxTopLeft());
        metadata.put("boxBottomRight", generated.boxBottomRight());
        metadata.put("affectedCells", generated.affectedCells());
        metadata.put("beforeValues", generated.beforeValues());
        metadata.put("afterValues", generated.afterValues());
        metadata.put("sourceSha256", generated.sourceSha256());
        metadata.put("outputSha256", generated.outputSha256());
        metadata.put("mode", generated.mode());
        metadata.put("structuralEditStatus", generated.structuralEditStatus());
        metadata.put("connectionEvidenceStatus", generated.connectionEvidenceStatus());
        metadata.put("profileId", generated.profileId());
        metadata.put("profileVersion", generated.profileVersion());
        metadata.put("profileStatus", generated.profileStatus());
        metadata.put("profileEvidenceLevel", generated.profileEvidenceLevel());
        metadata.put("profileShaMatch", generated.profileShaMatch());
        metadata.put("workingCopyShaMatch", generated.workingCopyShaMatch());
        metadata.put("reviewStatus", generated.reviewStatus());
        metadata.put("officialBaselineModified", false);
        metadata.put("interpretation", generated.interpretation());
        if (generated.testOnlyOutputId() != null) {
            metadata.put("testOnlyOutputStatus", "TEST_ONLY");
            metadata.put("testOnlyOutputReviewStatus", "REVIEW_REQUIRED");
        }
        return noStore(metadata);
    }

    @GetMapping("/download/{previewId}")
    public ResponseEntity<byte[]> download(HttpSession session, @PathVariable String previewId) {
        String username = login.requireAuthenticated(session);
        Object value = session.getAttribute(GENERATED);
        if (!(value instanceof DiagramModifyService.ModifyGenerated generated)
                || !generated.previewId().equals(previewId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No TEST_ONLY modify output exists in this login session.");
        }
        byte[] downloadBytes = generated.workbookBytes();
        String filename = safe(generated.processCode()) + "_" + safe(generated.sheetName())
                + "_TEST_ONLY_MODIFY_" + safe(generated.managementNo()) + ".xlsx";
        if (generated.testOnlyOutputId() != null) {
            userWorkingCopies.requireEnabled();
            var artifact = userWorkingCopies.downloadTestOnlyOutput(generated.testOnlyOutputId(), username);
            if (!artifact.outputSha256().equalsIgnoreCase(generated.outputSha256())
                    || artifact.workingDocumentId() != generated.workingDocumentId()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Stored output does not match this session's generated MODIFY result.");
            }
            downloadBytes = artifact.bytes();
            filename = artifact.filename();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(downloadBytes.length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", generated.outputSha256());
        headers.set("X-Source-SHA256", generated.sourceSha256());
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Connection-Evidence", "NOT_VERIFIED");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(downloadBytes);
    }

    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record ModifyPreviewRequest(@Positive long workingDocumentId, @NotBlank String sheetName,
                                       @NotBlank String managementNo, Map<String, String> replacements,
                                       Map<String, String> expectedCurrentValues) { }
    public record DeletePreviewRequest(@Positive long workingDocumentId, @NotBlank String sheetName,
                                       @NotBlank String managementNo, @NotBlank String expectedWorkingSha256,
                                       @NotBlank String analysisFingerprint, @NotBlank String rationale) { }
    public record PreviewIdRequest(@NotBlank String previewId) { }
}
