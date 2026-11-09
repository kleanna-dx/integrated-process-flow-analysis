package com.example.processautomation.material;

import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.example.processautomation.excel.MaterialBalanceExcelOutputModel;
import com.example.processautomation.excel.MasterWorkbookParser;
import com.example.processautomation.excel.DiagramEditPlanService;
import com.example.processautomation.model.MasterEquipment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Adds isolated change-history and synthetic balance sheets to one run-193 TEST_ONLY edit. */
@Service
public class Step6TestOnlyWorkbookEnrichmentService {
    private static final String CHANGE_SHEET = "변경내역";
    private static final String ACTUAL_DATA_STATUS =
            "실제 발생량·이동처·승인 연결 근거 부족으로 검증 불가 — 업로드 실제자료는 공란이며 TEST_ONLY 합성값과 분리";
    private final MaterialBalanceCalculator calculator;
    private final MaterialBalanceExcelExportService workbookExport;
    private final JdbcTemplate jdbc;
    private final MasterWorkbookParser masterParser;
    private final ObjectMapper objectMapper;

    public Step6TestOnlyWorkbookEnrichmentService(MaterialBalanceCalculator calculator,
                                                  MaterialBalanceExcelExportService workbookExport,
                                                  JdbcTemplate jdbc, MasterWorkbookParser masterParser,
                                                  ObjectMapper objectMapper) {
        this.calculator = calculator;
        this.workbookExport = workbookExport;
        this.jdbc = jdbc;
        this.masterParser = masterParser;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EnrichedWorkbook createChangeHistoryOnly(long planId) {
        return enrichStoredPlan(planId, 193, 217, true, null, false);
    }

    @Transactional(readOnly = true)
    public EnrichedWorkbook enrichStoredPlan(long planId) {
        return enrichStoredPlan(planId, 193, 217, true, null, true);
    }

    public void requireEligiblePlan(long changeRunId, long changeItemId, long planId) {
        if (changeRunId != 193 || changeItemId != 217 || planId <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "현재 연결된 통합 생성은 검증된 run 193 / item 217 시나리오만 지원합니다.");
        }
        Map<String, Object> plan = loadPlanRow(planId, changeRunId, changeItemId);
        validatePlanSelection(plan, changeRunId, changeItemId);
    }

    @Transactional
    public StoredIntegratedWorkbook createAndStoreIntegratedWorkbook(long changeRunId, long changeItemId,
                                                                     long planId,
                                                                     DiagramEditPlanService.GeneratedWorkbook diagram,
                                                                     boolean includeSyntheticTestValues) {
        requireEligiblePlan(changeRunId, changeItemId, planId);
        if (diagram == null || diagram.bytes() == null || diagram.bytes().length == 0
                || !sha256(diagram.bytes()).equalsIgnoreCase(diagram.sha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "편집 계획의 도면 산출물 해시가 없거나 일치하지 않아 통합 생성을 중단했습니다.");
        }
        String scenarioKey = includeSyntheticTestValues ? "SYNTHETIC_TEST_ONLY" : "ACTUAL_REVIEW_ONLY";
        List<Map<String, Object>> stored = jdbc.queryForList("""
                SELECT scenario_key, change_run_id, change_item_id, source_diagram_sha256,
                       output_sha256, output_filename, output_content
                FROM diagram_edit_integrated_output WHERE plan_id = ? AND scenario_key = ?
                """, planId, scenarioKey);
        if (!stored.isEmpty()) {
            Map<String, Object> row = stored.get(0);
            byte[] bytes = (byte[]) row.get("output_content");
            String hash = String.valueOf(row.get("output_sha256"));
            if (number(row.get("change_run_id")) != changeRunId || number(row.get("change_item_id")) != changeItemId
                    || !diagram.sha256().equalsIgnoreCase(String.valueOf(row.get("source_diagram_sha256")))
                    || bytes == null || !hash.equalsIgnoreCase(sha256(bytes))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "기존 통합 결과의 연결 또는 SHA-256이 달라 재사용할 수 없습니다. 기존 결과는 보존했습니다.");
            }
            return new StoredIntegratedWorkbook(bytes, String.valueOf(row.get("output_filename")), hash,
                    scenarioKey, planId, true);
        }
        EnrichedWorkbook enriched = enrichStoredPlan(planId, changeRunId, changeItemId,
                includeSyntheticTestValues, diagram.sha256(), true);
        jdbc.update("""
                INSERT INTO diagram_edit_integrated_output(plan_id, scenario_key, change_run_id, change_item_id,
                    source_diagram_sha256, output_sha256, output_filename, output_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, planId, scenarioKey, changeRunId, changeItemId, diagram.sha256(), enriched.sha256(),
                enriched.filename(), enriched.bytes());
        return new StoredIntegratedWorkbook(enriched.bytes(), enriched.filename(), enriched.sha256(),
                scenarioKey, planId, false);
    }

    @Transactional(readOnly = true)
    public StoredIntegratedWorkbook downloadIntegratedWorkbook(long planId, boolean includeSyntheticTestValues) {
        if (planId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planId는 양수여야 합니다.");
        String scenarioKey = includeSyntheticTestValues ? "SYNTHETIC_TEST_ONLY" : "ACTUAL_REVIEW_ONLY";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.plan_id AS planId, o.change_run_id AS changeRunId, o.change_item_id AS changeItemId,
                       o.output_sha256 AS outputSha256, o.output_filename AS outputFilename,
                       o.output_content AS outputContent, p.plan_status AS planStatus,
                       p.automatic_edit_allowed AS automaticEditAllowed, p.relation_status AS relationStatus,
                       p.analysis_mode AS analysisMode, p.target_process_code AS targetProcessCode,
                       p.target_sheet_name AS targetSheetName, p.target_filename AS targetFilename,
                       p.plan_json AS planJson, r.analysis_mode AS runMode, i.change_type AS changeType,
                       i.permit_management_no AS managementNo
                FROM diagram_edit_integrated_output o JOIN diagram_edit_plan p ON p.id = o.plan_id
                JOIN change_run r ON r.id = p.change_run_id
                JOIN change_item i ON i.id = p.change_item_id AND i.change_run_id = p.change_run_id
                WHERE o.plan_id = ? AND o.scenario_key = ?
                """, planId, scenarioKey);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "검증된 통합 Excel이 없습니다. 먼저 통합 생성 단계를 완료해 주세요.");
        Map<String, Object> row = rows.get(0);
        validatePlanSelection(row, number(row.get("changeRunId")), number(row.get("changeItemId")));
        byte[] bytes = (byte[]) row.get("outputContent");
        String hash = String.valueOf(row.get("outputSha256"));
        if (bytes == null || bytes.length == 0 || !hash.equalsIgnoreCase(sha256(bytes))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 통합 Excel의 SHA-256이 일치하지 않습니다.");
        }
        return new StoredIntegratedWorkbook(bytes, String.valueOf(row.get("outputFilename")), hash,
                scenarioKey, planId, true);
    }

    private EnrichedWorkbook enrichStoredPlan(long planId, long expectedRunId, long expectedItemId,
                                               boolean includeSyntheticTestValues, String expectedDiagramHash,
                                               boolean includeMaterialBalance) {
        Map<String, Object> row = loadPlanRow(planId, expectedRunId, expectedItemId);
        validatePlanSelection(row, expectedRunId, expectedItemId);
        long runId = number(row.get("changeRunId"));
        long itemId = number(row.get("changeItemId"));
        if (expectedDiagramHash != null && !expectedDiagramHash.equalsIgnoreCase(String.valueOf(row.get("outputSha256")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 도면 수정본의 SHA-256이 요청 결과와 다릅니다.");
        }
        byte[] editedDiagram = (byte[]) row.get("outputContent");
        String storedHash = String.valueOf(row.get("outputSha256"));
        if (editedDiagram == null || editedDiagram.length == 0 || !storedHash.equalsIgnoreCase(sha256(editedDiagram))) {
            throw new IllegalArgumentException("Stored plan output is empty or its SHA-256 does not match the DB metadata");
        }
        byte[] uploadedMaster = (byte[]) row.get("sourceContent");
        if (uploadedMaster == null || uploadedMaster.length == 0
                || !sha256(uploadedMaster).equalsIgnoreCase(String.valueOf(row.get("sourceSha256")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "저장 분석 run의 업로드 원본이 없거나 SHA-256 메타데이터와 일치하지 않습니다.");
        }
        String managementNo = String.valueOf(row.get("managementNo"));
        List<MasterEquipment> matches = masterParser.parse(uploadedMaster).equipment().stream()
                .filter(equipment -> managementNo.equals(equipment.permitManagementNo())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("The run-matched uploaded master must contain exactly one change item equipment");
        MasterEquipment equipment = matches.get(0);
        if (!"C-PW15020".equals(equipment.permitManagementNo()) || !"SCREEN FILTER".equals(equipment.facilityName())) {
            throw new IllegalArgumentException("The run-matched uploaded master item is not C-PW15020 / SCREEN FILTER");
        }
        requireActualFlowFieldsBlank(equipment);

        Map<String, Object> plan = readObject(String.valueOf(row.get("planJson")));
        Map<String, Object> storedPlan = new LinkedHashMap<>(plan);
        storedPlan.put("plan", plan);
        storedPlan.put("planId", row.get("planId"));
        storedPlan.put("changeRunId", runId);
        storedPlan.put("changeItemId", itemId);
        storedPlan.put("planStatus", row.get("planStatus"));
        storedPlan.put("automaticEditAllowed", row.get("automaticEditAllowed"));
        storedPlan.put("relationStatus", row.get("relationStatus"));
        storedPlan.put("changeType", row.get("changeType"));
        storedPlan.put("beforeValues", row.get("beforeValuesJson") == null ? "" : row.get("beforeValuesJson").toString());
        storedPlan.put("afterValues", row.get("afterValuesJson") == null ? "" : row.get("afterValuesJson").toString());
        storedPlan.put("reviewRequiredText", readStringList(String.valueOf(row.get("reviewRequiredJson"))).stream()
                .reduce((left, right) -> left + " / " + right).orElse("담당자 검토 필요"));
        requireStoredPlan(storedPlan, runId, itemId);
        workbookExport.validateGeneratedEditSource(editedDiagram);

        byte[] withChangeHistory = workbookExport.addChangeHistorySheet(editedDiagram,
                equipment, storedPlan, runId, itemId);
        if (!includeMaterialBalance) {
            String historyFilename = "TEST_ONLY-run" + runId + "-plan" + planId
                    + "-C-PW15020-PW-01-05-change-history.xlsx";
            return new EnrichedWorkbook(withChangeHistory, historyFilename, sha256(withChangeHistory),
                    null, null, CHANGE_SHEET, storedHash);
        }
        MaterialBalanceRequest request = includeSyntheticTestValues
                ? syntheticBalanceRequest(runId, managementNo)
                : actualReviewOnlyRequest(runId, managementNo);
        MaterialBalanceCalculator.Calculation calculation = calculator.calculate(request, Map.of());
        Map<String, Object> calculationResult = new LinkedHashMap<>();
        calculationResult.put("mode", "TEST_ONLY");
        calculationResult.put("officialBaselineModified", false);
        calculationResult.put("productionMaterialBalanceUpdated", false);
        calculationResult.put("transfers", calculation.transfers());
        calculationResult.put("nodes", calculation.nodes());
        MaterialBalanceExcelOutputModel outputModel = workbookExport.createOutputModel(request, calculationResult, withChangeHistory);
        MaterialBalanceExcelExportService.ExportArtifact balanceSheet =
                workbookExport.addGeneratedEditResultsSheet(withChangeHistory, outputModel, ACTUAL_DATA_STATUS,
                        includeSyntheticTestValues);
        String scenarioTag = includeSyntheticTestValues ? "synthetic-values" : "actual-review-only";
        String filename = "TEST_ONLY-run" + runId + "-item" + itemId + "-plan" + planId
                + "-PW-01-05-integrated-" + scenarioTag + ".xlsx";
        byte[] integratedWorkbook = balanceSheet.workbookBytes();
        validateFinalIntegratedWorkbook(integratedWorkbook);
        return new EnrichedWorkbook(integratedWorkbook, filename, sha256(integratedWorkbook),
                outputModel, balanceSheet.worksheetPart(), CHANGE_SHEET, storedHash);
    }

    private MaterialBalanceRequest actualReviewOnlyRequest(long runId, String managementNo) {
        return new MaterialBalanceRequest("TEST_ONLY-run" + runId + "-" + managementNo + "-actual-review-only",
                managementNo + " 실제자료 검토 — 합성 시나리오 미선택", "TEST_ONLY", List.of(), List.of());
    }

    private Map<String, Object> loadPlanRow(long planId, long runId, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id AS planId, p.change_run_id AS changeRunId, p.change_item_id AS changeItemId,
                       p.plan_status AS planStatus, p.automatic_edit_allowed AS automaticEditAllowed,
                       p.relation_status AS relationStatus, p.analysis_mode AS analysisMode,
                       p.target_process_code AS targetProcessCode, p.target_sheet_name AS targetSheetName,
                       p.target_filename AS targetFilename, p.plan_json AS planJson,
                       p.review_required_json AS reviewRequiredJson, p.output_filename AS outputFilename,
                       p.output_sha256 AS outputSha256, p.output_content AS outputContent,
                       r.analysis_mode AS runMode, r.source_content AS sourceContent,
                       r.source_sha256 AS sourceSha256,
                       i.change_type AS changeType, i.permit_management_no AS managementNo,
                       i.before_values_json AS beforeValuesJson, i.after_values_json AS afterValuesJson
                FROM diagram_edit_plan p
                JOIN change_run r ON r.id = p.change_run_id
                JOIN change_item i ON i.id = p.change_item_id AND i.change_run_id = p.change_run_id
                WHERE p.id = ? AND p.change_run_id = ? AND p.change_item_id = ?
                """, planId, runId, itemId);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "선택한 편집 계획이 해당 분석 run 및 변경 항목에 연결되어 있지 않습니다.");
        return rows.get(0);
    }

    private void validatePlanSelection(Map<String, Object> row, long runId, long itemId) {
        if (runId != 193 || itemId != 217
                || number(row.get("changeRunId")) != runId || number(row.get("changeItemId")) != itemId
                || !"TEST_ONLY".equals(row.getOrDefault("analysisMode", row.get("runMode")))
                || !"TEST_ONLY".equals(row.getOrDefault("runMode", row.get("analysisMode")))
                || !"ADDED".equals(row.get("changeType"))
                || !"C-PW15020".equals(row.get("managementNo"))
                || !"PW-01-05".equals(row.get("targetSheetName"))
                || !"PW-01 폐수처리공정 ★변경허가.xlsx".equals(row.get("targetFilename"))
                || !"PW-01-05".equals(row.get("targetProcessCode"))
                || !"TEST_ONLY_READY".equals(row.get("planStatus"))
                || !Boolean.TRUE.equals(row.get("automaticEditAllowed"))
                || !"REVIEW_REQUIRED".equals(row.get("relationStatus"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "저장 run/item/계획이 현재 지원되는 TEST_ONLY_READY / REVIEW_REQUIRED 시나리오와 일치하지 않습니다.");
        }
        Object planJson = row.get("planJson");
        if (planJson != null) {
            Map<String, Object> storedPlan = readObject(String.valueOf(planJson));
            Object targetValue = storedPlan.get("target");
            if (!(targetValue instanceof Map<?, ?> target)
                    || !"PW-01-05".equals(target.get("sheetName"))
                    || !"PW-01 폐수처리공정 ★변경허가.xlsx".equals(target.get("workbookFilename"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 계획의 도면/시트 대상이 검증된 범위와 다릅니다.");
            }
            Object scenario = storedPlan.get("testOnlyScenario");
            if (!(scenario instanceof Map<?, ?> settings) || !Boolean.TRUE.equals(settings.get("enabled"))
                    || !"PW-01-05".equals(settings.get("processCode"))
                    || !"PW-01 폐수처리공정 ★변경허가.xlsx".equals(settings.get("workbookFilename"))
                    || !"PW-01-05".equals(settings.get("sheetName"))
                    || !"C-PW15009".equals(settings.get("predecessorManagementNo"))
                    || !"C-PW15010".equals(settings.get("successorManagementNo"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "저장된 편집 계획의 명시적 TEST_ONLY 도면/연결 시나리오가 지원 범위와 다릅니다.");
            }
        }
    }

    private void validateFinalIntegratedWorkbook(byte[] bytes) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 8 || workbook.getSheet("PW-01-01") == null
                    || workbook.getSheet("PW-01-02") == null || workbook.getSheet("PW-01-03") == null
                    || workbook.getSheet("PW-01-04") == null || workbook.getSheet("PW-01-05") == null
                    || workbook.getSheet("PW-01-06") == null || workbook.getSheet("변경내역") == null
                    || workbook.getSheet("물질수지 검토") == null || workbook.getActiveSheetIndex() != 7) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "최종 통합 Excel 재열기 검사에서 8개 시트/활성 물질수지 시트를 확인하지 못했습니다.");
            }
            var history = workbook.getSheet("변경내역");
            var balance = workbook.getSheet("물질수지 검토");
            if (!new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(history.getRow(5).getCell(1))
                    .equals("C-PW15020") || !new org.apache.poi.ss.usermodel.DataFormatter()
                    .formatCellValue(balance.getRow(0).getCell(0)).contains("물질수지 검토 결과")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "최종 통합 Excel에 변경내역 또는 물질수지 검토 데이터가 누락되었습니다.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "최종 통합 Excel을 Apache POI로 다시 열지 못했습니다.", exception);
        }
    }

    private void requireActualFlowFieldsBlank(MasterEquipment equipment) {
        if (notBlank(equipment.wastewaterType()) || notBlank(equipment.wastewaterQuantityText())
                || notBlank(equipment.wastewaterDestination()) || notBlank(equipment.wasteType())
                || notBlank(equipment.wasteQuantityText()) || notBlank(equipment.wasteDestination())) {
            throw new IllegalArgumentException("Run-193 real wastewater/waste fields are not blank; do not infer or export synthetic values as actual data");
        }
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private Map<String, Object> readObject(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Stored plan JSON cannot be parsed", exception); }
    }

    private List<String> readStringList(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Stored review list cannot be parsed", exception); }
    }

    private void requireStoredPlan(Map<String, Object> storedPlan, long runId, long itemId) {
        if (storedPlan == null
                || number(storedPlan.get("changeRunId")) != runId
                || number(storedPlan.get("changeItemId")) != itemId
                || !"TEST_ONLY_READY".equals(storedPlan.get("planStatus"))
                || !Boolean.TRUE.equals(storedPlan.get("automaticEditAllowed"))
                || !"REVIEW_REQUIRED".equals(storedPlan.get("relationStatus"))) {
            throw new IllegalArgumentException("Persisted edit plan is not the run/item-matched TEST_ONLY_READY plan");
        }
        Object nested = storedPlan.get("plan");
        if (!(nested instanceof Map<?, ?> map)
                || !Boolean.FALSE.equals(map.get("officialApprovalCreated"))
                || !"TEST_ONLY_ARTIFACT_ONLY".equals(map.get("automaticEditScope"))) {
            throw new IllegalArgumentException("Persisted plan does not restrict output to a TEST_ONLY artifact");
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private MaterialBalanceRequest syntheticBalanceRequest(long runId, String managementNo) {
        String evidence = "가상 TEST_ONLY 시험값; 실제 계측자료, 이동처, 승인 관계 근거가 아님";
        return new MaterialBalanceRequest("TEST_ONLY-run" + runId + "-" + managementNo + "-virtual-balance",
                managementNo + " 명시적 선택 합성 시범값 — 실제 물질수지 아님", "TEST_ONLY",
                List.of(
                        new MaterialBalanceRequest.Node("C-PW15009", "TEST_ONLY 선행 설비 C-PW15009", null, null,
                                "SOURCE", new BigDecimal("100"), "WASTEWATER", "ton/day"),
                        new MaterialBalanceRequest.Node("C-PW15020", "TEST_ONLY 신규 설비 C-PW15020 / SCREEN FILTER",
                                null, null, "PROCESS", null, null, null),
                        new MaterialBalanceRequest.Node("C-PW15010", "TEST_ONLY 후행 설비 C-PW15010", null, null,
                                "PROCESS", null, null, null),
                        new MaterialBalanceRequest.Node("UNASSIGNED_WASTE_OUTPUT", "TEST_ONLY 폐기물 수신처 미지정",
                                null, null, "WASTE_SINK", null, null, null),
                        new MaterialBalanceRequest.Node("UNASSIGNED_WASTEWATER_RECEIVER", "TEST_ONLY 폐수 이동처 미지정",
                                null, null, "UNASSIGNED_RECEIVER", null, null, null)),
                List.of(
                        transfer("cpw15009-cpw15020-in", "C-PW15009", "C-PW15020", "DOWNSTREAM", "WASTEWATER", "100", evidence),
                        transfer("cpw15020-waste-0_5", "C-PW15020", "UNASSIGNED_WASTE_OUTPUT", "WASTE_GENERATION", "WASTE", "0.5", evidence),
                        transfer("cpw15020-unassigned-2_5", "C-PW15020", "UNASSIGNED_WASTEWATER_RECEIVER", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5", evidence),
                        transfer("cpw15020-cpw15010-out", "C-PW15020", "C-PW15010", "DOWNSTREAM", "WASTEWATER", "97", evidence)));
    }

    private MaterialBalanceRequest.Transfer transfer(String id, String source, String target,
                                                       String edgeType, String flowType, String quantity,
                                                       String evidence) {
        return new MaterialBalanceRequest.Transfer(id, "TEST_ONLY", null, source, target,
                edgeType, flowType, new BigDecimal(quantity), "ton/day", evidence);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record EnrichedWorkbook(byte[] bytes, String filename, String sha256,
                                   MaterialBalanceExcelOutputModel materialBalance,
                                   String materialBalanceWorksheetPart, String changeHistorySheetName,
                                   String sourcePlanSha256) { }
    public record StoredIntegratedWorkbook(byte[] bytes, String filename, String sha256,
                                           String scenarioKey, long planId, boolean reused) { }
}
