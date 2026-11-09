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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class Step6IntegratedWorkbookDeliveryIntegrationTest {
    @Autowired private DiagramEditPlanService diagramPlans;
    @Autowired private Step6TestOnlyWorkbookEnrichmentService integrated;

    @Test
    void createsAndDownloadsIntegratedWorkbookWithoutImplicitSyntheticQuantities() throws Exception {
        var diagram = diagramPlans.generate(33);
        var generated = integrated.createAndStoreIntegratedWorkbook(193, 217, 33, diagram, false);
        var downloaded = integrated.downloadIntegratedWorkbook(33, false);
        assertThat(generated.filename()).contains("actual-review-only");
        assertThat(generated.sha256()).isEqualTo(sha256(generated.bytes()));
        assertThat(downloaded.sha256()).isEqualTo(generated.sha256());
        assertThat(downloaded.bytes()).containsExactly(generated.bytes());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(downloaded.bytes()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(8);
            assertThat(workbook.getActiveSheetIndex()).isEqualTo(7);
            var history = workbook.getSheet("변경내역");
            var balance = workbook.getSheet("물질수지 검토");
            assertThat(history).isNotNull();
            assertThat(balance).isNotNull();
            DataFormatter formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(history.getRow(5).getCell(1))).isEqualTo("C-PW15020");
            assertThat(sheetText(history, formatter)).contains("run 193", "item 217", "plan 33",
                    "PW-01-05", "AI198:AZ208", "TEST_ONLY / REVIEW_REQUIRED");
            String balanceText = sheetText(balance, formatter);
            assertThat(balanceText).contains("실제 물질수지 검증: 불가", "시험 시나리오 미선택",
                    "실제 자료 검토 상태", "산출 안 함", "실제 폐수·폐기물 발생량");
            assertThat(balanceText).doesNotContain("100 = 0.5 + 2.5 + 97", "시험 후단 이동 97 ton/day");
            assertThat(formatter.formatCellValue(balance.getRow(8).getCell(1))).isEqualTo("미제공");
            assertThat(balance.getRow(12).getCell(1).getCellType())
                    .isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
        }
    }

    @Test
    void includesSyntheticValuesOnlyWhenExplicitlySelectedAndRejectsMismatchedIds() throws Exception {
        assertThatThrownBy(() -> integrated.requireEligiblePlan(192, 217, 33))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> integrated.requireEligiblePlan(193, 216, 33))
                .isInstanceOf(ResponseStatusException.class);
        var diagram = diagramPlans.generate(33);
        var generated = integrated.createAndStoreIntegratedWorkbook(193, 217, 33, diagram, true);
        var downloaded = integrated.downloadIntegratedWorkbook(33, true);
        assertThat(generated.filename()).contains("synthetic-values");
        assertThat(downloaded.sha256()).isEqualTo(generated.sha256());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(downloaded.bytes()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(8);
            var history = workbook.getSheet("변경내역");
            var balance = workbook.getSheet("물질수지 검토");
            DataFormatter formatter = new DataFormatter();
            String text = sheetText(balance, formatter);
            assertThat(text).contains("TEST_ONLY 가상 계산입니다.", "100 = 0.5 + 2.5 + 97 ton/day",
                    "실제 물질수지 검증: 불가");
            assertThat(balance.getRow(8).getCell(1).getNumericCellValue()).isEqualTo(100.0);
            assertThat(history.getRow(5).getCell(1).getStringCellValue()).isEqualTo("C-PW15020");
        }
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
