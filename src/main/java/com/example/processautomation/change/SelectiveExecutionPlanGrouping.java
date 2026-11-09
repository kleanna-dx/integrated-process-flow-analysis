package com.example.processautomation.change;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Deduplicates eligible output workbooks by immutable source filename. */
public final class SelectiveExecutionPlanGrouping {
    private SelectiveExecutionPlanGrouping() { }

    public static Map<String, List<PlannedTarget>> byWorkbook(List<PlannedTarget> targets) {
        Map<String, List<PlannedTarget>> grouped = new LinkedHashMap<>();
        Long runId = null;
        for (PlannedTarget target : targets) {
            if (runId == null) runId = target.changeRunId();
            if (runId != target.changeRunId()) {
                throw new IllegalArgumentException("A selective output group cannot mix changeRunId values.");
            }
            grouped.computeIfAbsent(target.workbookFilename(), ignored -> new ArrayList<>()).add(target);
        }
        Map<String, List<PlannedTarget>> immutable = new LinkedHashMap<>();
        grouped.forEach((filename, values) -> immutable.put(filename, List.copyOf(values)));
        return Collections.unmodifiableMap(immutable);
    }

    public record PlannedTarget(long changeRunId, long changeItemId, String managementNo, long planId,
                                long baselineDocumentId, String processCode, String workbookFilename,
                                String baselineSha256, String sheetName) { }
}
