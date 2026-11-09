package com.example.processautomation.material;

import java.math.BigDecimal;
import java.util.List;

/** TEST_ONLY handoff contract for a future Excel editor. Unknown workbook mappings remain non-editable. */
public record MaterialFlowExcelChangeContract(
        String scenarioId,
        String status,
        List<String> targetProcesses,
        List<String> targetEquipment,
        List<EdgeChange> edgeChanges,
        List<String> upstreamEquipment,
        List<String> downstreamEquipment,
        List<WorkbookMapping> workbookMappings,
        boolean modificationAllowed,
        List<String> requiredValidation,
        String calculationStatus,
        String evidenceStatus) {

    public MaterialFlowExcelChangeContract {
        targetProcesses = targetProcesses == null ? List.of() : List.copyOf(targetProcesses);
        targetEquipment = targetEquipment == null ? List.of() : List.copyOf(targetEquipment);
        edgeChanges = edgeChanges == null ? List.of() : List.copyOf(edgeChanges);
        upstreamEquipment = upstreamEquipment == null ? List.of() : List.copyOf(upstreamEquipment);
        downstreamEquipment = downstreamEquipment == null ? List.of() : List.copyOf(downstreamEquipment);
        workbookMappings = workbookMappings == null ? List.of() : List.copyOf(workbookMappings);
        requiredValidation = requiredValidation == null ? List.of() : List.copyOf(requiredValidation);
    }

    public record EdgeChange(String connectionId, String sourceProcess, String sourceEquipment,
                             String targetProcess, String targetEquipment, String materialClass,
                             String materialType, String unit, String period,
                             BigDecimal beforeQuantity, BigDecimal afterQuantity,
                             String calculationEvidenceStatus) { }

    public record WorkbookMapping(String connectionId, String workbook, String sheet,
                                  String cell, String shape, String pipe,
                                  String status, boolean editable) { }
}
