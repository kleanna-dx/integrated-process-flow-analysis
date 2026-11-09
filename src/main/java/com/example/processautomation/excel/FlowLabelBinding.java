package com.example.processautomation.excel;

import com.example.processautomation.material.DirectedProcessNetwork;
import java.math.BigDecimal;

/** Explicit, source-SHA-bound mapping between one TEST_ONLY network edge and one workbook flow label. */
public record FlowLabelBinding(
        String bindingId,
        String processCode,
        String sheetName,
        String edgeId,
        TargetType targetType,
        String cellAddress,
        BigDecimal expectedBefore,
        DirectedProcessNetwork.MaterialClass materialClass,
        String materialType,
        String unit,
        String period,
        String sourceSha256,
        String mappingStatus,
        String evidenceId) {

    public enum TargetType { NUMERIC_CELL, MERGED_NUMERIC_CELL, DRAWINGML_TEXT }
}
