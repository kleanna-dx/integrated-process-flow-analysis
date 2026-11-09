package com.example.processautomation.material;

import com.example.processautomation.material.DirectedProcessNetwork.ConnectionType;
import com.example.processautomation.material.DirectedProcessNetwork.MaterialClass;
import com.example.processautomation.material.DirectedProcessNetwork.NodeType;
import com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Server-created synthetic fixture; it contains no official workbook or inferred process values. */
@Component
public class FlowChangeSyntheticFixtureFactory {
    public static final String FIXTURE_ID = "SERVER_SYNTHETIC_FLOW_FIXTURE_V1";
    public static final String FLOW_EDGE_ID = "SYN-FLOW-P2X-P1N";
    public static final String SHEET_NAME = "Synthetic Flow";
    public static final String PERIOD = "2026-11-02/SYNTHETIC_TEST_PERIOD";
    private final Fixture fixture = build();

    public Fixture create() { return fixture; }

    private Fixture build() {
        byte[] workbook = workbook();
        List<NetworkWhatIfRequest.Node> nodes = List.of(
                node("S-P2", null, "P-02", NodeType.EXTERNAL_SOURCE),
                node("S-P1", null, "P-01", NodeType.EXTERNAL_SOURCE),
                node("X", "SYN-EQ-P2-X", "P-02", NodeType.PROCESS_EQUIPMENT),
                node("N", "SYN-EQ-P1-N", "P-01", NodeType.PROCESS_EQUIPMENT),
                node("OUT-P2", null, "P-02", NodeType.EXTERNAL_SINK),
                node("OUT-P1", null, "P-01", NodeType.EXTERNAL_SINK));
        List<NetworkWhatIfRequest.Edge> edges = List.of(
                edge("SYN-P2-IN", "S-P2", "X", "P-02", "P-02", "100", ConnectionType.EXTERNAL_INFLOW, true),
                edge("SYN-P1-IN", "S-P1", "N", "P-01", "P-01", "100", ConnectionType.EXTERNAL_INFLOW, true),
                edge(FLOW_EDGE_ID, "X", "N", "P-02", "P-01", "20", ConnectionType.INTERPROCESS_RECYCLE, true),
                edge("SYN-P2-OUT", "X", "OUT-P2", "P-02", "", null, ConnectionType.EXTERNAL_OUTFLOW, false),
                edge("SYN-P1-OUT", "N", "OUT-P1", "P-01", "", null, ConnectionType.EXTERNAL_OUTFLOW, false));
        NetworkWhatIfRequest network = new NetworkWhatIfRequest("TEST_ONLY", FIXTURE_ID,
                "Server synthetic FLOW_CHANGE 20 to independent 10/50 cases", "P-02", "SYN-EQ-P2-X", "SYN-EQ-P1-N",
                MaterialClass.WASTEWATER, "synthetic process wastewater", "ton/day", PERIOD,
                FLOW_EDGE_ID, new BigDecimal("20"), nodes, edges, List.of(), List.of(
                new NetworkWhatIfRequest.ChangeCase("FLOW-20", "Unchanged reference value", new BigDecimal("20")),
                new NetworkWhatIfRequest.ChangeCase("FLOW-10", "Independent 20 to 10 case", new BigDecimal("10")),
                new NetworkWhatIfRequest.ChangeCase("FLOW-50", "Independent 20 to 50 case", new BigDecimal("50"))));
        return new Fixture(FIXTURE_ID, workbook, network, List.of("B8", "B17"));
    }

    private byte[] workbook() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET_NAME);
            var format = workbook.createDataFormat();
            var numericStyle = workbook.createCellStyle();
            numericStyle.setDataFormat(format.getFormat("0.00"));
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Synthetic flow fixture — TEST_ONLY, not a plant document");
            sheet.createRow(7).createCell(1).setCellValue(20d);
            sheet.getRow(7).getCell(1).setCellStyle(numericStyle);
            sheet.createRow(16).createCell(1).setCellValue(20d);
            sheet.getRow(16).getCell(1).setCellStyle(numericStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(7, 7, 1, 2));
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setDefaultRowHeightInPoints(18f);
            workbook.setPrintArea(0, "A1:D20");
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create server-owned synthetic FLOW_CHANGE workbook", exception);
        }
    }

    private NetworkWhatIfRequest.Node node(String id, String equipment, String process, NodeType type) {
        return new NetworkWhatIfRequest.Node(id, equipment, process,
                type == NodeType.PROCESS_EQUIPMENT ? "PROCESS_EQUIPMENT" : "EXTERNAL_BOUNDARY",
                type, equipment == null ? id : equipment, "SYNTHETIC_TEST_ONLY", SHEET_NAME);
    }

    private NetworkWhatIfRequest.Edge edge(String id, String source, String target, String sourceProcess,
            String targetProcess, String quantity, ConnectionType type, boolean fixed) {
        return new NetworkWhatIfRequest.Edge(id, source, target, sourceProcess, targetProcess,
                MaterialClass.WASTEWATER, "synthetic process wastewater", "ton/day",
                quantity == null ? null : new BigDecimal(quantity), type,
                fixed ? QuantityBasis.USER_INPUT : QuantityBasis.CALCULATED, fixed,
                "SYNTHETIC:FLOW_CHANGE_FIXTURE_V1", PERIOD);
    }

    public record Fixture(String fixtureId, byte[] workbookBytes, NetworkWhatIfRequest network,
                          List<String> labelAddresses) {
        public Fixture { workbookBytes = workbookBytes.clone(); labelAddresses = List.copyOf(labelAddresses); }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }
}
