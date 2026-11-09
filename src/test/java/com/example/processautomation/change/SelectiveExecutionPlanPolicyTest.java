package com.example.processautomation.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SelectiveExecutionPlanPolicyTest {
    @Test
    void classifiesVerifiedCandidatesAsReviewUntilExplicitTestOnlyPlanExists() {
        assertThat(SelectiveExecutionPlanPolicy.classify(true, true))
                .isEqualTo(SelectiveExecutionPlanPolicy.Classification.A_TEST_ONLY_PLAN_READY);
        assertThat(SelectiveExecutionPlanPolicy.classify(true, false))
                .isEqualTo(SelectiveExecutionPlanPolicy.Classification.B_REVIEW_REQUIRED);
        assertThat(SelectiveExecutionPlanPolicy.classify(false, false))
                .isEqualTo(SelectiveExecutionPlanPolicy.Classification.C_AUTOMATIC_EDIT_UNAVAILABLE);
    }

    @Test
    void onlyExplicitTestOnlyAddedItemPlanWithReviewBoundaryIsEligible() {
        Map<String, Object> enabledScenario = Map.of("enabled", true);
        assertThat(SelectiveExecutionPlanPolicy.testOnlyPlanEligible("ADDED", "TEST_ONLY", "TEST_ONLY_READY",
                true, "REVIEW_REQUIRED", enabledScenario)).isTrue();
        assertThat(SelectiveExecutionPlanPolicy.testOnlyPlanEligible("ADDED", "TEST_ONLY", "TEST_ONLY_READY",
                true, "CONFIRMED", enabledScenario)).isFalse();
        assertThat(SelectiveExecutionPlanPolicy.testOnlyPlanEligible("MODIFIED", "TEST_ONLY", "TEST_ONLY_READY",
                true, "REVIEW_REQUIRED", enabledScenario)).isFalse();
        assertThat(SelectiveExecutionPlanPolicy.testOnlyPlanEligible("ADDED", "TEST_ONLY", "TEST_ONLY_READY",
                true, "REVIEW_REQUIRED", Map.of("enabled", false))).isFalse();
    }
}
