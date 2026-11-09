package com.example.processautomation.material;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EquipmentInsertionProposal(
        @NotBlank @Size(max = 64) String managementNo,
        @NotBlank @Size(max = 64) String itemNo,
        @NotBlank @Size(max = 255) String facilityName,
        @NotBlank @Size(max = 128) String capacity,
        @NotBlank @Size(max = 64) String quantity,
        @Size(max = 64) String targetSheet,
        @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{1,3}[1-9][0-9]*(?::[A-Z]{1,3}[1-9][0-9]*)?", message = "placementRange must be an A1 cell or range")
        String placementRange,
        @Size(max = 128) String predecessorEquipment,
        @Size(max = 128) String successorEquipment,
        boolean reviewerConfirmed,
        @Size(max = 128) String reviewerName) {
}
