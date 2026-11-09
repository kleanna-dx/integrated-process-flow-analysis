package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.example.processautomation.material.ProfileSelectionPreflight;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** DB-free, read-only verification for an exact P-02 candidate snapshot. */
class ProfileCandidatePreflightTest {
    private static final String SOURCE_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String WORKING_SHA = "c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2";
    private static final String PROCESS = "P-02";
    private static final String SHEET = "P-02-01(TOP)";
    private static final String MANAGEMENT_NO = "I-PP21005";

    @Test
    @EnabledIfSystemProperty(named = "pilot07.p02.real", matches = "true")
    void exactWorkingCopyProducesReadOnlyReportAndKeepsEveryWriteGuardClosed() throws Exception {
        Path workbookPath = Path.of(System.getProperty("pilot07.p02.working-copy",
                "/home/user/uploaded_files/P-02 제지3호기 공정(수정).xlsx"));
        assertThat(Files.isRegularFile(workbookPath)).isTrue();
        byte[] bytes = Files.readAllBytes(workbookPath);
        assertThat(sha256(bytes)).isEqualTo(WORKING_SHA);
        Path sourcePath = Path.of("/home/user/webapp/data/reference/P-02 제지3호기 공정 ★변경허가 REV3.xlsx");
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        assertThat(sha256(sourceBytes)).isEqualTo(SOURCE_SHA);

        ObjectMapper mapper = new ObjectMapper();
        var candidate = mapper.readValue(getClass().getResourceAsStream(
                "/profile-review/P02_CANDIDATE_REVIEW.json"), ProfileCandidatePreflight.CandidateSpec.class);
        var profiles = new EquipmentBoxProfileRepository(mapper);
        var existing = profiles.findFor(PROCESS, SHEET, SOURCE_SHA).orElseThrow();
        assertThat(existing.profileId()).isEqualTo("P-02-01-TOP-V1");
        assertThat(existing.status()).isEqualTo("VERIFIED");

        var preflight = new ProfileCandidatePreflight(new OoxmlPackageInspector(), new EquipmentBoxGeometryLocator());
        var report = preflight.inspect(sourceBytes, bytes, new ProfileCandidatePreflight.Request(
                PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA), candidate, existing);

        assertThat(report.candidateStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.structuralStatus()).isEqualTo("STRUCTURE_CHECK_PASS");
        assertThat(report.decisionStatus()).isEqualTo("BLOCKED");
        assertThat(report.modifyAuthorized()).isFalse();
        assertThat(report.actualBoxRange()).isEqualTo("I86:Z93");
        assertThat(report.sourceShaIdentityMatch()).isTrue();
        assertThat(report.workingCopyShaMatch()).isTrue();
        assertThat(report.liveMergedRanges()).containsExactly(
                new ProfileCandidatePreflight.RelativeRange(1, 13, 7, 17));
        assertThat(report.findings()).extracting(ProfileCandidatePreflight.Finding::code)
                .contains("PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED", "PROFILE_STYLE_MISMATCH",
                        "PROFILE_ROW_HEIGHT_MISMATCH", "MANAGEMENT_ID_CHANGE_REQUIRES_GLOBAL_REMAP",
                        "DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED");
        assertThat(report.drawingObjects()).filteredOn(drawing -> "19".equals(drawing.objectId()))
                .singleElement().satisfies(drawing -> {
                    assertThat(drawing.objectIndex()).isEqualTo(184);
                    assertThat(drawing.objectType()).isEqualTo("CONNECTOR");
                    assertThat(drawing.startRow()).isEqualTo(84);
                    assertThat(drawing.startColumn()).isEqualTo(15);
                    assertThat(drawing.startRowOffsetEmu()).isEqualTo(9_525L);
                    assertThat(drawing.endRow()).isEqualTo(85);
                    assertThat(drawing.endColumn()).isEqualTo(15);
                    assertThat(drawing.endRowOffsetEmu()).isZero();
                    assertThat(drawing.headArrowType()).isNull();
                    assertThat(drawing.tailArrowType()).isEqualTo("triangle");
                    assertThat(drawing.startConnectedShapeId()).isNull();
                    assertThat(drawing.endConnectedShapeId()).isNull();
                    assertThat(drawing.classification()).isEqualTo("BOX_REGION_REVIEW");
                    assertThat(drawing.overlappingFields()).isEmpty();
                });
        assertThat(report.drawingObjects()).allSatisfy(drawing -> assertThat(drawing.overlappingFields()).isEmpty());

        var name = report.fields().stream().filter(field -> "equipmentName".equals(field.field())).findFirst().orElseThrow();
        assertThat(name.cellAddress()).isEqualTo("I89");
        assertThat(name.observedExpectedCurrentValue()).isEqualTo("131 MULTI SORTER#1");
        assertThat(name.currentValueMatches()).isTrue();
        assertThat(name.existingProfileExpectedStyleId()).isEqualTo(220);
        assertThat(name.actualStyleId()).isEqualTo(262);
        assertThat(name.existingProfileExpectedRowHeightPt()).isEqualTo(11.1f);
        assertThat(name.actualRowHeightPt()).isEqualTo(12.0f);
        assertThat(name.actualStyleProperties().borderLeft()).isEqualTo("MEDIUM");
        assertThat(name.existingProfileReferenceStyle().borderLeft()).isEqualTo("THIN");

        var badWorkingSha = new ProfileCandidatePreflight.CandidateSpec(candidate.candidateId(),
                candidate.processCode(), candidate.sheetName(), candidate.managementNo(), candidate.sourceSha256(),
                "0".repeat(64), candidate.boxRange(), candidate.status(), candidate.registered(),
                candidate.activationAllowed(), candidate.approvalEvidencePresent(), candidate.templateBoxRange(),
                candidate.existingProfileId(), candidate.existingProfileVersion(), candidate.expectedLiveMerges(),
                candidate.templateRelativeMerges(), candidate.fields());
        var shaReport = preflight.inspect(sourceBytes, bytes, new ProfileCandidatePreflight.Request(
                PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA), badWorkingSha, existing);
        assertThat(shaReport.decisionStatus()).isEqualTo("BLOCKED");
        assertThat(shaReport.findings()).extracting(ProfileCandidatePreflight.Finding::code)
                .contains("PROFILE_WORKING_SHA_MISMATCH");

        var selector = new ProfileSelectionPreflight();
        var existingSelection = new ProfileSelectionPreflight.Candidate(existing.profileId(),
                existing.profileVersion(), PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA, WORKING_SHA,
                existing.status(), true);
        var reviewSelection = new ProfileSelectionPreflight.Candidate(candidate.candidateId(),
                "1.0.0", PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA, WORKING_SHA,
                candidate.status(), false);
        var selection = selector.evaluate(new ProfileSelectionPreflight.Request(
                PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA, WORKING_SHA, null, null),
                java.util.List.of(existingSelection, reviewSelection));
        assertThat(selection.status()).isEqualTo("BLOCKED");
        assertThat(selection.blockingConditions()).contains("PROFILE_SELECTION_AMBIGUOUS");
        var explicitlyExisting = selector.evaluate(new ProfileSelectionPreflight.Request(
                PROCESS, SHEET, MANAGEMENT_NO, SOURCE_SHA, WORKING_SHA, existing.profileId(), existing.profileVersion()),
                java.util.List.of(existingSelection, reviewSelection));
        assertThat(explicitlyExisting.status()).isEqualTo("BLOCKED");
        assertThat(explicitlyExisting.blockingConditions()).contains("REVIEW_CANDIDATE_CONFLICT_REQUIRES_REVIEW");

        DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
                new OoxmlPartEditor(), new OoxmlPackageInspector(), new EquipmentBoxGeometryLocator());
        var mapping = engine.mapFieldsByProfile(bytes, PROCESS, SHEET, MANAGEMENT_NO,
                existing, SOURCE_SHA, WORKING_SHA);
        assertThat(mapping.fields().get("equipmentName").reason()).isEqualTo("PROFILE_STYLE_MISMATCH");
        assertThat(mapping.availableFields()).isEmpty();
        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(bytes, PROCESS, SHEET,
                MANAGEMENT_NO, existing, SOURCE_SHA, WORKING_SHA,
                Map.of("equipmentName", "131 MULTI SORTER#1"), Map.of("equipmentName", "PILOT07 TEST")))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_MODIFY_FIELD_SET_INVALID");
        var i584Mapping = engine.mapFieldsByProfile(bytes, PROCESS, SHEET, "I-PP21039",
                existing, SOURCE_SHA, WORKING_SHA);
        assertThat(i584Mapping.fields().get("equipmentName").reason()).isEqualTo("DRAWINGML_FIELD_OVERLAP");
        assertThat(i584Mapping.availableFields()).doesNotContain("equipmentName");
        var connector109 = new OoxmlPackageInspector().inspectSheet(bytes, SHEET).drawingObjects().stream()
                .filter(drawing -> "109".equals(drawing.shapeId())).findFirst().orElseThrow();
        assertThat(connector109.startRow()).isEqualTo(583);
        assertThat(connector109.startColumn()).isEqualTo(2);
        assertThat(connector109.endRow()).isEqualTo(583);
        assertThat(connector109.endColumn()).isEqualTo(8);
        assertThat(connector109.startConnectedShapeId()).isNull();
        assertThat(connector109.endConnectedShapeId()).isNull();
        assertThat(sha256(Files.readAllBytes(sourcePath))).isEqualTo(SOURCE_SHA);
        assertThat(sha256(Files.readAllBytes(workbookPath))).isEqualTo(WORKING_SHA);

        String validationOutputDir = System.getProperty("pilot07.validation-output-dir");
        if (validationOutputDir == null || validationOutputDir.isBlank()) {
            throw new IllegalStateException("Set pilot07.validation-output-dir to a new, isolated run directory.");
        }
        Path reportPath = Path.of(validationOutputDir, "PROFILE_FIELD_VALIDATION.json");
        Files.createDirectories(reportPath.getParent());
        Map<String, Object> connector109Evidence = new java.util.LinkedHashMap<>();
        connector109Evidence.put("startRow", connector109.startRow());
        connector109Evidence.put("startColumn", connector109.startColumn());
        connector109Evidence.put("endRow", connector109.endRow());
        connector109Evidence.put("endColumn", connector109.endColumn());
        connector109Evidence.put("startConnectedShapeId", connector109.startConnectedShapeId());
        connector109Evidence.put("endConnectedShapeId", connector109.endConnectedShapeId());
        Map<String, Object> reportData = new java.util.LinkedHashMap<>();
        reportData.put("candidatePreflight", report);
        reportData.put("selectionPreflight", selection);
        reportData.put("relatedEquipmentI584", Map.of("status", i584Mapping.fields().get("equipmentName").status(),
                "reason", i584Mapping.fields().get("equipmentName").reason(),
                "availableFields", i584Mapping.availableFields()));
        reportData.put("connector109", connector109Evidence);
        byte[] reportBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(reportData);
        Files.write(reportPath, reportBytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot07.p01.real", matches = "true")
    void currentP01UploadRemainsReviewOnlyAndUsesProcessIndependentPreflight() throws Exception {
        String p01SourceSha = "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
        String p01WorkingSha = "a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb";
        Path sourcePath = Path.of("data/reference/P-01 제지2호기공정 ★변경허가 REV3.1.xlsx");
        Path workingPath = Path.of(System.getProperty("pilot07.p01.working-copy",
                "/home/user/uploaded_files/P-01 제지2호기공정(수정).xlsx"));
        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        byte[] workingBytes = Files.readAllBytes(workingPath);
        assertThat(sha256(sourceBytes)).isEqualTo(p01SourceSha);
        assertThat(sha256(workingBytes)).isEqualTo(p01WorkingSha);

        ObjectMapper mapper = new ObjectMapper();
        var candidate = mapper.readValue(getClass().getResourceAsStream(
                "/profile-review/P01_WORKING_COPY_REVIEW.json"), ProfileCandidatePreflight.CandidateSpec.class);
        var profile = new EquipmentBoxProfileRepository(mapper)
                .findFor("P-01", "P-01-01(TOP)", p01SourceSha).orElseThrow();
        assertThat(profile.status()).isEqualTo("REVIEW_REQUIRED");
        var preflight = new ProfileCandidatePreflight(new OoxmlPackageInspector(), new EquipmentBoxGeometryLocator());
        var report = preflight.inspect(sourceBytes, workingBytes, new ProfileCandidatePreflight.Request(
                "P-01", "P-01-01(TOP)", "I-PP11001", p01SourceSha), candidate, profile);

        assertThat(report.actualBoxRange()).isEqualTo("J4:AA20");
        assertThat(report.sourceShaIdentityMatch()).isTrue();
        assertThat(report.workingCopyShaMatch()).isTrue();
        assertThat(report.candidateStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.modifyAuthorized()).isFalse();
        assertThat(report.fields()).hasSize(6);
        assertThat(report.fields().stream().filter(field -> "equipmentName".equals(field.field()))
                .findFirst().orElseThrow().cellAddress()).isEqualTo("J7");
        assertThat(report.findings().stream().map(ProfileCandidatePreflight.Finding::code)
                .anyMatch(code -> "DRAWINGML_FIELD_OVERLAP".equals(code)
                        || "DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED".equals(code))).isTrue();
        assertThat(sha256(Files.readAllBytes(sourcePath))).isEqualTo(p01SourceSha);
        assertThat(sha256(Files.readAllBytes(workingPath))).isEqualTo(p01WorkingSha);

        String validationOutputDir = System.getProperty("pilot07.validation-output-dir");
        if (validationOutputDir == null || validationOutputDir.isBlank()) {
            throw new IllegalStateException("Set pilot07.validation-output-dir to a new, isolated run directory.");
        }
        Path output = Path.of(validationOutputDir, "CROSS_PROCESS_VALIDATION.json");
        Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
