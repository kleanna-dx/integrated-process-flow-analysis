package com.example.processautomation.material;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read-only bridge between the saved structural inventory and an uploaded material-balance workbook. */
@Service
public class FlowMappingReviewService {
    private final ProcessDiagramInventoryService inventory;
    private final Step7MaterialBalanceComparisonService comparison;
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final FlowMappingReviewWorkbookExporter workbookExporter;
    private static final int MAX_CACHED_COMPARISONS = 2;
    private static final int MAX_REVIEW_EXPORT_ROWS = 10_000;
    private static final int MAX_REVIEW_EXPORT_BYTES = 25 * 1024 * 1024;
    private static final int MAX_CACHED_DRAFTS = 5;
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final long DRAFT_CACHE_TTL_MILLIS = CACHE_TTL_MILLIS;
    private final Map<String, CachedComparison> comparisonCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true));
    private final Map<String, CachedDraft> draftCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true));

    public FlowMappingReviewService(ProcessDiagramInventoryService inventory,
            Step7MaterialBalanceComparisonService comparison, JdbcTemplate jdbc,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            FlowMappingReviewWorkbookExporter workbookExporter) {
        this.inventory = inventory;
        this.comparison = comparison;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workbookExporter = workbookExporter;
    }

    @Transactional(readOnly = true)
    public ProcessDiagramInventoryService.MappingReviewSnapshot overview() {
        return inventory.mappingReviewSnapshot();
    }

    @Transactional(readOnly = true)
    public ProcessDiagramInventoryService.MappingCandidateResults candidates(
            String processCode, String sheetName, String query, int page, int pageSize) {
        var result = inventory.mappingReviewCandidates(processCode, sheetName, query, page, pageSize);
        if ("STALE_INVENTORY".equals(result.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "저장 인벤토리가 현재 공식 원본과 일치하지 않아 후보를 제공하지 않았습니다.");
        if ("SHEET_NOT_IN_SAVED_INVENTORY".equals(result.status())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "선택 시트가 hash-matched 인벤토리에 없습니다.");
        if ("NO_SAVED_HASH_MATCHED_INVENTORY".equals(result.status()) || "INVENTORY_INCOMPLETE".equals(result.status()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "현재 원본과 완전히 일치하는 62시트 인벤토리 checkpoint가 없습니다.");
        return result;
    }

    @Transactional(readOnly = true)
    public ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults equipmentOccurrenceBatch(List<String> equipmentIds) {
        var result = inventory.mappingReviewEquipmentOccurrences(equipmentIds);
        if ("STALE_INVENTORY".equals(result.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "인벤토리 checkpoint가 현재 원본과 달라 설비 후보를 표시하지 않았습니다.");
        if ("INVENTORY_INCOMPLETE".equals(result.status())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "62개 모든 시트의 검증된 checkpoint가 없어 설비 후보를 표시하지 않았습니다.");
        return result;
    }

    @Transactional(readOnly = true)
    public ProcessDiagramInventoryService.EquipmentOccurrenceResults equipmentOccurrences(String equipmentId, int page, int pageSize) {
        var result = inventory.mappingReviewEquipmentOccurrences(equipmentId, page, pageSize);
        if ("STALE_INVENTORY".equals(result.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "인벤토리 checkpoint가 현재 원본과 달라 설비 후보를 표시하지 않았습니다.");
        if ("INVENTORY_INCOMPLETE".equals(result.status())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "62개 모든 시트의 검증된 checkpoint가 없어 설비 후보를 표시하지 않았습니다.");
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> compareMaterialBalance(byte[] sourceBytes, String filename, String requestUser,
                                                       int page, int pageSize, String query) {
        if (sourceBytes == null || sourceBytes.length == 0 || sourceBytes.length > 15 * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source workbook size is invalid");
        if (page < 0 || pageSize < 1 || pageSize > 100 || (query != null && query.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page/pageSize/search is outside the allowed range");
        MasterBlob master = readLockedMaster();
        var report = comparison.compare(sourceBytes, master.bytes(), filename, readOfficialEvidenceSnapshot());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sourceFilename", report.sourceFilename());
        response.put("sourceSha256", report.sourceSha256());
        response.put("sourceBytes", report.sourceBytes());
        response.put("inputRows", report.inputRows());
        response.put("workbookSheetCount", report.workbookSheetCount());
        response.put("inputColumnCount", report.inputColumnCount());
        response.put("bothEndpointsMasterMatched", report.bothEndpointsMasterMatched());
        response.put("oneEndpointMasterMatched", report.oneEndpointMasterMatched());
        response.put("noEndpointMasterMatch", report.noEndpointMasterMatch());
        response.put("zeroQuantityRows", report.zeroQuantityRows());
        response.put("positiveQuantityRows", report.positiveQuantityRows());
        response.put("blankQuantityRows", report.blankQuantityRows());
        response.put("blankMaterialTypeRows", report.blankMaterialTypeRows());
        response.put("blankExclusionRows", report.blankExclusionRows());
        response.put("duplicateEndpointPairGroups", report.duplicatedEndpointPairGroups());
        response.put("rowsWithinDuplicateEndpointPairs", report.rowsWithinDuplicatedEndpointPairs());
        response.put("cachedResidualNonzeroRows", report.cachedResidualNonzeroRows());
        response.put("officialEvidence", report.officialEvidence());
        response.put("statuses", report.statuses());
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Step7MaterialBalanceComparisonService.ConnectionComparison> filteredRows = report.connections().stream()
                .filter(row -> normalizedQuery.isBlank() || java.util.stream.Stream.of(row.source(), row.target(),
                        row.quantityText(), row.materialType(), row.sourceMajor(), row.targetMajor(), row.sourceMiddle(),
                        row.targetMiddle(), row.unit(), row.period(), row.quantityState())
                        .filter(java.util.Objects::nonNull).anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery)))
                .toList();
        long offset = (long) page * pageSize;
        List<Map<String, Object>> rows = filteredRows.stream().skip(offset).limit(pageSize).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceRow", row.sourceRow()); item.put("source", row.source()); item.put("target", row.target());
            item.put("quantity", row.quantity()); item.put("quantityText", row.quantityText());
            item.put("quantityState", row.quantityState()); item.put("materialType", row.materialType());
            item.put("sourceMajor", row.sourceMajor()); item.put("targetMajor", row.targetMajor());
            item.put("sourceMiddle", row.sourceMiddle()); item.put("targetMiddle", row.targetMiddle());
            item.put("unit", row.unit()); item.put("period", row.period());
            item.put("sourceExactMasterMatch", row.sourceExactMasterMatch());
            item.put("targetExactMasterMatch", row.targetExactMasterMatch());
            item.put("duplicateEndpointCount", row.duplicateEndpointCount());
            item.put("drawingConnectorCandidate", "미매핑");
            item.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
            item.put("qualityStatuses", qualityStatuses(row)); item.put("reviewReason", row.evidenceNote());
            return item;
        }).toList();
        response.put("rows", rows);
        response.put("page", page); response.put("pageSize", pageSize);
        response.put("totalRows", filteredRows.size());
        response.put("query", normalizedQuery);
        response.put("hasNext", offset + rows.size() < filteredRows.size());
        response.put("blankUnitRows", report.connections().stream().filter(row -> row.unit() == null || row.unit().isBlank()).count());
        response.put("blankPeriodRows", report.connections().stream().filter(row -> row.period() == null || row.period().isBlank()).count());
        response.put("duplicateEndpointPairGroups", report.duplicatedEndpointPairGroups());
        response.put("rowsWithinDuplicateEndpointPairs", report.rowsWithinDuplicatedEndpointPairs());
        response.put("connectorBindingCount", report.officialEvidence().pw01ExplicitEndpointBindings());
        response.put("limitations", report.limitations());
        response.put("reviewStatus", "REVIEW_REQUIRED");
        response.put("verifiedFlowMappingCount", 0);
        response.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        response.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        response.put("officialBaselineModified", false);
        response.put("persisted", false);
        response.put("requestedBy", requestUser);
        response.put("notice", "읽기 전용 비교 결과입니다. ID 문자열 일치는 연결 증명이 아니며 unit/period가 없는 유량으로 실제 계산·mapping을 확정하지 않습니다.");
        return Map.copyOf(response);
    }

    public Map<String, Object> startComparison(byte[] sourceBytes, String filename, String requestUser,
                                                String sessionId, int pageSize, String query, String stateFilter) {
        if (sourceBytes == null || sourceBytes.length == 0 || sourceBytes.length > 15 * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source workbook size is invalid");
        if (sessionId == null || sessionId.isBlank() || pageSize < 1 || pageSize > 100
                || (query != null && query.length() > 128) || (stateFilter != null && stateFilter.length() > 64))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comparison options are outside the allowed range");
        MasterBlob master = readLockedMaster();
        Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot evidence = readOfficialEvidenceSnapshot();
        var inventorySnapshot = inventory.mappingReviewSnapshot();
        String sourceSha = sha256(sourceBytes);
        String cacheKey = sha256((requestUser + "|" + sessionId + "|" + sourceSha + "|" + master.sha256()
                + "|" + inventorySnapshot.sourceFingerprint() + "|" + inventorySnapshot.checkpointSourceFingerprint()
                + "|" + evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CachedComparison existing = findCacheByKey(cacheKey, requestUser, sessionId);
        if (existing != null) {
            Map<String, Object> cachedResponse = comparisonPage(existing.id(), requestUser, sessionId, 0,
                    pageSize, query, stateFilter);
            cachedResponse.put("analysisId", existing.id()); cachedResponse.put("cacheHit", true);
            cachedResponse.put("cacheTtlSeconds", CACHE_TTL_MILLIS / 1000); cachedResponse.put("sourceUploadBytesRetained", false);
            return cachedResponse;
        }
        Step7MaterialBalanceComparisonService.ComparisonReport report;
        try {
            report = comparison.compare(sourceBytes, master.bytes(), filename, evidence);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "물질수지 workbook을 읽기 전용으로 해석하지 못했습니다.", exception);
        }
        Set<String> endpointIds = report.connections().stream().flatMap(row -> java.util.stream.Stream.of(row.source(), row.target()))
                .filter(id -> id != null && id.matches("[A-Za-z0-9._-]{1,64}"))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults occurrenceBatch;
        if ("STALE_INVENTORY".equals(inventorySnapshot.inventoryStatus())) {
            occurrenceBatch = new ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults(
                    inventorySnapshot.sourceFingerprint(), inventorySnapshot.checkpointSourceFingerprint(),
                    "STALE_INVENTORY", Map.of(), Map.of());
        } else if (endpointIds.isEmpty()) {
            occurrenceBatch = new ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults(
                    inventorySnapshot.sourceFingerprint(), inventorySnapshot.checkpointSourceFingerprint(),
                    "MISSING_EVIDENCE", Map.of(), Map.of());
        } else {
            occurrenceBatch = inventory.mappingReviewEquipmentOccurrences(List.copyOf(endpointIds));
        }
        MaterialBalanceReviewSnapshot snapshot = buildReviewSnapshot(report, master.sha256(), inventorySnapshot, occurrenceBatch);
        String analysisId = UUID.randomUUID().toString();
        CachedComparison cached = new CachedComparison(analysisId, cacheKey, requestUser, sessionId, Instant.now(), snapshot, report);
        synchronized (comparisonCache) {
            comparisonCache.entrySet().removeIf(entry -> Instant.now().toEpochMilli() - entry.getValue().createdAt().toEpochMilli() > CACHE_TTL_MILLIS);
            comparisonCache.put(analysisId, cached);
            while (comparisonCache.size() > MAX_CACHED_COMPARISONS)
                comparisonCache.remove(comparisonCache.keySet().iterator().next());
        }
        Map<String, Object> response = comparisonPage(analysisId, requestUser, sessionId, 0, pageSize, query, stateFilter);
        response.put("analysisId", analysisId);
        response.put("cacheTtlSeconds", CACHE_TTL_MILLIS / 1000);
        response.put("sourceUploadBytesRetained", false);
        return response;
    }

    public Map<String, Object> comparisonPage(String analysisId, String requestUser, String sessionId,
                                              int page, int pageSize, String query, String stateFilter) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (query != null && query.length() > 128)
                || (stateFilter != null && stateFilter.length() > 64))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page, pageSize, q, or status is outside the allowed range");
        CachedComparison cached = findComparison(analysisId, requestUser, sessionId);
        MaterialBalanceReviewSnapshot snapshot = cached.snapshot();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String requestedStatus = stateFilter == null ? "" : stateFilter.trim();
        validateStateFilter(requestedStatus, snapshot);
        List<MaterialBalanceReviewRow> filtered = snapshot.rows().stream()
                .filter(row -> matchesStateFilter(row, requestedStatus))
                .filter(row -> q.isBlank() || row.searchText().contains(q)).toList();
        Map<String, Object> reviewSummary = reviewSummary(snapshot);
        reviewSummary.put("filteredRows", filtered.size());
        reviewSummary.put("query", q);
        reviewSummary.put("statusFilter", requestedStatus);
        long offset = (long) page * pageSize;
        List<Map<String, Object>> rows = filtered.stream().skip(offset).limit(pageSize)
                .map(row -> reviewRowMap(row, snapshot.candidatesById(), snapshot.candidateCountsById())).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sourceFilename", snapshot.sourceFilename()); response.put("sourceSha256", snapshot.sourceSha256());
        response.put("sourceBytes", snapshot.sourceBytes()); response.put("masterSha256", snapshot.masterSha256());
        response.put("inputRows", snapshot.inputRows()); response.put("workbookSheetCount", snapshot.workbookSheetCount());
        response.put("inputColumnCount", snapshot.inputColumnCount()); response.put("bothEndpointsMasterMatched", snapshot.bothEndpointsMasterMatched());
        response.put("oneEndpointMasterMatched", snapshot.oneEndpointMasterMatched()); response.put("noEndpointMasterMatch", snapshot.noEndpointMasterMatch());
        response.put("positiveQuantityRows", snapshot.positiveQuantityRows()); response.put("zeroQuantityRows", snapshot.zeroQuantityRows());
        response.put("blankQuantityRows", snapshot.blankQuantityRows()); response.put("blankMaterialTypeRows", snapshot.blankMaterialTypeRows());
        response.put("blankUnitRows", snapshot.blankUnitRows()); response.put("blankPeriodRows", snapshot.blankPeriodRows());
        response.put("blankExclusionRows", snapshot.blankExclusionRows());
        response.put("duplicateEndpointPairGroups", snapshot.duplicateEndpointPairGroups());
        response.put("rowsWithinDuplicateEndpointPairs", snapshot.rowsWithinDuplicateEndpointPairs());
        response.put("officialEvidence", snapshot.officialEvidence()); response.put("inventoryStatus", snapshot.inventoryStatus());
        response.put("sourceFingerprint", snapshot.sourceFingerprint());
        response.put("checkpointSourceFingerprint", snapshot.checkpointSourceFingerprint());
        response.put("inventoryUpdatedAt", snapshot.inventoryUpdatedAt()); response.put("inventoryTotalSheets", snapshot.inventoryTotalSheets());
        response.put("explicitConnectorBindingCount", snapshot.explicitConnectorBindingCount());
        response.put("priorityGroups", snapshot.priorityGroups()); response.put("statusCounts", snapshot.statusCounts());
        response.put("reviewSummary", reviewSummary);
        response.put("rows", rows); response.put("page", page); response.put("pageSize", pageSize);
        response.put("totalRows", filtered.size()); response.put("hasNext", offset + rows.size() < filtered.size());
        response.put("query", q); response.put("statusFilter", requestedStatus);
        response.put("analysisId", analysisId); response.put("reviewStatus", "NOT_REVIEWED");
        response.put("verifiedFlowMappingCount", 0); response.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        response.put("officialReadiness", "CALCULATION_UNAVAILABLE"); response.put("officialBaselineModified", false);
        response.put("persisted", false); response.put("cacheExpiresAt", cached.createdAt().plusMillis(CACHE_TTL_MILLIS));
        response.put("limitations", cached.report().limitations());
        return response;
    }

    public byte[] exportReview(String analysisId, String requestUser, String sessionId, String format) {
        return exportReviewArtifact(analysisId, requestUser, sessionId, format, null, null).bytes();
    }

    public byte[] exportReview(String analysisId, String requestUser, String sessionId, String format, String stateFilter) {
        return exportReviewArtifact(analysisId, requestUser, sessionId, format, stateFilter, null).bytes();
    }

    public byte[] exportReview(String analysisId, String requestUser, String sessionId, String format,
                              String stateFilter, String query) {
        return exportReviewArtifact(analysisId, requestUser, sessionId, format, stateFilter, query).bytes();
    }

    public ReviewExport exportReviewArtifact(String analysisId, String requestUser, String sessionId, String format,
                                             String stateFilter, String query) {
        CachedComparison cached = findComparison(analysisId, requestUser, sessionId);
        MaterialBalanceReviewSnapshot original = cached.snapshot();
        String requestedStatus = stateFilter == null ? "" : stateFilter.trim();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어가 너무 깁니다.");
        validateStateFilter(requestedStatus, original);
        List<MaterialBalanceReviewRow> selectedRows = original.rows().stream()
                .filter(row -> matchesStateFilter(row, requestedStatus))
                .filter(row -> normalizedQuery.isBlank() || row.searchText().contains(normalizedQuery)).toList();
        if (selectedRows.size() > MAX_REVIEW_EXPORT_ROWS)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "필터 결과가 다운로드 제한 행 수를 초과했습니다.");
        MaterialBalanceReviewSnapshot snapshot = withRows(original, selectedRows);
        String exportedQuery = query == null ? "" : query.trim();
        byte[] bytes = switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx" -> workbookExporter.export(snapshot, requestedStatus, exportedQuery);
            case "json" -> writeJson(snapshot, requestedStatus, exportedQuery);
            case "csv" -> writeCsv(snapshot);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be csv, json, or xlsx");
        };
        if (bytes.length > MAX_REVIEW_EXPORT_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "검토 보고서가 다운로드 크기 제한을 초과했습니다.");
        return new ReviewExport(bytes, selectedRows.size(), requestedStatus, exportedQuery);
    }

    public ReviewExport exportReviewInputTemplate(String analysisId, String requestUser, String sessionId, String format) {
        MaterialBalanceReviewSnapshot snapshot = findComparison(analysisId, requestUser, sessionId).snapshot();
        byte[] bytes = switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx" -> workbookExporter.export(snapshot);
            case "csv" -> FlowMappingReviewDraftFileService.exportInputTemplateCsv(snapshot);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be csv or xlsx");
        };
        if (bytes.length > MAX_REVIEW_EXPORT_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "검토 양식 크기 제한을 초과했습니다.");
        return new ReviewExport(bytes, snapshot.rows().size(), "", "");
    }

    public void clearReviewDraftsForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        synchronized (draftCache) {
            draftCache.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
        }
        synchronized (comparisonCache) {
            comparisonCache.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
        }
    }

    public FlowMappingReviewDraft.ImportPreview importReviewDraft(String analysisId, String requestUser, String sessionId,
            String filename, byte[] bytes) {
        CachedComparison comparison = findComparison(analysisId, requestUser, sessionId);
        FlowMappingReviewDraft.ImportResult result = FlowMappingReviewDraftFileService.parseAndValidate(
                bytes, filename, comparison.snapshot());
        FlowMappingReviewDraft.ImportPreview preview = new FlowMappingReviewDraft.ImportPreview(UUID.randomUUID().toString(),
                comparison.snapshot().sourceSha256(), result.uploadedRows(), result.validDrafts().size(),
                result.reviewRequired().size(), result.conflicts().size(), result.missing().size(), result.allRows(), false, 0,
                "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "CALCULATION_UNAVAILABLE");
        synchronized (draftCache) {
            Instant now = Instant.now();
            draftCache.entrySet().removeIf(entry -> now.toEpochMilli() - entry.getValue().createdAt().toEpochMilli()
                    > DRAFT_CACHE_TTL_MILLIS);
            draftCache.put(preview.previewId(), new CachedDraft(analysisId, requestUser, sessionId, now, preview));
            while (draftCache.size() > MAX_CACHED_DRAFTS) draftCache.remove(draftCache.keySet().iterator().next());
        }
        return preview;
    }

    public FlowMappingReviewDraft.ImportPreview reviewDraftPreview(String analysisId, String previewId,
            String requestUser, String sessionId) {
        return findDraft(analysisId, previewId, requestUser, sessionId).preview();
    }

    public ReviewExport exportReviewDraft(String analysisId, String previewId, String requestUser, String sessionId,
            String format) {
        CachedDraft cached = findDraft(analysisId, previewId, requestUser, sessionId);
        FlowMappingReviewDraft.ImportPreview preview = cached.preview();
        byte[] bytes = switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "csv" -> FlowMappingReviewDraftFileService.exportCsv(preview);
            case "json" -> FlowMappingReviewDraftFileService.exportJson(preview, preview.sourceSha256());
            case "xlsx" -> FlowMappingReviewDraftFileService.exportXlsx(preview, preview.sourceSha256());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be csv, json, or xlsx");
        };
        if (bytes.length > MAX_REVIEW_EXPORT_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "검토 재출력 크기 제한을 초과했습니다.");
        return new ReviewExport(bytes, preview.rows().size(), "IMPORT_REVIEW_DRAFT", "");
    }

    private CachedDraft findDraft(String analysisId, String previewId, String requestUser, String sessionId) {
        CachedDraft cached;
        synchronized (draftCache) {
            cached = draftCache.get(previewId);
            if (cached != null && Instant.now().toEpochMilli() - cached.createdAt().toEpochMilli() > DRAFT_CACHE_TTL_MILLIS) {
                draftCache.remove(previewId); cached = null;
            }
        }
        if (cached == null || !cached.analysisId().equals(analysisId) || !cached.owner().equals(requestUser)
                || !cached.sessionId().equals(sessionId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "검토 초안이 만료됐거나 현재 로그인 사용자/분석 세션에 속하지 않습니다.");
        findComparison(analysisId, requestUser, sessionId);
        return cached;
    }

    private CachedComparison findCacheByKey(String cacheKey, String requestUser, String sessionId) {
        synchronized (comparisonCache) {
            comparisonCache.entrySet().removeIf(entry -> Instant.now().toEpochMilli() - entry.getValue().createdAt().toEpochMilli() > CACHE_TTL_MILLIS);
            return comparisonCache.values().stream().filter(entry -> entry.cacheKey().equals(cacheKey)
                    && entry.owner().equals(requestUser) && entry.sessionId().equals(sessionId)).findFirst().orElse(null);
        }
    }

    private CachedComparison findComparison(String analysisId, String requestUser, String sessionId) {
        CachedComparison cached;
        synchronized (comparisonCache) {
            cached = comparisonCache.get(analysisId);
            if (cached != null && Instant.now().toEpochMilli() - cached.createdAt().toEpochMilli() > CACHE_TTL_MILLIS) {
                comparisonCache.remove(analysisId); cached = null;
            }
        }
        if (cached == null || !cached.owner().equals(requestUser) || !cached.sessionId().equals(sessionId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 세션이 만료됐거나 현재 로그인 사용자에게 속하지 않습니다.");
        return cached;
    }

    private MaterialBalanceReviewSnapshot buildReviewSnapshot(Step7MaterialBalanceComparisonService.ComparisonReport report,
            String masterSha, ProcessDiagramInventoryService.MappingReviewSnapshot inventorySnapshot,
            ProcessDiagramInventoryService.EquipmentOccurrenceBatchResults batch) {
        Map<String, List<ProcessDiagramInventoryService.EquipmentOccurrence>> candidates = batch.occurrencesByEquipmentId();
        Map<String, Integer> candidateCounts = batch.totalOccurrencesByEquipmentId();
        List<MaterialBalanceReviewRow> rows = new ArrayList<>();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (var comparisonRow : report.connections()) {
            List<ProcessDiagramInventoryService.EquipmentOccurrence> sourceMatches = candidates.getOrDefault(comparisonRow.source(), List.of());
            List<ProcessDiagramInventoryService.EquipmentOccurrence> targetMatches = candidates.getOrDefault(comparisonRow.target(), List.of());
            LinkedHashSet<String> flags = new LinkedHashSet<>();
            boolean staleInventory = "STALE_INVENTORY".equals(batch.status())
                    || "STALE_INVENTORY".equals(inventorySnapshot.inventoryStatus());
            boolean usableInventory = !staleInventory && "HASH_MATCHED_CHECKPOINT".equals(inventorySnapshot.inventoryStatus())
                    && "HASH_MATCHED_CHECKPOINT".equals(batch.status());
            if (comparisonRow.sourceExactMasterMatch() || comparisonRow.targetExactMasterMatch()
                    || !sourceMatches.isEmpty() || !targetMatches.isEmpty()) flags.add("STRING_MATCH_ONLY");
            if (staleInventory) flags.add("STALE_INVENTORY");
            else if (!usableInventory) flags.add("MISSING_EVIDENCE");
            else {
                if (sourceMatches.isEmpty()) flags.add("MISSING_SOURCE_EQUIPMENT_CANDIDATE");
                else if (candidateCounts.getOrDefault(comparisonRow.source(), sourceMatches.size()) > 1) flags.add("MULTIPLE_LOCATION_CANDIDATES");
                if (targetMatches.isEmpty()) flags.add("MISSING_TARGET_EQUIPMENT_CANDIDATE");
                else if (candidateCounts.getOrDefault(comparisonRow.target(), targetMatches.size()) > 1) flags.add("MULTIPLE_LOCATION_CANDIDATES");
            }
            if (comparisonRow.duplicateEndpointCount() > 1) flags.add("DUPLICATE_ENDPOINT_PAIR");
            if (comparisonRow.materialType() == null || comparisonRow.materialType().isBlank()) flags.add("MISSING_MATERIAL_TYPE");
            if (comparisonRow.unit() == null || comparisonRow.unit().isBlank()) flags.add("MISSING_FLOW_UNIT");
            if (comparisonRow.period() == null || comparisonRow.period().isBlank()) flags.add("MISSING_PERIOD");
            flags.add("CONNECTOR_BINDING_REQUIRED"); flags.add("MISSING_EVIDENCE"); flags.add("REVIEW_REQUIRED");
            List<String> quality = List.copyOf(flags);
            quality.forEach(status -> statusCounts.merge(status, 1L, Long::sum));
            String searchText = java.util.stream.Stream.of(comparisonRow.source(), comparisonRow.target(), comparisonRow.materialType(),
                    comparisonRow.sourceMajor(), comparisonRow.targetMajor(), comparisonRow.sourceMiddle(), comparisonRow.targetMiddle(),
                    comparisonRow.quantityText(), comparisonRow.unit(), comparisonRow.period())
                    .filter(java.util.Objects::nonNull).collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
            rows.add(new MaterialBalanceReviewRow(report.sourceSha256() + ":" + comparisonRow.sourceRow(), comparisonRow.sourceRow(),
                    comparisonRow.source(), comparisonRow.target(), comparisonRow.sourceMajor(), comparisonRow.targetMajor(),
                    comparisonRow.sourceMiddle(), comparisonRow.targetMiddle(), comparisonRow.quantity(), comparisonRow.quantityText(),
                    comparisonRow.quantityState(), comparisonRow.materialType(), comparisonRow.unit(), comparisonRow.period(),
                    comparisonRow.sourceExactMasterMatch(), comparisonRow.targetExactMasterMatch(), comparisonRow.duplicateEndpointCount(),
                    quality, "NOT_MAPPED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "NOT_REVIEWED", comparisonRow.evidenceNote(),
                    candidateCounts.getOrDefault(comparisonRow.source(), sourceMatches.size()),
                    candidateCounts.getOrDefault(comparisonRow.target(), targetMatches.size()), searchText));
        }
        rows.sort(java.util.Comparator.comparingInt((MaterialBalanceReviewRow row) -> priorityOrder(row.qualityStatuses()))
                .thenComparingInt(MaterialBalanceReviewRow::sourceRow));
        List<PriorityGroup> groups = List.of(
                group(rows, "UNRESOLVED_EQUIPMENT", "설비 ID 후보 없음", 1, "MISSING_SOURCE_EQUIPMENT_CANDIDATE", "MISSING_TARGET_EQUIPMENT_CANDIDATE"),
                group(rows, "MULTIPLE_LOCATION_CANDIDATES", "복수 위치 후보", 2, "MULTIPLE_LOCATION_CANDIDATES"),
                group(rows, "DUPLICATE_ENDPOINT_PAIR", "중복 endpoint 쌍", 3, "DUPLICATE_ENDPOINT_PAIR"),
                group(rows, "MISSING_MATERIAL_TYPE", "물질 종류 누락", 4, "MISSING_MATERIAL_TYPE"),
                group(rows, "MISSING_FLOW_UNIT", "유량 단위 누락", 5, "MISSING_FLOW_UNIT"),
                group(rows, "MISSING_PERIOD", "기준 기간 누락", 6, "MISSING_PERIOD"),
                group(rows, "CONNECTOR_BINDING_REQUIRED", "Connector 매핑 필요", 7, "CONNECTOR_BINDING_REQUIRED"),
                group(rows, "MISSING_EVIDENCE", "증빙 필요", 8, "MISSING_EVIDENCE"));
        return new MaterialBalanceReviewSnapshot(report.sourceFilename(), report.sourceSha256(), report.sourceBytes(),
                report.inputRows(), report.workbookSheetCount(), report.inputColumnCount(), report.bothEndpointsMasterMatched(),
                report.oneEndpointMasterMatched(), report.noEndpointMasterMatch(), report.positiveQuantityRows(), report.zeroQuantityRows(),
                report.blankQuantityRows(), report.blankMaterialTypeRows(), rows.stream().filter(r -> r.unit().isBlank()).count(),
                rows.stream().filter(r -> r.period().isBlank()).count(), report.blankExclusionRows(),
                report.duplicatedEndpointPairGroups(), report.rowsWithinDuplicatedEndpointPairs(), report.officialEvidence(), masterSha,
                inventorySnapshot.inventoryStatus(), inventorySnapshot.sourceFingerprint(), inventorySnapshot.checkpointSourceFingerprint(),
                inventorySnapshot.inventoryUpdatedAt(), inventorySnapshot.totalSheetCount(), batch.status(),
                inventorySnapshot.explicitConnectorEndpointBindingCount(), groups, Map.copyOf(statusCounts), List.copyOf(rows),
                Map.copyOf(candidates), Map.copyOf(candidateCounts));
    }

    private PriorityGroup group(List<MaterialBalanceReviewRow> rows, String code, String label, int priority, String... statuses) {
        long count = rows.stream().filter(row -> java.util.Arrays.stream(statuses).anyMatch(row.qualityStatuses()::contains)).count();
        return new PriorityGroup(code, label, count, priority);
    }

    private Map<String, Object> reviewSummary(MaterialBalanceReviewSnapshot snapshot) {
        boolean candidateCountsAvailable = "HASH_MATCHED_CHECKPOINT".equals(snapshot.inventoryStatus())
                && "HASH_MATCHED_CHECKPOINT".equals(snapshot.candidateIndexStatus());
        long both = snapshot.rows().stream().filter(row -> row.sourceLocationCandidateCount() > 0
                && row.targetLocationCandidateCount() > 0).count();
        long one = snapshot.rows().stream().filter(row -> (row.sourceLocationCandidateCount() > 0)
                != (row.targetLocationCandidateCount() > 0)).count();
        long none = snapshot.rows().stream().filter(row -> row.sourceLocationCandidateCount() == 0
                && row.targetLocationCandidateCount() == 0).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateCountsAvailable", candidateCountsAvailable);
        result.put("inputRows", snapshot.inputRows());
        result.put("bothEndpointCandidateRows", candidateCountsAvailable ? both : null);
        result.put("oneEndpointCandidateRows", candidateCountsAvailable ? one : null);
        result.put("noEndpointCandidateRows", candidateCountsAvailable ? none : null);
        result.put("multipleLocationCandidateRows", candidateCountsAvailable
                ? snapshot.rows().stream().filter(row -> row.sourceLocationCandidateCount() > 1
                        || row.targetLocationCandidateCount() > 1).count() : null);
        result.put("duplicateEndpointPairGroups", snapshot.duplicateEndpointPairGroups());
        result.put("missingMaterialTypeRows", snapshot.blankMaterialTypeRows());
        result.put("missingUnitRows", snapshot.blankUnitRows());
        result.put("missingPeriodRows", snapshot.blankPeriodRows());
        result.put("connectorBindingRequiredRows", snapshot.statusCounts().getOrDefault("CONNECTOR_BINDING_REQUIRED", 0L));
        result.put("verifiedFlowMappingCount", 0);
        return result;
    }

    private boolean matchesStateFilter(MaterialBalanceReviewRow row, String filter) {
        if (filter == null || filter.isBlank()) return true;
        return switch (filter) {
            case "BOTH_ENDPOINT_CANDIDATES" -> row.sourceLocationCandidateCount() > 0 && row.targetLocationCandidateCount() > 0;
            case "ONE_ENDPOINT_CANDIDATE" -> (row.sourceLocationCandidateCount() > 0) != (row.targetLocationCandidateCount() > 0);
            case "NO_ENDPOINT_CANDIDATES" -> row.sourceLocationCandidateCount() == 0 && row.targetLocationCandidateCount() == 0;
            case "VERIFIED_FLOW_MAPPING" -> "VERIFIED".equals(row.reviewStatus());
            case "UNRESOLVED_EQUIPMENT" -> row.qualityStatuses().contains("MISSING_SOURCE_EQUIPMENT_CANDIDATE")
                    || row.qualityStatuses().contains("MISSING_TARGET_EQUIPMENT_CANDIDATE");
            default -> row.qualityStatuses().contains(filter) || row.reviewStatus().equals(filter);
        };
    }

    private void validateStateFilter(String filter, MaterialBalanceReviewSnapshot snapshot) {
        if (filter == null || filter.isBlank()) return;
        Set<String> supported = Set.of("BOTH_ENDPOINT_CANDIDATES", "ONE_ENDPOINT_CANDIDATE", "NO_ENDPOINT_CANDIDATES",
                "VERIFIED_FLOW_MAPPING", "UNRESOLVED_EQUIPMENT", "MISSING_SOURCE_EQUIPMENT_CANDIDATE",
                "MISSING_TARGET_EQUIPMENT_CANDIDATE", "MULTIPLE_LOCATION_CANDIDATES", "DUPLICATE_ENDPOINT_PAIR",
                "MISSING_MATERIAL_TYPE", "MISSING_FLOW_UNIT", "MISSING_PERIOD", "CONNECTOR_BINDING_REQUIRED",
                "MISSING_EVIDENCE", "REVIEW_REQUIRED", "NOT_REVIEWED", "NOT_MAPPED");
        if (!supported.contains(filter)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 검토 필터입니다.");
        if (Set.of("BOTH_ENDPOINT_CANDIDATES", "ONE_ENDPOINT_CANDIDATE", "NO_ENDPOINT_CANDIDATES",
                "MULTIPLE_LOCATION_CANDIDATES").contains(filter)
                && (!"HASH_MATCHED_CHECKPOINT".equals(snapshot.inventoryStatus())
                        || !"HASH_MATCHED_CHECKPOINT".equals(snapshot.candidateIndexStatus()))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "현재 hash-matched 도면 후보 인벤토리가 없어 후보 필터를 적용하지 않았습니다.");
        }
    }

    private MaterialBalanceReviewSnapshot withRows(MaterialBalanceReviewSnapshot original, List<MaterialBalanceReviewRow> selectedRows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        selectedRows.forEach(row -> row.qualityStatuses().forEach(status -> counts.merge(status, 1L, Long::sum)));
        List<PriorityGroup> groups = List.of(
                group(selectedRows, "UNRESOLVED_EQUIPMENT", "설비 ID 후보 없음", 1, "MISSING_SOURCE_EQUIPMENT_CANDIDATE", "MISSING_TARGET_EQUIPMENT_CANDIDATE"),
                group(selectedRows, "MULTIPLE_LOCATION_CANDIDATES", "복수 위치 후보", 2, "MULTIPLE_LOCATION_CANDIDATES"),
                group(selectedRows, "DUPLICATE_ENDPOINT_PAIR", "중복 endpoint 쌍", 3, "DUPLICATE_ENDPOINT_PAIR"),
                group(selectedRows, "MISSING_MATERIAL_TYPE", "물질 종류 누락", 4, "MISSING_MATERIAL_TYPE"),
                group(selectedRows, "MISSING_FLOW_UNIT", "유량 단위 누락", 5, "MISSING_FLOW_UNIT"),
                group(selectedRows, "MISSING_PERIOD", "기준 기간 누락", 6, "MISSING_PERIOD"),
                group(selectedRows, "CONNECTOR_BINDING_REQUIRED", "Connector 매핑 필요", 7, "CONNECTOR_BINDING_REQUIRED"),
                group(selectedRows, "MISSING_EVIDENCE", "증빙 필요", 8, "MISSING_EVIDENCE"));
        return new MaterialBalanceReviewSnapshot(original.sourceFilename(), original.sourceSha256(), original.sourceBytes(),
                original.inputRows(), original.workbookSheetCount(), original.inputColumnCount(), original.bothEndpointsMasterMatched(),
                original.oneEndpointMasterMatched(), original.noEndpointMasterMatch(), original.positiveQuantityRows(),
                original.zeroQuantityRows(), original.blankQuantityRows(), selectedRows.stream().filter(row -> blank(row.materialType()).isBlank()).count(),
                selectedRows.stream().filter(row -> blank(row.unit()).isBlank()).count(),
                selectedRows.stream().filter(row -> blank(row.period()).isBlank()).count(), original.blankExclusionRows(),
                original.duplicateEndpointPairGroups(), original.rowsWithinDuplicateEndpointPairs(), original.officialEvidence(),
                original.masterSha256(), original.inventoryStatus(), original.sourceFingerprint(), original.checkpointSourceFingerprint(),
                original.inventoryUpdatedAt(), original.inventoryTotalSheets(), original.candidateIndexStatus(),
                original.explicitConnectorBindingCount(), groups, Map.copyOf(counts), selectedRows,
                original.candidatesById(), original.candidateCountsById());
    }

    private int priorityOrder(List<String> statuses) {
        if (statuses.contains("MISSING_SOURCE_EQUIPMENT_CANDIDATE") || statuses.contains("MISSING_TARGET_EQUIPMENT_CANDIDATE")) return 1;
        if (statuses.contains("MULTIPLE_LOCATION_CANDIDATES")) return 2;
        if (statuses.contains("DUPLICATE_ENDPOINT_PAIR")) return 3;
        if (statuses.contains("MISSING_MATERIAL_TYPE") || statuses.contains("MISSING_FLOW_UNIT") || statuses.contains("MISSING_PERIOD")) return 4;
        return 5;
    }

    private Map<String, Object> reviewRowMap(MaterialBalanceReviewRow row,
            Map<String, List<ProcessDiagramInventoryService.EquipmentOccurrence>> candidates, Map<String, Integer> totals) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rowId", row.rowId()); value.put("sourceRow", row.sourceRow()); value.put("source", row.source());
        value.put("target", row.target()); value.put("sourceMajor", row.sourceMajor()); value.put("targetMajor", row.targetMajor());
        value.put("sourceMiddle", row.sourceMiddle()); value.put("targetMiddle", row.targetMiddle());
        value.put("quantity", row.quantity()); value.put("quantityText", row.quantityText()); value.put("quantityState", row.quantityState());
        value.put("materialType", row.materialType()); value.put("unit", row.unit()); value.put("period", row.period());
        value.put("sourceExactMasterMatch", row.sourceExactMasterMatch()); value.put("targetExactMasterMatch", row.targetExactMasterMatch());
        value.put("duplicateEndpointCount", row.duplicateEndpointCount()); value.put("sourceLocationCandidateCount", row.sourceLocationCandidateCount());
        value.put("targetLocationCandidateCount", row.targetLocationCandidateCount());
        value.put("sourceDiagramCandidates", candidates.getOrDefault(row.source(), List.of()));
        value.put("targetDiagramCandidates", candidates.getOrDefault(row.target(), List.of()));
        value.put("connectorBindingStatus", row.connectorBindingStatus()); value.put("physicalConnectionEvidenceStatus", row.physicalConnectionEvidenceStatus());
        value.put("qualityStatuses", row.qualityStatuses()); value.put("reviewStatus", row.reviewStatus()); value.put("reviewReason", row.reviewReason());
        return value;
    }

    private byte[] writeJson(MaterialBalanceReviewSnapshot snapshot, String filter, String query) {
        Map<String, Object> summary = summary(snapshot);
        summary.put("exportStatusFilter", filter);
        summary.put("exportQuery", query);
        summary.put("exportedRows", snapshot.rows().size());
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("summary", summary, "rows", snapshot.rows())); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize review JSON", exception); }
    }

    private byte[] writeCsv(MaterialBalanceReviewSnapshot snapshot) {
        StringBuilder csv = new StringBuilder("row_id,source_sheet,source_row,source_sha256,source_id,target_id,source_major,target_major,quantity_text,quantity_numeric,quantity_state,material_type,unit,period,source_locations,target_locations,connector_binding,physical_connection_evidence,quality_statuses,review_status,review_reason\r\n");
        for (MaterialBalanceReviewRow row : snapshot.rows()) {
            List<String> values = List.of(row.rowId(), "입력시트", Integer.toString(row.sourceRow()), snapshot.sourceSha256(),
                    blank(row.source()), blank(row.target()),
                    blank(row.sourceMajor()), blank(row.targetMajor()), blank(row.quantityText()), row.quantity() == null ? "" : row.quantity().toPlainString(),
                    blank(row.quantityState()), blank(row.materialType()), blank(row.unit()), blank(row.period()),
                    exportLocations(snapshot.candidatesById().getOrDefault(row.source(), List.of())),
                    exportLocations(snapshot.candidatesById().getOrDefault(row.target(), List.of())), row.connectorBindingStatus(),
                    row.physicalConnectionEvidenceStatus(), String.join("|", row.qualityStatuses()), row.reviewStatus(), blank(row.reviewReason()));
            csv.append(values.stream().map(FlowMappingReviewService::csvCell).collect(Collectors.joining(","))).append("\r\n");
        }
        return ("\uFEFF" + csv).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Object> summary(MaterialBalanceReviewSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceFilename", snapshot.sourceFilename()); result.put("sourceWorksheet", "입력시트");
        result.put("sourceSha256", snapshot.sourceSha256());
        result.put("inputRows", snapshot.inputRows()); result.put("priorityGroupsOverlap", true);
        result.put("inventoryStatus", snapshot.inventoryStatus()); result.put("sourceFingerprint", snapshot.sourceFingerprint());
        result.put("checkpointSourceFingerprint", snapshot.checkpointSourceFingerprint());
        result.put("inventoryUpdatedAt", snapshot.inventoryUpdatedAt() == null ? null : snapshot.inventoryUpdatedAt().toString());
        result.put("priorityGroups", snapshot.priorityGroups()); result.put("statusCounts", snapshot.statusCounts());
        result.put("verifiedFlowMappingCount", 0); result.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        result.put("officialReadiness", "CALCULATION_UNAVAILABLE"); result.put("officialBaselineModified", false);
        return result;
    }

    private String exportLocations(List<ProcessDiagramInventoryService.EquipmentOccurrence> candidates) {
        return candidates.stream().map(item -> item.processCode() + "/" + item.sheetName() + " " + item.topLeftCell()
                + "-" + item.bottomRightCell() + " [" + item.candidateStatus() + "]").collect(Collectors.joining("; "));
    }
    private String blank(String value) { return value == null ? "" : value; }
    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("(?s)^\\s*[=+@].*|^\\s*-\\d.*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static List<String> qualityStatuses(Step7MaterialBalanceComparisonService.ConnectionComparison row) {
        java.util.LinkedHashSet<String> statuses = new java.util.LinkedHashSet<>();
        if (row == null) return List.of("REVIEW_REQUIRED", "MISSING_EVIDENCE");
        if (row.sourceExactMasterMatch() || row.targetExactMasterMatch()) statuses.add("STRING_MATCH_ONLY");
        if (row.duplicateEndpointCount() > 1) statuses.add("DUPLICATE_ENDPOINT_PAIR");
        if (row.materialType() == null || row.materialType().isBlank()) statuses.add("MISSING_MATERIAL_TYPE");
        if (row.unit() == null || row.unit().isBlank()) statuses.add("MISSING_FLOW_UNIT");
        if (row.period() == null || row.period().isBlank()) statuses.add("MISSING_PERIOD");
        statuses.add("CONNECTOR_BINDING_REQUIRED");
        statuses.add("MISSING_EVIDENCE");
        statuses.add("REVIEW_REQUIRED");
        return List.copyOf(statuses);
    }

    private MasterBlob readLockedMaster() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.sha256,d.content_bytes AS contentBytes
                FROM baseline_document d JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.document_role='MASTER' AND b.version_code='V1.0' AND b.locked=TRUE
                """);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "잠금 V1.0 공식 MASTER가 단일 파일이 아니므로 비교하지 않았습니다.");
        Map<String, Object> row = rows.get(0);
        byte[] bytes = (byte[]) row.get("contentBytes");
        String sha = String.valueOf(row.get("sha256"));
        if (bytes == null || bytes.length == 0 || !sha256(bytes).equalsIgnoreCase(sha))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "잠금 V1.0 MASTER BLOB/SHA를 확인할 수 없어 비교를 수행하지 않았습니다.");
        return new MasterBlob(sha, bytes);
    }

    private Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot readOfficialEvidenceSnapshot() {
        int documents = count("""
                SELECT COUNT(*) FROM baseline_document d JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE
                """);
        int equipment = count("""
                SELECT COUNT(*) FROM equipment_record e JOIN baseline_release b ON b.id=e.baseline_release_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE
                """);
        int flows = count("""
                SELECT COUNT(*) FROM equipment_flow f JOIN equipment_record e ON e.id=f.equipment_id
                JOIN baseline_release b ON b.id=e.baseline_release_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE
                """);
        int confirmed = count("""
                SELECT COUNT(*) FROM process_relation r JOIN baseline_release b ON b.id=r.baseline_release_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE AND r.relation_status='CONFIRMED'
                """);
        int reviewRequired = count("""
                SELECT COUNT(*) FROM process_relation r JOIN baseline_release b ON b.id=r.baseline_release_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE AND r.relation_status='REVIEW_REQUIRED'
                """);
        int pw01Connectors = count("""
                SELECT COUNT(*) FROM drawing_object o JOIN workbook_sheet s ON s.id=o.workbook_sheet_id
                JOIN baseline_document d ON d.id=s.baseline_document_id JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.process_code='PW-01' AND d.document_role='PROCESS' AND b.version_code='V1.0'
                  AND b.locked=TRUE AND o.object_type='CONNECTOR'
                """);
        int pw01Bindings = count("""
                SELECT COUNT(*) FROM drawing_object o JOIN workbook_sheet s ON s.id=o.workbook_sheet_id
                JOIN baseline_document d ON d.id=s.baseline_document_id JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.process_code='PW-01' AND d.document_role='PROCESS' AND b.version_code='V1.0'
                  AND b.locked=TRUE AND o.object_type='CONNECTOR'
                  AND o.start_connected_shape_id IS NOT NULL AND o.end_connected_shape_id IS NOT NULL
                """);
        return new Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot(documents, equipment, flows,
                confirmed, reviewRequired, pw01Connectors, pw01Bindings, "V1.0_LOCKED");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record CachedComparison(String id, String cacheKey, String owner, String sessionId, Instant createdAt,
                                    MaterialBalanceReviewSnapshot snapshot,
                                    Step7MaterialBalanceComparisonService.ComparisonReport report) { }
    private record CachedDraft(String analysisId, String owner, String sessionId, Instant createdAt,
                              FlowMappingReviewDraft.ImportPreview preview) { }

    public record ReviewExport(byte[] bytes, int rowCount, String statusFilter, String query) {
        public ReviewExport { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record PriorityGroup(String code, String label, long count, int priority) { }
    public record MaterialBalanceReviewRow(String rowId, int sourceRow, String source, String target,
            String sourceMajor, String targetMajor, String sourceMiddle, String targetMiddle, java.math.BigDecimal quantity,
            String quantityText, String quantityState, String materialType, String unit, String period,
            boolean sourceExactMasterMatch, boolean targetExactMasterMatch, int duplicateEndpointCount,
            List<String> qualityStatuses, String connectorBindingStatus, String physicalConnectionEvidenceStatus,
            String reviewStatus, String reviewReason, int sourceLocationCandidateCount,
            int targetLocationCandidateCount, String searchText) {
        public MaterialBalanceReviewRow { qualityStatuses=List.copyOf(qualityStatuses); }
    }
    public record MaterialBalanceReviewSnapshot(String sourceFilename, String sourceSha256, long sourceBytes,
            long inputRows, int workbookSheetCount, int inputColumnCount, long bothEndpointsMasterMatched,
            long oneEndpointMasterMatched, long noEndpointMasterMatch, long positiveQuantityRows, long zeroQuantityRows,
            long blankQuantityRows, long blankMaterialTypeRows, long blankUnitRows, long blankPeriodRows,
            long blankExclusionRows, long duplicateEndpointPairGroups, long rowsWithinDuplicateEndpointPairs,
            Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot officialEvidence, String masterSha256,
            String inventoryStatus, String sourceFingerprint, String checkpointSourceFingerprint, Instant inventoryUpdatedAt,
            int inventoryTotalSheets, String candidateIndexStatus, int explicitConnectorBindingCount,
            List<PriorityGroup> priorityGroups, Map<String, Long> statusCounts, List<MaterialBalanceReviewRow> rows,
            Map<String, List<ProcessDiagramInventoryService.EquipmentOccurrence>> candidatesById,
            Map<String, Integer> candidateCountsById) {
        public MaterialBalanceReviewSnapshot {
            priorityGroups=List.copyOf(priorityGroups); statusCounts=Map.copyOf(statusCounts); rows=List.copyOf(rows);
            candidatesById=Map.copyOf(candidatesById); candidateCountsById=Map.copyOf(candidateCountsById);
        }
    }

    private static String blankValue(String value) { return value == null ? "" : value; }
    private static String csvCellLegacy(String value) {
        String safe=value==null?"":value;
        if (safe.matches("(?s)^\\\\s*[=+@].*|^\\\\s*-\\\\d.*")) safe="'"+safe;
        return "\\\""+safe.replace("\\\"", "\\\"\\\"")+"\\\"";
    }
    private record MasterBlob(String sha256, byte[] bytes) { }
}
