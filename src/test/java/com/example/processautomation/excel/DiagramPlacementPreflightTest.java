package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DiagramPlacementPreflightTest {
    private static final String PROCESS = "SYNTH-P";
    private static final String SHEET = "Layout";
    private static final String RANGE = "J2:K3";
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final EquipmentBoxGeometryLocator geometry = new EquipmentBoxGeometryLocator();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final DiagramPlacementPreflight preflight = new DiagramPlacementPreflight(inspector, geometry, parts);

    @Test
    void validatesMultipleProcessSheetMappingsAndMakesLocationEvidenceNonAuthorizing() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false);
        var result = preflight.analyze(fixture.bytes(), request(fixture, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(fixture), goodProfile(fixture)));
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.testOnlyReady()).isTrue();
        assertThat(result.scope()).contains("physical connection");

        var otherProcess = preflight.analyze(fixture.bytes(), request(fixture, "SYNTH-Q", SHEET, RANGE, "NEW-01", goodMapping(fixture), goodProfile(fixture)));
        assertThat(otherProcess.status()).isEqualTo("BLOCKED");
        assertThat(otherProcess.blockers()).contains("LOCATION_IDENTITY_MISMATCH");
    }

    @Test
    void blocksMissingSheetOutOfBoundsAndMalformedRange() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false);
        assertThat(preflight.analyze(fixture.bytes(), request(fixture, PROCESS, "missing", RANGE, "NEW-01", goodMapping(fixture), goodProfile(fixture)))
                .blockers()).contains("TARGET_SHEET_MISSING");
        assertThat(preflight.analyze(fixture.bytes(), request(fixture, PROCESS, SHEET, "XFE1:XFF2", "NEW-01", goodMapping(fixture), goodProfile(fixture)))
                .blockers()).contains("TARGET_RANGE_OUT_OF_BOUNDS");
        assertThat(preflight.analyze(fixture.bytes(), request(fixture, PROCESS, SHEET, "bad", "NEW-01", goodMapping(fixture), goodProfile(fixture)))
                .blockers()).contains("TARGET_RANGE_INVALID");
    }

    @Test
    void blocksMergesOccupiedCellsDuplicateIdsAndExistingBoxOverlap() throws Exception {
        Fixture merged = fixture(true, false, false, false, false);
        assertThat(preflight.analyze(merged.bytes(), request(merged, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(merged), goodProfile(merged)))
                .blockers()).anyMatch(item -> item.startsWith("MERGED_RANGE_COLLISION"));

        Fixture occupied = fixture(false, true, false, false, false);
        assertThat(preflight.analyze(occupied.bytes(), request(occupied, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(occupied), goodProfile(occupied)))
                .blockers()).anyMatch(item -> item.startsWith("TARGET_CELLS_OCCUPIED"));

        Fixture duplicate = fixture(false, false, true, false, false);
        assertThat(preflight.analyze(duplicate.bytes(), request(duplicate, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(duplicate), goodProfile(duplicate)))
                .blockers()).anyMatch(item -> item.startsWith("DUPLICATE_EQUIPMENT_ID"));

        Fixture overlapping = fixture(false, false, false, false, true);
        assertThat(preflight.analyze(overlapping.bytes(), request(overlapping, PROCESS, SHEET, "A1:H8", "NEW-01",
                mapping(overlapping, "A1:H8", DiagramLayoutMapping.PlacementStatus.VERIFIED,
                        DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED), profile(overlapping, "A1:H8")))
                .blockers()).anyMatch(item -> item.startsWith("EXISTING_EQUIPMENT_BOX_COLLISION"));
    }

    @Test
    void blocksDrawingShapeAndConnectorIntersectionsAndUnknownLocationEvidence() throws Exception {
        Fixture shape = fixture(false, false, false, true, false);
        var shapeResult = preflight.analyze(shape.bytes(), request(shape, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(shape), goodProfile(shape)));
        assertThat(shapeResult.blockers()).anyMatch(item -> item.startsWith("DRAWINGML_ANCHOR_COLLISION"));
        assertThat(shapeResult.drawingConflicts()).isNotEmpty();

        Fixture connector = fixture(false, false, false, false, false, true);
        var connectorResult = preflight.analyze(connector.bytes(), request(connector, PROCESS, SHEET, RANGE,
                "NEW-01", goodMapping(connector), goodProfile(connector)));
        assertThat(connectorResult.blockers()).anyMatch(item -> item.startsWith("DRAWINGML_ANCHOR_COLLISION"));
        assertThat(connectorResult.connectorCrossings()).isNotEmpty();

        Fixture clean = fixture(false, false, false, false, false);
        var unknown = preflight.analyze(clean.bytes(), request(clean, PROCESS, SHEET, RANGE, "NEW-01",
                mapping(clean, RANGE, DiagramLayoutMapping.PlacementStatus.UNKNOWN, DiagramLayoutMapping.EvidenceStatus.UNKNOWN), goodProfile(clean)));
        assertThat(unknown.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(unknown.testOnlyReady()).isFalse();
    }

    @Test
    void performsReservedRangeTestOnlyEditAndPreservesPackageDrawingMergesDimensions() throws Exception {
        Fixture fixture = withDistantShape(fixture(false, false, false, false, false));
        String sourceSha = sha256(fixture.bytes());
        var beforeInventory = inspector.inspect(fixture.bytes());
        var oldMerge = fixture.mergeList();
        var request = request(fixture, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(fixture), goodProfile(fixture));
        var edited = preflight.writeReservedRangeValuesTestOnly(fixture.bytes(), request,
                Map.of("managementNo", "NEW-01", "equipmentName", "New Tank"));
        Path artifacts = artifactDirectory();
        Path xlsx = artifacts.resolve("reserved-range-value-entry_TEST_ONLY.xlsx");
        Files.write(xlsx, edited.workbookBytes());
        byte[] persistedOutput = Files.readAllBytes(xlsx);
        assertThat(sha256(persistedOutput)).isEqualTo(edited.outputSha256());
        writeMetadata(artifacts.resolve("reserved-range-value-entry_TEST_ONLY.json"), Map.of(
                "operation", "RESERVED_RANGE_VALUE_ENTRY_ONLY", "sourceSha256", edited.sourceSha256(),
                "outputSha256", edited.outputSha256(), "changedCells", edited.changedCells(),
                "mode", edited.mode(), "relationStatus", edited.relationStatus(), "newShapeCreated", false,
                "physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"));
        assertZipReadable(persistedOutput);
        assertThat(edited.mode()).isEqualTo("TEST_ONLY");
        assertThat(edited.relationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(edited.sourceSha256()).isEqualTo(sourceSha);
        assertThat(sha256(fixture.bytes())).isEqualTo(sourceSha);
        assertThat(edited.changedCells()).containsKeys("J2", "J3");
        var afterInventory = inspector.inspect(persistedOutput);
        assertThat(afterInventory.shapeCount()).isEqualTo(beforeInventory.shapeCount());
        assertThat(afterInventory.connectorCount()).isEqualTo(beforeInventory.connectorCount());
        assertThat(inspector.inspectSheet(persistedOutput, SHEET).drawingObjects()).isEqualTo(beforeInventory.sheets().get(0).drawingObjects());
        String drawingPart = beforeInventory.sheets().get(0).drawingPartName();
        assertThat(parts.readPart(persistedOutput, drawingPart)).containsExactly(parts.readPart(fixture.bytes(), drawingPart));
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(edited.workbookBytes()))) {
            var sheet = workbook.getSheet(SHEET);
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(1).getCell(9))).isEqualTo("NEW-01");
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(2).getCell(9))).isEqualTo("New Tank");
            assertThat(sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString)).containsExactlyElementsOf(oldMerge);
            assertThat(sheet.getRow(1).getHeight()).isEqualTo((short) 420);
            assertThat(sheet.getColumnWidth(9)).isEqualTo(12 * 256);
        }

        // This editor is pure and does not persist replay state: identical baseline bytes yield the same TEST_ONLY result.
        var replay = preflight.writeReservedRangeValuesTestOnly(fixture.bytes(), request,
                Map.of("managementNo", "NEW-01", "equipmentName", "New Tank"));
        assertThat(replay.outputSha256()).isEqualTo(edited.outputSha256());
        assertThat(replay.changedCells()).isEqualTo(edited.changedCells());

        // A changed working copy cannot be replayed under the prior source-SHA-bound request/Profile.
        assertThat(preflight.analyze(edited.workbookBytes(), request).blockers()).contains("SOURCE_SHA_MISMATCH");
        assertThatThrownBy(() -> preflight.writeReservedRangeValuesTestOnly(edited.workbookBytes(), request,
                Map.of("managementNo", "NEW-01", "equipmentName", "New Tank")))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PLACEMENT_NOT_TEST_ONLY_READY:BLOCKED");
    }

    @Test
    void supportsAnExistingStyledButSelfClosingBlankOoxmlCellWithoutWideningEditScope() throws Exception {
        Fixture fixture = styleOnlyFixture();
        var worksheetPart = inspector.inspectSheet(fixture.bytes(), SHEET).worksheetPartName();
        String beforeXml = new String(parts.readPart(fixture.bytes(), worksheetPart), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(java.util.regex.Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\\\"J2\\\")[^>]*/\\s*>")
                .matcher(beforeXml).find()).isTrue();
        try (var original = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(fixture.bytes()))) {
            assertThat(new DataFormatter().formatCellValue(original.getSheet(SHEET).getRow(2).getCell(0)))
                    .isEqualTo("Synthetic Unit");
        }
        byte[] directEdit = parts.replaceCellText(fixture.bytes(), SHEET, "J2", "", "DIRECT-TEST");
        try (var direct = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(directEdit))) {
            assertThat(new DataFormatter().formatCellValue(direct.getSheet(SHEET).getRow(2).getCell(0)))
                    .isEqualTo("Synthetic Unit");
        }

        var request = request(fixture, PROCESS, SHEET, RANGE, "NEW-SELF-CLOSING",
                goodMapping(fixture), goodProfile(fixture));
        var result = preflight.writeReservedRangeValuesTestOnly(fixture.bytes(), request,
                Map.of("managementNo", "NEW-SELF-CLOSING"));
        Path output = artifactDirectory().resolve("self-closing-blank-cell_TEST_ONLY.xlsx");
        Files.write(output, result.workbookBytes());
        writeMetadata(output.resolveSibling("self-closing-blank-cell_TEST_ONLY.json"), Map.of(
                "operation", "RESERVED_RANGE_VALUE_ENTRY_ONLY", "sourceSha256", result.sourceSha256(),
                "outputSha256", result.outputSha256(), "changedCells", result.changedCells(),
                "mode", result.mode(), "newShapeCreated", false));
        byte[] persisted = Files.readAllBytes(output);
        assertZipReadable(persisted);
        assertThat(sha256(fixture.bytes())).isEqualTo(result.sourceSha256());
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(persisted))) {
            assertThat(new DataFormatter().formatCellValue(workbook.getSheet(SHEET).getRow(1).getCell(9)))
                    .isEqualTo("NEW-SELF-CLOSING");
        }
    }

    @Test
    void routesExplicitVerifiedMappingModifyThroughExistingProfileGuards() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false);
        var changeEngine = new DiagramBoxChangeEngine(parts, inspector, geometry);
        var profile = modifyProfile(fixture);
        var mapping = new DiagramLayoutMapping("fixture#Layout", sha256(fixture.bytes()), PROCESS, SHEET,
                "SYN-01", "A1:F8", "A1", Map.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(),
                DiagramLayoutMapping.PlacementStatus.VERIFIED, DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED);
        var modified = preflight.modifyMappedFieldsTestOnly(fixture.bytes(), mapping, profile,
                Map.of("equipmentName", "Synthetic Unit"), Map.of("equipmentName", "Revised Tank"), changeEngine);
        assertThat(modified.change().scenarioStatus()).isEqualTo("TEST_ONLY");
        assertThat(modified.change().affectedCells()).containsExactly("A3");
        Path xlsx = artifactDirectory().resolve("profile-modify_TEST_ONLY.xlsx");
        Files.write(xlsx, modified.change().workbookBytes());
        writeMetadata(xlsx.resolveSibling("profile-modify_TEST_ONLY.json"), Map.of(
                "operation", "PROFILE_MODIFY_TEST_ONLY", "sourceSha256", modified.change().sourceSha256(),
                "outputSha256", modified.change().outputSha256(), "changedCells", modified.change().affectedCells(),
                "mode", modified.change().scenarioStatus(), "relationStatus", "REVIEW_REQUIRED",
                "physicalConnectionEvidence", modified.change().connectionEvidenceStatus()));
        byte[] persistedModify = Files.readAllBytes(xlsx);
        assertZipReadable(persistedModify);
        try (var reopened = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(persistedModify))) {
            assertThat(new DataFormatter().formatCellValue(reopened.getSheet(SHEET).getRow(2).getCell(0)))
                    .isEqualTo("Revised Tank");
        }
        assertThat(inspector.inspectSheet(persistedModify, SHEET).drawingObjects())
                .isEqualTo(inspector.inspectSheet(fixture.bytes(), SHEET).drawingObjects());
        assertThatThrownBy(() -> preflight.modifyMappedFieldsTestOnly(fixture.bytes(), mapping, profile,
                Map.of("equipmentName", "Previous stale text"), Map.of("equipmentName", "Blocked"), changeEngine))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("STALE_VALUE");
        assertThatThrownBy(() -> preflight.modifyMappedFieldsTestOnly(fixture.bytes(),
                new DiagramLayoutMapping("fixture#Layout", sha256(fixture.bytes()), PROCESS, SHEET,
                        "SYN-01", "A1:F8", "A1", Map.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(),
                        DiagramLayoutMapping.PlacementStatus.REVIEW_REQUIRED, DiagramLayoutMapping.EvidenceStatus.STRUCTURAL_CANDIDATE),
                profile, Map.of("equipmentName", "Synthetic Unit"), Map.of("equipmentName", "Blocked"), changeEngine))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("LOCATION_NOT_VERIFIED_FOR_MODIFY");
        assertThatThrownBy(() -> preflight.modifyMappedFieldsTestOnly(fixture.bytes(),
                new DiagramLayoutMapping("fixture#Layout", sha256(fixture.bytes()), PROCESS, SHEET,
                        "SYN-01", "A1:F8", "A2", Map.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(),
                        DiagramLayoutMapping.PlacementStatus.VERIFIED, DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED),
                profile, Map.of("equipmentName", "Synthetic Unit"), Map.of("equipmentName", "Blocked"), changeEngine))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("MAPPING_IDENTIFIER_CELL_MISMATCH");
    }

    @Test
    void blocksUnverifiedTemplateAndSourceShaMismatches() throws Exception {
        Fixture fixture = fixture(false, false, false, false, false);
        var invalid = request(fixture, PROCESS, SHEET, RANGE, "NEW-01", goodMapping(fixture), profile(fixture, RANGE));
        var result = preflight.analyze(fixture.bytes(), invalid);
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).contains("TEMPLATE_PROFILE_NOT_ENABLED");
        var stale = new DiagramPlacementPreflight.Request(PROCESS, SHEET, RANGE, "NEW-01", "New Tank", 2, 2,
                "0".repeat(64), goodMapping(fixture), goodProfile(fixture));
        assertThat(preflight.analyze(fixture.bytes(), stale).blockers()).contains("SOURCE_SHA_MISMATCH");
    }

    private DiagramPlacementPreflight.Request request(Fixture fixture, String process, String sheet, String range,
            String equipmentId, DiagramLayoutMapping mapping, EquipmentBoxProfile profile) {
        return new DiagramPlacementPreflight.Request(process, sheet, range, equipmentId, "New Tank", 2, 2,
                sha256(fixture.bytes()), mapping, profile);
    }

    private DiagramLayoutMapping goodMapping(Fixture fixture) { return mapping(fixture, RANGE,
            DiagramLayoutMapping.PlacementStatus.VERIFIED, DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED); }

    private DiagramLayoutMapping mapping(Fixture fixture, String range, DiagramLayoutMapping.PlacementStatus status,
                                         DiagramLayoutMapping.EvidenceStatus evidence) {
        return new DiagramLayoutMapping("SYNTH-P#Layout", sha256(fixture.bytes()), PROCESS, SHEET, "SYNTH-01", range,
                "A1", Map.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(), status, evidence);
    }

    private EquipmentBoxProfile goodProfile(Fixture fixture) {
        return new EquipmentBoxProfile("SYNTH-VERIFIED", PROCESS, SHEET, "fixture.xlsx", sha256(fixture.bytes()),
                sha256(fixture.bytes()), "1", "VERIFIED", "SYNTHETIC_TESTED", "explicit synthetic fixture", RANGE,
                2, profileFields(fixture), List.of(), Map.of(), "Arial", (short) 10, null, Map.of(), Map.of(),
                "PRESERVE", "NO_CONNECTOR_EDIT", "GENERIC_TEST_ONLY", "'Layout'!$A$1:$K$20", "PRESERVE", null);
    }

    private EquipmentBoxProfile modifyProfile(Fixture fixture) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = Map.of(
                "managementNo", new EquipmentBoxProfile.FieldCell(0, 0, null, null, null, null, true, "TEXT"),
                "equipmentName", new EquipmentBoxProfile.FieldCell(2, 0, null, null, null, null, true, "TEXT"));
        return new EquipmentBoxProfile("SYNTH-MODIFY", PROCESS, SHEET, "fixture.xlsx", sha256(fixture.bytes()),
                sha256(fixture.bytes()), "1", "VERIFIED", "SYNTHETIC_TESTED", "fixture only", "A1:F8", 8,
                fields, List.of(), Map.of(), "Arial", (short) 10, null, Map.of(), Map.of(), "PRESERVE",
                "PRESERVE", "NO_GENERIC_ADD", "'Layout'!$A$1:$K$20", "PRESERVE", null);
    }

    private EquipmentBoxProfile profile(Fixture fixture, String range) {
        return new EquipmentBoxProfile("SYNTH-REVIEW", PROCESS, SHEET, "fixture.xlsx", sha256(fixture.bytes()),
                sha256(fixture.bytes()), "1", "REVIEW_REQUIRED", "STRUCTURE_ANALYZED", "not approved", range,
                2, profileFields(fixture), List.of(), Map.of(), "Arial", (short) 10, null, Map.of(), Map.of(),
                "PRESERVE", "NO_CONNECTOR_EDIT", "GENERIC_TEST_ONLY", "'Layout'!$A$1:$K$20", "PRESERVE", null);
    }

    private Map<String, EquipmentBoxProfile.FieldCell> profileFields(Fixture fixture) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", new EquipmentBoxProfile.FieldCell(0, 0, fixture.styleId(), null, null, null, true, "TEXT"));
        fields.put("equipmentName", new EquipmentBoxProfile.FieldCell(1, 0, fixture.styleId(), null, null, null, true, "TEXT"));
        return fields;
    }

    private Fixture styleOnlyFixture() throws Exception {
        Fixture source = fixture(false, false, false, false, false);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(source.bytes())); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet(SHEET);
            for (int row = 1; row <= 2; row++) for (int column = 9; column <= 10; column++)
                sheet.getRow(row).getCell(column).setBlank();
            workbook.write(output);
            return new Fixture(output.toByteArray(), source.styleId());
        }
    }

    private Fixture withDistantShape(Fixture fixture) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(fixture.bytes())); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var drawing = workbook.getSheet(SHEET).createDrawingPatriarch();
            var anchor = drawing.createAnchor(0, 0, 0, 0, 4, 4, 6, 6);
            drawing.createTextbox(anchor).setText(new org.apache.poi.xssf.usermodel.XSSFRichTextString("distant synthetic shape"));
            var connectorAnchor = drawing.createAnchor(0, 0, 0, 0, 3, 4, 7, 4);
            drawing.createConnector((org.apache.poi.xssf.usermodel.XSSFClientAnchor) connectorAnchor);
            workbook.write(output);
            return new Fixture(output.toByteArray(), fixture.styleId());
        }
    }

    private Fixture fixture(boolean merge, boolean occupied, boolean duplicate, boolean shape, boolean overlap) throws Exception {
        return fixture(merge, occupied, duplicate, shape, overlap, false);
    }

    private Fixture fixture(boolean merge, boolean occupied, boolean duplicate, boolean shape, boolean overlap,
                            boolean connector) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET);
            CellStyle style = workbook.createCellStyle();
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            CellStyle left = workbook.createCellStyle(); left.setBorderLeft(BorderStyle.THIN);
            CellStyle right = workbook.createCellStyle(); right.setBorderRight(BorderStyle.THIN);
            CellStyle bottom = workbook.createCellStyle(); bottom.setBorderBottom(BorderStyle.THIN);
            CellStyle corner = workbook.createCellStyle(); corner.setBorderRight(BorderStyle.THIN); corner.setBorderBottom(BorderStyle.THIN);
            for (int row = 0; row < 8; row++) {
                var line = sheet.createRow(row);
                for (int col = 0; col < 8; col++) line.createCell(col);
                for (int col = 0; col <= 5; col++) {
                    CellStyle border = row == 7 ? (col == 5 ? corner : bottom)
                            : (col == 5 ? right : left);
                    line.getCell(col).setCellStyle(border);
                }
            }
            sheet.getRow(0).getCell(0).setCellValue("SYN-01");
            sheet.getRow(2).getCell(0).setCellValue("Synthetic Unit");
            sheet.setColumnWidth(9, 12 * 256);
            sheet.getRow(1).setHeight((short) 420);
            sheet.getRow(2).setHeight((short) 420);
            for (int row = 1; row <= 2; row++) for (int col = 9; col <= 10; col++) {
                var reservedCell = sheet.getRow(row).createCell(col);
                reservedCell.setCellStyle(style);
                reservedCell.setCellValue(" ");
            }
            if (occupied) sheet.getRow(1).getCell(9).setCellValue("OCCUPIED");
            if (duplicate) sheet.getRow(6).getCell(1).setCellValue("NEW-01");
            if (merge) sheet.addMergedRegion(new CellRangeAddress(1, 1, 9, 10));
            if (shape || connector) {
                var drawing = sheet.createDrawingPatriarch();
                var anchor = drawing.createAnchor(0, 0, 0, 0, 9, 1, 11, 3);
                if (connector) drawing.createConnector((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
                else drawing.createTextbox((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
            }
            workbook.setPrintArea(0, "$A$1:$K$20");
            workbook.write(output);
            return new Fixture(output.toByteArray(), style.getIndex());
        }
    }

    private Path artifactDirectory() throws Exception {
        String run = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of("validation-results", "pilot-11a-20261108", run);
        Files.createDirectories(directory);
        return directory;
    }

    private void writeMetadata(Path path, Map<String, ?> data) throws Exception {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data);
    }

    private void assertZipReadable(byte[] bytes) throws Exception {
        int entries = 0;
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (input.getNextEntry() != null) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
                entries++;
            }
        }
        assertThat(entries).isGreaterThan(0);
        try (var reopened = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(reopened.getNumberOfSheets()).isGreaterThan(0);
        }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Fixture(byte[] bytes, int styleId) {
        List<String> mergeList() throws Exception {
            try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                return workbook.getSheet(SHEET).getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList();
            }
        }
    }
}
