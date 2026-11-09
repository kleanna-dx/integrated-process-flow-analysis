package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Audits the four freshly downloaded PILOT-04 HTTP A/B files, not earlier PILOT artifacts. */
@EnabledIfSystemProperty(named = "pilot04.output.dir", matches = ".+")
class Pilot04DownloadedWorkbookAuditTest {
    private static final Path RESULTS_DIR = Path.of("validation-results", "pilot-04-20261104");
    private static final Path DEFAULT_RUN = RESULTS_DIR.resolve("http-ab-20261104T081838Z");
    private static final Path SOURCE_DIR = Path.of("validation-results", "pilot-02-20261104");
    private final ObjectMapper mapper = new ObjectMapper();
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();

    @Test
    void allFourHttpAbDownloadsHaveOnlyThePinnedTargetCellChanged() throws Exception {
        Path runDirectory = Path.of(System.getProperty("pilot04.output.dir", DEFAULT_RUN.toString()));
        JsonNode report = mapper.readTree(Files.readAllBytes(runDirectory.resolve("HTTP_AB_RESULTS.json")));
        assertThat(report.path("runType").asText()).isEqualTo("PILOT-04 actual HTTP A/B dynamic generation");
        assertThat(report.path("passed").asInt()).isEqualTo(4);
        assertThat(report.path("failed").asInt()).isZero();
        assertThat(report.path("outputs").size()).isEqualTo(4);

        Map<String, String> outputShaByProcessAndInput = new LinkedHashMap<>();
        for (JsonNode item : report.path("outputs")) {
            String process = item.path("processCode").asText();
            String input = item.path("inputText").asText();
            String expectedSha = item.path("outputSha256").asText();
            String expectedSourceSha = item.path("sourceSha256").asText();
            String cellAddress = item.path("cell").asText();
            String sheetName = item.path("sheetName").asText();
            String sourceFilename = "P-02".equals(process)
                    ? "P02-before-official-copy.xlsx" : "P01-before-official-copy.xlsx";
            String expectedBaselineSha = "P-02".equals(process)
                    ? "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76"
                    : "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
            Path originalPath = SOURCE_DIR.resolve(sourceFilename);
            Path outputPath = RESULTS_DIR.resolve(item.path("artifact").asText());
            byte[] source = Files.readAllBytes(originalPath);
            byte[] output = Files.readAllBytes(outputPath);

            assertThat(item.path("preview").asText()).isEqualTo("PASS");
            assertThat(item.path("generate").asText()).isEqualTo("PASS");
            assertThat(item.path("download").asText()).isEqualTo("PASS");
            assertThat(item.path("inputText").asText()).startsWith("SYNTHETIC_TEST_ONLY ");
            assertThat(item.path("generatedAt").asText()).isNotBlank();
            assertThat(expectedSourceSha).isEqualTo(expectedBaselineSha);
            assertThat(sha256(source)).isEqualTo(expectedBaselineSha);
            assertThat(sha256(output)).isEqualTo(expectedSha);
            assertThat(item.path("downloadSha256").asText()).isEqualTo(expectedSha);
            assertThat(item.path("bytes").asLong()).isEqualTo(output.length);
            assertThat(output).isNotEqualTo(source);
            assertZipIntegrity(output);
            assertWorkbookValuesAndLayout(source, output, sheetName, cellAddress, input);
            assertPackageParts(source, output, cellAddress, input);

            var beforeDrawing = inspector.inspect(source);
            var afterDrawing = inspector.inspect(output);
            assertThat(afterDrawing.shapeCount()).isEqualTo(beforeDrawing.shapeCount());
            assertThat(afterDrawing.connectorCount()).isEqualTo(beforeDrawing.connectorCount());
            assertThat(inspector.inspectSheet(output, sheetName).drawingObjects())
                    .containsExactlyElementsOf(inspector.inspectSheet(source, sheetName).drawingObjects());
            assertThat(sha256(Files.readAllBytes(originalPath))).isEqualTo(expectedBaselineSha);
            outputShaByProcessAndInput.put(process + ":" + input, expectedSha);
        }

        assertThat(outputShaByProcessAndInput).hasSize(4);
        for (String process : List.of("P-02", "P-01")) {
            String processSuffix = process.replace("-", "");
            String a = outputShaByProcessAndInput.get(process + ":SYNTHETIC_TEST_ONLY PILOT04 " + processSuffix + " A");
            String b = outputShaByProcessAndInput.get(process + ":SYNTHETIC_TEST_ONLY PILOT04 " + processSuffix + " B");
            assertThat(a).isNotBlank();
            assertThat(b).isNotBlank();
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot04.chromium.report", matches = ".+")
    void allFourChromiumDownloadsMatchHttpAndPassWorkbookPreservationChecks() throws Exception {
        Path chromiumReport = Path.of(System.getProperty("pilot04.chromium.report",
                RESULTS_DIR.resolve("CHROMIUM_RESULTS-20261104T082503Z.json").toString()));
        JsonNode report = mapper.readTree(Files.readAllBytes(chromiumReport));
        Path httpRunDirectory = Path.of(System.getProperty("pilot04.output.dir", DEFAULT_RUN.toString()));
        JsonNode httpReport = mapper.readTree(Files.readAllBytes(httpRunDirectory.resolve("HTTP_AB_RESULTS.json")));
        assertThat(report.path("passed").asInt()).isEqualTo(4);
        assertThat(report.path("failed").asInt()).isZero();
        assertThat(report.path("consoleErrors").size()).isZero();
        assertThat(report.path("outputs").size()).isEqualTo(4);
        Map<String, String> generatedByProcessAndInput = new LinkedHashMap<>();

        for (JsonNode item : report.path("outputs")) {
            String process = item.path("processCode").asText();
            String input = item.path("input").asText();
            String cell = item.path("cell").asText();
            String outputSha = item.path("serverSha256").asText();
            assertThat(item.path("preview").asText()).isEqualTo("PASS");
            assertThat(item.path("generate").asText()).isEqualTo("PASS");
            assertThat(item.path("download").asText()).isEqualTo("PASS");
            assertThat(item.path("browserDownloadedSha256").asText()).isEqualTo(outputSha);
            assertThat(item.path("generatedAt").asText()).isNotBlank();
            assertThat(item.path("screenshots").size()).isEqualTo(2);
            for (JsonNode capture : item.path("screenshots")) {
                assertThat(Files.size(RESULTS_DIR.resolve(capture.asText()))).isPositive();
            }

            String[] captureParts = item.path("screenshots").get(0).asText().split("/");
            assertThat(captureParts).hasSize(3);
            Path browserWorkbook = RESULTS_DIR.resolve("generated/chromium-downloads")
                    .resolve(captureParts[1]).resolve(item.path("filename").asText());
            byte[] browserBytes = Files.readAllBytes(browserWorkbook);
            assertThat(sha256(browserBytes)).isEqualTo(outputSha);

            JsonNode httpOutput = null;
            for (JsonNode candidate : httpReport.path("outputs")) {
                if (candidate.path("processCode").asText().equals(process)
                        && candidate.path("inputText").asText().equals(input)) {
                    httpOutput = candidate;
                    break;
                }
            }
            assertThat(httpOutput).as("matching HTTP run for %s / %s", process, input).isNotNull();
            Path httpWorkbook = RESULTS_DIR.resolve(httpOutput.path("artifact").asText());
            assertThat(browserBytes).containsExactly(Files.readAllBytes(httpWorkbook));

            String sourceFile = "P-02".equals(process)
                    ? "P02-before-official-copy.xlsx" : "P01-before-official-copy.xlsx";
            String sourceSha = "P-02".equals(process)
                    ? "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76"
                    : "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
            String sheet = "P-02".equals(process) ? "P-02-01(TOP)" : "P-01-01(TOP)";
            byte[] source = Files.readAllBytes(SOURCE_DIR.resolve(sourceFile));
            assertThat(sha256(source)).isEqualTo(sourceSha);
            assertZipIntegrity(browserBytes);
            assertWorkbookValuesAndLayout(source, browserBytes, sheet, cell, input);
            assertPackageParts(source, browserBytes, cell, input);
            assertThat(inspector.inspect(browserBytes).shapeCount()).isEqualTo(inspector.inspect(source).shapeCount());
            assertThat(inspector.inspect(browserBytes).connectorCount()).isEqualTo(inspector.inspect(source).connectorCount());
            assertThat(inspector.inspectSheet(browserBytes, sheet).drawingObjects())
                    .containsExactlyElementsOf(inspector.inspectSheet(source, sheet).drawingObjects());
            generatedByProcessAndInput.put(process + ":" + input, outputSha);
        }

        assertThat(generatedByProcessAndInput).hasSize(4);
        for (String process : List.of("P-02", "P-01")) {
            String suffix = process.replace("-", "");
            String a = generatedByProcessAndInput.get(process + ":SYNTHETIC_TEST_ONLY PILOT04 " + suffix + " A");
            String b = generatedByProcessAndInput.get(process + ":SYNTHETIC_TEST_ONLY PILOT04 " + suffix + " B");
            assertThat(a).isNotBlank();
            assertThat(b).isNotBlank();
            assertThat(a).isNotEqualTo(b);
        }
    }

    private void assertWorkbookValuesAndLayout(byte[] source, byte[] output, String sheetName,
            String cellAddress, String input) throws Exception {
        try (var original = WorkbookFactory.create(new ByteArrayInputStream(source));
             var generated = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            assertThat(generated.getNumberOfSheets()).isEqualTo(original.getNumberOfSheets());
            for (int i = 0; i < original.getNumberOfSheets(); i++) {
                var oldSheet = original.getSheetAt(i);
                var newSheet = generated.getSheetAt(i);
                assertThat(newSheet.getSheetName()).isEqualTo(oldSheet.getSheetName());
                assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
                assertThat(newSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList())
                        .containsExactlyElementsOf(oldSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList());
                assertThat(generated.getPrintArea(i)).isEqualTo(original.getPrintArea(i));
                assertThat(newSheet.getPrintSetup().getLandscape()).isEqualTo(oldSheet.getPrintSetup().getLandscape());
                assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
                assertThat(newSheet.getPrintSetup().getFitWidth()).isEqualTo(oldSheet.getPrintSetup().getFitWidth());
                assertThat(newSheet.getPrintSetup().getFitHeight()).isEqualTo(oldSheet.getPrintSetup().getFitHeight());
                assertThat(newSheet.getPrintSetup().getScale()).isEqualTo(oldSheet.getPrintSetup().getScale());
                for (int rowIndex = 0; rowIndex <= oldSheet.getLastRowNum(); rowIndex++) {
                    var oldRow = oldSheet.getRow(rowIndex);
                    var newRow = newSheet.getRow(rowIndex);
                    assertThat(newRow == null ? -1 : newRow.getHeight())
                            .as("%s row height %d", oldSheet.getSheetName(), rowIndex)
                            .isEqualTo(oldRow == null ? -1 : oldRow.getHeight());
                }
                for (int column = 0; column < 80; column++) {
                    assertThat(newSheet.getColumnWidth(column))
                            .as("%s column width %d", oldSheet.getSheetName(), column)
                            .isEqualTo(oldSheet.getColumnWidth(column));
                    assertThat(newSheet.isColumnHidden(column))
                            .as("%s column hidden %d", oldSheet.getSheetName(), column)
                            .isEqualTo(oldSheet.isColumnHidden(column));
                }
                if (oldSheet.getSheetName().equals(sheetName)) {
                    var ref = new CellReference(cellAddress);
                    Cell oldCell = oldSheet.getRow(ref.getRow()).getCell(ref.getCol());
                    Cell newCell = newSheet.getRow(ref.getRow()).getCell(ref.getCol());
                    assertThat(oldCell.getStringCellValue()).isNotEqualTo(input);
                    assertThat(newCell.getCellType()).isEqualTo(CellType.STRING);
                    assertThat(newCell.getStringCellValue()).isEqualTo(input);
                    assertThat(newCell.getCellStyle().getIndex()).isEqualTo(oldCell.getCellStyle().getIndex());
                }
            }
        }
    }

    private void assertPackageParts(byte[] source, byte[] output, String targetCell, String replacement) throws Exception {
        Map<String, byte[]> before = packageParts(source);
        Map<String, byte[]> after = packageParts(output);
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        List<String> changed = before.keySet().stream().filter(name -> !Arrays.equals(before.get(name), after.get(name))).toList();
        assertThat(changed).containsExactly("xl/worksheets/sheet1.xml");
        for (String name : before.keySet()) {
            if (name.startsWith("xl/drawings/") || name.startsWith("xl/media/")) {
                assertThat(after.get(name)).as("preserve OOXML drawing/media part %s", name).containsExactly(before.get(name));
            }
        }
        String part = "xl/worksheets/sheet1.xml";
        String patternText = "<c(?=[\\s>])(?=[^>]*\\br=\\\"" + Pattern.quote(targetCell) + "\\\")[^>]*>.*?</c>";
        Pattern pattern = Pattern.compile(patternText, Pattern.DOTALL);
        String oldXml = new String(before.get(part), StandardCharsets.UTF_8);
        String newXml = new String(after.get(part), StandardCharsets.UTF_8);
        Matcher oldMatch = pattern.matcher(oldXml);
        Matcher newMatch = pattern.matcher(newXml);
        assertThat(oldMatch.find()).isTrue();
        assertThat(newMatch.find()).isTrue();
        assertThat(oldMatch.find()).isFalse();
        assertThat(newMatch.find()).isFalse();
        assertThat(newXml.replaceFirst(patternText, "<TARGET_CELL/>"))
                .isEqualTo(oldXml.replaceFirst(patternText, "<TARGET_CELL/>"));
        assertThat(newXml).contains(replacement);
    }

    private static Map<String, byte[]> packageParts(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    assertThat(result.put(entry.getName(), input.readAllBytes())).isNull();
                }
            }
        }
        return result;
    }

    private static void assertZipIntegrity(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) while (zip.read(buffer) != -1) { }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
