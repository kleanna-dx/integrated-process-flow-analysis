package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.Pw0105EquipmentInsertionEngine;
import com.example.processautomation.excel.DiagramEquipmentInsertionEngine;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.example.processautomation.model.MasterEquipment;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class Pw0105InsertionCandidateCompatibilityIntegrationTest {
    private static final Path OUTPUT = Path.of("validation-results", "2026-10-29-process-diagram-inventory",
            "pw0105-insertion-candidate-compatibility_TEST_ONLY.json");
    @Autowired private ProcessDiagramInventoryService inventory;
    @Autowired private ProcessDiagramStructureAnalyzer analyzer;
    @Autowired private NumericWorkingCopyService workingCopies;
    @Autowired private Pw0105EquipmentInsertionEngine insertionEngine;
    @Autowired private DiagramEquipmentInsertionEngine generalInsertionEngine;
    @Autowired private ObjectMapper mapper;

    @Test
    void probesAllSameColumnPw0105CandidatePairsWithoutEditingAnyWorkbook() throws Exception {
        var report = inventory.inspectLockedProcessDocuments().stream()
                .filter(item -> "PW-01".equals(item.processCode())).findFirst().orElseThrow();
        List<ProcessDiagramStructureAnalyzer.LocationCandidate> candidates = report.locationCandidates().stream()
                .filter(item -> "PW-01-05".equals(item.sheetName())).toList();
        assertThat(candidates).isNotEmpty();
        var copy = workingCopies.getWorkingCopy(36);
        List<Compatibility> results = new ArrayList<>();
        GeneratedOutput generatedOutput = null;
        String editFailure = null;
        var inspector = new OoxmlPackageInspector();
        String sourceHash = sha256(copy.bytes());
        for (var candidate : candidates) {
            var source = report.equipmentCandidates().stream().filter(item -> "PW-01-05".equals(item.sheetName())
                    && candidate.precedingEquipmentCandidate().equals(item.equipmentIdCandidate())
                    && item.topLeftCell() != null).findFirst().orElse(null);
            var target = report.equipmentCandidates().stream().filter(item -> "PW-01-05".equals(item.sheetName())
                    && candidate.followingEquipmentCandidate().equals(item.equipmentIdCandidate())
                    && item.topLeftCell() != null).findFirst().orElse(null);
            if (source == null || target == null) {
                results.add(new Compatibility(candidate.locationId(), candidate.precedingEquipmentCandidate(),
                        candidate.followingEquipmentCandidate(), "BLOCKED", null, "UNIQUE_BOX_CANDIDATE_NOT_FOUND"));
                continue;
            }
            try {
                var plan = insertionEngine.analyze(copy.bytes(), new Pw0105EquipmentInsertionEngine.TestEditSpec(
                        candidate.sheetName(), source.equipmentIdCandidate(), source.facilityNameCandidate(),
                        target.equipmentIdCandidate(), target.facilityNameCandidate(), "합성 TEST_ONLY 후보 유형"));
                boolean connector = plan.existingConnector() != null && plan.existingConnector().shapeId() != null;
                results.add(new Compatibility(candidate.locationId(), source.equipmentIdCandidate(),
                        target.equipmentIdCandidate(), connector ? "CONNECTOR_MATCH_REVIEW_REQUIRED" : "BLOCKED",
                        connector ? plan.existingConnector().shapeId() : null,
                        connector ? "Existing visual connector matches anchors; this is not evidence of physical material flow"
                                : "NO_EXACT_EXISTING_VISUAL_CONNECTOR"));
                if (connector && generatedOutput == null
                        && !("C-PW15009".equals(source.equipmentIdCandidate())
                        && "C-PW15010".equals(target.equipmentIdCandidate()))) {
                    try {
                        var equipment = syntheticEquipment();
                        byte[] outputBytes = insertionEngine.insertTestOnly(copy.bytes(), equipment,
                                new Pw0105EquipmentInsertionEngine.TestEditSpec(candidate.sheetName(),
                                        source.equipmentIdCandidate(), source.facilityNameCandidate(),
                                        target.equipmentIdCandidate(), target.facilityNameCandidate(), "저장시설"))
                                .workbookBytes();
                        var beforeDrawing = inspector.inspect(copy.bytes()).sheets().stream()
                                .filter(sheet -> candidate.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
                        var afterDrawing = inspector.inspect(outputBytes).sheets().stream()
                                .filter(sheet -> candidate.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
                        try (var before = WorkbookFactory.create(new ByteArrayInputStream(copy.bytes()));
                             var after = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
                            var oldSheet = before.getSheet(candidate.sheetName());
                            var newSheet = after.getSheet(candidate.sheetName());
                            assertThat(newSheet).isNotNull();
                            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
                            assertThat(after.getPrintArea(after.getSheetIndex(candidate.sheetName()))).isNotBlank();
                            assertThat(newSheet.getRow(20).getCell(34).getStringCellValue()).isEqualTo("C-PW15999");
                        }
                        long beforeConnectors = beforeDrawing.drawingObjects().stream()
                                .filter(object -> "CONNECTOR".equals(object.objectType())).count();
                        long afterConnectors = afterDrawing.drawingObjects().stream()
                                .filter(object -> "CONNECTOR".equals(object.objectType())).count();
                        long beforeShapes = beforeDrawing.drawingObjects().stream()
                                .filter(object -> "SHAPE".equals(object.objectType())).count();
                        long afterShapes = afterDrawing.drawingObjects().stream()
                                .filter(object -> "SHAPE".equals(object.objectType())).count();
                        assertThat(afterConnectors).isEqualTo(beforeConnectors + 1);
                        assertThat(afterShapes).isEqualTo(beforeShapes);
                        assertThat(afterDrawing.drawingObjects()).noneMatch(object -> object.objectName() != null
                                && (object.objectName().contains("TEST_ONLY / REVIEW_REQUIRED")
                                || object.textContent().contains("TEST_ONLY / REVIEW_REQUIRED")));
                        String filename = "PW-01-05_TEST_ONLY_ADD_" + source.equipmentIdCandidate() + "_to_"
                                + target.equipmentIdCandidate() + ".xlsx";
                        boolean sourceUnchanged = sourceHash.equals(sha256(workingCopies.getWorkingCopy(36).bytes()));
                        assertThat(sourceUnchanged).isTrue();
                        Files.write(OUTPUT.resolveSibling(filename), outputBytes);
                        generatedOutput = new GeneratedOutput("GENERATED", filename, sha256(outputBytes),
                                source.equipmentIdCandidate(), target.equipmentIdCandidate(), outputBytes.length,
                                sourceUnchanged, afterDrawing.drawingObjects().size(),
                                (int) afterConnectors, (int) afterShapes,
                                "POI reopen, merge count, print area, expected new equipment ID and connector delta passed");
                    } catch (RuntimeException | java.io.IOException exception) {
                        editFailure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                    }
                }
            } catch (RuntimeException exception) {
                results.add(new Compatibility(candidate.locationId(), source.equipmentIdCandidate(),
                        target.equipmentIdCandidate(), "BLOCKED", null,
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
        }
        if (generatedOutput == null) {
            generatedOutput = new GeneratedOutput("NOT_GENERATED", "", "", "", "", 0,
                    sourceHash.equals(sha256(workingCopies.getWorkingCopy(36).bytes())), 0, 0, 0,
                    editFailure == null ? "No additional connector-matched location was eligible for a generation attempt"
                            : editFailure);
        }
        var output = new CandidateAssessment("PW-01", copy.id(), copy.workingSha256(), candidates.size(),
                results.stream().filter(item -> item.status().equals("CONNECTOR_MATCH_REVIEW_REQUIRED")).count(),
                results.stream().filter(item -> item.status().equals("BLOCKED")).count(), false,
                "Only TEST_ONLY generation was attempted from a non-official working copy. Visual edges remain non-evidence; no official source was edited.",
                List.copyOf(results), generatedOutput);
        Files.createDirectories(OUTPUT.getParent());
        Files.write(OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(output));
        assertThat(output.automaticEditAllowed()).isFalse();
        assertThat(results).allSatisfy(item -> assertThat(item.status()).isIn("CONNECTOR_MATCH_REVIEW_REQUIRED", "BLOCKED"));
    }

    @Test
    void attemptsGenericInsertionOnPw03WorkingCopyOutsidePw0105FixedSlot() throws Exception {
        var copy = workingCopies.getWorkingCopy(15);
        assertThat(copy.processCode()).isEqualTo("PW-03");
        var report = analyzer.analyze(copy.processCode(), copy.filename(), copy.workingSha256(), copy.bytes());
        var location = report.locationCandidates().stream()
                .filter(item -> "PW-03-01".equals(item.sheetName())
                        && "C-PW31002".equals(item.precedingEquipmentCandidate())
                        && "C-PW31003".equals(item.followingEquipmentCandidate()))
                .findFirst().orElseThrow();
        var source = report.equipmentCandidates().stream().filter(item -> "PW-03-01".equals(item.sheetName())
                && location.precedingEquipmentCandidate().equals(item.equipmentIdCandidate())
                && item.topLeftCell() != null).findFirst().orElseThrow();
        var target = report.equipmentCandidates().stream().filter(item -> "PW-03-01".equals(item.sheetName())
                && location.followingEquipmentCandidate().equals(item.equipmentIdCandidate())
                && item.topLeftCell() != null).findFirst().orElseThrow();
        String sourceHash = sha256(copy.bytes());
        String status = "NOT_GENERATED";
        String reason = "";
        String outputHash = "";
        String filename = "";
        int outputBytes = 0;
        try {
            var spec = new Pw0105EquipmentInsertionEngine.TestEditSpec(location.sheetName(),
                    source.equipmentIdCandidate(), source.facilityNameCandidate(), target.equipmentIdCandidate(),
                    target.facilityNameCandidate(), "TEST_ONLY 합성 설비");
            byte[] result = insertionEngine.insertTestOnly(copy.bytes(), syntheticEquipment(), spec).workbookBytes();
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(result));
                 var original = WorkbookFactory.create(new ByteArrayInputStream(copy.bytes()))) {
                assertThat(workbook.getSheet(location.sheetName())).isNotNull();
                assertThat(workbook.getSheet(location.sheetName()).getNumMergedRegions()).isEqualTo(
                        original.getSheet(location.sheetName()).getNumMergedRegions());
            }
            filename = "PW-03_TEST_ONLY_ADD_C-PW31099.xlsx";
            Files.createDirectories(OUTPUT.getParent());
            Files.write(OUTPUT.getParent().resolve(filename), result);
            outputHash = sha256(result);
            outputBytes = result.length;
            status = "GENERATED";
        } catch (RuntimeException | java.io.IOException exception) {
            reason = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        assertThat(sha256(workingCopies.getWorkingCopy(15).bytes())).isEqualTo(sourceHash);
        var result = new GeneralProbe("PW-03", copy.id(), sourceHash, location.locationId(),
                location.candidateCellRange(), location.overlappingMergedRanges(), location.drawingObjectsCrossingBand(),
                status, filename, outputHash, outputBytes, reason,
                "Input is an independently stored non-official working copy; production candidate status remains REVIEW_REQUIRED.");
        Files.createDirectories(OUTPUT.getParent());
        Files.write(OUTPUT.getParent().resolve("general-insertion-probe_TEST_ONLY.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    @Test
    void generatesTemplateDrivenBoxOnlyTestOnlyWorkbookOnP02WorkingCopy() throws Exception {
        var copy = workingCopies.getWorkingCopy(3);
        assertThat(copy.processCode()).isEqualTo("P-02");
        String sourceHash = sha256(copy.bytes());
        var report = analyzer.analyze(copy.processCode(), copy.filename(), copy.workingSha256(), copy.bytes());
        var location = report.locationCandidates().stream()
                .filter(candidate -> "P-02-05(BROKE)".equals(candidate.sheetName())
                        && "I-PP22061".equals(candidate.precedingEquipmentCandidate())
                        && "I-PP25008".equals(candidate.followingEquipmentCandidate()))
                .findFirst().orElseThrow();
        assertThat(location.overlappingMergedRanges()).isEmpty();
        assertThat(location.drawingObjectsCrossingBand()).isZero();
        var plan = generalInsertionEngine.analyze(copy.bytes(), location.sheetName(),
                location.precedingEquipmentCandidate(), location.followingEquipmentCandidate());
        assertThat(plan.template().recognized()).isTrue();
        assertThat(plan.template().fieldOffsets()).containsEntry("managementNo", 0)
                .containsEntry("equipmentTypeLabel", 2).containsEntry("equipmentName", 3)
                .containsEntry("businessItemNo", 4).containsEntry("capacity", 5).containsEntry("quantity", 6);
        var equipment = new MasterEquipment(0, 0, "T-P02-TEST-001", "TEST-P02-ITEM-001", "P-02", "P-02",
                "TEST_ONLY", "TEST_ONLY synthetic pump", "TEST_ONLY synthetic pump", "TEST_ONLY equipment",
                "75", "m3/h", "1ea", "", "", "", "", "", "", "", "", "", "", "", "", Map.of());
        var edit = generalInsertionEngine.insertBoxOnlyTestOnly(copy.bytes(), equipment, location.sheetName(),
                location.precedingEquipmentCandidate(), location.followingEquipmentCandidate(), "TEST_ONLY equipment");
        byte[] output = edit.workbookBytes();
        String filename = "P-02-05_BROKE_TEST_ONLY_ADD_I-PP22061_to_I-PP25008.xlsx";
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(copy.bytes()));
             var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldSheet = before.getSheet(location.sheetName());
            var newSheet = after.getSheet(location.sheetName());
            assertThat(newSheet).isNotNull();
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            int row = edit.plan().firstCandidateRow() - 1;
            int col = edit.plan().firstColumn();
            assertThat(formatter.formatCellValue(newSheet.getRow(row).getCell(col))).isEqualTo("T-P02-TEST-001");
            assertThat(formatter.formatCellValue(newSheet.getRow(row + 2).getCell(col))).isEqualTo("TEST_ONLY equipment");
            assertThat(formatter.formatCellValue(newSheet.getRow(row + 3).getCell(col))).isEqualTo("TEST_ONLY synthetic pump");
            assertThat(formatter.formatCellValue(newSheet.getRow(row + 4).getCell(col))).isEqualTo("TEST-P02-ITEM-001");
            assertThat(formatter.formatCellValue(newSheet.getRow(row + 5).getCell(col))).isEqualTo("75m3/h");
            assertThat(formatter.formatCellValue(newSheet.getRow(row + 6).getCell(col))).isEqualTo("1ea");
            for (int offset = 0; offset < edit.plan().boxHeight(); offset++) {
                for (int column = edit.plan().firstColumn(); column <= edit.plan().lastColumn(); column++) {
                    assertThat(newSheet.getRow(row + offset).getCell(column).getCellStyle().getIndex())
                            .isEqualTo(oldSheet.getRow(edit.plan().source().firstRow() - 1 + offset)
                                    .getCell(column).getCellStyle().getIndex());
                }
            }
        }
        var oldDrawing = new OoxmlPackageInspector().inspect(copy.bytes()).sheets().stream()
                .filter(sheet -> location.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
        var newDrawing = new OoxmlPackageInspector().inspect(output).sheets().stream()
                .filter(sheet -> location.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
        assertThat(newDrawing.drawingObjects()).hasSameSizeAs(oldDrawing.drawingObjects());
        assertThat(new OoxmlPackageInspector().inspect(output).connectorCount())
                .isEqualTo(new OoxmlPackageInspector().inspect(copy.bytes()).connectorCount());
        int insertionIndex = edit.plan().firstCandidateRow() - 1;
        for (var original : oldDrawing.drawingObjects()) {
            var changed = newDrawing.drawingObjects().stream().filter(object -> original.shapeId().equals(object.shapeId()))
                    .findFirst().orElseThrow();
            assertThat(changed.startRow()).isEqualTo(original.startRow() >= insertionIndex
                    ? original.startRow() + edit.plan().insertedRows() : original.startRow());
            assertThat(changed.endRow()).isEqualTo(original.endRow() >= insertionIndex
                    ? original.endRow() + edit.plan().insertedRows() : original.endRow());
        }
        assertThat(sha256(workingCopies.getWorkingCopy(3).bytes())).isEqualTo(sourceHash);
        assertThat(edit.plan().connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        assertThat(edit.relationStatus()).isEqualTo("REVIEW_REQUIRED");
        Files.createDirectories(OUTPUT.getParent());
        Files.write(OUTPUT.getParent().resolve(filename), output);
        var audit = new GeneralProbe("P-02", copy.id(), sourceHash, location.locationId(),
                edit.plan().candidateCellRange(), location.overlappingMergedRanges(),
                location.drawingObjectsCrossingBand(), "GENERATED_BOX_ONLY", filename, sha256(output), output.length,
                "POI reopen, field values, template cell styles, merge/print setup, package allowlist, source SHA and existing DrawingML anchors verified; no new connector created.",
                "TEST_ONLY output derived from non-official P-02 working copy. Visual/physical flow remains NOT_VERIFIED.");
        Files.write(OUTPUT.getParent().resolve("p02-general-editor_TEST_ONLY.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(audit));
    }

    public record GeneralProbe(String processCode, long workingDocumentId, String sourceSha256,
                               String locationId, String candidateCellRange, List<String> overlappingMergedRanges,
                               int drawingObjectsCrossingBand, String status, String filename, String outputSha256,
                               int outputBytes, String reason, String safetyPolicy) { }

    public record Compatibility(String locationId, String sourceEquipmentCandidate, String targetEquipmentCandidate,
                               String status, String connectorShapeId, String reason) { }
    public record CandidateAssessment(String processCode, long workingDocumentId, String workingSha256,
                                      int candidatesTested, long visualConnectorMatches, long blockedCandidates,
                                      boolean automaticEditAllowed, String interpretationPolicy,
                                      List<Compatibility> candidates, GeneratedOutput generatedOutput) { }
    public record GeneratedOutput(String status, String filename, String sha256,
                                  String sourceEquipmentCandidate, String targetEquipmentCandidate,
                                  int byteSize, boolean sourceWorkingCopyUnchanged, int drawingObjectCount,
                                  int connectorCount, int shapeCount, String verification) { }

    private MasterEquipment syntheticEquipment() {
        return new MasterEquipment(0, 0, "C-PW15999", "TEST-ITEM-PW15999", "PW-01-05", "PW-01",
                "폐수처리", "TEST_ONLY 합성 설비", "TEST_ONLY 합성 설비", "설비", "125", "㎥/일", "1대",
                "", "", "", "", "", "", "", "", "", "", "", "", java.util.Map.of());
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
