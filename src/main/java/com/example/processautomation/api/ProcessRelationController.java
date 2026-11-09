package com.example.processautomation.api;

import com.example.processautomation.relation.ConnectorAnalysisService;
import com.example.processautomation.relation.RelationCandidateEvidenceRequest;
import com.example.processautomation.relation.RelationReviewRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ProcessRelationController {
    private final ConnectorAnalysisService service;
    private final ApiAccessGuard accessGuard;

    public ProcessRelationController(ConnectorAnalysisService service, ApiAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/connector-analysis")
    public Map<String, Object> analyze(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String processCode,
            @RequestParam(required = false) String sheetName,
            @RequestParam(required = false) String connectorShapeId) {
        accessGuard.requireToken(token);
        return service.analyze(processCode == null || processCode.isBlank() ? null : processCode.trim(),
                sheetName == null || sheetName.isBlank() ? null : sheetName.trim(),
                connectorShapeId == null || connectorShapeId.isBlank() ? null : connectorShapeId.trim());
    }

    @GetMapping("/process-relations")
    public List<Map<String, Object>> relations(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String processCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "500") int limit) {
        accessGuard.requireToken(token);
        return service.list(processCode, status, limit);
    }

    @GetMapping("/process-relations/{id}/history")
    public List<Map<String, Object>> history(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id) {
        accessGuard.requireToken(token);
        return service.history(id);
    }

    @PostMapping("/process-relations")
    public Map<String, Object> createReviewedRelation(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam long connectorCandidateId,
            @Valid @RequestBody RelationReviewRequest request) {
        accessGuard.requireToken(token);
        return service.createReviewedRelation(connectorCandidateId, request);
    }

    @PutMapping("/process-relations/{id}/evidence")
    public Map<String, Object> addCandidateEvidence(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id,
            @Valid @RequestBody RelationCandidateEvidenceRequest request) {
        accessGuard.requireToken(token);
        return service.addCandidateEvidence(id, request);
    }

    @PutMapping("/process-relations/{id}")
    public Map<String, Object> review(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long id,
            @Valid @RequestBody RelationReviewRequest request) {
        accessGuard.requireToken(token);
        return service.review(id, request);
    }
}
