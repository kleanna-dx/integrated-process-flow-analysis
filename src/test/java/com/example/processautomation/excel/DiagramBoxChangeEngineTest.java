package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DiagramBoxChangeEngineTest {
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
            new OoxmlPartEditor(), new OoxmlPackageInspector(), new EquipmentBoxGeometryLocator());

    @Test
    void modifiesOnlyExplicitSyntheticBoxFieldAndPreservesOutsideContent() throws Exception {
        byte[] source = fixture(false, false);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            assertThat(new EquipmentBoxGeometryLocator().locateByManagementNo(
                    workbook.getSheet("synthetic"), "SYN-BOX-01")).as("detected synthetic boxes").hasSize(1);
        }
        var result = engine.modifyBoxFieldsTestOnly(source, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("A3", "Synthetic Unit", "Synthetic Updated Unit")));

        assertThat(result.operation()).isEqualTo("MODIFY");
        assertThat(result.scenarioStatus()).isEqualTo("TEST_ONLY");
        assertThat(result.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        assertThat(result.affectedCells()).containsExactly("A3");
        assertThat(result.sourceSha256()).isNotEqualTo(result.outputSha256());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet("synthetic");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(0))).isEqualTo("Synthetic Updated Unit");
            assertThat(formatter.formatCellValue(sheet.getRow(10).getCell(8))).isEqualTo("outside sentinel");
        }
        assertOnlyWorksheetPartChanged(source, result.workbookBytes());
    }

    @Test
    void resolvesOnlyUniqueExactMasterTextAndLeavesAmbiguousFieldsForReview() throws Exception {
        byte[] source = fixture(false, false);
        var mapping = engine.mapFieldsByMaster(source, "synthetic", "SYN-BOX-01", Map.of(
                "managementNo", "SYN-BOX-01", "equipmentName", "Synthetic Unit",
                "businessItemNo", "ITEM-001", "capacityWithUnit", "12 m3/h", "quantity", "1"));
        assertThat(mapping.fields().get("equipmentName").status()).isEqualTo("VERIFIED");
        assertThat(mapping.fields().get("equipmentName").cellAddress()).isEqualTo("A3");
        assertThat(mapping.fields().get("businessItemNo").cellAddress()).isEqualTo("A4");
        assertThat(mapping.fields().get("capacityWithUnit").cellAddress()).isEqualTo("A5");
        assertThat(mapping.fields().get("managementNo").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(mapping.fields().get("quantity").status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void refusesStaleOutOfBoxMissingIdAndFormulaFieldTargets() throws Exception {
        byte[] source = fixture(false, false);
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(source, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("A3", "stale value", "replacement"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("CURRENT_FIELD_VALUE_MISMATCH");
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(source, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("I11", "outside sentinel", "replacement"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("FIELD_OUTSIDE_EQUIPMENT_BOX");
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(source, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("A1", "SYN-BOX-01", "SYN-BOX-02"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("MANAGEMENT_ID_CHANGE_REQUIRES_REMAP");
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(source, "synthetic", "MISSING-ID",
                List.of(new DiagramBoxChangeEngine.FieldChange("A3", "Synthetic Unit", "replacement"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("EQUIPMENT_BOX_NOT_UNIQUE");
        byte[] formula = fixtureWithFormula();
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(formula, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("A3", "Synthetic Unit", "replacement"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("FORMULA_IN_BOX");
    }

    @Test
    void refusesNonAnchorMergedFieldAndDuplicateEquipmentId() throws Exception {
        byte[] merged = fixture(true, false);
        assertThatThrownBy(() -> engine.modifyBoxFieldsTestOnly(merged, "synthetic", "SYN-BOX-01",
                List.of(new DiagramBoxChangeEngine.FieldChange("B3", "Synthetic Unit", "replacement"))))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("MERGED_FIELD_NOT_ANCHOR");
        byte[] duplicate = fixture(false, true);
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(duplicate, "synthetic", "SYN-BOX-01"))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("EQUIPMENT_BOX_NOT_UNIQUE");
        byte[] drawing = fixtureWithDrawing();
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(drawing, "synthetic", "SYN-BOX-01"))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("DRAWINGML_DELETE_REVIEW_REQUIRED");
    }

    @Test
    void deleteImpactAssessmentBlocksDrawingObjectsButAllowsIsolatedSyntheticBox() throws Exception {
        var safe = engine.assessDeleteImpact(fixture(false, false), "synthetic", "SYN-BOX-01");
        assertThat(safe.status()).isEqualTo("STRUCTURE_VERIFIED_BOX_ONLY_TEST_ONLY");
        assertThat(safe.automaticTestOnlyDeleteAllowed()).isTrue();
        assertThat(safe.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        var risky = engine.assessDeleteImpact(fixtureWithDrawing(), "synthetic", "SYN-BOX-01");
        assertThat(risky.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(risky.automaticTestOnlyDeleteAllowed()).isFalse();
        assertThat(risky.drawingImpacts()).isNotEmpty();
        assertThat(risky.reason()).contains("DRAWINGML");
    }

    @Test
    void deletesOnlySyntheticBoxCellsAndContainedMergesWithoutMovingRows() throws Exception {
        byte[] source = fixture(true, false);
        var result = engine.deleteBoxTestOnly(source, "synthetic", "SYN-BOX-01");

        assertThat(result.operation()).isEqualTo("DELETE");
        assertThat(result.affectedCells()).contains("A1", "A3", "A4");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet("synthetic");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(10).getCell(8))).isEqualTo("outside sentinel");
            assertThat(sheet.getLastRowNum()).isEqualTo(10);
            assertThat(sheet.getNumMergedRegions()).isZero();
            for (int row = 0; row < 8; row++) for (int column = 0; column <= 5; column++) {
                var cell = sheet.getRow(row).getCell(column);
                if (cell != null) {
                    assertThat(formatter.formatCellValue(cell)).isBlank();
                    assertThat(cell.getCellStyle().getIndex()).isZero();
                }
            }
        }
        assertOnlyWorksheetPartChanged(source, result.workbookBytes());
    }

    private byte[] fixtureWithFormula() throws Exception {
        byte[] base = fixture(false, false);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("synthetic").getRow(3).getCell(0).setCellFormula("1+1");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] fixtureWithDrawing() throws Exception {
        byte[] base = fixture(false, false);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var drawing = workbook.getSheet("synthetic").createDrawingPatriarch();
            var anchor = drawing.createAnchor(0, 0, 0, 0, 0, 0, 2, 2);
            drawing.createTextbox(anchor);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] fixture(boolean merged, boolean duplicateId) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("synthetic");
            CellStyle boxStyle = workbook.createCellStyle();
            boxStyle.setBorderLeft(BorderStyle.THIN);
            CellStyle rightStyle = workbook.createCellStyle();
            rightStyle.setBorderRight(BorderStyle.THIN);
            CellStyle bottomStyle = workbook.createCellStyle();
            bottomStyle.setBorderBottom(BorderStyle.THIN);
            CellStyle cornerStyle = workbook.createCellStyle();
            cornerStyle.setBorderRight(BorderStyle.THIN);
            cornerStyle.setBorderBottom(BorderStyle.THIN);
            for (int rowIndex = 0; rowIndex < 8; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                for (int column = 0; column <= 5; column++) {
                    var cell = row.createCell(column);
                    if (rowIndex == 7 && column == 5) cell.setCellStyle(cornerStyle);
                    else if (rowIndex == 7) cell.setCellStyle(bottomStyle);
                    else if (column == 5) cell.setCellStyle(rightStyle);
                    else cell.setCellStyle(boxStyle);
                }
            }
            sheet.getRow(0).getCell(0).setCellValue("SYN-BOX-01");
            sheet.getRow(2).getCell(0).setCellValue("Synthetic Unit");
            sheet.getRow(3).getCell(0).setCellValue("ITEM-001");
            sheet.getRow(4).getCell(0).setCellValue("12 m3/h");
            sheet.getRow(5).getCell(0).setCellValue("1ea");
            if (merged) sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 0, 1));
            var outside = sheet.createRow(10).createCell(8);
            outside.setCellValue("outside sentinel");
            if (duplicateId) {
                var duplicateRow = sheet.createRow(20);
                duplicateRow.createCell(0).setCellValue("SYN-BOX-01");
                for (int column = 0; column <= 5; column++) {
                    var cell = duplicateRow.createCell(column == 0 ? 1 : column);
                    cell.setCellStyle(column == 5 ? rightStyle : boxStyle);
                }
                duplicateRow.getCell(5).setCellStyle(rightStyle);
                sheet.createRow(26);
                for (int column = 0; column <= 5; column++) {
                    var cell = sheet.getRow(26).createCell(column);
                    cell.setCellStyle(column == 5 ? cornerStyle : bottomStyle);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void assertOnlyWorksheetPartChanged(byte[] before, byte[] after) throws Exception {
        var inspector = new OoxmlPackageInspector();
        String worksheet = inspector.inspectSheet(before, "synthetic").worksheetPartName();
        try (var oldZip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(before)).get();
             var newZip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(after)).get()) {
            var oldEntries = oldZip.getEntries();
            while (oldEntries.hasMoreElements()) {
                var entry = oldEntries.nextElement();
                var newEntry = newZip.getEntry(entry.getName());
                assertThat(newEntry).as("package part %s retained", entry.getName()).isNotNull();
                if (!entry.getName().equals(worksheet)) {
                    try (var oldBytes = oldZip.getInputStream(entry); var newBytes = newZip.getInputStream(newEntry)) {
                        assertThat(newBytes.readAllBytes()).containsExactly(oldBytes.readAllBytes());
                    }
                }
            }
        }
    }
}
