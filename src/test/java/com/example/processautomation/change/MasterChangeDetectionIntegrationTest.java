package com.example.processautomation.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.excel.InvalidWorkbookException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MasterChangeDetectionIntegrationTest {
    @Autowired
    private ChangeDetectionService changeDetectionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsStep62SingleAddedEquipmentFixtureFromTheLockedOfficialMaster() throws Exception {
        Path fixture = Path.of("validation-results/2026-10-19-step6-2/TEST_ONLY-master-change-C-PW15020.xlsx");
        byte[] changed;
        if (Files.exists(fixture)) {
            changed = Files.readAllBytes(fixture);
        } else {
            changed = editWorkbook(workbook -> {
                Sheet sheet = workbook.getSheet("마스터시트");
                Row added = sheet.createRow(sheet.getLastRowNum() + 1);
                copyRowValues(sheet.getRow(1), added);
                Map<String, Integer> columns = headerColumns(sheet);
                setText(added, columns, "인허가 관리번호", "C-PW15020");
                setText(added, columns, "사업장ItemNo.", "WW-F-AFT10");
                setText(added, columns, "대분류번호", "PW-01");
                setText(added, columns, "중분류번호", "PW-01-05");
                setText(added, columns, "공정분류", "폐수처리");
                setText(added, columns, "시설명", "SCREEN FILTER");
                setText(added, columns, "인허가명칭", "SCREEN FILTER");
                setText(added, columns, "인허가대분류", "설비");
                setText(added, columns, "용량", "1,500");
                setText(added, columns, "용량단위", "㎥/시");
                setText(added, columns, "수량", "1ea");
                setText(added, columns, "폐수종류", "");
                setText(added, columns, "폐수량 (톤/일)", "");
                setText(added, columns, "폐수 이동처", "");
                setText(added, columns, "폐기물종류", "");
                setText(added, columns, "폐기물량 (톤/일)", "");
                setText(added, columns, "폐기물 이동처", "");
            });
            Files.createDirectories(fixture.getParent());
            Files.write(fixture, changed, java.nio.file.StandardOpenOption.CREATE_NEW);
        }

        ChangeDetectionResult result = compare(changed);
        assertThat(result.baselineRecordCount()).isEqualTo(1332);
        assertThat(result.uploadedRecordCount()).isEqualTo(1333);
        assertThat(result.addedCount()).isEqualTo(1);
        assertThat(result.removedCount()).isZero();
        assertThat(result.modifiedCount()).isZero();
        assertThat(result.movedCount()).isZero();
        assertThat(result.changes()).singleElement().satisfies(item -> {
            assertThat(item.changeItemId()).isNotNull();
            assertThat(item.changeType()).isEqualTo("ADDED");
            assertThat(item.permitManagementNo()).isEqualTo("C-PW15020");
            assertThat(item.businessItemNo()).isEqualTo("WW-F-AFT10");
            assertThat(item.facilityName()).isEqualTo("SCREEN FILTER");
        });

    }

    @Test
    void identicalMasterProducesNoChangesAndNeverChangesBaseline() throws Exception {
        ChangeDetectionResult result = compare(loadBaselineMaster());

        assertThat(result.baselineRecordCount()).isEqualTo(1332);
        assertThat(result.uploadedRecordCount()).isEqualTo(1332);
        assertThat(result.addedCount()).isZero();
        assertThat(result.removedCount()).isZero();
        assertThat(result.modifiedCount()).isZero();
        assertThat(result.movedCount()).isZero();
        assertThat(result.validationIssueCount()).isZero();
        assertThat(result.baselineModified()).isFalse();
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void detectsOneNewPermitIdAsAnAddition() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Row template = sheet.getRow(1);
            Row added = sheet.createRow(sheet.getLastRowNum() + 1);
            copyRowValues(template, added);
            Map<String, Integer> columns = headerColumns(sheet);
            setText(added, columns, "인허가 관리번호", "I-TEST0001");
            setText(added, columns, "사업장ItemNo.", "TEST-ITEM-0001");
            setText(added, columns, "대분류번호", "P-01");
            setText(added, columns, "중분류번호", "PP-01-01");
            setText(added, columns, "시설명", "TEST 신규 설비");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.addedCount()).isEqualTo(1);
        assertThat(result.removedCount()).isZero();
        assertThat(result.modifiedCount()).isZero();
        assertThat(result.changes()).anySatisfy(change -> {
            assertThat(change.changeType()).isEqualTo("ADDED");
            assertThat(change.permitManagementNo()).isEqualTo("I-TEST0001");
        });
        assertThat(result.baselineModified()).isFalse();
    }

    @Test
    void detectsRemovedEquipmentWithoutChangingTheBaseline() throws Exception {
        byte[] changed = editWorkbook(workbook -> workbook.getSheet("마스터시트").removeRow(
                workbook.getSheet("마스터시트").getRow(1)));

        ChangeDetectionResult result = compare(changed);

        assertThat(result.removedCount()).isEqualTo(1);
        assertThat(result.addedCount()).isZero();
        assertThat(result.changes()).anySatisfy(change -> assertThat(change.changeType()).isEqualTo("REMOVED"));
        assertThat(result.baselineModified()).isFalse();
    }

    @Test
    void detectsChangedCapacityWithBeforeAndAfterValues() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Map<String, Integer> columns = headerColumns(sheet);
            sheet.getRow(1).getCell(columns.get("용량")).setCellValue("999.5");
            setText(sheet.getRow(1), columns, "시설명", "설비명 TEST_ONLY 변경");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.modifiedCount()).isEqualTo(1);
        ChangeDetectionResult.DetectedChange change = result.changes().stream()
                .filter(item -> item.changeType().equals("MODIFIED"))
                .findFirst().orElseThrow();
        assertThat(change.permitManagementNo()).isEqualTo("I-PU11001");
        assertThat(change.changedFields()).containsKeys("시설명", "용량");
        assertThat(change.changedFields().get("용량").before()).isEqualTo("40");
        assertThat(change.changedFields().get("용량").after()).isEqualTo("999.5");
        assertThat(change.changedFields().get("시설명").after()).isEqualTo("설비명 TEST_ONLY 변경");
    }

    @Test
    void identifiesRelativeMasterOrderChangesSeparateFromDataChanges() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Map<Integer, CellValue> rowTwo = captureRowValues(sheet.getRow(1));
            Map<Integer, CellValue> rowThree = captureRowValues(sheet.getRow(2));
            replaceRowValues(sheet.getRow(1), rowThree);
            replaceRowValues(sheet.getRow(2), rowTwo);
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.addedCount()).isZero();
        assertThat(result.removedCount()).isZero();
        assertThat(result.modifiedCount()).isZero();
        assertThat(result.movedCount()).isEqualTo(2);
        assertThat(result.changes()).hasSize(2).allSatisfy(change -> {
            assertThat(change.changeType()).isEqualTo("MOVED");
            assertThat(change.changedFields()).containsKey("마스터시트 행 순서");
        });
    }

    @Test
    void insertingAnEquipmentBetweenExistingRowsDoesNotMarkLaterRowsAsMoved() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            int lastRow = sheet.getLastRowNum();
            sheet.shiftRows(2, lastRow, 1, true, false);
            Row added = sheet.createRow(2);
            copyRowValues(sheet.getRow(1), added);
            Map<String, Integer> columns = headerColumns(sheet);
            setText(added, columns, "인허가 관리번호", "I-TEST-MID-INSERT");
            setText(added, columns, "사업장ItemNo.", "TEST-MID-INSERT");
            setText(added, columns, "대분류번호", "P-01");
            setText(added, columns, "중분류번호", "PP-01-01");
            setText(added, columns, "시설명", "중간 삽입 TEST_ONLY 설비");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.addedCount()).isEqualTo(1);
        assertThat(result.removedCount()).isZero();
        assertThat(result.modifiedCount()).isZero();
        assertThat(result.movedCount()).isZero();
        assertThat(result.changes()).hasSize(1).first().satisfies(change -> {
            assertThat(change.changeType()).isEqualTo("ADDED");
            assertThat(change.permitManagementNo()).isEqualTo("I-TEST-MID-INSERT");
        });
    }

    @Test
    void reportsWastewaterAndWasteFieldChangesWithoutCreatingRoutes() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Map<String, Integer> columns = headerColumns(sheet);
            setText(sheet.getRow(1), columns, "폐수량 (톤/일)", "0");
            setText(sheet.getRow(1), columns, "폐수 이동처", "PW-01");
            setText(sheet.getRow(1), columns, "폐기물량 (톤/일)", "0.8");
            setText(sheet.getRow(1), columns, "폐기물 이동처", "PW-02");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.modifiedCount()).isEqualTo(1);
        var change = result.changes().stream().filter(item -> item.changeType().equals("MODIFIED")).findFirst().orElseThrow();
        assertThat(change.changedFields().values()).contains(
                new ChangeDetectionResult.FieldChange("", "0"),
                new ChangeDetectionResult.FieldChange("", "PW-01"),
                new ChangeDetectionResult.FieldChange("", "0.8"),
                new ChangeDetectionResult.FieldChange("", "PW-02"));
        assertThat(change.reviewRequiredItems()).anyMatch(item -> item.contains("Manual confirmation"));
        assertThat(change.automaticMappingSucceeded()).isTrue();
        assertThat(change.diagramMappingStatus()).isEqualTo("EXISTING_BASELINE_MAPPING_REVIEW_REQUIRED");
    }

    @Test
    void changedProcessCodeMakesBaselineDrawingLocationHistoricalOnly() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            setText(sheet.getRow(1), headerColumns(sheet), "대분류번호", "P-99");
        });

        ChangeDetectionResult result = compare(changed);

        var change = result.changes().stream().filter(item -> item.changeType().equals("MODIFIED")).findFirst().orElseThrow();
        assertThat(change.diagramMappingStatus()).isEqualTo("PROCESS_CODE_CHANGED_REVIEW_REQUIRED");
        assertThat(change.automaticMappingSucceeded()).isFalse();
        assertThat(change.reviewRequiredItems()).anyMatch(item -> item.contains("historical evidence only"));
        assertThat(change.affectedDiagrams()).allSatisfy(impact ->
                assertThat(impact.mappingStatus()).isEqualTo("PROCESS_CODE_CHANGED_REVIEW_REQUIRED"));
    }

    @Test
    void multiSheetBaselineMappingIsReportedAsReviewRequired() throws Exception {
        Map<String, Object> selected = jdbcTemplate.queryForMap("""
                SELECT e.source_row_number, e.permit_management_no
                FROM equipment_record e JOIN equipment_mapping m
                  ON m.equipment_id = e.id AND m.baseline_release_id = e.baseline_release_id
                WHERE e.baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')
                  AND m.mapping_status = 'MULTI_SHEET_REVIEW'
                ORDER BY e.master_order LIMIT 1
                """);
        int sourceRow = ((Number) selected.get("source_row_number")).intValue();
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            setText(sheet.getRow(sourceRow - 1), headerColumns(sheet), "시설명", "다중 시트 검토 테스트 변경");
        });

        ChangeDetectionResult result = compare(changed);

        var change = result.changes().stream().filter(item -> item.permitManagementNo()
                .equals(selected.get("permit_management_no"))).findFirst().orElseThrow();
        assertThat(change.automaticMappingSucceeded()).isFalse();
        assertThat(change.affectedDiagrams()).extracting(ChangeDetectionResult.DiagramImpact::mappingStatus)
                .contains("MULTI_SHEET_REVIEW", "PROCESS_CODE_ONLY_REVIEW");
        assertThat(change.affectedDiagrams().stream()
                .filter(impact -> "MULTI_SHEET_REVIEW".equals(impact.mappingStatus()))).hasSize(2);
        assertThat(change.affectedDiagrams().stream()
                .filter(impact -> "MULTI_SHEET_REVIEW".equals(impact.mappingStatus()))
                .map(ChangeDetectionResult.DiagramImpact::sheetName).distinct()).hasSize(2);
        assertThat(change.reviewRequiredItems()).anyMatch(item -> item.contains("MULTI_SHEET_REVIEW"));
    }

    @Test
    void unmatchedBaselineEquipmentIsNotAutomaticallyMapped() throws Exception {
        Map<String, Object> selected = jdbcTemplate.queryForMap("""
                SELECT e.source_row_number, e.permit_management_no
                FROM equipment_record e JOIN equipment_mapping m
                  ON m.equipment_id = e.id AND m.baseline_release_id = e.baseline_release_id
                WHERE e.baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')
                  AND m.mapping_status = 'UNMATCHED'
                ORDER BY e.master_order LIMIT 1
                """);
        int sourceRow = ((Number) selected.get("source_row_number")).intValue();
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            setText(sheet.getRow(sourceRow - 1), headerColumns(sheet), "시설명", "미매핑 검토 테스트 변경");
        });

        ChangeDetectionResult result = compare(changed);

        var change = result.changes().stream().filter(item -> item.permitManagementNo()
                .equals(selected.get("permit_management_no"))).findFirst().orElseThrow();
        assertThat(change.automaticMappingSucceeded()).isFalse();
        assertThat(change.diagramMappingStatus()).isEqualTo("BASELINE_MAPPING_UNRESOLVED");
        assertThat(change.affectedDiagrams()).anySatisfy(impact ->
                assertThat(impact.mappingStatus()).isEqualTo("UNMATCHED"));
        assertThat(change.reviewRequiredItems()).isNotEmpty();
    }

    @Test
    void newEquipmentWithoutAProcessDocumentIsNotAutomaticallyMapped() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Row template = sheet.getRow(1);
            Row added = sheet.createRow(sheet.getLastRowNum() + 1);
            copyRowValues(template, added);
            Map<String, Integer> columns = headerColumns(sheet);
            setText(added, columns, "인허가 관리번호", "I-TEST-NO-DIAGRAM");
            setText(added, columns, "사업장ItemNo.", "TEST-NO-DIAGRAM");
            setText(added, columns, "대분류번호", "P-99");
            setText(added, columns, "중분류번호", "PP-99-01");
            setText(added, columns, "시설명", "미매핑 테스트 설비");
        });

        ChangeDetectionResult result = compare(changed);

        var added = result.changes().stream().filter(item -> item.changeType().equals("ADDED")).findFirst().orElseThrow();
        assertThat(added.automaticMappingSucceeded()).isFalse();
        assertThat(added.diagramMappingStatus()).isEqualTo("NO_PROCESS_DIAGRAM_MATCH");
        assertThat(added.affectedDiagrams()).anySatisfy(impact ->
                assertThat(impact.mappingStatus()).isEqualTo("NO_PROCESS_DIAGRAM_MATCH"));
        assertThat(added.reviewRequiredItems()).isNotEmpty();
    }

    @Test
    void explicitZeroIsNotTreatedAsBlankOrAsAnAutomaticRoute() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            int quantityColumn = headerColumns(sheet).get(normalizeHeader("폐수량 (톤/일)"));
            sheet.getRow(1).getCell(quantityColumn).setCellValue(0d);
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.modifiedCount()).isEqualTo(1);
        assertThat(result.validationIssues()).noneMatch(issue -> issue.code().equals("INCOMPLETE_FLOW_INFORMATION"));
        assertThat(result.validationIssues()).noneMatch(issue -> issue.code().equals("UNRESOLVED_FLOW_DESTINATION"));
        var flowCandidates = changeDetectionService.listFlowCandidates(result.changeRunId());
        assertThat(flowCandidates).hasSize(1);
        assertThat(flowCandidates.get(0).get("status")).isEqualTo("EXPLICIT_ZERO");
        assertThat(flowCandidates.get(0).get("routeCreated")).isEqualTo(false);
    }

    @Test
    void partialWastewaterMovementIsRetainedForReviewNotSilentlyIgnored() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            int quantityColumn = headerColumns(sheet).get(normalizeHeader("폐수량 (톤/일)"));
            sheet.getRow(1).getCell(quantityColumn).setCellValue("2.5");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.validationIssues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("INCOMPLETE_FLOW_INFORMATION");
            assertThat(issue.fieldName()).contains("WASTEWATER");
        });
        assertThat(result.baselineModified()).isFalse();
        var flowCandidates = changeDetectionService.listFlowCandidates(result.changeRunId());
        assertThat(flowCandidates).hasSize(1);
        assertThat(flowCandidates.get(0).get("status")).isEqualTo("INCOMPLETE");
        assertThat(flowCandidates.get(0).get("routeCreated")).isEqualTo(false);
    }

    @Test
    void unresolvedMovementDestinationCreatesReviewIssueAndNoGraphEdge() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Map<String, Integer> columns = headerColumns(sheet);
            setText(sheet.getRow(1), columns, "폐수량 (톤/일)", "2.5");
            setText(sheet.getRow(1), columns, "폐수 이동처", "NO-SUCH-DESTINATION");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.validationIssues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("UNRESOLVED_FLOW_DESTINATION");
            assertThat(issue.detail()).contains("No route was created");
        });
        var flowCandidates = changeDetectionService.listFlowCandidates(result.changeRunId());
        assertThat(flowCandidates).hasSize(1);
        assertThat(flowCandidates.get(0).get("status")).isEqualTo("UNRESOLVED_DESTINATION");
        assertThat(flowCandidates.get(0).get("routeCreated")).isEqualTo(false);
    }

    @Test
    void knownProcessDestinationIsProposedForReviewButNeverCreatesARoute() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            Map<String, Integer> columns = headerColumns(sheet);
            setText(sheet.getRow(1), columns, "폐수량 (톤/일)", "2.5");
            setText(sheet.getRow(1), columns, "폐수 이동처", "PW-01");
        });

        ChangeDetectionResult result = compare(changed);

        assertThat(result.validationIssues()).noneMatch(issue -> issue.code().equals("UNRESOLVED_FLOW_DESTINATION"));
        var candidates = changeDetectionService.listFlowCandidates(result.changeRunId());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("status")).isEqualTo("READY_FOR_REVIEW");
        assertThat(candidates.get(0).get("matchedProcessCode")).isEqualTo("PW-01");
        assertThat(candidates.get(0).get("routeCreated")).isEqualTo(false);
    }

    @Test
    void missingPermitIdentifierIsRejectedBeforeRunIsStored() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            int permitColumn = headerColumns(sheet).get("인허가관리번호");
            sheet.getRow(1).getCell(permitColumn).setBlank();
        });

        assertThatThrownBy(() -> compare(changed))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("MISSING_PERMIT_ID");
    }

    @Test
    void workbookWithoutRequiredMasterSheetFailsClearly() throws Exception {
        byte[] invalid;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("wrong-name");
            workbook.write(output);
            invalid = output.toByteArray();
        }

        assertThatThrownBy(() -> compare(invalid))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("required sheet '마스터시트'");
    }

    @Test
    void duplicatePermitIdsAreRejectedBeforeAChangeRunIsStored() throws Exception {
        byte[] changed = editWorkbook(workbook -> {
            Sheet sheet = workbook.getSheet("마스터시트");
            int permitColumn = headerColumns(sheet).get("인허가관리번호");
            sheet.getRow(2).getCell(permitColumn).setCellValue("I-PU11001");
        });

        assertThatThrownBy(() -> compare(changed))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("DUPLICATE_PERMIT_ID");
    }

    private byte[] loadBaselineMaster() {
        return jdbcTemplate.queryForObject(
                "SELECT content_bytes FROM baseline_document WHERE document_role = 'MASTER' AND original_filename = ?",
                byte[].class, "전체 목록.xlsx");
    }

    private ChangeDetectionResult compare(byte[] master) {
        return changeDetectionService.compare(master, "test-master-upload.xlsx");
    }

    private byte[] editWorkbook(WorkbookEditor editor) throws Exception {
        byte[] testCopy = stripCalculationChain(loadBaselineMaster());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(testCopy));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            editor.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] stripCalculationChain(byte[] workbookBytes) {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(workbookBytes));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if ("xl/calcChain.xml".equals(entry.getName())) continue;
                byte[] content = input.readAllBytes();
                if ("[Content_Types].xml".equals(entry.getName()) || "xl/_rels/workbook.xml.rels".equals(entry.getName())) {
                    String xml = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    if ("[Content_Types].xml".equals(entry.getName())) {
                        xml = xml.replaceAll("<Override(?=[^>]*PartName=\"/xl/calcChain\\.xml\")[^>]*/>", "");
                    } else {
                        xml = xml.replaceAll("<Relationship(?=[^>]*relationships/calcChain)[^>]*/>", "");
                    }
                    content = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                output.write(content);
                output.closeEntry();
            }
            output.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to prepare an isolated TEST_ONLY master copy", exception);
        }
    }

    private Map<String, Integer> headerColumns(Sheet sheet) {
        Map<String, Integer> result = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        Row headers = sheet.getRow(0);
        for (Cell cell : headers) {
            String text = formatter.formatCellValue(cell).trim();
            if (!text.isBlank()) result.put(normalizeHeader(text), cell.getColumnIndex());
        }
        return result;
    }

    private void setText(Row row, Map<String, Integer> columns, String header, String value) {
        Integer column = columns.get(normalizeHeader(header));
        if (column == null) throw new IllegalArgumentException("Unknown master column: " + header);
        row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(value);
    }

    private String normalizeHeader(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s._()（）·&/\\-]", "");
    }

    private void copyRowValues(Row source, Row target) {
        DataFormatter formatter = new DataFormatter();
        for (Cell sourceCell : source) {
            target.createCell(sourceCell.getColumnIndex()).setCellValue(formatter.formatCellValue(sourceCell));
        }
    }

    private Map<Integer, CellValue> captureRowValues(Row row) {
        Map<Integer, CellValue> result = new HashMap<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) result.put(index, CellValue.capture(cell));
        }
        return result;
    }

    private void replaceRowValues(Row row, Map<Integer, CellValue> values) {
        for (int index = 0; index < row.getLastCellNum(); index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) cell.setBlank();
        }
        values.forEach((index, value) -> value.apply(row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)));
    }

    @FunctionalInterface
    private interface WorkbookEditor {
        void apply(XSSFWorkbook workbook) throws Exception;
    }

    private record CellValue(CellType type, String text, double number, boolean bool, String formula) {
        private static CellValue capture(Cell cell) {
            return switch (cell.getCellType()) {
                case STRING -> new CellValue(CellType.STRING, cell.getStringCellValue(), 0, false, null);
                case NUMERIC -> new CellValue(CellType.NUMERIC, null, cell.getNumericCellValue(), false, null);
                case BOOLEAN -> new CellValue(CellType.BOOLEAN, null, 0, cell.getBooleanCellValue(), null);
                case FORMULA -> new CellValue(CellType.FORMULA, null, 0, false, cell.getCellFormula());
                case ERROR -> new CellValue(CellType.ERROR, null, 0, false, null);
                case BLANK, _NONE -> new CellValue(CellType.BLANK, null, 0, false, null);
            };
        }

        private void apply(Cell cell) {
            switch (type) {
                case STRING -> cell.setCellValue(text);
                case NUMERIC -> cell.setCellValue(number);
                case BOOLEAN -> cell.setCellValue(bool);
                case FORMULA -> cell.setCellFormula(formula);
                case ERROR, BLANK, _NONE -> cell.setBlank();
            }
        }
    }
}
