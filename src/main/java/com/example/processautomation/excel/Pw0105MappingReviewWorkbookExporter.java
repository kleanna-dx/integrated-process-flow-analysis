package com.example.processautomation.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** Produces a review-only workbook for the 19 PW-01-05 master-to-diagram mappings. */
@Component
public class Pw0105MappingReviewWorkbookExporter {
    private static final List<String> FIELDS = List.of("managementNo", "equipmentName", "businessItemNo", "capacity", "quantity");
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "managementNo", "설비번호", "equipmentName", "설비명", "businessItemNo", "Item No.",
            "capacity", "용량·단위", "quantity", "설비 수량");

    public byte[] export(List<Map<String, Object>> targets) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle title = titleStyle(workbook);
            CellStyle header = headerStyle(workbook);
            CellStyle body = bodyStyle(workbook);
            CellStyle input = inputStyle(workbook);
            addMappingSheet(workbook, targets, title, header, body);
            addFieldAssessmentSheet(workbook, targets, title, header, body);
            addDrawingEvidenceSheet(workbook, targets, title, header, body);
            addReviewerSheet(workbook, targets, title, header, body, input);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("PW-01-05 mapping review workbook could not be written", exception);
        }
    }

    private void addMappingSheet(Workbook workbook, List<Map<String, Object>> targets,
                                 CellStyle title, CellStyle header, CellStyle body) {
        Sheet sheet = workbook.createSheet("설비19건 매핑");
        addTitle(sheet, title, "PW-01-05 설비 19건 도면 매핑 검토 — TEST_ONLY");
        addNote(sheet, body, 1, "자동 매핑 근거를 정리한 검토 파일입니다. 현장 확인·공식 승인 또는 물질수지 계산을 의미하지 않습니다.");
        List<String> columns = List.of("마스터 설비번호", "마스터 명칭", "마스터 Item No.", "마스터 용량", "마스터 단위", "마스터 수량",
                "마스터 출처 시트", "마스터 원본행", "도면 설비번호", "도면 명칭", "도면 Item No.", "도면 용량·단위", "도면 수량", "도면 시트", "ID 셀/상자 범위",
                "ID 전체 표시 위치", "상자 밖 ID 위치", "표시 위치 역할·앵커", "상자 안 다른 마스터 ID", "마스터-도면 차이", "필드별 수정 가능", "겹치는 DrawingML ID",
                "커넥터/화살표 참고", "매핑 판정", "차단·검토 이유", "추가 확인 사항");
        writeHeader(sheet, 3, columns, header);
        int rowIndex = 4;
        for (Map<String, Object> target : targets) {
            Map<String, String> master = map(target, "masterValues");
            Map<String, String> diagram = map(target, "diagramValues");
            Map<String, Map<String, Object>> fields = nestedMap(target, "fieldAssessments");
            List<Map<String, Object>> drawings = listOfMaps(target, "drawingObjectsAtOrAcrossEquipmentBox");
            Row row = sheet.createRow(rowIndex++);
            List<String> values = List.of(
                    text(target.get("managementNo")), text(target.get("masterEquipmentName")), text(target.get("masterItemNo")),
                    text(target.get("masterCapacityValue")), text(target.get("masterCapacityUnit")), text(target.get("masterQuantity")),
                    text(target.get("masterSourceSheet")), text(target.get("masterSourceRow")),
                    diagram.getOrDefault("managementNo", ""), diagram.getOrDefault("equipmentName", ""),
                    diagram.getOrDefault("businessItemNo", ""), diagram.getOrDefault("capacity", ""), diagram.getOrDefault("quantity", ""),
                    "PW-01-05", text(target.get("equipmentBoxIdCell")) + " / " + text(target.get("cellRange")),
                    join(target.get("diagramIdOccurrences")), join(target.get("otherIdOccurrencesOutsideBox")),
                    occurrenceSummary(target), join(target.get("otherMasterIdsInsideBox")), join(target.get("masterDiagramFieldMismatches")), fieldSummary(fields), drawingIds(drawings),
                    connectorSummary(drawings), text(target.get("status")), join(target.get("reviewRequiredItems")),
                    join(target.get("reviewNotes")));
            writeRow(row, values, body);
        }
        formatSheet(sheet, columns.size(), rowIndex - 1, new int[]{20, 34, 20, 15, 15, 14, 18, 13, 20, 34, 20, 22, 14, 14, 25, 28, 28, 42, 30, 45, 38, 20, 55, 28, 55, 55});
    }

    private void addFieldAssessmentSheet(Workbook workbook, List<Map<String, Object>> targets,
                                         CellStyle title, CellStyle header, CellStyle body) {
        Sheet sheet = workbook.createSheet("필드별 수정판정");
        addTitle(sheet, title, "PW-01-05 필드별 셀 근거 및 수정 가능성");
        addNote(sheet, body, 1, "CELL_TEXT_TEST_ONLY는 셀 위치가 구조적으로 특정됐다는 뜻일 뿐, 값의 정답·현장 사실·공식 승인을 뜻하지 않습니다.");
        List<String> columns = List.of("설비번호", "필드", "마스터 표시값", "마스터 원문값", "도면 값", "셀 주소", "병합 범위", "셀 존재", "수식 셀",
                "마스터 일치", "정체성 검증", "기술적 수정 가능", "수정 상태", "차단/검토 이유");
        writeHeader(sheet, 3, columns, header);
        int rowIndex = 4;
        for (Map<String, Object> target : targets) {
            Map<String, Map<String, Object>> fields = nestedMap(target, "fieldAssessments");
            Map<String, String> mismatches = new LinkedHashMap<>();
            for (String mismatch : strings(target.get("masterDiagramFieldMismatches"))) {
                int sep = mismatch.indexOf(' ');
                if (sep > 0) mismatches.put(mismatch.substring(0, sep), mismatch.substring(sep + 1));
            }
            for (String field : FIELDS) {
                Map<String, Object> assessment = fields.getOrDefault(field, Map.of());
                String reason = mismatches.getOrDefault(field, "");
                if (!Boolean.TRUE.equals(target.get("identityVerified"))) reason = append(reason, "설비 정체성/상자 위치 미확정");
                if (!Boolean.TRUE.equals(assessment.get("modifiable"))) reason = append(reason, "셀/필드 위치를 수정 대상으로 확정할 근거 부족");
                Row row = sheet.createRow(rowIndex++);
                writeRow(row, List.of(text(target.get("managementNo")), FIELD_LABELS.get(field),
                        text(assessment.get("masterValue")), text(assessment.get("masterRawValue")), text(assessment.get("diagramValue")),
                        text(assessment.get("cell")), text(assessment.get("mergedRange")),
                        text(assessment.get("cellExists")), text(assessment.get("formulaCell")),
                        text(assessment.get("masterDiagramMatch")), text(target.get("identityVerified")),
                        text(assessment.get("modifiable")), text(assessment.get("editCapability")), reason), body);
            }
        }
        formatSheet(sheet, columns.size(), rowIndex - 1, new int[]{20, 20, 34, 24, 34, 16, 20, 14, 14, 16, 18, 18, 35, 60});
    }

    private void addDrawingEvidenceSheet(Workbook workbook, List<Map<String, Object>> targets,
                                         CellStyle title, CellStyle header, CellStyle body) {
        Sheet sheet = workbook.createSheet("DrawingML 증거");
        addTitle(sheet, title, "설비 상자 주변 DrawingML 객체 — 앵커 관찰, 논리 연결 미승인");
        addNote(sheet, body, 1, "앵커 겹침·화살표는 시각적 근접 증거일 뿐 연결 방향·유량·설비 관계 확정이 아닙니다. shape binding이 없는 경우 특히 REVIEW_REQUIRED입니다.");
        List<String> columns = List.of("설비번호", "설비 상자 ID 셀", "DrawingML 파트", "Shape ID", "도형명", "객체 유형",
                "도형 텍스트", "Anchor 시작", "Anchor 끝", "시작 화살표", "끝 화살표", "시작 연결 Shape ID",
                "끝 연결 Shape ID", "판정");
        writeHeader(sheet, 3, columns, header);
        int rowIndex = 4;
        for (Map<String, Object> target : targets) {
            List<Map<String, Object>> drawings = listOfMaps(target, "drawingObjectsAtOrAcrossEquipmentBox");
            if (drawings.isEmpty()) {
                Row row = sheet.createRow(rowIndex++);
                writeRow(row, List.of(text(target.get("managementNo")), text(target.get("equipmentBoxIdCell")),
                        text(target.get("drawingPartName")), "", "", "", "", "", "", "", "", "", "", "객체 없음/비매핑"), body);
            }
            for (Map<String, Object> drawing : drawings) {
                Row row = sheet.createRow(rowIndex++);
                writeRow(row, List.of(text(target.get("managementNo")), text(target.get("equipmentBoxIdCell")),
                        text(target.get("drawingPartName")), text(drawing.get("shapeId")), text(drawing.get("objectName")),
                        text(drawing.get("objectType")), text(drawing.get("text")), text(drawing.get("anchorFrom")),
                        text(drawing.get("anchorTo")), text(drawing.get("headArrowType")), text(drawing.get("tailArrowType")),
                        text(drawing.get("startConnectedShapeId")), text(drawing.get("endConnectedShapeId")),
                        text(drawing.get("relationAssessment"))), body);
            }
        }
        formatSheet(sheet, columns.size(), rowIndex - 1, new int[]{20, 18, 32, 12, 30, 18, 40, 16, 16, 16, 16, 20, 20, 42});
    }

    private void addReviewerSheet(Workbook workbook, List<Map<String, Object>> targets,
                                  CellStyle title, CellStyle header, CellStyle body, CellStyle input) {
        Sheet sheet = workbook.createSheet("담당자 확인 입력");
        addTitle(sheet, title, "담당자 확인 및 증빙 입력 — 공란은 미확인·미승인");
        addNote(sheet, body, 1, "확인 여부, 증빙, 확인자 등을 작성해도 공식 승인 상태는 자동으로 변경되지 않습니다. 승인 절차는 별도 관리 대상입니다.");
        List<String> columns = List.of("설비번호", "대상 필드", "기존 도면값", "공식 마스터값", "담당자 제안값", "도면 위치/셀",
                "현장 확인 완료(예/아니오)", "증빙 유형", "증빙 ID/파일", "확인자", "확인일", "검토 메모", "기술적 셀 수정 가능성", "공식 승인 상태");
        writeHeader(sheet, 3, columns, header);
        int rowIndex = 4;
        for (Map<String, Object> target : targets) {
            Map<String, Map<String, Object>> fields = nestedMap(target, "fieldAssessments");
            for (String field : FIELDS) {
                Map<String, Object> assessment = fields.getOrDefault(field, Map.of());
                Row row = sheet.createRow(rowIndex++);
                List<String> values = List.of(text(target.get("managementNo")), FIELD_LABELS.get(field),
                        text(assessment.get("diagramValue")), text(assessment.get("masterValue")), "",
                        text(assessment.get("cell")) + (text(assessment.get("mergedRange")).isBlank() ? "" : " / " + text(assessment.get("mergedRange"))),
                        "", "", "", "", "", "", text(assessment.get("editCapability")), "미승인");
                writeRow(row, values, body);
                for (int column = 4; column <= 11; column++) row.getCell(column).setCellStyle(input);
            }
        }
        formatSheet(sheet, columns.size(), rowIndex - 1, new int[]{20, 20, 32, 32, 32, 26, 24, 24, 30, 22, 16, 42, 35, 18});
    }

    private CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        var font = workbook.createFont(); font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 15);
        font.setBold(true); font.setColor(IndexedColors.BLACK.getIndex()); style.setFont(font);
        style.setWrapText(true); return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = bodyStyle(workbook);
        var font = workbook.createFont(); font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 10);
        font.setBold(true); font.setColor(IndexedColors.BLACK.getIndex()); style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN); return style;
    }

    private CellStyle bodyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        var font = workbook.createFont(); font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.BLACK.getIndex()); style.setFont(font);
        style.setWrapText(true); style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.HAIR); return style;
    }

    private CellStyle inputStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.cloneStyleFrom(bodyStyle(workbook));
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN); style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN); style.setBorderTop(BorderStyle.THIN); return style;
    }

    private void addTitle(Sheet sheet, CellStyle style, String value) {
        Row row = sheet.createRow(0); row.setHeightInPoints(28); row.createCell(0).setCellValue(value); row.getCell(0).setCellStyle(style);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 8));
    }

    private void addNote(Sheet sheet, CellStyle style, int rowIndex, String value) {
        Row row = sheet.createRow(rowIndex); row.setHeightInPoints(32); row.createCell(0).setCellValue(value); row.getCell(0).setCellStyle(style);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIndex, rowIndex, 0, 8));
    }

    private void writeHeader(Sheet sheet, int rowIndex, List<String> values, CellStyle style) {
        Row row = sheet.createRow(rowIndex); row.setHeightInPoints(34);
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column)); row.getCell(column).setCellStyle(style);
        }
    }

    private void writeRow(Row row, List<String> values, CellStyle style) {
        row.setHeightInPoints(36);
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column)); row.getCell(column).setCellStyle(style);
        }
    }

    private void formatSheet(Sheet sheet, int columnCount, int lastRow, int[] widths) {
        sheet.createFreezePane(0, 4);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(3, Math.max(3, lastRow), 0, columnCount - 1));
        for (int column = 0; column < Math.min(columnCount, widths.length); column++) sheet.setColumnWidth(column, widths[column] * 256);
        sheet.setDisplayGridlines(false);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setFitToPage(true);
    }

    private String fieldSummary(Map<String, Map<String, Object>> fields) {
        List<String> values = new ArrayList<>();
        for (String field : FIELDS) {
            Map<String, Object> item = fields.getOrDefault(field, Map.of());
            values.add(FIELD_LABELS.get(field) + "=" + (Boolean.TRUE.equals(item.get("modifiable")) ? "가능" : "차단"));
        }
        return String.join("; ", values);
    }

    private String occurrenceSummary(Map<String, Object> target) {
        List<String> summary = new ArrayList<>();
        for (Map<String, Object> occurrence : listOfMaps(target, "diagramIdOccurrenceDetails")) {
            List<Map<String, Object>> overlaps = listOfMaps(occurrence, "drawingMlAnchorOverlaps");
            String shapeIds = overlaps.stream().map(item -> text(item.get("shapeId"))).distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            String entry = text(occurrence.get("cell")) + "=" + text(occurrence.get("roleAssessment"));
            if (!shapeIds.isBlank()) entry += " shape=" + shapeIds;
            summary.add(entry);
        }
        return String.join("; ", summary);
    }

    private String drawingIds(List<Map<String, Object>> drawings) {
        return drawings.stream().map(item -> text(item.get("shapeId"))).distinct().collect(java.util.stream.Collectors.joining(", "));
    }

    private String connectorSummary(List<Map<String, Object>> drawings) {
        return drawings.stream().filter(item -> "CONNECTOR".equals(item.get("objectType"))).map(item ->
                text(item.get("shapeId")) + " " + text(item.get("anchorFrom")) + "→" + text(item.get("anchorTo"))
                        + " arrow=" + text(item.get("tailArrowType")) + " [시각 근접만]")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private String join(Object value) { return strings(value).stream().collect(java.util.stream.Collectors.joining("; ")); }
    private List<String> strings(Object value) {
        if (value instanceof List<?> list) return list.stream().map(this::text).toList();
        return value == null ? List.of() : List.of(text(value));
    }

    private Map<String, String> map(Map<String, Object> source, String key) {
        Object value = source.get(key); Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> entries) entries.forEach((k, v) -> result.put(String.valueOf(k), text(v)));
        return result;
    }

    private Map<String, Map<String, Object>> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key); Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> entries) entries.forEach((k, v) -> {
            if (v instanceof Map<?, ?> nested) {
                Map<String, Object> item = new LinkedHashMap<>(); nested.forEach((innerKey, innerValue) -> item.put(String.valueOf(innerKey), innerValue));
                result.put(String.valueOf(k), item);
            }
        });
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Map<String, Object> source, String key) {
        Object value = source.get(key); List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> values) {
            Map<String, Object> row = new LinkedHashMap<>(); values.forEach((k, v) -> row.put(String.valueOf(k), v)); result.add(row);
        }
        return result;
    }

    private String append(String left, String right) { return left == null || left.isBlank() ? right : left + "; " + right; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
