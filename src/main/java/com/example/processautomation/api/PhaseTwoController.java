package com.example.processautomation.api;

import com.example.processautomation.material.EquipmentInsertionPlanningService;
import com.example.processautomation.material.EquipmentInsertionProposal;
import com.example.processautomation.material.MaterialBalanceRequest;
import com.example.processautomation.material.MaterialBalanceService;
import com.example.processautomation.material.MaterialBalanceExcelWorkflowService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/phase2")
public class PhaseTwoController {
    private final ApiAccessGuard accessGuard;
    private final EquipmentInsertionPlanningService insertionPlanningService;
    private final MaterialBalanceService materialBalanceService;
    private final MaterialBalanceExcelWorkflowService materialBalanceExcelWorkflowService;

    public PhaseTwoController(ApiAccessGuard accessGuard,
                              EquipmentInsertionPlanningService insertionPlanningService,
                              MaterialBalanceService materialBalanceService,
                              MaterialBalanceExcelWorkflowService materialBalanceExcelWorkflowService) {
        this.accessGuard = accessGuard;
        this.insertionPlanningService = insertionPlanningService;
        this.materialBalanceService = materialBalanceService;
        this.materialBalanceExcelWorkflowService = materialBalanceExcelWorkflowService;
    }

    @PostMapping("/pw0105/equipment-insertion/preview")
    public Map<String, Object> previewInsertion(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody EquipmentInsertionProposal proposal) {
        accessGuard.requireToken(token);
        return insertionPlanningService.preview(proposal);
    }

    @PostMapping("/material-balance/calculate")
    public Map<String, Object> calculateMaterialBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody MaterialBalanceRequest request) {
        accessGuard.requireToken(token);
        return materialBalanceService.calculate(request);
    }

    @PostMapping(value = "/material-balance/export-cpw15020-test-only", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> exportCpw15020DiagramTestOnly(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestPart("request") MaterialBalanceRequest request,
            @RequestPart("workbook") MultipartFile workbook) throws IOException {
        accessGuard.requireToken(token);
        if (workbook.isEmpty()) throw new IllegalArgumentException("TEST_ONLY V5 workbook part is required");
        if (workbook.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("Workbook exceeds the 10 MB export limit");
        var result = materialBalanceExcelWorkflowService.exportCpw15020DiagramTestOnly(request, workbook.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.xlsx", StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Calculation-Mode", "TEST_ONLY");
        headers.set("X-Official-Material-Balance-Updated", "false");
        headers.set("X-Official-Diagram-Modified", "false");
        headers.set("X-Test-Only-Diagram-Overlay", "applied");
        headers.set("X-Test-Only-Diagram-Shapes-Added", Integer.toString(result.diagramOverlay().addedShapes()));
        headers.set("X-Test-Only-Diagram-Connectors-Added", Integer.toString(result.diagramOverlay().addedConnectors()));
        headers.set("X-Relation-Approval-Status", "REVIEW_REQUIRED");
        return ResponseEntity.ok().headers(headers).body(result.workbookBytes());
    }

    @PostMapping(value = "/material-balance/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> exportTestOnlyMaterialBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestPart("request") MaterialBalanceRequest request,
            @RequestPart("workbook") MultipartFile workbook) throws IOException {
        accessGuard.requireToken(token);
        if (workbook.isEmpty()) throw new IllegalArgumentException("TEST_ONLY V5 workbook part is required");
        if (workbook.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("Workbook exceeds the 10 MB export limit");
        var result = materialBalanceExcelWorkflowService.exportTestOnly(request, workbook.getBytes());
        String safeId = request.simulationId().replaceAll("[^A-Za-z0-9_-]", "_");
        String filename = "PW-01-05-TEST_ONLY-MATERIAL-BALANCE-" + safeId + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Calculation-Mode", "TEST_ONLY");
        headers.set("X-Official-Material-Balance-Updated", "false");
        return ResponseEntity.ok().headers(headers).body(result.workbookBytes());
    }
}
