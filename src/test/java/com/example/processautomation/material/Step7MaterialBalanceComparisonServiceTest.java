package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.MasterWorkbookParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class Step7MaterialBalanceComparisonServiceTest {
    private final Step7MaterialBalanceComparisonService service =
            new Step7MaterialBalanceComparisonService(new MasterWorkbookParser());
    private final Step7MaterialBalanceComparisonWorkbookExporter exporter =
            new Step7MaterialBalanceComparisonWorkbookExporter();

    private Path resolveSource(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xlsm"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("No read-only XLSM source found"));
        }
    }

    private Path resolveMaster(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .filter(path -> {
                        try {
                            new MasterWorkbookParser().parse(Files.readAllBytes(path));
                            return true;
                        } catch (Exception ignored) {
                            return false;
                        }
                    }).findFirst().orElseThrow(() -> new IllegalArgumentException("No official master workbook found"));
        }
    }

    @Test
    void comparisonStatesRequireIndependentEvidence() {
        var missing = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                "A", "B", BigDecimal.ONE, "t/day", false, false, false,
                "A", "B", BigDecimal.ONE, "t/day");
        assertThat(Step7MaterialBalanceComparisonService.classify(missing)).isEqualTo("INSUFFICIENT_EVIDENCE");

        var matched = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                "A", "B", BigDecimal.ONE, "t/day", true, false, false,
                "A", "B", BigDecimal.ONE, "t/day");
        assertThat(Step7MaterialBalanceComparisonService.classify(matched)).isEqualTo("MATCHED");

        var sourceDiff = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                "A", "B", BigDecimal.ONE, "t/day", true, false, false,
                "A", "C", BigDecimal.ONE, "t/day");
        assertThat(Step7MaterialBalanceComparisonService.classify(sourceDiff)).isEqualTo("SOURCE_DIFFERENCE_UNRESOLVED");

        var quantityDiff = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                "A", "B", BigDecimal.TEN, "t/day", true, false, false,
                "A", "B", BigDecimal.ONE, "t/day");
        assertThat(Step7MaterialBalanceComparisonService.classify(quantityDiff)).isEqualTo("SOURCE_DIFFERENCE_UNRESOLVED");

        var confirmedDbError = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                null, null, null, null, false, true, false, null, null, null, null);
        assertThat(Step7MaterialBalanceComparisonService.classify(confirmedDbError)).isEqualTo("DB_ERROR_CONFIRMED");

        var confirmedDiagramError = new Step7MaterialBalanceComparisonService.OfficialConnectionEvidence(
                null, null, null, null, false, false, true, null, null, null, null);
        assertThat(Step7MaterialBalanceComparisonService.classify(confirmedDiagramError))
                .isEqualTo("LEGACY_DIAGRAM_ERROR_CONFIRMED");
    }

    @Test
    void baselineResidualAndNewChangeResidualRemainSeparate() {
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(null, BigDecimal.ONE, true))
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(BigDecimal.ZERO, BigDecimal.ZERO, true))
                .isEqualTo("NO_NEW_RESIDUAL");
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(BigDecimal.ZERO, BigDecimal.ONE, true))
                .isEqualTo("NEW_CHANGE_ERROR");
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(new BigDecimal("2"), new BigDecimal("2"), false))
                .isEqualTo("EXISTING_BASELINE_IMBALANCE_NOT_NEW_ERROR");
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(new BigDecimal("2"), new BigDecimal("3"), true))
                .isEqualTo("CHANGE_DELTA_ON_EXISTING_IMBALANCE_REQUIRES_REVIEW");
        assertThat(Step7MaterialBalanceComparisonService.classifyChange(new BigDecimal("2"), new BigDecimal("3"), false))
                .isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void preservesStoredPrecisionAndClassifiesBlankAndInvalidQuantities() throws Exception {
        byte[] source = sourceWithQuantities();
        byte[] master = masterWithEquipment();
        var report = service.compare(source, master, "synthetic-quantities.xlsx",
                new Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot(1, 1, 0, 0, 0, 0, 0, "TEST_ONLY"));

        assertThat(report.connections()).hasSize(6);
        var rounded = report.connections().get(0);
        assertThat(rounded.quantityText()).isEqualTo("630.574");
        assertThat(rounded.quantity()).isEqualByComparingTo("630.5739999999995");
        assertThat(rounded.quantityState()).isEqualTo("POSITIVE");
        var different = report.connections().get(1);
        assertThat(different.quantityText()).isEqualTo("630.575");
        assertThat(different.quantity()).isNotEqualByComparingTo(rounded.quantity());
        assertThat(different.quantity().subtract(rounded.quantity()).abs()).isGreaterThan(new BigDecimal("0.000000001"));
        assertThat(report.connections().get(2).quantityState()).isEqualTo("INVALID");
        assertThat(report.connections().get(3).quantityState()).isEqualTo("INVALID");
        assertThat(report.connections().get(4).quantityState()).isEqualTo("BLANK");
        assertThat(report.connections().get(5).quantityState()).isEqualTo("INVALID");
    }

    private byte[] sourceWithQuantities() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("입력시트");
            String[] headers = {"어디서", "어디로", "보조열", "질량", "종류", "보내는 중분류", "받는 중분류",
                    "보내는 대분류", "받는 대분류", "질량수지 계산 제외"};
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            String[] texts = {null, null, "NaN", "Infinity", null, "malformed"};
            for (int index = 0; index < texts.length; index++) {
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue("SRC-" + index);
                row.createCell(1).setCellValue("DST-" + index);
                if (index == 0 || index == 1) {
                    var quantity = row.createCell(3);
                    quantity.setCellValue(index == 0 ? 630.5739999999995 : 630.575);
                    quantity.setCellStyle(workbook.createCellStyle());
                    var format = workbook.createDataFormat().getFormat("0.000");
                    quantity.getCellStyle().setDataFormat(format);
                } else if (texts[index] != null) {
                    row.createCell(3).setCellValue(texts[index]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] masterWithEquipment() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("마스터시트");
            String[] headers = {"인허가관리번호", "사업장 Item No", "중분류번호", "대분류번호", "시설명"};
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            String[] values = {"MASTER-1", "ITEM-1", "P01", "P-01", "설비"};
            var row = sheet.createRow(1);
            for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(values[column]);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "step7.sourceDir", matches = ".+")
    void readsActualSourceReadOnlyAndWritesDetachedWorkbook() throws Exception {
        Path source = resolveSource(Path.of(System.getProperty("step7.sourceDir")));
        Path master = resolveMaster(Path.of(System.getProperty("step7.masterDir")));
        Path output = Path.of(System.getProperty("step7.output"));
        var evidence = new Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot(
                16, 1332, 0, 0, 1, 986, 0, "V1.0");
        String sourceName = "0.3 250808 \uBB3C\uC9C8\uC218\uC9C0 DB.xlsm";
        var report = service.compare(source, master, sourceName, evidence);

        assertThat(report.sourceFilename()).isEqualTo(sourceName);
        assertThat(report.sourceSha256()).isEqualTo("565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa");
        assertThat(report.inputRows()).isEqualTo(3082);
        assertThat(report.pw01InputRows()).isEqualTo(504);
        assertThat(report.pw01UniqueEndpointPairs()).isEqualTo(501);
        assertThat(report.bothEndpointsMasterMatched()).isEqualTo(2777);
        assertThat(report.oneEndpointMasterMatched()).isEqualTo(305);
        assertThat(report.positiveQuantityRows()).isEqualTo(1975);
        assertThat(report.zeroQuantityRows()).isEqualTo(1106);
        assertThat(report.blankQuantityRows()).isEqualTo(1);
        assertThat(report.blankMaterialTypeRows()).isEqualTo(2830);
        assertThat(report.blankExclusionRows()).isEqualTo(3082);
        assertThat(report.cycleComponents()).hasSize(3);
        assertThat(report.cachedComparedTrueRows()).isEqualTo(1048);
        assertThat(report.cachedComparedFalseRows()).isEqualTo(130);
        assertThat(report.cachedComparedMissingRows()).isEqualTo(1);
        assertThat(report.cachedResidualBlankRows()).isEqualTo(30);
        assertThat(report.cachedResidualNonzeroRows()).isEqualTo(101);
        assertThat(report.statuses().baselineRowsInsufficientEvidence()).isEqualTo(3082);
        assertThat(report.statuses().dbErrorConfirmed()).isZero();
        assertThat(report.statuses().legacyDiagramErrorConfirmed()).isZero();

        byte[] workbookBytes = exporter.export(report);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, workbookBytes);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheet("연결별 대조").getLastRowNum()).isEqualTo(3082);
        assertThat(workbook.getSheet("기존 수지 불일치").getLastRowNum()).isGreaterThan(130);
            assertThat(workbook.getSheet("전후잔차 시험예시").getRow(1).getCell(4).getStringCellValue())
                    .isEqualTo("NEW_CHANGE_ERROR");
            for (var sheet : workbook) {
                for (var row : sheet) {
                    for (var cell : row) {
                        var font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                        assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                        assertThat(font.getColor()).isEqualTo(IndexedColors.BLACK.getIndex());
                    }
                }
            }
        }
        assertThat(Files.size(output)).isGreaterThan(100_000);
    }
}
