package com.example.processautomation.material;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** User-scoped, append-only uploads and TEST_ONLY outputs; official baseline bytes are never rewritten. */
@Service
public class UserWorkingCopyService {
    public static final String COPY_KIND = "USER_WORKING_COPY";
    public static final String CONVERSION_VERSION = "USER-WORKING-COPY-V1";
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OoxmlPackageInspector inspector;
    private final EquipmentBoxProfileRepository profiles;
    private final NumericWorkingCopyService numericCopies;
    private final boolean enabled;

    public UserWorkingCopyService(JdbcTemplate jdbc, ObjectMapper mapper, OoxmlPackageInspector inspector,
                                  EquipmentBoxProfileRepository profiles, NumericWorkingCopyService numericCopies,
                                  @Value("${app.user-working-copies.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.inspector = inspector;
        this.profiles = profiles;
        this.numericCopies = numericCopies;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "사용자 작업본 기능은 V25 migration과 격리 구성에서 활성화해야 합니다.");
    }

    @Transactional(readOnly = true)
    public List<WorkingCopySummary> list(String username, long baselineDocumentId) {
        requireEnabled();
        requireUser(username);
        return jdbc.query("""
                SELECT w.id,w.baseline_document_id,w.source_sha256,w.working_sha256,w.working_filename,
                       w.version_number,w.previous_working_document_id,w.working_size_bytes,w.verification_status,
                       w.sheet_metadata_json,w.created_at,d.process_code,d.original_filename
                FROM process_working_document w
                JOIN baseline_document d ON d.id=w.baseline_document_id
                JOIN baseline_release b ON b.id=d.baseline_release_id AND b.version_code='V1.0' AND b.locked=TRUE
                WHERE w.working_copy_kind=? AND w.registered_by=? AND w.baseline_document_id=?
                ORDER BY w.version_number DESC,w.id DESC
                """, (rs, rowNum) -> new WorkingCopySummary(rs.getLong("id"), rs.getLong("baseline_document_id"),
                rs.getString("process_code"), rs.getString("original_filename"), rs.getString("working_filename"),
                rs.getString("source_sha256"), rs.getString("working_sha256"), rs.getInt("version_number"),
                rs.getObject("previous_working_document_id") == null ? 0L : rs.getLong("previous_working_document_id"), rs.getLong("working_size_bytes"),
                rs.getString("verification_status"), readSheets(rs.getString("sheet_metadata_json")),
                rs.getTimestamp("created_at").toInstant().toString()), COPY_KIND, username, baselineDocumentId);
    }

    @Transactional
    public Registration register(String username, long baselineDocumentId, String originalFilename, byte[] bytes) {
        requireEnabled();
        requireUser(username);
        if (baselineDocumentId <= 0) throw badRequest("기준 공정도 ID가 필요합니다.");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw badRequest("작업본은 1바이트 이상 10MB 이하여야 합니다.");
        String filename = safeFilename(originalFilename);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw badRequest("현재 작업본 등록은 .xlsx 파일만 지원합니다.");

        Map<String, Object> official = jdbc.query("""
                SELECT d.id,d.process_code,d.original_filename,d.sha256,d.content_bytes
                FROM baseline_document d JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.id=? AND d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                FOR UPDATE
                """, rs -> rs.next() ? Map.of("id", rs.getLong("id"), "processCode", rs.getString("process_code"),
                "filename", rs.getString("original_filename"), "sha256", rs.getString("sha256"),
                "bytes", rs.getBytes("content_bytes")) : null, baselineDocumentId);
        if (official == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "잠금 V1.0 공식 공정도를 찾지 못했습니다.");
        byte[] officialBytes = (byte[]) official.get("bytes");
        String sourceSha = String.valueOf(official.get("sha256"));
        if (officialBytes == null || !sourceSha.equalsIgnoreCase(sha256(officialBytes)))
            throw conflict("공식 원본 BLOB과 기준 SHA가 일치하지 않습니다.");
        String workingSha = sha256(bytes);
        if (sourceSha.equalsIgnoreCase(workingSha))
            throw conflict("공식 원본 바이트는 USER_WORKING_COPY로 등록할 수 없습니다. 별도로 준비한 수정 작업본을 업로드하세요.");

        Map<String, Object> idempotent = jdbc.query("""
                SELECT id,version_number,previous_working_document_id,verification_status,sheet_metadata_json,
                       content_bytes,working_size_bytes
                FROM process_working_document
                WHERE baseline_document_id=? AND source_sha256=? AND conversion_version=?
                  AND working_copy_kind=? AND working_sha256=? AND registered_by=?
                """, rs -> rs.next() ? Map.of("id", rs.getLong("id"), "version", rs.getInt("version_number"),
                "previous", rs.getObject("previous_working_document_id") == null ? 0L : rs.getLong("previous_working_document_id"),
                "verification", rs.getString("verification_status"), "sheets", readSheets(rs.getString("sheet_metadata_json")),
                "content", rs.getBytes("content_bytes"), "size", rs.getLong("working_size_bytes")) : null,
                baselineDocumentId, sourceSha, CONVERSION_VERSION, COPY_KIND, workingSha, username);
        if (idempotent != null) {
            byte[] storedBytes = (byte[]) idempotent.get("content");
            if (storedBytes == null || !workingSha.equalsIgnoreCase(sha256(storedBytes))
                    || ((Number) idempotent.get("size")).longValue() != storedBytes.length)
                throw conflict("기존 등록 작업본의 저장 BLOB·크기·SHA 무결성이 일치하지 않습니다.");
            return new Registration(((Number) idempotent.get("id")).longValue(), true,
                    ((Number) idempotent.get("version")).intValue(), ((Number) idempotent.get("previous")).longValue(),
                    sourceSha, workingSha, bytes.length, String.valueOf(idempotent.get("verification")),
                    castSheets(idempotent.get("sheets")), "동일 사용자가 이미 등록한 동일 SHA의 작업본을 반환했습니다.");
        }

        WorkbookMetadata metadata = validateXlsx(String.valueOf(official.get("processCode")), sourceSha, workingSha, bytes);
        String verification = metadata.sheets().stream().anyMatch(SheetMetadata::profileShaMatch)
                ? "VERIFIED" : "REVIEW_REQUIRED";
        Map<String, Object> last = jdbc.query("""
                SELECT id,version_number FROM process_working_document
                WHERE baseline_document_id=? AND source_sha256=? AND conversion_version=?
                  AND working_copy_kind=? AND registered_by=?
                ORDER BY version_number DESC,id DESC LIMIT 1 FOR UPDATE
                """, rs -> rs.next() ? Map.of("id", rs.getLong("id"), "version", rs.getInt("version_number")) : null,
                baselineDocumentId, sourceSha, CONVERSION_VERSION, COPY_KIND, username);
        long previousId = last == null ? 0L : ((Number) last.get("id")).longValue();
        int version = last == null ? 1 : ((Number) last.get("version")).intValue() + 1;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("artifactKind", COPY_KIND);
        report.put("baselineDocumentId", baselineDocumentId);
        report.put("processCode", official.get("processCode"));
        report.put("officialFilename", official.get("filename"));
        report.put("sourceSha256", sourceSha);
        report.put("workingSha256", workingSha);
        report.put("registeredBy", username);
        report.put("version", version);
        report.put("previousWorkingCopyId", previousId == 0 ? null : previousId);
        report.put("verificationStatus", verification);
        report.put("zipIntegrity", "VERIFIED");
        report.put("poiReopen", "VERIFIED");
        report.put("sheetCount", metadata.sheets().size());
        report.put("sheets", metadata.sheets());
        report.put("officialBaselineModified", false);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id,source_sha256,working_sha256,
                    conversion_version,working_copy_kind,registered_by,version_number,previous_working_document_id,
                    working_size_bytes,verification_status,sheet_metadata_json,conversion_status,source_filename,
                    working_filename,conversion_report_json,content_bytes)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, baselineDocumentId, sourceSha, workingSha, CONVERSION_VERSION, COPY_KIND, username,
                version, previousId == 0 ? null : previousId, bytes.length, verification, writeJson(metadata.sheets()),
                "CREATED_WITH_REVIEW", official.get("filename"), filename, writeJson(report), bytes);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Registration(id == null ? 0 : id, false, version, previousId, sourceSha, workingSha, bytes.length,
                verification, metadata.sheets(), "별도 사용자 업로드로 저장했습니다. 공식 원본은 변경하지 않았습니다.");
    }

    @Transactional(readOnly = true)
    public NumericWorkingCopyService.WorkingCopy getForUser(long id, String username) {
        requireEnabled();
        requireUser(username);
        UserCopyIdentity identity = readIdentity(id);
        if (identity == null || !COPY_KIND.equals(identity.kind()) || !username.equals(identity.owner()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 로그인 사용자의 작업본을 찾지 못했습니다.");
        return withIdentity(numericCopies.getWorkingCopy(id), identity);
    }

    @Transactional(readOnly = true)
    public NumericWorkingCopyService.WorkingCopy getAccessible(long id, String username) {
        if (!enabled) return numericCopies.getWorkingCopy(id);
        UserCopyIdentity identity = readIdentity(id);
        if (identity != null && COPY_KIND.equals(identity.kind())) {
            if (username == null || username.isBlank() || !username.equals(identity.owner()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 로그인 사용자의 작업본을 찾지 못했습니다.");
            return withIdentity(numericCopies.getWorkingCopy(id), identity);
        }
        return numericCopies.getWorkingCopy(id);
    }

    private UserCopyIdentity readIdentity(long id) {
        return jdbc.query("""
                SELECT registered_by,working_copy_kind,verification_status,version_number,
                       previous_working_document_id,working_size_bytes,sheet_metadata_json
                FROM process_working_document WHERE id=?
                """, rs -> rs.next() ? new UserCopyIdentity(rs.getString("registered_by"), rs.getString("working_copy_kind"),
                rs.getString("verification_status"), rs.getInt("version_number"),
                rs.getObject("previous_working_document_id") == null ? null : rs.getLong("previous_working_document_id"),
                rs.getLong("working_size_bytes"), rs.getString("sheet_metadata_json")) : null, id);
    }

    private NumericWorkingCopyService.WorkingCopy withIdentity(NumericWorkingCopyService.WorkingCopy copy,
                                                                UserCopyIdentity identity) {
        return new NumericWorkingCopyService.WorkingCopy(copy.id(), copy.baselineDocumentId(), copy.processCode(),
                copy.officialFilename(), copy.filename(), copy.sourceSha256(), copy.workingSha256(),
                copy.conversionVersion(), copy.conversionStatus(), copy.bytes(), identity.kind(), identity.owner(),
                identity.version(), identity.previousId(), identity.byteSize(), identity.verificationStatus(), identity.sheetMetadata());
    }

    @Transactional
    public StoredOutput saveTestOnlyOutput(String username, long workingDocumentId, String inputWorkingSha,
                                           String expectedOutputSha, String changeType, String filename, byte[] bytes) {
        NumericWorkingCopyService.WorkingCopy copy = getForUser(workingDocumentId, username);
        if (!copy.workingSha256().equalsIgnoreCase(inputWorkingSha)) throw conflict("출력 입력 작업본 SHA가 변경됐습니다.");
        if (bytes == null || bytes.length == 0 || bytes.length > 25L * 1024 * 1024) throw conflict("TEST_ONLY 출력 크기가 허용 범위를 벗어났습니다.");
        if (!java.util.Set.of("ADD", "MODIFY", "DELETE", "PIPE_CHANGE").contains(changeType))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 영속화는 TEST_ONLY ADD, MODIFY, DELETE 및 합성 PIPE_CHANGE만 지원합니다.");
        String outputSha = sha256(bytes);
        if (expectedOutputSha == null || !outputSha.equalsIgnoreCase(expectedOutputSha))
            throw conflict("TEST_ONLY 출력 BLOB과 생성 엔진 SHA-256이 일치하지 않습니다.");
        validateOutputWorkbook(bytes);
        jdbc.update("""
                INSERT INTO process_test_only_output(working_document_id,registered_by,artifact_kind,change_type,
                    baseline_source_sha256,input_working_sha256,output_sha256,output_filename,output_size_bytes,
                    output_content,artifact_status,review_status)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'TEST_ONLY','REVIEW_REQUIRED')
                """, workingDocumentId, username, "TEST_ONLY_OUTPUT", changeType, copy.sourceSha256(),
                inputWorkingSha, outputSha, safeFilename(filename), bytes.length, bytes);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new StoredOutput(id == null ? 0 : id, workingDocumentId, outputSha, bytes.length,
                "TEST_ONLY", "REVIEW_REQUIRED");
    }

    /** Persist only the server-managed numeric-cell FLOW_CHANGE fixture; generic payloads cannot use this path. */
    @Transactional
    public StoredOutput saveFlowChangeSyntheticOutput(String username, long workingDocumentId,
            String inputWorkingSha, String fixtureId, String fixtureSha, String scenarioId,
            String expectedOutputSha, String filename, byte[] bytes) {
        NumericWorkingCopyService.WorkingCopy copy = getForUser(workingDocumentId, username);
        if (!FlowChangeSyntheticFixtureFactory.FIXTURE_ID.equals(fixtureId)
                || !"P-02".equals(copy.processCode())
                || !copy.workingSha256().equalsIgnoreCase(inputWorkingSha)
                || !copy.workingSha256().equalsIgnoreCase(fixtureSha)
                || !sha256(copy.bytes()).equalsIgnoreCase(fixtureSha))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "FLOW_CHANGE output storage is limited to this owner's byte-identical server P-02 fixture.");
        if (bytes == null || bytes.length == 0 || bytes.length > 25L * 1024 * 1024)
            throw conflict("FLOW_CHANGE TEST_ONLY output size is outside the allowed range.");
        String outputSha = sha256(bytes);
        if (expectedOutputSha == null || !outputSha.equalsIgnoreCase(expectedOutputSha))
            throw conflict("FLOW_CHANGE TEST_ONLY output bytes do not match the generated SHA-256.");
        validateOutputWorkbook(bytes);
        validateFlowChangeSyntheticWorkbook(copy.bytes(), bytes, scenarioId);
        String safe = safeFilename(filename);
        jdbc.update("""
                INSERT INTO process_test_only_output(working_document_id,registered_by,artifact_kind,change_type,
                    baseline_source_sha256,input_working_sha256,output_sha256,output_filename,output_size_bytes,
                    output_content,artifact_status,review_status)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'TEST_ONLY','REVIEW_REQUIRED')
                """, workingDocumentId, username, "TEST_ONLY_OUTPUT", "FLOW_CHANGE", copy.sourceSha256(),
                inputWorkingSha, outputSha, safe, bytes.length, bytes);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new StoredOutput(id == null ? 0 : id, workingDocumentId, outputSha, bytes.length,
                "TEST_ONLY", "REVIEW_REQUIRED");
    }

    private void validateFlowChangeSyntheticWorkbook(byte[] fixture, byte[] output, String scenarioId) {
        java.math.BigDecimal expected = switch (scenarioId == null ? "" : scenarioId) {
            case "FLOW-10" -> new java.math.BigDecimal("10");
            case "FLOW-50" -> new java.math.BigDecimal("50");
            default -> throw conflict("FLOW_CHANGE scenario is not an approved non-no-op fixture case.");
        };
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(fixture));
             var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldSheet = before.getSheet(FlowChangeSyntheticFixtureFactory.SHEET_NAME);
            var newSheet = after.getSheet(FlowChangeSyntheticFixtureFactory.SHEET_NAME);
            if (oldSheet == null || newSheet == null || oldSheet.getNumMergedRegions() != 1
                    || newSheet.getNumMergedRegions() != 1
                    || !"B8:C8".equals(oldSheet.getMergedRegion(0).formatAsString())
                    || !oldSheet.getMergedRegion(0).formatAsString().equals(newSheet.getMergedRegion(0).formatAsString()))
                throw conflict("FLOW_CHANGE fixture merge structure is unsupported.");
            for (String address : List.of("B8", "B17")) {
                var ref = new org.apache.poi.ss.util.CellReference(address);
                var oldCell = oldSheet.getRow(ref.getRow()).getCell(ref.getCol());
                var newCell = newSheet.getRow(ref.getRow()).getCell(ref.getCol());
                if (oldCell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
                        || newCell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
                        || java.math.BigDecimal.valueOf(oldCell.getNumericCellValue()).compareTo(new java.math.BigDecimal("20")) != 0
                        || java.math.BigDecimal.valueOf(newCell.getNumericCellValue()).compareTo(expected) != 0
                        || oldCell.getCellStyle().getIndex() != newCell.getCellStyle().getIndex())
                    throw conflict("FLOW_CHANGE workbook labels do not match the exact server fixture scenario.");
            }
            if (oldSheet.getNumMergedRegions() != newSheet.getNumMergedRegions()
                    || oldSheet.getColumnWidth(1) != newSheet.getColumnWidth(1)
                    || oldSheet.getRow(7).getHeight() != newSheet.getRow(7).getHeight()
                    || !java.util.Objects.equals(before.getPrintArea(before.getSheetIndex(oldSheet)),
                            after.getPrintArea(after.getSheetIndex(newSheet))))
                throw conflict("FLOW_CHANGE worksheet layout changed beyond the explicit numeric labels.");
            if (!inspector.inspect(fixture).sheets().stream().map(com.example.processautomation.excel.WorkbookInventory.SheetInventory::drawingObjects).toList()
                    .equals(inspector.inspect(output).sheets().stream().map(com.example.processautomation.excel.WorkbookInventory.SheetInventory::drawingObjects).toList()))
                throw conflict("FLOW_CHANGE output changed DrawingML/Connector objects.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "FLOW_CHANGE server fixture value/layout validation failed; output was not stored.", exception);
        }
    }

    @Transactional(readOnly = true)
    public StoredArtifact downloadTestOnlyOutput(long outputId, String username) {
        requireUser(username);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,working_document_id AS workingDocumentId,registered_by AS registeredBy,change_type AS changeType,
                       output_sha256 AS outputSha256,output_size_bytes AS outputSizeBytes,
                       output_content AS outputContent,output_filename AS outputFilename,
                       artifact_status AS artifactStatus,review_status AS reviewStatus
                FROM process_test_only_output WHERE id=? AND registered_by=? AND artifact_status='TEST_ONLY'
                """, outputId, username);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자의 TEST_ONLY 산출물을 찾지 못했습니다.");
        Map<String, Object> row = rows.get(0);
        byte[] bytes = (byte[]) row.get("outputContent");
        if (bytes == null || !sha256(bytes).equalsIgnoreCase(String.valueOf(row.get("outputSha256"))))
            throw conflict("저장된 TEST_ONLY 산출물 SHA-256이 일치하지 않습니다.");
        return new StoredArtifact(((Number) row.get("id")).longValue(),
                ((Number) row.get("workingDocumentId")).longValue(), String.valueOf(row.get("outputFilename")),
                String.valueOf(row.get("outputSha256")), ((Number) row.get("outputSizeBytes")).longValue(),
                String.valueOf(row.get("artifactStatus")), String.valueOf(row.get("reviewStatus")),
                String.valueOf(row.get("changeType")), bytes);
    }

    @Transactional(readOnly = true)
    public StoredArtifact metadataAndDownload(long outputId, String username) { return downloadTestOnlyOutput(outputId, username); }

    private void validateOutputWorkbook(byte[] bytes) {
        try {
            boolean contentTypes = false;
            boolean workbookXml = false;
            long expanded = 0;
            int entryCount = 0;
            var partNames = new HashSet<String>();
            try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
                var entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    if (++entryCount > 20_000 || !safePackagePath(entry.getName()) || !partNames.add(entry.getName()))
                        throw new IllegalArgumentException("unsafe, duplicate, or excessive OOXML package entries");
                    contentTypes |= "[Content_Types].xml".equals(entry.getName());
                    workbookXml |= "xl/workbook.xml".equals(entry.getName());
                    try (var input = zip.getInputStream(entry)) {
                        byte[] block = new byte[8192];
                        for (int read; (read = input.read(block)) >= 0;) expanded += read;
                    }
                    if (expanded > 150L * 1024 * 1024) throw new IllegalArgumentException("expanded output package exceeds limit");
                }
            }
            if (!contentTypes || !workbookXml) throw new IllegalArgumentException("required OOXML parts are missing");
            inspector.inspect(bytes);
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("output workbook has no sheets");
            }
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "TEST_ONLY Excel ZIP/POI 재열기 검증에 실패해 저장하지 않았습니다.", exception);
        }
    }

    private boolean safePackagePath(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")
                || name.matches("^[A-Za-z]:.*")) return false;
        for (String segment : name.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private WorkbookMetadata validateXlsx(String processCode, String sourceSha, String workingSha, byte[] bytes) {
        try {
            boolean contentTypes = false;
            boolean workbookXml = false;
            long expanded = 0;
            int entryCount = 0;
            var partNames = new HashSet<String>();
            try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
                var entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    if (++entryCount > 20_000 || !safePackagePath(entry.getName()) || !partNames.add(entry.getName()))
                        throw new IllegalArgumentException("unsafe, duplicate, or excessive OOXML package entries");
                    contentTypes |= "[Content_Types].xml".equals(entry.getName());
                    workbookXml |= "xl/workbook.xml".equals(entry.getName());
                    try (var input = zip.getInputStream(entry)) {
                        byte[] block = new byte[8192];
                        for (int read; (read = input.read(block)) >= 0;) expanded += read;
                    }
                    if (expanded > 100L * 1024 * 1024) throw new IllegalArgumentException("expanded XLSX exceeds the package safety limit");
                }
            }
            if (!contentTypes || !workbookXml) throw new IllegalArgumentException("required OOXML package parts are missing");
            var inventory = inspector.inspect(bytes);
            List<SheetMetadata> sheets = new ArrayList<>();
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                if (workbook.getNumberOfSheets() == 0 || workbook.getNumberOfSheets() != inventory.sheets().size())
                    throw new IllegalArgumentException("worksheet count is invalid");
                for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                    var sheet = workbook.getSheetAt(index);
                    var profile = profiles.findFor(processCode, sheet.getSheetName(), sourceSha).orElse(null);
                    boolean workingShaMatch = profile != null && profile.matchesWorkingCopySha(workingSha);
                    boolean profileHashMatch = profile != null && profile.enabledForGenericAdd() && workingShaMatch;
                    String status = profileHashMatch ? "VERIFIED" : profile == null ? "UNSUPPORTED" : profile.status();
                    if ("VERIFIED".equals(status) && !profileHashMatch) status = "REVIEW_REQUIRED";
                    sheets.add(new SheetMetadata(sheet.getSheetName(), index, workbook.getSheetVisibility(index).name(),
                            profile == null ? null : profile.profileId(), profile == null ? null : profile.profileVersion(),
                            status, profile == null ? null : profile.evidenceLevel(), profileHashMatch, workingShaMatch,
                            profile == null ? List.of() : profile.fields().keySet().stream().toList(),
                            profileHashMatch ? profile.fields().keySet().stream().toList() : List.of(),
                            profile == null ? "PROFILE_MISSING_NO_FALLBACK_ALLOWED" : profileHashMatch ? null
                                    : "PROFILE_OR_WORKING_SHA_NOT_VERIFIED"));
                }
            }
            return new WorkbookMetadata(List.copyOf(sheets));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "XLSX ZIP/POI/시트 검증에 실패했습니다: " + exception.getClass().getSimpleName(), exception);
        }
    }

    private List<SheetMetadata> readSheets(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 시트 metadata를 읽지 못했습니다.", exception); }
    }

    private List<SheetMetadata> castSheets(Object value) {
        if (value instanceof List<?> list) return list.stream().filter(SheetMetadata.class::isInstance)
                .map(SheetMetadata.class::cast).toList();
        return List.of();
    }

    private String safeFilename(String original) {
        if (original == null || original.isBlank()) throw badRequest("업로드 파일명이 필요합니다.");
        String leaf = original.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).trim();
        if (leaf.isBlank() || leaf.length() > 255 || leaf.codePoints().anyMatch(Character::isISOControl))
            throw badRequest("파일명이 올바르지 않습니다.");
        return leaf;
    }

    private void requireUser(String username) {
        if (username == null || username.isBlank() || username.length() > 128)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize working-copy metadata", exception); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record SheetMetadata(String sheetName, int sheetIndex, String visibility, String profileId,
                                String profileVersion, String profileStatus, String profileEvidenceLevel,
                                boolean profileShaMatch, boolean workingCopyShaMatch, List<String> profileFields,
                                List<String> availableFields, String reason) {
        public SheetMetadata {
            profileFields = profileFields == null ? List.of() : List.copyOf(profileFields);
            availableFields = availableFields == null ? List.of() : List.copyOf(availableFields);
        }
    }
    public record WorkbookMetadata(List<SheetMetadata> sheets) { public WorkbookMetadata { sheets = List.copyOf(sheets); } }
    public record WorkingCopySummary(long workingDocumentId, long baselineDocumentId, String processCode,
                                     String officialFilename, String filename, String sourceSha256, String workingSha256,
                                     int version, long previousWorkingCopyId, long byteSize, String verificationStatus,
                                     List<SheetMetadata> sheets, String registeredAt) { }
    public record Registration(long workingDocumentId, boolean idempotent, int version, long previousWorkingCopyId,
                              String sourceSha256, String workingSha256, long byteSize, String verificationStatus,
                              List<SheetMetadata> sheets, String message) { }
    public record StoredOutput(long outputId, long workingDocumentId, String outputSha256, long byteSize,
                               String artifactKind, String reviewStatus) { }
    public record StoredArtifact(long outputId, long workingDocumentId, String filename, String outputSha256,
                                 long byteSize, String artifactStatus, String reviewStatus, String changeType, byte[] bytes) { }
    private record UserCopyIdentity(String owner, String kind, String verificationStatus, int version,
                                    Long previousId, long byteSize, String sheetMetadata) { }
}
