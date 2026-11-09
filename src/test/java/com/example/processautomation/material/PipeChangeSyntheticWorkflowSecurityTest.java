package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.OoxmlPartEditor;
import com.example.processautomation.excel.PipeChangeDrawingEngine;
import com.example.processautomation.excel.PipeChangeSyntheticFixtureFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PipeChangeSyntheticWorkflowSecurityTest {
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final PipeChangeSyntheticFixtureFactory fixtures = new PipeChangeSyntheticFixtureFactory(inspector, parts);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NetworkWhatIfService whatIf = new NetworkWhatIfService(jdbc, new MaterialBalanceCalculator());
    private final UserWorkingCopyService workingCopies = mock(UserWorkingCopyService.class);
    private final ChangeProposalService proposals = mock(ChangeProposalService.class);
    private final PipeChangeSyntheticWorkflowService workflow = new PipeChangeSyntheticWorkflowService(
            new PipeChangeDrawingEngine(inspector, parts), fixtures,
            new PipeChangeSyntheticNetworkFactory(), whatIf, workingCopies, proposals, true);

    @Test
    void onlyByteIdenticalOwnedSyntheticCopyCanPreviewAndGenerateAppendOnlyTestOutput() {
        var fixture = fixtures.fixture();
        when(workingCopies.enabled()).thenReturn(true);
        when(workingCopies.getForUser(71L, "alice")).thenReturn(copy(71, "alice", fixture.bytes(), fixture.sha256()));
        when(proposals.create(eq("alice"), any())).thenAnswer(invocation -> proposal("alice", 71,
                fixture.sha256(), "PIPE_CHANGE"));
        when(proposals.recordSyntheticPipeChangeWhatIf(eq("alice"), any(), any(), any())).thenAnswer(invocation -> null);
        when(proposals.recordPipeChangeDrawingValidation(eq("alice"), any(), any())).thenAnswer(invocation -> null);
        when(workingCopies.saveTestOnlyOutput(eq("alice"), eq(71L), eq(fixture.sha256()), any(),
                eq("PIPE_CHANGE"), any(), any())).thenAnswer(invocation -> new UserWorkingCopyService.StoredOutput(
                        901, 71, invocation.getArgument(3), 4000, "TEST_ONLY", "REVIEW_REQUIRED"));
        when(proposals.recordTestOnlyOutput(eq("alice"), any(), any())).thenAnswer(invocation -> null);

        var preview = workflow.preview("alice", 71, fixture.sha256(), "Synthetic drawing route check");
        assertThat(preview.mappingComparison().status()).isEqualTo("SYNTHETIC_MAPPING_VERIFIED_TEST_ONLY");
        assertThat(preview.calculationComparison().status()).isEqualTo("DRAWING_WHATIF_MATCHED_TEST_ONLY");
        assertThat(preview.officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(preview.assessment().physicalEvidenceStatus())
                .isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        assertThat(preview.calculationComparison().officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(preview.calculationComparison().status()).isEqualTo("DRAWING_WHATIF_MATCHED_TEST_ONLY");
        assertThat(preview.officialBaselineModified()).isFalse();

        var generated = workflow.generate(preview, "alice");
        assertThat(generated.outputId()).isEqualTo(901);
        assertThat(generated.mode()).isEqualTo("SYNTHETIC_TEST_ONLY");
        assertThat(generated.officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(generated.calculationComparison().officialReadiness()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(generated.calculationComparison().interpretation())
                .contains("physical connection evidence");
        assertThat(generated.officialBaselineModified()).isFalse();
        assertThat(generated.outputSha256()).isNotEqualTo(fixture.sha256());
        when(workingCopies.downloadTestOnlyOutput(eq(901L), eq("alice"))).thenReturn(
                new UserWorkingCopyService.StoredArtifact(901, 71, "synthetic-pipe.xlsx", generated.outputSha256(),
                        generated.bytes().length, "TEST_ONLY", "REVIEW_REQUIRED", "PIPE_CHANGE", generated.bytes()));
        var downloaded = workflow.download(901, "alice");
        assertThat(sha(downloaded.bytes())).isEqualTo(generated.outputSha256());
        assertThat(downloaded.bytes()).isEqualTo(generated.bytes());
        try {
            var artifactRoot = java.nio.file.Path.of("target/test-artifacts/step8-2-pipe-change");
            java.nio.file.Files.createDirectories(artifactRoot);
            var run = java.nio.file.Files.createTempDirectory(artifactRoot, "server-fixture-run-");
            var before = run.resolve("fixture-BEFORE-SYNTHETIC_TEST_ONLY.xlsx");
            var after = run.resolve("fixture-AFTER-SYNTHETIC_TEST_ONLY.xlsx");
            java.nio.file.Files.write(before, fixture.bytes());
            java.nio.file.Files.write(after, generated.bytes());
            java.nio.file.Files.writeString(run.resolve("SHA256SUMS"), fixture.sha256() + "  " + before.getFileName()
                    + "\n" + generated.outputSha256() + "  " + after.getFileName() + "\n");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Unable to save a unique synthetic verification artifact", exception);
        }
        assertThat(inspector.inspectSheet(generated.bytes(), "Synthetic").drawingObjects())
                .anySatisfy(object -> {
                    if ("CONNECTOR".equals(object.objectType())) {
                        assertThat(object.startConnectedShapeId()).isEqualTo(fixture.startShapeId());
                        assertThat(object.endConnectedShapeId()).isEqualTo(fixture.afterEndShapeId());
                    }
                });
        verify(workingCopies).saveTestOnlyOutput(eq("alice"), eq(71L), eq(fixture.sha256()), eq(generated.outputSha256()),
                eq("PIPE_CHANGE"), any(), eq(generated.bytes()));
        verify(proposals).recordPipeChangeDrawingValidation(eq("alice"), eq(preview.proposalId()), any());
        verify(proposals).recordTestOnlyOutput(eq("alice"), eq(preview.proposalId()), any());
        verify(workingCopies).downloadTestOnlyOutput(901, "alice");
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void refusesSyntheticProposalReportsThatClaimAvailableReadinessOrModifiedBaseline() {
        var repository = mock(ChangeProposalRepository.class);
        when(repository.latest("proposal-available", "alice")).thenReturn(Map.of("change_type", "PIPE_CHANGE"));
        when(repository.latest("proposal-modified", "alice")).thenReturn(Map.of("change_type", "PIPE_CHANGE"));
        var service = new ChangeProposalService(repository, mock(GeneralDiagramEditService.class),
                mock(NetworkWhatIfService.class), mock(DirectedProcessNetworkWorkbookExporter.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        var available = new NetworkWhatIfService.WhatIfReport("synthetic-scope", "available spoof", "TEST_ONLY",
                Map.of("status", "AVAILABLE", "calculationAvailable", true), null, List.of(), false, false,
                "forged readiness");
        assertThatThrownBy(() -> service.recordSyntheticPipeChangeWhatIf("alice", "proposal-available", available, available))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot promote official readiness");

        var modified = new NetworkWhatIfService.WhatIfReport("synthetic-scope", "modified baseline spoof", "TEST_ONLY",
                Map.of("status", "CALCULATION_UNAVAILABLE", "calculationAvailable", false), null, List.of(),
                true, false, "forged baseline mutation");
        assertThatThrownBy(() -> service.recordSyntheticPipeChangeWhatIf("alice", "proposal-modified", modified, modified))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot promote official readiness");
        org.mockito.Mockito.verify(repository, never()).append(any());
    }

    @Test
    void blocksGenerateAfterWorkingCopyChangesOrPreviewFingerprintBecomesStale() {
        var fixture = fixtures.fixture();
        when(workingCopies.enabled()).thenReturn(true);
        byte[] changedBytes = fixture.bytes();
        changedBytes[changedBytes.length - 1] ^= 0x01;
        when(workingCopies.getForUser(78L, "alice")).thenReturn(
                copy(78, "alice", fixture.bytes(), fixture.sha256()),
                copy(78, "alice", changedBytes, sha(changedBytes)));
        when(workingCopies.getForUser(79L, "alice")).thenReturn(copy(79, "alice", fixture.bytes(), fixture.sha256()));
        when(proposals.create(eq("alice"), any())).thenAnswer(invocation -> proposal("alice",
                ((ChangeProposalService.ProposalInput) invocation.getArgument(1)).workingDocumentId(),
                fixture.sha256(), "PIPE_CHANGE"));
        when(proposals.recordSyntheticPipeChangeWhatIf(eq("alice"), any(), any(), any())).thenReturn(null);
        when(proposals.recordPipeChangeDrawingValidation(eq("alice"), any(), any())).thenReturn(null);
        when(proposals.recordTestOnlyOutput(eq("alice"), any(), any())).thenReturn(null);

        var changedPreview = workflow.preview("alice", 78, fixture.sha256(), "change after preview");
        assertThatThrownBy(() -> workflow.generate(changedPreview, "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("server-managed synthetic fixture");

        var fresh = workflow.preview("alice", 79, fixture.sha256(), "stale fingerprint");
        var forgedStale = new PipeChangeSyntheticWorkflowService.Preview(fresh.previewId(), fresh.proposalId(),
                fresh.requestUser(), fresh.workingDocumentId(), fresh.processCode(), fresh.workingSha256(),
                fresh.fixtureSha256(), "0".repeat(64), fresh.assessment(), fresh.mappingComparison(),
                fresh.calculationComparison(), fresh.mode(), fresh.officialReadiness(), fresh.officialBaselineModified(),
                fresh.notice());
        assertThatThrownBy(() -> workflow.generate(forgedStale, "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("stale");

        verify(workingCopies, never()).saveTestOnlyOutput(any(), any(Long.class), any(), any(), any(), any(), any());
        verify(proposals, never()).recordTestOnlyOutput(any(), any(), any());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(2)).create(eq("alice"), any());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(2)).recordPipeChangeDrawingValidation(
                eq("alice"), any(), any());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(2))
                .recordSyntheticPipeChangeWhatIf(eq("alice"), any(), any(), any());
    }

    @Test
    void rejectsDifferentUploadChangedShaAndUnownedCopyWithoutSavingOutput() {
        var fixture = fixtures.fixture();
        when(workingCopies.enabled()).thenReturn(true);
        byte[] altered = fixture.bytes().clone(); altered[altered.length - 1] ^= 0x01;
        when(workingCopies.getForUser(72L, "alice")).thenReturn(copy(72, "alice", altered, sha(altered)));
        assertThatThrownBy(() -> workflow.preview("alice", 72, sha(altered), "No fake pipe"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("server-managed synthetic fixture");
        when(workingCopies.getForUser(73L, "alice")).thenReturn(copy(73, "alice", fixture.bytes(), fixture.sha256()));
        assertThatThrownBy(() -> workflow.preview("alice", 73, "0".repeat(64), "Stale sha"))
                .isInstanceOf(ResponseStatusException.class);
        when(workingCopies.getForUser(74L, "mallory")).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "not owned"));
        when(workingCopies.getForUser(77L, "alice")).thenReturn(copy(77, "mallory", fixture.bytes(), fixture.sha256()));
        assertThatThrownBy(() -> workflow.preview("alice", 77, fixture.sha256(), "forged owner"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("server-managed synthetic fixture");
        assertThatThrownBy(() -> workflow.preview("mallory", 74, fixture.sha256(), "Other owner"))
                .isInstanceOf(ResponseStatusException.class);
        verify(workingCopies, never()).saveTestOnlyOutput(any(), any(Long.class), any(), any(), any(), any(), any());
        verify(proposals, never()).recordTestOnlyOutput(any(), any(), any());
    }

    @Test
    void featureFlagsAndForgedModeCannotEnableActualWorkbookGeneration() {
        when(workingCopies.enabled()).thenReturn(false);
        var disabled = new PipeChangeSyntheticWorkflowService(new PipeChangeDrawingEngine(inspector, parts), fixtures,
                new PipeChangeSyntheticNetworkFactory(), whatIf, workingCopies, proposals, true);
        assertThatThrownBy(() -> disabled.preview("alice", 75, fixtures.fixture().sha256(), "feature off"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");

        var off = new PipeChangeSyntheticWorkflowService(new PipeChangeDrawingEngine(inspector, parts), fixtures,
                new PipeChangeSyntheticNetworkFactory(), whatIf, workingCopies, proposals, false);
        assertThatThrownBy(() -> off.preview("alice", 76, fixtures.fixture().sha256(), "default off"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> off.fixtureForUser("alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> off.preview("", 76, fixtures.fixture().sha256(), "anonymous"))
                .isInstanceOf(ResponseStatusException.class);
        verify(workingCopies, never()).getForUser(any(Long.class), any());
    }

    private NumericWorkingCopyService.WorkingCopy copy(long id, String user, byte[] bytes, String sha) {
        return new NumericWorkingCopyService.WorkingCopy(id, 1, "SYNTHETIC-P", "synthetic-source.xlsx",
                "server-fixture.xlsx", "a".repeat(64), sha, UserWorkingCopyService.CONVERSION_VERSION,
                "CREATED_WITH_REVIEW", bytes, UserWorkingCopyService.COPY_KIND, user, 1, null,
                bytes.length, "REVIEW_REQUIRED", "[]");
    }

    private ChangeProposalService.ProposalView proposal(String user, long copyId, String sha, String type) {
        return new ChangeProposalService.ProposalView(UUID.randomUUID().toString(), 1, "PROPOSED", type,
                "SYNTHETIC-P", "Synthetic", copyId, sha, user, null, Map.of("connectionId", "SYN-A-B"),
                Map.of("connectionId", "SYN-A-C"), "test proposal", Map.of("classification", "SYNTHETIC_TEST_ONLY"),
                "REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", "REVIEW_REQUIRED", false, null, Map.of(), null,
                "2026-11-02T00:00:00Z", List.of());
    }

    private String sha(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
