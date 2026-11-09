package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProcessDiagramInventoryBatchIntegrationTest {
    private static final Path OUTPUT = Path.of("validation-results", "2026-10-31-process-diagram-inventory");
    @Autowired private ProcessDiagramInventoryService inventory;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void resumesFiveSheetBatchesAndExportsReopenableReviewWorkbook() throws Exception {
        ProcessDiagramInventoryService.InventoryRunResult result = inventory.inventoryProgress();
        int previousCompleted = result.completedSheets();
        int previousFailed = result.failedSheets();
        int calls = 0;
        while (result.remainingSheets() > 0) {
            result = inventory.runNextInventoryBatch(5);
            calls++;
            assertThat(result.completedSheets() + result.failedSheets()).isGreaterThanOrEqualTo(previousCompleted + previousFailed);
            previousCompleted = result.completedSheets();
            previousFailed = result.failedSheets();
            if (result.processedThisRequest() == 0 && result.remainingSheets() > 0) {
                throw new AssertionError("Inventory made no progress while worksheets remain: " + result);
            }
        }
        assertThat(result.documentCount()).isEqualTo(15);
        assertThat(result.totalSheets()).isEqualTo(62);
        assertThat(result.completedSheets() + result.failedSheets()).isEqualTo(62);
        assertThat(calls).isLessThanOrEqualTo(13);

        byte[] workbookBytes = inventory.exportSavedInventory();
        Files.createDirectories(OUTPUT);
        Files.write(OUTPUT.resolve("official-process-diagram-inventory_TEST_ONLY.xlsx"), workbookBytes);
        Files.write(OUTPUT.resolve("progress.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            assertThat(workbook.getSheet("공정도 요약")).isNotNull();
            assertThat(workbook.getSheet("시트·사용영역")).isNotNull();
            assertThat(workbook.getSheet("설비박스 후보")).isNotNull();
            assertThat(workbook.getSheet("위치후보")).isNotNull();
            assertThat(workbook.getSheet("DrawingML 객체")).isNotNull();
            assertThat(workbook.getSheet("배치 실패·미분석")).isNotNull();
            assertThat(workbook.getSheet("시트·사용영역").getLastRowNum()).isEqualTo(62);
            assertThat(workbook.getSheet("배치 실패·미분석").getLastRowNum()).isEqualTo(result.failedSheets());
        }
    }
}
