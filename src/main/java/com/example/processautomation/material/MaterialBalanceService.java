package com.example.processautomation.material;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MaterialBalanceService {
    private final JdbcTemplate jdbcTemplate;
    private final MaterialBalanceCalculator calculator;
    private final ObjectMapper objectMapper;

    public MaterialBalanceService(JdbcTemplate jdbcTemplate, MaterialBalanceCalculator calculator,
                                  ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> calculate(MaterialBalanceRequest request) {
        if (!"TEST_ONLY".equalsIgnoreCase(request.mode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only TEST_ONLY simulations are enabled; production material-balance writes are disabled");
        }
        String requestHash = sha256(serialize(request));
        Long simulationId = insertOrFindSimulation(request, requestHash);
        SimulationRecord existing = jdbcTemplate.queryForObject(
                "SELECT id, request_sha256, result_json FROM material_balance_simulation WHERE id = ?",
                (rs, rowNum) -> new SimulationRecord(rs.getLong("id"), rs.getString("request_sha256"),
                        rs.getString("result_json")), simulationId);
        if (existing != null && existing.resultJson() != null) {
            if (!requestHash.equals(existing.requestHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "simulationId already exists with a different request payload");
            }
            return deserialize(existing.resultJson());
        }
        if (existing == null || !requestHash.equals(existing.requestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "simulationId request conflict");
        }

        Map<String, String> exclusions = new LinkedHashMap<>();
        for (var transfer : request.transfers()) {
            if ("CONFIRMED".equalsIgnoreCase(transfer.relationMode())) {
                String reason = verifyApprovedRelation(transfer, request.nodes());
                if (reason != null) exclusions.put(transfer.transferId(), reason);
            } else if (!"TEST_ONLY".equalsIgnoreCase(transfer.relationMode())) {
                exclusions.put(transfer.transferId(), "UNSUPPORTED_RELATION_MODE");
            }
        }
        MaterialBalanceCalculator.Calculation calculation = calculator.calculate(request, exclusions);
        List<String> limitations = List.of(
                "모든 계산은 TEST_ONLY 시뮬레이션이며 공식 기준자료·실제 공정도·equipment_flow에 반영되지 않음",
                "CONFIRMED는 DB relation_status, 수동 검토 상태, reviewer/reviewed_at 및 immutable review history가 일치해야 포함",
                "폐수와 폐기물은 별도 flowType으로 유지하며 단위 변환은 수행하지 않음",
                "단위가 한 설비 안에서 섞이거나 필수 조건이 없으면 잔차를 계산 불가로 표시",
                "모든 신규 transfer에는 measurement period가 필수이며 기간별로 별도 계산",
                "이전 저장 transfer의 NULL 기간은 원래 값 그대로 유지하며 복원/추정하지 않음",
                "잔차는 선택된 TEST_ONLY 그래프 내 유입-유출이며 완전한 물리 보존의 증명은 아님");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulationId", request.simulationId());
        result.put("scenarioName", request.scenarioName());
        result.put("mode", "TEST_ONLY");
        result.put("officialBaselineModified", false);
        result.put("productionMaterialBalanceUpdated", false);
        result.put("calculationStatus", calculation.nodes().stream()
                .allMatch(node -> "CALCULATED".equals(node.get("balanceStatus"))) ? "CALCULATED" : "PARTIAL_OR_UNAVAILABLE");
        result.put("transfers", calculation.transfers());
        result.put("nodes", calculation.nodes());
        result.put("limitations", limitations);
        String resultJson = serialize(result);
        jdbcTemplate.update("UPDATE material_balance_simulation SET result_json = ? WHERE id = ?",
                resultJson, simulationId);
        persistTransfers(simulationId, request, calculation.transfers());
        return result;
    }

    private Long insertOrFindSimulation(MaterialBalanceRequest request, String requestHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO material_balance_simulation(simulation_id, scenario_name, scenario_mode,
                        request_sha256, result_json)
                    VALUES (?, ?, 'TEST_ONLY', ?, NULL)
                    ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.simulationId());
            statement.setString(2, request.scenarioName());
            statement.setString(3, requestHash);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id != null) return id.longValue();
        return jdbcTemplate.queryForObject("SELECT id FROM material_balance_simulation WHERE simulation_id = ?",
                Long.class, request.simulationId());
    }

    private String verifyApprovedRelation(MaterialBalanceRequest.Transfer transfer,
                                          List<MaterialBalanceRequest.Node> nodes) {
        if (transfer.relationId() == null) return "CONFIRMED_EDGE_MISSING_DATABASE_RELATION_ID";
        List<RelationRecord> relations = jdbcTemplate.query("""
                SELECT pr.id, pr.baseline_release_id, br.version_code, br.locked,
                       pr.source_equipment_id, pr.target_equipment_id,
                       pr.relation_type, pr.flow_type, pr.quantity, pr.quantity_unit, pr.destination_text,
                       pr.relation_status, pr.analysis_status, pr.reviewer_name, pr.reviewed_at
                FROM process_relation pr
                JOIN baseline_release br ON br.id = pr.baseline_release_id
                WHERE pr.id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new RelationRecord(rs.getLong("id"), rs.getLong("baseline_release_id"),
                rs.getString("version_code"), rs.getBoolean("locked"),
                (Long) rs.getObject("source_equipment_id"), (Long) rs.getObject("target_equipment_id"),
                rs.getString("relation_type"), rs.getString("flow_type"), rs.getBigDecimal("quantity"),
                rs.getString("quantity_unit"), rs.getString("destination_text"), rs.getString("relation_status"),
                rs.getString("analysis_status"), rs.getString("reviewer_name"), rs.getTimestamp("reviewed_at")),
                transfer.relationId());
        if (relations.isEmpty()) return "CONFIRMED_RELATION_NOT_FOUND_IN_DATABASE";
        RelationRecord relation = relations.get(0);
        if (!"V1.0".equals(relation.baselineVersion()) || !relation.releaseLocked()) {
            return "RELATION_NOT_FROM_LOCKED_V1_BASELINE";
        }
        if (!"CONFIRMED".equals(relation.relationStatus()) || !"MANUALLY_RESOLVED".equals(relation.analysisStatus())
                || blank(relation.reviewerName()) || relation.reviewedAt() == null) return "DATABASE_RELATION_NOT_APPROVED";
        Integer audited = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM process_relation_review_history h
                WHERE h.process_relation_id = ? AND h.action_type IN ('RELATION_CREATED', 'RELATION_UPDATED')
                  AND JSON_UNQUOTE(JSON_EXTRACT(h.resulting_snapshot_json, '$.relationStatus')) = 'CONFIRMED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(h.resulting_snapshot_json, '$.reviewerName')) = ?
                """, Integer.class, relation.id(), relation.reviewerName());
        if (audited == null || audited == 0) return "CONFIRMED_RELATION_APPROVAL_HISTORY_MISSING";
        // Existing process_relation rows have no measurement-period field. Never infer one.
        return "APPROVED_RELATION_PERIOD_NOT_STORED";
    }

    private void persistTransfers(Long simulationDbId, MaterialBalanceRequest request,
                                  List<Map<String, Object>> transferResults) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        transferResults.forEach(row -> results.put(row.get("transferId").toString(), row));
        for (var transfer : request.transfers()) {
            Map<String, Object> result = results.get(transfer.transferId());
            boolean included = Boolean.TRUE.equals(result.get("included"));
            String reason = result.get("reason") == null ? null : result.get("reason").toString();
            Long persistedRelationId = "CONFIRMED_RELATION_NOT_FOUND_IN_DATABASE".equals(reason)
                    ? null : transfer.relationId();
            try {
                jdbcTemplate.update("""
                        INSERT INTO material_balance_transfer(simulation_db_id, transfer_id, relation_mode,
                            relation_id, source_node_id, target_node_id, edge_type, flow_type, quantity,
                            receiver_quantity, quantity_unit, measurement_period, evidence_text, included, exclusion_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, simulationDbId, transfer.transferId(), transfer.relationMode(), persistedRelationId,
                        transfer.sourceId(), transfer.targetId(), transfer.edgeType(), transfer.flowType(),
                        transfer.quantity(), transfer.receiverQuantity(), transfer.unit(),
                        blank(transfer.period()) ? null : transfer.period(), transfer.evidence(), included, reason);
            } catch (DuplicateKeyException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "transferId has already been used; duplicate movement calculation rejected");
            }
        }
    }

    private String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize material-balance data", exception); }
    }

    private Map<String, Object> deserialize(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored simulation result is invalid", exception); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record SimulationRecord(long id, String requestHash, String resultJson) { }
    private record RelationRecord(long id, long releaseId, String baselineVersion, boolean releaseLocked,
                                  Long sourceEquipmentId, Long targetEquipmentId, String relationType,
                                  String flowType, java.math.BigDecimal quantity,
                                  String quantityUnit, String destinationText, String relationStatus,
                                  String analysisStatus, String reviewerName, java.sql.Timestamp reviewedAt) { }
}
