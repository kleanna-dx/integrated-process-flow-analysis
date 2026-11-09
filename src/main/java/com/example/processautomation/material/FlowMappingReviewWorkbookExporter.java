package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Produces a detached, review-only XLSX; generated strings are written as text, never formulas. */
@Component
public class FlowMappingReviewWorkbookExporter {
    private static final List<String> DETAIL_HEADERS = List.of("검토 행 ID", "원본 시트", "원본 행", "원본 SHA-256",
            "출발 ID", "도착 ID", "출발 대분류", "도착 대분류", "출발 중분류", "도착 중분류",
            "유량 원문", "유량 숫자", "유량 상태", "물질 종류", "단위", "기준 기간", "출발 도면 후보",
            "도착 도면 후보", "Connector binding", "물리 연결 증빙", "품질 상태", "검토 상태", "검토 의견");

    public byte[] export(FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        return export(snapshot, "", "");
    }

    public byte[] export(FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot, String stateFilter) {
        return export(snapshot, stateFilter, "");
    }

    public byte[] export(FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot, String stateFilter, String query) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = styles(workbook);
            Sheet summary = workbook.createSheet("종합 현황");
            List<List<Object>> summaryRows = new ArrayList<>(List.of(
                    List.of("항목", "값", "해석"),
                    List.of("산출물", "검토 전용", "공식 기준·원본 workbook·DB를 수정하지 않음"),
                    List.of("원본 파일", safe(snapshot.sourceFilename()), "사용자가 업로드한 읽기 전용 파일"),
                    List.of("원본 SHA-256", snapshot.sourceSha256(), "원본 행 식별의 일부"),
                    List.of("원본 시트", "입력시트", "기존 물질수지 입력 시트에서 읽은 행 번호를 보존"),
                    List.of("원본 행 수", snapshot.inputRows(), "검토 시트는 중복 포함될 수 있으며 시트별 건수 합은 고유 행 수가 아님"),
                    List.of("선택 검토 필터", stateFilter == null || stateFilter.isBlank() ? "전체" : stateFilter, "필터 조건은 상세 행에도 동일하게 적용"),
                    List.of("검색어", safe(query), "원본 행 검색 조건"),
                    List.of("내보낸 입력 행 수", snapshot.rows().size(), "선택 필터와 검색어를 통과한 고유 입력 행 수"),
                    List.of("인벤토리 상태", safe(snapshot.inventoryStatus()), "기존 checkpoint의 파일 SHA 일치와 DB fingerprint 확인은 구분"),
                    List.of("후보 출처", safe(snapshot.candidateIndexStatus()), "현재 DB freshness가 검증되지 않으면 후보는 참고용"),
                    List.of("현재 source fingerprint", safe(snapshot.sourceFingerprint()), "DB read-only metadata 기준"),
                    List.of("저장 checkpoint fingerprint", safe(snapshot.checkpointSourceFingerprint()), "동일 여부가 확인되지 않으면 후보 미제공"),
                    List.of("검토 행", snapshot.rows().size(), "원본 XLSM/공식 도면 파일은 수정하지 않음"),
                    List.of("실제 VERIFIED 유량 매핑", 0, "문자열 후보는 물리 연결 증빙이 아님"),
                    List.of("물리 연결 증빙", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "별도 확인 전까지 미검증"),
                    List.of("실제 readiness", "CALCULATION_UNAVAILABLE", "본 보고서는 공정 계산을 수행하지 않음"),
                    List.of("XLSM 처리", "매크로·수식·쿼리 미실행", "저장 값만 읽음")));
            snapshot.priorityGroups().forEach(group -> summaryRows.add(List.of("검토 그룹: " + group.label(), group.count(),
                    "그룹 간 중복 가능; 합계를 고유 행 수로 해석하지 않음")));
            addRows(summary, styles, summaryRows);
            String selectedRowsSheet = stateFilter == null || stateFilter.isBlank() ? "전체 입력 행" : "필터 결과 행";
            writeGroup(workbook.createSheet(selectedRowsSheet), styles, snapshot, snapshot.rows());
            writeGroup(workbook.createSheet("설비 ID 미확인"), styles, snapshot,
                    subset(snapshot, "MISSING_SOURCE_EQUIPMENT_CANDIDATE", "MISSING_TARGET_EQUIPMENT_CANDIDATE"));
            writeGroup(workbook.createSheet("복수 위치 후보"), styles, snapshot, subset(snapshot, "MULTIPLE_LOCATION_CANDIDATES"));
            writeGroup(workbook.createSheet("중복 endpoint"), styles, snapshot, subset(snapshot, "DUPLICATE_ENDPOINT_PAIR"));
            writeGroup(workbook.createSheet("물질 종류 누락"), styles, snapshot, subset(snapshot, "MISSING_MATERIAL_TYPE"));
            writeGroup(workbook.createSheet("단위·기간 누락"), styles, snapshot, subset(snapshot, "MISSING_FLOW_UNIT", "MISSING_PERIOD"));
            writeGroup(workbook.createSheet("연결 증빙 필요"), styles, snapshot, subset(snapshot, "CONNECTOR_BINDING_REQUIRED", "MISSING_EVIDENCE"));
            writeInputTemplate(workbook.createSheet("담당자 검토 입력 양식"), styles, snapshot);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to export the flow mapping review workbook", exception);
        }
    }

    private List<FlowMappingReviewService.MaterialBalanceReviewRow> subset(
            FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot, String... statuses) {
        return snapshot.rows().stream().filter(row -> java.util.Arrays.stream(statuses)
                .anyMatch(row.qualityStatuses()::contains)).toList();
    }

    private void writeGroup(Sheet sheet, Styles styles, FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot,
                            List<FlowMappingReviewService.MaterialBalanceReviewRow> rows) {
        List<List<Object>> values = new ArrayList<>();
        values.add(new ArrayList<>(DETAIL_HEADERS));
        for (var item : rows) {
            values.add(List.of(item.rowId(), "입력시트", item.sourceRow(), snapshot.sourceSha256(), safe(item.source()), safe(item.target()),
                    safe(item.sourceMajor()), safe(item.targetMajor()), safe(item.sourceMiddle()), safe(item.targetMiddle()),
                    safe(item.quantityText()), item.quantity() == null ? "" : item.quantity(),
                    safe(item.quantityState()), safe(item.materialType()), safe(item.unit()), safe(item.period()),
                    locations(snapshot.candidatesById().getOrDefault(item.source(), List.of())),
                    locations(snapshot.candidatesById().getOrDefault(item.target(), List.of())), item.connectorBindingStatus(),
                    item.physicalConnectionEvidenceStatus(), String.join(" | ", item.qualityStatuses()), item.reviewStatus(),
                    safe(item.reviewReason())));
        }
        addRows(sheet, styles, values);
        sheet.setColumnWidth(0, 38 * 256); sheet.setColumnWidth(1, 16 * 256);
        for (int i = 2; i < DETAIL_HEADERS.size(); i++) sheet.setColumnWidth(i, (i == 17 || i == 18 || i == 22 ? 48 : 20) * 256);
    }

    private void writeInputTemplate(Sheet sheet, Styles styles, FlowMappingReviewService.MaterialBalanceReviewSnapshot snapshot) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("검토 대상 ID", "원본 시트", "원본 행", "원본 SHA-256", "공정 후보 (원본)", "시트 후보 (원본)",
                "출발 설비 ID (원본)", "도착 설비 ID (원본)", "원본 도면 위치 후보", "검토 후보 위치", "기존 유량 원문 (원본)", "검토 유량값",
                "기존 물질 종류 (원본)", "검토 물질 종류", "기존 단위 (원본)", "검토 단위", "기존 기준 기간 (원본)",
                "검토 기준 기간", "검토 대상 공정", "검토 대상 시트", "연결·배관 식별자", "내부 순환 여부", "공정 간 이동 여부",
                "추가 증빙 필요 사항", "증빙 자료 식별정보", "검토자", "검토 일자", "검토 의견", "검토 상태"));
        for (var item : snapshot.rows()) {
            List<ProcessDiagramInventoryService.EquipmentOccurrence> candidates = new ArrayList<>();
            candidates.addAll(snapshot.candidatesById().getOrDefault(item.source(), List.of()));
            candidates.addAll(snapshot.candidatesById().getOrDefault(item.target(), List.of()));
            String processes = candidates.stream().map(ProcessDiagramInventoryService.EquipmentOccurrence::processCode).distinct()
                    .collect(java.util.stream.Collectors.joining(" | "));
            String sheets = candidates.stream().map(candidate -> candidate.processCode() + "/" + candidate.sheetName())
                    .distinct().collect(java.util.stream.Collectors.joining(" | "));
            String sourceSheet = "입력시트";
            String reviewKey = snapshot.sourceSha256() + ":" + sourceSheet + ":" + item.sourceRow();
            rows.add(java.util.Arrays.asList(reviewKey, sourceSheet, item.sourceRow(), snapshot.sourceSha256(), processes, sheets,
                    safe(item.source()), safe(item.target()), locations(candidates), "", safe(item.quantityText()), "",
                    safe(item.materialType()), "", safe(item.unit()), "", safe(item.period()), "", "", "", "", "", "", "", "", "", "", "",
                    "NOT_REVIEWED"));
        }
        for (int c=0;c<rows.get(0).size();c++) {
            String header=String.valueOf(rows.get(0).get(c));
            sheet.setColumnWidth(c,(header.contains("ID")||header.contains("설비")||header.contains("증빙")||header.contains("위치")?32:20)*256);
        }
        addRows(sheet, styles, rows, java.util.Set.of(9, 11, 13, 15, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28));
    }

    private String locations(List<ProcessDiagramInventoryService.EquipmentOccurrence> items) {
        return items == null ? "" : items.stream().map(item -> item.processCode() + "/" + item.sheetName()
                + " " + item.topLeftCell() + "-" + item.bottomRightCell() + " [" + item.candidateStatus() + "]")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private String safe(String value) { return value == null ? "" : value; }

    private void addRows(Sheet sheet, Styles styles, List<List<Object>> values) {
        addRows(sheet, styles, values, java.util.Set.of());
    }

    private void addRows(Sheet sheet, Styles styles, List<List<Object>> values, java.util.Set<Integer> inputColumns) {
        for (int r = 0; r < values.size(); r++) {
            Row row = sheet.createRow(r);
            row.setHeightInPoints(r == 0 ? 34 : 30);
            for (int c = 0; c < values.get(r).size(); c++) {
                var cell = row.createCell(c);
                Object value = values.get(r).get(c);
                if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else cell.setCellValue(value == null ? "" : String.valueOf(value));
                cell.setCellStyle(r == 0 ? styles.header() : inputColumns.contains(c) ? styles.input() : styles.normal());
            }
        }
        sheet.createFreezePane(0, 1);
        if (!values.isEmpty() && !values.get(0).isEmpty()) sheet.setAutoFilter(
                new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, values.size() - 1), 0, values.get(0).size() - 1));
    }

    private Styles styles(XSSFWorkbook workbook) {
        Font normalFont = workbook.createFont(); normalFont.setFontName("맑은 고딕"); normalFont.setColor(IndexedColors.BLACK.getIndex());
        Font headerFont = workbook.createFont(); headerFont.setFontName("맑은 고딕"); headerFont.setBold(true);
        headerFont.setColor(IndexedColors.BLACK.getIndex());
        CellStyle normal = workbook.createCellStyle(); normal.setFont(normalFont); normal.setWrapText(true);
        normal.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        CellStyle header = workbook.createCellStyle(); header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.THIN); header.setWrapText(true);
        header.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        CellStyle input = workbook.createCellStyle(); input.cloneStyleFrom(normal);
        input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return new Styles(normal, header, input);
    }
    private record Styles(CellStyle normal, CellStyle header, CellStyle input) { }
}
