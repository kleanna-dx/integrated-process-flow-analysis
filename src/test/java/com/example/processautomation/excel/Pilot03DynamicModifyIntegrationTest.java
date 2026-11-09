package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.api.OperatorLoginService;
import com.example.processautomation.api.OperatorSessionController;
import com.example.processautomation.material.FlowMappingReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-scope Preview -> Generate -> Download exercise over the two pinned PILOT-01 source copies.
 * This controller is not part of the application and is never included in production artifacts.
 */
class Pilot03DynamicModifyIntegrationTest {
    private static final String API = "/test-only/pilot-03/modify";
    private static final String USER = "pilot03-test-operator";
    private static final Path SOURCE_DIR = Path.of("validation-results", "pilot-02-20261104");
    private static final Path OUTPUT_DIR = Path.of("validation-results", "pilot-03-20261104");
    private static final String P02_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String P01_SHA = "426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391";
    private static final String P02_BEFORE = "131 MULTI SORTER#1";
    private static final String P01_BEFORE = "40㎥ PULPER (TOP, IV)";

    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
            new OoxmlPartEditor(), inspector, new EquipmentBoxGeometryLocator());
    private final DynamicWorkflow workflow = new DynamicWorkflow(engine, inspector, SOURCE_DIR);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String LOGIN_PASSWORD = "PILOT03-Only-Test-Password!";
    private static final String LOGIN_PASSWORD_HASH = createPasswordHash();
    private final OperatorLoginService login = new OperatorLoginService(USER, LOGIN_PASSWORD_HASH,
            org.mockito.Mockito.mock(JdbcTemplate.class));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestOnlyController(login, workflow, true),
                new OperatorSessionController(login, org.mockito.Mockito.mock(FlowMappingReviewService.class))).build();
    }

    @Test
    void testOnlyRouteIsDisabledByDefault() throws Exception {
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(new TestOnlyController(login, workflow),
                new OperatorSessionController(login, org.mockito.Mockito.mock(FlowMappingReviewService.class))).build();
        long outputCountBefore = countGeneratedOutputs();
        MockHttpSession session = authenticatedSession();
        disabledMvc.perform(post(API + "/preview").session(session)
                        .header("X-CSRF-Token", login.csrfToken(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new CaseInput("P-02", "SYNTHETIC_TEST_ONLY PILOT03 OFF"))))
                .andExpect(status().isNotFound());
        assertThat(countGeneratedOutputs()).isEqualTo(outputCountBefore);
    }

    @Test
    void serverCreatesFourDistinctInputDrivenWorkbooksAndDownloadsExactBytes() throws Exception {
        List<CaseInput> inputs = List.of(
                new CaseInput("P-02", "SYNTHETIC_TEST_ONLY PILOT03 P02 A"),
                new CaseInput("P-02", "SYNTHETIC_TEST_ONLY PILOT03 P02 B"),
                new CaseInput("P-01", "SYNTHETIC_TEST_ONLY PILOT03 P01 A"),
                new CaseInput("P-01", "SYNTHETIC_TEST_ONLY PILOT03 P01 B"));
        for (CaseInput input : inputs) {
            MockHttpSession session = authenticatedSession();
            var previewResult = mvc.perform(post(API + "/preview").session(session)
                            .header("X-CSRF-Token", login.csrfToken(session))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(input)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("TEST_ONLY_READY"))
                    .andExpect(jsonPath("$.processCode").value(input.processCode()))
                    .andExpect(jsonPath("$.replacementText").value(input.replacementText()))
                    .andReturn();
            Map<?, ?> preview = mapper.readValue(previewResult.getResponse().getContentAsByteArray(), Map.class);
            String previewId = String.valueOf(preview.get("previewId"));

            var generatedResult = mvc.perform(post(API + "/generate/" + previewId).session(session)
                            .header("X-CSRF-Token", login.csrfToken(session)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                    .andExpect(jsonPath("$.officialBaselineModified").value(false))
                    .andExpect(jsonPath("$.outputSha256").isNotEmpty())
                    .andReturn();
            Map<?, ?> generated = mapper.readValue(generatedResult.getResponse().getContentAsByteArray(), Map.class);
            String expectedOutputSha = String.valueOf(generated.get("outputSha256"));
            assertThat(generated.get("sourceSha256")).isEqualTo(preview.get("sourceSha256"));
            assertThat(generated.get("replacementText")).isEqualTo(input.replacementText());

            var downloaded = mvc.perform(get(API + "/download/" + previewId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Output-SHA256", expectedOutputSha))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Source-SHA256", String.valueOf(preview.get("sourceSha256"))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Scenario-Status", "TEST_ONLY"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Official-Baseline-Modified", "false"))
                    .andReturn();
            byte[] downloadedBytes = downloaded.getResponse().getContentAsByteArray();
            assertThat(sha256(downloadedBytes)).isEqualTo(expectedOutputSha);
            assertThat(downloadedBytes).containsExactly(workflow.generated(previewId, USER).bytes());

            CaseConfig config = workflow.config(input.processCode());
            Path source = SOURCE_DIR.resolve(config.sourceFilename());
            assertThat(sha256(Files.readAllBytes(source))).isEqualTo(config.officialSha256());
            assertWorkbookAndPackage(downloadedBytes, config, input.replacementText());
        }
        assertThat(countGeneratedOutputs()).isEqualTo(5);
    }

    @Test
    void realLoginSessionCsrfAndSessionIsolationProtectTheTestOnlyRequestFlow() throws Exception {
        LoginState loggedIn = loginThroughApplicationEndpoint();
        MockHttpSession alice = loggedIn.session();
        String csrf = loggedIn.csrfToken();
        var unauthenticated = new MockHttpSession();
        mvc.perform(post(API + "/preview").session(unauthenticated).header("X-CSRF-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new CaseInput("P-02", "SYNTHETIC_TEST_ONLY P02 AUTH"))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(API + "/preview").session(alice).header("X-CSRF-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new CaseInput("P-02", "SYNTHETIC_TEST_ONLY P02 AUTH"))))
                .andExpect(status().isForbidden());

        var previewResult = mvc.perform(post(API + "/preview").session(alice).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new CaseInput("P-02", "SYNTHETIC_TEST_ONLY P02 SESSION"))))
                .andExpect(status().isOk()).andReturn();
        String previewId = mapper.readTree(previewResult.getResponse().getContentAsByteArray())
                .path("previewId").asText();

        LoginState secondLogin = loginThroughApplicationEndpoint();
        MockHttpSession bob = secondLogin.session();
        mvc.perform(post(API + "/generate/" + previewId).session(bob)
                        .header("X-CSRF-Token", secondLogin.csrfToken()))
                .andExpect(status().isNotFound());
        mvc.perform(post(API + "/generate/" + previewId).session(alice).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/download/" + previewId).session(bob)).andExpect(status().isNotFound());
    }

    @Test
    void allowlistNoOpFormulaLengthAndTamperedPreviewAreHandled() throws Exception {
        assertThatThrownBy(() -> workflow.preview(USER, "PW-02", "SYNTHETIC_TEST_ONLY not allowed"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> workflow.preview(USER, "P-02", "=1+1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> workflow.preview(USER, "P-01", "X".repeat(81)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> workflow.preview(USER, "P-02", "UNMARKED OPERATING TEXT"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> workflow.preview(USER, "P-02", "SYNTHETIC_TEST_ONLY !bad"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        long beforeCount = countGeneratedOutputs();
        var noOp = workflow.preview(USER, "P-02", P02_BEFORE);
        assertThat(noOp.status()).isEqualTo("NO_OP");
        assertThat(noOp.previewId()).isNull();
        assertThat(countGeneratedOutputs()).isEqualTo(beforeCount);

        var valid = workflow.preview(USER, "P-01", "SYNTHETIC_TEST_ONLY PILOT03 TAMPER");
        var forged = new Preview(valid.previewId(), valid.owner(), valid.config(), valid.sourceSha256(),
                valid.beforeText(), "SYNTHETIC_TEST_ONLY PILOT03 OTHER", valid.fingerprint(), valid.status());
        assertThatThrownBy(() -> workflow.generate(USER, forged))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void pinnedSourceShaIsRecheckedAtGenerate(@TempDir Path tamperedSourceDir) throws Exception {
        CaseConfig config = new CaseConfig("P-02", "P02-before-official-copy.xlsx", P02_SHA,
                "P-02-01(TOP)", "I-PP21005", "I89", P02_BEFORE);
        byte[] source = Files.readAllBytes(SOURCE_DIR.resolve(config.sourceFilename()));
        Files.write(tamperedSourceDir.resolve(config.sourceFilename()), source);
        DynamicWorkflow isolated = new DynamicWorkflow(engine, inspector, tamperedSourceDir);
        Preview preview = isolated.preview(USER, "P-02", "SYNTHETIC_TEST_ONLY STALE CHECK");
        byte[] tampered = source.clone();
        tampered[tampered.length / 2] ^= 0x01;
        Files.write(tamperedSourceDir.resolve(config.sourceFilename()), tampered);
        assertThatThrownBy(() -> isolated.generate(USER, preview))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(Files.readAllBytes(SOURCE_DIR.resolve(config.sourceFilename()))).containsExactly(source);
    }

    private MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, USER);
        login.csrfToken(session);
        return session;
    }

    static String createPasswordHash() {
        return new OperatorLoginService(USER, "not-used", org.mockito.Mockito.mock(JdbcTemplate.class))
                .encodePassword(LOGIN_PASSWORD.toCharArray());
    }

    private LoginState loginThroughApplicationEndpoint() throws Exception {
        var response = mvc.perform(post("/api/session/login").header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", USER, "password", LOGIN_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new LoginState((MockHttpSession) response.getRequest().getSession(false),
                mapper.readTree(response.getResponse().getContentAsByteArray()).path("csrfToken").asText());
    }

    private record LoginState(MockHttpSession session, String csrfToken) { }

    private long countGeneratedOutputs() throws Exception {
        if (!Files.isDirectory(OUTPUT_DIR)) return 0;
        try (var paths = Files.list(OUTPUT_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".xlsx")).count();
        }
    }

    private void assertWorkbookAndPackage(byte[] output, CaseConfig config, String replacement) throws Exception {
        byte[] source = Files.readAllBytes(SOURCE_DIR.resolve(config.sourceFilename()));
        assertThat(sha256(source)).isEqualTo(config.officialSha256());
        assertThat(sha256(output)).isNotEqualTo(sha256(source));
        assertZipIntegrity(output);
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(source));
             var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldSheet = before.getSheet(config.sheetName());
            var newSheet = after.getSheet(config.sheetName());
            var target = new org.apache.poi.ss.util.CellReference(config.cellAddress());
            var oldCell = oldSheet.getRow(target.getRow()).getCell(target.getCol());
            var newCell = newSheet.getRow(target.getRow()).getCell(target.getCol());
            assertThat(newCell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(newCell.getStringCellValue()).isEqualTo(replacement);
            assertThat(newCell.getCellStyle().getIndex()).isEqualTo(oldCell.getCellStyle().getIndex());
            assertThat(after.getNumberOfSheets()).isEqualTo(before.getNumberOfSheets());
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(newSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList())
                    .containsExactlyElementsOf(oldSheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList());
            assertThat(after.getPrintArea(after.getSheetIndex(config.sheetName())))
                    .isEqualTo(before.getPrintArea(before.getSheetIndex(config.sheetName())));
            assertThat(newSheet.getPrintSetup().getLandscape()).isEqualTo(oldSheet.getPrintSetup().getLandscape());
            assertThat(newSheet.getPrintSetup().getPaperSize()).isEqualTo(oldSheet.getPrintSetup().getPaperSize());
            assertThat(newSheet.getPrintSetup().getFitWidth()).isEqualTo(oldSheet.getPrintSetup().getFitWidth());
            assertThat(newSheet.getPrintSetup().getFitHeight()).isEqualTo(oldSheet.getPrintSetup().getFitHeight());
            for (int row = 0; row <= oldSheet.getLastRowNum(); row++) {
                var oldRow = oldSheet.getRow(row);
                var newRow = newSheet.getRow(row);
                assertThat(newRow == null ? -1 : newRow.getHeight())
                        .as("row height %d", row).isEqualTo(oldRow == null ? -1 : oldRow.getHeight());
            }
            int maxColumn = Math.max(oldSheet.getRow(0).getLastCellNum(), newSheet.getRow(0).getLastCellNum());
            for (int col = 0; col < Math.max(64, maxColumn); col++)
                assertThat(newSheet.getColumnWidth(col)).as("column width %d", col).isEqualTo(oldSheet.getColumnWidth(col));
        }

        Map<String, byte[]> beforeParts = packageParts(source);
        Map<String, byte[]> afterParts = packageParts(output);
        assertThat(afterParts.keySet()).containsExactlyInAnyOrderElementsOf(beforeParts.keySet());
        List<String> changed = beforeParts.keySet().stream()
                .filter(name -> !Arrays.equals(beforeParts.get(name), afterParts.get(name))).toList();
        assertThat(changed).containsExactly("xl/worksheets/sheet1.xml");
        String worksheetPart = "xl/worksheets/sheet1.xml";
        String targetPattern = "<c(?=[\\s>])(?=[^>]*\\br=\\\"" + Pattern.quote(config.cellAddress())
                + "\\\")[^>]*>.*?</c>";
        String beforeXml = new String(beforeParts.get(worksheetPart), StandardCharsets.UTF_8);
        String afterXml = new String(afterParts.get(worksheetPart), StandardCharsets.UTF_8);
        assertThat(afterXml.replaceFirst(targetPattern, "<TARGET_CELL/>"))
                .isEqualTo(beforeXml.replaceFirst(targetPattern, "<TARGET_CELL/>"));
        assertThat(afterParts.get("xl/drawings/drawing1.xml"))
                .containsExactly(beforeParts.get("xl/drawings/drawing1.xml"));
        assertThat(afterXml).contains(replacement);
        assertThat(inspector.inspect(source).shapeCount()).isEqualTo(inspector.inspect(output).shapeCount());
        assertThat(inspector.inspect(source).connectorCount()).isEqualTo(inspector.inspect(output).connectorCount());
        var beforeSheet = inspector.inspectSheet(source, config.sheetName());
        var afterSheet = inspector.inspectSheet(output, config.sheetName());
        assertThat(afterSheet.drawingObjects()).containsExactlyElementsOf(beforeSheet.drawingObjects());
        assertThat(sha256(Files.readAllBytes(SOURCE_DIR.resolve(config.sourceFilename()))))
                .isEqualTo(config.officialSha256());
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

    record CaseInput(String processCode, String replacementText) { }
    private record CaseConfig(String processCode, String sourceFilename, String officialSha256,
                              String sheetName, String managementNo, String cellAddress, String beforeText) { }
    private record Preview(String previewId, String owner, CaseConfig config, String sourceSha256,
                           String beforeText, String replacementText, String fingerprint, String status) { }
    private record Generated(String previewId, byte[] bytes, String sourceSha256, String outputSha256,
                             String fileName, String replacementText) { }

    static final class DynamicWorkflow {
        private static final Pattern ALLOWED_TEXT = Pattern.compile(
                "[A-Za-z0-9가-힣][A-Za-z0-9가-힣 _#().,㎥℃/-]{0,79}");
        private static final Map<String, CaseConfig> CASES = Map.of(
                "P-02", new CaseConfig("P-02", "P02-before-official-copy.xlsx", P02_SHA,
                        "P-02-01(TOP)", "I-PP21005", "I89", P02_BEFORE),
                "P-01", new CaseConfig("P-01", "P01-before-official-copy.xlsx", P01_SHA,
                        "P-01-01(TOP)", "I-PP11001", "J7", P01_BEFORE));
        private final DiagramBoxChangeEngine engine;
        private final OoxmlPackageInspector inspector;
        private final Path sourceDirectory;
        private final Path outputDirectory;
        private final Map<String, Map<String, Preview>> previewsByUser = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Map<String, Generated>> generatedByUser = new java.util.concurrent.ConcurrentHashMap<>();

        private DynamicWorkflow(DiagramBoxChangeEngine engine, OoxmlPackageInspector inspector, Path sourceDirectory) {
            this(engine, inspector, sourceDirectory, OUTPUT_DIR);
        }

        DynamicWorkflow(DiagramBoxChangeEngine engine, OoxmlPackageInspector inspector,
                Path sourceDirectory, Path outputDirectory) {
            this.engine = engine;
            this.inspector = inspector;
            this.sourceDirectory = sourceDirectory;
            this.outputDirectory = outputDirectory;
        }

        CaseConfig config(String processCode) { return requireCase(processCode); }

        Preview preview(String owner, String processCode, String replacementText) {
            if (owner == null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            CaseConfig config = requireCase(processCode);
            byte[] source = readApprovedSource(config);
            String before = readText(source, config);
            if (!config.beforeText().equals(before))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned target cell changed from its verified PILOT value.");
            if (before.equals(replacementText))
                return new Preview(null, owner, config, config.officialSha256(), before, replacementText,
                        fingerprint(config, before, replacementText), "NO_OP");
            validateReplacement(replacementText);
            String fingerprint = fingerprint(config, before, replacementText);
            Preview preview = new Preview(UUID.randomUUID().toString(), owner, config,
                    config.officialSha256(), before, replacementText, fingerprint, "TEST_ONLY_READY");
            previewsByUser.computeIfAbsent(owner, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(preview.previewId(), preview);
            generatedByUser.computeIfAbsent(owner, ignored -> new java.util.concurrent.ConcurrentHashMap<>()).clear();
            return preview;
        }

        Generated generate(String owner, Preview supplied) {
            if (owner == null || owner.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            if (supplied == null || supplied.previewId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A non-no-op preview is required.");
            Preview stored = previewsByUser.getOrDefault(owner, Map.of()).get(supplied.previewId());
            if (stored == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview is not in this owner scope.");
            if (!stored.equals(supplied) || !stored.fingerprint().equals(
                    fingerprint(stored.config(), stored.beforeText(), stored.replacementText())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview input fingerprint changed.");
            byte[] source = readApprovedSource(stored.config());
            if (!stored.sourceSha256().equals(sha256Unchecked(source))
                    || !stored.config().officialSha256().equalsIgnoreCase(stored.sourceSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned source SHA changed after Preview.");
            String current = readText(source, stored.config());
            if (!stored.beforeText().equals(current))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Target cell value changed after Preview.");
            DiagramBoxChangeEngine.ChangeResult result = engine.modifyBoxFieldsTestOnly(source,
                    stored.config().sheetName(), stored.config().managementNo(), List.of(
                            new DiagramBoxChangeEngine.FieldChange(stored.config().cellAddress(),
                                    stored.beforeText(), stored.replacementText())));
            if (!stored.sourceSha256().equalsIgnoreCase(result.sourceSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Engine source SHA differs from Preview.");
            validateGenerated(result.workbookBytes(), stored.config(), stored.replacementText());
            String outputSha = sha256Unchecked(result.workbookBytes());
            String filename = stored.config().processCode() + "-TEST_ONLY-" + outputSha.substring(0, 16) + ".xlsx";
            persistNewOrCompare(outputDirectory.resolve(filename), result.workbookBytes());
            Generated generated = new Generated(stored.previewId(), result.workbookBytes(), result.sourceSha256(),
                    outputSha, filename, stored.replacementText());
            generatedByUser.computeIfAbsent(owner, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(stored.previewId(), generated);
            return generated;
        }

        Generated generated(String previewId, String owner) {
            Generated result = generatedByUser.getOrDefault(owner, Map.of()).get(previewId);
            if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return result;
        }

        Generated download(String previewId, String owner) { return generated(previewId, owner); }

        private CaseConfig requireCase(String processCode) {
            CaseConfig config = CASES.get(processCode);
            if (config == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process is outside the PILOT-03 allowlist.");
            return config;
        }

        private void validateReplacement(String replacement) {
            if (replacement == null || replacement.length() > 80
                    || !replacement.startsWith("SYNTHETIC_TEST_ONLY ")
                    || !ALLOWED_TEXT.matcher(replacement).matches()
                    || replacement.startsWith("=") || replacement.startsWith("+") || replacement.startsWith("-"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Replacement must be 1-80 allowed plain-text characters; formula syntax is rejected.");
        }

        private byte[] readApprovedSource(CaseConfig config) {
            try {
                byte[] source = Files.readAllBytes(sourceDirectory.resolve(config.sourceFilename()));
                if (!config.officialSha256().equalsIgnoreCase(sha256(source)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned official source SHA mismatch.");
                return source;
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pinned TEST_ONLY source copy unavailable.", exception);
            }
        }

        private String readText(byte[] source, CaseConfig config) {
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
                var reference = new org.apache.poi.ss.util.CellReference(config.cellAddress());
                var cell = workbook.getSheet(config.sheetName()).getRow(reference.getRow()).getCell(reference.getCol());
                if (cell.getCellType() != CellType.STRING) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pinned target is not a plain text cell.");
                return cell.getStringCellValue();
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned target cell could not be inspected.", exception);
            }
        }

        private String fingerprint(CaseConfig config, String before, String replacement) {
            String canonical = String.join("\u0000", config.processCode(), config.officialSha256(), config.sheetName(),
                    config.managementNo(), config.cellAddress(), before, replacement);
            return sha256Unchecked(canonical.getBytes(StandardCharsets.UTF_8));
        }

        private void validateGenerated(byte[] bytes, CaseConfig config, String replacement) {
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                var ref = new org.apache.poi.ss.util.CellReference(config.cellAddress());
                var cell = workbook.getSheet(config.sheetName()).getRow(ref.getRow()).getCell(ref.getCol());
                if (cell.getCellType() != CellType.STRING || !replacement.equals(cell.getStringCellValue()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Generated target cell does not match the preview text.");
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Generated XLSX could not be reopened.", exception);
            }
            if (inspector.inspect(bytes).sheets().isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Generated XLSX has no worksheets.");
        }

        private void persistNewOrCompare(Path path, byte[] bytes) {
            try {
                Files.createDirectories(outputDirectory);
                Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exists) {
                try {
                    if (!Arrays.equals(Files.readAllBytes(path), bytes))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing result name contains different bytes.");
                } catch (ResponseStatusException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Existing result could not be compared.", exception);
                }
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Generated workbook could not be persisted.", exception);
            }
        }

        private String sha256(byte[] bytes) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        private String sha256Unchecked(byte[] bytes) {
            try { return sha256(bytes); } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
    }

    @RestController
    @RequestMapping(API)
    static final class TestOnlyController {
        private static final String PREVIEWS = "pilot03.test-only.previews";
        private static final String GENERATED = "pilot03.test-only.generated";
        private final OperatorLoginService login;
        private final DynamicWorkflow workflow;
        private final boolean enabled;

        private TestOnlyController(OperatorLoginService login, DynamicWorkflow workflow) {
            this(login, workflow, false);
        }

        TestOnlyController(OperatorLoginService login, DynamicWorkflow workflow, boolean enabled) {
            this.login = login;
            this.workflow = workflow;
            this.enabled = enabled;
        }

        private void requireEnabled() {
            if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PILOT-03 is disabled.");
        }

        @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Map<String, Object>> preview(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpSession session,
                @RequestHeader(value = "X-CSRF-Token", required = false) String csrf,
                @RequestBody CaseInput input) {
            requireEnabled();
            String owner = login.requireAuthenticated(session);
            login.requireCsrf(request, session, csrf);
            Preview preview = workflow.preview(owner, input.processCode(), input.replacementText());
            if ("NO_OP".equals(preview.status())) return noStore(Map.of("status", "NO_OP", "beforeText", preview.beforeText()));
            @SuppressWarnings("unchecked")
            Map<String, Preview> plans = (Map<String, Preview>) session.getAttribute(PREVIEWS);
            if (plans == null) plans = new LinkedHashMap<>();
            plans.put(preview.previewId(), preview);
            session.setAttribute(PREVIEWS, plans);
            session.removeAttribute(GENERATED);
            return noStore(Map.of("previewId", preview.previewId(), "status", preview.status(),
                    "processCode", preview.config().processCode(), "sheetName", preview.config().sheetName(),
                    "managementNo", preview.config().managementNo(), "cellAddress", preview.config().cellAddress(),
                    "sourceSha256", preview.sourceSha256(), "beforeText", preview.beforeText(),
                    "replacementText", preview.replacementText(), "previewFingerprint", preview.fingerprint()));
        }

        @PostMapping(path = "/generate/{previewId}", consumes = MediaType.ALL_VALUE)
        ResponseEntity<Map<String, Object>> generate(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpSession session,
                @RequestHeader(value = "X-CSRF-Token", required = false) String csrf,
                @PathVariable String previewId) {
            requireEnabled();
            String owner = login.requireAuthenticated(session);
            login.requireCsrf(request, session, csrf);
            @SuppressWarnings("unchecked")
            Map<String, Preview> plans = (Map<String, Preview>) session.getAttribute(PREVIEWS);
            Preview plan = plans == null ? null : plans.get(previewId);
            if (plan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview is not in this session.");
            Generated generated = workflow.generate(owner, plan);
            @SuppressWarnings("unchecked")
            Map<String, Generated> outputs = (Map<String, Generated>) session.getAttribute(GENERATED);
            if (outputs == null) outputs = new LinkedHashMap<>();
            outputs.put(previewId, generated);
            session.setAttribute(GENERATED, outputs);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("previewId", previewId); result.put("filename", generated.fileName());
            result.put("sourceSha256", generated.sourceSha256()); result.put("outputSha256", generated.outputSha256());
            result.put("replacementText", generated.replacementText()); result.put("mode", "TEST_ONLY");
            result.put("officialBaselineModified", false);
            return noStore(result);
        }

        @GetMapping("/download/{previewId}")
        ResponseEntity<byte[]> download(jakarta.servlet.http.HttpSession session, @PathVariable String previewId) {
            requireEnabled();
            String owner = login.requireAuthenticated(session);
            @SuppressWarnings("unchecked")
            Map<String, Generated> outputs = (Map<String, Generated>) session.getAttribute(GENERATED);
            Generated generated = outputs == null ? null : outputs.get(previewId);
            if (generated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No output in this session.");
            generated = workflow.download(previewId, owner);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + generated.fileName() + "\"")
                    .header("X-Output-SHA256", generated.outputSha256())
                    .header("X-Source-SHA256", generated.sourceSha256())
                    .header("X-Scenario-Status", "TEST_ONLY")
                    .header("X-Official-Baseline-Modified", "false")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(generated.bytes());
        }

        private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    }
}
