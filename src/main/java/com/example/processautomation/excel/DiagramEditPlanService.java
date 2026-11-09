package com.example.processautomation.excel;

import com.example.processautomation.model.MasterEquipment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Builds TEST_ONLY edit plans from persisted Step 6-1 change items. */
@Service
public class DiagramEditPlanService {
    private static final String TEST_ONLY = "TEST_ONLY";
    private static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    private final JdbcTemplate jdbc;
    private final MasterWorkbookParser masterParser;
    private final OoxmlPackageInspector inspector;
    private final Pw0105EquipmentInsertionEngine engine;
    private final ObjectMapper mapper;

    public DiagramEditPlanService(JdbcTemplate jdbc, MasterWorkbookParser masterParser,
                                  OoxmlPackageInspector inspector, Pw0105EquipmentInsertionEngine engine,
                                  ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.masterParser = masterParser;
        this.inspector = inspector;
        this.engine = engine;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> createPlan(CreatePlanRequest request) {
        ChangeInput input = loadChangeInput(request.changeRunId(), request.changeItemId());
        List<String> reviews = new ArrayList<>();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("changeRunId", input.changeRunId());
        plan.put("analysisRunId", input.changeRunId());
        plan.put("changeItemId", input.changeItemId());
        plan.put("changeType", input.changeType());
        plan.put("analysisMode", input.analysisMode());
        plan.put("equipment", equipmentSummary(input.equipment()));
        plan.put("baselineMappingStatus", input.mappingStatus());
        plan.put("baselineDiagramCandidates", input.affectedDiagrams());
        plan.put("scenarioStatus", TEST_ONLY);
        plan.put("relationStatus", REVIEW_REQUIRED);
        plan.put("officialApprovalCreated", false);
        plan.put("testOnlyScenario", request.testOnlyScenario());

        String status = REVIEW_REQUIRED;
        boolean automaticEditAllowed = false;
        String targetProcessCode = null;
        String targetSheetName = null;
        String targetFilename = null;
        Long targetDocumentId = null;
        byte[] targetWorkbookBytes = null;
        Pw0105EquipmentInsertionEngine.Plan enginePlan = null;

        if (!"ADDED".equals(input.changeType())) {
            reviews.add("Only a new ADDED equipment item can create an insertion plan; this change item is not ADDED.");
        }
        if (!TEST_ONLY.equals(input.analysisMode())) {
            reviews.add("The source change run is not labeled TEST_ONLY; no edit may be generated.");
        }
        if (request.testOnlyScenario() == null || !request.testOnlyScenario().enabled()) {
            reviews.add("No explicit TEST_ONLY placement and endpoint configuration was supplied. Existing master-row adjacency is not relationship evidence.");
        }

        if ("ADDED".equals(input.changeType()) && TEST_ONLY.equals(input.analysisMode())
                && request.testOnlyScenario() != null && request.testOnlyScenario().enabled()) {
            TestOnlyScenario scenario = request.testOnlyScenario();
            targetProcessCode = clean(scenario.processCode());
            targetSheetName = clean(scenario.sheetName());
            targetFilename = clean(scenario.workbookFilename());
            if (!processMatches(input.equipment(), targetProcessCode)) {
                reviews.add("Explicit TEST_ONLY process code does not match the uploaded equipment process code.");
            } else if (!hasCandidate(input.affectedDiagrams(), targetProcessCode, targetFilename, targetSheetName)) {
                reviews.add("The explicit TEST_ONLY workbook/sheet is not present among Step 6-1 affected-diagram candidates.");
            } else {
                BaselineWorkbook baseline = loadBaselineWorkbook(targetProcessCode, targetFilename);
                targetDocumentId = baseline.documentId();
                targetWorkbookBytes = baseline.content();
                var inventory = inspector.inspect(targetWorkbookBytes);
                String selectedSheetName = targetSheetName;
                List<WorkbookInventory.SheetInventory> sameNamedSheets = inventory.sheets().stream()
                        .filter(sheet -> selectedSheetName.equals(sheet.sheetName())).toList();
                if (sameNamedSheets.size() != 1) {
                    reviews.add("The target worksheet is missing or ambiguous in the official baseline workbook.");
                } else {
                    var spec = new Pw0105EquipmentInsertionEngine.TestEditSpec(targetSheetName,
                            clean(scenario.predecessorManagementNo()), clean(scenario.predecessorName()),
                            clean(scenario.successorManagementNo()), clean(scenario.successorName()),
                            clean(scenario.equipmentTypeLabel()));
                    try {
                        enginePlan = engine.analyze(targetWorkbookBytes, spec);
                        if (enginePlan.existingConnector().shapeId() == null) {
                            reviews.add("No existing visual connector is present between the explicit TEST_ONLY endpoints.");
                        } else {
                            List<String> collisions = detectBoundaryCollisions(sameNamedSheets.get(0), enginePlan);
                            if (!collisions.isEmpty()) {
                                reviews.add("Existing drawing object(s) cross the insertion boundary: " + String.join(", ", collisions));
                            } else {
                                status = "TEST_ONLY_READY";
                                automaticEditAllowed = true;
                                reviews.add("The explicit scenario permits generation of a TEST_ONLY artifact only; the endpoint relationship and drawing assignment remain unapproved for business use.");
                            }
                        }
                    } catch (InvalidWorkbookException exception) {
                        reviews.add("Layout analysis is REVIEW_REQUIRED: " + exception.getMessage());
                    }
                }
            }
        }

        if (reviews.isEmpty() && !automaticEditAllowed) {
            reviews.add("No safe, explicitly configured TEST_ONLY edit was established.");
        }
        plan.put("target", targetMap(targetDocumentId, targetProcessCode, targetFilename, targetSheetName));
        plan.put("insertion", enginePlan == null ? null : insertionMap(enginePlan));
        plan.put("predecessorCandidate", enginePlan == null ? scenarioEndpoint(request, true) : boxMap(enginePlan.source()));
        plan.put("successorCandidate", enginePlan == null ? scenarioEndpoint(request, false) : boxMap(enginePlan.target()));
        plan.put("insertionEvidence", enginePlan == null
                ? "No verified target layout; adjacency in the master workbook is not used as evidence."
                : "Explicit TEST_ONLY endpoint configuration plus an existing visual connector and uniquely located workbook boxes. The visual line is not an approved business relationship.");
        plan.put("boxTemplate", enginePlan == null ? Map.of("status", REVIEW_REQUIRED)
                : Map.of("sourceManagementNo", enginePlan.source().managementNo(),
                        "sourceBox", enginePlan.source().topLeftCell() + ":" + enginePlan.source().bottomRightCell(),
                        "style", "Clone source equipment box cell styles, merged label layout, and border geometry"));
        plan.put("collisionAssessment", enginePlan == null ? "NOT_ANALYZED"
                : status.equals("TEST_ONLY_READY") ? "NO_CROSSING_OBJECTS_DETECTED" : "REVIEW_REQUIRED");
        plan.put("relationshipReview", List.of(REVIEW_REQUIRED,
                "TEST_ONLY predecessor/successor configuration is a test fixture, not an approved relation."));
        plan.put("reviewRequiredItems", List.copyOf(reviews));
        plan.put("automaticEditAllowed", automaticEditAllowed);
        plan.put("automaticEditScope", automaticEditAllowed ? "TEST_ONLY_ARTIFACT_ONLY" : "NONE");
        plan.put("generationSource", "Immutable V1.0 process workbook in baseline_document; source row is never edited.");
        plan.put("flowValuesPolicy", "No real generation, destination, wastewater, or waste quantities are inferred or filled.");

        String json = writeJson(plan);
        String reviewsJson = writeJson(reviews);
        String requestedJson = writeJson(request.testOnlyScenario());
        final Long storedDocumentId = targetDocumentId;
        final String storedStatus = status;
        final boolean storedAutomaticEditAllowed = automaticEditAllowed;
        final String storedProcessCode = targetProcessCode;
        final String storedSheetName = targetSheetName;
        final String storedFilename = targetFilename;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO diagram_edit_plan(change_run_id, change_item_id, baseline_document_id,
                        analysis_mode, plan_status, automatic_edit_allowed, relation_status,
                        target_process_code, target_sheet_name, target_filename,
                        requested_scenario_json, plan_json, review_required_json)
                    VALUES (?, ?, ?, 'TEST_ONLY', ?, ?, 'REVIEW_REQUIRED', ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, input.changeRunId());
            statement.setLong(2, input.changeItemId());
            if (storedDocumentId == null) statement.setNull(3, java.sql.Types.BIGINT);
            else statement.setLong(3, storedDocumentId);
            statement.setString(4, storedStatus);
            statement.setBoolean(5, storedAutomaticEditAllowed);
            statement.setString(6, storedProcessCode);
            statement.setString(7, storedSheetName);
            statement.setString(8, storedFilename);
            statement.setString(9, requestedJson);
            statement.setString(10, json);
            statement.setString(11, reviewsJson);
            return statement;
        };
        jdbc.update(creator, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("MariaDB did not return a diagram edit plan ID");
        plan.put("planId", id.longValue());
        plan.put("planStatus", status);
        return plan;
    }

    @Transactional
    public GeneratedWorkbook generate(long planId) {
        PlanRecord plan = loadPlan(planId);
        if (!"TEST_ONLY_READY".equals(plan.status()) || !plan.automaticEditAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan is REVIEW_REQUIRED; automatic TEST_ONLY workbook generation is blocked.");
        }
        if (plan.output() != null && plan.output().length > 0) {
            return new GeneratedWorkbook(plan.output(), plan.outputFilename(), plan.outputSha256(), true);
        }
        ChangeInput input = loadChangeInput(plan.changeRunId(), plan.changeItemId());
        Map<String, Object> scenario = readMap(plan.requestedScenarioJson());
        TestOnlyScenario spec = mapper.convertValue(scenario, TestOnlyScenario.class);
        BaselineWorkbook baseline = loadBaselineWorkbook(plan.processCode(), plan.filenameTarget());
        Pw0105EquipmentInsertionEngine.TestEditSpec editSpec = new Pw0105EquipmentInsertionEngine.TestEditSpec(
                spec.sheetName(), spec.predecessorManagementNo(), spec.predecessorName(),
                spec.successorManagementNo(), spec.successorName(), spec.equipmentTypeLabel());
        Pw0105EquipmentInsertionEngine.EditResult edit = engine.insertTestOnly(baseline.content(), input.equipment(), editSpec);
        byte[] output = edit.workbookBytes();
        String filename = "TEST_ONLY-" + safeName(input.equipment().permitManagementNo()) + "-" + safeName(spec.processCode()) + ".xlsx";
        String hash = sha256(output);
        jdbc.update("""
                UPDATE diagram_edit_plan SET output_filename = ?, output_sha256 = ?, output_content = ?
                WHERE id = ? AND plan_status = 'TEST_ONLY_READY' AND automatic_edit_allowed = TRUE
                """, filename, hash, output, planId);
        return new GeneratedWorkbook(output, filename, hash, false);
    }

    @Transactional(readOnly = true)
    public GeneratedWorkbook download(long planId) {
        PlanRecord plan = loadPlan(planId);
        if (plan.output() == null || plan.output().length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No TEST_ONLY workbook is stored for this plan.");
        }
        return new GeneratedWorkbook(plan.output(), plan.outputFilename(), plan.outputSha256(), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPlan(long planId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id AS planId, change_run_id AS changeRunId, change_item_id AS changeItemId,
                       plan_status AS planStatus, automatic_edit_allowed AS automaticEditAllowed,
                       relation_status AS relationStatus, target_process_code AS targetProcessCode,
                       target_sheet_name AS targetSheetName, target_filename AS targetFilename,
                       output_filename AS outputFilename, output_sha256 AS outputSha256,
                       plan_json AS planJson, review_required_json AS reviewRequiredJson, created_at AS createdAt
                FROM diagram_edit_plan WHERE id = ?
                """, planId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram edit plan not found.");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("plan", readMap((String) result.remove("planJson")));
        result.put("reviewRequiredItems", readStringList((String) result.remove("reviewRequiredJson")));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPlansForItem(long changeRunId, long changeItemId) {
        if (changeRunId <= 0 || changeItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Positive changeRunId and changeItemId are required.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id AS planId, p.change_run_id AS changeRunId, p.change_item_id AS changeItemId,
                       p.plan_status AS planStatus, p.automatic_edit_allowed AS automaticEditAllowed,
                       p.relation_status AS relationStatus, p.analysis_mode AS analysisMode,
                       p.target_process_code AS targetProcessCode, p.target_sheet_name AS targetSheetName,
                       p.target_filename AS targetFilename, p.output_filename AS outputFilename,
                       p.output_sha256 AS outputSha256, p.plan_json AS planJson,
                       p.review_required_json AS reviewRequiredJson, p.created_at AS createdAt
                FROM diagram_edit_plan p
                JOIN change_run r ON r.id = p.change_run_id
                JOIN change_item i ON i.id = p.change_item_id AND i.change_run_id = p.change_run_id
                WHERE p.change_run_id = ? AND p.change_item_id = ?
                ORDER BY p.id
                """, changeRunId, changeItemId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> plan = readMap((String) row.remove("planJson"));
            Map<String, Object> value = new LinkedHashMap<>(plan);
            value.putAll(row);
            value.put("plan", plan);
            value.put("reviewRequiredItems", readStringList((String) row.remove("reviewRequiredJson")));
            result.add(value);
        }
        return List.copyOf(result);
    }

    private ChangeInput loadChangeInput(long runId, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS run_id, r.analysis_mode, r.source_content,
                       i.id AS item_id, i.change_type, i.permit_management_no, i.after_values_json,
                       i.diagram_mapping_status, i.affected_diagrams_json
                FROM change_run r JOIN change_item i ON i.change_run_id = r.id
                WHERE r.id = ? AND i.id = ?
                """, runId, itemId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The supplied changeRunId/changeItemId pair does not exist.");
        Map<String, Object> row = rows.get(0);
        byte[] uploaded = (byte[]) row.get("source_content");
        if (uploaded == null || uploaded.length == 0) throw new InvalidWorkbookException("Uploaded master bytes are not stored for this change run.");
        String permitNo = (String) row.get("permit_management_no");
        MasterEquipment equipment = masterParser.parse(uploaded).equipment().stream()
                .filter(item -> permitNo.equals(item.permitManagementNo())).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Change item equipment was not found in the uploaded master payload."));
        List<Map<String, Object>> impacts = readMapList((String) row.get("affected_diagrams_json"));
        return new ChangeInput(((Number) row.get("run_id")).longValue(), ((Number) row.get("item_id")).longValue(),
                (String) row.get("analysis_mode"), (String) row.get("change_type"),
                (String) row.get("diagram_mapping_status"), impacts, equipment);
    }

    private BaselineWorkbook loadBaselineWorkbook(String processCode, String filename) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.original_filename, d.content_bytes, d.sha256
                FROM baseline_document d JOIN baseline_release b ON b.id = d.baseline_release_id
                WHERE b.version_code = 'V1.0' AND b.locked = TRUE AND d.document_role = 'PROCESS'
                  AND (d.process_code = ? OR ? LIKE CONCAT(d.process_code, '-%'))
                  AND d.original_filename = ?
                """, processCode, processCode, filename);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The requested process document is not uniquely registered in locked V1.0.");
        Map<String, Object> row = rows.get(0);
        byte[] bytes = (byte[]) row.get("content_bytes");
        if (!sha256(bytes).equalsIgnoreCase((String) row.get("sha256"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "V1.0 process workbook hash mismatch; edit stopped.");
        }
        return new BaselineWorkbook(((Number) row.get("id")).longValue(), bytes);
    }

    private PlanRecord loadPlan(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, change_run_id, change_item_id, plan_status, automatic_edit_allowed,
                       target_process_code, target_filename, requested_scenario_json,
                       output_filename, output_sha256, output_content
                FROM diagram_edit_plan WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram edit plan not found.");
        Map<String, Object> row = rows.get(0);
        return new PlanRecord(((Number) row.get("id")).longValue(), ((Number) row.get("change_run_id")).longValue(),
                ((Number) row.get("change_item_id")).longValue(), (String) row.get("plan_status"),
                Boolean.TRUE.equals(row.get("automatic_edit_allowed")), (String) row.get("target_process_code"),
                (String) row.get("target_filename"), (String) row.get("requested_scenario_json"),
                (String) row.get("output_filename"), (String) row.get("output_sha256"), (byte[]) row.get("output_content"));
    }

    private boolean hasCandidate(List<Map<String, Object>> candidates, String code, String filename, String sheet) {
        return candidates.stream().anyMatch(candidate -> {
            String candidateCode = candidate.get("processCode") == null ? "" : candidate.get("processCode").toString();
            boolean processCompatible = candidateCode.equalsIgnoreCase(code)
                    || code.toUpperCase(java.util.Locale.ROOT).startsWith(candidateCode.toUpperCase(java.util.Locale.ROOT) + "-");
            return processCompatible && equals(candidate.get("processWorkbook"), filename)
                    && equals(candidate.get("sheetName"), sheet);
        });
    }

    private boolean processMatches(MasterEquipment equipment, String code) {
        return code != null && (code.equalsIgnoreCase(clean(equipment.middleProcessCode()))
                || code.equalsIgnoreCase(clean(equipment.majorProcessCode())));
    }

    private List<String> detectBoundaryCollisions(WorkbookInventory.SheetInventory sheet,
                                                   Pw0105EquipmentInsertionEngine.Plan plan) {
        List<String> collisions = new ArrayList<>();
        int boundary = plan.insertionBeforeRow() - 1;
        for (WorkbookInventory.DrawingObject object : sheet.drawingObjects()) {
            if (object.shapeId() != null && object.shapeId().equals(plan.existingConnector().shapeId())) continue;
            if (object.startColumn() == null || object.endColumn() == null
                    || object.startRow() == null || object.endRow() == null) continue;
            boolean horizontalOverlap = object.startColumn() <= plan.lastColumn() && object.endColumn() >= plan.firstColumn();
            boolean crosses = object.startRow() < boundary && object.endRow() >= boundary;
            if (horizontalOverlap && crosses) collisions.add("id=" + object.shapeId() + " " + object.objectName());
        }
        return collisions;
    }

    private Map<String, Object> equipmentSummary(MasterEquipment equipment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managementNo", equipment.permitManagementNo());
        result.put("facilityName", equipment.facilityName());
        result.put("businessItemNo", equipment.businessItemNo());
        result.put("capacity", equipment.capacityText());
        result.put("capacityUnit", equipment.capacityUnit());
        result.put("quantity", equipment.quantityText());
        result.put("majorProcessCode", equipment.majorProcessCode());
        result.put("middleProcessCode", equipment.middleProcessCode());
        result.put("wastewaterType", equipment.wastewaterType());
        result.put("wastewaterQuantity", equipment.wastewaterQuantityText());
        result.put("wastewaterDestination", equipment.wastewaterDestination());
        result.put("wasteType", equipment.wasteType());
        result.put("wasteQuantity", equipment.wasteQuantityText());
        result.put("wasteDestination", equipment.wasteDestination());
        result.put("sourceRowNumber", equipment.sourceRowNumber());
        return result;
    }

    private Map<String, Object> insertionMap(Pw0105EquipmentInsertionEngine.Plan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetName", plan.sheetName());
        result.put("plannedBox", cellAddress(plan.firstColumn(), 0).replaceAll("[0-9]+$", "")
                + ":" + cellAddress(plan.lastColumn(), 0).replaceAll("[0-9]+$", ""));
        result.put("plannedCellRange", plan.source().topLeftCell().replaceAll("[0-9]+$", "") + plan.candidateFirstRow()
                + ":" + plan.source().bottomRightCell().replaceAll("[0-9]+$", "") + plan.candidateLastRow());
        result.put("insertBeforeRow", plan.insertionBeforeRow());
        result.put("candidateFirstRow", plan.candidateFirstRow());
        result.put("candidateLastRow", plan.candidateLastRow());
        result.put("rowsInserted", plan.insertedRows());
        result.put("originalGapRows", plan.originalGapRows());
        result.put("existingConnectorShapeId", plan.existingConnector().shapeId());
        result.put("connectorArrow", plan.existingConnector().arrowAtEnd());
        result.put("relationConfirmed", false);
        return result;
    }

    private String cellAddress(int column, int zeroBasedRow) {
        return new org.apache.poi.ss.util.CellReference(zeroBasedRow, column).formatAsString().replace("$", "");
    }

    private Map<String, Object> boxMap(Pw0105EquipmentInsertionEngine.EquipmentBox box) {
        return Map.of("managementNo", box.managementNo(), "facilityName", box.facilityName(),
                "cellRange", box.topLeftCell() + ":" + box.bottomRightCell());
    }

    private Object scenarioEndpoint(CreatePlanRequest request, boolean predecessor) {
        if (request.testOnlyScenario() == null) return Map.of("status", REVIEW_REQUIRED, "configured", false);
        TestOnlyScenario spec = request.testOnlyScenario();
        return Map.of("managementNo", predecessor ? clean(spec.predecessorManagementNo()) : clean(spec.successorManagementNo()),
                "name", predecessor ? clean(spec.predecessorName()) : clean(spec.successorName()),
                "status", REVIEW_REQUIRED, "source", "Explicit TEST_ONLY scenario only");
    }

    private Map<String, Object> targetMap(Long documentId, String code, String filename, String sheet) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baselineDocumentId", documentId);
        result.put("processCode", code);
        result.put("workbookFilename", filename);
        result.put("sheetName", sheet);
        return result;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Stored plan JSON is invalid", exception); }
    }

    private List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Stored diagram-candidate JSON is invalid", exception); }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("Stored review JSON is invalid", exception); }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize edit plan", exception); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private boolean equals(Object actual, String expected) {
        return actual != null && expected != null && expected.equalsIgnoreCase(actual.toString());
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String safeName(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_"); }

    public record CreatePlanRequest(long changeRunId, long changeItemId, TestOnlyScenario testOnlyScenario) { }
    public record TestOnlyScenario(boolean enabled, String processCode, String workbookFilename,
                                   String sheetName, String predecessorManagementNo, String predecessorName,
                                   String successorManagementNo, String successorName, String equipmentTypeLabel) { }
    public record GeneratedWorkbook(byte[] bytes, String filename, String sha256, boolean reused) { }
    private record ChangeInput(long changeRunId, long changeItemId, String analysisMode, String changeType,
                               String mappingStatus, List<Map<String, Object>> affectedDiagrams,
                               MasterEquipment equipment) { }
    private record BaselineWorkbook(long documentId, byte[] content) { }
    private record PlanRecord(long id, long changeRunId, long changeItemId, String status,
                              boolean automaticEditAllowed, String processCode, String filenameTarget,
                              String requestedScenarioJson, String outputFilename, String outputSha256,
                              byte[] output) { }
}
