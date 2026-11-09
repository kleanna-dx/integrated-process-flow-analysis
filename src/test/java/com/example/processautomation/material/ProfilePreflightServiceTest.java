package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProfilePreflightServiceTest {
    private static final String P02_SOURCE_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String P02_WORKING_SHA = "c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2";
    private static final String P01_SOURCE_SHA = "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
    private static final String P01_WORKING_SHA = "a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb";
    private static final String P02_SOURCE = "data/reference/P-02 제지3호기 공정 ★변경허가 REV3.xlsx";
    private static final String P02_WORKING = System.getProperty("pilot07.p02.working-copy",
            "../uploaded_files/P-02 제지3호기 공정(수정).xlsx");
    private static final String P01_SOURCE = "data/reference/P-01 제지2호기공정 ★변경허가 REV3.1.xlsx";
    private static final String P01_WORKING = System.getProperty("pilot07.p01.working-copy",
            "../uploaded_files/P-01 제지2호기공정(수정).xlsx");

    @Test
    @EnabledIfSystemProperty(named = "pilot08.real.p02", matches = "true")
    void exactP02WorkingCopyReturnsReadOnlyBlockersWithoutModifyAuthorization() throws Exception {
        byte[] sourceBytes = Files.readAllBytes(Path.of(P02_SOURCE));
        byte[] workingBytes = Files.readAllBytes(Path.of(P02_WORKING));
        var copy = copy(71, "P-02", P02_SOURCE_SHA, P02_WORKING_SHA, workingBytes, "alice");
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        when(userCopies.getForUser(71, "alice")).thenReturn(copy);
        var sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSourceWorkbook(711)).thenReturn(Optional.of(source(711, "P-02", P02_SOURCE_SHA, sourceBytes)));
        var service = service(userCopies, sources, true);

        var result = service.inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");

        assertThat(result.applicationStatus()).isEqualTo("READ_ONLY_REVIEW_ONLY");
        assertThat(result.sourceShaVerified()).isTrue();
        assertThat(result.workingCopyShaVerified()).isTrue();
        assertThat(result.candidateShaVerified()).isTrue();
        assertThat(result.requestIdentityVerified()).isTrue();
        assertThat(result.workingCopySha256()).isEqualTo(P02_WORKING_SHA);
        assertThat(result.sourceSha256()).isEqualTo(P02_SOURCE_SHA);
        assertThat(result.structuralStatus()).isEqualTo("STRUCTURE_CHECK_PASS");
        assertThat(result.decisionStatus()).isEqualTo("BLOCKED");
        assertThat(result.profileSelectionStatus()).isEqualTo("BLOCKED");
        assertThat(result.profileSelectionBlockers()).contains("PROFILE_SELECTION_AMBIGUOUS");
        assertThat(result.modifyAuthorized()).isFalse();
        assertThat(result.modifyBlockedReasons()).contains("PROFILE_STYLE_MISMATCH", "PROFILE_ROW_HEIGHT_MISMATCH",
                "PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED", "REVIEW_REQUIRED_CANDIDATE_NOT_AUTHORIZED",
                "MODIFY_NOT_AUTHORIZED_BY_PREFLIGHT");
        var name = result.fields().stream().filter(field -> "equipmentName".equals(field.field())).findFirst().orElseThrow();
        assertThat(name.cellAddress()).isEqualTo("I89");
        assertThat(name.expectedProfileStyleId()).isEqualTo(220);
        assertThat(name.actualStyleId()).isEqualTo(262);
        assertThat(name.expectedProfileRowHeightPt()).isEqualTo(11.1f);
        assertThat(name.actualRowHeightPt()).isEqualTo(12.0f);
        assertThat(result.mergeStructure().actualWorkingCopyRanges())
                .containsExactly(new com.example.processautomation.excel.ProfileCandidatePreflight.RelativeRange(1, 13, 7, 17));
        assertThat(result.mergeStructure().candidateDiffersFromProfileTemplate()).isTrue();
        assertThat(result.drawingInterference()).anySatisfy(drawing -> {
            assertThat(drawing.objectId()).isEqualTo("19");
            assertThat(drawing.context()).isEqualTo("TARGET_BOX");
            assertThat(drawing.classification()).isEqualTo("BOX_REGION_REVIEW");
        });
        assertThat(result.drawingInterference()).anySatisfy(drawing -> {
            assertThat(drawing.objectId()).isEqualTo("109");
            assertThat(drawing.context()).isEqualTo("PROFILE_REFERENCE_FIELD");
            assertThat(drawing.referenceCell()).isEqualTo("I584");
            assertThat(drawing.field()).isEqualTo("equipmentName");
            assertThat(drawing.classification()).isEqualTo("PROFILE_REFERENCE_FIELD_OVERLAP");
        });
        assertThat(result.errorCodes()).contains("DRAWINGML_PROFILE_REFERENCE_FIELD_OVERLAP");
        assertThat(result.findings()).extracting(ProfilePreflightService.FindingReview::code)
                .contains("DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED");
        assertThat(result.additionalApprovalRequirements()).contains(
                "INDEPENDENT_HUMAN_REVIEW_AND_EXPLICIT_APPROVAL_REQUIRED", "PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED",
                "DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED", "PROFILE_REFERENCE_DRAWING_INTERFERENCE_REVIEW");
        assertThat(sha256(Files.readAllBytes(Path.of(P02_SOURCE)))).isEqualTo(P02_SOURCE_SHA);
        assertThat(sha256(Files.readAllBytes(Path.of(P02_WORKING)))).isEqualTo(P02_WORKING_SHA);
        verify(userCopies).getForUser(71, "alice");
        writeResult("PROFILE_PREFLIGHT_P02.json", result);
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot08.real.p01", matches = "true")
    void exactP01WorkingCopyStaysReviewRequiredAndReportsDrawingInterference() throws Exception {
        byte[] sourceBytes = Files.readAllBytes(Path.of(P01_SOURCE));
        byte[] workingBytes = Files.readAllBytes(Path.of(P01_WORKING));
        var userCopies = mock(UserWorkingCopyService.class);
        when(userCopies.enabled()).thenReturn(true);
        when(userCopies.getForUser(72, "alice"))
                .thenReturn(copy(72, "P-01", P01_SOURCE_SHA, P01_WORKING_SHA, workingBytes, "alice"));
        var sources = mock(DiagramEditWorkingCopyRepository.class);
        when(sources.findLockedSourceWorkbook(712)).thenReturn(Optional.of(source(712, "P-01", P01_SOURCE_SHA, sourceBytes)));

        var result = service(userCopies, sources, true).inspect(72, "P-01", "P-01-01(TOP)", "I-PP11001", "alice");

        assertThat(result.processCode()).isEqualTo("P-01");
        assertThat(result.workingDocumentId()).isEqualTo(72);
        assertThat(result.sourceShaVerified()).isTrue();
        assertThat(result.workingCopyShaVerified()).isTrue();
        assertThat(result.candidateShaVerified()).isTrue();
        assertThat(result.candidateStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.profileStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.decisionStatus()).isEqualTo("BLOCKED");
        assertThat(result.modifyAuthorized()).isFalse();
        assertThat(result.fields()).hasSize(6);
        assertThat(result.findings()).extracting(ProfilePreflightService.FindingReview::code)
                .contains("DRAWINGML_FIELD_OVERLAP", "DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED",
                        "DRAWINGML_PROFILE_REFERENCE_FIELD_OVERLAP");
        assertThat(sha256(Files.readAllBytes(Path.of(P01_SOURCE)))).isEqualTo(P01_SOURCE_SHA);
        assertThat(sha256(Files.readAllBytes(Path.of(P01_WORKING)))).isEqualTo(P01_WORKING_SHA);
        writeResult("PROFILE_PREFLIGHT_P01.json", result);
    }

    @Test
    void staleWorkingShaAndUnmappedProcessOrSheetFailClosed() throws Exception {
        byte[] sourceBytes = Files.readAllBytes(Path.of(P02_SOURCE));
        byte[] workingBytes = Files.readAllBytes(Path.of(P02_WORKING));
        var staleCopies = mock(UserWorkingCopyService.class);
        when(staleCopies.enabled()).thenReturn(true);
        when(staleCopies.getForUser(73, "alice"))
                .thenReturn(copy(73, "P-02", P02_SOURCE_SHA, "0".repeat(64), workingBytes, "alice"));
        var staleSources = mock(DiagramEditWorkingCopyRepository.class);
        when(staleSources.findLockedSourceWorkbook(713)).thenReturn(Optional.of(source(713, "P-02", P02_SOURCE_SHA, sourceBytes)));
        var stale = service(staleCopies, staleSources, true)
                .inspect(73, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");
        assertThat(stale.decisionStatus()).isEqualTo("BLOCKED");
        assertThat(stale.errorCodes()).contains("WORKING_COPY_SHA_MISMATCH");
        assertThat(stale.modifyAuthorized()).isFalse();

        var unsupported = service(staleCopies, staleSources, true)
                .inspect(73, "P-02", "P-02-99(UNPROFILED)", "I-PP21005", "alice");
        assertThat(unsupported.errorCodes()).contains("WORKING_COPY_SHA_MISMATCH");
        assertThat(unsupported.modifyAuthorized()).isFalse();

        when(staleCopies.getForUser(73, "alice"))
                .thenReturn(copy(73, "P-02", P02_SOURCE_SHA, P02_WORKING_SHA, workingBytes, "alice"));
        var valid = service(staleCopies, staleSources, true)
                .inspect(73, "P-02", "P-02-99(UNPROFILED)", "I-PP21005", "alice");
        assertThat(valid.errorCodes()).contains("PROFILE_CANDIDATE_NOT_SUPPORTED");
        assertThat(valid.sourceShaVerified()).isTrue();
        assertThat(valid.workingCopyShaVerified()).isTrue();
        assertThat(valid.modifyAuthorized()).isFalse();
    }

    @Test
    void mismatchedProcessIsBlockedBeforeLoadingOfficialSource() throws Exception {
        byte[] p01Bytes = Files.readAllBytes(Path.of(P01_WORKING));
        var copies = mock(UserWorkingCopyService.class);
        when(copies.enabled()).thenReturn(true);
        when(copies.getForUser(75, "alice"))
                .thenReturn(copy(75, "P-01", P01_SOURCE_SHA, P01_WORKING_SHA, p01Bytes, "alice"));
        var sources = mock(DiagramEditWorkingCopyRepository.class);

        var result = service(copies, sources, true)
                .inspect(75, "P-02", "P-02-01(TOP)", "I-PP21005", "alice");

        assertThat(result.errorCodes()).contains("WORKING_COPY_PROCESS_MISMATCH");
        assertThat(result.sourceShaVerified()).isFalse();
        assertThat(result.modifyAuthorized()).isFalse();
        verify(sources, never()).findLockedSourceWorkbook(715);
    }

    @Test
    void featureFlagOffAndForeignOwnerAreRejectedBeforeSourceLookup() {
        var disabledCopies = mock(UserWorkingCopyService.class);
        when(disabledCopies.enabled()).thenReturn(false);
        var disabledSources = mock(DiagramEditWorkingCopyRepository.class);
        var disabled = service(disabledCopies, disabledSources, false);
        assertThatThrownBy(() -> disabled.inspect(71, "P-02", "P-02-01(TOP)", "I-PP21005", "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(disabledCopies, never()).getForUser(71, "alice");
        verify(disabledSources, never()).findLockedSourceWorkbook(711);

        var ownerCopies = mock(UserWorkingCopyService.class);
        when(ownerCopies.enabled()).thenReturn(true);
        when(ownerCopies.getForUser(74, "mallory")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        var ownerSources = mock(DiagramEditWorkingCopyRepository.class);
        assertThatThrownBy(() -> service(ownerCopies, ownerSources, true)
                .inspect(74, "P-02", "P-02-01(TOP)", "I-PP21005", "mallory"))
                .isInstanceOf(ResponseStatusException.class);
        verify(ownerSources, never()).findLockedSourceWorkbook(714);
    }

    private ProfilePreflightService service(UserWorkingCopyService userCopies,
                                            DiagramEditWorkingCopyRepository sources, boolean enabled) {
        return new ProfilePreflightService(userCopies, sources,
                new EquipmentBoxProfileRepository(new ObjectMapper()), new ObjectMapper(), enabled);
    }

    private NumericWorkingCopyService.WorkingCopy copy(long id, String process, String sourceSha,
            String declaredWorkingSha, byte[] bytes, String owner) throws Exception {
        return new NumericWorkingCopyService.WorkingCopy(id, id + 640, process, "official.xlsx", "working.xlsx",
                sourceSha, declaredWorkingSha, UserWorkingCopyService.CONVERSION_VERSION, "CREATED_WITH_REVIEW",
                bytes, UserWorkingCopyService.COPY_KIND, owner, 1, null, bytes.length, "REVIEW_REQUIRED", null);
    }

    private DiagramEditWorkingCopyRepository.LockedSourceWorkbook source(long id, String process,
            String sha, byte[] bytes) {
        return new DiagramEditWorkingCopyRepository.LockedSourceWorkbook(id, process, "PROCESS", sha,
                "V1.0", true, bytes);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void writeResult(String filename, ProfilePreflightService.PreflightResult result) throws Exception {
        String outputDirectory = System.getProperty("pilot08.validation-output-dir");
        if (outputDirectory == null || outputDirectory.isBlank() || !Files.isDirectory(Path.of(outputDirectory))) {
            throw new IllegalStateException("Use a pre-created PILOT-08 final-run directory for JSON evidence.");
        }
        byte[] json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        Files.write(Path.of(outputDirectory, filename), json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
