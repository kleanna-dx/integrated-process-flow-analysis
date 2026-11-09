package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
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

/** Five-sheet report for read-only Step 7-5A parsing and Step 7-4A schema-v2 bridge results. */
@Component
public class Step7FieldReviewBridgeWorkbookExporter {
    public byte[] export(Step7FieldReviewBridgeService.BridgePreview preview,
                         Step7FieldReviewBridgeService.BridgeImportResult importResult,
                         List<Step7FieldReviewBridgeService.HistoryResult> history) {
        try(XSSFWorkbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Styles styles=styles(workbook);
            writeStatus(workbook,styles,preview,importResult);
            writeMappings(workbook,styles,preview);
            writeIssues(workbook,styles,preview);
            writeConversions(workbook,styles,preview);
            writeHistory(workbook,styles,history,preview,importResult);
            workbook.setActiveSheet(0);workbook.write(out);return out.toByteArray();
        } catch(Exception e) { throw new IllegalStateException("Unable to export Step 7-5B field review report",e); }
    }

    private void writeStatus(Workbook wb,Styles st,Step7FieldReviewBridgeService.BridgePreview p,
                             Step7FieldReviewBridgeService.BridgeImportResult result) {
        Sheet s=wb.createSheet("① 현장 확인 입력 현황");
        add(s,st,true,List.of("항목","결과","설명"));
        add(s,st,false,List.of("자료 구분","ACTUAL_INPUT_READ_ONLY", "Step 7-5A 현장 Excel을 읽기 전용으로 처리. 합성 TEST_ONLY 결과는 별도 시험으로 분리."));
        add(s,st,false,List.of("snapshot_id",p.snapshotId(),"현재 schema-v2 후보 snapshot"));
        add(s,st,false,List.of("기준 버전",p.baselineVersion(),"잠긴 V1.0 여부는 서버 snapshot과 대조"));
        add(s,st,false,List.of("기존 후보 총수",p.snapshotCandidateCount(),"기존 snapshot에 저장된 후보 수"));
        add(s,st,false,List.of("기존 후보 자동 매핑 성공",p.mappingSuccessCount(),"candidate_id/snapshot_id/case_key/원본행/endpoint 대조"));
        add(s,st,false,List.of("미매핑 입력",p.unmappedCount(),"새 후보 ID를 만들거나 기존 ID에 자동 연결하지 않음"));
        add(s,st,false,List.of("비후보 참고행",p.referenceOnlyCount(),"REFERENCE_ONLY 행은 기존 검토 후보로 취급하지 않음"));
        add(s,st,false,List.of("현장 Excel에 없는 기존 후보",p.missingFromFieldWorkbookCount(),"기존 snapshot 후보 중 이번 현장 범위에 포함되지 않은 수"));
        add(s,st,false,List.of("담당자 입력 행",p.inputRowCount(),"실제 현장 입력이 없으면 0"));
        add(s,st,false,List.of("입력 오류 행",p.inputErrorCount(),"저장 차단"));
        add(s,st,false,List.of("근거 부족 행",p.evidenceInsufficientCount(),"물질/유량/단위/기간/유량 출처/증빙 미완료"));
        add(s,st,false,List.of("식별·상태 충돌 행",p.conflictCount(),"snapshot/case/version/경로 충돌은 자동 연결 차단"));
        add(s,st,false,List.of("schema-v2 변환 준비",p.schemaV2ConvertibleCount(),"기존 Step 7-4A importer 검증을 통과한 행만"));
        add(s,st,false,List.of("schema-v2 미리보기 검증",p.schemaV2Valid()?"PASS":"BLOCKED",String.join("; ",p.schemaV2Validation().stream().map(Step7ReviewLedgerWorkflowService.ReviewIssue::code).toList())));
        add(s,st,false,List.of("저장 결과",result==null?"PREVIEW_ONLY":result.valid()?"VALID":"NOT_SAVED",result==null?"미리보기는 DB 쓰기 없음":result.notice()));
        add(s,st,false,List.of("실제 EVIDENCE_VERIFIED","0","현장 확인 전 자동 생성 금지"));
        add(s,st,false,List.of("실제 계산망 편입","0","Step 7-4B adapter 결과에 실제 자동 편입 없음"));
        add(s,st,false,List.of("공식 승인 관계 생성","아니오","process_relation/CONFIRMED는 수정하지 않음"));
        add(s,st,false,List.of("원본 upload SHA-256",p.sourceUploadSha256(),"중복 제출 탐지용 입력 파일 fingerprint"));
        add(s,st,false,List.of("snapshot source SHA-256",p.snapshotSourceSha256(),"팀원 원본과 snapshot 대조"));
        add(s,st,false,List.of("주의",p.notice(),"증빙 문서 ID의 존재만으로 증빙 진위나 실제 연결을 확인한 것으로 보지 않음"));
        widths(s,34,48,100);s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,s.getLastRowNum(),0,2));
    }

    private void writeMappings(Workbook wb,Styles st,Step7FieldReviewBridgeService.BridgePreview p) {
        Sheet s=wb.createSheet("② 기존 후보 자동 매핑 결과");
        add(s,st,true,List.of("Excel 행","후보 ID","case_key","snapshot_id","DB 원본 행","후보 출발","후보 도착","자동 매핑","결과 코드","상태 설명","경로 ID"));
        for(var r:p.mappingRows())add(s,st,false,List.of(r.excelRow(),r.candidateId(),r.caseKey(),r.snapshotId(),r.dbRow(),r.sourceEquipment(),r.targetEquipment(),r.mapped()?"예":"아니오",r.status(),r.message(),r.routeId()));
        widths(s,12,34,42,66,15,24,24,14,34,70,28);s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,s.getLastRowNum(),0,10));
    }

    private void writeIssues(Workbook wb,Styles st,Step7FieldReviewBridgeService.BridgePreview p) {
        Sheet s=wb.createSheet("③ 입력 오류 및 추가 확인 필요");
        add(s,st,true,List.of("Excel 행","후보 ID","열","오류/확인 코드","설명","분류"));
        if(p.validationIssues().isEmpty())add(s,st,false,List.of("","","","없음","담당자 입력이 없거나 확인된 오류가 없습니다. 빈 입력은 검토 완료가 아닙니다.","INFORMATION"));
        else for(var i:p.validationIssues())add(s,st,false,List.of(i.excelRow(),i.candidateId(),i.column(),i.code(),i.message(),i.severity()));
        for(var i:p.schemaV2Validation())add(s,st,false,List.of(i.row(),"","schema-v2:"+i.column(),i.code(),i.message(),"ERROR"));
        widths(s,12,34,34,44,100,22);s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,s.getLastRowNum(),0,5));
    }

    private void writeConversions(Workbook wb,Styles st,Step7FieldReviewBridgeService.BridgePreview p) {
        Sheet s=wb.createSheet("④ schema-v2 변환 결과");
        add(s,st,true,List.of("Excel 행","candidate_id","case_key","DB 원본 행","변환 상태","경로 ID","검토 결과","검증 코드","기준 버전","snapshot_id","변환 안내"));
        if(p.conversionRows().isEmpty())add(s,st,false,List.of("","","","","NO_FIELD_INPUT","","","","V1.0",p.snapshotId(),"담당자 입력이 없습니다. schema-v2 변환 행을 만들지 않았습니다."));
        else for(var r:p.conversionRows())add(s,st,false,List.of(r.excelRow(),r.candidateId(),r.caseKey(),r.dbRow(),r.status(),r.routeId(),r.reviewDecision(),r.issueCodes(),p.baselineVersion(),p.snapshotId(),"이 시트는 변환 결과 설명이며 업로드 입력 템플릿이 아닙니다."));
        widths(s,12,34,42,15,34,28,26,50,18,66,75);s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,s.getLastRowNum(),0,10));
    }

    private void writeHistory(Workbook wb,Styles st,List<Step7FieldReviewBridgeService.HistoryResult> history,
                              Step7FieldReviewBridgeService.BridgePreview p,Step7FieldReviewBridgeService.BridgeImportResult result) {
        Sheet s=wb.createSheet("⑤ 검토 이력 반영 결과");
        add(s,st,true,List.of("candidate_id","case_key","검토 상태","review_version","history row count","이번 Excel 입력 상태","업로드 반영 상태","공식 승인 관계 생성"));
        if(history.isEmpty())add(s,st,false,List.of("","","UNREVIEWED","0","0","NO_FIELD_INPUT",result==null?"미리보기만":"저장 안 함","아니오"));
        else for(var r:history)add(s,st,false,List.of(r.candidateId(),r.caseKey(),r.reviewState(),r.reviewVersion(),r.historyCount(),r.fieldRowStatus(),result==null?"미리보기만":result.valid()?"반영 완료/중복 무시":"저장 안 함","아니오"));
        widths(s,34,42,28,18,22,34,32,28);s.createFreezePane(0,1);s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,s.getLastRowNum(),0,7));
    }

    private void add(Sheet sheet,Styles styles,boolean header,List<?> values) {
        Row row=sheet.createRow(sheet.getPhysicalNumberOfRows());row.setHeightInPoints(header?32:36);
        for(int i=0;i<values.size();i++) {
            Cell c=row.createCell(i);Object value=values.get(i);
            if(value instanceof Number n)c.setCellValue(n.doubleValue());else if(value!=null)c.setCellValue(value.toString());
            c.setCellStyle(header?styles.header():styles.body());
        }
    }
    private Styles styles(Workbook wb) {
        Font normal=wb.createFont();normal.setFontName("맑은 고딕");normal.setColor(IndexedColors.BLACK.getIndex());normal.setFontHeightInPoints((short)10);
        Font bold=wb.createFont();bold.setFontName("맑은 고딕");bold.setColor(IndexedColors.BLACK.getIndex());bold.setBold(true);bold.setFontHeightInPoints((short)10);
        CellStyle body=wb.createCellStyle();body.setFont(normal);body.setWrapText(true);body.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        CellStyle header=wb.createCellStyle();header.setFont(bold);header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setBorderBottom(BorderStyle.THIN);header.setWrapText(true);header.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        return new Styles(body,header);
    }
    private void widths(Sheet s,int... widths){for(int i=0;i<widths.length;i++)s.setColumnWidth(i,Math.min(widths[i],120)*256);}
    private record Styles(CellStyle body,CellStyle header){}
}
