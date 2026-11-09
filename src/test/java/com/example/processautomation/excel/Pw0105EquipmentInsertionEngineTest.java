package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.model.MasterEquipment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFTextBox;
import org.junit.jupiter.api.Test;

class Pw0105EquipmentInsertionEngineTest {
    private static final Path SOURCE_DIRECTORY = Path.of("data", "reference");

    @Test
    void locatesBoxesAndVisualTestConnectionThenCreatesWorkbookCopy() throws Exception {
        byte[] original = loadOriginal();
        String originalHash = sha256(original);
        var editor = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());

        var plan = editor.analyze(original, testSpec());

        assertThat(plan.source().topLeftCell()).isEqualTo("AI183");
        assertThat(plan.source().bottomRightCell()).isEqualTo("AZ193");
        assertThat(plan.target().topLeftCell()).isEqualTo("AI198");
        assertThat(plan.target().bottomRightCell()).isEqualTo("AZ206");
        assertThat(plan.existingConnector().shapeId()).isEqualTo("90");
        assertThat(plan.existingConnector().arrowAtEnd()).isEqualTo("triangle");
        assertThat(plan.existingConnector().hasShapeBindings()).isFalse();
        assertThat(plan.relationConfirmed()).isFalse();
        assertThat(plan.scenarioStatus()).isEqualTo("TEST_ONLY");

        var edit = editor.insertTestOnly(original, sampleEquipment("C-PW15020"), testSpec());
        byte[] editedBytes = edit.workbookBytes();
        Map<String, byte[]> originalParts = readParts(original);
        Map<String, byte[]> editedParts = readParts(editedBytes);
        assertThat(editedParts.keySet()).containsExactlyInAnyOrderElementsOf(originalParts.keySet());
        var allowedChangedParts = java.util.Set.of("xl/workbook.xml", "xl/worksheets/sheet5.xml",
                "xl/drawings/drawing5.xml");
        originalParts.forEach((name, bytes) -> {
            if (!allowedChangedParts.contains(name)) assertThat(editedParts.get(name)).as("untouched OOXML part %s", name).containsExactly(bytes);
        });
        var inspector = new OoxmlPackageInspector();
        var beforePage = inspector.inspect(original).sheets().stream().filter(s -> s.sheetName().equals("PW-01-05")).findFirst().orElseThrow();
        var afterPage = inspector.inspect(editedBytes).sheets().stream().filter(s -> s.sheetName().equals("PW-01-05")).findFirst().orElseThrow();
        assertThat(afterPage.drawingObjects().stream().map(o -> o.shapeId()).toList())
                .containsAll(beforePage.drawingObjects().stream().map(o -> o.shapeId()).toList());
        beforePage.drawingObjects().forEach(before -> {
            var after = afterPage.drawingObjects().stream()
                    .filter(candidate -> before.shapeId().equals(candidate.shapeId())).findFirst().orElseThrow();
            assertThat(after.objectType()).as("shape type preserved for id %s", before.shapeId()).isEqualTo(before.objectType());
            assertThat(after.objectName()).as("shape name preserved for id %s", before.shapeId()).isEqualTo(before.objectName());
            assertThat(after.geometryPreset()).as("geometry preserved for id %s", before.shapeId()).isEqualTo(before.geometryPreset());
            assertThat(after.headArrowType()).as("head arrow preserved for id %s", before.shapeId()).isEqualTo(before.headArrowType());
            assertThat(after.tailArrowType()).as("tail arrow preserved for id %s", before.shapeId()).isEqualTo(before.tailArrowType());
            assertThat(after.startConnectedShapeId()).as("start binding preserved for id %s", before.shapeId())
                    .isEqualTo(before.startConnectedShapeId());
            assertThat(after.endConnectedShapeId()).as("end binding preserved for id %s", before.shapeId())
                    .isEqualTo(before.endConnectedShapeId());
        });
        long originalConnectors = beforePage.drawingObjects().stream().filter(o -> "CONNECTOR".equals(o.objectType())).count();
        assertThat(afterPage.drawingObjects().stream().filter(o -> "CONNECTOR".equals(o.objectType())).count())
                .isEqualTo(originalConnectors + 1);
        assertThat(afterPage.drawingObjects().stream().map(WorkbookInventory.DrawingObject::shapeId))
                .doesNotHaveDuplicates();
        assertThat(afterPage.drawingObjects().stream()
                .filter(o -> "TEST_ONLY C-PW15020 flow connector".equals(o.objectName())))
                .singleElement().satisfies(o -> assertThat(o.shapeId()).isEqualTo("160"));
        assertThat(afterPage.drawingObjects().stream().filter(o -> "TEST_ONLY C-PW15020 flow connector".equals(o.objectName())))
                .singleElement().satisfies(o -> {
                    assertThat(o.tailArrowType()).isEqualTo("triangle");
                    assertThat(o.startColumn()).isEqualTo(40);
                    assertThat(o.startRow()).isEqualTo(208);
                    assertThat(o.endColumn()).isEqualTo(40);
                    assertThat(o.endRow()).isEqualTo(211);
                });
        assertThat(afterPage.drawingObjects()).noneMatch(o -> o.objectName() != null
                && (o.objectName().contains("TEST_ONLY / REVIEW_REQUIRED")
                || o.textContent().contains("TEST_ONLY / REVIEW_REQUIRED")));
        assertThat(afterPage.drawingObjects().stream().filter(o -> "90".equals(o.shapeId())))
                .singleElement().satisfies(o -> {
                    assertThat(o.startRow()).isEqualTo(193);
                    assertThat(o.endRow()).isEqualTo(197);
                    assertThat(o.tailArrowType()).isEqualTo("triangle");
                });
            try (var originalWorkbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(original));
             var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(editedBytes))) {

            var sheet = workbook.getSheet("PW-01-05");
            var beforeSheet = originalWorkbook.getSheet("PW-01-05");
            for (var object : beforePage.drawingObjects()) {
                assertThat(object.startAnchorXEmu()).as("source anchor X for object %s", object.shapeId())
                        .isEqualTo(expectedXEmu(beforeSheet, object.startColumn(), object.startColumnOffset()));
                assertThat(object.startAnchorYEmu()).as("source anchor Y for object %s", object.shapeId())
                        .isEqualTo(expectedYEmu(beforeSheet, object.startRow(), object.startRowOffset()));
                assertThat(object.endAnchorXEmu()).as("source end anchor X for object %s", object.shapeId())
                        .isEqualTo(expectedXEmu(beforeSheet, object.endColumn(), object.endColumnOffset()));
                assertThat(object.endAnchorYEmu()).as("source end anchor Y for object %s", object.shapeId())
                        .isEqualTo(expectedYEmu(beforeSheet, object.endRow(), object.endRowOffset()));
            }
            for (var object : afterPage.drawingObjects()) {
                assertThat(object.startAnchorXEmu()).as("edited anchor X for object %s", object.shapeId())
                        .isEqualTo(expectedXEmu(sheet, object.startColumn(), object.startColumnOffset()));
                assertThat(object.startAnchorYEmu()).as("edited anchor Y for object %s", object.shapeId())
                        .isEqualTo(expectedYEmu(sheet, object.startRow(), object.startRowOffset()));
                assertThat(object.endAnchorXEmu()).as("edited end anchor X for object %s", object.shapeId())
                        .isEqualTo(expectedXEmu(sheet, object.endColumn(), object.endColumnOffset()));
                assertThat(object.endAnchorYEmu()).as("edited end anchor Y for object %s", object.shapeId())
                        .isEqualTo(expectedYEmu(sheet, object.endRow(), object.endRowOffset()));
            }
            assertThat(sheet.getNumMergedRegions()).isEqualTo(beforeSheet.getNumMergedRegions());
            assertThat(sheet.getMergedRegions()).noneMatch(range -> "AV198:AZ198".equals(range.formatAsString()));
            assertThat(sheet.getMergedRegions()).noneMatch(range -> "AI198:AU198".equals(range.formatAsString()));
            assertThat(sheet.getMergedRegions()).noneMatch(range -> "AI200:AU204".equals(range.formatAsString()));
            for (int row = 0; row < 11; row++) {
                for (int col = 34; col <= 51; col++) {
                    var insertedCell = sheet.getRow(197 + row).getCell(col);
                    var sourceCell = beforeSheet.getRow(182 + row).getCell(col);
                    assertThat(insertedCell.getCellStyle().getIndex())
                            .as("cloned source-box cell style rowOffset=%s col=%s", row, col)
                            .isEqualTo(sourceCell.getCellStyle().getIndex());
                }
            }
            for (int rowIndex = 197; rowIndex <= 210; rowIndex++) {
                var insertedRow = sheet.getRow(rowIndex);
                assertThat(insertedRow.cellIterator()).toIterable()
                        .as("only equipment-box cells and existing filled bands are cloned into row %s", rowIndex + 1)
                        .allSatisfy(cell -> {
                            if (cell.getColumnIndex() < 34 || cell.getColumnIndex() > 51) {
                                assertThat(cell.getCellStyle().getFillPattern())
                                        .isNotEqualTo(org.apache.poi.ss.usermodel.FillPatternType.NO_FILL);
                            }
                        });
            }
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(34))).isEqualTo("C-PW15020");
            assertThat(formatter.formatCellValue(sheet.getRow(199).getCell(34))).isEqualTo("저장시설");
            var typeCell = sheet.getRow(199).getCell(34);
            var sourceTypeCell = beforeSheet.getRow(184).getCell(34);
            assertThat(typeCell.getCellStyle().getIndex()).isEqualTo(sourceTypeCell.getCellStyle().getIndex());
            var typeFont = workbook.getFontAt(typeCell.getCellStyle().getFontIndexAsInt());
            assertThat(typeFont.getFontName()).isEqualTo("맑은 고딕");
            assertThat(typeFont.getFontHeightInPoints()).isEqualTo((short) 7);
            assertThat(typeCell.getCellStyle().getAlignment()).isEqualTo(sourceTypeCell.getCellStyle().getAlignment());
            assertThat(formatter.formatCellValue(sheet.getRow(200).getCell(34))).isEqualTo("SCREEN FILTER");
            assertThat(formatter.formatCellValue(sheet.getRow(201).getCell(34))).isEqualTo("WW-F-AFT10");
            assertThat(formatter.formatCellValue(sheet.getRow(202).getCell(34))).isEqualTo("1,500㎥/시");
            assertThat(formatter.formatCellValue(sheet.getRow(203).getCell(34))).isEqualTo("1ea");
            assertThat(formatter.formatCellValue(sheet.getRow(199).getCell(47))).isEqualTo("폐수");
            assertThat(formatter.formatCellValue(sheet.getRow(205).getCell(47))).isEqualTo("폐기물");
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(47))).isEqualTo(" ");
            assertThat(formatter.formatCellValue(sheet.getRow(196).getCell(41)))
                    .isEqualTo(formatter.formatCellValue(beforeSheet.getRow(196).getCell(41)))
                    .isEqualTo("일반폐수 11,818.58 톤/일");
            for (int rowIndex : new int[]{198, 200, 201, 202, 203, 204, 206, 207}) {
                assertThat(formatter.formatCellValue(sheet.getRow(rowIndex).getCell(47)))
                        .as("source-style blank placeholder is retained without copying legacy waste data")
                        .isEqualTo(" ");
            }
            assertThat(formatter.formatCellValue(sheet.getRow(200).getCell(47))).isNotEqualTo("WT-310509");
            assertThat(formatter.formatCellValue(sheet.getRow(206).getCell(47))).isNotEqualTo("Ws-310506");
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getWrapText()).isFalse();
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getShrinkToFit()).isFalse();
            assertThat(sheet.getRow(197).getHeightInPoints()).isEqualTo(beforeSheet.getRow(182).getHeightInPoints());
            assertThat(sheet.getRow(197).getCell(47).getCellStyle().getAlignment())
                    .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            assertThat(sheet.getRow(197).getCell(47).getCellStyle().getVerticalAlignment())
                    .isEqualTo(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getBorderLeft())
                    .isEqualTo(beforeSheet.getRow(182).getCell(34).getCellStyle().getBorderLeft());
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getBorderTop())
                    .isEqualTo(beforeSheet.getRow(182).getCell(34).getCellStyle().getBorderTop());
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getBorderBottom())
                    .isEqualTo(beforeSheet.getRow(182).getCell(34).getCellStyle().getBorderBottom());
            for (int rowIndex = 198; rowIndex <= 207; rowIndex++) {
                assertThat(sheet.getRow(rowIndex).getCell(34).getCellStyle().getBorderLeft())
                        .as("continuous left outer border row %s", rowIndex + 1)
                        .isEqualTo(beforeSheet.getRow(rowIndex - 15).getCell(34).getCellStyle().getBorderLeft());
                assertThat(sheet.getRow(rowIndex).getCell(47).getCellStyle().getBorderLeft())
                        .as("continuous wastewater/waste divider row %s", rowIndex + 1)
                        .isEqualTo(beforeSheet.getRow(rowIndex - 15).getCell(47).getCellStyle().getBorderLeft());
                assertThat(sheet.getRow(rowIndex).getCell(51).getCellStyle().getBorderRight())
                        .as("continuous right outer border row %s", rowIndex + 1)
                        .isEqualTo(beforeSheet.getRow(rowIndex - 15).getCell(51).getCellStyle().getBorderRight());
            }
            assertThat(sheet.getRow(204).getCell(47).getCellStyle().getBorderTop())
                    .isEqualTo(beforeSheet.getRow(189).getCell(47).getCellStyle().getBorderTop());
            assertThat(sheet.getRow(207).getCell(34).getCellStyle().getBorderBottom())
                    .isEqualTo(beforeSheet.getRow(192).getCell(34).getCellStyle().getBorderBottom());
            assertThat(formatter.formatCellValue(sheet.getRow(193).getCell(58))).isEqualTo("대기배출");
            for (int rowIndex = 208; rowIndex <= 210; rowIndex++) {
                var strayLabel = sheet.getRow(rowIndex).getCell(58);
                assertThat(strayLabel == null ? "" : formatter.formatCellValue(strayLabel)).isBlank();
            }
            assertThat(formatter.formatCellValue(sheet.getRow(211).getCell(34))).isEqualTo("C-PW15010");
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getAlignment())
                    .isEqualTo(beforeSheet.getRow(182).getCell(34).getCellStyle().getAlignment());
            for (int rowIndex = 199; rowIndex <= 203; rowIndex++) {
                assertThat(sheet.getRow(rowIndex).getCell(34).getCellStyle().getAlignment())
                        .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER_SELECTION);
            }
            var candidateFont = workbook.getFontAt(sheet.getRow(197).getCell(34).getCellStyle().getFontIndexAsInt());
            var sourceIdFont = originalWorkbook.getFontAt(beforeSheet.getRow(182).getCell(34).getCellStyle().getFontIndexAsInt());
            var sourceFont = originalWorkbook.getFontAt(beforeSheet.getRow(185).getCell(34).getCellStyle().getFontIndexAsInt());
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getIndex())
                    .isEqualTo(beforeSheet.getRow(182).getCell(34).getCellStyle().getIndex());
            assertThat(candidateFont.getFontName()).isEqualTo(sourceIdFont.getFontName()).isEqualTo("맑은 고딕");
            assertThat(candidateFont.getFontHeightInPoints()).isEqualTo(sourceIdFont.getFontHeightInPoints()).isEqualTo((short) 7);
            assertThat(candidateFont.getBold()).isEqualTo(sourceIdFont.getBold()).isFalse();
            assertThat(sheet.getRow(197).getCell(34).getCellStyle().getShrinkToFit()).isFalse();
            assertThat(sheet.getRow(200).getCell(34).getCellStyle().getShrinkToFit()).isTrue();
            for (int rowIndex = 200; rowIndex <= 203; rowIndex++) {
                var bodyFont = workbook.getFontAt(sheet.getRow(rowIndex).getCell(34).getCellStyle().getFontIndexAsInt());
                assertThat(bodyFont.getIndexAsInt()).isEqualTo(sourceFont.getIndexAsInt());
                assertThat(bodyFont.getFontName()).isEqualTo("맑은 고딕");
                assertThat(bodyFont.getFontHeightInPoints()).isEqualTo((short) 7);
            }
            for (int rowIndex : new int[]{199, 205}) {
                var labelFont = workbook.getFontAt(sheet.getRow(rowIndex).getCell(47).getCellStyle().getFontIndexAsInt());
                assertThat(labelFont.getFontName()).isEqualTo("맑은 고딕");
                assertThat(labelFont.getColor()).isEqualTo(sourceFont.getColor());
                assertThat(sheet.getRow(rowIndex).getCell(47).getCellStyle().getAlignment())
                        .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER_SELECTION);
            }
            assertThat(sheet.getRow(197).getHeightInPoints()).isEqualTo(12f);
            assertThat(sheet.getRow(207).getHeightInPoints()).isEqualTo(12f);
            assertThat(sheet.getRow(211).getHeightInPoints()).isEqualTo(12f);
            assertThat(sheet.isDisplayGridlines()).isFalse();
            assertThat(workbook.getPrintArea(4)).contains("$BJ$230");
        }
        assertThat(sha256(loadOriginal())).isEqualTo(originalHash);
        assertThat(edit.scenarioStatus()).isEqualTo("TEST_ONLY");
        assertThat(edit.relationStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    private Map<String, byte[]> readParts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (InputStream input = zip.getInputStream(entry)) { parts.put(entry.getName(), input.readAllBytes()); }
            }
        }
        return parts;
    }

    private byte[] loadOriginal() throws Exception {
        try (var paths = Files.list(SOURCE_DIRECTORY)) {
            Path source = paths.filter(path -> path.getFileName().toString().startsWith("PW-01 "))
                    .filter(path -> path.getFileName().toString().endsWith(".xlsx"))
                    .findFirst().orElseThrow();
            return Files.readAllBytes(source);
        }
    }

    @Test
    void nineCharacterEquipmentNumberMatchesExistingStyleWhileOtherLabelsShrink() throws Exception {
        byte[] original = loadOriginal();
        String managementNo = "T-PW15009";
        var equipment = new MasterEquipment(0, 0, managementNo, "WW-F-AFT10", "PW-01-05", "PW-01",
                "폐수처리", "SCREEN FILTER", "SCREEN FILTER", "여과시설", "125", "m³/h", "1ea",
                "", "", "", "", "", "", "", "", "", "", "", "", Map.of());
        var engine = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());
        var result = engine.insertTestOnly(original, equipment, testSpec());
        try (var reopened = WorkbookFactory.create(new java.io.ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = reopened.getSheet("PW-01-05");
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(34))).isEqualTo(managementNo);
            assertThat(formatter.formatCellValue(sheet.getRow(200).getCell(34))).isEqualTo("SCREEN FILTER");
            assertThat(formatter.formatCellValue(sheet.getRow(201).getCell(34))).isEqualTo("WW-F-AFT10");
            assertThat(formatter.formatCellValue(sheet.getRow(202).getCell(34))).isEqualTo("125m³/h");
            assertThat(formatter.formatCellValue(sheet.getRow(203).getCell(34))).isEqualTo("1ea");
            var idCell = sheet.getRow(197).getCell(34);
            var idFont = reopened.getFontAt(idCell.getCellStyle().getFontIndexAsInt());
            assertThat(idFont.getFontHeightInPoints()).isEqualTo((short) 7);
            assertThat(idFont.getBold()).isFalse();
            assertThat(idFont.getFontName()).isEqualTo("맑은 고딕");
            assertThat(idCell.getCellStyle().getShrinkToFit()).isFalse();
            assertThat(idCell.getCellStyle().getWrapText()).isFalse();
            assertThat(sheet.getRow(197).getHeightInPoints()).isEqualTo(12.0f);
            assertThat(idCell.getCellStyle().getIndex()).isEqualTo((short) 30);
            assertThat(sheet.getMergedRegions()).noneMatch(range -> "AI198:AU198".equals(range.formatAsString()));
            for (int rowIndex : new int[]{200, 201, 202, 203}) {
                var cell = sheet.getRow(rowIndex).getCell(34);
                assertThat(cell.getCellStyle().getShrinkToFit()).isTrue();
                var font = reopened.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                assertThat(font.getFontHeightInPoints()).isEqualTo((short) 7);
                assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                assertThat(font.getFontName()).isEqualTo("맑은 고딕");
            }
            assertThat(reopened.getActiveSheetIndex()).isEqualTo(4);
            assertThat((int) sheet.getTopRow()).isEqualTo(197);
        }
        var reopenedInventory = new OoxmlPackageInspector().inspect(result.workbookBytes());
        assertThat(reopenedInventory.sheets().stream().filter(s -> s.sheetName().equals("PW-01-05"))
                .flatMap(s -> s.drawingObjects().stream())).noneMatch(object -> object.objectName() != null
                && object.objectName().contains("REVIEW_REQUIRED"));
        assertThat(sha256(loadOriginal())).isEqualTo(sha256(original));
    }

    @Test
    void createsNineCharacterTestOnlyWorkbookWithExistingEquipmentStyle() throws Exception {
        byte[] original = loadOriginal();
        String sourceHash = sha256(original);
        var engine = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());
        String managementNo = "T-PW15009";
        assertThat(managementNo).hasSize(9);
        var equipment = new MasterEquipment(0, 0, managementNo, "QA-ITEM-09", "PW-01-05", "PW-01",
                "폐수처리", "합성 분리 여과장치 A", "합성 분리 여과장치 A", "여과시설", "125", "m³/h", "1ea",
                "", "", "", "", "", "", "", "", "", "", "", "", Map.of());
        Path outputDirectory = Path.of("validation-results", "2026-10-28-equipment-style-match");
        Files.createDirectories(outputDirectory);
        var edit = engine.insertTestOnly(original, equipment, testSpec());
        try (var source = WorkbookFactory.create(new java.io.ByteArrayInputStream(original));
             var reopened = WorkbookFactory.create(new java.io.ByteArrayInputStream(edit.workbookBytes()))) {
            var sheet = reopened.getSheet("PW-01-05");
            var sourceSheet = source.getSheet("PW-01-05");
            var idCell = sheet.getRow(197).getCell(34);
            var sourceIdCell = sourceSheet.getRow(182).getCell(34);
            var sourceNextIdCell = sourceSheet.getRow(197).getCell(34);
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            for (var sourceRow : sourceSheet) for (var sourceCell : sourceRow) {
                assertThat(formatter.formatCellValue(sourceCell)).isNotEqualTo(managementNo);
            }
            long idOccurrences = 0;
            for (var outputRow : sheet) for (var outputCell : outputRow) {
                if (managementNo.equals(formatter.formatCellValue(outputCell))) idOccurrences++;
            }
            assertThat(idOccurrences).isEqualTo(1);
            assertThat(formatter.formatCellValue(idCell)).isEqualTo("T-PW15009");
            assertThat(formatter.formatCellValue(sourceIdCell)).isEqualTo("C-PW15009");
            assertThat(formatter.formatCellValue(sourceNextIdCell)).isEqualTo("C-PW15010");
            assertThat(sourceIdCell.getCellStyle().getIndex()).isEqualTo(sourceNextIdCell.getCellStyle().getIndex());
            assertThat(idCell.getCellStyle().getIndex()).isEqualTo(sourceIdCell.getCellStyle().getIndex());
            var idFont = reopened.getFontAt(idCell.getCellStyle().getFontIndexAsInt());
            var sourceFont = source.getFontAt(sourceIdCell.getCellStyle().getFontIndexAsInt());
            assertThat(idFont.getFontName()).isEqualTo(sourceFont.getFontName()).isEqualTo("맑은 고딕");
            assertThat(idFont.getFontHeightInPoints()).isEqualTo(sourceFont.getFontHeightInPoints()).isEqualTo((short) 7);
            assertThat(idFont.getBold()).isEqualTo(sourceFont.getBold()).isFalse();
            assertThat(idCell.getCellStyle().getAlignment()).isEqualTo(sourceIdCell.getCellStyle().getAlignment());
            assertThat(sheet.getRow(197).getHeightInPoints()).isEqualTo(sourceSheet.getRow(182).getHeightInPoints());
            assertThat(sheet.getRow(197).getHeightInPoints()).isEqualTo(12.0f);
            assertThat(sheet.getMergedRegions()).hasSameSizeAs(sourceSheet.getMergedRegions());
            assertThat(reopened.getActiveSheetIndex()).isEqualTo(4);
            for (int offset = 0; offset < 11; offset++) {
                for (int column = 34; column <= 51; column++) {
                    assertThat(sheet.getRow(197 + offset).getCell(column).getCellStyle().getIndex())
                            .isEqualTo(sourceSheet.getRow(182 + offset).getCell(column).getCellStyle().getIndex());
                }
            }
            assertThat(formatter.formatCellValue(sheet.getRow(200).getCell(34))).isEqualTo("합성 분리 여과장치 A");
            assertThat(formatter.formatCellValue(sheet.getRow(201).getCell(34))).isEqualTo("QA-ITEM-09");
            assertThat(formatter.formatCellValue(sheet.getRow(202).getCell(34))).isEqualTo("125m³/h");
            assertThat(formatter.formatCellValue(sheet.getRow(203).getCell(34))).isEqualTo("1ea");
        }
        Map<String, byte[]> sourceParts = readParts(original);
        Map<String, byte[]> editedParts = readParts(edit.workbookBytes());
        assertThat(editedParts.keySet()).containsExactlyInAnyOrderElementsOf(sourceParts.keySet());
        assertThat(editedParts.get("xl/styles.xml")).containsExactly(sourceParts.get("xl/styles.xml"));
        var before = new OoxmlPackageInspector().inspect(original).sheets().stream()
                .filter(item -> item.sheetName().equals("PW-01-05")).findFirst().orElseThrow();
        var after = new OoxmlPackageInspector().inspect(edit.workbookBytes()).sheets().stream()
                .filter(item -> item.sheetName().equals("PW-01-05")).findFirst().orElseThrow();
        assertThat(after.drawingObjects().stream().map(WorkbookInventory.DrawingObject::shapeId))
                .containsAll(before.drawingObjects().stream().map(WorkbookInventory.DrawingObject::shapeId).toList());
        assertThat(after.drawingObjects().stream().filter(object -> "CONNECTOR".equals(object.objectType())).count())
                .isEqualTo(before.drawingObjects().stream().filter(object -> "CONNECTOR".equals(object.objectType())).count() + 1);
        assertThat(after.drawingObjects()).noneMatch(object -> object.objectName() != null
                && (object.objectName().contains("REVIEW_REQUIRED") || object.textContent().contains("REVIEW_REQUIRED")));
        Files.write(outputDirectory.resolve("PW-01-05_T-PW15009_style-match_TEST_ONLY.xlsx"), edit.workbookBytes());
        assertThat(sha256(loadOriginal())).isEqualTo(sourceHash);
    }

    @Test
    void syntheticModifyEditsOnlySelectedFieldAndPreservesDrawingMl() throws Exception {
        byte[] source;
        try (var workbook = new XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("PW-01-05");
            var rightStyle = workbook.createCellStyle();
            rightStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            var bottomStyle = workbook.createCellStyle();
            bottomStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            var headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("SYNTH-BOX-01");
            headerRow.createCell(5).setCellStyle(rightStyle);
            var bottomRow = sheet.createRow(7);
            for (int column = 0; column <= 5; column++) {
                bottomRow.createCell(column).setCellStyle(bottomStyle);
            }
            sheet.createRow(3).createCell(0).setCellValue("Fixture Filter");
            sheet.createRow(4).createCell(0).setCellValue("FIXTURE-ITEM");
            sheet.createRow(5).createCell(0).setCellValue("100 m3/h");
            sheet.createRow(6).createCell(0).setCellValue("1 unit");
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 6, 0, 9, 2);
            XSSFTextBox note = drawing.createTextbox(anchor);
            note.setText(new XSSFRichTextString("Synthetic fixture annotation"));
            workbook.write(output);
            source = output.toByteArray();
        }
        var engine = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(source))) {
            var sheet = workbook.getSheet("PW-01-05");
            assertThat(sheet.getRow(0).getCell(5).getCellStyle().getBorderRight()).isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            for (int column = 0; column <= 5; column++) {
                assertThat(sheet.getRow(7).getCell(column).getCellStyle().getBorderBottom()).isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            }
            assertThat(engine.locateExistingBoxesByManagementNo(sheet, "SYNTH-BOX-01")).hasSize(1);
        }
        Map<String, String> expected = Map.of("equipmentName", "Fixture Filter");
        Map<String, String> replacement = Map.of("equipmentName", "TEST_ONLY Fixture Filter Updated");
        var result = engine.modifyExistingEquipmentText(source, "PW-01-05", "SYNTH-BOX-01",
                "Fixture Filter", expected, replacement);
        assertThat(result.box().topLeftCell()).isEqualTo("A1");
        assertThat(result.changedCells()).containsExactly(Map.entry("A4", "equipmentName"));
        assertThat(result.beforeValues().get("managementNo")).isEqualTo("SYNTH-BOX-01");
        assertThat(result.afterValues().get("equipmentName")).isEqualTo("TEST_ONLY Fixture Filter Updated");
        var partsBefore = readParts(source);
        var partsAfter = readParts(result.workbookBytes());
        assertThat(partsAfter.keySet()).containsExactlyInAnyOrderElementsOf(partsBefore.keySet());
        String drawingPart = result.drawingPartName();
        assertThat(partsAfter.get(drawingPart)).containsExactly(partsBefore.get(drawingPart));
        try (var before = WorkbookFactory.create(new java.io.ByteArrayInputStream(source));
             var after = WorkbookFactory.create(new java.io.ByteArrayInputStream(result.workbookBytes()))) {
            var beforeSheet = before.getSheet("PW-01-05");
            var afterSheet = after.getSheet("PW-01-05");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(afterSheet.getRow(0).getCell(0))).isEqualTo("SYNTH-BOX-01");
            assertThat(formatter.formatCellValue(afterSheet.getRow(3).getCell(0))).isEqualTo(replacement.get("equipmentName"));
            assertThat(formatter.formatCellValue(afterSheet.getRow(4).getCell(0))).isEqualTo(formatter.formatCellValue(beforeSheet.getRow(4).getCell(0)));
            assertThat(formatter.formatCellValue(afterSheet.getRow(5).getCell(0))).isEqualTo(formatter.formatCellValue(beforeSheet.getRow(5).getCell(0)));
            assertThat(formatter.formatCellValue(afterSheet.getRow(6).getCell(0))).isEqualTo(formatter.formatCellValue(beforeSheet.getRow(6).getCell(0)));
        }
        assertThat(new OoxmlPackageInspector().inspect(source).sheets().get(0).drawingObjects())
                .isEqualTo(new OoxmlPackageInspector().inspect(result.workbookBytes()).sheets().get(0).drawingObjects());
    }

    @Test
    void blankEquipmentTypeDoesNotInsertTheLegacyDefault() throws Exception {
        byte[] original = loadOriginal();
        var engine = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());
        var blankTypeSpec = new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05", "C-PW15009",
                "ACTIVATED CARBON FILTER", "C-PW15010", "FINAL EFFLUENT TANK", "");
        var result = engine.insertTestOnly(original, sampleEquipment("SYNTH-BLANK-TYPE"), blankTypeSpec);

        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet("PW-01-05");
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            assertThat(formatter.formatCellValue(sheet.getRow(199).getCell(34))).isEmpty();
            assertThat(formatter.formatCellValue(sheet.getRow(199).getCell(34))).isNotEqualTo("여과시설");
        }
    }

    @Test
    void differentManagementNumberIsInsertedFromTheRuntimeEquipmentRecord() throws Exception {
        byte[] original = loadOriginal();
        var engine = new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), new OoxmlPackageInspector());
        var otherEquipment = sampleEquipment("I-OTHER-001");

        var edit = engine.insertTestOnly(original, otherEquipment, testSpec());

        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(edit.workbookBytes()))) {
            var sheet = workbook.getSheet("PW-01-05");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(34))).isEqualTo("I-OTHER-001");
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(34))).isNotEqualTo("C-PW15020");
            assertThat(new OoxmlPackageInspector().inspect(edit.workbookBytes()).sheets().stream()
                    .filter(item -> item.sheetName().equals("PW-01-05"))
                    .flatMap(item -> item.drawingObjects().stream())
                    .map(WorkbookInventory.DrawingObject::objectName))
                    .contains("TEST_ONLY I-OTHER-001 flow connector")
                    .doesNotContain("TEST_ONLY C-PW15020 flow connector");
        }
    }

    private Pw0105EquipmentInsertionEngine.TestEditSpec testSpec() {
        return new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05", "C-PW15009",
                "ACTIVATED CARBON FILTER", "C-PW15010", "FINAL EFFLUENT TANK", "저장시설");
    }

    private MasterEquipment sampleEquipment(String managementNo) {
        return new MasterEquipment(0, 0, managementNo, "WW-F-AFT10", "PW-01-05", "PW-01",
                "폐수처리", "SCREEN FILTER", "SCREEN FILTER", "설비", "1,500", "㎥/시", "1ea",
                "", "", "", "", "", "", "", "", "", "", "", "", Map.of());
    }

    private Long expectedXEmu(org.apache.poi.ss.usermodel.Sheet sheet, Integer column, Long offset) {
        if (sheet == null || column == null || offset == null || column < 0) return null;
        long x = 0;
        for (int index = 0; index < column; index++) {
            if (!sheet.isColumnHidden(index)) x += Math.round(sheet.getColumnWidthInPixels(index) * 9525d);
        }
        return x + offset;
    }

    private Long expectedYEmu(org.apache.poi.ss.usermodel.Sheet sheet, Integer rowIndex, Long offset) {
        if (sheet == null || rowIndex == null || offset == null || rowIndex < 0) return null;
        long y = 0;
        for (int index = 0; index < rowIndex; index++) {
            var row = sheet.getRow(index);
            if (row != null && row.getZeroHeight()) continue;
            float height = row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
            y += Math.round(height * 12700d);
        }
        return y + offset;
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
