package com.example.processautomation.api;

import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.material.Step7ConnectionEvidenceService;
import com.example.processautomation.material.Step7ConnectionEvidenceWorkbookExporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/step7-3/pw-01-05")
public class Step7ConnectionEvidenceController {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLSM_TYPE = "application/vnd.ms-excel.sheet.macroEnabled.12";

    private final Step7ConnectionEvidenceService evidenceService;
    private final Step7ConnectionEvidenceWorkbookExporter exporter;
    private final ApiAccessGuard accessGuard;
    private final com.example.processautomation.material.Step7ReviewLedgerWorkflowService reviewWorkflow;
    private final com.example.processautomation.material.Step7FieldReviewBridgeService fieldReviewBridge;
    private final com.example.processautomation.material.Step7WholeNetworkReviewWorkflowService networkReviewWorkflow;
    private final com.example.processautomation.material.Step7WholeNetworkReviewWorkbookExporter networkReviewExporter;

    public Step7ConnectionEvidenceController(Step7ConnectionEvidenceService evidenceService,
                                             Step7ConnectionEvidenceWorkbookExporter exporter,
                                             ApiAccessGuard accessGuard,
                                             com.example.processautomation.material.Step7ReviewLedgerWorkflowService reviewWorkflow,
                                             com.example.processautomation.material.Step7FieldReviewBridgeService fieldReviewBridge,
                                             com.example.processautomation.material.Step7WholeNetworkReviewWorkflowService networkReviewWorkflow,
                                             com.example.processautomation.material.Step7WholeNetworkReviewWorkbookExporter networkReviewExporter) {
        this.evidenceService = evidenceService;
        this.exporter = exporter;
        this.accessGuard = accessGuard;
        this.reviewWorkflow = reviewWorkflow;
        this.fieldReviewBridge = fieldReviewBridge;
        this.networkReviewWorkflow = networkReviewWorkflow;
        this.networkReviewExporter = networkReviewExporter;
    }

    @PostMapping(path = "/network-review/candidate-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> networkReviewCandidatePreview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("teamWorkbook") MultipartFile teamWorkbook) {
        accessGuard.requireToken(token);
        String filename = safeFilename(teamWorkbook);
        var data = evidenceService.buildWorkbook(readXlsm(teamWorkbook, filename), filename);
        List<Map<String, Object>> candidates = data.candidates().stream().map(candidate -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("candidateId", candidate.caseKey());
            row.put("dbSourceRow", candidate.sourceRow());
            row.put("sourceEquipment", candidate.source());
            row.put("targetEquipment", candidate.target());
            row.put("sourceCellHints", candidate.sourceCellHints());
            row.put("targetCellHints", candidate.targetCellHints());
            row.put("candidateStatus", candidate.candidateStatus());
            row.put("evidenceStatus", candidate.evidenceStatus());
            row.put("comparisonStatus", candidate.comparisonStatus());
            row.put("connectorHints", candidate.connectorHints().stream().map(hint -> {
                Map<String, Object> connector = new java.util.LinkedHashMap<>();
                connector.put("shapeId", hint.shapeId());
                connector.put("anchorStartCell", hint.anchorStartCell());
                connector.put("anchorEndCell", hint.anchorEndCell());
                connector.put("sourceLabelCellHint", hint.sourceLabelCellHint());
                connector.put("targetLabelCellHint", hint.targetLabelCellHint());
                connector.put("explicitEndpointBinding", hint.explicitEndpointBinding());
                return connector;
            }).toList());
            return row;
        }).toList();
        long verifiedCandidates = candidates.stream().filter(row -> "EVIDENCE_VERIFIED".equals(row.get("evidenceStatus"))).count();
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("sourceFilename", data.teamFilename());
        response.put("sourceSha256", data.teamSha256());
        response.put("teamConnectionRows", data.comparison().inputRows());
        response.put("officialProcessWorkbookCount", Math.max(0, data.officialSnapshot().documentCount() - 1));
        response.put("officialMasterWorkbookCount", 1);
        response.put("officialEquipmentCount", data.officialSnapshot().equipmentCount());
        response.put("pw0105CandidateCount", candidates.size());
        response.put("evidenceVerifiedCandidateCount", verifiedCandidates);
        response.put("officialConfirmedRelationCount", data.officialSnapshot().confirmedRelationCount());
        response.put("connectorEndpointBindingCount", data.officialSnapshot().explicitBindingCount());
        response.put("candidates", candidates);
        response.put("warnings", data.warnings());
        response.put("notice", "읽기 전용 candidate preview입니다. 검토 case/history, 공식 승인, process_relation, equipment_flow를 저장하지 않습니다.");
        return response;
    }

    @PostMapping(path = "/network-review-workbook", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> networkReviewWorkbook(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("teamWorkbook") MultipartFile teamWorkbook) {
        accessGuard.requireToken(token);
        String filename = safeFilename(teamWorkbook);
        var data = evidenceService.buildWorkbook(readXlsm(teamWorkbook, filename), filename);
        var template = reviewWorkflow.createSnapshot(data);
        byte[] output = networkReviewExporter.export(data, template);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("PW-01-05_전체33개_연결망_현장검토_TEST_ONLY.xlsx", StandardCharsets.UTF_8).build());
        headers.setContentLength(output.length);
        headers.set("X-Review-Snapshot-Id", template.snapshotId());
        headers.set("X-Auto-Candidate-Count", Integer.toString(template.rows().size()));
        return new ResponseEntity<>(output, headers, HttpStatus.OK);
    }

    @PostMapping(path = "/network-review/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.example.processautomation.material.Step7WholeNetworkReviewWorkflowService.IntakePreview previewNetworkReview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("reviewWorkbook") MultipartFile reviewWorkbook) {
        accessGuard.requireToken(token);
        return networkReviewWorkflow.preview(readXlsx(reviewWorkbook, safeFilename(reviewWorkbook)));
    }

    @PostMapping(path = "/network-review/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.example.processautomation.material.Step7WholeNetworkReviewWorkflowService.IntakeResult importNetworkReview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("reviewWorkbook") MultipartFile reviewWorkbook) {
        accessGuard.requireToken(token);
        return networkReviewWorkflow.importWorkbook(readXlsx(reviewWorkbook, safeFilename(reviewWorkbook)));
    }

    @GetMapping("/review-snapshots/{snapshotId}/candidates/{candidateId}/route-profiles")
    public List<Map<String, Object>> routeProfiles(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String snapshotId, @PathVariable String candidateId) {
        accessGuard.requireToken(token);
        return networkReviewWorkflow.routeProfiles(snapshotId, candidateId);
    }

    @GetMapping("/review-snapshots/{snapshotId}/candidates/{candidateId}/evidence-provenance-history")
    public List<Map<String, Object>> evidenceProvenanceHistory(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String snapshotId, @PathVariable String candidateId) {
        accessGuard.requireToken(token);
        return networkReviewWorkflow.evidenceHistory(snapshotId, candidateId);
    }

    @PostMapping(path = "/field-review/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.example.processautomation.material.Step7FieldReviewBridgeService.BridgePreview previewFieldReview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("fieldWorkbook") MultipartFile fieldWorkbook) {
        accessGuard.requireToken(token);
        return fieldReviewBridge.preview(readXlsx(fieldWorkbook,safeFilename(fieldWorkbook)));
    }

    @PostMapping(path = "/field-review/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.example.processautomation.material.Step7FieldReviewBridgeService.BridgeImportResult importFieldReview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("fieldWorkbook") MultipartFile fieldWorkbook) {
        accessGuard.requireToken(token);
        return fieldReviewBridge.importResults(readXlsx(fieldWorkbook,safeFilename(fieldWorkbook)));
    }

    @PostMapping(path = "/field-review/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> exportFieldReviewReport(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("fieldWorkbook") MultipartFile fieldWorkbook) {
        accessGuard.requireToken(token);
        byte[] input=readXlsx(fieldWorkbook,safeFilename(fieldWorkbook));
        byte[] output=fieldReviewBridge.resultWorkbook(input,null);
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename("PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx",StandardCharsets.UTF_8).build());
        headers.setContentLength(output.length);headers.set("X-Source-SHA256",sha256(input));
        return new ResponseEntity<>(output,headers,HttpStatus.OK);
    }

    @PostMapping(path = "/evidence-workbook", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> evidenceWorkbook(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("teamWorkbook") MultipartFile teamWorkbook) {
        accessGuard.requireToken(token);
        String filename = safeFilename(teamWorkbook);
        byte[] input = readXlsm(teamWorkbook, filename);
        var data = evidenceService.buildWorkbook(input, filename);
        var reviewTemplate = reviewWorkflow.createSnapshot(data);
        byte[] output = exporter.export(data, reviewTemplate);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("PW-01-05_연결증빙_검토_TEST_ONLY.xlsx", StandardCharsets.UTF_8).build());
        headers.setContentLength(output.length);
        headers.set("X-Source-SHA256", data.teamSha256());
        headers.set("X-Verified-Connections", Long.toString(data.verifiedRelationCount()));
        headers.set("X-Auto-Candidate-Count", Integer.toString(data.candidates().size()));
        headers.set("X-Review-Snapshot-Id", reviewTemplate.snapshotId());
        headers.set("X-Review-Template-Version", reviewTemplate.schemaVersion());
        return new ResponseEntity<>(output, headers, HttpStatus.OK);
    }

    @PostMapping(path = "/review-ledger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.example.processautomation.material.Step7ReviewLedgerWorkflowService.ReviewImportResult importReviewLedger(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("reviewWorkbook") MultipartFile reviewWorkbook) {
        accessGuard.requireToken(token);
        byte[] bytes = readXlsx(reviewWorkbook, safeFilename(reviewWorkbook));
        return reviewWorkflow.importWorkbook(bytes);
    }

    @GetMapping("/review-snapshots/{snapshotId}/candidates/{candidateId}/status")
    public Map<String, Object> reviewStatus(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                            @PathVariable String snapshotId, @PathVariable String candidateId) {
        accessGuard.requireToken(token);
        return reviewWorkflow.status(snapshotId, candidateId);
    }

    @GetMapping("/review-snapshots/{snapshotId}/candidates/{candidateId}/history")
    public List<Map<String, Object>> reviewSnapshotHistory(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                           @PathVariable String snapshotId, @PathVariable String candidateId) {
        accessGuard.requireToken(token);
        return reviewWorkflow.history(snapshotId, candidateId);
    }

    @GetMapping("/review-history/{caseKey}")
    public Object reviewHistory(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                @PathVariable String caseKey) {
        accessGuard.requireToken(token);
        return evidenceService.reviewHistory(caseKey);
    }

    private byte[] readXlsm(MultipartFile file, String filename) {
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsm")) {
            throw new InvalidWorkbookException("Only the read-only .xlsm team source workbook is accepted");
        }
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!type.equals("application/octet-stream") && !type.equals(XLSM_TYPE)
                && !type.equals("application/vnd.ms-excel")) {
            throw new InvalidWorkbookException("Unexpected content type for XLSM source");
        }
        return readBytes(file, filename);
    }

    private byte[] readXlsx(MultipartFile file, String filename) {
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new InvalidWorkbookException("Only .xlsx review workbooks are accepted");
        }
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!type.equals("application/octet-stream") && !type.equals(XLSX_TYPE)) {
            throw new InvalidWorkbookException("Unexpected content type for review XLSX");
        }
        return readBytes(file, filename);
    }

    private byte[] readBytes(MultipartFile file, String filename) {
        if (file == null || file.isEmpty()) throw new InvalidWorkbookException("Workbook upload is empty: " + filename);
        if (file.getSize() > 10L * 1024 * 1024) throw new InvalidWorkbookException("Workbook exceeds 10 MB");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                throw new InvalidWorkbookException("Uploaded file is not an OOXML ZIP workbook: " + filename);
            }
            return bytes;
        } catch (IOException exception) {
            throw new InvalidWorkbookException("Unable to read uploaded workbook: " + filename, exception);
        }
    }

    private String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("SHA-256 is unavailable",e);}
    }

    private String safeFilename(MultipartFile file) {
        String raw = file == null ? null : file.getOriginalFilename();
        if (raw == null || raw.isBlank()) throw new InvalidWorkbookException("Original workbook filename is required");
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("..")) throw new InvalidWorkbookException("Invalid workbook filename");
        return name;
    }
}
