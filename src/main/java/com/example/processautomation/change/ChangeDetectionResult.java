package com.example.processautomation.change;

import java.util.List;
import java.util.Map;

public record ChangeDetectionResult(
        long changeRunId,
        String baselineVersion,
        String analysisMode,
        String uploadedFilename,
        String uploadedSha256,
        int baselineRecordCount,
        int uploadedRecordCount,
        int addedCount,
        int removedCount,
        int modifiedCount,
        int movedCount,
        int validationIssueCount,
        boolean baselineModified,
        String diagramMappingStatus,
        boolean automaticMappingSucceeded,
        List<DiagramImpact> affectedDiagrams,
        List<String> reviewRequiredItems,
        List<DetectedChange> changes,
        List<ChangeIssue> validationIssues) {

    public ChangeDetectionResult {
        affectedDiagrams = List.copyOf(affectedDiagrams);
        reviewRequiredItems = List.copyOf(reviewRequiredItems);
        changes = List.copyOf(changes);
        validationIssues = List.copyOf(validationIssues);
    }

    public record DetectedChange(
            Long changeItemId,
            String changeType,
            String permitManagementNo,
            String businessItemNo,
            String facilityName,
            Integer oldSourceRow,
            Integer newSourceRow,
            Map<String, FieldChange> changedFields,
            List<String> affectedProcessCodes,
            List<String> candidateSheetNames,
            String diagramMappingStatus,
            boolean automaticMappingSucceeded,
            List<DiagramImpact> affectedDiagrams,
            List<String> reviewRequiredItems) {

        public DetectedChange {
            changedFields = Map.copyOf(changedFields);
            affectedProcessCodes = List.copyOf(affectedProcessCodes);
            candidateSheetNames = List.copyOf(candidateSheetNames);
            affectedDiagrams = List.copyOf(affectedDiagrams);
            reviewRequiredItems = List.copyOf(reviewRequiredItems);
        }
    }

    public record DiagramImpact(String processCode, String processWorkbook, String sheetName,
                                String mappingStatus, String matchMethod, String evidence,
                                boolean automaticMappingSucceeded) {
    }

    public record FieldChange(String before, String after) {
    }

    public record ChangeIssue(int sourceRowNumber, String severity, String code, String fieldName, String detail) {
    }
}
