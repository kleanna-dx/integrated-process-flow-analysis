package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.processautomation.excel.DiagramEquipmentInsertionEngine;
import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GeneralDiagramEditProfileStatusTest {
    private static final String P02_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String PW02_SHA = "2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a";
    private static final String P01_SHA = "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
    private static final String PW01_SHA = "784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0";

    @Test
    void reportsCatalogStatusesExplicitlyAndBlocksGenericPreviewForNonVerifiedLocations() {
        var cases = List.of(
                new StatusCase("P-02", "P-02-05(BROKE)", P02_SHA, "I-PP22061", "I-PP25008", "REVIEW_REQUIRED", "STRUCTURE_ANALYZED"),
                new StatusCase("P-02", "P-02-99(UNPROFILED)", P02_SHA, "SRC-02", "DST-02", "UNSUPPORTED", null),
                new StatusCase("PW-02", "PW-02-01", PW02_SHA, "I-PW21007", "I-PW21008", "REVIEW_REQUIRED", "STRUCTURE_ANALYZED"),
                new StatusCase("P-01", "P-01-01(TOP)", P01_SHA, "I-PP11001", "I-PP11002", "REVIEW_REQUIRED", "STRUCTURE_ANALYZED"),
                new StatusCase("PW-01", "PW-01-05", PW01_SHA, "C-PW15009", "C-PW15010", "LEGACY_ONLY", "LEGACY_FIXED_PATH_ONLY"));

        for (StatusCase scenario : cases) {
            var workingCopies = mock(NumericWorkingCopyService.class);
            var sources = mock(DiagramEditWorkingCopyRepository.class);
            var analyzer = mock(ProcessDiagramStructureAnalyzer.class);
            var engine = mock(DiagramEquipmentInsertionEngine.class);
            var copy = new NumericWorkingCopyService.WorkingCopy(7, 17, scenario.process(), "official.xlsx",
                    "working.xlsx", scenario.sourceSha(), "working-sha", "NUMERIC-WORKING-UPLOAD-V1",
                    "CREATED_WITH_REVIEW", new byte[]{1, 2, 3});
            when(workingCopies.getWorkingCopy(7)).thenReturn(copy);
            when(sources.findLockedSource(17)).thenReturn(new DiagramEditWorkingCopyRepository.SourceIdentity(
                    17, scenario.process(), "PROCESS", scenario.sourceSha(), "V1.0", true));
            var candidate = new ProcessDiagramStructureAnalyzer.LocationCandidate("fixture-location", scenario.process(),
                    scenario.sheet(), scenario.sourceEquipment(), scenario.targetEquipment(), "A11:F18", 2, 8,
                    "xl/drawings/drawing1.xml", 0, 0, 0, List.of(), false, "REVIEW_REQUIRED", List.of(),
                    "REVIEW_REQUIRED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "fixture candidate");
            var report = new ProcessDiagramStructureAnalyzer.WorkbookReport(scenario.process(), "working.xlsx",
                    "working-sha", 3, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0,
                    List.of(), List.of(), List.of(candidate), "synthetic status fixture");
            when(analyzer.analyzeSheet(eq(scenario.process()), anyString(), eq("working-sha"),
                    any(byte[].class), eq(scenario.sheet()))).thenReturn(report);

            var service = new GeneralDiagramEditService(workingCopies, sources, analyzer, engine,
                    new EquipmentBoxProfileRepository(new ObjectMapper()));
            var location = service.assessLocations(7, scenario.sheet()).get(0);
            assertThat(location.editabilityStatus()).isEqualTo(scenario.expectedStatus());
            assertThat(location.profileStatus()).isEqualTo(scenario.expectedStatus());
            assertThat(location.profileEvidenceLevel()).isEqualTo(scenario.expectedEvidenceLevel());
            if ("STRUCTURE_ANALYZED".equals(scenario.expectedEvidenceLevel())) {
                assertThat(location.profileFields()).contains("managementNo", "equipmentTypeLabel", "equipmentName",
                        "businessItemNo", "capacity", "quantity");
                assertThat(location.availableFields()).isEmpty();
            }
            if ("UNSUPPORTED".equals(scenario.expectedStatus())) assertThat(location.profileId()).isNull();
            else assertThat(location.profileId()).isNotBlank();

            var input = new GeneralDiagramEditService.EditInput(7, location.locationId(), scenario.sheet(),
                    "T-TEST-001", "TEST_ONLY equipment", "TEST-ITEM-001", "1", "m3/h", "1ea", "TEST_ONLY", null);
            assertThatThrownBy(() -> service.preview(input))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value())
                            .isEqualTo(409));
            verifyNoInteractions(engine);
        }

        try {
            String html = new org.springframework.core.io.ClassPathResource("static/index.html")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).contains("VERIFIED:'검증됨'")
                    .contains("REVIEW_REQUIRED:'검토 필요'")
                    .contains("UNSUPPORTED:'미지원'")
                    .contains("LEGACY_ONLY:'레거시 전용'")
                    .contains("isLegacyPw01Location(item)")
                    .contains("selectedLocation.editabilityStatus!=='VERIFIED'&&!legacyPw01")
                    .contains("profileEvidenceLevel")
                    .contains("workingCopyShaMatch");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Profile UI resource must be available on the classpath", exception);
        }
    }

    private record StatusCase(String process, String sheet, String sourceSha,
                              String sourceEquipment, String targetEquipment, String expectedStatus,
                              String expectedEvidenceLevel) { }
}
