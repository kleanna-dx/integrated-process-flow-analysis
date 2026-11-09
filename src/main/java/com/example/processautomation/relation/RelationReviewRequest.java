package com.example.processautomation.relation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RelationReviewRequest(
        Long sourceEquipmentId,
        Long targetEquipmentId,
        Long sourceDrawingObjectId,
        Long targetDrawingObjectId,
        @NotBlank @Size(max = 32) String relationType,
        @Size(max = 24) String flowType,
        BigDecimal quantity,
        @Size(max = 64) String quantityUnit,
        @Size(max = 512) String destinationText,
        @NotBlank @Size(max = 12000) String evidenceText,
        @NotBlank @Size(max = 24) String relationStatus,
        @NotBlank @Size(max = 128) String reviewerName,
        @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{1,3}[1-9][0-9]*(?::[A-Z]{1,3}[1-9][0-9]*)?")
        String sourceCellRange,
        @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{1,3}[1-9][0-9]*(?::[A-Z]{1,3}[1-9][0-9]*)?")
        String targetCellRange) {
}
