package com.example.processautomation.material;

import com.example.processautomation.excel.DiagramEquipmentInsertionEngine;
import com.example.processautomation.excel.ProcessDiagramInventoryWorkbookExporter;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only inventory of locked V1.0 process-document BLOBs, verified against their stored SHA-256 values. */
@Service
public class ProcessDiagramInventoryService {
    private final JdbcTemplate jdbc;
    private final ProcessDiagramStructureAnalyzer analyzer;
    private final ProcessDiagramInventoryWorkbookExporter exporter;
    private final DiagramEquipmentInsertionEngine editabilityAnalyzer;
    private final ProcessDiagramInventoryCheckpointStore checkpointStore;
    private final NumericWorkingCopyService workingCopies;
    private final GeneralDiagramEditService diagramEdits;
    private final ObjectMapper objectMapper;

    public ProcessDiagramInventoryService(JdbcTemplate jdbc, ProcessDiagramStructureAnalyzer analyzer,
                                          ProcessDiagramInventoryWorkbookExporter exporter,
                                          DiagramEquipmentInsertionEngine editabilityAnalyzer,
                                          ProcessDiagramInventoryCheckpointStore checkpointStore,
                                          NumericWorkingCopyService workingCopies,
                                          GeneralDiagramEditService diagramEdits,
                                          ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.analyzer = analyzer;
        this.exporter = exporter;
        this.editabilityAnalyzer = editabilityAnalyzer;
        this.checkpointStore = checkpointStore;
        this.workingCopies = workingCopies;
        this.diagramEdits = diagramEdits;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProcessDiagramStructureAnalyzer.WorkbookReport inspectLockedProcessSheet(
            long baselineDocumentId, String sheetName) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sha256, d.content_bytes AS contentBytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.id=? AND d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                """, baselineDocumentId);
        byte[] bytes = (byte[]) row.get("contentBytes");
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "잠금 V1.0 공정도 시트 BLOB이 비어 있습니다.");
        var report = analyzer.analyzeSheet(String.valueOf(row.get("processCode")), String.valueOf(row.get("filename")),
                String.valueOf(row.get("sha256")), bytes, sheetName);
        List<ProcessDiagramStructureAnalyzer.LocationCandidate> locations = report.locationCandidates().stream()
                .map(candidate -> assessLocation(candidate, bytes)).toList();
        return new ProcessDiagramStructureAnalyzer.WorkbookReport(report.processCode(), report.filename(),
                report.sha256(), report.byteSize(), report.sheetCount(), report.nonEmptyCellCount(),
                report.mergedRangeCount(), report.formulaCellCount(), report.drawingPartCount(), report.shapeCount(),
                report.connectorCount(), report.pictureCount(), report.otherObjectCount(), report.explicitConnectorBindings(),
                report.sheets(), report.equipmentCandidates(), locations, report.interpretationPolicy());
    }

    @Transactional(readOnly = true)
    public List<ProcessDiagramStructureAnalyzer.WorkbookReport> inspectLockedProcessDocuments() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sha256, d.content_bytes AS contentBytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id = d.baseline_release_id
                WHERE d.document_role = 'PROCESS' AND b.version_code = 'V1.0' AND b.locked = TRUE
                ORDER BY d.process_code, d.original_filename
                """);
        if (rows.size() != 15) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "잠금 V1.0 공식 PROCESS 문서가 15개가 아닙니다. 발견 수=" + rows.size());
        }
        return rows.stream().map(row -> {
            byte[] bytes = (byte[]) row.get("contentBytes");
            if (bytes == null || bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "공식 PROCESS BLOB이 비어 있어 구조 분석을 중단했습니다: " + row.get("processCode"));
            }
            var report = analyzer.analyze(String.valueOf(row.get("processCode")), String.valueOf(row.get("filename")),
                    String.valueOf(row.get("sha256")), bytes);
            List<ProcessDiagramStructureAnalyzer.LocationCandidate> locations = report.locationCandidates().stream()
                    .map(candidate -> assessLocation(candidate, bytes)).toList();
            return new ProcessDiagramStructureAnalyzer.WorkbookReport(report.processCode(), report.filename(),
                    report.sha256(), report.byteSize(), report.sheetCount(), report.nonEmptyCellCount(),
                    report.mergedRangeCount(), report.formulaCellCount(), report.drawingPartCount(), report.shapeCount(),
                    report.connectorCount(), report.pictureCount(), report.otherObjectCount(), report.explicitConnectorBindings(),
                    report.sheets(), report.equipmentCandidates(), locations, report.interpretationPolicy());
        }).toList();
    }

    private ProcessDiagramStructureAnalyzer.LocationCandidate assessLocation(
            ProcessDiagramStructureAnalyzer.LocationCandidate candidate, byte[] bytes) {
        if (!candidate.overlappingMergedRanges().isEmpty() || candidate.drawingObjectsCrossingBand() > 0) {
            return withEditability(candidate, "UNSUPPORTED", "MERGED_OR_DRAWING_OBJECTS_OVERLAP_CANDIDATE");
        }
        try {
            var plan = editabilityAnalyzer.analyze(bytes, candidate.sheetName(),
                    candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate());
            return withEditability(candidate, "VERIFIED", plan.template().profileId());
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            String status = reason.contains("MERGED") || reason.contains("DRAWING") || reason.contains("ANCHOR")
                    || reason.contains("OVERLAP") || reason.contains("UNSUPPORTED") ? "UNSUPPORTED" : "REVIEW_REQUIRED";
            return withEditability(candidate, status, reason.substring(0, Math.min(500, reason.length())));
        }
    }

    private ProcessDiagramStructureAnalyzer.LocationCandidate withEditability(
            ProcessDiagramStructureAnalyzer.LocationCandidate candidate, String editability, String reason) {
        return new ProcessDiagramStructureAnalyzer.LocationCandidate(candidate.locationId(), candidate.processCode(),
                candidate.sheetName(), candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate(),
                candidate.candidateCellRange(), candidate.gapRows(), candidate.sourceBoxHeight(), candidate.drawingPart(),
                candidate.connectorCount(), candidate.explicitConnectorBindings(), candidate.drawingObjectsCrossingBand(),
                candidate.overlappingMergedRanges(), false, "REVIEW_REQUIRED", candidate.reviewReasons(), editability,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", reason);
    }

    /** Process a bounded worksheet batch; checkpoint reports supplied by the caller are preserved. */
    @Transactional(readOnly = true)
    public SheetBatchResult inspectLockedProcessSheets(List<SheetCheckpoint> checkpoints) {
        return inspectLockedProcessSheets(checkpoints, 5);
    }

    @Transactional(readOnly = true)
    public SheetBatchResult inspectLockedProcessSheets(List<SheetCheckpoint> checkpoints, int maxSheets) {
        if (maxSheets < 1 || maxSheets > 10) throw new IllegalArgumentException("maxSheets must be between 1 and 10");
        List<Map<String, Object>> documents = jdbc.queryForList("""
                SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sha256
                FROM baseline_document d
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                ORDER BY CASE d.process_code WHEN 'P-02' THEN 0 WHEN 'PW-01' THEN 1 ELSE 2 END,
                         d.process_code, d.original_filename
                """);
        if (documents.size() != 15) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "잠금 V1.0 공식 PROCESS 문서 수가 15가 아닙니다: " + documents.size());
        List<ProcessDiagramStructureAnalyzer.WorkbookReport> complete = new java.util.ArrayList<>();
        Map<String, String> failures = new java.util.LinkedHashMap<>();
        List<SheetCheckpoint> ordered = checkpoints == null ? List.of() : checkpoints.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(SheetCheckpoint::documentOrder)
                        .thenComparingInt(SheetCheckpoint::sheetIndex)).toList();
        int completedCount = 0;
        int failedCount = 0;
        java.util.Set<String> finishedKeys = new java.util.HashSet<>();
        for (SheetCheckpoint checkpoint : ordered) {
            if ("COMPLETED".equals(checkpoint.status()) && checkpoint.report() != null) {
                complete.add(checkpoint.report()); completedCount++; finishedKeys.add(checkpoint.key());
            } else if ("FAILED".equals(checkpoint.status())) {
                failures.put(checkpoint.key(), checkpoint.failureReason() == null ? "UNKNOWN_FAILURE" : checkpoint.failureReason());
                failedCount++; finishedKeys.add(checkpoint.key());
            }
        }
        int totalSheets = jdbc.queryForObject("""
                SELECT COUNT(*) FROM workbook_sheet ws JOIN baseline_document d ON d.id=ws.baseline_document_id
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                """, Integer.class);
        List<SheetCheckpoint> updated = new java.util.ArrayList<>(ordered);
        int processed = 0;
        for (int documentOrder = 0; documentOrder < documents.size() && processed < maxSheets; documentOrder++) {
            Map<String, Object> document = documents.get(documentOrder);
            long documentId = ((Number) document.get("documentId")).longValue();
            List<Map<String, Object>> sheetRows = jdbc.queryForList(
                    "SELECT sheet_index AS sheetIndex, sheet_name AS sheetName FROM workbook_sheet WHERE baseline_document_id=? ORDER BY sheet_index",
                    documentId);
            byte[] bytes = null;
            for (Map<String, Object> sheetRow : sheetRows) {
                int sheetIndex = ((Number) sheetRow.get("sheetIndex")).intValue();
                String sheetName = String.valueOf(sheetRow.get("sheetName"));
                SheetCheckpoint keyRecord = new SheetCheckpoint(documentOrder, documentId, sheetIndex, sheetName, null, null, null);
                if (finishedKeys.contains(keyRecord.key())) continue;
                String key = keyRecord.key();
                try {
                    if (bytes == null) bytes = jdbc.queryForObject(
                            "SELECT content_bytes FROM baseline_document WHERE id=?", byte[].class, documentId);
                    if (bytes == null || bytes.length == 0) throw new IllegalStateException("공식 PROCESS BLOB이 비어 있습니다.");
                    var report = analyzer.analyzeSheet(String.valueOf(document.get("processCode")),
                            String.valueOf(document.get("filename")), String.valueOf(document.get("sha256")), bytes, sheetName);
                    byte[] sourceBytes = bytes;
                    List<ProcessDiagramStructureAnalyzer.LocationCandidate> locations = report.locationCandidates().stream()
                            .map(candidate -> assessLocation(candidate, sourceBytes)).toList();
                    var reviewed = new ProcessDiagramStructureAnalyzer.WorkbookReport(report.processCode(), report.filename(),
                            report.sha256(), report.byteSize(), report.sheetCount(), report.nonEmptyCellCount(),
                            report.mergedRangeCount(), report.formulaCellCount(), report.drawingPartCount(), report.shapeCount(),
                            report.connectorCount(), report.pictureCount(), report.otherObjectCount(), report.explicitConnectorBindings(),
                            report.sheets(), report.equipmentCandidates(), locations, report.interpretationPolicy());
                    complete.add(reviewed);
                    updated.add(new SheetCheckpoint(documentOrder, documentId, sheetIndex, sheetName, "COMPLETED", reviewed, null));
                    completedCount++;
                } catch (RuntimeException exception) {
                    String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                    failures.put(key, reason);
                    updated.add(new SheetCheckpoint(documentOrder, documentId, sheetIndex, sheetName, "FAILED", null, reason));
                    failedCount++;
                }
                finishedKeys.add(key);
                processed++;
                if (processed >= maxSheets) break;
            }
            bytes = null;
        }
        return new SheetBatchResult(List.copyOf(complete), Map.copyOf(failures), List.copyOf(updated),
                completedCount, failedCount, documents.size(), totalSheets, processed,
                Math.max(0, totalSheets - finishedKeys.size()));
    }

    @Transactional(readOnly = true)
    public SheetBatchResult inspectLockedProcessSheets(int maxSheets) {
        return inspectLockedProcessSheets(List.of(), maxSheets);
    }

    /** Persist one worksheet at a time so a process interruption cannot discard earlier completed sheets. */
    @Transactional(readOnly = true)
    public synchronized InventoryRunResult runNextInventoryBatch(int maxSheets) {
        if (maxSheets < 1 || maxSheets > 10) throw new IllegalArgumentException("maxSheets must be between 1 and 10");
        String fingerprint = lockedProcessSourceFingerprint();
        if (!checkpointStore.exists()) seedVerifiedLegacySheets(fingerprint);
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = checkpointStore.load(fingerprint);
        SheetBatchResult latest = null;
        int processed = 0;
        for (int index = 0; index < maxSheets; index++) {
            latest = inspectLockedProcessSheets(snapshot.checkpoints(), 1);
            snapshot = new ProcessDiagramInventoryCheckpointStore.Snapshot(
                    fingerprint, java.time.Instant.now(), latest.checkpoints());
            checkpointStore.save(snapshot);
            processed += latest.processedThisBatch();
            if (latest.processedThisBatch() == 0 || latest.remainingSheets() == 0) break;
        }
        return summarize(snapshot, processed);
    }

    @Transactional(readOnly = true)
    public InventoryRunResult inventoryProgress() {
        String fingerprint = lockedProcessSourceFingerprint();
        return summarize(checkpointStore.load(fingerprint), 0);
    }

    @Transactional(readOnly = true)
    public ProcessDiagramInventoryCheckpointStore.EditabilityValidation registerGeneratedBoxOnlyValidation(
            ProcessDiagramInventoryCheckpointStore.EditabilityValidation validation) {
        if (validation == null || !"VERIFIED_BOX_ONLY_TEST_ONLY".equals(validation.structuralEditStatus())
                || !"NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE".equals(validation.physicalConnectionStatus())) {
            throw new IllegalArgumentException("Only independently tested BOX_ONLY results with unverified physical connection can be registered");
        }
        if (validation.validatedAt() == null || validation.sourceDocumentId() <= 0 || validation.workingDocumentId() <= 0
                || validation.locationId() == null || validation.locationId().isBlank()
                || validation.sourceEquipmentId() == null || validation.sourceEquipmentId().isBlank()
                || validation.targetEquipmentId() == null || validation.targetEquipmentId().isBlank()
                || validation.officialSourceSha256() == null || !validation.officialSourceSha256().matches("(?i)[0-9a-f]{64}")
                || validation.workingCopySha256() == null || !validation.workingCopySha256().matches("(?i)[0-9a-f]{64}")
                || validation.artifactPath() == null || validation.artifactPath().isBlank()
                || validation.artifactSha256() == null || !validation.artifactSha256().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Validation identity, timestamps, and hashes are required");
        }
        var copy = diagramEdits.safeWorkingCopy(validation.workingDocumentId());
        if (copy.baselineDocumentId() != validation.sourceDocumentId()
                || !copy.processCode().equals(validation.processCode())
                || !copy.sourceSha256().equalsIgnoreCase(validation.officialSourceSha256())
                || !copy.workingSha256().equalsIgnoreCase(validation.workingCopySha256())) {
            throw new IllegalStateException("Validation source and working-copy hashes do not match the locked source");
        }
        var location = diagramEdits.assessLocations(copy.id(), validation.sheetName()).stream()
                .filter(item -> item.locationId().equals(validation.locationId())
                        && item.sourceEquipmentId().equals(validation.sourceEquipmentId())
                        && item.targetEquipmentId().equals(validation.targetEquipmentId())
                        && "VERIFIED".equals(item.editabilityStatus()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Stored location is not currently VERIFIED"));
        if (validation.physicalConnectionStatus() == null
                || !validation.physicalConnectionStatus().equals(location.connectionEvidenceStatus())) {
            throw new IllegalStateException("Physical connection state must remain NOT_VERIFIED");
        }
        Path artifactPath = Path.of(validation.artifactPath()).normalize();
        if (artifactPath.isAbsolute() || artifactPath.startsWith("..")
                || !artifactPath.startsWith(Path.of("validation-results"))) {
            throw new IllegalArgumentException("Only project validation-results artifacts can be registered");
        }
        Path resolvedArtifact = artifactPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolvedArtifact)) throw new IllegalStateException("TEST_ONLY workbook artifact is missing");
        try {
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(resolvedArtifact)));
            if (!actualHash.equalsIgnoreCase(validation.artifactSha256())) {
                throw new IllegalStateException("TEST_ONLY workbook artifact SHA-256 mismatch");
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to verify TEST_ONLY workbook artifact", exception);
        }
        checkpointStore.registerValidation(lockedProcessSourceFingerprint(), validation);
        return validation;
    }

    @Transactional(readOnly = true)
    public byte[] exportSavedInventory() {
        String fingerprint = lockedProcessSourceFingerprint();
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = checkpointStore.load(fingerprint);
        return exporter.export(snapshot.checkpoints().stream()
                        .filter(item -> "COMPLETED".equals(item.status()) && item.report() != null)
                        .map(SheetCheckpoint::report).toList(),
                snapshot.checkpoints().stream().filter(item -> "FAILED".equals(item.status()))
                        .collect(java.util.stream.Collectors.toMap(SheetCheckpoint::key,
                                item -> item.failureReason() == null ? "UNKNOWN_FAILURE" : item.failureReason(),
                                (first, ignored) -> first, java.util.LinkedHashMap::new)));
    }

    private InventoryRunResult summarize(ProcessDiagramInventoryCheckpointStore.Snapshot snapshot, int processedThisRequest) {
        int completed = (int) snapshot.checkpoints().stream().filter(item -> "COMPLETED".equals(item.status())).count();
        int failed = (int) snapshot.checkpoints().stream().filter(item -> "FAILED".equals(item.status())).count();
        int total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM workbook_sheet ws JOIN baseline_document d ON d.id=ws.baseline_document_id
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                """, Integer.class);
        return new InventoryRunResult(snapshot.sourceFingerprint(), snapshot.updatedAt(), 15, total,
                completed, failed, Math.max(0, total - completed - failed), processedThisRequest);
    }

    private void seedVerifiedLegacySheets(String fingerprint) {
        Path legacyReportPath = Path.of("validation-results", "2026-10-29-process-diagram-inventory",
                "official-process-diagram-inventory_TEST_ONLY.json");
        if (!Files.isRegularFile(legacyReportPath)) {
            checkpointStore.save(ProcessDiagramInventoryCheckpointStore.Snapshot.empty(fingerprint));
            return;
        }
        try {
            List<ProcessDiagramStructureAnalyzer.WorkbookReport> legacyReports = objectMapper.readValue(
                    legacyReportPath.toFile(), new TypeReference<>() { });
            List<Map<String, Object>> documents = jdbc.queryForList("""
                    SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename, d.sha256 AS sha256
                    FROM baseline_document d JOIN baseline_release b ON b.id=d.baseline_release_id
                    WHERE d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                    ORDER BY CASE d.process_code WHEN 'P-02' THEN 0 WHEN 'PW-01' THEN 1 ELSE 2 END,
                             d.process_code, d.original_filename
                    """);
            if (documents.size() != 15 || legacyReports.size() != 15) throw new IllegalStateException(
                    "Legacy inventory cannot be reused: expected exactly 15 source and report workbooks");
            Map<String, ProcessDiagramStructureAnalyzer.WorkbookReport> reportByIdentity = legacyReports.stream()
                    .collect(java.util.stream.Collectors.toMap(report -> report.processCode() + "|" + report.filename(),
                            report -> report));
            List<SheetCheckpoint> reusable = new java.util.ArrayList<>();
            int totalSheets = 0;
            for (int documentOrder = 0; documentOrder < documents.size(); documentOrder++) {
                Map<String, Object> document = documents.get(documentOrder);
                String processCode = String.valueOf(document.get("processCode"));
                String filename = String.valueOf(document.get("filename"));
                var report = reportByIdentity.get(processCode + "|" + filename);
                if (report == null || !String.valueOf(document.get("sha256")).equalsIgnoreCase(report.sha256())) {
                    throw new IllegalStateException("Legacy inventory hash does not match locked source: " + processCode);
                }
                long documentId = ((Number) document.get("documentId")).longValue();
                List<Map<String, Object>> sheetRows = jdbc.queryForList(
                        "SELECT sheet_index AS sheetIndex, sheet_name AS sheetName FROM workbook_sheet WHERE baseline_document_id=? ORDER BY sheet_index",
                        documentId);
                if (sheetRows.size() != report.sheetCount() || report.sheets().size() != sheetRows.size()) {
                    throw new IllegalStateException("Legacy inventory sheet count does not match locked source: " + processCode);
                }
                totalSheets += sheetRows.size();
                if ("P-02".equals(processCode) || "PW-01".equals(processCode)) continue;
                Map<String, ProcessDiagramStructureAnalyzer.SheetReport> sheetReports = report.sheets().stream()
                        .collect(java.util.stream.Collectors.toMap(ProcessDiagramStructureAnalyzer.SheetReport::sheetName,
                                item -> item));
                for (Map<String, Object> sheetRow : sheetRows) {
                    String sheetName = String.valueOf(sheetRow.get("sheetName"));
                    var sheet = sheetReports.get(sheetName);
                    if (sheet == null) throw new IllegalStateException("Legacy inventory worksheet mismatch: " + processCode + " / " + sheetName);
                    int sheetIndex = ((Number) sheetRow.get("sheetIndex")).intValue();
                    List<ProcessDiagramStructureAnalyzer.EquipmentCandidate> equipment = report.equipmentCandidates().stream()
                            .filter(item -> sheetName.equals(item.sheetName())).toList();
                    List<ProcessDiagramStructureAnalyzer.LocationCandidate> locations = report.locationCandidates().stream()
                            .filter(item -> sheetName.equals(item.sheetName()))
                            .map(item -> item.editabilityStatus() == null
                                    ? withEditability(item, "REVIEW_REQUIRED", "LEGACY_ANALYSIS_STATUS_NOT_PRESENT_REQUIRES_REVIEW")
                                    : item).toList();
                    var sheetOnly = new ProcessDiagramStructureAnalyzer.WorkbookReport(report.processCode(), report.filename(),
                            report.sha256(), report.byteSize(), 1, sheet.nonEmptyCellCount(), sheet.mergedRanges().size(),
                            sheet.formulaCellCount(), sheet.drawingPart() == null ? 0 : 1, sheet.shapeCount(),
                            sheet.connectorCount(), sheet.pictureCount(),
                            (int) sheet.drawingObjects().stream().filter(object -> !List.of("SHAPE", "CONNECTOR", "PICTURE")
                                    .contains(object.objectType())).count(), sheet.explicitBindingObjectCount(),
                            List.of(sheet), equipment, locations, report.interpretationPolicy()
                                    + "; source SHA-256 matched; reused prior structural scan");
                    reusable.add(new SheetCheckpoint(documentOrder, documentId, sheetIndex, sheetName,
                            "COMPLETED", sheetOnly, null));
                }
            }
            if (totalSheets != 62) throw new IllegalStateException("Expected 62 locked PROCESS worksheets; found " + totalSheets);
            checkpointStore.save(new ProcessDiagramInventoryCheckpointStore.Snapshot(
                    fingerprint, java.time.Instant.now(), reusable));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to import previous read-only inventory", exception);
        }
    }

    private String lockedProcessSourceFingerprint() {
        List<Map<String, Object>> documents = jdbc.queryForList("""
                SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename, d.sha256 AS sha256
                FROM baseline_document d JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                ORDER BY d.process_code, d.original_filename
                """);
        if (documents.size() != 15) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "잠금 V1.0 공식 PROCESS 문서 수가 15가 아닙니다: " + documents.size());
        String identity = documents.stream().map(row -> row.get("documentId") + "|" + row.get("processCode")
                + "|" + row.get("filename") + "|" + row.get("sha256")).collect(java.util.stream.Collectors.joining("\\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Reuses the existing fingerprint-matched checkpoint; this read path never regenerates inventory. */
    @Transactional(readOnly = true)
    public MappingReviewSnapshot mappingReviewSnapshot() {
        String fingerprint = lockedProcessSourceFingerprint();
        ProcessDiagramInventoryCheckpointStore.Snapshot saved = checkpointStore.loadLatest();
        boolean stale = saved.sourceFingerprint() != null && !fingerprint.equals(saved.sourceFingerprint());
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = stale ? saved : checkpointStore.load(fingerprint);
        InventoryRunResult progress = summarize(snapshot, 0);
        List<SheetMappingOverview> sheets = (stale ? List.<SheetCheckpoint>of() : snapshot.checkpoints()).stream()
                .filter(item -> item != null && item.report() != null)
                .flatMap(item -> item.report().sheets().stream().map(sheet -> {
                    var report = item.report();
                    List<ProcessDiagramStructureAnalyzer.LocationCandidate> locations = report.locationCandidates().stream()
                            .filter(location -> sheet.sheetName().equals(location.sheetName())).toList();
                    int verifiedBoxes = (int) locations.stream().filter(location -> "VERIFIED".equals(location.editabilityStatus())).count();
                    int unsupportedBoxes = (int) locations.stream().filter(location -> "UNSUPPORTED".equals(location.editabilityStatus())).count();
                    int reviewBoxes = locations.size() - verifiedBoxes - unsupportedBoxes;
                    return new SheetMappingOverview(report.processCode(), report.filename(), report.sha256(), sheet.sheetIndex(),
                            sheet.sheetName(), sheet.shapeCount(), sheet.connectorCount(), sheet.explicitBindingObjectCount(),
                            (int) report.equipmentCandidates().stream().filter(candidate -> sheet.sheetName().equals(candidate.sheetName())).count(),
                            locations.size(), verifiedBoxes, reviewBoxes, unsupportedBoxes,
                            "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
                })).toList();
        int connectorCount = sheets.stream().mapToInt(SheetMappingOverview::connectorCount).sum();
        int shapeCount = sheets.stream().mapToInt(SheetMappingOverview::shapeCount).sum();
        int explicitBindings = sheets.stream().mapToInt(SheetMappingOverview::explicitConnectorEndpointBindings).sum();
        String freshness = stale ? "STALE_INVENTORY" : snapshot.checkpoints().isEmpty() ? "MISSING_EVIDENCE"
                : isCompleteSnapshot(snapshot) ? "HASH_MATCHED_CHECKPOINT" : "INVENTORY_INCOMPLETE";
        return new MappingReviewSnapshot(fingerprint, snapshot.sourceFingerprint(), freshness, snapshot.updatedAt(),
                progress.documentCount(), progress.totalSheets(), stale ? 0 : progress.completedSheets(),
                stale ? progress.totalSheets() : progress.failedSheets(), stale ? progress.totalSheets() : progress.remainingSheets(),
                connectorCount, shapeCount, explicitBindings, 0, "MISSING_EVIDENCE", sheets,
                "Saved structural inventory is presented only when source fingerprint matches. Connector anchors and equipment text candidates are not physical-flow evidence; actual flow mapping remains unverified.");
    }

    @Transactional(readOnly = true)
    public MappingCandidateResults mappingReviewCandidates(String processCode, String sheetName, String query, int requestedLimit) {
        return mappingReviewCandidates(processCode, sheetName, query, 0, requestedLimit);
    }

    @Transactional(readOnly = true)
    public MappingCandidateResults mappingReviewCandidates(String processCode, String sheetName, String query,
                                                           int requestedPage, int requestedPageSize) {
        if (processCode == null || !processCode.matches("[A-Z]{1,3}-\\d{2}(?:-\\d{2})?")
                || sheetName == null || sheetName.isBlank() || sheetName.length() > 128)
            throw new IllegalArgumentException("A valid processCode and bounded sheetName are required");
        if (requestedPage < 0 || requestedPageSize < 1 || requestedPageSize > 100)
            throw new IllegalArgumentException("page must be non-negative and pageSize must be between 1 and 100");
        if (query != null && query.length() > 128) throw new IllegalArgumentException("query is too long");
        int page = requestedPage;
        int pageSize = requestedPageSize;
        String fingerprint = lockedProcessSourceFingerprint();
        long offset = (long) page * pageSize;
        ProcessDiagramInventoryCheckpointStore.Snapshot saved = checkpointStore.loadLatest();
        if (saved.sourceFingerprint() != null && !fingerprint.equals(saved.sourceFingerprint()))
            return new MappingCandidateResults(processCode, sheetName, fingerprint, page, pageSize,
                    0, 0, 0, List.of(), List.of(), List.of(), "STALE_INVENTORY");
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = checkpointStore.load(fingerprint);
        if (!isCompleteSnapshot(snapshot)) return new MappingCandidateResults(processCode, sheetName, fingerprint, page, pageSize,
                0, 0, 0, List.of(), List.of(), List.of(), "INVENTORY_INCOMPLETE");
        var report = snapshot.checkpoints().stream().filter(item -> item != null && item.report() != null)
                .map(SheetCheckpoint::report).filter(item -> processCode.equals(item.processCode()))
                .findFirst().orElse(null);
        if (report == null) return new MappingCandidateResults(processCode, sheetName, fingerprint, page, pageSize,
                0, 0, 0, List.of(), List.of(), List.of(), "NO_SAVED_HASH_MATCHED_INVENTORY");
        var sheet = report.sheets().stream().filter(item -> sheetName.equals(item.sheetName())).findFirst().orElse(null);
        if (sheet == null) return new MappingCandidateResults(processCode, sheetName, fingerprint, page, pageSize,
                0, 0, 0, List.of(), List.of(), List.of(), "SHEET_NOT_IN_SAVED_INVENTORY");
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<ProcessDiagramStructureAnalyzer.EquipmentCandidate> allEquipment = report.equipmentCandidates().stream()
                .filter(item -> sheetName.equals(item.sheetName()))
                .filter(item -> q.isBlank() || contains(item.equipmentIdCandidate(), q) || contains(item.facilityNameCandidate(), q)
                        || contains(item.topLeftCell(), q) || contains(item.bottomRightCell(), q)).toList();
        List<ProcessDiagramStructureAnalyzer.DrawingSummary> allConnectors = (sheet.drawingObjects() == null
                ? List.<ProcessDiagramStructureAnalyzer.DrawingSummary>of() : sheet.drawingObjects()).stream()
                .filter(item -> "CONNECTOR".equals(item.objectType()))
                .filter(item -> q.isBlank() || contains(item.shapeId(), q) || contains(item.objectName(), q)
                        || contains(item.textContent(), q) || contains(item.geometryPreset(), q)).toList();
        List<ProcessDiagramStructureAnalyzer.LocationCandidate> allLocations = report.locationCandidates().stream()
                .filter(item -> sheetName.equals(item.sheetName()))
                .filter(item -> q.isBlank() || contains(item.precedingEquipmentCandidate(), q)
                        || contains(item.followingEquipmentCandidate(), q) || contains(item.locationId(), q)).toList();
        List<EquipmentMappingCandidate> equipment = allEquipment.stream().skip(offset).limit(pageSize)
                .map(item -> new EquipmentMappingCandidate(item.equipmentIdCandidate(), item.facilityNameCandidate(),
                        item.topLeftCell(), item.bottomRightCell(), item.status(), item.reason())).toList();
        List<ConnectorMappingCandidate> connectors = allConnectors.stream().skip(offset).limit(pageSize)
                .map(item -> new ConnectorMappingCandidate(item.shapeId(), item.objectName(), item.textContent(),
                        item.startColumn(), item.startRow(), item.endColumn(), item.endRow(), item.startConnectedShapeId(),
                        item.endConnectedShapeId(), item.headArrowType(), item.tailArrowType(),
                        item.startConnectedShapeId() != null && item.endConnectedShapeId() != null
                                ? "ENDPOINT_BINDING_PRESENT_REQUIRES_REVIEW" : "UNBOUND_REVIEW_REQUIRED",
                        "Anchor/arrow metadata describes drawing placement, not a verified physical process connection.")).toList();
        List<LocationMappingCandidate> locations = allLocations.stream().skip(offset).limit(pageSize)
                .map(item -> new LocationMappingCandidate(item.locationId(), item.precedingEquipmentCandidate(),
                        item.followingEquipmentCandidate(), item.candidateCellRange(), item.connectorCount(),
                        item.explicitConnectorBindings(), item.editabilityStatus() == null ? "REVIEW_REQUIRED" : item.editabilityStatus(),
                        item.connectionEvidenceStatus(), item.editabilityReason(), item.reviewReasons())).toList();
        return new MappingCandidateResults(processCode, sheetName, fingerprint, page, pageSize, allEquipment.size(),
                allConnectors.size(), allLocations.size(), equipment, connectors, locations,
                "CANDIDATES_ONLY_NOT_PHYSICAL_MAPPING");
    }

    @Transactional(readOnly = true)
    public EquipmentOccurrenceBatchResults mappingReviewEquipmentOccurrences(List<String> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty() || equipmentIds.size() > 5000
                || equipmentIds.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9._-]{1,64}")))
            throw new IllegalArgumentException("equipmentIds must contain 1 to 5000 safe identifiers");
        String fingerprint = lockedProcessSourceFingerprint();
        ProcessDiagramInventoryCheckpointStore.Snapshot saved = checkpointStore.loadLatest();
        Map<String, List<EquipmentOccurrence>> matches = new java.util.LinkedHashMap<>();
        equipmentIds.stream().distinct().forEach(id -> matches.put(id, new java.util.ArrayList<>()));
        if (saved.sourceFingerprint() != null && !fingerprint.equals(saved.sourceFingerprint()))
            return new EquipmentOccurrenceBatchResults(fingerprint, saved.sourceFingerprint(), "STALE_INVENTORY", Map.of(), Map.of());
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = checkpointStore.load(fingerprint);
        if (!isCompleteSnapshot(snapshot)) return new EquipmentOccurrenceBatchResults(fingerprint, snapshot.sourceFingerprint(),
                "INVENTORY_INCOMPLETE", Map.of(), Map.of());
        for (SheetCheckpoint checkpoint : snapshot.checkpoints()) {
            if (checkpoint == null || checkpoint.report() == null) continue;
            var report = checkpoint.report();
            for (var candidate : report.equipmentCandidates()) {
                var bucket = matches.get(candidate.equipmentIdCandidate());
                if (bucket == null) continue;
                bucket.add(new EquipmentOccurrence(report.processCode(), report.filename(), report.sha256(),
                        candidate.sheetName(), candidate.equipmentIdCandidate(), candidate.facilityNameCandidate(),
                        candidate.topLeftCell(), candidate.bottomRightCell(), candidate.idTextCells(), candidate.status(),
                        candidate.reason(), "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE"));
            }
        }
        Map<String, List<EquipmentOccurrence>> immutable = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        matches.forEach((id, items) -> { immutable.put(id, List.copyOf(items)); counts.put(id, items.size()); });
        return new EquipmentOccurrenceBatchResults(fingerprint, snapshot.sourceFingerprint(),
                "HASH_MATCHED_CHECKPOINT", Map.copyOf(counts), Map.copyOf(immutable));
    }

    @Transactional(readOnly = true)
    public EquipmentOccurrenceResults mappingReviewEquipmentOccurrences(String equipmentId, int requestedPage, int requestedPageSize) {
        if (equipmentId == null || !equipmentId.matches("[A-Za-z0-9._-]{1,64}")
                || requestedPage < 0 || requestedPageSize < 1 || requestedPageSize > 100)
            throw new IllegalArgumentException("equipmentId/page/pageSize is outside the allowed range");
        String fingerprint = lockedProcessSourceFingerprint();
        ProcessDiagramInventoryCheckpointStore.Snapshot saved = checkpointStore.loadLatest();
        if (saved.sourceFingerprint() != null && !fingerprint.equals(saved.sourceFingerprint()))
            return new EquipmentOccurrenceResults(equipmentId, fingerprint, requestedPage, requestedPageSize,
                    0, List.of(), "STALE_INVENTORY");
        ProcessDiagramInventoryCheckpointStore.Snapshot snapshot = checkpointStore.load(fingerprint);
        if (!isCompleteSnapshot(snapshot)) return new EquipmentOccurrenceResults(equipmentId, fingerprint,
                requestedPage, requestedPageSize, 0, List.of(), "INVENTORY_INCOMPLETE");
        List<EquipmentOccurrence> matches = snapshot.checkpoints().stream().filter(item -> item != null && item.report() != null)
                .flatMap(item -> item.report().equipmentCandidates().stream()
                        .filter(candidate -> equipmentId.equals(candidate.equipmentIdCandidate()))
                        .map(candidate -> new EquipmentOccurrence(item.report().processCode(), item.report().filename(),
                                item.report().sha256(), candidate.sheetName(), candidate.equipmentIdCandidate(),
                                candidate.facilityNameCandidate(), candidate.topLeftCell(), candidate.bottomRightCell(),
                                candidate.idTextCells(), candidate.status(), candidate.reason(),
                                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE")))
                .toList();
        long offset=(long)requestedPage*requestedPageSize;
        return new EquipmentOccurrenceResults(equipmentId, fingerprint, requestedPage, requestedPageSize,
                matches.size(), matches.stream().skip(offset).limit(requestedPageSize).toList(),
                matches.isEmpty() ? "NO_EXACT_INVENTORY_ID_CANDIDATE" : "STRING_MATCH_ONLY");
    }

    private boolean isCompleteSnapshot(ProcessDiagramInventoryCheckpointStore.Snapshot snapshot) {
        return snapshot != null && snapshot.checkpoints().size() == 62
                && snapshot.checkpoints().stream().allMatch(item -> item != null && "COMPLETED".equals(item.status()) && item.report() != null);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    public record SheetMappingOverview(String processCode, String filename, String sourceSha256, int sheetIndex,
                                       String sheetName, int shapeCount, int connectorCount,
                                       int explicitConnectorEndpointBindings, int equipmentIdCandidates,
                                       int boxLocationCandidates, int verifiedBoxLayoutCandidates,
                                       int reviewRequiredBoxCandidates, int unsupportedBoxCandidates,
                                       String physicalConnectionEvidenceStatus) { }
    public record MappingReviewSnapshot(String sourceFingerprint, String checkpointSourceFingerprint,
                                        String inventoryStatus, java.time.Instant inventoryUpdatedAt,
                                        int officialProcessDocumentCount, int totalSheetCount, int completedSheetCount,
                                        int failedSheetCount, int remainingSheetCount, int connectorCandidateCount,
                                        int shapeCandidateCount, int explicitConnectorEndpointBindingCount,
                                        int verifiedFlowLabelMappingCount, String flowMappingStatus,
                                        List<SheetMappingOverview> sheets, String interpretationNotice) {
        public MappingReviewSnapshot { sheets = List.copyOf(sheets); }
    }
    public record EquipmentMappingCandidate(String equipmentIdCandidate, String facilityNameCandidate,
                                            String topLeftCell, String bottomRightCell, String candidateStatus,
                                            String reviewReason) { }
    public record EquipmentOccurrence(String processCode, String filename, String sourceSha256, String sheetName,
                                      String equipmentIdCandidate, String facilityNameCandidate, String topLeftCell,
                                      String bottomRightCell, List<String> idTextCells, String candidateStatus,
                                      String reviewReason, String physicalConnectionEvidenceStatus) {
        public EquipmentOccurrence { idTextCells = idTextCells == null ? List.of() : List.copyOf(idTextCells); }
    }
    public record EquipmentOccurrenceResults(String equipmentId, String sourceFingerprint, int page, int pageSize,
                                             int totalOccurrences, List<EquipmentOccurrence> occurrences, String status) {
        public EquipmentOccurrenceResults { occurrences = List.copyOf(occurrences); }
    }
    public record EquipmentOccurrenceBatchResults(String sourceFingerprint, String checkpointSourceFingerprint,
                                                  String status, Map<String, Integer> totalOccurrencesByEquipmentId,
                                                  Map<String, List<EquipmentOccurrence>> occurrencesByEquipmentId) {
        public EquipmentOccurrenceBatchResults {
            Map<String, List<EquipmentOccurrence>> copy = new java.util.LinkedHashMap<>();
            if (occurrencesByEquipmentId != null) occurrencesByEquipmentId.forEach((id, items) -> copy.put(id, List.copyOf(items)));
            occurrencesByEquipmentId = Map.copyOf(copy);
            totalOccurrencesByEquipmentId = totalOccurrencesByEquipmentId == null ? Map.of() : Map.copyOf(totalOccurrencesByEquipmentId);
        }
    }
    public record ConnectorMappingCandidate(String connectorId, String objectName, String drawingText,
                                            Integer anchorStartColumn, Integer anchorStartRow, Integer anchorEndColumn,
                                            Integer anchorEndRow, String startConnectedShapeId, String endConnectedShapeId,
                                            String headArrowType, String tailArrowType, String candidateStatus,
                                            String reviewReason) { }
    public record LocationMappingCandidate(String candidateId, String precedingEquipmentCandidate,
                                           String followingEquipmentCandidate, String candidateCellRange,
                                           int connectorCountInBand, int explicitEndpointBindings,
                                           String structuralStatus, String physicalConnectionEvidenceStatus,
                                           String blockingReason, List<String> reviewReasons) {
        public LocationMappingCandidate { reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons); }
    }
    public record MappingCandidateResults(String processCode, String sheetName, String sourceFingerprint,
                                          int page, int pageSize, int totalEquipmentCandidates,
                                          int totalConnectorCandidates, int totalLocationCandidates,
                                          List<EquipmentMappingCandidate> equipmentCandidates,
                                          List<ConnectorMappingCandidate> connectorCandidates,
                                          List<LocationMappingCandidate> locationCandidates, String status) {
        public MappingCandidateResults {
            equipmentCandidates = List.copyOf(equipmentCandidates); connectorCandidates = List.copyOf(connectorCandidates);
            locationCandidates = List.copyOf(locationCandidates);
        }
    }

    public record InventoryRunResult(String sourceFingerprint, java.time.Instant updatedAt, int documentCount,
                                      int totalSheets, int completedSheets, int failedSheets,
                                      int remainingSheets, int processedThisRequest) { }

    @Transactional(readOnly = true)
    public byte[] exportLockedProcessInventory(SheetBatchResult result) {
        if (result == null) throw new IllegalArgumentException("batch result is required");
        return exporter.export(result.completedReports(), result.failures());
    }

    @Transactional(readOnly = true)
    public byte[] exportLockedProcessInventory() {
        return exportSavedInventory();
    }

    @Transactional(readOnly = true)
    public byte[] exportLockedProcessInventory(List<ProcessDiagramStructureAnalyzer.WorkbookReport> reports,
                                               Map<String, String> failedSheets) {
        return exporter.export(reports, failedSheets == null ? Map.of() : failedSheets);
    }

    public record SheetCheckpoint(int documentOrder, long documentId, int sheetIndex, String sheetName,
                                  String status, ProcessDiagramStructureAnalyzer.WorkbookReport report,
                                  String failureReason) {
        public String key() { return documentId + "|" + sheetIndex + "|" + sheetName; }
    }
    public record SheetBatchResult(List<ProcessDiagramStructureAnalyzer.WorkbookReport> completedReports,
                                   Map<String, String> failures, List<SheetCheckpoint> checkpoints,
                                   int completedSheets, int failedSheets, int documentCount, int totalSheets,
                                   int processedThisBatch, int remainingSheets) { }
}
