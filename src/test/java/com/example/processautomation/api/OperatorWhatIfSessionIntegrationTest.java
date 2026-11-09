package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.material.DirectedProcessNetwork;
import com.example.processautomation.material.NetworkWhatIfRequest;
import com.example.processautomation.material.NumericWorkingCopyService;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorWhatIfSessionIntegrationTest {
    private static final String TEST_USERNAME = "whatif-test";
    private static final String TEST_PASSWORD = "What-if-test-" + UUID.randomUUID() + "-Passphrase!";
    private static final String TEST_PASSWORD_HASH = OperatorLoginService.hashForTests(TEST_PASSWORD,
            "whatif-test-salt-123".getBytes(StandardCharsets.UTF_8), 210_000);

    @DynamicPropertySource
    static void loginProperties(DynamicPropertyRegistry registry) {
        registry.add("app.operator.username", () -> TEST_USERNAME);
        registry.add("app.operator.password-hash", () -> TEST_PASSWORD_HASH);
        registry.add("app.baseline.admin-token", () -> "test-admin-token");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NumericWorkingCopyService workingCopies;
    @Autowired private OoxmlPackageInspector packageInspector;
    @Autowired private com.example.processautomation.material.DiagramEditWorkingCopyRepository sourceRepository;
    @Autowired private com.example.processautomation.material.GeneralDiagramEditService diagramEdits;
    @Autowired private com.example.processautomation.material.ProcessDiagramInventoryService inventoryService;

    @Test
    void loginCsrfPeriodWhatIfAndExcelExportAreProtectedAndExecutable() throws Exception {
        String api = "/api/workspace/material-balance/what-if";
        NetworkWhatIfRequest payload = request("2026-10-01/TEST_PERIOD");
        mvc.perform(get(api + "/readiness")).andExpect(status().isUnauthorized());
        mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isUnauthorized());

        MvcResult loginResult = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String csrf = mapper.readTree(loginResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        assertThat(session).isNotNull();
        assertThat(csrf).isNotBlank();

        mvc.perform(get(api + "/readiness").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CALCULATION_UNAVAILABLE"));
        mvc.perform(post(api).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isForbidden());
        mvc.perform(post(api).session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.actualReadiness.status").value("CALCULATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.scenarios[0].result.newQuantity").value(10))
                .andExpect(jsonPath("$.scenarios[1].result.newQuantity").value(50));

        NetworkWhatIfRequest noPeriod = request("");
        mvc.perform(post(api).session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(noPeriod)))
                .andExpect(status().isBadRequest());

        MvcResult exported = mvc.perform(post(api + "/export").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Official-Baseline-Modified", "false")).andReturn();
        byte[] bytes = exported.getResponse().getContentAsByteArray();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("시나리오 비교")).isNotNull();
            assertThat(workbook.getSheet("공정별 수지")).isNotNull();
            assertThat(workbook.getSheet("연결 유량")).isNotNull();
            assertThat(workbook.getSheet("설비별 수지")).isNotNull();
        }
        TestTransaction.flagForRollback();
    }

    @Test
    void p02BoxOnlyAddIsAvailableThroughAuthenticatedSessionAndCsrfProtectedApi() throws Exception {
        String base = "/api/workspace/diagram-editor";
        mvc.perform(get(base + "/working-copies/3")).andExpect(status().isUnauthorized());
        MvcResult loginResult = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String csrf = mapper.readTree(loginResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        mvc.perform(get(base + "/working-copies/3").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processCode").value("P-02"))
                .andExpect(jsonPath("$.officialBaselineModified").value(false));
        mvc.perform(get(base + "/working-copies/3/sheets").session(session))
                .andExpect(status().isOk());
        String sheet = "P-02-05(BROKE)";
        MvcResult locationsResult = mvc.perform(get(base + "/working-copies/3/locations")
                        .param("sheetName", sheet).session(session))
                .andExpect(status().isOk()).andReturn();
        var locations = mapper.readTree(locationsResult.getResponse().getContentAsByteArray());
        var candidate = java.util.stream.StreamSupport.stream(locations.spliterator(), false)
                .filter(item -> item.path("sourceEquipmentId").asText().equals("I-PP22061")
                        && item.path("targetEquipmentId").asText().equals("I-PP25008"))
                .findFirst().orElseThrow();
        assertThat(candidate.path("editabilityStatus").asText()).isEqualTo("VERIFIED");
        assertThat(candidate.path("connectionEvidenceStatus").asText()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");

        Map<String, Object> input = Map.of("workingDocumentId", 3, "locationId", candidate.path("locationId").asText(),
                "sheetName", sheet, "managementNo", "T-P02-WEB-TEST-01", "equipmentName", "TEST_ONLY web pump",
                "businessItemNo", "TEST-ITEM-WEB-01", "capacity", "75", "capacityUnit", "m3/h",
                "quantity", "1ea", "equipmentTypeLabel", "TEST_ONLY equipment");
        mvc.perform(post(base + "/preview").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isForbidden());
        MvcResult previewResult = mvc.perform(post(base + "/preview").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.relationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.physicalConnectionVerified").value(false)).andReturn();
        String previewId = mapper.readTree(previewResult.getResponse().getContentAsByteArray()).path("previewId").asText();
        MvcResult generate = mvc.perform(post(base + "/generate").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.officialBaselineModified").value(false))
                .andExpect(jsonPath("$.connectionEvidenceStatus").value("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE")).andReturn();
        MvcResult downloaded = mvc.perform(get(base + "/download").session(session).param("previewId", previewId))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(downloaded.getResponse().getContentAsByteArray()))) {
            var added = workbook.getSheet(sheet);
            assertThat(added).isNotNull();
            assertThat(added.getRow(candidate.path("candidateCellRange").asText().contains(":")
                    ? Integer.parseInt(candidate.path("candidateCellRange").asText().split(":")[0].replaceAll("[^0-9]", "")) - 1 : 0)
                    .getCell(9).getStringCellValue()).isEqualTo("T-P02-WEB-TEST-01");
        }
        MvcResult secondLogin = mvc.perform(post("/api/session/login").header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession otherSession = (MockHttpSession) secondLogin.getRequest().getSession(false);
        mvc.perform(get(base + "/download").session(otherSession).param("previewId", previewId))
                .andExpect(status().isNotFound());
        TestTransaction.flagForRollback();
    }

    @Test
    void p02AdditionalVerifiedSlotProducesBoxOnlyTestOnlyWorkbook() throws Exception {
        String base = "/api/workspace/diagram-editor";
        MvcResult loginResult = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String csrf = mapper.readTree(loginResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        String sheet = "P-02-01(TOP)";
        MvcResult locationsResult = mvc.perform(get(base + "/working-copies/3/locations")
                        .param("sheetName", sheet).session(session))
                .andExpect(status().isOk()).andReturn();
        var locations = mapper.readTree(locationsResult.getResponse().getContentAsByteArray());
        var candidate = java.util.stream.StreamSupport.stream(locations.spliterator(), false)
                .filter(item -> item.path("sourceEquipmentId").asText().equals("I-PP21039")
                        && item.path("targetEquipmentId").asText().equals("I-PP21040"))
                .findFirst().orElseThrow();
        assertThat(candidate.path("editabilityStatus").asText()).isEqualTo("VERIFIED");
        assertThat(candidate.path("connectionEvidenceStatus").asText())
                .isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");

        Map<String, Object> input = Map.of("workingDocumentId", 3, "locationId", candidate.path("locationId").asText(),
                "sheetName", sheet, "managementNo", "T-P02-EXPAND-20261031-01", "equipmentName", "TEST_ONLY P02 added unit",
                "businessItemNo", "TEST-ITEM-P02-EXPAND-01", "capacity", "42", "capacityUnit", "m3/h",
                "quantity", "1ea", "equipmentTypeLabel", "TEST_ONLY unit");
        MvcResult previewResult = mvc.perform(post(base + "/preview").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.relationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.physicalConnectionVerified").value(false)).andReturn();
        String previewId = mapper.readTree(previewResult.getResponse().getContentAsByteArray()).path("previewId").asText();
        mvc.perform(post(base + "/generate").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.officialBaselineModified").value(false));
        MvcResult downloaded = mvc.perform(get(base + "/download").session(session).param("previewId", previewId))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        byte[] sourceBytes = workingCopies.getWorkingCopy(3).bytes();
        byte[] outputBytes = downloaded.getResponse().getContentAsByteArray();
        String outputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outputBytes));
        assertThat(downloaded.getResponse().getHeader("X-Output-SHA256")).isEqualTo(outputHash);
        var sourceInventory = packageInspector.inspect(sourceBytes);
        var outputInventory = packageInspector.inspect(outputBytes);
        var sourceDrawing = sourceInventory.sheets().stream().filter(item -> sheet.equals(item.sheetName())).findFirst().orElseThrow();
        var outputDrawing = outputInventory.sheets().stream().filter(item -> sheet.equals(item.sheetName())).findFirst().orElseThrow();
        assertThat(outputDrawing.drawingObjects()).hasSize(sourceDrawing.drawingObjects().size());
        assertThat(outputDrawing.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).count())
                .isEqualTo(sourceDrawing.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).count());
        Map<String, com.example.processautomation.excel.WorkbookInventory.DrawingObject> outputByShapeId =
                outputDrawing.drawingObjects().stream().collect(java.util.stream.Collectors.toMap(
                        com.example.processautomation.excel.WorkbookInventory.DrawingObject::shapeId, item -> item));
        assertThat(outputByShapeId.keySet()).containsExactlyInAnyOrderElementsOf(sourceDrawing.drawingObjects().stream()
                .map(com.example.processautomation.excel.WorkbookInventory.DrawingObject::shapeId).toList());
        for (var sourceObject : sourceDrawing.drawingObjects()) {
            var outputObject = outputByShapeId.get(sourceObject.shapeId());
            assertThat(outputObject).isNotNull();
            assertThat(outputObject.objectType()).isEqualTo(sourceObject.objectType());
            assertThat(outputObject.objectName()).isEqualTo(sourceObject.objectName());
            assertThat(outputObject.textContent()).isEqualTo(sourceObject.textContent());
            assertThat(outputObject.geometryPreset()).isEqualTo(sourceObject.geometryPreset());
            assertThat(outputObject.headArrowType()).isEqualTo(sourceObject.headArrowType());
            assertThat(outputObject.tailArrowType()).isEqualTo(sourceObject.tailArrowType());
            assertThat(outputObject.startConnectedShapeId()).isEqualTo(sourceObject.startConnectedShapeId());
            assertThat(outputObject.endConnectedShapeId()).isEqualTo(sourceObject.endConnectedShapeId());
        }
        String topLeft = candidate.path("candidateCellRange").asText().split(":")[0];
        var ref = new org.apache.poi.ss.util.CellReference(topLeft);
        int insertionRowOneBased = ref.getRow() + 1;
        int insertedRows = candidate.path("boxHeight").asInt()
                + Math.min(candidate.path("originalGapRows").asInt(), 3);
        int insertionRowZeroBased = ref.getRow();
        for (var sourceObject : sourceDrawing.drawingObjects()) {
            var outputObject = outputByShapeId.get(sourceObject.shapeId());
            assertThat(outputObject.startRow()).isEqualTo(sourceObject.startRow() == null ? null
                    : sourceObject.startRow() >= insertionRowZeroBased
                            ? sourceObject.startRow() + insertedRows : sourceObject.startRow());
            assertThat(outputObject.endRow()).isEqualTo(sourceObject.endRow() == null ? null
                    : sourceObject.endRow() >= insertionRowZeroBased
                            ? sourceObject.endRow() + insertedRows : sourceObject.endRow());
        }
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
             var outputWorkbook = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
            var sourceSheet = sourceWorkbook.getSheet(sheet);
            var outputSheet = outputWorkbook.getSheet(sheet);
            assertThat(outputSheet).isNotNull();
            assertThat(outputSheet.getNumMergedRegions()).isEqualTo(sourceSheet.getNumMergedRegions() + 1);
            java.util.Set<String> shiftedSourceMerges = new java.util.HashSet<>();
            for (int index = 0; index < sourceSheet.getNumMergedRegions(); index++) {
                var sourceMerge = sourceSheet.getMergedRegion(index);
                int firstRow = sourceMerge.getFirstRow() >= insertionRowZeroBased
                        ? sourceMerge.getFirstRow() + insertedRows : sourceMerge.getFirstRow();
                int lastRow = sourceMerge.getLastRow() >= insertionRowZeroBased
                        ? sourceMerge.getLastRow() + insertedRows : sourceMerge.getLastRow();
                shiftedSourceMerges.add(new org.apache.poi.ss.util.CellRangeAddress(firstRow, lastRow,
                        sourceMerge.getFirstColumn(), sourceMerge.getLastColumn()).formatAsString());
            }
            java.util.Set<String> outputMerges = new java.util.HashSet<>();
            for (int index = 0; index < outputSheet.getNumMergedRegions(); index++) {
                outputMerges.add(outputSheet.getMergedRegion(index).formatAsString());
            }
            assertThat(outputMerges).containsAll(shiftedSourceMerges);
            String sourcePrintArea = sourceWorkbook.getPrintArea(sourceWorkbook.getSheetIndex(sheet));
            String outputPrintArea = outputWorkbook.getPrintArea(outputWorkbook.getSheetIndex(sheet));
            assertThat(outputPrintArea).isEqualTo(shiftPrintAreaRows(sourcePrintArea, insertionRowOneBased, insertedRows));
            assertThat(outputSheet.getRow(ref.getRow()).getCell(ref.getCol()).getStringCellValue())
                    .isEqualTo("T-P02-EXPAND-20261031-01");
        }
        Path artifact = Path.of("validation-results", "2026-10-31-process-diagram-inventory",
                "P-02-01_TOP_TEST_ONLY_ADD_T-P02-EXPAND-20261031-01.xlsx");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, outputBytes);
        registerBoxOnlyValidation("P-02|P-02-01_TOP_|I-PP21039->I-PP21040", 3, sheet,
                "I-PP21039", "I-PP21040", "T-P02-EXPAND-20261031-01",
                artifact.toString().replace('\\', '/'), outputHash);
        TestTransaction.flagForRollback();
    }

    @Test
    void p01LegacyCandidatesAreReassessedAndRemainUnsupported() {
        var copy = workingCopies.getWorkingCopy(2);
        var source = sourceRepository.findLockedSource(copy.baselineDocumentId());
        assertThat(copy.processCode()).isEqualTo("P-01");
        assertThat(copy.conversionVersion()).isEqualTo("NUMERIC-WORKING-UPLOAD-V1");
        assertThat(copy.conversionStatus()).isEqualTo("CREATED_WITH_REVIEW");
        assertThat(source).isNotNull();
        assertThat(source.locked()).isTrue();
        assertThat(source.baselineVersion()).isEqualTo("V1.0");
        assertThat(source.sourceSha256()).isEqualToIgnoringCase(copy.sourceSha256());
        var locations = diagramEdits.assessLocations(copy.id(), "P-01-01(TOP)");
        assertThat(locations).hasSize(8);
        assertThat(locations).allSatisfy(location -> {
            assertThat(location.editabilityStatus()).isEqualTo("UNSUPPORTED");
            assertThat(location.editabilityReason()).contains("MERGED_OR_DRAWING_OBJECTS_OVERLAP_CANDIDATE");
        });
        TestTransaction.flagForRollback();
    }

    @Test
    void pw02VerifiedCandidateProducesBoxOnlyTestOnlyWorkbook() throws Exception {
        String base = "/api/workspace/diagram-editor";
        MvcResult loginResult = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String sheet = "PW-02-01";
        var copy = mapper.readTree(mvc.perform(get(base + "/working-copies/8").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(copy.path("processCode").asText()).isEqualTo("PW-02");
        assertThat(copy.path("sourceSha256").asText())
                .isEqualTo("2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a");
        MvcResult locationsResult = mvc.perform(get(base + "/working-copies/8/locations")
                        .param("sheetName", sheet).session(session))
                .andExpect(status().isOk()).andReturn();
        var locations = mapper.readTree(locationsResult.getResponse().getContentAsByteArray());
        var candidate = java.util.stream.StreamSupport.stream(locations.spliterator(), false)
                .filter(item -> item.path("sourceEquipmentId").asText().equals("I-PW21007")
                        && item.path("targetEquipmentId").asText().equals("I-PW21008"))
                .findFirst().orElseThrow();
        assertThat(candidate.path("editabilityStatus").asText()).isEqualTo("VERIFIED");
        assertThat(candidate.path("connectionEvidenceStatus").asText())
                .isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        String managementNo = "T-PW02-EXPANSION-20261031-01";
        Map<String, Object> input = Map.of("workingDocumentId", 8, "locationId", candidate.path("locationId").asText(),
                "sheetName", sheet, "managementNo", managementNo, "equipmentName", "TEST_ONLY PW-02 additional unit",
                "businessItemNo", "TEST-PW02-EXPANSION-01", "capacity", "12", "capacityUnit", "m3/h",
                "quantity", "1ea", "equipmentTypeLabel", "TEST_ONLY unit");
        String csrf = mapper.readTree(loginResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        MvcResult preview = mvc.perform(post(base + "/preview").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.relationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.physicalConnectionVerified").value(false)).andReturn();
        String previewId = mapper.readTree(preview.getResponse().getContentAsByteArray()).path("previewId").asText();
        mvc.perform(post(base + "/generate").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.officialBaselineModified").value(false));
        MvcResult download = mvc.perform(get(base + "/download").session(session).param("previewId", previewId))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        byte[] sourceBytes = workingCopies.getWorkingCopy(8).bytes();
        byte[] outputBytes = download.getResponse().getContentAsByteArray();
        String outputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outputBytes));
        assertThat(download.getResponse().getHeader("X-Output-SHA256")).isEqualTo(outputHash);
        assertZipIntegrity(outputBytes);
        var sourceDrawing = packageInspector.inspectSheet(sourceBytes, sheet);
        var outputDrawing = packageInspector.inspectSheet(outputBytes, sheet);
        assertThat(outputDrawing.drawingObjects()).hasSize(sourceDrawing.drawingObjects().size());
        assertThat(outputDrawing.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).count())
                .isEqualTo(sourceDrawing.drawingObjects().stream().filter(item -> "CONNECTOR".equals(item.objectType())).count());
        Map<String, com.example.processautomation.excel.WorkbookInventory.DrawingObject> outputByShapeId =
                outputDrawing.drawingObjects().stream().collect(java.util.stream.Collectors.toMap(
                        com.example.processautomation.excel.WorkbookInventory.DrawingObject::shapeId, item -> item));
        assertThat(outputByShapeId.keySet()).containsExactlyInAnyOrderElementsOf(sourceDrawing.drawingObjects().stream()
                .map(com.example.processautomation.excel.WorkbookInventory.DrawingObject::shapeId).toList());
        for (var original : sourceDrawing.drawingObjects()) {
            var generated = outputByShapeId.get(original.shapeId());
            assertThat(generated.objectType()).isEqualTo(original.objectType());
            assertThat(generated.objectName()).isEqualTo(original.objectName());
            assertThat(generated.textContent()).isEqualTo(original.textContent());
            assertThat(generated.geometryPreset()).isEqualTo(original.geometryPreset());
            assertThat(generated.headArrowType()).isEqualTo(original.headArrowType());
            assertThat(generated.tailArrowType()).isEqualTo(original.tailArrowType());
            assertThat(generated.startConnectedShapeId()).isEqualTo(original.startConnectedShapeId());
            assertThat(generated.endConnectedShapeId()).isEqualTo(original.endConnectedShapeId());
        }
        String topLeft = candidate.path("candidateCellRange").asText().split(":")[0];
        var ref = new org.apache.poi.ss.util.CellReference(topLeft);
        int insertedRows = candidate.path("boxHeight").asInt()
                + Math.min(candidate.path("originalGapRows").asInt(), 3);
        for (var original : sourceDrawing.drawingObjects()) {
            var generated = outputByShapeId.get(original.shapeId());
            assertThat(generated.startRow()).isEqualTo(original.startRow() == null ? null
                    : original.startRow() >= ref.getRow() ? original.startRow() + insertedRows : original.startRow());
            assertThat(generated.endRow()).isEqualTo(original.endRow() == null ? null
                    : original.endRow() >= ref.getRow() ? original.endRow() + insertedRows : original.endRow());
            assertThat(generated.startColumn()).isEqualTo(original.startColumn());
            assertThat(generated.endColumn()).isEqualTo(original.endColumn());
            assertThat(generated.startRowOffset()).isEqualTo(original.startRowOffset());
            assertThat(generated.endRowOffset()).isEqualTo(original.endRowOffset());
        }
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
             var outputWorkbook = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
            var sourceSheet = sourceWorkbook.getSheet(sheet);
            var outputSheet = outputWorkbook.getSheet(sheet);
            var newIdCell = outputSheet.getRow(ref.getRow()).getCell(ref.getCol());
            assertThat(newIdCell.getStringCellValue()).isEqualTo(managementNo);
            assertThat(newIdCell.getCellStyle().getIndex()).isNotZero();
            assertThat(outputSheet.getNumMergedRegions()).isEqualTo(sourceSheet.getNumMergedRegions());
            java.util.Set<String> expectedMerges = new java.util.HashSet<>();
            for (int i = 0; i < sourceSheet.getNumMergedRegions(); i++) {
                var merge = sourceSheet.getMergedRegion(i);
                int firstRow = merge.getFirstRow() >= ref.getRow() ? merge.getFirstRow() + insertedRows : merge.getFirstRow();
                int lastRow = merge.getLastRow() >= ref.getRow() ? merge.getLastRow() + insertedRows : merge.getLastRow();
                expectedMerges.add(new org.apache.poi.ss.util.CellRangeAddress(firstRow, lastRow,
                        merge.getFirstColumn(), merge.getLastColumn()).formatAsString());
            }
            java.util.Set<String> actualMerges = new java.util.HashSet<>();
            for (int i = 0; i < outputSheet.getNumMergedRegions(); i++) {
                actualMerges.add(outputSheet.getMergedRegion(i).formatAsString());
            }
            assertThat(actualMerges).isEqualTo(expectedMerges);
            String sourcePrintArea = sourceWorkbook.getPrintArea(sourceWorkbook.getSheetIndex(sheet));
            String outputPrintArea = outputWorkbook.getPrintArea(outputWorkbook.getSheetIndex(sheet));
            assertThat(outputPrintArea).isEqualTo(shiftPrintAreaRows(sourcePrintArea, ref.getRow() + 1, insertedRows));
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            Map<Integer, String> expectedFields = Map.of(0, managementNo, 2, "TEST_ONLY unit",
                    3, "TEST_ONLY PW-02 additional unit", 4, "TEST-PW02-EXPANSION-01", 5, "12m3/h", 6, "1ea");
            var sourceId = findCell(sourceSheet, "I-PW21007");
            assertThat(sourceId).isNotNull();
            for (var field : expectedFields.entrySet()) {
                var outputCell = outputSheet.getRow(ref.getRow() + field.getKey()).getCell(ref.getCol());
                var sourceCell = sourceSheet.getRow(sourceId.getRowIndex() + field.getKey()).getCell(sourceId.getColumnIndex());
                assertThat(formatter.formatCellValue(outputCell)).isEqualTo(field.getValue());
                assertThat(outputCell.getCellStyle().getIndex()).isEqualTo(sourceCell.getCellStyle().getIndex());
            }
        }
        Path artifact = Path.of("validation-results", "2026-10-31-process-diagram-inventory",
                "PW-02-01_TEST_ONLY_ADD_" + managementNo + ".xlsx");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, outputBytes);
        registerBoxOnlyValidation("PW-02|PW-02-01|I-PW21007->I-PW21008", 8, sheet,
                "I-PW21007", "I-PW21008", managementNo,
                artifact.toString().replace('\\', '/'), outputHash);
        TestTransaction.flagForRollback();
    }

    @Test
    void pw03LocationsAreReassessedFromLockedShaLinkedWorkingCopy() throws Exception {
        String base = "/api/workspace/diagram-editor";
        MvcResult loginResult = mvc.perform(post("/api/session/login")
                        .header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", TEST_USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String sheet = "PW-03-01";
        var copy = mapper.readTree(mvc.perform(get(base + "/working-copies/15").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(copy.path("processCode").asText()).isEqualTo("PW-03");
        assertThat(copy.path("sourceSha256").asText())
                .isEqualTo("647ea78e8466165bc7f7fc97bb91ef0fed3700222b51475f99884e7b40a93134");
        assertThat(copy.path("workingSha256").asText()).isNotBlank();
        MvcResult locationsResult = mvc.perform(get(base + "/working-copies/15/locations")
                        .param("sheetName", sheet).session(session))
                .andExpect(status().isOk()).andReturn();
        var locations = mapper.readTree(locationsResult.getResponse().getContentAsByteArray());
        assertThat(locations.isArray()).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(locations.spliterator(), false)
                .filter(item -> "VERIFIED".equals(item.path("editabilityStatus").asText())).count()).isZero();
        var candidate = java.util.stream.StreamSupport.stream(locations.spliterator(), false)
                .filter(item -> "REVIEW_REQUIRED".equals(item.path("editabilityStatus").asText()))
                .findFirst().orElseThrow();
        assertThat(candidate.path("editabilityReason").asText()).contains("TEMPLATE_FIELD_MAP_UNVERIFIED");
        Map<String, Object> blockedInput = Map.of("workingDocumentId", 15,
                "locationId", candidate.path("locationId").asText(), "sheetName", sheet,
                "managementNo", "T-PW03-EXPANSION-20261031-01", "equipmentName", "TEST_ONLY PW-03 unit",
                "businessItemNo", "TEST-PW03-EXPANSION-01", "capacity", "12", "capacityUnit", "m3/h",
                "quantity", "1ea", "equipmentTypeLabel", "TEST_ONLY unit");
        String csrf = mapper.readTree(loginResult.getResponse().getContentAsByteArray()).path("csrfToken").asText();
        mvc.perform(post(base + "/preview").session(session).header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(blockedInput)))
                .andExpect(status().isConflict());
        TestTransaction.flagForRollback();
    }

    @Test
    void validationRegistrationRequiresAdminToken() throws Exception {
        String endpoint = "/api/admin/workspace/process-diagram-inventory/validations";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint).header("X-Admin-Token", "invalid-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private void registerBoxOnlyValidation(String validationId, long workingDocumentId, String sheetName,
                                           String sourceEquipmentId, String targetEquipmentId, String managementNo,
                                           String artifactPath, String artifactSha256) {
        var copy = workingCopies.getWorkingCopy(workingDocumentId);
        var validation = new com.example.processautomation.material.ProcessDiagramInventoryCheckpointStore.EditabilityValidation(
                validationId, java.time.Instant.now(), copy.processCode(), sheetName, copy.baselineDocumentId(), copy.id(),
                copy.sourceSha256(), copy.workingSha256(), validationId, sourceEquipmentId, targetEquipmentId,
                managementNo, artifactPath, artifactSha256, "VERIFIED_BOX_ONLY_TEST_ONLY",
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE",
                "LOGIN_CSRF_API_PREVIEW_GENERATE_DOWNLOAD_POI_REOPEN_OOXML_AND_VISUAL_REVIEW");
        var stored = inventoryService.registerGeneratedBoxOnlyValidation(validation);
        assertThat(stored.structuralEditStatus()).isEqualTo("VERIFIED_BOX_ONLY_TEST_ONLY");
        assertThat(stored.physicalConnectionStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
    }

    private org.apache.poi.ss.usermodel.Cell findCell(org.apache.poi.ss.usermodel.Sheet sheet, String value) {
        var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
        java.util.List<org.apache.poi.ss.usermodel.Cell> matches = new java.util.ArrayList<>();
        for (var row : sheet) for (var cell : row) {
            if (value.equals(formatter.formatCellValue(cell).trim())) matches.add(cell);
        }
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private void assertZipIntegrity(byte[] workbookBytes) throws Exception {
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(workbookBytes))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                while (input.read(buffer) != -1) { }
                input.closeEntry();
            }
        }
    }

    private String shiftPrintAreaRows(String formula, int insertionRowOneBased, int delta) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\$?[A-Z]{1,3}\\$?)([0-9]+)")
                .matcher(formula);
        StringBuffer shifted = new StringBuffer();
        while (matcher.find()) {
            int row = Integer.parseInt(matcher.group(2));
            String replacement = matcher.group(1) + (row >= insertionRowOneBased ? row + delta : row);
            matcher.appendReplacement(shifted, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(shifted);
        return shifted.toString();
    }

    private NetworkWhatIfRequest request(String period) {
        List<NetworkWhatIfRequest.Node> nodes = List.of(
                node("S-P2", null, "P2", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                node("S-P1", null, "P1", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                node("X", "P2-X", "P2", DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT),
                node("N", "P1-N", "P1", DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT),
                node("OUT-P2", null, "P2", DirectedProcessNetwork.NodeType.EXTERNAL_SINK),
                node("OUT-P1", null, "P1", DirectedProcessNetwork.NodeType.EXTERNAL_SINK));
        List<NetworkWhatIfRequest.Edge> edges = List.of(
                edge("P2-IN", "S-P2", "X", "P2", "P2", "100", DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, period, true),
                edge("P1-IN", "S-P1", "N", "P1", "P1", "100", DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, period, true),
                edge("P2-X-P1-N", "X", "N", "P2", "P1", "20", DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, period, true),
                edge("P2-X-P2-OUT", "X", "OUT-P2", "P2", "", null, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, period, false),
                edge("P1-N-P1-OUT", "N", "OUT-P1", "P1", "", null, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, period, false));
        return new NetworkWhatIfRequest("TEST_ONLY", "session-whatif-" + UUID.randomUUID(),
                "Synthetic session what-if", "P2", "P2-X", "P1-N", DirectedProcessNetwork.MaterialClass.WASTEWATER,
                "process wastewater", "ton/day", period, "P2-X-P1-N", new BigDecimal("20"), nodes, edges, List.of(), List.of(
                new NetworkWhatIfRequest.ChangeCase("FLOW-10", "20 to 10", new BigDecimal("10")),
                new NetworkWhatIfRequest.ChangeCase("FLOW-50", "20 to 50", new BigDecimal("50"))));
    }

    private NetworkWhatIfRequest.Node node(String id, String equipment, String process,
                                           DirectedProcessNetwork.NodeType type) {
        return new NetworkWhatIfRequest.Node(id, equipment, process,
                type == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT ? "PROCESS_EQUIPMENT" : "EXTERNAL_BOUNDARY",
                type, id, "TEST_ONLY synthetic", "TEST_ONLY");
    }

    private NetworkWhatIfRequest.Edge edge(String id, String source, String target, String sourceProcess,
                                           String targetProcess, String quantity,
                                           DirectedProcessNetwork.ConnectionType type, String period, boolean fixed) {
        return new NetworkWhatIfRequest.Edge(id, source, target, sourceProcess, targetProcess,
                DirectedProcessNetwork.MaterialClass.WASTEWATER, "process wastewater", "ton/day",
                quantity == null ? null : new BigDecimal(quantity), type,
                fixed ? DirectedProcessNetwork.QuantityBasis.USER_INPUT : DirectedProcessNetwork.QuantityBasis.CALCULATED,
                fixed, "Synthetic fixture only; no actual evidence", period);
    }
}
