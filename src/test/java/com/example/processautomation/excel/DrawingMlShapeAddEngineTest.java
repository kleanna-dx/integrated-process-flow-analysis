package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DrawingMlShapeAddEngineTest {
    private static final String PROCESS = "SYNTH-11C";
    private static final String SHEET = "Layout";
    private static final String SOURCE_FILE = "synthetic-11c-layout.xlsx";
    private static final String TEMPLATE_RANGE = "A1:C3";
    private static final String TARGET_RANGE = "E1:G3";
    private static final byte[] PNG = createPng();
    private static final Path ARTIFACTS = artifactDirectory();

    private static Path artifactDirectory() {
        String configured = System.getProperty("pilot11c.runDir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of("validation-results", "pilot-11c-20261108",
                "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static byte[] createPng() {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x == 0 || y == 0 || x == image.getWidth() - 1 || y == image.getHeight() - 1
                        ? 0x17365D : 0xF2B134);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final EquipmentBoxGeometryLocator geometry = new EquipmentBoxGeometryLocator();
    private final DiagramPlacementPreflight placement = new DiagramPlacementPreflight(inspector, geometry, parts);
    private final DiagramBoxAddEngine boxEngine = new DiagramBoxAddEngine(placement, inspector, parts);
    private final DrawingMlShapeAddEngine engine = new DrawingMlShapeAddEngine(inspector, parts);

    @Test
    void addsRectangleToExistingShapeDrawingAndPreservesExistingXmlAndPackageParts() throws Exception {
        Fixture fixture = fixture(true, false, false, false, false, false);
        DrawingMlShapeAddEngine.ShapeAddRequest request = shapeRequest(fixture, null, null, "Pilot11C-Rectangle", false);
        var check = engine.analyze(fixture.bytes(), request);
        assertThat(check.testOnlyReady()).as("preflight=%s", check).isTrue();
        assertThat(check.shapeId()).isEqualTo("2");
        var result = engine.addShapeTestOnly(fixture.bytes(), withText(request, true));
        assertThat(result.status()).isEqualTo("TEST_ONLY");
        assertThat(result.geometryPreset()).isEqualTo("rect");
        assertThat(result.shapeId()).isEqualTo("2");
        assertOutput(fixture, result.workbookBytes(), result.drawingPartName(), 1, 0, 0, result.shapeId(), result.shapeName());
        assertThat(sha256(fixture.bytes())).isEqualTo(result.sourceSha256());
        persist("a-existing-shape", fixture.bytes(), result.workbookBytes(), result);
    }

    @Test
    void addsRectangleBesideExistingConnectorWithoutChangingConnectorOrBindings() throws Exception {
        Fixture fixture = fixture(false, true, false, false, false, false);
        var before = inspector.inspectSheet(fixture.bytes(), SHEET);
        assertThat(before.drawingObjects()).anyMatch(item -> "CONNECTOR".equals(item.objectType()));
        var result = engine.addShapeTestOnly(fixture.bytes(), shapeRequest(fixture, null, null, "Pilot11C-WithConnector", false));
        var after = inspector.inspectSheet(result.workbookBytes(), SHEET);
        assertThat(after.drawingObjects().subList(0, before.drawingObjects().size())).isEqualTo(before.drawingObjects());
        assertThat(after.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).toList())
                .isEqualTo(before.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).toList());
        assertOutput(fixture, result.workbookBytes(), result.drawingPartName(), 0, 1, 0, result.shapeId(), result.shapeName());
        assertThat(sha256(fixture.bytes())).isEqualTo(result.sourceSha256());
        persist("b-existing-connector", fixture.bytes(), result.workbookBytes(), result);
    }

    @Test
    void addsRectangleBesideExistingPictureAndPreservesImageRelationshipAndTarget() throws Exception {
        Fixture fixture = fixture(false, false, true, false, false, false);
        var relPath = relationshipPart(fixture.drawingPart());
        byte[] beforeRels = parts.readPart(fixture.bytes(), relPath);
        var beforeInventory = inspector.inspectSheet(fixture.bytes(), SHEET);
        assertThat(beforeInventory.drawingObjects()).anyMatch(item -> "PICTURE".equals(item.objectType()));
        var result = engine.addShapeTestOnly(fixture.bytes(), shapeRequest(fixture, null, null, "Pilot11C-WithPicture", false));
        assertThat(parts.readPart(result.workbookBytes(), relPath)).containsExactly(beforeRels);
        assertOutput(fixture, result.workbookBytes(), result.drawingPartName(), 0, 0, 1, result.shapeId(), result.shapeName());
        assertThat(sha256(fixture.bytes())).isEqualTo(result.sourceSha256());
        persist("c-existing-picture", fixture.bytes(), result.workbookBytes(), result);
    }

    @Test
    void combinesCellLayoutAndShapeInMemoryAndReturnsOnlyFullyValidatedWorkbook() throws Exception {
        Fixture fixture = fixture(true, true, false, true, false, true);
        var boxRequest = boxRequest(fixture);
        var shapeRequest = shapeRequest(fixture, null, null, "Pilot11C-Combined-Box", false);
        assertThat(placement.analyzeBoxAdd(fixture.bytes(), boxRequest).blockers()).contains("SHAPE_REQUIRED_BLOCKED");
        assertThatThrownBy(() -> boxEngine.addCellLayoutTestOnly(fixture.bytes(), boxRequest, fieldValues()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("BOX_ADD_NOT_TEST_ONLY_READY:BLOCKED");
        var result = boxEngine.addCellLayoutAndShapeTestOnly(fixture.bytes(), boxRequest, fieldValues(), shapeRequest);
        assertThat(result.status()).isEqualTo("CELL_LAYOUT_AND_DRAWINGML_SHAPE_ADD_TEST_ONLY");
        assertThat(result.relationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.changedCells()).hasSize(6);
        assertThat(result.shapeId()).isEqualTo("3");
        assertThat(result.note()).contains("no Connector", "physical connection", "no operational approval");
        assertOutput(fixture, result.workbookBytes(), result.drawingPartName(), 1, 1, 0,
                result.shapeId(), result.shapeName(), true);
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet(SHEET);
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(0).getCell(4))).isEqualTo("NEW-11C-01");
            assertThat(sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList())
                    .contains("C2:C3", "G2:G3");
            assertThat(normalizePrintArea(workbook.getPrintArea(workbook.getSheetIndex(SHEET))))
                    .isEqualTo(normalizePrintArea(fixture.printArea()));
            assertThat(sheet.getLastRowNum()).isEqualTo(9);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(10);
            assertThat(sheet.getRow(0).getHeight()).isEqualTo((short) 360);
            assertThat(sheet.getColumnWidth(6)).isEqualTo(sheet.getColumnWidth(2));
        }
        String originalSha = sha256(fixture.bytes());
        assertThat(originalSha).isEqualTo(result.sourceSha256());
        var insetShape = shapeRequest(fixture, new DrawingMlShapeAddEngine.Position(4, 0, 0, 0),
                new DrawingMlShapeAddEngine.Position(6, 2, 1, 1), "Pilot11C-Inset", false);
        assertThatThrownBy(() -> boxEngine.addCellLayoutAndShapeTestOnly(
                fixture.bytes(), boxRequest, fieldValues(), insetShape))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("CELL_LAYOUT_SHAPE_MUST_MATCH_BOX_BOUNDS");
        var invalidTemplate = withTemplate(shapeRequest(fixture, null, null, "Pilot11C-Composite-Failure", false),
                "UNREVIEWED_SHAPE");
        assertThatThrownBy(() -> boxEngine.addCellLayoutAndShapeTestOnly(
                fixture.bytes(), boxRequest, fieldValues(), invalidTemplate))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("SHAPE_TEMPLATE_UNSUPPORTED");
        assertThat(sha256(fixture.bytes())).isEqualTo(originalSha);
        persist("d-combined-cell-layout-shape", fixture.bytes(), result.workbookBytes(), result);
    }

    @Test
    void blocksShapeOverlapDuplicateIdsMalformedAnchorsAndUnapprovedTemplates() throws Exception {
        Fixture collision = fixture(true, false, false, false, true, false);
        var collisionResult = engine.analyze(collision.bytes(), shapeRequest(collision, null, null, "Pilot11C-Collision", false));
        assertThat(collisionResult.testOnlyReady()).isFalse();
        assertThat(collisionResult.blockers()).anyMatch(value -> value.startsWith("DRAWING_ANCHOR_COLLISION:"));

        Fixture duplicate = fixture(true, true, false, false, false, false);
        var duplicateResult = engine.analyze(duplicate.bytes(), shapeRequest(duplicate, null, null, "Pilot11C-Duplicate", false));
        assertThat(duplicateResult.testOnlyReady()).isFalse();
        assertThat(duplicateResult.blockers()).contains("DRAWING_NONVISUAL_ID_DUPLICATE:1");

        Fixture duplicateNameFixture = fixture(true, false, false, false, false, false);
        var duplicateName = engine.analyze(duplicateNameFixture.bytes(),
                shapeRequest(duplicateNameFixture, null, null, "Shape 1", false));
        assertThat(duplicateName.blockers()).contains("SHAPE_NAME_COLLISION:Shape 1");

        Fixture geometryFixture = fixture(true, false, false, false, false, false);
        byte[] geometryCollisionBytes = withTargetEquipmentBox(geometryFixture.bytes());
        var geometryCollision = engine.analyze(geometryCollisionBytes,
                shapeRequest(geometryCollisionBytes, "synthetic-11c#Layout", "Pilot11C-BoxCollision", false));
        assertThat(geometryCollision.blockers()).contains("EXISTING_EQUIPMENT_BOX_COLLISION:EQ-1234");

        Fixture malformed = fixture(true, false, false, false, false, false);
        String drawing = new String(parts.readPart(malformed.bytes(), malformed.drawingPart()), StandardCharsets.UTF_8);
        drawing = drawing.replaceFirst("<xdr:colOff>0</xdr:colOff>", "<xdr:colOff>-1</xdr:colOff>");
        byte[] malformedBytes = parts.replacePackagePart(malformed.bytes(), malformed.drawingPart(), drawing.getBytes(StandardCharsets.UTF_8));
        var malformedResult = engine.analyze(malformedBytes, shapeRequest(malformedBytes, "synthetic-11c#Layout", "Pilot11C-Malformed", false));
        assertThat(malformedResult.testOnlyReady()).isFalse();
        assertThat(malformedResult.blockers()).contains("DRAWING_ANCHOR_COORDINATES_UNVERIFIABLE:twoCellAnchor");

        Fixture good = fixture(true, false, false, false, false, false);
        var wrongTemplate = withTemplate(shapeRequest(good, null, null, "Pilot11C-Unapproved", false), "UNREVIEWED_SHAPE");
        assertThat(engine.analyze(good.bytes(), wrongTemplate).blockers()).contains("SHAPE_TEMPLATE_UNSUPPORTED");
        var notTestOnly = withTestOnly(shapeRequest(good, null, null, "Pilot11C-NotTestOnly", false), false);
        assertThat(engine.analyze(good.bytes(), notTestOnly).blockers()).contains("TEST_ONLY_REQUIRED");
    }

    @Test
    void rejectsUnsupportedDrawingStructureAndShapeAnchorOutsideTarget() throws Exception {
        Fixture fixture = fixture(true, false, false, false, false, false);
        String xml = new String(parts.readPart(fixture.bytes(), fixture.drawingPart()), StandardCharsets.UTF_8);
        String unsupported = xml.replace("twoCellAnchor", "absoluteAnchor");
        byte[] unsupportedBytes = parts.replacePackagePart(fixture.bytes(), fixture.drawingPart(), unsupported.getBytes(StandardCharsets.UTF_8));
        var unsupportedResult = engine.analyze(unsupportedBytes, shapeRequest(unsupportedBytes,
                "synthetic-11c#Layout", "Pilot11C-Unsupported", false));
        assertThat(unsupportedResult.testOnlyReady()).isFalse();
        assertThat(unsupportedResult.blockers()).anyMatch(value -> value.startsWith("DRAWING_ANCHOR_UNSUPPORTED:"));

        var outside = engine.analyze(fixture.bytes(), shapeRequest(fixture,
                new DrawingMlShapeAddEngine.Position(3, 0, 0, 0), new DrawingMlShapeAddEngine.Position(7, 3, 0, 0),
                "Pilot11C-Outside", false));
        assertThat(outside.testOnlyReady()).isFalse();
        assertThat(outside.blockers()).contains("SHAPE_ANCHOR_OUTSIDE_TARGET_BOX");
        assertThatThrownBy(() -> engine.addShapeTestOnly(fixture.bytes(), withTemplate(
                shapeRequest(fixture, null, null, "Pilot11C-Unapproved", false), "UNREVIEWED_SHAPE")))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("SHAPE_TEMPLATE_UNSUPPORTED");
    }

    private Fixture fixture(boolean existingShape, boolean existingConnector, boolean existingPicture,
                            boolean uniqueExistingIds, boolean shapeOnTarget, boolean mergedTemplate) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET);
            var boxStyle = workbook.createCellStyle();
            boxStyle.setBorderTop(BorderStyle.THIN);
            boxStyle.setBorderBottom(BorderStyle.THIN);
            boxStyle.setBorderLeft(BorderStyle.THIN);
            boxStyle.setBorderRight(BorderStyle.THIN);
            for (int rowIndex = 0; rowIndex < 10; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                row.setHeight((short) (rowIndex < 3 ? 360 : 300));
                for (int column = 0; column < 10; column++) {
                    var cell = row.createCell(column);
                    if (rowIndex < 3 && column < 3) cell.setCellStyle(boxStyle);
                    if (rowIndex < 3 && column >= 4 && column < 7) {
                        cell.setCellStyle(boxStyle);
                        cell.setCellValue(" ");
                    }
                }
            }
            for (int column = 0; column < 10; column++) sheet.setColumnWidth(column, 12 * 256);
            sheet.getRow(0).getCell(0).setCellValue("BASE-11C");
            sheet.getRow(0).getCell(1).setCellValue("Vessel");
            sheet.getRow(1).getCell(0).setCellValue("Base vessel");
            sheet.getRow(1).getCell(1).setCellValue("ITM-BASE");
            sheet.getRow(2).getCell(0).setCellValue("1,250 kg/h");
            sheet.getRow(2).getCell(1).setCellValue("1");
            if (mergedTemplate) sheet.addMergedRegion(new CellRangeAddress(1, 2, 2, 2));
            sheet.setAutobreaks(false);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 1);
            sheet.setHorizontallyCenter(true);
            sheet.setVerticallyCenter(false);
            workbook.setPrintArea(0, "$A$1:$J$10");
            var drawing = sheet.createDrawingPatriarch();
            int startColumn = shapeOnTarget ? 4 : 7;
            int startRow = shapeOnTarget ? 0 : 7;
            int endColumn = shapeOnTarget ? 7 : 9;
            int endRow = shapeOnTarget ? 3 : 9;
            if (existingShape) {
                var anchor = drawing.createAnchor(0, 0, 0, 0, startColumn, startRow, endColumn, endRow);
                drawing.createTextbox((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
            }
            if (existingConnector) {
                var anchor = drawing.createAnchor(0, 0, 0, 0, 0, 7, 2, 9);
                drawing.createConnector((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor);
            }
            if (existingPicture) {
                int pictureId = workbook.addPicture(PNG, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
                var anchor = drawing.createAnchor(0, 0, 0, 0, 7, 7, 9, 9);
                drawing.createPicture((org.apache.poi.xssf.usermodel.XSSFClientAnchor) anchor, pictureId);
            }
            workbook.write(output);
            byte[] bytes = output.toByteArray();
            String drawingPart = inspector.inspectSheet(bytes, SHEET).drawingPartName();
            if (uniqueExistingIds) bytes = normalizeIds(bytes, drawingPart);
            return new Fixture(bytes, drawingPart, (int) boxStyle.getIndex(), "$A$1:$J$10", mergedTemplate);
        }
    }

    private byte[] withTargetEquipmentBox(byte[] source) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet(SHEET);
            for (int rowIndex = 0; rowIndex <= 6; rowIndex++) {
                var row = sheet.getRow(rowIndex);
                for (int column = 4; column <= 9; column++) {
                    var cell = row.getCell(column);
                    if (cell == null) cell = row.createCell(column);
                    var style = workbook.createCellStyle();
                    if (column == 4) style.setBorderLeft(BorderStyle.THIN);
                    if (column == 9) style.setBorderRight(BorderStyle.THIN);
                    if (rowIndex == 0) style.setBorderTop(BorderStyle.THIN);
                    if (rowIndex == 6) style.setBorderBottom(BorderStyle.THIN);
                    cell.setCellStyle(style);
                }
            }
            sheet.getRow(0).getCell(4).setCellValue("EQ-1234");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] normalizeIds(byte[] bytes, String drawingPart) {
        String xml = new String(parts.readPart(bytes, drawingPart), StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(<xdr:cNvPr\\s+id=\\\")[0-9]+(\\\")").matcher(xml);
        StringBuffer result = new StringBuffer();
        int id = 1;
        while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + id++ + matcher.group(2)));
        matcher.appendTail(result);
        return parts.replacePackagePart(bytes, drawingPart, result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private DiagramPlacementPreflight.BoxAddRequest boxRequest(Fixture fixture) {
        String sha = sha256(fixture.bytes());
        var mapping = new DiagramLayoutMapping("synthetic-11c#Layout", sha, PROCESS, SHEET, "BASE-11C",
                TARGET_RANGE, "E1", Map.of(), List.of(), Map.of(), Map.of(), List.of(), List.of(),
                DiagramLayoutMapping.PlacementStatus.VERIFIED, DiagramLayoutMapping.EvidenceStatus.STRUCTURE_VERIFIED);
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", field(fixture, 0, 0));
        fields.put("equipmentTypeLabel", field(fixture, 0, 1));
        fields.put("equipmentName", field(fixture, 1, 0));
        fields.put("businessItemNo", field(fixture, 1, 1));
        fields.put("capacity", field(fixture, 2, 0));
        fields.put("quantity", field(fixture, 2, 1));
        List<String> merges = fixture.mergedTemplate() ? List.of("C2:C3") : List.of();
        Map<Integer, Float> heights = Map.of(0, 18f, 1, 18f, 2, 18f);
        Map<String, Double> widths = Map.of("A", 12d, "B", 12d, "C", 12d);
        var profile = new EquipmentBoxProfile("SYNTH-11C-RECT", PROCESS, SHEET, SOURCE_FILE,
                sha, sha, "1", "VERIFIED", "SYNTHETIC_TEST_ONLY", "Synthetic fixture only",
                TEMPLATE_RANGE, 3, fields, merges, Map.of(), "Arial", (short) 10, "DEFAULT_BLACK",
                heights, widths, "SHAPE_REQUIRED_TEST_ONLY", "PRESERVE_ONLY", "GENERIC_TEST_ONLY",
                "'Layout'!$A$1:$J$10", "PRESERVE", null);
        return new DiagramPlacementPreflight.BoxAddRequest(PROCESS, SHEET, TEMPLATE_RANGE, TARGET_RANGE,
                "NEW-11C-01", "Pilot vessel", sha, mapping, profile);
    }

    private EquipmentBoxProfile.FieldCell field(Fixture fixture, int row, int column) {
        return new EquipmentBoxProfile.FieldCell(row, column, fixture.styleId(), null, null, null, true, "TEXT");
    }

    private Map<String, String> fieldValues() {
        return Map.of("managementNo", "NEW-11C-01", "equipmentTypeLabel", "Vessel",
                "equipmentName", "Pilot vessel", "businessItemNo", "ITM-11C-01",
                "capacity", "2,500 kg/h", "quantity", "1");
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest shapeRequest(Fixture fixture, DrawingMlShapeAddEngine.Position from,
                                                                  DrawingMlShapeAddEngine.Position to, String name,
                                                                  boolean includeText) {
        return shapeRequest(fixture.bytes(), "synthetic-11c#Layout", name, includeText,
                from == null ? new DrawingMlShapeAddEngine.Position(4, 0, 0, 0) : from,
                to == null ? new DrawingMlShapeAddEngine.Position(7, 3, 0, 0) : to);
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest shapeRequest(byte[] bytes, String fileIdentifier, String name,
                                                                  boolean includeText) {
        return shapeRequest(bytes, fileIdentifier, name, includeText,
                new DrawingMlShapeAddEngine.Position(4, 0, 0, 0),
                new DrawingMlShapeAddEngine.Position(7, 3, 0, 0));
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest shapeRequest(byte[] bytes, String fileIdentifier, String name,
                                                                  boolean includeText, DrawingMlShapeAddEngine.Position from,
                                                                  DrawingMlShapeAddEngine.Position to) {
        return new DrawingMlShapeAddEngine.ShapeAddRequest(sha256(bytes), PROCESS, fileIdentifier, SHEET, TARGET_RANGE,
                DrawingMlShapeAddEngine.ShapeKind.RECTANGLE, from, to,
                new DrawingMlShapeAddEngine.ShapeStyle("17365D", 12700, null), includeText,
                includeText ? "Synthetic rectangle" : null, name,
                DrawingMlShapeAddEngine.SUPPORTED_TEMPLATE_ID,
                DrawingMlShapeAddEngine.REQUIRED_TEMPLATE_APPROVAL, true);
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest withTemplate(DrawingMlShapeAddEngine.ShapeAddRequest request,
                                                                  String template) {
        return new DrawingMlShapeAddEngine.ShapeAddRequest(request.sourceWorkbookSha256(), request.processId(),
                request.sourceFileIdentifier(), request.sheetName(), request.targetBoxRange(), request.shapeKind(),
                request.from(), request.to(), request.style(), request.includeText(), request.text(), request.shapeName(),
                template, request.templateApprovalStatus(), request.testOnly());
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest withText(DrawingMlShapeAddEngine.ShapeAddRequest request,
                                                              boolean includeText) {
        return new DrawingMlShapeAddEngine.ShapeAddRequest(request.sourceWorkbookSha256(), request.processId(),
                request.sourceFileIdentifier(), request.sheetName(), request.targetBoxRange(), request.shapeKind(),
                request.from(), request.to(), request.style(), includeText, includeText ? "Synthetic rectangle" : null,
                request.shapeName(), request.shapeTemplateId(), request.templateApprovalStatus(), request.testOnly());
    }

    private DrawingMlShapeAddEngine.ShapeAddRequest withTestOnly(DrawingMlShapeAddEngine.ShapeAddRequest request,
                                                                  boolean testOnly) {
        return new DrawingMlShapeAddEngine.ShapeAddRequest(request.sourceWorkbookSha256(), request.processId(),
                request.sourceFileIdentifier(), request.sheetName(), request.targetBoxRange(), request.shapeKind(),
                request.from(), request.to(), request.style(), request.includeText(), request.text(), request.shapeName(),
                request.shapeTemplateId(), request.templateApprovalStatus(), testOnly);
    }

    private void assertOutput(Fixture fixture, byte[] output, String drawingPart, int expectedShape,
                              int expectedConnector, int expectedPicture, String shapeId, String shapeName) throws Exception {
        assertOutput(fixture, output, drawingPart, expectedShape, expectedConnector, expectedPicture,
                shapeId, shapeName, false);
    }

    private void assertOutput(Fixture fixture, byte[] output, String drawingPart, int expectedShape,
                              int expectedConnector, int expectedPicture, String shapeId, String shapeName,
                              boolean worksheetMayChange) throws Exception {
        assertZipIntegrity(output);
        var before = inspector.inspect(fixture.bytes());
        var after = inspector.inspect(output);
        assertThat(after.shapeCount()).isEqualTo(before.shapeCount() + 1);
        assertThat(after.connectorCount()).isEqualTo(before.connectorCount());
        assertThat(after.pictureCount()).isEqualTo(before.pictureCount());
        assertThat(after.sheets().stream().filter(s -> SHEET.equals(s.sheetName())).findFirst().orElseThrow().drawingObjects())
                .anySatisfy(object -> {
                    assertThat(object.objectType()).isEqualTo("SHAPE");
                    assertThat(object.shapeId()).isEqualTo(shapeId);
                    assertThat(object.objectName()).isEqualTo(shapeName);
                    assertThat(object.geometryPreset()).isEqualTo("rect");
                    assertThat(object.startColumn()).isEqualTo(4);
                    assertThat(object.startRow()).isEqualTo(0);
                    assertThat(object.endColumn()).isEqualTo(7);
                    assertThat(object.endRow()).isEqualTo(3);
                });
        byte[] oldDrawing = parts.readPart(fixture.bytes(), drawingPart);
        byte[] newDrawing = parts.readPart(output, drawingPart);
        String added = new String(newDrawing, StandardCharsets.UTF_8);
        assertThat(added.replaceAll("<xdr:twoCellAnchor editAs=\\\"twoCell\\\" xmlns:xdr=\\\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\\\" xmlns:a=\\\"http://schemas.openxmlformats.org/drawingml/2006/main\\\">.*?</xdr:twoCellAnchor>", ""))
                .isEqualTo(new String(oldDrawing, StandardCharsets.UTF_8));
        assertThat(before.drawingPartCount()).isEqualTo(after.drawingPartCount());
        assertPackagePartsExceptDrawingEqual(fixture.bytes(), output, drawingPart, worksheetMayChange);
        assertThat(before.shapeCount()).isEqualTo(expectedShape);
        assertThat(after.shapeCount()).isEqualTo(expectedShape + 1);
        assertThat(after.connectorCount()).isEqualTo(expectedConnector);
        assertThat(expectedPicture).isEqualTo(after.pictureCount());
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldWorkbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(fixture.bytes()));
            try {
                var oldSheet = oldWorkbook.getSheet(SHEET);
                var newSheet = workbook.getSheet(SHEET);
                assertThat(oldSheet.getLastRowNum()).isEqualTo(newSheet.getLastRowNum());
                if (!worksheetMayChange) assertThat(oldSheet.getNumMergedRegions()).isEqualTo(newSheet.getNumMergedRegions());
                assertThat(normalizePrintArea(oldWorkbook.getPrintArea(oldWorkbook.getSheetIndex(SHEET))))
                        .isEqualTo(normalizePrintArea(workbook.getPrintArea(workbook.getSheetIndex(SHEET))));
                assertThat(oldSheet.getPrintSetup().getFitWidth()).isEqualTo(newSheet.getPrintSetup().getFitWidth());
                assertThat(oldSheet.getAutobreaks()).isEqualTo(newSheet.getAutobreaks());
            } finally { oldWorkbook.close(); }
        }
    }

    private void assertPackagePartsExceptDrawingEqual(byte[] before, byte[] after, String drawingPart,
                                                      boolean worksheetMayChange) throws Exception {
        String worksheetPart = inspector.inspectSheet(before, SHEET).worksheetPartName();
        try (ZipFile oldZip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(before)).get();
             ZipFile newZip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(after)).get()) {
            var entries = oldZip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var next = newZip.getEntry(entry.getName());
                assertThat(next).isNotNull();
                if (drawingPart.equals(entry.getName()) || worksheetMayChange && worksheetPart.equals(entry.getName())) continue;
                try (var oldStream = oldZip.getInputStream(entry); var newStream = newZip.getInputStream(next)) {
                    assertThat(oldStream.readAllBytes()).containsExactly(newStream.readAllBytes());
                }
            }
            var entriesAfter = newZip.getEntries();
            while (entriesAfter.hasMoreElements()) assertThat(oldZip.getEntry(entriesAfter.nextElement().getName())).isNotNull();
        }
    }

    private void assertZipIntegrity(byte[] bytes) throws Exception {
        try (var channel = new SeekableInMemoryByteChannel(bytes); ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            var entries = zip.getEntries();
            int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                count++;
            }
            assertThat(count).isGreaterThan(0);
        }
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet(SHEET)).isNotNull();
        }
    }

    private void persist(String label, byte[] source, byte[] output, Object result) throws Exception {
        Files.createDirectories(ARTIFACTS);
        Path sourcePath = ARTIFACTS.resolve(label + "_SOURCE_TEST_ONLY.xlsx");
        Path outputPath = ARTIFACTS.resolve(label + "_OUTPUT_TEST_ONLY.xlsx");
        Files.write(sourcePath, source);
        Files.write(outputPath, output);
        String inputSha;
        String outputSha;
        String shapeId;
        String shapeName;
        String sheetName;
        String validationStatus;
        if (result instanceof DrawingMlShapeAddEngine.ShapeAddResult shape) {
            inputSha = shape.sourceSha256();
            outputSha = shape.outputSha256();
            shapeId = shape.shapeId();
            shapeName = shape.shapeName();
            sheetName = SHEET;
            validationStatus = shape.status();
        } else if (result instanceof DiagramBoxAddEngine.BoxAndShapeAddResult composite) {
            inputSha = composite.sourceSha256();
            outputSha = composite.outputSha256();
            shapeId = composite.shapeId();
            shapeName = composite.shapeName();
            sheetName = composite.sheetName();
            validationStatus = composite.status();
        } else {
            throw new IllegalArgumentException("Unsupported TEST_ONLY result metadata type: " + result.getClass().getName());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenarioId", label);
        metadata.put("inputSha256", inputSha);
        metadata.put("outputSha256", outputSha);
        metadata.put("relativeOutputPath", ARTIFACTS.resolve(outputPath.getFileName()).toString().replace('\\', '/'));
        metadata.put("addedShapeId", shapeId);
        metadata.put("addedShapeName", shapeName);
        metadata.put("sheetName", sheetName);
        metadata.put("validationStatus", validationStatus);
        metadata.put("preservationStatus", "PASS");
        metadata.put("renderStatus", "PENDING_RENDER");
        metadata.put("failureReasons", List.of());
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(ARTIFACTS.resolve(label + "_RESULT.json").toFile(), metadata);
    }

    private String relationshipPart(String drawingPart) {
        int slash = drawingPart.lastIndexOf('/');
        return drawingPart.substring(0, slash + 1) + "_rels/" + drawingPart.substring(slash + 1) + ".rels";
    }

    private String normalizePrintArea(String value) {
        return value == null ? null : value.replace("'", "").replace("$", "").replaceAll("(?i)^" + SHEET + "!", "");
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Fixture(byte[] bytes, String drawingPart, int styleId, String printArea, boolean mergedTemplate) { }
}
