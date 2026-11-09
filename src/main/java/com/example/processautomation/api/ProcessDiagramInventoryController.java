package com.example.processautomation.api;

import com.example.processautomation.material.ProcessDiagramInventoryCheckpointStore;
import com.example.processautomation.material.ProcessDiagramInventoryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/workspace/process-diagram-inventory")
public class ProcessDiagramInventoryController {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ApiAccessGuard access;
    private final ProcessDiagramInventoryService inventory;

    public ProcessDiagramInventoryController(ApiAccessGuard access, ProcessDiagramInventoryService inventory) {
        this.access = access;
        this.inventory = inventory;
    }

    @GetMapping
    public ResponseEntity<ProcessDiagramInventoryService.InventoryRunResult> inspect(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.inventoryProgress());
    }

    @GetMapping("/progress")
    public ResponseEntity<ProcessDiagramInventoryService.InventoryRunResult> progress(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.inventoryProgress());
    }

    @PostMapping("/batches")
    public ResponseEntity<ProcessDiagramInventoryService.InventoryRunResult> runBatch(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "5") int maxSheets) {
        access.requireToken(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.runNextInventoryBatch(maxSheets));
    }

    @PostMapping("/validations")
    public ResponseEntity<ProcessDiagramInventoryCheckpointStore.EditabilityValidation> registerValidation(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @org.springframework.web.bind.annotation.RequestBody ValidationRegistrationRequest request) {
        access.requireToken(token);
        var validation = new ProcessDiagramInventoryCheckpointStore.EditabilityValidation(
                request.validationId(), java.time.Instant.now(), request.processCode(), request.sheetName(),
                request.sourceDocumentId(), request.workingDocumentId(), request.officialSourceSha256(),
                request.workingCopySha256(), request.locationId(), request.sourceEquipmentId(),
                request.targetEquipmentId(), request.managementNo(), request.artifactPath(), request.artifactSha256(),
                "VERIFIED_BOX_ONLY_TEST_ONLY", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE",
                "LOGIN_CSRF_API_PREVIEW_GENERATE_DOWNLOAD_POI_REOPEN_OOXML_AND_VISUAL_REVIEW");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inventory.registerGeneratedBoxOnlyValidation(validation));
    }

    @GetMapping("/review-workbook-checkpoint")
    public ResponseEntity<byte[]> exportCheckpoint(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return workbookResponse(inventory.exportSavedInventory());
    }

    @GetMapping("/review-workbook")
    public ResponseEntity<byte[]> export(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return workbookResponse(inventory.exportSavedInventory());
    }

    public record ValidationRegistrationRequest(String validationId, String processCode, String sheetName,
                                                long sourceDocumentId, long workingDocumentId,
                                                String officialSourceSha256, String workingCopySha256,
                                                String locationId, String sourceEquipmentId, String targetEquipmentId,
                                                String managementNo, String artifactPath, String artifactSha256) { }

    private ResponseEntity<byte[]> workbookResponse(byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("official-process-diagram-inventory_TEST_ONLY.xlsx", StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-store");
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Structure-Analysis-Only", "true");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(bytes);
    }
}
