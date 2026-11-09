package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DiagramBoxAddEngineTest {
    private static final String PROCESS = "SYNTH-11B";
    private static final String SHEET = "Layout";
    private static final String TEMPLATE = "A1:C3";
    private static final String TARGET = "E1:G3";
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final EquipmentBoxGeometryLocator geometry = new EquipmentBoxGeometryLocator();
    private final DiagramPlacementPreflight preflight = new DiagramPlacementPreflight(inspector, geometry, parts);
    private final DiagramBoxAddEngine engine = new DiagramBoxAddEngine(preflight, inspector, parts);

    @Test
    void createsNoMergeCellLayoutWithoutMovingRowsOrColumnsAndPreservesDistantDrawing() throws Exception {
        Fixture fixture = fixture(false, false, false, true);
        var request = request(fixture, TARGET);
        var check = preflight.analyzeBoxAdd(fixture.bytes(), request);
        assertThat(check.testOnlyReady()).as("preflight: %s", check).isTrue();
        assertThat(check.status()).isEqualTo("CELL_LAYOUT_BOX_ADD_TEST_ONLY");
        var beforeInventory = inspector.inspect(fixture.bytes());
        var result = engine.addCellLayoutTestOnly(fixture.bytes(), request, values("NEW-11A", "New vessel A"));
        Path artifacts = artifactDirectory();
        Path sourceArtifact = artifacts.resolve("cell-layout-no-merge_SOURCE_TEST_ONLY.xlsx");
        Files.write(sourceArtifact, fixture.bytes());
        assertThat(sha256(Files.readAllBytes(sourceArtifact))).isEqualTo(result.sourceSha256());
        Path output = artifacts.resolve("cell-layout-no-merge_TEST_ONLY.xlsx");
        Files.write(output, result.workbookBytes());
        writeJson(artifacts.resolve("cell-layout-no-merge_TEST_ONLY.json"), Map.ofEntries(
                Map.entry("operation", "CELL_LAYOUT_BOX_ADD_TEST_ONLY"), Map.entry("template", TEMPLATE),
                Map.entry("sourceArtifact", sourceArtifact.getFileName().toString()),
                Map.entry("target", TARGET), Map.entry("sourceSha256", result.sourceSha256()),
                Map.entry("outputSha256", result.outputSha256()), Map.entry("changedCells", result.changedCells()),
                Map.entry("status", result.layoutStatus()), Map.entry("relationStatus", result.relationStatus()),
                Map.entry("rowColumnMovement", false), Map.entry("newShapeCreated", false),
                Map.entry("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE")));
        assertZipReadable(Files.readAllBytes(output));
        assertThat(result.changedCells()).hasSize(6);
        assertThat(result.layoutStatus()).isEqualTo("CELL_LAYOUT_BOX_ADD_TEST_ONLY");
        assertThat(result.relationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(inspector.inspect(result.workbookBytes()).shapeCount()).isEqualTo(beforeInventory.shapeCount());
        assertThat(inspector.inspect(result.workbookBytes()).connectorCount()).isEqualTo(beforeInventory.connectorCount());
        assertThat(parts.readPart(result.workbookBytes(), beforeInventory.sheets().get(0).drawingPartName()))
                .containsExactly(parts.readPart(fixture.bytes(), beforeInventory.sheets().get(0).drawingPartName()));
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet(SHEET);
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(0).getCell(4))).isEqualTo("NEW-11A");
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(1).getCell(4))).isEqualTo("New vessel A");
            assertThat(sheet.getLastRowNum()).isEqualTo(9);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(10);
            assertThat(sheet.getRow(0).getHeight()).isEqualTo(fixture.rowHeight());
            assertThat(sheet.getColumnWidth(0)).isEqualTo(sheet.getColumnWidth(4));
            assertThat(sheet.getMergedRegions()).isEmpty();
        }
        var replay = engine.addCellLayoutTestOnly(fixture.bytes(), request, values("NEW-11A", "New vessel A"));
        assertThat(replay.outputSha256()).isEqualTo(result.outputSha256());
        assertThat(sha256(fixture.bytes())).isEqualTo(result.sourceSha256());
    }

    @Test
    void clonesMergedBorderedCellLayoutAndConnectorDrawingPartsByteForByte() throws Exception {
        Fixture fixture = fixture(true, false, false, true, false, false, false, true);
        var request = request(fixture, TARGET, "NEW-11B", "New vessel B");
        var check = preflight.analyzeBoxAdd(fixture.bytes(), request);
        assertThat(check.testOnlyReady()).as("preflight: %s", check).isTrue();
        var beforeInventory = inspector.inspect(fixture.bytes());
        var result = engine.addCellLayoutTestOnly(fixture.bytes(), request, values("NEW-11B", "New vessel B"));
        Path artifacts = artifactDirectory();
        Path sourceArtifact = artifacts.resolve("cell-layout-merged-border_SOURCE_TEST_ONLY.xlsx");
        Files.write(sourceArtifact, fixture.bytes());
        assertThat(sha256(Files.readAllBytes(sourceArtifact))).isEqualTo(result.sourceSha256());
        Path output = artifacts.resolve("cell-layout-merged-border_TEST_ONLY.xlsx");
        Files.write(output, result.workbookBytes());
        writeJson(artifacts.resolve("cell-layout-merged-border_TEST_ONLY.json"), Map.ofEntries(
                Map.entry("operation", "CELL_LAYOUT_BOX_ADD_TEST_ONLY"), Map.entry("template", TEMPLATE),
                Map.entry("sourceArtifact", sourceArtifact.getFileName().toString()),
                Map.entry("target", TARGET), Map.entry("sourceSha256", result.sourceSha256()),
                Map.entry("outputSha256", result.outputSha256()), Map.entry("changedCells", result.changedCells()),
                Map.entry("sourceMerges", List.of("C2:C3")), Map.entry("createdMerges", List.of("G2:G3")),
                Map.entry("status", result.layoutStatus()), Map.entry("relationStatus", result.relationStatus()),
                Map.entry("rowColumnMovement", false), Map.entry("newShapeCreated", false),
                Map.entry("connectorPolicy", "PRESERVE_ONLY_NO_CONNECTOR_CREATED_OR_REWRITTEN")));
        assertZipReadable(Files.readAllBytes(output));
        var afterInventory = inspector.inspect(result.workbookBytes());
        assertThat(afterInventory.shapeCount()).isEqualTo(beforeInventory.shapeCount());
        assertThat(afterInventory.connectorCount()).isEqualTo(beforeInventory.connectorCount());
        assertThat(beforeInventory.connectorCount()).isGreaterThan(0);
        assertThat(afterInventory.sheets().get(0).drawingObjects()).isEqualTo(beforeInventory.sheets().get(0).drawingObjects());
        String drawingPart = beforeInventory.sheets().get(0).drawingPartName();
        assertThat(parts.readPart(result.workbookBytes(), drawingPart)).containsExactly(parts.readPart(fixture.bytes(), drawingPart));
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet(SHEET);
            assertThat(sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList())
                    .containsExactly("C2:C3", "G2:G3");
            assertThat(sheet.getRow(0).getCell(4).getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(0).getCell(4).getCellStyle().getIndex())
                    .isEqualTo(sheet.getRow(0).getCell(0).getCellStyle().getIndex());
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(0).getCell(4))).isEqualTo("NEW-11B");
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(2).getCell(4))).isEqualTo("2,500 kg/h");
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(2).getCell(5))).isEqualTo("1");
            assertThat(sheet.getLastRowNum()).isEqualTo(9);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(10);
        }
    }

    @Test
    void failsClosedForOccupiedDuplicateMergeDrawingConnectorAndStaleReplayRequests() throws Exception {
        Fixture occupied = fixture(false, true, false, false);
        assertThat(preflight.analyzeBoxAdd(occupied.bytes(), request(occupied, TARGET)).blockers())
                .anyMatch(reason -> reason.startsWith("TARGET_CELLS_OCCUPIED"));

        Fixture duplicate = fixture(false, false, true, false);
        assertThat(preflight.analyzeBoxAdd(duplicate.bytes(), request(duplicate, TARGET)).blockers())
                .anyMatch(reason -> reason.startsWith("DUPLICATE_EQUIPMENT_ID"));

        Fixture targetMerge = fixture(false, false, false, false, true);
        assertThat(preflight.analyzeBoxAdd(targetMerge.bytes(), request(targetMerge, TARGET)).blockers())
                .anyMatch(reason -> reason.startsWith("MERGED_RANGE_COLLISION"));

        Fixture shapeCollision = fixture(false, false, false, false, false, false, true);
        var shapeResult = preflight.analyzeBoxAdd(shapeCollision.bytes(), request(shapeCollision, TARGET));
        assertThat(shapeResult.blockers()).anyMatch(reason -> reason.startsWith("DRAWINGML_ANCHOR_COLLISION"));
        assertThat(shapeResult.drawingConflicts()).isNotEmpty();

        Fixture collision = fixture(false, false, false, false, false, true);
        var collisionResult = preflight.analyzeBoxAdd(collision.bytes(), request(collision, TARGET));
        assertThat(collisionResult.blockers()).anyMatch(reason -> reason.startsWith("DRAWINGML_ANCHOR_COLLISION"));
        assertThat(collisionResult.connectorCrossings()).isNotEmpty();

        Fixture shapeRequired = fixture(false, false, false, false);
        var shapeRequiredRequest = request(shapeRequired, TARGET);
        var shapeRequiredProfile = profileWithPolicies(shapeRequired, "SHAPE_REQUIRED", "PRESERVE_ONLY");
        assertThat(preflight.analyzeBoxAdd(shapeRequired.bytes(), new DiagramPlacementPreflight.BoxAddRequest(
                PROCESS, SHEET, TEMPLATE, TARGET, "NEW-11A", "New vessel A", shapeRequiredRequest.sourceSha256(),
                shapeRequiredRequest.mapping(), shapeRequiredProfile)).blockers()).contains("SHAPE_REQUIRED_BLOCKED");

        Fixture connectorRequired = fixture(false, false, false, false);
        var connectorRequiredRequest = request(connectorRequired, TARGET);
        var connectorRequiredProfile = profileWithPolicies(connectorRequired, "PRESERVE_ONLY", "CONNECTOR_CREATE_REQUIRED");
        assertThat(preflight.analyzeBoxAdd(connectorRequired.bytes(), new DiagramPlacementPreflight.BoxAddRequest(
                PROCESS, SHEET, TEMPLATE, TARGET, "NEW-11A", "New vessel A", connectorRequiredRequest.sourceSha256(),
                connectorRequiredRequest.mapping(), connectorRequiredProfile)).blockers()).contains("CONNECTOR_EDIT_REQUIRED_BLOCKED");

        Fixture stale = fixture(false, false, false, false);
        var request = request(stale, TARGET);
        Fixture changed = fixture(false, true, false, false);
        var staleResult = preflight.analyzeBoxAdd(changed.bytes(), request);
        assertThat(staleResult.blockers()).contains("SOURCE_SHA_MISMATCH");
        var first = engine.addCellLayoutTestOnly(stale.bytes(), request, values("NEW-11A", "New vessel A"));
        assertThatThrownBy(() -> engine.addCellLayoutTestOnly(first.workbookBytes(), request, values("NEW-11A", "New vessel A")))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("BOX_ADD_NOT_TEST_ONLY_READY:BLOCKED");
        assertThatThrownBy(() -> engine.addCellLayoutTestOnly(stale.bytes(), request, values("OTHER", "New vessel A")))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("EQUIPMENT_ID_VALUE_MISMATCH");
    }

    private DiagramPlacementPreflight.BoxAddRequest request(Fixture fixture, String target) {
        return request(fixture, target, "NEW-11A", "New vessel A");
    }

    private DiagramPlacementPreflight.BoxAddRequest request(Fixture fixture, String target, String equipmentId, String equipmentName) {
        String sha = sha256(fixture.bytes());
        var mapping = new DiagramLayoutMapping("synthetic-11b#Layout", sha, PROCESS, SHEET, "SYN-11-BASE",
                target, target.substring(0, target.indexOf(':')).replace("E", "E"), Map.of(),
                List.of(), Map.of(), Map.of(), List.of(), List.of(), DiagramLayoutMapping.PlacementStatus.VERIFIED,
                DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED);
        return new DiagramPlacementPreflight.BoxAddRequest(PROCESS, SHEET, TEMPLATE, target,
                equipmentId, equipmentName, sha, mapping, profile(fixture));
    }

    private EquipmentBoxProfile profile(Fixture fixture) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", field(fixture, 0, 0));
        fields.put("equipmentTypeLabel", field(fixture, 0, 1));
        fields.put("equipmentName", field(fixture, 1, 0));
        fields.put("businessItemNo", field(fixture, 1, 1));
        fields.put("capacity", field(fixture, 2, 0));
        fields.put("quantity", field(fixture, 2, 1));
        List<String> merges = fixture.merged() ? List.of("C2:C3") : List.of();
        Map<Integer, Float> heights = Map.of(0, (float) fixture.rowHeight() / 20f, 1, (float) fixture.rowHeight() / 20f,
                2, (float) fixture.rowHeight() / 20f);
        Map<String, Double> widths = Map.of("A", 12d, "B", 12d, "C", 12d);
        String sha = sha256(fixture.bytes());
        return new EquipmentBoxProfile("SYNTH-11B-" + (fixture.merged() ? "MERGE" : "PLAIN"), PROCESS,
                SHEET, "synthetic-test-only.xlsx", sha, sha, "1", "VERIFIED", "SYNTHETIC_TEST_ONLY",
                "Synthetic layout fixture; no production authority", TEMPLATE, 3, fields, merges, Map.of(),
                "Arial", (short) 10, "DEFAULT_BLACK", heights, widths, "PRESERVE_ONLY",
                "PRESERVE_ONLY_NEVER_CREATE", "GENERIC_TEST_ONLY", "'Layout'!$A$1:$J$10", "PRESERVE", null);
    }

    private EquipmentBoxProfile profileWithPolicies(Fixture fixture, String drawingPolicy, String connectorPolicy) {
        EquipmentBoxProfile base = profile(fixture);
        return new EquipmentBoxProfile(base.profileId(), base.processCode(), base.sheetName(), base.sourceFilename(),
                base.sourceSha256(), base.verifiedWorkingSha256(), base.profileVersion(), base.status(), base.evidenceLevel(),
                base.evidence(), base.templateRange(), base.boxHeight(), base.fields(), base.mergedRanges(),
                base.expectedStyleIds(), base.fontName(), base.fontSizePt(), base.fontColor(), base.rowHeightsPt(),
                base.columnWidths(), drawingPolicy, connectorPolicy, base.insertionPolicy(), base.printArea(),
                base.pageBreakPolicy(), base.blocker(), base.deletePolicy());
    }

    private EquipmentBoxProfile.FieldCell field(Fixture fixture, int row, int col) {
        return new EquipmentBoxProfile.FieldCell(row, col, fixture.styleId(), null, null, null, true, "TEXT");
    }

    private Fixture fixture(boolean merged, boolean occupied, boolean duplicate, boolean shape) throws Exception {
        return fixture(merged, occupied, duplicate, shape, false, false);
    }

    private Fixture fixture(boolean merged, boolean occupied, boolean duplicate, boolean shape,
                            boolean targetMerge) throws Exception {
        return fixture(merged, occupied, duplicate, shape, targetMerge, false);
    }

    private Fixture fixture(boolean merged, boolean occupied, boolean duplicate, boolean shape,
                            boolean targetMerge, boolean connectorCollision) throws Exception {
        return fixture(merged, occupied, duplicate, shape, targetMerge, connectorCollision, false);
    }

    private Fixture fixture(boolean merged, boolean occupied, boolean duplicate, boolean shape,
                            boolean targetMerge, boolean connectorCollision, boolean shapeCollision) throws Exception {
        return fixture(merged, occupied, duplicate, shape, targetMerge, connectorCollision, shapeCollision, false);
    }

    private Fixture fixture(boolean merged, boolean occupied, boolean duplicate, boolean shape,
                            boolean targetMerge, boolean connectorCollision, boolean shapeCollision,
                            boolean distantConnector) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET);
            CellStyle box = workbook.createCellStyle();
            box.setBorderTop(BorderStyle.THIN);
            box.setBorderBottom(BorderStyle.THIN);
            box.setBorderLeft(BorderStyle.THIN);
            box.setBorderRight(BorderStyle.THIN);
            for (int rowIndex = 0; rowIndex < 10; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                row.setHeight((short) (rowIndex < 3 ? 360 : 300));
                for (int column = 0; column < 10; column++) {
                    var cell = row.createCell(column);
                    if (rowIndex < 3 && column < 3) cell.setCellStyle(box);
                    if (rowIndex < 3 && column >= 4 && column < 7) {
                        cell.setCellStyle(box);
                        cell.setCellValue(" ");
                    }
                }
            }
            for (int column = 0; column < 10; column++) sheet.setColumnWidth(column, 12 * 256);
            sheet.getRow(0).getCell(0).setCellValue("SYN-11-BASE");
            sheet.getRow(0).getCell(1).setCellValue("Vessel");
            sheet.getRow(1).getCell(0).setCellValue("Base tank");
            sheet.getRow(1).getCell(1).setCellValue("ITM-BASE");
            sheet.getRow(2).getCell(0).setCellValue("1,250 kg/h");
            sheet.getRow(2).getCell(1).setCellValue("1");
            if (occupied) sheet.getRow(0).getCell(4).setCellValue("OCCUPIED");
            if (duplicate) sheet.getRow(8).getCell(0).setCellValue("NEW-11A");
            if (merged) sheet.addMergedRegion(new CellRangeAddress(1, 2, 2, 2));
            if (targetMerge) sheet.addMergedRegion(new CellRangeAddress(1, 2, 6, 6));
            if (shape || connectorCollision || shapeCollision || distantConnector) {
                var drawing = sheet.createDrawingPatriarch();
                boolean intersectsTarget = connectorCollision || shapeCollision;
                var anchor = drawing.createAnchor(0, 0, 0, 0,
                        intersectsTarget ? 4 : 3, intersectsTarget ? 0 : 3,
                        intersectsTarget ? 7 : 4, intersectsTarget ? 3 : 4);
                if (connectorCollision) drawing.createConnector((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
                else drawing.createTextbox((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
                if (distantConnector) {
                    var connectorAnchor = drawing.createAnchor(0, 0, 0, 0, 0, 3, 1, 4);
                    drawing.createConnector((org.apache.poi.xssf.usermodel.XSSFClientAnchor) connectorAnchor);
                }
            }
            workbook.setPrintArea(0, "$A$1:$J$10");
            workbook.write(output);
            return new Fixture(output.toByteArray(), box.getIndex(), (short) 360, merged);
        }
    }

    private Map<String, String> values(String equipmentId, String equipmentName) {
        return Map.of("managementNo", equipmentId, "equipmentTypeLabel", "Vessel", "equipmentName", equipmentName,
                "businessItemNo", "ITM-NEW", "capacity", "2,500 kg/h", "quantity", "1");
    }

    private Path artifactDirectory() throws Exception {
        String run = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of("validation-results", "pilot-11b-20261108", run);
        Files.createDirectories(directory);
        return directory;
    }

    private void writeJson(Path path, Map<String, ?> contents) throws Exception {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), contents);
    }

    private void assertZipReadable(byte[] bytes) throws Exception {
        int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                zip.transferTo(java.io.OutputStream.nullOutputStream());
                entries++;
            }
        }
        assertThat(entries).isGreaterThan(0);
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Fixture(byte[] bytes, int styleId, short rowHeight, boolean merged) { }
}
