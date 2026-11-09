package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.MasterWorkbookParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FlowMappingReviewActualDataExportTest {
    private static final Pattern CELL_RANGE = Pattern.compile("^([A-Z]+[0-9]+):([A-Z]+[0-9]+)$");
    private static final String SOURCE_FILENAME = "0.3 250808 " + (char) 0xBB3C + (char) 0xC9C8 + (char) 0xC218 + (char) 0xC9C0 + " DB.xlsm";
    private static final String SOURCE_SHA256 = "565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa";
    private static final String MASTER_SHA256 = "1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "step8.sourceDir", matches = ".+")
    void createsAndReopensActualWorkbookWithoutDatabaseOrSourceWrites() throws Exception {
        Path sourcePath = resolveSource(Path.of(System.getProperty("step8.sourceDir")));
        Path referenceDirectory = Path.of(System.getProperty("step8.referenceDir"));
        Path masterPath = resolveBySha(referenceDirectory, MASTER_SHA256);
        Path crosswalkPath = Path.of(System.getProperty("step8.crosswalk"));
        Path outputPath = Path.of(System.getProperty("step8.output"));
        Path validationPath = Path.of(System.getProperty("step8.validation"));
        String actualSourceSha = sha256(Files.readAllBytes(sourcePath));
        assertThat(actualSourceSha).isEqualTo(SOURCE_SHA256);
        assertThat(Files.exists(outputPath)).isFalse();
        assertThat(Files.exists(validationPath)).isFalse();

        JsonNode crosswalk = mapper.readTree(crosswalkPath.toFile());
        JsonNode priorSummary = crosswalk.path("summary");
        assertThat(priorSummary.path("sourceSha256").asText()).isEqualTo(actualSourceSha);
        assertThat(crosswalk.path("rows").size()).isEqualTo(3082);
        validatePriorInventoryHashes(priorSummary.path("officialInventory").path("workbooks"), referenceDirectory);

        byte[] sourceBytes = Files.readAllBytes(sourcePath);
        byte[] masterBytes = Files.readAllBytes(masterPath);
        JsonNode precisionSourceRow = null;
        for (JsonNode row : crosswalk.path("rows")) {
            if (row.path("dbRow").asInt() == 3058) { precisionSourceRow = row; break; }
        }
        assertThat(precisionSourceRow).isNotNull();
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var rawCell = sourceWorkbook.getSheet("입력시트").getRow(3057).getCell(3);
            assertThat(rawCell.getNumericCellValue()).isEqualTo(new java.math.BigDecimal(
                    precisionSourceRow.path("quantityNumeric").asText()).doubleValue());
        }
        var evidence = new Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot(
                16, new MasterWorkbookParser().parse(masterBytes).equipment().size(), 0, 0, 0,
                0, 0, "LOCAL_FILES_ONLY_DB_NOT_REVALIDATED");
        var report = new Step7MaterialBalanceComparisonService(new MasterWorkbookParser())
                .compare(sourceBytes, masterBytes, SOURCE_FILENAME, evidence);
        assertThat(report.sourceSha256()).isEqualTo(actualSourceSha);
        assertThat(report.inputRows()).isEqualTo(priorSummary.path("inputRows").asLong());
        assertThat(report.positiveQuantityRows()).isEqualTo(priorSummary.path("quantityCounts").path("POSITIVE").asLong());
        assertThat(report.zeroQuantityRows()).isEqualTo(priorSummary.path("quantityCounts").path("ZERO").asLong());
        assertThat(report.blankQuantityRows()).isEqualTo(priorSummary.path("quantityCounts").path("BLANK").asLong());
        assertThat(report.blankMaterialTypeRows()).isEqualTo(priorSummary.path("blankMaterialTypeRows").asLong());
        assertThat(report.duplicatedEndpointPairGroups()).isEqualTo(priorSummary.path("duplicateEndpointPairGroups").asLong());
        assertThat(report.connections()).hasSize(crosswalk.path("rows").size());
        List<FlowMappingReviewService.MaterialBalanceReviewRow> rows = new ArrayList<>(report.connections().size());
        Map<String, List<ProcessDiagramInventoryService.EquipmentOccurrence>> candidatesById = new LinkedHashMap<>();
        Map<String, Integer> candidateCounts = new LinkedHashMap<>();
        Map<String, String> candidateIdentityById = new HashMap<>();
        Map<String, String> filenames = new HashMap<>();
        Map<String, String> sourceHashes = new HashMap<>();
        for (JsonNode workbook : priorSummary.path("officialInventory").path("workbooks")) {
            String name = workbook.path("filename").asText();
            String sha = workbook.path("currentSha256").asText();
            filenames.put(name, name);
            sourceHashes.put(name, sha);
        }
        long nonzeroNumericPrecisionDifferenceRows = 0;
        long formattedDisplayDifferenceRows = 0;
        java.math.BigDecimal maxNumericPrecisionDifference = java.math.BigDecimal.ZERO;
        for (int index = 0; index < report.connections().size(); index++) {
            var comparison = report.connections().get(index);
            JsonNode old = crosswalk.path("rows").get(index);
            assertThat(old.path("dbRow").asInt()).isEqualTo(comparison.sourceRow());
            assertThat(old.path("sourceEquipmentId").asText()).isEqualTo(comparison.source());
            assertThat(old.path("targetEquipmentId").asText()).isEqualTo(comparison.target());
            assertThat(old.path("quantityState").asText()).isEqualTo(comparison.quantityState());
            String priorDisplay = old.path("quantityDisplay").asText();
            String priorNumeric = old.path("quantityNumeric").asText("");
            if (!priorDisplay.equals(comparison.quantityText())) formattedDisplayDifferenceRows++;
            if (!priorNumeric.isBlank() && comparison.quantity() != null) {
                java.math.BigDecimal difference = new java.math.BigDecimal(priorNumeric).subtract(comparison.quantity()).abs();
                if (comparison.sourceRow() == 3058) {
                    assertThat(comparison.quantity()).withFailMessage("source row 3058 quantity state=%s text=%s", comparison.quantityState(), comparison.quantityText())
                            .isEqualByComparingTo(new java.math.BigDecimal(priorNumeric));
                }
                assertThat(difference).withFailMessage("Numeric comparison differs at source row %d: prior display=%s prior numeric=%s current parser=%s delta=%s",
                        comparison.sourceRow(), priorDisplay, priorNumeric, comparison.quantity(), difference)
                        .isLessThanOrEqualTo(new java.math.BigDecimal("0.000000001"));
                if (difference.signum() > 0) nonzeroNumericPrecisionDifferenceRows++;
                if (difference.compareTo(maxNumericPrecisionDifference) > 0) maxNumericPrecisionDifference = difference;
            }
            addCandidates(old.path("sourceDiagramCandidates"), comparison.source(), filenames, sourceHashes,
                    candidatesById, candidateCounts, candidateIdentityById);
            addCandidates(old.path("targetDiagramCandidates"), comparison.target(), filenames, sourceHashes,
                    candidatesById, candidateCounts, candidateIdentityById);
        }
        for (var comparison : report.connections()) {
            List<ProcessDiagramInventoryService.EquipmentOccurrence> sourceCandidates = candidatesById.getOrDefault(comparison.source(), List.of());
            List<ProcessDiagramInventoryService.EquipmentOccurrence> targetCandidates = candidatesById.getOrDefault(comparison.target(), List.of());
            Set<String> statuses = new LinkedHashSet<>();
            if (comparison.sourceExactMasterMatch() || comparison.targetExactMasterMatch()
                    || !sourceCandidates.isEmpty() || !targetCandidates.isEmpty()) statuses.add("STRING_MATCH_ONLY");
            if (sourceCandidates.isEmpty()) statuses.add("MISSING_SOURCE_EQUIPMENT_CANDIDATE");
            if (targetCandidates.isEmpty()) statuses.add("MISSING_TARGET_EQUIPMENT_CANDIDATE");
            if (sourceCandidates.size() > 1 || targetCandidates.size() > 1) statuses.add("MULTIPLE_LOCATION_CANDIDATES");
            if (comparison.duplicateEndpointCount() > 1) statuses.add("DUPLICATE_ENDPOINT_PAIR");
            if (blank(comparison.materialType()).isBlank()) statuses.add("MISSING_MATERIAL_TYPE");
            if (blank(comparison.unit()).isBlank()) statuses.add("MISSING_FLOW_UNIT");
            if (blank(comparison.period()).isBlank()) statuses.add("MISSING_PERIOD");
            statuses.add("CONNECTOR_BINDING_REQUIRED");
            statuses.add("MISSING_EVIDENCE");
            statuses.add("REVIEW_REQUIRED");
            String reason = "Prior 62-sheet candidate crosswalk; all 15 workbook SHA values match local read-only files, but current DB fingerprint was not recomputed. Physical connection remains unverified.";
            rows.add(new FlowMappingReviewService.MaterialBalanceReviewRow(actualSourceSha + ":" + comparison.sourceRow(),
                    comparison.sourceRow(), comparison.source(), comparison.target(), comparison.sourceMajor(), comparison.targetMajor(),
                    comparison.sourceMiddle(), comparison.targetMiddle(), comparison.quantity(), comparison.quantityText(),
                    comparison.quantityState(), comparison.materialType(), comparison.unit(), comparison.period(),
                    comparison.sourceExactMasterMatch(), comparison.targetExactMasterMatch(), comparison.duplicateEndpointCount(),
                    List.copyOf(statuses), "NOT_MAPPED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "NOT_REVIEWED", reason,
                    candidateCounts.getOrDefault(comparison.source(), 0), candidateCounts.getOrDefault(comparison.target(), 0),
                    String.join(" ", comparison.source(), comparison.target(), blank(comparison.materialType()),
                            blank(comparison.sourceMajor()), blank(comparison.targetMajor()), blank(comparison.sourceMiddle()),
                            blank(comparison.targetMiddle())).toLowerCase(java.util.Locale.ROOT)));
        }
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        rows.forEach(row -> row.qualityStatuses().forEach(status -> statusCounts.merge(status, 1L, Long::sum)));
        List<FlowMappingReviewService.PriorityGroup> groups = List.of(
                group(rows, "UNRESOLVED_EQUIPMENT", "설비 ID 미확인", 1, "MISSING_SOURCE_EQUIPMENT_CANDIDATE", "MISSING_TARGET_EQUIPMENT_CANDIDATE"),
                group(rows, "MULTIPLE_LOCATION_CANDIDATES", "복수 위치 후보", 2, "MULTIPLE_LOCATION_CANDIDATES"),
                group(rows, "DUPLICATE_ENDPOINT_PAIR", "중복 endpoint", 3, "DUPLICATE_ENDPOINT_PAIR"),
                group(rows, "MISSING_MATERIAL_TYPE", "물질 종류 누락", 4, "MISSING_MATERIAL_TYPE"),
                group(rows, "MISSING_FLOW_UNIT", "단위 누락", 5, "MISSING_FLOW_UNIT"),
                group(rows, "MISSING_PERIOD", "기간 누락", 6, "MISSING_PERIOD"),
                group(rows, "CONNECTOR_BINDING_REQUIRED", "Connector binding 필요", 7, "CONNECTOR_BINDING_REQUIRED"),
                group(rows, "MISSING_EVIDENCE", "추가 증빙 필요", 8, "MISSING_EVIDENCE"));
        var priorInventory = priorSummary.path("officialInventory");
        var snapshot = new FlowMappingReviewService.MaterialBalanceReviewSnapshot(report.sourceFilename(), actualSourceSha,
                sourceBytes.length, report.inputRows(), report.workbookSheetCount(), report.inputColumnCount(),
                report.bothEndpointsMasterMatched(), report.oneEndpointMasterMatched(), report.noEndpointMasterMatch(),
                report.positiveQuantityRows(), report.zeroQuantityRows(), report.blankQuantityRows(), report.blankMaterialTypeRows(),
                rows.stream().filter(row -> blank(row.unit()).isBlank()).count(), rows.stream().filter(row -> blank(row.period()).isBlank()).count(),
                report.blankExclusionRows(), report.duplicatedEndpointPairGroups(), report.rowsWithinDuplicatedEndpointPairs(),
                evidence, sha256(masterBytes), "CHECKPOINT_CONTENT_SHA_MATCHED_DB_FINGERPRINT_UNVERIFIED",
                null, priorInventory.path("checkpointFingerprint").asText(),
                null, priorInventory.path("checkpointSheets").asInt(), "PRIOR_CROSSWALK_DB_FINGERPRINT_NOT_RECOMPUTED",
                0, groups, Map.copyOf(statusCounts), rows, candidatesById, candidateCounts);
        byte[] xlsx = new FlowMappingReviewWorkbookExporter().export(snapshot);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.createDirectories(validationPath.toAbsolutePath().getParent());
        Files.write(outputPath, xlsx);
        ZipSecureFile.setMinInflateRatio(0.001);
        try (var zip = new java.util.zip.ZipFile(outputPath.toFile());
             var workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            assertThat(zip.size()).isGreaterThan(10);
            assertThat(workbook.getNumberOfSheets()).isEqualTo(9);
            assertThat(workbook.getSheetName(0)).isEqualTo("종합 현황");
            assertThat(workbook.getSheet("전체 입력 행").getLastRowNum()).isEqualTo(3082);
            assertThat(workbook.getSheet("담당자 검토 입력 양식").getLastRowNum()).isEqualTo(3082);
            assertThat(workbook.getSheet("설비 ID 미확인").getLastRowNum()).isEqualTo(
                    countUnion(rows, "MISSING_SOURCE_EQUIPMENT_CANDIDATE", "MISSING_TARGET_EQUIPMENT_CANDIDATE"));
            assertThat(workbook.getSheet("복수 위치 후보").getLastRowNum()).isEqualTo(count(rows, "MULTIPLE_LOCATION_CANDIDATES"));
            assertThat(workbook.getSheet("중복 endpoint").getLastRowNum()).isEqualTo(count(rows, "DUPLICATE_ENDPOINT_PAIR"));
            assertThat(workbook.getSheet("물질 종류 누락").getLastRowNum()).isEqualTo(count(rows, "MISSING_MATERIAL_TYPE"));
            assertThat(workbook.getSheet("단위·기간 누락").getLastRowNum()).isEqualTo(
                    countUnion(rows, "MISSING_FLOW_UNIT", "MISSING_PERIOD"));
            var detailRow = workbook.getSheet("전체 입력 행").getRow(1);
            assertThat(detailRow.getCell(0).getStringCellValue()).isEqualTo(actualSourceSha + ":2");
            assertThat(detailRow.getCell(1).getStringCellValue()).isEqualTo("입력시트");
            assertThat(detailRow.getCell(2).getNumericCellValue()).isEqualTo(2);
            assertThat(detailRow.getCell(3).getStringCellValue()).isEqualTo(actualSourceSha);
            assertThat(workbook.getSheet("담당자 검토 입력 양식").getRow(1).getCell(20).getStringCellValue()).isEqualTo("NOT_REVIEWED");
            String multipleLocations = workbook.getSheet("전체 입력 행").getRow(1).getCell(16).getStringCellValue();
            assertThat(multipleLocations).contains("P-02/").contains("PW-01/").contains("P-01/");
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                var font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                assertThat(font.getColor()).isEqualTo(IndexedColors.BLACK.getIndex());
            }
        }
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("sourceSha256", actualSourceSha);
        validation.put("sourceRows", report.inputRows());
        validation.put("sourceWorksheet", "입력시트");
        validation.put("sourceFilesReadOnly", true);
        validation.put("macroFormulaQueryExecution", false);
        validation.put("officialProcessFileShaMatched", 15);
        validation.put("checkpointSheets", priorInventory.path("checkpointSheets").asInt());
        validation.put("dbFingerprintRecomputed", false);
        validation.put("inventoryStatus", "CHECKPOINT_CONTENT_SHA_MATCHED_DB_FINGERPRINT_UNVERIFIED");
        validation.put("quantities", Map.of("positive", report.positiveQuantityRows(), "zero", report.zeroQuantityRows(), "blank", report.blankQuantityRows()));
        validation.put("crosswalkNumericPrecisionDifferenceRows", nonzeroNumericPrecisionDifferenceRows);
        validation.put("maxNumericPrecisionDifference", maxNumericPrecisionDifference.toPlainString());
        validation.put("formattedDisplayDifferenceRowsVsPriorCrosswalk", formattedDisplayDifferenceRows);
        validation.put("sourceQuantityRepresentation", "Excel formatted display string and underlying stored numeric are reported separately; display rounding is not treated as stored-value change.");
        validation.put("masterEndpointMatches", Map.of("both", report.bothEndpointsMasterMatched(), "one", report.oneEndpointMasterMatched(), "none", report.noEndpointMasterMatch()));
        validation.put("candidateRowCounts", Map.of("both", priorSummary.path("rowsWithBothDiagramIdCandidates").asLong(),
                "one", priorSummary.path("rowsWithOneDiagramIdCandidate").asLong(), "none", priorSummary.path("rowsWithNoDiagramIdCandidate").asLong(),
                "multipleLocations", priorSummary.path("rowsWithMultipleDiagramLocationCandidates").asLong()));
        validation.put("duplicateEndpointPairGroups", report.duplicatedEndpointPairGroups());
        validation.put("rowsInDuplicateEndpointPairs", report.rowsWithinDuplicatedEndpointPairs());
        validation.put("blankMaterialTypeRows", report.blankMaterialTypeRows());
        validation.put("blankUnitRows", rows.stream().filter(row -> blank(row.unit()).isBlank()).count());
        validation.put("blankPeriodRows", rows.stream().filter(row -> blank(row.period()).isBlank()).count());
        validation.put("verifiedFlowMappingCount", 0);
        validation.put("connectorEndpointBindingCount", priorSummary.path("explicitConnectorEndpointBindingCount").asInt());
        validation.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("workbook", Map.of("file", outputPath.getFileName().toString(), "bytes", Files.size(outputPath),
                "sha256", sha256(xlsx), "sheetCount", 9, "zipIntegrity", "PASS", "poiReopen", "PASS",
                "formulaCells", 0, "font", "맑은 고딕", "fontColor", "BLACK"));
        Files.writeString(validationPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(validation));
        assertThat(Files.size(validationPath)).isPositive();
    }

    private Path resolveSource(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> {
                try {
                    return Files.size(path) == 973318 && sha256(Files.readAllBytes(path)).equals(SOURCE_SHA256);
                } catch (Exception exception) {
                    return false;
                }
            }).findFirst().orElseThrow(() -> new IllegalStateException(
                    "Expected read-only source XLSM with the recorded SHA-256 was not found"));
        }
    }

    private void validatePriorInventoryHashes(JsonNode workbooks, Path directory) throws Exception {
        assertThat(workbooks.size()).isEqualTo(15);
        Set<String> localHashes = new LinkedHashSet<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) localHashes.add(sha256(Files.readAllBytes(path)));
        }
        for (JsonNode workbook : workbooks) {
            String currentSha = workbook.path("currentSha256").asText();
            assertThat(workbook.path("shaMatch").asBoolean(true)).isTrue();
            assertThat(localHashes).contains(currentSha);
            assertThat(workbook.path("checkpointSha256").asText()).isEqualTo(currentSha);
        }
    }

    private Path resolveBySha(Path directory, String expectedSha) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).filter(path -> {
                try {
                    return sha256(Files.readAllBytes(path)).equals(expectedSha);
                } catch (Exception exception) {
                    return false;
                }
            }).findFirst().orElseThrow(() -> new IllegalStateException("Read-only file with expected SHA-256 was not found: " + expectedSha));
        }
    }

    private void addCandidates(JsonNode nodes, String equipmentId, Map<String, String> filenames, Map<String, String> sourceHashes,
            Map<String, List<ProcessDiagramInventoryService.EquipmentOccurrence>> candidatesById,
            Map<String, Integer> candidateCounts, Map<String, String> identities) {
        List<ProcessDiagramInventoryService.EquipmentOccurrence> items = candidatesById.computeIfAbsent(equipmentId, ignored -> new ArrayList<>());
        for (JsonNode node : nodes) {
            String processCode = node.path("processCode").asText();
            String sheetName = node.path("sheetName").asText();
            String range = node.path("cellRange").asText("");
            Matcher matcher = CELL_RANGE.matcher(range);
            String topLeft = matcher.matches() ? matcher.group(1) : "";
            String bottomRight = matcher.matches() ? matcher.group(2) : "";
            String filename = filenames.keySet().stream().filter(name -> name.startsWith(processCode + " ")
                    || name.startsWith(processCode + ".") || name.equals(processCode + ".xlsx")
                    || name.startsWith(processCode + "_")).findFirst().orElse(processCode + ".xlsx");
            String sourceSha = sourceHashes.getOrDefault(filename, "");
            List<String> idCells = new ArrayList<>();
            node.path("idTextCells").forEach(cell -> idCells.add(cell.asText()));
            String key = processCode + "|" + sheetName + "|" + range + "|" + idCells + "|" + node.path("candidateStatus").asText();
            String identity = equipmentId + "|" + key;
            if (identities.putIfAbsent(identity, identity) != null) continue;
            items.add(new ProcessDiagramInventoryService.EquipmentOccurrence(processCode, filename, sourceSha, sheetName,
                    equipmentId, "이름 미확인", topLeft, bottomRight, idCells, node.path("candidateStatus").asText(),
                    node.path("reason").asText(), "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"));
        }
        candidateCounts.put(equipmentId, items.size());
    }

    private FlowMappingReviewService.PriorityGroup group(List<FlowMappingReviewService.MaterialBalanceReviewRow> rows,
            String code, String label, int priority, String... statuses) {
        long count = rows.stream().filter(row -> java.util.Arrays.stream(statuses).anyMatch(row.qualityStatuses()::contains)).count();
        return new FlowMappingReviewService.PriorityGroup(code, label, count, priority);
    }

    private long count(List<FlowMappingReviewService.MaterialBalanceReviewRow> rows, String status) {
        return rows.stream().filter(row -> row.qualityStatuses().contains(status)).count();
    }

    private long countUnion(List<FlowMappingReviewService.MaterialBalanceReviewRow> rows, String... statuses) {
        return rows.stream().filter(row -> java.util.Arrays.stream(statuses).anyMatch(row.qualityStatuses()::contains)).count();
    }

    private String blank(String value) { return value == null ? "" : value; }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
