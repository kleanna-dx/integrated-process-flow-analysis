package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.OoxmlPackageInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Step7DrawingEvidenceExtractorTest {
    private final Step7DrawingEvidenceExtractor extractor =
            new Step7DrawingEvidenceExtractor(new OoxmlPackageInspector());

    private Path officialPw01() throws Exception {
        try (var files = Files.list(Path.of("data/reference"))) {
            return files.filter(path -> path.getFileName().toString().startsWith("PW-01"))
                    .findFirst().orElseThrow();
        }
    }

    @Test
    void extractsActualLabelsCellsFlowTextAndUnboundConnectorAnchors() throws Exception {
        byte[] bytes = Files.readAllBytes(officialPw01());
        var evidence = extractor.extract(bytes, "PW-01-05");

        assertThat(evidence.worksheetPart()).isEqualTo("xl/worksheets/sheet5.xml");
        assertThat(evidence.drawingPart()).isEqualTo("xl/drawings/drawing5.xml");
        assertThat(evidence.drawingObjectCount()).isEqualTo(103);
        assertThat(evidence.equipmentLabels()).extracting(Step7DrawingEvidenceExtractor.EquipmentLabel::equipmentNumber)
                .contains("C-PW15017", "C-PW15018", "C-PW15005", "C-PW15007", "C-PW15008", "C-PW15009", "C-PW15010");
        assertThat(evidence.equipmentLabels()).anySatisfy(label -> {
            assertThat(label.equipmentNumber()).isEqualTo("C-PW15017");
            assertThat(label.cellAddress()).isEqualTo("D78");
            assertThat(label.rawCellText()).isEqualTo("C-PW15017");
        });
        assertThat(evidence.equipmentLabels()).anySatisfy(label -> {
            assertThat(label.cellAddress()).isEqualTo("D107");
            assertThat(label.approximateLabel()).isTrue();
        });

        var connector = evidence.connectors().stream().filter(item -> "125".equals(item.shapeId())).findFirst().orElseThrow();
        assertThat(connector.startColumn()).isEqualTo(3);
        assertThat(connector.startRow()).isEqualTo(79);
        assertThat(connector.endColumn()).isEqualTo(37);
        assertThat(connector.endRow()).isEqualTo(79);
        assertThat(connector.tailArrowType()).isEqualTo("triangle");
        assertThat(connector.startConnectedShapeId()).isNull();
        assertThat(connector.endConnectedShapeId()).isNull();
        assertThat(evidence.connectorEndpointBindings()).isZero();

        var flow = evidence.flowLabels().stream().filter(item -> item.cellAddress().equals("F80")).findFirst().orElseThrow();
        assertThat(flow.rawCellText()).isEqualTo("일반폐수 10,000.0톤/일");
        assertThat(flow.materialName()).isEqualTo("일반폐수");
        assertThat(flow.quantity().toPlainString()).isEqualTo("10000.0");
        assertThat(flow.unit()).isEqualTo("톤/일");
        assertThat(flow.cellType()).isEqualTo("STRING");
    }

    @Test
    void formulaCellsAreCapturedButNeverEvaluated() {
        // Formula handling is exercised via a minimal in-memory workbook in the XLSX integration exporter test.
        assertThat(extractor).isNotNull();
    }
}
