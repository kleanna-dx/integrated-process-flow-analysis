package com.example.processautomation.api;

import com.example.processautomation.material.ChangeProposalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

/** Authenticated per-operator append-only change proposals. */
@RestController
@RequestMapping("/api/workspace/change-proposals")
public class OperatorChangeProposalController {
    private final OperatorLoginService login;
    private final ChangeProposalService proposals;

    public OperatorChangeProposalController(OperatorLoginService login, ChangeProposalService proposals) {
        this.login = login;
        this.proposals = proposals;
    }

    @GetMapping
    public ResponseEntity<java.util.List<ChangeProposalService.ProposalView>> list(HttpSession session,
            @RequestParam(defaultValue = "50") int limit) {
        String user = login.requireAuthenticated(session);
        return noStore(proposals.list(user, limit));
    }

    @GetMapping("/{proposalId}")
    public ResponseEntity<ChangeProposalService.ProposalView> detail(HttpSession session,
            @PathVariable String proposalId) {
        String user = login.requireAuthenticated(session);
        return noStore(proposals.detail(user, proposalId));
    }

    @GetMapping(path = "/{proposalId}/what-if/export")
    public ResponseEntity<byte[]> exportWhatIf(HttpSession session, @PathVariable String proposalId) {
        String user = login.requireAuthenticated(session);
        ChangeProposalService.ProposalWorkbook workbook = proposals.exportWhatIf(user, proposalId);
        String filename = "what-if-" + workbook.sha256().substring(0, 12) + "-TEST_ONLY.xlsx";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Output-SHA256", workbook.sha256())
                .header("X-Test-Only", "true")
                .header("X-Official-Baseline-Modified", "false")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbook.bytes());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChangeProposalService.ProposalView> create(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody ProposalRequest body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        var input = new ChangeProposalService.ProposalInput(body.workingDocumentId(), body.processCode(),
                body.sheetName(), body.changeType(), body.managementNo(), body.workingSha256(), body.beforeValues(),
                body.afterValues(), body.rationale(), body.evidence());
        return noStore(proposals.create(user, input));
    }

    @PostMapping(path = "/{proposalId}/what-if", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChangeProposalService.ProposalView> runSyntheticWhatIf(HttpServletRequest request,
            HttpSession session, @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @PathVariable String proposalId, @Valid @RequestBody ChangeProposalService.WhatIfComparisonInput body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        return noStore(proposals.recordWhatIf(user, proposalId, body));
    }

    @PostMapping(path = "/{proposalId}/test-only-output", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChangeProposalService.ProposalView> recordTestOnlyOutput(HttpServletRequest request,
            HttpSession session, @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @PathVariable String proposalId, @Valid @RequestBody TestOnlyOutputRequest body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        return noStore(proposals.recordTestOnlyOutput(user, proposalId,
                new ChangeProposalService.TestOnlyOutput(body.workingDocumentId(), body.processCode(), body.sheetName(),
                        body.workingSha256(), body.outputSha256(), body.validationResults())));
    }

    @PostMapping(path = "/{proposalId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChangeProposalService.ProposalView> addNote(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @PathVariable String proposalId, @Valid @RequestBody ReviewNoteRequest body) {
        String user = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        return noStore(proposals.addReviewNote(user, proposalId,
                new ChangeProposalService.ReviewNote(body.note(), body.evidence())));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record ProposalRequest(long workingDocumentId, @NotBlank String processCode,
                                  @NotBlank String sheetName, @NotBlank String changeType,
                                  String managementNo, @NotBlank String workingSha256,
                                  java.util.Map<String, Object> beforeValues,
                                  java.util.Map<String, Object> afterValues,
                                  @NotBlank String rationale,
                                  java.util.Map<String, Object> evidence) { }
    public record ReviewNoteRequest(@NotBlank String note, java.util.Map<String, Object> evidence) { }
    public record TestOnlyOutputRequest(long workingDocumentId, @NotBlank String processCode,
                                        @NotBlank String sheetName, @NotBlank String workingSha256,
                                        @NotBlank String outputSha256,
                                        java.util.Map<String, Object> validationResults) { }
}
