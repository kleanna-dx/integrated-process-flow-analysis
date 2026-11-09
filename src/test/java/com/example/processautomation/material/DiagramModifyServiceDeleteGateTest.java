package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.DeleteImpactAnalyzer;
import com.example.processautomation.excel.DiagramBoxChangeEngine;
import com.example.processautomation.excel.EquipmentBoxProfile;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DiagramModifyServiceDeleteGateTest {
    private static final String USER = "alice@example.test";
    private static final String SOURCE_SHA = "1".repeat(64);
    private static final String WORKING_SHA = "2".repeat(64);
    private static final String FINGERPRINT = "3".repeat(64);

    @Test
    void featureFlagOffBlocksDeletePreviewBeforeProposalOrWorkbookGeneration() {
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(false);
        var proposals = mock(ChangeProposalService.class);
        var engine = mock(DiagramBoxChangeEngine.class);
        var analyzer = mock(DeleteImpactAnalyzer.class);
        var service = service(userCopies, proposals, engine, analyzer,
                mock(GeneralDiagramEditService.class), mock(DiagramEditWorkingCopyRepository.class));

        assertThatThrownBy(() -> service.previewDelete(input(FINGERPRINT), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(proposals, never()).create(eq(USER), any(ChangeProposalService.ProposalInput.class));
        verify(engine, never()).deleteBoxTestOnly(any(), any(DeleteImpactAnalyzer.Assessment.class));
        verify(analyzer, never()).analyze(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteAssessmentRejectsAnotherUsersWorkingCopy() {
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        var guard = mock(GeneralDiagramEditService.class);
        when(guard.safeWorkingCopy(71, USER)).thenReturn(copy("bob@example.test"));
        var sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSource(701)).thenReturn(new DiagramEditWorkingCopyRepository.SourceIdentity(
                701, "SYNTH-DELETE", "PROCESS", SOURCE_SHA, "V1.0", true));
        var service = service(userCopies, mock(ChangeProposalService.class), mock(DiagramBoxChangeEngine.class),
                mock(DeleteImpactAnalyzer.class), guard, sources);

        assertThatThrownBy(() -> service.assessDelete(71, "Synthetic-8-row", "SYNTH-01", USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void changedFingerprintBlocksPreviewWithoutAppendingProposal() {
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        var guard = mock(GeneralDiagramEditService.class);
        when(guard.safeWorkingCopy(71, USER)).thenReturn(copy(USER));
        var sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSource(701)).thenReturn(new DiagramEditWorkingCopyRepository.SourceIdentity(
                701, "SYNTH-DELETE", "PROCESS", SOURCE_SHA, "V1.0", true));
        var profile = profile();
        var profiles = mock(EquipmentBoxProfileRepository.class);
        when(profiles.findFor("SYNTH-DELETE", "Synthetic-8-row", SOURCE_SHA)).thenReturn(Optional.of(profile));
        var analysis = safeAssessment();
        var analyzer = mock(DeleteImpactAnalyzer.class);
        when(analyzer.analyze(any(), eq("SYNTH-DELETE"), eq("Synthetic-8-row"), eq("SYNTH-01"),
                eq(profile), eq(SOURCE_SHA), eq(WORKING_SHA))).thenReturn(analysis);
        var proposals = mock(ChangeProposalService.class);
        var service = service(userCopies, proposals, mock(DiagramBoxChangeEngine.class), analyzer, guard, sources, profiles);

        assertThatThrownBy(() -> service.previewDelete(input("f".repeat(64)), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fingerprint");
        verify(proposals, never()).create(eq(USER), any(ChangeProposalService.ProposalInput.class));
    }

    @Test
    void changedStateAtGenerateBlocksEngineAndArtifactPersistence() {
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        var guard = mock(GeneralDiagramEditService.class);
        when(guard.safeWorkingCopy(71, USER)).thenReturn(copy(USER));
        var sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSource(701)).thenReturn(new DiagramEditWorkingCopyRepository.SourceIdentity(
                701, "SYNTH-DELETE", "PROCESS", SOURCE_SHA, "V1.0", true));
        var profile = profile();
        var profiles = mock(EquipmentBoxProfileRepository.class);
        when(profiles.findFor("SYNTH-DELETE", "Synthetic-8-row", SOURCE_SHA)).thenReturn(Optional.of(profile));
        var current = safeAssessment();
        var analyzer = mock(DeleteImpactAnalyzer.class);
        when(analyzer.analyze(any(), eq("SYNTH-DELETE"), eq("Synthetic-8-row"), eq("SYNTH-01"),
                eq(profile), eq(SOURCE_SHA), eq(WORKING_SHA))).thenReturn(current);
        var engine = mock(DiagramBoxChangeEngine.class);
        var proposals = mock(ChangeProposalService.class);
        var service = service(userCopies, proposals, engine, analyzer, guard, sources, profiles);
        var stalePreview = new DiagramModifyService.DeletePreview("preview-1", "proposal-1", 71,
                "SYNTH-DELETE", "Synthetic-8-row", "SYNTH-01", WORKING_SHA,
                "f".repeat(64), current, "TEST_ONLY_READY", "CALCULATION_UNAVAILABLE");

        assertThatThrownBy(() -> service.generateDelete(stalePreview, USER))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("미리보기와 일치하지 않거나 안전하지 않습니다");
        verify(engine, never()).deleteBoxTestOnly(any(), any(DeleteImpactAnalyzer.Assessment.class));
        verify(userCopies, never()).saveTestOnlyOutput(eq(USER), eq(71), any(), any(), eq("DELETE"), any(), any());
        verify(proposals, never()).recordTestOnlyOutput(eq(USER), eq("proposal-1"), any());
    }

    private DiagramModifyService service(UserWorkingCopyService userCopies, ChangeProposalService proposals,
                                         DiagramBoxChangeEngine engine, DeleteImpactAnalyzer analyzer,
                                         GeneralDiagramEditService guard, DiagramEditWorkingCopyRepository sources) {
        return service(userCopies, proposals, engine, analyzer, guard, sources,
                mock(EquipmentBoxProfileRepository.class));
    }

    private DiagramModifyService service(UserWorkingCopyService userCopies, ChangeProposalService proposals,
                                         DiagramBoxChangeEngine engine, DeleteImpactAnalyzer analyzer,
                                         GeneralDiagramEditService guard, DiagramEditWorkingCopyRepository sources,
                                         EquipmentBoxProfileRepository profiles) {
        return new DiagramModifyService(mock(NumericWorkingCopyService.class), guard, sources, engine,
                mock(JdbcTemplate.class), profiles, mock(ProcessDiagramStructureAnalyzer.class), analyzer,
                userCopies, proposals);
    }

    private DiagramModifyService.DeleteInput input(String fingerprint) {
        return new DiagramModifyService.DeleteInput(71, "Synthetic-8-row", "SYNTH-01",
                WORKING_SHA, fingerprint, "Synthetic test only proposal rationale");
    }

    private NumericWorkingCopyService.WorkingCopy copy(String owner) {
        return new NumericWorkingCopyService.WorkingCopy(71, 701, "SYNTH-DELETE", "official.xlsx",
                "working.xlsx", SOURCE_SHA, WORKING_SHA, UserWorkingCopyService.CONVERSION_VERSION,
                "CREATED_WITH_REVIEW", new byte[]{1, 2, 3}, UserWorkingCopyService.COPY_KIND, owner,
                1, null, 3, "VERIFIED", "[]");
    }

    private EquipmentBoxProfile profile() {
        return new EquipmentBoxProfile("SYNTH-DELETE", "SYNTH-DELETE", "Synthetic-8-row", "synthetic.xlsx",
                SOURCE_SHA, WORKING_SHA, "0.1.0", "VERIFIED", "SYNTHETIC_TESTED", "synthetic only",
                "J1:AA8", 8, Map.of(), List.of(), Map.of(), "맑은 고딕", (short) 8, "DEFAULT_BLACK",
                Map.of(), Map.of(), "PRESERVE_ONLY", "PRESERVE_ONLY_NO_NEW_CONNECTOR", "BOX_ONLY_TEST_ONLY",
                "'Synthetic-8-row'!A1:AN60", "PRESERVE", null, "BOX_ONLY_SYNTHETIC_TEST_ONLY");
    }

    private DeleteImpactAnalyzer.Assessment safeAssessment() {
        return new DeleteImpactAnalyzer.Assessment("SAFE_BOX_ONLY_TEST_ONLY", true, "SYNTH-DELETE",
                "Synthetic-8-row", "SYNTH-01", "J1", "AA8", "J1:AA8", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "SYNTH-DELETE", "0.1.0", "profile-hash", "VERIFIED",
                "SYNTHETIC_TESTED", "BOX_ONLY_SYNTHETIC_TEST_ONLY", true, true, SOURCE_SHA, WORKING_SHA,
                WORKING_SHA, List.of(), "'Synthetic-8-row'!A1:AN60", "layout", true,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", List.of(), "synthetic test only");
    }
}
