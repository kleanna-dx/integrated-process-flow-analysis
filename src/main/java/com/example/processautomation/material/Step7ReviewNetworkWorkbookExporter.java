package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Detached five-sheet report: actual review readiness stays separate from synthetic solver output. */
@Component
public class Step7ReviewNetworkWorkbookExporter {
    public byte[] export(Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = styles(workbook);
            writeReviewStatus(workbook, styles, report);
            writeGraphProjection(workbook, styles, report);
            writeHeldReasons(workbook, styles, report);
            writeSyntheticScenarios(workbook, styles, report);
            writeActualReadiness(workbook, styles, report);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to export Step 7-4B TEST_ONLY review network report", exception);
        }
    }

    private void writeReviewStatus(Workbook workbook, Styles styles, Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        Sheet sheet = workbook.createSheet("검토 연결 현황");
        add(sheet, styles, row("자료 구분", "후보 ID", "검토 상태", "계산망 편입 가능", "처리", "제외/보류 사유", "검토 버전", "검토 이력 ID", "DB 원본 행", "공식 도면", "시트", "출발 설비", "도착 설비", "물질", "검토 유량", "단위", "기준 기간", "출발 셀 힌트", "도착 셀 힌트", "Connector 후보 ID", "검토자", "증빙 유형", "증빙 ID", "검토 근거"));
        for (var candidate : report.actualReadiness().candidates()) {
            add(sheet, styles, row("ACTUAL_PW0105_READ_ONLY", candidate.candidateId(), candidate.reviewState(),
                    candidate.eligible() ? "예" : "아니오", candidate.disposition(), String.join("; ", candidate.reasons()),
                    candidate.reviewVersion(), candidate.reviewHistoryId(), candidate.sourceRowNumber(), candidate.drawingFile(),
                    candidate.drawingSheet(), candidate.sourceEquipment(), candidate.targetEquipment(), candidate.materialType(),
                    candidate.quantity(), candidate.unit(), candidate.period(), candidate.sourceCellHint(), candidate.targetCellHint(),
                    candidate.connectorCandidateIds(), candidate.reviewer(), candidate.evidenceType(), candidate.evidenceIdentifier(),
                    candidate.evidenceText()));
        }
        add(sheet, styles, row("ACTUAL_SUMMARY", "", "", "아니오", "실제 데이터 계산 미수행",
                "EVIDENCE_VERIFIED 0; 33개 원본 후보는 검토/route topology/완결 scope 증빙 부족", "", "", "", "", "", "", "", "", "", "", ""));
        widths(sheet, 25, 36, 24, 20, 34, 54, 16, 19, 15, 44, 20, 22, 22, 22, 18, 16, 25, 22, 22, 38, 24, 24, 30, 70);
    }

    private void writeGraphProjection(Workbook workbook, Styles styles, Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        Sheet sheet = workbook.createSheet("계산망 변환 결과");
        add(sheet, styles, row("자료 구분", "Node/Edge", "ID", "출발 Node", "도착 Node", "출발 설비", "도착 설비", "출발 공정", "도착 공정", "물질 구분", "물질 종류", "유량", "단위", "기준 기간", "고정/변경", "입력 근거", "후보 ID", "검토 이력 ID", "검토 버전", "DB 행", "공식 도면", "도면 시트", "도면/경로 증빙", "경로 ID", "연결 유형", "검토 상태", "검토자", "증빙 유형", "검토 증빙 ID", "검토 근거", "유량 증빙 ID", "원본 출발 셀", "원본 도착 셀", "Connector 후보 ID"));
        var representative = report.syntheticScenarios().stream()
                .filter(scenario -> scenario.scenarioId().equals("A") && scenario.network() != null).findFirst().orElse(null);
        if (representative == null || representative.network() == null) {
            add(sheet, styles, row("ACTUAL_PW0105", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "실제 계산망 편입 0건"));
        } else {
            for (var node : representative.network().nodes()) {
                add(sheet, styles, row("SYNTHETIC_TEST_ONLY", "NODE", node.nodeId(), "", "", node.equipmentNumber(),
                        "", "", node.processCode(), "", "", "", "", "", "", "TEST_FIXTURE_ONLY", "", "", "", "",
                        node.sourceDocument(), node.sourceSheet(), "합성 fixture boundary/equipment"));
            }
            Map<String, Step7ReviewNetworkAdapter.EdgeProvenance> source = representative.edgeProvenance().stream()
                    .collect(java.util.stream.Collectors.toMap(Step7ReviewNetworkAdapter.EdgeProvenance::edgeId, p -> p));
            for (var edge : representative.network().edges()) {
                var p = source.get(edge.connectionId());
                add(sheet, styles, row("SYNTHETIC_TEST_ONLY", "EDGE", edge.connectionId(), edge.sourceNodeId(), edge.targetNodeId(),
                        p == null ? "" : p.sourceEquipment(), p == null ? "" : p.targetEquipment(), edge.sourceProcess(), edge.targetProcess(),
                        edge.materialClass(), edge.materialType(), edge.quantity(), edge.unit(), p == null ? "" : p.period(),
                        edge.fixed() ? "고정" : "계산 대상", edge.quantityBasis(), p == null ? "" : p.candidateId(),
                        p == null ? "" : p.reviewHistoryId(), p == null ? "" : p.reviewVersion(), p == null ? "" : p.sourceRowNumber(),
                        p == null ? "" : p.drawingFile(), p == null ? "" : p.drawingSheet(),
                        p == null ? edge.evidence() : p.reviewEvidenceIdentifier() + "; " + p.pathEvidenceIdentifier(),
                        p == null ? "" : p.routeId(), p == null ? edge.connectionType() : p.connectionType(),
                        p == null ? edge.reviewStatus() : p.reviewState(), p == null ? "" : p.reviewer(),
                        p == null ? "" : p.evidenceType(), p == null ? "" : p.reviewEvidenceIdentifier(),
                        p == null ? "" : p.reviewEvidenceText(), p == null ? "" : p.quantityEvidenceIdentifier(),
                        p == null ? "" : p.sourceCellHint(), p == null ? "" : p.targetCellHint(),
                        p == null ? "" : p.connectorCandidateIds()));
            }
        }
        widths(sheet, 22, 14, 34, 28, 28, 23, 23, 16, 16, 20, 24, 16, 16, 25, 18, 22, 34, 20, 16, 14, 42, 20, 56, 28, 24, 22, 20, 22, 28, 56, 28, 22, 22, 40);
    }

    private void writeHeldReasons(Workbook workbook, Styles styles, Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        Sheet sheet = workbook.createSheet("계산 불가·보류 사유");
        add(sheet, styles, row("자료 구분", "후보 ID/범위", "상태", "사유 코드", "필요한 추가 근거"));
        for (var candidate : report.actualReadiness().candidates()) {
            if (candidate.reasons().isEmpty()) continue;
            for (String reason : candidate.reasons()) add(sheet, styles, row("ACTUAL_PW0105_READ_ONLY", candidate.candidateId(),
                    candidate.reviewState(), reason, guidance(reason)));
        }
        for (String issue : report.actualReadiness().issues()) {
            add(sheet, styles, row("ACTUAL_SCOPE", report.actualSnapshotId(), report.actualReadiness().status(), issue, guidance(issue)));
        }
        if (sheet.getPhysicalNumberOfRows() == 1) add(sheet, styles, row("ACTUAL_PW0105_READ_ONLY", "", "", "현재 보류 항목 없음", ""));
        widths(sheet, 24, 42, 26, 46, 88);
    }

    private void writeSyntheticScenarios(Workbook workbook, Styles styles, Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        Sheet sheet = workbook.createSheet("TEST_ONLY 시나리오 결과");
        add(sheet, styles, row("자료 구분", "시나리오 ID", "설명", "계산 상태", "계산 가능", "기준 수지 상태", "신규 변경 영향", "변경 Edge", "변경 전", "변경 후", "실제 변경 유량", "외부 유입", "외부 유출", "기준 잔차", "변경 후 잔차", "잔차 변화", "Stream별 잔차 상세", "누락/제약", "주의"));
        for (var scenario : report.syntheticScenarios()) {
            String streamDetails = scenario.externalStreamBalances() == null ? "" : scenario.externalStreamBalances().stream()
                    .map(balance -> balance.materialClass() + "/" + balance.materialType() + "/" + balance.unit() + "/" + balance.period()
                            + ": baseline=" + balance.baselineResidual() + ", after=" + balance.afterResidual()
                            + ", delta=" + balance.residualDelta() + ", status=" + balance.status())
                    .collect(java.util.stream.Collectors.joining(" | "));
            add(sheet, styles, row("SYNTHETIC_TEST_ONLY", scenario.scenarioId(), scenario.scenarioName(), scenario.calculationStatus(),
                    scenario.calculationAvailable() ? "예" : "아니오", scenario.baselineBalanceStatus(), scenario.changeImpactStatus(),
                    scenario.changedConnectionId(), scenario.oldQuantity(), scenario.newQuantity(), scenario.resultingChangedQuantity(),
                    scenario.externalInflow(), scenario.externalOutflow(), scenario.baselineResidual(), scenario.afterResidual(),
                    scenario.residualDelta(), streamDetails, String.join("; ", scenario.issues()),
                    "합성 데이터 전용; 실제 공정 결과 아님"));
        }
        widths(sheet, 23, 22, 52, 30, 16, 26, 32, 34, 15, 15, 17, 17, 17, 18, 18, 18, 82, 66, 54);
    }

    private void writeActualReadiness(Workbook workbook, Styles styles, Step7ReviewNetworkWorkflowService.IntegrationReport report) {
        Sheet sheet = workbook.createSheet("실제 적용 준비 상태");
        add(sheet, styles, row("검사항목", "현재 결과", "판정", "필요 조치/설명"));
        add(sheet, styles, row("기준 snapshot", report.actualSnapshotId(), report.baselineVersion(), "V1.0 잠금 기준 snapshot; 공식 파일은 읽기 전용"));
        add(sheet, styles, row("PW-01-05 실제 후보", report.actualCandidateCount(), "후보", "후보는 실제 연결 확정/승인 건수와 다름"));
        add(sheet, styles, row("실제 EVIDENCE_VERIFIED", report.actualEvidenceVerifiedCount(), report.actualEvidenceVerifiedCount() == 0 ? "현재 0건" : "담당자 검토 상태", "기술 검토 상태이며 공식 CONFIRMED 승인 아님"));
        add(sheet, styles, row("실제 계산망 편입", report.actualEligibleConnectionCount(), report.actualEligibleConnectionCount() == 0 ? "미편입" : "적격성 적용", "route/topology/scope 증빙이 별도 완비되어야 함"));
        add(sheet, styles, row("실제 계산 가능 범위", "미계산", report.actualReadiness().status(), "실제 연결망/현장 데이터 계산은 수행하지 않음"));
        add(sheet, styles, row("실제 경계·분기·순환", "미확인", "보류", "외부 경계, 분기 배분, 순환 조건의 근거가 필요"));
        add(sheet, styles, row("단위·기간·물질", "완전 검증 자료 없음", "보류", "같은 차원·물질·측정기간을 확인. 밀도/시간/기체 조건 환산 금지"));
        add(sheet, styles, row("담당자/현장 확인", "필요", "다음 입력", "승인된 P&ID/현장 trace, 유량계 값, 증빙 ID, 기준기간, 외부 경계, 필요한 모든 route 및 term 목록"));
        add(sheet, styles, row("실제 기준 잔차/변경 영향", "미계산", "분리 유지", "기존 기준 residual과 변경 후 residual은 같은 단위·기간·boundary scope일 때만 비교"));
        add(sheet, styles, row("안내", report.notice(), "TEST_ONLY", "시나리오 sheet의 합성 값은 실제 공정 판정 아님"));
        widths(sheet, 34, 50, 28, 90);
    }

    private String guidance(String reason) {
        if (reason.contains("REVIEW_STATE") || reason.contains("REVIEW_HISTORY")) return "최신 담당자 검토 상태와 동일 review version/history를 확인";
        if (reason.contains("ENDPOINT") || reason.contains("EQUIPMENT") || reason.contains("PROCESS")) return "출발/도착 설비와 V1.0 process mapping을 대조하고 양 방향 endpoint 증빙";
        if (reason.contains("PATH") || reason.contains("TOPOLOGY") || reason.contains("DIRECTION")) return "별도 식별되는 실제 배관 경로, 방향, 승인 도면/현장 evidence";
        if (reason.contains("UNIT") || reason.contains("PERIOD") || reason.contains("MATERIAL") || reason.contains("GAS")) return "물질·dimension·unit·측정기간과 기체 기준조건(해당 시); 환산 없이 동질성 확인";
        if (reason.contains("SCOPE") || reason.contains("BOUNDARY") || reason.contains("BRANCH") || reason.contains("CYCLE")) return "모든 필요한 route, external boundary, split/merge/recycle 및 ProcessTerm을 scope 증빙으로 선언";
        return "원본 검토 snapshot과 route별 증빙을 확인하고 누락된 정보를 기록";
    }

    private void add(Sheet sheet, Styles styles, List<Object> values) {
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int column = 0; column < values.size(); column++) {
            var cell = row.createCell(column);
            Object value = values.get(column);
            if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
            else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else if (value instanceof Boolean bool) cell.setCellValue(bool);
            else if (value != null) cell.setCellValue(value.toString());
            cell.setCellStyle(row.getRowNum() == 0 ? styles.header() : styles.body());
        }
    }

    private Styles styles(XSSFWorkbook workbook) {
        var normalFont = workbook.createFont();
        normalFont.setFontName("맑은 고딕");
        normalFont.setColor(IndexedColors.BLACK.getIndex());
        normalFont.setFontHeightInPoints((short) 10);
        var headerFont = workbook.createFont();
        headerFont.setFontName("맑은 고딕");
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.BLACK.getIndex());
        CellStyle body = workbook.createCellStyle();
        body.setFont(normalFont);
        body.setWrapText(true);
        body.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.THIN);
        header.setWrapText(true);
        header.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        return new Styles(header, body);
    }

    private void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(widths[i], 100) * 256);
        sheet.createFreezePane(0, 1);
        if (sheet.getPhysicalNumberOfRows() > 0) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,
                sheet.getPhysicalNumberOfRows() - 1, 0, sheet.getRow(0).getLastCellNum() - 1));
    }

    private List<Object> row(Object... values) { return Arrays.asList(values); }
    private record Styles(CellStyle header, CellStyle body) { }
}
