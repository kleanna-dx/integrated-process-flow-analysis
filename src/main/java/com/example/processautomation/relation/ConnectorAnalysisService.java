package com.example.processautomation.relation;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorAnalysisService {
    private final JdbcTemplate jdbcTemplate;
    private final OoxmlPackageInspector inspector;
    private final ObjectMapper objectMapper;

    public ConnectorAnalysisService(JdbcTemplate jdbcTemplate, OoxmlPackageInspector inspector,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> analyze(String processCode) {
        return analyze(processCode, null, null);
    }

    @Transactional
    public Map<String, Object> analyze(String processCode, String sheetName, String connectorShapeId) {
        if ((sheetName != null || connectorShapeId != null)
                && (processCode == null || processCode.isBlank())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "processCode is required when filtering by sheet or connector");
        }
        if (connectorShapeId != null && (sheetName == null || sheetName.isBlank())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "sheetName is required when filtering by connectorShapeId");
        }
        List<BaselineWorkbook> workbooks = jdbcTemplate.query("""
                SELECT id, process_code, content_bytes
                FROM baseline_document
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = 'V1.0')
                  AND document_role = 'PROCESS'
                  AND (? IS NULL OR process_code = ?)
                ORDER BY process_code
                """, (rs, rowNum) -> new BaselineWorkbook(rs.getLong("id"), rs.getString("process_code"),
                rs.getBytes("content_bytes")), processCode, processCode);
        if (workbooks.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "No baseline process workbook matched the request");
        }

        int scanned = 0;
        int explicitBindings = 0;
        int directionalMarkers = 0;
        int connectorsWithoutArrowMarker = 0;
        for (BaselineWorkbook workbook : workbooks) {
            WorkbookInventory inventory = inspector.inspect(workbook.content());
            for (WorkbookInventory.SheetInventory sheet : inventory.sheets()) {
                if (sheetName != null && !sheetName.equals(sheet.sheetName())) continue;
                Long sheetId = jdbcTemplate.queryForObject("""
                        SELECT id FROM workbook_sheet WHERE baseline_document_id = ? AND sheet_name = ?
                        """, Long.class, workbook.documentId(), sheet.sheetName());
                Map<String, Long> objectIds = new HashMap<>();
                Map<Integer, Long> objectIdsByIndex = new HashMap<>();
                jdbcTemplate.query("SELECT id, shape_id, object_index FROM drawing_object WHERE workbook_sheet_id = ?",
                        rs -> {
                            long objectId = rs.getLong("id");
                            String shapeId = rs.getString("shape_id");
                            if (shapeId != null) objectIds.put(shapeId, objectId);
                            objectIdsByIndex.put(rs.getInt("object_index"), objectId);
                        }, sheetId);
                jdbcTemplate.batchUpdate("""
                        UPDATE drawing_object
                        SET transform_x_emu = ?, transform_y_emu = ?, transform_width_emu = ?,
                            transform_height_emu = ?, rotation_60000 = ?, flip_horizontal = ?, flip_vertical = ?
                        WHERE workbook_sheet_id = ? AND object_index = ?
                        """, sheet.drawingObjects(), 400, (statement, object) -> {
                    statement.setObject(1, object.transformXEmu());
                    statement.setObject(2, object.transformYEmu());
                    statement.setObject(3, object.transformWidthEmu());
                    statement.setObject(4, object.transformHeightEmu());
                    statement.setObject(5, object.rotation60000());
                    statement.setObject(6, object.flipHorizontal());
                    statement.setObject(7, object.flipVertical());
                    statement.setLong(8, sheetId);
                    statement.setInt(9, object.objectIndex());
                });
                for (WorkbookInventory.DrawingObject object : sheet.drawingObjects()) {
                    if (!"CONNECTOR".equals(object.objectType())) continue;
                    if (connectorShapeId != null && !connectorShapeId.equals(object.shapeId())) continue;
                    scanned++;
                    Long startId = object.startConnectedShapeId() == null ? null : objectIds.get(object.startConnectedShapeId());
                    Long endId = object.endConnectedShapeId() == null ? null : objectIds.get(object.endConnectedShapeId());
                    if (startId != null || endId != null) explicitBindings++;
                    boolean arrow = hasArrow(object.headArrowType()) || hasArrow(object.tailArrowType());
                    if (arrow) directionalMarkers++;
                    else connectorsWithoutArrowMarker++;
                    boolean geometryAvailable = object.transformXEmu() != null && object.transformYEmu() != null
                            && object.transformWidthEmu() != null && object.transformHeightEmu() != null
                            && object.geometryPreset() != null;
                    String analysisStatus = arrow && geometryAvailable ? "REVIEW_REQUIRED" : "UNANALYZABLE";
                    String evidence = evidenceJson(workbook.processCode(), sheet.sheetName(), object, arrow,
                            startId != null, endId != null);
                    jdbcTemplate.update("""
                            INSERT INTO process_relation(
                                baseline_release_id, process_document_id, workbook_sheet_id,
                                source_drawing_object_id, target_drawing_object_id, connector_drawing_object_id,
                                connector_analysis_key, relation_type, relation_status, analysis_status, evidence_text)
                            VALUES ((SELECT id FROM baseline_release WHERE version_code = 'V1.0'), ?, ?, ?, ?, ?, ?,
                                    'UNKNOWN', 'REVIEW_REQUIRED', ?, ?)
                            ON DUPLICATE KEY UPDATE
                                process_document_id = VALUES(process_document_id),
                                workbook_sheet_id = VALUES(workbook_sheet_id),
                                source_drawing_object_id = IF(reviewed_at IS NULL AND candidate_evidence_at IS NULL,
                                    VALUES(source_drawing_object_id), source_drawing_object_id),
                                target_drawing_object_id = IF(reviewed_at IS NULL AND candidate_evidence_at IS NULL,
                                    VALUES(target_drawing_object_id), target_drawing_object_id),
                                source_equipment_id = IF(reviewed_at IS NULL AND candidate_evidence_at IS NULL,
                                    NULL, source_equipment_id),
                                target_equipment_id = IF(reviewed_at IS NULL AND candidate_evidence_at IS NULL,
                                    NULL, target_equipment_id),
                                analysis_status = IF(reviewed_at IS NULL AND candidate_evidence_at IS NULL,
                                    VALUES(analysis_status), analysis_status),
                                evidence_text = VALUES(evidence_text)
                            """, workbook.documentId(), sheetId, startId, endId,
                            objectIdsByIndex.get(object.objectIndex()), workbook.documentId() + ":" + sheetId + ":"
                                    + objectIdsByIndex.get(object.objectIndex()), analysisStatus, evidence);
                }
            }
        }

        if (scanned == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "No connector matched the requested process, sheet, and shape ID");
        }
        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS scanned,
                       SUM(CASE WHEN relation_status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmed,
                       SUM(CASE WHEN relation_status <> 'CONFIRMED' AND analysis_status = 'REVIEW_REQUIRED'
                                THEN 1 ELSE 0 END) AS reviewRequired,
                       SUM(CASE WHEN relation_status <> 'CONFIRMED' AND analysis_status = 'UNANALYZABLE'
                                THEN 1 ELSE 0 END) AS unanalyzable,
                       SUM(CASE WHEN relation_status <> 'CONFIRMED' AND analysis_status = 'MANUALLY_RESOLVED'
                                THEN 1 ELSE 0 END) AS manuallyResolved
                FROM process_relation pr
                JOIN workbook_sheet s ON s.id = pr.workbook_sheet_id
                JOIN drawing_object connector ON connector.id = pr.connector_drawing_object_id
                JOIN baseline_document d ON d.id = pr.process_document_id
                WHERE pr.connector_drawing_object_id IS NOT NULL
                  AND (? IS NULL OR d.process_code = ?)
                  AND (? IS NULL OR s.sheet_name = ?)
                  AND (? IS NULL OR connector.shape_id = ?)
                """, processCode, processCode, sheetName, sheetName, connectorShapeId, connectorShapeId);
        summary.put("requestedProcessCode", processCode == null ? "ALL" : processCode);
        summary.put("requestedSheetName", sheetName == null ? "ALL" : sheetName);
        summary.put("requestedConnectorShapeId", connectorShapeId == null ? "ALL" : connectorShapeId);
        summary.put("connectorsScannedThisRun", scanned);
        summary.put("explicitShapeBindingsThisRun", explicitBindings);
        summary.put("connectorsWithArrowMarkerThisRun", directionalMarkers);
        summary.put("connectorsWithoutArrowMarkerThisRun", connectorsWithoutArrowMarker);
        summary.put("automaticEquipmentRelationsConfirmedThisRun", 0);
        summary.put("caution", "Connector segments are not equipment-to-equipment routes. All links remain unconfirmed until an administrator validates the flow direction and process meaning.");
        return summary;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String processCode, String status, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT pr.id, d.process_code AS processCode, s.sheet_name AS sheetName,
                       connector.shape_id AS connectorShapeId, connector.object_name AS connectorName,
                       connector.geometry_preset AS geometryPreset,
                       connector.transform_x_emu AS shapeLocalXEmu,
                       connector.transform_y_emu AS shapeLocalYEmu,
                       connector.transform_width_emu AS shapeLocalWidthEmu,
                       connector.transform_height_emu AS shapeLocalHeightEmu,
                       connector.rotation_60000 AS rotation60000, connector.flip_horizontal AS flipHorizontal,
                       connector.flip_vertical AS flipVertical, connector.head_arrow_type AS headArrowType,
                       connector.tail_arrow_type AS tailArrowType,
                       source.shape_id AS sourceShapeId, target.shape_id AS targetShapeId,
                       pr.source_equipment_id AS sourceEquipmentId,
                       pr.target_equipment_id AS targetEquipmentId,
                       pr.relation_type AS relationType, pr.relation_status AS relationStatus,
                       pr.analysis_status AS analysisStatus, pr.reviewer_name AS reviewerName,
                       pr.reviewed_at AS reviewedAt, pr.evidence_text AS connectorEvidence,
                       pr.candidate_evidence_text AS candidateEvidence,
                       pr.candidate_evidence_by AS candidateEvidenceBy,
                       pr.candidate_evidence_at AS candidateEvidenceAt,
                       pr.source_cell_range AS sourceCellRange, pr.target_cell_range AS targetCellRange
                FROM process_relation pr
                JOIN baseline_document d ON d.id = pr.process_document_id
                JOIN workbook_sheet s ON s.id = pr.workbook_sheet_id
                JOIN drawing_object connector ON connector.id = pr.connector_drawing_object_id
                LEFT JOIN drawing_object source ON source.id = pr.source_drawing_object_id
                LEFT JOIN drawing_object target ON target.id = pr.target_drawing_object_id
                WHERE pr.connector_drawing_object_id IS NOT NULL
                  AND (? IS NULL OR d.process_code = ?)
                  AND (? IS NULL OR pr.relation_status = ? OR pr.analysis_status = ?)
                ORDER BY d.process_code, s.sheet_index, connector.object_index
                LIMIT ?
                """, processCode, processCode, status, status, status, Math.max(1, Math.min(limit, 2000)));
    }

    @Transactional
    public Map<String, Object> createReviewedRelation(long connectorCandidateId, RelationReviewRequest request) {
        Long connectorObjectId = jdbcTemplate.queryForObject("""
                SELECT connector_drawing_object_id FROM process_relation
                WHERE id = ? AND connector_drawing_object_id IS NOT NULL
                """, Long.class, connectorCandidateId);
        validateStatus(request.relationStatus());
        validateFlowType(request.flowType());
        validateRelationType(request.relationType());
        if (request.sourceEquipmentId() == null && request.targetEquipmentId() == null
                && (request.destinationText() == null || request.destinationText().isBlank())) {
            throw new IllegalArgumentException("A reviewed relation must identify an equipment endpoint or destination text");
        }
        validateReviewReferences(connectorCandidateId, request);
        Long[] releaseAndDocument = jdbcTemplate.queryForObject("""
                SELECT pr.baseline_release_id, pr.process_document_id FROM process_relation pr WHERE pr.id = ?
                """, (rs, rowNum) -> new Long[] {rs.getLong(1), rs.getLong(2)}, connectorCandidateId);
        Long sheetId = jdbcTemplate.queryForObject("SELECT workbook_sheet_id FROM process_relation WHERE id = ?",
                Long.class, connectorCandidateId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO process_relation(
                        baseline_release_id, source_equipment_id, target_equipment_id, process_document_id,
                        workbook_sheet_id, source_drawing_object_id, target_drawing_object_id,
                        connector_drawing_object_id, connector_analysis_key, relation_type, flow_type,
                        quantity, quantity_unit, destination_text, relation_status, analysis_status,
                        evidence_text, reviewer_name, reviewed_at, source_cell_range, target_cell_range)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, releaseAndDocument[0]);
            statement.setObject(2, request.sourceEquipmentId());
            statement.setObject(3, request.targetEquipmentId());
            statement.setLong(4, releaseAndDocument[1]);
            statement.setLong(5, sheetId);
            statement.setObject(6, request.sourceDrawingObjectId());
            statement.setObject(7, request.targetDrawingObjectId());
            statement.setLong(8, connectorObjectId);
            statement.setString(9, request.relationType());
            statement.setString(10, request.flowType());
            statement.setBigDecimal(11, request.quantity());
            statement.setString(12, request.quantityUnit());
            statement.setString(13, request.destinationText());
            statement.setString(14, request.relationStatus());
            statement.setString(15, analysisStatus(request.relationStatus()));
            statement.setString(16, request.evidenceText());
            statement.setString(17, request.reviewerName());
            statement.setString(18, request.sourceCellRange());
            statement.setString(19, request.targetCellRange());
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("MariaDB did not return the reviewed relation ID");
        Map<String, Object> result = relationSnapshot(id.longValue());
        insertReviewHistory(id.longValue(), "RELATION_CREATED", request.reviewerName(),
                request.reviewerName(), null, result);
        return result;
    }

    @Transactional
    public Map<String, Object> review(long id, RelationReviewRequest request) {
        List<Long> relationIds = jdbcTemplate.query("SELECT id FROM process_relation WHERE id = ? AND connector_drawing_object_id IS NOT NULL",
                (rs, rowNum) -> rs.getLong(1), id);
        if (relationIds.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Connector relation candidate not found");
        }
        validateStatus(request.relationStatus());
        validateFlowType(request.flowType());
        validateRelationType(request.relationType());
        if (request.sourceEquipmentId() == null && request.targetEquipmentId() == null
                && (request.destinationText() == null || request.destinationText().isBlank())) {
            throw new IllegalArgumentException("A reviewed relation must identify an equipment endpoint or destination text");
        }
        validateReviewReferences(id, request);
        Map<String, Object> previous = relationSnapshot(id);
        jdbcTemplate.update("""
                UPDATE process_relation
                SET source_equipment_id = ?, target_equipment_id = ?,
                    source_drawing_object_id = ?, target_drawing_object_id = ?,
                    relation_status = ?, analysis_status = ?,
                    relation_type = ?, flow_type = ?, quantity = ?, quantity_unit = ?,
                    destination_text = ?, evidence_text = ?, reviewer_name = ?,
                    source_cell_range = ?, target_cell_range = ?,
                    reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, request.sourceEquipmentId(), request.targetEquipmentId(), request.sourceDrawingObjectId(),
                request.targetDrawingObjectId(), request.relationStatus(), analysisStatus(request.relationStatus()),
                request.relationType(), request.flowType(), request.quantity(), request.quantityUnit(),
                request.destinationText(), request.evidenceText(),
                request.reviewerName(), request.sourceCellRange(), request.targetCellRange(), id);
        Map<String, Object> result = relationSnapshot(id);
        insertReviewHistory(id, "RELATION_UPDATED", request.reviewerName(),
                request.reviewerName(), previous, result);
        return result;
    }

    @Transactional
    public Map<String, Object> addCandidateEvidence(long id, RelationCandidateEvidenceRequest request) {
        List<Map<String, Object>> candidate = jdbcTemplate.queryForList("""
                SELECT relation_status AS relationStatus, connector_drawing_object_id AS connectorDrawingObjectId
                FROM process_relation WHERE id = ?
                """, id);
        if (candidate.isEmpty() || candidate.get(0).get("connectorDrawingObjectId") == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Connector candidate not found");
        }
        if (!"REVIEW_REQUIRED".equals(candidate.get(0).get("relationStatus"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Evidence-only updates are allowed only while the candidate remains unconfirmed");
        }
        validateReviewReferences(id, request.sourceEquipmentId(), request.targetEquipmentId(),
                request.sourceDrawingObjectId(), request.targetDrawingObjectId());
        Map<String, Object> previous = relationSnapshot(id);
        jdbcTemplate.update("""
                UPDATE process_relation
                SET source_equipment_id = ?, target_equipment_id = ?,
                    source_drawing_object_id = ?, target_drawing_object_id = ?,
                    source_cell_range = ?, target_cell_range = ?, candidate_evidence_text = ?,
                    candidate_evidence_by = ?, candidate_evidence_at = CURRENT_TIMESTAMP
                WHERE id = ? AND relation_status = 'REVIEW_REQUIRED'
                """, request.sourceEquipmentId(), request.targetEquipmentId(),
                request.sourceDrawingObjectId(), request.targetDrawingObjectId(),
                request.sourceCellRange(), request.targetCellRange(), request.evidenceText(), request.actorName(), id);
        Map<String, Object> result = relationSnapshot(id);
        insertReviewHistory(id, "CANDIDATE_EVIDENCE_ADDED", request.actorName(), null, previous, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        return jdbcTemplate.queryForList("""
                SELECT id, process_relation_id AS processRelationId, action_type AS actionType,
                       actor_name AS actorName, reviewer_name AS reviewerName,
                       previous_snapshot_json AS previousSnapshot,
                       resulting_snapshot_json AS resultingSnapshot, occurred_at AS occurredAt
                FROM process_relation_review_history WHERE process_relation_id = ?
                ORDER BY occurred_at, id
                """, id);
    }

    private Map<String, Object> relationSnapshot(long id) {
        return jdbcTemplate.queryForMap("""
                SELECT id, baseline_release_id AS baselineReleaseId, source_equipment_id AS sourceEquipmentId,
                       target_equipment_id AS targetEquipmentId, process_document_id AS processDocumentId,
                       workbook_sheet_id AS workbookSheetId, source_drawing_object_id AS sourceDrawingObjectId,
                       target_drawing_object_id AS targetDrawingObjectId,
                       connector_drawing_object_id AS connectorDrawingObjectId, relation_type AS relationType,
                       flow_type AS flowType, quantity, quantity_unit AS quantityUnit,
                       destination_text AS destinationText, relation_status AS relationStatus,
                       analysis_status AS analysisStatus, evidence_text AS connectorEvidence,
                       candidate_evidence_text AS candidateEvidence,
                       candidate_evidence_by AS candidateEvidenceBy,
                       candidate_evidence_at AS candidateEvidenceAt,
                       reviewer_name AS reviewerName, reviewed_at AS reviewedAt,
                       source_cell_range AS sourceCellRange, target_cell_range AS targetCellRange
                FROM process_relation WHERE id = ?
                """, id);
    }

    private void insertReviewHistory(long relationId, String action, String actor, String reviewer,
                                     Map<String, Object> previous, Map<String, Object> result) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO process_relation_review_history(
                        process_relation_id, action_type, actor_name, reviewer_name,
                        previous_snapshot_json, resulting_snapshot_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, relationId, action, actor, reviewer,
                    previous == null ? null : objectMapper.writeValueAsString(previous),
                    objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize relation review history", exception);
        }
    }

    private String analysisStatus(String relationStatus) {
        return "REVIEW_REQUIRED".equals(relationStatus) ? "REVIEW_REQUIRED" : "MANUALLY_RESOLVED";
    }

    private void validateRelationType(String type) {
        if (type == null || !List.of("PROCESS", "WASTEWATER", "WASTE", "RECYCLE", "BRANCH",
                "MERGE", "PARALLEL", "RECIRCULATION", "UNKNOWN").contains(type)) {
            throw new IllegalArgumentException("Unsupported relationType");
        }
    }

    private void validateReviewReferences(long relationId, RelationReviewRequest request) {
        validateReviewReferences(relationId, request.sourceEquipmentId(), request.targetEquipmentId(),
                request.sourceDrawingObjectId(), request.targetDrawingObjectId());
    }

    private void validateReviewReferences(long relationId, RelationCandidateEvidenceRequest request) {
        validateReviewReferences(relationId, request.sourceEquipmentId(), request.targetEquipmentId(),
                request.sourceDrawingObjectId(), request.targetDrawingObjectId());
    }

    private void validateReviewReferences(long relationId, Long sourceEquipmentId, Long targetEquipmentId,
                                          Long sourceDrawingObjectId, Long targetDrawingObjectId) {
        Long releaseId = jdbcTemplate.queryForObject(
                "SELECT baseline_release_id FROM process_relation WHERE id = ?", Long.class, relationId);
        if (sourceEquipmentId != null && jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equipment_record WHERE id = ? AND baseline_release_id = ?",
                Integer.class, sourceEquipmentId, releaseId) == 0) {
            throw new IllegalArgumentException("Source equipment is not part of the V1.0 baseline");
        }
        if (targetEquipmentId != null && jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equipment_record WHERE id = ? AND baseline_release_id = ?",
                Integer.class, targetEquipmentId, releaseId) == 0) {
            throw new IllegalArgumentException("Target equipment is not part of the V1.0 baseline");
        }
        Long sheetId = jdbcTemplate.queryForObject("SELECT workbook_sheet_id FROM process_relation WHERE id = ?",
                Long.class, relationId);
        for (Long objectId : Arrays.asList(sourceDrawingObjectId, targetDrawingObjectId)) {
            if (objectId != null && jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM drawing_object WHERE id = ? AND workbook_sheet_id = ?",
                    Integer.class, objectId, sheetId) == 0) {
                throw new IllegalArgumentException("Selected drawing object must belong to the candidate connector sheet");
            }
        }
    }

    private String cellAddress(Integer column, Integer row) {
        if (column == null || row == null) return null;
        int number = column + 1;
        StringBuilder name = new StringBuilder();
        while (number > 0) {
            int remainder = (number - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            number = (number - 1) / 26;
        }
        return name + Integer.toString(row + 1);
    }

    private boolean hasArrow(String type) {
        return type != null && !"none".equalsIgnoreCase(type);
    }

    private String evidenceJson(String processCode, String sheetName, WorkbookInventory.DrawingObject object,
                                boolean arrow, boolean hasStartBinding, boolean hasEndBinding) {
        Map<String, Object> details = new HashMap<>();
        details.put("processCode", processCode);
        details.put("sheetName", sheetName);
        details.put("objectIndex", object.objectIndex());
        details.put("shapeId", object.shapeId());
        details.put("objectName", object.objectName());
        details.put("geometryPreset", object.geometryPreset());
        details.put("anchorFromCell", cellAddress(object.startColumn(), object.startRow()));
        details.put("anchorToCell", cellAddress(object.endColumn(), object.endRow()));
        details.put("anchorFromOffsetEmu", Arrays.asList(object.startColumnOffset(), object.startRowOffset()));
        details.put("anchorToOffsetEmu", Arrays.asList(object.endColumnOffset(), object.endRowOffset()));
        details.put("anchorFromAbsolutePointEmu", Arrays.asList(object.startAnchorXEmu(), object.startAnchorYEmu()));
        details.put("anchorToAbsolutePointEmu", Arrays.asList(object.endAnchorXEmu(), object.endAnchorYEmu()));
        details.put("shapeTransformLocalEmu", Arrays.asList(object.transformXEmu(), object.transformYEmu(),
                object.transformWidthEmu(), object.transformHeightEmu()));
        details.put("rotation60000", object.rotation60000());
        details.put("flipHorizontal", object.flipHorizontal());
        details.put("flipVertical", object.flipVertical());
        details.put("headArrowType", object.headArrowType());
        details.put("tailArrowType", object.tailArrowType());
        details.put("directionalArrowMetadata", arrow);
        details.put("explicitStartShapeId", object.startConnectedShapeId());
        details.put("explicitEndShapeId", object.endConnectedShapeId());
        details.put("explicitStartBindingResolved", hasStartBinding);
        details.put("explicitEndBindingResolved", hasEndBinding);
        details.put("meaning", "Visual connector segment only; not an equipment route without review.");
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize connector analysis evidence", exception);
        }
    }

    private void validateStatus(String status) {
        if (!List.of("CONFIRMED", "INFERRED", "REVIEW_REQUIRED").contains(status)) {
            throw new IllegalArgumentException("relationStatus must be CONFIRMED, INFERRED, or REVIEW_REQUIRED");
        }
    }

    private void validateFlowType(String flowType) {
        if (flowType != null && !List.of("WASTEWATER", "WASTE", "PROCESS", "UNKNOWN").contains(flowType)) {
            throw new IllegalArgumentException("Unsupported flowType");
        }
    }

    private record BaselineWorkbook(long documentId, String processCode, byte[] content) {
    }
}
