package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DiagramEditPlanIntegrationTest {
    private static final String SOURCE_FILENAME = "TEST_ONLY-master-change-C-PW15020.xlsx";
    private static final String WORKBOOK = "PW-01 폐수처리공정 ★변경허가.xlsx";

    @Autowired
    private DiagramEditPlanService editPlanService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void uploadedChangeItemProducesReviewPlanAndExplicitTestOnlyWorkbookOnly() throws Exception {
        Map<String, Object> analyzedItem = jdbc.queryForMap("""
                SELECT r.id AS changeRunId, i.id AS changeItemId, r.analysis_mode AS analysisMode,
                       r.total_records AS totalRecords, r.added_count AS addedCount, r.source_sha256 AS sourceSha256
                FROM change_run r JOIN change_item i ON i.change_run_id = r.id
                WHERE r.source_filename = ? AND i.change_type = 'ADDED'
                  AND i.permit_management_no = 'C-PW15020'
                ORDER BY r.id DESC LIMIT 1
                """, SOURCE_FILENAME);
        long changeRunId = ((Number) analyzedItem.get("changeRunId")).longValue();
        long changeItemId = ((Number) analyzedItem.get("changeItemId")).longValue();
        assertThat(analyzedItem).containsEntry("analysisMode", "TEST_ONLY")
                .containsEntry("totalRecords", 1333)
                .containsEntry("addedCount", 1)
                .containsEntry("sourceSha256", "487e68ecd59a1ea1859344508d4e88e95057ba3d1667d317162c4ac65d19c4d0");
        long confirmedBefore = relationCount("CONFIRMED");
        long reviewBefore = relationCount("REVIEW_REQUIRED");
        String baselineHashBefore = jdbc.queryForObject("""
                SELECT sha256 FROM baseline_document
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')
                  AND original_filename = ?
                """, String.class, WORKBOOK);

        var changeItem = jdbc.queryForMap("""
                SELECT change_type, permit_management_no, diagram_mapping_status
                FROM change_item WHERE id = ? AND change_run_id = ?
                """, changeItemId, changeRunId);
        assertThat(changeItem).containsEntry("change_type", "ADDED")
                .containsEntry("permit_management_no", "C-PW15020")
                .containsEntry("diagram_mapping_status", "PROCESS_CODE_ONLY_REVIEW");

        var reviewPlan = editPlanService.createPlan(new DiagramEditPlanService.CreatePlanRequest(
                changeRunId, changeItemId, null));
        assertThat(reviewPlan.get("planStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(reviewPlan.get("automaticEditAllowed")).isEqualTo(false);
        long blockedPlanId = ((Number) reviewPlan.get("planId")).longValue();
        assertThatThrownBy(() -> editPlanService.generate(blockedPlanId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVIEW_REQUIRED");

        var explicitScenario = new DiagramEditPlanService.TestOnlyScenario(true, "PW-01-05", WORKBOOK,
                "PW-01-05", "C-PW15009", "ACTIVATED CARBON FILTER",
                "C-PW15010", "FINAL EFFLUENT TANK", "여과시설");
        var readyPlan = editPlanService.createPlan(new DiagramEditPlanService.CreatePlanRequest(
                changeRunId, changeItemId, explicitScenario));
        assertThat(readyPlan.get("planStatus")).isEqualTo("TEST_ONLY_READY");
        assertThat(readyPlan.get("automaticEditAllowed")).isEqualTo(true);
        assertThat(readyPlan.get("relationStatus")).isEqualTo("REVIEW_REQUIRED");
        assertThat(readyPlan.get("officialApprovalCreated")).isEqualTo(false);
        assertThat(readyPlan.get("equipment")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> equipment = (Map<String, Object>) readyPlan.get("equipment");
        assertThat(equipment).containsEntry("managementNo", "C-PW15020")
                .containsEntry("facilityName", "SCREEN FILTER")
                .containsEntry("businessItemNo", "WW-F-AFT10")
                .containsEntry("capacity", "1,500")
                .containsEntry("capacityUnit", "㎥/시")
                .containsEntry("quantity", "1ea");

        long readyPlanId = ((Number) readyPlan.get("planId")).longValue();
        var output = editPlanService.generate(readyPlanId);
        assertThat(output.bytes()).isNotEmpty();
        assertThat(output.sha256()).hasSize(64);
        assertThat(output.filename()).contains("TEST_ONLY", "C-PW15020");
        try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(output.bytes()))) {
            var sheet = workbook.getSheet("PW-01-05");
            var formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(34))).isEqualTo("C-PW15020");
            assertThat(formatter.formatCellValue(sheet.getRow(200).getCell(34))).isEqualTo("SCREEN FILTER");
            assertThat(formatter.formatCellValue(sheet.getRow(202).getCell(34))).isEqualTo("1,500㎥/시");
            assertThat(formatter.formatCellValue(sheet.getRow(203).getCell(34))).isEqualTo("1ea");
            assertThat(formatter.formatCellValue(sheet.getRow(197).getCell(47))).isEqualTo(" ");
        }
        var drawingObjects = new OoxmlPackageInspector().inspect(output.bytes()).sheets().stream()
                .filter(sheet -> sheet.sheetName().equals("PW-01-05"))
                .flatMap(sheet -> sheet.drawingObjects().stream()).toList();
        assertThat(drawingObjects).extracting(WorkbookInventory.DrawingObject::shapeId).doesNotHaveDuplicates();
        assertThat(drawingObjects).anySatisfy(object -> assertThat(object.objectName())
                .isEqualTo("TEST_ONLY C-PW15020 flow connector"));

        assertThat(relationCount("CONFIRMED")).isEqualTo(confirmedBefore);
        assertThat(relationCount("REVIEW_REQUIRED")).isEqualTo(reviewBefore);
        String baselineHashAfter = jdbc.queryForObject("""
                SELECT sha256 FROM baseline_document
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')
                  AND original_filename = ?
                """, String.class, WORKBOOK);
        assertThat(baselineHashAfter).isEqualTo(baselineHashBefore);

        Path apiArtifact = Path.of("validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5.xlsx");
        if (Files.isRegularFile(apiArtifact)) {
            try (var apiWorkbook = WorkbookFactory.create(Files.newInputStream(apiArtifact))) {
                var apiSheet = apiWorkbook.getSheet("PW-01-05");
                var apiFormatter = new DataFormatter();
                assertThat(apiFormatter.formatCellValue(apiSheet.getRow(197).getCell(34))).isEqualTo("C-PW15020");
                assertThat(apiFormatter.formatCellValue(apiSheet.getRow(200).getCell(34))).isEqualTo("SCREEN FILTER");
                assertThat(apiFormatter.formatCellValue(apiSheet.getRow(202).getCell(34))).isEqualTo("1,500㎥/시");
                assertThat(apiFormatter.formatCellValue(apiSheet.getRow(203).getCell(34))).isEqualTo("1ea");
                // This archived 2026-10-19 artifact predates the blank placeholder update.
                assertThat(apiFormatter.formatCellValue(apiSheet.getRow(197).getCell(47)))
                        .isEqualTo("TEST_ONLY / REVIEW_REQUIRED");
            }
            var apiDrawingObjects = new OoxmlPackageInspector().inspect(Files.readAllBytes(apiArtifact)).sheets().stream()
                    .filter(sheet -> sheet.sheetName().equals("PW-01-05"))
                    .flatMap(sheet -> sheet.drawingObjects().stream()).toList();
            assertThat(apiDrawingObjects).extracting(WorkbookInventory.DrawingObject::shapeId).doesNotHaveDuplicates();
        }
    }

    private long relationCount(String status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = ?",
                Long.class, status);
        return count == null ? 0 : count;
    }
}
