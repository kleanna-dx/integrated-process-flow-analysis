package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.example.processautomation.model.MasterEquipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DiagramEquipmentInsertionEngineTest {
    private static final String PROCESS = "SYNTH-P";
    private static final String SHEET = "Layout";
    private static final String SOURCE_SHA = "a".repeat(64);
    private final DiagramEquipmentInsertionEngine engine = new DiagramEquipmentInsertionEngine(
            new OoxmlPartEditor(), new OoxmlPackageInspector(), new EquipmentBoxGeometryLocator());

    @Test
    void addsBoxWithProfileAcrossSeparatedFieldsSplitCapacityAndMergedTemplate() throws Exception {
        Fixture fixture = fixture(Layout.CAPACITY_UNIT_SPLIT, true, false, false);
        EquipmentBoxProfile profile = profile(fixture, true, null);
        var output = engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment(), SHEET,
                "SRC-01", "DST-01", "저장시설", profile, PROCESS, SOURCE_SHA);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.workbookBytes()))) {
            var sheet = workbook.getSheet(SHEET);
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(10).getCell(0))).isEqualTo("NEW-01");
            assertThat(formatter.formatCellValue(sheet.getRow(12).getCell(1))).isEqualTo("저장시설");
            assertThat(formatter.formatCellValue(sheet.getRow(13).getCell(1))).isEqualTo("Profile Tank");
            assertThat(formatter.formatCellValue(sheet.getRow(14).getCell(3))).isEqualTo("NEW-ITEM-01");
            assertThat(formatter.formatCellValue(sheet.getRow(15).getCell(2))).isEqualTo("125");
            assertThat(formatter.formatCellValue(sheet.getRow(15).getCell(3))).isEqualTo("m3/h");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(1))).isEqualTo("1ea");
            assertThat(sheet.getNumMergedRegions()).isEqualTo(2);
            assertThat(sheet.getMergedRegions().stream().map(r -> r.formatAsString()))
                    .contains("D2:E2", "D12:E12");
            assertThat(sheet.getRow(10).getHeightInPoints()).isEqualTo(12f);
            assertThat(sheet.getRow(17).getHeightInPoints()).isEqualTo(19f);
            var id = sheet.getRow(10).getCell(0);
            assertThat((int) id.getCellStyle().getIndex()).isEqualTo(fixture.styleIds().get("managementNo"));
            var font = workbook.getFontAt(id.getCellStyle().getFontIndexAsInt());
            assertThat(font.getFontName()).isEqualTo("맑은 고딕");
            assertThat(font.getFontHeightInPoints()).isEqualTo((short) 8);
            assertThat(workbook.getPrintArea(0)).contains("A1:H50");
        }
        assertThat(output.mode()).isEqualTo("TEST_ONLY");
        assertThat(output.relationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(output.plan().template().fieldCells()).containsEntry("businessItemNo", "D5");
        assertThat(output.plan().template().fieldColumnOffsets()).containsEntry("businessItemNo", 3);
    }

    @Test
    void optionalNoteIsWrittenOnlyWhenProfileMapsIt() throws Exception {
        Fixture fixture = fixture(false, false, false, false);
        EquipmentBoxProfile mapped = profileWithNote(fixture);
        var result = engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment(), SHEET,
                "SRC-01", "DST-01", "저장시설", mapped, PROCESS, SOURCE_SHA, "TEST_ONLY note");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            assertThat(new DataFormatter().formatCellValue(workbook.getSheet(SHEET).getRow(12).getCell(2)))
                    .isEqualTo("TEST_ONLY note");
        }
        var unmapped = profile(fixture, false, null);
        assertThatThrownBy(() -> engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment(), SHEET,
                "SRC-01", "DST-01", "저장시설", unmapped, PROCESS, SOURCE_SHA, "not mapped"))
                .hasMessageContaining("PROFILE_NOTE_NOT_MAPPED");
    }

    @Test
    void ordinaryRectangleProfileWorksWithoutMergedCells() throws Exception {
        Fixture fixture = fixture(false, false, false, false);
        var plan = engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                profile(fixture, false, null), PROCESS, SOURCE_SHA);
        assertThat(plan.template().recognized()).isTrue();
        assertThat(plan.sourceMerges()).isEmpty();
    }

    @Test
    void adjacentDrawingShapeIsShiftedAndPreservedWithoutBlockingBox() {
        Fixture fixture = fixture(false, true, true, false);
        var result = engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment(), SHEET,
                "SRC-01", "DST-01", "저장시설", profile(fixture, false, null), PROCESS, SOURCE_SHA);
        var before = new OoxmlPackageInspector().inspect(fixture.bytes());
        var after = new OoxmlPackageInspector().inspect(result.workbookBytes());
        assertThat(after.shapeCount()).isEqualTo(before.shapeCount());
        assertThat(after.connectorCount()).isEqualTo(before.connectorCount());
        assertThat(after.sheets().get(0).drawingObjects().get(0).startRow()).isGreaterThanOrEqualTo(20);
        assertThat(after.sheets().get(0).drawingObjects().get(0).objectName())
                .isEqualTo(before.sheets().get(0).drawingObjects().get(0).objectName());
    }

    @Test
    void connectorCrossingCandidateBandIsBlocked() {
        Fixture fixture = fixture(false, false, false, true);
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                profile(fixture, false, null), PROCESS, SOURCE_SHA))
                .hasMessageContaining("DRAWING_ANCHOR_CROSSES_INSERTION");
    }

    @Test
    void generatesSevenTestOnlyLayoutArtifactsAndBlocksConnectorCrossing() throws Exception {
        String run = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        java.nio.file.Path directory = java.nio.file.Path.of("validation-results", "2026-11-01-step7-7-profile-add-test", run);
        java.nio.file.Files.createDirectories(directory);
        List<String> manifest = new java.util.ArrayList<>();
        List<Fixture> layouts = List.of(
                fixture(Layout.STANDARD, false, false, false),
                fixture(Layout.STANDARD, true, false, false),
                fixture(Layout.ID_NAME_SEPARATE_ROWS, false, false, false),
                fixture(Layout.ITEM_NO_SEPARATE_AREA, false, false, false),
                fixture(Layout.CAPACITY_UNIT_SPLIT, false, false, false),
                fixture(Layout.VARIABLE_ROW_HEIGHTS, false, false, false),
                fixture(Layout.STANDARD, false, true, false));
        String[] names = {"01-rectangle", "02-merged", "03-id-name-rows", "04-item-separate-area",
                "05-capacity-unit-split", "06-variable-row-heights", "07-adjacent-drawing"};
        List<String> outputHashes = new java.util.ArrayList<>();
        List<String> worksheetXmlHashes = new java.util.ArrayList<>();
        for (int index = 0; index < layouts.size(); index++) {
            Fixture fixture = layouts.get(index);
            var result = engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment(), SHEET,
                    "SRC-01", "DST-01", "저장시설", profile(fixture, index == 1, null), PROCESS, SOURCE_SHA);
            assertThat(result.mode()).isEqualTo("TEST_ONLY");
            assertThat(result.relationStatus()).isEqualTo("REVIEW_REQUIRED");
            assertOutputValuesAndZip(result.workbookBytes(), fixture, names[index]);
            java.nio.file.Path output = directory.resolve(names[index] + ".xlsx");
            if (java.nio.file.Files.exists(output)) throw new AssertionError("Refusing to overwrite existing TEST_ONLY artifact: " + output);
            java.nio.file.Files.write(output, result.workbookBytes());
            String outputHash = sha256(result.workbookBytes());
            outputHashes.add(outputHash);
            worksheetXmlHashes.add(sha256(readPackagePart(result.workbookBytes(), "xl/worksheets/sheet1.xml")));
            manifest.add(names[index] + ".xlsx\t" + outputHash);
        }
        assertThat(outputHashes).as("Each named layout must produce a structurally distinct XLSX").doesNotHaveDuplicates();
        assertThat(worksheetXmlHashes.subList(0, 6)).as("The six layout scenarios must have distinct worksheet XML")
                .doesNotHaveDuplicates();
        Fixture connectorFixture = fixture(false, false, false, true);
        assertThat(new OoxmlPackageInspector().inspect(connectorFixture.bytes()).connectorCount())
                .as("08 negative fixture must contain a connector crossing the insertion band").isEqualTo(1);
        assertThatThrownBy(() -> engine.insertBoxOnlyTestOnly(connectorFixture.bytes(), equipment(), SHEET,
                "SRC-01", "DST-01", "저장시설", profile(connectorFixture, false, null), PROCESS, SOURCE_SHA))
                .hasMessageContaining("DRAWING_ANCHOR_CROSSES_INSERTION");
        manifest.add("08-connector-crossing\tBLOCKED_DRAWING_ANCHOR_CROSSES_INSERTION");
        java.nio.file.Files.writeString(directory.resolve("artifact-sha256.tsv"), String.join("\n", manifest) + "\n");
    }

    private void assertOutputValuesAndZip(byte[] bytes, Fixture fixture, String artifactName) throws Exception {
        Map<String, byte[]> packageParts = readPackage(bytes);
        assertThat(packageParts).containsKey("xl/worksheets/sheet1.xml");
        EquipmentBoxProfile outputProfile = profile(fixture, artifactName.equals("02-merged"), null);
        var beforeInventory = new OoxmlPackageInspector().inspect(fixture.bytes());
        var afterInventory = new OoxmlPackageInspector().inspect(bytes);
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(fixture.bytes()));
             var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var oldSheet = before.getSheet(SHEET);
            var sheet = workbook.getSheet(SHEET);
            var formatter = new DataFormatter();
            Map<String, String> expectedValues = Map.of(
                    "managementNo", "NEW-01", "equipmentTypeLabel", "저장시설", "equipmentName", "Profile Tank",
                    "businessItemNo", "NEW-ITEM-01", "capacity", fixture.layout() == Layout.CAPACITY_UNIT_SPLIT ? "125" : "125m3/h",
                    "capacityUnit", "m3/h", "quantity", "1ea");
            for (var entry : outputProfile.fields().entrySet()) {
                String field = entry.getKey();
                var mapping = entry.getValue();
                String expected = expectedValues.get(field);
                if (expected == null) continue;
                int rowIndex = 10 + mapping.rowOffset();
                int columnIndex = mapping.columnOffset();
                assertThat(formatter.formatCellValue(sheet.getRow(rowIndex).getCell(columnIndex)))
                        .as(artifactName + " " + field + " mapped at row/column " + rowIndex + "/" + columnIndex)
                        .isEqualTo(expected);
                assertThat((int) sheet.getRow(rowIndex).getCell(columnIndex).getCellStyle().getIndex())
                        .as(artifactName + " copied style for " + field).isEqualTo(mapping.expectedStyleId());
            }
            var idMapping = outputProfile.fields().get("managementNo");
            var insertedStyle = sheet.getRow(10 + idMapping.rowOffset()).getCell(idMapping.columnOffset()).getCellStyle();
            assertThat(insertedStyle.getFillPattern()).as(artifactName + " fill").isEqualTo(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            assertThat(insertedStyle.getFillForegroundColor()).as(artifactName + " fill color")
                    .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            assertThat(insertedStyle.getBorderLeft()).as(artifactName + " border").isEqualTo(BorderStyle.THIN);
            assertThat(insertedStyle.getAlignment()).as(artifactName + " alignment")
                    .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            assertThat(insertedStyle.getVerticalAlignment()).as(artifactName + " vertical alignment")
                    .isEqualTo(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var font = workbook.getFontAt(insertedStyle.getFontIndexAsInt());
            assertThat(font.getFontName()).as(artifactName + " font").isEqualTo("맑은 고딕");
            assertThat(font.getColor()).as(artifactName + " font color").isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            assertThat(font.getFontHeightInPoints()).as(artifactName + " font size").isEqualTo((short) 8);
            for (int offset = 0; offset < 8; offset++)
                assertThat(sheet.getRow(10 + offset).getHeightInPoints()).as(artifactName + " copied row height offset " + offset)
                        .isEqualTo(fixture.rowHeights().get(offset));
            assertThat(sheet.getNumMergedRegions()).as(artifactName + " merge replication")
                    .isEqualTo(artifactName.equals("02-merged") ? 2 : 0);
            assertThat(workbook.getPrintArea(0)).as(artifactName + " print area").contains("A1:H50");
            assertThat(sheet.getPrintSetup().getPaperSize()).as(artifactName + " print page setup")
                    .isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            assertThat(sheet.getPrintSetup().getLandscape()).as(artifactName + " orientation")
                    .isEqualTo(oldSheet.getPrintSetup().getLandscape());
            assertThat(sheet.getPrintSetup().getFitWidth()).as(artifactName + " print fit width")
                    .isEqualTo(oldSheet.getPrintSetup().getFitWidth());
            assertThat(sheet.getPrintSetup().getFitHeight()).as(artifactName + " print fit height")
                    .isEqualTo(oldSheet.getPrintSetup().getFitHeight());
            assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin)).as(artifactName + " left print margin")
                    .isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin));
            assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin)).as(artifactName + " right print margin")
                    .isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin));
            assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin)).as(artifactName + " top print margin")
                    .isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin));
            assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin)).as(artifactName + " bottom print margin")
                    .isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin));
            assertExistingCellsAndDimensionsPreserved(oldSheet, sheet, 10, 10);
            var oldMerges = oldSheet.getMergedRegions().stream().map(org.apache.poi.ss.util.CellRangeAddress::formatAsString).toList();
            for (String oldMerge : oldMerges) {
                var range = org.apache.poi.ss.util.CellRangeAddress.valueOf(oldMerge);
                int delta = range.getFirstRow() >= 10 ? 11 : 0;
                var moved = new org.apache.poi.ss.util.CellRangeAddress(range.getFirstRow() + delta,
                        range.getLastRow() + delta, range.getFirstColumn(), range.getLastColumn()).formatAsString();
                assertThat(sheet.getMergedRegions().stream().map(org.apache.poi.ss.util.CellRangeAddress::formatAsString))
                        .as(artifactName + " preserves original merge " + oldMerge).contains(moved);
            }
        }
        assertThat(afterInventory.shapeCount()).as(artifactName + " shape count").isEqualTo(beforeInventory.shapeCount());
        assertThat(afterInventory.connectorCount()).as(artifactName + " connector count").isEqualTo(beforeInventory.connectorCount());
        if (artifactName.equals("07-adjacent-drawing"))
            assertThat(beforeInventory.shapeCount()).as("07 must have its own adjacent DrawingML object").isEqualTo(1);
        else assertThat(beforeInventory.shapeCount()).as(artifactName + " must not inherit the adjacent DrawingML fixture").isZero();
        var oldObjects = beforeInventory.sheets().get(0).drawingObjects();
        var newObjects = afterInventory.sheets().get(0).drawingObjects();
        for (var oldObject : oldObjects) {
            var current = newObjects.stream().filter(object -> object.shapeId().equals(oldObject.shapeId())).findFirst().orElseThrow();
            assertThat(current.objectType()).isEqualTo(oldObject.objectType());
            assertThat(current.objectName()).isEqualTo(oldObject.objectName());
            assertThat(current.startConnectedShapeId()).isEqualTo(oldObject.startConnectedShapeId());
            assertThat(current.endConnectedShapeId()).isEqualTo(oldObject.endConnectedShapeId());
            assertThat(current.headArrowType()).isEqualTo(oldObject.headArrowType());
            assertThat(current.tailArrowType()).isEqualTo(oldObject.tailArrowType());
            assertThat(current.startColumn()).isEqualTo(oldObject.startColumn());
            assertThat(current.endColumn()).isEqualTo(oldObject.endColumn());
            assertThat(current.startRow()).isEqualTo(oldObject.startRow() >= 10 ? oldObject.startRow() + 10 : oldObject.startRow());
            assertThat(current.endRow()).isEqualTo(oldObject.endRow() >= 10 ? oldObject.endRow() + 10 : oldObject.endRow());
        }
    }

    private void assertExistingCellsAndDimensionsPreserved(org.apache.poi.ss.usermodel.Sheet before,
                                                            org.apache.poi.ss.usermodel.Sheet after,
                                                            int insertionRowIndex, int rowDelta) {
        DataFormatter formatter = new DataFormatter();
        for (var oldRow : before) {
            int movedRowIndex = oldRow.getRowNum() >= insertionRowIndex ? oldRow.getRowNum() + rowDelta : oldRow.getRowNum();
            var newRow = after.getRow(movedRowIndex);
            assertThat(newRow).as("preserved row " + movedRowIndex).isNotNull();
            assertThat(newRow.getHeight()).as("preserved row height " + movedRowIndex).isEqualTo(oldRow.getHeight());
            for (var oldCell : oldRow) {
                var newCell = newRow.getCell(oldCell.getColumnIndex());
                assertThat(newCell).as("preserved cell " + oldCell.getAddress()).isNotNull();
                assertThat(formatter.formatCellValue(newCell)).as("preserved value " + oldCell.getAddress())
                        .isEqualTo(formatter.formatCellValue(oldCell));
                assertThat((int) newCell.getCellStyle().getIndex()).as("preserved style " + oldCell.getAddress())
                        .isEqualTo((int) oldCell.getCellStyle().getIndex());
            }
        }
        int lastColumn = Math.max(before.getRow(0).getLastCellNum(), after.getRow(0).getLastCellNum());
        for (int column = 0; column < lastColumn; column++)
            assertThat(after.getColumnWidth(column)).as("preserved column width " + column).isEqualTo(before.getColumnWidth(column));
    }

    private Map<String, byte[]> readPackage(byte[] bytes) throws Exception {
        Map<String, byte[]> packageParts = new LinkedHashMap<>();
        try (var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { packageParts.put(entry.getName(), input.readAllBytes()); }
            }
        }
        return packageParts;
    }

    private byte[] readPackagePart(byte[] bytes, String name) throws Exception {
        return readPackage(bytes).get(name);
    }

    @Test
    void duplicateEquipmentIdAndItemNoAreBlockedWithoutChangingInputBytes() {
        Fixture fixture = fixture(false, false, false, false);
        String originalSha = sha256(fixture.bytes());
        var profile = profile(fixture, false, null);
        assertThatThrownBy(() -> engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment("SRC-01", "NEW-ITEM-01"),
                SHEET, "SRC-01", "DST-01", "저장시설", profile, PROCESS, SOURCE_SHA))
                .hasMessageContaining("DUPLICATE_EQUIPMENT_ID");
        assertThatThrownBy(() -> engine.insertBoxOnlyTestOnly(fixture.bytes(), equipment("NEW-02", "ITEM-01"),
                SHEET, "SRC-01", "DST-01", "저장시설", profile, PROCESS, SOURCE_SHA))
                .hasMessageContaining("DUPLICATE_ITEM_NO");
        assertThat(sha256(fixture.bytes())).isEqualTo(originalSha);
    }

    @Test
    void missingProfileAndSourceOrWorkingShaMismatchAreBlocked() {
        Fixture fixture = fixture(false, false, false, false);
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                null, PROCESS, SOURCE_SHA)).hasMessageContaining("PROFILE_MISSING");
        var correct = profile(fixture, false, null);
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                correct, PROCESS, "b".repeat(64))).hasMessageContaining("PROFILE_IDENTITY_MISMATCH");
        var staleWorking = new EquipmentBoxProfile(correct.profileId(), correct.processCode(), correct.sheetName(),
                correct.sourceFilename(), correct.sourceSha256(), "0".repeat(64), correct.profileVersion(),
                correct.status(), correct.evidenceLevel(), correct.evidence(), correct.templateRange(), correct.boxHeight(), correct.fields(),
                correct.mergedRanges(), correct.expectedStyleIds(), correct.fontName(), correct.fontSizePt(),
                correct.fontColor(), correct.rowHeightsPt(), correct.columnWidths(), correct.drawingPolicy(),
                correct.connectorPolicy(), correct.insertionPolicy(), correct.printArea(), correct.pageBreakPolicy(), null);
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                staleWorking, PROCESS, SOURCE_SHA)).hasMessageContaining("PROFILE_WORKING_SHA_MISMATCH");
    }

    @Test
    void wrongStyleMergeAndUnsupportedMappingAreBlocked() {
        Fixture fixture = fixture(true, false, false, false);
        var original = profile(fixture, true, null);
        var fields = new LinkedHashMap<>(original.fields());
        var id = fields.get("managementNo");
        fields.put("managementNo", new EquipmentBoxProfile.FieldCell(id.rowOffset(), id.columnOffset(),
                id.expectedStyleId() + 1, id.expectedFontName(), id.expectedFontSizePt(), id.expectedFontColor(), true, id.valueMode()));
        var wrongStyle = copyProfile(original, fields, original.mergedRanges());
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                wrongStyle, PROCESS, SOURCE_SHA)).hasMessageContaining("PROFILE_STYLE_MISMATCH");

        var wrongMerge = copyProfile(original, original.fields(), List.of("D4:E4"));
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                wrongMerge, PROCESS, SOURCE_SHA)).hasMessageContaining("PROFILE_MERGE_MISMATCH");

        var unsupportedFields = new LinkedHashMap<>(original.fields());
        unsupportedFields.put("unknownFlow", new EquipmentBoxProfile.FieldCell(1, 1, null, null, null, null, false, "TEXT"));
        var unsupported = copyProfile(original, unsupportedFields, original.mergedRanges());
        assertThatThrownBy(() -> engine.analyze(fixture.bytes(), SHEET, "SRC-01", "DST-01",
                unsupported, PROCESS, SOURCE_SHA)).hasMessageContaining("PROFILE_FIELD_UNSUPPORTED");
    }

    @Test
    void profileCatalogIsImmutableAndKeepsLegacyAndUnresolvedCasesSeparate() {
        var repository = new EquipmentBoxProfileRepository(new ObjectMapper());
        var profiles = repository.findAll();
        assertThat(profiles).hasSize(5);
        var p02 = repository.findFor("P-02", "P-02-01(TOP)",
                "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76").orElseThrow();
        assertThat(p02.status()).isEqualTo("VERIFIED");
        assertThat(p02.evidenceLevel()).isEqualTo("WORKING_COPY_VERIFIED");
        assertThat(p02.matchesWorkingCopySha("c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2")).isTrue();
        assertThat(repository.findExact("P-02-01-TOP-V1", "1.0.0", "P-02", "P-02-01(TOP)",
                "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76")).isPresent();
        assertThat(repository.findExact("P-02-01-TOP-V1", "9.9.9", "P-02", "P-02-01(TOP)",
                "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76")).isEmpty();
        var p02Broke = repository.findFor("P-02", "P-02-05(BROKE)",
                "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76").orElseThrow();
        assertThat(p02Broke.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(p02Broke.evidenceLevel()).isEqualTo("STRUCTURE_ANALYZED");
        assertThat(p02Broke.enabledForGenericAdd()).isFalse();
        assertThat(p02Broke.fields()).containsKeys("managementNo", "equipmentTypeLabel", "equipmentName",
                "businessItemNo", "capacity", "quantity");
        assertThat(p02Broke.templateRange()).isEqualTo("J129:AA136");
        var pw02 = repository.findFor("PW-02", "PW-02-01",
                "2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a").orElseThrow();
        assertThat(pw02.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(pw02.evidenceLevel()).isEqualTo("STRUCTURE_ANALYZED");
        assertThat(pw02.matchesWorkingCopySha("4250b004c723b9d366d1231ce98d75761a864564402250fe5a5873665b00b8d1")).isFalse();
        assertThat(pw02.enabledForGenericAdd()).isFalse();
        var p01 = repository.findFor("P-01", "P-01-01(TOP)",
                "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391").orElseThrow();
        assertThat(p01.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(p01.evidenceLevel()).isEqualTo("STRUCTURE_ANALYZED");
        assertThat(p01.templateRange()).isEqualTo("J4:AA20");
        assertThat(p01.boxHeight()).isEqualTo(17);
        assertThat(p01.enabledForGenericAdd()).isFalse();
        var legacy = repository.findFor("PW-01", "PW-01-05",
                "784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0").orElseThrow();
        assertThat(legacy.status()).isEqualTo("LEGACY_ONLY");
        assertThat(legacy.evidenceLevel()).isEqualTo("LEGACY_FIXED_PATH_ONLY");
        assertThat(legacy.insertionPolicy()).isEqualTo("LEGACY_ONLY");
        assertThat(legacy.enabledForGenericAdd()).isFalse();
        assertThatThrownBy(() -> profiles.add(p02)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void syntheticFixtureHasOneGeometryForEachManagementNumber() throws Exception {
        Fixture fixture = fixture(false, false, false, false);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture.bytes()))) {
            var sheet = workbook.getSheet(SHEET);
            var locator = new EquipmentBoxGeometryLocator();
            assertThat(locator.locateByManagementNo(sheet, "SRC-01")).as("source boxes").hasSize(1);
            assertThat(locator.locateByManagementNo(sheet, "DST-01")).as("target boxes").hasSize(1);
        }
    }

    @Test
    void generatesDistinctSyntheticProfileExpansionLayoutsWithManifestAndPreservedDrawing() throws Exception {
        String runId = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        java.nio.file.Path outputDirectory = java.nio.file.Path.of("validation-results",
                "2026-11-01-step7-9-profile-expansion", runId);
        java.nio.file.Files.createDirectories(outputDirectory.getParent());
        java.nio.file.Files.createDirectory(outputDirectory);
        List<String> manifest = new java.util.ArrayList<>();
        List<ExpansionLayout> layouts = List.of(
                new ExpansionLayout("COMPACT_SPLIT_UNIT", "SYNTHETIC_TEST_ONLY_SPLIT", 8, true, false),
                new ExpansionLayout("LONG_MERGED_BOX", "SYNTHETIC_TEST_ONLY_LONG", 17, false, true));

        for (ExpansionLayout layout : layouts) {
            byte[] baseline = syntheticExpansionWorkbook(layout, false);
            byte[] working = syntheticExpansionWorkbook(layout, true);
            String sourceSha = sha256(baseline);
            String workingSha = sha256(working);
            assertThat(sourceSha).isNotEqualTo(workingSha);
            EquipmentBoxProfile profile = syntheticExpansionProfile(layout, sourceSha, workingSha, working);
            assertThat(profile.evidenceLevel()).isEqualTo("SYNTHETIC_TESTED");
            assertThat(profile.enabledForGenericAdd()).isTrue();

            var beforeInventory = new OoxmlPackageInspector().inspect(working);
            assertThat(beforeInventory.shapeCount()).isEqualTo(1);
            assertThat(beforeInventory.connectorCount()).isEqualTo(1);
            assertThat(beforeInventory.sheets().get(0).drawingObjects().stream()
                    .map(WorkbookInventory.DrawingObject::shapeId)).doesNotHaveDuplicates();
            var result = engine.insertBoxOnlyTestOnly(working,
                    equipment("SYNTHETIC-NEW-" + layout.key(), "SYNTHETIC-ITEM-" + layout.key()), layout.sheetName(),
                    "SYNTHETIC-SOURCE-" + layout.key(), "SYNTHETIC-TARGET-" + layout.key(),
                    "Synthetic storage", profile, PROCESS, sourceSha);
            assertThat(result.sourceSha256()).isEqualTo(workingSha);
            assertThat(result.outputSha256()).isEqualTo(sha256(result.workbookBytes()));
            assertThat(result.mode()).isEqualTo("TEST_ONLY");
            assertThat(result.relationStatus()).isEqualTo("REVIEW_REQUIRED");
            assertThat(result.plan().connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
            assertThat(result.plan().boxHeight()).isEqualTo(layout.boxHeight());

            try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(working));
                 var workbook = WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
                var sourceSheet = sourceWorkbook.getSheet(layout.sheetName());
                var sheet = workbook.getSheet(layout.sheetName());
                assertExistingCellsAndDimensionsPreserved(sourceSheet, sheet,
                        result.plan().firstCandidateRow() - 1, result.plan().insertedRows());
                var formatter = new DataFormatter(java.util.Locale.ROOT);
                Map<String, String> expected = Map.ofEntries(
                        Map.entry("managementNo", "SYNTHETIC-NEW-" + layout.key()),
                        Map.entry("equipmentTypeLabel", "Synthetic storage"),
                        Map.entry("equipmentName", "Profile Tank"),
                        Map.entry("businessItemNo", "SYNTHETIC-ITEM-" + layout.key()),
                        Map.entry("capacity", layout.splitCapacity() ? "125" : "125m3/h"),
                        Map.entry("quantity", "1ea"));
                for (var field : profile.fields().entrySet()) {
                    var mapping = field.getValue();
                    int row = result.plan().firstCandidateRow() - 1 + mapping.rowOffset();
                    int column = result.plan().firstColumn() + mapping.columnOffset();
                    var cell = sheet.getRow(row).getCell(column);
                    if (expected.containsKey(field.getKey())) {
                        assertThat(formatter.formatCellValue(cell)).as(layout.key() + " " + field.getKey())
                                .isEqualTo(expected.get(field.getKey()));
                    }
                    assertThat((int) cell.getCellStyle().getIndex()).as(layout.key() + " copied style " + field.getKey())
                            .isEqualTo(mapping.expectedStyleId());
                    var font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                    assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                    assertThat(font.getFontHeightInPoints()).isEqualTo((short) 7);
                    assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                }
                if (layout.splitCapacity()) {
                    var unit = profile.fields().get("capacityUnit");
                    assertThat(formatter.formatCellValue(sheet.getRow(result.plan().firstCandidateRow() - 1 + unit.rowOffset())
                            .getCell(result.plan().firstColumn() + unit.columnOffset()))).isEqualTo("m3/h");
                }
                assertThat(formatter.formatCellValue(sheet.getRow(layout.boxHeight() + 4 + result.plan().insertedRows())
                        .getCell(result.plan().firstColumn()))).isEqualTo("SYNTHETIC-TARGET-" + layout.key());
                assertThat(formatter.formatCellValue(sheet.getRow(74 + result.plan().insertedRows()).getCell(0))).isEqualTo("SYNTHETIC_WORKING_COPY_DELTA");
                for (int offset = 0; offset < layout.boxHeight(); offset++) {
                    assertThat(sheet.getRow(result.plan().firstCandidateRow() - 1 + offset).getHeightInPoints())
                            .as(layout.key() + " inserted row height " + offset).isEqualTo(12f);
                }
                for (int column = 9; column <= 26; column++) {
                    String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column);
                    double width = switch (letter) {
                        case "J", "K", "X" -> 1.375d;
                        case "W", "AA" -> 0.875d;
                        default -> 13d;
                    };
                    assertThat(sheet.getColumnWidth(column)).as(layout.key() + " preserved column width " + letter)
                            .isEqualTo((int) Math.round(width * 256d));
                }
                assertThat(workbook.getPrintArea(workbook.getSheetIndex(layout.sheetName()))).contains("AN");
                assertThat(sheet.getPrintSetup().getLandscape()).isTrue();
                assertThat(sheet.getPrintSetup().getPaperSize()).isEqualTo(org.apache.poi.ss.usermodel.PrintSetup.LETTER_PAPERSIZE);
                assertThat(sheet.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
                assertThat(sheet.getPrintSetup().getFitHeight()).isEqualTo((short) 0);
                assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin)).isEqualTo(0.4d);
                assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin)).isEqualTo(0.4d);
                assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin)).isEqualTo(0.5d);
                assertThat(sheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin)).isEqualTo(0.5d);
                assertThat(sheet.getNumMergedRegions()).isEqualTo(layout.mergedTemplate() ? 3 : 0);
            }
            var afterInventory = new OoxmlPackageInspector().inspect(result.workbookBytes());
            assertThat(afterInventory.shapeCount()).isEqualTo(beforeInventory.shapeCount());
            assertThat(afterInventory.connectorCount()).isEqualTo(beforeInventory.connectorCount());
            var oldObjects = beforeInventory.sheets().get(0).drawingObjects();
            var newObjects = afterInventory.sheets().get(0).drawingObjects();
            assertThat(newObjects).hasSameSizeAs(oldObjects);
            assertThat(newObjects.stream().map(WorkbookInventory.DrawingObject::shapeId)).doesNotHaveDuplicates();
            for (var oldObject : oldObjects) {
                var newObject = newObjects.stream().filter(candidate -> candidate.shapeId().equals(oldObject.shapeId()))
                        .findFirst().orElseThrow();
                assertThat(newObject.objectType()).isEqualTo(oldObject.objectType());
                assertThat(newObject.objectName()).isEqualTo(oldObject.objectName());
                assertThat(newObject.startConnectedShapeId()).isEqualTo(oldObject.startConnectedShapeId());
                assertThat(newObject.endConnectedShapeId()).isEqualTo(oldObject.endConnectedShapeId());
                assertThat(newObject.textContent()).isEqualTo(oldObject.textContent());
                assertThat(newObject.geometryPreset()).isEqualTo(oldObject.geometryPreset());
                assertThat(newObject.startColumn()).isEqualTo(oldObject.startColumn());
                assertThat(newObject.endColumn()).isEqualTo(oldObject.endColumn());
                assertThat(newObject.startColumnOffset()).isEqualTo(oldObject.startColumnOffset());
                assertThat(newObject.endColumnOffset()).isEqualTo(oldObject.endColumnOffset());
                assertThat(newObject.headArrowType()).isEqualTo(oldObject.headArrowType());
                assertThat(newObject.tailArrowType()).isEqualTo(oldObject.tailArrowType());
                assertThat(newObject.transformXEmu()).isEqualTo(oldObject.transformXEmu());
                assertThat(newObject.transformWidthEmu()).isEqualTo(oldObject.transformWidthEmu());
                assertThat(newObject.transformHeightEmu()).isEqualTo(oldObject.transformHeightEmu());
                assertThat(newObject.rotation60000()).isEqualTo(oldObject.rotation60000());
                assertThat(newObject.flipHorizontal()).isEqualTo(oldObject.flipHorizontal());
                assertThat(newObject.flipVertical()).isEqualTo(oldObject.flipVertical());
                assertThat(newObject.startRow()).isEqualTo(oldObject.startRow() + result.plan().insertedRows());
                assertThat(newObject.endRow()).isEqualTo(oldObject.endRow() + result.plan().insertedRows());
            }
            assertThat(readPackage(result.workbookBytes()).keySet()).containsExactlyInAnyOrderElementsOf(readPackage(working).keySet());
            writeSyntheticExpansionArtifact(outputDirectory, manifest,
                    "SYNTHETIC_TEST_ONLY_" + layout.key() + "_BASELINE.xlsx", baseline, "SYNTHETIC_BASELINE_NOT_OFFICIAL");
            writeSyntheticExpansionArtifact(outputDirectory, manifest,
                    "SYNTHETIC_TEST_ONLY_" + layout.key() + "_USER_WORKING_COPY.xlsx", working, "SYNTHETIC_USER_WORKING_COPY");
            writeSyntheticExpansionArtifact(outputDirectory, manifest,
                    "SYNTHETIC_TEST_ONLY_" + layout.key() + "_ADD_OUTPUT.xlsx", result.workbookBytes(), "TEST_ONLY_REVIEW_REQUIRED");
        }
        String manifestText = "artifact\tsha256\tbytes\tclassification\n" + String.join("\n", manifest) + "\n";
        java.nio.file.Files.writeString(outputDirectory.resolve("SHA256SUMS.tsv"), manifestText,
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("STEP79_SYNTHETIC_ARTIFACT_DIR=" + outputDirectory);
    }

    private void writeSyntheticExpansionArtifact(java.nio.file.Path directory, List<String> manifest,
                                                String filename, byte[] bytes, String classification) throws Exception {
        java.nio.file.Path path = directory.resolve(filename);
        java.nio.file.Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        manifest.add(filename + "\t" + sha256(bytes) + "\t" + bytes.length + "\t" + classification);
    }

    private byte[] syntheticExpansionWorkbook(ExpansionLayout layout, boolean workingCopy) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(layout.sheetName());
            for (int column = 9; column <= 26; column++) {
                String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column);
                double width = switch (letter) {
                    case "J", "K", "X" -> 1.375d;
                    case "W", "AA" -> 0.875d;
                    default -> 13d;
                };
                sheet.setColumnWidth(column, (int) Math.round(width * 256d));
            }
            var font = workbook.createFont(); font.setFontName("맑은 고딕");
            font.setFontHeightInPoints((short) 7); font.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var base = workbook.createCellStyle(); base.setFont(font); base.setBorderLeft(BorderStyle.THIN);
            base.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            base.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var right = workbook.createCellStyle(); right.cloneStyleFrom(base); right.setBorderRight(BorderStyle.THIN);
            var bottom = workbook.createCellStyle(); bottom.cloneStyleFrom(base); bottom.setBorderBottom(BorderStyle.THIN);
            int targetTop = layout.boxHeight() + 4;
            writeExpansionBox(sheet, 0, layout, "SYNTHETIC-SOURCE-" + layout.key(), base, right, bottom);
            writeExpansionBox(sheet, targetTop, layout, "SYNTHETIC-TARGET-" + layout.key(), base, right, bottom);
            for (int row = layout.boxHeight(); row < targetTop; row++) {
                var gap = sheet.createRow(row);
                for (int col = 9; col <= 26; col++) gap.createCell(col).setCellStyle(base);
            }
            if (layout.mergedTemplate()) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 12, 13));
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(targetTop + 1, targetTop + 1, 12, 13));
            }
            sheet.createRow(74).createCell(0).setCellValue(workingCopy
                    ? "SYNTHETIC_WORKING_COPY_DELTA" : "SYNTHETIC_BASELINE_MARKER");
            int drawingRow = targetTop + layout.boxHeight() + 8;
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            var shape = drawing.createTextbox(drawing.createAnchor(0, 0, 0, 0, 30, drawingRow, 32, drawingRow + 2));
            shape.setText(new org.apache.poi.xssf.usermodel.XSSFRichTextString("Synthetic TEST_ONLY note"));
            var connector = drawing.createConnector(drawing.createAnchor(0, 0, 0, 0, 34, drawingRow, 36, drawingRow + 2));
            connector.getCTConnector().getNvCxnSpPr().getCNvPr().setId(2L);
            connector.getCTConnector().getNvCxnSpPr().getCNvPr().setName("Synthetic connector 2");
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.LETTER_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.4d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.4d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin, 0.5d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin, 0.5d);
            workbook.setPrintArea(0, "A1:AN80");
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create a separate synthetic profile layout", exception);
        }
    }

    private void writeExpansionBox(org.apache.poi.ss.usermodel.Sheet sheet, int top, ExpansionLayout layout,
                                   String id, org.apache.poi.ss.usermodel.CellStyle base,
                                   org.apache.poi.ss.usermodel.CellStyle right, org.apache.poi.ss.usermodel.CellStyle bottom) {
        for (int offset = 0; offset < layout.boxHeight(); offset++) {
            var row = sheet.getRow(top + offset);
            if (row == null) row = sheet.createRow(top + offset);
            for (int col = 9; col <= 26; col++) row.createCell(col).setCellStyle(offset == 0 && col == 26 ? right
                    : offset == layout.boxHeight() - 1 ? bottom : base);
            row.setHeightInPoints(12f);
        }
        sheet.getRow(top).getCell(9).setCellValue(id);
        sheet.getRow(top + 2).getCell(9).setCellValue("Synthetic type");
        sheet.getRow(top + 3).getCell(9).setCellValue("Synthetic equipment " + layout.key());
        if (layout.splitCapacity()) {
            sheet.getRow(top + 4).getCell(9).setCellValue(id + "-ITEM");
            sheet.getRow(top + 5).getCell(11).setCellValue("125");
            sheet.getRow(top + 5).getCell(12).setCellValue("m3/h");
        } else {
            sheet.getRow(top + 4).getCell(9).setCellValue(id + "-ITEM");
            sheet.getRow(top + 5).getCell(9).setCellValue("125m3/h");
        }
        sheet.getRow(top + 6).getCell(9).setCellValue("1ea");
    }

    private EquipmentBoxProfile syntheticExpansionProfile(ExpansionLayout layout, String sourceSha,
                                                          String workingSha, byte[] workingBytes) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", new EquipmentBoxProfile.FieldCell(0, 0, 1, "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        fields.put("equipmentTypeLabel", new EquipmentBoxProfile.FieldCell(2, 0, 1, "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        fields.put("equipmentName", new EquipmentBoxProfile.FieldCell(3, 0, 1, "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        fields.put("businessItemNo", new EquipmentBoxProfile.FieldCell(4, 0, 1, "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        fields.put("capacity", new EquipmentBoxProfile.FieldCell(5, layout.splitCapacity() ? 2 : 0, 1,
                "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT_WITH_UNIT"));
        if (layout.splitCapacity()) fields.put("capacityUnit", new EquipmentBoxProfile.FieldCell(5, 3, 1,
                "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        fields.put("quantity", new EquipmentBoxProfile.FieldCell(6, 0, 1, "맑은 고딕", (short) 7, "DEFAULT_BLACK", true, "TEXT"));
        Map<Integer, Float> heights = new LinkedHashMap<>();
        for (int offset = 0; offset < layout.boxHeight(); offset++) heights.put(offset, 12f);
        Map<String, Double> widths = new LinkedHashMap<>();
        for (int column = 9; column <= 26; column++) {
            String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column);
            widths.put(letter, switch (letter) { case "J", "K", "X" -> 1.375d; case "W", "AA" -> 0.875d; default -> 13d; });
        }
        List<String> merges = layout.mergedTemplate() ? List.of("M2:N2") : List.of();
        Map<String, Integer> styles = new LinkedHashMap<>();
        fields.forEach((name, field) -> styles.put(name, field.expectedStyleId()));
        return new EquipmentBoxProfile("SYNTHETIC-TEST-PROFILE-" + layout.key(), PROCESS, layout.sheetName(),
                "SYNTHETIC_TEST_ONLY_BASELINE.xlsx", sourceSha, sha256(workingBytes), "0.1.0", "VERIFIED",
                "SYNTHETIC_TESTED", "Synthetic-only relative profile; not a production or official-workbook mapping.",
                "J1:AA" + layout.boxHeight(), layout.boxHeight(), fields, merges, styles,
                "맑은 고딕", (short) 7, "DEFAULT_BLACK", heights, widths,
                "REVALIDATE_ANCHORS_ON_EACH_REQUEST", "PRESERVE_ONLY_NO_NEW_CONNECTOR",
                "BOX_ONLY_TEST_ONLY", "'" + layout.sheetName() + "'!$A$1:$AN$80",
                "PRESERVE_AND_SHIFT_AFTER_INSERTION_BOUNDARY", null);
    }

    private record ExpansionLayout(String key, String sheetName, int boxHeight, boolean splitCapacity,
                                  boolean mergedTemplate) { }

    private EquipmentBoxProfile copyProfile(EquipmentBoxProfile p,
                                             Map<String, EquipmentBoxProfile.FieldCell> fields,
                                             List<String> merges) {
        return new EquipmentBoxProfile(p.profileId(), p.processCode(), p.sheetName(), p.sourceFilename(),
                p.sourceSha256(), p.verifiedWorkingSha256(), p.profileVersion(), p.status(), p.evidenceLevel(), p.evidence(),
                p.templateRange(), p.boxHeight(), fields, merges, p.expectedStyleIds(), p.fontName(), p.fontSizePt(),
                p.fontColor(), p.rowHeightsPt(), p.columnWidths(), p.drawingPolicy(), p.connectorPolicy(),
                p.insertionPolicy(), p.printArea(), p.pageBreakPolicy(), p.blocker());
    }

    private EquipmentBoxProfile profileWithNote(Fixture fixture) {
        EquipmentBoxProfile base = profile(fixture, false, null);
        var fields = new LinkedHashMap<>(base.fields());
        fields.put("note", new EquipmentBoxProfile.FieldCell(2, 2, fixture.styleIds().get("note"),
                "맑은 고딕", (short) 8, "DEFAULT_BLACK", false, "TEXT"));
        return copyProfile(base, fields, base.mergedRanges());
    }

    private EquipmentBoxProfile profile(Fixture fixture, boolean merged, String blocker) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", map(0, 0, fixture.styleIds().get("managementNo")));
        fields.put("equipmentTypeLabel", map(2, 1, fixture.styleIds().get("equipmentTypeLabel")));
        switch (fixture.layout()) {
            case ID_NAME_SEPARATE_ROWS -> {
                fields.put("equipmentName", map(4, 1, fixture.styleIds().get("equipmentName")));
                fields.put("businessItemNo", map(5, 3, fixture.styleIds().get("businessItemNo")));
                fields.put("capacity", map(6, 2, fixture.styleIds().get("capacity")));
                fields.put("quantity", map(7, 1, fixture.styleIds().get("quantity")));
            }
            case ITEM_NO_SEPARATE_AREA -> {
                fields.put("equipmentName", map(3, 1, fixture.styleIds().get("equipmentName")));
                fields.put("businessItemNo", map(2, 4, fixture.styleIds().get("businessItemNo")));
                fields.put("capacity", map(5, 2, fixture.styleIds().get("capacity")));
                fields.put("quantity", map(6, 1, fixture.styleIds().get("quantity")));
            }
            default -> {
                fields.put("equipmentName", map(3, 1, fixture.styleIds().get("equipmentName")));
                fields.put("businessItemNo", map(4, 3, fixture.styleIds().get("businessItemNo")));
                fields.put("capacity", map(5, 2, fixture.styleIds().get("capacity")));
                fields.put("quantity", map(6, 1, fixture.styleIds().get("quantity")));
            }
        }
        if (fixture.layout() == Layout.CAPACITY_UNIT_SPLIT) fields.put("capacityUnit", map(5, 3, fixture.styleIds().get("capacityUnit")));
        Map<Integer, Float> heights = new LinkedHashMap<>();
        for (int offset = 0; offset < 8; offset++) heights.put(offset, fixture.rowHeights().get(offset));
        return new EquipmentBoxProfile("SYNTH-PROFILE-1", PROCESS, SHEET, "synthetic.xlsx", SOURCE_SHA,
                sha256(fixture.bytes()), "1.0.0", "VERIFIED", "SYNTHETIC_TESTED", "Synthetic fixture exact cell/style/font evidence",
                "A1:F8", 8, fields, merged ? List.of("D2:E2") : List.of(), fixture.styleIds(),
                "맑은 고딕", (short) 8, "DEFAULT_BLACK", heights, Map.of(), "REVALIDATE_ANCHORS",
                "PRESERVE_ONLY_NO_NEW_CONNECTOR", "BOX_ONLY_TEST_ONLY", "'Layout'!$A$1:$H$40",
                "PRESERVE_AND_SHIFT_AFTER_INSERTION", blocker);
    }

    private EquipmentBoxProfile.FieldCell map(int row, int column, int style) {
        return new EquipmentBoxProfile.FieldCell(row, column, style, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", true, "TEXT");
    }

    private Fixture fixture(boolean merged, boolean includeAllFields, boolean adjacentShape,
                            boolean crossingConnector) {
        return fixture(Layout.STANDARD, merged, adjacentShape, crossingConnector);
    }

    private Fixture fixture(Layout layout, boolean merged, boolean adjacentShape, boolean crossingConnector) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET);
            for (int column = 0; column < 8; column++) sheet.setColumnWidth(column, (10 + column) * 256);
            var font = workbook.createFont();
            font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 8); font.setColor((short) 8);
            var textStyle = workbook.createCellStyle(); textStyle.setFont(font);
            textStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            textStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            textStyle.setBorderLeft(BorderStyle.THIN);
            textStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            textStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var rightStyle = workbook.createCellStyle(); rightStyle.cloneStyleFrom(textStyle); rightStyle.setBorderRight(BorderStyle.THIN);
            var bottomStyle = workbook.createCellStyle(); bottomStyle.cloneStyleFrom(textStyle); bottomStyle.setBorderBottom(BorderStyle.THIN);
            Map<String, Integer> styles = new LinkedHashMap<>();
            int quantityStyle = layout == Layout.ID_NAME_SEPARATE_ROWS ? (int) bottomStyle.getIndex() : (int) textStyle.getIndex();
            styles.put("managementNo", (int) textStyle.getIndex());
            styles.put("equipmentTypeLabel", (int) textStyle.getIndex());
            styles.put("equipmentName", (int) textStyle.getIndex());
            styles.put("businessItemNo", (int) textStyle.getIndex());
            styles.put("capacity", (int) textStyle.getIndex());
            styles.put("capacityUnit", (int) textStyle.getIndex());
            styles.put("quantity", quantityStyle);
            styles.put("note", (int) textStyle.getIndex());
            writeBox(sheet, 0, "SRC-01", layout, textStyle, rightStyle, bottomStyle);
            sheet.createRow(8).createCell(0).setCellStyle(textStyle);
            sheet.createRow(9).createCell(0).setCellStyle(textStyle);
            writeBox(sheet, 10, "DST-01", layout, textStyle, rightStyle, bottomStyle);
            List<Float> sourceHeights = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                float sourceHeight = layout == Layout.VARIABLE_ROW_HEIGHTS ? 10f + i * 2f : 12f + i;
                float targetHeight = layout == Layout.VARIABLE_ROW_HEIGHTS ? 19f - i : 12f + i;
                sheet.getRow(i).setHeightInPoints(sourceHeight);
                sheet.getRow(10 + i).setHeightInPoints(targetHeight);
                sourceHeights.add(sourceHeight);
            }
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.LETTER_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.35d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.35d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin, 0.5d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin, 0.5d);
            if (merged) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 3, 4));
            }
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            if (adjacentShape) {
                XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 6, 10, 8, 12);
                var shape = drawing.createTextbox(anchor);
                shape.setText(new org.apache.poi.xssf.usermodel.XSSFRichTextString("Adjacent annotation"));
            }
            if (crossingConnector) {
                XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 0, 9, 8, 12);
                drawing.createConnector(anchor);
            }
            workbook.setPrintArea(0, "A1:H40");
            workbook.write(output);
            return new Fixture(output.toByteArray(), styles, layout, List.copyOf(sourceHeights));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void writeBox(org.apache.poi.ss.usermodel.Sheet sheet, int top, String id, Layout layout,
                          org.apache.poi.ss.usermodel.CellStyle textStyle,
                          org.apache.poi.ss.usermodel.CellStyle rightStyle,
                          org.apache.poi.ss.usermodel.CellStyle bottomStyle) {
        for (int rowOffset = 0; rowOffset < 8; rowOffset++) {
            var row = sheet.createRow(top + rowOffset);
            for (int column = 0; column < 6; column++) row.createCell(column).setCellStyle(textStyle);
        }
        sheet.getRow(top).getCell(0).setCellValue(id);
        sheet.getRow(top).getCell(5).setCellStyle(rightStyle);
        sheet.getRow(top + 2).getCell(1).setCellValue("Filter");
        switch (layout) {
            case ID_NAME_SEPARATE_ROWS -> {
                sheet.getRow(top + 4).getCell(1).setCellValue("Tank");
                sheet.getRow(top + 5).getCell(3).setCellValue("ITEM-01");
                sheet.getRow(top + 6).getCell(2).setCellValue("125m3/h");
                sheet.getRow(top + 7).getCell(1).setCellValue("1ea");
            }
            case ITEM_NO_SEPARATE_AREA -> {
                sheet.getRow(top + 3).getCell(1).setCellValue("Tank");
                sheet.getRow(top + 2).getCell(4).setCellValue("ITEM-01");
                sheet.getRow(top + 5).getCell(2).setCellValue("125m3/h");
                sheet.getRow(top + 6).getCell(1).setCellValue("1ea");
            }
            case CAPACITY_UNIT_SPLIT -> {
                sheet.getRow(top + 3).getCell(1).setCellValue("Tank");
                sheet.getRow(top + 4).getCell(3).setCellValue("ITEM-01");
                sheet.getRow(top + 5).getCell(2).setCellValue("125");
                sheet.getRow(top + 5).getCell(3).setCellValue("m3/h");
                sheet.getRow(top + 6).getCell(1).setCellValue("1ea");
            }
            default -> {
                sheet.getRow(top + 3).getCell(1).setCellValue("Tank");
                sheet.getRow(top + 4).getCell(3).setCellValue("ITEM-01");
                sheet.getRow(top + 5).getCell(2).setCellValue("125m3/h");
                sheet.getRow(top + 6).getCell(1).setCellValue("1ea");
            }
        }
        for (int column = 0; column < 6; column++) sheet.getRow(top + 7).getCell(column).setCellStyle(bottomStyle);
    }

    private MasterEquipment equipment() { return equipment("NEW-01", "NEW-ITEM-01"); }

    private MasterEquipment equipment(String managementNo, String itemNo) {
        return new MasterEquipment(0, 0, managementNo, itemNo, PROCESS, PROCESS, "TEST_ONLY",
                "Profile Tank", "Profile Tank", "저장시설", "125", "m3/h", "1ea",
                "", "", "", "", "", "", "", "", "", "", "", "", Map.of());
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private enum Layout { STANDARD, ID_NAME_SEPARATE_ROWS, ITEM_NO_SEPARATE_AREA, CAPACITY_UNIT_SPLIT, VARIABLE_ROW_HEIGHTS }

    private record Fixture(byte[] bytes, Map<String, Integer> styleIds, Layout layout, List<Float> rowHeights) { }
}
