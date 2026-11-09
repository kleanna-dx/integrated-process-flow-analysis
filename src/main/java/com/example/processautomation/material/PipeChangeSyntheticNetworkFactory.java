package com.example.processautomation.material;

import com.example.processautomation.excel.PipeChangeDrawingEngine;
import com.example.processautomation.excel.PipeChangeSyntheticFixtureFactory;
import com.example.processautomation.material.DirectedProcessNetwork.ConnectionType;
import com.example.processautomation.material.DirectedProcessNetwork.MaterialClass;
import com.example.processautomation.material.DirectedProcessNetwork.NodeType;
import com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds the single server-managed synthetic A→B / A→C test graph; it contains no imported process values. */
@Component
public class PipeChangeSyntheticNetworkFactory {
    public NetworkPair create(PipeChangeSyntheticFixtureFactory.Fixture fixture) {
        String period = "2026-11-02/SYNTHETIC_TEST_PERIOD";
        String connectionBefore = "SYN-A-B";
        String connectionAfter = "SYN-A-C";
        List<NetworkWhatIfRequest.Node> nodes = List.of(
                node("IN", null, NodeType.EXTERNAL_SOURCE, "Synthetic boundary in"),
                node("A", "SYN-EQ-A", NodeType.PROCESS_EQUIPMENT, "Synthetic equipment A"),
                node("B", "SYN-EQ-B", NodeType.PROCESS_EQUIPMENT, "Synthetic equipment B"),
                node("C", "SYN-EQ-C", NodeType.PROCESS_EQUIPMENT, "Synthetic equipment C"),
                node("OUT", null, NodeType.EXTERNAL_SINK, "Synthetic boundary out"));
        List<NetworkWhatIfRequest.Edge> beforeEdges = List.of(
                edge("SYN-IN-A", "IN", "A", "", "SYNTHETIC-P", "100", ConnectionType.EXTERNAL_INFLOW, true, period),
                edge(connectionBefore, "A", "B", "SYNTHETIC-P", "SYNTHETIC-P", "20", ConnectionType.SERIES, true, period),
                edge("SYN-A-OUT", "A", "OUT", "SYNTHETIC-P", "", null, ConnectionType.EXTERNAL_OUTFLOW, false, period),
                edge("SYN-B-OUT", "B", "OUT", "SYNTHETIC-P", "", null, ConnectionType.EXTERNAL_OUTFLOW, false, period),
                edge("SYN-C-OUT", "C", "OUT", "SYNTHETIC-P", "", null, ConnectionType.EXTERNAL_OUTFLOW, false, period));
        List<NetworkWhatIfRequest.Edge> afterEdges = List.of(
                beforeEdges.get(0),
                edge(connectionAfter, "A", "C", "SYNTHETIC-P", "SYNTHETIC-P", "20", ConnectionType.SERIES, true, period),
                beforeEdges.get(2), beforeEdges.get(3), beforeEdges.get(4));
        NetworkWhatIfRequest before = request("Synthetic A→B route", "SYN-EQ-B", connectionBefore, period, nodes, beforeEdges);
        NetworkWhatIfRequest after = request("Synthetic A→C route", "SYN-EQ-C", connectionAfter, period, nodes, afterEdges);
        var mapping = new PipeChangeDrawingEngine.SyntheticWhatIfMapping(connectionBefore, connectionAfter,
                fixture.connectorId(), fixture.startShapeId(), fixture.beforeEndShapeId(), fixture.startShapeId(), fixture.afterEndShapeId(),
                "SYNTHETIC-P", "SYN-EQ-A", "SYNTHETIC-P", "SYN-EQ-B",
                "SYNTHETIC-P", "SYN-EQ-A", "SYNTHETIC-P", "SYN-EQ-C",
                "SYNTHETIC:PIPE_CHANGE_ENGINE_FIXTURE:v1");
        return new NetworkPair(before, after, mapping);
    }

    private NetworkWhatIfRequest request(String name, String target, String changedId, String period,
                                         List<NetworkWhatIfRequest.Node> nodes, List<NetworkWhatIfRequest.Edge> edges) {
        return new NetworkWhatIfRequest("TEST_ONLY", "PIPE_CHANGE_SYNTHETIC_FIXTURE_V1", name,
                "SYNTHETIC-P", "SYN-EQ-A", target, MaterialClass.WASTEWATER, "synthetic water", "ton/day",
                period, changedId, new BigDecimal("20"), nodes, edges, List.of(),
                List.of(new NetworkWhatIfRequest.ChangeCase("UNCHANGED", "Retain explicit synthetic quantity", new BigDecimal("20"))));
    }

    private NetworkWhatIfRequest.Node node(String id, String equipment, NodeType type, String label) {
        return new NetworkWhatIfRequest.Node(id, equipment, "SYNTHETIC-P",
                type == NodeType.PROCESS_EQUIPMENT ? "PROCESS_EQUIPMENT" : "EXTERNAL_BOUNDARY",
                type, label, "SYNTHETIC_TEST_ONLY", "Synthetic");
    }

    private NetworkWhatIfRequest.Edge edge(String id, String source, String target, String sourceProcess,
                                           String targetProcess, String quantity, ConnectionType type,
                                           boolean fixed, String period) {
        return new NetworkWhatIfRequest.Edge(id, source, target, sourceProcess, targetProcess,
                MaterialClass.WASTEWATER, "synthetic water", "ton/day",
                quantity == null ? null : new BigDecimal(quantity), type,
                fixed ? QuantityBasis.USER_INPUT : QuantityBasis.CALCULATED, fixed,
                "SYNTHETIC_TEST_ONLY server fixture input", period);
    }

    public record NetworkPair(NetworkWhatIfRequest before, NetworkWhatIfRequest after,
                              PipeChangeDrawingEngine.SyntheticWhatIfMapping mapping) { }
}
