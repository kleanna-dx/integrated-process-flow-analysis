package com.example.processautomation.material;

import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import com.example.processautomation.excel.Pw0105EquipmentInsertionEngine;
import com.example.processautomation.model.MasterEquipment;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Three-step, TEST_ONLY workflow backed by immutable baseline and separately stored working copies. */
@Service
public class ProcessWorkspaceService {
    private static final String SUPPORTED_SHEET = "PW-01-05";
    private static final String SUPPORTED_PLACEMENT = "PW01-05-BETWEEN-C-PW15009-C-PW15010";
    private static final String SUPPORTED_SOURCE_ID = "C-PW15009";
    private static final String SUPPORTED_SOURCE_NAME = "ACTIVATED CARBON FILTER";
    private static final String SUPPORTED_TARGET_ID = "C-PW15010";
    private static final String SUPPORTED_TARGET_NAME = "FINAL EFFLUENT TANK";
    private static final String SUPPORTED_NUMERIC_CANDIDATE_VERSION = "PW01-OFFICIAL-NUMERIC-V1";
    private static final String OUTPUT_SUFFIX = "_TEST_ONLY-integrated.xlsx";
    private static final String BALANCE_LIMITATION = "CALCULATION_UNAVAILABLE: 현재 공식 CONFIRMED 연결이 없고, 사용자 입력의 배관 경로 설명만으로는 독립 연결 증빙·전체 범위·경계·단위/기간의 완결성을 검증할 수 없습니다. 유량은 입력값으로 기록하되 수지 계산에는 편입하지 않았습니다.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NumericWorkingCopyService workingCopies;
    private final Pw0105EquipmentInsertionEngine insertionEngine;
    private final OoxmlPackageInspector inspector;
    private final MaterialBalanceExcelExportService workbookExport;
    private final MaterialBalanceCalculator calculator;
    private final Step7ReviewLedgerWorkflowService reviewLedger;
    private final Step7ReviewNetworkWorkflowService reviewNetworks;
    private final Step7ReviewNetworkAdapter reviewNetworkAdapter;

    public ProcessWorkspaceService(JdbcTemplate jdbc, ObjectMapper mapper, NumericWorkingCopyService workingCopies,
                                   Pw0105EquipmentInsertionEngine insertionEngine, OoxmlPackageInspector inspector,
                                   MaterialBalanceExcelExportService workbookExport,
                                   MaterialBalanceCalculator calculator,
                                   Step7ReviewLedgerWorkflowService reviewLedger,
                                   Step7ReviewNetworkWorkflowService reviewNetworks,
                                   Step7ReviewNetworkAdapter reviewNetworkAdapter) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workingCopies = workingCopies;
        this.insertionEngine = insertionEngine;
        this.inspector = inspector;
        this.workbookExport = workbookExport;
        this.calculator = calculator;
        this.reviewLedger = reviewLedger;
        this.reviewNetworks = reviewNetworks;
        this.reviewNetworkAdapter = reviewNetworkAdapter;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> referenceCase() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeType", "ADD");
        result.put("managementNo", "");
        result.put("equipmentName", "");
        result.put("businessItemNo", "");
        result.put("processCode", "PW-01-05");
        result.put("capacity", "");
        result.put("capacityUnit", "");
        result.put("equipmentQuantity", "");
        result.put("equipmentTypeLabel", "");
        result.put("placementId", SUPPORTED_PLACEMENT);
        result.put("wastewaterType", "");
        result.put("wastewaterQuantity", "");
        result.put("wastewaterDestination", "");
        result.put("wasteType", "");
        result.put("wasteQuantity", "");
        result.put("wasteDestination", "");
        result.put("sourceStatus", "GENERIC_USER_ENTRY_TEMPLATE");
        result.put("note", "기존 run/item/plan이나 C-PW15020 입력값을 읽지 않습니다. 신규 요청은 사용자 입력으로 별도 저장됩니다.");
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listModifyTargets(long workingDocumentId, String sheetName) {
        NumericWorkingCopyService.WorkingCopy working = workingCopies.getWorkingCopy(workingDocumentId);
        if (!"PW-01".equals(working.processCode()) || !SUPPORTED_SHEET.equals(sheetName)
                || !isSafePw01Candidate(working)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "기존 설비 조회는 ID 36의 안전 PW-01 후보와 PW-01-05에 한정됩니다.");
        }
        List<Map<String, Object>> masterRows = jdbc.queryForList("""
                SELECT e.permit_management_no AS managementNo, e.facility_name AS facilityName,
                       e.business_item_no AS businessItemNo, e.capacity_text AS capacityText,
                       e.capacity_unit AS capacityUnit, e.quantity_text AS quantityText,
                       e.source_sheet_name AS sourceSheetName, e.source_row_number AS sourceRowNumber,
                       (SELECT COUNT(*) FROM equipment_record e2 WHERE e2.baseline_release_id=e.baseline_release_id
                         AND UPPER(TRIM(e2.permit_management_no))=UPPER(TRIM(e.permit_management_no))) AS masterIdCount
                FROM equipment_record e
                WHERE e.baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0' AND locked=TRUE)
                  AND e.middle_process_code='PW-01-05'
                ORDER BY e.master_order
                """);
        var inventory = inspector.inspect(working.bytes());
        var sheetInventory = inventory.sheets().stream()
                .filter(sheet -> SUPPORTED_SHEET.equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "PW-01-05 시트를 찾지 못했습니다."));
        Set<String> pw0105MasterIds = masterRows.stream().map(row -> objectString(row.get("managementNo")))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Map<String, Object>> results = new ArrayList<>();
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(working.bytes()))) {
            var worksheet = workbook.getSheet(sheetName);
            if (worksheet == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "PW-01-05 시트를 찾지 못했습니다.");
            for (Map<String, Object> master : masterRows) {
                String managementNo = String.valueOf(master.get("managementNo"));
                results.add(inspectModifyTargetEvidence(working, sheetName, worksheet, sheetInventory, master, managementNo, pw0105MasterIds));
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PW-01-05 작업본의 설비 매핑을 읽지 못했습니다.", exception);
        }
        return List.copyOf(results);
    }

    @Transactional
    public Map<String, Object> preview(ChangeInput request) {
        validateInput(request);
        NumericWorkingCopyService.WorkingCopy working = workingCopies.getWorkingCopy(request.workingDocumentId());
        String changeType = normalizeChangeType(request.changeType());
        List<String> blockers = new ArrayList<>();
        Map<String, Object> plan = new LinkedHashMap<>();
        boolean safeCandidate = isSafePw01Candidate(working);
        boolean supportedSheet = "PW-01".equals(working.processCode()) && SUPPORTED_SHEET.equals(request.sheetName());
        boolean supportedPosition = SUPPORTED_PLACEMENT.equals(request.placementId());
        boolean supportedAdd = "ADD".equals(changeType) && supportedSheet && safeCandidate && supportedPosition;
        String duplicateReason = "ADD".equals(changeType) ? duplicateEquipmentReason(request, null) : null;
        Map<String, Object> modifyTarget = null;
        if (!List.of("ADD", "MODIFY").contains(changeType)) {
            blockers.add("현재 Excel 생성은 설비 추가만 지원합니다. 삭제 요청은 차단됩니다.");
        }
        if (!supportedSheet) blockers.add("현재 검증된 도면 범위는 PW-01 / PW-01-05뿐입니다.");
        if (!safeCandidate) blockers.add("선택 작업본이 공식 PW-01 안전 숫자형 후보 버전이 아닙니다. 안전 후보 ID 36의 형식과 source hash를 확인하세요.");
        if ("ADD".equals(changeType) && !supportedPosition) {
            blockers.add("선택한 위치는 미지원입니다. 현재 자동 배치는 C-PW15009와 C-PW15010 사이의 검증된 PW-01-05 위치만 허용합니다.");
        }
        if (duplicateReason != null) blockers.add(duplicateReason);
        if ("MODIFY".equals(changeType)) {
            String targetNo = blank(request.targetManagementNo()) ? request.managementNo() : request.targetManagementNo();
            modifyTarget = inspectModifyTargetById(working, request.sheetName(), targetNo);
            @SuppressWarnings("unchecked")
            List<String> targetBlockers = (List<String>) modifyTarget.get("reviewRequiredItems");
            blockers.addAll(targetBlockers);
            if (modifyTarget.get("diagramValues") instanceof Map<?, ?> values) {
                @SuppressWarnings("unchecked")
                Map<String, String> diagramValues = (Map<String, String>) values;
                boolean sameId = normalizeEquipmentValue(diagramValues.get("managementNo")).equals(normalizeEquipmentValue(request.managementNo()));
                boolean sameName = normalizeEquipmentValue(diagramValues.get("equipmentName")).equals(normalizeEquipmentValue(request.equipmentName()));
                boolean sameItem = normalizeEquipmentValue(diagramValues.get("businessItemNo")).equals(normalizeEquipmentValue(request.businessItemNo()));
                boolean sameCapacity = normalizeEquipmentValue(diagramValues.get("capacity")).equals(normalizeEquipmentValue(safe(request.capacity()) + safe(request.capacityUnit())));
                boolean sameQuantity = normalizeEquipmentValue(diagramValues.get("quantity")).equals(normalizeEquipmentValue(request.equipmentQuantity()));
                if (!sameCapacity && blank(request.capacityUnit())) {
                    blockers.add("용량을 변경할 때는 도면 표시 단위를 확인해 함께 입력해야 합니다.");
                }
                if (sameId && sameName && sameItem && sameCapacity && sameQuantity) {
                    blockers.add("수정 입력이 기존 도면 표시값과 모두 같아 변경할 필드가 없습니다.");
                }
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> fieldAssessments = (Map<String, Map<String, Object>>) modifyTarget.getOrDefault("fieldAssessments", Map.of());
                Map<String, String> requestedValues = Map.of(
                        "managementNo", safe(request.managementNo()),
                        "equipmentName", safe(request.equipmentName()),
                        "businessItemNo", safe(request.businessItemNo()),
                        "capacity", safe(request.capacity()) + safe(request.capacityUnit()),
                        "quantity", safe(request.equipmentQuantity()));
                Map<String, Boolean> fieldChanges = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : requestedValues.entrySet()) {
                    boolean changed = !normalizeEquipmentValue(diagramValues.get(entry.getKey()) == null
                            ? "" : diagramValues.get(entry.getKey())).equals(normalizeEquipmentValue(entry.getValue()));
                    fieldChanges.put(entry.getKey(), changed);
                    Map<String, Object> assessment = fieldAssessments.get(entry.getKey());
                    if (changed && (assessment == null || !Boolean.TRUE.equals(assessment.get("modifiable")))) {
                        blockers.add(entry.getKey() + " 필드는 셀 매핑 근거가 부족해 수정할 수 없습니다.");
                    }
                }
                modifyTarget.put("requestedFieldChanges", fieldChanges);
                if (Boolean.TRUE.equals(fieldChanges.get("managementNo"))) {
                    String idConflict = modifyManagementNoConflict(request.managementNo(), targetNo);
                    if (idConflict != null) blockers.add(idConflict);
                }
            }
        }

        plan.put("mode", "TEST_ONLY");
        plan.put("officialBaselineModified", false);
        plan.put("workingDocumentId", working.id());
        plan.put("workingSha256", working.workingSha256());
        plan.put("workingConversionVersion", working.conversionVersion());
        plan.put("workingDisposition", "SAFE_CANDIDATE_NOT_APPROVED_BASELINE");
        plan.put("processCode", working.processCode());
        plan.put("sheetName", request.sheetName());
        plan.put("changeType", changeType);
        plan.put("equipmentManagementNo", safe(request.managementNo()));
        plan.put("placementId", safe(request.placementId()));
        plan.put("beforeSummary", "공식 V1.0 및 설비 마스터는 읽기 전용; 별도 안전 후보 작업본만 검토합니다.");
        plan.put("afterSummary", afterSummary(request));
        if (modifyTarget != null) plan.put("modifyTargetAssessment", modifyTarget);
        plan.put("balanceStatus", "CALCULATION_UNAVAILABLE");
        plan.put("actualNetworkReadiness", actualNetworkReadiness());
        plan.put("syntheticTestOnlyCalculation", syntheticNetworkCalculation(request));
        plan.put("relationStatus", "REVIEW_REQUIRED");
        plan.put("officialApprovalCreated", false);
        plan.put("reviewRequiredItems", blockers);

        boolean supportedModify = "MODIFY".equals(changeType) && supportedSheet && safeCandidate
                && modifyTarget != null && Boolean.TRUE.equals(modifyTarget.get("eligible"))
                && blockers.isEmpty();
        if ("MODIFY".equals(changeType) && !supportedModify && blockers.isEmpty()) {
            blockers.add("수정 대상의 master·diagram·셀·DrawingML 검증이 완료되지 않아 생성하지 않습니다.");
        }
        if (supportedAdd && duplicateReason == null) {
            try {
                var enginePlan = insertionEngine.analyze(working.bytes(), editSpec(request));
                Map<String, Object> insertion = new LinkedHashMap<>();
                insertion.put("plannedCellRange", enginePlan.source().topLeftCell() + ":" + enginePlan.target().bottomRightCell());
                insertion.put("candidateCellRange", enginePlan.source().topLeftCell().replaceAll("[0-9]+$", "")
                        + enginePlan.candidateFirstRow() + ":" + enginePlan.target().bottomRightCell().replaceAll("[0-9]+$", "")
                        + enginePlan.candidateLastRow());
                insertion.put("insertBeforeRow", enginePlan.insertionBeforeRow());
                insertion.put("rowsInserted", enginePlan.insertedRows());
                insertion.put("predecessor", enginePlan.source().managementNo());
                insertion.put("successor", enginePlan.target().managementNo());
                insertion.put("connectorShapeId", enginePlan.existingConnector().shapeId());
                insertion.put("connectorName", enginePlan.existingConnector().name());
                insertion.put("connectorBindingPresent", enginePlan.existingConnector().hasShapeBindings());
                insertion.put("placementVerified", true);
                plan.put("insertion", insertion);
                plan.put("editPlanStatus", "TEST_ONLY_READY");
                plan.put("automaticEditAllowed", true);
            } catch (RuntimeException exception) {
                blockers.add("검증된 위치 분석 실패; 임의 배치는 하지 않습니다: " + exception.getMessage());
                plan.put("editPlanStatus", "REVIEW_REQUIRED");
                plan.put("automaticEditAllowed", false);
            }
        } else if (supportedModify) {
            plan.put("modifyTarget", modifyTarget);
            plan.put("editPlanStatus", "TEST_ONLY_READY");
            plan.put("automaticEditAllowed", true);
        } else {
            plan.put("editPlanStatus", "GENERATION_BLOCKED");
            plan.put("automaticEditAllowed", false);
        }
        plan.put("inputCoverage", inputCoverage(request));
        plan.put("reviewRequiredItems", blockers);
        String status = Boolean.TRUE.equals(plan.get("automaticEditAllowed")) ? "PREVIEWED" : "GENERATION_BLOCKED";
        long requestId = insertRequest(working, request, plan, status);
        Map<String, Object> response = new LinkedHashMap<>(plan);
        response.put("requestId", requestId);
        response.put("requestStatus", status);
        response.put("outputAvailable", false);
        return response;
    }

    @Transactional
    public Map<String, Object> generate(long requestId) {
        Map<String, Object> stored = loadRequest(requestId, false);
        if (!"PREVIEWED".equals(stored.get("requestStatus"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "미리보기에서 허용된 PW-01-05 TEST_ONLY 추가/수정만 생성할 수 있습니다.");
        }
        if (stored.get("outputContent") instanceof byte[] content && content.length > 0) {
            String hash = String.valueOf(stored.get("outputSha256"));
            if (!hash.equalsIgnoreCase(sha256(content))) throw new ResponseStatusException(HttpStatus.CONFLICT, "저장 Excel SHA-256이 일치하지 않습니다.");
            return outputMetadata(stored, content, true);
        }
        ChangeInput input = readRequest(String.valueOf(stored.get("requestJson")));
        validateInput(input);
        NumericWorkingCopyService.WorkingCopy working = workingCopies.getWorkingCopy(((Number) stored.get("workingDocumentId")).longValue());
        if (!working.workingSha256().equalsIgnoreCase(String.valueOf(stored.get("workingSha256")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "미리보기 이후 숫자형 작업본이 바뀌었습니다. 다시 미리보기를 실행하세요.");
        }
        if (!isSafePw01Candidate(working) || !SUPPORTED_PLACEMENT.equals(input.placementId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "안전 후보 또는 검증된 삽입 위치가 달라져 생성을 차단했습니다.");
        }
        String changeType = normalizeChangeType(String.valueOf(stored.get("changeType")));
        if ("MODIFY".equals(changeType)) {
            String targetNo = blank(input.targetManagementNo()) ? input.managementNo() : input.targetManagementNo();
            Map<String, Object> currentTarget = inspectModifyTargetById(working, input.sheetName(), targetNo);
            if (!Boolean.TRUE.equals(currentTarget.get("eligible"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "생성 시 기존 설비 마스터·셀·도면 매핑 재검증에 실패했습니다.");
            }
            String idConflict = modifyManagementNoConflict(input.managementNo(), targetNo);
            if (idConflict != null) throw new ResponseStatusException(HttpStatus.CONFLICT, idConflict);
        }
        if ("ADD".equals(changeType)) {
            String duplicateReason = duplicateEquipmentReason(input, requestId);
            if (duplicateReason != null) throw new ResponseStatusException(HttpStatus.CONFLICT, duplicateReason);
        }
        Map<String, Object> storedPlan = jsonObject(stored.get("planJson"));
        byte[] editedDiagram;
        List<List<?>> comparisonRows;
        List<List<?>> drawingDiffRows;
        String diagramFocusCell = "AI198";
        if ("ADD".equals(changeType)) {
            MasterEquipment equipment = masterEquipment(input);
            Pw0105EquipmentInsertionEngine.EditResult edit = insertionEngine.insertTestOnly(working.bytes(), equipment, editSpec(input));
            editedDiagram = edit.workbookBytes();
            Map<String, Object> insertion = jsonObject(storedPlan.get("insertion"));
            String insertionRange = String.valueOf(insertion.getOrDefault("candidateCellRange", "검증 엔진 산출 범위"));
            int separator = insertionRange.indexOf(':');
            if (separator > 0) diagramFocusCell = insertionRange.substring(0, separator).replace("$", "");
            comparisonRows = addComparisonRows(input, insertionRange);
            drawingDiffRows = drawingDiffRows(working.bytes(), editedDiagram, edit.plan());
        } else if ("MODIFY".equals(changeType)) {
            Map<String, Object> target = jsonObject(storedPlan.get("modifyTarget"));
            if (!Boolean.TRUE.equals(target.get("eligible"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "미리보기의 기존 설비 매핑이 더 이상 검증되지 않습니다.");
            }
            @SuppressWarnings("unchecked")
            Map<String, String> beforeValues = (Map<String, String>) target.get("diagramValues");
            @SuppressWarnings("unchecked")
            Map<String, Boolean> fieldChanges = (Map<String, Boolean>) target.getOrDefault("requestedFieldChanges", Map.of());
            Map<String, String> requestedValues = Map.of(
                    "managementNo", safe(input.managementNo()),
                    "equipmentName", safe(input.equipmentName()),
                    "businessItemNo", safe(input.businessItemNo()),
                    "capacity", safe(input.capacity()) + safe(input.capacityUnit()),
                    "quantity", safe(input.equipmentQuantity()));
            Map<String, String> replacements = new LinkedHashMap<>();
            fieldChanges.forEach((field, changed) -> {
                if (Boolean.TRUE.equals(changed)) replacements.put(field, requestedValues.get(field));
            });
            if (replacements.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "변경할 검증 셀이 없습니다.");
            var edit = insertionEngine.modifyExistingEquipmentText(working.bytes(), input.sheetName(),
                    blank(input.targetManagementNo()) ? input.managementNo() : input.targetManagementNo(),
                    String.valueOf(target.get("masterEquipmentName")), beforeValues, replacements);
            editedDiagram = edit.workbookBytes();
            comparisonRows = modifyComparisonRows(input, edit.box().topLeftCell(), edit.beforeValues(), edit.afterValues(),
                    edit.changedCells());
            drawingDiffRows = modifyDrawingDiffRows(working.bytes(), editedDiagram, edit.changedCells(), target);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "삭제 작업은 지원되지 않습니다.");
        }
        String editedDiagramHash = sha256(editedDiagram);
        List<String> reviewItems = inputCoverage(input);
        byte[] output = workbookExport.appendTableWorksheet(editedDiagram, "변경 전후",
                "변경 전후 — PW-01-05 TEST_ONLY",
                "공식 원본은 변경하지 않았습니다. 변경은 별도 TEST_ONLY 작업본에만 기록합니다.",
                List.of("항목", "변경 전", "변경 후", "판정/주의"), comparisonRows);
        output = workbookExport.appendTableWorksheet(output, "도면 객체 비교",
                "TEST_ONLY 도면 변경 전후 — PW-01-05",
                "V1.0 원본 → 별도 숫자형 작업본을 기준으로 편집했습니다. 기존/신규 DrawingML 객체와 신규 설비 셀 값을 기록합니다. 연결선은 시각 객체이며 논리 관계 승인을 뜻하지 않습니다.",
                List.of("구분", "객체 ID", "객체명/셀", "변경 전", "변경 후", "객체 유형/검토"), drawingDiffRows);
        output = workbookExport.appendTableWorksheet(output, "사용자 입력사항",
                "PW-01-05 사용자 입력 변경사항 — TEST_ONLY",
                "직접 입력한 값입니다. 물리 연결 증명·공식 승인·수지 계산 결과로 해석하지 마십시오.",
                List.of("분류", "항목", "입력값", "단위", "기준 기간", "이동처/경로", "증빙 참조", "검토 상태"), inputRows(input));
        Map<String, Object> syntheticCalculation = jsonObject(storedPlan.get("syntheticTestOnlyCalculation"));
        Map<String, Object> actualReadiness = jsonObject(storedPlan.get("actualNetworkReadiness"));
        String reason = BALANCE_LIMITATION;
        List<List<?>> balanceRows = new ArrayList<>();
        balanceRows.add(List.of("물질수지 구분", "상태", "경로 수/결과", "제한"));
        balanceRows.add(List.of("실제 PW-01 승인 연결망", String.valueOf(actualReadiness.getOrDefault("status", "CALCULATION_UNAVAILABLE")),
                actualReadiness.getOrDefault("eligibleConnectionCount", 0), reason));
        Object actualIssues = actualReadiness.get("issues");
        String missingEvidence = actualIssues instanceof List<?> issues && !issues.isEmpty()
                ? issues.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("; "))
                : "확인된 전체 연결 증빙·경계·단위·기간 자료를 제출해야 합니다.";
        balanceRows.add(List.of("실제 계산 부족 증빙", "CALCULATION_UNAVAILABLE",
                actualIssues instanceof List<?> issues ? issues.size() : 0, missingEvidence));
        balanceRows.add(List.of("합성 TEST_ONLY 연결망", String.valueOf(syntheticCalculation.getOrDefault("status", "NOT_REQUESTED")),
                String.valueOf(syntheticCalculation.getOrDefault("calculationAvailable", false)),
                "사용자 입력 fixture만 Step 7-4B Adapter와 Step 7-1 solver로 평가; 실제 공정 결과 아님"));
        balanceRows.add(List.of("실제 도면 유량 표기", "미반영", "0 셀 변경", "증빙/기간/단위 확인 없이 물량이나 화살표를 생성하지 않음"));
        balanceRows.add(List.of("공식 자료/승인", "미변경", "0 승인", "V1.0 원본과 master는 read-only"));
        output = workbookExport.appendTableWorksheet(output, "수지 검토 상태",
                "실제/합성 TEST_ONLY 물질수지 검토",
                "실제 연결은 최신 Step 7 검토 증거로만 평가합니다. 사용자 합성 fixture는 TEST_ONLY로 분리하며 도면 유량 표기에 반영하지 않습니다.",
                List.of("항목", "상태/값", "경로 수/근거", "제한"), balanceRows);
        if ("ADD".equals(changeType)) {
            output = workbookExport.focusWorksheet(output, input.sheetName(), diagramFocusCell, 90);
        }
        String outputHash = sha256(output);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("requestId", requestId);
        audit.put("sourceOfficialSha256", working.sourceSha256());
        audit.put("sourceWorkingSha256", working.workingSha256());
        audit.put("editedDiagramSha256", editedDiagramHash);
        audit.put("outputSha256", outputHash);
        audit.put("drawingStatus", "ADD".equals(changeType)
                ? "TEST_ONLY equipment box and connector only; no review-status callout or decorative DrawingML was added."
                : "MODIFY changed only the uniquely verified worksheet value cells; DrawingML bytes remain identical.");
        audit.put("drawingDiffRows", drawingDiffRows.size());
        audit.put("changeType", changeType);
        audit.put("placementId", input.placementId());
        audit.put("equipmentManagementNo", input.managementNo());
        audit.put("balanceStatus", "CALCULATION_UNAVAILABLE");
        audit.put("balanceReason", reason);
        audit.put("actualRoutesAdmitted", actualReadiness.getOrDefault("eligibleConnectionCount", 0));
        audit.put("syntheticTestOnlyCalculation", syntheticCalculation);
        audit.put("reviewRequiredItems", reviewItems);
        String auditJson = writeJson(audit);
        String filename = "PW-01-05_" + filenameToken(input.managementNo()) + "_" + changeType + "_request-" + requestId + OUTPUT_SUFFIX;
        jdbc.update("""
                UPDATE process_change_request
                SET request_status='GENERATED', calculation_status='CALCULATION_UNAVAILABLE',
                    output_filename=?, output_sha256=?, output_content=?, plan_json=?, generated_at=?
                WHERE id=? AND request_status='PREVIEWED'
                """, filename, outputHash, output, auditJson, Timestamp.from(Instant.now()), requestId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("requestStatus", "GENERATED");
        result.put("filename", filename);
        result.put("sha256", outputHash);
        result.put("sourceOfficialSha256", working.sourceSha256());
        result.put("sourceWorkingSha256", working.workingSha256());
        result.put("editedDiagramSha256", editedDiagramHash);
        result.put("drawingEditApplied", true);
        result.put("balanceStatus", "CALCULATION_UNAVAILABLE");
        result.put("balanceReason", reason);
        result.put("officialBaselineModified", false);
        result.put("officialApprovalCreated", false);
        result.put("sheetCount", countSheets(output));
        result.put("drawingDiffRows", drawingDiffRows.size());
        result.put("audit", audit);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> request(long requestId) {
        Map<String, Object> row = loadRequest(requestId, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("requestStatus", row.get("requestStatus"));
        result.put("calculationStatus", row.get("calculationStatus"));
        result.put("processCode", row.get("processCode"));
        result.put("sheetName", row.get("sheetName"));
        result.put("changeType", row.get("changeType"));
        result.put("equipmentManagementNo", row.get("equipmentManagementNo"));
        result.put("request", jsonObject(row.get("requestJson")));
        result.put("planOrAudit", jsonObject(row.get("planJson")));
        result.put("filename", row.get("outputFilename"));
        result.put("sha256", row.get("outputSha256"));
        result.put("outputAvailable", row.get("outputContent") instanceof byte[] bytes && bytes.length > 0);
        return result;
    }

    @Transactional(readOnly = true)
    public GeneratedFile download(long requestId) {
        Map<String, Object> row = loadRequest(requestId, false);
        byte[] content = row.get("outputContent") instanceof byte[] bytes ? bytes : null;
        if (content == null || content.length == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "생성된 Excel이 없습니다.");
        String hash = String.valueOf(row.get("outputSha256"));
        if (!hash.equalsIgnoreCase(sha256(content))) throw new ResponseStatusException(HttpStatus.CONFLICT, "다운로드 결과 SHA-256이 맞지 않습니다.");
        return new GeneratedFile(String.valueOf(row.get("outputFilename")), hash, content);
    }

    private List<List<?>> drawingDiffRows(byte[] beforeBytes, byte[] afterBytes,
                                              Pw0105EquipmentInsertionEngine.Plan plan) {
        var beforeInventory = inspector.inspect(beforeBytes).sheets().stream()
                .filter(sheet -> plan.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
        var afterInventory = inspector.inspect(afterBytes).sheets().stream()
                .filter(sheet -> plan.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow();
        Map<String, com.example.processautomation.excel.WorkbookInventory.DrawingObject> before = new LinkedHashMap<>();
        Map<String, com.example.processautomation.excel.WorkbookInventory.DrawingObject> after = new LinkedHashMap<>();
        beforeInventory.drawingObjects().forEach(object -> before.put(object.shapeId(), object));
        afterInventory.drawingObjects().forEach(object -> after.put(object.shapeId(), object));
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("객체 수", "—", "PW-01-05 DrawingML", Integer.toString(before.size()),
                Integer.toString(after.size()), "TEST_ONLY 출력에서 보존된 기존 객체 + 신규 객체"));
        for (var entry : after.entrySet()) {
            String id = entry.getKey();
            var current = entry.getValue();
            var previous = before.get(id);
            if (previous == null) {
                rows.add(List.of("신규 객체", safe(id), safe(current.objectName()), "없음", anchor(current),
                        safe(current.objectType()) + "; arrow=" + safe(current.tailArrowType()) + "; 논리 연결 미승인"));
            } else if (!sameAnchor(previous, current) || !Objects.equals(previous.textContent(), current.textContent())) {
                rows.add(List.of("변경 객체", safe(id), safe(current.objectName()), anchor(previous), anchor(current),
                        safe(current.objectType()) + "; 기존 object identity 유지"));
            }
        }
        for (var entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                rows.add(List.of("삭제 객체", safe(entry.getKey()), safe(entry.getValue().objectName()),
                        anchor(entry.getValue()), "없음", safe(entry.getValue().objectType())));
            }
        }
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(afterBytes))) {
            var sheet = workbook.getSheet(plan.sheetName());
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            for (int rowNumber = plan.candidateFirstRow(); rowNumber <= plan.candidateLastRow(); rowNumber++) {
                var row = sheet.getRow(rowNumber - 1);
                if (row == null) continue;
                for (int column = plan.firstColumn(); column <= plan.lastColumn(); column++) {
                    var cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null || formatter.formatCellValue(cell).isBlank()) continue;
                    rows.add(List.of("신규 셀 값", "—", cell.getAddress().formatAsString(), "삽입 전 공란/기존 내용",
                            formatter.formatCellValue(cell), "styleId=" + cell.getCellStyle().getIndex()));
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create the PW-01-05 drawing diff evidence", exception);
        }
        return List.copyOf(rows);
    }

    private List<List<?>> addComparisonRows(ChangeInput input, String insertionRange) {
        return List.of(
                List.of("변경 유형", "공식 V1.0", "TEST_ONLY 후보", "판정/주의"),
                List.of("설비번호", "기존 마스터 검색", input.managementNo(), "합성/사용자 입력; 마스터 미등록"),
                List.of("설비명", "기존 도면 미수정", safe(input.equipmentName()), "사용자 입력"),
                List.of("Item No.", "기존 도면 미수정", safe(input.businessItemNo()), "사용자 입력"),
                List.of("설비 용량", "기존 도면 미수정", safe(input.capacity()) + safe(input.capacityUnit()), "물질수지 유량과 구분"),
                List.of("설비 수량", "기존 도면 미수정", safe(input.equipmentQuantity()), "사용자 입력"),
                List.of("삽입 위치", "기존 도면 미수정", insertionRange, "검증된 C-PW15009와 C-PW15010 사이만"),
                List.of("인접 표시", "기존 설비·화살표 보존", safe(input.managementNo()), "시각 connector 복제; 실제 관계 미승인"),
                List.of("폐수·폐기물·배관 값", "공식 유량 미변경", Integer.toString(input.routes() == null ? 0 : input.routes().size()), "별도 사용자 입력 시트; 계산/도면 라벨 미반영"));
    }

    private List<List<?>> modifyComparisonRows(ChangeInput input, String topLeftCell,
            Map<String, String> beforeValues, Map<String, String> afterValues, Map<String, String> changedCells) {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("변경 유형", "변경 전 안전 후보", "변경 후 TEST_ONLY", "판정/주의"));
        rows.add(List.of("설비번호", safe(beforeValues.get("managementNo")), safe(afterValues.get("managementNo")),
                Objects.equals(beforeValues.get("managementNo"), afterValues.get("managementNo")) ? "값 동일" : "검증 셀 값 수정"));
        Map<String, String> labels = Map.of("equipmentName", "설비명", "businessItemNo", "Item No.",
                "capacity", "용량·단위", "quantity", "설비 수량");
        for (String field : List.of("equipmentName", "businessItemNo", "capacity", "quantity")) {
            String cell = cellAt(topLeftCell, switch (field) {
                case "equipmentName" -> 3;
                case "businessItemNo" -> 4;
                case "capacity" -> 5;
                default -> 6;
            });
            rows.add(List.of(labels.get(field), safe(beforeValues.get(field)), safe(afterValues.get(field)),
                    cell + (changedCells.containsKey(cell) ? " 수정" : " 값 동일")));
        }
        rows.add(List.of("폐수·폐기물·배관 입력", "기존 도면/수지 불변", Integer.toString(input.routes() == null ? 0 : input.routes().size()),
                "별도 사용자 입력; 유량/화살표와 실제 물질수지에 반영하지 않음"));
        return List.copyOf(rows);
    }

    private List<List<?>> modifyDrawingDiffRows(byte[] beforeBytes, byte[] afterBytes,
            Map<String, String> changedCells, Map<String, Object> targetAssessment) {
        var beforeSheet = inspector.inspect(beforeBytes).sheets().stream()
                .filter(sheet -> SUPPORTED_SHEET.equals(sheet.sheetName())).findFirst().orElseThrow();
        var afterSheet = inspector.inspect(afterBytes).sheets().stream()
                .filter(sheet -> SUPPORTED_SHEET.equals(sheet.sheetName())).findFirst().orElseThrow();
        if (!Objects.equals(beforeSheet.drawingPartName(), afterSheet.drawingPartName())
                || !beforeSheet.drawingObjects().equals(afterSheet.drawingObjects())) {
            throw new IllegalStateException("MODIFY attempted to change existing DrawingML inventory");
        }
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("DrawingML 보존", "—", String.valueOf(targetAssessment.get("drawingPartName")),
                Integer.toString(beforeSheet.drawingObjects().size()), Integer.toString(afterSheet.drawingObjects().size()),
                "기존 도형·connector·arrow·anchor·binding 변경 0; 대상 sheet drawing part byte-identical"));
        for (Map.Entry<String, String> cell : changedCells.entrySet()) {
            rows.add(List.of("변경된 셀", "—", cell.getKey(), "기존 장비 box 셀", cell.getValue(),
                    "DrawingML 변경 없음; 실제 연결/물질수지 변경을 의미하지 않음"));
        }
        return List.copyOf(rows);
    }

    private boolean sameAnchor(com.example.processautomation.excel.WorkbookInventory.DrawingObject first,
                               com.example.processautomation.excel.WorkbookInventory.DrawingObject second) {
        return Objects.equals(first.startColumn(), second.startColumn())
                && Objects.equals(first.startRow(), second.startRow())
                && Objects.equals(first.startColumnOffset(), second.startColumnOffset())
                && Objects.equals(first.startRowOffset(), second.startRowOffset())
                && Objects.equals(first.endColumn(), second.endColumn())
                && Objects.equals(first.endRow(), second.endRow())
                && Objects.equals(first.endColumnOffset(), second.endColumnOffset())
                && Objects.equals(first.endRowOffset(), second.endRowOffset());
    }

    private String anchor(com.example.processautomation.excel.WorkbookInventory.DrawingObject object) {
        if (object.startColumn() == null || object.startRow() == null) return "anchor 미상";
        String start = new org.apache.poi.ss.util.CellReference(object.startRow(), object.startColumn()).formatAsString();
        if (object.endColumn() == null || object.endRow() == null) return start;
        String end = new org.apache.poi.ss.util.CellReference(object.endRow(), object.endColumn()).formatAsString();
        return start + " → " + end;
    }

    private long insertRequest(NumericWorkingCopyService.WorkingCopy working, ChangeInput input,
                               Map<String, Object> plan, String status) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO process_change_request(working_document_id, process_code, sheet_name, change_type,
                        equipment_management_no, request_json, plan_json, request_status, calculation_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CALCULATION_UNAVAILABLE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, working.id());
            statement.setString(2, working.processCode());
            statement.setString(3, input.sheetName());
            statement.setString(4, normalizeChangeType(input.changeType()));
            statement.setString(5, input.managementNo());
            statement.setString(6, writeJson(input));
            statement.setString(7, writeJson(plan));
            statement.setString(8, status);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("MariaDB did not return a process change request ID");
        return key.longValue();
    }

    private Map<String, Object> loadRequest(long requestId, boolean metadataOnly) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, r.working_document_id AS workingDocumentId, r.process_code AS processCode,
                       r.sheet_name AS sheetName, r.change_type AS changeType,
                       r.equipment_management_no AS equipmentManagementNo, r.request_json AS requestJson,
                       r.plan_json AS planJson, r.request_status AS requestStatus,
                       r.calculation_status AS calculationStatus, r.output_filename AS outputFilename,
                       r.output_sha256 AS outputSha256, r.output_content AS outputContent,
                       w.working_sha256 AS workingSha256
                FROM process_change_request r JOIN process_working_document w ON w.id=r.working_document_id
                WHERE r.id=?
                """, requestId);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "변경 요청을 찾지 못했습니다.");
        return rows.get(0);
    }

    private int inputRouteCount(ChangeInput input) {
        return input.routes() == null ? 0 : (int) input.routes().stream().filter(route -> route.quantity() != null).count();
    }

    private Map<String, Object> inspectModifyTargetById(NumericWorkingCopyService.WorkingCopy working,
                                                          String sheetName, String managementNo) {
        List<Map<String, Object>> masterRows = jdbc.queryForList("""
                SELECT e.permit_management_no AS managementNo, e.facility_name AS facilityName,
                       e.business_item_no AS businessItemNo, e.capacity_text AS capacityText,
                       e.capacity_unit AS capacityUnit, e.quantity_text AS quantityText,
                       e.source_sheet_name AS sourceSheetName, e.source_row_number AS sourceRowNumber,
                       (SELECT COUNT(*) FROM equipment_record e2 WHERE e2.baseline_release_id=e.baseline_release_id
                         AND UPPER(TRIM(e2.permit_management_no))=UPPER(TRIM(e.permit_management_no))) AS masterIdCount
                FROM equipment_record e
                WHERE e.baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0' AND locked=TRUE)
                  AND e.middle_process_code='PW-01-05'
                  AND UPPER(TRIM(e.permit_management_no))=UPPER(TRIM(?))
                """, managementNo);
        if (masterRows.isEmpty()) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("managementNo", managementNo);
            missing.put("eligible", false);
            missing.put("reviewRequiredItems", List.of("잠긴 V1.0 마스터의 PW-01-05 설비번호와 일치하지 않습니다."));
            return missing;
        }
        var sheetInventory = inspector.inspect(working.bytes()).sheets().stream()
                .filter(sheet -> sheetName.equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "요청 시트를 찾지 못했습니다."));
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(working.bytes()))) {
            var worksheet = workbook.getSheet(sheetName);
            if (worksheet == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "요청 시트를 찾지 못했습니다.");
            return inspectModifyTargetEvidence(working, sheetName, worksheet, sheetInventory, masterRows.get(0), managementNo,
                    pw0105MasterIds());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PW-01-05 설비 매핑을 읽지 못했습니다.", exception);
        }
    }

    private Map<String, Object> inspectModifyTarget(NumericWorkingCopyService.WorkingCopy working,
            String sheetName, org.apache.poi.ss.usermodel.Sheet sheet,
            WorkbookInventory.SheetInventory sheetInventory, Map<String, Object> master, String managementNo) {
        List<String> blockers = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        Pw0105EquipmentInsertionEngine.EquipmentBox targetBox = null;
        result.put("managementNo", managementNo);
        result.put("masterEquipmentName", objectString(master.get("facilityName")));
        result.put("masterItemNo", objectString(master.get("businessItemNo")));
        result.put("masterCapacityValue", objectString(master.get("capacityText")));
        result.put("masterCapacityUnit", objectString(master.get("capacityUnit")));
        result.put("masterCapacity", displayCapacity(objectString(master.get("capacityText")),
                objectString(master.get("capacityUnit"))));
        result.put("masterQuantity", displayQuantity(objectString(master.get("quantityText"))));
        result.put("masterSourceSheet", master.get("sourceSheetName"));
        result.put("masterSourceRow", master.get("sourceRowNumber"));
        if (((Number) master.getOrDefault("masterIdCount", 0)).intValue() != 1) {
            blockers.add("잠긴 PW-01-05 설비 마스터에서 관리번호가 유일하지 않습니다.");
        }
        if (!SUPPORTED_SHEET.equals(sheetName) || !"PW-01".equals(working.processCode()) || !isSafePw01Candidate(working)) {
            blockers.add("대상 공정·시트·작업본이 허용 범위와 일치하지 않습니다.");
        }
        try {
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            List<String> occurrences = new ArrayList<>();
            for (var row : sheet) for (var cell : row) {
                if (managementNo.equals(formatter.formatCellValue(cell).trim())) {
                    occurrences.add(cell.getAddress().formatAsString());
                }
            }
            result.put("diagramIdCells", List.copyOf(occurrences));
            targetBox = insertionEngine.locateExistingBox(sheet, sheetName, managementNo,
                    String.valueOf(master.get("facilityName")));
            result.put("equipmentBoxIdCell", targetBox.topLeftCell());
            result.put("cellRange", targetBox.topLeftCell() + ":" + targetBox.bottomRightCell());
            String equipmentBoxIdCell = targetBox.topLeftCell();
            List<String> otherIdReferences = occurrences.stream().filter(address -> !address.equals(equipmentBoxIdCell)).toList();
            result.put("otherDiagramIdReferences", otherIdReferences);
            if (!otherIdReferences.isEmpty()) blockers.add("설비번호가 장비 상자 외 도면 위치에도 표시되어 있습니다: "
                    + String.join(", ", otherIdReferences) + ". 다른 표기를 함께 변경해야 하는지 담당자 확인이 필요합니다.");
            result.put("fieldCells", Map.of(
                    "managementNo", targetBox.topLeftCell(),
                    "equipmentName", cellAt(targetBox.topLeftCell(), 3),
                    "businessItemNo", cellAt(targetBox.topLeftCell(), 4),
                    "capacity", cellAt(targetBox.topLeftCell(), 5),
                    "quantity", cellAt(targetBox.topLeftCell(), 6)));
            int baseRow = new org.apache.poi.ss.util.CellReference(targetBox.topLeftCell()).getRow();
            int col = new org.apache.poi.ss.util.CellReference(targetBox.topLeftCell()).getCol();
            Map<String, String> diagramValues = new LinkedHashMap<>();
            diagramValues.put("managementNo", formatter.formatCellValue(sheet.getRow(baseRow).getCell(col)));
            diagramValues.put("equipmentTypeLabel", formatter.formatCellValue(sheet.getRow(baseRow + 2).getCell(col)));
            diagramValues.put("equipmentName", formatter.formatCellValue(sheet.getRow(baseRow + 3).getCell(col)));
            diagramValues.put("businessItemNo", formatter.formatCellValue(sheet.getRow(baseRow + 4).getCell(col)));
            diagramValues.put("capacity", formatter.formatCellValue(sheet.getRow(baseRow + 5).getCell(col)));
            diagramValues.put("quantity", formatter.formatCellValue(sheet.getRow(baseRow + 6).getCell(col)));
            result.put("diagramValues", diagramValues);
            Map<String, String> masterValues = Map.of(
                    "managementNo", managementNo,
                    "equipmentName", objectString(master.get("facilityName")),
                    "businessItemNo", objectString(master.get("businessItemNo")),
                    "capacity", displayCapacity(objectString(master.get("capacityText")), objectString(master.get("capacityUnit"))),
                    "quantity", displayQuantity(objectString(master.get("quantityText"))));
            List<String> fieldMismatches = new ArrayList<>();
            for (String field : masterValues.keySet()) {
                if (!normalizeEquipmentValue(masterValues.get(field)).equals(normalizeEquipmentValue(diagramValues.get(field)))) {
                    fieldMismatches.add(field + " master=" + masterValues.get(field) + " diagram=" + diagramValues.get(field));
                }
            }
            result.put("masterDiagramFieldMismatches", List.copyOf(fieldMismatches));
            if (!fieldMismatches.isEmpty()) blockers.add("마스터와 도면의 기존 표시값이 일치하지 않습니다: " + String.join("; ", fieldMismatches));
        } catch (Exception exception) {
            blockers.add("설비 box/cell 매핑을 단일하게 검증하지 못했습니다: " + exception.getMessage());
        }
        List<Map<String, Object>> overlapping = new ArrayList<>();
        List<String> matchingDrawingText = new ArrayList<>();
        for (WorkbookInventory.DrawingObject object : sheetInventory.drawingObjects()) {
            String drawingText = safe(object.textContent());
            if (List.of(managementNo, objectString(master.get("facilityName")),
                    objectString(master.get("businessItemNo"))).stream().filter(value -> !value.isBlank())
                    .anyMatch(drawingText::equals)) {
                matchingDrawingText.add(object.shapeId() + ":" + object.objectName() + "=" + drawingText);
            }
            if (object.startColumn() == null || object.startRow() == null || object.endColumn() == null || object.endRow() == null) continue;
            boolean overlapsBox = targetBox != null && object.startColumn() <= targetBox.lastColumn()
                    && object.endColumn() >= targetBox.firstColumn()
                    && object.startRow() <= targetBox.lastRow() - 1 && object.endRow() >= targetBox.firstRow() - 1;
            if (overlapsBox) {
                Map<String, Object> mapping = new LinkedHashMap<>();
                mapping.put("shapeId", object.shapeId());
                mapping.put("name", object.objectName());
                mapping.put("type", object.objectType());
                mapping.put("start", cellAtIndex(object.startRow(), object.startColumn()));
                mapping.put("end", cellAtIndex(object.endRow(), object.endColumn()));
                mapping.put("tailArrowType", object.tailArrowType());
                mapping.put("startConnectedShapeId", object.startConnectedShapeId());
                mapping.put("endConnectedShapeId", object.endConnectedShapeId());
                overlapping.add(mapping);
            }
        }
        result.put("drawingPartName", sheetInventory.drawingPartName());
        result.put("drawingObjectsAtOrAcrossEquipmentBox", List.copyOf(overlapping));
        result.put("matchingDrawingTextObjects", List.copyOf(matchingDrawingText));
        if (!matchingDrawingText.isEmpty()) blockers.add("설비번호·명칭·Item No.와 일치하는 DrawingML 텍스트 객체가 있습니다. 셀 외 표기 대상 검토가 필요합니다.");
        boolean explicitMapping = overlapping.stream().anyMatch(object -> object.get("startConnectedShapeId") != null
                || object.get("endConnectedShapeId") != null);
        result.put("explicitDrawingShapeBinding", explicitMapping);
        result.put("drawingEditPolicy", "CELL_TEXT_ONLY; KEEP_DRAWING_PART_BYTE_IDENTICAL");
        if (sheetInventory.drawingPartName() == null) blockers.add("대상 시트에 DrawingML 파트가 없어 도면 보존을 검증할 수 없습니다.");
        if (!explicitMapping) result.put("drawingMappingNote",
                "도면은 셀 기반 장비 상자와 별도 anchor 객체로 구성됩니다. 주변 DrawingML은 확인했으나 논리 shape binding은 없으며 MODIFY는 셀 텍스트만 바꾸고 DrawingML 파트를 바이트 그대로 보존해야 합니다.");
        result.put("eligible", blockers.isEmpty());
        result.put("status", blockers.isEmpty() ? "VERIFIED" : "REVIEW_REQUIRED");
        result.put("reviewRequiredItems", List.copyOf(blockers));
        return result;
    }

    private Map<String, Object> inspectModifyTargetEvidence(NumericWorkingCopyService.WorkingCopy working,
            String sheetName, org.apache.poi.ss.usermodel.Sheet sheet, WorkbookInventory.SheetInventory inventory,
            Map<String, Object> master, String managementNo, Set<String> pw0105MasterIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        List<String> reviewNotes = new ArrayList<>();
        result.put("managementNo", managementNo);
        result.put("masterEquipmentName", objectString(master.get("facilityName")));
        result.put("masterItemNo", objectString(master.get("businessItemNo")));
        result.put("masterCapacityValue", objectString(master.get("capacityText")));
        result.put("masterCapacityUnit", objectString(master.get("capacityUnit")));
        result.put("masterCapacity", displayCapacity(objectString(master.get("capacityText")), objectString(master.get("capacityUnit"))));
        result.put("masterQuantity", displayQuantity(objectString(master.get("quantityText"))));
        result.put("masterQuantityRawValue", objectString(master.get("quantityText")));
        result.put("masterSourceSheet", objectString(master.get("sourceSheetName")));
        result.put("masterSourceRow", master.get("sourceRowNumber"));
        int masterIdCount = ((Number) master.getOrDefault("masterIdCount", 0)).intValue();
        result.put("masterManagementNoCount", masterIdCount);
        if (masterIdCount != 1) blockers.add("잠긴 V1.0 마스터에서 설비번호가 유일하지 않습니다.");
        if (!SUPPORTED_SHEET.equals(sheetName) || !"PW-01".equals(working.processCode()) || !isSafePw01Candidate(working)) {
            blockers.add("대상 공정·시트·작업본이 승인된 검토 범위와 일치하지 않습니다.");
        }

        var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
        List<String> occurrences = new ArrayList<>();
        for (var row : sheet) for (var cell : row) {
            if (isManagementNoCellOccurrence(formatter.formatCellValue(cell).trim(), managementNo)) {
                occurrences.add(cell.getAddress().formatAsString());
            }
        }
        result.put("diagramIdOccurrences", List.copyOf(occurrences));
        List<Pw0105EquipmentInsertionEngine.EquipmentBox> boxes = insertionEngine.locateExistingBoxesByManagementNo(sheet, managementNo);
        List<Map<String, Object>> candidateBoxes = boxes.stream().map(box -> {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("idCell", box.topLeftCell());
            candidate.put("range", box.topLeftCell() + ":" + box.bottomRightCell());
            candidate.put("firstRow", box.firstRow());
            candidate.put("lastRow", box.lastRow());
            return candidate;
        }).toList();
        result.put("candidateEquipmentBoxes", candidateBoxes);
        Pw0105EquipmentInsertionEngine.EquipmentBox box = boxes.size() == 1 ? boxes.get(0) : null;
        result.put("equipmentBoxIdCell", box == null ? "" : box.topLeftCell());
        result.put("cellRange", box == null ? "" : box.topLeftCell() + ":" + box.bottomRightCell());
        List<String> otherMasterIdsInsideBox = new ArrayList<>();
        if (box != null) {
            int idRow = new org.apache.poi.ss.util.CellReference(box.topLeftCell()).getRow();
            for (int rowIndex = idRow; rowIndex <= box.lastRow() - 1; rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (int column = box.firstColumn(); column <= box.lastColumn(); column++) {
                    var cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null) continue;
                    String value = formatter.formatCellValue(cell).trim();
                    if (!value.isBlank() && !value.equals(managementNo) && pw0105MasterIds.contains(value)) {
                        otherMasterIdsInsideBox.add(value + "@" + cell.getAddress().formatAsString());
                    }
                }
            }
        }
        result.put("otherMasterIdsInsideBox", List.copyOf(otherMasterIdsInsideBox));
        boolean groupedBox = !otherMasterIdsInsideBox.isEmpty();
        result.put("equipmentBoxMappingStatus", groupedBox ? "AGGREGATED_MULTI_MASTER_BOX"
                : boxes.size() == 1 ? "UNIQUE_STYLED_BOX" : "REVIEW_REQUIRED");
        if (groupedBox) blockers.add("설비 상자 안에 다른 PW-01-05 마스터 설비번호가 함께 표시되어 개별 설비 정체성을 확정할 수 없습니다: "
                + String.join(", ", otherMasterIdsInsideBox));
        List<String> outsideBoxIds = box == null ? occurrences : occurrences.stream()
                .filter(address -> !address.equals(box.topLeftCell())).toList();
        result.put("otherIdOccurrencesOutsideBox", outsideBoxIds);
        List<Map<String, Object>> occurrenceDetails = new ArrayList<>();
        for (String address : occurrences) {
            var ref = new org.apache.poi.ss.util.CellReference(address);
            var row = sheet.getRow(ref.getRow());
            var cell = row == null ? null : row.getCell(ref.getCol(), org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String rawText = cell == null ? "" : formatter.formatCellValue(cell).trim();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("cell", address);
            detail.put("rawText", rawText);
            String role = "UNCLASSIFIED_CELL_TEXT";
            if (box != null && address.equals(box.topLeftCell())) {
                role = "EQUIPMENT_BOX_ID_CELL";
            } else if (otherMasterIdsInsideBox.stream().anyMatch(value -> value.endsWith("@" + address))) {
                role = "OTHER_MASTER_ID_IN_AGGREGATE_BOX";
            } else if (rawText.endsWith("~")) {
                role = "EQUIPMENT_RANGE_REFERENCE_TEXT";
            } else if (isReferenceLabelCell(sheet, ref.getRow(), ref.getCol(), formatter)) {
                role = "DIAGRAM_REFERENCE_TABLE_LABEL";
            } else if (isWithinAnotherMasterBox(sheet, ref.getRow(), ref.getCol(), managementNo, pw0105MasterIds)) {
                role = "SPATIALLY_INSIDE_OTHER_MASTER_BOX_IDENTITY_UNRESOLVED";
            }
            detail.put("roleAssessment", role);
            List<Map<String, Object>> visualAnchors = inventory.drawingObjects().stream()
                    .filter(object -> object.startColumn() != null && object.startRow() != null
                            && object.endColumn() != null && object.endRow() != null
                            && object.startColumn() <= ref.getCol() && object.endColumn() >= ref.getCol()
                            && object.startRow() <= ref.getRow() && object.endRow() >= ref.getRow())
                    .map(object -> Map.<String, Object>of("shapeId", object.shapeId(), "objectType", object.objectType(),
                            "anchorFrom", cellAtIndex(object.startRow(), object.startColumn()),
                            "anchorTo", cellAtIndex(object.endRow(), object.endColumn()),
                            "relationAssessment", "VISUAL_ANCHOR_OVERLAP_ONLY"))
                    .toList();
            detail.put("drawingMlAnchorOverlaps", visualAnchors);
            occurrenceDetails.add(detail);
        }
        result.put("diagramIdOccurrenceDetails", List.copyOf(occurrenceDetails));
        if (boxes.size() == 0) blockers.add("설비번호를 포함한 경계/행 구조의 설비 상자를 찾지 못했습니다.");
        if (boxes.size() > 1) blockers.add("같은 설비번호에서 설비 상자 후보가 여러 개 발견되어 기본 대상 위치가 모호합니다.");
        if (boxes.size() == 1 && !outsideBoxIds.isEmpty()) {
            reviewNotes.add("설비번호가 설비 상자 밖에도 표시됨. 해당 위치는 별도 표기이며 동일 물리 설비라고 단정하지 않았습니다.");
        }

        Map<String, String> diagramValues = new LinkedHashMap<>();
        Map<String, String> fieldCells = new LinkedHashMap<>();
        Map<String, Map<String, Object>> fieldAssessments = new LinkedHashMap<>();
        List<String> masterDiagramMismatches = new ArrayList<>();
        if (box != null) {
            int idRow = new org.apache.poi.ss.util.CellReference(box.topLeftCell()).getRow();
            int column = new org.apache.poi.ss.util.CellReference(box.topLeftCell()).getCol();
            Map<String, Integer> offsets = Map.of("managementNo", 0, "equipmentName", 3,
                    "businessItemNo", 4, "capacity", 5, "quantity", 6);
            Map<String, String> masterValues = Map.of(
                    "managementNo", managementNo,
                    "equipmentName", objectString(master.get("facilityName")),
                    "businessItemNo", objectString(master.get("businessItemNo")),
                    "capacity", displayCapacity(objectString(master.get("capacityText")), objectString(master.get("capacityUnit"))),
                    "quantity", displayQuantity(objectString(master.get("quantityText"))));
            Map<String, String> masterRawValues = Map.of(
                    "managementNo", managementNo,
                    "equipmentName", objectString(master.get("facilityName")),
                    "businessItemNo", objectString(master.get("businessItemNo")),
                    "capacity", objectString(master.get("capacityText")),
                    "quantity", objectString(master.get("quantityText")));
            for (Map.Entry<String, Integer> entry : offsets.entrySet()) {
                String field = entry.getKey();
                int rowIndex = idRow + entry.getValue();
                var row = sheet.getRow(rowIndex);
                var cell = row == null ? null : row.getCell(column,
                        org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell);
                String address = new org.apache.poi.ss.util.CellReference(rowIndex, column).formatAsString();
                diagramValues.put(field, value);
                fieldCells.put(field, address);
                boolean exists = cell != null;
                boolean formula = exists && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA;
                boolean boxMapped = exists && !formula;
                boolean sameAsMaster = normalizeEquipmentValue(masterValues.get(field)).equals(normalizeEquipmentValue(value));
                String mergedRange = mergedRangeFor(sheet, rowIndex, column);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("field", field);
                evidence.put("masterValue", masterValues.get(field));
                evidence.put("masterRawValue", masterRawValues.get(field));
                evidence.put("diagramValue", value);
                evidence.put("cell", address);
                evidence.put("mergedRange", mergedRange);
                evidence.put("cellExists", exists);
                evidence.put("formulaCell", formula);
                evidence.put("masterDiagramMatch", sameAsMaster);
                evidence.put("editCapability", groupedBox ? "BLOCKED_MULTI_MASTER_BOX"
                        : field.equals("managementNo") && !outsideBoxIds.isEmpty() ? "BLOCKED_MULTIPLE_ID_OCCURRENCES"
                        : boxMapped ? "CELL_TEXT_TEST_ONLY" : "BLOCKED_CELL_UNVERIFIED");
                evidence.put("modifiable", !groupedBox && (field.equals("managementNo")
                        ? boxMapped && outsideBoxIds.isEmpty() : boxMapped));
                fieldAssessments.put(field, evidence);
                if (!sameAsMaster) masterDiagramMismatches.add(field + " [" + address + "]: master="
                        + masterValues.get(field) + ", diagram=" + value);
                if (!boxMapped) reviewNotes.add(field + " 대상 셀 " + address + "은 누락 또는 수식이라 수정할 수 없습니다.");
            }
            diagramValues.put("equipmentTypeLabel", valueAt(sheet, idRow + 2, column, formatter));
        }
        result.put("diagramValues", Map.copyOf(diagramValues));
        result.put("fieldCells", Map.copyOf(fieldCells));
        result.put("fieldAssessments", Map.copyOf(fieldAssessments));
        result.put("masterDiagramFieldMismatches", List.copyOf(masterDiagramMismatches));
        if (!masterDiagramMismatches.isEmpty()) reviewNotes.add("마스터와 도면 값이 다릅니다. 자동으로 어느 값을 정답으로 간주하지 않았습니다.");

        List<Map<String, Object>> drawingObjects = new ArrayList<>();
        if (box != null) for (WorkbookInventory.DrawingObject object : inventory.drawingObjects()) {
            if (object.startColumn() == null || object.startRow() == null || object.endColumn() == null || object.endRow() == null) continue;
            boolean overlaps = object.startColumn() <= box.lastColumn() && object.endColumn() >= box.firstColumn()
                    && object.startRow() <= box.lastRow() - 1 && object.endRow() >= box.firstRow() - 1;
            if (!overlaps) continue;
            Map<String, Object> drawing = new LinkedHashMap<>();
            drawing.put("shapeId", object.shapeId());
            drawing.put("objectName", object.objectName());
            drawing.put("objectType", object.objectType());
            drawing.put("text", object.textContent());
            drawing.put("anchorFrom", cellAtIndex(object.startRow(), object.startColumn()));
            drawing.put("anchorTo", cellAtIndex(object.endRow(), object.endColumn()));
            drawing.put("headArrowType", object.headArrowType());
            drawing.put("tailArrowType", object.tailArrowType());
            drawing.put("startConnectedShapeId", object.startConnectedShapeId());
            drawing.put("endConnectedShapeId", object.endConnectedShapeId());
            drawing.put("relationAssessment", object.startConnectedShapeId() == null && object.endConnectedShapeId() == null
                    ? "VISUAL_OVERLAP_ONLY_NO_SHAPE_BINDING" : "SHAPE_BINDING_PRESENT_NOT_APPROVAL");
            drawingObjects.add(drawing);
        }
        result.put("drawingPartName", inventory.drawingPartName());
        result.put("drawingObjectsAtOrAcrossEquipmentBox", List.copyOf(drawingObjects));
        result.put("drawingEditPolicy", "CELL_TEXT_ONLY; KEEP_DRAWING_PART_BYTE_IDENTICAL");
        if (inventory.drawingPartName() == null) blockers.add("PW-01-05 DrawingML 파트가 없어 비대상 객체 보존을 확인할 수 없습니다.");
        if (!reviewNotes.isEmpty()) blockers.addAll(reviewNotes);
        result.put("identityVerified", masterIdCount == 1 && boxes.size() == 1 && !groupedBox);
        result.put("eligible", masterIdCount == 1 && boxes.size() == 1 && !groupedBox
                && masterDiagramMismatches.isEmpty() && blockers.isEmpty());
        result.put("status", blockers.isEmpty() ? "IDENTITY_VERIFIED_FIELD_REVIEW" : "REVIEW_REQUIRED");
        result.put("reviewRequiredItems", List.copyOf(blockers));
        result.put("reviewNotes", List.copyOf(reviewNotes));
        return result;
    }

    private Set<String> pw0105MasterIds() {
        return jdbc.queryForList("""
                SELECT permit_management_no FROM equipment_record
                WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0' AND locked=TRUE)
                  AND middle_process_code='PW-01-05'
                """, String.class).stream().map(this::safe).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean isManagementNoCellOccurrence(String value, String managementNo) {
        return value.equals(managementNo) || value.startsWith(managementNo + "~");
    }

    private boolean isReferenceLabelCell(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, int column,
                                         org.apache.poi.ss.usermodel.DataFormatter formatter) {
        if (column != 3 && column != 58) return false;
        var row = sheet.getRow(rowIndex);
        var nextRow = sheet.getRow(rowIndex + 1);
        if (row == null || nextRow == null) return false;
        var processCell = nextRow.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        String processCode = processCell == null ? "" : formatter.formatCellValue(processCell).trim();
        if (!"PW-01-05".equals(processCode)) return false;
        int itemColumn = column == 3 ? 5 : 60;
        var itemCell = row.getCell(itemColumn, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return itemCell != null && !formatter.formatCellValue(itemCell).isBlank();
    }

    private boolean isWithinAnotherMasterBox(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, int column,
                                              String managementNo, Set<String> pw0105MasterIds) {
        for (String otherId : pw0105MasterIds) {
            if (otherId.equalsIgnoreCase(managementNo)) continue;
            for (var otherBox : insertionEngine.locateExistingBoxesByManagementNo(sheet, otherId)) {
                int idRow = new org.apache.poi.ss.util.CellReference(otherBox.topLeftCell()).getRow();
                boolean inside = rowIndex >= idRow && rowIndex <= otherBox.lastRow() - 1
                        && column >= otherBox.firstColumn() && column <= otherBox.lastColumn();
                if (inside && !otherBox.topLeftCell().equals(cellAtIndex(rowIndex, column))) return true;
            }
        }
        return false;
    }

    private String mergedRangeFor(org.apache.poi.ss.usermodel.Sheet sheet, int row, int column) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            var range = sheet.getMergedRegion(index);
            if (range.isInRange(row, column)) return range.formatAsString();
        }
        return "";
    }

    private String valueAt(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, int column,
                           org.apache.poi.ss.usermodel.DataFormatter formatter) {
        var row = sheet.getRow(rowIndex);
        var cell = row == null ? null : row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private String objectString(Object value) { return value == null ? "" : String.valueOf(value); }

    private String modifyManagementNoConflict(String replacement, String target) {
        if (replacement.equalsIgnoreCase(target)) return null;
        Long masterMatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_record
                WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0')
                  AND UPPER(TRIM(permit_management_no))=UPPER(TRIM(?))
                """, Long.class, replacement);
        if (masterMatches != null && masterMatches > 0) return "변경할 설비번호가 다른 공식 마스터 설비와 중복되어 변경을 차단합니다.";
        Long requestMatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM process_change_request
                WHERE UPPER(TRIM(equipment_management_no))=UPPER(TRIM(?))
                  AND change_type IN ('ADD','MODIFY')
                """, Long.class, replacement);
        if (requestMatches != null && requestMatches > 0) return "변경할 설비번호가 기존 변경 요청과 중복되어 변경을 차단합니다.";
        return null;
    }

    private String displayCapacity(String capacity, String unit) {
        String number = capacity == null ? "" : capacity.trim();
        String suffix = unit == null ? "" : unit.trim();
        if (number.isBlank() || suffix.isBlank() || number.contains(suffix)) return number;
        return number + suffix;
    }

    private String displayQuantity(String quantity) {
        String value = quantity == null ? "" : quantity.trim();
        if (value.isBlank() || value.matches(".*[A-Za-z%㎥].*")) return value;
        return value + "ea";
    }

    private String normalizeEquipmentValue(String value) {
        return safe(value).replace(",", "").replace("㎥", "m3").replace("³", "3")
                .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String cellAt(String origin, int rowOffset) {
        return cellAt(origin, rowOffset, 0);
    }

    private String cellAt(String origin, int rowOffset, int columnOffset) {
        var ref = new org.apache.poi.ss.util.CellReference(origin);
        return new org.apache.poi.ss.util.CellReference(ref.getRow() + rowOffset,
                ref.getCol() + columnOffset).formatAsString().replace("$", "");
    }

    private String cellAtIndex(int zeroBasedRow, int zeroBasedColumn) {
        return new org.apache.poi.ss.util.CellReference(zeroBasedRow, zeroBasedColumn).formatAsString().replace("$", "");
    }

    private boolean isSafePw01Candidate(NumericWorkingCopyService.WorkingCopy working) {
        return working != null && working.baselineDocumentId() == 12 && "PW-01".equals(working.processCode())
                && SUPPORTED_NUMERIC_CANDIDATE_VERSION.equals(working.conversionVersion())
                && "CREATED_WITH_REVIEW".equals(working.conversionStatus())
                && "784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0"
                .equalsIgnoreCase(working.sourceSha256());
    }

    private String duplicateEquipmentReason(ChangeInput input, Long currentRequestId) {
        Long masterMatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_record
                WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0')
                  AND UPPER(TRIM(permit_management_no))=UPPER(TRIM(?))
                """, Long.class, input.managementNo());
        if (masterMatches != null && masterMatches > 0) return "설비번호가 잠긴 V1.0 설비 마스터에 이미 있습니다. 신규 추가를 차단합니다.";
        Long requestMatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM process_change_request
                WHERE UPPER(TRIM(equipment_management_no))=UPPER(TRIM(?))
                  AND change_type='ADD'
                  AND (? IS NULL OR id<>?)
                """, Long.class, input.managementNo(), currentRequestId, currentRequestId);
        return requestMatches != null && requestMatches > 0
                ? "동일 설비번호의 기존 변경 요청이 있습니다. 중복 설비 Excel 생성을 차단합니다." : null;
    }

    private Map<String, Object> actualNetworkReadiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "CALCULATION_UNAVAILABLE");
        result.put("calculationAvailable", false);
        result.put("eligibleConnectionCount", 0);
        try {
            String snapshotId = reviewLedger.latestSnapshotId();
            if (blank(snapshotId)) {
                result.put("reason", "STEP7_REVIEW_SNAPSHOT_NOT_FOUND");
                return result;
            }
            var report = reviewNetworks.buildIntegrationReport(snapshotId);
            var prepared = report.actualReadiness();
            result.put("snapshotId", snapshotId);
            result.put("candidateCount", report.actualCandidateCount());
            result.put("evidenceVerifiedCount", report.actualEvidenceVerifiedCount());
            result.put("eligibleConnectionCount", report.actualEligibleConnectionCount());
            result.put("status", prepared.status());
            result.put("calculationAvailable", prepared.calculationAvailable());
            result.put("issues", prepared.issues());
            if (prepared.calculationAvailable()) {
                var solved = calculator.calculateDirectedNetwork(prepared.network());
                result.put("solverStatus", solved.status());
                result.put("solverIssues", solved.issues());
                result.put("calculationAvailable", "CALCULATION_UNAVAILABLE".equals(solved.status()) == false
                        && "UNDERDETERMINED".equals(solved.status()) == false
                        && "INCONSISTENT".equals(solved.status()) == false);
                result.put("status", solved.status());
            }
        } catch (RuntimeException exception) {
            result.put("status", "CALCULATION_UNAVAILABLE");
            result.put("reason", "STEP7_ACTUAL_REVIEW_NOT_READY: " + exception.getMessage());
        }
        return result;
    }

    private Map<String, Object> syntheticNetworkCalculation(ChangeInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "TEST_ONLY");
        result.put("officialDiagramModified", false);
        result.put("officialApprovalCreated", false);
        result.put("actualRouteAdmitted", false);
        if (!input.runSyntheticTestOnlyNetwork()) {
            result.put("status", "NOT_REQUESTED");
            result.put("calculationAvailable", false);
            result.put("reason", "사용자 입력만 저장하며 합성 수지 시험은 실행하지 않았습니다.");
            return result;
        }
        List<RouteInput> routes = input.routes() == null ? List.of() : input.routes();
        if (!input.syntheticNetworkScopeComplete() || routes.isEmpty()
                || routes.stream().anyMatch(route -> !isCompleteSyntheticRoute(route))) {
            result.put("status", "CALCULATION_UNAVAILABLE");
            result.put("calculationAvailable", false);
            result.put("routeCount", routes.size());
            result.put("reason", "합성 TEST_ONLY 계산에는 완결로 표시된 scope와 모든 endpoint/유량/단위/기간/증빙 입력이 필요합니다.");
            return result;
        }
        try {
            String snapshotId = "SYNTHETIC-WORKSPACE-" + filenameToken(input.managementNo());
            List<DirectedProcessNetwork.Node> nodes = new ArrayList<>();
            List<Step7ReviewNetworkAdapter.ReviewEvidence> reviews = new ArrayList<>();
            List<Step7ReviewNetworkAdapter.RouteEvidence> routeEvidence = new ArrayList<>();
            Set<String> nodeIds = new java.util.LinkedHashSet<>();
            Set<String> candidateIds = new java.util.LinkedHashSet<>();
            Map<String, String> boundaryEvidence = new LinkedHashMap<>();
            Map<String, String> endpointProcesses = new LinkedHashMap<>();
            List<String> unitPeriodIssues = new ArrayList<>();
            Map<String, String> streamUnits = new LinkedHashMap<>();
            Map<String, String> streamPeriods = new LinkedHashMap<>();

            for (int index = 0; index < routes.size(); index++) {
                RouteInput route = routes.get(index);
                String candidateId = filenameToken(input.managementNo()) + "-ROUTE-" + (index + 1);
                candidateIds.add(candidateId);
                String materialClassText = safe(route.materialClass()).trim().toUpperCase(java.util.Locale.ROOT);
                DirectedProcessNetwork.MaterialClass materialClass = switch (materialClassText) {
                    case "WASTEWATER" -> DirectedProcessNetwork.MaterialClass.WASTEWATER;
                    case "WASTE" -> DirectedProcessNetwork.MaterialClass.WASTE;
                    case "CHEMICAL" -> DirectedProcessNetwork.MaterialClass.CHEMICAL;
                    default -> null;
                };
                String materialType = safe(route.flowType()).trim();
                String unit = safe(route.unit()).trim();
                String period = safe(route.period()).trim();
                String sourceProcess = safe(route.sourceProcess()).isBlank() ? "PW-01" : safe(route.sourceProcess()).trim();
                String targetProcess = safe(route.targetProcess()).isBlank() ? "PW-01" : safe(route.targetProcess()).trim();
                String sourceNodeId = addSyntheticEndpoint(route.sourceEquipment(), sourceProcess, nodes, nodeIds,
                        endpointProcesses, boundaryEvidence);
                String targetNodeId = addSyntheticEndpoint(route.targetEquipment(), targetProcess, nodes, nodeIds,
                        endpointProcesses, boundaryEvidence);
                DirectedProcessNetwork.ConnectionType connectionType = syntheticConnectionType(route);
                Step7ReviewNetworkAdapter.FlowDimension dimension = flowDimension(unit);
                String stream = materialClassText + "|" + materialType;
                String oldUnit = streamUnits.putIfAbsent(stream, normalize(unit));
                String oldPeriod = streamPeriods.putIfAbsent(stream, normalize(period));
                if (oldUnit != null && !oldUnit.equals(normalize(unit))) unitPeriodIssues.add("MIXED_UNIT_NO_CONVERSION:" + stream);
                if (oldPeriod != null && !oldPeriod.equals(normalize(period))) unitPeriodIssues.add("MIXED_PERIOD_NO_ALIGNMENT:" + stream);
                long caseId = index + 1L;
                BigDecimal quantity = route.quantity();
                reviews.add(new Step7ReviewNetworkAdapter.ReviewEvidence("V1.0", true, snapshotId, candidateId,
                        caseId, caseId, 1, 1, "EVIDENCE_VERIFIED", index + 1,
                        "SYNTHETIC-TEST-ONLY.xlsx", input.sheetName(), "SYNTHETIC-SOURCE", "SYNTHETIC-TARGET",
                        "SYNTHETIC-CONNECTOR-" + (index + 1), syntheticEquipment(route.sourceEquipment()),
                        syntheticEquipment(route.targetEquipment()), sourceProcess, targetProcess,
                        "EVIDENCE_VERIFIED", true, materialType, quantity, unit, period,
                        "SYNTHETIC_TEST_ONLY", "SYNTHETIC-INPUT-" + route.evidenceReference(),
                        "User-supplied synthetic fixture only; not verified plant evidence: " + route.pathDescription(),
                        "TEST_ONLY_USER_INPUT", false, true));
                routeEvidence.add(new Step7ReviewNetworkAdapter.RouteEvidence(snapshotId, "V1.0", candidateId,
                        caseId, caseId, 1, "SYNTHETIC-ROUTE-" + (index + 1), sourceNodeId, targetNodeId,
                        connectionType, materialClass, materialType, dimension,
                        Step7ReviewNetworkAdapter.GasCondition.NOT_APPLICABLE, "NOT_APPLICABLE", quantity,
                        "SYNTHETIC-QUANTITY-" + route.evidenceReference(), unit, period,
                        DirectedProcessNetwork.QuantityBasis.USER_INPUT, true, route.pathDescription(),
                        "SYNTHETIC-PATH-" + route.evidenceReference(), true, true));
            }
            if (!unitPeriodIssues.isEmpty()) {
                result.put("status", "CALCULATION_UNAVAILABLE");
                result.put("calculationAvailable", false);
                result.put("routeCount", routes.size());
                result.put("issues", List.copyOf(unitPeriodIssues));
                result.put("reason", "동일 물질 흐름의 단위·기간이 섞였으며 변환이나 시간 정렬은 수행하지 않았습니다.");
                return result;
            }
            Step7ReviewNetworkAdapter.GraphScope scope = new Step7ReviewNetworkAdapter.GraphScope(
                    "TEST_ONLY", "V1.0", true, snapshotId, snapshotId, "User supplied synthetic network",
                    routes.get(0).period(), candidateIds, nodes, List.of(), boundaryEvidence,
                    true, true, true, true, true, true, "SYNTHETIC-SCOPE-" + filenameToken(input.managementNo()),
                    List.of("Explicitly user-declared synthetic scope", "No actual plant path or approval inferred"));
            var prepared = reviewNetworkAdapter.adapt(reviews, routeEvidence, scope);
            result.put("routeCount", routes.size());
            result.put("candidateAssessments", prepared.candidates());
            result.put("issues", prepared.issues());
            if (!prepared.calculationAvailable()) {
                result.put("status", "CALCULATION_UNAVAILABLE");
                result.put("calculationAvailable", false);
                result.put("reason", "Step 7-4B adapter excluded one or more synthetic route inputs.");
                return result;
            }
            var solved = calculator.calculateDirectedNetwork(prepared.network());
            result.put("status", solved.status());
            result.put("calculationAvailable", !Set.of("CALCULATION_UNAVAILABLE", "UNDERDETERMINED", "INCONSISTENT")
                    .contains(solved.status()));
            result.put("solverResult", solved);
            result.put("edgeProvenance", prepared.edgeProvenance());
            result.put("reason", "Result belongs only to a declared TEST_ONLY fixture; never an actual PW-01 material balance.");
        } catch (RuntimeException exception) {
            result.put("status", "CALCULATION_UNAVAILABLE");
            result.put("calculationAvailable", false);
            result.put("routeCount", routes.size());
            result.put("reason", "SYNTHETIC_TEST_ONLY_VALIDATION_FAILED: " + exception.getMessage());
        }
        return result;
    }

    private String addSyntheticEndpoint(String endpoint, String process, List<DirectedProcessNetwork.Node> nodes,
                                       Set<String> nodeIds, Map<String, String> endpointProcesses,
                                       Map<String, String> boundaryEvidence) {
        String value = endpoint.trim();
        boolean sourceBoundary = value.startsWith("EXT-SOURCE:");
        boolean sinkBoundary = value.startsWith("EXT-SINK:");
        String id = "SYNTH-" + (sourceBoundary ? "SOURCE-" : sinkBoundary ? "SINK-" : "EQUIPMENT-") + value;
        String processCode = sourceBoundary || sinkBoundary ? "" : process;
        String previousProcess = endpointProcesses.putIfAbsent(value, processCode);
        if (previousProcess != null && !previousProcess.equals(processCode)) {
            throw new IllegalArgumentException("SYNTHETIC_ENDPOINT_PROCESS_MISMATCH:" + value);
        }
        if (nodeIds.add(id)) {
            DirectedProcessNetwork.NodeType type = sourceBoundary ? DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE
                    : sinkBoundary ? DirectedProcessNetwork.NodeType.EXTERNAL_SINK
                    : DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT;
            nodes.add(new DirectedProcessNetwork.Node(id, sourceBoundary || sinkBoundary ? null : value,
                    processCode.isBlank() ? null : processCode, "SYNTHETIC_TEST_ONLY", type, value,
                    "SYNTHETIC-TEST-ONLY.xlsx", "USER_INPUT", "TEST_ONLY"));
            if (sourceBoundary || sinkBoundary) boundaryEvidence.put(id, "SYNTHETIC-BOUNDARY-USER-DECLARED");
        }
        return id;
    }

    private String syntheticEquipment(String endpoint) {
        return endpoint.startsWith("EXT-SOURCE:") || endpoint.startsWith("EXT-SINK:") ? endpoint : endpoint;
    }

    private boolean isCompleteSyntheticRoute(RouteInput route) {
        return route != null && route.complete() && !blank(route.sourceEquipment()) && !blank(route.targetEquipment())
                && !route.sourceEquipment().equals(route.targetEquipment()) && !blank(route.pathDescription())
                && !blank(route.flowType()) && !blank(route.materialClass()) && !blank(route.connectionType())
                && route.quantity() != null && route.quantity().signum() >= 0 && !blank(route.unit())
                && !blank(route.period()) && !blank(route.evidenceReference());
    }

    private DirectedProcessNetwork.ConnectionType syntheticConnectionType(RouteInput route) {
        if (route.internalRecycle()) return "BRANCH".equalsIgnoreCase(route.connectionType())
                ? DirectedProcessNetwork.ConnectionType.BRANCH : DirectedProcessNetwork.ConnectionType.INTERNAL_RECYCLE;
        String value = safe(route.connectionType()).trim().toUpperCase(java.util.Locale.ROOT);
        try { return DirectedProcessNetwork.ConnectionType.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException exception) {
            return route.branchOrMerge() ? DirectedProcessNetwork.ConnectionType.BRANCH
                    : DirectedProcessNetwork.ConnectionType.SERIES;
        }
    }

    private Step7ReviewNetworkAdapter.FlowDimension flowDimension(String unit) {
        String value = normalize(unit).replace("³", "3").replace("㎥", "m3");
        if (Set.of("ton/day", "t/day", "ton/month", "t/month", "ton/year", "t/year", "kg/day", "kg/hr", "kg/h", "g/s").contains(value)) {
            return Step7ReviewNetworkAdapter.FlowDimension.MASS_PER_TIME;
        }
        if (Set.of("l/year", "l/yr", "m3/min", "am3/min", "nm3/min", "sm3/min", "am3/h", "nm3/h", "sm3/h", "m3/day").contains(value)) {
            return Step7ReviewNetworkAdapter.FlowDimension.VOLUME_PER_TIME;
        }
        return Step7ReviewNetworkAdapter.FlowDimension.UNKNOWN;
    }

    private Map<String, Object> syntheticUnavailable(String status, String message) {
        return Map.of("mode", "TEST_ONLY", "status", status, "calculationAvailable", false,
                "officialDiagramModified", false, "officialApprovalCreated", false,
                "actualRouteAdmitted", false, "reason", message);
    }

    private List<List<?>> inputRows(ChangeInput input) {
        List<List<?>> rows = new ArrayList<>();
        addInputRow(rows, "설비", "설비번호", input.managementNo(), "", "", "", "USER_INPUT");
        addInputRow(rows, "설비", "설비명", input.equipmentName(), "", "", "", "USER_INPUT");
        addInputRow(rows, "설비", "사업장 Item No.", input.businessItemNo(), "", "", "", "USER_INPUT");
        addInputRow(rows, "설비", "용량", input.capacity(), input.capacityUnit(), "", "", "USER_INPUT");
        addInputRow(rows, "설비", "설비 수량", input.equipmentQuantity(), "", "", "", "USER_INPUT");
        addInputRow(rows, "폐수", "종류", input.wastewaterType(), "", "", input.wastewaterDestination(), input.wastewaterEvidence());
        addInputRow(rows, "폐수", "발생량", input.wastewaterQuantity() == null ? "" : input.wastewaterQuantity().toPlainString(), input.wastewaterUnit(), input.wastewaterPeriod(),
                input.wastewaterDestination(), input.wastewaterEvidence());
        addInputRow(rows, "폐기물", "종류", input.wasteType(), "", "", input.wasteDestination(), input.wasteEvidence());
        addInputRow(rows, "폐기물", "발생량", input.wasteQuantity() == null ? "" : input.wasteQuantity().toPlainString(), input.wasteUnit(), input.wastePeriod(),
                input.wasteDestination(), input.wasteEvidence());
        if (input.routes() != null) {
            for (int i = 0; i < input.routes().size(); i++) {
                RouteInput route = input.routes().get(i);
                rows.add(List.of("배관/경로", "경로 " + (i + 1), safe(route.sourceEquipment()) + " → " + safe(route.targetEquipment())
                        + " / " + safe(route.pathDescription()), safe(route.unit()), safe(route.period()),
                        safe(route.flowType()) + " / " + safe(route.materialClass()), safe(route.evidenceReference()), "REVIEW_REQUIRED"));
                rows.add(List.of("배관/경로", "경로 유형/분기·순환 " + (i + 1), safe(route.connectionType()),
                        safe(route.sourceProcess()) + " → " + safe(route.targetProcess()),
                        Boolean.toString(route.branchOrMerge()), Boolean.toString(route.internalRecycle()),
                        Boolean.toString(route.complete()), "TEST_ONLY_USER_INPUT"));
                rows.add(List.of("배관/경로", "유량 " + (i + 1), "", route.quantity() == null ? "" : route.quantity(),
                        safe(route.unit()), safe(route.period()), safe(route.pathDescription()),
                        route.hasEvidenceReference() ? "USER_REFERENCE_NOT_VERIFIED" : "REVIEW_REQUIRED"));
            }
        }
        if (rows.size() < 10) rows.add(List.of("수지", "계산 상태", "CALCULATION_UNAVAILABLE", "", "", "", "", "확인된 전체 경로·단위/기간/경계가 없어 미계산"));
        return rows;
    }

    private void addInputRow(List<List<?>> rows, String group, String field, String text, String unit,
                             String period, String destination, String status) {
        rows.add(List.of(group, field, safe(text), safe(unit), safe(period), safe(destination), "", safe(status)));
    }

    private List<String> inputCoverage(ChangeInput input) {
        List<String> result = new ArrayList<>();
        if (blank(input.wastewaterType()) && input.wastewaterQuantity() == null && blank(input.wastewaterDestination())) {
            result.add("폐수 종류/발생량/이동처가 입력되지 않아 공란으로 보존됩니다.");
        }
        if (blank(input.wasteType()) && input.wasteQuantity() == null && blank(input.wasteDestination())) {
            result.add("폐기물 종류/발생량/이동처가 입력되지 않아 공란으로 보존됩니다.");
        }
        if (input.routes() == null || input.routes().isEmpty()) result.add("배관/이동 경로 입력이 없어 실제 연결망 계산은 수행하지 않습니다.");
        else if (input.routes().stream().anyMatch(route -> !route.complete())) {
            result.add("하나 이상의 배관 경로에 유량, 단위, 기간 또는 증빙 참조가 빠져 검토 필요입니다.");
        }
        result.add("사용자 입력·경로 설명은 공식 연결 증빙 또는 승인으로 자동 승격하지 않습니다.");
        return result;
    }

    private void validateInput(ChangeInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경 입력이 없습니다.");
        if (input.workingDocumentId() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PW-01 작업본을 먼저 선택하세요.");
        if (blank(input.sheetName())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정할 시트를 선택하세요.");
        if (blank(input.changeType()) || !List.of("ADD", "MODIFY", "REMOVE").contains(normalizeChangeType(input.changeType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경 유형은 ADD/MODIFY/REMOVE 중 하나여야 합니다.");
        }
        if (blank(input.managementNo()) || !input.managementNo().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비번호는 영문/숫자로 시작하고 영문·숫자·점·밑줄·하이픈만 사용하세요(최대 64자).");
        }
        if (blank(input.equipmentName()) || blank(input.businessItemNo()) || blank(input.capacity())
                || ("ADD".equals(normalizeChangeType(input.changeType())) && blank(input.capacityUnit()))
                || blank(input.equipmentQuantity()) || blank(input.equipmentTypeLabel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비명, Item No., 용량, 수량 및 설비 유형은 필수입니다. ADD는 용량 단위도 입력하세요.");
        }
        if (input.equipmentName().length() > 255 || input.businessItemNo().length() > 128
                || input.capacity().length() > 128 || input.capacityUnit().length() > 64
                || input.equipmentQuantity().length() > 64 || input.equipmentTypeLabel().length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비 입력값이 허용 길이를 초과했습니다.");
        }
        if (input.placementId() == null || input.placementId().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "삽입 위치를 선택하세요.");
        }
        if (input.wastewaterQuantity() != null && (input.wastewaterQuantity().signum() < 0 || blank(input.wastewaterUnit()) || blank(input.wastewaterPeriod()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "폐수량은 0 이상 숫자와 단위·기준 기간을 함께 입력하세요.");
        }
        if (input.wasteQuantity() != null && (input.wasteQuantity().signum() < 0 || blank(input.wasteUnit()) || blank(input.wastePeriod()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "폐기물량은 0 이상 숫자와 단위·기준 기간을 함께 입력하세요.");
        }
        if ("MODIFY".equals(normalizeChangeType(input.changeType())) && blank(input.targetManagementNo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기존 설비 수정에는 targetManagementNo가 필요합니다.");
        }
        if (input.targetManagementNo() != null && !input.targetManagementNo().isBlank()
                && !input.targetManagementNo().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "대상 설비번호 형식이 올바르지 않습니다.");
        }
        if (input.routes() != null && input.routes().size() > 24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "배관 경로 입력은 24건까지입니다.");
        if (input.routes() != null) for (RouteInput route : input.routes()) {
            if (route == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "배관 경로 항목은 비어 있을 수 없습니다.");
            if (route.sourceEquipment() != null && route.sourceEquipment().length() > 128
                    || route.targetEquipment() != null && route.targetEquipment().length() > 128
                    || route.sourceProcess() != null && route.sourceProcess().length() > 64
                    || route.targetProcess() != null && route.targetProcess().length() > 64
                    || route.evidenceReference() != null && route.evidenceReference().length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "배관 endpoint/process/evidence 입력이 허용 길이를 초과했습니다.");
            }
            if (route.quantity() != null && (route.quantity().signum() < 0 || blank(route.unit()) || blank(route.period()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "배관 유량은 0 이상 숫자와 단위·기준 기간을 함께 입력하세요.");
            }
            if (route.pathDescription() != null && route.pathDescription().length() > 512) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "배관 경로 설명은 512자 이하여야 합니다.");
            }
        }
    }

    private String normalizeChangeType(String value) { return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT); }

    private MasterEquipment masterEquipment(ChangeInput input) {
        return new MasterEquipment(0, 0, input.managementNo(), safe(input.businessItemNo()), input.sheetName(), "PW-01",
                "폐수처리", safe(input.equipmentName()), safe(input.equipmentName()), safe(input.equipmentTypeLabel()), safe(input.capacity()),
                safe(input.capacityUnit()), safe(input.equipmentQuantity()), "", "", "", safe(input.wasteType()),
                input.wasteQuantity() == null ? "" : input.wasteQuantity().toPlainString(), safe(input.wasteDestination()),
                safe(input.wastewaterType()), input.wastewaterQuantity() == null ? "" : input.wastewaterQuantity().toPlainString(),
                safe(input.wastewaterDestination()), "", "", "", Map.of());
    }

    private Pw0105EquipmentInsertionEngine.TestEditSpec editSpec(ChangeInput input) {
        return new Pw0105EquipmentInsertionEngine.TestEditSpec(input.sheetName(), SUPPORTED_SOURCE_ID,
                SUPPORTED_SOURCE_NAME, SUPPORTED_TARGET_ID, SUPPORTED_TARGET_NAME, input.equipmentTypeLabel());
    }

    private Map<String, Object> historyPlan(ChangeInput input, Map<String, Object> original, long requestId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", requestId);
        plan.put("scenarioStatus", "TEST_ONLY");
        plan.put("target", Map.of("sheetName", SUPPORTED_SHEET,
                "workbookFilename", "PW-01 폐수처리공정 ★변경허가.xlsx"));
        Object insertion = original.get("insertion");
        plan.put("insertion", insertion == null ? Map.of("plannedCellRange", "검증 엔진 셀 범위", "insertBeforeRow", "미리보기") : insertion);
        plan.put("predecessorCandidate", Map.of("managementNo", "C-PW15009"));
        plan.put("successorCandidate", Map.of("managementNo", "C-PW15010"));
        plan.put("testOnlyScenario", Map.of("enabled", true, "processCode", "PW-01-05",
                "workbookFilename", "PW-01 폐수처리공정 ★변경허가.xlsx", "sheetName", SUPPORTED_SHEET,
                "predecessorManagementNo", "C-PW15009", "successorManagementNo", "C-PW15010"));
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("plan", plan);
        stored.put("planId", requestId);
        stored.put("planStatus", "TEST_ONLY_READY");
        stored.put("automaticEditAllowed", true);
        stored.put("relationStatus", "REVIEW_REQUIRED");
        stored.put("changeType", "ADDED");
        stored.put("beforeValues", "V1.0 설비 마스터에 미등록");
        stored.put("afterValues", writeJson(input));
        stored.put("reviewRequiredText", BALANCE_LIMITATION);
        return stored;
    }

    private Map<String, Object> outputMetadata(Map<String, Object> stored, byte[] content, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", stored.get("id"));
        result.put("requestStatus", stored.get("requestStatus"));
        result.put("filename", stored.get("outputFilename"));
        result.put("sha256", stored.get("outputSha256"));
        result.put("sourceWorkingSha256", stored.get("workingSha256"));
        result.put("drawingEditApplied", true);
        result.put("balanceStatus", "CALCULATION_UNAVAILABLE");
        result.put("balanceReason", BALANCE_LIMITATION);
        result.put("officialBaselineModified", false);
        result.put("officialApprovalCreated", false);
        result.put("sheetCount", countSheets(content));
        result.put("reused", reused);
        return result;
    }

    private int countSheets(byte[] bytes) {
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            return workbook.getNumberOfSheets();
        } catch (Exception exception) { throw new IllegalStateException("Generated workbook cannot be reopened", exception); }
    }

    private ChangeInput readRequest(String json) {
        try { return mapper.readValue(json, ChangeInput.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored change request cannot be parsed", exception); }
    }

    private Map<String, Object> jsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, val) -> result.put(String.valueOf(key), val)); return result;
        }
        if (value == null) return Map.of();
        try { return mapper.readValue(String.valueOf(value), new TypeReference<>() { }); }
        catch (Exception exception) { return Map.of("unparsed", String.valueOf(value)); }
    }

    private String afterSummary(ChangeInput input) {
        return safe(input.managementNo()) + " / " + safe(input.equipmentName()) + " / "
                + safe(input.businessItemNo()) + " / " + safe(input.capacity()) + safe(input.capacityUnit())
                + " / " + safe(input.equipmentQuantity()) + " / " + input.equipmentTypeLabel();
    }

    private String filenameToken(String value) {
        String token = safe(value).replaceAll("[^A-Za-z0-9._-]", "_");
        return token.isBlank() ? "TEST-ONLY" : token;
    }

    private String normalize(String value) {
        return safe(value).trim().replace("㎥", "m3").replace("³", "3").replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize request", exception); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value.trim(); }

    public record ChangeInput(long workingDocumentId, String sheetName, String changeType,
                              String managementNo, String equipmentName, String businessItemNo,
                              String capacity, String capacityUnit, String equipmentQuantity,
                              String placementId, String equipmentTypeLabel, String targetManagementNo,
                              String wastewaterType, BigDecimal wastewaterQuantity, String wastewaterUnit,
                              String wastewaterPeriod, String wastewaterDestination, String wastewaterEvidence,
                              String wasteType, BigDecimal wasteQuantity, String wasteUnit,
                              String wastePeriod, String wasteDestination, String wasteEvidence,
                              List<RouteInput> routes, boolean runSyntheticTestOnlyNetwork,
                              boolean syntheticNetworkScopeComplete, boolean scopeCompleteAttested) { }
    public record RouteInput(String sourceEquipment, String targetEquipment, String sourceProcess,
                             String targetProcess, String pathDescription, String flowType, String materialClass,
                             String connectionType, BigDecimal quantity, String unit, String period,
                             String evidenceReference, boolean internalRecycle, boolean branchOrMerge,
                             boolean complete) {
        public boolean hasEvidenceReference() { return evidenceReference != null && !evidenceReference.isBlank(); }
    }
    public record GeneratedFile(String filename, String sha256, byte[] bytes) { }
}
