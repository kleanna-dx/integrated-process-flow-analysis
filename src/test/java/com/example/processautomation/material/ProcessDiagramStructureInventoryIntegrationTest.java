package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.ProcessDiagramInventoryWorkbookExporter;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ProcessDiagramStructureInventoryIntegrationTest {
    private static final Path OUTPUT = Path.of("validation-results", "2026-10-29-process-diagram-inventory");
    @Autowired private ProcessDiagramInventoryService service;
    @Autowired private ProcessDiagramInventoryWorkbookExporter exporter;
    @Autowired private ObjectMapper mapper;

    @Test
    void inventoriesEveryLockedOfficialProcessWorkbookAndWritesReviewArtifacts() throws Exception {
        var reports = service.inspectLockedProcessDocuments();
        assertThat(reports).hasSize(15);
        assertThat(reports).extracting(ProcessDiagramStructureAnalyzer.WorkbookReport::processCode)
                .contains("P-01", "P-08", "PU-01", "PU-03", "PW-01", "PW-02", "PW-02-05", "PW-03");
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.sha256()).hasSize(64);
            assertThat(report.sheetCount()).isGreaterThan(0);
            assertThat(report.sheets()).hasSize(report.sheetCount());
            assertThat(report.interpretationPolicy()).contains("no editability");
            assertThat(report.locationCandidates()).allSatisfy(candidate -> {
                assertThat(candidate.automaticEditAllowed()).isFalse();
                assertThat(candidate.status()).isEqualTo("REVIEW_REQUIRED");
                assertThat(candidate.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
                assertThat(candidate.editabilityStatus()).isIn("VERIFIED", "REVIEW_REQUIRED", "UNSUPPORTED");
            });
        });

        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(reports);
        byte[] workbookBytes = exporter.export(reports);
        Files.createDirectories(OUTPUT);
        Files.write(OUTPUT.resolve("official-process-diagram-inventory_TEST_ONLY.json"), json);
        Files.write(OUTPUT.resolve("official-process-diagram-inventory_TEST_ONLY.xlsx"), workbookBytes);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            assertThat(workbook.getSheet("공정도 요약").getLastRowNum()).isEqualTo(15);
            assertThat(workbook.getSheet("시트·사용영역")).isNotNull();
            assertThat(workbook.getSheet("설비박스 후보")).isNotNull();
            assertThat(workbook.getSheet("위치후보")).isNotNull();
            assertThat(workbook.getSheet("DrawingML 객체")).isNotNull();
            assertThat(workbook.getSheet("행열·서식 프로필")).isNotNull();
            assertThat(workbook.getSheet("열너비·숨김")).isNotNull();
        }
        assertThat(reports.stream().mapToInt(ProcessDiagramStructureAnalyzer.WorkbookReport::sheetCount).sum()).isGreaterThan(15);
        assertThat(reports.stream().flatMap(report -> report.locationCandidates().stream())
                .filter(candidate -> "P-02".equals(candidate.processCode())
                        && "P-02-05(BROKE)".equals(candidate.sheetName())
                        && "I-PP22061".equals(candidate.precedingEquipmentCandidate())
                        && "I-PP25008".equals(candidate.followingEquipmentCandidate()))
                .map(ProcessDiagramStructureAnalyzer.LocationCandidate::editabilityStatus))
                .containsExactly("VERIFIED");
    }
}
