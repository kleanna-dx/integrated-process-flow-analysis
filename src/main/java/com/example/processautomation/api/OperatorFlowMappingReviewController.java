package com.example.processautomation.api;

import com.example.processautomation.material.FlowMappingReviewService;
import com.example.processautomation.material.ProcessDiagramInventoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated read-only structural and workbook comparison routes. */
@RestController
@RequestMapping("/api/workspace/flow-mapping-review")
public class OperatorFlowMappingReviewController {
    private static final long MAX_SOURCE_BYTES = 15L * 1024 * 1024;
    private final OperatorLoginService login;
    private final FlowMappingReviewService review;

    public OperatorFlowMappingReviewController(OperatorLoginService login, FlowMappingReviewService review) {
        this.login = login;
        this.review = review;
    }

    @GetMapping("/overview")
    public ResponseEntity<ProcessDiagramInventoryService.MappingReviewSnapshot> overview(HttpSession session) {
        login.requireAuthenticated(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(review.overview());
    }

    @GetMapping("/candidates")
    public ResponseEntity<ProcessDiagramInventoryService.MappingCandidateResults> candidates(HttpSession session,
            @RequestParam String processCode, @RequestParam String sheetName,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        login.requireAuthenticated(session);
        if (processCode == null || !processCode.matches("[A-Z]{1,3}-\\d{2}(?:-\\d{2})?")
                || sheetName == null || sheetName.isBlank() || sheetName.length() > 128
                || page < 0 || pageSize < 1 || pageSize > 100 || (q != null && q.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공정·시트·검색·페이지 조건이 허용 범위를 벗어났습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.candidates(processCode, sheetName, q, page, pageSize));
    }

    @GetMapping("/equipment-occurrences/batch")
    public ResponseEntity<ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults> equipmentOccurrenceBatch(
            HttpSession session, @RequestParam List<String> equipmentIds) {
        login.requireAuthenticated(session);
        if (equipmentIds == null || equipmentIds.isEmpty() || equipmentIds.size() > 100
                || equipmentIds.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9._-]{1,64}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비 ID는 안전한 식별자 1~100개여야 합니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.equipmentOccurrenceBatch(equipmentIds));
    }

    @GetMapping("/equipment-occurrences")
    public ResponseEntity<ProcessDiagramInventoryService.EquipmentOccurrenceResults> equipmentOccurrences(
            HttpSession session, @RequestParam String equipmentId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        login.requireAuthenticated(session);
        if (equipmentId == null || !equipmentId.matches("[A-Za-z0-9._-]{1,64}")
                || page < 0 || pageSize < 1 || pageSize > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비 ID 또는 페이지 조건이 허용 범위를 벗어났습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.equipmentOccurrences(equipmentId, page, pageSize));
    }

    @GetMapping(value = "/review-template.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> reviewTemplate(HttpSession session) {
        login.requireAuthenticated(session);
        String csv = "공정,시트,출발 설비,도착 설비,배관·연결 식별자,물질 종류,폐수·폐기물 분류,유량,단위,측정 기간,내부 순환 여부,외부 유입·유출 여부,공식 도면 참조 위치,증빙 파일 또는 자료,검토자,검토 상태,비고" + System.lineSeparator();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=flow-mapping-review-template.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv);
    }

    /** Reads a user-selected XLSM/XLSX in memory; no upload bytes or comparison result are persisted. */
    @PostMapping(path = "/material-balance-comparison", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> compareMaterialBalance(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @RequestParam("workbook") MultipartFile workbook,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String statusFilter) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        if (workbook == null || workbook.isEmpty() || workbook.getSize() > MAX_SOURCE_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분석할 물질수지 XLSM/XLSX는 1바이트 이상 15MB 이하여야 합니다.");
        if (page < 0 || pageSize < 1 || pageSize > 100 || (q != null && q.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page/pageSize/search 범위를 확인하세요.");
        String original = workbook.getOriginalFilename();
        String filename = original == null ? "material-balance-source.xlsm"
                : original.replace('\\', '/').substring(original.replace('\\', '/').lastIndexOf('/') + 1);
        String normalized = filename.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.endsWith(".xlsm") && !normalized.endsWith(".xlsx"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSM 또는 XLSX 파일만 읽기 전용 분석할 수 있습니다.");
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(review.startComparison(workbook.getBytes(), filename, username, session.getId(), pageSize, q, statusFilter));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "물질수지 파일을 읽지 못했습니다.", exception);
        }
    }

    @GetMapping("/material-balance-comparison/{analysisId}/rows")
    public ResponseEntity<Map<String, Object>> comparisonRows(HttpSession session,
            @org.springframework.web.bind.annotation.PathVariable String analysisId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String statusFilter) {
        String authenticated = login.requireAuthenticated(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.comparisonPage(analysisId, authenticated, session.getId(), page, pageSize, q, statusFilter));
    }

    @GetMapping("/material-balance-comparison/{analysisId}/export")
    public ResponseEntity<byte[]> exportComparison(HttpSession session,
            @org.springframework.web.bind.annotation.PathVariable String analysisId,
            @RequestParam String format, @RequestParam(required = false) String statusFilter,
            @RequestParam(required = false) String q) {
        String username = login.requireAuthenticated(session);
        if (!List.of("csv", "json", "xlsx").contains(format.toLowerCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원 format: csv, json, xlsx");
        if (statusFilter != null && statusFilter.length() > 64 || q != null && q.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검토 필터 또는 검색어 길이가 허용 범위를 벗어났습니다.");
        FlowMappingReviewService.ReviewExport export = review.exportReviewArtifact(
                analysisId, username, session.getId(), format, statusFilter, q);
        byte[] bytes = export.bytes();
        MediaType type = switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "csv" -> MediaType.parseMediaType("text/csv;charset=UTF-8");
            case "json" -> MediaType.APPLICATION_JSON;
            default -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
                .header("X-Review-Only", "true").header("X-Official-Baseline-Modified", "false")
                .header("X-Review-SHA256", sha256(bytes))
                .header("X-Review-Exported-Rows", Integer.toString(export.rowCount()))
                .header("X-Review-Status-Filter", export.statusFilter())
                .header("Content-Disposition", "attachment; filename=flow-mapping-review." + format.toLowerCase(java.util.Locale.ROOT))
                .body(bytes);
    }

    @GetMapping("/material-balance-comparison/{analysisId}/draft-template")
    public ResponseEntity<byte[]> downloadReviewDraftTemplate(HttpSession session,
            @org.springframework.web.bind.annotation.PathVariable String analysisId, @RequestParam String format) {
        String username = login.requireAuthenticated(session);
        if (!List.of("csv", "xlsx").contains(format.toLowerCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원 양식: csv, xlsx");
        var export = review.exportReviewInputTemplate(analysisId, username, session.getId(), format);
        byte[] bytes = export.bytes();
        MediaType type = format.equalsIgnoreCase("csv") ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
                .header("X-Review-Template-Only", "true").header("X-Official-Baseline-Modified", "false")
                .header("X-Review-SHA256", sha256(bytes))
                .header("X-Review-Exported-Rows", Integer.toString(export.rowCount()))
                .header("Content-Disposition", "attachment; filename=flow-mapping-review-template." + format.toLowerCase(java.util.Locale.ROOT))
                .body(bytes);
    }

    @PostMapping(path = "/material-balance-comparison/{analysisId}/draft-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<com.example.processautomation.material.FlowMappingReviewDraft.ImportPreview> importReviewDraft(
            HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @org.springframework.web.bind.annotation.PathVariable String analysisId,
            @RequestParam("file") MultipartFile file) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        if (analysisId == null || !analysisId.matches("[A-Za-z0-9-]{1,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분석 ID 형식이 올바르지 않습니다.");
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검토 파일은 1바이트 이상 5MB 이하여야 합니다.");
        try {
            var preview = review.importReviewDraft(analysisId, username, session.getId(), file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검토 파일을 읽지 못했습니다.", exception);
        }
    }

    @GetMapping("/material-balance-comparison/{analysisId}/draft-preview/{previewId}")
    public ResponseEntity<com.example.processautomation.material.FlowMappingReviewDraft.ImportPreview> reviewDraftPreview(
            HttpSession session, @org.springframework.web.bind.annotation.PathVariable String analysisId,
            @org.springframework.web.bind.annotation.PathVariable String previewId) {
        String username = login.requireAuthenticated(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.reviewDraftPreview(analysisId, previewId, username, session.getId()));
    }

    @GetMapping("/material-balance-comparison/{analysisId}/draft-preview/{previewId}/export")
    public ResponseEntity<byte[]> exportReviewDraft(HttpSession session,
            @org.springframework.web.bind.annotation.PathVariable String analysisId,
            @org.springframework.web.bind.annotation.PathVariable String previewId, @RequestParam String format) {
        String username = login.requireAuthenticated(session);
        if (!List.of("csv", "json", "xlsx").contains(format.toLowerCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원 format: csv, json, xlsx");
        var export = review.exportReviewDraft(analysisId, previewId, username, session.getId(), format);
        byte[] bytes = export.bytes();
        MediaType type = switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "csv" -> MediaType.parseMediaType("text/csv;charset=UTF-8");
            case "json" -> MediaType.APPLICATION_JSON;
            default -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
                .header("X-Review-Draft-Only", "true").header("X-Official-Baseline-Modified", "false")
                .header("X-Review-Persisted", "false").header("X-Review-Verified-Flow-Mappings", "0")
                .header("X-Review-Readiness", "CALCULATION_UNAVAILABLE")
                .header("X-Review-Exported-Rows", Integer.toString(export.rowCount()))
                .header("X-Review-SHA256", sha256(bytes))
                .header("Content-Disposition", "attachment; filename=flow-mapping-review-draft." + format.toLowerCase(java.util.Locale.ROOT))
                .body(bytes);
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
