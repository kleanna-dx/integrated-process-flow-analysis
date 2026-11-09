package com.example.processautomation.material;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads only versioned review evidence and runs isolated Step 7-1 TEST_ONLY scenarios. */
@Service
public class Step7ReviewNetworkWorkflowService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Step7ReviewNetworkAdapter adapter;
    private final MaterialBalanceCalculator calculator;

    public Step7ReviewNetworkWorkflowService(JdbcTemplate jdbc, ObjectMapper mapper,
                                             Step7ReviewNetworkAdapter adapter,
                                             MaterialBalanceCalculator calculator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.adapter = adapter;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public IntegrationReport buildIntegrationReport(String snapshotId) {
        Snapshot snapshot = findSnapshot(snapshotId);
        List<Step7ReviewNetworkAdapter.ReviewEvidence> actualReviews = loadReviews(snapshot.id());
        Set<String> expected = actualReviews.stream().map(Step7ReviewNetworkAdapter.ReviewEvidence::candidateId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var actualScope = new Step7ReviewNetworkAdapter.GraphScope("TEST_ONLY", snapshot.baselineVersion(), snapshot.locked(),
                snapshot.id(), "ACTUAL-PW0105-READINESS", "Actual PW-01-05 readiness only", "",
                expected, List.of(), List.of(), Map.of(), false, false, false, false, false, false,
                "", List.of());
        var actualPrepared = adapter.adapt(actualReviews, List.of(), actualScope);
        List<ScenarioResult> syntheticScenarios = runSyntheticScenarios();
        return new IntegrationReport(snapshot.id(), snapshot.baselineVersion(), actualReviews.size(),
                actualReviews.stream().filter(r -> "EVIDENCE_VERIFIED".equals(r.reviewState())).count(),
                actualPrepared.candidates().stream().filter(Step7ReviewNetworkAdapter.CandidateAssessment::eligible).count(),
                actualPrepared, syntheticScenarios,
                "Actual PW-01-05 input is read-only readiness data. All solver values below are separate synthetic TEST_ONLY fixtures.");
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String snapshotId) { return findSnapshot(snapshotId); }

    private Snapshot findSnapshot(String requestedId) {
        String sql = """
                SELECT s.snapshot_id,s.baseline_version,br.locked,s.team_source_sha256,
                       s.official_process_sha256,s.official_master_sha256,s.candidate_count
                FROM balance_evidence_review_snapshot s
                JOIN baseline_release br ON br.id=s.baseline_release_id
                WHERE s.snapshot_id=?
                """;
        List<Snapshot> rows = jdbc.query(sql, (rs, n) -> new Snapshot(rs.getString(1), rs.getString(2),
                rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)), requestedId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Review snapshot not found");
        return rows.get(0);
    }

    private List<Step7ReviewNetworkAdapter.ReviewEvidence> loadReviews(String snapshotId) {
        String sql = """
                SELECT s.baseline_version,br.locked,s.snapshot_id,s.team_source_sha256,
                       c.candidate_id,c.source_row_number,c.official_drawing_file,c.official_drawing_sheet,
                       c.source_equipment AS candidate_source,c.target_equipment AS candidate_target,
                       c.source_cell_hint,c.target_cell_hint,c.connector_candidate_ids,
                       rc.id AS case_id,rc.review_version,rc.source_sha256 AS review_source_sha256,
                       rc.source_equipment AS reviewed_source,rc.target_equipment AS reviewed_target,
                       rc.review_state,rc.reviewed_quantity,rc.reviewed_unit,rc.reviewed_material_type,
                       rc.review_period,rc.physical_connection_confirmed,rc.evidence_type,rc.evidence_identifier,
                       rc.evidence_text,rc.reviewer_name_declared,rc.official_approval_created,
                       h.id AS history_id,h.review_version AS history_review_version,
                       JSON_UNQUOTE(JSON_EXTRACT(h.after_json,'$.reviewState')) AS history_review_state,
                       COALESCE(src.middle_process_code,src.major_process_code) AS source_process,
                       COALESCE(dst.middle_process_code,dst.major_process_code) AS target_process
                FROM balance_evidence_review_candidate c
                JOIN balance_evidence_review_snapshot s ON s.snapshot_id=c.snapshot_id
                JOIN baseline_release br ON br.id=s.baseline_release_id
                LEFT JOIN balance_evidence_review_case rc
                       ON rc.snapshot_id=c.snapshot_id AND rc.candidate_id=c.candidate_id
                LEFT JOIN balance_evidence_review_history h
                       ON h.id=(SELECT MAX(h2.id) FROM balance_evidence_review_history h2 WHERE h2.case_key=rc.case_key)
                LEFT JOIN equipment_record src
                       ON src.baseline_release_id=s.baseline_release_id
                      AND src.permit_management_no=COALESCE(rc.source_equipment,c.source_equipment)
                LEFT JOIN equipment_record dst
                       ON dst.baseline_release_id=s.baseline_release_id
                      AND dst.permit_management_no=COALESCE(rc.target_equipment,c.target_equipment)
                WHERE s.snapshot_id=?
                ORDER BY c.source_row_number,c.candidate_id
                """;
        return jdbc.query(sql, (rs, n) -> {
            String candidateSource = rs.getString("candidate_source");
            String candidateTarget = rs.getString("candidate_target");
            String reviewedSource = rs.getString("reviewed_source");
            String reviewedTarget = rs.getString("reviewed_target");
            long caseId = rs.getLong("case_id");
            boolean hasCase = !rs.wasNull();
            long historyId = rs.getLong("history_id");
            boolean hasHistory = !rs.wasNull();
            long version = hasCase ? rs.getLong("review_version") : 0;
            long historyVersion = hasHistory ? rs.getLong("history_review_version") : 0;
            String sha = rs.getString("review_source_sha256");
            String snapshotSha = rs.getString("team_source_sha256");
            String state = hasCase ? rs.getString("review_state") : "UNREVIEWED";
            boolean approval = hasCase && rs.getBoolean("official_approval_created");
            String sourceEquipment = hasCase && reviewedSource != null ? reviewedSource : candidateSource;
            String targetEquipment = hasCase && reviewedTarget != null ? reviewedTarget : candidateTarget;
            String sourceProcess = rs.getString("source_process");
            String targetProcess = rs.getString("target_process");
            String evidence = rs.getString("evidence_text");
            String evidenceId = rs.getString("evidence_identifier");
            String evidenceType = rs.getString("evidence_type");
            String reviewer = rs.getString("reviewer_name_declared");
            boolean physical = hasCase && rs.getObject("physical_connection_confirmed") != null
                    && rs.getBoolean("physical_connection_confirmed");
            if (hasCase && !snapshotSha.equalsIgnoreCase(sha)) state = "STALE_SOURCE_SHA";
            return new Step7ReviewNetworkAdapter.ReviewEvidence(rs.getString("baseline_version"), rs.getBoolean("locked"),
                    rs.getString("snapshot_id"), rs.getString("candidate_id"), caseId, historyId, version,
                    historyVersion, hasHistory ? rs.getString("history_review_state") : "",
                    rs.getInt("source_row_number"), rs.getString("official_drawing_file"),
                    rs.getString("official_drawing_sheet"), rs.getString("source_cell_hint"),
                    rs.getString("target_cell_hint"), rs.getString("connector_candidate_ids"),
                    sourceEquipment, targetEquipment, sourceProcess, targetProcess,
                    state, physical, rs.getString("reviewed_material_type"), rs.getBigDecimal("reviewed_quantity"),
                    rs.getString("reviewed_unit"), rs.getString("review_period"), evidenceType, evidenceId,
                    evidence, reviewer, approval, false);
        }, snapshotId);
    }

    private List<ScenarioResult> runSyntheticScenarios() {
        List<ScenarioResult> results = new ArrayList<>();
        ScenarioFixture transfer = transferFixture("A", "20");
        var base20 = prepare(transfer);
        results.add(scenario("A", "합성 interprocess transfer 기준 20", base20, null, null));
        results.add(scenario("F-10", "합성 interprocess transfer 20→10", base20, transfer.changedCandidateId(), bd("10")));
        results.add(scenario("F-50", "합성 interprocess transfer 20→50", base20, transfer.changedCandidateId(), bd("50")));
        results.add(scenario("H-120", "합성 과도 이동 20→120 음수 제약", base20, transfer.changedCandidateId(), bd("120")));

        ScenarioFixture parallel = parallelFixture();
        results.add(scenario("D-parallel", "합성 동일 endpoint의 2개 독립 경로", prepare(parallel), null, null));

        ScenarioFixture recycle = recycleFixture("20");
        var recycle20 = prepare(recycle);
        results.add(scenario("G-recycle-20", "합성 내부·공정간 재순환 20", recycle20, null, null));
        results.add(scenario("G-recycle-30", "합성 내부·공정간 재순환 20→30", recycle20, "P2-X-P1-A", bd("30")));

        ScenarioFixture imbalance = imbalanceFixture("95");
        var residual5 = prepare(imbalance);
        results.add(scenario("I-baseline-5", "합성 기준 잔차 +5", residual5, null, null));
        results.add(scenario("I-after-8", "합성 기준 잔차 +5; 신규 변경 후 +8", residual5, "S74B-IMBALANCE-OUT", bd("92")));

        ScenarioFixture incomplete = transferFixture("E", "20");
        var incompleteReviews = new ArrayList<>(incomplete.reviews());
        incompleteReviews.remove(incompleteReviews.size() - 1);
        var incompletePrepared = adapter.adapt(incompleteReviews, incomplete.routes(), incomplete.scope());
        results.add(unavailableScenario("E-partial-scope", "합성 연결 일부만 확인된 공정", incompletePrepared));

        ScenarioFixture unreviewed = transferFixture("C", "20");
        var unreviewedReviews = new ArrayList<>(unreviewed.reviews());
        unreviewedReviews.set(2, withState(unreviewedReviews.get(2), "UNREVIEWED"));
        results.add(unavailableScenario("C-unreviewed", "UNREVIEWED 합성 연결 제외", adapter.adapt(unreviewedReviews, unreviewed.routes(), unreviewed.scope())));

        ScenarioFixture missing = transferFixture("B", "20");
        var missingRoutes = new ArrayList<>(missing.routes());
        missingRoutes.set(2, withDimension(missingRoutes.get(2), Step7ReviewNetworkAdapter.FlowDimension.UNKNOWN));
        results.add(unavailableScenario("B-incomplete-unit", "EVIDENCE_VERIFIED이나 단위 차원 증빙 부족", adapter.adapt(missing.reviews(), missingRoutes, missing.scope())));

        ScenarioFixture stale = transferFixture("J", "20");
        var staleRoutes = new ArrayList<>(stale.routes());
        staleRoutes.set(2, withVersion(staleRoutes.get(2), 2));
        results.add(unavailableScenario("J-stale-history", "검토 이력 버전 충돌", adapter.adapt(stale.reviews(), staleRoutes, stale.scope())));
        return List.copyOf(results);
    }

    private PreparedFixture prepare(ScenarioFixture fixture) {
        var prepared = adapter.adapt(fixture.reviews(), fixture.routes(), fixture.scope());
        if (!prepared.calculationAvailable()) throw new IllegalStateException("Synthetic adapter fixture not eligible: " + prepared.issues());
        return new PreparedFixture(prepared, calculator.calculateDirectedNetwork(prepared.network()));
    }

    private ScenarioResult scenario(String id, String name, PreparedFixture prepared, String changedCandidate,
                                    BigDecimal replacementQuantity) {
        var network = prepared.prepared().network();
        var result = changedCandidate == null ? prepared.baseline()
                : calculator.recalculateDirectedNetwork(network, id, changedCandidate, replacementQuantity);
        return summarize(id, name, network, prepared.prepared(), result);
    }


    private ScenarioResult summarize(String id, String name, DirectedProcessNetwork network,
                                     Step7ReviewNetworkAdapter.PreparedNetwork prepared,
                                     DirectedProcessNetworkSolver.Result result) {
        DirectedProcessNetworkSolver.Result baseline = result.changedConnectionId() == null
                ? result : calculator.calculateDirectedNetwork(network);
        List<ExternalStreamBalance> streamBalances = externalStreamBalances(baseline.external(), result.external());
        BigDecimal baselineResidual = streamBalances.size() == 1 ? streamBalances.get(0).baselineResidual() : null;
        BigDecimal afterResidual = streamBalances.size() == 1 ? streamBalances.get(0).afterResidual() : null;
        BigDecimal delta = streamBalances.size() == 1 ? streamBalances.get(0).residualDelta() : null;
        BigDecimal externalIn = streamBalances.size() == 1 ? streamBalances.get(0).afterInflow() : null;
        BigDecimal externalOut = streamBalances.size() == 1 ? streamBalances.get(0).afterOutflow() : null;
        String changeImpact = result.changedConnectionId() == null ? "BASELINE_ONLY"
                : delta == null ? "NOT_COMPARABLE_BY_STREAM_OR_PERIOD"
                : "CONSTRAINT_VIOLATION".equals(result.status()) ? "NEW_CONSTRAINT_VIOLATION"
                : delta.signum() > 0 ? "RESIDUAL_INCREASED"
                : delta.signum() < 0 ? "RESIDUAL_DECREASED_NOT_A_PROOF" : "NO_RESIDUAL_CHANGE";
        DirectedProcessNetworkSolver.EdgeResult changed = result.edges().stream()
                .filter(edge -> edge.connectionId().equals(result.changedConnectionId())).findFirst().orElse(null);
        return new ScenarioResult(id, name, "TEST_ONLY", result.status(), baseline.status(), changeImpact,
                result.changedConnectionId(), result.previousQuantity(), result.newQuantity(), externalIn, externalOut,
                baselineResidual, afterResidual, delta, changed == null ? null : changed.quantity(),
                "CALCULATED".equals(result.status()) || "RESIDUAL_UNRESOLVED".equals(result.status()),
                result.issues(), prepared.candidates(), prepared.edgeProvenance(), streamBalances, result, network);
    }

    private List<ExternalStreamBalance> externalStreamBalances(
            List<DirectedProcessNetworkSolver.ExternalBalance> baseline,
            List<DirectedProcessNetworkSolver.ExternalBalance> after) {
        Map<String, DirectedProcessNetworkSolver.ExternalBalance> beforeByStream = baseline.stream()
                .collect(java.util.stream.Collectors.toMap(this::streamKey, row -> row, (left, right) -> left));
        return after.stream().map(row -> {
            DirectedProcessNetworkSolver.ExternalBalance before = beforeByStream.get(streamKey(row));
            BigDecimal delta = before == null || before.residual() == null || row.residual() == null
                    ? null : row.residual().subtract(before.residual());
            return new ExternalStreamBalance(row.materialClass().name(), row.materialType(), row.unit(), row.period(),
                    before == null ? null : before.externalInflow(), before == null ? null : before.externalOutflow(),
                    before == null ? null : before.residual(), row.externalInflow(), row.externalOutflow(), row.residual(), delta,
                    before == null ? "BASELINE_STREAM_MISSING" : row.status());
        }).toList();
    }

    private String streamKey(DirectedProcessNetworkSolver.ExternalBalance balance) {
        return balance.materialClass() + "|" + balance.materialType() + "|" + balance.unit() + "|"
                + (balance.period() == null ? "" : balance.period().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT));
    }

    private ScenarioFixture transferFixture(String suffix, String amount) {
        String snapshot = "SYNTHETIC-SNAPSHOT-" + suffix;
        List<DirectedProcessNetwork.Node> nodes = List.of(
                external("S74B-S-P2", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                external("S74B-S-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                equipment("S74B-X", "P2-X", "P2"), equipment("S74B-N", "P1-N", "P1"),
                external("S74B-OUT-P2", DirectedProcessNetwork.NodeType.EXTERNAL_SINK),
                external("S74B-OUT-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SINK));
        List<EdgeSpec> specs = List.of(
                edgeSpec("P2-IN", "S74B-S-P2", "S74B-X", "P2", "P2", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic P2 source"),
                edgeSpec("P1-IN", "S74B-S-P1", "S74B-N", "P1", "P1", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic P1 source"),
                edgeSpec("P2-X-P1-N", "S74B-X", "S74B-N", "P2", "P1", amount, true, DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, "Synthetic P2→P1 route"),
                edgeSpec("P2-X-P2-OUT", "S74B-X", "S74B-OUT-P2", "P2", "", null, false, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic P2 outlet"),
                edgeSpec("P1-N-P1-OUT", "S74B-N", "S74B-OUT-P1", "P1", "", null, false, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic P1 outlet"));
        return fixture(snapshot, "SYNTHETIC-TRANSFER-" + suffix, "Synthetic P1/P2 transfer", specs, nodes,
                Set.of("P2-IN", "P1-IN", "P2-X-P1-N", "P2-X-P2-OUT", "P1-N-P1-OUT"), "P2-X-P1-N", true, true);
    }

    private ScenarioFixture parallelFixture() {
        String snapshot = "SYNTHETIC-SNAPSHOT-PARALLEL";
        List<DirectedProcessNetwork.Node> nodes = List.of(external("S74B-S-P2", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                external("S74B-S-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE), equipment("S74B-X", "P2-X", "P2"),
                equipment("S74B-N", "P1-N", "P1"), external("S74B-OUT-P2", DirectedProcessNetwork.NodeType.EXTERNAL_SINK),
                external("S74B-OUT-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SINK));
        List<EdgeSpec> specs = List.of(
                edgeSpec("P2-IN", "S74B-S-P2", "S74B-X", "P2", "P2", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic P2 source"),
                edgeSpec("P1-IN", "S74B-S-P1", "S74B-N", "P1", "P1", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic P1 source"),
                edgeSpec("P2-X-P1-N-ROUTE-A", "S74B-X", "S74B-N", "P2", "P1", "10", true, DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, "Separate synthetic path A"),
                edgeSpec("P2-X-P1-N-ROUTE-B", "S74B-X", "S74B-N", "P2", "P1", "10", true, DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, "Separate synthetic path B"),
                edgeSpec("P2-X-P2-OUT", "S74B-X", "S74B-OUT-P2", "P2", "", null, false, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic P2 outlet"),
                edgeSpec("P1-N-P1-OUT", "S74B-N", "S74B-OUT-P1", "P1", "", null, false, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic P1 outlet"));
        return fixture(snapshot, "SYNTHETIC-PARALLEL", "Parallel path identity", specs, nodes,
                specs.stream().map(EdgeSpec::id).collect(java.util.stream.Collectors.toSet()), "", true, true);
    }

    private ScenarioFixture recycleFixture(String amount) {
        String snapshot = "SYNTHETIC-SNAPSHOT-RECYCLE";
        List<DirectedProcessNetwork.Node> nodes = List.of(external("S74B-S-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                equipment("S74B-A", "P1-A", "P1"), equipment("S74B-N", "P1-N", "P1"),
                equipment("S74B-X", "P2-X", "P2"), external("S74B-OUT-P1", DirectedProcessNetwork.NodeType.EXTERNAL_SINK));
        List<EdgeSpec> specs = List.of(
                edgeSpec("P1-IN", "S74B-S-P1", "S74B-A", "P1", "P1", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic P1 source"),
                edgeSpec("P1-A-P2-X", "S74B-A", "S74B-X", "P1", "P2", null, false, DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, "Synthetic return route"),
                edgeSpec("P2-X-P1-A", "S74B-X", "S74B-A", "P2", "P1", amount, true, DirectedProcessNetwork.ConnectionType.INTERPROCESS_RECYCLE, "Synthetic recycle setpoint"),
                edgeSpec("P1-A-P1-N", "S74B-A", "S74B-N", "P1", "P1", null, false, DirectedProcessNetwork.ConnectionType.SERIES, "Synthetic process path"),
                edgeSpec("P1-N-OUT", "S74B-N", "S74B-OUT-P1", "P1", "", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic P1 outlet"));
        return fixture(snapshot, "SYNTHETIC-RECYCLE", "Synthetic P1/P2 recycle", specs, nodes,
                specs.stream().map(EdgeSpec::id).collect(java.util.stream.Collectors.toSet()), "P2-X-P1-A", true, true);
    }

    private ScenarioFixture imbalanceFixture(String outflow) {
        String snapshot = "SYNTHETIC-SNAPSHOT-IMBALANCE";
        List<DirectedProcessNetwork.Node> nodes = List.of(external("S74B-S", DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE),
                equipment("S74B-P", "P1-P", "P1"), external("S74B-OUT", DirectedProcessNetwork.NodeType.EXTERNAL_SINK));
        List<EdgeSpec> specs = List.of(
                edgeSpec("S74B-IMBALANCE-IN", "S74B-S", "S74B-P", "P1", "P1", "100", true, DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW, "Synthetic 100 input"),
                edgeSpec("S74B-IMBALANCE-OUT", "S74B-P", "S74B-OUT", "P1", "", outflow, true, DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW, "Synthetic pre-existing outlet"));
        return fixture(snapshot, "SYNTHETIC-IMBALANCE", "Synthetic baseline residual comparison", specs, nodes,
                Set.of("S74B-IMBALANCE-IN", "S74B-IMBALANCE-OUT"), "S74B-IMBALANCE-OUT", true, true);
    }

    private ScenarioFixture fixture(String snapshot, String networkId, String name, List<EdgeSpec> specs,
                                    List<DirectedProcessNetwork.Node> nodes, Set<String> expected,
                                    String changedCandidate, boolean cycleVerified, boolean complete) {
        List<Step7ReviewNetworkAdapter.ReviewEvidence> reviews = new ArrayList<>();
        List<Step7ReviewNetworkAdapter.RouteEvidence> routes = new ArrayList<>();
        long caseId = 1000, historyId = 5000;
        for (EdgeSpec spec : specs) {
            long id = expected.contains(spec.id()) ? ++caseId : ++caseId;
            long history = ++historyId;
            String sourceEquipment = spec.sourceProcess().isBlank() ? "EXT-SOURCE-" + spec.id() : equipmentForNode(nodes, spec.sourceNodeId());
            String targetEquipment = spec.targetProcess().isBlank() ? "EXT-SINK-" + spec.id() : equipmentForNode(nodes, spec.targetNodeId());
            BigDecimal quantity = spec.quantity() == null ? null : new BigDecimal(spec.quantity());
            var review = new Step7ReviewNetworkAdapter.ReviewEvidence("V1.0", true, snapshot, spec.id(), id, history,
                    1, 1, "EVIDENCE_VERIFIED", 100 + reviews.size(), "SYNTHETIC-TEST-ONLY.xlsx", "PW-01-05",
                    "R" + (100 + reviews.size()), "C" + (100 + reviews.size()), "SYNTHETIC-CONNECTOR-" + spec.id(), sourceEquipment,
                    targetEquipment, spec.sourceProcess().isBlank() ? spec.targetProcess() : spec.sourceProcess(),
                    spec.targetProcess().isBlank() ? spec.sourceProcess() : spec.targetProcess(), "EVIDENCE_VERIFIED", true,
                    "process wastewater", quantity, "ton/day", "2026-10-01/TEST_PERIOD",
                    "FIELD_INSPECTION", "SYNTHETIC-REVIEW-" + spec.id(), "Synthetic evidence; not real plant evidence",
                    "synthetic-reviewer", false, true);
            reviews.add(review);
            routes.add(new Step7ReviewNetworkAdapter.RouteEvidence(snapshot, "V1.0", spec.id(), id, history, 1,
                    "ROUTE-" + spec.id(), spec.sourceNodeId(), spec.targetNodeId(), spec.connectionType(),
                    DirectedProcessNetwork.MaterialClass.WASTEWATER, "process wastewater",
                    Step7ReviewNetworkAdapter.FlowDimension.MASS_PER_TIME, Step7ReviewNetworkAdapter.GasCondition.NOT_APPLICABLE,
                    "NOT_APPLICABLE", quantity,
                    "SYNTHETIC-QUANTITY-" + spec.id(), "ton/day", "2026-10-01/TEST_PERIOD",
                    spec.fixed() ? DirectedProcessNetwork.QuantityBasis.MEASURED : DirectedProcessNetwork.QuantityBasis.CALCULATED,
                    spec.fixed(), spec.path(), "SYNTHETIC-PATH-" + spec.id(), true, true));
        }
        Map<String, String> boundaryEvidence = new LinkedHashMap<>();
        nodes.stream().filter(n -> n.nodeType() == DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE
                        || n.nodeType() == DirectedProcessNetwork.NodeType.EXTERNAL_SINK)
                .forEach(n -> boundaryEvidence.put(n.nodeId(), "SYNTHETIC-BOUNDARY-" + n.nodeId()));
        var scope = new Step7ReviewNetworkAdapter.GraphScope("TEST_ONLY", "V1.0", true, snapshot, networkId, name,
                "2026-10-01/TEST_PERIOD", expected, nodes, List.of(), boundaryEvidence, complete, complete,
                complete, cycleVerified, cycleVerified, true, "SYNTHETIC-SCOPE-" + networkId,
                List.of("Synthetic scope fixture only", "No official process evidence represented"));
        return new ScenarioFixture(reviews, routes, scope, changedCandidate);
    }

    private ScenarioResult unavailableScenario(String id, String name,
                                               Step7ReviewNetworkAdapter.PreparedNetwork prepared) {
        return new ScenarioResult(id, name, "TEST_ONLY", prepared.status(), "NOT_CALCULATED", "NOT_ASSESSED", null,
                null, null, null, null, null, null, null, null, false,
                prepared.issues(), prepared.candidates(), prepared.edgeProvenance(), List.of(), null, prepared.network());
    }

    private BigDecimal baselineExternalResidual(DirectedProcessNetwork network) {
        return calculator.calculateDirectedNetwork(network).external().stream()
                .map(DirectedProcessNetworkSolver.ExternalBalance::residual).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal baselineExternalResidual(Step7ReviewNetworkAdapter.PreparedNetwork prepared) {
        if (prepared.network() == null) return null;
        return baselineExternalResidual(prepared.network());
    }

    private Step7ReviewNetworkAdapter.ReviewEvidence withState(Step7ReviewNetworkAdapter.ReviewEvidence review, String state) {
        return new Step7ReviewNetworkAdapter.ReviewEvidence(review.baselineVersion(), review.baselineLocked(), review.snapshotId(),
                review.candidateId(), review.reviewCaseId(), review.reviewHistoryId(), review.reviewVersion(), review.historyReviewVersion(),
                review.historyReviewState(), review.sourceRowNumber(), review.drawingFile(), review.drawingSheet(),
                review.sourceCellHint(), review.targetCellHint(), review.connectorCandidateIds(),
                review.sourceEquipment(), review.targetEquipment(),
                review.sourceProcess(), review.targetProcess(), state, review.physicalConnectionConfirmed(), review.materialType(),
                review.quantity(), review.unit(), review.period(), review.evidenceType(), review.evidenceIdentifier(), review.evidenceText(),
                review.reviewer(), review.officialApprovalCreated(), review.syntheticFixture());
    }

    private Step7ReviewNetworkAdapter.RouteEvidence withDimension(Step7ReviewNetworkAdapter.RouteEvidence route,
                                                                  Step7ReviewNetworkAdapter.FlowDimension dimension) {
        return new Step7ReviewNetworkAdapter.RouteEvidence(route.snapshotId(), route.baselineVersion(), route.candidateId(),
                route.reviewCaseId(), route.reviewHistoryId(), route.reviewVersion(), route.routeId(), route.sourceNodeId(),
                route.targetNodeId(), route.connectionType(), route.materialClass(), route.materialType(), dimension,
                route.gasCondition(), route.gasReferenceCondition(), route.quantity(), route.quantityEvidenceIdentifier(),
                route.unit(), route.period(), route.quantityBasis(), route.fixed(), route.pathDescription(),
                route.pathEvidenceIdentifier(), route.directionConfirmed(), route.topologyConfirmed());
    }

    private Step7ReviewNetworkAdapter.RouteEvidence withVersion(Step7ReviewNetworkAdapter.RouteEvidence route, long version) {
        return new Step7ReviewNetworkAdapter.RouteEvidence(route.snapshotId(), route.baselineVersion(), route.candidateId(),
                route.reviewCaseId(), route.reviewHistoryId(), version, route.routeId(), route.sourceNodeId(), route.targetNodeId(),
                route.connectionType(), route.materialClass(), route.materialType(), route.dimension(), route.gasCondition(),
                route.gasReferenceCondition(), route.quantity(), route.quantityEvidenceIdentifier(), route.unit(), route.period(),
                route.quantityBasis(), route.fixed(), route.pathDescription(), route.pathEvidenceIdentifier(),
                route.directionConfirmed(), route.topologyConfirmed());
    }

    private EdgeSpec edgeSpec(String id, String source, String target, String sourceProcess, String targetProcess,
                              String quantity, boolean fixed, DirectedProcessNetwork.ConnectionType type, String path) {
        return new EdgeSpec(id, source, target, sourceProcess, targetProcess, quantity, fixed, type, path);
    }

    private String equipmentForNode(List<DirectedProcessNetwork.Node> nodes, String nodeId) {
        return nodes.stream().filter(n -> n.nodeId().equals(nodeId)).map(DirectedProcessNetwork.Node::equipmentNumber)
                .filter(java.util.Objects::nonNull).findFirst().orElse("SYNTHETIC-EXTERNAL-" + nodeId);
    }

    private DirectedProcessNetwork.Node equipment(String id, String number, String process) {
        return new DirectedProcessNetwork.Node(id, number, process, "SYNTHETIC_PROCESS_EQUIPMENT",
                DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT, number, "SYNTHETIC-TEST-ONLY.xlsx", "PW-01-05", "TEST_ONLY");
    }

    private DirectedProcessNetwork.Node external(String id, DirectedProcessNetwork.NodeType type) {
        return new DirectedProcessNetwork.Node(id, null, null, "SYNTHETIC_BOUNDARY", type, id,
                "SYNTHETIC-TEST-ONLY.xlsx", "BOUNDARY", "TEST_ONLY");
    }

    private String bdText(BigDecimal quantity) { return quantity == null ? "0" : quantity.stripTrailingZeros().toPlainString(); }
    private BigDecimal bd(String quantity) { return new BigDecimal(quantity); }
    private String safe(String value) { return value == null ? "" : value; }

    public record Snapshot(String id, String baselineVersion, boolean locked, String teamSha,
                           String processSha, String masterSha, int candidateCount) { }
    private record EdgeSpec(String id, String sourceNodeId, String targetNodeId, String sourceProcess,
                            String targetProcess, String quantity, boolean fixed,
                            DirectedProcessNetwork.ConnectionType connectionType, String path) { }
    private record ScenarioFixture(List<Step7ReviewNetworkAdapter.ReviewEvidence> reviews,
                                  List<Step7ReviewNetworkAdapter.RouteEvidence> routes,
                                  Step7ReviewNetworkAdapter.GraphScope scope, String changedCandidateId) { }
    private record PreparedFixture(Step7ReviewNetworkAdapter.PreparedNetwork prepared,
                                   DirectedProcessNetworkSolver.Result baseline) {
        BigDecimal externalResidual() { return baseline.external().stream().map(DirectedProcessNetworkSolver.ExternalBalance::residual)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add); }
    }

    public record ExternalStreamBalance(String materialClass, String materialType, String unit, String period,
                                         BigDecimal baselineInflow, BigDecimal baselineOutflow,
                                         BigDecimal baselineResidual, BigDecimal afterInflow,
                                         BigDecimal afterOutflow, BigDecimal afterResidual,
                                         BigDecimal residualDelta, String status) { }

    public record ScenarioResult(String scenarioId, String scenarioName, String mode, String calculationStatus,
                                 String baselineBalanceStatus, String changeImpactStatus,
                                 String changedConnectionId, BigDecimal oldQuantity, BigDecimal newQuantity,
                                 BigDecimal externalInflow, BigDecimal externalOutflow, BigDecimal baselineResidual,
                                 BigDecimal afterResidual, BigDecimal residualDelta, BigDecimal resultingChangedQuantity,
                                 boolean calculationAvailable, List<String> issues,
                                 List<Step7ReviewNetworkAdapter.CandidateAssessment> candidates,
                                 List<Step7ReviewNetworkAdapter.EdgeProvenance> edgeProvenance,
                                 List<ExternalStreamBalance> externalStreamBalances,
                                 DirectedProcessNetworkSolver.Result solverResult, DirectedProcessNetwork network) { }

    public record IntegrationReport(String actualSnapshotId, String baselineVersion, int actualCandidateCount,
                                    long actualEvidenceVerifiedCount, long actualEligibleConnectionCount,
                                    Step7ReviewNetworkAdapter.PreparedNetwork actualReadiness,
                                    List<ScenarioResult> syntheticScenarios, String notice) { }
}
