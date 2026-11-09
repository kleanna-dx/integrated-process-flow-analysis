package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class OfficialTextNumericConverterTest {
    private final OfficialTextNumericConverter converter =
            new OfficialTextNumericConverter(new OoxmlPartEditor(), new OoxmlPackageInspector());

    @Test
    void convertsExplicitFlowTextAndReproducesGroupingDecimalsLeadingZeroesAndPlusSign() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("PW-01-05");
            sheet.createRow(0).createCell(0).setCellValue("+001.20 톤/일");
            sheet.getRow(0).createCell(1).setCellValue("1,234.50 kg/day");
            sheet.getRow(0).createCell(2).setCellValue("0007 ㎥/일");
            sheet.getRow(0).createCell(3).setCellValue("황산(9.5%) 0.8톤/일");
            workbook.write(output);
            source = output.toByteArray();
        }

        var result = converter.convert(source);

        assertThat(result.cells()).hasSize(3);
        assertThat(result.assessments()).hasSize(4);
        assertThat(result.classificationCounts()).containsEntry("CONVERTED", 3L)
                .containsEntry("REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS", 1L);
        assertThat(result.compoundOrUnsupportedCellCount()).isEqualTo(1L);
        assertThat(result.assessments()).filteredOn(item -> "D1".equals(item.cellAddress())).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS");
                    assertThat(item.extractedQuantity()).isEqualByComparingTo("0.8");
                    assertThat(item.reason()).contains("concentration");
                });
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheet("PW-01-05");
            var formatter = new DataFormatter(Locale.ROOT);
            assertThat(sheet.getRow(0).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(BigDecimal.valueOf(sheet.getRow(0).getCell(0).getNumericCellValue()))
                    .isEqualByComparingTo("1.20");
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(0))).isEqualTo("+001.20 톤/일");
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(1))).isEqualTo("1,234.50 kg/day");
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(2))).isEqualTo("0007 ㎥/일");
            assertThat(sheet.getRow(0).getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(3))).isEqualTo("황산(9.5%) 0.8톤/일");
            assertThat(result.changedOoxmlParts()).contains("xl/styles.xml", "xl/worksheets/sheet1.xml");
            assertThat(result.sourceInventory().sheets()).containsExactlyElementsOf(result.candidateInventory().sheets());
        }
    }
}
