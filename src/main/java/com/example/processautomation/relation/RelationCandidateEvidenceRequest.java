package com.example.processautomation.relation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RelationCandidateEvidenceRequest(
        Long sourceEquipmentId,
        Long targetEquipmentId,
        Long sourceDrawingObjectId,
        Long targetDrawingObjectId,
        @Pattern(regexp = "[A-Z]{1,3}[1-9][0-9]*(?::[A-Z]{1,3}[1-9][0-9]*)?")
        String sourceCellRange,
        @Pattern(regexp = "[A-Z]{1,3}[1-9][0-9]*(?::[A-Z]{1,3}[1-9][0-9]*)?")
        String targetCellRange,
        @NotBlank @Size(max = 12000) String evidenceText,
        @NotBlank @Size(max = 128) String actorName) {
}
