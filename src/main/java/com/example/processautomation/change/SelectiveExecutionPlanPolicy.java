package com.example.processautomation.change;

import java.util.Map;

/** Pure classification rules for pre-generation selective edit plans. */
public final class SelectiveExecutionPlanPolicy {
    private SelectiveExecutionPlanPolicy() { }

    public static Classification classify(boolean candidateVerified, boolean explicitTestOnlyPlanValidated) {
        if (explicitTestOnlyPlanValidated && candidateVerified) return Classification.A_TEST_ONLY_PLAN_READY;
        if (candidateVerified) return Classification.B_REVIEW_REQUIRED;
        return Classification.C_AUTOMATIC_EDIT_UNAVAILABLE;
    }

    public static boolean testOnlyPlanEligible(String changeType, String analysisMode, String planStatus,
                                               boolean automaticEditAllowed, String relationStatus,
                                               Map<String, Object> scenario) {
        return "ADDED".equals(changeType)
                && "TEST_ONLY".equals(analysisMode)
                && "TEST_ONLY_READY".equals(planStatus)
                && automaticEditAllowed
                && "REVIEW_REQUIRED".equals(relationStatus)
                && Boolean.TRUE.equals(scenario.get("enabled"));
    }

    public enum Classification {
        A_TEST_ONLY_PLAN_READY,
        B_REVIEW_REQUIRED,
        C_AUTOMATIC_EDIT_UNAVAILABLE
    }
}
