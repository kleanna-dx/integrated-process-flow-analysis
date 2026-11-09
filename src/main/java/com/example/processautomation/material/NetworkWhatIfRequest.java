package com.example.processautomation.material;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Request contract for an isolated, user-supplied TEST_ONLY network what-if run. */
public record NetworkWhatIfRequest(
        @NotBlank @Pattern(regexp = "TEST_ONLY") String mode,
        @NotBlank @Size(max = 96) String simulationId,
        @NotBlank @Size(max = 255) String scenarioName,
        @NotBlank @Size(max = 32) String processCode,
        @NotBlank @Size(max = 128) String sourceEquipment,
        @NotBlank @Size(max = 128) String targetEquipment,
        @NotNull DirectedProcessNetwork.MaterialClass materialClass,
        @NotBlank @Size(max = 128) String materialType,
        @NotBlank @Size(max = 64) String unit,
        @NotBlank @Size(max = 128) String period,
        @NotBlank @Size(max = 128) String changedConnectionId,
        @NotNull BigDecimal baseQuantity,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Node> nodes,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull Edge> edges,
        @Size(max = 100) List<@Valid @NotNull ProcessTerm> processTerms,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull ChangeCase> scenarios) {

    public NetworkWhatIfRequest {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        processTerms = processTerms == null ? List.of() : List.copyOf(processTerms);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    public record Node(
            @NotBlank @Size(max = 128) String nodeId,
            @Size(max = 128) String equipmentNumber,
            @Size(max = 32) String processCode,
            @NotBlank @Size(max = 64) String equipmentType,
            @NotNull DirectedProcessNetwork.NodeType nodeType,
            @NotBlank @Size(max = 255) String label,
            @NotBlank @Size(max = 255) String sourceDocument,
            @NotBlank @Size(max = 128) String sourceSheet) { }

    public record Edge(
            @NotBlank @Size(max = 128) String connectionId,
            @NotBlank @Size(max = 128) String sourceNodeId,
            @NotBlank @Size(max = 128) String targetNodeId,
            @Size(max = 32) String sourceProcess,
            @Size(max = 32) String targetProcess,
            @NotNull DirectedProcessNetwork.MaterialClass materialClass,
            @NotBlank @Size(max = 128) String materialType,
            @NotBlank @Size(max = 64) String unit,
            BigDecimal quantity,
            @NotNull DirectedProcessNetwork.ConnectionType connectionType,
            @NotNull DirectedProcessNetwork.QuantityBasis quantityBasis,
            boolean fixed,
            @NotBlank @Size(max = 512) String evidence,
            @NotBlank @Size(max = 128) String period) { }

    public record ProcessTerm(
            @Size(max = 128) String nodeId,
            @NotBlank @Size(max = 32) String processCode,
            @NotNull DirectedProcessNetwork.MaterialClass materialClass,
            @NotBlank @Size(max = 128) String materialType,
            @NotBlank @Size(max = 64) String unit,
            @NotNull BigDecimal netGeneration,
            @NotNull DirectedProcessNetwork.QuantityBasis quantityBasis,
            @NotBlank @Size(max = 512) String evidence,
            @NotBlank @Size(max = 128) String period) { }

    public record ChangeCase(
            @NotBlank @Size(max = 64) String scenarioId,
            @NotBlank @Size(max = 255) String description,
            @NotNull BigDecimal newQuantity) { }
}
