package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.processautomation.material.EquipmentBoxProfileRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Read-only re-scan of the 15 official diagram workbooks. Never edits source workbooks. */
class Pilot11AOfficialInventoryTest {
    private static final Path REFERENCES = Path.of("data", "reference");
    private static final Path BASELINE = Path.of("validation-results", "2026-10-29-process-diagram-inventory",
            "official-process-diagram-inventory_TEST_ONLY.json");
    private static final Path OUTPUT_ROOT = Path.of("validation-results", "pilot-11a-20261108");
    private static final Path PROGRESS = OUTPUT_ROOT.resolve("SCAN_PROGRESS.json");

    @Test
    void reanalyzesAllOfficialProcessSheetsAndReconcilesSourceSha() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<Map<String, Object>> baseline = mapper.readValue(BASELINE.toFile(), new TypeReference<>() { });
        Map<String, String> baselineHashes = baseline.stream().collect(java.util.stream.Collectors.toMap(
                item -> String.valueOf(item.get("filename")), item -> String.valueOf(item.get("sha256"))));
        OoxmlPackageInspector inspector = new OoxmlPackageInspector();
        var analyzer = new ProcessDiagramStructureAnalyzer(inspector,
                new Pw0105EquipmentInsertionEngine(new OoxmlPartEditor(), inspector));
        EquipmentBoxProfileRepository profiles = new EquipmentBoxProfileRepository(mapper);
        List<Path> files;
        try (var listed = Files.list(REFERENCES)) {
            files = listed.filter(path -> path.toString().endsWith(".xlsx"))
                    .filter(path -> !path.getFileName().toString().equals("전체 목록.xlsx"))
                    .sorted().toList();
        }
        assertThat(files).hasSize(15);
        String run = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        String existingRun = null;
        if (Files.exists(PROGRESS)) {
            Map<String, Object> saved = mapper.readValue(PROGRESS.toFile(), new TypeReference<>() { });
            existingRun = String.valueOf(saved.get("runId"));
        }
        if (existingRun != null && !existingRun.equals("null")) run = existingRun;
        Path checkpointDirectory = OUTPUT_ROOT.resolve("checkpoints").resolve(run);
        Files.createDirectories(checkpointDirectory);
        Map<String, Map<String, Object>> progressByFile = new java.util.LinkedHashMap<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            byte[] source = Files.readAllBytes(file);
            String hash = sha256(source);
            assertThat(baselineHashes).as("baseline includes " + filename).containsKey(filename);
            assertThat(hash).as("official SHA unchanged: " + filename).isEqualTo(baselineHashes.get(filename));
            progressByFile.put(filename, new java.util.LinkedHashMap<>(Map.of("sha256", hash,
                    "status", "PENDING", "sheetCount", 0, "checkpoint", checkpointDirectory.resolve(filename + ".json").toString())));
        }
        writeProgress(mapper, run, progressByFile, 0, 15);

        List<ProcessDiagramStructureAnalyzer.WorkbookReport> reports = new java.util.ArrayList<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            Map<String, Object> entry = progressByFile.get(filename);
            String hash = String.valueOf(entry.get("sha256"));
            Path checkpoint = checkpointDirectory.resolve(filename + ".json");
            ProcessDiagramStructureAnalyzer.WorkbookReport report;
            if (Files.exists(checkpoint)) {
                report = mapper.readValue(checkpoint.toFile(), ProcessDiagramStructureAnalyzer.WorkbookReport.class);
                if (!hash.equalsIgnoreCase(report.sha256())) throw new AssertionError("Checkpoint SHA mismatch: " + filename);
            } else {
                entry.put("status", "SCANNING");
                writeProgress(mapper, run, progressByFile, (int) progressByFile.values().stream()
                        .filter(item -> "COMPLETE".equals(item.get("status"))).count(), 15);
                try {
                    byte[] source = Files.readAllBytes(file);
                    String processId = filename.contains(" ") ? filename.substring(0, filename.indexOf(' '))
                            : filename.substring(0, filename.lastIndexOf('.'));
                    report = analyzer.analyze(processId, filename, hash, source);
                    if (report.sheetCount() < 1 || report.sheets().size() != report.sheetCount())
                        throw new AssertionError("Sheet inventory incomplete: " + filename);
                    Path temp = checkpoint.resolveSibling(checkpoint.getFileName() + ".tmp");
                    mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
                    Files.move(temp, checkpoint, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    entry.put("status", "COMPLETE");
                    entry.put("sheetCount", report.sheetCount());
                } catch (Exception exception) {
                    entry.put("status", "FAILED");
                    entry.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
                    writeProgress(mapper, run, progressByFile, 0, 15);
                    throw exception;
                }
                writeProgress(mapper, run, progressByFile, (int) progressByFile.values().stream()
                        .filter(item -> "COMPLETE".equals(item.get("status"))).count(), 15);
            }
            entry.put("status", "COMPLETE");
            entry.put("sheetCount", report.sheetCount());
            reports.add(report);
        }
        assertThat(reports).hasSize(15);
        assertThat(reports.stream().mapToInt(ProcessDiagramStructureAnalyzer.WorkbookReport::sheetCount).sum()).isEqualTo(62);
        assertThat(reports).allSatisfy(report -> assertThat(report.sheets()).hasSize(report.sheetCount()));
        writeProgress(mapper, run, progressByFile, 15, 15);
        Files.createDirectories(OUTPUT_ROOT);
        Path allInventory = OUTPUT_ROOT.resolve("ALL_SHEET_INVENTORY.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(allInventory.toFile(), reports);

        List<Map<String, Object>> mappings = new java.util.ArrayList<>();
        for (var report : reports) {
            var matchingProfile = report.sheets().stream().map(sheet -> Map.<String, Object>of(
                    "sheetName", sheet.sheetName(), "profile", profiles.findFor(report.processCode(), sheet.sheetName(), report.sha256())
                            .map(profile -> Map.of("profileId", profile.profileId(), "status", profile.status(),
                                    "evidenceLevel", profile.evidenceLevel(), "genericAddEnabled", profile.enabledForGenericAdd()))
                            .orElse(Map.of("status", "NO_MATCHING_PROFILE")))).toList();
            var locationStatuses = report.locationCandidates().stream().map(this::candidateMapping).toList();
            var equipmentStatuses = report.equipmentCandidates().stream().map(this::equipmentCandidateMapping).toList();
            mappings.add(Map.of("processId", report.processCode(), "filename", report.filename(), "sourceSha256", report.sha256(),
                    "sheets", report.sheets().stream().map(this::sheetMapping).toList(),
                    "profiles", matchingProfile, "equipmentCandidates", equipmentStatuses, "locationCandidates", locationStatuses,
                    "interpretation", "All candidates stay REVIEW_REQUIRED; no physical connection or edit authorization is inferred."));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_ROOT.resolve("SHEET_LAYOUT_MAPPING.json").toFile(), mappings);
        StringBuilder comparison = new StringBuilder("# PILOT-11A inventory comparison\n\n")
                .append("Read-only scan of 15 official diagram workbooks. Manifest and legacy baseline SHA must agree.\n\n")
                .append("| Process | File | SHA matches baseline | Sheets | Cells | Merges | Shapes | Connectors | Pictures |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var report : reports) comparison.append("| ").append(report.processCode()).append(" | ").append(report.filename())
                .append(" | yes | ").append(report.sheetCount()).append(" | ").append(report.nonEmptyCellCount())
                .append(" | ").append(report.mergedRangeCount()).append(" | ").append(report.shapeCount())
                .append(" | ").append(report.connectorCount()).append(" | ").append(report.pictureCount()).append(" |\n");
        comparison.append("\nTotal worksheets: ").append(reports.stream().mapToInt(ProcessDiagramStructureAnalyzer.WorkbookReport::sheetCount).sum())
                .append(". All equipment and location matches remain structural candidates; no physical connection is verified.\n");
        Files.writeString(OUTPUT_ROOT.resolve("INVENTORY_COMPARISON.md"), comparison.toString());
    }

    private Map<String, Object> sheetMapping(ProcessDiagramStructureAnalyzer.SheetReport sheet) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sheetName", sheet.sheetName());
        result.put("drawingPart", sheet.drawingPart());
        result.put("drawingObjectCount", sheet.drawingObjectCount());
        result.put("drawingObjects", sheet.drawingObjects());
        result.put("mergedRanges", sheet.mergedRanges());
        result.put("usedRange", sheet.usedRange());
        result.put("columns", sheet.columns());
        result.put("nonEmptyRows", sheet.nonEmptyRows());
        return result;
    }

    private Map<String, Object> equipmentCandidateMapping(ProcessDiagramStructureAnalyzer.EquipmentCandidate candidate) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sheetName", candidate.sheetName());
        result.put("equipmentIdCandidate", candidate.equipmentIdCandidate());
        result.put("idTextCells", candidate.idTextCells());
        result.put("topLeftCell", candidate.topLeftCell());
        result.put("bottomRightCell", candidate.bottomRightCell());
        result.put("facilityNameCandidate", candidate.facilityNameCandidate());
        result.put("status", candidate.status());
        result.put("reason", candidate.reason());
        result.put("verified", false);
        return result;
    }

    private Map<String, Object> candidateMapping(ProcessDiagramStructureAnalyzer.LocationCandidate candidate) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("locationId", candidate.locationId());
        result.put("sheetName", candidate.sheetName());
        result.put("precedingEquipmentCandidate", candidate.precedingEquipmentCandidate());
        result.put("followingEquipmentCandidate", candidate.followingEquipmentCandidate());
        result.put("candidateCellRange", candidate.candidateCellRange());
        result.put("status", "REVIEW_REQUIRED");
        result.put("automaticEditAllowed", false);
        result.put("physicalConnectionEvidence", candidate.connectionEvidenceStatus());
        result.put("editabilityStatus", candidate.editabilityStatus());
        return result;
    }

    private void writeProgress(ObjectMapper mapper, String run, Map<String, Map<String, Object>> files,
                               int completed, int total) throws Exception {
        Map<String, Object> report = Map.of("runId", run, "expectedDiagramCount", total, "completedCount", completed,
                "files", files, "status", completed == total ? "COMPLETE" : "IN_PROGRESS",
                "sourceMutationPolicy", "READ_ONLY_OFFICIAL_SOURCES");
        Path temp = PROGRESS.resolveSibling(PROGRESS.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
        Files.move(temp, PROGRESS, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
