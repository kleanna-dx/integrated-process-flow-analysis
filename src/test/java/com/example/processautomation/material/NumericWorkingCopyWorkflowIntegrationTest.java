package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NumericWorkingCopyWorkflowIntegrationTest {
    @Autowired private NumericWorkingCopyService workingCopies;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OoxmlPackageInspector inspector;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    @Test
    void createsSeparatePw01NumericWorksheetAndKeepsOriginalBytesUnchanged() throws Exception {
        byte[] official = jdbc.queryForObject(
                "SELECT content_bytes FROM baseline_document WHERE id=12 AND document_role='PROCESS'", byte[].class);
        String officialHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(official));

        var created = workingCopies.createPw01Copy(12);
        var copy = workingCopies.getWorkingCopy(((Number) created.get("workingDocumentId")).longValue());

        assertThat(created.get("conversionStatus")).isEqualTo("CREATED_WITH_REVIEW");
        assertThat(created.get("sourceSha256")).isEqualTo(officialHash);
        assertThat(created.get("workingSha256")).isEqualTo(copy.workingSha256());
        assertThat(created.get("sourceWorkbookCellEdits")).isEqualTo(0);
        assertThat(Boolean.TRUE.equals(created.get("drawingObjectsPreserved"))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT LOWER(SHA2(content_bytes,256)) FROM baseline_document WHERE id=12", String.class))
                .isEqualTo(officialHash);

        var before = inspector.inspect(official);
        var after = inspector.inspect(copy.bytes());
        assertThat(after.sheets()).hasSize(before.sheets().size() + 1);
        assertThat(after.shapeCount()).isEqualTo(before.shapeCount());
        assertThat(after.connectorCount()).isEqualTo(before.connectorCount());
        assertThat(after.pictureCount()).isEqualTo(before.pictureCount());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(copy.bytes()))) {
            var numeric = workbook.getSheet(NumericWorkingCopyService.NUMERIC_SHEET);
            assertThat(numeric).isNotNull();
            assertThat(numeric.getRow(0).getCell(0).getStringCellValue()).contains("TEST_ONLY");
            long numericCellCount = 0;
            for (var row : numeric) for (var cell : row) if (cell.getCellType() == CellType.NUMERIC) numericCellCount++;
            assertThat(numericCellCount).isEqualTo(((Number) created.get("convertedNumericRows")).longValue());
            assertThat(numericCellCount).isPositive();
        }
        TestTransaction.flagForRollback();
    }

    @Test
    void createsSeparateOfficialTextNumericCandidateAndKeepsSixUploadDiscrepanciesForReview() throws Exception {
        long originalUploadId = jdbc.queryForObject("""
                SELECT id FROM process_working_document
                WHERE baseline_document_id=12 AND conversion_version='NUMERIC-WORKING-UPLOAD-V1'
                """, Long.class);
        String originalUploadHash = jdbc.queryForObject(
                "SELECT LOWER(working_sha256) FROM process_working_document WHERE id=?", String.class, originalUploadId);

        var result = workingCopies.createOfficialNumericCandidate(12);
        var candidate = workingCopies.getWorkingCopy(((Number) result.get("workingDocumentId")).longValue());
        @SuppressWarnings("unchecked")
        var deltas = (java.util.List<java.util.Map<String, Object>>) result.get("knownDiscrepancyCells");

        assertThat(result.get("conversionStatus")).isEqualTo("CREATED_WITH_REVIEW");
        assertThat(((Number) result.get("convertedCellCount")).intValue()).isPositive();
        assertThat(result.get("structureValidationStatus")).isEqualTo("VERIFIED");
        assertThat(result.get("candidateDisposition").toString()).contains("NOT A REPLACEMENT");
        @SuppressWarnings("unchecked")
        var classificationCounts = (java.util.Map<String, Number>) result.get("classificationCounts");
        assertThat(classificationCounts.get("CONVERTED").longValue()).isEqualTo(5L);
        assertThat(classificationCounts.get("REVIEW_REQUIRED_UNSUPPORTED_UNIT").longValue()).isEqualTo(2580L);
        assertThat(classificationCounts.get("PRESERVED_COMPOUND_TEXT_REVIEW_REQUIRED").longValue()).isEqualTo(338L);
        assertThat(classificationCounts.get("REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS").longValue()).isEqualTo(70L);
        assertThat(((Number) result.get("classifiedCellCount")).longValue()).isEqualTo(2993L);
        assertThat(((Number) result.get("compoundOrUnsupportedCells")).longValue()).isEqualTo(2988L);
        assertThat(result.get("classificationCategoriesDisjoint")).isEqualTo(true);
        assertThat(deltas).hasSize(6).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("REVIEW_REQUIRED");
            assertThat(row.get("uploadMinusOfficialTextMetadata").toString()).isEqualTo("0.22");
            assertThat(row.get("officialCellTreatment")).isEqualTo("PRESERVED_COMPOUND_TEXT_REVIEW_REQUIRED");
        });
        assertThat(candidate.filename()).isEqualTo("PW-01_OFFICIAL_NUMERIC_CANDIDATE_V1.xlsx");
        assertThat(candidate.conversionStatus()).isEqualTo("CREATED_WITH_REVIEW");
        assertThat(inspector.inspect(candidate.bytes()).sheets()).containsExactlyElementsOf(inspector.inspect(
                jdbc.queryForObject("SELECT content_bytes FROM baseline_document WHERE id=12", byte[].class)).sheets());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(candidate.bytes()))) {
            var sheet = workbook.getSheet("PW-01-05");
            assertThat(sheet.getRow(178).getCell(53).getCellType()).isEqualTo(CellType.STRING);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT)
                    .formatCellValue(sheet.getRow(178).getCell(53))).isEqualTo("일반폐수 16,439.95 톤/일");
            assertThat(sheet.getNumMergedRegions()).isEqualTo(84);
            var compound = workbook.getSheet("PW-01-01").getRow(295).getCell(3);
            assertThat(compound.getCellType()).isEqualTo(CellType.STRING);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT).formatCellValue(compound))
                    .isEqualTo("황산(9.5%) 0.8톤/일");
        }
        @SuppressWarnings("unchecked")
        var assessments = (java.util.List<java.util.Map<String, Object>>) result.get("cellAssessments");
        assertThat(assessments).anySatisfy(item -> {
            assertThat(item.get("sheet")).isEqualTo("PW-01-01");
            assertThat(item.get("cell")).isEqualTo("D296");
            assertThat(item.get("status")).isEqualTo("REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS");
            assertThat(item.get("extractedQuantityMetadata").toString()).isEqualTo("0.8");
        });
        assertThat(jdbc.queryForObject("SELECT LOWER(working_sha256) FROM process_working_document WHERE id=?",
                String.class, originalUploadId)).isEqualTo(originalUploadHash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_working_document WHERE conversion_version='NUMERIC-WORKING-UPLOAD-V1'",
                Long.class)).isEqualTo(15L);
        TestTransaction.flagForRollback();
    }

    @Test
    void numericCopyApiReturns401ForAnonymous400ForInvalidFilenameAnd200ForValidPw01() throws Exception {
        byte[] official = jdbc.queryForObject(
                "SELECT content_bytes FROM baseline_document WHERE id=12 AND document_role='PROCESS'", byte[].class);
        mockMvc.perform(post("/api/admin/workspace/process-documents/12/numeric-working-copy"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/admin/workspace/process-documents/12/numeric-working-copy/upload")
                        .file(new MockMultipartFile("file", "wrong-name.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", official))
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/api/admin/workspace/process-documents/12/numeric-working-copy")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDocumentId").exists())
                .andExpect(jsonPath("$.convertedNumericRows").isNumber());
        TestTransaction.flagForRollback();
    }

    @Test
    void previewsGeneratesAndDownloadsTwoDistinctUserInputWorkbooks() throws Exception {
        String requestFiveHash = jdbc.queryForObject(
                "SELECT LOWER(output_sha256) FROM process_change_request WHERE id=5", String.class);
        String candidate36Hash = jdbc.queryForObject(
                "SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36", String.class);
        String officialHash = jdbc.queryForObject(
                "SELECT LOWER(sha256) FROM baseline_document WHERE id=12", String.class);
        int equipmentBefore = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class);
        Path artifacts = Files.createTempDirectory(Path.of("/tmp"), "pw0105-add-regression-");
        Files.createDirectories(artifacts);

        List<GeneratedCase> generated = new ArrayList<>();
        generated.add(generateAndVerify(userInput("SYNTH-PW01-TEST-A", "Synthetic Filter Alpha",
                "SYN-ITEM-A", "475", "m3/h", "2 units", true), artifacts));
        generated.add(generateAndVerify(userInput("SYNTH-PW01-TEST-B", "Synthetic Clarifier Beta",
                "SYN-ITEM-B", "820", "kg/h", "3 units", false), artifacts));

        assertThat(generated).hasSize(2);
        assertThat(generated.get(0).requestId()).isNotEqualTo(generated.get(1).requestId());
        assertThat(generated.get(0).sha256()).isNotEqualTo(generated.get(1).sha256());
        assertThat(generated.get(0).bytes()).isNotEqualTo(generated.get(1).bytes());
        assertThat(generated.get(0).managementNo()).isNotEqualTo(generated.get(1).managementNo());
        assertThat(generated.get(0).name()).isNotEqualTo(generated.get(1).name());
        writeComparisonTable(artifacts, generated);
        assertThat(jdbc.queryForObject("SELECT LOWER(output_sha256) FROM process_change_request WHERE id=5",
                String.class)).isEqualTo(requestFiveHash);
        assertThat(jdbc.queryForObject("SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36",
                String.class)).isEqualTo(candidate36Hash);
        assertThat(jdbc.queryForObject("SELECT LOWER(sha256) FROM baseline_document WHERE id=12",
                String.class)).isEqualTo(officialHash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class)).isEqualTo(equipmentBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_change_request WHERE id IN (?,?)",
                Integer.class, generated.get(0).requestId(), generated.get(1).requestId())).isEqualTo(2);
        TestTransaction.flagForRollback();
    }

    @Test
    void rejectsExistingCpw15020DuplicateUnsupportedPlacementAndChangeType() throws Exception {
        String legacyPayload = objectMapper.writeValueAsString(userInput("C-PW15020", "SCREEN FILTER",
                "WW-F-AFT10", "1,500", "㎥/시", "1ea", false));
        Map<String, Object> duplicate = postPreview(legacyPayload);
        assertThat(duplicate.get("editPlanStatus")).isEqualTo("GENERATION_BLOCKED");
        assertThat(duplicate.get("automaticEditAllowed")).isEqualTo(false);
        assertThat(duplicate.get("reviewRequiredItems").toString()).contains("동일 설비번호의 기존 변경 요청");

        Map<String, Object> wrongPositionInput = userInput("SYNTH-PW01-BAD-PLACE", "Synthetic Bad Position",
                "SYN-BAD-PLACE", "100", "m3/h", "1 unit", false);
        wrongPositionInput.put("placementId", "CUSTOM_UNVERIFIED");
        Map<String, Object> wrongPosition = postPreview(objectMapper.writeValueAsString(wrongPositionInput));
        assertThat(wrongPosition.get("editPlanStatus")).isEqualTo("GENERATION_BLOCKED");
        assertThat(wrongPosition.get("reviewRequiredItems").toString()).contains("미지원입니다");

        Map<String, Object> unsupportedChangeInput = userInput("SYNTH-PW01-BAD-TYPE", "Synthetic Bad Type",
                "SYN-BAD-TYPE", "100", "m3/h", "1 unit", false);
        unsupportedChangeInput.put("changeType", "MODIFY");
        unsupportedChangeInput.put("targetManagementNo", "NO-SUCH-PW01-EQUIPMENT");
        Map<String, Object> unsupportedChange = postPreview(objectMapper.writeValueAsString(unsupportedChangeInput));
        assertThat(unsupportedChange.get("editPlanStatus")).isEqualTo("GENERATION_BLOCKED");
        assertThat(unsupportedChange.get("reviewRequiredItems").toString()).contains("잠긴 V1.0 마스터의 PW-01-05 설비번호와 일치하지 않습니다.");
        TestTransaction.flagForRollback();
    }

    @Disabled("This legacy test writes synthetic values into copies of the real ID 36 drawing; use the isolated synthetic MODIFY engine fixture until a field change is explicitly authorized.")
    @Test
    void discoversFieldVerifiedPw0105EquipmentAndModifiesOnlySelectedCellsInTwoTestOnlyWorkbooks() throws Exception {
        Path artifacts = Files.createTempDirectory(Path.of("/tmp"), "pw0105-modify-fixture-");
        Files.createDirectories(artifacts);
        String officialHash = jdbc.queryForObject(
                "SELECT LOWER(sha256) FROM baseline_document WHERE id=12", String.class);
        String candidateHash = jdbc.queryForObject(
                "SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36", String.class);
        Map<Long, String> protectedRequestHashes = new LinkedHashMap<>();
        for (long id : List.of(5L, 37L, 38L)) {
            protectedRequestHashes.put(id, jdbc.queryForObject(
                    "SELECT COALESCE(LOWER(output_sha256),'') FROM process_change_request WHERE id=?", String.class, id));
        }
        byte[] sourceBytes = workingCopies.getWorkingCopy(36).bytes();
        var sourceInventory = inspector.inspect(sourceBytes);
        var sourceSheet = sourceInventory.sheets().stream().filter(sheet -> "PW-01-05".equals(sheet.sheetName()))
                .findFirst().orElseThrow();

        MvcResult targetsResponse = mockMvc.perform(get("/api/admin/workspace/modify-targets")
                        .param("workingDocumentId", "36").param("sheetName", "PW-01-05")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> targets = objectMapper.readValue(targetsResponse.getResponse().getContentAsByteArray(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<Map<String, Object>> eligible = targets.stream().filter(target -> Boolean.TRUE.equals(target.get("identityVerified"))
                && Boolean.TRUE.equals(target.get("eligible"))).toList();
        assertThat(targets).hasSize(19);
        assertThat(eligible).as("at least one styled equipment box must have a unique master and cell mapping").isNotEmpty();
        Map<String, Object> target = eligible.get(0);
        String targetNo = String.valueOf(target.get("managementNo"));
        @SuppressWarnings("unchecked")
        Map<String, String> diagramValues = (Map<String, String>) target.get("diagramValues");
        @SuppressWarnings("unchecked")
        Map<String, String> fieldCells = (Map<String, String>) target.get("fieldCells");
        assertThat(target.get("status")).isEqualTo("IDENTITY_VERIFIED_FIELD_REVIEW");
        assertThat((List<?>) target.get("candidateEquipmentBoxes")).as("styled ID cell resolves to one equipment box").hasSize(1);
        assertThat(target.get("fieldAssessments")).isNotNull();
        assertThat(target.get("drawingEditPolicy")).isEqualTo("CELL_TEXT_ONLY; KEEP_DRAWING_PART_BYTE_IDENTICAL");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> assessments = (Map<String, Map<String, Object>>) target.get("fieldAssessments");
        String fieldToChange = List.of("equipmentName", "businessItemNo", "quantity").stream()
                .filter(field -> Boolean.TRUE.equals(assessments.get(field).get("modifiable"))).findFirst().orElseThrow();
        assertThat(List.of(diagramValues.get("equipmentName"), diagramValues.get("businessItemNo"),
                diagramValues.get("capacity"), diagramValues.get("quantity"))).allMatch(value -> value != null && !value.isBlank());

        List<String> outputHashes = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String suffix = index == 0 ? "A" : "B";
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("workingDocumentId", 36);
            input.put("sheetName", "PW-01-05");
            input.put("changeType", "MODIFY");
            input.put("targetManagementNo", targetNo);
            input.put("managementNo", targetNo);
            input.put("equipmentName", diagramValues.get("equipmentName"));
            input.put("businessItemNo", diagramValues.get("businessItemNo"));
            input.put("capacity", diagramValues.get("capacity"));
            input.put("capacityUnit", "");
            input.put("equipmentQuantity", diagramValues.get("quantity"));
            String changedValue = switch (fieldToChange) {
                case "equipmentName" -> "Synthetic Modify " + suffix;
                case "businessItemNo" -> "SYN-MOD-ITEM-" + suffix;
                default -> (index == 0 ? "9" : "11") + " units";
            };
            switch (fieldToChange) {
                case "equipmentName" -> input.put("equipmentName", changedValue);
                case "businessItemNo" -> input.put("businessItemNo", changedValue);
                default -> input.put("equipmentQuantity", changedValue);
            }
            input.put("placementId", "PW01-05-BETWEEN-C-PW15009-C-PW15010");
            input.put("equipmentTypeLabel", diagramValues.get("equipmentTypeLabel"));
            input.put("wastewaterType", "TEST_ONLY wastewater " + suffix);
            input.put("wastewaterQuantity", index == 0 ? "2.4" : "3.6");
            input.put("wastewaterUnit", "ton/day");
            input.put("wastewaterPeriod", "2026-10-01/2026-10-31");
            input.put("wastewaterDestination", "SYNTH-WW-DEST-" + suffix);
            input.put("wastewaterEvidence", "SYNTH-WW-EVIDENCE-" + suffix);
            input.put("wasteType", "TEST_ONLY waste " + suffix);
            input.put("wasteQuantity", index == 0 ? "0.15" : "0.25");
            input.put("wasteUnit", "ton/day");
            input.put("wastePeriod", "2026-10-01/2026-10-31");
            input.put("wasteDestination", "SYNTH-WASTE-DEST-" + suffix);
            input.put("wasteEvidence", "SYNTH-WASTE-EVIDENCE-" + suffix);
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("sourceEquipment", "SYNTH-SOURCE-" + suffix);
            route.put("targetEquipment", "SYNTH-TARGET-" + suffix);
            route.put("sourceProcess", "PW-01"); route.put("targetProcess", "PW-01");
            route.put("pathDescription", "TEST_ONLY synthetic route " + suffix);
            route.put("flowType", "TEST_ONLY wastewater"); route.put("materialClass", "WASTEWATER");
            route.put("connectionType", "SERIES"); route.put("quantity", index == 0 ? "8.1" : "9.2");
            route.put("unit", "ton/day"); route.put("period", "2026-10-01/2026-10-31");
            route.put("evidenceReference", "SYNTH-ROUTE-EVIDENCE-" + suffix);
            route.put("internalRecycle", false); route.put("branchOrMerge", false); route.put("complete", true);
            input.put("routes", List.of(route));
            input.put("runSyntheticTestOnlyNetwork", false);
            input.put("syntheticNetworkScopeComplete", false);
            input.put("scopeCompleteAttested", false);

            Map<String, Object> preview = postPreview(objectMapper.writeValueAsString(input));
            assertThat(preview.get("changeType")).isEqualTo("MODIFY");
            assertThat(preview.get("editPlanStatus")).isEqualTo("TEST_ONLY_READY");
            assertThat(preview.get("automaticEditAllowed")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> plannedTarget = (Map<String, Object>) preview.get("modifyTarget");
            assertThat(plannedTarget.get("managementNo")).isEqualTo(targetNo);
            long requestId = ((Number) preview.get("requestId")).longValue();
            MvcResult stored = mockMvc.perform(get("/api/admin/workspace/requests/{requestId}", requestId)
                            .header("X-Admin-Token", adminToken))
                    .andExpect(status().isOk()).andReturn();
            var storedRequest = objectMapper.readTree(stored.getResponse().getContentAsByteArray()).path("request");
            assertThat(storedRequest.path("targetManagementNo").asText()).isEqualTo(targetNo);
            assertThat(storedRequest.path("managementNo").asText()).isEqualTo(targetNo);
            assertThat(storedRequest.path("wastewaterQuantity").decimalValue().toPlainString())
                    .isEqualTo(index == 0 ? "2.4" : "3.6");
            assertThat(storedRequest.path("wasteQuantity").decimalValue().toPlainString())
                    .isEqualTo(index == 0 ? "0.15" : "0.25");
            assertThat(storedRequest.path("routes").get(0).path("quantity").decimalValue().toPlainString())
                    .isEqualTo(index == 0 ? "8.1" : "9.2");
            assertThat(storedRequest.path("routes").get(0).path("evidenceReference").asText())
                    .isEqualTo("SYNTH-ROUTE-EVIDENCE-" + suffix);

            MvcResult generated = mockMvc.perform(post("/api/admin/workspace/requests/{requestId}/generate", requestId)
                            .header("X-Admin-Token", adminToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.requestStatus").value("GENERATED"))
                    .andExpect(jsonPath("$.balanceStatus").value("CALCULATION_UNAVAILABLE"))
                    .andExpect(jsonPath("$.officialBaselineModified").value(false))
                    .andExpect(jsonPath("$.officialApprovalCreated").value(false)).andReturn();
            var metadata = objectMapper.readTree(generated.getResponse().getContentAsByteArray());
            byte[] downloaded = mockMvc.perform(get("/api/admin/workspace/requests/{requestId}/download", requestId)
                            .header("X-Admin-Token", adminToken))
                    .andExpect(status().isOk()).andExpect(header().string("X-Official-Baseline-Modified", "false"))
                    .andReturn().getResponse().getContentAsByteArray();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded));
            outputHashes.add(hash);
            assertThat(metadata.path("sha256").asText()).isEqualTo(hash);
            assertThat(metadata.path("audit").path("drawingStatus").asText()).contains("DrawingML bytes remain identical");
            assertThat(metadata.path("audit").path("balanceStatus").asText()).isEqualTo("CALCULATION_UNAVAILABLE");
            verifyZip(downloaded);

            try (var output = WorkbookFactory.create(new ByteArrayInputStream(downloaded))) {
                var diagram = output.getSheet("PW-01-05");
                var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
                String expectedAddress = fieldCells.get(fieldToChange);
                Map<String, String> expected = Map.of(expectedAddress, changedValue);
                List<String> actualCellChanges = new ArrayList<>();
                var outputDiagram = output.getSheet("PW-01-05");
                var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
                try {
                    var originalDiagram = sourceWorkbook.getSheet("PW-01-05");
                    int maxRows = Math.max(originalDiagram.getLastRowNum(), outputDiagram.getLastRowNum());
                    for (int rowIndex = 0; rowIndex <= maxRows; rowIndex++) {
                        var beforeRow = originalDiagram.getRow(rowIndex); var afterRow = outputDiagram.getRow(rowIndex);
                        int maxCols = Math.max(beforeRow == null ? -1 : beforeRow.getLastCellNum(), afterRow == null ? -1 : afterRow.getLastCellNum());
                        for (int col = 0; col < maxCols; col++) {
                            var beforeCell = beforeRow == null ? null : beforeRow.getCell(col);
                            var afterCell = afterRow == null ? null : afterRow.getCell(col);
                            String beforeValue = beforeCell == null ? "" : formatter.formatCellValue(beforeCell);
                            String afterValue = afterCell == null ? "" : formatter.formatCellValue(afterCell);
                            if (!beforeValue.equals(afterValue)) actualCellChanges.add(new org.apache.poi.ss.util.CellReference(rowIndex, col).formatAsString());
                        }
                    }
                } finally { sourceWorkbook.close(); }
                assertThat(actualCellChanges).containsExactlyInAnyOrderElementsOf(expected.keySet());
                expected.forEach((address, value) -> {
                    var ref = new org.apache.poi.ss.util.CellReference(address);
                    assertThat(formatter.formatCellValue(outputDiagram.getRow(ref.getRow()).getCell(ref.getCol())))
                            .as("changed diagram cell %s", address).isEqualTo(value);
                });
                assertThat(output.getSheet("변경 전후")).isNotNull();
                assertThat(output.getSheet("도면 객체 비교")).isNotNull();
                assertThat(output.getSheet("사용자 입력사항")).isNotNull();
                var balanceSheet = output.getSheet("수지 검토 상태");
                assertThat(balanceSheet).isNotNull();
                StringBuilder balanceText = new StringBuilder();
                for (var row : balanceSheet) for (var cell : row) balanceText.append(formatter.formatCellValue(cell)).append(' ');
                assertThat(balanceText.toString()).contains("CALCULATION_UNAVAILABLE");
            }
            Map<String, byte[]> sourceParts = readZipParts(sourceBytes);
            Map<String, byte[]> outputParts = readZipParts(downloaded);
            assertThat(outputParts.get(sourceSheet.drawingPartName())).containsExactly(sourceParts.get(sourceSheet.drawingPartName()));
            var outputDrawing = inspector.inspect(downloaded).sheets().stream()
                    .filter(sheet -> "PW-01-05".equals(sheet.sheetName())).findFirst().orElseThrow();
            assertThat(outputDrawing.drawingObjects()).containsExactlyElementsOf(sourceSheet.drawingObjects());
            Path file = artifacts.resolve(String.valueOf(metadata.path("filename").asText()));
            Files.write(file, downloaded);
            assertThat(Files.readAllBytes(file)).containsExactly(downloaded);
        }
        assertThat(outputHashes).hasSize(2).doesNotHaveDuplicates();
        assertThat(outputHashes.get(0)).isNotEqualTo(outputHashes.get(1));
        assertThat(jdbc.queryForObject("SELECT LOWER(sha256) FROM baseline_document WHERE id=12", String.class)).isEqualTo(officialHash);
        assertThat(jdbc.queryForObject("SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36", String.class)).isEqualTo(candidateHash);
        for (Map.Entry<Long, String> entry : protectedRequestHashes.entrySet()) {
            assertThat(jdbc.queryForObject("SELECT COALESCE(LOWER(output_sha256),'') FROM process_change_request WHERE id=?",
                    String.class, entry.getKey())).isEqualTo(entry.getValue());
        }
        TestTransaction.flagForRollback();
    }

    @Test
    void exportsNineteenEquipmentMappingReviewWorkbookWithUnapprovedReviewerInputs() throws Exception {
        Path artifacts = Files.createTempDirectory(Path.of("/tmp"), "pw0105-mapping-review-");
        Files.createDirectories(artifacts);
        String officialHash = jdbc.queryForObject("SELECT LOWER(sha256) FROM baseline_document WHERE id=12", String.class);
        String candidateHash = jdbc.queryForObject("SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36", String.class);
        MvcResult targetsResponse = mockMvc.perform(get("/api/admin/workspace/modify-targets")
                        .param("workingDocumentId", "36").param("sheetName", "PW-01-05")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> targets = objectMapper.readValue(targetsResponse.getResponse().getContentAsByteArray(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(targets).hasSize(19);
        assertThat(targets).noneMatch(target -> Boolean.TRUE.equals(target.get("eligible")));
        Map<String, Object> grouped = targets.stream().filter(target -> "C-PW15011".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        assertThat(grouped.get("equipmentBoxMappingStatus")).isEqualTo("AGGREGATED_MULTI_MASTER_BOX");
        assertThat(grouped.get("identityVerified")).isEqualTo(false);
        assertThat(grouped.get("otherMasterIdsInsideBox").toString()).contains("C-PW15016@AO21");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> groupedFields = (Map<String, Map<String, Object>>) grouped.get("fieldAssessments");
        assertThat(groupedFields.values()).noneMatch(field -> Boolean.TRUE.equals(field.get("modifiable")));
        Map<String, Object> labelOnly = targets.stream().filter(target -> "C-PW15016".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> occurrences = (List<Map<String, Object>>) labelOnly.get("diagramIdOccurrenceDetails");
        assertThat(occurrences).anySatisfy(occurrence -> {
            assertThat(occurrence.get("cell")).isEqualTo("AO21");
            assertThat(occurrence.get("roleAssessment")).isEqualTo("SPATIALLY_INSIDE_OTHER_MASTER_BOX_IDENTITY_UNRESOLVED");
        });
        Map<String, Object> missing = targets.stream().filter(target -> "C-PW15012".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        assertThat(missing.get("identityVerified")).isEqualTo(false);
        assertThat(missing.get("candidateEquipmentBoxes")).isEqualTo(List.of());
        Map<String, Object> unique = targets.stream().filter(target -> "C-PW15001".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        assertThat(unique.get("identityVerified")).isEqualTo(true);
        assertThat(unique.get("eligible")).isEqualTo(false);
        assertThat(unique.get("reviewRequiredItems").toString()).contains("마스터와 도면 값이 다릅니다.");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> uniqueFields = (Map<String, Map<String, Object>>) unique.get("fieldAssessments");
        assertThat(uniqueFields.values()).allSatisfy(field -> assertThat(field.get("modifiable")).isEqualTo(true));
        Map<String, Object> repeatedId = targets.stream().filter(target -> "C-PW15018".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> repeatedFields = (Map<String, Map<String, Object>>) repeatedId.get("fieldAssessments");
        assertThat(repeatedFields.get("managementNo").get("modifiable")).isEqualTo(false);
        assertThat(List.of("equipmentName", "businessItemNo", "capacity", "quantity"))
                .allSatisfy(field -> assertThat(repeatedFields.get(field).get("modifiable")).isEqualTo(true));
        Map<String, Object> referenceLabel = targets.stream().filter(target -> "C-PW15017".equals(target.get("managementNo")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> referenceOccurrences = (List<Map<String, Object>>) referenceLabel.get("diagramIdOccurrenceDetails");
        assertThat(referenceOccurrences).anySatisfy(occurrence -> {
            assertThat(occurrence.get("cell")).isEqualTo("D78");
            assertThat(occurrence.get("roleAssessment")).isEqualTo("DIAGRAM_REFERENCE_TABLE_LABEL");
        });
        @SuppressWarnings("unchecked")
        Map<String, String> uniqueDiagramValues = (Map<String, String>) unique.get("diagramValues");
        Map<String, Object> blockedModifyInput = userInput("C-PW15001", uniqueDiagramValues.get("equipmentName"),
                uniqueDiagramValues.get("businessItemNo"), uniqueDiagramValues.get("capacity"), "",
                uniqueDiagramValues.get("quantity"), false);
        blockedModifyInput.put("changeType", "MODIFY");
        blockedModifyInput.put("targetManagementNo", "C-PW15001");
        blockedModifyInput.put("equipmentName", "TEST_ONLY proposed name change");
        Map<String, Object> blockedModify = postPreview(objectMapper.writeValueAsString(blockedModifyInput));
        assertThat(blockedModify.get("automaticEditAllowed")).isEqualTo(false);
        assertThat(blockedModify.get("editPlanStatus")).isEqualTo("GENERATION_BLOCKED");
        assertThat(blockedModify.get("reviewRequiredItems").toString()).contains("마스터와 도면 값이 다릅니다.");
        MvcResult response = mockMvc.perform(get("/api/admin/workspace/modify-targets/review-workbook")
                        .param("workingDocumentId", "36").param("sheetName", "PW-01-05")
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk()).andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andReturn();
        byte[] bytes = response.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(response.getResponse().getHeader("X-Output-SHA256")).isEqualTo(hash);
        verifyZip(bytes);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            var mapping = workbook.getSheet("설비19건 매핑");
            var fields = workbook.getSheet("필드별 수정판정");
            var drawing = workbook.getSheet("DrawingML 증거");
            var reviewer = workbook.getSheet("담당자 확인 입력");
            assertThat(mapping.getLastRowNum()).isEqualTo(22);
            assertThat(fields.getLastRowNum()).isEqualTo(98);
            assertThat(reviewer.getLastRowNum()).isEqualTo(98);
            assertThat(mapping.getRow(3).getCell(0).getStringCellValue()).isEqualTo("마스터 설비번호");
            assertThat(mapping.getRow(4).getCell(0).getStringCellValue()).isEqualTo("C-PW15001");
            assertThat(mapping.getRow(4).getCell(2).getStringCellValue()).isEqualTo("WW-F-STK01");
            assertThat(fields.getRow(3).getCell(11).getStringCellValue()).isEqualTo("기술적 수정 가능");
            assertThat(reviewer.getRow(3).getCell(13).getStringCellValue()).isEqualTo("공식 승인 상태");
            assertThat(reviewer.getRow(4).getCell(13).getStringCellValue()).isEqualTo("미승인");
            assertThat(reviewer.getRow(4).getCell(4).getStringCellValue()).isEmpty();
            for (var sheet : List.of(mapping, fields, drawing, reviewer)) {
                for (var row : sheet) for (var cell : row) {
                    if (new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell).isBlank()) continue;
                    var font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                    assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                    assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                }
            }
            Path file = artifacts.resolve("pw0105-mapping-review_TEST_ONLY.xlsx");
            Files.write(file, bytes);
            assertThat(Files.readAllBytes(file)).containsExactly(bytes);
        }
        assertThat(jdbc.queryForObject("SELECT LOWER(sha256) FROM baseline_document WHERE id=12", String.class)).isEqualTo(officialHash);
        assertThat(jdbc.queryForObject("SELECT LOWER(working_sha256) FROM process_working_document WHERE id=36", String.class)).isEqualTo(candidateHash);
        TestTransaction.flagForRollback();
    }

    private GeneratedCase generateAndVerify(Map<String, Object> input, Path artifactDirectory) throws Exception {
        String payload = objectMapper.writeValueAsString(input);
        Map<String, Object> preview = postPreview(payload);
        assertThat(preview.get("editPlanStatus")).isEqualTo("TEST_ONLY_READY");
        assertThat(preview.get("automaticEditAllowed")).isEqualTo(true);
        assertThat(preview.get("placementId")).isEqualTo("PW01-05-BETWEEN-C-PW15009-C-PW15010");
        assertThat(preview.get("workingDisposition")).isEqualTo("SAFE_CANDIDATE_NOT_APPROVED_BASELINE");
        long requestId = ((Number) preview.get("requestId")).longValue();
        assertThat(requestId).isGreaterThan(5L);

        MvcResult readback = mockMvc.perform(get("/api/admin/workspace/requests/{requestId}", requestId)
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk()).andReturn();
        var storedRequest = objectMapper.readTree(readback.getResponse().getContentAsByteArray()).path("request");
        assertThat(storedRequest.path("managementNo").asText()).isEqualTo(String.valueOf(input.get("managementNo")));
        assertThat(storedRequest.path("equipmentName").asText()).isEqualTo(String.valueOf(input.get("equipmentName")));
        assertThat(storedRequest.path("capacity").asText()).isEqualTo(String.valueOf(input.get("capacity")));
        assertThat(storedRequest.path("equipmentQuantity").asText()).isEqualTo(String.valueOf(input.get("equipmentQuantity")));
        assertThat(storedRequest.path("placementId").asText()).isEqualTo("PW01-05-BETWEEN-C-PW15009-C-PW15010");
        if (Boolean.TRUE.equals(input.get("runSyntheticTestOnlyNetwork"))) {
            assertThat(storedRequest.path("wastewaterQuantity").asText()).isEqualTo("12.5");
            assertThat(storedRequest.path("wasteQuantity").asText()).isEqualTo("0.25");
            assertThat(storedRequest.path("routes").get(0).path("sourceEquipment").asText())
                    .isEqualTo("EXT-SOURCE:SYNTH-BOUNDARY-A");
            assertThat(storedRequest.path("routes").get(0).path("evidenceReference").asText())
                    .isEqualTo("SYNTH-A-IN-EVIDENCE");
        }

        MvcResult generation = mockMvc.perform(post("/api/admin/workspace/requests/{requestId}/generate", requestId)
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("GENERATED"))
                .andExpect(jsonPath("$.balanceStatus").value("CALCULATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.officialBaselineModified").value(false))
                .andExpect(jsonPath("$.officialApprovalCreated").value(false))
                .andReturn();
        var generatedMetadata = objectMapper.readTree(generation.getResponse().getContentAsByteArray());
        String filename = generatedMetadata.path("filename").asText();
        String syntheticStatus = generatedMetadata.path("audit").path("syntheticTestOnlyCalculation").path("status").asText();
        if (Boolean.TRUE.equals(input.get("runSyntheticTestOnlyNetwork"))) {
            assertThat(syntheticStatus).isEqualTo("CALCULATED");
        } else {
            assertThat(syntheticStatus).isEqualTo("NOT_REQUESTED");
        }

        byte[] downloaded = mockMvc.perform(get("/api/admin/workspace/requests/{requestId}/download", requestId)
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Official-Baseline-Modified", "false"))
                .andReturn().getResponse().getContentAsByteArray();
        String downloadedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded));
        assertThat(generatedMetadata.path("sha256").asText()).isEqualTo(downloadedHash);
        assertThat(jdbc.queryForObject("SELECT LOWER(output_sha256) FROM process_change_request WHERE id=?",
                String.class, requestId)).isEqualTo(downloadedHash);
        verifyZip(downloaded);

        String managementNo = String.valueOf(input.get("managementNo"));
        String equipmentName = String.valueOf(input.get("equipmentName"));
        var sourceWorkingCopy = workingCopies.getWorkingCopy(36);
        String itemNo = String.valueOf(input.get("businessItemNo"));
        String capacity = String.valueOf(input.get("capacity"));
        String capacityUnit = String.valueOf(input.get("capacityUnit"));
        String quantity = String.valueOf(input.get("equipmentQuantity"));
        String diagramCell = verifyWorkbookDiagram(downloaded, managementNo, equipmentName, itemNo,
                capacity + capacityUnit, quantity);
        verifyDiagramStructure(sourceWorkingCopy.bytes(), downloaded, preview, managementNo);
        Path output = artifactDirectory.resolve(filename);
        Files.write(output, downloaded);
        assertThat(Files.readAllBytes(output)).containsExactly(downloaded);
        return new GeneratedCase(requestId, managementNo, equipmentName, itemNo, capacity + capacityUnit,
                quantity, String.valueOf(input.get("placementId")), diagramCell, filename, downloadedHash,
                downloaded, syntheticStatus, output);
    }

    private Map<String, Object> postPreview(String payload) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/admin/workspace/change-preview")
                        .header("X-Admin-Token", adminToken)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(response.getResponse().getContentAsByteArray(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private Map<String, Object> userInput(String id, String name, String itemNo,
                                          String capacity, String capacityUnit, String quantity,
                                          boolean withSyntheticNetwork) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workingDocumentId", 36);
        input.put("sheetName", "PW-01-05");
        input.put("changeType", "ADD");
        input.put("managementNo", id);
        input.put("equipmentName", name);
        input.put("businessItemNo", itemNo);
        input.put("capacity", capacity);
        input.put("capacityUnit", capacityUnit);
        input.put("equipmentQuantity", quantity);
        input.put("placementId", "PW01-05-BETWEEN-C-PW15009-C-PW15010");
        input.put("equipmentTypeLabel", "TEST_ONLY 설비");
        input.put("wastewaterType", withSyntheticNetwork ? "TEST_ONLY 합성폐수" : "");
        input.put("wastewaterQuantity", withSyntheticNetwork ? "12.5" : null);
        input.put("wastewaterUnit", withSyntheticNetwork ? "ton/day" : "");
        input.put("wastewaterPeriod", withSyntheticNetwork ? "2026-10-01" : "");
        input.put("wastewaterDestination", withSyntheticNetwork ? "SYNTH-BOUNDARY-A-OUT" : "");
        input.put("wastewaterEvidence", withSyntheticNetwork ? "SYNTH-A-WW-EVIDENCE" : "");
        input.put("wasteType", withSyntheticNetwork ? "TEST_ONLY 합성여과잔재" : "");
        input.put("wasteQuantity", withSyntheticNetwork ? "0.25" : null);
        input.put("wasteUnit", withSyntheticNetwork ? "ton/day" : "");
        input.put("wastePeriod", withSyntheticNetwork ? "2026-10-01" : "");
        input.put("wasteDestination", withSyntheticNetwork ? "SYNTH-WASTE-HOLD" : "");
        input.put("wasteEvidence", withSyntheticNetwork ? "SYNTH-A-WASTE-EVIDENCE" : "");
        input.put("routes", withSyntheticNetwork ? syntheticRoutes() : List.of());
        input.put("runSyntheticTestOnlyNetwork", withSyntheticNetwork);
        input.put("syntheticNetworkScopeComplete", withSyntheticNetwork);
        input.put("scopeCompleteAttested", false);
        return input;
    }

    private List<Map<String, Object>> syntheticRoutes() {
        return List.of(
                syntheticRoute("EXT-SOURCE:SYNTH-BOUNDARY-A", "SYNTH-PW01-TEST-A", "EXTERNAL_INFLOW", "SYNTH-A-IN-EVIDENCE"),
                syntheticRoute("SYNTH-PW01-TEST-A", "EXT-SINK:SYNTH-BOUNDARY-A", "EXTERNAL_OUTFLOW", "SYNTH-A-OUT-EVIDENCE"));
    }

    private Map<String, Object> syntheticRoute(String source, String target, String type, String evidence) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("sourceEquipment", source);
        route.put("targetEquipment", target);
        route.put("sourceProcess", "PW-01");
        route.put("targetProcess", "PW-01");
        route.put("pathDescription", "TEST_ONLY synthetic route; no plant evidence");
        route.put("flowType", "TEST_ONLY wastewater fixture");
        route.put("materialClass", "WASTEWATER");
        route.put("connectionType", type);
        route.put("quantity", "12.5");
        route.put("unit", "ton/day");
        route.put("period", "2026-10-01");
        route.put("evidenceReference", evidence);
        route.put("internalRecycle", false);
        route.put("branchOrMerge", false);
        route.put("complete", true);
        return route;
    }

    private String verifyWorkbookDiagram(byte[] bytes, String id, String name, String itemNo,
                                          String capacity, String quantity) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            assertThat(workbook.getNumberOfSheets()).isEqualTo(10);
            var diagram = workbook.getSheet("PW-01-05");
            assertThat(diagram).isNotNull();
            List<org.apache.poi.ss.usermodel.Cell> ids = new ArrayList<>();
            for (var row : diagram) for (var cell : row) if (id.equals(formatter.formatCellValue(cell))) ids.add(cell);
            assertThat(ids).hasSize(1);
            var idCell = ids.get(0);
            assertThat(formatter.formatCellValue(diagram.getRow(idCell.getRowIndex() + 3).getCell(idCell.getColumnIndex())))
                    .isEqualTo(name);
            assertThat(formatter.formatCellValue(diagram.getRow(idCell.getRowIndex() + 4).getCell(idCell.getColumnIndex())))
                    .isEqualTo(itemNo);
            assertThat(formatter.formatCellValue(diagram.getRow(idCell.getRowIndex() + 5).getCell(idCell.getColumnIndex())))
                    .isEqualTo(capacity);
            assertThat(formatter.formatCellValue(diagram.getRow(idCell.getRowIndex() + 6).getCell(idCell.getColumnIndex())))
                    .isEqualTo(quantity);
            assertThat(workbook.getSheet("변경 전후")).isNotNull();
            assertThat(workbook.getSheet("도면 객체 비교")).isNotNull();
            assertThat(workbook.getSheet("사용자 입력사항")).isNotNull();
            assertThat(workbook.getSheet("수지 검토 상태")).isNotNull();
            return idCell.getAddress().formatAsString();
        }
    }

    private void writeComparisonTable(Path artifactDirectory, List<GeneratedCase> cases) throws Exception {
        List<String> header = List.of("request_id", "management_no", "equipment_name", "item_no", "capacity",
                "quantity", "placement_id", "diagram_cell", "diagram_value_verified", "synthetic_balance_status",
                "actual_process_balance_status", "official_approval", "sha256", "filename");
        StringBuilder tsv = new StringBuilder(String.join("\t", header)).append('\n');
        for (GeneratedCase item : cases) {
            List<String> values = List.of(Long.toString(item.requestId()), item.managementNo(), item.name(), item.itemNo(),
                    item.capacity(), item.quantity(), item.placementId(), item.diagramCell(),
                    "PASS: managementNo/name/item/capacity/quantity", item.syntheticStatus(),
                    "CALCULATION_UNAVAILABLE", "false", item.sha256(), item.filename());
            tsv.append(values.stream().map(value -> value.replace("\t", " ").replace("\r", " ").replace("\n", " "))
                    .collect(java.util.stream.Collectors.joining("\t"))).append('\n');
        }
        Files.writeString(artifactDirectory.resolve("input-to-diagram-comparison.tsv"), tsv.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private void verifyDiagramStructure(byte[] sourceBytes, byte[] outputBytes,
                                        Map<String, Object> preview, String managementNo) throws Exception {
        var sourceInventory = inspector.inspect(sourceBytes);
        var outputInventory = inspector.inspect(outputBytes);
        var sourceSheet = sourceInventory.sheets().stream().filter(sheet -> "PW-01-05".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        var outputSheet = outputInventory.sheets().stream().filter(sheet -> "PW-01-05".equals(sheet.sheetName()))
                .findFirst().orElseThrow();
        assertThat(outputInventory.shapeCount()).isEqualTo(sourceInventory.shapeCount());
        assertThat(outputInventory.connectorCount()).isEqualTo(sourceInventory.connectorCount() + 1);
        assertThat(outputInventory.pictureCount()).isEqualTo(sourceInventory.pictureCount());
        @SuppressWarnings("unchecked")
        Map<String, Object> insertion = (Map<String, Object>) preview.get("insertion");
        int insertBeforeRow = ((Number) insertion.get("insertBeforeRow")).intValue();
        int rowsInserted = ((Number) insertion.get("rowsInserted")).intValue();
        String originalConnectorId = String.valueOf(insertion.get("connectorShapeId"));
        Map<String, com.example.processautomation.excel.WorkbookInventory.DrawingObject> afterById = new LinkedHashMap<>();
        outputSheet.drawingObjects().forEach(object -> afterById.put(object.shapeId(), object));
        for (var before : sourceSheet.drawingObjects()) {
            var after = afterById.get(before.shapeId());
            assertThat(after).as("preserved drawing object id " + before.shapeId()).isNotNull();
            assertThat(after.objectName()).isEqualTo(before.objectName());
            assertThat(after.objectType()).isEqualTo(before.objectType());
            assertThat(after.textContent()).isEqualTo(before.textContent());
            assertThat(after.tailArrowType()).isEqualTo(before.tailArrowType());
            boolean originalConnector = originalConnectorId.equals(before.shapeId());
            if (before.startRow() != null && !originalConnector) {
                int expectedStart = before.startRow() + 1 >= insertBeforeRow ? before.startRow() + rowsInserted : before.startRow();
                assertThat(after.startRow()).as("shifted drawing start row " + before.shapeId()).isEqualTo(expectedStart);
            }
            if (before.endRow() != null && !originalConnector) {
                int expectedEnd = before.endRow() + 1 >= insertBeforeRow ? before.endRow() + rowsInserted : before.endRow();
                assertThat(after.endRow()).as("shifted drawing end row " + before.shapeId()).isEqualTo(expectedEnd);
            }
        }
        assertThat(outputSheet.drawingObjects()).anySatisfy(object ->
                assertThat(object.objectName()).isEqualTo("TEST_ONLY " + managementNo + " flow connector"));
        assertThat(outputSheet.drawingObjects()).noneMatch(object -> object.objectName() != null
                && object.objectName().contains("TEST_ONLY / REVIEW_REQUIRED"));

        try (var source = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
             var output = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
            var oldDiagram = source.getSheet("PW-01-05");
            var newDiagram = output.getSheet("PW-01-05");
            assertThat(newDiagram.getNumMergedRegions()).isEqualTo(oldDiagram.getNumMergedRegions() + 1);
            assertThat(output.getActiveSheetIndex()).isEqualTo(output.getSheetIndex("PW-01-05"));
            assertThat((int) newDiagram.getTopRow()).isEqualTo(197);
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            for (var row : oldDiagram) {
                int outputRowIndex = row.getRowNum() + 1 >= insertBeforeRow
                        ? row.getRowNum() + rowsInserted : row.getRowNum();
                var outputRow = newDiagram.getRow(outputRowIndex);
                assertThat(outputRow).as("preserved PW-01-05 row " + row.getRowNum()).isNotNull();
                for (var cell : row) {
                    var outputCell = outputRow.getCell(cell.getColumnIndex(),
                            org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    assertThat(outputCell).as("preserved diagram cell " + cell.getAddress()).isNotNull();
                    assertThat(outputCell.getCellStyle().getIndex()).as("preserved style " + cell.getAddress())
                            .isEqualTo(cell.getCellStyle().getIndex());
                    assertThat(outputCell.getCellType()).as("preserved type " + cell.getAddress()).isEqualTo(cell.getCellType());
                    assertThat(formatter.formatCellValue(outputCell)).as("preserved display " + cell.getAddress())
                            .isEqualTo(formatter.formatCellValue(cell));
                }
            }
        }
        Map<String, byte[]> beforeParts = readZipParts(sourceBytes);
        Map<String, byte[]> afterParts = readZipParts(outputBytes);
        String worksheetPart = sourceSheet.worksheetPartName();
        String drawingPart = sourceSheet.drawingPartName();
        Set<String> expectedChanged = Set.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "[Content_Types].xml",
                "docProps/app.xml", "xl/styles.xml", worksheetPart, drawingPart);
        for (String part : beforeParts.keySet()) {
            if (!expectedChanged.contains(part)) assertThat(afterParts.get(part)).as("preserved OOXML part " + part)
                    .containsExactly(beforeParts.get(part));
        }
        assertStylesAppendOnly(beforeParts.get("xl/styles.xml"), afterParts.get("xl/styles.xml"), outputBytes);
        Set<String> additions = new java.util.HashSet<>(afterParts.keySet());
        additions.removeAll(beforeParts.keySet());
        assertThat(additions).hasSize(4).allMatch(part -> part.matches("xl/worksheets/sheet\\d+\\.xml"));
    }

    private void assertStylesAppendOnly(byte[] sourceStyles, byte[] outputStyles, byte[] workbookBytes) throws Exception {
        var sourceDocument = parseStyles(sourceStyles);
        var outputDocument = parseStyles(outputStyles);
        var sourceRoot = sourceDocument.getDocumentElement();
        var outputRoot = outputDocument.getDocumentElement();
        for (String catalogName : List.of("numFmts", "fills", "borders", "cellStyleXfs", "cellStyles", "dxfs", "tableStyles")) {
            var beforeCatalog = directStyleChild(sourceRoot, catalogName);
            var afterCatalog = directStyleChild(outputRoot, catalogName);
            if (beforeCatalog == null) assertThat(afterCatalog).as(catalogName + " not newly introduced").isNull();
            else assertThat(afterCatalog.isEqualNode(beforeCatalog)).as(catalogName + " remains unchanged").isTrue();
        }
        var beforeFonts = styleChildren(directStyleChild(sourceRoot, "fonts"));
        var afterFonts = styleChildren(directStyleChild(outputRoot, "fonts"));
        var beforeXfs = styleChildren(directStyleChild(sourceRoot, "cellXfs"));
        var afterXfs = styleChildren(directStyleChild(outputRoot, "cellXfs"));
        for (int i = 0; i < beforeFonts.size(); i++) {
            assertThat(afterFonts.get(i).isEqualNode(beforeFonts.get(i))).as("existing font ID " + i).isTrue();
        }
        for (int i = 0; i < beforeXfs.size(); i++) {
            assertThat(afterXfs.get(i).isEqualNode(beforeXfs.get(i))).as("existing style ID " + i).isTrue();
        }
        assertThat(afterFonts.size()).isGreaterThanOrEqualTo(beforeFonts.size());
        assertThat(afterXfs.size()).isGreaterThanOrEqualTo(beforeXfs.size());
        for (int i = beforeFonts.size(); i < afterFonts.size(); i++) {
            var fontColor = directStyleChild(afterFonts.get(i), "color");
            var fontName = directStyleChild(afterFonts.get(i), "name");
            assertThat(fontColor).as("new font color ID " + i).isNotNull();
            assertThat(fontColor.getAttribute("rgb")).isEqualToIgnoringCase("FF000000");
            assertThat(fontColor.hasAttribute("theme")).isFalse();
            assertThat(fontName).as("new font family ID " + i).isNotNull();
            assertThat(fontName.getAttribute("val")).isEqualTo("맑은 고딕");
        }
        int generatedEquipmentStyleEnd = beforeXfs.size() + 5;
        for (int i = beforeXfs.size(); i < afterXfs.size(); i++) {
            var xf = afterXfs.get(i);
            int fontId = Integer.parseInt(xf.getAttribute("fontId"));
            assertThat(fontId).isBetween(beforeFonts.size(), afterFonts.size() - 1);
            assertThat(xf.getAttribute("applyFont")).isEqualTo("1");
            boolean equipmentCellStyle = i < generatedEquipmentStyleEnd;
            assertThat(beforeXfs.stream().anyMatch(original ->
                    sameStyleExceptFont(original, xf, equipmentCellStyle)))
                    .as("new style ID " + i + " preserves source style except permitted readability changes").isTrue();
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            for (String sheetName : List.of("변경 전후", "도면 객체 비교", "사용자 입력사항", "수지 검토 상태")) {
                var sheet = workbook.getSheet(sheetName);
                assertThat(sheet).as("result sheet " + sheetName).isNotNull();
                for (var row : sheet) for (var cell : row) {
                    if (new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell).isBlank()) continue;
                    assertThat((int) cell.getCellStyle().getIndex()).isGreaterThanOrEqualTo(beforeXfs.size());
                    var font = (org.apache.poi.xssf.usermodel.XSSFFont)
                            workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                    assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                    assertThat(font.getXSSFColor()).isNotNull();
                    assertThat(font.getXSSFColor().getARGBHex()).isEqualToIgnoringCase("FF000000");
                }
            }
        }
        System.out.printf("WORKSPACE_STYLE_APPEND_ONLY numFmts=%d/%d fonts=%d/%d fills=%d/%d borders=%d/%d cellXfs=%d/%d%n",
                styleChildren(directStyleChild(sourceRoot, "numFmts")).size(), styleChildren(directStyleChild(outputRoot, "numFmts")).size(),
                beforeFonts.size(), afterFonts.size(), styleChildren(directStyleChild(sourceRoot, "fills")).size(),
                styleChildren(directStyleChild(outputRoot, "fills")).size(), styleChildren(directStyleChild(sourceRoot, "borders")).size(),
                styleChildren(directStyleChild(outputRoot, "borders")).size(), beforeXfs.size(), afterXfs.size());
    }

    private org.w3c.dom.Document parseStyles(byte[] bytes) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private org.w3c.dom.Element directStyleChild(org.w3c.dom.Element parent, String localName) {
        if (parent == null) return null;
        for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private List<org.w3c.dom.Element> styleChildren(org.w3c.dom.Element parent) {
        List<org.w3c.dom.Element> result = new ArrayList<>();
        if (parent != null) for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element) result.add(element);
        }
        return result;
    }

    private boolean sameStyleExceptFont(org.w3c.dom.Element source, org.w3c.dom.Element variant,
                                        boolean requireEquipmentReadabilityAlignment) {
        // POI may make equivalent border/fill IDs explicit with applyBorder/applyFill on appended XFs.
        for (String attribute : List.of("numFmtId", "fillId", "borderId", "xfId", "alignmentId",
                "protectionId", "quotePrefix", "pivotButton")) {
            if (!source.getAttribute(attribute).equals(variant.getAttribute(attribute))) return false;
        }
        for (String childName : List.of("protection", "extLst")) {
            var sourceChild = directStyleChild(source, childName);
            var variantChild = directStyleChild(variant, childName);
            if (sourceChild == null ? variantChild != null : variantChild == null || !sourceChild.isEqualNode(variantChild)) return false;
        }
        String sourceApplyAlignment = source.getAttribute("applyAlignment");
        String variantApplyAlignment = variant.getAttribute("applyAlignment");
        if ((!sourceApplyAlignment.isEmpty() && !"1".equals(sourceApplyAlignment))
                || (!variantApplyAlignment.isEmpty() && !"1".equals(variantApplyAlignment))) return false;
        var sourceAlignment = directStyleChild(source, "alignment");
        var variantAlignment = directStyleChild(variant, "alignment");
        if (requireEquipmentReadabilityAlignment
                && (!"1".equals(variantApplyAlignment) || variantAlignment == null
                || !"1".equals(variantAlignment.getAttribute("shrinkToFit"))
                || !"0".equals(variantAlignment.getAttribute("wrapText")))) return false;
        if (variantAlignment == null) return sourceAlignment == null;
        var normalizedAlignment = (org.w3c.dom.Element) variantAlignment.cloneNode(true);
        normalizedAlignment.removeAttribute("shrinkToFit");
        normalizedAlignment.removeAttribute("wrapText");
        if (sourceAlignment != null) {
            var normalizedSourceAlignment = (org.w3c.dom.Element) sourceAlignment.cloneNode(true);
            normalizedSourceAlignment.removeAttribute("shrinkToFit");
            normalizedSourceAlignment.removeAttribute("wrapText");
            return normalizedSourceAlignment.isEqualNode(normalizedAlignment);
        }
        if (normalizedAlignment.hasAttributes()) return false;
        for (org.w3c.dom.Node node = normalizedAlignment.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element
                    || (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE && !node.getTextContent().isBlank())) return false;
        }
        return true;
    }

    private Map<String, byte[]> readZipParts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return parts;
    }

    private void verifyZip(byte[] bytes) throws Exception {
        Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                assertThat(names.add(entry.getName())).isTrue();
                zip.readAllBytes();
                zip.closeEntry();
                count++;
            }
            assertThat(count).isGreaterThan(10);
        }
    }

    private record GeneratedCase(long requestId, String managementNo, String name, String itemNo,
                                 String capacity, String quantity, String placementId, String diagramCell,
                                 String filename, String sha256, byte[] bytes, String syntheticStatus,
                                 Path outputPath) { }

    @Test
    void rejectsFilenameWithoutExactProcessCodeBeforePersisting() throws Exception {
        byte[] official = jdbc.queryForObject(
                "SELECT content_bytes FROM baseline_document WHERE id=12 AND document_role='PROCESS'", byte[].class);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM process_working_document", Long.class);

        assertThatThrownBy(() -> workingCopies.registerNumericCopy(12, "wrong-name.xlsx", official))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_working_document", Long.class)).isEqualTo(before);
        TestTransaction.flagForRollback();
    }
}
