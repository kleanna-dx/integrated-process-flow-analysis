package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Six-sheet route-aware field intake. Existing schema-v2 files remain a separate contract. */
@Component
public class Step7WholeNetworkReviewWorkbookExporter {
    public static final List<String> SHEETS = List.of("① 전체 연결 후보 33건", "② 배관 경로 및 순환 확인",
            "③ 유량·단위·기준 기간 확인", "④ 증빙자료 및 담당자 검토", "⑤ 입력 오류 및 추가 확인 필요",
            "⑥ 프로그램 재업로드 안내");
    public static final Set<Integer> PREVIOUSLY_MISSING_ROWS = Set.of(2621, 2623, 2636, 2637, 2689, 2690, 2691, 2693);
    private static final List<String> EVIDENCE_TYPES = List.of("OFFICIAL_DRAWING_TRACE", "APPROVED_PID",
            "FIELD_INSPECTION", "FLOW_METER_RECORD", "CALCULATION_BASIS", "OTHER_DOCUMENT");
    private static final List<String> REVIEW_STATES = List.of("REVIEWED_PROVISIONAL", "EVIDENCE_VERIFIED",
            "NEEDS_MORE_INFO", "REJECTED");

    public byte[] export(Step7ConnectionEvidenceService.EvidenceWorkbookData data,
                         Step7ReviewLedgerWorkflowService.ReviewTemplate template) {
        if (data == null || template == null || template.rows().size() != 33) {
            throw new IllegalArgumentException("The locked 33-candidate snapshot is required");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = styles(wb);
            writeCandidates(wb, styles, data, template);
            writeRoutes(wb, styles, template);
            writeQuantities(wb, styles, template);
            writeReview(wb, styles, template);
            writeIssues(wb, styles, template);
            writeInstructions(wb, styles, template);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create six-sheet Step 7-6A review workbook", e);
        }
    }

    private void writeCandidates(Workbook wb, Styles st, Step7ConnectionEvidenceService.EvidenceWorkbookData data,
                                 Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(0));
        header(s,st,"후보 및 원본 위치 정보는 자동 입력된 참고값이며 연결 승인값이 아닙니다.",
                "candidate_id","case_key","snapshot_id","source_sha256","db_source_row","source_equipment","target_equipment",
                "official_drawing_file","official_drawing_sheet","source_cell_hint","target_cell_hint","connector_candidate_ids",
                "origin_class","source_db_mass_raw_reference","source_db_mass_value_reference","source_db_material_type_reference",
                "candidate_status","evidence_status","calculation_included");
        for(var r:t.rows()) {
            var c=data.candidates().stream().filter(x->x.caseKey().equals(r.candidateId())).findFirst().orElseThrow();
            add(s,st,r.candidateId(),r.caseKey(),r.snapshotId(),r.sourceSha256(),r.dbSourceRow(),r.candidateSource(),r.candidateTarget(),
                    r.drawingFile(),r.drawingSheet(),r.sourceCell(),r.targetCell(),r.connectorIds(),
                    PREVIOUSLY_MISSING_ROWS.contains(r.dbSourceRow())?"기존 7-5A 누락 8건":"기존 7-5A 포함 25건",
                    r.sourceMassRaw(),r.sourceMassValue(),r.sourceMaterialType(),c.candidateStatus(),c.evidenceStatus(),"아니오");
        }
        widths(s,22,47,66,66,14,24,24,42,20,36,36,30,27,24,24,24,30,28,18);
    }

    private void writeRoutes(Workbook wb, Styles st, Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(1));
        header(s,st,"미확정 route_id는 공란 유지. 여러 경로는 route_id별로 행을 추가하고 endpoint 쌍으로 합치지 마십시오.",
                "candidate_id","case_key","snapshot_id","expected_review_version","route_id","source_equipment","target_equipment",
                "movement_type","direction_confirmed","material_type","branch_merge_type","recycle_path_id","boundary_scope_id",
                "path_evidence_reference","path_description","scope_expected_route_count","topology_confirmed","scope_all_inflows","scope_all_outflows","scope_branches_merges","scope_internal_recycles",
                "scope_interprocess_moves","scope_external_boundaries","scope_generation_consumption","scope_unit_period","scope_baseline_time");
        for(var r:t.rows()){
            Object[] values=new Object[26];java.util.Arrays.fill(values,"");
            values[0]=r.candidateId();values[1]=r.caseKey();values[2]=r.snapshotId();values[3]=r.expectedVersion();
            values[5]=r.candidateSource();values[6]=r.candidateTarget();values[15]=null;
            add(s,st,values);
        }
        list(s,7,new String[]{"EXTERNAL_INFLOW","EXTERNAL_OUTFLOW","INTERPROCESS_MOVE","INTERNAL_RECYCLE"});
        list(s,10,new String[]{"NONE","BRANCH","MERGE","BRANCH_AND_MERGE","UNKNOWN"});
        widths(s,42,48,68,19,26,24,24,26,22,25,28,24,24,45,60,22,22,23,23,25,26,26,28,31,23,23);
    }

    private void writeQuantities(Workbook wb, Styles st, Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(2));
        header(s,st,"물량은 route별 별도 입력. 원본 DB 수치는 참고용이며 단위·기간을 추정하거나 공란을 0으로 바꾸지 않습니다.",
                "candidate_id","case_key","snapshot_id","route_id","source_db_mass_raw_reference","quantity","unit","period",
                "quantity_evidence_reference","baseline_time_reference");
        for(var r:t.rows())add(s,st,r.candidateId(),r.caseKey(),r.snapshotId(),"",r.sourceMassRaw(),null,"","","","");
        widths(s,42,48,68,26,31,19,18,27,45,32);
    }

    private void writeReview(Workbook wb, Styles st, Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(3));
        header(s,st,"모든 후보를 유지합니다. 증빙 식별자만으로 EVIDENCE_VERIFIED가 되지 않으며 공식 CONFIRMED 승인은 생성되지 않습니다.",
                "candidate_id","case_key","snapshot_id","source_sha256","expected_review_version","physical_connection_confirmed",
                "review_material_type","review_actual_quantity","review_unit","review_period","evidence_type","evidence_identifier","route_id",
                "evidence_reference","evidence_location","verification_method","verified_by","verified_at","verification_comment",
                "review_evidence","reviewer_name","reviewed_date","review_comment","review_decision","official_approval_created");
        for(var r:t.rows()){
            Object[] values=new Object[25];java.util.Arrays.fill(values,"");
            values[0]=r.candidateId();values[1]=r.caseKey();values[2]=r.snapshotId();values[3]=r.sourceSha256();values[4]=r.expectedVersion();
            values[7]=null;values[24]="아니오";add(s,st,values);
        }
        list(s,5,new String[]{"YES","NO"});
        list(s,10,EVIDENCE_TYPES.toArray(String[]::new));
        list(s,22,REVIEW_STATES.toArray(String[]::new));
        widths(s,42,48,68,68,22,29,25,19,18,26,28,30,42,42,30,24,26,54,60,24,22,52,28,25);
    }

    private void writeIssues(Workbook wb, Styles st, Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(4));
        header(s,st,"공식 V1.0 원수치·팀원 DB 원수치·담당자 수치·기준 잔차·변경 후 잔차는 분리 기록하며 자료 차이만으로 오류를 확정하지 않습니다.",
                "record_class","candidate_or_reference_id","db_source_row","official_diagram_raw_hint_unassigned","team_db_raw_mass_reference","reviewer_confirmed_value","baseline_residual","after_change_residual","residual_delta","existing_error_status","new_error_status","issue_or_follow_up","automatically_approved");
        for(var r:t.rows())add(s,st,PREVIOUSLY_MISSING_ROWS.contains(r.dbSourceRow())?"기존 현장파일 누락 후보":"후보별 원수치/잔차 분리 기록",r.candidateId(),r.dbSourceRow(),"미귀속—도면 숫자 힌트 별도 검토",r.sourceMassRaw(),null,null,null,null,"미확정","미확정","이번 33건 대장에 포함; 실제 endpoint·경로·물량과 기준 잔차는 별도 확인 필요","아니오");
        Object[][] refs={
                {3024,"외부유입","C-PW15018","3.1700"},{2702,"C-PW15008","외부반출","24.21"},
                {2706,"C-PW15009","외부반출","24.21"},{2709,"C-PW15010","외부반출","28140"},
                {3022,"외부유입","C-PW15008","26.0200"},{3023,"외부유입","C-PW15009","26.0200"}};
        for(Object[] x:refs)add(s,st,"참고전용—후보 아님","REFERENCE_ONLY_NOT_STEP7-4A_CANDIDATE",x[0],x[1],x[2],null,null,null,null,"미확정","미확정","별도 참고행; 신규 후보·승인으로 집계 금지","아니오");
        add(s,st,"계산망 전체 scope","전체 33 후보","","미귀속","","","","","미확정","미확정","모든 유입/유출·경계·분기/합류·순환·발생/소모·단위/기간·기준시점 확인 전 계산 불가","아니오");
        add(s,st,"오류 판정 규칙","공식 V1.0 / 팀원 DB / 담당자 / 잔차","","미귀속","","","","","미확정","미확정","차이만으로 기존 오류 또는 신규 오류를 확정하지 않음","아니오");
        widths(s,34,46,18,48,30,25,22,22,22,24,24,86,22);
    }

    private void writeInstructions(Workbook wb, Styles st, Step7ReviewLedgerWorkflowService.ReviewTemplate t) {
        Sheet s=wb.createSheet(SHEETS.get(5));
        header(s,st,"재업로드는 이 6시트 전용 route-aware 검증 API를 사용합니다. 기존 schema-v2 API 계약은 변경하지 않습니다.","항목","안내");
        add(s,st,"재업로드 API","POST /api/admin/step7-3/pw-01-05/network-review/preview → 검증 결과 확인 후 /network-review/import");
        add(s,st,"인증","기존 관리자 X-Admin-Token 필요. 파일은 .xlsx 10 MB 이하, 수식·매크로·외부 연결 금지.");
        add(s,st,"매핑","candidate_id + case_key + snapshot_id + source SHA + 원본행/endpoint 대조. 오래된 snapshot/version, 중복 ID/route는 차단.");
        add(s,st,"경로","각 물리 경로마다 고유 route_id. endpoint 쌍이 같아도 route_id가 다르면 별도 경로. 미확정이면 route_id 공란.");
        add(s,st,"유량","route_id별 숫자형 셀·단위·기간·유량 근거·기준시점을 함께 기록. 미확정은 공란이며 0으로 대체하지 않음.");
        add(s,st,"증빙","허용 유형: OFFICIAL_DRAWING_TRACE, APPROVED_PID, FIELD_INSPECTION, FLOW_METER_RECORD, CALCULATION_BASIS, OTHER_DOCUMENT. 위치·검증방법·검증자·시각·의견 기록.");
        add(s,st,"검토상태","EVIDENCE_VERIFIED는 실제 연결 YES, 검증 가능한 route/물량과 typed 증빙 및 명시적 담당자 검토가 모두 충족될 때만 유지. 부족 시 NEEDS_MORE_INFO.");
        add(s,st,"승인","이 업로드는 검토·증빙 이력만 저장합니다. official_approval_created는 반드시 아니오이며 CONFIRMED 관계를 생성하지 않습니다.");
        add(s,st,"계산 가능 여부","부분 경로 또는 미확인 scope는 CALCULATION_UNAVAILABLE. 전체 구간 completeness 확인 전 물질수지 계산 불가.");
        add(s,st,"재업로드","같은 파일 SHA 재업로드는 멱등 처리됩니다. 수정 시 최신 snapshot/template를 다시 받고 review version을 유지하십시오.");
        add(s,st,"고정 snapshot",t.snapshotId());
        widths(s,28,120);
    }

    private void header(Sheet s,Styles st,String note,String... machine) {
        add(s,st,note);
        add(s,st,(Object[])machine);
        s.createFreezePane(0,2);
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(1,1,0,machine.length-1));
    }
    private void add(Sheet s,Styles st,Object... values) {
        int n=s.getPhysicalNumberOfRows();Row r=s.createRow(n);r.setHeightInPoints(n<2?32:34);
        for(int i=0;i<values.length;i++){
            Cell c=r.createCell(i);Object v=values[i];
            if(v instanceof BigDecimal d)c.setCellValue(d.doubleValue());
            else if(v instanceof Number x)c.setCellValue(x.doubleValue());
            else if(v instanceof Boolean b)c.setCellValue(b);
            else if(v!=null)c.setCellValue(v.toString());
            c.setCellStyle(n==0||n==1?st.header():st.normal());
        }
    }
    private void list(Sheet s,int column,String[] values){DataValidationHelper h=s.getDataValidationHelper();var c=h.createExplicitListConstraint(values);var v=h.createValidation(c,new org.apache.poi.ss.util.CellRangeAddressList(2,5000,column,column));v.setShowErrorBox(true);v.setSuppressDropDownArrow(false);s.addValidationData(v);}
    private void widths(Sheet s,int... widths){for(int i=0;i<widths.length;i++)s.setColumnWidth(i,Math.min(widths[i],120)*256);}
    private Styles styles(Workbook w){Font f=w.createFont();f.setFontName("맑은 고딕");f.setColor(IndexedColors.BLACK.getIndex());f.setFontHeightInPoints((short)10);CellStyle n=w.createCellStyle();n.setFont(f);n.setWrapText(true);n.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);Font h=w.createFont();h.setFontName("맑은 고딕");h.setBold(true);h.setColor(IndexedColors.BLACK.getIndex());CellStyle hs=w.createCellStyle();hs.setFont(h);hs.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());hs.setFillPattern(FillPatternType.SOLID_FOREGROUND);hs.setBorderBottom(BorderStyle.THIN);hs.setWrapText(true);return new Styles(n,hs);}
    private record Styles(CellStyle normal,CellStyle header){}
}
