package com.example.processautomation.material;

import com.example.processautomation.material.DirectedProcessNetwork.Edge;
import com.example.processautomation.material.DirectedProcessNetwork.Node;
import com.example.processautomation.material.DirectedProcessNetwork.ProcessTerm;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Session-scoped TEST_ONLY What-if orchestration. It never creates official relations or edits drawings. */
@Service
public class NetworkWhatIfService {
    private final JdbcTemplate jdbc;
    private final MaterialBalanceCalculator calculator;

    public NetworkWhatIfService(JdbcTemplate jdbc, MaterialBalanceCalculator calculator) {
        this.jdbc = jdbc;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> actualReadiness() {
        int confirmed = count("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'");
        int verifiedReviews = count("SELECT COUNT(*) FROM balance_evidence_review_case WHERE review_state='EVIDENCE_VERIFIED'");
        int typedRoutes = count("SELECT COUNT(*) FROM balance_evidence_review_route");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "CALCULATION_UNAVAILABLE");
        result.put("calculationAvailable", false);
        result.put("confirmedRelations", confirmed);
        result.put("evidenceVerifiedReviews", verifiedReviews);
        result.put("typedRouteProfiles", typedRoutes);
        result.put("reason", confirmed == 0 || verifiedReviews == 0 || typedRoutes == 0
                ? "NO_COMPLETE_EVIDENCE_VERIFIED_ROUTE_SCOPE"
                : "REVIEW_ROUTE_SCOPE_AND_PERIOD_ALIGNMENT_NOT_VALIDATED_FOR_LIVE_CALCULATION");
        result.put("notice", "실제 사업장 물질수지는 현재 계산하지 않습니다. 합성 TEST_ONLY 결과와 공식 자료를 분리합니다.");
        return Map.copyOf(result);
    }

    @Transactional(readOnly = true)
    public WhatIfReport calculate(NetworkWhatIfRequest request) {
        return calculateInternal(request, actualReadiness());
    }

    /**
     * Package-scoped in-memory solver path for server-owned synthetic fixtures only.
     * User/API payloads must use calculate(), which applies actual readiness gating.
     */
    WhatIfReport calculateTestOnly(NetworkWhatIfRequest request) {
        Map<String, Object> readiness = Map.of("status", "CALCULATION_UNAVAILABLE", "calculationAvailable", false,
                "reason", "SYNTHETIC_TEST_ONLY_READINESS_NOT_QUERIED",
                "notice", "합성 TEST_ONLY 계산이며 실제 사업장 물질수지 readiness는 평가하지 않습니다.");
        return calculateInternal(request, readiness);
    }

    private WhatIfReport calculateInternal(NetworkWhatIfRequest request, Map<String, Object> readiness) {
        validateRequest(request);
        DirectedProcessNetwork network = buildNetwork(request);
        DirectedProcessNetworkSolver.Result baseline = calculator.calculateDirectedNetwork(network);
        List<ScenarioResult> cases = new ArrayList<>();
        for (NetworkWhatIfRequest.ChangeCase change : request.scenarios()) {
            DirectedProcessNetworkSolver.Result result = calculator.recalculateDirectedNetwork(network,
                    change.scenarioId(), request.changedConnectionId(), change.newQuantity());
            cases.add(new ScenarioResult(change.scenarioId(), change.description(),
                    request.baseQuantity(), change.newQuantity(), result));
        }
        return new WhatIfReport(request.simulationId(), request.scenarioName(), "TEST_ONLY", readiness,
                baseline, List.copyOf(cases), false, false,
                "합성 입력 네트워크 전용 계산입니다. 실제 도면·운전값·공식 관계를 나타내지 않습니다.");
    }

    private void validateRequest(NetworkWhatIfRequest request) {
        if (request == null) throw bad("요청 본문이 필요합니다.");
        if (!"TEST_ONLY".equals(request.mode())) throw bad("TEST_ONLY 계산만 허용됩니다.");
        if (blank(request.simulationId()) || blank(request.scenarioName()) || blank(request.processCode())
                || blank(request.sourceEquipment()) || blank(request.targetEquipment())
                || request.materialClass() == null || blank(request.materialType()) || blank(request.unit())
                || blank(request.period()) || "TEST_ONLY_UNSPECIFIED".equalsIgnoreCase(request.period().trim())) {
            throw bad("네트워크 식별, 물질, 단위, 설비, 측정 기간은 모두 필요합니다.");
        }
        if (request.baseQuantity() == null || request.baseQuantity().signum() < 0) throw bad("변경 전 유량은 0 이상이어야 합니다.");
        if (request.scenarios().size() < 1 || request.scenarios().size() > 20) throw bad("What-if 시나리오는 1~20개여야 합니다.");
        Set<String> scenarioIds = new HashSet<>();
        for (var scenario : request.scenarios()) {
            if (scenario.newQuantity() == null || scenario.newQuantity().signum() < 0) throw bad("시나리오 유량은 0 이상이어야 합니다.");
            if (!scenarioIds.add(scenario.scenarioId())) throw bad("시나리오 ID는 중복될 수 없습니다.");
        }
        NetworkWhatIfRequest.Edge changed = request.edges().stream().filter(edge -> request.changedConnectionId().equals(edge.connectionId()))
                .findFirst().orElseThrow(() -> bad("변경 대상 연결 ID가 입력 네트워크에 없습니다."));
        if (!changed.fixed() || changed.quantity() == null || changed.quantity().compareTo(request.baseQuantity()) != 0) {
            throw bad("변경 전 유량은 고정된 대상 연결의 입력 유량과 일치해야 합니다.");
        }
        if (!request.sourceEquipment().equals(equipmentForNode(request.nodes(), changed.sourceNodeId()))
                || !request.targetEquipment().equals(equipmentForNode(request.nodes(), changed.targetNodeId()))) {
            throw bad("출발·도착 설비는 선택한 대상 연결의 endpoint와 일치해야 합니다.");
        }
        if (request.materialClass() != changed.materialClass()
                || !same(request.materialType(), changed.materialType())
                || !same(request.unit(), changed.unit()) || !same(request.period(), changed.period())) {
            throw bad("물질 분류·종류·단위·측정 기간은 변경 대상 연결의 값과 일치해야 합니다.");
        }
        if (!request.processCode().equals(processFor(request.nodes(), changed.sourceNodeId()))
                && !request.processCode().equals(processFor(request.nodes(), changed.targetNodeId()))) {
            throw bad("선택 공정은 변경 대상 연결의 출발 또는 도착 공정이어야 합니다.");
        }
        for (var edge : request.edges()) {
            if (edge.quantity() != null && edge.quantity().signum() < 0) {
                throw bad("유량은 음수가 될 수 없습니다: " + edge.connectionId());
            }
            if (blank(edge.period()) || "TEST_ONLY_UNSPECIFIED".equalsIgnoreCase(edge.period().trim())) {
                throw bad("각 연결에 측정 기간을 명시해야 합니다.");
            }
            if (!request.period().equals(edge.period())) {
                throw bad("현재 What-if 입력은 한 측정 기간으로 제한됩니다. 기간별 별도 요청으로 나누세요.");
            }
        }
    }

    private DirectedProcessNetwork buildNetwork(NetworkWhatIfRequest request) {
        List<Node> nodes = request.nodes().stream().map(node -> new Node(node.nodeId(), node.equipmentNumber(),
                node.processCode(), node.equipmentType(), node.nodeType(), node.label(), node.sourceDocument(),
                node.sourceSheet(), "TEST_ONLY")).toList();
        List<Edge> edges = request.edges().stream().map(edge -> new Edge(edge.connectionId(), edge.sourceNodeId(),
                edge.targetNodeId(), emptyToNull(edge.sourceProcess()), emptyToNull(edge.targetProcess()),
                edge.materialClass(), edge.materialType(), edge.unit(), edge.quantity(), edge.connectionType(),
                edge.quantityBasis(), true, edge.fixed(), "USER-SUPPLIED TEST_ONLY: " + edge.evidence(),
                "TEST_ONLY", edge.period())).toList();
        List<ProcessTerm> terms = request.processTerms() == null ? List.of() : request.processTerms().stream()
                .map(term -> new ProcessTerm(emptyToNull(term.nodeId()), term.processCode(), term.materialClass(),
                        term.materialType(), term.unit(), term.netGeneration(), term.quantityBasis(),
                        "USER-SUPPLIED TEST_ONLY: " + term.evidence(), "TEST_ONLY", term.period())).toList();
        return new DirectedProcessNetwork(request.simulationId(), request.scenarioName(), "TEST_ONLY", nodes, edges, terms);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String equipmentForNode(List<NetworkWhatIfRequest.Node> nodes, String nodeId) {
        return nodes.stream().filter(node -> node.nodeId().equals(nodeId)).map(NetworkWhatIfRequest.Node::equipmentNumber)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private String processFor(List<NetworkWhatIfRequest.Node> nodes, String nodeId) {
        return nodes.stream().filter(node -> node.nodeId().equals(nodeId)).map(NetworkWhatIfRequest.Node::processCode)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private boolean same(String left, String right) {
        return left != null && right != null && left.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(right.trim().replaceAll("\\s+", " "));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }

    /** Publicly typed projection of package-private solver records for cross-package TEST_ONLY verification. */
    public static List<CalculatedEdgeSnapshot> calculatedEdgeSnapshots(WhatIfReport report) {
        if (report == null || report.baseline() == null) return List.of();
        return report.baseline().edges().stream().map(edge -> new CalculatedEdgeSnapshot(edge.connectionId(),
                edge.sourceEquipment(), edge.targetEquipment(), edge.sourceProcess(), edge.targetProcess(),
                edge.materialClass(), edge.materialType(), edge.unit(), edge.quantity(), edge.connectionType(),
                edge.status(), edge.period(), edge.fixed(), edge.quantityBasis(), edge.evidence())).toList();
    }

    public static boolean fullyCalculated(WhatIfReport report) {
        return report != null && report.baseline() != null && "CALCULATED".equals(report.baseline().status())
                && report.scenarios().stream().allMatch(item -> item.result() != null
                && "CALCULATED".equals(item.result().status()));
    }

    public record CalculatedEdgeSnapshot(String connectionId, String sourceEquipment, String targetEquipment,
                                         String sourceProcess, String targetProcess,
                                         DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                                         String unit, BigDecimal quantity, DirectedProcessNetwork.ConnectionType connectionType,
                                         String status, String period, boolean fixed, DirectedProcessNetwork.QuantityBasis quantityBasis,
                                         String evidence) { }

    public record ScenarioResult(String scenarioId, String description, BigDecimal beforeQuantity,
                                 BigDecimal afterQuantity, DirectedProcessNetworkSolver.Result result) { }
    public record WhatIfReport(String simulationId, String scenarioName, String mode,
                               Map<String, Object> actualReadiness,
                               DirectedProcessNetworkSolver.Result baseline,
                               List<ScenarioResult> scenarios,
                               boolean officialDiagramModified, boolean officialMasterModified,
                               String notice) { }
}
