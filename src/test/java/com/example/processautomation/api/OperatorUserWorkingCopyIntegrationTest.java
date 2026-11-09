package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.material.UserWorkingCopyService;
import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.example.processautomation.excel.EquipmentBoxProfile;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorUserWorkingCopyIntegrationTest {
    private static final String USER = "step78-owner";
    private static final String PASSWORD = "Step78-test-" + UUID.randomUUID() + "-Passphrase!";
    private static final String PASSWORD_HASH = OperatorLoginService.hashForTests(PASSWORD,
            "step78-owner-salt-16".getBytes(StandardCharsets.UTF_8), 210_000);

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("DB_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("DB_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("DB_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.target", () -> "25");
        registry.add("app.user-working-copies.enabled", () -> "true");
        registry.add("app.operator.username", () -> USER);
        registry.add("app.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserWorkingCopyService workingCopies;
    @Autowired private OoxmlPackageInspector inspector;
    @Autowired private ProcessDiagramStructureAnalyzer structureAnalyzer;
    @MockitoBean private EquipmentBoxProfileRepository profiles;

    @Test
    void profileBoundSyntheticAddRunsThroughRegistrationPreviewGenerateAndVerifiedDownload() throws Exception {
        String sheetName = "P-02-01(TOP)";
        byte[] officialBytes = layoutWorkbook(sheetName, false);
        byte[] workingBytes = layoutWorkbook(sheetName, true);
        String sourceSha = sha256(officialBytes);
        String workingSha = sha256(workingBytes);
        EquipmentBoxProfile profile = syntheticProfile(sheetName, sourceSha, workingSha);
        when(profiles.findFor("P-02", sheetName, sourceSha)).thenReturn(java.util.Optional.of(profile));

        long releaseId = ensureSyntheticBaselineRelease("STEP 7.8 isolated synthetic Profile baseline", "fixture master");
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "P-02", "P-02_SYNTHETIC_BASELINE_PROFILE.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes.length, sourceSha, officialBytes);
        long baselineId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Login owner = login();

        MvcResult registered = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "P-02_USER_SYNTHETIC.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workingBytes))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.sheets[0].profileId").value("SYNTHETIC-API-P02"))
                .andExpect(jsonPath("$.sheets[0].profileStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.sheets[0].profileEvidenceLevel").value("SYNTHETIC_TESTED"))
                .andExpect(jsonPath("$.sheets[0].profileShaMatch").value(true))
                .andExpect(jsonPath("$.sheets[0].workingCopyShaMatch").value(true))
                .andExpect(jsonPath("$.sheets[0].profileFields").isArray())
                .andExpect(jsonPath("$.sheets[0].availableFields").isArray()).andReturn();
        JsonNode registration = mapper.readTree(registered.getResponse().getContentAsByteArray());
        long workingId = registration.path("workingDocumentId").asLong();
        assertThat(registration.path("version").asInt()).isEqualTo(1);
        assertThat(registration.path("sourceSha256").asText()).isEqualTo(sourceSha);
        assertThat(registration.path("workingSha256").asText()).isEqualTo(workingSha);

        mvc.perform(get("/api/workspace/diagram-editor/working-copies")
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].workingDocumentId").value(workingId))
                .andExpect(jsonPath("$[0].sheets[0].profileStatus").value("VERIFIED"));
        mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}", workingId).session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workingCopyKind").value("USER_WORKING_COPY"))
                .andExpect(jsonPath("$.workingSha256").value(workingSha));
        mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}/sheets", workingId).session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sheetName").value(sheetName));

        var structure = structureAnalyzer.analyzeSheet("P-02", "P-02_USER_SYNTHETIC.xlsx", workingSha, workingBytes, sheetName);
        assertThat(structure.equipmentCandidates()).as("synthetic equipment candidates from the existing analyzer").hasSize(2)
                .allSatisfy(candidate -> assertThat(candidate.topLeftCell()).isNotNull());
        MvcResult locationsResponse = mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}/locations", workingId)
                        .param("sheetName", sheetName).session(owner.session()))
                .andExpect(status().isOk()).andReturn();
        JsonNode locations = mapper.readTree(locationsResponse.getResponse().getContentAsByteArray());
        JsonNode location = null;
        for (JsonNode candidate : locations) if ("VERIFIED".equals(candidate.path("editabilityStatus").asText())) location = candidate;
        assertThat(location).as("the exact-SHA synthetic Profile must expose a verified insertion location; candidates=%s", locations).isNotNull();
        String locationId = location.path("locationId").asText();

        Map<String, Object> previewBody = Map.ofEntries(Map.entry("workingDocumentId", workingId),
                Map.entry("locationId", locationId), Map.entry("sheetName", sheetName),
                Map.entry("managementNo", "SYNTH-ADD-01"), Map.entry("equipmentName", "Synthetic ADD tank"),
                Map.entry("businessItemNo", "SYNTH-ITEM-01"), Map.entry("capacity", "125"),
                Map.entry("capacityUnit", "m3/h"), Map.entry("quantity", "1ea"),
                Map.entry("equipmentTypeLabel", "Storage"), Map.entry("note", ""));
        MvcResult previewResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/preview").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(previewBody)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.automaticEditAllowed").value(true))
                .andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.physicalConnectionVerified").value(false)).andReturn();
        String previewId = mapper.readTree(previewResponse.getResponse().getContentAsByteArray()).path("previewId").asText();
        MvcResult generatedResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/generate").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.connectionEvidenceStatus").value("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"))
                .andExpect(jsonPath("$.officialBaselineModified").value(false)).andExpect(jsonPath("$.testOnlyOutputId").isNumber()).andReturn();
        JsonNode generated = mapper.readTree(generatedResponse.getResponse().getContentAsByteArray());
        long outputId = generated.path("testOnlyOutputId").asLong();

        MockHttpSession stranger = new MockHttpSession();
        stranger.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "step78-other-user");
        mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", outputId).session(stranger))
                .andExpect(status().isNotFound());
        MvcResult download = mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", outputId)
                        .session(owner.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        byte[] downloaded = download.getResponse().getContentAsByteArray();
        String outputSha = sha256(downloaded);
        assertThat(outputSha).isEqualTo(generated.path("outputSha256").asText());
        assertThat(download.getResponse().getHeader("X-Output-SHA256")).isEqualTo(outputSha);
        assertThat(download.getResponse().getHeader("X-Official-Baseline-Modified")).isEqualTo("false");
        assertThat(download.getResponse().getHeader("X-Connection-Evidence"))
                .isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        writeSyntheticArtifacts(officialBytes, workingBytes, downloaded, sourceSha, workingSha, outputSha);
        var inputInventory = inspector.inspect(workingBytes);
        var outputInventory = inspector.inspect(downloaded);
        assertThat(outputInventory.shapeCount()).isEqualTo(inputInventory.shapeCount());
        assertThat(outputInventory.connectorCount()).isEqualTo(inputInventory.connectorCount());
        assertThat(outputInventory.sheets().get(0).drawingObjects().stream().map(item -> item.objectName()).toList())
                .containsExactlyElementsOf(inputInventory.sheets().get(0).drawingObjects().stream().map(item -> item.objectName()).toList());
        try (var before = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(workingBytes));
             var after = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(downloaded))) {
            var sheet = after.getSheet(sheetName);
            var formatter = new DataFormatter();
            assertThat(cellCount(after, "SYNTH-ADD-01")).isEqualTo(1);
            assertThat(cellCount(after, "SYNTH-ITEM-01")).isEqualTo(1);
            assertThat(cellCount(after, "Synthetic ADD tank")).isEqualTo(1);
            assertThat(cellCount(after, "synthetic user working copy delta")).isEqualTo(1);
            assertThat(after.getPrintArea(after.getSheetIndex(sheetName))).isEqualTo("'P-02-01(TOP)'!A1:H50");
            assertThat(after.getPrintArea(after.getSheetIndex(sheetName))).contains(before.getSheet(sheetName).getSheetName());
            assertThat(sheet.getNumMergedRegions()).isEqualTo(before.getSheet(sheetName).getNumMergedRegions());
        }
        assertThat(sha256((byte[]) jdbc.queryForObject("SELECT content_bytes FROM baseline_document WHERE id=?", Object.class, baselineId))).isEqualTo(sourceSha);
        assertThat(sha256((byte[]) jdbc.queryForObject("SELECT content_bytes FROM process_working_document WHERE id=?", Object.class, workingId))).isEqualTo(workingSha);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE id=? AND output_sha256=?", Integer.class,
                outputId, outputSha)).isEqualTo(1);
        TestTransaction.flagForRollback();
    }

    @Test
    void staleAndMissingProfileShaRemainReviewOrUnsupportedAndCannotPreview() throws Exception {
        String sheetName = "P-02-01(TOP)";
        byte[] officialBytes = layoutWorkbook(sheetName, false);
        byte[] workingBytes = layoutWorkbook(sheetName, true);
        String sourceSha = sha256(officialBytes);
        String workingSha = sha256(workingBytes);
        when(profiles.findFor("P-02", sheetName, sourceSha)).thenReturn(java.util.Optional.of(
                syntheticProfile(sheetName, sourceSha, "0".repeat(64))));

        long releaseId = ensureSyntheticBaselineRelease("STEP 7.8 isolated negative Profile fixture", "negative fixture master");
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "P-02", "P-02_SYNTHETIC_NEGATIVE_BASELINE.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes.length, sourceSha, officialBytes);
        long baselineId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Login owner = login();
        MvcResult registrationResponse = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "P-02_SYNTHETIC_STALE_PROFILE.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workingBytes))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.sheets[0].profileStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.sheets[0].profileShaMatch").value(false))
                .andExpect(jsonPath("$.sheets[0].workingCopyShaMatch").value(false)).andReturn();
        long workingId = mapper.readTree(registrationResponse.getResponse().getContentAsByteArray())
                .path("workingDocumentId").asLong();
        assertThat(mapper.readTree(registrationResponse.getResponse().getContentAsByteArray())
                .path("workingSha256").asText()).isEqualTo(workingSha);

        MvcResult staleLocationsResponse = mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}/locations", workingId)
                        .param("sheetName", sheetName).session(owner.session()))
                .andExpect(status().isOk()).andReturn();
        JsonNode staleLocations = mapper.readTree(staleLocationsResponse.getResponse().getContentAsByteArray());
        assertThat(staleLocations).isNotEmpty();
        assertThat(staleLocations).allSatisfy(item -> assertThat(item.path("editabilityStatus").asText())
                .isIn("REVIEW_REQUIRED", "UNSUPPORTED"));
        JsonNode staleLocation = staleLocations.get(0);
        Map<String, Object> previewBody = new java.util.LinkedHashMap<>(Map.ofEntries(Map.entry("workingDocumentId", workingId),
                Map.entry("locationId", staleLocation.path("locationId").asText()), Map.entry("sheetName", sheetName),
                Map.entry("managementNo", "SYNTH-NEGATIVE-01"), Map.entry("equipmentName", "Synthetic blocked tank"),
                Map.entry("businessItemNo", "SYNTH-NEGATIVE-ITEM"), Map.entry("capacity", "1"),
                Map.entry("capacityUnit", "m3/h"), Map.entry("quantity", "1ea"), Map.entry("equipmentTypeLabel", "Storage")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workspace/diagram-editor/preview")
                        .session(owner.session()).header("X-CSRF-Token", owner.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(previewBody)))
                .andExpect(status().isConflict());

        when(profiles.findFor("P-02", sheetName, sourceSha)).thenReturn(java.util.Optional.empty());
        MvcResult missingProfileResponse = mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}/locations", workingId)
                        .param("sheetName", sheetName).session(owner.session()))
                .andExpect(status().isOk()).andReturn();
        JsonNode missingProfileLocations = mapper.readTree(missingProfileResponse.getResponse().getContentAsByteArray());
        assertThat(missingProfileLocations).isNotEmpty().allSatisfy(item ->
                assertThat(item.path("editabilityStatus").asText()).isEqualTo("UNSUPPORTED"));
        previewBody.put("locationId", missingProfileLocations.get(0).path("locationId").asText());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workspace/diagram-editor/preview")
                        .session(owner.session()).header("X-CSRF-Token", owner.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(previewBody)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE working_document_id=?", Integer.class,
                workingId)).isZero();
        TestTransaction.flagForRollback();
    }

    @Test
    void registrationVersioningOwnershipCsrfAndAppendOnlyOutputUseSyntheticDataOnly() throws Exception {
        byte[] officialBytes = workbook("SYNTH-P02", "synthetic baseline");
        byte[] firstUpload = workbook("SYNTH-P02", "synthetic working copy v1");
        byte[] secondUpload = workbook("SYNTH-P02", "synthetic working copy v2");
        String officialSha = sha256(officialBytes);
        long releaseId = ensureSyntheticBaselineRelease("STEP 7.8 isolated synthetic fixture", "synthetic master");
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "P-02", "P-02_SYNTHETIC_REGISTRATION_BASELINE.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes.length, officialSha, officialBytes);
        long baselineId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Login owner = login();
        mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "copy.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", firstUpload))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session()))
                .andExpect(status().isForbidden());

        MvcResult firstResponse = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "copy.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", firstUpload))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.sheets[0].profileStatus").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.idempotent").value(false)).andReturn();
        JsonNode first = mapper.readTree(firstResponse.getResponse().getContentAsByteArray());
        long firstId = first.path("workingDocumentId").asLong();
        assertThat(first.path("workingSha256").asText()).isEqualTo(sha256(firstUpload));

        MvcResult duplicateResponse = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "same-content.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", firstUpload))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent").value(true)).andReturn();
        assertThat(mapper.readTree(duplicateResponse.getResponse().getContentAsByteArray()).path("workingDocumentId").asLong()).isEqualTo(firstId);

        MvcResult secondResponse = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "copy-v2.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", secondUpload))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.previousWorkingCopyId").value(firstId)).andReturn();
        long secondId = mapper.readTree(secondResponse.getResponse().getContentAsByteArray()).path("workingDocumentId").asLong();

        mvc.perform(get("/api/workspace/diagram-editor/working-copies")
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].workingDocumentId").value(secondId))
                .andExpect(jsonPath("$[1].workingDocumentId").value(firstId));
        mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "official.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isConflict());
        mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "fake.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not-xlsx".getBytes(StandardCharsets.UTF_8)))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "fake.xls", "application/vnd.ms-excel", firstUpload))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isBadRequest());

        MockHttpSession stranger = new MockHttpSession();
        stranger.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "step78-stranger");
        mvc.perform(get("/api/workspace/diagram-editor/working-copies/{id}", secondId).session(stranger))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT sha256 FROM baseline_document WHERE id=?", String.class, baselineId)).isEqualTo(officialSha);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_working_document WHERE working_copy_kind='USER_WORKING_COPY' AND registered_by=? AND baseline_document_id=?", Integer.class, USER, baselineId)).isEqualTo(2);

        byte[] outputBytes = workbook("SYNTH-ADD", "output");
        int existingOutputCount = jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output", Integer.class);
        UserWorkingCopyService.StoredOutput saved = workingCopies.saveTestOnlyOutput(USER, secondId,
                sha256(secondUpload), sha256(outputBytes), "ADD", "SYNTHETIC_ADD_TEST_ONLY.xlsx", outputBytes);
        assertThat(saved.artifactKind()).isEqualTo("TEST_ONLY");
        MvcResult download = mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", saved.outputId())
                        .session(owner.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        assertThat(sha256(download.getResponse().getContentAsByteArray())).isEqualTo(saved.outputSha256());
        assertThatThrownBy(() -> jdbc.update("UPDATE process_test_only_output SET review_status='UNSUPPORTED' WHERE id=?", saved.outputId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM process_test_only_output WHERE id=?", saved.outputId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE id=? AND output_sha256=?",
                Integer.class, saved.outputId(), saved.outputSha256())).isEqualTo(1);
        mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", saved.outputId()).session(stranger))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output", Integer.class)).isEqualTo(existingOutputCount + 1);
        TestTransaction.flagForRollback();
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void profileModifyStoresOutputAndAppendsProposalHistoryForTheOwnerOnly() throws Exception {
        String sheetName = "P-02-01(TOP)";
        byte[] officialBytes = layoutWorkbook(sheetName, false);
        byte[] workingBytes = layoutWorkbook(sheetName, true);
        String sourceSha = sha256(officialBytes);
        String workingSha = sha256(workingBytes);
        when(profiles.findFor("P-02", sheetName, sourceSha))
                .thenReturn(java.util.Optional.of(syntheticProfile(sheetName, sourceSha, workingSha)));

        long releaseId = ensureSyntheticBaselineRelease("PILOT-05 isolated synthetic MODIFY baseline", "pilot05 synthetic master");
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "P-02", "PILOT05_SYNTHETIC_BASELINE.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes.length,
                sourceSha, officialBytes);
        long baselineId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Login owner = login();

        MvcResult registrationResponse = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", "PILOT05_SYNTHETIC_USER_COPY.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workingBytes))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.sourceSha256").value(sourceSha))
                .andExpect(jsonPath("$.workingSha256").value(workingSha)).andReturn();
        long workingId = mapper.readTree(registrationResponse.getResponse().getContentAsByteArray())
                .path("workingDocumentId").asLong();

        Map<String, Object> modifyRequest = Map.of("workingDocumentId", workingId, "sheetName", sheetName,
                "managementNo", "SYN-02", "replacements", Map.of("equipmentName", "SYNTHETIC_TEST_ONLY PILOT05 name"),
                "expectedCurrentValues", Map.of("equipmentName", "Synthetic tank"));
        MvcResult previewResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/modify/preview").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(modifyRequest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileBased").value(true))
                .andExpect(jsonPath("$.status").value("TEST_ONLY_READY"))
                .andExpect(jsonPath("$.profileShaMatch").value(true))
                .andExpect(jsonPath("$.workingCopyShaMatch").value(true))
                .andExpect(jsonPath("$.beforeValues.equipmentName").value("Synthetic tank"))
                .andExpect(jsonPath("$.afterValues.equipmentName").value("SYNTHETIC_TEST_ONLY PILOT05 name"))
                .andExpect(jsonPath("$.proposalId").isNotEmpty()).andReturn();
        JsonNode preview = mapper.readTree(previewResponse.getResponse().getContentAsByteArray());
        String previewId = preview.path("previewId").asText();
        String proposalId = preview.path("proposalId").asText();
        assertThat(previewId).isNotBlank();
        assertThat(proposalId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE proposal_id=?",
                Integer.class, proposalId)).isEqualTo(1);

        byte[] generateBody = mapper.writeValueAsBytes(Map.of("previewId", previewId));
        jdbc.execute("""
                CREATE TRIGGER pilot05_fail_test_only_output_event
                BEFORE INSERT ON process_change_proposal_event
                FOR EACH ROW
                BEGIN
                    IF NEW.event_type = 'TEST_ONLY_OUTPUT' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PILOT-05 synthetic persistence failure';
                    END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/workspace/diagram-editor/modify/generate").session(owner.session())
                            .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(generateBody)))
                    .isInstanceOf(jakarta.servlet.ServletException.class)
                    .hasMessageContaining("PILOT-05 synthetic persistence failure");
        } finally {
            jdbc.execute("DROP TRIGGER pilot05_fail_test_only_output_event");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE working_document_id=?",
                Integer.class, workingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE proposal_id=?",
                Integer.class, proposalId)).isEqualTo(1);

        MvcResult generateResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/modify/generate").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposalId").value(proposalId))
                .andExpect(jsonPath("$.testOnlyOutputId").isNumber())
                .andExpect(jsonPath("$.officialBaselineModified").value(false))
                .andExpect(jsonPath("$.mode").value("TEST_ONLY")).andReturn();
        JsonNode generated = mapper.readTree(generateResponse.getResponse().getContentAsByteArray());
        long outputId = generated.path("testOnlyOutputId").asLong();
        String outputSha = generated.path("outputSha256").asText();
        assertThat(outputId).isPositive();
        assertThat(outputSha).isEqualTo(jdbc.queryForObject(
                "SELECT output_sha256 FROM process_test_only_output WHERE id=? AND registered_by=?",
                String.class, outputId, USER));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/modify/generate").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE working_document_id=?",
                Integer.class, workingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE proposal_id=?",
                Integer.class, proposalId)).isEqualTo(2);

        MvcResult proposalDetail = mvc.perform(get("/api/workspace/change-proposals/{proposalId}", proposalId)
                        .session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestEventType").value("TEST_ONLY_OUTPUT"))
                .andExpect(jsonPath("$.testOnlyExcelCreated").value(true))
                .andExpect(jsonPath("$.outputSha256").value(outputSha))
                .andExpect(jsonPath("$.events.length()").value(2)).andReturn();
        JsonNode proposal = mapper.readTree(proposalDetail.getResponse().getContentAsByteArray());
        assertThat(proposal.path("events").get(0).path("eventType").asText()).isEqualTo("PROPOSED");
        assertThat(proposal.path("events").get(1).path("eventType").asText()).isEqualTo("TEST_ONLY_OUTPUT");
        assertThat(proposal.path("events").get(1).path("validationResults").path("testOnlyOutputId").asLong())
                .isEqualTo(outputId);
        assertThat(proposal.path("calculationStatus").asText()).isEqualTo("CALCULATION_UNAVAILABLE");
        assertThat(proposal.path("reviewStatus").asText()).isEqualTo("REVIEW_REQUIRED");

        MockHttpSession stranger = new MockHttpSession();
        stranger.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "pilot05-other-owner");
        mvc.perform(get("/api/workspace/change-proposals/{proposalId}", proposalId).session(stranger))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", outputId).session(stranger))
                .andExpect(status().isNotFound());
        MvcResult savedDownload = mvc.perform(get("/api/workspace/diagram-editor/test-only-outputs/{id}/download", outputId)
                        .session(owner.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        MvcResult modifyDownload = mvc.perform(get("/api/workspace/diagram-editor/modify/download/{previewId}", previewId)
                        .session(owner.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Output-SHA256", outputSha)).andReturn();
        byte[] downloaded = savedDownload.getResponse().getContentAsByteArray();
        assertThat(sha256(downloaded)).isEqualTo(outputSha);
        assertThat(modifyDownload.getResponse().getContentAsByteArray()).containsExactly(downloaded);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM baseline_document WHERE id=?", String.class, baselineId))
                .isEqualTo(sourceSha);
        assertThat(sha256((byte[]) jdbc.queryForObject(
                "SELECT content_bytes FROM process_working_document WHERE id=?", Object.class, workingId)))
                .isEqualTo(workingSha);
        assertThat(jdbc.queryForObject("SELECT output_sha256 FROM process_test_only_output WHERE id=?",
                String.class, outputId)).isEqualTo(outputSha);
        verifyModifyWorkbook(workingBytes, downloaded, sheetName, "B14", "SYNTHETIC_TEST_ONLY PILOT05 name");
        writePilot05ModifyArtifacts(officialBytes, workingBytes, downloaded, sourceSha, workingSha,
                outputSha, workingId, outputId, proposalId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_working_document WHERE id=? AND registered_by=?",
                Integer.class, workingId, USER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE id=? AND output_sha256=?",
                Integer.class, outputId, outputSha)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE proposal_id=?",
                Integer.class, proposalId)).isEqualTo(2);
    }

    @Test
    @EnabledIfSystemProperty(named = "pilot05.actual-working-copy", matches = ".+")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void exactCataloguedP02WorkingCopyBlocksUnverifiedFieldsWithoutWrites() throws Exception {
        Path workingPath = Path.of(System.getProperty("pilot05.actual-working-copy")).toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(workingPath)).isTrue();
        byte[] workingBytes = Files.readAllBytes(workingPath);
        String workingSha = sha256(workingBytes);
        assertThat(workingSha).isEqualToIgnoringCase(
                "c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2");

        Path sourcePath;
        try (var paths = Files.list(Path.of("data/reference"))) {
            sourcePath = paths.filter(path -> path.getFileName().toString().startsWith("P-02 ")
                            && path.getFileName().toString().endsWith(".xlsx"))
                    .findFirst().orElseThrow();
        }
        String sourceFilename = "P02_PROFILE_VERIFIED_SOURCE.xlsx";
        byte[] officialBytes = Files.readAllBytes(sourcePath);
        String sourceSha = sha256(officialBytes);
        assertThat(sourceSha).isEqualToIgnoringCase(
                "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76");

        String sheetName = "P-02-01(TOP)";
        var catalogProfile = new com.example.processautomation.material.EquipmentBoxProfileRepository(mapper)
                .findFor("P-02", sheetName, sourceSha).orElseThrow();
        assertThat(catalogProfile.profileId()).isEqualTo("P-02-01-TOP-V1");
        assertThat(catalogProfile.status()).isEqualTo("VERIFIED");
        assertThat(catalogProfile.evidenceLevel()).isEqualTo("WORKING_COPY_VERIFIED");
        assertThat(catalogProfile.matchesWorkingCopySha(workingSha)).isTrue();
        assertThat(catalogProfile.fields()).isNotEmpty();
        assertThat(catalogProfile.fields().get("equipmentName").expectedStyleId()).isEqualTo(220);
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(workingBytes))) {
            assertThat(workbook.getSheet(sheetName).getRow(88).getCell(8).getCellStyle().getIndex()).isEqualTo((short) 262);
            assertThat(workbook.getSheet(sheetName).getRow(583).getCell(8).getCellStyle().getIndex()).isEqualTo((short) 220);
        }
        when(profiles.findFor("P-02", sheetName, sourceSha)).thenReturn(java.util.Optional.of(catalogProfile));

        long releaseId = ensureSyntheticBaselineRelease("PILOT-05 isolated copy of locked P-02 source", "pilot05 real-source master hash seed");
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", "P-02", sourceFilename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", officialBytes.length,
                sourceSha, officialBytes);
        long baselineId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Login owner = login();

        MvcResult registration = mvc.perform(multipart("/api/workspace/diagram-editor/working-copies")
                        .file(new MockMultipartFile("file", workingPath.getFileName().toString(),
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workingBytes))
                        .param("baselineDocumentId", String.valueOf(baselineId)).session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.sourceSha256").value(sourceSha))
                .andExpect(jsonPath("$.workingSha256").value(workingSha))
                .andExpect(jsonPath("$.sheets[?(@.sheetName == 'P-02-01(TOP)')].profileId").value(
                        org.hamcrest.Matchers.contains("P-02-01-TOP-V1"))).andReturn();
        long workingId = mapper.readTree(registration.getResponse().getContentAsByteArray())
                .path("workingDocumentId").asLong();
        MvcResult inspection = mvc.perform(get("/api/workspace/diagram-editor/modify/equipment")
                        .session(owner.session()).param("workingDocumentId", String.valueOf(workingId))
                        .param("sheetName", sheetName).param("managementNo", "I-PP21005"))
                .andExpect(status().isOk()).andReturn();
        JsonNode inspectionJson = mapper.readTree(inspection.getResponse().getContentAsByteArray());
        Map<String, Object> diagnostic = Map.ofEntries(
                Map.entry("workingDocumentId", workingId), Map.entry("sourceSha256", sourceSha),
                Map.entry("workingSha256", workingSha), Map.entry("profileId", catalogProfile.profileId()),
                Map.entry("profileStatus", catalogProfile.status()),
                Map.entry("profileEvidenceLevel", catalogProfile.evidenceLevel()),
                Map.entry("catalogProfileFields", catalogProfile.fields().keySet()),
                Map.entry("profileShaMatch", inspectionJson.path("mapping").path("profileShaMatch").asBoolean()),
                Map.entry("workingCopyShaMatch", inspectionJson.path("mapping").path("workingCopyShaMatch").asBoolean()),
                Map.entry("boxTopLeft", inspectionJson.path("mapping").path("boxTopLeft").asText()),
                Map.entry("boxBottomRight", inspectionJson.path("mapping").path("boxBottomRight").asText()),
                Map.entry("availableFields", mapper.convertValue(inspectionJson.path("mapping").path("availableFields"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })),
                Map.entry("equipmentNameMapping", inspectionJson.path("mapping").path("fields").path("equipmentName")));
        System.out.println("PILOT05_ACTUAL_PROFILE_DIAGNOSTIC=" + mapper.writeValueAsString(diagnostic));
        assertThat(inspectionJson.path("mapping").path("fields").path("equipmentName").path("cellAddress").asText())
                .isEqualTo("I89");
        assertThat(inspectionJson.path("mapping").path("fields").path("equipmentName").path("status").asText())
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(inspectionJson.path("mapping").path("fields").path("equipmentName").path("reason").asText())
                .isEqualTo("PROFILE_STYLE_MISMATCH");
        assertThat(inspectionJson.path("mapping").path("availableFields")).isEmpty();

        Map<String, Object> unprofiledTargetRequest = Map.of("workingDocumentId", workingId, "sheetName", sheetName,
                "managementNo", "I-PP21005", "replacements", Map.of("equipmentName",
                        "SYNTHETIC_TEST_ONLY PILOT05 P02 I89"), "expectedCurrentValues",
                Map.of("equipmentName", "131 MULTI SORTER#1"));
        MvcResult blockedI89 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/modify/preview").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(unprofiledTargetRequest))).andReturn();
        assertThat(blockedI89.getResponse().getStatus()).isEqualTo(400);
        assertThat(blockedI89.getResponse().getContentAsString()).contains("PROFILE_MODIFY_FIELD_SET_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE working_document_id=?",
                Integer.class, workingId)).isZero();

        MvcResult i584Inspection = mvc.perform(get("/api/workspace/diagram-editor/modify/equipment")
                        .session(owner.session()).param("workingDocumentId", String.valueOf(workingId))
                        .param("sheetName", sheetName).param("managementNo", "I-PP21039"))
                .andExpect(status().isOk()).andReturn();
        JsonNode i584InspectionJson = mapper.readTree(i584Inspection.getResponse().getContentAsByteArray());
        JsonNode i584FieldMapping = i584InspectionJson.path("mapping").path("fields").path("equipmentName");
        assertThat(i584InspectionJson.path("mapping").path("profileShaMatch").asBoolean()).isTrue();
        assertThat(i584InspectionJson.path("mapping").path("workingCopyShaMatch").asBoolean()).isTrue();
        assertThat(i584FieldMapping.path("cellAddress").asText()).isEqualTo("I584");
        assertThat(i584FieldMapping.path("currentText").asText()).isEqualTo("WW-L-2 CHEST(백수탱크)");
        assertThat(i584FieldMapping.path("expectedStyleId").asInt()).isEqualTo(220);
        assertThat(i584FieldMapping.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(i584FieldMapping.path("reason").asText()).isEqualTo("DRAWINGML_FIELD_OVERLAP");
        var i584OverlappingObjects = inspector.inspectSheet(workingBytes, sheetName).drawingObjects().stream()
                .filter(drawing -> drawing.startRow() != null && drawing.endRow() != null
                        && drawing.startColumn() != null && drawing.endColumn() != null
                        && 583 >= Math.min(drawing.startRow(), drawing.endRow())
                        && 583 <= Math.max(drawing.startRow(), drawing.endRow())
                        && 8 >= Math.min(drawing.startColumn(), drawing.endColumn())
                        && 8 <= Math.max(drawing.startColumn(), drawing.endColumn()))
                .toList();
        assertThat(i584OverlappingObjects).isNotEmpty();

        Map<String, Object> modifyRequest = Map.of("workingDocumentId", workingId, "sheetName", sheetName,
                "managementNo", "I-PP21039", "replacements", Map.of("equipmentName",
                        "SYNTHETIC_TEST_ONLY PILOT05 P02 I584"), "expectedCurrentValues",
                Map.of("equipmentName", "WW-L-2 CHEST(백수탱크)"));
        MvcResult blockedI584 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspace/diagram-editor/modify/preview").session(owner.session())
                        .header("X-CSRF-Token", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(modifyRequest))).andReturn();
        assertThat(blockedI584.getResponse().getStatus()).isEqualTo(400);
        String responseBody = blockedI584.getResponse().getContentAsString();
        assertThat(responseBody).contains("PROFILE_FIELD_NOT_EDITABLE", "DRAWINGML_FIELD_OVERLAP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_proposal_event WHERE working_document_id=?",
                Integer.class, workingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_test_only_output WHERE working_document_id=?",
                Integer.class, workingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT sha256 FROM baseline_document WHERE id=?", String.class, baselineId))
                .isEqualTo(sourceSha);
        assertThat(sha256((byte[]) jdbc.queryForObject(
                "SELECT content_bytes FROM process_working_document WHERE id=?", Object.class, workingId)))
                .isEqualTo(workingSha);
        assertThat(sha256(Files.readAllBytes(workingPath))).isEqualTo(workingSha);

        String configuredDirectory = System.getProperty("pilot05.actual-artifact-dir");
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            Path directory = Path.of(configuredDirectory).toAbsolutePath();
            Files.createDirectories(directory);
            Map<String, Object> report = Map.ofEntries(
                    Map.entry("classification", "PROFILE_WORKBOOK_DRAWINGML_OVERLAP_SAFE_BLOCK"),
                    Map.entry("processCode", "P-02"), Map.entry("sheetName", sheetName),
                    Map.entry("sourceSha256", sourceSha), Map.entry("workingSha256", workingSha),
                    Map.entry("profileId", catalogProfile.profileId()), Map.entry("profileShaMatch", true),
                    Map.entry("workingCopyShaMatch", true), Map.entry("i89Mapping", diagnostic.get("equipmentNameMapping")),
                    Map.entry("i89PreviewStatus", 400), Map.entry("i89ErrorCode", "PROFILE_MODIFY_FIELD_SET_INVALID"),
                    Map.entry("i584ManagementNo", "I-PP21039"), Map.entry("i584Mapping", i584FieldMapping),
                    Map.entry("i584AvailableFields", i584InspectionJson.path("mapping").path("availableFields")),
                    Map.entry("i584OverlappingDrawingObjects", i584OverlappingObjects),
                    Map.entry("i584Request", modifyRequest), Map.entry("i584PreviewStatus", 400),
                    Map.entry("i584PreviewResponse", mapper.readTree(responseBody)),
                    Map.entry("proposalEventsCreated", 0), Map.entry("testOnlyOutputsCreated", 0),
                    Map.entry("officialBaselineModified", false), Map.entry("workingCopyModified", false),
                    Map.entry("decision", "SAFE_BLOCK; no Profile/style/DrawingML/auth guard bypass"));
            Path diagnosis = directory.resolve("P02_PREVIEW_400_DIAGNOSIS.json");
            Files.writeString(diagnosis, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(diagnosis,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Path sums = directory.resolve("SHA256SUMS");
            Files.writeString(sums, sha256(Files.readAllBytes(diagnosis)) + "  " + diagnosis.getFileName() + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(sums, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    private void verifyModifyWorkbook(byte[] beforeBytes, byte[] outputBytes, String sheetName,
                                      String targetCell, String expectedName) throws Exception {
        var beforeInventory = inspector.inspect(beforeBytes);
        var afterInventory = inspector.inspect(outputBytes);
        assertThat(afterInventory.shapeCount()).isEqualTo(beforeInventory.shapeCount());
        assertThat(afterInventory.connectorCount()).isEqualTo(beforeInventory.connectorCount());
        assertThat(afterInventory.sheets().get(0).drawingObjects())
                .containsExactlyElementsOf(beforeInventory.sheets().get(0).drawingObjects());
        int shapeCount = afterInventory.shapeCount();
        int connectorCount = afterInventory.connectorCount();
        var beforeParts = workbookParts(beforeBytes);
        var afterParts = workbookParts(outputBytes);
        assertThat(afterParts.keySet()).containsExactlyInAnyOrderElementsOf(beforeParts.keySet());
        List<String> changedParts = beforeParts.keySet().stream()
                .filter(name -> !java.util.Arrays.equals(beforeParts.get(name), afterParts.get(name))).toList();
        assertThat(changedParts).containsExactly("xl/worksheets/sheet1.xml");
        String targetPattern = "<c(?=[\\s>])(?=[^>]*\\br=\"" + targetCell
                + "\")[^>]*>.*?</c>";
        String beforeXml = new String(beforeParts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        String afterXml = new String(afterParts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        var targetMatcher = java.util.regex.Pattern.compile(targetPattern, java.util.regex.Pattern.DOTALL);
        assertThat(targetMatcher.matcher(beforeXml).find()).isTrue();
        assertThat(targetMatcher.matcher(afterXml).find()).isTrue();
        assertThat(afterXml.replaceAll(targetPattern, "<TARGET_CELL/>"))
                .isEqualTo(beforeXml.replaceAll(targetPattern, "<TARGET_CELL/>"));
        try (var before = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(beforeBytes));
             var after = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(outputBytes))) {
            int rowIndex = Integer.parseInt(targetCell.replaceAll("[^0-9]", "")) - 1;
            int columnIndex = org.apache.poi.ss.util.CellReference.convertColStringToIndex(
                    targetCell.replaceAll("[0-9]", ""));
            var beforeSheet = before.getSheet(sheetName);
            var afterSheet = after.getSheet(sheetName);
            assertThat(afterSheet.getRow(rowIndex).getCell(columnIndex).getStringCellValue()).isEqualTo(expectedName);
            assertThat(afterSheet.getRow(rowIndex).getCell(columnIndex).getCellStyle().getIndex())
                    .isEqualTo(beforeSheet.getRow(rowIndex).getCell(columnIndex).getCellStyle().getIndex());
            assertThat(afterSheet.getNumMergedRegions()).isEqualTo(beforeSheet.getNumMergedRegions());
            assertThat(after.getPrintArea(after.getSheetIndex(sheetName)))
                    .isEqualTo(before.getPrintArea(before.getSheetIndex(sheetName)));
        }
        assertThat(inspector.inspect(outputBytes).shapeCount()).isEqualTo(shapeCount);
        assertThat(inspector.inspect(outputBytes).connectorCount()).isEqualTo(connectorCount);
        assertThat(new com.example.processautomation.excel.OoxmlPartEditor()
                .readPart(outputBytes, "xl/drawings/drawing1.xml"))
                .containsExactly(new com.example.processautomation.excel.OoxmlPartEditor()
                        .readPart(beforeBytes, "xl/drawings/drawing1.xml"));
    }

    private Map<String, byte[]> workbookParts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new java.util.LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) parts.put(entry.getName(), zip.readAllBytes());
            }
        }
        return parts;
    }

    private void writePilot05ModifyArtifacts(byte[] baseline, byte[] working, byte[] output,
            String sourceSha, String workingSha, String outputSha, long workingId, long outputId,
            String proposalId) throws Exception {
        String configuredDirectory = System.getProperty("pilot05.modify.artifact-dir");
        if (configuredDirectory == null || configuredDirectory.isBlank()) return;
        var directory = java.nio.file.Path.of(configuredDirectory).toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        Map<String, byte[]> artifacts = Map.of("P05_SYNTHETIC_BASELINE.xlsx", baseline,
                "P05_SYNTHETIC_USER_WORKING_COPY.xlsx", working, "P05_SYNTHETIC_MODIFY_OUTPUT.xlsx", output);
        for (var artifact : artifacts.entrySet()) {
            var path = directory.resolve(artifact.getKey());
            java.nio.file.Files.write(path, artifact.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
            java.nio.file.Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        var manifest = directory.resolve("DB_MODIFY_RESULT.json");
        Map<String, Object> report = Map.ofEntries(Map.entry("classification", "SYNTHETIC_TEST_ONLY_NOT_PRODUCTION_PROFILE"),
                Map.entry("transactionDisposition", "COMMITTED_AND_REQUERIED"),
                Map.entry("workingDocumentId", workingId), Map.entry("testOnlyOutputId", outputId), Map.entry("proposalId", proposalId),
                Map.entry("sourceSha256", sourceSha), Map.entry("workingSha256", workingSha), Map.entry("outputSha256", outputSha),
                Map.entry("officialBaselineModified", false), Map.entry("profileStatus", "VERIFIED_SYNTHETIC_ONLY"),
                Map.entry("profileEvidenceLevel", "SYNTHETIC_TESTED"), Map.entry("reviewStatus", "REVIEW_REQUIRED"),
                Map.entry("calculationStatus", "CALCULATION_UNAVAILABLE"));
        java.nio.file.Files.writeString(manifest, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.setPosixFilePermissions(manifest, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    private long ensureSyntheticBaselineRelease(String description, String masterSeed) throws Exception {
        List<Long> releases = jdbc.query("SELECT id FROM baseline_release WHERE version_code='V1.0' AND locked=TRUE",
                (rs, rowNum) -> rs.getLong(1));
        if (!releases.isEmpty()) return releases.get(0);
        jdbc.update("INSERT INTO baseline_release(version_code,description,source_master_sha256,locked) VALUES(?,?,?,TRUE)",
                "V1.0", description, sha256(masterSeed.getBytes(StandardCharsets.UTF_8)));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Login login() throws Exception {
        MvcResult response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/session/login")
                        .header("X-Login-Request", "same-origin").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", USER, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new Login((MockHttpSession) response.getRequest().getSession(false),
                mapper.readTree(response.getResponse().getContentAsByteArray()).path("csrfToken").asText());
    }

    private void writeSyntheticArtifacts(byte[] baseline, byte[] working, byte[] output,
                                         String baselineSha, String workingSha, String outputSha) throws Exception {
        String configuredDirectory = System.getProperty("step78.synthetic-artifact-dir");
        if (configuredDirectory == null || configuredDirectory.isBlank()) return;
        var directory = java.nio.file.Path.of(configuredDirectory).toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        Map<String, byte[]> artifacts = Map.of(
                "SYNTHETIC_BASELINE_NOT_OFFICIAL.xlsx", baseline,
                "SYNTHETIC_USER_WORKING_COPY.xlsx", working,
                "SYNTHETIC_PROFILE_ADD_TEST_ONLY.xlsx", output);
        for (var artifact : artifacts.entrySet()) {
            var path = directory.resolve(artifact.getKey());
            if (java.nio.file.Files.exists(path)) throw new IllegalStateException("Refusing to overwrite TEST_ONLY artifact: " + path);
            java.nio.file.Files.write(path, artifact.getValue());
        }
        var manifest = directory.resolve("SHA256SUMS.tsv");
        if (java.nio.file.Files.exists(manifest)) throw new IllegalStateException("Refusing to overwrite TEST_ONLY SHA manifest: " + manifest);
        java.nio.file.Files.writeString(manifest, "artifact\tsha256\tbytes\tclassification\n"
                + "SYNTHETIC_BASELINE_NOT_OFFICIAL.xlsx\t" + baselineSha + "\t" + baseline.length + "\tSYNTHETIC_BASELINE_NOT_OFFICIAL\n"
                + "SYNTHETIC_USER_WORKING_COPY.xlsx\t" + workingSha + "\t" + working.length + "\tUSER_WORKING_COPY_SYNTHETIC\n"
                + "SYNTHETIC_PROFILE_ADD_TEST_ONLY.xlsx\t" + outputSha + "\t" + output.length + "\tTEST_ONLY_REVIEW_REQUIRED\n",
                StandardCharsets.UTF_8);
    }

    private int cellCount(org.apache.poi.ss.usermodel.Workbook workbook, String expected) {
        int count = 0;
        DataFormatter formatter = new DataFormatter();
        for (var sheet : workbook) for (var row : sheet) for (var cell : row)
            if (expected.equals(formatter.formatCellValue(cell))) count++;
        return count;
    }

    private byte[] layoutWorkbook(String sheetName, boolean userDelta) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            for (int column = 0; column < 8; column++) sheet.setColumnWidth(column, (10 + column) * 256);
            var font = workbook.createFont(); font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 8); font.setColor((short) 8);
            var body = workbook.createCellStyle(); body.setFont(font); body.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            body.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND); body.setBorderLeft(BorderStyle.THIN);
            body.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER); body.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var right = workbook.createCellStyle(); right.cloneStyleFrom(body); right.setBorderRight(BorderStyle.THIN);
            var bottom = workbook.createCellStyle(); bottom.cloneStyleFrom(body); bottom.setBorderBottom(BorderStyle.THIN);
            writeSyntheticBox(sheet, 0, "SYN-01", body, right, bottom, "SOURCE-ITEM");
            sheet.createRow(8).createCell(0).setCellStyle(body); sheet.createRow(9).createCell(0).setCellStyle(body);
            writeSyntheticBox(sheet, 10, "SYN-02", body, right, bottom, "TARGET-ITEM");
            sheet.createRow(30).createCell(0).setCellValue(userDelta ? "synthetic user working copy delta" : "synthetic baseline source");
            for (int offset = 0; offset < 8; offset++) { sheet.getRow(offset).setHeightInPoints(12f + offset); sheet.getRow(10 + offset).setHeightInPoints(12f + offset); }
            var drawing = sheet.createDrawingPatriarch();
            var anchor = new XSSFClientAnchor(0, 0, 0, 0, 6, 25, 8, 27);
            var note = drawing.createTextbox(anchor); note.setText(new org.apache.poi.xssf.usermodel.XSSFRichTextString("Synthetic annotation"));
            sheet.getPrintSetup().setLandscape(true); workbook.setPrintArea(0, "A1:H40"); workbook.write(output); return output.toByteArray();
        }
    }

    private void writeSyntheticBox(org.apache.poi.ss.usermodel.Sheet sheet, int top, String id,
                                   org.apache.poi.ss.usermodel.CellStyle body,
                                   org.apache.poi.ss.usermodel.CellStyle right,
                                   org.apache.poi.ss.usermodel.CellStyle bottom, String itemNo) {
        for (int offset = 0; offset < 8; offset++) {
            var row = sheet.createRow(top + offset);
            for (int column = 0; column < 6; column++) row.createCell(column).setCellStyle(body);
            row.getCell(5).setCellStyle(right);
            if (offset == 7) for (int column = 0; column < 6; column++) row.getCell(column).setCellStyle(bottom);
        }
        sheet.getRow(top).getCell(0).setCellValue(id);
        sheet.getRow(top + 2).getCell(0).setCellValue("Type label");
        sheet.getRow(top + 3).getCell(0).setCellValue("Equipment name label");
        sheet.getRow(top + 2).getCell(1).setCellValue("Filter");
        sheet.getRow(top + 3).getCell(1).setCellValue("Synthetic tank");
        sheet.getRow(top + 4).getCell(3).setCellValue(itemNo);
        sheet.getRow(top + 5).getCell(2).setCellValue("100 m3/h");
        sheet.getRow(top + 6).getCell(1).setCellValue("1ea");
    }

    private EquipmentBoxProfile syntheticProfile(String sheetName, String sourceSha, String workingSha) {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new java.util.LinkedHashMap<>();
        fields.put("managementNo", syntheticField(0, 0));
        fields.put("equipmentTypeLabel", syntheticField(2, 1));
        fields.put("equipmentName", syntheticField(3, 1));
        fields.put("businessItemNo", syntheticField(4, 3));
        fields.put("capacity", syntheticField(5, 2));
        fields.put("quantity", syntheticField(6, 1));
        Map<Integer, Float> heights = new java.util.LinkedHashMap<>();
        for (int offset = 0; offset < 8; offset++) heights.put(offset, 12f + offset);
        Map<String, Integer> styleIds = Map.of("managementNo", 1, "equipmentTypeLabel", 1, "equipmentName", 1,
                "businessItemNo", 1, "capacity", 1, "quantity", 1);
        return new EquipmentBoxProfile("SYNTHETIC-API-P02", "P-02", sheetName, "P-02_SYNTHETIC_BASELINE.xlsx",
                sourceSha, workingSha, "TEST-ONLY-1", "VERIFIED", "SYNTHETIC_TESTED",
                "Isolated synthetic fixture; never production evidence", "A1:F8", 8, fields, List.of(), styleIds,
                "맑은 고딕", (short) 8, "DEFAULT_BLACK", heights,
                Map.of(), "REVALIDATE_ANCHORS_ON_EACH_REQUEST", "PRESERVE_ONLY_NO_NEW_CONNECTOR",
                "BOX_ONLY_TEST_ONLY", "'" + sheetName + "'!$A$1:$H$40", "PRESERVE_AND_SHIFT_AFTER_INSERTION", null);
    }

    private EquipmentBoxProfile.FieldCell syntheticField(int rowOffset, int columnOffset) {
        return new EquipmentBoxProfile.FieldCell(rowOffset, columnOffset, null, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", true, "TEXT");
    }

    private byte[] workbook(String sheetName, String value) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            sheet.createRow(0).createCell(0).setCellValue(value);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing isolated test setting: " + key);
        return value;
    }

    private record Login(MockHttpSession session, String csrf) { }
}
