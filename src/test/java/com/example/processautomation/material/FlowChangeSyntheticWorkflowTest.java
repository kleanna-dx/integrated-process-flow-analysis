package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.FlowChangeDrawingEngine;
import com.example.processautomation.excel.FlowLabelBinding;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.OoxmlPartEditor;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FlowChangeSyntheticWorkflowTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NetworkWhatIfService whatIf = new NetworkWhatIfService(jdbc, new MaterialBalanceCalculator());
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final FlowChangeDrawingEngine engine = new FlowChangeDrawingEngine(inspector, new OoxmlPartEditor());
    private final FlowChangeSyntheticFixtureFactory fixtures = new FlowChangeSyntheticFixtureFactory();
    private final FlowChangeSyntheticWorkflowService workflow = new FlowChangeSyntheticWorkflowService(
            fixtures, engine, whatIf, inspector);

    @Test
    void independentlyCalculatesAndWritesTwentyTenFiftyToMappedMergedAndOrdinaryNumericCells() throws Exception {
        var result = workflow.runServerSyntheticFixture(FlowChangeSyntheticFixtureFactory.FIXTURE_ID);
        assertThat(result.reviewStatus()).isEqualTo("TEST_ONLY_REVIEW_REQUIRED");
        assertThat(result.actualReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(result.officialBaselineModified()).isFalse();
        assertThat(result.calculation().baseline().status()).isEqualTo("CALCULATED");
        assertThat(result.calculation().scenarios()).extracting(NetworkWhatIfService.ScenarioResult::afterQuantity)
                .containsExactly(new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("50"));
        assertThat(result.scenarios()).hasSize(3);
        assertThat(result.bindings()).extracting(FlowLabelBinding::cellAddress).containsExactly("B8", "B17");
        assertThat(result.scenarios()).allSatisfy(scenario -> {
            assertThat(scenario.calculatedEdgeQuantity()).isEqualByComparingTo(scenario.afterQuantity());
            assertThat(scenario.calculation().status()).isEqualTo("CALCULATED");
            assertThat(scenario.edit().status()).isEqualTo("TEST_ONLY_REVIEW_REQUIRED");
            assertThat(scenario.edit().changes()).hasSize(2).allSatisfy(change -> {
                assertThat(change.beforeQuantity()).isEqualByComparingTo("20");
                assertThat(change.afterQuantity()).isEqualByComparingTo(scenario.calculatedEdgeQuantity());
                assertThat(change.unit()).isEqualTo("ton/day");
                assertThat(change.period()).isEqualTo(FlowChangeSyntheticFixtureFactory.PERIOD);
                assertThat(change.materialClass()).isEqualTo(DirectedProcessNetwork.MaterialClass.WASTEWATER);
                assertThat(change.materialType()).isEqualTo("synthetic process wastewater");
                assertThat(change.displayAfter()).isEqualTo(scenario.calculatedEdgeQuantity().setScale(2).toPlainString());
            });
        });
        assertThat(result.scenarios().get(0).calculation().external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
            assertThat(balance.residual()).isEqualByComparingTo("0");
        });
        assertThat(result.scenarios().get(1).calculation().processes()).allSatisfy(balance ->
                assertThat(balance.residual()).isEqualByComparingTo("0"));
        assertThat(result.scenarios().get(2).calculation().processes()).allSatisfy(balance ->
                assertThat(balance.residual()).isEqualByComparingTo("0"));
        assertThat(result.scenarios().get(1).calculation().external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
        });
        assertThat(result.scenarios().get(2).calculation().external()).singleElement().satisfies(balance -> {
            assertThat(balance.externalInflow()).isEqualByComparingTo("200");
            assertThat(balance.externalOutflow()).isEqualByComparingTo("200");
        });

        for (var scenario : result.scenarios()) {
            byte[] bytes = scenario.workbookBytes();
            assertThat(sha256(bytes)).isEqualTo(scenario.edit().outputSha256());
            verifyZipCrc(bytes);
            try (var original = WorkbookFactory.create(new ByteArrayInputStream(result.sourceWorkbook()));
                 var output = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                var oldSheet = original.getSheet(result.sheetName());
                var newSheet = output.getSheet(result.sheetName());
                assertThat(newSheet.getRow(7).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(newSheet.getRow(7).getCell(1).getNumericCellValue()).isEqualTo(scenario.afterQuantity().doubleValue());
                assertThat(newSheet.getRow(16).getCell(1).getNumericCellValue()).isEqualTo(scenario.afterQuantity().doubleValue());
                assertThat(newSheet.getRow(7).getCell(1).getCellStyle().getIndex())
                        .isEqualTo(oldSheet.getRow(7).getCell(1).getCellStyle().getIndex());
                assertThat(newSheet.getRow(16).getCell(1).getCellStyle().getIndex())
                        .isEqualTo(oldSheet.getRow(16).getCell(1).getCellStyle().getIndex());
                assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
                assertThat(newSheet.getMergedRegion(0).formatAsString()).isEqualTo("B8:C8");
                assertThat(newSheet.getRow(0).getCell(0).getStringCellValue())
                        .isEqualTo(oldSheet.getRow(0).getCell(0).getStringCellValue());
                assertThat(newSheet.getColumnWidth(1)).isEqualTo(oldSheet.getColumnWidth(1));
                assertThat(newSheet.getRow(7).getHeight()).isEqualTo(oldSheet.getRow(7).getHeight());
            }
        }
        writeReviewArtifacts(result);
        verifyNoInteractions(jdbc);
    }

    @Test
    void blocksUnknownFixtureAndUnsafeOrStaleLabelMappingsBeforeReturningAnArtifact() {
        assertThatThrownBy(() -> workflow.runServerSyntheticFixture("CLIENT_SUPPLIED_FIXTURE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FIXTURE_ID_MISMATCH");
        var preview = workflow.previewServerSyntheticFixture(FlowChangeSyntheticFixtureFactory.FIXTURE_ID);
        var forged = new FlowChangeSyntheticWorkflowService.Preview(preview.fixtureId(), preview.sourceSha256(),
                preview.sourceWorkbook(), preview.sheetName(), preview.network(), preview.calculation(),
                preview.bindings(), "0".repeat(64));
        assertThatThrownBy(() -> workflow.generateServerSyntheticFixture(forged))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STALE_FLOW_CHANGE_PREVIEW");

        var fixture = fixtures.create();
        String sha = sha256(fixture.workbookBytes());
        var good = new FlowLabelBinding("BIND", "P-02", FlowChangeSyntheticFixtureFactory.SHEET_NAME,
                FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID, FlowLabelBinding.TargetType.MERGED_NUMERIC_CELL,
                "B8", new BigDecimal("20"), DirectedProcessNetwork.MaterialClass.WASTEWATER,
                "synthetic process wastewater", "ton/day", FlowChangeSyntheticFixtureFactory.PERIOD,
                sha, "SYNTHETIC_VERIFIED_TEST_ONLY", "SYNTHETIC:FLOW_CHANGE_FIXTURE_V1");
        assertThatThrownBy(() -> engine.editNumericLabelsTestOnly(fixture.workbookBytes(), "P-02",
                FlowChangeSyntheticFixtureFactory.SHEET_NAME, "0".repeat(64), FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID,
                new BigDecimal("20"), new BigDecimal("10"), List.of(good)))
                .hasMessageContaining("WORKING_COPY_SHA_MISMATCH");
        assertThatThrownBy(() -> engine.editNumericLabelsTestOnly(fixture.workbookBytes(), "P-02",
                FlowChangeSyntheticFixtureFactory.SHEET_NAME, sha, FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID,
                new BigDecimal("19"), new BigDecimal("10"), List.of(good)))
                .hasMessageContaining("FLOW_LABEL_BEFORE_VALUE_MISMATCH");
        byte[] staleWorkbook;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture.workbookBytes()));
             var output = new java.io.ByteArrayOutputStream()) {
            workbook.getSheet(FlowChangeSyntheticFixtureFactory.SHEET_NAME).getRow(16).getCell(1).setCellValue(21d);
            workbook.write(output);
            staleWorkbook = output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        var staleBinding = new FlowLabelBinding("STALE", good.processCode(), good.sheetName(), good.edgeId(),
                FlowLabelBinding.TargetType.NUMERIC_CELL, "B17", new BigDecimal("20"), good.materialClass(),
                good.materialType(), good.unit(), good.period(), sha256(staleWorkbook), good.mappingStatus(), good.evidenceId());
        assertThatThrownBy(() -> engine.editNumericLabelsTestOnly(staleWorkbook, "P-02",
                FlowChangeSyntheticFixtureFactory.SHEET_NAME, sha256(staleWorkbook), FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID,
                new BigDecimal("20"), new BigDecimal("10"), List.of(staleBinding)))
                .hasMessageContaining("STALE_FLOW_LABEL_VALUE");
        var duplicate = new FlowLabelBinding("BIND-DUP", good.processCode(), good.sheetName(), good.edgeId(),
                good.targetType(), good.cellAddress(), good.expectedBefore(), good.materialClass(), good.materialType(),
                good.unit(), good.period(), sha, good.mappingStatus(), "SYNTHETIC:DUP");
        assertThatThrownBy(() -> engine.editNumericLabelsTestOnly(fixture.workbookBytes(), "P-02",
                FlowChangeSyntheticFixtureFactory.SHEET_NAME, sha, FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID,
                new BigDecimal("20"), new BigDecimal("10"), List.of(good, duplicate)))
                .hasMessageContaining("DUPLICATE_FLOW_LABEL_TARGET");
        var drawing = new FlowLabelBinding("DRAWING", good.processCode(), good.sheetName(), good.edgeId(),
                FlowLabelBinding.TargetType.DRAWINGML_TEXT, null, good.expectedBefore(), good.materialClass(),
                good.materialType(), good.unit(), good.period(), sha, good.mappingStatus(), "SYNTHETIC:DRAWING");
        assertThatThrownBy(() -> engine.editNumericLabelsTestOnly(fixture.workbookBytes(), "P-02",
                FlowChangeSyntheticFixtureFactory.SHEET_NAME, sha, FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID,
                new BigDecimal("20"), new BigDecimal("10"), List.of(drawing)))
                .hasMessageContaining("DRAWINGML_FLOW_LABEL_UNSUPPORTED");
        verifyNoInteractions(jdbc);
    }

    private void writeReviewArtifacts(FlowChangeSyntheticWorkflowService.WorkflowResult result) throws Exception {
        Path root = Path.of("target/test-artifacts/step8-3-flow-change");
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "review-run-");
        var manifest = new StringBuilder();
        Path before = run.resolve("before-synthetic.xlsx");
        Files.write(before, result.sourceWorkbook());
        manifest.append(sha256(result.sourceWorkbook())).append("  ").append(before.getFileName()).append('\n');
        for (var scenario : result.scenarios()) {
            if ("FLOW-20".equals(scenario.scenarioId())) continue;
            String name = "after-" + scenario.scenarioId().toLowerCase(java.util.Locale.ROOT) + ".xlsx";
            Path output = run.resolve(name);
            Files.write(output, scenario.workbookBytes());
            manifest.append(sha256(scenario.workbookBytes())).append("  ").append(name).append('\n');
        }
        Files.writeString(run.resolve("SHA256SUMS"), manifest, StandardCharsets.UTF_8);
        String shaManifest = Files.readString(run.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(shaManifest).contains("after-flow-10.xlsx", "after-flow-50.xlsx").doesNotContain("after-flow-20.xlsx");
    }

    private void verifyZipCrc(byte[] bytes) throws Exception {
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                zip.transferTo(java.io.OutputStream.nullOutputStream());
                assertThat(entry.getCrc()).isGreaterThan(0L);
                entries++;
            }
        }
        assertThat(entries).isGreaterThan(5);
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
