package com.example.processautomation.material;

import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.example.processautomation.excel.OfficialTextNumericConverter;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates immutable, separately stored numeric working copies from locked V1.0 process documents. */
@Service
public class NumericWorkingCopyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NumericWorkingCopyService.class);
    public static final String CONVERSION_VERSION = "PW01-NUMERIC-WORKING-V1";
    public static final String NUMERIC_SHEET = "숫자형 작업값";
    private static final String REQUIRED_PROCESS = "PW-01";
    private static final String WORKING_FILE = "PW-01_NUMERIC_WORKING_V1.xlsx";
    private static final String UPLOAD_CONVERSION_VERSION = "NUMERIC-WORKING-UPLOAD-V1";
    private static final String OFFICIAL_NUMERIC_CANDIDATE_VERSION = "PW01-OFFICIAL-NUMERIC-V1";
    private static final String OFFICIAL_NUMERIC_CANDIDATE_FILE = "PW-01_OFFICIAL_NUMERIC_CANDIDATE_V1.xlsx";
    private static final List<String> PW01_DELTA_CELLS = List.of("BB179", "AQ164", "AH51", "AH97", "AK147", "AO41");
    private static final java.util.regex.Pattern PROCESS_CODE_PREFIX =
            java.util.regex.Pattern.compile("^((?:PW|PU|P)-\\d{2}(?:-\\d{2})?)(?:\\s|_|\\.|\\(|$)");
    private static final java.util.Set<String> FLOW_UNITS = java.util.Set.of(
            "톤/일", "ton/day", "t/day", "kg/일", "kg/day", "㎥/일", "m³/day", "m3/day", "L/일", "L/day");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OoxmlPackageInspector inspector;
    private final Step7DrawingEvidenceExtractor extractor;
    private final MaterialBalanceExcelExportService workbookExport;
    private final OfficialTextNumericConverter officialTextNumericConverter;

    public NumericWorkingCopyService(JdbcTemplate jdbc, ObjectMapper mapper, OoxmlPackageInspector inspector,
                                     Step7DrawingEvidenceExtractor extractor,
                                     MaterialBalanceExcelExportService workbookExport,
                                     OfficialTextNumericConverter officialTextNumericConverter) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.inspector = inspector;
        this.extractor = extractor;
        this.workbookExport = workbookExport;
        this.officialTextNumericConverter = officialTextNumericConverter;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProcessDocuments() {
        return jdbc.queryForList("""
                SELECT d.id AS baselineDocumentId, d.process_code AS processCode,
                       d.original_filename AS officialFilename, d.sha256 AS officialSha256,
                       d.byte_size AS byteSize, w.id AS workingDocumentId,
                       w.working_filename AS workingFilename, w.working_sha256 AS workingSha256,
                       w.conversion_version AS workingConversionVersion,
                       w.conversion_status AS conversionStatus, w.conversion_report_json AS conversionReport
                FROM baseline_document d
                JOIN baseline_release b ON b.id = d.baseline_release_id AND b.version_code = 'V1.0' AND b.locked = TRUE
                LEFT JOIN process_working_document w ON w.id = (
                    SELECT MAX(w2.id) FROM process_working_document w2
                    WHERE w2.baseline_document_id = d.id
                )
                WHERE d.document_role = 'PROCESS'
                ORDER BY d.process_code, d.original_filename
                """);
    }

    @Transactional
    public Map<String, Object> createPw01Copy(long baselineDocumentId) {
        List<Map<String, Object>> sources = jdbc.queryForList("""
                SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sourceSha256, d.content_bytes AS contentBytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id = d.baseline_release_id
                WHERE d.id = ? AND d.document_role = 'PROCESS'
                  AND b.version_code = 'V1.0' AND b.locked = TRUE
                """, baselineDocumentId);
        if (sources.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "잠금 V1.0 공정도 문서를 찾지 못했습니다.");
        Map<String, Object> source = sources.get(0);
        if (!REQUIRED_PROCESS.equals(source.get("processCode"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 숫자형 작업본 생성은 PW-01만 지원합니다.");
        }
        byte[] officialBytes = (byte[]) source.get("contentBytes");
        String sourceHash = String.valueOf(source.get("sourceSha256"));
        if (officialBytes == null || !sourceHash.equalsIgnoreCase(sha256(officialBytes))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 V1.0 원본 BLOB과 SHA-256이 일치하지 않아 작업본을 생성하지 않았습니다.");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id AS workingDocumentId, working_filename AS workingFilename,
                       working_sha256 AS workingSha256, conversion_status AS conversionStatus,
                       conversion_report_json AS conversionReport
                FROM process_working_document
                WHERE baseline_document_id = ? AND source_sha256 = ? AND conversion_version = ?
                """, baselineDocumentId, sourceHash, CONVERSION_VERSION);
        if (!existing.isEmpty()) return existing.get(0);

        List<List<?>> numericRows = new ArrayList<>();
        List<List<?>> reviewRows = new ArrayList<>();
        for (var sheet : inspector.inspect(officialBytes).sheets()) {
            var evidence = extractor.extract(officialBytes, sheet.sheetName());
            for (var flow : evidence.flowLabels()) {
                String unit = normalizeUnit(flow.unit());
                if (flow.quantity() != null && FLOW_UNITS.contains(unit)) {
                    numericRows.add(List.of(sheet.sheetName(), flow.cellAddress(), flow.materialName(),
                            flow.quantity(), flow.unit(), flow.rawCellText(),
                            "PARSED_EXPLICIT_QUANTITY_AND_UNIT", "SOURCE_TEXT_ONLY; ROUTE_NOT_VERIFIED"));
                } else if (flow.quantity() != null && isQuantityLike(flow.unit(), flow.materialName())) {
                    reviewRows.add(List.of(sheet.sheetName(), flow.cellAddress(), flow.rawCellText(),
                            flow.quantity().toPlainString(), flow.unit(), "REVIEW_REQUIRED: unit/measure semantics are not whitelisted"));
                }
            }
            for (var cell : evidence.numericCells()) {
                reviewRows.add(List.of(sheet.sheetName(), cell.cellAddress(), cell.displayValue(),
                        cell.numericValue() == null ? "" : cell.numericValue(), "",
                        "REVIEW_REQUIRED: pre-existing numeric cell has no verified unit metadata"));
            }
        }
        List<List<?>> table = new ArrayList<>();
        table.addAll(numericRows);
        for (List<?> row : reviewRows) {
            table.add(List.of(row.get(0), row.get(1), "", row.get(3) == null ? "" : row.get(3).toString(), row.get(4), row.get(2), row.get(5), ""));
        }
        byte[] workingBytes = workbookExport.appendTableWorksheet(officialBytes, NUMERIC_SHEET,
                "TEST_ONLY — PW-01 명시 단위 기반 숫자형 작업값",
                "V1.0 원본 불변. 기존 도면 셀·병합·DrawingML은 그대로 두고, 명시된 유량 문자열만 별도 시트의 숫자 셀로 구조화했습니다. 단위는 별도 열이며 이 목록은 연결/경로 승인 자료가 아닙니다.",
                List.of("시트", "원본 셀", "물질/설명", "숫자 값", "단위", "원본 표시문", "처리 상태", "연결 검증 상태"), table);
        verifyCopyIntegrity(officialBytes, workingBytes, numericRows.size());
        String status = reviewRows.isEmpty() ? "CREATED" : "CREATED_WITH_REVIEW";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("conversionVersion", CONVERSION_VERSION);
        report.put("conversionStatus", status);
        report.put("sourceSha256", sourceHash);
        report.put("workingSha256", sha256(workingBytes));
        report.put("sourceWorkbookCellEdits", 0);
        report.put("convertedNumericRows", numericRows.size());
        report.put("reviewRequiredRows", reviewRows.size());
        report.put("newWorksheet", NUMERIC_SHEET);
        report.put("unitHandling", "Units are stored as separate text metadata; no unit conversion is performed.");
        report.put("drawingObjectsPreserved", true);
        report.put("routeEvidenceVerified", false);
        String reportJson = writeJson(report);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id, source_sha256, working_sha256,
                    conversion_version, conversion_status, source_filename, working_filename,
                    conversion_report_json, content_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, baselineDocumentId, sourceHash, sha256(workingBytes), CONVERSION_VERSION, status,
                String.valueOf(source.get("filename")), WORKING_FILE, reportJson, workingBytes);
        Long workingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> response = new LinkedHashMap<>(report);
        response.put("workingDocumentId", workingId);
        response.put("workingFilename", WORKING_FILE);
        response.put("workingSha256", sha256(workingBytes));
        response.put("conversionReport", report);
        return response;
    }

    @Transactional
    public Map<String, Object> createOfficialNumericCandidate(long baselineDocumentId) {
        List<Map<String, Object>> sources = jdbc.queryForList("""
                SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sourceSha256, d.content_bytes AS contentBytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id = d.baseline_release_id
                WHERE d.id = ? AND d.document_role = 'PROCESS'
                  AND b.version_code = 'V1.0' AND b.locked = TRUE
                """, baselineDocumentId);
        if (sources.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "잠금 V1.0 공정도 문서를 찾지 못했습니다.");
        Map<String, Object> source = sources.get(0);
        if (!REQUIRED_PROCESS.equals(source.get("processCode"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 숫자 변환 후보는 PW-01만 지원합니다.");
        }
        byte[] officialBytes = (byte[]) source.get("contentBytes");
        String sourceHash = String.valueOf(source.get("sourceSha256"));
        if (officialBytes == null || !sourceHash.equalsIgnoreCase(sha256(officialBytes))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 V1.0 원본 BLOB과 SHA-256이 일치하지 않아 후보를 생성하지 않았습니다.");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id AS workingDocumentId, working_filename AS workingFilename,
                       working_sha256 AS workingSha256, conversion_status AS conversionStatus,
                       conversion_report_json AS conversionReport
                FROM process_working_document
                WHERE baseline_document_id = ? AND source_sha256 = ? AND conversion_version = ?
                """, baselineDocumentId, sourceHash, OFFICIAL_NUMERIC_CANDIDATE_VERSION);
        if (!existing.isEmpty()) {
            Map<String, Object> prior = new LinkedHashMap<>(existing.get(0));
            Map<String, Object> report = readJsonMap(String.valueOf(prior.get("conversionReport")));
            report.putAll(classificationSummary(report));
            prior.putAll(report);
            prior.put("conversionReport", report);
            return prior;
        }

        final OfficialTextNumericConverter.ConversionResult conversion;
        try {
            conversion = officialTextNumericConverter.convert(officialBytes);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to convert official numeric candidate for {}: {}", source.get("filename"), exception.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "공식 문자열 수량 표시 또는 OOXML 구조 검증에 실패해 후보를 저장하지 않았습니다.", exception);
        }
        List<Map<String, Object>> deltaReview = compareKnownPw01Cells(baselineDocumentId, officialBytes, conversion.assessments());
        String status = "CREATED_WITH_REVIEW";
        String candidateHash = sha256(conversion.workbookBytes());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("conversionVersion", OFFICIAL_NUMERIC_CANDIDATE_VERSION);
        report.put("conversionStatus", status);
        report.put("candidateDisposition", "SEPARATE_OFFICIAL_TEXT_NUMERIC_CANDIDATE; NOT A REPLACEMENT FOR V1.0 OR UPLOAD");
        report.put("sourceSha256", sourceHash);
        report.put("workingSha256", candidateHash);
        report.put("convertedCellCount", conversion.cells().size());
        report.put("convertedCells", conversion.cells().stream().map(cell -> Map.of(
                "sheet", cell.sheetName(), "cell", cell.cellAddress(), "sourceText", cell.sourceText(),
                "numericValue", cell.quantity(), "unit", cell.unit(), "numberFormat", cell.numberFormat())).toList());
        report.put("cellAssessments", conversion.assessments().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sheet", item.sheetName()); row.put("cell", item.cellAddress());
            row.put("sourceText", item.sourceText()); row.put("status", item.status());
            row.put("extractedQuantityMetadata", item.extractedQuantity()); row.put("unit", item.unit()); row.put("reason", item.reason());
            return row;
        }).toList());
        report.putAll(classificationSummary(report));
        report.put("knownDiscrepancyCells", deltaReview);
        report.put("structureValidationStatus", "VERIFIED");
        report.put("structureValidation", Map.of(
                "sheetOrderPreserved", true,
                "mergeRangesPreserved", true,
                "drawingObjectsAndAnchorsPreserved", true,
                "rawDrawingPartsPreserved", true,
                "onlyStylesAndEligibleWorksheetPartsChanged", true,
                "changedParts", conversion.changedOoxmlParts()));
        report.put("periodVerified", false);
        report.put("discrepancyCauseProven", false);
        report.put("calculationStatus", "CALCULATION_UNAVAILABLE");
        report.put("officialBaselineModified", false);
        report.put("uploadedNumericWorkingCopyModified", false);
        String reportJson = writeJson(report);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id, source_sha256, working_sha256,
                    conversion_version, conversion_status, source_filename, working_filename,
                    conversion_report_json, content_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, baselineDocumentId, sourceHash, candidateHash, OFFICIAL_NUMERIC_CANDIDATE_VERSION,
                status, String.valueOf(source.get("filename")), OFFICIAL_NUMERIC_CANDIDATE_FILE,
                reportJson, conversion.workbookBytes());
        Long workingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> response = new LinkedHashMap<>(report);
        response.put("workingDocumentId", workingId);
        response.put("workingFilename", OFFICIAL_NUMERIC_CANDIDATE_FILE);
        response.put("workingSha256", candidateHash);
        response.put("conversionReport", report);
        return response;
    }

    private Map<String, Object> classificationSummary(Map<String, Object> report) {
        Object raw = report.get("cellAssessments");
        if (!(raw instanceof List<?> assessments)) {
            throw new IllegalStateException("Candidate audit is missing its cell assessments");
        }
        Map<String, Long> counts = new java.util.TreeMap<>();
        java.util.Set<String> uniqueCells = new java.util.HashSet<>();
        for (Object row : assessments) {
            if (!(row instanceof Map<?, ?> values)) throw new IllegalStateException("Malformed cell assessment in candidate audit");
            String status = String.valueOf(values.get("status"));
            String key = values.get("sheet") + "!" + values.get("cell");
            if (!uniqueCells.add(key)) throw new IllegalStateException("Cell classification is not disjoint at " + key);
            counts.merge(status, 1L, Long::sum);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long converted = counts.getOrDefault("CONVERTED", 0L);
        long preservedOrReview = total - converted;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classificationCounts", Map.copyOf(counts));
        result.put("classifiedCellCount", total);
        result.put("uniqueClassifiedCellCount", (long) uniqueCells.size());
        result.put("compoundOrUnsupportedCells", preservedOrReview);
        result.put("classificationCategoriesDisjoint", total == uniqueCells.size());
        return result;
    }

    private List<Map<String, Object>> compareKnownPw01Cells(long baselineDocumentId, byte[] officialBytes,
                                                              List<OfficialTextNumericConverter.CellAssessment> assessments) {
        Map<String, OfficialTextNumericConverter.CellAssessment> assessmentByAddress = new LinkedHashMap<>();
        assessments.stream().filter(cell -> "PW-01-05".equals(cell.sheetName()))
                .forEach(cell -> assessmentByAddress.put(cell.cellAddress(), cell));
        List<Map<String, Object>> uploads = jdbc.queryForList("""
                SELECT content_bytes AS contentBytes FROM process_working_document
                WHERE baseline_document_id = ? AND conversion_version = ?
                ORDER BY id DESC LIMIT 1
                """, baselineDocumentId, UPLOAD_CONVERSION_VERSION);
        byte[] upload = uploads.isEmpty() ? null : (byte[]) uploads.get(0).get("contentBytes");
        List<Map<String, Object>> result = new ArrayList<>();
        try (var officialWorkbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(officialBytes));
             var uploadWorkbook = upload == null ? null : org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(upload))) {
            var officialSheet = officialWorkbook.getSheet("PW-01-05");
            var uploadSheet = uploadWorkbook == null ? null : uploadWorkbook.getSheet("PW-01-05");
            for (String address : PW01_DELTA_CELLS) {
                var officialCell = officialSheet == null ? null : officialSheet.getRow(
                        new org.apache.poi.ss.util.CellReference(address).getRow()) == null ? null
                        : officialSheet.getRow(new org.apache.poi.ss.util.CellReference(address).getRow())
                                .getCell(new org.apache.poi.ss.util.CellReference(address).getCol());
                var uploadedCell = uploadSheet == null ? null : uploadSheet.getRow(
                        new org.apache.poi.ss.util.CellReference(address).getRow()) == null ? null
                        : uploadSheet.getRow(new org.apache.poi.ss.util.CellReference(address).getRow())
                                .getCell(new org.apache.poi.ss.util.CellReference(address).getCol());
                var assessment = assessmentByAddress.get(address);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cell", address);
                row.put("officialSourceText", officialCell == null ? null : new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT).formatCellValue(officialCell));
                row.put("officialTextQuantityMetadata", assessment == null ? null : assessment.extractedQuantity());
                row.put("officialCellTreatment", assessment == null ? "PRESERVED_UNCLASSIFIED" : assessment.status());
                row.put("uploadedNumericValue", uploadedCell == null || uploadedCell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
                        ? null : BigDecimal.valueOf(uploadedCell.getNumericCellValue()));
                BigDecimal officialValue = assessment == null ? null : assessment.extractedQuantity();
                BigDecimal uploadedValue = (BigDecimal) row.get("uploadedNumericValue");
                row.put("uploadMinusOfficialTextMetadata", officialValue == null || uploadedValue == null ? null : uploadedValue.subtract(officialValue));
                row.put("cause", "UNDETERMINED; no verified period/measurement-basis evidence");
                row.put("status", "REVIEW_REQUIRED");
                result.add(row);
            }
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "기존 숫자형 업로드와 공식 후보의 지정 셀 비교에 실패했습니다.", exception);
        }
        return List.copyOf(result);
    }

    @Transactional
    public Map<String, Object> registerNumericCopy(long baselineDocumentId, String filename, byte[] workingBytes) {
        if (workingBytes == null || workingBytes.length == 0 || workingBytes.length > 10L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "숫자형 Excel 파일 크기는 1바이트 이상 10MB 이하여야 합니다.");
        }
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "등록 파일은 .xlsx 형식이어야 합니다.");
        }
        var matcher = PROCESS_CODE_PREFIX.matcher(filename);
        if (!matcher.find()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일명에서 정확한 공정번호를 확인할 수 없습니다.");
        List<Map<String, Object>> sources = jdbc.queryForList("""
                SELECT d.id AS documentId, d.process_code AS processCode, d.original_filename AS filename,
                       d.sha256 AS sourceSha256, d.content_bytes AS contentBytes
                FROM baseline_document d
                JOIN baseline_release b ON b.id=d.baseline_release_id
                WHERE d.id=? AND d.document_role='PROCESS' AND b.version_code='V1.0' AND b.locked=TRUE
                """, baselineDocumentId);
        if (sources.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "잠금 V1.0 공정도 문서를 찾지 못했습니다.");
        Map<String, Object> source = sources.get(0);
        String processCode = String.valueOf(source.get("processCode"));
        if (!processCode.equals(matcher.group(1))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "파일 공정번호 " + matcher.group(1)
                    + "와 선택한 공식 문서 " + processCode + "가 일치하지 않습니다.");
        }
        byte[] officialBytes = (byte[]) source.get("contentBytes");
        String sourceHash = String.valueOf(source.get("sourceSha256"));
        if (officialBytes == null || !sourceHash.equalsIgnoreCase(sha256(officialBytes))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "공식 V1.0 원본 BLOB과 SHA-256이 일치하지 않아 등록을 중단했습니다.");
        }
        String workingHash = sha256(workingBytes);
        List<Map<String, Object>> prior = jdbc.queryForList("""
                SELECT id AS workingDocumentId, working_filename AS workingFilename,
                       working_sha256 AS workingSha256, conversion_status AS conversionStatus,
                       conversion_report_json AS conversionReport
                FROM process_working_document
                WHERE baseline_document_id=? AND source_sha256=? AND conversion_version=?
                """, baselineDocumentId, sourceHash, UPLOAD_CONVERSION_VERSION);
        if (!prior.isEmpty()) {
            Map<String, Object> row = prior.get(0);
            if (!workingHash.equalsIgnoreCase(String.valueOf(row.get("workingSha256")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "이 공식 문서에는 다른 숫자형 업로드가 이미 등록되어 있습니다. 기존 작업본을 보존하고 덮어쓰지 않았습니다.");
            }
            Map<String, Object> result = new LinkedHashMap<>(row);
            result.put("workingSha256", workingHash);
            result.put("idempotent", true);
            return result;
        }
        final com.example.processautomation.excel.WorkbookInventory officialInventory;
        final com.example.processautomation.excel.WorkbookInventory workingInventory;
        final Map<String, Object> structure = new LinkedHashMap<>();
        try {
            officialInventory = inspector.inspect(officialBytes);
            workingInventory = inspector.inspect(workingBytes);
            structure.putAll(compareWorkbookStructure(officialBytes, workingBytes, officialInventory, workingInventory));
            try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(workingBytes))) {
                if (workbook.getNumberOfSheets() != workingInventory.sheets().size()) {
                    throw new IllegalArgumentException("Apache POI worksheet count differs from the OOXML inventory");
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Rejected numeric working copy {} for process {} due to {}: {}", filename, processCode,
                    exception.getClass().getSimpleName(), exception.getMessage(), exception);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "업로드 파일을 유효한 XLSX로 열거나 구조를 비교할 수 없습니다. 상세유형: "
                            + exception.getClass().getSimpleName(), exception);
        }
        boolean needsReview = Boolean.TRUE.equals(structure.get("structureReviewRequired"));
        String status = needsReview ? "CREATED_WITH_REVIEW" : "CREATED";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("conversionVersion", UPLOAD_CONVERSION_VERSION);
        report.put("conversionStatus", status);
        report.put("sourceSha256", sourceHash);
        report.put("workingSha256", workingHash);
        report.put("workingFileSha256", workingHash);
        report.put("sourceWorkbookCellEdits", "USER_SUPPLIED_NUMERIC_WORKING_COPY; NOT A V1.0 REPLACEMENT");
        report.put("fileNameMatchedProcessCode", matcher.group(1));
        report.put("officialDocumentId", baselineDocumentId);
        report.put("officialFilename", source.get("filename"));
        report.put("structure", structure);
        report.put("officialBaselineModified", false);
        report.put("approvalOrRelationCreated", false);
        String reportJson = writeJson(report);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id, source_sha256, working_sha256,
                    conversion_version, conversion_status, source_filename, working_filename,
                    conversion_report_json, content_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, baselineDocumentId, sourceHash, workingHash, UPLOAD_CONVERSION_VERSION, status,
                String.valueOf(source.get("filename")), filename, reportJson, workingBytes);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> response = new LinkedHashMap<>(report);
        response.put("workingDocumentId", id);
        response.put("workingFilename", filename);
        response.put("workingSha256", workingHash);
        response.put("idempotent", false);
        return response;
    }

    private Map<String, Object> compareWorkbookStructure(byte[] officialBytes, byte[] workingBytes,
            com.example.processautomation.excel.WorkbookInventory official,
            com.example.processautomation.excel.WorkbookInventory working) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> officialNames = official.sheets().stream().map(s -> s.sheetName()).toList();
        List<String> workingNames = working.sheets().stream().map(s -> s.sheetName()).toList();
        result.put("officialSheetNames", officialNames);
        result.put("workingSheetNames", workingNames);
        result.put("sheetNamesAndOrderMatch", officialNames.equals(workingNames));
        Map<String, List<String>> officialMerges = readMergeRanges(officialBytes);
        Map<String, List<String>> workingMerges = readMergeRanges(workingBytes);
        List<Map<String, Object>> mergeDifferences = new ArrayList<>();
        for (String name : officialNames) {
            List<String> before = officialMerges.getOrDefault(name, List.of());
            List<String> after = workingMerges.getOrDefault(name, List.of());
            if (!before.equals(after)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("sheetName", name); diff.put("officialMergeCount", before.size());
                diff.put("workingMergeCount", after.size()); diff.put("addedRanges", after.stream().filter(x -> !before.contains(x)).toList());
                diff.put("removedRanges", before.stream().filter(x -> !after.contains(x)).toList());
                mergeDifferences.add(diff);
            }
        }
        Map<String, com.example.processautomation.excel.WorkbookInventory.SheetInventory> officialSheets = new LinkedHashMap<>();
        official.sheets().forEach(s -> officialSheets.put(s.sheetName(), s));
        Map<String, com.example.processautomation.excel.WorkbookInventory.SheetInventory> workingSheets = new LinkedHashMap<>();
        working.sheets().forEach(s -> workingSheets.put(s.sheetName(), s));
        List<Map<String, Object>> drawingDifferences = new ArrayList<>();
        for (String name : officialNames) {
            var before = officialSheets.get(name); var after = workingSheets.get(name);
            if (before == null || after == null || !Objects.equals(before.drawingObjects(), after.drawingObjects())) {
                Map<String, Object> diff = new LinkedHashMap<>(); diff.put("sheetName", name);
                diff.put("officialDrawingPart", before == null ? null : before.drawingPartName());
                diff.put("workingDrawingPart", after == null ? null : after.drawingPartName());
                diff.put("officialObjectCount", before == null ? 0 : before.drawingObjects().size());
                diff.put("workingObjectCount", after == null ? 0 : after.drawingObjects().size());
                drawingDifferences.add(diff);
            }
        }
        Map<String, Object> cells = inspectNumericCells(workingBytes);
        result.put("officialMergeCounts", officialMerges.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().size(), (a,b) -> a, LinkedHashMap::new)));
        result.put("workingMergeCounts", workingMerges.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().size(), (a,b) -> a, LinkedHashMap::new)));
        result.put("mergeDifferences", mergeDifferences);
        result.put("drawingObjectDifferences", drawingDifferences);
        result.put("officialDrawingObjectCount", official.shapeCount() + official.connectorCount() + official.pictureCount() + official.otherObjectCount());
        result.put("workingDrawingObjectCount", working.shapeCount() + working.connectorCount() + working.pictureCount() + working.otherObjectCount());
        result.put("officialShapeCount", official.shapeCount()); result.put("workingShapeCount", working.shapeCount());
        result.put("officialConnectorCount", official.connectorCount()); result.put("workingConnectorCount", working.connectorCount());
        result.put("officialPictureCount", official.pictureCount()); result.put("workingPictureCount", working.pictureCount());
        result.put("numericCells", cells.get("numericCells")); result.put("numericCellsWithUnitNumberFormat", cells.get("numericCellsWithUnitNumberFormat"));
        result.put("numericCellSamples", cells.get("samples"));
        result.put("structureReviewRequired", !officialNames.equals(workingNames) || !mergeDifferences.isEmpty() || !drawingDifferences.isEmpty()
                || official.shapeCount() != working.shapeCount() || official.connectorCount() != working.connectorCount()
                || official.pictureCount() != working.pictureCount());
        return result;
    }

    private Map<String, List<String>> readMergeRanges(byte[] bytes) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            for (var sheet : workbook) {
                List<String> ranges = new ArrayList<>();
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) ranges.add(sheet.getMergedRegion(i).formatAsString());
                result.put(sheet.getSheetName(), List.copyOf(ranges));
            }
        }
        return result;
    }

    private Map<String, Object> inspectNumericCells(byte[] bytes) throws Exception {
        int numeric = 0, numericWithUnitFormat = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC) continue;
                numeric++;
                String numberFormat = cell.getCellStyle().getDataFormatString();
                String lower = numberFormat.toLowerCase(Locale.ROOT);
                boolean unitFormat = lower.contains("톤") || lower.contains("ton") || lower.contains("kg")
                        || lower.contains("㎥") || lower.contains("m³") || lower.contains("m3") || lower.contains("%")
                        || lower.contains("/일") || lower.contains("/day");
                if (unitFormat) {
                    numericWithUnitFormat++;
                    if (samples.size() < 30) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("sheetName", sheet.getSheetName()); sample.put("cellAddress", cell.getAddress().formatAsString());
                        sample.put("numericValue", cell.getNumericCellValue()); sample.put("displayedValue", formatter.formatCellValue(cell));
                        sample.put("numberFormat", numberFormat); samples.add(sample);
                    }
                }
            }
        }
        return Map.of("numericCells", numeric, "numericCellsWithUnitNumberFormat", numericWithUnitFormat, "samples", samples);
    }

    @Transactional(readOnly = true)
    public WorkingCopy getWorkingCopy(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT w.id, w.baseline_document_id AS baselineDocumentId, w.source_sha256 AS sourceSha256,
                       w.working_sha256 AS workingSha256, w.working_filename AS filename,
                       w.conversion_version AS conversionVersion,
                       w.conversion_status AS conversionStatus, w.conversion_report_json AS conversionReport,
                       w.content_bytes AS contentBytes, d.process_code AS processCode,
                       d.original_filename AS officialFilename
                FROM process_working_document w JOIN baseline_document d ON d.id = w.baseline_document_id
                WHERE w.id = ?
                """, id);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "숫자형 작업본을 찾지 못했습니다.");
        Map<String, Object> row = rows.get(0);
        byte[] content = (byte[]) row.get("contentBytes");
        if (content == null || !String.valueOf(row.get("workingSha256")).equalsIgnoreCase(sha256(content))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "저장된 작업본의 SHA-256이 일치하지 않습니다.");
        }
        return new WorkingCopy(((Number) row.get("id")).longValue(),
                ((Number) row.get("baselineDocumentId")).longValue(), String.valueOf(row.get("processCode")),
                String.valueOf(row.get("officialFilename")), String.valueOf(row.get("filename")),
                String.valueOf(row.get("sourceSha256")), String.valueOf(row.get("workingSha256")),
                String.valueOf(row.get("conversionVersion")), String.valueOf(row.get("conversionStatus")), content);
    }

    private void verifyCopyIntegrity(byte[] source, byte[] output, int numericRowCount) {
        var sourceInventory = inspector.inspect(source);
        var outputInventory = inspector.inspect(output);
        if (outputInventory.sheets().size() != sourceInventory.sheets().size() + 1
                || outputInventory.shapeCount() != sourceInventory.shapeCount()
                || outputInventory.connectorCount() != sourceInventory.connectorCount()
                || outputInventory.pictureCount() != sourceInventory.pictureCount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PW-01 작업본 생성 뒤 시트 또는 DrawingML inventory 검증에 실패했습니다.");
        }
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet(NUMERIC_SHEET);
            if (sheet == null || numericRowCount == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "명시 단위 숫자형 행이 없어 작업본 등록을 중단했습니다.");
            }
            for (var original : sourceInventory.sheets()) {
                var generated = outputInventory.sheets().stream().filter(item -> original.sheetName().equals(item.sheetName()))
                        .findFirst().orElseThrow();
                if (!original.drawingPartName().equals(generated.drawingPartName())
                        || original.drawingObjects().size() != generated.drawingObjects().size()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "공식 원본 DrawingML이 작업본에서 보존되지 않았습니다: " + original.sheetName());
                }
            }
            int numericCells = 0;
            for (var row : sheet) for (var cell : row) if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) numericCells++;
            if (numericCells != numericRowCount) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "숫자형 변환 행 수가 예상과 다릅니다.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PW-01 숫자형 작업본을 다시 열지 못했습니다.", exception);
        }
    }

    private boolean isQuantityLike(String unit, String material) {
        String normalized = normalizeUnit(unit);
        String text = (material == null ? "" : material) + " " + (unit == null ? "" : unit);
        return normalized.contains("㎥") || normalized.contains("m³") || normalized.contains("m3")
                || normalized.contains("%") || text.contains("폐수") || text.contains("폐기물") || text.contains("톤");
    }

    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> readJsonMap(String value) {
        try { return mapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to deserialize working copy audit", exception); }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize working copy audit", exception); }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record WorkingCopy(long id, long baselineDocumentId, String processCode, String officialFilename,
                              String filename, String sourceSha256, String workingSha256,
                              String conversionVersion, String conversionStatus, byte[] bytes,
                              String workingCopyKind, String registeredBy, int versionNumber,
                              Long previousWorkingDocumentId, long byteSize, String verificationStatus,
                              String sheetMetadataJson) {
        public WorkingCopy(long id, long baselineDocumentId, String processCode, String officialFilename,
                           String filename, String sourceSha256, String workingSha256,
                           String conversionVersion, String conversionStatus, byte[] bytes) {
            this(id, baselineDocumentId, processCode, officialFilename, filename, sourceSha256, workingSha256,
                    conversionVersion, conversionStatus, bytes, "SYSTEM_GENERATED", "SYSTEM", 1, null,
                    bytes == null ? 0 : bytes.length, "REVIEW_REQUIRED", null);
        }
    }
}
