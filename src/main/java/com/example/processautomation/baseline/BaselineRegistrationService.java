package com.example.processautomation.baseline;

import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.MasterWorkbookParser;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import com.example.processautomation.model.MasterEquipment;
import com.example.processautomation.model.ParsedMaster;
import com.example.processautomation.model.ParsedMaster.ParseIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BaselineRegistrationService {
    public static final String BASELINE_VERSION = "V1.0";
    private static final String MASTER_FILENAME = "전체 목록.xlsx";
    private static final int MAX_WORKBOOK_BYTES = 10 * 1024 * 1024;
    private static final Pattern PROCESS_CODE_PATTERN = Pattern.compile("^(PW|PU|P)-\\d{2}(?:-\\d{2})?");
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");

    private final JdbcTemplate jdbcTemplate;
    private final MasterWorkbookParser masterParser;
    private final OoxmlPackageInspector inspector;
    private final ObjectMapper objectMapper;

    public BaselineRegistrationService(JdbcTemplate jdbcTemplate, MasterWorkbookParser masterParser,
                                       OoxmlPackageInspector inspector, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterParser = masterParser;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BaselineRegistrationResult register(byte[] masterBytes, List<BaselineSourceFile> sourceFiles) {
        if (masterBytes == null || masterBytes.length == 0) {
            throw new InvalidWorkbookException("The master workbook is empty");
        }
        if (masterBytes.length > MAX_WORKBOOK_BYTES) {
            throw new InvalidWorkbookException("The master workbook exceeds the 10 MB limit");
        }
        if (sourceFiles == null || sourceFiles.size() != 15) {
            throw new InvalidWorkbookException("Exactly 15 process workbooks are required for the V1.0 baseline");
        }

        String masterHash = sha256(masterBytes);
        Map<String, BaselineSourceFile> filesByCode = validateProcessFiles(sourceFiles);
        ParsedMaster parsedMaster = masterParser.parse(masterBytes);
        validateUniquePermitIds(parsedMaster.equipment());
        WorkbookInventory masterInventory = inspector.inspect(masterBytes);
        ExistingBaseline existing = findExistingBaseline();
        if (existing != null) {
            if (existing.masterSha256().equals(masterHash) && sameStoredProcessFiles(existing.id(), sourceFiles)) {
                return resultForExisting(existing.id());
            }
            throw new BaselineConflictException("Locked baseline " + BASELINE_VERSION
                    + " already exists with different source files. It was not overwritten.");
        }

        long releaseId = insertRelease(masterHash);
        Map<String, Long> documentIds = new HashMap<>();
        List<BaselineSourceFile> orderedFiles = new ArrayList<>(sourceFiles);
        orderedFiles.sort(Comparator.comparing(BaselineSourceFile::processCode));
        for (BaselineSourceFile file : orderedFiles) {
            long documentId = insertDocument(releaseId, "PROCESS", file.processCode(), file.originalFilename(),
                    file.contentType(), file.content(), file.sha256(), file.inventory());
            documentIds.put(file.processCode(), documentId);
            persistInventory(documentId, file.inventory());
        }
        long masterDocumentId = insertDocument(releaseId, "MASTER", null, MASTER_FILENAME,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", masterBytes,
                masterHash, masterInventory);
        persistInventory(masterDocumentId, masterInventory);

        Map<String, Long> equipmentIds = persistEquipment(releaseId, masterDocumentId, parsedMaster.equipment());
        persistFlows(equipmentIds, parsedMaster.equipment());
        persistMappings(releaseId, parsedMaster.equipment(), filesByCode, documentIds, equipmentIds);
        persistIssues(releaseId, masterDocumentId, parsedMaster, filesByCode, equipmentIds);
        persistImportRun(releaseId, MASTER_FILENAME, masterHash, parsedMaster.equipment().size(), parsedMaster.issues().size());

        return result(releaseId, parsedMaster.equipment(), filesByCode, masterInventory);
    }

    @Transactional(readOnly = true)
    public BaselineRegistrationResult current() {
        ExistingBaseline existing = findExistingBaseline();
        if (existing == null) {
            throw new BaselineConflictException("No locked baseline V1.0 has been registered");
        }
        return resultForExisting(existing.id());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDocuments() {
        return jdbcTemplate.queryForList("""
                SELECT id, document_role AS role, process_code AS processCode, original_filename AS filename,
                       byte_size AS byteSize, sha256, sheet_count AS sheetCount, drawing_part_count AS drawingParts,
                       shape_count AS shapes, connector_count AS connectors, picture_count AS pictures
                FROM baseline_document WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)
                ORDER BY document_role, process_code, original_filename
                """, BASELINE_VERSION);
    }

    @Transactional(readOnly = true)
    public byte[] originalDocument(long documentId) {
        List<byte[]> values = jdbcTemplate.query("SELECT content_bytes FROM baseline_document WHERE id = ?",
                (rs, rowNum) -> rs.getBytes(1), documentId);
        if (values.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Baseline document not found");
        }
        return values.get(0);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEquipment(String processCode, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        StringBuilder sql = new StringBuilder("""
                SELECT permit_management_no AS permitManagementNo, business_item_no AS businessItemNo,
                       major_process_code AS majorProcessCode, middle_process_code AS middleProcessCode,
                       process_category AS processCategory, facility_name AS facilityName,
                       permit_name AS permitName, capacity_text AS capacity, capacity_unit AS capacityUnit,
                       quantity_text AS quantity, pid_number AS pidNumber, closed_status AS closedStatus,
                       source_row_number AS sourceRowNumber
                FROM equipment_record
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(BASELINE_VERSION);
        if (StringUtils.hasText(processCode)) {
            sql.append(" AND (major_process_code = ? OR middle_process_code = ? OR REPLACE(middle_process_code, 'PP-', 'P-') = ?)");
            args.add(processCode.trim());
            args.add(processCode.trim());
            args.add(processCode.trim());
        }
        if (StringUtils.hasText(query)) {
            sql.append(" AND (permit_management_no LIKE ? OR business_item_no LIKE ? OR facility_name LIKE ?)");
            String pattern = "%" + query.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY master_order LIMIT ? OFFSET ?");
        args.add(safeSize);
        args.add(safePage * safeSize);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMappings(String status, String processCode, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.permit_management_no AS permitManagementNo, e.business_item_no AS businessItemNo,
                       e.middle_process_code AS middleProcessCode, e.facility_name AS facilityName,
                       m.mapping_status AS status, m.match_method AS matchMethod, m.evidence_text AS evidence,
                       d.process_code AS processCode, s.sheet_name AS sheetName
                FROM equipment_mapping m
                JOIN equipment_record e ON e.id = m.equipment_id
                LEFT JOIN baseline_document d ON d.id = m.process_document_id
                LEFT JOIN workbook_sheet s ON s.id = m.workbook_sheet_id
                WHERE m.baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(BASELINE_VERSION);
        if (StringUtils.hasText(status)) {
            sql.append(" AND m.mapping_status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(processCode)) {
            sql.append(" AND (e.major_process_code = ? OR e.middle_process_code = ? OR d.process_code = ?)");
            args.add(processCode.trim());
            args.add(processCode.trim());
            args.add(processCode.trim());
        }
        sql.append(" ORDER BY e.master_order, s.sheet_index LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listIssues(String severity, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT issue_code AS code, severity, field_name AS fieldName, source_row_number AS sourceRowNumber,
                       detail_text AS detail
                FROM validation_issue
                WHERE baseline_release_id = (SELECT id FROM baseline_release WHERE version_code = ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(BASELINE_VERSION);
        if (StringUtils.hasText(severity)) {
            sql.append(" AND severity = ?");
            args.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY severity DESC, source_row_number LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private Map<String, BaselineSourceFile> validateProcessFiles(List<BaselineSourceFile> sourceFiles) {
        Map<String, BaselineSourceFile> filesByCode = new TreeMap<>();
        for (BaselineSourceFile file : sourceFiles) {
            if (file.content().length == 0 || file.content().length > MAX_WORKBOOK_BYTES) {
                throw new InvalidWorkbookException("Invalid or oversized process workbook: " + file.originalFilename());
            }
            Matcher matcher = PROCESS_CODE_PATTERN.matcher(file.originalFilename());
            if (!matcher.find()) {
                throw new InvalidWorkbookException("Cannot derive an exact process code from filename: " + file.originalFilename());
            }
            String code = matcher.group();
            if (!code.equals(file.processCode())) {
                throw new InvalidWorkbookException("Process code does not match the file name: " + file.originalFilename());
            }
            if (filesByCode.putIfAbsent(code, file) != null) {
                throw new InvalidWorkbookException("Duplicate process document code: " + code);
            }
            if (file.inventory().sheets().isEmpty()) {
                throw new InvalidWorkbookException("Process workbook has no sheets: " + file.originalFilename());
            }
        }
        Set<String> expected = Set.of("P-01", "P-02", "P-03", "P-04", "P-05", "P-06", "P-07", "P-08",
                "PU-01", "PU-02", "PU-03", "PW-01", "PW-02", "PW-02-05", "PW-03");
        if (!filesByCode.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(filesByCode.keySet());
            Set<String> extra = new HashSet<>(filesByCode.keySet());
            extra.removeAll(expected);
            throw new InvalidWorkbookException("The 15 process files do not match the required baseline set. Missing: "
                    + missing + "; unexpected: " + extra);
        }
        return filesByCode;
    }

    private void validateUniquePermitIds(List<MasterEquipment> equipment) {
        Set<String> ids = new HashSet<>();
        for (MasterEquipment item : equipment) {
            if (!ids.add(item.permitManagementNo())) {
                throw new InvalidWorkbookException("Duplicate permit management number: " + item.permitManagementNo());
            }
        }
    }

    private ExistingBaseline findExistingBaseline() {
        List<ExistingBaseline> rows = jdbcTemplate.query(
                "SELECT id, source_master_sha256 FROM baseline_release WHERE version_code = ?",
                (rs, rowNum) -> new ExistingBaseline(rs.getLong("id"), rs.getString("source_master_sha256")),
                BASELINE_VERSION);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean sameStoredProcessFiles(long releaseId, List<BaselineSourceFile> files) {
        Map<String, String> stored = new HashMap<>();
        jdbcTemplate.query("SELECT original_filename, sha256 FROM baseline_document WHERE baseline_release_id = ? AND document_role = 'PROCESS'",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    stored.put(rs.getString(1), rs.getString(2));
                }, releaseId);
        if (stored.size() != files.size()) {
            return false;
        }
        return files.stream().allMatch(file -> file.sha256().equals(stored.get(file.originalFilename())));
    }

    private long insertRelease(String masterHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO baseline_release(version_code, description, source_master_sha256, locked) VALUES (?, ?, ?, TRUE)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, BASELINE_VERSION);
            statement.setString(2, "Initial immutable master and 15 final process workbooks");
            statement.setString(3, masterHash);
            return statement;
        };
        try {
            jdbcTemplate.update(creator, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new BaselineConflictException("Baseline V1.0 was registered concurrently; no existing baseline was modified.");
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("MariaDB did not return the baseline ID");
        }
        return key.longValue();
    }

    private long insertDocument(long releaseId, String role, String processCode, String filename, String contentType,
                                byte[] bytes, String sha256, WorkbookInventory inventory) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO baseline_document(
                        baseline_release_id, document_role, process_code, original_filename, content_type,
                        byte_size, sha256, sheet_count, drawing_part_count, shape_count, connector_count,
                        picture_count, content_bytes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, releaseId);
            statement.setString(2, role);
            statement.setString(3, processCode);
            statement.setString(4, filename);
            statement.setString(5, StringUtils.hasText(contentType) ? contentType : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            statement.setLong(6, bytes.length);
            statement.setString(7, sha256);
            statement.setInt(8, inventory.sheets().size());
            statement.setInt(9, inventory.drawingPartCount());
            statement.setInt(10, inventory.shapeCount());
            statement.setInt(11, inventory.connectorCount());
            statement.setInt(12, inventory.pictureCount());
            statement.setBytes(13, bytes);
            return statement;
        };
        jdbcTemplate.update(creator, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("MariaDB did not return a document ID for " + filename);
        }
        return key.longValue();
    }

    private void persistInventory(long documentId, WorkbookInventory inventory) {
        for (WorkbookInventory.SheetInventory sheet : inventory.sheets()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            PreparedStatementCreator creator = connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO workbook_sheet(
                            baseline_document_id, sheet_index, sheet_name, state_name, physical_row_count,
                            last_column_index, drawing_part_name, shape_count, connector_count, picture_count,
                            explicit_connector_bindings)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                int shapes = 0;
                int connectors = 0;
                int pictures = 0;
                int bindings = 0;
                for (WorkbookInventory.DrawingObject object : sheet.drawingObjects()) {
                    switch (object.objectType()) {
                        case "SHAPE" -> shapes++;
                        case "CONNECTOR" -> connectors++;
                        case "PICTURE" -> pictures++;
                        default -> { }
                    }
                    if (object.startConnectedShapeId() != null || object.endConnectedShapeId() != null) {
                        bindings++;
                    }
                }
                statement.setLong(1, documentId);
                statement.setInt(2, sheet.sheetIndex());
                statement.setString(3, sheet.sheetName());
                statement.setString(4, sheet.visibility());
                statement.setInt(5, sheet.physicalRowCount());
                statement.setInt(6, sheet.lastColumnIndex());
                statement.setString(7, sheet.drawingPartName());
                statement.setInt(8, shapes);
                statement.setInt(9, connectors);
                statement.setInt(10, pictures);
                statement.setInt(11, bindings);
                return statement;
            };
            jdbcTemplate.update(creator, keyHolder);
            Number sheetId = keyHolder.getKey();
            if (sheetId == null) {
                throw new IllegalStateException("MariaDB did not return a sheet ID for " + sheet.sheetName());
            }
            persistDrawingObjects(sheetId.longValue(), sheet);
        }
    }

    private void persistDrawingObjects(long sheetId, WorkbookInventory.SheetInventory sheet) {
        if (sheet.drawingObjects().isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO drawing_object(
                    workbook_sheet_id, package_part_name, object_index, object_type, shape_id, object_name,
                    text_content, geometry_preset, start_col, start_row, start_col_offset, start_row_offset,
                    end_col, end_row, end_col_offset, end_row_offset, start_connected_shape_id,
                    end_connected_shape_id, head_arrow_type, tail_arrow_type, transform_x_emu, transform_y_emu,
                    transform_width_emu, transform_height_emu, rotation_60000, flip_horizontal, flip_vertical)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sheet.drawingObjects(), 400, (statement, object) -> {
            statement.setLong(1, sheetId);
            statement.setString(2, sheet.drawingPartName());
            statement.setInt(3, object.objectIndex());
            statement.setString(4, object.objectType());
            statement.setString(5, object.shapeId());
            statement.setString(6, object.objectName());
            statement.setString(7, object.textContent());
            statement.setString(8, object.geometryPreset());
            setInteger(statement, 9, object.startColumn());
            setInteger(statement, 10, object.startRow());
            setLong(statement, 11, object.startColumnOffset());
            setLong(statement, 12, object.startRowOffset());
            setInteger(statement, 13, object.endColumn());
            setInteger(statement, 14, object.endRow());
            setLong(statement, 15, object.endColumnOffset());
            setLong(statement, 16, object.endRowOffset());
            statement.setString(17, object.startConnectedShapeId());
            statement.setString(18, object.endConnectedShapeId());
            statement.setString(19, object.headArrowType());
            statement.setString(20, object.tailArrowType());
            setLong(statement, 21, object.transformXEmu());
            setLong(statement, 22, object.transformYEmu());
            setLong(statement, 23, object.transformWidthEmu());
            setLong(statement, 24, object.transformHeightEmu());
            if (object.rotation60000() == null) statement.setNull(25, java.sql.Types.INTEGER);
            else statement.setInt(25, object.rotation60000());
            if (object.flipHorizontal() == null) statement.setNull(26, java.sql.Types.BOOLEAN);
            else statement.setBoolean(26, object.flipHorizontal());
            if (object.flipVertical() == null) statement.setNull(27, java.sql.Types.BOOLEAN);
            else statement.setBoolean(27, object.flipVertical());
        });
    }

    private Map<String, Long> persistEquipment(long releaseId, long masterDocumentId, List<MasterEquipment> equipment) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO equipment_record(
                    baseline_release_id, source_document_id, source_sheet_name, source_row_number, master_order,
                    permit_management_no, business_item_no, middle_process_code, major_process_code, process_category,
                    facility_name, permit_name, permit_category, capacity_text, capacity_unit, quantity_text,
                    operating_hours_text, annual_operating_days_text, purpose_text, pid_number, closed_status,
                    paper_exists_status, raw_values_json)
                VALUES (?, ?, '마스터시트', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, equipment, 400, (statement, record) -> {
            statement.setLong(1, releaseId);
            statement.setLong(2, masterDocumentId);
            statement.setInt(3, record.sourceRowNumber());
            statement.setInt(4, record.masterOrder());
            statement.setString(5, record.permitManagementNo());
            statement.setString(6, emptyToNull(record.businessItemNo()));
            statement.setString(7, emptyToNull(record.middleProcessCode()));
            statement.setString(8, emptyToNull(record.majorProcessCode()));
            statement.setString(9, emptyToNull(record.processCategory()));
            statement.setString(10, emptyToNull(record.facilityName()));
            statement.setString(11, emptyToNull(record.permitName()));
            statement.setString(12, emptyToNull(record.permitCategory()));
            statement.setString(13, emptyToNull(record.capacityText()));
            statement.setString(14, emptyToNull(record.capacityUnit()));
            statement.setString(15, emptyToNull(record.quantityText()));
            statement.setString(16, emptyToNull(record.operatingHoursText()));
            statement.setString(17, emptyToNull(record.annualOperatingDaysText()));
            statement.setString(18, emptyToNull(record.purposeText()));
            statement.setString(19, emptyToNull(record.pidNumber()));
            statement.setString(20, emptyToNull(record.closedStatus()));
            statement.setString(21, emptyToNull(record.paperExistsStatus()));
            statement.setString(22, toJson(record.rawValues()));
        });
        Map<String, Long> ids = new HashMap<>();
        jdbcTemplate.query("SELECT id, permit_management_no FROM equipment_record WHERE baseline_release_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    ids.put(rs.getString("permit_management_no"), rs.getLong("id"));
                }, releaseId);
        if (ids.size() != equipment.size()) {
            throw new IllegalStateException("Not all master equipment records were persisted");
        }
        return ids;
    }

    private void persistFlows(Map<String, Long> equipmentIds, List<MasterEquipment> equipment) {
        List<FlowRow> flows = new ArrayList<>();
        for (MasterEquipment item : equipment) {
            addFlow(flows, equipmentIds.get(item.permitManagementNo()), item, "WASTE", item.wasteType(),
                    item.wasteQuantityText(), item.wasteDestination());
            addFlow(flows, equipmentIds.get(item.permitManagementNo()), item, "WASTEWATER", item.wastewaterType(),
                    item.wastewaterQuantityText(), item.wastewaterDestination());
        }
        if (flows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO equipment_flow(
                    equipment_id, flow_type, material_type, quantity, quantity_source_text, quantity_unit,
                    destination_text, completeness_status, source_row_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, flows, 300, (statement, flow) -> {
            statement.setLong(1, flow.equipmentId());
            statement.setString(2, flow.flowType());
            statement.setString(3, emptyToNull(flow.materialType()));
            if (flow.quantity() == null) statement.setNull(4, java.sql.Types.DECIMAL);
            else statement.setBigDecimal(4, flow.quantity());
            statement.setString(5, emptyToNull(flow.quantityText()));
            statement.setString(6, flow.quantityUnit());
            statement.setString(7, emptyToNull(flow.destination()));
            statement.setString(8, flow.status());
            statement.setInt(9, flow.rowNumber());
        });
    }

    private void addFlow(List<FlowRow> flows, Long equipmentId, MasterEquipment item, String type, String material,
                         String quantityText, String destination) {
        if (isBlank(material) && isBlank(quantityText) && isBlank(destination)) {
            return;
        }
        java.math.BigDecimal quantity = parseQuantity(quantityText);
        String status;
        if (quantityText != null && !quantityText.isBlank() && (quantity == null || quantity.signum() < 0)) {
            status = "INVALID";
        } else if ((!isBlank(quantityText) && !isBlank(destination)) || (quantity != null && quantity.signum() == 0)) {
            status = "COMPLETE";
        } else {
            status = "REVIEW_REQUIRED";
        }
        flows.add(new FlowRow(equipmentId, type, material, quantity, quantityText,
                type.equals("WASTE") ? "톤/일" : "톤/일", destination, status, item.sourceRowNumber()));
    }

    private void persistMappings(long releaseId, List<MasterEquipment> equipment,
                                 Map<String, BaselineSourceFile> filesByCode,
                                 Map<String, Long> documentIds, Map<String, Long> equipmentIds) {
        Map<String, Map<String, Long>> sheetIdsByCode = new HashMap<>();
        for (Map.Entry<String, BaselineSourceFile> entry : filesByCode.entrySet()) {
            Long documentId = documentIds.get(entry.getKey());
            Map<String, Long> sheetIds = new HashMap<>();
            jdbcTemplate.query("SELECT id, sheet_name FROM workbook_sheet WHERE baseline_document_id = ?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        sheetIds.put(rs.getString("sheet_name"), rs.getLong("id"));
                    }, documentId);
            sheetIdsByCode.put(entry.getKey(), sheetIds);
        }

        Map<WorkbookInventory.SheetInventory, String> searchableTextBySheet = new IdentityHashMap<>();
        for (BaselineSourceFile sourceFile : filesByCode.values()) {
            for (WorkbookInventory.SheetInventory sheet : sourceFile.inventory().sheets()) {
                searchableTextBySheet.put(sheet, sheet.searchableText());
            }
        }
        List<MappingRow> mappings = new ArrayList<>();
        for (MasterEquipment record : equipment) {
            String documentCode = chooseDocumentCode(record, filesByCode.keySet());
            Long equipmentId = equipmentIds.get(record.permitManagementNo());
            if (documentCode == null) {
                mappings.add(new MappingRow(releaseId, equipmentId, null, null, "NO_PROCESS_DOCUMENT", null,
                        "No registered process workbook for " + firstNonBlank(record.middleProcessCode(), record.majorProcessCode()),
                        record.majorProcessCode()));
                continue;
            }
            BaselineSourceFile document = filesByCode.get(documentCode);
            String normalizedSheetCode = normalizeProcessCode(record.middleProcessCode());
            List<WorkbookInventory.SheetInventory> candidateSheets = document.inventory().sheets().stream()
                    .filter(sheet -> sheetCodeMatches(sheet.sheetName(), normalizedSheetCode)).toList();
            if (candidateSheets.isEmpty()) {
                candidateSheets = document.inventory().sheets();
            }
            List<WorkbookInventory.SheetInventory> exactMatches = new ArrayList<>();
            List<WorkbookInventory.SheetInventory> itemMatches = new ArrayList<>();
            for (WorkbookInventory.SheetInventory sheet : candidateSheets) {
                String searchableText = searchableTextBySheet.get(sheet);
                if (containsToken(searchableText, record.permitManagementNo())) {
                    exactMatches.add(sheet);
                } else if (isUniqueBusinessItem(record.businessItemNo(), equipment)
                        && containsToken(searchableText, record.businessItemNo())) {
                    itemMatches.add(sheet);
                }
            }
            List<WorkbookInventory.SheetInventory> selected = exactMatches.isEmpty() ? itemMatches : exactMatches;
            String method = exactMatches.isEmpty() ? "BUSINESS_ITEM_NO" : "PERMIT_MANAGEMENT_NO";
            if (selected.isEmpty()) {
                mappings.add(new MappingRow(releaseId, equipmentId, documentIds.get(documentCode), null,
                        "UNMATCHED", null,
                        "Not found on expected process sheet(s) " + candidateSheets.stream()
                                .map(WorkbookInventory.SheetInventory::sheetName).toList(), documentCode));
            } else {
                String status = selected.size() > 1 ? "MULTI_SHEET_REVIEW"
                        : exactMatches.isEmpty() ? "MAPPED_ITEM_NO" : "MAPPED_PERMIT_ID";
                for (WorkbookInventory.SheetInventory sheet : selected) {
                    mappings.add(new MappingRow(releaseId, equipmentId, documentIds.get(documentCode),
                            sheetIdsByCode.get(documentCode).get(sheet.sheetName()), status, method,
                            record.permitManagementNo() + " / " + record.businessItemNo(), documentCode));
                }
            }
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO equipment_mapping(
                    baseline_release_id, equipment_id, process_document_id, workbook_sheet_id,
                    mapping_status, match_method, evidence_text, expected_process_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, mappings, 500, (statement, mapping) -> {
            statement.setLong(1, mapping.releaseId());
            statement.setLong(2, mapping.equipmentId());
            setLong(statement, 3, mapping.documentId());
            setLong(statement, 4, mapping.sheetId());
            statement.setString(5, mapping.status());
            statement.setString(6, mapping.method());
            statement.setString(7, mapping.evidence());
            statement.setString(8, mapping.expectedProcessCode());
        });
    }

    private void persistIssues(long releaseId, long masterDocumentId, ParsedMaster parsed,
                               Map<String, BaselineSourceFile> processFiles, Map<String, Long> equipmentIds) {
        List<IssueRow> issues = new ArrayList<>();
        for (ParseIssue issue : parsed.issues()) {
            MasterEquipment row = parsed.equipment().stream()
                    .filter(item -> item.sourceRowNumber() == issue.sourceRowNumber()).findFirst().orElse(null);
            issues.add(new IssueRow(releaseId, row == null ? null : equipmentIds.get(row.permitManagementNo()),
                    masterDocumentId, issue.sourceRowNumber(), issue.severity(), issue.code(), issue.fieldName(), issue.detail()));
        }
        jdbcTemplate.query("""
                SELECT e.id, e.source_row_number, e.permit_management_no, m.mapping_status, m.evidence_text
                FROM equipment_mapping m JOIN equipment_record e ON e.id = m.equipment_id
                WHERE m.baseline_release_id = ? AND m.mapping_status IN ('UNMATCHED', 'NO_PROCESS_DOCUMENT', 'MULTI_SHEET_REVIEW')
                """, rs -> {
            String code = rs.getString("mapping_status");
            String detail = rs.getString("evidence_text");
            issues.add(new IssueRow(releaseId, rs.getLong("id"), masterDocumentId,
                    rs.getInt("source_row_number"), code.equals("MULTI_SHEET_REVIEW") ? "WARNING" : "REVIEW",
                    code, "공정도 매핑", detail));
        }, releaseId);

        if (issues.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO validation_issue(
                    baseline_release_id, equipment_id, source_document_id, source_row_number,
                    severity, issue_code, field_name, detail_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, issues, 400, (statement, issue) -> {
            statement.setLong(1, issue.releaseId());
            setLong(statement, 2, issue.equipmentId());
            setLong(statement, 3, issue.documentId());
            if (issue.rowNumber() == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, issue.rowNumber());
            statement.setString(5, issue.severity());
            statement.setString(6, issue.code());
            statement.setString(7, issue.fieldName());
            statement.setString(8, issue.detail());
        });
    }

    private void persistImportRun(long releaseId, String filename, String sourceHash, int count, int warningCount) {
        jdbcTemplate.update("""
                INSERT INTO import_run(baseline_release_id, run_type, status, source_filename, source_sha256,
                                       record_count, warning_count, summary_json, completed_at)
                VALUES (?, 'BASELINE', 'COMPLETED', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, releaseId, filename, sourceHash, count, warningCount,
                toJson(Map.of("baselineVersion", BASELINE_VERSION, "recordCount", count)));
    }

    private BaselineRegistrationResult result(long releaseId, List<MasterEquipment> equipment,
                                              Map<String, BaselineSourceFile> processFiles,
                                              WorkbookInventory masterInventory) {
        int matchedPermit = countMatchMethod(releaseId, "PERMIT_MANAGEMENT_NO");
        int matchedItem = countMatchMethod(releaseId, "BUSINESS_ITEM_NO");
        int unmatched = countMapping(releaseId, "UNMATCHED") + countMapping(releaseId, "NO_PROCESS_DOCUMENT");
        int multi = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT equipment_id) FROM equipment_mapping WHERE baseline_release_id = ? AND mapping_status = 'MULTI_SHEET_REVIEW'", Integer.class, releaseId);
        List<String> missing = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (MasterEquipment item : equipment) {
            counts.merge(item.majorProcessCode(), 1, Integer::sum);
            String code = chooseDocumentCode(item, processFiles.keySet());
            if (code == null && !missing.contains(item.majorProcessCode())) {
                missing.add(item.majorProcessCode());
            }
        }
        int sheetCount = processFiles.values().stream().mapToInt(file -> file.inventory().sheets().size()).sum();
        int drawingParts = processFiles.values().stream().mapToInt(file -> file.inventory().drawingPartCount()).sum();
        int shapes = processFiles.values().stream().mapToInt(file -> file.inventory().shapeCount()).sum();
        int connectors = processFiles.values().stream().mapToInt(file -> file.inventory().connectorCount()).sum();
        int pictures = processFiles.values().stream().mapToInt(file -> file.inventory().pictureCount()).sum();
        int issues = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM validation_issue WHERE baseline_release_id = ?", Integer.class, releaseId);
        return new BaselineRegistrationResult(releaseId, BASELINE_VERSION, MASTER_FILENAME,
                findExistingBaseline().masterSha256(), equipment.size(), processFiles.size(), sheetCount,
                drawingParts, shapes, connectors, pictures, matchedPermit, matchedItem, unmatched, multi,
                issues, missing.stream().sorted().toList(), counts);
    }

    private BaselineRegistrationResult resultForExisting(long releaseId) {
        String masterHash = jdbcTemplate.queryForObject("SELECT source_master_sha256 FROM baseline_release WHERE id = ?",
                String.class, releaseId);
        int equipmentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM equipment_record WHERE baseline_release_id = ?", Integer.class, releaseId);
        int processCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM baseline_document WHERE baseline_release_id = ? AND document_role = 'PROCESS'", Integer.class, releaseId);
        int sheetCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workbook_sheet s JOIN baseline_document d ON d.id = s.baseline_document_id WHERE d.baseline_release_id = ? AND d.document_role = 'PROCESS'", Integer.class, releaseId);
        int drawingParts = querySum("SELECT COALESCE(SUM(drawing_part_count),0) FROM baseline_document WHERE baseline_release_id = ? AND document_role='PROCESS'", releaseId);
        int shapes = querySum("SELECT COALESCE(SUM(shape_count),0) FROM baseline_document WHERE baseline_release_id = ? AND document_role='PROCESS'", releaseId);
        int connectors = querySum("SELECT COALESCE(SUM(connector_count),0) FROM baseline_document WHERE baseline_release_id = ? AND document_role='PROCESS'", releaseId);
        int pictures = querySum("SELECT COALESCE(SUM(picture_count),0) FROM baseline_document WHERE baseline_release_id = ? AND document_role='PROCESS'", releaseId);
        int matchedPermit = countMatchMethod(releaseId, "PERMIT_MANAGEMENT_NO");
        int matchedItem = countMatchMethod(releaseId, "BUSINESS_ITEM_NO");
        int unmatched = countMapping(releaseId, "UNMATCHED") + countMapping(releaseId, "NO_PROCESS_DOCUMENT");
        int multi = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT equipment_id) FROM equipment_mapping WHERE baseline_release_id = ? AND mapping_status = 'MULTI_SHEET_REVIEW'", Integer.class, releaseId);
        int issues = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM validation_issue WHERE baseline_release_id = ?", Integer.class, releaseId);
        Map<String, Integer> byProcess = new TreeMap<>();
        jdbcTemplate.query("SELECT major_process_code, COUNT(*) FROM equipment_record WHERE baseline_release_id = ? GROUP BY major_process_code",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    byProcess.put(rs.getString(1), rs.getInt(2));
                }, releaseId);
        List<String> missing = jdbcTemplate.queryForList("""
                SELECT DISTINCT major_process_code FROM equipment_record e
                WHERE baseline_release_id = ? AND NOT EXISTS (
                    SELECT 1 FROM equipment_mapping m WHERE m.equipment_id = e.id
                    AND m.mapping_status NOT IN ('NO_PROCESS_DOCUMENT'))
                ORDER BY major_process_code
                """, String.class, releaseId);
        return new BaselineRegistrationResult(releaseId, BASELINE_VERSION, MASTER_FILENAME, masterHash,
                equipmentCount, processCount, sheetCount, drawingParts, shapes, connectors, pictures,
                matchedPermit, matchedItem, unmatched, multi, issues, missing, byProcess);
    }

    private int countMatchMethod(long releaseId, String method) {
        return jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT equipment_id) FROM equipment_mapping WHERE baseline_release_id = ? AND match_method = ?",
                Integer.class, releaseId, method);
    }

    private int countMapping(long releaseId, String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT equipment_id) FROM equipment_mapping WHERE baseline_release_id = ? AND mapping_status = ?",
                Integer.class, releaseId, status);
    }

    private int querySum(String sql, long releaseId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, releaseId);
    }

    private String chooseDocumentCode(MasterEquipment item, Set<String> fileCodes) {
        String middle = normalizeProcessCode(item.middleProcessCode());
        if (!middle.isBlank() && fileCodes.contains(middle)) {
            return middle;
        }
        String major = item.majorProcessCode() == null ? "" : item.majorProcessCode().trim();
        return fileCodes.contains(major) ? major : null;
    }

    private String normalizeProcessCode(String code) {
        if (code == null) return "";
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return trimmed.startsWith("PP-") ? "P-" + trimmed.substring(3) : trimmed;
    }

    private boolean sheetCodeMatches(String sheetName, String code) {
        if (sheetName == null || code == null || code.isBlank()) return false;
        String normalizedSheet = sheetName.trim().toUpperCase(Locale.ROOT);
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        if (!normalizedSheet.startsWith(normalizedCode)) return false;
        return normalizedSheet.length() == normalizedCode.length()
                || !Character.isLetterOrDigit(normalizedSheet.charAt(normalizedCode.length()));
    }

    private boolean containsToken(String text, String token) {
        if (text == null || token == null || token.isBlank() || token.equals("-")) return false;
        String escaped = Pattern.quote(token.trim());
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9])" + escaped + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(text).find();
    }

    private boolean isUniqueBusinessItem(String itemNo, List<MasterEquipment> equipment) {
        if (itemNo == null || itemNo.isBlank() || itemNo.equals("-")) return false;
        return equipment.stream().filter(item -> itemNo.equalsIgnoreCase(item.businessItemNo())).limit(2).count() == 1;
    }

    private java.math.BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = QUANTITY_PATTERN.matcher(text.replace('\u00a0', ' '));
        if (!matcher.find()) return null;
        try {
            return new java.math.BigDecimal(matcher.group().replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
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
            throw new IllegalStateException("Unable to serialize import data", exception);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void setInteger(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER);
        else statement.setInt(index, value);
    }

    private void setLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }

    private record ExistingBaseline(long id, String masterSha256) {
    }

    private record FlowRow(long equipmentId, String flowType, String materialType, java.math.BigDecimal quantity,
                           String quantityText, String quantityUnit, String destination, String status, int rowNumber) {
    }

    private record MappingRow(long releaseId, long equipmentId, Long documentId, Long sheetId, String status,
                              String method, String evidence, String expectedProcessCode) {
    }

    private record IssueRow(long releaseId, Long equipmentId, Long documentId, Integer rowNumber, String severity,
                            String code, String fieldName, String detail) {
    }
}
