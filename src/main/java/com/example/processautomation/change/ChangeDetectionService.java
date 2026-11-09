package com.example.processautomation.change;

import com.example.processautomation.baseline.BaselineConflictException;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.MasterWorkbookParser;
import com.example.processautomation.model.MasterEquipment;
import com.example.processautomation.model.ParsedMaster;
import com.example.processautomation.model.ParsedMaster.ParseIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ChangeDetectionService {
    private static final String BASELINE_VERSION = "V1.0";
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Set<String> DUPLICATE_ID_CODES = Set.of("DUPLICATE_PERMIT_ID", "MISSING_PERMIT_ID");

    private final JdbcTemplate jdbcTemplate;
    private final MasterWorkbookParser parser;
    private final ObjectMapper objectMapper;
    private final DiagramImpactAnalysisService diagramImpactAnalysisService;

    public ChangeDetectionService(JdbcTemplate jdbcTemplate, MasterWorkbookParser parser, ObjectMapper objectMapper,
                                 DiagramImpactAnalysisService diagramImpactAnalysisService) {
        this.jdbcTemplate = jdbcTemplate;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.diagramImpactAnalysisService = diagramImpactAnalysisService;
    }

    @Transactional
    public ChangeDetectionResult compare(byte[] newMasterBytes, String filename) {
        if (newMasterBytes == null || newMasterBytes.length == 0 || newMasterBytes.length > MAX_UPLOAD_BYTES) {
            throw new InvalidWorkbookException("The updated master file is empty or exceeds 10 MB");
        }
        Long baselineId = jdbcTemplate.query("SELECT id FROM baseline_release WHERE version_code = ? AND locked = TRUE",
                rs -> rs.next() ? rs.getLong(1) : null, BASELINE_VERSION);
        if (baselineId == null) {
            throw new BaselineConflictException("The immutable V1.0 baseline must be registered before comparing uploads");
        }

        ParsedMaster parsed = parser.parse(newMasterBytes);
        for (ParseIssue issue : parsed.issues()) {
            if (DUPLICATE_ID_CODES.contains(issue.code())) {
                throw new InvalidWorkbookException("Unsafe master upload; comparison was not run: " + issue.code()
                        + " at source row " + issue.sourceRowNumber() + " (" + issue.detail() + ")");
            }
        }
        Map<String, EquipmentSnapshot> baseline = loadBaseline(baselineId);
        Map<String, MasterEquipment> uploaded = new LinkedHashMap<>();
        for (MasterEquipment record : parsed.equipment()) uploaded.put(record.permitManagementNo(), record);

        List<ChangeDetectionResult.ChangeIssue> issues = new ArrayList<>();
        for (ParseIssue issue : parsed.issues()) {
            issues.add(new ChangeDetectionResult.ChangeIssue(issue.sourceRowNumber(), issue.severity(),
                    issue.code(), issue.fieldName(), issue.detail()));
        }
        List<ChangeDraft> drafts = new ArrayList<>();
        Set<String> movedIds = detectMovedIds(baseline, parsed.equipment());
        Set<String> allIds = new HashSet<>(baseline.keySet());
        allIds.addAll(uploaded.keySet());
        List<String> orderedIds = allIds.stream().sorted().toList();
        int addedCount = 0;
        int removedCount = 0;
        int modifiedCount = 0;
        int movedCount = 0;
        for (String permitId : orderedIds) {
            EquipmentSnapshot before = baseline.get(permitId);
            MasterEquipment after = uploaded.get(permitId);
            if (before == null) {
                addedCount++;
                drafts.add(makeDraft("ADDED", baselineId, null, after, Map.of(), List.of()));
            } else if (after == null) {
                removedCount++;
                drafts.add(makeDraft("REMOVED", baselineId, before, null, Map.of(), List.of()));
            } else {
                List<String> blankFlowReviews = new ArrayList<>();
                Map<String, ChangeDetectionResult.FieldChange> fields = fieldChanges(before, after, issues, blankFlowReviews);
                boolean orderMoved = movedIds.contains(permitId);
                boolean attributesChanged = !fields.isEmpty();
                if (orderMoved) {
                    movedCount++;
                    fields.put("마스터시트 행 순서", new ChangeDetectionResult.FieldChange(
                            Integer.toString(before.masterOrder()), Integer.toString(after.masterOrder())));
                }
                if (attributesChanged) modifiedCount++;
                if (attributesChanged || orderMoved || !blankFlowReviews.isEmpty()) {
                    drafts.add(makeDraft(attributesChanged ? "MODIFIED" : orderMoved ? "MOVED" : "REVIEW_REQUIRED",
                            baselineId, before, after, fields, blankFlowReviews));
                }
            }
        }

        List<FlowCandidate> flowCandidates = collectFlowCandidates(parsed.equipment(), baselineId, issues);
        List<ChangeDetectionResult.DiagramImpact> affectedDiagrams = drafts.stream()
                .flatMap(draft -> draft.affectedDiagrams().stream()).distinct().toList();
        List<String> reviewItems = new ArrayList<>();
        for (ChangeDraft draft : drafts) reviewItems.addAll(draft.reviewRequiredItems());
        for (ChangeDetectionResult.ChangeIssue issue : issues) {
            if (!"INFO".equalsIgnoreCase(issue.severity())) {
                reviewItems.add(issue.code() + " (row " + issue.sourceRowNumber() + "): " + issue.detail());
            }
        }
        List<String> reviewRequiredItems = List.copyOf(new java.util.LinkedHashSet<>(reviewItems));
        boolean automaticMappingSucceeded = !drafts.isEmpty()
                && drafts.stream().allMatch(ChangeDraft::automaticMappingSucceeded);
        String diagramMappingStatus = drafts.isEmpty() ? "NO_CHANGED_EQUIPMENT"
                : automaticMappingSucceeded ? "EXISTING_BASELINE_MAPPING_REVIEW_REQUIRED" : "REVIEW_REQUIRED";
        String hash = sha256(newMasterBytes);
        long changeRunId = insertChangeRun(baselineId, filename, hash, newMasterBytes,
                parsed.equipment().size(), addedCount, removedCount, modifiedCount, movedCount, issues.size(),
                diagramMappingStatus, automaticMappingSucceeded, affectedDiagrams, reviewRequiredItems);
        for (ChangeDraft draft : drafts) insertChangeItem(changeRunId, draft);
        for (ChangeDetectionResult.ChangeIssue issue : issues) {
            insertChangeIssue(changeRunId, issue, permitIdForIssue(parsed.equipment(), issue.sourceRowNumber()));
        }
        persistFlowCandidates(changeRunId, flowCandidates);
        Map<String, Long> changeItemIds = new HashMap<>();
        jdbcTemplate.query("SELECT id, permit_management_no FROM change_item WHERE change_run_id = ?", rs -> {
            changeItemIds.put(rs.getString("permit_management_no"), rs.getLong("id"));
        }, changeRunId);

        return new ChangeDetectionResult(changeRunId, BASELINE_VERSION, "TEST_ONLY", safeFilename(filename), hash,
                baseline.size(), parsed.equipment().size(), addedCount, removedCount, modifiedCount,
                movedCount, issues.size(), false, diagramMappingStatus, automaticMappingSucceeded,
                affectedDiagrams, reviewRequiredItems, drafts.stream()
                        .map(draft -> draft.toResponse(changeItemIds.get(draft.permitId()))).toList(), issues);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(long id) {
        List<Map<String, Object>> result = jdbcTemplate.queryForList("""
                SELECT id AS changeRunId, baseline_release_id AS baselineId, source_filename AS filename,
                       source_sha256 AS sha256, analysis_mode AS analysisMode, total_records AS totalRecords,
                       added_count AS addedCount, removed_count AS removedCount, modified_count AS modifiedCount,
                       moved_count AS movedCount, validation_issue_count AS validationIssueCount,
                       diagram_mapping_status AS diagramMappingStatus,
                       automatic_mapping_succeeded AS automaticMappingSucceeded,
                       affected_diagrams_json AS affectedDiagrams, review_required_json AS reviewRequiredItems,
                       created_at AS createdAt
                FROM change_run WHERE id = ?
                """, id);
        if (result.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Change run not found");
        return result.get(0);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRuns(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id AS changeRunId, source_filename AS filename, source_sha256 AS sha256,
                       analysis_mode AS analysisMode, total_records AS totalRecords, added_count AS addedCount,
                       removed_count AS removedCount, modified_count AS modifiedCount, moved_count AS movedCount,
                       validation_issue_count AS validationIssueCount, diagram_mapping_status AS diagramMappingStatus,
                       automatic_mapping_succeeded AS automaticMappingSucceeded, created_at AS createdAt
                FROM change_run ORDER BY id DESC LIMIT ?
                """, Math.max(1, Math.min(limit, 100)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChangeItems(long runId) {
        return jdbcTemplate.queryForList("""
                SELECT id AS changeItemId, change_type AS changeType, permit_management_no AS permitManagementNo,
                       old_source_row AS oldSourceRow, new_source_row AS newSourceRow,
                       changed_fields_json AS changedFields, before_values_json AS beforeValues,
                       after_values_json AS afterValues, diagram_mapping_status AS diagramMappingStatus,
                       automatic_mapping_succeeded AS automaticMappingSucceeded,
                       affected_diagrams_json AS affectedDiagrams, review_required_json AS reviewRequiredItems
                FROM change_item WHERE change_run_id = ?
                ORDER BY FIELD(change_type, 'ADDED', 'REMOVED', 'MODIFIED', 'MOVED'), permit_management_no
                """, runId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChangeIssues(long runId) {
        return jdbcTemplate.queryForList("""
                SELECT permit_management_no AS permitManagementNo, source_row_number AS sourceRowNumber,
                       severity, issue_code AS code, field_name AS fieldName, detail_text AS detail
                FROM change_validation_issue WHERE change_run_id = ?
                ORDER BY severity, source_row_number
                """, runId);
    }

    private Map<String, EquipmentSnapshot> loadBaseline(long baselineId) {
        Map<String, EquipmentSnapshot> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT permit_management_no, business_item_no, major_process_code, middle_process_code,
                       process_category, facility_name, permit_name, permit_category, capacity_text,
                       capacity_unit, quantity_text, operating_hours_text, annual_operating_days_text,
                       purpose_text, pid_number, closed_status, paper_exists_status, raw_values_json,
                       source_row_number, master_order
                FROM equipment_record WHERE baseline_release_id = ? ORDER BY master_order
                """, rs -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> raw = objectMapper.readValue(rs.getString("raw_values_json"), Map.class);
                String permitId = rs.getString("permit_management_no");
                result.put(permitId, new EquipmentSnapshot(permitId, rs.getString("business_item_no"),
                        rs.getString("major_process_code"), rs.getString("middle_process_code"),
                        rs.getString("process_category"), rs.getString("facility_name"), rs.getString("permit_name"),
                        rs.getString("permit_category"), rs.getString("capacity_text"), rs.getString("capacity_unit"),
                        rs.getString("quantity_text"), rs.getString("operating_hours_text"),
                        rs.getString("annual_operating_days_text"), rs.getString("purpose_text"),
                        rs.getString("pid_number"), rs.getString("closed_status"), rs.getString("paper_exists_status"),
                        raw, rs.getInt("source_row_number"), rs.getInt("master_order")));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to read immutable baseline equipment row", exception);
            }
        }, baselineId);
        return result;
    }

    private Set<String> detectMovedIds(Map<String, EquipmentSnapshot> baseline, List<MasterEquipment> uploaded) {
        Set<String> retainedIds = uploaded.stream()
                .map(MasterEquipment::permitManagementNo)
                .filter(baseline::containsKey)
                .collect(java.util.stream.Collectors.toSet());
        List<String> baselineOrder = baseline.values().stream()
                .filter(item -> retainedIds.contains(item.permitManagementNo()))
                .sorted(Comparator.comparingInt(EquipmentSnapshot::masterOrder))
                .map(EquipmentSnapshot::permitManagementNo)
                .toList();
        List<String> uploadedOrder = uploaded.stream()
                .filter(item -> retainedIds.contains(item.permitManagementNo()))
                .map(MasterEquipment::permitManagementNo)
                .toList();
        Set<String> moved = new HashSet<>();
        for (int index = 0; index < Math.min(baselineOrder.size(), uploadedOrder.size()); index++) {
            if (!baselineOrder.get(index).equals(uploadedOrder.get(index))) {
                moved.add(baselineOrder.get(index));
                moved.add(uploadedOrder.get(index));
            }
        }
        return moved;
    }

    private Map<String, ChangeDetectionResult.FieldChange> fieldChanges(EquipmentSnapshot before, MasterEquipment after,
                                                                         List<ChangeDetectionResult.ChangeIssue> issues,
                                                                         List<String> blankFlowReviews) {
        MasterFieldChangePolicy.Result comparison = MasterFieldChangePolicy.compare(before.rawValues(), after.rawValues());
        comparison.preservedBlankFlowInputs().forEach((field, detail) -> {
            blankFlowReviews.add(field + ": " + detail);
            issues.add(new ChangeDetectionResult.ChangeIssue(after.sourceRowNumber(), "REVIEW",
                    "BLANK_FLOW_INPUT_NOT_TREATED_AS_DELETION", field, detail));
        });
        return new LinkedHashMap<>(comparison.changedFields());
    }

    private ChangeDraft makeDraft(String type, long baselineId, EquipmentSnapshot before, MasterEquipment after,
                                  Map<String, ChangeDetectionResult.FieldChange> changes,
                                  List<String> fieldReviewItems) {
        String permitId = before != null ? before.permitManagementNo() : after.permitManagementNo();
        String itemNo = after != null ? after.businessItemNo() : before.businessItemNo();
        String name = after != null ? after.facilityName() : before.facilityName();
        List<String> codes = new ArrayList<>();
        addCode(codes, before == null ? null : before.majorProcessCode());
        addCode(codes, before == null ? null : before.middleProcessCode());
        addCode(codes, after == null ? null : after.majorProcessCode());
        addCode(codes, after == null ? null : after.middleProcessCode());
        DiagramImpactAnalysisService.Analysis impact = diagramImpactAnalysisService.analyze(
                baselineId, permitId, itemNo, codes);
        List<String> reviewItems = new ArrayList<>(fieldReviewItems);
        reviewItems.addAll(impact.reviewRequiredItems());
        boolean processCodeChanged = changes.keySet().stream().map(ChangeDetectionService::normalizeHeader)
                .anyMatch(Set.of("대분류번호", "대분류코드", "공정번호", "중분류번호", "중분류코드", "공정세부번호")::contains);
        String mappingStatus = impact.mappingStatus();
        boolean automaticMapping = impact.automaticMappingSucceeded();
        List<ChangeDetectionResult.DiagramImpact> diagrams = impact.affectedDiagrams();
        if (processCodeChanged) {
            mappingStatus = "PROCESS_CODE_CHANGED_REVIEW_REQUIRED";
            automaticMapping = false;
            reviewItems.add("Process code changed; the V1.0 mapping is historical evidence only. Confirm the new process workbook/sheet manually.");
            diagrams = diagrams.stream().map(item -> new ChangeDetectionResult.DiagramImpact(item.processCode(),
                    item.processWorkbook(), item.sheetName(), "PROCESS_CODE_CHANGED_REVIEW_REQUIRED",
                    item.matchMethod(), item.evidence(), false)).toList();
        }
        List<String> candidates = diagrams.stream().map(item ->
                (item.processCode() == null ? "" : item.processCode() + " → ")
                        + (item.processWorkbook() == null ? "[도면 미확인]" : item.processWorkbook())
                        + (item.sheetName() == null ? "" : " / " + item.sheetName())).distinct().toList();
        return new ChangeDraft(type, permitId, itemNo, name,
                before == null ? null : before.sourceRowNumber(), after == null ? null : after.sourceRowNumber(),
                Map.copyOf(changes), codes, candidates, mappingStatus, automaticMapping, diagrams,
                List.copyOf(new java.util.LinkedHashSet<>(reviewItems)), before, after);
    }

    private EquipmentSnapshot snapshot(MasterEquipment item) {
        return new EquipmentSnapshot(item.permitManagementNo(), item.businessItemNo(), item.majorProcessCode(),
                item.middleProcessCode(), item.processCategory(), item.facilityName(), item.permitName(),
                item.permitCategory(), item.capacityText(), item.capacityUnit(), item.quantityText(),
                item.operatingHoursText(), item.annualOperatingDaysText(), item.purposeText(), item.pidNumber(),
                item.closedStatus(), item.paperExistsStatus(), item.rawValues(), item.sourceRowNumber(), item.masterOrder());
    }

    private List<FlowCandidate> collectFlowCandidates(List<MasterEquipment> equipment, long baselineId,
                                                        List<ChangeDetectionResult.ChangeIssue> issues) {
        Set<String> knownPermitIds = new HashSet<>();
        Set<String> knownItemNos = new HashSet<>();
        Set<String> knownProcesses = new HashSet<>();
        Map<String, Integer> itemNoCounts = new HashMap<>();
        for (MasterEquipment item : equipment) {
            addNormalized(knownPermitIds, item.permitManagementNo());
            countNormalized(itemNoCounts, item.businessItemNo());
            addNormalized(knownProcesses, item.majorProcessCode());
            addNormalized(knownProcesses, normalizeProcessCode(item.middleProcessCode()));
        }
        itemNoCounts.forEach((itemNo, count) -> {
            if (count == 1) knownItemNos.add(itemNo);
        });
        List<FlowCandidate> candidates = new ArrayList<>();
        addFlowCandidate(candidates, equipment, "WASTEWATER", "폐수 종류", "폐수량 (톤/일)", "폐수 이동처",
                knownPermitIds, knownItemNos, knownProcesses, issues);
        addFlowCandidate(candidates, equipment, "WASTE", "폐기물종류", "폐기물량 (톤/일)", "폐기물 이동처",
                knownPermitIds, knownItemNos, knownProcesses, issues);
        return candidates;
    }

    private void addFlowCandidate(List<FlowCandidate> candidates, List<MasterEquipment> equipment,
                                  String flowType, String materialColumn, String quantityColumn, String destinationColumn,
                                  Set<String> knownPermitIds, Set<String> knownItemNos, Set<String> knownProcesses,
                                  List<ChangeDetectionResult.ChangeIssue> issues) {
        for (MasterEquipment item : equipment) {
            String material = item.rawValues().getOrDefault(normalizeHeader(materialColumn), "");
            String quantityText = item.rawValues().getOrDefault(normalizeHeader(quantityColumn), "");
            String destination = item.rawValues().getOrDefault(normalizeHeader(destinationColumn), "");
            if (isBlank(material) && isBlank(quantityText) && isBlank(destination)) continue;
            BigDecimal quantity = parseQuantity(quantityText);
            String status;
            String targetPermit = null;
            String targetItem = null;
            String targetProcess = null;
            if (!isBlank(quantityText) && (quantity == null || quantity.signum() < 0)) {
                status = "INVALID";
            } else if (quantity != null && quantity.signum() == 0) {
                status = "EXPLICIT_ZERO";
            } else if (isBlank(quantityText) || isBlank(destination)) {
                status = "INCOMPLETE";
            } else {
                String normalized = normalizeIdentifier(destination);
                if (knownPermitIds.contains(normalized)) {
                    targetPermit = destination.trim();
                    status = "READY_FOR_REVIEW";
                } else if (knownItemNos.contains(normalized)) {
                    targetItem = destination.trim();
                    status = "READY_FOR_REVIEW";
                } else if (knownProcesses.contains(normalized)) {
                    targetProcess = normalizeProcessCode(destination);
                    status = "READY_FOR_REVIEW";
                } else {
                    status = "UNRESOLVED_DESTINATION";
                    issues.add(new ChangeDetectionResult.ChangeIssue(item.sourceRowNumber(), "REVIEW",
                            "UNRESOLVED_FLOW_DESTINATION", destinationColumn,
                            "Destination '" + destination + "' did not exactly match a known permit ID, item number, or process code. No route was created."));
                }
            }
            candidates.add(new FlowCandidate(item.permitManagementNo(), flowType, material, quantity,
                    quantityText, "톤/일", destination, targetPermit, targetItem, targetProcess, status,
                    item.sourceRowNumber(), item.rawValues()));
        }
    }

    private void persistFlowCandidates(long runId, List<FlowCandidate> candidates) {
        if (candidates.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                INSERT INTO change_flow_candidate(
                    change_run_id, permit_management_no, flow_type, material_type, quantity, quantity_source_text,
                    quantity_unit, destination_text, matched_target_permit_no, matched_target_item_no,
                    matched_process_code, resolution_status, route_created, source_row_number, source_values_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)
                """, candidates, 300, (statement, candidate) -> {
            statement.setLong(1, runId);
            statement.setString(2, candidate.permitId());
            statement.setString(3, candidate.flowType());
            statement.setString(4, emptyToNull(candidate.materialType()));
            if (candidate.quantity() == null) statement.setNull(5, java.sql.Types.DECIMAL);
            else statement.setBigDecimal(5, candidate.quantity());
            statement.setString(6, emptyToNull(candidate.quantityText()));
            statement.setString(7, candidate.quantityUnit());
            statement.setString(8, emptyToNull(candidate.destination()));
            statement.setString(9, candidate.targetPermitNo());
            statement.setString(10, candidate.targetItemNo());
            statement.setString(11, candidate.targetProcessCode());
            statement.setString(12, candidate.status());
            statement.setInt(13, candidate.rowNumber());
            statement.setString(14, toJson(candidate.rawValues()));
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listFlowCandidates(long runId) {
        return jdbcTemplate.queryForList("""
                SELECT permit_management_no AS permitManagementNo, flow_type AS flowType,
                       material_type AS materialType, quantity, quantity_source_text AS quantitySourceText,
                       quantity_unit AS quantityUnit, destination_text AS destination,
                       matched_target_permit_no AS matchedTargetPermitNo,
                       matched_target_item_no AS matchedTargetItemNo, matched_process_code AS matchedProcessCode,
                       resolution_status AS status, route_created AS routeCreated,
                       source_row_number AS sourceRowNumber
                FROM change_flow_candidate WHERE change_run_id = ?
                ORDER BY source_row_number, flow_type
                """, runId);
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s._()（）·&/\\-]", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void insertChangeItem(long runId, ChangeDraft draft) {
        jdbcTemplate.update("""
                INSERT INTO change_item(change_run_id, change_type, permit_management_no, old_source_row,
                                        new_source_row, changed_fields_json, before_values_json, after_values_json,
                                        diagram_mapping_status, automatic_mapping_succeeded,
                                        affected_diagrams_json, review_required_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, draft.type(), draft.permitId(), draft.oldRow(), draft.newRow(),
                toJson(draft.changedFields()), draft.before() == null ? null : toJson(draft.before().rawValues()),
                draft.after() == null ? null : toJson(draft.after().rawValues()), draft.mappingStatus(),
                draft.automaticMappingSucceeded(), toJson(draft.affectedDiagrams()), toJson(draft.reviewRequiredItems()));
    }

    private void insertChangeIssue(long runId, ChangeDetectionResult.ChangeIssue issue, String permitId) {
        jdbcTemplate.update("""
                INSERT INTO change_validation_issue(change_run_id, permit_management_no, source_row_number,
                                                    severity, issue_code, field_name, detail_text)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, permitId, issue.sourceRowNumber(), issue.severity(), issue.code(),
                issue.fieldName(), issue.detail());
    }

    private String permitIdForIssue(List<MasterEquipment> equipment, int rowNumber) {
        return equipment.stream().filter(item -> item.sourceRowNumber() == rowNumber)
                .map(MasterEquipment::permitManagementNo).findFirst().orElse(null);
    }

    private long insertChangeRun(long baselineId, String filename, String hash, byte[] bytes, int total,
                                 int added, int removed, int modified, int moved, int issueCount,
                                 String mappingStatus, boolean automaticMappingSucceeded,
                                 List<ChangeDetectionResult.DiagramImpact> affectedDiagrams,
                                 List<String> reviewRequiredItems) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO change_run(baseline_release_id, source_filename, source_sha256, source_content,
                                           analysis_mode, total_records, added_count, removed_count, modified_count,
                                           moved_count, validation_issue_count, diagram_mapping_status,
                                           automatic_mapping_succeeded, affected_diagrams_json, review_required_json)
                    VALUES (?, ?, ?, ?, 'TEST_ONLY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, baselineId);
            statement.setString(2, safeFilename(filename));
            statement.setString(3, hash);
            statement.setBytes(4, bytes);
            statement.setInt(5, total);
            statement.setInt(6, added);
            statement.setInt(7, removed);
            statement.setInt(8, modified);
            statement.setInt(9, moved);
            statement.setInt(10, issueCount);
            statement.setString(11, mappingStatus);
            statement.setBoolean(12, automaticMappingSucceeded);
            statement.setString(13, toJson(affectedDiagrams));
            statement.setString(14, toJson(reviewRequiredItems));
            return statement;
        };
        jdbcTemplate.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("MariaDB did not return a change run ID");
        return key.longValue();
    }

    private void addCode(List<String> codes, String code) {
        if (StringUtils.hasText(code) && !codes.contains(code.trim())) codes.add(code.trim());
    }

    private BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = NUMBER_PATTERN.matcher(text.replace('\u00a0', ' '));
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group().replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeProcessCode(String code) {
        if (code == null) return "";
        String value = code.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("PP-") ? "P-" + value.substring(3) : value;
    }

    private void countNormalized(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank() || value.trim().equals("-")) return;
        counts.merge(normalizeIdentifier(value), 1, Integer::sum);
    }

    private void addNormalized(Set<String> values, String value) {
        if (value != null && !value.isBlank() && !value.trim().equals("-")) {
            values.add(normalizeIdentifier(value));
        }
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String safeFilename(String value) {
        if (value == null || value.isBlank()) return "uploaded-master.xlsx";
        String sanitized = value.replace('\\', '/');
        sanitized = sanitized.substring(sanitized.lastIndexOf('/') + 1);
        return sanitized.length() > 255 ? sanitized.substring(sanitized.length() - 255) : sanitized;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize change data", exception);
        }
    }

    private record FlowCandidate(String permitId, String flowType, String materialType, BigDecimal quantity,
                                 String quantityText, String quantityUnit, String destination,
                                 String targetPermitNo, String targetItemNo, String targetProcessCode,
                                 String status, int rowNumber, Map<String, String> rawValues) {
    }

    private record EquipmentSnapshot(String permitManagementNo, String businessItemNo, String majorProcessCode,
                                     String middleProcessCode, String processCategory, String facilityName,
                                     String permitName, String permitCategory, String capacityText,
                                     String capacityUnit, String quantityText, String operatingHoursText,
                                     String annualOperatingDaysText, String purposeText, String pidNumber,
                                     String closedStatus, String paperExistsStatus, Map<String, String> rawValues,
                                     int sourceRowNumber, int masterOrder) {
    }

    private record ChangeDraft(String type, String permitId, String itemNo, String facilityName,
                               Integer oldRow, Integer newRow,
                               Map<String, ChangeDetectionResult.FieldChange> changedFields,
                               List<String> processCodes, List<String> candidateSheets,
                               String mappingStatus, boolean automaticMappingSucceeded,
                               List<ChangeDetectionResult.DiagramImpact> affectedDiagrams,
                               List<String> reviewRequiredItems,
                               EquipmentSnapshot before, MasterEquipment after) {
        private ChangeDetectionResult.DetectedChange toResponse(Long changeItemId) {
            return new ChangeDetectionResult.DetectedChange(changeItemId, type, permitId, itemNo, facilityName, oldRow, newRow,
                    changedFields, processCodes, candidateSheets, mappingStatus, automaticMappingSucceeded,
                    affectedDiagrams, reviewRequiredItems);
        }
    }
}
