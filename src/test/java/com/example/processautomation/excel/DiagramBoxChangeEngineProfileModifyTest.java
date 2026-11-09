package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DiagramBoxChangeEngineProfileModifyTest {
    private static final String PROCESS = "SYNTH-MODIFY";
    private static final String SHEET_8 = "Synthetic-8-row";
    private static final String SHEET_17 = "Synthetic-17-row";
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
            parts, inspector, new EquipmentBoxGeometryLocator());

    @Test
    void profileModifyCreatesDistinctSyntheticOutputsAndBlocksUnsafeChanges() throws Exception {
        Path outputDirectory = Files.createTempDirectory(
                Files.createDirectories(Path.of("validation-results", "2026-11-01-step8-0-profile-modify")), "run-");
        List<String> manifest = new ArrayList<>();
        List<SuccessfulCase> successful = List.of(
                new SuccessfulCase("A_EQUIPMENT_NAME_8_ROW", 8, false, false,
                        Map.of("equipmentName", "Synthetic Updated Name")),
                new SuccessfulCase("B_ITEM_NO_8_ROW", 8, false, false,
                        Map.of("businessItemNo", "SYNTH-ITEM-UPDATED")),
                new SuccessfulCase("C_CAPACITY_ONLY_8_ROW", 8, false, false,
                        Map.of("capacity", "140")),
                new SuccessfulCase("C_UNIT_ONLY_8_ROW", 8, false, false,
                        Map.of("capacityUnit", "L/min")),
                new SuccessfulCase("C_CAPACITY_AND_UNIT_8_ROW", 8, false, false,
                        Map.of("capacity", "140", "capacityUnit", "L/min")),
                new SuccessfulCase("D_QUANTITY_8_ROW", 8, false, false,
                        Map.of("quantity", "2ea")),
                new SuccessfulCase("E_MERGED_17_ROW", 17, true, false,
                        Map.of("equipmentName", "Synthetic 17-row Updated")),
                new SuccessfulCase("G_DRAWING_NEIGHBOR_PRESERVED", 8, false, true,
                        Map.of("equipmentName", "Synthetic Drawing Neighbor")));

        for (SuccessfulCase scenario : successful) {
            Fixture fixture = fixture(scenario.boxHeight(), scenario.merged(), scenario.drawing(), false, false);
            String sourceSha = sha256(fixture.baseline());
            String workingSha = sha256(fixture.workingCopy());
            EquipmentBoxProfile profile = profile(scenario, sourceSha, workingSha);
            Map<String, String> expectedCurrent = new LinkedHashMap<>();
            scenario.replacements().keySet().forEach(field -> expectedCurrent.put(field, fixture.originalValues().get(field)));

            DiagramBoxChangeEngine.ProfileModifyResult result = engine.modifyProfileFieldsTestOnly(
                    fixture.workingCopy(), PROCESS, fixture.sheetName(), fixture.managementNo(), profile,
                    sourceSha, workingSha, expectedCurrent, scenario.replacements());
            assertThat(result.change().operation()).isEqualTo("MODIFY");
            assertThat(result.change().scenarioStatus()).isEqualTo("TEST_ONLY");
            assertThat(result.change().connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
            assertThat(result.change().sourceSha256()).isEqualTo(workingSha);
            assertThat(result.change().outputSha256()).isEqualTo(sha256(result.change().workbookBytes()));
            assertThat(result.beforeValues()).isEqualTo(expectedCurrent);
            assertThat(result.afterValues()).isEqualTo(scenario.replacements());
            assertWorkbookDiffOnlyAtRequestedCells(fixture.workingCopy(), result.change().workbookBytes(),
                    fixture.sheetName(), fixture.fieldCells(), scenario.replacements());
            assertDrawingPreserved(fixture.workingCopy(), result.change().workbookBytes(), fixture.sheetName());
            assertZipIntegrity(result.change().workbookBytes());

            String prefix = "SYNTHETIC_TEST_ONLY_" + scenario.name();
            writeArtifact(outputDirectory, manifest, prefix + "_BASELINE.xlsx", fixture.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");
            writeArtifact(outputDirectory, manifest, prefix + "_USER_WORKING_COPY.xlsx", fixture.workingCopy(), "SYNTHETIC_USER_WORKING_COPY");
            writeArtifact(outputDirectory, manifest, prefix + "_MODIFY_OUTPUT.xlsx", result.change().workbookBytes(), "TEST_ONLY_REVIEW_REQUIRED");
        }

        Fixture badStyle = fixture(8, false, false, false, false);
        String badStyleSource = sha256(badStyle.baseline());
        String badStyleWorking = sha256(badStyle.workingCopy());
        SuccessfulCase invalidFieldScenario = new SuccessfulCase("F_INVALID_PROFILE_STYLE", 8, false, false,
                Map.of("equipmentName", "Must Not Apply"));
        var wrongStyle = wrongStyle(profile(invalidFieldScenario, badStyleSource, badStyleWorking), "equipmentName");
        var reviewMapping = engine.mapFieldsByProfile(badStyle.workingCopy(), PROCESS, badStyle.sheetName(),
                badStyle.managementNo(), wrongStyle, badStyleSource, badStyleWorking);
        assertThat(reviewMapping.fields().get("equipmentName").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reviewMapping.availableFields()).doesNotContain("equipmentName");
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(badStyle.workingCopy(), PROCESS, badStyle.sheetName(),
                badStyle.managementNo(), wrongStyle, badStyleSource, badStyleWorking,
                Map.of("equipmentName", "Synthetic Unit"), invalidFieldScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_FIELD_NOT_EDITABLE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_INVALID_PROFILE_STYLE_USER_WORKING_COPY.xlsx",
                badStyle.workingCopy(), "SYNTHETIC_NEGATIVE_REVIEW_REQUIRED");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_INVALID_PROFILE_STYLE_BASELINE.xlsx",
                badStyle.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");

        Fixture stale = fixture(8, false, false, false, false);
        String staleSource = sha256(stale.baseline());
        String staleWorking = sha256(stale.workingCopy());
        SuccessfulCase staleScenario = new SuccessfulCase("F_STALE_VALUE", 8, false, false,
                Map.of("equipmentName", "Stale replacement"));
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(stale.workingCopy(), PROCESS, stale.sheetName(),
                stale.managementNo(), profile(staleScenario, staleSource, staleWorking), staleSource, staleWorking,
                Map.of("equipmentName", "Value Seen Earlier"), staleScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("STALE_VALUE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_STALE_VALUE_USER_WORKING_COPY.xlsx",
                stale.workingCopy(), "SYNTHETIC_NEGATIVE_STALE_VALUE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_STALE_VALUE_BASELINE.xlsx",
                stale.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");

        Fixture mergedField = fixture(17, true, false, false, false);
        String mergedSource = sha256(mergedField.baseline());
        String mergedWorking = sha256(mergedField.workingCopy());
        SuccessfulCase mergedScenario = new SuccessfulCase("F_MERGED_FIELD", 17, true, false,
                Map.of("equipmentName", "Must Not Apply"));
        var mergedProfile = withFieldMapping(profile(mergedScenario, mergedSource, mergedWorking),
                "equipmentName", map(1, 3, "TEXT", true));
        var mergedMap = engine.mapFieldsByProfile(mergedField.workingCopy(), PROCESS, mergedField.sheetName(),
                mergedField.managementNo(), mergedProfile, mergedSource, mergedWorking);
        assertThat(mergedMap.fields().get("equipmentName").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(mergedMap.fields().get("equipmentName").reason()).isEqualTo("MERGED_FIELD_OVERLAP");
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(mergedField.workingCopy(), PROCESS,
                mergedField.sheetName(), mergedField.managementNo(), mergedProfile, mergedSource, mergedWorking,
                Map.of("equipmentName", "Synthetic Unit"), mergedScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_FIELD_NOT_EDITABLE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_MERGED_FIELD_USER_WORKING_COPY.xlsx",
                mergedField.workingCopy(), "SYNTHETIC_NEGATIVE_MERGED_CELL");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_MERGED_FIELD_BASELINE.xlsx",
                mergedField.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");

        Fixture formulaBase = fixture(8, false, false, false, false);
        Fixture formulaField = withFormula(formulaBase, "J4");
        String formulaSource = sha256(formulaField.baseline());
        String formulaWorking = sha256(formulaField.workingCopy());
        SuccessfulCase formulaScenario = new SuccessfulCase("F_FORMULA_FIELD", 8, false, false,
                Map.of("equipmentName", "Must Not Apply"));
        var formulaProfile = profile(formulaScenario, formulaSource, formulaWorking);
        assertThatThrownBy(() -> engine.mapFieldsByProfile(formulaField.workingCopy(), PROCESS,
                formulaField.sheetName(), formulaField.managementNo(), formulaProfile, formulaSource, formulaWorking))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("FORMULA_IN_BOX");
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(formulaField.workingCopy(), PROCESS,
                formulaField.sheetName(), formulaField.managementNo(), formulaProfile, formulaSource, formulaWorking,
                Map.of("equipmentName", "Synthetic Unit"), formulaScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("FORMULA_IN_BOX");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_FORMULA_FIELD_USER_WORKING_COPY.xlsx",
                formulaField.workingCopy(), "SYNTHETIC_NEGATIVE_FORMULA_FIELD");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_FORMULA_FIELD_BASELINE.xlsx",
                formulaField.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");

        Fixture numericBase = fixture(8, false, false, false, false);
        Fixture numericField = withNumericValue(numericBase, "J4", 42);
        String numericSource = sha256(numericField.baseline());
        String numericWorking = sha256(numericField.workingCopy());
        SuccessfulCase numericScenario = new SuccessfulCase("F_NUMERIC_TEXT_FIELD", 8, false, false,
                Map.of("equipmentName", "Must Not Apply"));
        var numericProfile = profile(numericScenario, numericSource, numericWorking);
        var numericMapping = engine.mapFieldsByProfile(numericField.workingCopy(), PROCESS, numericField.sheetName(),
                numericField.managementNo(), numericProfile, numericSource, numericWorking);
        assertThat(numericMapping.fields().get("equipmentName").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(numericMapping.fields().get("equipmentName").reason()).isEqualTo("PROFILE_CELL_TYPE_MISMATCH");
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(numericField.workingCopy(), PROCESS,
                numericField.sheetName(), numericField.managementNo(), numericProfile, numericSource, numericWorking,
                Map.of("equipmentName", "Synthetic Unit"), numericScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_FIELD_NOT_EDITABLE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_NUMERIC_TEXT_FIELD_USER_WORKING_COPY.xlsx",
                numericField.workingCopy(), "SYNTHETIC_NEGATIVE_PROFILE_TYPE_MISMATCH");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_F_NUMERIC_TEXT_FIELD_BASELINE.xlsx",
                numericField.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");

        Fixture sameValue = fixture(8, false, false, false, false);
        String sameSource = sha256(sameValue.baseline());
        String sameWorking = sha256(sameValue.workingCopy());
        var sameScenario = new SuccessfulCase("F_UNCHANGED_VALUE", 8, false, false,
                Map.of("capacityUnit", sameValue.originalValues().get("capacityUnit")));
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(sameValue.workingCopy(), PROCESS,
                sameValue.sheetName(), sameValue.managementNo(), profile(sameScenario, sameSource, sameWorking),
                sameSource, sameWorking, Map.of("capacityUnit", sameValue.originalValues().get("capacityUnit")),
                sameScenario.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("MODIFY_VALUE_UNCHANGED");

        Fixture duplicateId = fixture(8, false, false, true, false);
        String duplicateIdSource = sha256(duplicateId.baseline());
        String duplicateIdWorking = sha256(duplicateId.workingCopy());
        var duplicateIdProfile = profile(new SuccessfulCase("H_DUPLICATE_ID", 8, false, false,
                Map.of("businessItemNo", "NEW-ITEM")), duplicateIdSource, duplicateIdWorking);
        assertThatThrownBy(() -> engine.mapFieldsByProfile(duplicateId.workingCopy(), PROCESS,
                duplicateId.sheetName(), duplicateId.managementNo(), duplicateIdProfile,
                duplicateIdSource, duplicateIdWorking))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("EQUIPMENT_BOX_NOT_UNIQUE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_H_DUPLICATE_MANAGEMENT_ID_USER_WORKING_COPY.xlsx",
                duplicateId.workingCopy(), "SYNTHETIC_NEGATIVE_DUPLICATE_MANAGEMENT_ID");

        Fixture duplicateItem = fixture(8, false, false, false, true);
        String duplicateItemSource = sha256(duplicateItem.baseline());
        String duplicateItemWorking = sha256(duplicateItem.workingCopy());
        var duplicateItemCase = new SuccessfulCase("H_DUPLICATE_ITEM", 8, false, false,
                Map.of("businessItemNo", "ITEM-IN-OTHER-CELL"));
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(duplicateItem.workingCopy(), PROCESS,
                duplicateItem.sheetName(), duplicateItem.managementNo(),
                profile(duplicateItemCase, duplicateItemSource, duplicateItemWorking), duplicateItemSource,
                duplicateItemWorking, Map.of("businessItemNo", duplicateItem.originalValues().get("businessItemNo")),
                duplicateItemCase.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("DUPLICATE_ITEM_NO");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_H_DUPLICATE_ITEM_NO_USER_WORKING_COPY.xlsx",
                duplicateItem.workingCopy(), "SYNTHETIC_NEGATIVE_DUPLICATE_ITEM_NO");

        Fixture managementChange = fixture(8, false, false, false, false);
        String managementSource = sha256(managementChange.baseline());
        String managementWorking = sha256(managementChange.workingCopy());
        var managementCase = new SuccessfulCase("H_MANAGEMENT_NO_REMAP", 8, false, false,
                Map.of("managementNo", "SYNTH-RENUMBERED"));
        var managementProfile = profile(managementCase, managementSource, managementWorking);
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(managementChange.workingCopy(), PROCESS,
                managementChange.sheetName(), managementChange.managementNo(), managementProfile,
                managementSource, managementWorking, Map.of("managementNo", managementChange.managementNo()),
                managementCase.replacements()))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_FIELD_NOT_EDITABLE");
        writeArtifact(outputDirectory, manifest, "SYNTHETIC_TEST_ONLY_H_MANAGEMENT_ID_REMAP_USER_WORKING_COPY.xlsx",
                managementChange.workingCopy(), "SYNTHETIC_NEGATIVE_REVIEW_REQUIRED");

        Files.writeString(outputDirectory.resolve("SHA256SUMS.tsv"),
                "artifact\tsha256\tbytes\tclassification\n" + String.join("\n", manifest) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(manifest).hasSize(37);
        assertThat(outputDirectory.toString()).contains("2026-11-01-step8-0-profile-modify");
    }

    private void assertWorkbookDiffOnlyAtRequestedCells(byte[] beforeBytes, byte[] afterBytes, String sheetName,
                                                        Map<String, String> fieldCells,
                                                        Map<String, String> replacements) throws Exception {
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(beforeBytes));
             var after = WorkbookFactory.create(new ByteArrayInputStream(afterBytes))) {
            var oldSheet = before.getSheet(sheetName);
            var newSheet = after.getSheet(sheetName);
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(after.getPrintArea(after.getSheetIndex(sheetName))).isEqualTo(before.getPrintArea(before.getSheetIndex(sheetName)));
            assertThat(newSheet.getPrintSetup().getLandscape()).isEqualTo(oldSheet.getPrintSetup().getLandscape());
            assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            assertThat(newSheet.getPrintSetup().getFitWidth()).isEqualTo(oldSheet.getPrintSetup().getFitWidth());
            assertThat(newSheet.getPrintSetup().getFitHeight()).isEqualTo(oldSheet.getPrintSetup().getFitHeight());
            assertThat(newSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin)).isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin));
            assertThat(newSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin)).isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin));
            assertThat(newSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin)).isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin));
            assertThat(newSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin)).isEqualTo(oldSheet.getMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin));
            for (int rowIndex = 0; rowIndex <= oldSheet.getLastRowNum(); rowIndex++) {
                var oldRow = oldSheet.getRow(rowIndex);
                var newRow = newSheet.getRow(rowIndex);
                if (oldRow == null && newRow == null) continue;
                short oldHeight = oldRow == null ? -1 : oldRow.getHeight();
                short newHeight = newRow == null ? -1 : newRow.getHeight();
                assertThat(newHeight).as("preserved row height %d", rowIndex).isEqualTo(oldHeight);
                int lastColumn = Math.max(oldRow == null ? 0 : oldRow.getLastCellNum(), newRow == null ? 0 : newRow.getLastCellNum());
                for (int column = 0; column < lastColumn; column++) {
                    var oldCell = oldRow == null ? null : oldRow.getCell(column);
                    var newCell = newRow == null ? null : newRow.getCell(column);
                    String address = new org.apache.poi.ss.util.CellReference(rowIndex, column).formatAsString();
                    boolean requested = replacements.keySet().stream().anyMatch(field -> address.equals(fieldCells.get(field)));
                    if (requested) {
                        String field = replacements.keySet().stream().filter(key -> address.equals(fieldCells.get(key))).findFirst().orElseThrow();
                        assertThat(newCell).isNotNull();
                        assertThat(formatter.formatCellValue(newCell)).as("changed field %s", field).isEqualTo(replacements.get(field));
                        assertThat(newCell.getCellStyle().getIndex()).as("preserved modified style %s", field)
                                .isEqualTo(oldCell.getCellStyle().getIndex());
                        var font = after.getFontAt(newCell.getCellStyle().getFontIndexAsInt());
                        assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                        assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                    } else {
                        assertThat(newCell == null ? "" : formatter.formatCellValue(newCell))
                                .as("unrequested cell %s unchanged", address)
                                .isEqualTo(oldCell == null ? "" : formatter.formatCellValue(oldCell));
                        if (oldCell != null && newCell != null)
                            assertThat(newCell.getCellStyle().getIndex()).as("unrequested style %s", address)
                                    .isEqualTo(oldCell.getCellStyle().getIndex());
                    }
                }
            }
            for (int column = 0; column <= 39; column++)
                assertThat(newSheet.getColumnWidth(column)).as("preserved column width %s", column)
                        .isEqualTo(oldSheet.getColumnWidth(column));
        }
    }

    private void assertDrawingPreserved(byte[] before, byte[] after, String sheetName) {
        var oldSheet = inspector.inspectSheet(before, sheetName);
        var newSheet = inspector.inspectSheet(after, sheetName);
        assertThat(newSheet.drawingObjects()).isEqualTo(oldSheet.drawingObjects());
        assertThat(newSheet.drawingPartName()).isEqualTo(oldSheet.drawingPartName());
        if (oldSheet.drawingPartName() != null) {
            assertThat(parts.readPart(after, newSheet.drawingPartName()))
                    .containsExactly(parts.readPart(before, oldSheet.drawingPartName()));
        }
    }

    private void assertZipIntegrity(byte[] bytes) throws Exception {
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                while (input.read(buffer) >= 0) { }
                input.closeEntry();
            }
        }
    }

    private Fixture fixture(int boxHeight, boolean merged, boolean drawing, boolean duplicateId, boolean duplicateItem)
            throws Exception {
        String sheetName = boxHeight == 17 ? SHEET_17 : SHEET_8;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
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
            font.setFontHeightInPoints((short) 8); font.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var text = workbook.createCellStyle(); text.setFont(font); text.setBorderLeft(BorderStyle.THIN);
            text.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            text.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var right = workbook.createCellStyle(); right.cloneStyleFrom(text); right.setBorderRight(BorderStyle.THIN);
            var bottom = workbook.createCellStyle(); bottom.cloneStyleFrom(text); bottom.setBorderBottom(BorderStyle.THIN);
            var corner = workbook.createCellStyle(); corner.cloneStyleFrom(text); corner.setBorderRight(BorderStyle.THIN); corner.setBorderBottom(BorderStyle.THIN);
            int top = 0;
            String managementNo = "SYNTH-MODIFY-01";
            Map<String, String> originalValues = writeBox(sheet, top, boxHeight, managementNo, text, right, bottom, corner);
            for (int row = 0; row < boxHeight; row++) sheet.getRow(row).setHeightInPoints(boxHeight == 17 ? 13.5f : 12f);
            sheet.createRow(40).createCell(0).setCellValue("SYNTHETIC_TEST_ONLY_BASELINE");
            sheet.getRow(40).getCell(0).setCellValue("SYNTHETIC_TEST_ONLY_WORKING_COPY");
            if (duplicateId) {
                var row = sheet.createRow(50);
                row.createCell(9).setCellValue(managementNo);
                for (int column = 9; column <= 26; column++) {
                    var cell = row.createCell(column);
                    cell.setCellStyle(column == 26 ? right : text);
                }
                row.getCell(9).setCellValue(managementNo);
                for (int offset = 1; offset < 7; offset++) {
                    var duplicateRow = sheet.createRow(50 + offset);
                    for (int column = 9; column <= 26; column++) duplicateRow.createCell(column).setCellStyle(offset == 6 ? bottom : text);
                }
                sheet.getRow(50 + 6).getCell(26).setCellStyle(corner);
            }
            if (duplicateItem) sheet.createRow(45).createCell(9).setCellValue("ITEM-IN-OTHER-CELL");
            if (merged) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 12, 13));
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(7, 7, 12, 13));
            }
            if (drawing) {
                var patriarch = sheet.createDrawingPatriarch();
                var shape = ((org.apache.poi.xssf.usermodel.XSSFDrawing) patriarch).createTextbox(
                        patriarch.createAnchor(0, 0, 0, 0, 30, 28, 32, 30));
                shape.getCTShape().getNvSpPr().getCNvPr().setId(10L);
                shape.getCTShape().getNvSpPr().getCNvPr().setName("Synthetic MODIFY note");
                var connector = ((org.apache.poi.xssf.usermodel.XSSFDrawing) patriarch).createConnector(
                        patriarch.createAnchor(0, 0, 0, 0, 34, 28, 36, 30));
                connector.getCTConnector().getNvCxnSpPr().getCNvPr().setId(11L);
                connector.getCTConnector().getNvCxnSpPr().getCNvPr().setName("Synthetic MODIFY connector");
            }
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.LETTER_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0); sheet.setFitToPage(true);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.4d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.4d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.TopMargin, 0.5d);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.BottomMargin, 0.5d);
            workbook.setPrintArea(0, "A1:AN60");
            workbook.write(output);
            byte[] working = output.toByteArray();
            byte[] baseline;
            try (var baselineWorkbook = WorkbookFactory.create(new ByteArrayInputStream(working));
                 var baselineOutput = new ByteArrayOutputStream()) {
                baselineWorkbook.getSheet(sheetName).getRow(40).getCell(0).setCellValue("SYNTHETIC_TEST_ONLY_BASELINE");
                baselineWorkbook.write(baselineOutput);
                baseline = baselineOutput.toByteArray();
            }
            return new Fixture(baseline, working, sheetName, managementNo, originalValues, fieldAddresses(boxHeight));
        }
    }

    private Map<String, String> writeBox(org.apache.poi.ss.usermodel.Sheet sheet, int top, int height,
                                         String managementNo, org.apache.poi.ss.usermodel.CellStyle text,
                                         org.apache.poi.ss.usermodel.CellStyle right,
                                         org.apache.poi.ss.usermodel.CellStyle bottom,
                                         org.apache.poi.ss.usermodel.CellStyle corner) {
        for (int rowOffset = 0; rowOffset < height; rowOffset++) {
            var row = sheet.createRow(top + rowOffset);
            for (int column = 9; column <= 26; column++) {
                boolean isRight = column == 26;
                boolean isBottom = rowOffset == height - 1;
                row.createCell(column).setCellStyle(isRight && isBottom ? corner : isRight ? right : isBottom ? bottom : text);
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("managementNo", managementNo);
        values.put("equipmentTypeLabel", "Storage");
        values.put("equipmentName", "Synthetic Unit");
        values.put("businessItemNo", "ITEM-001");
        values.put("capacity", "120");
        values.put("capacityUnit", "m3/h");
        values.put("quantity", "1ea");
        values.put("note", "Synthetic note");
        sheet.getRow(top).getCell(9).setCellValue(values.get("managementNo"));
        sheet.getRow(top + 2).getCell(9).setCellValue(values.get("equipmentTypeLabel"));
        sheet.getRow(top + 2).getCell(11).setCellValue(values.get("note"));
        sheet.getRow(top + 3).getCell(9).setCellValue(values.get("equipmentName"));
        sheet.getRow(top + 4).getCell(9).setCellValue(values.get("businessItemNo"));
        sheet.getRow(top + 5).getCell(11).setCellValue(values.get("capacity"));
        sheet.getRow(top + 5).getCell(12).setCellValue(values.get("capacityUnit"));
        sheet.getRow(top + 6).getCell(9).setCellValue(values.get("quantity"));
        return values;
    }

    private Map<String, String> fieldAddresses(int boxHeight) {
        Map<String, String> addresses = new LinkedHashMap<>();
        addresses.put("managementNo", "J1");
        addresses.put("equipmentTypeLabel", "J3");
        addresses.put("note", "L3");
        addresses.put("equipmentName", "J4");
        addresses.put("businessItemNo", "J5");
        addresses.put("capacity", "L6");
        addresses.put("capacityUnit", "M6");
        addresses.put("quantity", "J7");
        if (boxHeight == 17) return addresses;
        return addresses;
    }

    private EquipmentBoxProfile profile(SuccessfulCase scenario, String sourceSha, String workingSha) throws Exception {
        Fixture fixture = fixture(scenario.boxHeight(), scenario.merged(), scenario.drawing(), false, false);
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", map(0, 0, "TEXT", true));
        fields.put("equipmentTypeLabel", map(2, 0, "TEXT", true));
        fields.put("equipmentName", map(3, 0, "TEXT", true));
        fields.put("businessItemNo", map(4, 0, "TEXT", true));
        fields.put("capacity", map(5, 2, "TEXT", true));
        fields.put("capacityUnit", map(5, 3, "TEXT", true));
        fields.put("quantity", map(6, 0, "TEXT", true));
        fields.put("note", map(2, 2, "TEXT", false));
        Map<Integer, Float> heights = new LinkedHashMap<>();
        for (int row = 0; row < scenario.boxHeight(); row++) heights.put(row, scenario.boxHeight() == 17 ? 13.5f : 12f);
        Map<String, Integer> styles = new LinkedHashMap<>();
        fields.forEach((name, cell) -> styles.put(name, cell.expectedStyleId()));
        return new EquipmentBoxProfile("SYNTHETIC-MODIFY-" + scenario.name(), PROCESS, fixture.sheetName(),
                "SYNTHETIC_TEST_ONLY_BASELINE.xlsx", sourceSha, workingSha, "0.1.0", "VERIFIED", "SYNTHETIC_TESTED",
                "Synthetic-only Profile for isolated MODIFY testing; not an official process mapping.",
                "J1:AA" + scenario.boxHeight(), scenario.boxHeight(), fields,
                scenario.merged() ? List.of("M2:N2", "M8:N8") : List.of(), styles, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", heights, Map.of(), "REVALIDATE_ANCHORS_ON_EACH_REQUEST",
                "PRESERVE_ONLY_NO_NEW_CONNECTOR", "BOX_ONLY_TEST_ONLY", "'" + fixture.sheetName() + "'!$A$1:$AN$60",
                "PRESERVE_AND_SHIFT_AFTER_INSERTION_BOUNDARY", null);
    }

    private EquipmentBoxProfile.FieldCell map(int row, int column, String valueMode, boolean required) {
        return new EquipmentBoxProfile.FieldCell(row, column, 1, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", required, valueMode);
    }

    private EquipmentBoxProfile withFieldMapping(EquipmentBoxProfile profile, String field,
                                                 EquipmentBoxProfile.FieldCell replacement) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>(profile.fields());
        fields.put(field, replacement);
        return new EquipmentBoxProfile(profile.profileId() + "-REMAPPED", profile.processCode(), profile.sheetName(),
                profile.sourceFilename(), profile.sourceSha256(), profile.verifiedWorkingSha256(), profile.profileVersion(),
                profile.status(), profile.evidenceLevel(), profile.evidence(), profile.templateRange(), profile.boxHeight(),
                fields, profile.mergedRanges(), profile.expectedStyleIds(), profile.fontName(), profile.fontSizePt(),
                profile.fontColor(), profile.rowHeightsPt(), profile.columnWidths(), profile.drawingPolicy(),
                profile.connectorPolicy(), profile.insertionPolicy(), profile.printArea(), profile.pageBreakPolicy(), profile.blocker());
    }

    private Fixture withNumericValue(Fixture fixture, String address, double value) throws Exception {
        byte[] bytes;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture.workingCopy()));
             var output = new ByteArrayOutputStream()) {
            var reference = new org.apache.poi.ss.util.CellReference(address);
            workbook.getSheet(fixture.sheetName()).getRow(reference.getRow()).getCell(reference.getCol()).setCellValue(value);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        return new Fixture(fixture.baseline(), bytes, fixture.sheetName(), fixture.managementNo(),
                fixture.originalValues(), fixture.fieldCells());
    }

    private Fixture withFormula(Fixture fixture, String address) throws Exception {
        byte[] bytes;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture.workingCopy()));
             var output = new ByteArrayOutputStream()) {
            var reference = new org.apache.poi.ss.util.CellReference(address);
            workbook.getSheet(fixture.sheetName()).getRow(reference.getRow()).getCell(reference.getCol())
                    .setCellFormula("1+1");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        return new Fixture(fixture.baseline(), bytes, fixture.sheetName(), fixture.managementNo(),
                fixture.originalValues(), fixture.fieldCells());
    }

    private EquipmentBoxProfile wrongStyle(EquipmentBoxProfile profile, String field) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>(profile.fields());
        var original = fields.get(field);
        fields.put(field, new EquipmentBoxProfile.FieldCell(original.rowOffset(), original.columnOffset(),
                original.expectedStyleId() + 100, original.expectedFontName(), original.expectedFontSizePt(),
                original.expectedFontColor(), original.required(), original.valueMode()));
        return new EquipmentBoxProfile(profile.profileId() + "-WRONG-STYLE", profile.processCode(), profile.sheetName(),
                profile.sourceFilename(), profile.sourceSha256(), profile.verifiedWorkingSha256(), profile.profileVersion(),
                profile.status(), profile.evidenceLevel(), profile.evidence(), profile.templateRange(), profile.boxHeight(),
                fields, profile.mergedRanges(), profile.expectedStyleIds(), profile.fontName(), profile.fontSizePt(),
                profile.fontColor(), profile.rowHeightsPt(), profile.columnWidths(), profile.drawingPolicy(),
                profile.connectorPolicy(), profile.insertionPolicy(), profile.printArea(), profile.pageBreakPolicy(), profile.blocker());
    }

    private void writeArtifact(Path directory, List<String> manifest, String filename, byte[] bytes, String classification)
            throws Exception {
        Path target = directory.resolve(filename);
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        manifest.add(filename + "\t" + sha256(bytes) + "\t" + bytes.length + "\t" + classification);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record SuccessfulCase(String name, int boxHeight, boolean merged, boolean drawing,
                                 Map<String, String> replacements) { }

    private record Fixture(byte[] baseline, byte[] workingCopy, String sheetName, String managementNo,
                           Map<String, String> originalValues, Map<String, String> fieldCells) { }
}
