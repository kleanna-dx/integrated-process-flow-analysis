package com.example.processautomation.api;

import com.example.processautomation.change.SelectiveEditExecutionPlanService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/change-runs")
public class SelectiveEditExecutionPlanController {
    private final ApiAccessGuard accessGuard;
    private final SelectiveEditExecutionPlanService plans;

    public SelectiveEditExecutionPlanController(ApiAccessGuard accessGuard,
                                                SelectiveEditExecutionPlanService plans) {
        this.accessGuard = accessGuard;
        this.plans = plans;
    }

    @PostMapping("/{changeRunId}/selective-edit-plan")
    public Map<String, Object> createPlan(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable long changeRunId,
            @RequestBody(required = false) SelectiveEditExecutionPlanService.Request request) {
        accessGuard.requireToken(token);
        if (changeRunId <= 0) throw new IllegalArgumentException("Positive changeRunId is required.");
        return plans.create(changeRunId, request);
    }
}
