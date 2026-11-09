package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

class PipeChangeDrawingEngineSyntheticTest {
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final PipeChangeDrawingEngine engine = new PipeChangeDrawingEngine(inspector, parts);

    @Test
    void changesOnlySelectedSyntheticConnectorAndPreservesOtherPackageParts() throws Exception {
        Fixture fixture = fixture(false, false);
        var preview = engine.analyze(fixture.bytes(), fixture.request());
        assertThat(preview.status()).as(preview.blockers().toString()).isEqualTo("SYNTHETIC_EDITABLE_TEST_ONLY");
        assertThat(preview.physicalEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");

        var generated = engine.generate(fixture.bytes(), fixture.request(), preview);
        assertThat(generated.outputSha256()).isEqualTo(sha(generated.bytes()));
        assertThat(generated.outputSha256()).isNotEqualTo(sha(fixture.bytes()));
        var result = inspector.inspectSheet(generated.bytes(), "Synthetic");
        var connector = result.drawingObjects().stream().filter(o -> fixture.connectorId().equals(o.shapeId())).findFirst().orElseThrow();
        assertThat(connector.startConnectedShapeId()).isEqualTo(fixture.startId());
        assertThat(connector.endConnectedShapeId()).isEqualTo(fixture.afterEndId());
        assertThat(connector.startAnchorXEmu()).isEqualTo(fixture.beforeConnector().startAnchorXEmu());
        assertThat(connector.endAnchorYEmu()).isNotEqualTo(fixture.beforeConnector().endAnchorYEmu());
        assertThat(connector.geometryPreset()).isEqualTo("straightConnector1");
        assertThat(result.drawingObjects()).filteredOn(o -> !fixture.connectorId().equals(o.shapeId()))
                .containsExactlyElementsOf(fixture.beforeObjects().stream().filter(o -> !fixture.connectorId().equals(o.shapeId())).toList());
        assertThat(generated.safetyNotice()).contains("physical connection remains unverified", "no flow was inferred");

        Path artifactRoot = Path.of("target/test-artifacts/step8-2-pipe-change");
        Files.createDirectories(artifactRoot);
        Path runDirectory = Files.createTempDirectory(artifactRoot, "run-");
        Path beforeArtifact = runDirectory.resolve("fixture-BEFORE-SYNTHETIC_TEST_ONLY.xlsx");
        Path out = runDirectory.resolve("fixture-AFTER-SYNTHETIC_TEST_ONLY.xlsx");
        Files.write(beforeArtifact, fixture.bytes());
        Files.write(out, generated.bytes());
        Files.writeString(runDirectory.resolve("sha256-manifest.txt"), sha(fixture.bytes()) + "  " + beforeArtifact.getFileName()
                + "\n" + generated.outputSha256() + "  " + out.getFileName() + "\n");
        try (var original = new XSSFWorkbook(new ByteArrayInputStream(fixture.bytes()));
             var reopened = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            var originalSheet = original.getSheet("Synthetic");
            var generatedSheet = reopened.getSheet("Synthetic");
            assertThat(generatedSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("fixture-cell");
            assertThat(generatedSheet.getNumMergedRegions()).isEqualTo(1);
            assertThat(reopened.getPrintArea(reopened.getSheetIndex("Synthetic"))).contains("A1:F10");
            assertThat(generatedSheet.getRow(0).getHeight()).isEqualTo(originalSheet.getRow(0).getHeight());
            assertThat(generatedSheet.getColumnWidth(0)).isEqualTo(originalSheet.getColumnWidth(0));
            assertThat(generatedSheet.getColumnWidth(5)).isEqualTo(originalSheet.getColumnWidth(5));
            assertThat(generatedSheet.getRow(0).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(originalSheet.getRow(0).getCell(0).getCellStyle().getIndex());
        }
    }

    @Test
    void rejectsDuplicateIdsMissingEndpointCrossSheetAndUnverifiedActualRequest() throws Exception {
        Fixture fixture = fixture(false, false);
        byte[] duplicate = duplicateShapeId(fixture.bytes(), fixture.startId(), fixture.afterEndId());
        var duplicateAssessment = engine.analyze(duplicate, request(fixture, sha(duplicate), true,
                "Synthetic", "Synthetic", "EXPLICIT_SYNTHETIC_VERIFIED"));
        assertThat(duplicateAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(duplicateAssessment.blockers()).anyMatch(value -> value.startsWith("DUPLICATE_DRAWING_OBJECT_IDS"));

        var missing = request(fixture, sha(fixture.bytes()), true, "Synthetic", "Synthetic", "EXPLICIT_SYNTHETIC_VERIFIED");
        missing = withAfterEnd(missing, "999999");
        assertThat(engine.analyze(fixture.bytes(), missing).blockers()).contains("AFTER_END_SHAPE_NOT_UNIQUE_OR_MISSING");

        var crossSheet = request(fixture, sha(fixture.bytes()), true, "Synthetic", "Other", "EXPLICIT_SYNTHETIC_VERIFIED");
        assertThat(engine.analyze(fixture.bytes(), crossSheet).blockers()).contains("CROSS_SHEET_ENDPOINTS_UNSUPPORTED");

        var actual = request(fixture, sha(fixture.bytes()), false, "Synthetic", "Synthetic", "EXPLICIT_SYNTHETIC_VERIFIED");
        assertThat(engine.analyze(fixture.bytes(), actual).status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void staleShaAndChangedDrawingFingerprintCannotGenerate() throws Exception {
        Fixture fixture = fixture(false, false);
        var preview = engine.analyze(fixture.bytes(), fixture.request());
        assertThat(preview.status()).as(preview.blockers().toString()).isEqualTo("SYNTHETIC_EDITABLE_TEST_ONLY");
        byte[] changed = parts.replaceCellText(fixture.bytes(), "Synthetic", "A1", "fixture-cell", "changed-cell");
        assertThatThrownBy(() -> engine.generate(changed, fixture.request(), preview))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");

        var wrongSha = request(fixture, "0".repeat(64), true, "Synthetic", "Synthetic", "EXPLICIT_SYNTHETIC_VERIFIED");
        assertThat(engine.analyze(fixture.bytes(), wrongSha).blockers()).contains("WORKING_COPY_SHA_MISMATCH");
    }

    @Test
    void mapsDrawingPipeChangeToBeforeAfterSyntheticNetworkAndKeepsReadinessUnavailable() throws Exception {
        Fixture fixture = fixture(false, false);
        var assessment = engine.analyze(fixture.bytes(), fixture.request());
        var beforeRequest = network(fixture, false);
        var afterRequest = network(fixture, true);
        var mapping = new PipeChangeDrawingEngine.SyntheticWhatIfMapping("SYN-A-B", "SYN-A-C",
                fixture.connectorId(), fixture.startId(), fixture.beforeEndId(), fixture.startId(), fixture.afterEndId(),
                "SYNTHETIC-P", "SYN-EQ-A", "SYNTHETIC-P", "SYN-EQ-B",
                "SYNTHETIC-P", "SYN-EQ-A", "SYNTHETIC-P", "SYN-EQ-C",
                "SYNTHETIC:PIPE_CHANGE_ENGINE_TEST_FIXTURE");
        var mappingCheck = engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, afterRequest);
        assertThat(mappingCheck.status()).isEqualTo("SYNTHETIC_MAPPING_VERIFIED_TEST_ONLY");
        assertThat(mappingCheck.unchangedEdgesPreserved()).isTrue();
        assertThat(mappingCheck.officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");

        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);
        var whatIf = new com.example.processautomation.material.NetworkWhatIfService(jdbc,
                new com.example.processautomation.material.MaterialBalanceCalculator());
        var before = whatIf.calculate(beforeRequest);
        var after = whatIf.calculate(afterRequest);
        var agreement = engine.compareCalculatedWhatIf(assessment, mapping, before, after);
        assertThat(before.baseline().status()).isEqualTo("CALCULATED");
        assertThat(after.baseline().status()).isEqualTo("CALCULATED");
        assertThat(before.actualReadiness()).containsEntry("status", "CALCULATION_UNAVAILABLE");
        assertThat(after.actualReadiness()).containsEntry("status", "CALCULATION_UNAVAILABLE");
        assertThat(before.scenarios().get(0).result().impactedEquipmentNumbers()).contains("SYN-EQ-A", "SYN-EQ-B");
        assertThat(after.scenarios().get(0).result().impactedEquipmentNumbers()).contains("SYN-EQ-A", "SYN-EQ-C");
        var beforeEdge = com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(before).stream()
                .filter(edge -> edge.connectionId().equals("SYN-A-B")).findFirst().orElseThrow();
        var afterEdge = com.example.processautomation.material.NetworkWhatIfService.calculatedEdgeSnapshots(after).stream()
                .filter(edge -> edge.connectionId().equals("SYN-A-C")).findFirst().orElseThrow();
        assertThat(beforeEdge.sourceEquipment()).isEqualTo("SYN-EQ-A");
        assertThat(beforeEdge.targetEquipment()).isEqualTo("SYN-EQ-B");
        assertThat(afterEdge.sourceEquipment()).isEqualTo("SYN-EQ-A");
        assertThat(afterEdge.targetEquipment()).isEqualTo("SYN-EQ-C");
        assertThat(beforeEdge.quantity()).isEqualByComparingTo("20");
        assertThat(afterEdge.quantity()).isEqualByComparingTo("20");
        assertThat(agreement.status()).as(agreement.blockers().toString()).isEqualTo("DRAWING_WHATIF_MATCHED_TEST_ONLY");
        assertThat(agreement.beforeConnectionId()).isEqualTo("SYN-A-B");
        assertThat(agreement.afterConnectionId()).isEqualTo("SYN-A-C");
        assertThat(agreement.drawingAndWhatIfEndpointsAgree()).isTrue();
        assertThat(agreement.unchangedEdgesPreserved()).isTrue();
        assertThat(agreement.bothReportsCalculated()).isTrue();
        assertThat(agreement.officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");

        var ambiguousAfter = new com.example.processautomation.material.NetworkWhatIfRequest(afterRequest.mode(),
                afterRequest.simulationId(), afterRequest.scenarioName(), afterRequest.processCode(),
                afterRequest.sourceEquipment(), afterRequest.targetEquipment(), afterRequest.materialClass(),
                afterRequest.materialType(), afterRequest.unit(), afterRequest.period(), afterRequest.changedConnectionId(),
                afterRequest.baseQuantity(), afterRequest.nodes(),
                java.util.stream.Stream.concat(afterRequest.edges().stream(), afterRequest.edges().stream()
                        .filter(edge -> edge.connectionId().equals("SYN-A-C"))).toList(),
                afterRequest.processTerms(), afterRequest.scenarios());
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, ambiguousAfter).blockers())
                .contains("WHAT_IF_CONNECTION_ID_NOT_UNIQUE");
    }

    @Test
    void rejectsNetworkMappingBasisQuantityAndUnchangedEdgeDrift() throws Exception {
        Fixture fixture = fixture(false, false);
        var assessment = engine.analyze(fixture.bytes(), fixture.request());
        var pair = new com.example.processautomation.material.PipeChangeSyntheticNetworkFactory().create(
                new PipeChangeSyntheticFixtureFactory(inspector, parts).fixture());
        var mapping = pair.mapping();
        var beforeRequest = pair.before();
        var afterRequest = pair.after();

        var wrongEndpoint = new PipeChangeDrawingEngine.SyntheticWhatIfMapping(mapping.beforeConnectionId(),
                mapping.afterConnectionId(), mapping.connectorShapeId(), mapping.beforeStartShapeId(),
                mapping.beforeEndShapeId(), mapping.afterStartShapeId(), mapping.beforeEndShapeId(),
                mapping.beforeSourceProcess(), mapping.beforeSourceEquipment(), mapping.beforeTargetProcess(),
                mapping.beforeTargetEquipment(), mapping.afterSourceProcess(), mapping.afterSourceEquipment(),
                mapping.afterTargetProcess(), mapping.afterTargetEquipment(), mapping.evidenceId());
        assertThat(engine.verifyWhatIfMapping(assessment, wrongEndpoint, beforeRequest, afterRequest).blockers())
                .contains("DRAWING_MAPPING_DOES_NOT_MATCH_ASSESSMENT");

        var duplicateId = withEdges(afterRequest, java.util.stream.Stream.concat(afterRequest.edges().stream(),
                afterRequest.edges().stream().filter(edge -> edge.connectionId().equals("SYN-A-C"))).toList());
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, duplicateId).blockers())
                .contains("WHAT_IF_CONNECTION_ID_NOT_UNIQUE");
        var missingId = withEdges(afterRequest, afterRequest.edges().stream()
                .filter(edge -> !edge.connectionId().equals("SYN-A-C")).toList());
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, missingId).blockers())
                .contains("WHAT_IF_CONNECTION_ID_NOT_UNIQUE");

        var wrongPeriod = replaceConnection(afterRequest, "SYN-A-C", edge -> edgeWith(edge,
                null, null, null, null, "OTHER_PERIOD"));
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, wrongPeriod).blockers())
                .contains("PIPE_CHANGE_MUST_NOT_CHANGE_OR_INFER_FLOW_BASIS");
        var wrongUnit = replaceConnection(afterRequest, "SYN-A-C", edge -> edgeWith(edge,
                null, null, "kg/day", null, null));
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, wrongUnit).blockers())
                .contains("PIPE_CHANGE_MUST_NOT_CHANGE_OR_INFER_FLOW_BASIS");
        var wrongMaterial = replaceConnection(afterRequest, "SYN-A-C", edge -> edgeWith(edge,
                com.example.processautomation.material.DirectedProcessNetwork.MaterialClass.CHEMICAL,
                "synthetic chemical", null, null, null));
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, wrongMaterial).blockers())
                .contains("PIPE_CHANGE_MUST_NOT_CHANGE_OR_INFER_FLOW_BASIS");
        var wrongQuantity = replaceConnection(afterRequest, "SYN-A-C", edge -> edgeWith(edge,
                null, null, null, new java.math.BigDecimal("21"), null));
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, wrongQuantity).blockers())
                .contains("PIPE_CHANGE_MUST_NOT_CHANGE_OR_INFER_FLOW_BASIS");
        var changedUnrelated = replaceConnection(afterRequest, "SYN-IN-A", edge -> edgeWith(edge,
                null, null, null, new java.math.BigDecimal("101"), null));
        assertThat(engine.verifyWhatIfMapping(assessment, mapping, beforeRequest, changedUnrelated).blockers())
                .contains("UNRELATED_WHAT_IF_EDGE_CHANGED_OR_MAPPING_AMBIGUOUS");
    }

    @Test
    void blocksGeometryInterferenceAndASecondConnectorThatSharesAffectedEndpoint() throws Exception {
        Fixture crossing = fixture(true, false);
        assertThat(engine.analyze(crossing.bytes(), crossing.request()).blockers())
                .anyMatch(value -> value.startsWith("OTHER_SHAPE_INTERFERES_WITH_PROPOSED_CONNECTOR"));

        Fixture shared = fixture(false, true);
        assertThat(engine.analyze(shared.bytes(), shared.request()).blockers())
                .anyMatch(value -> value.startsWith("OTHER_CONNECTOR_SHARES_AFFECTED_ENDPOINT"));
    }

    private com.example.processautomation.material.NetworkWhatIfRequest withEdges(
            com.example.processautomation.material.NetworkWhatIfRequest request,
            java.util.List<com.example.processautomation.material.NetworkWhatIfRequest.Edge> edges) {
        return new com.example.processautomation.material.NetworkWhatIfRequest(request.mode(), request.simulationId(),
                request.scenarioName(), request.processCode(), request.sourceEquipment(), request.targetEquipment(),
                request.materialClass(), request.materialType(), request.unit(), request.period(),
                request.changedConnectionId(), request.baseQuantity(), request.nodes(), edges,
                request.processTerms(), request.scenarios());
    }

    private com.example.processautomation.material.NetworkWhatIfRequest replaceConnection(
            com.example.processautomation.material.NetworkWhatIfRequest request, String id,
            java.util.function.UnaryOperator<com.example.processautomation.material.NetworkWhatIfRequest.Edge> update) {
        return withEdges(request, request.edges().stream().map(edge -> edge.connectionId().equals(id)
                ? update.apply(edge) : edge).toList());
    }

    private com.example.processautomation.material.NetworkWhatIfRequest.Edge edgeWith(
            com.example.processautomation.material.NetworkWhatIfRequest.Edge edge,
            com.example.processautomation.material.DirectedProcessNetwork.MaterialClass materialClass,
            String materialType, String unit, java.math.BigDecimal quantity, String period) {
        return new com.example.processautomation.material.NetworkWhatIfRequest.Edge(edge.connectionId(),
                edge.sourceNodeId(), edge.targetNodeId(), edge.sourceProcess(), edge.targetProcess(),
                materialClass == null ? edge.materialClass() : materialClass,
                materialType == null ? edge.materialType() : materialType,
                unit == null ? edge.unit() : unit, quantity == null ? edge.quantity() : quantity,
                edge.connectionType(), edge.quantityBasis(), edge.fixed(), edge.evidence(),
                period == null ? edge.period() : period);
    }

    private com.example.processautomation.material.NetworkWhatIfRequest network(Fixture fixture, boolean after) {
        String period = "2026-11-01/TEST_PERIOD";
        String targetNode = after ? "C" : "B";
        String targetEquipment = after ? "SYN-EQ-C" : "SYN-EQ-B";
        String changedId = after ? "SYN-A-C" : "SYN-A-B";
        var nodes = java.util.List.of(
                new com.example.processautomation.material.NetworkWhatIfRequest.Node("IN", null, "SYNTHETIC-P",
                        "EXTERNAL_BOUNDARY", com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE,
                        "Synthetic source", "TEST_ONLY", "Synthetic"),
                new com.example.processautomation.material.NetworkWhatIfRequest.Node("A", "SYN-EQ-A", "SYNTHETIC-P",
                        "PROCESS_EQUIPMENT", com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT,
                        "Synthetic A", "TEST_ONLY", "Synthetic"),
                new com.example.processautomation.material.NetworkWhatIfRequest.Node("B", "SYN-EQ-B", "SYNTHETIC-P",
                        "PROCESS_EQUIPMENT", com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT,
                        "Synthetic B", "TEST_ONLY", "Synthetic"),
                new com.example.processautomation.material.NetworkWhatIfRequest.Node("C", "SYN-EQ-C", "SYNTHETIC-P",
                        "PROCESS_EQUIPMENT", com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT,
                        "Synthetic C", "TEST_ONLY", "Synthetic"),
                new com.example.processautomation.material.NetworkWhatIfRequest.Node("OUT", null, "SYNTHETIC-P",
                        "EXTERNAL_BOUNDARY", com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SINK,
                        "Synthetic sink", "TEST_ONLY", "Synthetic"));
        var edges = java.util.List.of(
                networkEdge("SYN-IN-A", "IN", "A", "", "SYNTHETIC-P", "100",
                        com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, true, period),
                networkEdge(changedId, "A", targetNode, "SYNTHETIC-P", "SYNTHETIC-P", "20",
                        com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.SERIES, true, period),
                networkEdge("SYN-A-OUT", "A", "OUT", "SYNTHETIC-P", "", null,
                        com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, false, period),
                networkEdge("SYN-B-OUT", "B", "OUT", "SYNTHETIC-P", "", null,
                        com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, false, period),
                networkEdge("SYN-C-OUT", "C", "OUT", "SYNTHETIC-P", "", null,
                        com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, false, period));
        return new com.example.processautomation.material.NetworkWhatIfRequest("TEST_ONLY", "synthetic-pipe-8-2",
                after ? "Synthetic A to C" : "Synthetic A to B", "SYNTHETIC-P", "SYN-EQ-A", targetEquipment,
                com.example.processautomation.material.DirectedProcessNetwork.MaterialClass.WASTEWATER,
                "synthetic water", "ton/day", period, changedId, new java.math.BigDecimal("20"), nodes, edges,
                java.util.List.of(), java.util.List.of(new com.example.processautomation.material.NetworkWhatIfRequest.ChangeCase(
                        "SAME-FLOW", "Retain explicit synthetic flow", new java.math.BigDecimal("20"))));
    }

    private com.example.processautomation.material.NetworkWhatIfRequest.Edge networkEdge(String id, String source,
            String target, String sourceProcess, String targetProcess, String quantity,
            com.example.processautomation.material.DirectedProcessNetwork.ConnectionType connectionType,
            boolean fixed, String period) {
        return new com.example.processautomation.material.NetworkWhatIfRequest.Edge(id, source, target,
                sourceProcess, targetProcess, com.example.processautomation.material.DirectedProcessNetwork.MaterialClass.WASTEWATER,
                "synthetic water", "ton/day", quantity == null ? null : new java.math.BigDecimal(quantity), connectionType,
                fixed ? com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis.USER_INPUT
                        : com.example.processautomation.material.DirectedProcessNetwork.QuantityBasis.CALCULATED,
                fixed, "SYNTHETIC_TEST_ONLY explicit network input", period);
    }

    private Fixture fixture(boolean crossingShape, boolean secondConnector) throws Exception {
        byte[] base;
        String sheetName = "Synthetic";
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(sheetName);
            sheet.createRow(0).createCell(0).setCellValue("fixture-cell");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(8, 8, 0, 2));
            workbook.setPrintArea(0, "A1:F10");
            var drawing = sheet.createDrawingPatriarch();
            sheet.setColumnWidth(1, 24 * 256);
            sheet.setColumnWidth(5, 24 * 256);
            sheet.createRow(1).setHeightInPoints(32);
            var left = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 1, 1, 2, 2));
            left.setText("A");
            var oldTarget = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 3, 1, 4, 2));
            oldTarget.setText("B");
            var newTarget = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 5, 5, 6, 6));
            newTarget.setText("C");
            if (crossingShape) {
                var obstacle = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 3, 3, 4, 4));
                obstacle.setText("Obstacle");
            }
            var connector = drawing.createConnector(drawing.createAnchor(0, 0, 0, 0, 1, 1, 3, 1));
            connector.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1);
            connector.setLineStyleColor(31, 78, 121);
            connector.setLineWidth(2.25);
            if (secondConnector) {
                var parallel = drawing.createConnector(drawing.createAnchor(0, 0, 0, 0, 1, 1, 3, 1));
                parallel.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); workbook.write(out); base = out.toByteArray();
        }
        var objects = inspector.inspectSheet(base, sheetName).drawingObjects();
        String startId = objects.stream().filter(o -> "SHAPE".equals(o.objectType()) && "A".equals(o.textContent())).findFirst().orElseThrow().shapeId();
        String oldId = objects.stream().filter(o -> "SHAPE".equals(o.objectType()) && "B".equals(o.textContent())).findFirst().orElseThrow().shapeId();
        String newId = objects.stream().filter(o -> "SHAPE".equals(o.objectType()) && "C".equals(o.textContent())).findFirst().orElseThrow().shapeId();
        var connectors = objects.stream().filter(o -> "CONNECTOR".equals(o.objectType())).toList();
        String connectorId = connectors.get(0).shapeId();
        String secondId = connectors.size() > 1 ? connectors.get(1).shapeId() : null;
        String drawingPart = inspector.inspectSheet(base, sheetName).drawingPartName();
        byte[] patched = patchConnections(base, drawingPart, connectorId, startId, oldId, secondId, startId, newId);
        var finalObjects = inspector.inspectSheet(patched, sheetName).drawingObjects();
        String finalConnectorId = finalObjects.stream().filter(o -> "CONNECTOR".equals(o.objectType())).findFirst().orElseThrow().shapeId();
        var beforeConnector = finalObjects.stream().filter(o -> finalConnectorId.equals(o.shapeId())).findFirst().orElseThrow();
        var request = new PipeChangeDrawingEngine.Request("synthetic-document", sheetName, sha(patched), finalConnectorId,
                startId, oldId, startId, newId, "EXPLICIT_SYNTHETIC_VERIFIED", true,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", sheetName, sheetName);
        return new Fixture(patched, request, startId, oldId, newId, finalConnectorId, beforeConnector, finalObjects);
    }

    private byte[] patchConnections(byte[] bytes, String drawingPart, String connectorId,
                                    String startId, String endId, String secondId,
                                    String secondStart, String secondEnd) throws Exception {
        var document = parse(parts.readPart(bytes, drawingPart));
        var drawingObjects = inspector.inspectSheet(bytes, "Synthetic").drawingObjects();
        int nextShapeId = drawingObjects.stream()
                .map(WorkbookInventory.DrawingObject::shapeId).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::parseInt).max().orElse(0) + 1;
        int connectorIndex = 0;
        for (Element connector : elements(document, "cxnSp")) {
            Element nv = first(connector, XDR, "cNvPr");
            if (nv == null) continue;
            if (connectorIndex == 0) {
                nv.setAttribute("id", Integer.toString(nextShapeId));
                setConnections(connector, startId, endId);
                makeConnectorVisible(connector);
                alignConnector(connector, drawingObjects, startId, endId);
            } else if (connectorIndex == 1 && secondId != null) {
                nv.setAttribute("id", Integer.toString(nextShapeId + 1));
                setConnections(connector, secondStart, secondEnd);
                alignConnector(connector, drawingObjects, secondStart, secondEnd);
            }
            connectorIndex++;
        }
        return parts.replacePackagePart(bytes, drawingPart, serialize(document));
    }

    private byte[] duplicateShapeId(byte[] bytes, String sourceId, String duplicateId) throws Exception {
        String drawing = inspector.inspectSheet(bytes, "Synthetic").drawingPartName();
        var document = parse(parts.readPart(bytes, drawing));
        for (Element shape : elements(document, "sp")) {
            Element nv = first(shape, XDR, "cNvPr");
            if (nv != null && sourceId.equals(nv.getAttribute("id"))) { nv.setAttribute("id", duplicateId); break; }
        }
        return parts.replacePackagePart(bytes, drawing, serialize(document));
    }

    private void setConnections(Element connector, String startId, String endId) {
        Element nonVisual = first(connector, XDR, "cNvCxnSpPr");
        if (nonVisual == null) throw new IllegalStateException("POI connector non-visual element missing");
        Element start = first(nonVisual, A, "stCxn");
        if (start == null) { start = connector.getOwnerDocument().createElementNS(A, "a:stCxn"); nonVisual.appendChild(start); }
        Element end = first(nonVisual, A, "endCxn");
        if (end == null) { end = connector.getOwnerDocument().createElementNS(A, "a:endCxn"); nonVisual.appendChild(end); }
        start.setAttribute("id", startId); start.setAttribute("idx", "0");
        end.setAttribute("id", endId); end.setAttribute("idx", "0");
    }

    private void makeConnectorVisible(Element connector) {
        Element shapeProperties = first(connector, XDR, "spPr");
        Element line = first(shapeProperties, A, "ln");
        if (line == null) {
            line = connector.getOwnerDocument().createElementNS(A, "a:ln");
            shapeProperties.appendChild(line);
        }
        line.setAttribute("w", "28575");
        Element solidFill = first(line, A, "solidFill");
        if (solidFill == null) {
            solidFill = connector.getOwnerDocument().createElementNS(A, "a:solidFill");
            line.appendChild(solidFill);
        }
        Element color = first(solidFill, A, "srgbClr");
        if (color == null) {
            color = connector.getOwnerDocument().createElementNS(A, "a:srgbClr");
            solidFill.appendChild(color);
        }
        color.setAttribute("val", "1F4E79");
        Element tail = first(line, A, "tailEnd");
        if (tail == null) {
            tail = connector.getOwnerDocument().createElementNS(A, "a:tailEnd");
            line.appendChild(tail);
        }
        tail.setAttribute("type", "triangle");
        tail.setAttribute("w", "med");
        tail.setAttribute("len", "med");
    }

    private void alignConnector(Element connector, java.util.List<WorkbookInventory.DrawingObject> objects,
                                String startId, String endId) {
        var start = objects.stream().filter(o -> startId.equals(o.shapeId())).findFirst().orElseThrow();
        var end = objects.stream().filter(o -> endId.equals(o.shapeId())).findFirst().orElseThrow();
        long startWidth = start.endAnchorXEmu() - start.startAnchorXEmu();
        long startHeight = start.endAnchorYEmu() - start.startAnchorYEmu();
        long endWidth = end.endAnchorXEmu() - end.startAnchorXEmu();
        long endHeight = end.endAnchorYEmu() - end.startAnchorYEmu();
        long sx = start.startAnchorXEmu() + startWidth / 2L;
        long sy = start.startAnchorYEmu() + startHeight / 2L;
        long ex = end.startAnchorXEmu() + endWidth / 2L;
        long ey = end.startAnchorYEmu() + endHeight / 2L;
        Element anchor = (Element) connector.getParentNode();
        Element from = first(anchor, XDR, "from");
        Element to = first(anchor, XDR, "to");
        coordinate(from, "col", start.startColumn()); coordinate(from, "row", start.startRow());
        coordinate(from, "colOff", startWidth / 2L); coordinate(from, "rowOff", startHeight / 2L);
        coordinate(to, "col", end.startColumn()); coordinate(to, "row", end.startRow());
        coordinate(to, "colOff", endWidth / 2L); coordinate(to, "rowOff", endHeight / 2L);
        Element xfrm = first(connector, A, "xfrm");
        Element off = first(xfrm, A, "off"); Element ext = first(xfrm, A, "ext");
        off.setAttribute("x", Long.toString(Math.min(sx, ex))); off.setAttribute("y", Long.toString(Math.min(sy, ey)));
        ext.setAttribute("cx", Long.toString(Math.abs(ex - sx))); ext.setAttribute("cy", Long.toString(Math.abs(ey - sy)));
    }

    private void coordinate(Element parent, String name, long value) {
        Element item = first(parent, XDR, name);
        if (item == null) { item = parent.getOwnerDocument().createElementNS(XDR, "xdr:" + name); parent.appendChild(item); }
        item.setTextContent(Long.toString(value));
    }

    private PipeChangeDrawingEngine.Request request(Fixture fixture, String sha, boolean synthetic,
                                                    String sourceSheet, String targetSheet, String mapping) {
        return new PipeChangeDrawingEngine.Request("synthetic-document", "Synthetic", sha, fixture.connectorId(),
                fixture.startId(), fixture.beforeEndId(), fixture.startId(), fixture.afterEndId(), mapping, synthetic,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", sourceSheet, targetSheet);
    }

    private PipeChangeDrawingEngine.Request withAfterEnd(PipeChangeDrawingEngine.Request request, String id) {
        return new PipeChangeDrawingEngine.Request(request.workingDocumentId(), request.sheetName(), request.expectedWorkingSha256(),
                request.connectorShapeId(), request.beforeStartShapeId(), request.beforeEndShapeId(), request.afterStartShapeId(),
                id, request.mappingEvidence(), request.syntheticFixture(), request.physicalEvidenceStatus(),
                request.sourceSheetName(), request.targetSheetName());
    }

    private Element first(Element parent, String ns, String name) {
        var nodes = parent.getElementsByTagNameNS(ns, name); return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }
    private java.util.List<Element> elements(org.w3c.dom.Document document, String name) {
        var nodes = document.getElementsByTagNameNS(XDR, name); var result = new java.util.ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i)); return result;
    }
    private org.w3c.dom.Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder(); builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
        return builder.parse(new ByteArrayInputStream(xml));
    }
    private byte[] serialize(org.w3c.dom.Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); transformer.transform(new DOMSource(document), new StreamResult(out)); return out.toByteArray();
    }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private record Fixture(byte[] bytes, PipeChangeDrawingEngine.Request request, String startId,
                           String beforeEndId, String afterEndId, String connectorId,
                           WorkbookInventory.DrawingObject beforeConnector,
                           java.util.List<WorkbookInventory.DrawingObject> beforeObjects) { }
}
