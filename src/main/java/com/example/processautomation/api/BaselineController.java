package com.example.processautomation.api;

import com.example.processautomation.baseline.BaselineRegistrationResult;
import com.example.processautomation.baseline.BaselineRegistrationService;
import com.example.processautomation.baseline.BaselineSourceFile;
import com.example.processautomation.change.ChangeDetectionResult;
import com.example.processautomation.change.ChangeDetectionService;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
@RequestMapping("/api/admin")
public class BaselineController {
    private static final Pattern PROCESS_CODE = Pattern.compile("^((?:PW|PU|P)-\\d{2}(?:-\\d{2})?)");
    private final BaselineRegistrationService baselineService;
    private final ChangeDetectionService changeService;
    private final OoxmlPackageInspector inspector;
    private final ApiAccessGuard accessGuard;

    public BaselineController(BaselineRegistrationService baselineService, ChangeDetectionService changeService,
                              OoxmlPackageInspector inspector, ApiAccessGuard accessGuard) {
        this.baselineService = baselineService;
        this.changeService = changeService;
        this.inspector = inspector;
        this.accessGuard = accessGuard;
    }

    @PostMapping(path = "/baselines", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaselineRegistrationResult> registerBaseline(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("masterFile") MultipartFile masterFile,
            @RequestParam("processFiles") MultipartFile[] processFiles) {
        accessGuard.requireToken(token);
        byte[] masterBytes = readWorkbook(masterFile, "masterFile");
        List<BaselineSourceFile> processSources = new ArrayList<>();
        for (MultipartFile file : processFiles) {
            String filename = safeFilename(file);
            Matcher matcher = PROCESS_CODE.matcher(filename);
            if (!matcher.find()) {
                throw new InvalidWorkbookException("Cannot determine an exact process code from filename: " + filename);
            }
            byte[] bytes = readWorkbook(file, "processFiles");
            WorkbookInventory inventory;
            try {
                inventory = inspector.inspect(bytes);
            } catch (InvalidWorkbookException exception) {
                throw new InvalidWorkbookException("Failed to inspect process workbook " + filename + ": "
                        + exception.getMessage(), exception);
            }
            processSources.add(new BaselineSourceFile(filename, matcher.group(1), contentType(file),
                    bytes, sha256(bytes), inventory));
        }
        BaselineRegistrationResult result = baselineService.register(masterBytes, processSources);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/baselines/current")
    public BaselineRegistrationResult currentBaseline(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        accessGuard.requireToken(token);
        return baselineService.current();
    }

    @GetMapping("/baselines/documents")
    public List<Map<String, Object>> documents(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        accessGuard.requireToken(token);
        return baselineService.listDocuments();
    }

    @GetMapping("/baselines/documents/{id}/download")
    public ResponseEntity<byte[]> downloadOriginal(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        byte[] content = baselineService.originalDocument(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("baseline-" + id + ".xlsx").build());
        headers.setContentLength(content.length);
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    @GetMapping("/equipment")
    public List<Map<String, Object>> equipment(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String processCode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        accessGuard.requireToken(token);
        return baselineService.listEquipment(processCode, q, page, size);
    }

    @GetMapping("/mappings")
    public List<Map<String, Object>> mappings(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String processCode,
            @RequestParam(defaultValue = "500") int limit) {
        accessGuard.requireToken(token);
        return baselineService.listMappings(status, processCode, limit);
    }

    @GetMapping("/issues")
    public List<Map<String, Object>> issues(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "500") int limit) {
        accessGuard.requireToken(token);
        return baselineService.listIssues(severity, limit);
    }

    @GetMapping("/change-runs")
    public List<Map<String, Object>> changeRuns(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "20") int limit) {
        accessGuard.requireToken(token);
        return changeService.listRuns(limit);
    }

    @GetMapping("/change-runs/{id}")
    public Map<String, Object> changeRun(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        return changeService.getRun(id);
    }

    @GetMapping("/change-runs/{id}/items")
    public List<Map<String, Object>> changeItems(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        changeService.getRun(id);
        return changeService.listChangeItems(id);
    }

    @GetMapping("/change-runs/{id}/issues")
    public List<Map<String, Object>> changeIssues(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        changeService.getRun(id);
        return changeService.listChangeIssues(id);
    }

    @GetMapping("/change-runs/{id}/flows")
    public List<Map<String, Object>> changeFlows(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        changeService.getRun(id);
        return changeService.listFlowCandidates(id);
    }

    @PostMapping(path = "/master-diff", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChangeDetectionResult detectChanges(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("masterFile") MultipartFile updatedMaster) {
        accessGuard.requireToken(token);
        return changeService.compare(readWorkbook(updatedMaster, "masterFile"), safeFilename(updatedMaster));
    }

    private byte[] readWorkbook(MultipartFile file, String partName) {
        if (file == null || file.isEmpty()) {
            throw new InvalidWorkbookException("Required Excel upload is empty: " + partName);
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new InvalidWorkbookException("Excel upload exceeds the 10 MB limit: " + safeFilename(file));
        }
        String filename = safeFilename(file);
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new InvalidWorkbookException("Only .xlsx files are accepted: " + filename);
        }
        String contentType = contentType(file);
        if (!contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                && !contentType.equals("application/octet-stream")) {
            throw new InvalidWorkbookException("Unexpected content type for .xlsx: " + contentType);
        }
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

    private String safeFilename(MultipartFile file) {
        String raw = file == null ? null : file.getOriginalFilename();
        if (raw == null || raw.isBlank()) return "upload.xlsx";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("..")) throw new InvalidWorkbookException("Invalid filename");
        return name;
    }

    private String contentType(MultipartFile file) {
        return file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
