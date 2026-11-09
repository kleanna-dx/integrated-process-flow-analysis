package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Writes a detached TEST_ONLY comparison workbook; source files are never modified. */
@Component
public class Step7MaterialBalanceComparisonWorkbookExporter {
    public byte[] export(Step7MaterialBalanceComparisonService.ComparisonReport report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = styles(workbook);
            writeSummary(workbook, styles, report);
            writeConnections(workbook, styles, report);
            writeExistingBalances(workbook, styles, report);
            writeCycles(workbook, styles, report);
            writeChangeScenarios(workbook, styles, report);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create Step 7-2 TEST_ONLY comparison workbook", exception);
        }
    }

    private void writeSummary(Workbook workbook, Styles styles,
                              Step7MaterialBalanceComparisonService.ComparisonReport report) {
        Sheet sheet = workbook.createSheet("전체 요약");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("항목", "결과", "근거 / 해석"));
        rows.add(row("산출물 성격", "TEST_ONLY 비교본", "분리 XLSX. 원본 XLSM·공식 V1.0·DB에 기록하지 않음."));
        rows.add(row("팀원 원본", report.sourceFilename(), "SHA-256: " + report.sourceSha256()));
        rows.add(row("원본 크기(bytes)", report.sourceBytes(), "원본을 read-only로 로드"));
        rows.add(row("입력 시트 연결 행", report.inputRows(), "입력시트 행 데이터. 저장 캐시를 새로고침하지 않음."));
        rows.add(row("원본 시트 수 / 입력 열 수", report.workbookSheetCount() + " / " + report.inputColumnCount(), "단위·측정 기준 기간 필드는 입력시트에 없음."));
        rows.add(row("PW-01 입력 연결 행", report.pw01InputRows(), "송신 또는 수신 대분류가 PW1인 행"));
        rows.add(row("PW-01 고유 endpoint 쌍", report.pw01UniqueEndpointPairs(), "출발·도착 문자열 쌍; 공식 도면 연결 확정이 아님"));
        rows.add(row("정확 마스터 매칭 — 양쪽 endpoint", report.bothEndpointsMasterMatched(), "전체 입력행 기준, 설비 ID 문자열 정확 일치"));
        rows.add(row("정확 마스터 매칭 — 한쪽 endpoint", report.oneEndpointMasterMatched(), "전체 입력행 기준"));
        rows.add(row("정확 마스터 매칭 — 양쪽 모두 불일치", report.noEndpointMasterMatch(), "외부 경계/별칭일 수 있으므로 오류 단정 금지"));
        rows.add(row("PW-01 양 endpoint 정확 마스터 매칭 행", report.pw01BothEndpointsMasterMatched(), "공식 설비 ID 식별만 확인; 연결 endpoint 일치 증거는 아님"));
        rows.add(row("유량 행 — 양수 / 0 / 공란", report.positiveQuantityRows() + " / " + report.zeroQuantityRows() + " / " + report.blankQuantityRows(), "0과 공란은 별도 유지"));
        rows.add(row("물질 종류 공란 행", report.blankMaterialTypeRows(), "미분류를 0 또는 동일 물질로 취급하지 않음"));
        rows.add(row("질량수지 계산 제외 필드 공란", report.blankExclusionRows(), "전부 공란이어도 '제외 없음'으로 해석하지 않음"));
        rows.add(row("중복 endpoint 쌍 그룹 / 관련 행", report.duplicatedEndpointPairGroups() + " / " + report.rowsWithinDuplicatedEndpointPairs(), "중복만으로 중복 오류 판정하지 않음"));
        rows.add(row("공식 기준 버전", report.officialEvidence().baselineVersion(), "읽기 전용 공식 V1.0 기준"));
        rows.add(row("공식 기준 문서 / 설비", report.officialEvidence().baselineDocuments() + " / " + report.officialEvidence().officialEquipmentRecords(), "공식 도면 15 + 마스터 1, 설비 1,332"));
        rows.add(row("공식 equipment_flow 행", report.officialEvidence().officialEquipmentFlowRows(), "현재 저장된 공식 유량 행이 없어 DB 유량의 직접 비교 근거 없음"));
        rows.add(row("공식 PW-01 connector / endpoint binding", report.officialEvidence().pw01ConnectorCount() + " / " + report.officialEvidence().pw01ExplicitEndpointBindings(), "connector 외형/좌표만으로 설비 endpoint를 추론하지 않음"));
        rows.add(row("공식 관계 — CONFIRMED / REVIEW_REQUIRED", report.officialEvidence().confirmedRelations() + " / " + report.officialEvidence().reviewRequiredRelations(), "기존 검토 후보를 승인 관계로 간주하지 않음"));
        rows.add(row("연결별 상태", "INSUFFICIENT_EVIDENCE " + report.statuses().baselineRowsInsufficientEvidence(), "공식 도면의 연결 endpoint와 유량을 독립적으로 특정할 자료 부족"));
        rows.add(row("확정 오류 건수", "DB_ERROR_CONFIRMED 0 / LEGACY_DIAGRAM_ERROR_CONFIRMED 0", "추가 독립 근거가 없어 오류 확정하지 않음"));
        rows.add(row("저장된 비교 캐시 행", report.cachedBalances().size(), "팀원 파일 저장 당시의 Power Query/수식 캐시이며 이번에 재계산하지 않음"));
        rows.add(row("캐시 비교 TRUE / FALSE / 공란", report.cachedComparedTrueRows() + " / "
                + report.cachedComparedFalseRows() + " / " + report.cachedComparedMissingRows(),
                "캐시 값 그대로 기록; FALSE만으로 DB/공식도면 오류로 확정하지 않음"));
        rows.add(row("캐시 잔차 공란 / 비영", report.cachedResidualBlankRows() + " / " + report.cachedResidualNonzeroRows(),
                "저장 시점의 기존 불일치 후보. 측정기간·단위·자료 기준시점 미상"));
        rows.add(row("비교 제한", report.limitations(), "원인·시점·단위 확인 전에는 차이를 오류로 판정할 수 없음"));
        addRows(sheet, rows, styles);
        widths(sheet, 34, 50, 108);
    }

    private void writeConnections(Workbook workbook, Styles styles,
                                  Step7MaterialBalanceComparisonService.ComparisonReport report) {
        Sheet sheet = workbook.createSheet("연결별 대조");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("원본 행", "PW-01 범위", "출발 설비/경계", "도착 설비/경계", "질량 원문", "질량 숫자",
                "질량 상태", "물질 종류", "출발 중분류", "도착 중분류", "출발 대분류", "도착 대분류",
                "계산 제외 원문", "출발 ID 마스터 일치", "도착 ID 마스터 일치", "동일 endpoint 쌍 행수",
                "단위", "기준 기간", "공식 연결 비교 상태", "근거/제한"));
        for (var connection : report.connections()) {
            rows.add(row(connection.sourceRow(), connection.pw01Scope() ? "PW-01" : "기타",
                    connection.source(), connection.target(), connection.quantityText(), connection.quantity(),
                    connection.quantityState(), connection.materialType(), connection.sourceMiddle(), connection.targetMiddle(),
                    connection.sourceMajor(), connection.targetMajor(), connection.excludedRaw(),
                    connection.sourceExactMasterMatch() ? "정확 일치" : "미일치",
                    connection.targetExactMasterMatch() ? "정확 일치" : "미일치", connection.duplicateEndpointCount(),
                    connection.unit(), connection.period(), connection.status(), connection.evidenceNote()));
        }
        addRows(sheet, rows, styles);
        widths(sheet, 12, 12, 22, 22, 18, 16, 16, 24, 16, 16, 16, 16, 20, 18, 18, 20, 12, 18, 28, 88);
    }

    private void writeExistingBalances(Workbook workbook, Styles styles,
                                       Step7MaterialBalanceComparisonService.ComparisonReport report) {
        Sheet sheet = workbook.createSheet("기존 수지 불일치");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("캐시 행", "설비 ID", "공식 대분류", "PW-01 공식 마스터 설비", "저장 유입 합계", "저장 유출 합계",
                "저장 비교 결과", "저장 잔차", "캐시 검토 분류", "데이터 상태", "주의"));
        for (var balance : report.cachedBalances()) {
            boolean residualNonZero = balance.cachedResidual() != null
                    && balance.cachedResidual().compareTo(BigDecimal.ZERO) != 0;
            boolean cachedMismatch = "false".equalsIgnoreCase(balance.cachedCompared())
                    || balance.cachedCompared() == null || residualNonZero;
            if (!balance.pw01MasterEquipment() && !cachedMismatch) continue;
            String reviewClass = balance.cachedResidual() == null
                    ? "저장 잔차 공란 — 재계산하지 않음"
                    : cachedMismatch ? "기존 저장 캐시 불일치 후보 — 원인 미확정"
                    : "저장 캐시상 일치 — 공식 자료 일치 판정 아님";
            rows.add(row(balance.sourceRow(), balance.equipment(), balance.officialMajorProcessCode(),
                    balance.pw01MasterEquipment() ? "예" : "아니오", balance.cachedInflow(), balance.cachedOutflow(),
                    balance.cachedCompared(), balance.cachedResidual(), reviewClass, balance.provenance(),
                    "파일에 저장된 시점의 캐시. 측정 기간·단위/기준시점 미상. 기존 불일치 가능성이며 신규 변경 오류 또는 공식 도면/DB 오류 확정 아님."));
        }
        addRows(sheet, rows, styles);
        widths(sheet, 12, 24, 18, 24, 18, 18, 18, 18, 48, 44, 88);
    }

    private void writeCycles(Workbook workbook, Styles styles,
                             Step7MaterialBalanceComparisonService.ComparisonReport report) {
        Sheet sheet = workbook.createSheet("순환·근거 부족");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("유형", "항목 ID", "endpoint/설비 목록", "입력 연결 행 수", "상태", "설명"));
        rows.add(row("근거 한계", "PW-01 도면 endpoint", "", "", "INSUFFICIENT_EVIDENCE",
                "공식 PW-01 connector 986개에 start/end connected-shape binding 0개. 좌표·좌우·행순서로 연결을 승인하지 않음."));
        rows.add(row("근거 한계", "공식 유량", "", "", "INSUFFICIENT_EVIDENCE",
                "V1.0 equipment_flow 저장행 0개. 공식 도면과 팀원 XLSM 비교에 필요한 동일 시점·단위/유량 표기가 제공되지 않음."));
        rows.add(row("관계 검토", "기존 REVIEW_REQUIRED", "PW-01-05 connector shape 149 후보", "", "REVIEW_REQUIRED",
                "기존 후보는 설비 endpoint/유량/단위가 비어 있음. 승인된 관계가 아니며 이 결과에서도 승인하지 않음."));
        for (var cycle : report.cycleComponents()) {
            rows.add(row("DB 방향코드 순환 후보", cycle.componentId(), String.join(", ", cycle.nodeIds()),
                    cycle.connectionRows(), "INSUFFICIENT_EVIDENCE", cycle.note()));
        }
        rows.add(row("해석", "순환 유량 계산", "", "", "계산 미수행",
                "SCC는 입력시트 대분류 방향에서 계산한 후보일 뿐 실제 배관·순환유량 또는 도면 관계가 아님. 순환 질량수지 계산에 쓰지 않음."));
        addRows(sheet, rows, styles);
        widths(sheet, 28, 28, 100, 20, 28, 100);
    }

    private void writeChangeScenarios(Workbook workbook, Styles styles,
                                      Step7MaterialBalanceComparisonService.ComparisonReport report) {
        Sheet sheet = workbook.createSheet("전후잔차 시험예시");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("예시", "기준 잔차", "변경 후 잔차", "변경 원인 검증", "분류", "해석"));
        rows.add(row("A — 균형 기준에 신규 변경", BigDecimal.ZERO, new BigDecimal("2.00"), "합성 시험에서만 TRUE",
                Step7MaterialBalanceComparisonService.classifyChange(BigDecimal.ZERO, new BigDecimal("2.00"), true),
                "오직 합성 예시: 기준 잔차 0에서 인과관계가 검증된 새 변경이 잔차 2를 만들었다고 가정. 실제 공정 값이 아님."));
        rows.add(row("B — 기존 잔차 보존", new BigDecimal("262.32"), new BigDecimal("262.32"), "실제 인과근거 없음",
                Step7MaterialBalanceComparisonService.classifyChange(new BigDecimal("262.32"), new BigDecimal("262.32"), false),
                "팀원 파일 저장 캐시 C-PW14004의 +262.32 사례를 설명용 기준으로 사용. 기존 잔차를 신규 변경 오류로 승격하지 않음."));
        rows.add(row("C — 기존 잔차와 가상 변경 차이", new BigDecimal("262.32"), new BigDecimal("264.32"), "실제 인과근거 없음",
                Step7MaterialBalanceComparisonService.classifyChange(new BigDecimal("262.32"), new BigDecimal("264.32"), false),
                "2.00 차이는 산술 예시일 뿐 실제 변경량이 아님. 변경 원인이 확인되지 않아 신규 오류 판정 불가."));
        rows.add(row("D — 변경 전/후 자료 누락", null, new BigDecimal("1.00"), "불명",
                Step7MaterialBalanceComparisonService.classifyChange(null, new BigDecimal("1.00"), true),
                "기준 잔차가 없으면 신규 변경 여부를 계산하지 않음."));
        rows.add(row("실제 XLSM 신규 변경", null, null, "변경 자료 미제공", "INSUFFICIENT_EVIDENCE",
                "업로드 자료는 기존 캐시/현재 입력 연결 목록이며 새 변경 이벤트가 없음. 실제 변경 전후 잔차를 생성하지 않음."));
        rows.add(row("보존 규칙", "기준 잔차는 독립적으로 기록", "변경 후 잔차도 독립적으로 기록", "인과관계 근거 필요", "별도 분류",
                "기존 baseline imbalance는 신규 변화와 구별한다. 공식/DB 오류는 추가 독립 근거가 있어야 확정한다."));
        addRows(sheet, rows, styles);
        widths(sheet, 38, 20, 20, 28, 48, 110);
    }

    private Styles styles(Workbook workbook) {
        Font normalFont = workbook.createFont();
        normalFont.setFontName("맑은 고딕");
        normalFont.setColor(IndexedColors.BLACK.getIndex());
        normalFont.setFontHeightInPoints((short) 10);
        CellStyle normal = workbook.createCellStyle();
        normal.setFont(normalFont);
        normal.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        normal.setWrapText(true);

        Font headerFont = workbook.createFont();
        headerFont.setFontName("맑은 고딕");
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.BLACK.getIndex());
        headerFont.setFontHeightInPoints((short) 10);
        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.THIN);
        header.setWrapText(true);
        header.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        return new Styles(normal, header);
    }

    private void addRows(Sheet sheet, List<List<Object>> rows, Styles styles) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            row.setHeightInPoints(r == 0 ? 34 : 30);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                Cell cell = row.createCell(c);
                Object value = values.get(c);
                if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
                else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else if (value != null) cell.setCellValue(value.toString());
                cell.setCellStyle(r == 0 ? styles.header() : styles.normal());
            }
        }
        sheet.createFreezePane(0, 1);
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size() - 1), 0, rows.get(0).size() - 1));
        }
    }

    private void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(widths[i], 120) * 256);
    }

    private List<Object> row(Object... values) { return java.util.Arrays.asList(values); }
    private record Styles(CellStyle normal, CellStyle header) { }
}
