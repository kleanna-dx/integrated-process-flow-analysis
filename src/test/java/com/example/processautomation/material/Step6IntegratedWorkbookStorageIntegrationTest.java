package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.excel.DiagramEditPlanService;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class Step6IntegratedWorkbookStorageIntegrationTest {
    @Autowired private Step6TestOnlyWorkbookEnrichmentService integrated;
    @Autowired private DiagramEditPlanService plans;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void storesAndDownloadsBothExplicitAndReviewOnlyOutputsWithoutReplacingDiagramIntermediate() throws Exception {
        String diagramHashBefore = jdbc.queryForObject(
                "SELECT output_sha256 FROM diagram_edit_plan WHERE id = 33 AND change_run_id = 193 AND change_item_id = 217",
                String.class);
        int confirmedBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class);
        int reviewBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class);
        var diagram = plans.generate(33);
        assertThat(diagram.sha256()).isEqualTo(sha256(diagram.bytes()));
        assertThat(diagram.sha256()).isEqualToIgnoringCase(diagramHashBefore);

        var actualReview = integrated.createAndStoreIntegratedWorkbook(193, 217, 33, diagram, false);
        assertThat(actualReview.scenarioKey()).isEqualTo("ACTUAL_REVIEW_ONLY");
        assertThat(actualReview.filename()).contains("actual-review-only");
        assertThat(actualReview.sha256()).isEqualTo(sha256(actualReview.bytes()));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(actualReview.bytes()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(8);
            assertThat(workbook.getActiveSheetIndex()).isEqualTo(7);
            assertThat(workbook.getSheet("변경내역")).isNotNull();
            var balance = workbook.getSheet("물질수지 검토");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(balance.getRow(0).getCell(0)))
                    .contains("물질수지 검토 결과");
            assertThat(formatter.formatCellValue(balance.getRow(2).getCell(0)))
                    .contains("시험 시나리오 미선택");
            assertThat(valueFor(balance, formatter, "유입량")).isEqualTo("미제공");
            assertThat(valueFor(balance, formatter, "출구 합계")).isEqualTo("산출 안 함");
            assertThat(valueFor(balance, formatter, "잔차")).isEqualTo("산출 안 함");
            assertThat(sheetText(balance, formatter)).doesNotContain("100 = 0.5 + 2.5 + 97");
        }

        var synthetic = integrated.createAndStoreIntegratedWorkbook(193, 217, 33, diagram, true);
        assertThat(synthetic.scenarioKey()).isEqualTo("SYNTHETIC_TEST_ONLY");
        assertThat(synthetic.sha256()).isEqualTo(sha256(synthetic.bytes()));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(synthetic.bytes()))) {
            var formatter = new DataFormatter();
            var balance = workbook.getSheet("물질수지 검토");
            assertThat(workbook.getNumberOfSheets()).isEqualTo(8);
            assertThat(formatter.formatCellValue(balance.getRow(0).getCell(0)))
                    .contains("물질수지 검토 결과");
            assertThat(formatter.formatCellValue(balance.getRow(2).getCell(0)))
                    .contains("TEST_ONLY 가상 계산");
            assertThat(valueFor(balance, formatter, "유입량")).isEqualTo("100");
            assertThat(valueFor(balance, formatter, "후단 이동")).isEqualTo("97");
            assertThat(valueFor(balance, formatter, "별도 폐수 이동")).isEqualTo("2.5");
            assertThat(valueFor(balance, formatter, "폐기물 발생")).isEqualTo("0.5");
            assertThat(balance.getRow(12).getCell(1).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
            assertThat(balance.getRow(12).getCell(1).getCellFormula()).isEqualTo("SUM(B10:B12)");
            assertThat(balance.getRow(12).getCell(1).getNumericCellValue()).isEqualTo(100.0);
            assertThat(balance.getRow(13).getCell(1).getCellFormula()).isEqualTo("B9-B13");
            assertThat(balance.getRow(13).getCell(1).getNumericCellValue()).isEqualTo(0.0);
            assertThat(sheetText(balance, formatter)).contains("실제 물질수지 검증: 불가");
        }

        var downloaded = integrated.downloadIntegratedWorkbook(33, false);
        assertThat(downloaded.bytes()).isEqualTo(actualReview.bytes());
        assertThat(downloaded.sha256()).isEqualTo(actualReview.sha256());
        assertThat(integrated.downloadIntegratedWorkbook(33, true).bytes()).isEqualTo(synthetic.bytes());
        assertThat(jdbc.queryForObject(
                "SELECT output_sha256 FROM diagram_edit_plan WHERE id = 33", String.class))
                .isEqualTo(diagramHashBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class))
                .isEqualTo(confirmedBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class))
                .isEqualTo(reviewBefore);

        assertThatThrownBy(() -> integrated.requireEligiblePlan(194, 217, 33))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> integrated.requireEligiblePlan(193, 216, 33))
                .isInstanceOf(ResponseStatusException.class);
    }

    private String valueFor(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String label) {
        for (var row : sheet) {
            if (row.getCell(0) != null && label.equals(formatter.formatCellValue(row.getCell(0)))) {
                return row.getCell(1) == null ? "" : formatter.formatCellValue(row.getCell(1));
            }
        }
        throw new AssertionError("Missing cell label " + label);
    }

    private String sheetText(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter) {
        StringBuilder text = new StringBuilder();
        for (var row : sheet) for (var cell : row) text.append(formatter.formatCellValue(cell)).append('\n');
        return text.toString();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
