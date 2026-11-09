package com.example.processautomation.baseline;

import java.util.List;
import java.util.Map;

public record BaselineRegistrationResult(
        long baselineId,
        String version,
        String masterFilename,
        String masterSha256,
        int equipmentCount,
        int processDocumentCount,
        int processSheetCount,
        int drawingPartCount,
        int shapeCount,
        int connectorCount,
        int pictureCount,
        int mappedByPermitIdCount,
        int mappedByItemNoCount,
        int unmatchedCount,
        int multiSheetCount,
        int validationIssueCount,
        List<String> missingProcessCodes,
        Map<String, Integer> equipmentCountByProcess) {

    public BaselineRegistrationResult {
        missingProcessCodes = List.copyOf(missingProcessCodes);
        equipmentCountByProcess = Map.copyOf(equipmentCountByProcess);
    }
}
