package com.example.processautomation.change;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Builds a read-only, grouped TEST_ONLY execution plan from persisted change analysis. */
@Service
public class SelectiveEditExecutionPlanService {
    private static final String TEST_ONLY = "TEST_ONLY";
    private static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SelectiveEditExecutionPlanService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** This endpoint only reads persisted records and returns a plan; it never writes or generates a workbook. */
    @Transactional(readOnly = true)
    public Map<String, Object> create(long changeRunId, Request request) {
        long started = System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>();

        long phase = System.nanoTime();
        Map<String, Object> run = one("""
                SELECT r.id, r.baseline_release_id, r.analysis_mode, r.source_sha256, r.source_content,
                       r.added_count, r.removed_count, r.modified_count, r.moved_count,
                       (SELECT COUNT(*) FROM change_item ci WHERE ci.change_run_id = r.id) AS total_change_items,
                       b.version_code, b.source_master_sha256, b.locked
                FROM change_run r JOIN baseline_release b ON b.id = r.baseline_release_id
                WHERE r.id = ?
                """, changeRunId, "Change run not found");
        timings.put("loadRunMs", elapsedMs(phase));
        if (!TEST_ONLY.equals(run.get("analysis_mode"))) {
            throw conflict("Only TEST_ONLY change runs can be planned.");
        }
        if (!"V1.0".equals(run.get("version_code")) || !Boolean.TRUE.equals(run.get("locked"))) {
            throw conflict("The change run is not tied to the locked V1.0 baseline.");
        }

        phase = System.nanoTime();
        byte[] sourceContent = bytes(run.get("source_content"));
        String sourceHash = text(run.get("source_sha256"));
        if (sourceContent.length == 0 || !sha256(sourceContent).equalsIgnoreCase(sourceHash)) {
            throw conflict("Stored change-run source hash mismatch; planning stopped.");
        }
        BaselineSnapshot snapshot = loadBaselineSnapshot(
                number(run.get("baseline_release_id")), text(run.get("source_master_sha256")));
        timings.put("validateSourceAndBaselineMs", elapsedMs(phase));

        phase = System.nanoTime();
        List<Map<String, Object>> items = loadItems(changeRunId, request == null ? null : request.changeItemIds());
        if (items.isEmpty()) throw conflict("No change items were selected for this execution plan.");
        List<Long> selectedItemIds = items.stream().map(row -> number(row.get("id"))).toList();
        List<Map<String, Object>> selectedPlans = loadPlans(
                changeRunId, selectedItemIds, request == null ? null : request.testOnlyPlanIds());
        timings.put("loadItemsAndPlansMs", elapsedMs(phase));

        phase = System.nanoTime();
        LinkedHashMap<String, VerifiedDocument> verifiedDocuments = new LinkedHashMap<>();
        List<ItemEvidence> itemEvidence = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long itemId = number(item.get("id"));
            List<Map<String, Object>> stored = readMapList(text(item.get("affected_diagrams_json")));
            List<CandidateEvidence> candidates = new ArrayList<>();
            for (Map<String, Object> candidate : stored) {
                CandidateEvidence evidence = verifyCandidate(number(run.get("baseline_release_id")), candidate,
                        verifiedDocuments);
                candidates.add(evidence);
            }
            itemEvidence.add(new ItemEvidence(item, candidates));
        }
        timings.put("verifyPersistedMappingAndHashesMs", elapsedMs(phase));

        phase = System.nanoTime();
        Map<Long, ValidPlan> validPlansByItem = new LinkedHashMap<>();
        List<Map<String, Object>> invalidPlans = new ArrayList<>();
        for (Map<String, Object> plan : selectedPlans) {
            long itemId = number(plan.get("change_item_id"));
            ItemEvidence item = itemEvidence.stream().filter(value -> number(value.item().get("id")) == itemId)
                    .findFirst().orElse(null);
            ValidPlan validated = validatePlan(plan, item, verifiedDocuments);
            if (validated.valid() && validPlansByItem.containsKey(itemId)) {
                validated = ValidPlan.invalid(number(plan.get("id")),
                        "Only one explicit TEST_ONLY target plan is accepted per changed item in this execution request.");
            }
            if (validated.valid()) {
                validPlansByItem.put(itemId, validated);
            } else {
                invalidPlans.add(Map.of("planId", number(plan.get("id")), "changeItemId", itemId,
                        "reason", validated.reason()));
            }
        }
        timings.put("validateSelectedTestOnlyPlansMs", elapsedMs(phase));
        phase = System.nanoTime();
        Map<String, OutputGroup> outputGroups = new LinkedHashMap<>();
        List<SelectiveExecutionPlanGrouping.PlannedTarget> plannedTargets = new ArrayList<>();
        List<Map<String, Object>> itemResults = new ArrayList<>();
        LinkedHashSet<String> candidateDocumentNames = new LinkedHashSet<>();
        LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();
        List<Map<String, Object>> reviewItems = new ArrayList<>();
        List<Map<String, Object>> uneditableItems = new ArrayList<>();
        List<Map<String, Object>> connectionReviews = new ArrayList<>();

        for (ItemEvidence evidence : itemEvidence) {
            Map<String, Object> item = evidence.item();
            long itemId = number(item.get("id"));
            String managementNo = text(item.get("permit_management_no"));
            ValidPlan validPlan = validPlansByItem.get(itemId);
            List<Map<String, Object>> candidateResults = new ArrayList<>();
            boolean hasVerifiedCandidate = false;
            for (CandidateEvidence candidate : evidence.candidates()) {
                if (candidate.verified() && candidate.filename() != null) {
                    candidateDocumentNames.add(candidate.filename());
                }
                candidateKeys.add(candidate.key());
                boolean selectedByPlan = validPlan != null && validPlan.matches(candidate);
                String classification = SelectiveExecutionPlanPolicy.classify(candidate.verified(), selectedByPlan).name();
                hasVerifiedCandidate |= candidate.verified();
                Map<String, Object> value = candidate.toMap(classification, selectedByPlan);
                candidateResults.add(value);
                if (!selectedByPlan && candidate.verified()) reviewItems.add(itemReference(item, candidate.reason()));
                if (!candidate.verified()) uneditableItems.add(itemReference(item, candidate.reason()));
            }

            String itemClass = SelectiveExecutionPlanPolicy.classify(hasVerifiedCandidate, validPlan != null).name();
            if (validPlan != null) {
                CandidateEvidence target = validPlan.target();
                plannedTargets.add(new SelectiveExecutionPlanGrouping.PlannedTarget(changeRunId, itemId, managementNo,
                        validPlan.planId(), target.documentId(), target.processCode(), target.filename(),
                        target.baselineSha256(), target.sheet()));
                connectionReviews.add(Map.of("changeItemId", itemId, "managementNo", managementNo,
                        "relationStatus", REVIEW_REQUIRED, "approved", false,
                        "predecessor", safeScenarioValue(validPlan.scenario(), "predecessorManagementNo"),
                        "successor", safeScenarioValue(validPlan.scenario(), "successorManagementNo"),
                        "reason", "Explicit TEST_ONLY endpoints are a test fixture, not an approved business relationship."));
            } else if (candidateResults.isEmpty()) {
                uneditableItems.add(itemReference(item, "No persisted diagram candidates exist for this changed item."));
            } else {
                connectionReviews.add(Map.of("changeItemId", itemId, "managementNo", managementNo,
                        "relationStatus", REVIEW_REQUIRED, "approved", false,
                        "reason", "No explicitly selected, validated TEST_ONLY plan; visual or process-code evidence is not approval."));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changeItemId", itemId);
            result.put("changeType", item.get("change_type"));
            result.put("managementNo", managementNo);
            result.put("mappingStatus", item.get("diagram_mapping_status"));
            result.put("classification", itemClass);
            result.put("testOnlyPlanId", validPlan == null ? null : validPlan.planId());
            result.put("officialEditAllowed", false);
            result.put("testOnlyArtifactEligible", validPlan != null);
            result.put("candidates", candidateResults);
            itemResults.add(result);
        }
        timings.put("classifyChangedItemsMs", elapsedMs(phase));
        phase = System.nanoTime();

        Map<String, List<SelectiveExecutionPlanGrouping.PlannedTarget>> groupedTargets =
                SelectiveExecutionPlanGrouping.byWorkbook(plannedTargets);
        groupedTargets.forEach((filename, targets) -> {
            SelectiveExecutionPlanGrouping.PlannedTarget first = targets.get(0);
            OutputGroup output = new OutputGroup(changeRunId, first.baselineDocumentId(), first.processCode(),
                    filename, first.baselineSha256());
            for (SelectiveExecutionPlanGrouping.PlannedTarget target : targets) {
                ValidPlan plan = validPlansByItem.get(target.changeItemId());
                if (plan == null) continue;
                output.add(target.changeItemId(), target.managementNo(), plan);
            }
            outputGroups.put(filename, output);
        });
        List<Map<String, Object>> candidateFileGroups = buildCandidateFileGroups(
                itemEvidence, verifiedDocuments, outputGroups, validPlansByItem);
        List<Map<String, Object>> outputTargets = outputGroups.values().stream()
                .map(OutputGroup::toMap).toList();
        List<String> unaffectedFiles = snapshot.processFilenames().stream()
                .filter(filename -> !candidateDocumentNames.contains(filename)).sorted().toList();
        List<Map<String, Object>> expectedOutputs = outputGroups.values().stream()
                .map(OutputGroup::expectedOutput).toList();
        timings.put("groupOfficialFilesAndSheetsMs", elapsedMs(phase));
        phase = System.nanoTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeRunId", changeRunId);
        result.put("analysisMode", TEST_ONLY);
        result.put("baselineVersion", "V1.0");
        result.put("baselineReleaseId", snapshot.releaseId());
        result.put("baselineFingerprint", snapshot.fingerprint());
        result.put("baselineFingerprintMethod", "locked V1.0 master SHA-256 + sorted official process-document SHA-256 metadata");
        result.put("changeItemCount", items.size());
        result.put("totalChangeItemsInRun", number(run.get("total_change_items")));
        result.put("affectedDiagramCandidateCount", candidateKeys.size());
        result.put("affectedFileCandidateCount", candidateDocumentNames.size());
        result.put("outputTargetFileCount", outputTargets.size());
        result.put("officialProcessWorkbookCount", snapshot.processFilenames().size());
        result.put("officialProcessSheetCount", snapshot.sheetCount());
        result.put("itemPlans", itemResults);
        result.put("candidateFileGroups", candidateFileGroups);
        result.put("outputTargets", outputTargets);
        result.put("reviewRequiredItems", reviewItems);
        result.put("automaticEditUnavailableItems", uneditableItems);
        result.put("invalidOrStalePlanReferences", invalidPlans);
        result.put("additionalRelationshipReview", connectionReviews);
        result.put("expectedOutputFiles", expectedOutputs);
        result.put("unaffectedFilesExcluded", unaffectedFiles);
        result.put("executionPlanReady", !outputTargets.isEmpty());
        result.put("fileGenerationStarted", false);
        result.put("zipGenerationStarted", false);
        result.put("officialEditAllowed", false);
        result.put("relationStatus", REVIEW_REQUIRED);
        result.put("reusedPersistedChangeAnalysis", true);
        result.put("reusedPersistedWorkbookSheetInventory", true);
        result.put("drawingMlReanalysisPerformed", false);
        timings.put("buildExecutionPlanResponseMs", elapsedMs(phase));
        timings.put("apiServiceTotalMs", elapsedMs(started));
        result.put("timingMs", timings);
        result.put("totalElapsedMs", elapsedMs(started));
        return result;
    }

    private List<Map<String, Object>> loadItems(long runId, List<Long> requestedIds) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, change_type, permit_management_no, diagram_mapping_status, automatic_mapping_succeeded,
                       affected_diagrams_json, review_required_json
                FROM change_item WHERE change_run_id = ? ORDER BY id
                """, runId);
        if (requestedIds == null || requestedIds.isEmpty()) return rows;
        Set<Long> unique = new LinkedHashSet<>(requestedIds);
        if (unique.size() != requestedIds.size() || unique.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "changeItemIds must be positive and unique.");
        }
        List<Map<String, Object>> selected = rows.stream()
                .filter(row -> unique.contains(number(row.get("id")))).toList();
        if (selected.size() != unique.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selected changeItemId does not belong to this changeRunId.");
        }
        return selected;
    }

    private List<Map<String, Object>> loadPlans(long runId, List<Long> itemIds, List<Long> requestedPlanIds) {
        if (requestedPlanIds == null || requestedPlanIds.isEmpty()) return List.of();
        Set<Long> unique = new LinkedHashSet<>(requestedPlanIds);
        if (unique.size() != requestedPlanIds.size() || unique.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "testOnlyPlanIds must be positive and unique.");
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Long planId : unique) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, change_run_id, change_item_id, baseline_document_id, analysis_mode, plan_status,
                           automatic_edit_allowed, relation_status, target_process_code, target_sheet_name,
                           target_filename, requested_scenario_json, output_filename, output_sha256, output_content,
                           plan_json
                    FROM diagram_edit_plan WHERE id = ? AND change_run_id = ?
                    """, planId, runId);
            if (rows.size() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selected testOnlyPlanId does not belong to this run.");
            }
            Map<String, Object> plan = rows.get(0);
            if (!itemIds.contains(number(plan.get("change_item_id")))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selected plan belongs to an unselected change item.");
            }
            plans.add(plan);
        }
        return plans;
    }

    private BaselineSnapshot loadBaselineSnapshot(long releaseId, String releaseMasterHash) {
        Map<String, Object> master = one("""
                SELECT id, sha256, content_bytes FROM baseline_document
                WHERE baseline_release_id = ? AND document_role = 'MASTER'
                """, releaseId, "Locked baseline master document is missing");
        byte[] masterBytes = bytes(master.get("content_bytes"));
        if (!releaseMasterHash.equalsIgnoreCase(text(master.get("sha256")))
                || masterBytes.length == 0 || !sha256(masterBytes).equalsIgnoreCase(releaseMasterHash)) {
            throw conflict("V1.0 release master BLOB hash does not match its immutable release hash.");
        }
        List<Map<String, Object>> docs = jdbc.queryForList("""
                SELECT d.id, d.process_code, d.original_filename, d.sha256, d.byte_size, d.sheet_count,
                       COUNT(s.id) AS indexed_sheet_count
                FROM baseline_document d LEFT JOIN workbook_sheet s ON s.baseline_document_id = d.id
                WHERE d.baseline_release_id = ? AND d.document_role = 'PROCESS'
                GROUP BY d.id, d.process_code, d.original_filename, d.sha256, d.byte_size, d.sheet_count
                ORDER BY d.process_code, d.original_filename
                """, releaseId);
        long sheetTotal = docs.stream().mapToLong(row -> number(row.get("indexed_sheet_count"))).sum();
        int sheets = Math.toIntExact(sheetTotal);
        boolean sheetInventoryMatches = docs.stream().allMatch(row ->
                number(row.get("sheet_count")) == number(row.get("indexed_sheet_count")));
        if (docs.size() != 15 || sheets != 62 || !sheetInventoryMatches) {
            throw conflict("Locked V1.0 process inventory is not the expected 15 workbooks / 62 consistent indexed sheets.");
        }
        StringBuilder fingerprintSource = new StringBuilder("V1.0|").append(releaseId).append('|')
                .append(releaseMasterHash).append('|');
        List<String> filenames = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            String filename = text(doc.get("original_filename"));
            filenames.add(filename);
            fingerprintSource.append(text(doc.get("process_code"))).append('|').append(filename).append('|')
                    .append(text(doc.get("sha256"))).append('|').append(number(doc.get("byte_size"))).append('|')
                    .append(number(doc.get("sheet_count"))).append('\n');
        }
        return new BaselineSnapshot(releaseId, releaseMasterHash, List.copyOf(filenames), sheets,
                sha256(fingerprintSource.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private CandidateEvidence verifyCandidate(long releaseId, Map<String, Object> candidate,
                                                Map<String, VerifiedDocument> verifiedDocuments) {
        String filename = nullableText(candidate.get("processWorkbook"));
        String sheet = nullableText(candidate.get("sheetName"));
        String processCode = nullableText(candidate.get("processCode"));
        String mappingStatus = nullableText(candidate.get("mappingStatus"));
        String evidence = nullableText(candidate.get("evidence"));
        if (filename == null || sheet == null) {
            return CandidateEvidence.invalid(processCode, filename, sheet, mappingStatus, evidence,
                    "Candidate lacks a specific official workbook or worksheet.");
        }
        VerifiedDocument verified = verifiedDocuments.get(filename);
        if (verified == null) {
            List<Map<String, Object>> docs = jdbc.queryForList("""
                    SELECT id, process_code, original_filename, sha256, byte_size, content_bytes
                    FROM baseline_document
                    WHERE baseline_release_id = ? AND document_role = 'PROCESS' AND original_filename = ?
                    """, releaseId, filename);
            if (docs.size() != 1) {
                verified = VerifiedDocument.invalid("Candidate workbook is absent or ambiguous in locked V1.0.");
            } else {
                Map<String, Object> doc = docs.get(0);
                byte[] content = bytes(doc.get("content_bytes"));
                String expectedHash = text(doc.get("sha256"));
                List<String> sheets = jdbc.query("SELECT sheet_name FROM workbook_sheet WHERE baseline_document_id = ? ORDER BY sheet_index",
                        (rs, row) -> rs.getString(1), number(doc.get("id")));
                boolean hashValid = content.length > 0 && sha256(content).equalsIgnoreCase(expectedHash);
                hashValid &= content.length == number(doc.get("byte_size"));
                verified = new VerifiedDocument(number(doc.get("id")), text(doc.get("process_code")), filename,
                        expectedHash, List.copyOf(sheets), hashValid,
                        hashValid ? "" : "V1.0 workbook BLOB hash does not match its immutable metadata.");
            }
            verifiedDocuments.put(filename, verified);
        }
        if (!verified.valid()) {
            return CandidateEvidence.invalid(processCode, filename, sheet, mappingStatus, evidence, verified.reason());
        }
        if (!verified.sheets().contains(sheet)) {
            return CandidateEvidence.invalid(processCode, filename, sheet, mappingStatus, evidence,
                    "Candidate sheet is not present in the persisted V1.0 workbook-sheet inventory.");
        }
        if (!processCompatible(processCode, verified.processCode())) {
            return CandidateEvidence.invalid(processCode, filename, sheet, mappingStatus, evidence,
                    "Candidate process code does not belong to the registered V1.0 workbook.");
        }
        return new CandidateEvidence(number(verified.documentId()), processCode, filename, sheet, mappingStatus,
                evidence, verified.sha256(), true,
                "Persisted analysis candidate verified against locked V1.0 workbook SHA-256 and sheet inventory.");
    }

    private ValidPlan validatePlan(Map<String, Object> row, ItemEvidence item,
                                   Map<String, VerifiedDocument> documents) {
        long planId = number(row.get("id"));
        if (item == null) return ValidPlan.invalid(planId, "The plan's change item was not selected.");
        if (!SelectiveExecutionPlanPolicy.testOnlyPlanEligible(text(item.item().get("change_type")),
                text(row.get("analysis_mode")), text(row.get("plan_status")), truthy(row.get("automatic_edit_allowed")),
                text(row.get("relation_status")), readMap(text(row.get("requested_scenario_json"))))) {
            return ValidPlan.invalid(planId, "Plan is not a TEST_ONLY_READY artifact-only plan with REVIEW_REQUIRED relation status.");
        }
        Map<String, Object> scenario = readMap(text(row.get("requested_scenario_json")));
        if (!Boolean.TRUE.equals(scenario.get("enabled"))) {
            return ValidPlan.invalid(planId, "The plan does not contain an explicit enabled TEST_ONLY scenario.");
        }
        if (!text(row.get("target_process_code")).equalsIgnoreCase(text(scenario.get("processCode")))
                || !text(row.get("target_filename")).equals(text(scenario.get("workbookFilename")))
                || !text(row.get("target_sheet_name")).equals(text(scenario.get("sheetName")))
                || text(scenario.get("predecessorManagementNo")).isBlank()
                || text(scenario.get("successorManagementNo")).isBlank()) {
            return ValidPlan.invalid(planId, "The stored TEST_ONLY scenario does not match the plan target or lacks explicit endpoints.");
        }
        long documentId = number(row.get("baseline_document_id"));
        String filename = text(row.get("target_filename"));
        String sheet = text(row.get("target_sheet_name"));
        String processCode = text(row.get("target_process_code"));
        CandidateEvidence candidate = item.candidates().stream().filter(value -> value.verified()
                        && value.documentId() == documentId && filename.equals(value.filename())
                        && sheet.equals(value.sheet()) && processCompatible(processCode, value.processCode()))
                .findFirst().orElse(null);
        if (candidate == null) return ValidPlan.invalid(planId, "The selected plan target does not match a verified persisted run-173 diagram candidate.");
        VerifiedDocument document = documents.get(filename);
        if (document == null || !document.valid() || document.documentId() != documentId) {
            return ValidPlan.invalid(planId, "The plan target document is not verified against the current locked V1.0 snapshot.");
        }
        String outputSha = nullableText(row.get("output_sha256"));
        byte[] output = bytes(row.get("output_content"));
        if (output.length > 0 && (outputSha == null || !sha256(output).equalsIgnoreCase(outputSha))) {
            return ValidPlan.invalid(planId, "Stored prior TEST_ONLY output hash mismatch.");
        }
        Map<String, Object> planJson = readMap(text(row.get("plan_json")));
        Map<String, Object> storedTarget = asMap(planJson.get("target"));
        Map<String, Object> insertion = asMap(planJson.get("insertion"));
        if (!"ADDED".equals(text(planJson.get("changeType")))
                || !TEST_ONLY.equals(text(planJson.get("analysisMode")))
                || !REVIEW_REQUIRED.equals(text(planJson.get("relationStatus")))
                || !Boolean.FALSE.equals(planJson.get("officialApprovalCreated"))
                || number(storedTarget.get("baselineDocumentId")) != documentId
                || !filename.equals(text(storedTarget.get("workbookFilename")))
                || !sheet.equals(text(storedTarget.get("sheetName")))
                || !Boolean.FALSE.equals(insertion.get("relationConfirmed"))) {
            return ValidPlan.invalid(planId, "Stored plan JSON is inconsistent with its TEST_ONLY/REVIEW_REQUIRED target boundary.");
        }
        return new ValidPlan(planId, true, "", candidate, scenario,
                nullableText(row.get("output_filename")), outputSha, output.length > 0,
                nullableText(planJson.get("changeType")));
    }

    private List<Map<String, Object>> buildCandidateFileGroups(List<ItemEvidence> items,
                                                                 Map<String, VerifiedDocument> verifiedDocuments,
                                                                 Map<String, OutputGroup> outputGroups,
                                                                 Map<Long, ValidPlan> validPlansByItem) {
        Map<String, CandidateFileGroup> grouped = new LinkedHashMap<>();
        for (ItemEvidence item : items) {
            long itemId = number(item.item().get("id"));
            String permit = text(item.item().get("permit_management_no"));
            for (CandidateEvidence candidate : item.candidates()) {
                if (candidate.filename() == null) continue;
                CandidateFileGroup group = grouped.computeIfAbsent(candidate.filename(), ignored ->
                        new CandidateFileGroup(candidate.documentId(), candidate.processCode(), candidate.filename(),
                                verifiedDocuments.get(candidate.filename()) == null ? null
                                        : verifiedDocuments.get(candidate.filename()).sha256()));
                ValidPlan selectedPlan = validPlansByItem.get(itemId);
                boolean selectedForOutput = selectedPlan != null && selectedPlan.matches(candidate);
                group.add(itemId, permit, candidate, selectedForOutput);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CandidateFileGroup group : grouped.values()) {
            OutputGroup output = outputGroups.get(group.filename);
            result.add(group.toMap(output == null ? List.of() : output.sheets()));
        }
        return result;
    }

    private boolean processCompatible(String target, String candidate) {
        if (target == null || candidate == null) return false;
        return target.equalsIgnoreCase(candidate)
                || target.toUpperCase(java.util.Locale.ROOT)
                .startsWith(candidate.toUpperCase(java.util.Locale.ROOT) + "-");
    }

    private Map<String, Object> itemReference(Map<String, Object> item, String reason) {
        return Map.of("changeItemId", number(item.get("id")),
                "managementNo", text(item.get("permit_management_no")),
                "changeType", text(item.get("change_type")), "reason", reason);
    }

    private Map<String, Object> one(String sql, Object arg, String notFound) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arg);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound);
        return rows.get(0);
    }

    private List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, MAP_LIST); }
        catch (Exception exception) { throw conflict("Persisted diagram mapping JSON is invalid."); }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, MAP); }
        catch (Exception exception) { throw conflict("Persisted edit plan JSON is invalid."); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private long elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private byte[] bytes(Object value) { return value instanceof byte[] data ? data : new byte[0]; }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private String text(Object value) { return value == null ? "" : value.toString(); }
    private String nullableText(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private String safeScenarioValue(Map<String, Object> scenario, String key) {
        Object value = scenario.get(key);
        return value == null ? "" : value.toString();
    }
    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    public record Request(List<Long> changeItemIds, List<Long> testOnlyPlanIds) { }

    private record BaselineSnapshot(long releaseId, String masterHash, List<String> processFilenames,
                                    int sheetCount, String fingerprint) { }
    private record ItemEvidence(Map<String, Object> item, List<CandidateEvidence> candidates) { }
    private record VerifiedDocument(long documentId, String processCode, String filename, String sha256,
                                    List<String> sheets, boolean valid, String reason) {
        static VerifiedDocument invalid(String reason) {
            return new VerifiedDocument(0, "", "", "", List.of(), false, reason);
        }
    }
    private record CandidateEvidence(long documentId, String processCode, String filename, String sheet,
                                     String mappingStatus, String evidence, String baselineSha256,
                                     boolean verified, String reason) {
        static CandidateEvidence invalid(String processCode, String filename, String sheet,
                                         String mappingStatus, String evidence, String reason) {
            return new CandidateEvidence(0, processCode, filename, sheet, mappingStatus, evidence, null, false, reason);
        }
        String key() { return (filename == null ? "" : filename) + "\u0000" + (sheet == null ? "" : sheet); }
        Map<String, Object> toMap(String classification, boolean selected) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("processCode", processCode);
            result.put("workbookFilename", filename);
            result.put("sheetName", sheet);
            result.put("mappingStatus", mappingStatus);
            result.put("evidence", evidence);
            result.put("baselineSha256", baselineSha256);
            result.put("classification", classification);
            result.put("candidateIntegrityVerified", verified);
            result.put("selectedByTestOnlyPlan", selected);
            result.put("reviewReason", reason);
            return result;
        }
    }
    private record ValidPlan(long planId, boolean valid, String reason, CandidateEvidence target,
                             Map<String, Object> scenario, String outputFilename, String outputSha,
                             boolean existingOutput, String changeType) {
        static ValidPlan invalid(long id, String reason) {
            return new ValidPlan(id, false, reason, null, Map.of(), null, null, false, null);
        }
        boolean matches(CandidateEvidence candidate) {
            return valid && target.key().equals(candidate.key()) && target.documentId() == candidate.documentId();
        }
    }

    private static final class CandidateFileGroup {
        private final Long documentId;
        private final String processCode;
        private final String filename;
        private final String sha256;
        private final Set<String> sheets = new LinkedHashSet<>();
        private final Set<String> outputSheets = new LinkedHashSet<>();
        private final Set<Long> itemIds = new LinkedHashSet<>();
        private final Set<String> equipment = new LinkedHashSet<>();
        private final List<Map<String, Object>> candidateDetails = new ArrayList<>();
        CandidateFileGroup(long documentId, String processCode, String filename, String sha256) {
            this.documentId = documentId; this.processCode = processCode; this.filename = filename; this.sha256 = sha256;
        }
        void add(long itemId, String permit, CandidateEvidence candidate, boolean selectedForOutput) {
            itemIds.add(itemId); equipment.add(permit);
            if (candidate.sheet() != null) sheets.add(candidate.sheet());
            if (selectedForOutput && candidate.sheet() != null) outputSheets.add(candidate.sheet());
            candidateDetails.add(candidate.toMap(
                    SelectiveExecutionPlanPolicy.classify(candidate.verified(), selectedForOutput).name(), selectedForOutput));
        }
        Map<String, Object> toMap(List<String> outputSheets) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baselineDocumentId", documentId);
            result.put("processCode", processCode);
            result.put("workbookFilename", filename);
            result.put("baselineSha256", sha256);
            result.put("changeItemIds", List.copyOf(itemIds));
            result.put("changedEquipment", List.copyOf(equipment));
            result.put("candidateSheets", List.copyOf(sheets));
            result.put("candidateDetails", List.copyOf(candidateDetails));
            result.put("outputSheets", outputSheets);
            result.put("includedInOutput", !outputSheets.isEmpty());
            return result;
        }
    }

    private static final class OutputGroup {
        private final long runId;
        private final long documentId;
        private final String processCode;
        private final String filename;
        private final String sourceHash;
        private final Set<Long> itemIds = new LinkedHashSet<>();
        private final Set<Long> planIds = new LinkedHashSet<>();
        private final Set<String> sheets = new LinkedHashSet<>();
        private final Set<String> equipment = new LinkedHashSet<>();
        private final Set<String> priorFilenames = new LinkedHashSet<>();
        private final Set<String> priorHashes = new LinkedHashSet<>();
        private final List<Map<String, Object>> review = new ArrayList<>();
        OutputGroup(long runId, long documentId, String processCode, String filename, String sourceHash) {
            this.runId = runId; this.documentId = documentId; this.processCode = processCode;
            this.filename = filename; this.sourceHash = sourceHash;
        }
        void add(long itemId, String permit, ValidPlan plan) {
            if (documentId != plan.target().documentId()
                    || !sourceHash.equalsIgnoreCase(plan.target().baselineSha256())) {
                throw new IllegalStateException("Conflicting V1.0 document identity inside one output file group.");
            }
            itemIds.add(itemId); planIds.add(plan.planId()); equipment.add(permit);
            sheets.add(plan.target().sheet());
            if (plan.outputFilename() != null) priorFilenames.add(plan.outputFilename());
            if (plan.outputSha() != null) priorHashes.add(plan.outputSha());
            review.add(Map.of("planId", plan.planId(), "relationStatus", REVIEW_REQUIRED,
                    "existingOutputAvailable", plan.existingOutput(), "officialEditAllowed", false));
        }
        List<String> sheets() { return List.copyOf(sheets); }
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baselineDocumentId", documentId);
            result.put("processCode", processCode);
            result.put("workbookFilename", filename);
            result.put("baselineSha256", sourceHash);
            result.put("changeItemIds", List.copyOf(itemIds));
            result.put("existingEditPlanIds", List.copyOf(planIds));
            result.put("changedEquipment", List.copyOf(equipment));
            result.put("outputSheets", List.copyOf(sheets));
            result.put("expectedOutputFilename", "TEST_ONLY-" + safe(processCode) + "-CHANGE-RUN-" + runId + ".xlsx");
            result.put("priorPlanOutputFilenames", List.copyOf(priorFilenames));
            result.put("priorPlanOutputSha256", List.copyOf(priorHashes));
            result.put("priorPlanOutputVerified", !priorHashes.isEmpty());
            result.put("generatedByThisRequest", false);
            result.put("officialEditAllowed", false);
            result.put("relationStatus", REVIEW_REQUIRED);
            result.put("planReview", List.copyOf(review));
            return result;
        }
        Map<String, Object> expectedOutput() {
            return Map.of("workbookFilename", filename, "processCode", processCode,
                    "expectedOutputFilename", "TEST_ONLY-" + safe(processCode) + "-CHANGE-RUN-"
                            + runId + ".xlsx",
                    "sheets", List.copyOf(sheets), "changeItemIds", List.copyOf(itemIds),
                    "generatedByThisRequest", false);
        }
        private static String safe(String value) { return value == null ? "UNKNOWN" : value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    }
}
