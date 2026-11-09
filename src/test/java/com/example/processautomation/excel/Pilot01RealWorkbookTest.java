package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit opt-in pilot against the protected official-workbook copy; no DB or runtime service is used. */
@EnabledIfSystemProperty(named = "pilot01.run", matches = "true")
class Pilot01RealWorkbookTest {
    private static final String PROCESS = "P-02";
    private static final String SHEET = "P-02-01(TOP)";
    private static final String EQUIPMENT_ID = "I-PP21005";
    private static final String SOURCE_PREFIX = "P-02 ";
    private static final String OFFICIAL_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String ORIGINAL_NAME = "131 MULTI SORTER#1";
    private static final String TEST_NAME = "SYNTHETIC_TEST_ONLY 131 MULTI SORTER#1";
    private static final Path PILOT_DIR = Path.of("validation-results", "pilot-01-20261104");
    private static final Path FINAL_RUN_DIR = PILOT_DIR.resolve("final-run-20261104-r2");

    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final DiagramBoxChangeEngine changeEngine = new DiagramBoxChangeEngine(
            new OoxmlPartEditor(), inspector, new EquipmentBoxGeometryLocator());

    @Test
    void createsAndVerifiesTestOnlyModifyFromOfficialCopyAndRecordsBlockedOperations() throws Exception {
        Path referenceDirectory = Path.of("data", "reference");
        Path sourcePath;
        try (var files = Files.list(referenceDirectory)) {
            sourcePath = files.filter(path -> path.getFileName().toString().startsWith(SOURCE_PREFIX)
                    && path.getFileName().toString().endsWith(".xlsx"))
                    .findFirst().orElseThrow();
        }
        Path beforePath = PILOT_DIR.resolve("before-official-copy.xlsx");
        Path afterPath = PILOT_DIR.resolve("after-modify-test-only.xlsx");
        Path resultPath = FINAL_RUN_DIR.resolve("validation-results.json");
        assertThat(Files.isRegularFile(sourcePath)).isTrue();
        assertThat(Files.isRegularFile(beforePath)).isTrue();
        // Existing result files are verified byte-for-byte by JSON content below and never overwritten.

        byte[] beforeBytes = Files.readAllBytes(beforePath);
        assertThat(sha256(Files.readAllBytes(sourcePath))).isEqualTo(OFFICIAL_SHA);
        assertThat(sha256(beforeBytes)).isEqualTo(OFFICIAL_SHA);

        List<EquipmentBoxGeometryLocator.Box> geometry;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(beforeBytes))) {
            var sheet = workbook.getSheet(SHEET);
            geometry = new EquipmentBoxGeometryLocator().locateByManagementNo(sheet, EQUIPMENT_ID);
            assertThat(sheet.getRow(88).getCell(8).getStringCellValue()).isEqualTo(ORIGINAL_NAME);
            assertThat((int) sheet.getRow(88).getCell(8).getCellStyle().getIndex()).isEqualTo(262);
            assertThat(sheet.getMergedRegions().stream().anyMatch(range -> range.isInRange(88, 8))).isFalse();
        }
        assertThat(geometry).hasSize(1);
        assertThat(geometry.get(0).topLeftCell()).isEqualTo("I86");
        assertThat(geometry.get(0).bottomRightCell()).isEqualTo("Z93");

        var result = changeEngine.modifyBoxFieldsTestOnly(beforeBytes, SHEET, EQUIPMENT_ID,
                List.of(new DiagramBoxChangeEngine.FieldChange("I89", ORIGINAL_NAME, TEST_NAME)));
        assertThat(result.operation()).isEqualTo("MODIFY");
        assertThat(result.scenarioStatus()).isEqualTo("TEST_ONLY");
        assertThat(result.structuralEditStatus()).isEqualTo("STRUCTURE_VERIFIED_TEXT_FIELDS_ONLY");
        assertThat(result.connectionEvidenceStatus()).isEqualTo(
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        assertThat(result.affectedCells()).containsExactly("I89");
        assertThat(sha256(result.workbookBytes())).isEqualTo(result.outputSha256());

        verifyPackageDelta(beforeBytes, result.workbookBytes(), "xl/worksheets/sheet1.xml");
        verifyTargetSheetStructureAndValues(beforeBytes, result.workbookBytes(), SHEET, "I89", ORIGINAL_NAME, TEST_NAME);
        String drawingPart = "xl/drawings/drawing1.xml";
        Map<String, byte[]> packagePartsBefore = packageParts(beforeBytes);
        Map<String, byte[]> packagePartsAfter = packageParts(result.workbookBytes());
        assertThat(packagePartsBefore).containsKey(drawingPart);
        String drawingXml = new String(packagePartsBefore.get(drawingPart), java.nio.charset.StandardCharsets.UTF_8);
        int connectorCount = countXmlElements(drawingXml, "cxnSp");
        int shapeCount = countXmlElements(drawingXml, "<xdr:sp");
        assertThat(connectorCount).isEqualTo(266);
        assertThat(shapeCount).isEqualTo(19);
        assertThat(packagePartsAfter.get(drawingPart)).containsExactly(packagePartsBefore.get(drawingPart));

        if (Files.exists(afterPath)) {
            assertThat(Files.readAllBytes(afterPath)).containsExactly(result.workbookBytes());
        } else {
            writeNew(afterPath, result.workbookBytes());
        }

        var profile = new EquipmentBoxProfileRepository(new ObjectMapper())
                .findFor(PROCESS, SHEET, OFFICIAL_SHA).orElseThrow();
        String addBlocker;
        try {
            new DiagramEquipmentInsertionEngine(new OoxmlPartEditor(), inspector,
                    new EquipmentBoxGeometryLocator()).analyze(beforeBytes, SHEET,
                    "I-PP21003", "I-PP21005", profile, PROCESS, OFFICIAL_SHA);
            throw new AssertionError("Profile ADD must not use a different verified working-copy SHA");
        } catch (InvalidWorkbookException expected) {
            addBlocker = expected.getMessage();
            assertThat(addBlocker).contains("PROFILE_WORKING_SHA_MISMATCH");
        }

        var deletion = changeEngine.assessDeleteImpact(beforeBytes, SHEET, EQUIPMENT_ID);
        assertThat(deletion.automaticTestOnlyDeleteAllowed()).isFalse();
        assertThat(deletion.status()).isEqualTo("REVIEW_REQUIRED");

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("pilot", "PILOT-01");
        validation.put("classification", "TEST_ONLY; NOT AN OFFICIAL PROCESS CHANGE");
        validation.put("processCode", PROCESS);
        validation.put("sheetName", SHEET);
        validation.put("equipmentId", EQUIPMENT_ID);
        validation.put("boxRange", geometry.get(0).topLeftCell() + ":" + geometry.get(0).bottomRightCell());
        validation.put("officialSource", sourcePath.toString());
        validation.put("officialSourceSha256", OFFICIAL_SHA);
        validation.put("beforeCopySha256", sha256(beforeBytes));
        validation.put("modify", Map.ofEntries(
                Map.entry("status", "GENERATED_AND_REOPENED_TEST_ONLY"),
                Map.entry("field", "equipmentName"), Map.entry("cell", "I89"),
                Map.entry("before", ORIGINAL_NAME), Map.entry("after", TEST_NAME),
                Map.entry("output", afterPath.toString()), Map.entry("outputSha256", result.outputSha256()),
                Map.entry("drawingmlPreserved", true),
                Map.entry("drawingObjectCountBefore", connectorCount + shapeCount),
                Map.entry("drawingObjectCountAfter", connectorCount + shapeCount),
                Map.entry("connectorCountBefore", connectorCount), Map.entry("connectorCountAfter", connectorCount),
                Map.entry("shapeCountBefore", shapeCount), Map.entry("shapeCountAfter", shapeCount),
                Map.entry("connectorEvidence", "All non-worksheet OOXML package parts byte-identical"),
                Map.entry("connectionEvidenceStatus", result.connectionEvidenceStatus())));
        validation.put("add", Map.of("status", "BLOCKED_NO_OUTPUT", "reason", addBlocker,
                "requiredWorkingCopySha256", profile.verifiedWorkingSha256(),
                "availableWorkingCopySha256", sha256(beforeBytes)));
        validation.put("delete", Map.of("status", "BLOCKED_NO_OUTPUT", "reason", deletion.reason(),
                "assessmentStatus", deletion.status(), "drawingImpacts", deletion.drawingImpacts().size(),
                "nearbyTextCount", deletion.nearbyText().size()));
        validation.put("verifiedFlowMappings", 0);
        validation.put("materialBalanceReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("pipeChangeStatus", "VISUAL_REVIEW_REQUIRED");
        validation.put("generatedFiles", List.of(beforePath.toString(), afterPath.toString()));
        Files.createDirectories(FINAL_RUN_DIR);
        ObjectMapper mapper = new ObjectMapper();
        byte[] validationJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(validation);
        if (Files.exists(resultPath)) {
            assertThat(mapper.readTree(Files.readAllBytes(resultPath))).isEqualTo(mapper.readTree(validationJson));
        } else {
            Files.write(resultPath, validationJson, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        }
        assertThat(sha256(Files.readAllBytes(sourcePath))).isEqualTo(OFFICIAL_SHA);
        assertThat(Files.exists(PILOT_DIR.resolve("after-add-test-only.xlsx"))).isFalse();
        assertThat(Files.exists(PILOT_DIR.resolve("after-delete-test-only.xlsx"))).isFalse();
    }

    private void verifyTargetSheetStructureAndValues(byte[] beforeBytes, byte[] afterBytes, String sheetName,
                                                     String changedCell, String oldText, String newText) throws Exception {
        String worksheetPart = "xl/worksheets/sheet1.xml";
        String beforeXml = new String(new OoxmlPartEditor().readPart(beforeBytes, worksheetPart),
                java.nio.charset.StandardCharsets.UTF_8);
        String afterXml = new String(new OoxmlPartEditor().readPart(afterBytes, worksheetPart),
                java.nio.charset.StandardCharsets.UTF_8);
        String cellPattern = "<c(?=[\\s>])(?=[^>]*\\br=\\\"" + changedCell + "\\\")[^>]*>.*?</c>";
        assertThat(afterXml.replaceFirst(cellPattern, "<TARGET_CELL/>"))
                .as("worksheet OOXML must differ only in the selected cell")
                .isEqualTo(beforeXml.replaceFirst(cellPattern, "<TARGET_CELL/>"));
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(beforeBytes));
             var after = WorkbookFactory.create(new ByteArrayInputStream(afterBytes))) {
            var oldSheet = before.getSheet(sheetName);
            var newSheet = after.getSheet(sheetName);
            assertThat(after.getNumberOfSheets()).isEqualTo(before.getNumberOfSheets());
            var oldCell = oldSheet.getRow(88).getCell(8);
            var newCell = newSheet.getRow(88).getCell(8);
            assertThat(oldCell.getStringCellValue()).isEqualTo(oldText);
            assertThat(newCell.getStringCellValue()).isEqualTo(newText);
            assertThat(newCell.getCellStyle().getIndex()).isEqualTo(oldCell.getCellStyle().getIndex());
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(newSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList())
                    .containsExactlyElementsOf(oldSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList());
            int sheetIndex = before.getSheetIndex(sheetName);
            assertThat(after.getPrintArea(sheetIndex)).isEqualTo(before.getPrintArea(sheetIndex));
            assertThat(newSheet.getPrintSetup().getLandscape()).isEqualTo(oldSheet.getPrintSetup().getLandscape());
            assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            assertThat(newSheet.getPrintSetup().getFitWidth()).isEqualTo(oldSheet.getPrintSetup().getFitWidth());
            assertThat(newSheet.getPrintSetup().getFitHeight()).isEqualTo(oldSheet.getPrintSetup().getFitHeight());
            assertThat(newSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList())
                    .contains("U582:Z588");
            for (int row = 85; row <= 92; row++)
                assertThat(newSheet.getRow(row).getHeight()).isEqualTo(oldSheet.getRow(row).getHeight());
            for (int column = 0; column < 64; column++)
                assertThat(newSheet.getColumnWidth(column)).isEqualTo(oldSheet.getColumnWidth(column));
        }
    }

    private int countXmlElements(String xml, String localNameOrStartTag) {
        String expression = localNameOrStartTag.startsWith("<")
                ? java.util.regex.Pattern.quote(localNameOrStartTag) + "(?:\\s|>)"
                : "<xdr:" + java.util.regex.Pattern.quote(localNameOrStartTag) + "(?:\\s|>)";
        var matcher = java.util.regex.Pattern.compile(expression).matcher(xml);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private void verifyPackageDelta(byte[] before, byte[] after, String onlyChangedPart) throws Exception {
        Map<String, byte[]> oldParts = packageParts(before);
        Map<String, byte[]> newParts = packageParts(after);
        assertThat(newParts.keySet()).containsExactlyInAnyOrderElementsOf(oldParts.keySet());
        List<String> changedParts = new ArrayList<>();
        for (String part : oldParts.keySet()) {
            if (!java.util.Arrays.equals(oldParts.get(part), newParts.get(part))) changedParts.add(part);
        }
        assertThat(changedParts).containsExactly(onlyChangedPart);
    }

    private Map<String, byte[]> packageParts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                assertThat(parts).doesNotContainKey(entry.getName());
                parts.put(entry.getName(), input.readAllBytes());
            }
        }
        return parts;
    }

    private void writeNew(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
