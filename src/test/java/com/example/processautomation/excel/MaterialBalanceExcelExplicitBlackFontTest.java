package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class MaterialBalanceExcelExplicitBlackFontTest {
    @Test
    void appendedTestOnlyWorksheetsUseExplicitRgbBlackAndKeepEarlierStyles() throws Exception {
        OoxmlPackageInspector inspector = new OoxmlPackageInspector();
        MaterialBalanceExcelExportService exporter = new MaterialBalanceExcelExportService(inspector,
                new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), inspector));
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Source").createRow(0).createCell(0).setCellValue("unchanged");
            workbook.write(output);
            source = output.toByteArray();
        }

        byte[] first = exporter.appendTableWorksheet(source, "TEST_ONLY A", "TEST_ONLY A",
                "TEST_ONLY synthetic", List.of("Name", "Quantity"), List.of(List.of("A", 1)));
        byte[] second = exporter.appendTableWorksheet(first, "TEST_ONLY B", "TEST_ONLY B",
                "TEST_ONLY synthetic", List.of("Name", "Quantity"), List.of(List.of("B", 2)));

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(second))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertExplicitBlack(workbook.getSheet("TEST_ONLY A"));
            assertExplicitBlack(workbook.getSheet("TEST_ONLY B"));
            assertThat(workbook.getSheet("Source").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("unchanged");
        }
    }

    private void assertExplicitBlack(org.apache.poi.ss.usermodel.Sheet sheet) {
        assertThat(sheet).isNotNull();
        for (var row : sheet) {
            for (var cell : row) {
                var color = ((XSSFCell) cell).getCellStyle().getFont().getXSSFColor();
                assertThat(color).as("font color at %s", cell.getAddress()).isNotNull();
                assertThat(color.getARGBHex()).as("explicit RGB black at %s", cell.getAddress())
                        .isEqualToIgnoringCase("FF000000");
            }
        }
    }
}
