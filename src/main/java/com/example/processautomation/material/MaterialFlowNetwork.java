package com.example.processautomation.material;

import java.math.BigDecimal;
import java.util.List;

/** Immutable, explicit process/equipment directed graph contract. Runtime use is TEST_ONLY. */
public record MaterialFlowNetwork(String networkId, String scenarioName, String mode,
                                  List<Process> processes, List<Equipment> equipment,
                                  List<Connection> connections, List<TransferRule> transferRules) {
    public MaterialFlowNetwork(String networkId, String scenarioName, String mode,
                               List<Process> processes, List<Equipment> equipment,
                               List<Connection> connections) {
        this(networkId, scenarioName, mode, processes, equipment, connections, List.of());
    }

    public MaterialFlowNetwork {
        processes = processes == null ? List.of() : List.copyOf(processes);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        connections = connections == null ? List.of() : List.copyOf(connections);
        transferRules = transferRules == null ? List.of() : List.copyOf(transferRules);
    }

    public enum EvidenceStatus { VERIFIED, REVIEW_REQUIRED, UNKNOWN, CONFLICT }
    public enum EndpointType { PROCESS, EQUIPMENT, EXTERNAL_SOURCE, EXTERNAL_SINK }
    public enum FlowType { INFLOW, OUTFLOW, TRANSFER, INTERNAL_RECYCLE, INTERPROCESS_RECYCLE, BRANCH, MERGE }
    public enum MaterialClass { WASTEWATER, CHEMICAL, WASTE, OTHER }
    public enum TransferMode { PASS_THROUGH, BRANCH_RATIO, SUM_INPUTS, TRANSFORM }
    public enum ChangeType { FLOW_CHANGE, PIPE_CHANGE, EDGE_ADD, EDGE_DELETE,
        BRANCH_ALLOCATION_CHANGE, INTERNAL_RECYCLE_CHANGE, INTERPROCESS_TRANSFER_CHANGE }

    public record Process(String processCode, String label, String sourceDocument,
                         EvidenceStatus evidenceStatus) { }

    public record Equipment(String equipmentId, String processCode, String label,
                            String sourceDocument, EvidenceStatus evidenceStatus) { }

    /** A null quantity means unknown; it is never inferred by this engine. */
    public record Connection(String connectionId, EndpointType sourceType, String sourceId,
                             EndpointType targetType, String targetId, FlowType flowType, MaterialClass materialClass,
                             String materialType, String unit, String period, BigDecimal quantity,
                             String dataSource, EvidenceStatus evidenceStatus, String evidence,
                             boolean explicitAllocation, BigDecimal allocationRatio,
                             boolean scenarioOnly) { }

    /** Explicitly evidenced rule: derive one edge quantity from named, same-stream basis edges. */
    public record TransferRule(String connectionId, TransferMode mode, List<String> basisConnectionIds,
                               BigDecimal factor, String dataSource, EvidenceStatus evidenceStatus,
                               String evidence, boolean scenarioOnly) {
        public TransferRule {
            basisConnectionIds = basisConnectionIds == null ? List.of() : List.copyOf(basisConnectionIds);
        }
    }

    public record AllocationChange(String connectionId, BigDecimal allocationRatio) { }

    public record Scenario(String scenarioId, String description, ChangeType changeType,
                           String connectionId, BigDecimal newQuantity,
                           Connection replacementOrAddition, List<AllocationChange> allocationChanges) {
        public Scenario(String scenarioId, String description, ChangeType changeType,
                        String connectionId, BigDecimal newQuantity, Connection replacementOrAddition) {
            this(scenarioId, description, changeType, connectionId, newQuantity, replacementOrAddition, List.of());
        }
        public Scenario {
            allocationChanges = allocationChanges == null ? List.of() : List.copyOf(allocationChanges);
        }
    }
}
