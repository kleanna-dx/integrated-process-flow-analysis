package com.example.processautomation.excel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** Review-only workbook for read-only inventories of locked official process diagrams. */
@Component
public class ProcessDiagramInventoryWorkbookExporter {
    public byte[] export(List<ProcessDiagramStructureAnalyzer.WorkbookReport> reports) {
        return export(reports, Map.of());
    }

    /** Export completed reports and explicit batch-level failures without upgrading unknown sheets to verified. */
    public byte[] export(List<ProcessDiagramStructureAnalyzer.WorkbookReport> reports, Map<String, String> failedSheets) {
        if (reports == null || failedSheets == null) throw new IllegalArgumentException("Inventory results are required");
        if (reports.isEmpty() && failedSheets.isEmpty()) throw new IllegalArgumentException("No completed or failed inventory sheets are available");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            CellStyle header = headerStyle(workbook);
            Sheet summary = workbook.createSheet("공정도 요약");
            write(summary, header, new String[]{"공정", "파일", "SHA-256", "시트 수", "설비 박스 후보", "설비 ID 텍스트", "위치 후보",
                    "편집 VERIFIED", "편집 REVIEW_REQUIRED", "편집 UNSUPPORTED", "그림 도형", "연결선", "화살표/바인딩", "병합 범위", "비어있지 않은 셀", "분석 정책"},
                    reports.stream().map(report -> new Object[]{report.processCode(), report.filename(), report.sha256(),
                            report.sheetCount(), report.equipmentCandidates().stream().filter(item -> item.topLeftCell() != null).count(),
                            report.equipmentCandidates().size(), report.locationCandidates().size(),
                            report.locationCandidates().stream().filter(item -> "VERIFIED".equals(item.editabilityStatus())).count(),
                            report.locationCandidates().stream().filter(item -> "REVIEW_REQUIRED".equals(item.editabilityStatus())).count(),
                            report.locationCandidates().stream().filter(item -> "UNSUPPORTED".equals(item.editabilityStatus())).count(),
                            report.shapeCount(), report.connectorCount(), report.explicitConnectorBindings(), report.mergedRangeCount(),
                            report.nonEmptyCellCount(), report.interpretationPolicy()}).toList());
            widths(summary, 14, 58, 68, 12, 18, 18, 16, 15, 20, 16, 14, 14, 18, 16, 20, 70);

            Sheet sheets = workbook.createSheet("시트·사용영역");
            List<Object[]> sheetRows = reports.stream().flatMap(report -> report.sheets().stream().map(sheet -> new Object[]{
                    report.processCode(), report.filename(), sheet.sheetIndex(), sheet.sheetName(), sheet.visibility(),
                    sheet.usedRange(), sheet.nonEmptyCellCount(), sheet.physicalRowCount(), sheet.usedLastRow(), sheet.usedLastColumn(),
                    sheet.formulaCellCount(), sheet.styledNonEmptyCellCount(), sheet.mergedRanges().size(),
                    String.join("; ", sheet.mergedRanges()), sheet.customHeightRows(), sheet.hiddenRows(),
                    sheet.columns().size(), sheet.drawingPart(), sheet.drawingObjectCount(), sheet.shapeCount(),
                    sheet.connectorCount(), sheet.explicitBindingObjectCount(), sheet.equipmentIdentifierCandidates(),
                    sheet.locationCandidates()})).toList();
            write(sheets, header, new String[]{"공정", "파일", "시트 순번", "시트", "표시상태", "사용영역(셀값)", "비어있지 않은 셀", "물리 행 수",
                    "마지막 사용 행", "마지막 사용 열(0-based)", "수식 셀", "서식 있는 셀", "병합 수", "병합 범위",
                    "사용행 맞춤높이", "숨김 행", "열 프로필 수", "DrawingML 파트", "DrawingML 객체", "도형", "연결선",
                    "연결 바인딩", "설비 ID 후보", "위치 후보"}, sheetRows);
            widths(sheets, 14, 58, 12, 30, 12, 24, 18, 16, 16, 20, 12, 14, 12, 64, 16, 12, 16, 30, 16, 12, 12, 16, 14, 14);

            Sheet equipment = workbook.createSheet("설비박스 후보");
            List<Object[]> equipmentRows = reports.stream().flatMap(report -> report.equipmentCandidates().stream().map(item -> new Object[]{
                    report.processCode(), report.filename(), item.sheetName(), item.equipmentIdCandidate(),
                    String.join(", ", item.idTextCells()), item.topLeftCell(), item.bottomRightCell(), item.facilityNameCandidate(),
                    item.firstColumn(), item.firstRow(), item.lastColumn(), item.lastRow(), item.boxHeight(),
                    item.status(), item.reason(), item.styleUsage().toString(), String.join("; ", item.overlappingMergedRanges())})).toList();
            write(equipment, header, new String[]{"공정", "파일", "시트", "설비번호 후보", "텍스트 셀 위치", "박스 좌상단", "박스 우하단", "설비명 후보",
                    "좌열(0-based)", "시작 행", "우열(0-based)", "끝 행", "박스 높이", "분석 상태", "검토 사유", "박스 셀 스타일빈도", "겹치는 병합 범위"}, equipmentRows);
            widths(equipment, 14, 58, 30, 22, 34, 16, 16, 38, 14, 14, 14, 14, 14, 32, 72, 34, 45);

            Sheet locations = workbook.createSheet("위치후보");
            List<Object[]> locationRows = reports.stream().flatMap(report -> report.locationCandidates().stream().map(item -> new Object[]{
                    report.processCode(), report.filename(), item.locationId(), item.sheetName(), item.precedingEquipmentCandidate(),
                    item.followingEquipmentCandidate(), item.candidateCellRange(), item.gapRows(), item.sourceBoxHeight(),
                    item.drawingPart(), item.connectorCount(), item.explicitConnectorBindings(), item.drawingObjectsCrossingBand(),
                    String.join("; ", item.overlappingMergedRanges()), item.automaticEditAllowed(), item.status(),
                    item.editabilityStatus(), item.connectionEvidenceStatus(), item.editabilityReason(),
                    String.join("; ", item.reviewReasons())})).toList();
            write(locations, header, new String[]{"공정", "파일", "후보 ID", "시트", "선행 설비 후보", "후행 설비 후보", "후보 셀 범위(근사)",
                    "중간 빈행 수", "기준 박스 높이", "DrawingML 파트", "연결선 수", "명시 바인딩 수", "후보대 교차 객체",
                    "겹치는 병합", "자동 편집 허용", "후보 상태", "기술 편집 상태", "실제 연결 증빙 상태", "편집 차단 사유", "검토 사유"}, locationRows);
            widths(locations, 14, 58, 52, 30, 22, 22, 24, 14, 16, 30, 14, 16, 18, 40, 16, 22, 20, 42, 72, 80);

            Sheet drawing = workbook.createSheet("DrawingML 객체");
            List<Object[]> drawingRows = reports.stream().flatMap(report -> report.sheets().stream().flatMap(sheet ->
                    sheet.drawingObjects().stream().map(item -> new Object[]{report.processCode(), report.filename(), sheet.sheetName(),
                            item.objectIndex(), item.objectType(), item.shapeId(), item.objectName(), item.geometryPreset(),
                            item.textContent(), item.startColumn(), item.startRow(), item.startColumnOffset(), item.startRowOffset(),
                            item.endColumn(), item.endRow(), item.endColumnOffset(), item.endRowOffset(),
                            item.startConnectedShapeId(), item.endConnectedShapeId(), item.headArrowType(), item.tailArrowType(),
                            item.transformXEmu(), item.transformYEmu(), item.transformWidthEmu(), item.transformHeightEmu(),
                            item.startAnchorXEmu(), item.startAnchorYEmu(), item.endAnchorXEmu(), item.endAnchorYEmu()}))).toList();
            write(drawing, header, new String[]{"공정", "파일", "시트", "객체 순번", "유형", "Shape ID", "객체명", "기하", "텍스트",
                    "시작열", "시작행", "시작열 오프셋", "시작행 오프셋", "끝열", "끝행", "끝열 오프셋", "끝행 오프셋",
                    "시작 연결 Shape", "끝 연결 Shape", "화살표 Head", "화살표 Tail", "Transform X EMU", "Transform Y EMU",
                    "폭 EMU", "높이 EMU", "앵커 시작 X", "앵커 시작 Y", "앵커 끝 X", "앵커 끝 Y"}, drawingRows);
            widths(drawing, 14, 58, 30, 12, 16, 12, 26, 18, 55, 12, 12, 18, 18, 12, 12, 18, 18, 20, 20, 18, 18, 20, 20, 20, 20, 20, 20, 20, 20);

            Sheet rows = workbook.createSheet("행열·서식 프로필");
            List<Object[]> rowRows = reports.stream().flatMap(report -> report.sheets().stream().flatMap(sheet ->
                    sheet.nonEmptyRows().stream().map(row -> new Object[]{report.processCode(), report.filename(), sheet.sheetName(),
                            row.rowNumber(), row.firstNonEmptyColumn(), row.lastNonEmptyColumn(), row.nonEmptyCellCount(),
                            row.heightPoints(), row.hidden()}))).toList();
            write(rows, header, new String[]{"공정", "파일", "시트", "행 번호", "첫 사용 열", "마지막 사용 열", "사용 셀 수", "행 높이(pt)", "숨김"}, rowRows);
            widths(rows, 14, 58, 30, 12, 14, 16, 16, 16, 12);

            Sheet columns = workbook.createSheet("열너비·숨김");
            List<Object[]> columnRows = reports.stream().flatMap(report -> report.sheets().stream().flatMap(sheet ->
                    sheet.columns().stream().map(column -> new Object[]{report.processCode(), report.filename(), sheet.sheetName(),
                            column.zeroBasedIndex(), column.columnLetter(), column.widthUnits(), column.widthPixels(), column.hidden()}))).toList();
            write(columns, header, new String[]{"공정", "파일", "시트", "열(0-based)", "열문자", "폭 단위", "폭(px)", "숨김"}, columnRows);
            widths(columns, 14, 58, 30, 15, 14, 14, 14, 12);

            Sheet failures = workbook.createSheet("배치 실패·미분석");
            List<Object[]> failureRows = failedSheets.entrySet().stream().map(entry -> new Object[]{entry.getKey(), entry.getValue()}).toList();
            write(failures, header, new String[]{"문서·시트 키", "실패 사유"}, failureRows);
            widths(failures, 64, 100);
            workbook.setActiveSheet(0);
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to export read-only process diagram inventory", exception);
        }
    }

    private void write(Sheet sheet, CellStyle header, String[] headings, List<Object[]> values) {
        createRow(sheet, header, 0, headings);
        for (int index = 0; index < values.size(); index++) createRow(sheet, null, index + 1, values.get(index));
        sheet.createFreezePane(0, 1);
        if (headings.length > 0) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,
                Math.max(0, values.size()), 0, headings.length - 1));
    }

    private void createRow(Sheet sheet, CellStyle header, int rowNumber, Object[] values) {
        Row row = sheet.createRow(rowNumber);
        for (int column = 0; column < values.length; column++) {
            var cell = row.createCell(column);
            Object value = values[column];
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else if (value instanceof Boolean bool) cell.setCellValue(bool);
            else if (value != null) cell.setCellValue(value.toString());
            if (header != null) cell.setCellStyle(header);
        }
    }

    private void widths(Sheet sheet, int... widths) {
        for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setWrapText(true);
        return style;
    }
}
