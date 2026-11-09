package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.FlowChangeDrawingEngine;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.OoxmlPartEditor;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class FlowChangeAuthenticatedWorkflowServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserWorkingCopyService userCopies = mock(UserWorkingCopyService.class);
    private final ChangeProposalService proposals = mock(ChangeProposalService.class);
    private final NetworkWhatIfService whatIf = new NetworkWhatIfService(jdbc, new MaterialBalanceCalculator());
    private final FlowChangeSyntheticFixtureFactory fixtures = new FlowChangeSyntheticFixtureFactory();
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final FlowChangeSyntheticWorkflowService synthetic = new FlowChangeSyntheticWorkflowService(fixtures,
            new FlowChangeDrawingEngine(inspector, new OoxmlPartEditor()), whatIf, inspector);
    private FlowChangeAuthenticatedWorkflowService enabledWorkflow;
    private NumericWorkingCopyService.WorkingCopy validCopy;
    private String fixtureSha;

    @BeforeEach
    void setUp() throws Exception {
        byte[] fixtureBytes = fixtures.create().workbookBytes();
        fixtureSha = sha256(fixtureBytes);
        validCopy = new NumericWorkingCopyService.WorkingCopy(71, 9, "P-02", "synthetic-baseline.xlsx",
                "user-fixture.xlsx", "e".repeat(64), fixtureSha, "USER-WORKING-COPY-V1", "CREATED_WITH_REVIEW",
                fixtureBytes, UserWorkingCopyService.COPY_KIND, "alice", 1, null, fixtureBytes.length,
                "REVIEW_REQUIRED", null);
        when(userCopies.enabled()).thenReturn(true);
        when(userCopies.getForUser(71, "alice")).thenReturn(validCopy);
        enabledWorkflow = new FlowChangeAuthenticatedWorkflowService(synthetic, userCopies, proposals, whatIf, true);
    }

    @Test
    void bothFeatureFlagsAreRequiredBeforeWorkingCopyAccess() {
        var disabled = new FlowChangeAuthenticatedWorkflowService(synthetic, userCopies, proposals, whatIf, false);
        assertThatThrownBy(() -> disabled.fixtureForUser("alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("disabled");
        verify(userCopies, never()).getForUser(anyLong(), any());
    }

    @Test
    void rejectsNonFixtureBytesAndWrongOwnerBeforeProposalOrOutputCreation() throws Exception {
        byte[] foreignBytes = "not the registered server fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String foreignSha = sha256(foreignBytes);
        var foreign = new NumericWorkingCopyService.WorkingCopy(72, 9, "P-02", "source.xlsx", "foreign.xlsx",
                "e".repeat(64), foreignSha, "USER-WORKING-COPY-V1", "CREATED_WITH_REVIEW", foreignBytes,
                UserWorkingCopyService.COPY_KIND, "alice", 1, null, foreignBytes.length, "REVIEW_REQUIRED", null);
        when(userCopies.getForUser(72, "alice")).thenReturn(foreign);
        assertThatThrownBy(() -> enabledWorkflow.preview("alice", 72, foreignSha,
                FlowChangeSyntheticFixtureFactory.FIXTURE_ID, fixtureSha, "FLOW-10", "synthetic test"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("byte-identical");

        var wrongOwner = new NumericWorkingCopyService.WorkingCopy(73, 9, "P-02", "source.xlsx", "other-owner.xlsx",
                "e".repeat(64), fixtureSha, "USER-WORKING-COPY-V1", "CREATED_WITH_REVIEW",
                fixtures.create().workbookBytes(), UserWorkingCopyService.COPY_KIND, "bob", 1, null,
                fixtures.create().workbookBytes().length, "REVIEW_REQUIRED", null);
        when(userCopies.getForUser(73, "alice")).thenReturn(wrongOwner);
        assertThatThrownBy(() -> enabledWorkflow.preview("alice", 73, fixtureSha,
                FlowChangeSyntheticFixtureFactory.FIXTURE_ID, fixtureSha, "FLOW-10", "synthetic test"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("byte-identical");
        verify(proposals, never()).create(any(), any());
        verify(userCopies, never()).saveFlowChangeSyntheticOutput(any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void baselineTwentyIsExplicitNoOpAndCannotGenerateOutputOrProposal() {
        var preview = enabledWorkflow.preview("alice", 71, fixtureSha,
                FlowChangeSyntheticFixtureFactory.FIXTURE_ID, fixtureSha, "FLOW-20", "baseline no-op verification");
        assertThat(preview.noOp()).isTrue();
        assertThat(preview.beforeQuantity()).isEqualByComparingTo("20");
        assertThat(preview.requestedQuantity()).isEqualByComparingTo("20");
        assertThat(preview.proposalId()).isNull();
        assertThatThrownBy(() -> enabledWorkflow.generate(preview, "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("BASELINE_NO_OP");
        verify(proposals, never()).create(any(), any());
        verify(proposals, never()).recordTestOnlyOutput(any(), any(), any());
        verify(userCopies, never()).saveFlowChangeSyntheticOutput(any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleFingerprintIsRejectedBeforeAnyOutputPersistence() {
        var current = synthetic.previewServerSyntheticFixture(FlowChangeSyntheticFixtureFactory.FIXTURE_ID, fixtureSha);
        var stale = new FlowChangeAuthenticatedWorkflowService.Preview("preview-stale", "proposal-stale", "alice", 71,
                "P-02", fixtureSha, current.fixtureId(), current.sourceSha256(), "0".repeat(64), "FLOW-10",
                new java.math.BigDecimal("20"), new java.math.BigDecimal("10"), new java.math.BigDecimal("10"),
                java.util.Map.of(), java.util.List.of(), false, "TEST_ONLY_REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", false);
        assertThatThrownBy(() -> enabledWorkflow.generate(stale, "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("PREVIEW_STALE");
        verify(userCopies, never()).saveFlowChangeSyntheticOutput(any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(proposals, never()).recordTestOnlyOutput(any(), any(), any());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
