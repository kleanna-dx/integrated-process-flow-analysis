package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Step7ReviewNetworkIntegrationTest {
    @Autowired private Step7ConnectionEvidenceService evidenceService;
    @Autowired private Step7ReviewLedgerWorkflowService reviewLedger;
    @Autowired private Step7ReviewNetworkWorkflowService workflow;
    @Autowired private Step7ReviewNetworkWorkbookExporter exporter;
    @Autowired private JdbcTemplate jdbc;

    private Path source() throws Exception {
        Path configured = Path.of(System.getProperty("step7.review.source"));
        if (Files.isDirectory(configured)) {
            try (var files = Files.list(configured)) {
                return files.filter(path -> path.getFileName().toString().endsWith(".xlsm")).findFirst().orElseThrow();
            }
        }
        return configured;
    }

    @Test
    @EnabledIfSystemProperty(named = "step7.review.source", matches = ".+")
    void adaptsActualReviewsReadOnlyAndRunsSeparatedSyntheticScenarios() throws Exception {
        Path source = source();
        byte[] teamBytes = Files.readAllBytes(source);
        var workbookData = evidenceService.buildWorkbook(teamBytes, source.getFileName().toString());
        var snapshot = reviewLedger.createSnapshot(workbookData);
        int confirmedBefore = jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'", Integer.class);
        int caseCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case", Integer.class);
        int historyCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_history", Integer.class);

        var report = workflow.buildIntegrationReport(snapshot.snapshotId());
        assertThat(report.actualCandidateCount()).isEqualTo(33);
        assertThat(report.actualEvidenceVerifiedCount()).isZero();
        assertThat(report.actualEligibleConnectionCount()).isZero();
        assertThat(report.actualReadiness().calculationAvailable()).isFalse();
        assertThat(report.actualReadiness().status()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(report.actualReadiness().candidates()).hasSize(33)
                .allSatisfy(candidate -> {
                    assertThat(candidate.eligible()).isFalse();
                    assertThat(candidate.reasons()).contains("REVIEW_STATE_NOT_EVIDENCE_VERIFIED:UNREVIEWED");
                });

        var at20 = scenario(report, "A");
        assertThat(at20.calculationStatus()).isEqualTo("CALCULATED");
        assertThat(at20.externalInflow()).isEqualByComparingTo("200");
        assertThat(at20.externalOutflow()).isEqualByComparingTo("200");
        assertThat(at20.afterResidual()).isEqualByComparingTo("0");
        assertThat(at20.network().edges()).hasSize(5);

        var at10 = scenario(report, "F-10");
        assertThat(at10.changedConnectionId()).isEqualTo("P2-X-P1-N");
        assertThat(at10.oldQuantity()).isEqualByComparingTo("20");
        assertThat(at10.newQuantity()).isEqualByComparingTo("10");
        assertThat(edge(at10, "P2-X-P2-OUT").quantity()).isEqualByComparingTo("90");
        assertThat(edge(at10, "P1-N-P1-OUT").quantity()).isEqualByComparingTo("110");
        var at50 = scenario(report, "F-50");
        assertThat(edge(at50, "P2-X-P2-OUT").quantity()).isEqualByComparingTo("50");
        assertThat(edge(at50, "P1-N-P1-OUT").quantity()).isEqualByComparingTo("150");
        assertThat(at50.externalInflow()).isEqualByComparingTo("200");
        assertThat(at50.externalOutflow()).isEqualByComparingTo("200");

        var negative = scenario(report, "H-120");
        assertThat(negative.calculationStatus()).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(edge(negative, "P2-X-P2-OUT").quantity()).isEqualByComparingTo("-20");
        var parallel = scenario(report, "D-parallel");
        assertThat(parallel.network().edges().stream()
                .filter(edge -> edge.sourceNodeId().equals("S74B-X") && edge.targetNodeId().equals("S74B-N")))
                .hasSize(2);
        assertThat(parallel.network().edges().stream().map(DirectedProcessNetwork.Edge::connectionId).distinct()).hasSize(6);

        var recycle20 = scenario(report, "G-recycle-20");
        var recycle30 = scenario(report, "G-recycle-30");
        assertThat(recycle20.calculationStatus()).isEqualTo("CALCULATED");
        assertThat(recycle30.calculationStatus()).isEqualTo("CALCULATED");
        assertThat(edge(recycle30, "P2-X-P1-A").quantity()).isEqualByComparingTo("30");
        assertThat(recycle30.externalInflow()).isEqualByComparingTo("100");
        assertThat(recycle30.externalOutflow()).isEqualByComparingTo("100");

        var beforeChange = scenario(report, "I-baseline-5");
        var afterChange = scenario(report, "I-after-8");
        assertThat(beforeChange.calculationStatus()).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat(beforeChange.afterResidual()).isEqualByComparingTo("5");
        assertThat(afterChange.baselineBalanceStatus()).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat(afterChange.calculationStatus()).isEqualTo("RESIDUAL_UNRESOLVED");
        assertThat(afterChange.changeImpactStatus()).isEqualTo("RESIDUAL_INCREASED");
        assertThat(afterChange.baselineResidual()).isEqualByComparingTo("5");
        assertThat(afterChange.afterResidual()).isEqualByComparingTo("8");
        assertThat(afterChange.residualDelta()).isEqualByComparingTo("3");
        assertThat(afterChange.externalStreamBalances()).hasSize(1)
                .singleElement().satisfies(stream -> {
                    assertThat(stream.baselineResidual()).isEqualByComparingTo("5");
                    assertThat(stream.afterResidual()).isEqualByComparingTo("8");
                    assertThat(stream.residualDelta()).isEqualByComparingTo("3");
                });

        assertThat(scenario(report, "E-partial-scope").calculationStatus()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(scenario(report, "E-partial-scope").issues()).contains("EXPECTED_CONNECTION_SCOPE_INCOMPLETE");
        assertThat(scenario(report, "C-unreviewed").calculationStatus()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(scenario(report, "B-incomplete-unit").calculationStatus()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(scenario(report, "B-incomplete-unit").candidates().stream()
                .filter(candidate -> "P2-X-P1-N".equals(candidate.candidateId())).findFirst().orElseThrow().reasons())
                .contains("UNIT_OR_PERIOD_UNVERIFIED");
        assertThat(scenario(report, "J-stale-history").calculationStatus()).isEqualTo("CALCULATION_UNAVAILABLE");

        int actualVerifiedAfter = jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case WHERE review_state='EVIDENCE_VERIFIED'", Integer.class);
        assertThat(actualVerifiedAfter).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'", Integer.class)).isEqualTo(confirmedBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case", Integer.class)).isEqualTo(caseCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_history", Integer.class)).isEqualTo(historyCountBefore);
        assertThat(Files.readAllBytes(source)).containsExactly(teamBytes);

        byte[] reportBytes = exporter.export(report);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(reportBytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheetName(0)).isEqualTo("검토 연결 현황");
            assertThat(workbook.getSheetName(1)).isEqualTo("계산망 변환 결과");
            assertThat(workbook.getSheetName(2)).isEqualTo("계산 불가·보류 사유");
            assertThat(workbook.getSheetName(3)).isEqualTo("TEST_ONLY 시나리오 결과");
            assertThat(workbook.getSheetName(4)).isEqualTo("실제 적용 준비 상태");
            assertThat(workbook.getSheet("검토 연결 현황").getLastRowNum()).isGreaterThanOrEqualTo(33);
            for (int rowIndex = 1; rowIndex <= 33; rowIndex++) {
                var candidateRow = workbook.getSheet("검토 연결 현황").getRow(rowIndex);
                assertThat(candidateRow.getCell(8).getNumericCellValue()).isPositive();
                assertThat(candidateRow.getCell(9).getStringCellValue()).isNotBlank();
                assertThat(candidateRow.getCell(10).getStringCellValue()).isNotBlank();
            }
            assertThat(workbook.getSheet("계산망 변환 결과").getLastRowNum()).isGreaterThan(1);
            assertThat(workbook.getSheet("TEST_ONLY 시나리오 결과").getLastRowNum()).isEqualTo(13);
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getFontName()).isEqualTo("맑은 고딕");
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt()).getColor())
                        .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                assertThat(cell.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
            }
        }
        if (System.getProperty("step7.review.network.output") != null) {
            Path outputDir = Path.of(System.getProperty("step7.review.network.output"));
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve("Step7_4B_network_validation_TEST_ONLY.xlsx"), reportBytes);
        }
        TestTransaction.flagForRollback();
        TestTransaction.end();
    }

    private Step7ReviewNetworkWorkflowService.ScenarioResult scenario(
            Step7ReviewNetworkWorkflowService.IntegrationReport report, String id) {
        return report.syntheticScenarios().stream().filter(result -> result.scenarioId().equals(id)).findFirst().orElseThrow();
    }

    private DirectedProcessNetworkSolver.EdgeResult edge(Step7ReviewNetworkWorkflowService.ScenarioResult scenario, String id) {
        return scenario.solverResult().edges().stream().filter(result -> result.connectionId().equals(id)).findFirst().orElseThrow();
    }
}
