package com.example.processautomation.material;

import com.example.processautomation.excel.WorkbookInventory;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Detached five-sheet TEST_ONLY evidence workbook; this exporter never edits its inputs. */
@Component
public class Step7ConnectionEvidenceWorkbookExporter {
    private static final String OFFICIAL_DRAWING_FILE = "PW-01 폐수처리공정 ★변경허가.xlsx";
    private static final String OFFICIAL_DRAWING_SHEET = "PW-01-05";

    public byte[] export(Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        return export(data, null);
    }

    public byte[] exportSchemaV2Bridge(Step7ReviewLedgerWorkflowService.ReviewTemplate template) {
        if (template == null) throw new IllegalArgumentException("Schema-v2 review template is required");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = createStyles(workbook);
            for (String name : List.of("연결 후보", "검토 필요 연결", "도면·DB 유량 비교", "기존 물질수지 불일치")) {
                Sheet empty = workbook.createSheet(name);
                add(empty, styles, row("Step 7-5B schema-v2 bridge", "No source workbook edits; review entries only"));
            }
            writeReviewLedger(workbook, styles, null, template);
            workbook.setActiveSheet(4);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create schema-v2 bridge workbook", exception);
        }
    }

    public byte[] export(Step7ConnectionEvidenceService.EvidenceWorkbookData data,
                        Step7ReviewLedgerWorkflowService.ReviewTemplate reviewTemplate) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = createStyles(workbook);
            writeCandidates(workbook, styles, data);
            writeReviewNeeded(workbook, styles, data);
            writeFlowComparison(workbook, styles, data);
            writeLegacyIssues(workbook, styles, data);
            writeReviewLedger(workbook, styles, data, reviewTemplate);
            workbook.setActiveSheet(reviewTemplate == null ? 0 : 4);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create Step 7 TEST_ONLY evidence workbook", exception);
        }
    }

    private void writeCandidates(Workbook workbook, Styles styles,
                                Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        Sheet sheet = workbook.createSheet("연결 후보");
        java.util.Map<Integer, List<Step7ConnectionEvidenceService.ManualFixtureAudit>> fixturesByDbRow =
                data.manualFixtureAudits().stream().collect(java.util.stream.Collectors.groupingBy(
                        Step7ConnectionEvidenceService.ManualFixtureAudit::dbRow));
        add(sheet, styles, List.of(
                row("DB 원본 행", "공식 도면 파일", "공식 도면 시트", "출발 설비번호", "도착 설비번호", "도면 출발 라벨 셀(위치 힌트; 박스 도형 미확정)",
                        "도면 도착 라벨 셀(위치 힌트; 박스 도형 미확정)", "공간상 connector 후보 ID", "도형 anchor 시작 셀", "도형 anchor 끝 셀",
                        "start/end endpoint binding", "화살표 메타데이터", "공식 도면 유량 라벨 후보", "DB 질량 원문", "DB 질량 값",
                        "DB 질량 상태", "DB 물질 종류", "DB 단위/기간", "후보 종류", "원본 추출 상태", "연결 후보 상태",
                        "증빙 검토 상태", "비교 상태", "동일 DB 행의 수동 fixture 참조", "계산망 포함")));
        for (var candidate : data.candidates()) {
            add(sheet, styles, row(candidate.sourceRow(), OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET,
                    candidate.source(), candidate.target(), candidate.sourceCellHints(), candidate.targetCellHints(),
                    candidate.connectorHints().stream().map(Step7ConnectionEvidenceService.ConnectorHint::shapeId).toList(),
                    candidate.connectorHints().stream().map(Step7ConnectionEvidenceService.ConnectorHint::anchorStartCell).toList(),
                    candidate.connectorHints().stream().map(Step7ConnectionEvidenceService.ConnectorHint::anchorEndCell).toList(),
                    candidate.connectorHints().stream().map(h -> h.explicitEndpointBinding() ? "있음" : "없음").toList(),
                    candidate.connectorHints().stream().map(Step7ConnectionEvidenceWorkbookExporter::arrowDescription).toList(),
                    candidate.flowHints().stream().map(Step7ConnectionEvidenceService.FlowEvidence::description).toList(),
                    candidate.quantityText(), candidate.quantity(), candidate.quantityState(), candidate.materialType(),
                    "원본 열 없음 / 기준 기간 없음", "AUTO_EXTRACTED_DB_ID_PAIR", candidate.rawStatus(),
                    candidate.candidateStatus(), candidate.evidenceStatus(), candidate.comparisonStatus(),
                    fixturesByDbRow.getOrDefault(candidate.sourceRow(), List.of()).stream()
                            .map(Step7ConnectionEvidenceService.ManualFixtureAudit::caseKey).toList(), "아니오"));
        }
        widths(sheet, 16, 42, 18, 22, 22, 27, 27, 23, 19, 19, 23, 42, 52, 21, 18, 18, 22, 24, 29, 22, 27, 27, 27, 52, 18);
    }

    private void writeReviewNeeded(Workbook workbook, Styles styles,
                                   Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        Sheet sheet = workbook.createSheet("검토 필요 연결");
        add(sheet, styles, row("사례 ID", "DB 행", "공식 도면 파일", "공식 도면 시트", "출발 설비", "도착 설비",
                "불확실한 연결", "확보된 사실", "부족한 증빙", "담당자 확인 필요 사항", "자동 확정 금지 이유", "상태"));
        add(sheet, styles, row("SYSTEM-PW0105", "", OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET, "", "",
                "PW-01-05 전체", data.methodSummary(),
                "DrawingML connector logical endpoint 바인딩, 독립 설비-배관 근거, DB 단위·기간, 도면/DB 기준 시점",
                "현장 배관 추적 또는 승인된 P&ID/현장기록과 유량계·단위·측정기간을 함께 확인",
                "셀 및 도형의 화면상 근접도/anchor는 논리적 배관 관계가 아님", data.verifiedRelationCount() == 0 ? "EVIDENCE_INSUFFICIENT" : "REVIEW_REQUIRED"));
        for (var candidate : data.candidates()) {
            add(sheet, styles, row(candidate.caseKey(), candidate.sourceRow(), OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET,
                    candidate.source(), candidate.target(),
                    String.join("; ", candidate.connectorHints().stream().map(h -> "shape " + h.shapeId() + " "
                            + h.anchorStartCell() + "→" + h.anchorEndCell() + " (위치 힌트)").toList()),
                    "DB endpoint 쌍과 도면 셀의 설비번호 문자열이 각각 읽힘; 연결선 anchor/화살표 metadata는 별도 기록",
                    candidate.missingEvidence(), "출발·도착 설비와 실제 배관을 현장/승인된 도면으로 확인; 유량·단위·기간·기준시점 확인",
                    "기하학적 근접성만으로 연결/방향/수치 비교를 승인할 수 없음",
                    candidate.candidateStatus() + " / " + candidate.evidenceStatus()));
        }
        for (var fixture : data.manualFixtureAudits()) {
            add(sheet, styles, row("FIXTURE-" + fixture.caseKey(), fixture.dbRow(), OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET,
                    fixture.source(), fixture.target(),
                    "shape " + fixture.actualConnectorId() + " " + fixture.anchorStartCell() + "→" + fixture.anchorEndCell(),
                    "기존 코드의 지정 row·shape·cell이 실제 파일에 존재하는지 조사. 이는 fixture 원출처/논리연결 증거 아님",
                    fixture.auditNote(), "fixture를 자동추출 결과로 승격하지 말 것. 실제 endpoint 및 해당 배관 경로를 독립 증빙으로 확인",
                    "수작업 정의/공간 힌트. endpoint binding은 " + (fixture.explicitEndpointBinding() ? "있음" : "없음"), "MANUAL_UNVERIFIED"));
        }
        for (String warning : data.warnings()) {
            add(sheet, styles, row("SYSTEM-NOTE", "", OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET,
                    "", "", "", warning, "", "", "", "EVIDENCE_INSUFFICIENT"));
        }
        widths(sheet, 28, 15, 42, 18, 23, 23, 46, 70, 80, 72, 72, 30);
    }

    private void writeFlowComparison(Workbook workbook, Styles styles,
                                     Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        Sheet sheet = workbook.createSheet("도면·DB 유량 비교");
        add(sheet, styles, row("DB 행", "출발", "도착", "공식 도면 파일", "공식 도면 시트", "DB 질량 원문/값", "DB 물질", "DB 단위", "DB 기준기간",
                "도면 유량 라벨 셀", "도면 표기 원문", "도면 물질명", "도면 값", "도면 단위", "Excel 셀 형식",
                "기준 시점", "동일 기준 비교 가능", "차이", "검토 상태", "근거/해석"));
        for (var candidate : data.candidates()) {
            if (candidate.flowHints().isEmpty()) {
                add(sheet, styles, row(candidate.sourceRow(), candidate.source(), candidate.target(),
                        OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET, candidate.quantityText(), candidate.materialType(),
                        "미입력", "미입력", "", "", "", null, "",
                        "도면 유량 표기 미특정", "미확인", "아니오", null, "UNIT_OR_PERIOD_UNVERIFIED",
                        "DB 질량 열에 단위·기간이 없고 이 DB 연결에 해당한다고 구조적으로 확인된 도면 유량표기도 없음."));
            } else {
                for (var flow : candidate.flowHints()) {
                    add(sheet, styles, row(candidate.sourceRow(), candidate.source(), candidate.target(),
                            OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET, candidate.quantityText(), candidate.materialType(),
                            "미입력", "미입력", flow.cellAddress(),
                            flow.rawText(), flow.materialName(), flow.quantity(), flow.unit(), flow.cellType(),
                            "공식 V1.0 고정본(기준 운전시점/측정기간 미확인)", "아니오", null,
                            "UNIT_OR_PERIOD_UNVERIFIED", "도면의 문자열 라벨 후보일 뿐 이 DB 행의 배관 연결이나 동일시점·단위는 확정되지 않음."));
                }
            }
        }
        widths(sheet, 12, 22, 22, 42, 18, 23, 20, 14, 18, 17, 39, 22, 18, 17, 24, 42, 23, 16, 32, 90);
    }

    private void writeLegacyIssues(Workbook workbook, Styles styles,
                                   Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        Sheet sheet = workbook.createSheet("기존 물질수지 불일치");
        add(sheet, styles, row("설비번호/사례", "공정", "공식 도면 파일", "시트", "공식 도면 잔차", "DB 캐시 유입",
                "DB 캐시 유출", "DB 캐시 잔차", "기준 잔차", "변경 후 잔차", "기준 대비 잔차 증감", "기존부터 존재",
                "원인 확정", "기존 오류 확정", "검토 상태", "검토 근거", "검토자", "검토일", "자료 기준"));
        for (var issue : data.legacyIssues()) {
            add(sheet, styles, row(issue.equipment(), issue.processCode(), issue.diagramFile(), issue.diagramSheet(),
                    issue.officialResidual(), issue.cachedDbInflow(), issue.cachedDbOutflow(), issue.cachedDbResidual(),
                    issue.residualBeforeChange(), issue.residualAfterChange(), null,
                    issue.preExistingInSourceCache() ? "예" : "아니오",
                    issue.causeConfirmed() ? "예" : "아니오", "아니오 — 독립 근거/담당자 검토 전", issue.status(),
                    issue.evidence(), issue.reviewer(), issue.reviewedDate(), issue.dataBasis()));
        }
        if (data.legacyIssues().isEmpty()) {
            add(sheet, styles, row("", "", "", "", null, null, null, null, null, null, null, "", "", "아니오",
                    "현재 추출된 기존 잔차 표시 없음", "저장 캐시를 재계산하지 않음", "", "", ""));
        }
        add(sheet, styles, row("TEST-ONLY-SYNTHETIC", "합성 네트워크", "", "", null, null, null, null,
                data.syntheticDelta().baselineResidual(), data.syntheticDelta().afterResidual(), data.syntheticDelta().residualDelta(),
                "해당 없음", "합성 시나리오 입력으로 확인", "아니오 — 실제 공정/오류 아님",
                "TEST_ONLY_SYNTHETIC_DELTA", "기준 잔차 10 → 가상 변경 후 15; 증감 +5. 기존 기준잔차와 변경영향을 별도 열로 보존.",
                "", "", "비실측 TEST_ONLY fixture"));
        widths(sheet, 27, 15, 42, 22, 20, 20, 20, 20, 19, 19, 23, 22, 22, 42, 42, 76, 22, 18, 64);
    }

    private void writeReviewLedger(Workbook workbook, Styles styles,
                                   Step7ConnectionEvidenceService.EvidenceWorkbookData data,
                                   Step7ReviewLedgerWorkflowService.ReviewTemplate reviewTemplate) {
        Sheet sheet = workbook.createSheet("담당자 검토대장");
        add(sheet, styles, row("템플릿 버전", "검토 키", "기준 스냅샷 ID", "기준 버전", "후보 ID", "원본 SHA-256",
                "DB 원본 행", "공식 도면 파일", "도면 시트", "원본 후보 출발", "원본 후보 도착", "출발 셀 힌트",
                "도착 셀 힌트", "connector 후보 ID", "DB 질량 원문(참고)", "DB 질량 값(참고)", "DB 물질 종류(참고)",
                "검토 버전", "실제 출발 설비번호", "실제 도착 설비번호", "검토 물질 종류", "확인 유량", "유량 단위",
                "단위 기준 기간", "실제 배관 확인(YES/NO)", "근거 유형", "증빙 식별정보", "근거 설명", "검토자",
                "검토일(YYYY-MM-DD)", "검토 의견", "검토 결과", "순환 후보", "TEST_ONLY 인정", "공식 승인 관계 생성"));
        add(sheet, styles, row("template_schema_version", "case_key", "snapshot_id", "baseline_version", "candidate_id",
                "source_sha256", "db_source_row", "official_drawing_file", "official_drawing_sheet",
                "candidate_source_equipment", "candidate_target_equipment", "source_diagram_cell_hint",
                "target_diagram_cell_hint", "connector_candidate_ids", "source_db_mass_text", "source_db_mass_value",
                "source_db_material_type", "expected_review_version", "source_equipment", "target_equipment",
                "review_material_type", "review_actual_quantity", "review_unit", "review_period",
                "physical_connection_confirmed", "evidence_type", "evidence_identifier", "review_evidence",
                "reviewer_name", "reviewed_date", "review_comment", "review_decision", "review_cycle_candidate",
                "test_only_evidence_accepted", "official_approval_created"));
        if (reviewTemplate != null) {
            for (var candidate : reviewTemplate.rows()) {
                int rowIndex = sheet.getPhysicalNumberOfRows();
                add(sheet, styles, row(reviewTemplate.schemaVersion(), candidate.caseKey(), candidate.snapshotId(),
                        candidate.baselineVersion(), candidate.candidateId(), candidate.sourceSha256(), candidate.dbSourceRow(),
                        candidate.drawingFile(), candidate.drawingSheet(), candidate.candidateSource(), candidate.candidateTarget(),
                        candidate.sourceCell(), candidate.targetCell(), candidate.connectorIds(), candidate.sourceMassRaw(),
                        candidate.sourceMassValue(), candidate.sourceMaterialType(), candidate.expectedVersion(),
                        candidate.source(), candidate.target(), candidate.material(), candidate.quantity(),
                        candidate.unit(), candidate.period(), candidate.connectionConfirmed(), candidate.evidenceType(),
                        candidate.evidenceId(), candidate.evidenceText(), candidate.reviewer(), candidate.reviewedDate(),
                        candidate.comment(), candidate.decision(), "", "예", "아니오"));
                for (int column = 18; column <= 32; column++) {
                    sheet.getRow(rowIndex).getCell(column).setCellStyle(styles.input());
                }
            }
        } else {
            for (var candidate : data.candidates()) {
                add(sheet, styles, row(Step7ReviewLedgerWorkflowService.TEMPLATE_SCHEMA_VERSION, "", "", "V1.0", candidate.caseKey(), data.teamSha256(),
                        candidate.sourceRow(), OFFICIAL_DRAWING_FILE, OFFICIAL_DRAWING_SHEET, candidate.source(), candidate.target(),
                        candidate.sourceCellHints(), candidate.targetCellHints(),
                        candidate.connectorHints().stream().map(Step7ConnectionEvidenceService.ConnectorHint::shapeId).toList(),
                        candidate.quantityText(), candidate.quantity(), candidate.materialType(), 0, candidate.source(), candidate.target(),
                        candidate.materialType(), null, "", "", "", "", "", "", "", "", "", "", "예", "아니오"));
            }
        }
        widths(sheet, 22, 48, 68, 16, 32, 68, 14, 42, 18, 24, 24, 34, 34, 26, 23, 18, 22, 17,
                24, 24, 24, 18, 15, 19, 25, 30, 34, 70, 24, 22, 60, 28, 17, 24, 29);
        sheet.createFreezePane(0, 2);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(1,
                Math.max(1, sheet.getPhysicalNumberOfRows() - 1), 0, 34));
        if (reviewTemplate != null && !reviewTemplate.rows().isEmpty()) {
            var helper = sheet.getDataValidationHelper();
            int first = 2;
            int last = sheet.getPhysicalNumberOfRows() - 1;
            addListValidation(sheet, helper, new String[]{"UNREVIEWED", "REVIEWED_PROVISIONAL", "EVIDENCE_VERIFIED", "REJECTED", "NEEDS_MORE_INFO"}, first, last, 31);
            addListValidation(sheet, helper, new String[]{"FIELD_INSPECTION", "APPROVED_DRAWING_TRACE", "OFFICIAL_DRAWING_TRACE", "FLOW_METER_RECORD", "OTHER_DOCUMENT"}, first, last, 25);
            addListValidation(sheet, helper, new String[]{"YES", "NO"}, first, last, 24);
        }
    }

    private static String arrowDescription(Step7ConnectionEvidenceService.ConnectorHint hint) {
        return "head=" + safe(hint.headArrowType()) + ", tail=" + safe(hint.tailArrowType())
                + ", anchor orientation=" + hint.anchorOrientation() + " (논리 유향 확정 아님)";
    }

    private Styles createStyles(Workbook workbook) {
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
        CellStyle input = workbook.createCellStyle();
        input.cloneStyleFrom(normal);
        input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return new Styles(normal, header, input);
    }

    private void add(Sheet sheet, Styles styles, List<Object> values) {
        int rowNumber = sheet.getPhysicalNumberOfRows();
        Row row = sheet.createRow(rowNumber);
        row.setHeightInPoints(36);
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = values.get(i);
            if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
            else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else if (value instanceof Boolean bool) cell.setCellValue(bool);
            else if (value != null) cell.setCellValue(value.toString());
            boolean headerRow = rowNumber == 0 || (rowNumber == 1 && sheet.getSheetName().equals("담당자 검토대장"));
            cell.setCellStyle(headerRow ? styles.header() : styles.normal());
        }
        if (rowNumber == 0) sheet.createFreezePane(0, sheet.getSheetName().equals("담당자 검토대장") ? 2 : 1);
        if (sheet.getRow(0) != null && !sheet.getSheetName().equals("담당자 검토대장")) {
            int lastColumn = sheet.getRow(0).getLastCellNum() - 1;
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rowNumber, 0, lastColumn));
        }
    }

    private void addListValidation(Sheet sheet, org.apache.poi.ss.usermodel.DataValidationHelper helper,
                                   String[] choices, int firstRow, int lastRow, int column) {
        var constraint = helper.createExplicitListConstraint(choices);
        var address = new org.apache.poi.ss.util.CellRangeAddressList(firstRow, lastRow, column, column);
        var validation = helper.createValidation(constraint, address);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(false);
        sheet.addValidationData(validation);
    }

    private void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(widths[i], 120) * 256);
    }

    private List<Object> row(Object... values) { return Arrays.asList(values); }
    private static String safe(String value) { return value == null ? "미기록" : value; }
    private record Styles(CellStyle normal, CellStyle header, CellStyle input) { }
}
