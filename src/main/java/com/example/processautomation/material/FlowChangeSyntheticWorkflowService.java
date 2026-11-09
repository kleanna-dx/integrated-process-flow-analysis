package com.example.processautomation.material;

import com.example.processautomation.excel.FlowChangeDrawingEngine;
import com.example.processautomation.excel.FlowLabelBinding;
import com.example.processautomation.excel.OoxmlPackageInspector;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/** Database-free, server-owned FLOW_CHANGE rehearsal. It never accepts a real workbook or client graph. */
@Service
public class FlowChangeSyntheticWorkflowService {
    private final FlowChangeSyntheticFixtureFactory fixtures;
    private final FlowChangeDrawingEngine editor;
    private final NetworkWhatIfService whatIf;
    private final OoxmlPackageInspector inspector;

    public FlowChangeSyntheticWorkflowService(FlowChangeSyntheticFixtureFactory fixtures,
            FlowChangeDrawingEngine editor, NetworkWhatIfService whatIf, OoxmlPackageInspector inspector) {
        this.fixtures = fixtures;
        this.editor = editor;
        this.whatIf = whatIf;
        this.inspector = inspector;
    }

    public FixtureDownload serverFixture() {
        FlowChangeSyntheticFixtureFactory.Fixture fixture = fixtures.create();
        byte[] bytes = fixture.workbookBytes();
        return new FixtureDownload(fixture.fixtureId(), sha256(bytes), bytes);
    }

    public Preview previewServerSyntheticFixture(String fixtureId) {
        return previewServerSyntheticFixture(fixtureId, null);
    }

    public Preview previewServerSyntheticFixture(String fixtureId, String expectedSourceSha256) {
        FlowChangeSyntheticFixtureFactory.Fixture fixture = fixtures.create();
        if (!FlowChangeSyntheticFixtureFactory.FIXTURE_ID.equals(fixtureId))
            throw new IllegalArgumentException("FLOW_CHANGE_SYNTHETIC_FIXTURE_ID_MISMATCH");
        byte[] source = fixture.workbookBytes();
        String sourceSha = sha256(source);
        if (expectedSourceSha256 != null && !sourceSha.equalsIgnoreCase(expectedSourceSha256))
            throw new IllegalStateException("FLOW_CHANGE_FIXTURE_SHA_MISMATCH");
        var sheetInventory = inspector.inspectSheet(source, FlowChangeSyntheticFixtureFactory.SHEET_NAME);
        List<FlowLabelBinding> bindings = List.of(
                binding("SYN-BIND-MERGED-B8", "B8", FlowLabelBinding.TargetType.MERGED_NUMERIC_CELL, sourceSha),
                binding("SYN-BIND-CELL-B17", "B17", FlowLabelBinding.TargetType.NUMERIC_CELL, sourceSha));
        validateBindingsAgainstNetwork(fixture.network(), bindings);
        NetworkWhatIfService.WhatIfReport report = whatIf.calculateTestOnly(fixture.network());
        if (!"CALCULATION_UNAVAILABLE".equals(report.actualReadiness().get("status"))
                || !Boolean.FALSE.equals(report.actualReadiness().get("calculationAvailable"))
                || report.officialDiagramModified() || report.officialMasterModified())
            throw new IllegalStateException("FLOW_CHANGE_SYNTHETIC_READINESS_OR_BASELINE_GUARD_FAILED");
        String fingerprint = sha256((fixture.fixtureId() + "|" + sourceSha + "|" + fixture.network()
                + "|" + bindings + "|" + report).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Preview(fixture.fixtureId(), sourceSha, source, sheetInventory.sheetName(),
                fixture.network(), report, bindings, fingerprint);
    }

    public WorkflowResult generateServerSyntheticFixture(Preview submittedPreview) {
        if (submittedPreview == null) throw new IllegalArgumentException("FLOW_CHANGE_PREVIEW_REQUIRED");
        Preview current = previewServerSyntheticFixture(submittedPreview.fixtureId());
        if (!current.analysisFingerprint().equals(submittedPreview.analysisFingerprint()))
            throw new IllegalStateException("STALE_FLOW_CHANGE_PREVIEW");
        return generate(current);
    }

    public WorkflowResult runServerSyntheticFixture(String fixtureId) {
        return generateServerSyntheticFixture(previewServerSyntheticFixture(fixtureId));
    }

    private WorkflowResult generate(Preview preview) {
        byte[] source = preview.sourceWorkbook();
        String sourceSha = preview.sourceSha256();
        var network = preview.network();
        var report = preview.calculation();
        var bindings = preview.bindings();
        List<ScenarioOutput> outputs = new ArrayList<>();
        for (NetworkWhatIfService.ScenarioResult scenario : report.scenarios()) {
            BigDecimal calculated = scenario.result().edges().stream()
                    .filter(edge -> FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID.equals(edge.connectionId()))
                    .map(DirectedProcessNetworkSolver.EdgeResult::quantity).findFirst().orElse(null);
            if (!"CALCULATED".equals(scenario.result().status()) || calculated == null
                    || calculated.compareTo(scenario.afterQuantity()) != 0)
                throw new IllegalStateException("FLOW_LABEL_CALCULATION_MAPPING_MISMATCH:" + scenario.scenarioId());
            FlowChangeDrawingEngine.EditResult edit = editor.editNumericLabelsTestOnly(source,
                    network.processCode(), FlowChangeSyntheticFixtureFactory.SHEET_NAME, sourceSha,
                    FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID, network.baseQuantity(), calculated, bindings);
            if (edit.changes().size() != bindings.size()
                    || edit.changes().stream().anyMatch(change -> change.afterQuantity().compareTo(calculated) != 0
                    || !"ton/day".equals(change.unit()) || !FlowChangeSyntheticFixtureFactory.PERIOD.equals(change.period())
                    || change.materialClass() != DirectedProcessNetwork.MaterialClass.WASTEWATER
                    || !"synthetic process wastewater".equals(change.materialType())))
                throw new IllegalStateException("FLOW_LABEL_OUTPUT_BASIS_MISMATCH:" + scenario.scenarioId());
            outputs.add(new ScenarioOutput(scenario.scenarioId(), scenario.beforeQuantity(), scenario.afterQuantity(),
                    calculated, scenario.result(), edit, edit.workbookBytes().clone()));
        }
        if (outputs.size() != 3 || outputs.get(0).afterQuantity().compareTo(new BigDecimal("20")) != 0
                || outputs.get(1).afterQuantity().compareTo(new BigDecimal("10")) != 0
                || outputs.get(2).afterQuantity().compareTo(new BigDecimal("50")) != 0)
            throw new IllegalStateException("FLOW_CHANGE_INDEPENDENT_20_10_50_SCENARIOS_REQUIRED");
        return new WorkflowResult(preview.fixtureId(), sourceSha, source, preview.sheetName(),
                network, report, bindings, List.copyOf(outputs),
                "TEST_ONLY_REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", false,
                "Network/Excel agreement is a server-generated synthetic demonstration only; no real process mapping or physical connection is verified.");
    }

    private void validateBindingsAgainstNetwork(NetworkWhatIfRequest network, List<FlowLabelBinding> bindings) {
        List<NetworkWhatIfRequest.Edge> target = network.edges().stream()
                .filter(edge -> FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID.equals(edge.connectionId())).toList();
        if (target.size() != 1) throw new IllegalStateException("FLOW_CHANGE_SYNTHETIC_EDGE_NOT_UNIQUE");
        var edge = target.get(0);
        for (FlowLabelBinding binding : bindings) {
            if (!edge.fixed() || edge.quantity() == null || edge.quantity().compareTo(binding.expectedBefore()) != 0
                    || edge.materialClass() != binding.materialClass()
                    || !same(edge.materialType(), binding.materialType()) || !same(edge.unit(), binding.unit())
                    || !same(edge.period(), binding.period()))
                throw new IllegalStateException("FLOW_LABEL_EDGE_BASIS_MISMATCH:" + binding.bindingId());
        }
    }

    private FlowLabelBinding binding(String id, String address, FlowLabelBinding.TargetType targetType, String sha) {
        return new FlowLabelBinding(id, "P-02", FlowChangeSyntheticFixtureFactory.SHEET_NAME,
                FlowChangeSyntheticFixtureFactory.FLOW_EDGE_ID, targetType, address, new BigDecimal("20"),
                DirectedProcessNetwork.MaterialClass.WASTEWATER, "synthetic process wastewater", "ton/day",
                FlowChangeSyntheticFixtureFactory.PERIOD, sha, "SYNTHETIC_VERIFIED_TEST_ONLY",
                "SYNTHETIC:FLOW_CHANGE_FIXTURE_V1:" + id);
    }

    private boolean same(String first, String second) {
        return first != null && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record FixtureDownload(String fixtureId, String sha256, byte[] bytes) {
        public FixtureDownload { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Preview(String fixtureId, String sourceSha256, byte[] sourceWorkbook, String sheetName,
                          NetworkWhatIfRequest network, NetworkWhatIfService.WhatIfReport calculation,
                          List<FlowLabelBinding> bindings, String analysisFingerprint) {
        public Preview { sourceWorkbook = sourceWorkbook.clone(); bindings = List.copyOf(bindings); }
        @Override public byte[] sourceWorkbook() { return sourceWorkbook.clone(); }
    }

    public record ScenarioOutput(String scenarioId, BigDecimal beforeQuantity, BigDecimal afterQuantity,
                                 BigDecimal calculatedEdgeQuantity, DirectedProcessNetworkSolver.Result calculation,
                                 FlowChangeDrawingEngine.EditResult edit, byte[] workbookBytes) {
        public ScenarioOutput { workbookBytes = workbookBytes.clone(); }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }

    public record WorkflowResult(String fixtureId, String sourceSha256, byte[] sourceWorkbook,
                                 String sheetName, NetworkWhatIfRequest network,
                                 NetworkWhatIfService.WhatIfReport calculation,
                                 List<FlowLabelBinding> bindings, List<ScenarioOutput> scenarios,
                                 String reviewStatus, String actualReadiness, boolean officialBaselineModified,
                                 String limitation) {
        public WorkflowResult { sourceWorkbook = sourceWorkbook.clone(); bindings = List.copyOf(bindings); scenarios = List.copyOf(scenarios); }
        @Override public byte[] sourceWorkbook() { return sourceWorkbook.clone(); }
    }
}
