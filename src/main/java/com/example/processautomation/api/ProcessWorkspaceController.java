package com.example.processautomation.api;

import com.example.processautomation.excel.Pw0105MappingReviewWorkbookExporter;
import com.example.processautomation.material.NumericWorkingCopyService;
import com.example.processautomation.material.ProcessWorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/workspace")
public class ProcessWorkspaceController {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ApiAccessGuard access;
    private final NumericWorkingCopyService workingCopies;
    private final ProcessWorkspaceService workspace;
    private final Pw0105MappingReviewWorkbookExporter mappingReviewExporter;

    public ProcessWorkspaceController(ApiAccessGuard access, NumericWorkingCopyService workingCopies,
                                      ProcessWorkspaceService workspace,
                                      Pw0105MappingReviewWorkbookExporter mappingReviewExporter) {
        this.access = access;
        this.workingCopies = workingCopies;
        this.workspace = workspace;
        this.mappingReviewExporter = mappingReviewExporter;
    }

    @GetMapping("/process-documents")
    public List<Map<String, Object>> documents(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return workingCopies.listProcessDocuments();
    }

    @PostMapping("/process-documents/{baselineDocumentId}/numeric-working-copy")
    public Map<String, Object> createWorkingCopy(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long baselineDocumentId) {
        access.requireToken(token);
        return workingCopies.createPw01Copy(baselineDocumentId);
    }

    @PostMapping("/process-documents/{baselineDocumentId}/official-numeric-candidate")
    public Map<String, Object> createOfficialNumericCandidate(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long baselineDocumentId) {
        access.requireToken(token);
        return workingCopies.createOfficialNumericCandidate(baselineDocumentId);
    }

    @GetMapping("/modify-targets")
    public List<Map<String, Object>> modifyTargets(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam long workingDocumentId,
            @RequestParam(defaultValue = "PW-01-05") String sheetName) {
        access.requireToken(token);
        return workspace.listModifyTargets(workingDocumentId, sheetName);
    }

    @GetMapping("/modify-targets/review-workbook")
    public ResponseEntity<byte[]> modifyMappingReviewWorkbook(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam long workingDocumentId,
            @RequestParam(defaultValue = "PW-01-05") String sheetName) {
        access.requireToken(token);
        byte[] bytes = mappingReviewExporter.export(workspace.listModifyTargets(workingDocumentId, sheetName));
        return workbook("PW-01-05_설비19건_도면매핑_검토_TEST_ONLY.xlsx", sha256(bytes), bytes);
    }

    @PostMapping(path = "/process-documents/{baselineDocumentId}/numeric-working-copy/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> registerNumericCopy(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long baselineDocumentId,
            @RequestParam("file") MultipartFile file) {
        access.requireToken(token);
        if (file == null || file.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "숫자형 작업본 파일이 비어 있습니다.");
        }
        try {
            return workingCopies.registerNumericCopy(baselineDocumentId, file.getOriginalFilename(), file.getBytes());
        } catch (java.io.IOException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "업로드 파일을 읽을 수 없습니다.", exception);
        }
    }

    @GetMapping("/working-copies/{workingDocumentId}/download")
    public ResponseEntity<byte[]> downloadWorkingCopy(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long workingDocumentId) {
        access.requireToken(token);
        NumericWorkingCopyService.WorkingCopy copy = workingCopies.getWorkingCopy(workingDocumentId);
        return workbook(copy.filename(), copy.workingSha256(), copy.bytes());
    }

    @GetMapping("/reference-case")
    public Map<String, Object> referenceCase(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return workspace.referenceCase();
    }

    @PostMapping("/change-preview")
    public Map<String, Object> preview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody ProcessWorkspaceService.ChangeInput request) {
        access.requireToken(token);
        return workspace.preview(request);
    }

    @GetMapping("/requests/{requestId}")
    public Map<String, Object> request(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long requestId) {
        access.requireToken(token);
        return workspace.request(requestId);
    }

    @PostMapping("/requests/{requestId}/generate")
    public Map<String, Object> generate(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long requestId) {
        access.requireToken(token);
        return workspace.generate(requestId);
    }

    @GetMapping("/requests/{requestId}/download")
    public ResponseEntity<byte[]> downloadOutput(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long requestId) {
        access.requireToken(token);
        ProcessWorkspaceService.GeneratedFile file = workspace.download(requestId);
        return workbook(file.filename(), file.sha256(), file.bytes());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> workspaceError(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        Map<String, Object> body = Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason());
        return ResponseEntity.status(status).body(body);
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private ResponseEntity<byte[]> workbook(String filename, String hash, byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", hash);
        headers.set("X-Official-Baseline-Modified", "false");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
