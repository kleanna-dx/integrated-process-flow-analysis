package com.example.processautomation.excel;

import java.math.BigDecimal;
import java.util.List;

/** TEST_ONLY projection of a calculated simulation for a separate workbook sheet. */
public record MaterialBalanceExcelOutputModel(
        String simulationId,
        String scenarioName,
        String mode,
        String sourceWorkbookSha256,
        LayoutAudit layoutAudit,
        List<TransferLine> transfers,
        List<BalanceLine> balances,
        List<NodeMappingLine> nodeMappings,
        boolean officialWorkbookCellsModified,
        boolean officialMaterialBalanceUpdated) {

    public record LayoutAudit(
            String existingTestOnlyCandidateId,
            String existingTestOnlyCandidateCellRange,
            String downstreamEquipmentId,
            String downstreamEquipmentCellRange,
            String visualConnectorShapeId,
            String wastewaterLabelCell,
            String wasteLabelCell,
            String status) { }

    public record TransferLine(
            String transferId,
            String senderFlowType,
            String receiverFlowType,
            String sourceNodeId,
            String sourceLabel,
            String sourceProcessCode,
            Long sourceEquipmentId,
            String targetNodeId,
            String targetLabel,
            String targetProcessCode,
            Long targetEquipmentId,
            BigDecimal senderQuantity,
            BigDecimal receiverQuantity,
            String unit,
            String reconciliationStatus,
            boolean included,
            String exclusionReason) { }

    public record BalanceLine(
            String nodeId,
            String label,
            String processCode,
            Long equipmentId,
            String role,
            String receiverType,
            String dispositionStatus,
            String flowType,
            String unit,
            BigDecimal inflow,
            BigDecimal outflow,
            BigDecimal storageChange,
            BigDecimal residual,
            String balanceStatus,
            String boundaryCondition) { }

    public record NodeMappingLine(
            String nodeId,
            String label,
            String processCode,
            Long equipmentId,
            String workbookCellMapping,
            String mappingStatus) { }
}
