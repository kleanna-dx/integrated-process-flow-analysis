package com.example.processautomation.material;

import com.example.processautomation.excel.ExcelCellText;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.MasterWorkbookParser;
import com.example.processautomation.model.MasterEquipment;
import com.example.processautomation.model.ParsedMaster;
import com.example.processautomation.excel.WorkbookInventory;
import com.example.processautomation.excel.WorkbookInventory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Step 7-3 drawing/database evidence mapping and append-only review intake. */
@Service
public class Step7ConnectionEvidenceService {
    private static final String BASELINE_VERSION = "V1.0";
    private static final String PROCESS_FILE = "PW-01 폐수처리공정 ★변경허가.xlsx";
    private static final String MASTER_FILE = "전체 목록.xlsx";
    private static final String PROCESS_SHEET = "PW-01-05";
    private static final String EXPECTED_TEAM_SHA = "565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa";
    private static final Pattern FLOW_TEXT = Pattern.compile("^\\s*(.*?)\\s*([-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)\\s*(.*?)\\s*$");
    private static final Pattern CASE_KEY = Pattern.compile("PW0105-[A-Z0-9-]{4,64}");
    private static final Set<String> REVIEW_DECISIONS = Set.of(
            "TEST_ONLY_EVIDENCE_ACCEPTED", "NEEDS_MORE_EVIDENCE", "REJECTED");

    private final JdbcTemplate jdbc;
    private final MasterWorkbookParser masterParser;
    private final Step7MaterialBalanceComparisonService comparisonService;
    private final MaterialBalanceCalculator networkCalculator;
    private final ObjectMapper objectMapper;
    private final Step7DrawingEvidenceExtractor drawingExtractor;

    public Step7ConnectionEvidenceService(JdbcTemplate jdbc, MasterWorkbookParser masterParser,
                                          Step7MaterialBalanceComparisonService comparisonService,
                                          MaterialBalanceCalculator networkCalculator, ObjectMapper objectMapper,
                                          Step7DrawingEvidenceExtractor drawingExtractor) {
        this.jdbc = jdbc;
        this.masterParser = masterParser;
        this.comparisonService = comparisonService;
        this.networkCalculator = networkCalculator;
        this.objectMapper = objectMapper;
        this.drawingExtractor = drawingExtractor;
    }

    @Transactional(readOnly = true)
    public EvidenceWorkbookData buildWorkbook(byte[] teamWorkbook, String sourceFilename) {
        if (teamWorkbook == null || teamWorkbook.length == 0) {
            throw new InvalidWorkbookException("The team material balance workbook is empty");
        }
        BaselineFiles baseline = loadBaselineFiles();
        OfficialSnapshot snapshot = loadOfficialSnapshot();
        var comparison = comparisonService.compare(teamWorkbook, baseline.masterBytes(), sourceFilename,
                new Step7MaterialBalanceComparisonService.OfficialEvidenceSnapshot(
                        snapshot.documentCount(), snapshot.equipmentCount(), snapshot.equipmentFlowCount(),
                        snapshot.confirmedRelationCount(), snapshot.reviewRelationCount(), snapshot.connectorCount(),
                        snapshot.explicitBindingCount(), BASELINE_VERSION));
        Step7DrawingEvidenceExtractor.SheetEvidence drawing = drawingExtractor.extract(baseline.processBytes(), PROCESS_SHEET);
        Map<String, List<Step7DrawingEvidenceExtractor.EquipmentLabel>> labels = drawing.equipmentLabels().stream()
                .collect(java.util.stream.Collectors.groupingBy(Step7DrawingEvidenceExtractor.EquipmentLabel::equipmentNumber,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<ConnectionCandidate> candidates = new ArrayList<>();
        for (var transfer : comparison.connections()) {
            if (!transfer.pw01Scope() || !labels.containsKey(transfer.source()) || !labels.containsKey(transfer.target())) continue;
            List<ConnectorHint> connectorHints = spatialConnectorHints(labels.get(transfer.source()),
                    labels.get(transfer.target()), drawing.connectors());
            List<FlowEvidence> flowHints = nearbyFlowHints(connectorHints, drawing.flowLabels());
            boolean binding = connectorHints.stream().anyMatch(ConnectorHint::explicitEndpointBinding);
            String status = "EVIDENCE_INSUFFICIENT";
            String candidateStatus = "CONNECTION_CANDIDATE";
            String caseKey = "PW0105-AUTO-DBROW-" + transfer.sourceRow();
            candidates.add(new ConnectionCandidate(caseKey, transfer.sourceRow(),
                    transfer.source(), transfer.target(), labelCells(labels.get(transfer.source())),
                    labelCells(labels.get(transfer.target())), transfer.quantity(), transfer.quantityText(),
                    transfer.quantityState(), transfer.materialType(), connectorHints, flowHints,
                    binding ? "connector endpoint binding이 관찰되나 설비 연결 논리는 별도 검토 필요" : "위치 기반 connector hint만 존재; 논리 endpoint 미확정",
                    "RAW_EXTRACTED", candidateStatus, status, "UNIT_OR_PERIOD_UNVERIFIED", "AUTO_EXTRACTED_DB_ID_PAIR"));
        }
        List<ManualFixtureAudit> fixtures = auditManualFixtures(comparison, drawing, labels);
        List<LegacyBalanceIssue> legacyIssues = legacyIssues(comparison);
        SyntheticDelta syntheticDelta = createSyntheticResidualDelta();
        List<String> warnings = new ArrayList<>();
        if (!EXPECTED_TEAM_SHA.equalsIgnoreCase(comparison.sourceSha256())) {
            warnings.add("팀원 XLSM 해시가 Step 7-2 기준판과 다릅니다. 팀원 입력값의 자동 추출은 유지하되 식별 결과를 원본 비교 없이 승인하지 않습니다.");
        }
        if (drawing.connectorEndpointBindings() == 0) {
            warnings.add("PW-01-05 DrawingML connector endpoint binding은 0건입니다. 셀·도형 근접도는 논리적 연결의 증거가 아니며 검증 완료 관계는 0건입니다.");
        }
        if (snapshot.equipmentFlowCount() == 0) {
            warnings.add("공식 equipment_flow는 0행입니다. Excel 표시 유량은 추출하되 DB 수치와 동일 배관/기준으로 비교하지 않습니다.");
        }
        warnings.add("팀원 DB 질량 열에는 단위와 기준 기간이 없으므로 공식 도면 라벨 숫자와의 차이값은 생성하지 않았습니다.");
        long formulaCellCount = drawing.rawCells().stream().filter(cell -> "FORMULA".equals(cell.cellType())).count();
        String extractionSummary = "PW-01-05 read-only extract: objects=" + drawing.drawingObjectCount()
                + ", shapes=" + drawing.shapes().size() + ", connectors=" + drawing.connectors().size()
                + ", connector endpoint bindings=" + drawing.connectorEndpointBindings()
                + ", distinct equipment labels=" + labels.size() + ", flow text cells=" + drawing.flowLabels().size()
                + ", numeric cells=" + drawing.numericCells().size() + ", formula cells=" + formulaCellCount
                + ". Automatic DB-ID candidates=" + candidates.size() + "; manual fixtures=" + fixtures.size()
                + " are overlapping audit references, not additional unique connections. Label cells/anchors are location hints, not logical endpoints.";
        return new EvidenceWorkbookData(comparison.sourceFilename(), comparison.sourceSha256(),
                baseline.processSha256(), baseline.masterSha256(), snapshot, drawing, comparison,
                List.copyOf(candidates), fixtures, legacyIssues, syntheticDelta,
                List.copyOf(warnings), 0, extractionSummary);
    }

    @Transactional(readOnly = true)
    public EvidenceWorkbookData buildWorkbookFromStoredBaseline(byte[] teamWorkbook, String sourceFilename) {
        return buildWorkbook(teamWorkbook, sourceFilename);
    }

    /** Imports only a completed, machine-keyed review ledger. It never creates/updates process_relation. */
    @Transactional
    public ReviewImportResult importReviewedLedger(byte[] workbookBytes) {
        List<ReviewLedgerRow> rows = parseReviewLedger(workbookBytes);
        int ignored = 0;
        int rejected = 0;
        int persisted = 0;
        List<String> issues = new ArrayList<>();
        for (ReviewLedgerRow row : rows) {
            if (row.isEmptyReviewerRow()) {
                ignored++;
                continue;
            }
            List<String> validation = validateLedgerRow(row);
            if (!validation.isEmpty()) {
                rejected++;
                issues.add(row.caseKey() + ": " + String.join(",", validation));
                continue;
            }
            saveReview(row);
            persisted++;
        }
        return new ReviewImportResult(rows.size(), persisted, ignored, rejected, List.copyOf(issues),
                false, "TEST_ONLY evidence review is not an official CONFIRMED process relation.");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviewHistory(String caseKey) {
        if (caseKey == null || !CASE_KEY.matcher(caseKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid evidence case key");
        }
        return jdbc.queryForList("""
                SELECT id, case_key AS caseKey, action_code AS actionCode, before_json AS beforeState,
                       after_json AS afterState, actor_source AS actorSource, created_at AS createdAt
                FROM balance_evidence_review_history WHERE case_key = ? ORDER BY id
                """, caseKey);
    }

    /** Converts only explicitly reviewed rows to isolated TEST_ONLY edges; never grants official approval. */
    public Step7EvidenceNetworkDraft createTestOnlyNetwork(List<ReviewedEvidence> reviewedRows,
                                                            Map<String, EquipmentMap> equipmentMaps) {
        if (reviewedRows == null || reviewedRows.isEmpty()) {
            throw new IllegalArgumentException("At least one TEST_ONLY-reviewed evidence row is required");
        }
        List<DirectedProcessNetwork.Node> nodes = new ArrayList<>();
        List<DirectedProcessNetwork.Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReviewedEvidence row : reviewedRows) {
            if (!"TEST_ONLY_EVIDENCE_ACCEPTED".equals(row.reviewDecision()) || row.reviewedAt() == null
                    || blank(row.reviewer()) || blank(row.evidenceText()) || blank(row.sourceEquipment())
                    || blank(row.targetEquipment()) || row.quantity() == null || row.quantity().signum() < 0
                    || blank(row.unit()) || blank(row.materialType())) {
                throw new IllegalArgumentException("Only complete TEST_ONLY_EVIDENCE_ACCEPTED rows can form a test network");
            }
            if (!seen.add(row.caseKey())) throw new IllegalArgumentException("Duplicate evidence case key: " + row.caseKey());
            EquipmentMap source = equipmentMaps.get(row.sourceEquipment());
            EquipmentMap target = equipmentMaps.get(row.targetEquipment());
            if (source == null || target == null) {
                throw new IllegalArgumentException("Both reviewed endpoints must resolve in official V1.0 master: " + row.caseKey());
            }
            nodes.add(node(row.sourceEquipment(), source));
            nodes.add(node(row.targetEquipment(), target));
            DirectedProcessNetwork.MaterialClass materialClass = materialClass(row.flowType());
            DirectedProcessNetwork.ConnectionType type = row.cycleCandidate()
                    ? DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE
                    : DirectedProcessNetwork.ConnectionType.SERIES;
            edges.add(new DirectedProcessNetwork.Edge(row.caseKey(), row.sourceEquipment(), row.targetEquipment(),
                    source.majorProcessCode(), target.majorProcessCode(), materialClass, row.materialType(), row.unit(),
                    row.quantity(), type, DirectedProcessNetwork.QuantityBasis.USER_INPUT, true, true,
                    "TEST_ONLY human evidence review; reviewer=" + row.reviewer() + "; " + row.evidenceText(), "TEST_ONLY"));
        }
        List<DirectedProcessNetwork.Node> uniqueNodes = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(DirectedProcessNetwork.Node::nodeId, node -> node,
                        (a, b) -> a, LinkedHashMap::new)).values().stream().toList();
        String id = "PW0105-REVIEW-" + sha256(String.join("|", edges.stream()
                .map(DirectedProcessNetwork.Edge::connectionId).sorted().toList()).getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        DirectedProcessNetwork network = new DirectedProcessNetwork(id, "PW-01-05 reviewed evidence TEST_ONLY draft",
                "TEST_ONLY", uniqueNodes, edges, List.of());
        return new Step7EvidenceNetworkDraft(network, false, "No process_relation or approval relation is created.");
    }

    public ResidualDeltaReport compareTestOnlyScenario(DirectedProcessNetwork baseNetwork,
                                                        String changedConnectionId, BigDecimal newQuantity) {
        if (baseNetwork == null || !"TEST_ONLY".equalsIgnoreCase(baseNetwork.mode())) {
            throw new IllegalArgumentException("Only an isolated TEST_ONLY network is allowed");
        }
        var before = networkCalculator.calculateDirectedNetwork(baseNetwork);
        var after = networkCalculator.recalculateDirectedNetwork(baseNetwork,
                baseNetwork.networkId() + "-AFTER", changedConnectionId, newQuantity);
        Map<BalanceKey, BigDecimal> beforeResiduals = residuals(before);
        Map<BalanceKey, BigDecimal> afterResiduals = residuals(after);
        Set<BalanceKey> keys = new LinkedHashSet<>(beforeResiduals.keySet());
        keys.addAll(afterResiduals.keySet());
        List<ResidualDelta> deltas = keys.stream().sorted(java.util.Comparator.comparing(BalanceKey::equipment)
                .thenComparing(BalanceKey::materialType).thenComparing(BalanceKey::unit).thenComparing(BalanceKey::period)).map(key -> {
                    BigDecimal old = beforeResiduals.get(key);
                    BigDecimal now = afterResiduals.get(key);
                    BigDecimal delta = old == null || now == null ? null : now.subtract(old);
                    String status = residualsComplete(before) && residualsComplete(after)
                            ? Step7MaterialBalanceComparisonService.classifyChange(old, now, true)
                            : "INSUFFICIENT_EVIDENCE";
                    return new ResidualDelta(key.equipment(), key.process(), key.materialType(), key.unit(), key.period(),
                            old, now, delta, status);
                }).toList();
        return new ResidualDeltaReport(before, after, deltas,
                "residual_delta = after_residual - baseline_residual; both runs use the same immutable TEST_ONLY base network",
                false, false);
    }

    private List<ReviewLedgerRow> parseReviewLedger(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new InvalidWorkbookException("Review ledger is empty");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("담당자 검토대장");
            if (sheet == null) throw new InvalidWorkbookException("Missing sheet 담당자 검토대장");
            Row machineHeader = sheet.getRow(1);
            if (machineHeader == null) throw new InvalidWorkbookException("Machine-readable header row is missing");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String, Integer> columns = new HashMap<>();
            for (var cell : machineHeader) {
                String key = ExcelCellText.format(cell, formatter).trim();
                if (!key.isBlank()) columns.put(key, cell.getColumnIndex());
            }
            List<String> required = List.of("case_key", "source_sha256", "source_equipment", "target_equipment",
                    "review_actual_quantity", "review_unit", "review_material_type", "review_evidence",
                    "review_decision", "reviewer_name", "reviewed_date");
            if (!columns.keySet().containsAll(required)) {
                throw new InvalidWorkbookException("Review ledger machine keys do not match schema v1");
            }
            List<ReviewLedgerRow> rows = new ArrayList<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String key = value(row, columns, "case_key", formatter);
                if (key.isBlank()) continue;
                rows.add(new ReviewLedgerRow(key, value(row, columns, "source_sha256", formatter),
                        value(row, columns, "source_equipment", formatter), value(row, columns, "target_equipment", formatter),
                        decimal(value(row, columns, "review_actual_quantity", formatter)),
                        value(row, columns, "review_unit", formatter), value(row, columns, "review_material_type", formatter),
                        value(row, columns, "review_flow_type", formatter), value(row, columns, "review_evidence", formatter),
                        value(row, columns, "review_decision", formatter), value(row, columns, "reviewer_name", formatter),
                        value(row, columns, "reviewed_date", formatter), value(row, columns, "review_cycle_candidate", formatter)));
            }
            return List.copyOf(rows);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read review ledger without recalculation", exception);
        }
    }

    private List<String> validateLedgerRow(ReviewLedgerRow row) {
        List<String> errors = new ArrayList<>();
        if (!CASE_KEY.matcher(row.caseKey()).matches()) errors.add("INVALID_CASE_KEY");
        if (!EXPECTED_TEAM_SHA.equalsIgnoreCase(row.sourceSha256())) errors.add("SOURCE_SHA256_MISMATCH");
        if (blank(row.sourceEquipment()) || blank(row.targetEquipment())) errors.add("ENDPOINTS_REQUIRED");
        if (row.quantity() == null || row.quantity().signum() < 0) errors.add("NONNEGATIVE_REVIEW_QUANTITY_REQUIRED");
        if (blank(row.unit())) errors.add("REVIEW_UNIT_REQUIRED");
        if (blank(row.materialType())) errors.add("REVIEW_MATERIAL_TYPE_REQUIRED");
        if (blank(row.evidenceText())) errors.add("REVIEW_EVIDENCE_REQUIRED");
        if (blank(row.reviewer()) || row.reviewer().length() > 128) errors.add("REVIEWER_NAME_REQUIRED");
        try { LocalDate.parse(row.reviewedDate()); } catch (Exception ignored) { errors.add("REVIEWED_DATE_MUST_BE_ISO_DATE"); }
        if (!REVIEW_DECISIONS.contains(row.reviewDecision())) errors.add("INVALID_REVIEW_DECISION");
        return errors;
    }

    private void saveReview(ReviewLedgerRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceEquipment", row.sourceEquipment());
        snapshot.put("targetEquipment", row.targetEquipment());
        snapshot.put("quantity", row.quantity());
        snapshot.put("unit", row.unit());
        snapshot.put("materialType", row.materialType());
        snapshot.put("flowType", safe(row.flowType()));
        snapshot.put("evidenceText", row.evidenceText());
        snapshot.put("reviewDecision", row.reviewDecision());
        snapshot.put("reviewerNameDeclared", row.reviewer());
        snapshot.put("reviewedDate", row.reviewedDate());
        snapshot.put("cycleCandidate", safe(row.cycleCandidate()));
        snapshot.put("officialApprovalCreated", false);
        String afterJson = json(snapshot);
        String status = "TEST_ONLY_EVIDENCE_ACCEPTED".equals(row.reviewDecision())
                ? "TEST_ONLY_EVIDENCE_REVIEWED" : row.reviewDecision();
        Map<String, Object> old = jdbc.query("SELECT review_status, review_snapshot_json FROM balance_evidence_review_case WHERE case_key = ?",
                rs -> rs.next() ? Map.of("reviewStatus", rs.getString(1), "reviewSnapshot", rs.getString(2)) : null,
                row.caseKey());
        String eventHash = sha256((row.caseKey() + "|" + afterJson).getBytes(StandardCharsets.UTF_8));
        if (old != null && afterJson.equals(old.get("reviewSnapshot"))) return;
        if (old == null) {
            jdbc.update("""
                    INSERT INTO balance_evidence_review_case
                    (case_key, baseline_release_id, source_sha256, source_equipment, target_equipment,
                     reviewed_quantity, reviewed_unit, reviewed_material_type, reviewed_flow_type, evidence_text,
                     review_status, reviewer_name_declared, reviewed_date, review_snapshot_json,
                     official_approval_created, imported_by)
                    VALUES (?, (SELECT id FROM baseline_release WHERE version_code = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 'ADMIN_TOKEN_IMPORT')
                    """, row.caseKey(), BASELINE_VERSION, row.sourceSha256(), row.sourceEquipment(), row.targetEquipment(),
                    row.quantity(), row.unit(), row.materialType(), row.flowType(), row.evidenceText(), status,
                    row.reviewer(), LocalDate.parse(row.reviewedDate()), afterJson);
        } else {
            jdbc.update("""
                    UPDATE balance_evidence_review_case
                    SET source_sha256 = ?, source_equipment = ?, target_equipment = ?, reviewed_quantity = ?,
                        reviewed_unit = ?, reviewed_material_type = ?, reviewed_flow_type = ?, evidence_text = ?,
                        review_status = ?, reviewer_name_declared = ?, reviewed_date = ?, review_snapshot_json = ?,
                        imported_by = 'ADMIN_TOKEN_IMPORT', updated_at = CURRENT_TIMESTAMP
                    WHERE case_key = ?
                    """, row.sourceSha256(), row.sourceEquipment(), row.targetEquipment(), row.quantity(), row.unit(),
                    row.materialType(), row.flowType(), row.evidenceText(), status, row.reviewer(),
                    LocalDate.parse(row.reviewedDate()), afterJson, row.caseKey());
        }
        String beforeJson = old == null ? null : json(old);
        jdbc.update("""
                INSERT INTO balance_evidence_review_history
                (case_key, action_code, before_json, after_json, actor_source, event_sha256)
                VALUES (?, ?, ?, ?, 'ADMIN_TOKEN_IMPORT', ?)
                """, row.caseKey(), old == null ? "CREATED" : "UPDATED", beforeJson, afterJson, eventHash);
    }

    private List<LegacyBalanceIssue> legacyIssues(Step7MaterialBalanceComparisonService.ComparisonReport comparison) {
        return comparison.cachedBalances().stream()
                .filter(Step7MaterialBalanceComparisonService.BalanceSnapshot::pw01MasterEquipment)
                .filter(balance -> !"true".equalsIgnoreCase(balance.cachedCompared())
                        || balance.cachedResidual() == null
                        || balance.cachedResidual().compareTo(BigDecimal.ZERO) != 0)
                .map(balance -> {
                    String status = balance.cachedResidual() == null ? "CACHE_RESIDUAL_MISSING_UNRESOLVED"
                            : balance.cachedResidual().compareTo(BigDecimal.ZERO) == 0
                                    ? "CACHE_COMPARISON_FLAG_UNRESOLVED" : "PRE_EXISTING_DB_CACHE_RESIDUAL_UNRESOLVED";
                    return new LegacyBalanceIssue("CACHE-" + balance.equipment(), balance.equipment(),
                            balance.officialMajorProcessCode(), PROCESS_FILE, "도면 시트 미특정", null, null,
                            balance.cachedInflow(), balance.cachedOutflow(), balance.cachedResidual(), null, null,
                            "팀원 XLSM 저장 당시 비교 캐시(재계산하지 않음); 단위·기간·기준시점 미상",
                            true, false, "독립 수지자료 및 원인 근거 없음", "검토자 미지정", null, status,
                            "업로드 원본에 이미 존재한 캐시 불일치 표시. DB 오류/공식 도면 오류/운전 오류로 확정되지 않음.");
                }).toList();
    }

    private SyntheticDelta createSyntheticResidualDelta() {
        var nodes = List.of(
                new DirectedProcessNetwork.Node("SYN-SOURCE", null, "SYN-PROCESS", "TEST_SOURCE",
                        DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE, "Synthetic source", PROCESS_FILE, PROCESS_SHEET, "TEST_ONLY"),
                new DirectedProcessNetwork.Node("SYN-P1", "SYNTHETIC-P1", "SYN-PROCESS", "TEST_EQUIPMENT",
                        DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT, "Synthetic process", "TEST_ONLY_FIXTURE", "TEST_ONLY", "TEST_ONLY"),
                new DirectedProcessNetwork.Node("SYN-SINK", null, "SYN-PROCESS", "TEST_SINK",
                        DirectedProcessNetwork.NodeType.EXTERNAL_SINK, "Synthetic outlet", PROCESS_FILE, PROCESS_SHEET, "TEST_ONLY"));
        var edges = List.of(
                new DirectedProcessNetwork.Edge("SYN-IN", "SYN-SOURCE", "SYN-P1", null, "SYN-PROCESS",
                        DirectedProcessNetwork.MaterialClass.WASTEWATER, "일반폐수", "ton/day", new BigDecimal("100"),
                        DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, DirectedProcessNetwork.QuantityBasis.USER_INPUT,
                        true, true, "Synthetic fixture only", "TEST_ONLY", "2026-10-01/TEST_PERIOD"),
                new DirectedProcessNetwork.Edge("SYN-OUT", "SYN-P1", "SYN-SINK", "SYN-PROCESS", null,
                        DirectedProcessNetwork.MaterialClass.WASTEWATER, "일반폐수", "ton/day", new BigDecimal("90"),
                        DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, DirectedProcessNetwork.QuantityBasis.USER_INPUT,
                        true, true, "Synthetic fixture only", "TEST_ONLY", "2026-10-01/TEST_PERIOD"));
        var network = new DirectedProcessNetwork("SYNTHETIC-STEP7-3", "Residual delta example", "TEST_ONLY",
                nodes, edges, List.of());
        ResidualDeltaReport report = compareTestOnlyScenario(network, "SYN-OUT", new BigDecimal("85"));
        return new SyntheticDelta(report.baseline().nodes().stream().filter(node -> "SYNTHETIC-P1".equals(node.equipmentNumber()))
                .map(DirectedProcessNetworkSolver.NodeBalance::residual).findFirst().orElse(null),
                report.after().nodes().stream().filter(node -> "SYNTHETIC-P1".equals(node.equipmentNumber()))
                        .map(DirectedProcessNetworkSolver.NodeBalance::residual).findFirst().orElse(null),
                report.deltas().stream().filter(delta -> "SYNTHETIC-P1".equals(delta.equipment()))
                        .map(ResidualDelta::delta).findFirst().orElse(null), report.baseline().status(), report.after().status());
    }

    private boolean residualsComplete(DirectedProcessNetworkSolver.Result result) {
        return result != null && !Set.of("CALCULATION_UNAVAILABLE", "UNDERDETERMINED", "INCONSISTENT",
                        "CONSTRAINT_VIOLATION").contains(result.status())
                && result.nodes().stream().allMatch(node -> node.residual() != null)
                && result.processes().stream().allMatch(process -> process.residual() != null)
                && result.external().stream().allMatch(external -> external.residual() != null);
    }

    private Map<BalanceKey, BigDecimal> residuals(DirectedProcessNetworkSolver.Result result) {
        Map<BalanceKey, BigDecimal> values = new LinkedHashMap<>();
        for (var node : result.nodes()) {
            values.put(new BalanceKey(node.equipmentNumber() == null ? node.label() : node.equipmentNumber(),
                    node.processCode(), node.materialType(), node.unit(), node.period()), node.residual());
        }
        return values;
    }


    private BaselineFiles loadBaselineFiles() {
        List<BaselineFile> files = jdbc.query("""
                SELECT original_filename, sha256, content_bytes FROM baseline_document
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)
                  AND original_filename IN (?, ?)
                """, (rs, row) -> new BaselineFile(rs.getString("original_filename"), rs.getString("sha256"),
                rs.getBytes("content_bytes")), BASELINE_VERSION, PROCESS_FILE, MASTER_FILE);
        BaselineFile process = files.stream().filter(file -> PROCESS_FILE.equals(file.filename())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Locked PW-01 baseline is unavailable"));
        BaselineFile master = files.stream().filter(file -> MASTER_FILE.equals(file.filename())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Locked V1.0 master is unavailable"));
        if (!process.sha256().equalsIgnoreCase(sha256(process.bytes()))
                || !master.sha256().equalsIgnoreCase(sha256(master.bytes()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored V1.0 baseline hash check failed");
        }
        return new BaselineFiles(process.bytes(), process.sha256(), master.bytes(), master.sha256());
    }

    private OfficialSnapshot loadOfficialSnapshot() {
        int docs = count("SELECT COUNT(*) FROM baseline_document WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)", BASELINE_VERSION);
        int equipment = count("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)", BASELINE_VERSION);
        int flows = count("SELECT COUNT(*) FROM equipment_flow ef JOIN equipment_record er ON er.id=ef.equipment_id WHERE er.baseline_release_id=(SELECT id FROM baseline_release WHERE version_code = ?)", BASELINE_VERSION);
        int confirmed = count("SELECT COUNT(*) FROM process_relation WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code = ?) AND relation_status='CONFIRMED'", BASELINE_VERSION);
        int review = count("SELECT COUNT(*) FROM process_relation WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code = ?) AND relation_status='REVIEW_REQUIRED'", BASELINE_VERSION);
        int connectors = count("""
                SELECT COUNT(*) FROM drawing_object o JOIN workbook_sheet s ON s.id=o.workbook_sheet_id
                JOIN baseline_document d ON d.id=s.baseline_document_id
                WHERE d.baseline_release_id=(SELECT id FROM baseline_release WHERE version_code = ?)
                  AND d.original_filename=? AND o.object_type='CONNECTOR'
                """, BASELINE_VERSION, PROCESS_FILE);
        int bindings = count("""
                SELECT COUNT(*) FROM drawing_object o JOIN workbook_sheet s ON s.id=o.workbook_sheet_id
                JOIN baseline_document d ON d.id=s.baseline_document_id
                WHERE d.baseline_release_id=(SELECT id FROM baseline_release WHERE version_code = ?)
                  AND d.original_filename=? AND o.object_type='CONNECTOR'
                  AND (o.start_connected_shape_id IS NOT NULL OR o.end_connected_shape_id IS NOT NULL)
                """, BASELINE_VERSION, PROCESS_FILE);
        return new OfficialSnapshot(docs, equipment, flows, confirmed, review, connectors, bindings);
    }


    private String columnName(int column) {
        StringBuilder name = new StringBuilder();
        while (column > 0) {
            int remainder = (column - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            column = (column - 1) / 26;
        }
        return name.toString();
    }

    private String safe(String value) { return value == null ? "" : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private BigDecimal decimal(String text) {
        if (blank(text)) return null;
        try { return new BigDecimal(text.replace(",", "").trim()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private String normalizeUnit(String value) { return value == null ? "" : value.replaceAll("[\\s]", "").toLowerCase(Locale.ROOT); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String value(Row row, Map<String, Integer> columns, String key, DataFormatter formatter) {
        Integer column = columns.get(key);
        if (column == null) return "";
        return ExcelCellText.format(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter).trim();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize review snapshot", exception); }
    }
    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private List<ManualFixtureAudit> auditManualFixtures(
            Step7MaterialBalanceComparisonService.ComparisonReport comparison,
            Step7DrawingEvidenceExtractor.SheetEvidence drawing,
            Map<String, List<Step7DrawingEvidenceExtractor.EquipmentLabel>> labels) {
        Map<Integer, Step7MaterialBalanceComparisonService.ConnectionComparison> byRow = comparison.connections().stream()
                .collect(java.util.stream.Collectors.toMap(Step7MaterialBalanceComparisonService.ConnectionComparison::sourceRow,
                        row -> row, (left, right) -> left));
        Map<Integer, WorkbookInventory.DrawingObject> connectorById = drawing.connectors().stream()
                .filter(object -> object.shapeId() != null).collect(java.util.stream.Collectors.toMap(
                        object -> Integer.parseInt(object.shapeId()), object -> object, (left, right) -> left));
        Map<String, Step7DrawingEvidenceExtractor.CellEvidence> cells = drawing.rawCells().stream()
                .collect(java.util.stream.Collectors.toMap(Step7DrawingEvidenceExtractor.CellEvidence::cellAddress,
                        cell -> cell, (left, right) -> left));
        List<ManualFixtureAudit> result = new ArrayList<>();
        for (ManualCandidateFixture fixture : manualCandidateFixtures()) {
            var transfer = byRow.get(fixture.dbRow());
            WorkbookInventory.DrawingObject connector = connectorById.get(fixture.connectorId());
            var flow = cells.get(fixture.flowCell());
            boolean endpointsMatch = transfer != null && fixture.source().equals(transfer.source())
                    && fixture.target().equals(transfer.target());
            boolean bound = connector != null && (connector.startConnectedShapeId() != null
                    || connector.endConnectedShapeId() != null);
            String note = "HAND_AUTHORED_TEST_FIXTURE only; not counted as auto-extracted candidate or confirmed link. ";
            note += endpointsMatch ? "DB source row endpoints match fixture labels. " : "DB row/endpoints mismatch or missing. ";
            note += connector == null ? "connector ID absent from actual drawing. "
                    : "connector ID exists as DrawingML connector. ";
            note += bound ? "endpoint binding present; still requires logical equipment target validation. "
                    : "stCxn/endCxn endpoint binding absent; anchors are visual coordinates only. ";
            note += flow == null ? "flow cell absent/blank. " : "flow cell read as " + flow.cellType() + " value '" + flow.displayValue() + "'.";
            String actualSourceCell = labels.getOrDefault(fixture.source(), List.of()).stream()
                    .filter(label -> fixture.sourceCell().equals(label.cellAddress()))
                    .map(Step7DrawingEvidenceExtractor.EquipmentLabel::cellAddress).findFirst().orElse("");
            String actualTargetCell = labels.getOrDefault(fixture.target(), List.of()).stream()
                    .filter(label -> fixture.targetCell().equals(label.cellAddress()))
                    .map(Step7DrawingEvidenceExtractor.EquipmentLabel::cellAddress).findFirst().orElse("");
            result.add(new ManualFixtureAudit(fixture.caseKey(), fixture.dbRow(), fixture.source(), fixture.target(),
                    fixture.sourceCell(), fixture.targetCell(), fixture.connectorId(), actualSourceCell, actualTargetCell,
                    connector == null ? -1 : Integer.parseInt(connector.shapeId()),
                    connector == null ? "" : cellAddress(connector.startColumn(), connector.startRow()),
                    connector == null ? "" : cellAddress(connector.endColumn(), connector.endRow()),
                    connector == null || connector.headArrowType() == null ? "" : connector.headArrowType(),
                    connector == null || connector.tailArrowType() == null ? "" : connector.tailArrowType(),
                    flow == null ? fixture.flowCell() : flow.cellAddress(), flow == null ? "" : flow.displayValue(),
                    transfer == null ? null : transfer.quantity(), transfer == null ? "" : transfer.quantityText(),
                    transfer == null ? "SOURCE_ROW_NOT_FOUND" : transfer.quantityState(),
                    transfer == null ? "" : transfer.materialType(), bound,
                    labels.containsKey(fixture.source()) ? labelCells(labels.get(fixture.source())) : "",
                    labels.containsKey(fixture.target()) ? labelCells(labels.get(fixture.target())) : "", note));
        }
        return List.copyOf(result);
    }

    private List<ManualCandidateFixture> manualCandidateFixtures() {
        return List.of(
                new ManualCandidateFixture("PW0105-DB-2712", 2712, "C-PW15017", "C-PW15018", "D78", "AM75", 125, "F80"),
                new ManualCandidateFixture("PW0105-DB-2713", 2713, "C-PW15017", "C-PW15005", "AI42", "AI98", 114, "AH51"),
                new ManualCandidateFixture("PW0105-DB-2716", 2716, "C-PW15018", "C-PW15007", "D150", "AI148", 149, "D152"),
                new ManualCandidateFixture("PW0105-DB-2700", 2700, "C-PW15008", "C-PW15010", "D200", "AI198", 76, "D202"),
                new ManualCandidateFixture("PW0105-DB-2703", 2703, "C-PW15009", "C-PW15010", "AI183", "AI198", 90, "AI182"),
                new ManualCandidateFixture("PW0105-DB-2708", 2708, "C-PW15010", "C-PW15008", "AI198", "BG207", 53, "BB207"),
                new ManualCandidateFixture("PW0105-DB-2707", 2707, "C-PW15010", "C-PW15009", "AI198", "BG210", 10, "BB210"));
    }

    private List<ConnectorHint> spatialConnectorHints(List<Step7DrawingEvidenceExtractor.EquipmentLabel> sources,
                                                       List<Step7DrawingEvidenceExtractor.EquipmentLabel> targets,
                                                       List<WorkbookInventory.DrawingObject> connectors) {
        List<ConnectorHint> ranked = new ArrayList<>();
        for (var connector : connectors) {
            if (connector.shapeId() == null || connector.startColumn() == null || connector.startRow() == null
                    || connector.endColumn() == null || connector.endRow() == null) continue;
            EndpointDistance best = null;
            for (var source : sources) for (var target : targets) {
                int[] sourceCell = parseCell(source.cellAddress());
                int[] targetCell = parseCell(target.cellAddress());
                int directStart = manhattan(connector.startColumn(), connector.startRow(), sourceCell[0], sourceCell[1]);
                int directEnd = manhattan(connector.endColumn(), connector.endRow(), targetCell[0], targetCell[1]);
                int reverseStart = manhattan(connector.startColumn(), connector.startRow(), targetCell[0], targetCell[1]);
                int reverseEnd = manhattan(connector.endColumn(), connector.endRow(), sourceCell[0], sourceCell[1]);
                EndpointDistance direct = new EndpointDistance(directStart + directEnd, Math.max(directStart, directEnd),
                        source.cellAddress(), target.cellAddress());
                EndpointDistance reverse = new EndpointDistance(reverseStart + reverseEnd, Math.max(reverseStart, reverseEnd),
                        target.cellAddress(), source.cellAddress());
                EndpointDistance candidate = direct.compareTo(reverse) <= 0 ? direct : reverse;
                if (best == null || candidate.compareTo(best) < 0) best = candidate;
            }
            if (best == null) continue;
            String orientation = connector.startRow().equals(connector.endRow()) ? "수평 anchor"
                    : connector.startColumn().equals(connector.endColumn()) ? "수직 anchor" : "대각 anchor";
            ranked.add(new ConnectorHint(Integer.parseInt(connector.shapeId()), cellAddress(connector.startColumn(), connector.startRow()),
                    cellAddress(connector.endColumn(), connector.endRow()), best.sourceCell(), best.targetCell(),
                    connector.startConnectedShapeId(), connector.endConnectedShapeId(), connector.headArrowType(),
                    connector.tailArrowType(), orientation, best.totalDistance(), best.maxDistance(),
                    connector.startConnectedShapeId() != null || connector.endConnectedShapeId() != null,
                    connector.geometryPreset()));
        }
        ranked.sort(java.util.Comparator.comparingInt(ConnectorHint::totalAnchorToLabelDistance)
                .thenComparingInt(ConnectorHint::maxAnchorToLabelDistance).thenComparingInt(ConnectorHint::shapeId));
        return ranked.stream().filter(hint -> hint.totalAnchorToLabelDistance() <= 20
                && hint.maxAnchorToLabelDistance() <= 12).limit(3).toList();
    }

    private List<FlowEvidence> nearbyFlowHints(List<ConnectorHint> connectors,
                                              List<Step7DrawingEvidenceExtractor.FlowLabel> flows) {
        Map<String, FlowEvidence> result = new LinkedHashMap<>();
        for (var connector : connectors) {
            int[] start = parseCell(connector.anchorStartCell());
            int[] end = parseCell(connector.anchorEndCell());
            flows.stream().map(flow -> new FlowDistance(flow, Math.min(
                            manhattan(start[0], start[1], parseCell(flow.cellAddress())[0], parseCell(flow.cellAddress())[1]),
                            manhattan(end[0], end[1], parseCell(flow.cellAddress())[0], parseCell(flow.cellAddress())[1]))))
                    .filter(item -> item.distance() <= 4)
                    .sorted(java.util.Comparator.comparingInt(FlowDistance::distance))
                    .limit(2)
                    .forEach(item -> result.putIfAbsent(item.flow().cellAddress(), new FlowEvidence(
                            item.flow().cellAddress(), item.flow().rawCellText(), item.flow().materialName(),
                            item.flow().quantity(), item.flow().unit(), item.flow().cellType(),
                            "NEAR_CONNECTOR_SPATIAL_HINT; not a linked endpoint", item.distance())));
        }
        return List.copyOf(result.values());
    }

    private String labelCells(List<Step7DrawingEvidenceExtractor.EquipmentLabel> labels) {
        return labels.stream().map(Step7DrawingEvidenceExtractor.EquipmentLabel::cellAddress)
                .distinct().sorted().reduce((left, right) -> left + ", " + right).orElse("");
    }

    private int[] parseCell(String address) {
        org.apache.poi.ss.util.CellReference ref = new org.apache.poi.ss.util.CellReference(address);
        return new int[]{ref.getCol(), ref.getRow()};
    }

    private int manhattan(int colA, int rowA, int colB, int rowB) {
        return Math.abs(colA - colB) + Math.abs(rowA - rowB);
    }

    private String cellAddress(Integer zeroBasedColumn, Integer zeroBasedRow) {
        if (zeroBasedColumn == null || zeroBasedRow == null) return "";
        return org.apache.poi.ss.util.CellReference.convertNumToColString(zeroBasedColumn) + (zeroBasedRow + 1);
    }

    private DirectedProcessNetwork.Node node(String equipmentNumber, EquipmentMap mapping) {
        return new DirectedProcessNetwork.Node(equipmentNumber, equipmentNumber, mapping.majorProcessCode(),
                mapping.mappingStatus(), DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT, equipmentNumber,
                PROCESS_FILE, mapping.sheetName(), "TEST_ONLY");
    }

    private DirectedProcessNetwork.MaterialClass materialClass(String type) {
        if ("WASTE".equalsIgnoreCase(type)) return DirectedProcessNetwork.MaterialClass.WASTE;
        if ("CHEMICAL".equalsIgnoreCase(type)) return DirectedProcessNetwork.MaterialClass.CHEMICAL;
        return DirectedProcessNetwork.MaterialClass.WASTEWATER;
    }

    public record ManualCandidateFixture(String caseKey, int dbRow, String source, String target,
                                         String sourceCell, String targetCell, int connectorId, String flowCell) { }

    public record EquipmentMap(String equipmentNumber, String majorProcessCode, String middleProcessCode,
                               String mappingStatus, String filename, String sheetName) { }

    public record ConnectorHint(int shapeId, String anchorStartCell, String anchorEndCell,
                                String sourceLabelCellHint, String targetLabelCellHint,
                                String startConnectedShapeId, String endConnectedShapeId,
                                String headArrowType, String tailArrowType, String anchorOrientation,
                                int totalAnchorToLabelDistance, int maxAnchorToLabelDistance,
                                boolean explicitEndpointBinding, String geometryPreset) { }

    public record FlowEvidence(String cellAddress, String rawText, String materialName,
                               BigDecimal quantity, String unit, String cellType,
                               String associationBasis, int anchorDistance) {
        public String description() { return cellAddress + "=" + rawText + " [" + associationBasis + "]"; }
    }

    public record ConnectionCandidate(String caseKey, int sourceRow, String source, String target,
                                      String sourceCellHints, String targetCellHints, BigDecimal quantity,
                                      String quantityText, String quantityState, String materialType,
                                      List<ConnectorHint> connectorHints, List<FlowEvidence> flowHints,
                                      String missingEvidence, String rawStatus, String candidateStatus,
                                      String evidenceStatus, String comparisonStatus, String candidateOrigin) {
        public ConnectionCandidate {
            connectorHints = List.copyOf(connectorHints);
            flowHints = List.copyOf(flowHints);
        }
    }

    public record ManualFixtureAudit(String caseKey, int dbRow, String source, String target,
                                     String expectedSourceCell, String expectedTargetCell, int expectedConnectorId,
                                     String actualSourceCell, String actualTargetCell, int actualConnectorId,
                                     String anchorStartCell, String anchorEndCell, String headArrowType,
                                     String tailArrowType, String flowCell, String flowText,
                                     BigDecimal dbQuantity, String dbQuantityText, String dbQuantityState,
                                     String dbMaterialType, boolean explicitEndpointBinding,
                                     String sourceCellHints, String targetCellHints, String auditNote) {
        public String arrowDescription() { return "head=" + safeStatic(headArrowType) + ", tail=" + safeStatic(tailArrowType); }
    }

    public record LegacyBalanceIssue(String caseKey, String equipment, String processCode,
                                     String diagramFile, String diagramSheet, BigDecimal officialValue,
                                     BigDecimal officialResidual, BigDecimal cachedDbInflow,
                                     BigDecimal cachedDbOutflow, BigDecimal cachedDbResidual,
                                     BigDecimal residualBeforeChange, BigDecimal residualAfterChange,
                                     String dataBasis, boolean preExistingInSourceCache,
                                     boolean causeConfirmed, String evidence, String reviewer,
                                     LocalDate reviewedDate, String status, String note) { }

    public record ReviewLedgerRow(String caseKey, String sourceSha256, String sourceEquipment,
                                  String targetEquipment, BigDecimal quantity, String unit, String materialType,
                                  String flowType, String evidenceText, String reviewDecision,
                                  String reviewer, String reviewedDate, String cycleCandidate) {
        public boolean isEmptyReviewerRow() {
            return isBlank(sourceEquipment) && isBlank(targetEquipment) && quantity == null && isBlank(unit)
                    && isBlank(materialType) && isBlank(evidenceText) && isBlank(reviewDecision)
                    && isBlank(reviewer) && isBlank(reviewedDate);
        }
        private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    }

    public record EvidenceWorkbookData(String teamFilename, String teamSha256,
                                      String officialProcessSha256, String officialMasterSha256,
                                      OfficialSnapshot officialSnapshot,
                                      Step7DrawingEvidenceExtractor.SheetEvidence drawingEvidence,
                                      Step7MaterialBalanceComparisonService.ComparisonReport comparison,
                                      List<ConnectionCandidate> candidates, List<ManualFixtureAudit> manualFixtureAudits,
                                      List<LegacyBalanceIssue> legacyIssues, SyntheticDelta syntheticDelta,
                                      List<String> warnings, long verifiedRelationCount, String methodSummary) {
        public EvidenceWorkbookData {
            candidates = List.copyOf(candidates);
            manualFixtureAudits = List.copyOf(manualFixtureAudits);
            legacyIssues = List.copyOf(legacyIssues);
            warnings = List.copyOf(warnings);
        }
    }

    public record OfficialSnapshot(int documentCount, int equipmentCount, int equipmentFlowCount,
                                   int confirmedRelationCount, int reviewRelationCount,
                                   int connectorCount, int explicitBindingCount) { }

    public record ReviewImportResult(int rowsRead, int rowsPersisted, int rowsIgnored,
                                     int rowsRejected, List<String> issues,
                                     boolean officialApprovalCreated, String notice) { }

    public record ReviewedEvidence(String caseKey, String sourceEquipment, String targetEquipment,
                                   BigDecimal quantity, String unit, String materialType,
                                   String flowType, String evidenceText, String reviewDecision,
                                   String reviewer, LocalDate reviewedAt, boolean cycleCandidate) { }

    public record Step7EvidenceNetworkDraft(DirectedProcessNetwork network, boolean officialApprovalCreated,
                                            String notice) { }

    public record BalanceKey(String equipment, String process, String materialType, String unit, String period) { }
    public record ResidualDelta(String equipment, String process, String materialType,
                                String unit, String period, BigDecimal baselineResidual, BigDecimal afterResidual,
                                BigDecimal delta, String status) { }

    public record ResidualDeltaReport(DirectedProcessNetworkSolver.Result baseline,
                                      DirectedProcessNetworkSolver.Result after,
                                      List<ResidualDelta> deltas, String formula,
                                      boolean officialDiagramModified, boolean officialApprovalCreated) { }

    public record SyntheticDelta(BigDecimal baselineResidual, BigDecimal afterResidual,
                                 BigDecimal residualDelta, String baselineStatus, String afterStatus) { }

    private record BaselineFile(String filename, String sha256, byte[] bytes) { }
    private record BaselineFiles(byte[] processBytes, String processSha256, byte[] masterBytes, String masterSha256) { }
    private record EndpointDistance(int totalDistance, int maxDistance, String sourceCell, String targetCell)
            implements Comparable<EndpointDistance> {
        @Override public int compareTo(EndpointDistance other) {
            int compare = Integer.compare(totalDistance, other.totalDistance);
            if (compare != 0) return compare;
            compare = Integer.compare(maxDistance, other.maxDistance);
            if (compare != 0) return compare;
            compare = sourceCell.compareTo(other.sourceCell);
            return compare != 0 ? compare : targetCell.compareTo(other.targetCell);
        }
    }
    private record FlowDistance(Step7DrawingEvidenceExtractor.FlowLabel flow, int distance) { }

    private static String safeStatic(String value) { return value == null ? "" : value; }
}
