package com.example.processautomation.api;

import com.example.processautomation.material.NumericWorkingCopyService;
import com.example.processautomation.material.ProcessWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@RestController
@RequestMapping("/api/workspace")
public class OperatorWorkspaceController {
    private static final String SESSION_REQUESTS = "operator.workspace.request-ids";
    private static final long APPROVED_WORKING_COPY_ID = 36L;
    private static final String SUPPORTED_PROCESS = "PW-01";
    private static final String SUPPORTED_SHEET = "PW-01-05";
    private static final String SUPPORTED_PLACEMENT = "PW01-05-BETWEEN-C-PW15009-C-PW15010";
    private static final String APPROVED_CONVERSION = "PW01-OFFICIAL-NUMERIC-V1";
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OperatorLoginService login;
    private final NumericWorkingCopyService workingCopies;
    private final ProcessWorkspaceService workspace;

    public OperatorWorkspaceController(OperatorLoginService login, NumericWorkingCopyService workingCopies,
                                       ProcessWorkspaceService workspace) {
        this.login = login;
        this.workingCopies = workingCopies;
        this.workspace = workspace;
    }

    @GetMapping("/process-documents")
    public ResponseEntity<List<Map<String, Object>>> documents(HttpSession session) {
        login.requireAuthenticated(session);
        List<Map<String, Object>> summaries = workingCopies.listProcessDocuments().stream().map(document -> Map.<String, Object>of(
                "baselineDocumentId", document.get("baselineDocumentId"),
                "processCode", document.get("processCode"),
                "officialFilename", document.get("officialFilename"),
                "workingDocumentId", document.get("workingDocumentId") == null ? 0L : document.get("workingDocumentId"),
                "workingFilename", document.get("workingFilename") == null ? "" : document.get("workingFilename"),
                "conversionStatus", document.get("conversionStatus") == null ? "" : document.get("conversionStatus")))
                .toList();
        return noStore(summaries);
    }

    @GetMapping("/working-copies/{workingDocumentId}")
    public ResponseEntity<Map<String, Object>> workingCopy(HttpSession session, @PathVariable long workingDocumentId) {
        login.requireAuthenticated(session);
        if (workingDocumentId != APPROVED_WORKING_COPY_ID) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "검증된 PW-01 작업본을 찾지 못했습니다.");
        }
        NumericWorkingCopyService.WorkingCopy copy = workingCopies.getWorkingCopy(workingDocumentId);
        if (!SUPPORTED_PROCESS.equals(copy.processCode()) || !APPROVED_CONVERSION.equals(copy.conversionVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 검증 범위에 없는 작업본입니다.");
        }
        return noStore(Map.of(
                "workingDocumentId", copy.id(),
                "baselineDocumentId", copy.baselineDocumentId(),
                "processCode", copy.processCode(),
                "filename", copy.filename(),
                "conversionVersion", copy.conversionVersion(),
                "conversionStatus", copy.conversionStatus(),
                "workingSha256", copy.workingSha256()));
    }

    @PostMapping(path = "/change-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> preview(
            HttpServletRequest servletRequest,
            HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody ProcessWorkspaceService.ChangeInput request) {
        login.requireAuthenticated(session);
        login.requireCsrf(servletRequest, session, csrfToken);
        requireSupportedInput(request);
        Map<String, Object> result = workspace.preview(request);
        if (!Boolean.TRUE.equals(result.get("automaticEditAllowed"))
                || !"TEST_ONLY_READY".equals(result.get("editPlanStatus"))) {
            return noStore(result);
        }
        long requestId = ((Number) result.get("requestId")).longValue();
        rememberRequest(session, requestId);
        return noStore(result);
    }

    @PostMapping("/requests/{requestId}/generate")
    public ResponseEntity<Map<String, Object>> generate(
            HttpServletRequest servletRequest,
            HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @PathVariable long requestId) {
        login.requireAuthenticated(session);
        login.requireCsrf(servletRequest, session, csrfToken);
        requireSessionRequest(session, requestId);
        Map<String, Object> stored = workspace.request(requestId);
        requireSessionAddRequest(stored);
        return noStore(workspace.generate(requestId));
    }

    @GetMapping("/requests/{requestId}/download")
    public ResponseEntity<byte[]> download(HttpSession session, @PathVariable long requestId) {
        login.requireAuthenticated(session);
        requireSessionRequest(session, requestId);
        Map<String, Object> stored = workspace.request(requestId);
        requireSessionAddRequest(stored);
        if (!"GENERATED".equals(stored.get("requestStatus"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "생성 완료된 Excel만 다운로드할 수 있습니다.");
        }
        ProcessWorkspaceService.GeneratedFile file = workspace.download(requestId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", file.sha256());
        headers.set("X-Official-Baseline-Modified", "false");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(file.bytes());
    }

    private void requireSupportedInput(ProcessWorkspaceService.ChangeInput request) {
        if (request == null || request.workingDocumentId() != APPROVED_WORKING_COPY_ID
                || !SUPPORTED_SHEET.equals(request.sheetName())
                || !"ADD".equalsIgnoreCase(request.changeType()) || !SUPPORTED_PLACEMENT.equals(request.placementId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "현재는 검증된 PW-01-05 작업본의 지정 위치에 설비 추가만 사용할 수 있습니다.");
        }
    }

    private void requireSessionAddRequest(Map<String, Object> stored) {
        Object requestObject = stored.get("request");
        if (!(requestObject instanceof Map<?, ?> request)
                || !"ADD".equals(String.valueOf(request.get("changeType")))
                || !(request.get("workingDocumentId") instanceof Number workingId)
                || workingId.longValue() != APPROVED_WORKING_COPY_ID
                || !SUPPORTED_SHEET.equals(String.valueOf(request.get("sheetName")))
                || !SUPPORTED_PLACEMENT.equals(String.valueOf(request.get("placementId")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 요청은 현재 사용자 업무 범위에서 사용할 수 없습니다.");
        }
    }

    private void rememberRequest(HttpSession session, long requestId) {
        synchronized (session) {
            Object current = session.getAttribute(SESSION_REQUESTS);
            @SuppressWarnings("unchecked")
            Set<Long> ids = current instanceof Set<?> set ? (Set<Long>) set : new LinkedHashSet<>();
            ids.add(requestId);
            while (ids.size() > 200) ids.remove(ids.iterator().next());
            session.setAttribute(SESSION_REQUESTS, ids);
        }
    }

    private void requireSessionRequest(HttpSession session, long requestId) {
        synchronized (session) {
            Object current = session.getAttribute(SESSION_REQUESTS);
            if (!(current instanceof Set<?> ids) || !ids.contains(requestId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이 로그인에서 만든 요청을 찾지 못했습니다.");
            }
        }
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
