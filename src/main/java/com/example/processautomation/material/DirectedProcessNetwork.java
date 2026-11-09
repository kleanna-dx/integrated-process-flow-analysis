package com.example.processautomation.material;

import java.math.BigDecimal;
import java.util.List;

/** Synthetic or explicitly reviewed directed process graph. Step 7-1 accepts TEST_ONLY graphs only. */
public record DirectedProcessNetwork(
        String networkId,
        String scenarioName,
        String mode,
        List<Node> nodes,
        List<Edge> edges,
        List<ProcessTerm> processTerms) {

    public DirectedProcessNetwork {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        processTerms = processTerms == null ? List.of() : List.copyOf(processTerms);
    }

    public enum NodeType { PROCESS_EQUIPMENT, EXTERNAL_SOURCE, EXTERNAL_SINK }
    public enum MaterialClass { WASTEWATER, CHEMICAL, WASTE }
    public enum ConnectionType { SERIES, MERGE, BRANCH, INTERNAL_RECYCLE, INTERPROCESS_RECYCLE, EXTERNAL_INFLOW, EXTERNAL_OUTFLOW }
    public enum QuantityBasis { MEASURED, USER_INPUT, CALCULATED }

    public record Node(
            String nodeId,
            String equipmentNumber,
            String processCode,
            String equipmentType,
            NodeType nodeType,
            String label,
            String sourceDocument,
            String sourceSheet,
            String reviewStatus) { }

    /** A null quantity with fixed=false is an unknown solved from node conservation equations. */
    public record Edge(
            String connectionId,
            String sourceNodeId,
            String targetNodeId,
            String sourceProcess,
            String targetProcess,
            MaterialClass materialClass,
            String materialType,
            String unit,
            BigDecimal quantity,
            ConnectionType connectionType,
            QuantityBasis quantityBasis,
            boolean userSupplied,
            boolean fixed,
            String evidence,
            String reviewStatus,
            String period) {
        public Edge(String connectionId, String sourceNodeId, String targetNodeId,
                    String sourceProcess, String targetProcess, MaterialClass materialClass,
                    String materialType, String unit, BigDecimal quantity, ConnectionType connectionType,
                    QuantityBasis quantityBasis, boolean userSupplied, boolean fixed,
                    String evidence, String reviewStatus) {
            this(connectionId, sourceNodeId, targetNodeId, sourceProcess, targetProcess, materialClass,
                    materialType, unit, quantity, connectionType, quantityBasis, userSupplied, fixed,
                    evidence, reviewStatus, "");
        }
    }

    /** Positive quantity is generation; negative quantity is verified consumption. */
    public record ProcessTerm(
            String nodeId,
            String processCode,
            MaterialClass materialClass,
            String materialType,
            String unit,
            BigDecimal netGeneration,
            QuantityBasis quantityBasis,
            String evidence,
            String reviewStatus,
            String period) {
        public ProcessTerm(String nodeId, String processCode, MaterialClass materialClass,
                           String materialType, String unit, BigDecimal netGeneration,
                           QuantityBasis quantityBasis, String evidence, String reviewStatus) {
            this(nodeId, processCode, materialClass, materialType, unit, netGeneration,
                    quantityBasis, evidence, reviewStatus, "");
        }
    }
}
