package com.example.processautomation.material;

import com.example.processautomation.material.FlowMappingReviewDraft.DraftRow;
import com.example.processautomation.material.FlowMappingReviewDraft.ImportResult;
import com.example.processautomation.material.FlowMappingReviewDraft.RowDisposition;
import com.example.processautomation.material.FlowMappingReviewDraft.ReviewStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** File-only validator/exporter for operator review drafts. It never writes to a database or source workbook. */
public final class FlowMappingReviewDraftFileService {
    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    public static final int MAX_DATA_ROWS = 10_000;
    private static final int MAX_PACKAGE_ENTRIES = 2_000;
    private static final long MAX_INFLATED_BYTES = 25L * 1024 * 1024;
    private static final String TEMPLATE_SHEET = "담당자 검토 입력 양식";
    private static final List<String> HEADERS = List.of(
            "검토 대상 ID", "원본 시트", "원본 행", "원본 SHA-256", "공정 후보 (원본)", "시트 후보 (원본)",
            "출발 설비 ID (원본)", "도착 설비 ID (원본)", "원본 도면 위치 후보", "검토 후보 위치", "기존 유량 원문 (원본)",
            "검토 유량값", "기존 물질 종류 (원본)", "검토 물질 종류", "기존 단위 (원본)", "검토 단위",
            "기존 기준 기간 (원본)", "검토 기준 기간", "검토 대상 공정", "검토 대상 시트", "연결·배관 식별자",
            "내부 순환 여부", "공정 간 이동 여부", "추가 증빙 필요 사항", "증빙 자료 식별정보", "검토자", "검토 일자",
            "검토 의견", "검토 상태");
    private static final Set<Integer> TEXT_INPUT_COLUMNS = Set.of(9, 13, 15, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27);

    private FlowMappingReviewDraftFileService() { }

    public static ImportResult parseAndValidate(byte[] bytes, String filename,
            FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES)
            throw badRequest("검토 파일은 1바이트 이상 5MB 이하여야 합니다.");
        if (filename == null || filename.isBlank() || filename.length() > 180
                || filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0
                || filename.contains(":")) throw badRequest("업로드 파일명에 경로 또는 허용되지 않는 문자가 있습니다.");
        String lower = filename.toLowerCase(Locale.ROOT);
        List<List<String>> records;
        if (lower.endsWith(".csv")) records = parseCsv(bytes);
        else if (lower.endsWith(".xlsx")) records = parseXlsx(bytes);
        else throw badRequest("검토 초안은 CSV 또는 XLSX 형식만 지원합니다. XLSM은 허용하지 않습니다.");
        if (records.isEmpty() || !records.get(0).equals(HEADERS))
            throw badRequest("검토 파일 열 이름·순서가 다운로드한 검토 입력 양식과 일치하지 않습니다.");
        if (records.size() - 1 > MAX_DATA_ROWS) throw badRequest("검토 파일이 최대 데이터 행 수를 초과했습니다.");

        Map<Integer, FlowMappingReviewService.MaterialBalanceReviewRow> expectedByRow = new HashMap<>();
        snapshot.rows().forEach(row -> expectedByRow.put(row.sourceRow(), row));
        List<ParsedRow> parsed = new ArrayList<>();
        Map<String, Integer> identityCounts = new HashMap<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> values = records.get(index);
            if (values.stream().allMatch(String::isBlank)) continue;
            if (values.size() != HEADERS.size()) throw badRequest("검토 행 " + (index + 1) + "의 열 수가 양식과 다릅니다.");
            String id = value(values, 0);
            identityCounts.merge(id, 1, Integer::sum);
            parsed.add(validateRow(values, expectedByRow, snapshot));
        }
        Set<Integer> uploadedSourceRows = new HashSet<>();
        List<DraftRow> valid = new ArrayList<>(), review = new ArrayList<>(), conflicts = new ArrayList<>();
        for (ParsedRow item : parsed) {
            DraftRow row = item.row();
            List<String> issues = new ArrayList<>(row.issues());
            if (item.sourceRowExists()) uploadedSourceRows.add(row.sourceRow());
            if (identityCounts.getOrDefault(row.rowId(), 0) > 1) {
                issues.add("업로드 파일 안에 중복 검토 대상 행이 있습니다.");
                row = withDisposition(row, RowDisposition.DUPLICATE_REVIEW_ROW, issues);
                conflicts.add(row);
            } else if (item.conflictDisposition() != null) {
                row = withDisposition(row, item.conflictDisposition(), issues);
                conflicts.add(row);
            } else if (item.invalidFields()) {
                row = withDisposition(row, RowDisposition.INVALID_ROW, issues);
                conflicts.add(row);
            } else {
                if (row.reviewStatus().equals(ReviewStatus.NOT_REVIEWED.name())) issues.add("담당자 검토가 아직 시작되지 않았습니다.");
                if (row.reviewStatus().equals(ReviewStatus.NEEDS_EVIDENCE.name())) issues.add("추가 증빙 확인이 필요합니다.");
                if (!issues.isEmpty() || row.reviewStatus().equals(ReviewStatus.NOT_REVIEWED.name())
                        || row.reviewStatus().equals(ReviewStatus.NEEDS_EVIDENCE.name())) {
                    row = withDisposition(row, RowDisposition.REVIEW_REQUIRED, issues);
                    review.add(row);
                } else {
                    row = withDisposition(row, RowDisposition.VALID_DRAFT, issues);
                    valid.add(row);
                }
            }
        }
        List<DraftRow> missing = new ArrayList<>();
        for (var source : snapshot.rows()) {
            if (!uploadedSourceRows.contains(source.sourceRow())) {
                String id = snapshot.sourceSha256() + ":입력시트:" + source.sourceRow();
                CandidateSnapshot candidates = candidateSnapshot(source, snapshot);
                missing.add(new DraftRow(id, "입력시트", source.sourceRow(), snapshot.sourceSha256(), safe(source.source()),
                        safe(source.target()), "", "", candidates.processes(), candidates.sheets(), candidates.locations(), "",
                        safe(source.quantityText()), "", safe(source.materialType()), "", safe(source.unit()), "",
                        safe(source.period()), "", "", "", "", "", "", "", "", "",
                        ReviewStatus.NOT_REVIEWED.name(), RowDisposition.MISSING_FROM_UPLOAD,
                        List.of("원본 분석에 존재하지만 업로드 파일에서 찾을 수 없습니다.")));
            }
        }
        return new ImportResult(valid, review, conflicts, missing, parsed.size());
    }

    private static ParsedRow validateRow(List<String> values,
            Map<Integer, FlowMappingReviewService.MaterialBalanceReviewRow> expectedByRow,
            FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        List<String> issues = new ArrayList<>();
        boolean invalid = false;
        String rowId = value(values, 0), sheet = value(values, 1), sourceSha = value(values, 3);
        String expectedSha = snapshot.sourceSha256();
        int sourceRow = -1;
        try { sourceRow = Integer.parseInt(value(values, 2)); }
        catch (NumberFormatException exception) { issues.add("원본 행 번호가 정수가 아닙니다."); }
        var expected = expectedByRow.get(sourceRow);
        boolean sourceRowExists = expected != null;
        RowDisposition conflict = null;
        if (!expectedSha.equalsIgnoreCase(sourceSha)) {
            conflict = RowDisposition.SOURCE_SHA_MISMATCH;
            issues.add("원본 XLSM SHA-256이 현재 분석과 다릅니다.");
        } else if (expected == null || !"입력시트".equals(sheet)
                || !(expectedSha + ":입력시트:" + sourceRow).equals(rowId)) {
            conflict = RowDisposition.SOURCE_ROW_MISMATCH;
            issues.add("원본 시트·행 또는 검토 대상 ID가 현재 분석과 다릅니다.");
        } else if (!csvCellValueMatches(safe(expected.source()), value(values, 6))
                || !csvCellValueMatches(safe(expected.target()), value(values, 7))) {
            conflict = RowDisposition.ORIGINAL_EQUIPMENT_ID_CHANGED;
            issues.add("출발 또는 도착 설비 ID 원본 snapshot이 변경됐습니다.");
        } else if (!originalSnapshotMatches(values, expected, snapshot)) {
            conflict = RowDisposition.SOURCE_ROW_MISMATCH;
            issues.add("원본 후보 위치·유량·물질·단위·기간 snapshot이 변경됐습니다.");
        }
        String status = value(values, 28);
        if (status.isBlank()) status = ReviewStatus.NOT_REVIEWED.name();
        if (!isStatus(status)) { invalid = true; issues.add("지원하지 않는 검토 상태입니다."); }
        String quantity = value(values, 11);
        if (!quantity.isBlank()) {
            try { new BigDecimal(quantity.trim()); }
            catch (NumberFormatException exception) { invalid = true; issues.add("검토 유량값이 유한한 숫자 형식이 아닙니다."); }
        }
        String date = value(values, 26);
        if (!date.isBlank()) {
            try { LocalDate.parse(date); }
            catch (RuntimeException exception) { invalid = true; issues.add("검토 일자는 YYYY-MM-DD 형식이어야 합니다."); }
        }
        for (int column : TEXT_INPUT_COLUMNS) {
            String text = value(values, column);
            if (isFormulaLike(text)) { invalid = true; issues.add("수식/스프레드시트 명령처럼 해석될 수 있는 입력값이 있습니다."); break; }
            if (text.length() > 8_000) { invalid = true; issues.add("입력 필드가 허용 길이 8,000자를 초과합니다."); break; }
        }
        if (quantity.length() > 128 || value(values, 13).length() > 256 || value(values, 15).length() > 128
                || value(values, 17).length() > 128) { invalid = true; issues.add("유량·물질·단위·기간 필드 길이가 허용 범위를 초과합니다."); }
        if (!quantity.isBlank() && value(values, 15).isBlank()) issues.add("검토 유량값의 단위가 입력되지 않았습니다.");
        if (!quantity.isBlank() && value(values, 17).isBlank()) issues.add("검토 유량값의 기준 기간이 입력되지 않았습니다.");
        CandidateSnapshot sourceCandidates = expected == null ? new CandidateSnapshot("", "", "") : candidateSnapshot(expected, snapshot);
        boolean preserveOriginal = expected != null && conflict == null;
        DraftRow draft = new DraftRow(rowId, sheet, sourceRow, sourceSha,
                preserveOriginal ? safe(expected.source()) : value(values, 6),
                preserveOriginal ? safe(expected.target()) : value(values, 7),
                value(values, 18), value(values, 19),
                preserveOriginal ? sourceCandidates.processes() : value(values, 4),
                preserveOriginal ? sourceCandidates.sheets() : value(values, 5),
                preserveOriginal ? sourceCandidates.locations() : value(values, 8), value(values, 9),
                preserveOriginal ? safe(expected.quantityText()) : value(values, 10), quantity,
                preserveOriginal ? safe(expected.materialType()) : value(values, 12), value(values, 13),
                preserveOriginal ? safe(expected.unit()) : value(values, 14), value(values, 15),
                preserveOriginal ? safe(expected.period()) : value(values, 16), value(values, 17),
                value(values, 20), value(values, 21), value(values, 22), value(values, 23), value(values, 24),
                value(values, 25), date, value(values, 27), status,
                RowDisposition.REVIEW_REQUIRED, issues);
        return new ParsedRow(draft, conflict, invalid, sourceRowExists);
    }

    private static boolean originalSnapshotMatches(List<String> values,
            FlowMappingReviewService.MaterialBalanceReviewRow expected,
            FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        CandidateSnapshot candidates = candidateSnapshot(expected, snapshot);
        return csvCellValueMatches(candidates.processes(), value(values, 4))
                && csvCellValueMatches(candidates.sheets(), value(values, 5))
                && csvCellValueMatches(candidates.locations(), value(values, 8))
                && csvCellValueMatches(safe(expected.quantityText()), value(values, 10))
                && csvCellValueMatches(safe(expected.materialType()), value(values, 12))
                && csvCellValueMatches(safe(expected.unit()), value(values, 14))
                && csvCellValueMatches(safe(expected.period()), value(values, 16));
    }

    private static boolean csvCellValueMatches(String expected, String uploaded) {
        return expected.equals(uploaded) || (isFormulaLike(expected) && ("'" + expected).equals(uploaded));
    }

    private static CandidateSnapshot candidateSnapshot(FlowMappingReviewService.MaterialBalanceReviewRow row,
            FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        List<ProcessDiagramInventoryService.EquipmentOccurrence> candidates = new ArrayList<>();
        candidates.addAll(snapshot.candidatesById().getOrDefault(row.source(), List.of()));
        candidates.addAll(snapshot.candidatesById().getOrDefault(row.target(), List.of()));
        String processes = candidates.stream().map(ProcessDiagramInventoryService.EquipmentOccurrence::processCode).distinct()
                .collect(java.util.stream.Collectors.joining(" | "));
        String sheets = candidates.stream().map(candidate -> candidate.processCode() + "/" + candidate.sheetName())
                .distinct().collect(java.util.stream.Collectors.joining(" | "));
        String locations = candidates.stream().map(item -> item.processCode() + "/" + item.sheetName() + " " + item.topLeftCell()
                + "-" + item.bottomRightCell() + " [" + item.candidateStatus() + "]")
                .collect(java.util.stream.Collectors.joining("; "));
        return new CandidateSnapshot(processes, sheets, locations);
    }

    private static List<List<String>> parseCsv(byte[] bytes) {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception exception) { throw badRequest("CSV는 유효한 UTF-8이어야 합니다."); }
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, quoteClosed = false;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') { field.append('"'); index++; }
                    else { quoted = false; quoteClosed = true; }
                } else field.append(ch);
            } else if (quoteClosed && ch != ',' && ch != '\r' && ch != '\n') {
                throw badRequest("CSV 따옴표 뒤에 허용되지 않는 문자가 있습니다.");
            } else if (!quoteClosed && ch == '"') {
                if (field.length() != 0) throw badRequest("CSV 따옴표 위치가 잘못되었습니다.");
                quoted = true;
            } else if (ch == ',') {
                record.add(field.toString()); field.setLength(0); quoteClosed = false;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                record.add(field.toString()); field.setLength(0); quoteClosed = false;
                records.add(List.copyOf(record)); record.clear();
                if (records.size() > MAX_DATA_ROWS + 1) throw badRequest("CSV 데이터 행 수 제한을 초과했습니다.");
            } else field.append(ch);
            if (field.length() > 32_000) throw badRequest("CSV 셀 길이가 허용 범위를 초과했습니다.");
        }
        if (quoted) throw badRequest("CSV 인용 문자열이 닫히지 않았습니다.");
        if (field.length() > 0 || quoteClosed || !record.isEmpty()) {
            record.add(field.toString()); records.add(List.copyOf(record));
        }
        return records;
    }

    private static List<List<String>> parseXlsx(byte[] bytes) {
        validateOoxmlPackage(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof XSSFWorkbook)) throw badRequest("XLSX OOXML workbook만 지원합니다.");
            for (var sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                if (cell.getCellType() == CellType.FORMULA) throw badRequest("수식 셀이 포함된 검토 XLSX는 허용하지 않습니다.");
            }
            var sheet = workbook.getSheet(TEMPLATE_SHEET);
            if (sheet == null || sheet.getLastRowNum() > MAX_DATA_ROWS)
                throw badRequest("검토 입력 양식 시트가 없거나 데이터 행 수 제한을 초과했습니다.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> records = new ArrayList<>();
            Row header = sheet.getRow(0);
            if (header == null) throw badRequest("검토 입력 시트의 헤더가 없습니다.");
            records.add(readXlsxRow(header, HEADERS.size(), formatter, true));
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                List<String> values = row == null ? java.util.Collections.nCopies(HEADERS.size(), "")
                        : readXlsxRow(row, HEADERS.size(), formatter, false);
                records.add(values);
            }
            return records;
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw badRequest("검토 XLSX를 손상 없이 읽지 못했습니다."); }
    }

    private static List<String> readXlsxRow(Row row, int columns, DataFormatter formatter, boolean header) {
        for (int c = columns; c < Math.max(columns, row.getLastCellNum()); c++) {
            Cell extra = row.getCell(c);
            if (extra != null && !formatter.formatCellValue(extra).isBlank()) throw badRequest("검토 입력 시트에 지원하지 않는 추가 열이 있습니다.");
        }
        List<String> values = new ArrayList<>(columns);
        for (int c = 0; c < columns; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) { values.add(""); continue; }
            if (!header && cell.getCellType() == CellType.NUMERIC && c != 2 && c != 11)
                values.add(formatter.formatCellValue(cell));
            else values.add(formatter.formatCellValue(cell));
        }
        return List.copyOf(values);
    }

    private static void validateOoxmlPackage(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw badRequest("유효한 XLSX ZIP 패키지가 아닙니다.");
        Set<String> names = new HashSet<>();
        boolean contentTypes = false, workbook = false;
        int entries = 0;
        long inflated = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++entries > MAX_PACKAGE_ENTRIES || name.startsWith("/") || name.contains("..") || name.contains("\\")
                        || !names.add(name)) throw badRequest("OOXML ZIP entry 경로·개수·중복이 허용 범위를 벗어났습니다.");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.startsWith("xl/externallinks/")
                        || lower.equals("xl/connections.xml") || lower.startsWith("xl/querytables/")
                        || lower.startsWith("xl/embeddings/")) throw badRequest("매크로·외부 링크·쿼리·embedded object가 포함된 XLSX는 허용하지 않습니다.");
                if (name.equals("[Content_Types].xml")) contentTypes = true;
                if (name.equals("xl/workbook.xml")) workbook = true;
                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    inflated += count;
                    if (inflated > MAX_INFLATED_BYTES) throw badRequest("압축 해제 후 OOXML 크기 제한을 초과했습니다.");
                    entryBytes.write(buffer, 0, count);
                }
                if (lower.endsWith(".rels") && new String(entryBytes.toByteArray(), StandardCharsets.UTF_8)
                        .matches("(?s).*TargetMode\\s*=\\s*[\\\"']External[\\\"'].*"))
                    throw badRequest("외부 대상으로 연결되는 OOXML 관계가 포함되어 있습니다.");
                zip.closeEntry();
            }
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw badRequest("OOXML ZIP 무결성 검증에 실패했습니다."); }
        if (!contentTypes || !workbook) throw badRequest("XLSX OOXML 필수 파트가 없습니다.");
    }

    public static byte[] exportInputTemplateCsv(FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        StringBuilder output = new StringBuilder(HEADERS.stream().map(FlowMappingReviewDraftFileService::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (var row : snapshot.rows()) {
            CandidateSnapshot candidates = candidateSnapshot(row, snapshot);
            String rowId = snapshot.sourceSha256() + ":입력시트:" + row.sourceRow();
            List<String> values = List.of(rowId, "입력시트", Integer.toString(row.sourceRow()), snapshot.sourceSha256(),
                    candidates.processes(), candidates.sheets(), safe(row.source()), safe(row.target()), candidates.locations(), "",
                    safe(row.quantityText()), "", safe(row.materialType()), "", safe(row.unit()), "", safe(row.period()), "",
                    "", "", "", "", "", "", "", "", "", "", ReviewStatus.NOT_REVIEWED.name());
            output.append(values.stream().map(FlowMappingReviewDraftFileService::csvCell)
                    .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return ("\uFEFF" + output).toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] exportCsv(FlowMappingReviewDraft.ImportPreview preview) {
        StringBuilder output = new StringBuilder(HEADERS.stream().map(FlowMappingReviewDraftFileService::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (DraftRow row : preview.rows()) {
            List<String> values = List.of(row.rowId(), row.sourceSheet(), Integer.toString(row.sourceRow()), row.sourceSha256(),
                    row.processCandidates(), row.sheetCandidates(), row.sourceEquipmentId(), row.targetEquipmentId(),
                    row.candidateLocations(), row.selectedCandidateLocation(), row.originalFlowText(), row.reviewedFlowValue(),
                    row.originalMaterialType(), row.reviewedMaterialType(), row.originalUnit(), row.reviewedUnit(),
                    row.originalPeriod(), row.reviewedPeriod(), row.processTarget(), row.sheetTarget(), row.connectionId(),
                    row.internalRecycle(), row.interProcessTransfer(), row.additionalEvidenceRequired(), row.evidenceReference(),
                    row.reviewer(), row.reviewDate(), row.reviewerComment(), row.reviewStatus());
            output.append(values.stream().map(FlowMappingReviewDraftFileService::csvCell)
                    .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return ("\uFEFF" + output).toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] exportJson(FlowMappingReviewDraft.ImportPreview preview, String originalSourceSha) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("preview", preview); output.put("originalSourceSha256", originalSourceSha);
        output.put("reviewOnly", true); output.put("persisted", false); output.put("verifiedFlowMappingCount", 0);
        output.put("physicalConnectionEvidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        output.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(output); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize review draft JSON", exception); }
    }

    public static byte[] exportXlsx(FlowMappingReviewDraft.ImportPreview preview, String originalSourceSha) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var headerFont = workbook.createFont(); headerFont.setFontName("맑은 고딕"); headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var bodyFont = workbook.createFont(); bodyFont.setFontName("맑은 고딕");
            bodyFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var header = workbook.createCellStyle(); header.setFont(headerFont);
            header.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            header.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            var body = workbook.createCellStyle(); body.setFont(bodyFont); body.setWrapText(true);
            var summary = workbook.createSheet("검토 가져오기 요약");
            String[][] metrics = {{"항목", "값"}, {"검토본", "파일 기반 읽기 전용"}, {"원본 XLSM SHA-256", originalSourceSha},
                    {"업로드 행", Integer.toString(preview.uploadedRows())}, {"정상 초안", Integer.toString(preview.validDraftRows())},
                    {"검토 필요", Integer.toString(preview.reviewRequiredRows())}, {"충돌", Integer.toString(preview.conflictRows())},
                    {"업로드 누락", Integer.toString(preview.missingRows())}, {"DB 영속 저장", "false"},
                    {"실제 VERIFIED 유량 매핑", "0"}, {"실제 readiness", "CALCULATION_UNAVAILABLE"}};
            for (int r = 0; r < metrics.length; r++) for (int c = 0; c < 2; c++) {
                Cell cell = summary.createRow(r).createCell(c); cell.setCellValue(metrics[r][c]); cell.setCellStyle(r == 0 ? header : body);
            }
            summary.setColumnWidth(0, 32 * 256); summary.setColumnWidth(1, 64 * 256);
            var sheet = workbook.createSheet(TEMPLATE_SHEET);
            writeXlsxRow(sheet, 0, HEADERS, header);
            for (int i = 0; i < preview.rows().size(); i++) {
                DraftRow row = preview.rows().get(i);
                List<String> values = List.of(row.rowId(), row.sourceSheet(), Integer.toString(row.sourceRow()), row.sourceSha256(),
                        row.processCandidates(), row.sheetCandidates(), row.sourceEquipmentId(), row.targetEquipmentId(),
                        row.candidateLocations(), row.selectedCandidateLocation(), row.originalFlowText(), row.reviewedFlowValue(),
                        row.originalMaterialType(), row.reviewedMaterialType(), row.originalUnit(), row.reviewedUnit(),
                        row.originalPeriod(), row.reviewedPeriod(), row.processTarget(), row.sheetTarget(), row.connectionId(),
                        row.internalRecycle(), row.interProcessTransfer(), row.additionalEvidenceRequired(), row.evidenceReference(),
                        row.reviewer(), row.reviewDate(), row.reviewerComment(), row.reviewStatus());
                writeXlsxRow(sheet, i + 1, values, body);
            }
            sheet.createFreezePane(0, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,
                    Math.max(0, preview.rows().size()), 0, HEADERS.size() - 1));
            for (int c = 0; c < HEADERS.size(); c++) sheet.setColumnWidth(c, (c == 0 || c == 3 || c >= 20 ? 38 : 20) * 256);
            var diagnostics = workbook.createSheet("가져오기 판정");
            List<String> diagnosticHeaders = List.of("검토 대상 ID", "검토 상태", "가져오기 판정", "검토 사유");
            writeXlsxRow(diagnostics, 0, diagnosticHeaders, header);
            for (int i = 0; i < preview.rows().size(); i++) {
                DraftRow row = preview.rows().get(i);
                writeXlsxRow(diagnostics, i + 1, List.of(row.rowId(), row.reviewStatus(), row.disposition().name(),
                        String.join(" | ", row.issues())), body);
            }
            diagnostics.createFreezePane(0, 1);
            diagnostics.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, preview.rows().size()), 0, 3));
            diagnostics.setColumnWidth(0, 48 * 256); diagnostics.setColumnWidth(1, 32 * 256);
            diagnostics.setColumnWidth(2, 38 * 256); diagnostics.setColumnWidth(3, 80 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException("Unable to export review draft", exception); }
    }

    private static void writeXlsxRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, List<String> values,
            org.apache.poi.ss.usermodel.CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.size(); c++) { Cell cell = row.createCell(c, CellType.STRING); cell.setCellValue(values.get(c)); cell.setCellStyle(style); }
    }

    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (isFormulaLike(safe)) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static DraftRow withDisposition(DraftRow row, RowDisposition disposition, List<String> issues) {
        return new DraftRow(row.rowId(), row.sourceSheet(), row.sourceRow(), row.sourceSha256(), row.sourceEquipmentId(),
                row.targetEquipmentId(), row.processTarget(), row.sheetTarget(), row.processCandidates(), row.sheetCandidates(),
                row.candidateLocations(), row.selectedCandidateLocation(), row.originalFlowText(),
                row.reviewedFlowValue(), row.originalMaterialType(), row.reviewedMaterialType(), row.originalUnit(), row.reviewedUnit(),
                row.originalPeriod(), row.reviewedPeriod(), row.connectionId(), row.internalRecycle(), row.interProcessTransfer(),
                row.additionalEvidenceRequired(), row.evidenceReference(), row.reviewer(), row.reviewDate(),
                row.reviewerComment(), row.reviewStatus(), disposition, issues);
    }

    private static boolean isStatus(String status) {
        try { ReviewStatus.valueOf(status); return true; } catch (RuntimeException exception) { return false; }
    }

    private static boolean isFormulaLike(String value) {
        return value != null && value.matches("(?s)^\\s*[=+@].*|^\\s*-\\d.*");
    }

    private static String value(List<String> values, int index) { return index < values.size() && values.get(index) != null ? values.get(index) : ""; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record ParsedRow(DraftRow row, RowDisposition conflictDisposition, boolean invalidFields, boolean sourceRowExists) { }
    private record CandidateSnapshot(String processes, String sheets, String locations) { }
}
