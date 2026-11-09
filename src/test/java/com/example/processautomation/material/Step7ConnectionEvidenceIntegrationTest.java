package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Step7ConnectionEvidenceIntegrationTest {
    @Autowired private Step7ConnectionEvidenceService service;
    @Autowired private Step7ConnectionEvidenceWorkbookExporter exporter;
    @Autowired private JdbcTemplate jdbc;

    private java.util.Map<String, Integer> databaseSnapshot() {
        return java.util.Map.of(
                "baselineDocuments", jdbc.queryForObject("SELECT COUNT(*) FROM baseline_document WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')", Integer.class),
                "equipmentRecords", jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')", Integer.class),
                "confirmedRelations", jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0') AND relation_status = 'CONFIRMED'", Integer.class),
                "reviewRequiredRelations", jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0') AND relation_status = 'REVIEW_REQUIRED'", Integer.class));
    }

    private Path findXlsm(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".xlsm"))
                    .findFirst().orElseThrow();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "step7.connection.source", matches = ".+")
    void extractsTargetSheetEvidenceAndWritesFiveSheetReviewWorkbook() throws Exception {
        Path configuredSource = Path.of(System.getProperty("step7.connection.source"));
        Path sourcePath = Files.isDirectory(configuredSource)
                ? findXlsm(configuredSource) : configuredSource;
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        var dbBefore = databaseSnapshot();
        var data = service.buildWorkbook(sourceBytes, sourcePath.getFileName().toString());
        var dbAfter = databaseSnapshot();
        assertThat(dbAfter).isEqualTo(dbBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15' AND success = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('balance_evidence_review_case', 'balance_evidence_review_history')", Integer.class))
                .isEqualTo(2);

        assertThat(data.teamSha256()).isEqualTo("565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa");
        assertThat(data.drawingEvidence().sheetName()).isEqualTo("PW-01-05");
        assertThat(data.drawingEvidence().drawingObjectCount()).isEqualTo(103);
        assertThat(data.drawingEvidence().connectorEndpointBindings()).isZero();
        assertThat(data.drawingEvidence().equipmentLabels()).hasSizeGreaterThan(40);
        assertThat(data.drawingEvidence().flowLabels()).hasSizeGreaterThan(10);
        assertThat(data.candidates()).hasSize(33);
        assertThat(data.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.candidateOrigin()).isEqualTo("AUTO_EXTRACTED_DB_ID_PAIR");
            assertThat(candidate.rawStatus()).isEqualTo("RAW_EXTRACTED");
            assertThat(candidate.candidateStatus()).isEqualTo("CONNECTION_CANDIDATE");
            assertThat(candidate.evidenceStatus()).isEqualTo("EVIDENCE_INSUFFICIENT");
            assertThat(candidate.comparisonStatus()).isEqualTo("UNIT_OR_PERIOD_UNVERIFIED");
        });
        assertThat(data.candidates()).anySatisfy(candidate -> {
            assertThat(candidate.sourceRow()).isEqualTo(2712);
            assertThat(candidate.source()).isEqualTo("C-PW15017");
            assertThat(candidate.target()).isEqualTo("C-PW15018");
            assertThat(candidate.quantity().toPlainString()).isEqualTo("10000");
        });
        assertThat(data.manualFixtureAudits()).hasSize(7).allSatisfy(fixture -> {
            assertThat(fixture.actualConnectorId()).isEqualTo(fixture.expectedConnectorId());
            assertThat(fixture.actualSourceCell()).isEqualTo(fixture.expectedSourceCell());
            assertThat(fixture.actualTargetCell()).isEqualTo(fixture.expectedTargetCell());
            assertThat(fixture.explicitEndpointBinding()).isFalse();
            assertThat(fixture.auditNote()).contains("HAND_AUTHORED_TEST_FIXTURE");
        });
        assertThat(data.verifiedRelationCount()).isZero();
        assertThat(data.officialSnapshot().confirmedRelationCount()).isZero();
        assertThat(data.legacyIssues()).isNotEmpty().allSatisfy(issue -> {
            assertThat(issue.preExistingInSourceCache()).isTrue();
            assertThat(issue.causeConfirmed()).isFalse();
            assertThat(issue.status()).doesNotContain("CONFIRMED");
        });
        assertThat(data.syntheticDelta().baselineResidual()).isEqualByComparingTo("10");
        assertThat(data.syntheticDelta().afterResidual()).isEqualByComparingTo("15");
        assertThat(data.syntheticDelta().residualDelta()).isEqualByComparingTo("5");

        byte[] output = exporter.export(data);
        if (System.getProperty("step7.connection.output") != null) {
            Path outputDirectory = Path.of(System.getProperty("step7.connection.output"));
            Files.createDirectories(outputDirectory);
            Path savedWorkbook = outputDirectory.resolve("step7-3-evidence-review.xlsx");
            Files.write(savedWorkbook, output);
            output = Files.readAllBytes(savedWorkbook);
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheetName(0)).isEqualTo("연결 후보");
            assertThat(workbook.getSheetName(1)).isEqualTo("검토 필요 연결");
            assertThat(workbook.getSheetName(2)).isEqualTo("도면·DB 유량 비교");
            assertThat(workbook.getSheetName(3)).isEqualTo("기존 물질수지 불일치");
            assertThat(workbook.getSheetName(4)).isEqualTo("담당자 검토대장");
            assertThat(workbook.getSheet("연결 후보").getLastRowNum()).isEqualTo(33);
            assertThat(workbook.getSheet("검토 필요 연결").getLastRowNum()).isGreaterThanOrEqualTo(41);
            assertThat(workbook.getSheet("도면·DB 유량 비교").getLastRowNum()).isGreaterThanOrEqualTo(33);
            assertThat(workbook.getSheet("담당자 검토대장").getLastRowNum()).isEqualTo(34);
            var autoCandidates = workbook.getSheet("연결 후보");
            var db2712 = java.util.stream.StreamSupport.stream(autoCandidates.spliterator(), false)
                    .filter(row -> row.getCell(0) != null && row.getCell(0).getCellType() == CellType.NUMERIC
                            && row.getCell(0).getNumericCellValue() == 2712)
                    .findFirst().orElseThrow();
            assertThat(db2712.getCell(1).getStringCellValue()).isEqualTo("PW-01 폐수처리공정 ★변경허가.xlsx");
            assertThat(db2712.getCell(2).getStringCellValue()).isEqualTo("PW-01-05");
            assertThat(db2712.getCell(3).getStringCellValue()).isEqualTo("C-PW15017");
            assertThat(db2712.getCell(4).getStringCellValue()).isEqualTo("C-PW15018");
            assertThat(db2712.getCell(14).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(db2712.getCell(21).getStringCellValue()).isEqualTo("EVIDENCE_INSUFFICIENT");
            assertThat(db2712.getCell(22).getStringCellValue()).isEqualTo("UNIT_OR_PERIOD_UNVERIFIED");
            assertThat(db2712.getCell(23).getStringCellValue()).contains("PW0105-DB-2712");
            boolean fixtureAuditRowPresent = java.util.stream.StreamSupport
                    .stream(workbook.getSheet("검토 필요 연결").spliterator(), false)
                    .anyMatch(row -> row.getCell(0) != null
                            && row.getCell(0).getStringCellValue().startsWith("FIXTURE-"));
            assertThat(fixtureAuditRowPresent).isTrue();
            assertThat(workbook.getSheet("담당자 검토대장").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("template_schema_version");
            assertThat(workbook.getSheet("담당자 검토대장").getRow(2).getCell(4).getStringCellValue())
                    .startsWith("PW0105-AUTO-DBROW-");
            for (var sheet : workbook) {
                for (var row : sheet) {
                    for (var cell : row) {
                        assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getFontName())
                                .isEqualTo("맑은 고딕");
                        assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getColor())
                                .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                        assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                    }
                }
            }
        }
    }
}
