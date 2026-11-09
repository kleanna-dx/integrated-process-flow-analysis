package com.example.processautomation.material;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record MaterialBalanceRequest(
        @NotBlank @Size(max = 96) String simulationId,
        @NotBlank @Size(max = 255) String scenarioName,
        @NotBlank @Pattern(regexp = "TEST_ONLY") @Size(max = 16) String mode,
        @NotEmpty @Size(max = 256) List<@Valid Node> nodes,
        @NotEmpty @Size(max = 1000) List<@Valid Transfer> transfers) {

    public record Node(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 255) String label,
            @Size(max = 32) String processCode,
            Long equipmentId,
            @NotBlank @Size(max = 24) String role,
            BigDecimal initialInflow,
            @Size(max = 24) String initialFlowType,
            @Size(max = 64) String initialUnit,
            BigDecimal storageChange,
            @Size(max = 24) String storageFlowType,
            @Size(max = 64) String storageUnit,
            @Size(max = 32) String boundaryCondition,
            @Size(max = 128) String initialPeriod,
            @Size(max = 128) String storagePeriod) {
        public Node(String id, String label, String processCode, Long equipmentId, String role,
                    BigDecimal initialInflow, String initialFlowType, String initialUnit,
                    BigDecimal storageChange, String storageFlowType, String storageUnit, String boundaryCondition) {
            this(id, label, processCode, equipmentId, role, initialInflow, initialFlowType,
                    initialUnit, storageChange, storageFlowType, storageUnit, boundaryCondition, "", "");
        }

        public Node(String id, String label, String processCode, Long equipmentId, String role,
                    BigDecimal initialInflow, String initialFlowType, String initialUnit) {
            this(id, label, processCode, equipmentId, role, initialInflow, initialFlowType,
                    initialUnit, null, null, null, null, "", "");
        }
    }

    public record Transfer(
            @NotBlank @Size(max = 128) String transferId,
            @NotBlank @Size(max = 24) String relationMode,
            Long relationId,
            @Size(max = 128) String sourceId,
            @Size(max = 128) String targetId,
            @Size(max = 40) String edgeType,
            @Size(max = 24) String flowType,
            BigDecimal quantity,
            @Size(max = 64) String unit,
            @Size(max = 1024) String evidence,
            BigDecimal receiverQuantity,
            @NotBlank @Size(max = 128) String period) {
        public Transfer(String transferId, String relationMode, Long relationId, String sourceId, String targetId,
                        String edgeType, String flowType, BigDecimal quantity, String unit, String evidence,
                        BigDecimal receiverQuantity) {
            this(transferId, relationMode, relationId, sourceId, targetId, edgeType, flowType,
                    quantity, unit, evidence, receiverQuantity, "");
        }

        public Transfer(String transferId, String relationMode, Long relationId, String sourceId, String targetId,
                        String edgeType, String flowType, BigDecimal quantity, String unit, String evidence) {
            this(transferId, relationMode, relationId, sourceId, targetId, edgeType, flowType,
                    quantity, unit, evidence, null, "");
        }
    }
}
