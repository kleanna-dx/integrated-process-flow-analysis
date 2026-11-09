package com.example.processautomation.api;

import com.example.processautomation.excel.DiagramEditPlanService;
import com.example.processautomation.material.Step6TestOnlyWorkbookEnrichmentService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/diagram-edit-plans")
public class DiagramEditPlanController {
    private final ApiAccessGuard accessGuard;
    private final DiagramEditPlanService plans;
    private final Step6TestOnlyWorkbookEnrichmentService integratedWorkbooks;

    public DiagramEditPlanController(ApiAccessGuard accessGuard, DiagramEditPlanService plans,
                                     Step6TestOnlyWorkbookEnrichmentService integratedWorkbooks) {
        this.accessGuard = accessGuard;
        this.plans = plans;
        this.integratedWorkbooks = integratedWorkbooks;
    }

    @PostMapping
    public Map<String, Object> createPlan(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody DiagramEditPlanService.CreatePlanRequest request) {
        accessGuard.requireToken(token);
        if (request.changeRunId() <= 0 || request.changeItemId() <= 0) {
            throw new IllegalArgumentException("Positive changeRunId and changeItemId are required.");
        }
        return plans.createPlan(request);
    }

    @GetMapping("/{planId}")
    public Map<String, Object> getPlan(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long planId) {
        accessGuard.requireToken(token);
        return plans.getPlan(planId);
    }

    @GetMapping("/by-run/{changeRunId}/items/{changeItemId}")
    public java.util.List<Map<String, Object>> listPlansForItem(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long changeRunId,
            @PathVariable long changeItemId) {
        accessGuard.requireToken(token);
        return plans.listPlansForItem(changeRunId, changeItemId);
    }

    @PostMapping("/{planId}/generate-test-only")
    public ResponseEntity<byte[]> generateTestOnly(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long planId) {
        accessGuard.requireToken(token);
        return response(plans.generate(planId));
    }

    @GetMapping("/{planId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long planId) {
        accessGuard.requireToken(token);
        return response(plans.download(planId));
    }

    @PostMapping("/{planId}/generate-integrated-test-only")
    public Map<String, Object> generateIntegratedTestOnly(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long planId,
            @RequestBody IntegratedGenerationRequest request) {
        accessGuard.requireToken(token);
        if (request.changeRunId() <= 0 || request.changeItemId() <= 0 || planId <= 0) {
            throw new IllegalArgumentException("Positive changeRunId, changeItemId and planId are required.");
        }
        integratedWorkbooks.requireEligiblePlan(request.changeRunId(), request.changeItemId(), planId);
        DiagramEditPlanService.GeneratedWorkbook diagram = plans.generate(planId);
        var integrated = integratedWorkbooks.createAndStoreIntegratedWorkbook(request.changeRunId(),
                request.changeItemId(), planId, diagram, request.includeSyntheticTestValues());
        return Map.of("planId", integrated.planId(), "changeRunId", request.changeRunId(),
                "changeItemId", request.changeItemId(), "filename", integrated.filename(),
                "sha256", integrated.sha256(), "scenarioKey", integrated.scenarioKey(),
                "reused", integrated.reused(), "integrated", true, "sheetCount", 8);
    }

    @GetMapping("/{planId}/download-integrated")
    public ResponseEntity<byte[]> downloadIntegrated(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long planId,
            @RequestParam(defaultValue = "false") boolean includeSyntheticTestValues) {
        accessGuard.requireToken(token);
        return response(integratedWorkbooks.downloadIntegratedWorkbook(planId, includeSyntheticTestValues));
    }

    private ResponseEntity<byte[]> response(DiagramEditPlanService.GeneratedWorkbook generated) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(generated.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(generated.bytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Relation-Status", "REVIEW_REQUIRED");
        headers.set("X-Baseline-Modified", "false");
        headers.set("X-Output-SHA256", generated.sha256());
        return ResponseEntity.ok().headers(headers).body(generated.bytes());
    }

    private ResponseEntity<byte[]> response(Step6TestOnlyWorkbookEnrichmentService.StoredIntegratedWorkbook generated) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(generated.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(generated.bytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Relation-Status", "REVIEW_REQUIRED");
        headers.set("X-Baseline-Modified", "false");
        headers.set("X-Output-SHA256", generated.sha256());
        headers.set("X-Integrated-Workbook", "true");
        return ResponseEntity.ok().headers(headers).body(generated.bytes());
    }

    public record IntegratedGenerationRequest(long changeRunId, long changeItemId,
                                              boolean includeSyntheticTestValues) { }
}
