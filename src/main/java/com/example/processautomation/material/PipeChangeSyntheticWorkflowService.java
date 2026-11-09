package com.example.processautomation.material;

import com.example.processautomation.excel.PipeChangeDrawingEngine;
import com.example.processautomation.excel.PipeChangeSyntheticFixtureFactory;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Feature-flagged, user-scoped workflow admitted only for the byte-identical server-managed synthetic fixture. */
@Service
public class PipeChangeSyntheticWorkflowService {
    private final PipeChangeDrawingEngine engine;
    private final PipeChangeSyntheticFixtureFactory fixtures;
    private final PipeChangeSyntheticNetworkFactory networks;
    private final NetworkWhatIfService whatIf;
    private final UserWorkingCopyService userCopies;
    private final ChangeProposalService proposals;
    private final boolean enabled;

    public PipeChangeSyntheticWorkflowService(PipeChangeDrawingEngine engine,
            PipeChangeSyntheticFixtureFactory fixtures, PipeChangeSyntheticNetworkFactory networks,
            NetworkWhatIfService whatIf, UserWorkingCopyService userCopies, ChangeProposalService proposals,
            @Value("${app.pipe-change.synthetic-enabled:false}") boolean enabled) {
        this.engine = engine; this.fixtures = fixtures; this.networks = networks;
        this.whatIf = whatIf; this.userCopies = userCopies; this.proposals = proposals; this.enabled = enabled;
    }

    public PipeChangeSyntheticFixtureFactory.Fixture fixtureForUser(String username) {
        requireEnabled(username);
        return fixtures.fixture();
    }

    public Preview preview(String username, long workingDocumentId, String expectedWorkingSha256, String rationale) {
        requireEnabled(username);
        if (workingDocumentId <= 0 || blank(expectedWorkingSha256)
                || !expectedWorkingSha256.matches("(?i)[0-9a-f]{64}") || blank(rationale) || rationale.length() > 8000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A user working copy, exact SHA-256 and rationale are required.");
        var copy = userCopies.getForUser(workingDocumentId, username);
        var fixture = fixtures.fixture();
        requireExactSyntheticFixture(copy, username, fixture.sha256(), expectedWorkingSha256);
        Ready ready = analyze(fixture.bytes(), fixture);
        Map<String, Object> before = snapshot("SYN-EQ-B", "SYN-A-B");
        Map<String, Object> after = snapshot("SYN-EQ-C", "SYN-A-C");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("classification", "SERVER_MANAGED_SYNTHETIC_FIXTURE_V1");
        evidence.put("mappingEvidenceId", ready.networks().mapping().evidenceId());
        evidence.put("fixtureSha256", fixture.sha256());
        evidence.put("drawingAssessmentFingerprint", ready.assessment().analysisFingerprint());
        evidence.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        evidence.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        var proposal = proposals.create(username, new ChangeProposalService.ProposalInput(copy.id(), copy.processCode(),
                "Synthetic", "PIPE_CHANGE", null, copy.workingSha256(), before, after,
                rationale.trim(), evidence));
        proposals.recordSyntheticPipeChangeWhatIf(username, proposal.proposalId(),
                ready.beforeReport(), ready.afterReport());
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("calculationKind", "SYNTHETIC_TEST_ONLY");
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("drawingAssessmentFingerprint", ready.assessment().analysisFingerprint());
        validation.put("mappingComparison", ready.mappingComparison());
        validation.put("calculationComparison", ready.calculationComparison());
        proposals.recordPipeChangeDrawingValidation(username, proposal.proposalId(), validation);
        return new Preview(UUID.randomUUID().toString(), proposal.proposalId(), username, copy.id(), copy.processCode(),
                copy.workingSha256(), fixture.sha256(), ready.assessment().analysisFingerprint(),
                ready.assessment(), ready.mappingComparison(), ready.calculationComparison(),
                "TEST_ONLY_READY", "CALCULATION_UNAVAILABLE", false,
                "Only a byte-identical server-owned synthetic fixture is accepted; no actual process workbook can be generated.");
    }

    public Generated generate(Preview preview, String username) {
        requireEnabled(username);
        if (preview == null || !username.equals(preview.requestUser()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No PIPE_CHANGE preview exists in this user scope.");
        var copy = userCopies.getForUser(preview.workingDocumentId(), username);
        var fixture = fixtures.fixture();
        requireExactSyntheticFixture(copy, username, fixture.sha256(), preview.workingSha256());
        if (!copy.processCode().equals(preview.processCode()) || !copy.workingSha256().equalsIgnoreCase(preview.workingSha256())
                || !fixture.sha256().equalsIgnoreCase(preview.fixtureSha256()))
            throw conflict("Working copy or synthetic fixture SHA changed after preview.");
        Ready current = analyze(fixture.bytes(), fixture);
        if (!current.assessment().analysisFingerprint().equals(preview.analysisFingerprint())
                || !current.mappingComparison().equals(preview.mappingComparison())
                || !current.calculationComparison().equals(preview.calculationComparison()))
            throw conflict("PIPE_CHANGE assessment or What-if comparison is stale; preview again.");
        var output = engine.generate(fixture.bytes(), current.request(), current.assessment());
        var stored = userCopies.saveTestOnlyOutput(username, copy.id(), copy.workingSha256(), output.outputSha256(),
                "PIPE_CHANGE", safe(copy.processCode()) + "_PIPE_CHANGE_SYNTHETIC_TEST_ONLY.xlsx", output.bytes());
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("calculationKind", "SYNTHETIC_TEST_ONLY");
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("analysisFingerprint", current.assessment().analysisFingerprint());
        validation.put("mappingComparison", current.mappingComparison());
        validation.put("calculationComparison", current.calculationComparison());
        validation.put("sourceFixtureSha256", fixture.sha256());
        validation.put("outputSha256", output.outputSha256());
        validation.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        proposals.recordTestOnlyOutput(username, preview.proposalId(), new ChangeProposalService.TestOnlyOutput(
                copy.id(), copy.processCode(), "Synthetic", copy.workingSha256(), output.outputSha256(), validation));
        return new Generated(preview.previewId(), preview.proposalId(), stored.outputId(), copy.id(), copy.processCode(),
                "Synthetic", output.bytes(), fixture.sha256(), output.outputSha256(), current.assessment().analysisFingerprint(),
                current.mappingComparison(), current.calculationComparison(), "SYNTHETIC_TEST_ONLY",
                "CALCULATION_UNAVAILABLE", false, "TEST_ONLY synthetic output; actual process readiness is unchanged.");
    }

    public UserWorkingCopyService.StoredArtifact download(long outputId, String username) {
        requireEnabled(username);
        var artifact = userCopies.downloadTestOnlyOutput(outputId, username);
        if (!"PIPE_CHANGE".equals(artifact.changeType()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No PIPE_CHANGE output exists in this user scope.");
        return artifact;
    }

    private Ready analyze(byte[] workbook, PipeChangeSyntheticFixtureFactory.Fixture fixture) {
        var request = new PipeChangeDrawingEngine.Request("SERVER_SYNTHETIC_FIXTURE_V1", "Synthetic", fixture.sha256(),
                fixture.connectorId(), fixture.startShapeId(), fixture.beforeEndShapeId(), fixture.startShapeId(),
                fixture.afterEndShapeId(), "EXPLICIT_SYNTHETIC_VERIFIED", true,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "Synthetic", "Synthetic");
        var assessment = engine.analyze(workbook, request);
        if (!"SYNTHETIC_EDITABLE_TEST_ONLY".equals(assessment.status()))
            throw conflict("Server synthetic DrawingML assessment failed: " + assessment.blockers());
        var pair = networks.create(fixture);
        var mappingCheck = engine.verifyWhatIfMapping(assessment, pair.mapping(), pair.before(), pair.after());
        if (!"SYNTHETIC_MAPPING_VERIFIED_TEST_ONLY".equals(mappingCheck.status()))
            throw conflict("Server synthetic network mapping failed: " + mappingCheck.blockers());
        var beforeReport = whatIf.calculateTestOnly(pair.before());
        var afterReport = whatIf.calculateTestOnly(pair.after());
        var comparison = engine.compareCalculatedWhatIf(assessment, pair.mapping(), beforeReport, afterReport);
        if (!"DRAWING_WHATIF_MATCHED_TEST_ONLY".equals(comparison.status()))
            throw conflict("DrawingML and synthetic solver results disagree: " + comparison.blockers());
        return new Ready(request, assessment, pair, mappingCheck, comparison, beforeReport, afterReport);
    }

    private void requireExactSyntheticFixture(NumericWorkingCopyService.WorkingCopy copy, String username,
                                              String fixtureSha, String expectedSha) {
        if (!UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                || copy.registeredBy() == null || !username.equals(copy.registeredBy()) || copy.bytes() == null
                || !copy.workingSha256().equalsIgnoreCase(expectedSha)
                || !copy.workingSha256().equalsIgnoreCase(fixtureSha)
                || !sha256(copy.bytes()).equalsIgnoreCase(fixtureSha))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "PIPE_CHANGE generation is limited to the byte-identical server-managed synthetic fixture.");
    }

    private Map<String, Object> snapshot(String targetEquipment, String connectionId) {
        return Map.ofEntries(Map.entry("sourceProcess", "SYNTHETIC-P"), Map.entry("sourceEquipment", "SYN-EQ-A"),
                Map.entry("targetProcess", "SYNTHETIC-P"), Map.entry("targetEquipment", targetEquipment),
                Map.entry("connectionId", connectionId), Map.entry("connectionType", "SERIES"),
                Map.entry("materialClass", "WASTEWATER"), Map.entry("materialType", "synthetic water"),
                Map.entry("unit", "ton/day"), Map.entry("period", "2026-11-02/SYNTHETIC_TEST_PERIOD"));
    }
    private void requireEnabled(String username) {
        if (blank(username)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
        if (!enabled || !userCopies.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "PIPE_CHANGE is disabled unless both isolated synthetic and user-working-copy feature flags are enabled.");
    }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record Ready(PipeChangeDrawingEngine.Request request, PipeChangeDrawingEngine.Assessment assessment,
                        PipeChangeSyntheticNetworkFactory.NetworkPair networks,
                        PipeChangeDrawingEngine.WhatIfMappingComparison mappingComparison,
                        PipeChangeDrawingEngine.WhatIfCalculationComparison calculationComparison,
                        NetworkWhatIfService.WhatIfReport beforeReport,
                        NetworkWhatIfService.WhatIfReport afterReport) { }

    public record Preview(String previewId, String proposalId, String requestUser, long workingDocumentId,
                          String processCode, String workingSha256, String fixtureSha256,
                          String analysisFingerprint, PipeChangeDrawingEngine.Assessment assessment,
                          PipeChangeDrawingEngine.WhatIfMappingComparison mappingComparison,
                          PipeChangeDrawingEngine.WhatIfCalculationComparison calculationComparison,
                          String mode, String officialReadiness, boolean officialBaselineModified,
                          String notice) { }

    public record Generated(String previewId, String proposalId, long outputId, long workingDocumentId,
                            String processCode, String sheetName, byte[] bytes, String sourceFixtureSha256,
                            String outputSha256, String analysisFingerprint,
                            PipeChangeDrawingEngine.WhatIfMappingComparison mappingComparison,
                            PipeChangeDrawingEngine.WhatIfCalculationComparison calculationComparison,
                            String mode, String officialReadiness, boolean officialBaselineModified, String notice) {
        public Generated { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
