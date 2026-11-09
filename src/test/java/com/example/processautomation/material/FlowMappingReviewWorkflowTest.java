package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.MasterWorkbookParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FlowMappingReviewWorkflowTest {
    @Test
    void comparisonBuildsOverlappingPriorityGroupsKeepsSessionCacheAndExportsReviewOnlyArtifacts() throws Exception {
        byte[] master = masterWorkbook();
        byte[] source = sourceWorkbook();
        String originalSourceSha = sha256(source);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("sha256", sha256(master), "contentBytes", master)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        ProcessDiagramInventoryService inventory = mock(ProcessDiagramInventoryService.class);
        when(inventory.mappingReviewSnapshot()).thenReturn(new ProcessDiagramInventoryService.MappingReviewSnapshot(
                "source-fingerprint", "source-fingerprint", "HASH_MATCHED_CHECKPOINT", Instant.parse("2026-11-03T00:00:00Z"),
                15, 62, 62, 0, 0, 8094, 586, 0, 0, "MISSING_EVIDENCE", List.of(), "candidate-only inventory"));
        var p01 = occurrence("P-01", "EQ-2", "P-01-01", "C4", "D20");
        var p02 = occurrence("P-02", "EQ-2", "P-02-01", "F4", "K20");
        when(inventory.mappingReviewEquipmentOccurrences(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults("source-fingerprint", "source-fingerprint",
                        "HASH_MATCHED_CHECKPOINT", Map.of("EQ-1", 1, "EQ-2", 2, "=1+1", 0),
                        Map.of("EQ-1", List.of(occurrence("P-01", "EQ-1", "P-01-01", "A1", "D14")),
                                "EQ-2", List.of(p01, p02), "=1+1", List.of())));

        var service = new FlowMappingReviewService(inventory,
                new Step7MaterialBalanceComparisonService(new MasterWorkbookParser()), jdbc,
                new ObjectMapper(), new FlowMappingReviewWorkbookExporter());
        Map<String, Object> started = service.startComparison(source, "balance.xlsx", "alice", "session-a", 2, null, null);
        String analysisId = (String) started.get("analysisId");
        assertThat(analysisId).isNotBlank();
        assertThat(started.get("inputRows")).isEqualTo(3L);
        assertThat(started.get("verifiedFlowMappingCount")).isEqualTo(0);
        assertThat(started.get("officialReadiness")).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(started.get("physicalConnectionEvidence")).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        assertThat(started.get("sourceUploadBytesRetained")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> reviewSummary = (Map<String, Object>) started.get("reviewSummary");
        assertThat(reviewSummary).containsEntry("inputRows", 3L).containsEntry("candidateCountsAvailable", true)
                .containsEntry("bothEndpointCandidateRows", 2L).containsEntry("oneEndpointCandidateRows", 1L)
                .containsEntry("noEndpointCandidateRows", 0L).containsEntry("multipleLocationCandidateRows", 2L)
                .containsEntry("verifiedFlowMappingCount", 0);
        @SuppressWarnings("unchecked")
        List<FlowMappingReviewService.PriorityGroup> groups = (List<FlowMappingReviewService.PriorityGroup>) started.get("priorityGroups");
        assertThat(groups).filteredOn(group -> group.code().equals("MULTIPLE_LOCATION_CANDIDATES")).singleElement()
                .extracting(FlowMappingReviewService.PriorityGroup::count).isEqualTo(2L);
        assertThat(groups).filteredOn(group -> group.code().equals("DUPLICATE_ENDPOINT_PAIR")).singleElement()
                .extracting(FlowMappingReviewService.PriorityGroup::count).isEqualTo(2L);
        assertThat(groups).filteredOn(group -> group.code().equals("MISSING_FLOW_UNIT")).singleElement()
                .extracting(FlowMappingReviewService.PriorityGroup::count).isEqualTo(3L);

        Map<String, Object> repeatedUpload = service.startComparison(source, "balance.xlsx", "alice", "session-a", 2, null, null);
        assertThat(repeatedUpload.get("cacheHit")).isEqualTo(true);
        assertThat(repeatedUpload.get("analysisId")).isEqualTo(analysisId);
        var duplicatePage = service.comparisonPage(analysisId, "alice", "session-a", 0, 1, null, "DUPLICATE_ENDPOINT_PAIR");
        assertThat(duplicatePage.get("totalRows")).isEqualTo(2);
        assertThat(duplicatePage.get("pageSize")).isEqualTo(1);
        var noDuplicatePage = service.comparisonPage(analysisId, "alice", "session-a", 0, 50, null, "UNRESOLVED_EQUIPMENT");
        assertThat(noDuplicatePage.get("totalRows")).isEqualTo(1);
        assertThat(service.comparisonPage(analysisId, "alice", "session-a", 0, 50, null, "BOTH_ENDPOINT_CANDIDATES").get("totalRows"))
                .isEqualTo(2);
        assertThat(service.comparisonPage(analysisId, "alice", "session-a", 0, 50, null, "ONE_ENDPOINT_CANDIDATE").get("totalRows"))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.comparisonPage(
                analysisId, "alice", "session-a", 0, 50, null, "NOT_A_REAL_FILTER"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("지원하지 않는 검토 필터");
        var searchedPage = service.comparisonPage(analysisId, "alice", "session-a", 1, 1, "EQ-1", null);
        assertThat(searchedPage.get("totalRows")).isEqualTo(3);
        assertThat(searchedPage.get("page")).isEqualTo(1);
        assertThat((List<?>) searchedPage.get("rows")).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.comparisonPage(
                analysisId, "alice", "session-a", -1, 1, null, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.comparisonPage(
                analysisId, "alice", "other-session", 0, 50, null, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        byte[] csv = service.exportReview(analysisId, "alice", "session-a", "csv");
        String csvText = new String(csv, StandardCharsets.UTF_8);
        assertThat(csvText).contains("'=1+1").contains("MISSING_SOURCE_EQUIPMENT_CANDIDATE")
                .contains("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");

        byte[] json = service.exportReview(analysisId, "alice", "session-a", "json");
        var jsonNode = new ObjectMapper().readTree(json);
        assertThat(jsonNode.path("summary").path("inputRows").asInt()).isEqualTo(3);
        assertThat(jsonNode.path("rows").size()).isEqualTo(3);

        var filteredPage = service.comparisonPage(analysisId, "alice", "session-a", 0, 1, "EQ-2", "DUPLICATE_ENDPOINT_PAIR");
        assertThat(filteredPage.get("totalRows")).isEqualTo(2);
        var filteredCsv = service.exportReviewArtifact(analysisId, "alice", "session-a", "csv", "DUPLICATE_ENDPOINT_PAIR", "EQ-2");
        var filteredJson = service.exportReviewArtifact(analysisId, "alice", "session-a", "json", "DUPLICATE_ENDPOINT_PAIR", "EQ-2");
        var filteredXlsxArtifact = service.exportReviewArtifact(analysisId, "alice", "session-a", "xlsx", "DUPLICATE_ENDPOINT_PAIR", "EQ-2");
        assertThat(filteredCsv.rowCount()).isEqualTo(2);
        assertThat(filteredJson.rowCount()).isEqualTo(2);
        assertThat(filteredXlsxArtifact.rowCount()).isEqualTo(2);
        String filteredCsvText = new String(filteredCsv.bytes(), StandardCharsets.UTF_8);
        assertThat(filteredCsvText.split("\\r\\n", -1)).hasSize(4);
        assertThat(filteredCsvText).contains("DUPLICATE_ENDPOINT_PAIR");
        var filteredJsonNode = new ObjectMapper().readTree(filteredJson.bytes());
        assertThat(filteredJsonNode.path("rows").size()).isEqualTo(2);
        assertThat(filteredJsonNode.path("summary").path("exportedRows").asInt()).isEqualTo(2);
        assertThat(filteredJsonNode.path("summary").path("exportStatusFilter").asText()).isEqualTo("DUPLICATE_ENDPOINT_PAIR");
        assertThat(filteredJsonNode.path("summary").path("exportQuery").asText()).isEqualTo("EQ-2");
        byte[] filteredXlsx = filteredXlsxArtifact.bytes();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(filteredXlsx))) {
            assertThat(workbook.getSheet("필터 결과 행").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("담당자 검토 입력 양식").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("필터 결과 행").getRow(1).getCell(20).getStringCellValue())
                    .contains("DUPLICATE_ENDPOINT_PAIR");
            assertThat(workbook.getSheet("종합 현황").getRow(5).getCell(1).getNumericCellValue()).isEqualTo(3);
            assertThat(workbook.getSheet("종합 현황").getRow(6).getCell(1).getStringCellValue())
                    .isEqualTo("DUPLICATE_ENDPOINT_PAIR");
            assertThat(workbook.getSheet("종합 현황").getRow(8).getCell(1).getNumericCellValue()).isEqualTo(2);
            assertThat(workbook.getSheet("전체 입력 행")).isNull();
        }
        byte[] xlsx = service.exportReview(analysisId, "alice", "session-a", "xlsx");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(9);
            assertThat(workbook.getSheet("전체 입력 행").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("담당자 검토 입력 양식").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("전체 입력 행").getRow(1).getCell(1).getStringCellValue()).isEqualTo("입력시트");
            assertThat(workbook.getSheet("전체 입력 행").getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo(workbook.getSheet("전체 입력 행").getRow(1).getCell(0).getStringCellValue().split(":")[0]);
            var allRows = workbook.getSheet("전체 입력 행");
            int secondSourceRow = java.util.stream.IntStream.rangeClosed(1, allRows.getLastRowNum())
                    .filter(index -> allRows.getRow(index).getCell(2).getNumericCellValue() == 2)
                    .findFirst().orElseThrow();
            assertThat(allRows.getRow(secondSourceRow).getCell(17).getStringCellValue())
                    .contains("P-01-01").contains("P-02-01");
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getFontName()).isEqualTo("맑은 고딕");
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getColor()).isEqualTo((short) 8);
            }
        }
        verifyDraftFileRoundTrip(service, analysisId);
        assertThat(sha256(source)).isEqualTo(originalSourceSha);
    }

    private void verifyDraftFileRoundTrip(FlowMappingReviewService service, String analysisId) throws Exception {
        Path outputDirectory = Path.of(System.getProperty("step8.draftOutputDir",
                "target/test-artifacts/step8-7-draft/round-" + java.util.UUID.randomUUID()));
        Files.createDirectories(outputDirectory);
        for (String filename : List.of("synthetic-review-input.xlsx", "synthetic-review-input.csv",
                "validated-review-output.xlsx", "validated-review-output.csv", "import-validation.json"))
            assertThat(Files.exists(outputDirectory.resolve(filename))).as("preserve existing %s", filename).isFalse();

        byte[] template = service.exportReviewInputTemplate(analysisId, "alice", "session-a", "xlsx").bytes();
        byte[] authored = mutateReviewTemplate(template, 2, Map.of(11, "12.5", 13, "synthetic water", 15, "kg/day",
                17, "2026-01/TEST", 25, "reviewer-a", 26, "2026-11-03", 27, "checked, \"pending\"\nnext line",
                28, "REVIEW_IN_PROGRESS"));
        Files.write(outputDirectory.resolve("synthetic-review-input.xlsx"), authored);
        var imported = service.importReviewDraft(analysisId, "alice", "session-a", "synthetic-review-input.xlsx", authored);
        assertThat(imported.persisted()).isFalse();
        assertThat(imported.verifiedFlowMappingCount()).isZero();
        assertThat(imported.officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(imported.conflictRows()).isZero();
        assertThat(imported.missingRows()).isZero();
        assertThat(imported.rows()).filteredOn(row -> row.reviewStatus().equals("REVIEW_IN_PROGRESS"))
                .singleElement().satisfies(row -> {
                    assertThat(row.reviewedFlowValue()).isEqualTo("12.5");
                    assertThat(row.reviewedMaterialType()).isEqualTo("synthetic water");
                    assertThat(row.reviewedUnit()).isEqualTo("kg/day");
                    assertThat(row.reviewerComment()).contains("checked, \"pending\"").contains("next line");
                });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reviewDraftPreview(
                analysisId, imported.previewId(), "mallory", "session-a"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reviewDraftPreview(
                analysisId, imported.previewId(), "alice", "other-session"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        byte[] roundTripXlsx = service.exportReviewDraft(analysisId, imported.previewId(), "alice", "session-a", "xlsx").bytes();
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(roundTripXlsx))) {
            while (zip.getNextEntry() != null) { zip.transferTo(java.io.OutputStream.nullOutputStream()); zip.closeEntry(); }
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(roundTripXlsx))) {
            assertThat(workbook.getSheet("담당자 검토 입력 양식")).isNotNull();
            assertThat(workbook.getSheet("가져오기 판정")).isNotNull();
            for (var sheet : workbook) for (var row : sheet) for (var cell : row)
                assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
        }
        Files.write(outputDirectory.resolve("validated-review-output.xlsx"), roundTripXlsx);
        var reimportedXlsx = service.importReviewDraft(analysisId, "alice", "session-a", "roundtrip.xlsx", roundTripXlsx);
        assertThat(reimportedXlsx.conflictRows()).isZero();
        assertThat(reimportedXlsx.rows()).filteredOn(row -> row.reviewStatus().equals("REVIEW_IN_PROGRESS"))
                .singleElement().extracting(FlowMappingReviewDraft.DraftRow::reviewedFlowValue).isEqualTo("12.5");

        byte[] authoredCsv = makeCsvWithReview(template, 2, 11, "12.5", 13, "synthetic water", 15, "kg/day",
                17, "2026-01/TEST", 25, "reviewer-b", 26, "2026-11-03", 27, "note, with \"quotes\"\nand newline",
                28, "REVIEW_COMPLETED_PENDING_VALIDATION");
        Files.write(outputDirectory.resolve("synthetic-review-input.csv"), authoredCsv);
        var importedCsv = service.importReviewDraft(analysisId, "alice", "session-a", "synthetic-review-input.csv", authoredCsv);
        assertThat(importedCsv.conflictRows()).isZero();
        assertThat(importedCsv.verifiedFlowMappingCount()).isZero();
        assertThat(importedCsv.rows()).filteredOn(row -> row.reviewStatus().equals("REVIEW_COMPLETED_PENDING_VALIDATION"))
                .singleElement().satisfies(row -> assertThat(row.reviewerComment()).contains("note, with \"quotes\"").contains("and newline"));
        byte[] roundTripCsv = service.exportReviewDraft(analysisId, importedCsv.previewId(), "alice", "session-a", "csv").bytes();
        Files.write(outputDirectory.resolve("validated-review-output.csv"), roundTripCsv);
        var reimportedCsv = service.importReviewDraft(analysisId, "alice", "session-a", "roundtrip.csv", roundTripCsv);
        assertThat(reimportedCsv.conflictRows()).isZero();
        assertThat(reimportedCsv.rows()).filteredOn(row -> row.reviewStatus().equals("REVIEW_COMPLETED_PENDING_VALIDATION"))
                .singleElement().extracting(FlowMappingReviewDraft.DraftRow::reviewerComment).asString().contains("and newline");

        byte[] shaConflict = mutateReviewTemplate(template, 2, Map.of(3, "0".repeat(64)));
        var shaPreview = service.importReviewDraft(analysisId, "alice", "session-a", "sha-conflict.xlsx", shaConflict);
        assertThat(shaPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.SOURCE_SHA_MISMATCH)
                .hasSize(1);
        byte[] equipmentConflict = mutateReviewTemplate(template, 2, Map.of(6, "SYN-CHANGED"));
        var equipmentPreview = service.importReviewDraft(analysisId, "alice", "session-a", "equipment-conflict.xlsx", equipmentConflict);
        assertThat(equipmentPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.ORIGINAL_EQUIPMENT_ID_CHANGED)
                .hasSize(1);
        byte[] rowConflict = mutateReviewTemplate(template, 2, Map.of(2, "99999"));
        var rowPreview = service.importReviewDraft(analysisId, "alice", "session-a", "row-conflict.xlsx", rowConflict);
        assertThat(rowPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.SOURCE_ROW_MISMATCH)
                .hasSize(1);
        byte[] invalidStatus = mutateReviewTemplate(template, 2, Map.of(28, "APPROVED"));
        var invalidStatusPreview = service.importReviewDraft(analysisId, "alice", "session-a", "invalid-status.xlsx", invalidStatus);
        assertThat(invalidStatusPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.INVALID_ROW)
                .hasSize(1);
        byte[] invalidQuantity = mutateReviewTemplate(template, 2, Map.of(11, "NaN"));
        var invalidQuantityPreview = service.importReviewDraft(analysisId, "alice", "session-a", "invalid-quantity.xlsx", invalidQuantity);
        assertThat(invalidQuantityPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.INVALID_ROW)
                .hasSize(1);
        byte[] csvFormula = makeCsvWithReview(template, 2, 27, "=HYPERLINK(\"https://example.invalid\")");
        var csvFormulaPreview = service.importReviewDraft(analysisId, "alice", "session-a", "formula.csv", csvFormula);
        assertThat(csvFormulaPreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.INVALID_ROW)
                .hasSize(1);
        byte[] duplicate = duplicateReviewRow(template, 2);
        var duplicatePreview = service.importReviewDraft(analysisId, "alice", "session-a", "duplicate.xlsx", duplicate);
        assertThat(duplicatePreview.rows()).filteredOn(row -> row.disposition() == FlowMappingReviewDraft.RowDisposition.DUPLICATE_REVIEW_ROW)
                .hasSize(2);
        byte[] formula = insertReviewFormula(template, 2, 27);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importReviewDraft(
                analysisId, "alice", "session-a", "formula.xlsx", formula))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        var csvTemplate = service.exportReviewInputTemplate(analysisId, "alice", "session-a", "csv");
        var csvTemplateBytes = csvTemplate.bytes();
        var csvTemplatePreview = service.importReviewDraft(analysisId, "alice", "session-a", "template.csv", csvTemplateBytes);
        assertThat(csvTemplatePreview.uploadedRows()).isEqualTo(3);
        assertThat(csvTemplatePreview.reviewRequiredRows()).isEqualTo(3);
        Files.write(outputDirectory.resolve("import-validation.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.ofEntries(
                Map.entry("xlsxRoundTripRows", reimportedXlsx.rows().size()), Map.entry("csvRoundTripRows", reimportedCsv.rows().size()),
                Map.entry("shaConflictDisposition", "SOURCE_SHA_MISMATCH"), Map.entry("equipmentConflictDisposition", "ORIGINAL_EQUIPMENT_ID_CHANGED"),
                Map.entry("rowConflictDisposition", "SOURCE_ROW_MISMATCH"), Map.entry("invalidStatus", "INVALID_ROW"),
                Map.entry("invalidQuantity", "INVALID_ROW"), Map.entry("csvFormulaInjection", "INVALID_ROW"),
                Map.entry("duplicateRows", "DUPLICATE_REVIEW_ROW"), Map.entry("sourceBytesUnchanged", true),
                Map.entry("verifiedFlowMappingCount", 0), Map.entry("officialReadiness", "CALCULATION_UNAVAILABLE"),
                Map.entry("persisted", false))));
        service.clearReviewDraftsForSession("session-a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reviewDraftPreview(
                analysisId, imported.previewId(), "alice", "session-a"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private byte[] mutateReviewTemplate(byte[] source, int sourceRow, Map<Integer, String> updates) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("담당자 검토 입력 양식");
            var row = java.util.stream.IntStream.rangeClosed(1, sheet.getLastRowNum()).mapToObj(sheet::getRow)
                    .filter(candidate -> candidate.getCell(2).getNumericCellValue() == sourceRow).findFirst().orElseThrow();
            updates.forEach((column, value) -> row.getCell(column).setCellValue(value));
            workbook.write(output); return output.toByteArray();
        }
    }

    private byte[] duplicateReviewRow(byte[] source, int sourceRow) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("담당자 검토 입력 양식");
            var original = java.util.stream.IntStream.rangeClosed(1, sheet.getLastRowNum()).mapToObj(sheet::getRow)
                    .filter(candidate -> candidate.getCell(2).getNumericCellValue() == sourceRow).findFirst().orElseThrow();
            var copy = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int column = 0; column < 29; column++) {
                var sourceCell = original.getCell(column); var cell = copy.createCell(column);
                cell.setCellStyle(sourceCell.getCellStyle());
                if (sourceCell.getCellType() == CellType.NUMERIC) cell.setCellValue(sourceCell.getNumericCellValue());
                else cell.setCellValue(sourceCell.getStringCellValue());
            }
            workbook.write(output); return output.toByteArray();
        }
    }

    private byte[] insertReviewFormula(byte[] source, int sourceRow, int column) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("담당자 검토 입력 양식");
            var row = java.util.stream.IntStream.rangeClosed(1, sheet.getLastRowNum()).mapToObj(sheet::getRow)
                    .filter(candidate -> candidate.getCell(2).getNumericCellValue() == sourceRow).findFirst().orElseThrow();
            row.getCell(column).setCellFormula("1+1");workbook.write(output);return output.toByteArray();
        }
    }

    private byte[] makeCsvWithReview(byte[] template, int sourceRow, Object... updates) throws Exception {
        Map<Integer, String> replacements = new java.util.HashMap<>();
        for (int i = 0; i < updates.length; i += 2) replacements.put((Integer) updates[i], String.valueOf(updates[i + 1]));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template)); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("담당자 검토 입력 양식");var formatter = new DataFormatter(java.util.Locale.ROOT);
            StringBuilder csv = new StringBuilder("\uFEFF");
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);if (row == null) continue;
                List<String> values = new java.util.ArrayList<>();
                for (int column = 0; column < 29; column++) {
                    String value = formatter.formatCellValue(row.getCell(column));
                    if (r > 0 && row.getCell(2).getNumericCellValue() == sourceRow && replacements.containsKey(column)) value = replacements.get(column);
                    values.add(csv(value));
                }
                csv.append(String.join(",", values)).append("\r\n");
            }
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private byte[] masterWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("마스터시트");
            var header = sheet.createRow(0);
            String[] headers = {"인허가관리번호", "사업장 Item No", "중분류번호", "대분류번호", "시설명"};
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            String[][] records = {{"EQ-1", "ITEM-1", "P01", "P-01", "설비 1"}, {"EQ-2", "ITEM-2", "P02", "P-02", "설비 2"}};
            for (int r = 0; r < records.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < records[r].length; c++) row.createCell(c).setCellValue(records[r][c]);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] sourceWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("입력시트");
            String[] headers = {"어디서", "어디로", "보조열", "질량", "종류", "보내는 중분류", "받는 중분류",
                    "보내는 대분류", "받는 대분류", "질량수지 계산 제외"};
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            String[][] rows = {
                    {"EQ-1", "EQ-2", "EQ-1EQ-2", "10", "", "P01", "P02", "P-01", "P-02", ""},
                    {"EQ-1", "EQ-2", "EQ-1EQ-2", "0", "water", "P01", "P02", "P-01", "P-02", ""},
                    {"=1+1", "EQ-1", "=1+1EQ-1", "1", "", "P01", "P02", "P-01", "P-02", ""}
            };
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private ProcessDiagramInventoryService.EquipmentOccurrence occurrence(String process, String id, String sheet,
            String firstCell, String lastCell) {
        return new ProcessDiagramInventoryService.EquipmentOccurrence(process, process + ".xlsx", "a".repeat(64), sheet,
                id, "후보 설비", firstCell, lastCell, List.of(firstCell), "CANDIDATE", "문자열 ID 후보", 
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
