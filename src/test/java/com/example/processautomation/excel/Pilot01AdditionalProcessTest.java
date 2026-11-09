package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in TEST_ONLY edit on a second official process with a distinct box geometry. */
@EnabledIfSystemProperty(named = "pilot01.additional.run", matches = "true")
class Pilot01AdditionalProcessTest {
    private static final String PROCESS = "P-01";
    private static final String SHEET = "P-01-01(TOP)";
    private static final String EQUIPMENT_ID = "I-PP11001";
    private static final String SOURCE_PREFIX = "P-01 ";
    private static final String SOURCE_SHA = "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
    private static final String OLD_NAME = "40㎥ PULPER (TOP, IV)";
    private static final String TEST_NAME = "SYNTHETIC_TEST_ONLY 40㎥ PULPER (TOP, IV)";
    private static final Path OUTPUT_DIR = Path.of("validation-results", "pilot-01-20261104",
            "final-run-20261104-r3-p01");

    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
            new OoxmlPartEditor(), inspector, new EquipmentBoxGeometryLocator());

    @Test
    void createsTestOnlyModifyOnDistinctP01BoxAndPreservesTheOfficialPackage() throws Exception {
        Path referenceDirectory = Path.of("data", "reference");
        Path source;
        try (var paths = Files.list(referenceDirectory)) {
            source = paths.filter(path -> path.getFileName().toString().startsWith(SOURCE_PREFIX)
                    && path.getFileName().toString().endsWith(".xlsx")).findFirst().orElseThrow();
        }
        assertThat(sha256(Files.readAllBytes(source))).isEqualTo(SOURCE_SHA);

        byte[] beforeBytes = Files.readAllBytes(source);
        List<EquipmentBoxGeometryLocator.Box> boxes;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(beforeBytes))) {
            var sheet = workbook.getSheet(SHEET);
            boxes = new EquipmentBoxGeometryLocator().locateByManagementNo(sheet, EQUIPMENT_ID);
            assertThat(sheet.getRow(6).getCell(9).getStringCellValue()).isEqualTo(OLD_NAME);
            assertThat((int) sheet.getRow(6).getCell(9).getCellStyle().getIndex()).isEqualTo(100);
            assertThat(sheet.getMergedRegions().stream().anyMatch(range -> range.isInRange(6, 9))).isFalse();
        }
        assertThat(boxes).hasSize(1);
        assertThat(boxes.get(0).topLeftCell()).isEqualTo("J4");
        assertThat(boxes.get(0).bottomRightCell()).isEqualTo("AA20");

        var map = engine.mapFieldsByMaster(beforeBytes, SHEET, EQUIPMENT_ID,
                Map.of("equipmentName", OLD_NAME));
        var field = map.fields().get("equipmentName");
        assertThat(field.status()).isEqualTo("VERIFIED");
        assertThat(field.cellAddress()).isEqualTo("J7");
        assertThat(field.currentText()).isEqualTo(OLD_NAME);
        assertThat(map.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        var actualSheet = inspector.inspectSheet(beforeBytes, SHEET);
        assertThat(actualSheet.drawingObjects()).hasSize(56);
        assertThat(actualSheet.drawingObjects()).filteredOn(object -> "CONNECTOR".equals(object.objectType()))
                .hasSize(56);
        assertThat(actualSheet.drawingObjects()).filteredOn(object ->
                object.startRow() != null && object.endRow() != null
                        && object.startColumn() != null && object.endColumn() != null
                        && 6 >= Math.min(object.startRow(), object.endRow())
                        && 6 <= Math.max(object.startRow(), object.endRow())
                        && 9 >= Math.min(object.startColumn(), object.endColumn())
                        && 9 <= Math.max(object.startColumn(), object.endColumn())).isEmpty();

        var result = engine.modifyBoxFieldsTestOnly(beforeBytes, SHEET, EQUIPMENT_ID,
                List.of(new DiagramBoxChangeEngine.FieldChange("J7", OLD_NAME, TEST_NAME)));
        assertThat(result.operation()).isEqualTo("MODIFY");
        assertThat(result.scenarioStatus()).isEqualTo("TEST_ONLY");
        assertThat(result.affectedCells()).containsExactly("J7");
        assertThat(result.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");

        Map<String, byte[]> partsBefore = packageParts(beforeBytes);
        Map<String, byte[]> partsAfter = packageParts(result.workbookBytes());
        assertThat(partsAfter.keySet()).containsExactlyInAnyOrderElementsOf(partsBefore.keySet());
        assertThat(partsAfter.get("xl/drawings/drawing1.xml")).containsExactly(partsBefore.get("xl/drawings/drawing1.xml"));
        List<String> changed = partsBefore.keySet().stream()
                .filter(part -> !java.util.Arrays.equals(partsBefore.get(part), partsAfter.get(part))).toList();
        assertThat(changed).containsExactly("xl/worksheets/sheet1.xml");
        String beforeWorksheet = new String(partsBefore.get("xl/worksheets/sheet1.xml"), java.nio.charset.StandardCharsets.UTF_8);
        String afterWorksheet = new String(partsAfter.get("xl/worksheets/sheet1.xml"), java.nio.charset.StandardCharsets.UTF_8);
        String targetCellPattern = "<c(?=[\\s>])(?=[^>]*\\br=\\\"J7\\\")[^>]*>.*?</c>";
        assertThat(afterWorksheet.replaceFirst(targetCellPattern, "<TARGET_CELL/>"))
                .isEqualTo(beforeWorksheet.replaceFirst(targetCellPattern, "<TARGET_CELL/>"));
        assertThat(sha256(result.workbookBytes())).isEqualTo(result.outputSha256());

        try (var before = WorkbookFactory.create(new ByteArrayInputStream(beforeBytes));
             var after = WorkbookFactory.create(new ByteArrayInputStream(result.workbookBytes()))) {
            var oldSheet = before.getSheet(SHEET);
            var newSheet = after.getSheet(SHEET);
            assertThat(newSheet.getRow(6).getCell(9).getStringCellValue()).isEqualTo(TEST_NAME);
            assertThat(newSheet.getRow(6).getCell(9).getCellStyle().getIndex())
                    .isEqualTo(oldSheet.getRow(6).getCell(9).getCellStyle().getIndex());
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(newSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList())
                    .containsExactlyElementsOf(oldSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList());
            assertThat(after.getPrintArea(after.getSheetIndex(SHEET))).isEqualTo(before.getPrintArea(before.getSheetIndex(SHEET)));
            assertThat(newSheet.getPrintSetup().getLandscape()).isEqualTo(oldSheet.getPrintSetup().getLandscape());
            assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            assertThat(newSheet.getPrintSetup().getFitWidth()).isEqualTo(oldSheet.getPrintSetup().getFitWidth());
            assertThat(newSheet.getPrintSetup().getFitHeight()).isEqualTo(oldSheet.getPrintSetup().getFitHeight());
            for (int row = 0; row <= oldSheet.getLastRowNum(); row++) {
                var oldRow = oldSheet.getRow(row);
                var newRow = newSheet.getRow(row);
                assertThat(newRow == null ? -1 : newRow.getHeight())
                        .as("P-01 row height %d", row).isEqualTo(oldRow == null ? -1 : oldRow.getHeight());
            }
            for (int column = 0; column < 64; column++)
                assertThat(newSheet.getColumnWidth(column)).isEqualTo(oldSheet.getColumnWidth(column));
        }
        assertThat(zipEntryNames(result.workbookBytes())).containsExactlyInAnyOrderElementsOf(partsBefore.keySet());
        assertThat(sha256(Files.readAllBytes(source))).isEqualTo(SOURCE_SHA);

        Files.createDirectories(OUTPUT_DIR);
        Path beforePath = OUTPUT_DIR.resolve("before-P01-official-copy.xlsx");
        Path afterPath = OUTPUT_DIR.resolve("after-P01-name-test-only.xlsx");
        writeOrCompare(beforePath, beforeBytes);
        writeOrCompare(afterPath, result.workbookBytes());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("classification", "TEST_ONLY; NOT AN OFFICIAL PROCESS CHANGE");
        report.put("processCode", PROCESS);
        report.put("sheetName", SHEET);
        report.put("equipmentId", EQUIPMENT_ID);
        report.put("boxRange", "J4:AA20");
        report.put("sourceSha256", SOURCE_SHA);
        report.put("field", "equipmentName");
        report.put("cell", "J7");
        report.put("before", OLD_NAME);
        report.put("after", TEST_NAME);
        report.put("outputSha256", result.outputSha256());
        report.put("connectorCountPreserved", 56);
        report.put("drawingmlPartUnchanged", true);
        report.put("profileStatus", "REVIEW_REQUIRED; NOT PROMOTED");
        report.put("connectionEvidenceStatus", result.connectionEvidenceStatus());
        report.put("generatedFiles", List.of(beforePath.toString(), afterPath.toString()));
        ObjectMapper mapper = new ObjectMapper();
        Path validationPath = OUTPUT_DIR.resolve("p01-validation-results.json");
        byte[] reportBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        if (Files.exists(validationPath)) {
            assertThat(mapper.readTree(Files.readAllBytes(validationPath))).isEqualTo(mapper.readTree(reportBytes));
        } else {
            Files.write(validationPath, reportBytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        }
    }

    private void writeOrCompare(Path output, byte[] expectedBytes) throws Exception {
        if (Files.exists(output)) {
            assertThat(Files.readAllBytes(output)).containsExactly(expectedBytes);
        } else {
            Files.write(output, expectedBytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        }
    }

    private Map<String, byte[]> packageParts(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }

    private List<String> zipEntryNames(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            List<String> names = new java.util.ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            return names;
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
