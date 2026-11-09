package com.example.processautomation.material;

import com.example.processautomation.excel.FlowChangeDrawingEngine;
import com.example.processautomation.excel.FlowLabelBinding;
import com.example.processautomation.material.FlowChangeSyntheticWorkflowService.ScenarioOutput;
import com.example.processautomation.material.FlowChangeSyntheticWorkflowService.WorkflowResult;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated-owner workflow for the single immutable server-synthetic FLOW_CHANGE fixture. */
@Service
public class FlowChangeAuthenticatedWorkflowService {
    private static final Set<String> SCENARIOS = Set.of("FLOW-20", "FLOW-10", "FLOW-50");
    private final FlowChangeSyntheticWorkflowService synthetic;
    private final UserWorkingCopyService userCopies;
    private final ChangeProposalService proposals;
    private final NetworkWhatIfService whatIf;
    private final boolean enabled;

    public FlowChangeAuthenticatedWorkflowService(FlowChangeSyntheticWorkflowService synthetic,
            UserWorkingCopyService userCopies, ChangeProposalService proposals, NetworkWhatIfService whatIf,
            @Value("${app.flow-change.synthetic-enabled:false}") boolean enabled) {
        this.synthetic = synthetic;
        this.userCopies = userCopies;
        this.proposals = proposals;
        this.whatIf = whatIf;
        this.enabled = enabled;
    }

    public FlowChangeSyntheticWorkflowService.FixtureDownload fixtureForUser(String username) {
        requireEnabled(username);
        return synthetic.serverFixture();
    }

    @Transactional
    public Preview preview(String username, long workingDocumentId, String expectedWorkingSha256,
                           String fixtureId, String expectedFixtureSha256, String scenarioId, String rationale) {
        requireEnabled(username);
        validateRequest(workingDocumentId, expectedWorkingSha256, fixtureId, expectedFixtureSha256, scenarioId, rationale);
        var copy = userCopies.getForUser(workingDocumentId, username);
        FlowChangeSyntheticWorkflowService.Preview calculationPreview = requireExactServerCopy(copy, username, fixtureId, expectedFixtureSha256,
                expectedWorkingSha256);
        ScenarioOutput scenario = requireScenario(calculationPreview, scenarioId);
        boolean noOp = scenario.afterQuantity().compareTo(scenario.beforeQuantity()) == 0;
        String proposalId = null;
        if (!noOp) {
            Map<String, Object> before = snapshot(calculationPreview.network(), calculationPreview.network().baseQuantity());
            Map<String, Object> after = snapshot(calculationPreview.network(), scenario.afterQuantity());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("classification", "SERVER_MANAGED_SYNTHETIC_FLOW_FIXTURE_V1");
            evidence.put("fixtureId", fixtureId);
            evidence.put("fixtureSha256", calculationPreview.sourceSha256());
            evidence.put("analysisFingerprint", calculationPreview.analysisFingerprint());
            evidence.put("bindingIds", calculationPreview.bindings().stream().map(FlowLabelBinding::bindingId).toList());
            evidence.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
            evidence.put("officialReadiness", "CALCULATION_UNAVAILABLE");
            var proposal = proposals.create(username, new ChangeProposalService.ProposalInput(copy.id(), copy.processCode(),
                    FlowChangeSyntheticFixtureFactory.SHEET_NAME, "FLOW_CHANGE", null, copy.workingSha256(),
                    before, after, rationale.trim(), evidence));
            proposalId = proposal.proposalId();
            var beforeNetwork = networkWithQuantity(calculationPreview.network(),
                    calculationPreview.network().baseQuantity(), scenario.afterQuantity());
            var afterNetwork = networkWithQuantity(calculationPreview.network(), scenario.afterQuantity(), scenario.afterQuantity());
            var beforeReport = whatIf.calculateTestOnly(beforeNetwork);
            var afterReport = whatIf.calculateTestOnly(afterNetwork);
            proposals.recordSyntheticFlowChangeWhatIf(username, proposalId, beforeReport, afterReport,
                    beforeNetwork, afterNetwork);
        }
        return toPreview(calculationPreview, scenario, username, copy.id(), copy.processCode(),
                copy.workingSha256(), scenarioId, proposalId, noOp);
    }

    @Transactional
    public Generated generate(Preview submitted, String username) {
        requireEnabled(username);
        if (submitted == null || !username.equals(submitted.requestUser()))
            throw notFound("No FLOW_CHANGE preview exists in this user scope.");
        if (submitted.noOp()) throw conflict("FLOW_CHANGE_BASELINE_NO_OP: baseline 20 is not a generated change.");
        if (!SCENARIOS.contains(submitted.scenarioId()) || "FLOW-20".equals(submitted.scenarioId()))
            throw conflict("Only a non-no-op server-approved synthetic scenario may be generated.");
        var copy = userCopies.getForUser(submitted.workingDocumentId(), username);
        FlowChangeSyntheticWorkflowService.Preview current = requireExactServerCopy(copy, username, submitted.fixtureId(), submitted.fixtureSha256(),
                submitted.workingSha256());
        if (!copy.processCode().equals(submitted.processCode()) || copy.id() != submitted.workingDocumentId()
                || !copy.workingSha256().equalsIgnoreCase(submitted.workingSha256())
                || !current.analysisFingerprint().equals(submitted.analysisFingerprint()))
            throw conflict("FLOW_CHANGE_PREVIEW_STALE: working copy, fixture, binding or calculation changed.");
        ScenarioOutput selected = requireScenario(current, submitted.scenarioId());
        if (selected.afterQuantity().compareTo(submitted.requestedQuantity()) != 0
                || selected.edit().changes().size() != current.bindings().size()
                || selected.edit().changes().stream().anyMatch(change -> change.afterQuantity().compareTo(selected.calculatedEdgeQuantity()) != 0))
            throw conflict("FLOW_CHANGE_PREVIEW_LABEL_MAPPING_STALE: all bound cells must match the computed edge.");
        if (submitted.proposalId() == null || submitted.proposalId().isBlank())
            throw conflict("FLOW_CHANGE_PROPOSAL_REQUIRED_BEFORE_GENERATE.");

        String outputSha = selected.edit().outputSha256();
        String filename = safe(copy.processCode()) + "_FLOW_CHANGE_" + safe(submitted.scenarioId()) + "_TEST_ONLY.xlsx";
        UserWorkingCopyService.StoredOutput stored = userCopies.saveFlowChangeSyntheticOutput(username, copy.id(),
                copy.workingSha256(), submitted.fixtureId(), submitted.fixtureSha256(), submitted.scenarioId(),
                outputSha, filename, selected.workbookBytes());
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("calculationKind", "SYNTHETIC_TEST_ONLY");
        validation.put("fixtureId", current.fixtureId());
        validation.put("fixtureSha256", current.sourceSha256());
        validation.put("workingCopySha256", copy.workingSha256());
        validation.put("analysisFingerprint", current.analysisFingerprint());
        validation.put("scenarioId", selected.scenarioId());
        validation.put("beforeQuantity", selected.beforeQuantity());
        validation.put("requestedQuantity", selected.afterQuantity());
        validation.put("calculatedEdgeQuantity", selected.calculatedEdgeQuantity());
        validation.put("labelChanges", selected.edit().changes());
        validation.put("networkResult", selected.calculation());
        validation.put("outputSha256", outputSha);
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        proposals.recordTestOnlyOutput(username, submitted.proposalId(), new ChangeProposalService.TestOnlyOutput(
                copy.id(), copy.processCode(), FlowChangeSyntheticFixtureFactory.SHEET_NAME,
                copy.workingSha256(), outputSha, validation));
        return new Generated(submitted.previewId(), submitted.proposalId(), username, copy.id(), copy.processCode(),
                submitted.scenarioId(), submitted.requestedQuantity(), selected.calculatedEdgeQuantity(),
                stored.outputId(), filename, outputSha, current.sourceSha256(), current.analysisFingerprint(),
                selected.edit().changes(), calculationSummary(selected.calculation()), "TEST_ONLY", "REVIEW_REQUIRED",
                "CALCULATION_UNAVAILABLE", false, "No real physical connection or actual flow mapping was asserted.");
    }

    public UserWorkingCopyService.StoredArtifact download(long outputId, String username,
            long expectedWorkingDocumentId, String expectedOutputSha256) {
        requireEnabled(username);
        UserWorkingCopyService.StoredArtifact artifact = userCopies.downloadTestOnlyOutput(outputId, username);
        if (!"FLOW_CHANGE".equals(artifact.changeType()) || artifact.workingDocumentId() != expectedWorkingDocumentId
                || !artifact.outputSha256().equalsIgnoreCase(expectedOutputSha256))
            throw notFound("No matching FLOW_CHANGE TEST_ONLY output exists in this user scope.");
        var copy = userCopies.getForUser(expectedWorkingDocumentId, username);
        if (!UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                || !username.equals(copy.registeredBy()))
            throw notFound("The FLOW_CHANGE output working-copy owner is no longer accessible.");
        return artifact;
    }

    private FlowChangeSyntheticWorkflowService.Preview requireExactServerCopy(NumericWorkingCopyService.WorkingCopy copy, String username,
            String fixtureId, String fixtureSha, String expectedWorkingSha) {
        if (!UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                || !username.equals(copy.registeredBy()) || !"P-02".equals(copy.processCode())
                || copy.bytes() == null || !sha256(copy.bytes()).equalsIgnoreCase(fixtureSha)
                || !copy.workingSha256().equalsIgnoreCase(fixtureSha)
                || !copy.workingSha256().equalsIgnoreCase(expectedWorkingSha))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the byte-identical server fixture registered as this user's P-02 working copy is allowed.");
        try {
            return synthetic.previewServerSyntheticFixture(fixtureId, fixtureSha);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Server FLOW_CHANGE fixture/SHA verification failed.", exception);
        }
    }

    private ScenarioOutput requireScenario(FlowChangeSyntheticWorkflowService.Preview preview, String scenarioId) {
        if (!SCENARIOS.contains(scenarioId)) throw badRequest("Unknown server-defined FLOW_CHANGE scenario.");
        return synthetic.runServerSyntheticFixture(preview.fixtureId()).scenarios().stream()
                .filter(scenario -> scenarioId.equals(scenario.scenarioId())).findFirst()
                .orElseThrow(() -> conflict("Server FLOW_CHANGE scenario result is missing."));
    }

    private NetworkWhatIfRequest networkWithQuantity(NetworkWhatIfRequest base, BigDecimal edgeQuantity,
                                                      BigDecimal scenarioQuantity) {
        var edges = base.edges().stream().map(edge -> edge.connectionId().equals(base.changedConnectionId())
                ? new NetworkWhatIfRequest.Edge(edge.connectionId(), edge.sourceNodeId(), edge.targetNodeId(),
                        edge.sourceProcess(), edge.targetProcess(), edge.materialClass(), edge.materialType(), edge.unit(),
                        edgeQuantity, edge.connectionType(), edge.quantityBasis(), true, edge.evidence(), edge.period())
                : edge).toList();
        return new NetworkWhatIfRequest(base.mode(), base.simulationId(), base.scenarioName(), base.processCode(),
                base.sourceEquipment(), base.targetEquipment(), base.materialClass(), base.materialType(), base.unit(),
                base.period(), base.changedConnectionId(), edgeQuantity, base.nodes(), edges, base.processTerms(),
                java.util.List.of(new NetworkWhatIfRequest.ChangeCase("SELECTED-" + scenarioQuantity.toPlainString(),
                        "Independent selected synthetic flow case", scenarioQuantity)));
    }

    private Map<String, Object> snapshot(NetworkWhatIfRequest network, BigDecimal quantity) {
        var edge = network.edges().stream().filter(item -> network.changedConnectionId().equals(item.connectionId()))
                .findFirst().orElseThrow();
        var source = network.nodes().stream().filter(item -> edge.sourceNodeId().equals(item.nodeId())).findFirst().orElseThrow();
        var target = network.nodes().stream().filter(item -> edge.targetNodeId().equals(item.nodeId())).findFirst().orElseThrow();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceProcess", edge.sourceProcess()); values.put("sourceEquipment", source.equipmentNumber());
        values.put("targetProcess", edge.targetProcess()); values.put("targetEquipment", target.equipmentNumber());
        values.put("connectionId", edge.connectionId()); values.put("connectionType", edge.connectionType());
        values.put("materialClass", edge.materialClass()); values.put("materialType", edge.materialType());
        values.put("unit", edge.unit()); values.put("period", edge.period()); values.put("quantity", quantity);
        values.put("branchInfo", "SYNTHETIC_SINGLE_INTERPROCESS_EDGE; no branch quantity inferred");
        values.put("recycleInfo", "SYNTHETIC_INTERPROCESS_TRANSFER; not evidence of physical recycle");
        return values;
    }

    private Preview toPreview(FlowChangeSyntheticWorkflowService.Preview calculationPreview, ScenarioOutput scenario, String username,
            long workingDocumentId, String processCode, String workingSha, String scenarioId,
            String proposalId, boolean noOp) {
        return new Preview(java.util.UUID.randomUUID().toString(), proposalId, username, workingDocumentId,
                processCode, workingSha, calculationPreview.fixtureId(), calculationPreview.sourceSha256(),
                calculationPreview.analysisFingerprint(), scenarioId, scenario.beforeQuantity(), scenario.afterQuantity(),
                scenario.calculatedEdgeQuantity(), calculationSummary(scenario.calculation()), scenario.edit().changes(), noOp,
                "TEST_ONLY_REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", false);
    }

    private Map<String, Object> calculationSummary(DirectedProcessNetworkSolver.Result result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.status());
        summary.put("scenarioId", result.scenarioId());
        summary.put("changedConnectionId", result.changedConnectionId());
        summary.put("beforeQuantity", result.previousQuantity());
        summary.put("afterQuantity", result.newQuantity());
        summary.put("edges", result.edges());
        summary.put("nodeBalances", result.nodes());
        summary.put("processBalances", result.processes());
        summary.put("externalBalances", result.external());
        summary.put("impactedEquipment", result.impactedEquipmentNumbers());
        summary.put("impactedProcesses", result.impactedProcessCodes());
        summary.put("issues", result.issues());
        return Map.copyOf(summary);
    }

    private void validateRequest(long workingDocumentId, String workingSha, String fixtureId,
            String fixtureSha, String scenarioId, String rationale) {
        if (workingDocumentId <= 0 || blank(workingSha) || !workingSha.matches("(?i)[0-9a-f]{64}")
                || !FlowChangeSyntheticFixtureFactory.FIXTURE_ID.equals(fixtureId)
                || blank(fixtureSha) || !fixtureSha.matches("(?i)[0-9a-f]{64}")
                || !SCENARIOS.contains(scenarioId) || blank(rationale) || rationale.length() > 8000)
            throw badRequest("Working-copy ID/SHA, exact server fixture ID/SHA, scenario ID and rationale are required.");
    }

    private void requireEnabled(String username) {
        if (blank(username)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
        if (!enabled || !userCopies.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "FLOW_CHANGE is disabled unless both isolated synthetic and user-working-copy feature flags are enabled.");
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String safe(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    public record Preview(String previewId, String proposalId, String requestUser, long workingDocumentId,
                          String processCode, String workingSha256, String fixtureId, String fixtureSha256,
                          String analysisFingerprint, String scenarioId, BigDecimal beforeQuantity,
                          BigDecimal requestedQuantity, BigDecimal calculatedEdgeQuantity,
                          Map<String, Object> calculationSummary,
                          java.util.List<FlowChangeDrawingEngine.LabelChange> labelChanges,
                          boolean noOp, String reviewStatus, String actualReadiness,
                          boolean officialBaselineModified) {
        public Preview { labelChanges = java.util.List.copyOf(labelChanges); }
    }

    public record Generated(String previewId, String proposalId, String requestUser, long workingDocumentId,
                            String processCode, String scenarioId, BigDecimal beforeQuantity,
                            BigDecimal generatedQuantity, long outputId, String filename, String outputSha256,
                            String fixtureSha256, String analysisFingerprint,
                            java.util.List<FlowChangeDrawingEngine.LabelChange> labelChanges,
                            Map<String, Object> calculationSummary, String mode, String reviewStatus,
                            String officialReadiness, boolean officialBaselineModified, String notice) {
        public Generated { labelChanges = java.util.List.copyOf(labelChanges); }
    }
}
