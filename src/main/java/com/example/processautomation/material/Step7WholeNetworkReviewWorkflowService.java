package com.example.processautomation.material;

import com.example.processautomation.excel.InvalidWorkbookException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Route-aware, fail-closed intake that delegates review writes to the existing schema-v2 ledger. */
@Service
public class Step7WholeNetworkReviewWorkflowService {
    private static final List<String> REVIEW_HEADERS=List.of("candidate_id","case_key","snapshot_id","source_sha256","expected_review_version","physical_connection_confirmed","review_material_type","review_actual_quantity","review_unit","review_period","evidence_type","evidence_identifier","route_id","evidence_reference","evidence_location","verification_method","verified_by","verified_at","verification_comment","review_evidence","reviewer_name","reviewed_date","review_comment","review_decision","official_approval_created");
    private static final List<String> ROUTE_HEADERS=List.of("candidate_id","case_key","snapshot_id","expected_review_version","route_id","source_equipment","target_equipment","movement_type","direction_confirmed","material_type","branch_merge_type","recycle_path_id","boundary_scope_id","path_evidence_reference","path_description","scope_expected_route_count","topology_confirmed","scope_all_inflows","scope_all_outflows","scope_branches_merges","scope_internal_recycles","scope_interprocess_moves","scope_external_boundaries","scope_generation_consumption","scope_unit_period","scope_baseline_time");
    private static final List<String> QUANTITY_HEADERS=List.of("candidate_id","case_key","snapshot_id","route_id","source_db_mass_raw_reference","quantity","unit","period","quantity_evidence_reference","baseline_time_reference");
    private static final Set<String> EVIDENCE_TYPES=Set.of("OFFICIAL_DRAWING_TRACE","APPROVED_PID","FIELD_INSPECTION","FLOW_METER_RECORD","CALCULATION_BASIS","OTHER_DOCUMENT");
    private static final Set<String> MOVEMENT_TYPES=Set.of("EXTERNAL_INFLOW","EXTERNAL_OUTFLOW","INTERPROCESS_MOVE","INTERNAL_RECYCLE");
    private static final Set<String> BRANCH_TYPES=Set.of("NONE","BRANCH","MERGE","BRANCH_AND_MERGE","UNKNOWN");
    private static final String[] SHEETS=Step7WholeNetworkReviewWorkbookExporter.SHEETS.toArray(String[]::new);
    private final JdbcTemplate jdbc;
    private final Step7ReviewLedgerWorkflowService ledger;
    private final Step7ConnectionEvidenceWorkbookExporter schemaExporter;

    public Step7WholeNetworkReviewWorkflowService(JdbcTemplate jdbc,Step7ReviewLedgerWorkflowService ledger,
                                                   Step7ConnectionEvidenceWorkbookExporter schemaExporter){
        this.jdbc=jdbc;this.ledger=ledger;this.schemaExporter=schemaExporter;
    }

    @Transactional(readOnly=true)
    public IntakePreview preview(byte[] bytes){
        Parsed p=parse(bytes);List<IntakeIssue> issues=new ArrayList<>(p.issues());
        if(p.candidateIds().size()!=33)issues.add(new IntakeIssue(0,"candidate_id","CANDIDATE_SET_INCOMPLETE","전체 후보 시트는 snapshot의 33건을 모두 포함해야 합니다."));
        if(!p.snapshotId().equals(ledger.latestSnapshotId()))issues.add(new IntakeIssue(0,"snapshot_id","STALE_SNAPSHOT","최신 snapshot이 아니므로 저장을 차단합니다."));
        var metadata=ledger.snapshotMetadata(p.snapshotId());
        if(metadata==null||!metadata.locked()||!"V1.0".equals(metadata.baselineVersion()))issues.add(new IntakeIssue(0,"snapshot_id","BASELINE_SNAPSHOT_INVALID","잠긴 V1.0 snapshot을 확인할 수 없습니다."));
        Set<String> known=new LinkedHashSet<>(ledger.candidateIds(p.snapshotId()));
        if(!known.equals(p.candidateIds()))issues.add(new IntakeIssue(0,"candidate_id","CANDIDATE_SET_MISMATCH","candidate_id 전체 집합이 snapshot과 일치하지 않습니다."));
        validateRouteRows(p,issues);
        var converted=toSchemaV2(p,issues);
        var schema=converted==null?null:ledger.validateWorkbook(converted,p.uploadSha());
        if(schema!=null&&!schema.valid())for(var i:schema.issues())issues.add(new IntakeIssue(i.row(),i.column(),i.code(),i.message()));
        boolean valid=issues.isEmpty()&&(schema==null||schema.valid());
        return new IntakePreview(p.snapshotId(),p.uploadSha(),p.candidateIds().size(),p.reviewInputs().size(),p.routes().size(),
                p.referenceRows(),(int)issues.stream().filter(x->x.code().contains("MISSING")||x.code().contains("INCOMPLETE")).count(),valid,
                List.copyOf(issues),"TEST_ONLY 검토 이력 입력 전용; official CONFIRMED 및 실제 계산망 편입은 수행하지 않습니다.");
    }

    @Transactional
    public IntakeResult importWorkbook(byte[] bytes){
        IntakePreview preview=preview(bytes);
        if(!preview.valid())return new IntakeResult(false,preview,0,0,preview.issues(),false,"입력 오류/충돌로 전체 저장을 차단했습니다.");
        Parsed p=parse(bytes);byte[] schemaBytes=toSchemaV2(p,new ArrayList<>());
        if(schemaBytes==null)return new IntakeResult(true,preview,0,0,List.of(),false,"담당자 입력이 없어 저장하지 않았습니다.");
        var saved=ledger.importWorkbook(schemaBytes,p.uploadSha());
        if(!saved.valid())return new IntakeResult(false,preview,0,0,saved.issues().stream().map(i->new IntakeIssue(i.row(),i.column(),i.code(),i.message())).toList(),false,saved.notice());
        int typed=0;
        if(saved.rowsPersisted()>0){
            for(RouteRow route:p.routes()){
                if(route.routeId().isBlank())continue;
                String candidate=route.candidateId();
                Map<String,Object> status=ledger.status(p.snapshotId(),candidate);
                Object review=status.get("review");if(!(review instanceof Map<?,?> m))continue;
                long version=((Number)m.get("reviewVersion")).longValue();
                List<Map<String,Object>> history=ledger.history(p.snapshotId(),candidate);
                long historyId=history.stream().filter(h->((Number)h.getOrDefault("reviewVersion",-1)).longValue()==version)
                        .mapToLong(h->((Number)h.get("id")).longValue()).max().orElseThrow();
                persistRoute(route,historyId,version,p.uploadSha());typed++;
            }
            for(ReviewRow r:p.reviewInputs()){
                if(!r.hasEvidenceDetail())continue;
                Map<String,Object> status=ledger.status(p.snapshotId(),r.candidateId());
                Object review=status.get("review");if(!(review instanceof Map<?,?> m))continue;
                long version=((Number)m.get("reviewVersion")).longValue();
                List<Map<String,Object>> history=ledger.history(p.snapshotId(),r.candidateId());
                long historyId=history.stream().filter(h->((Number)h.getOrDefault("reviewVersion",-1)).longValue()==version)
                        .mapToLong(h->((Number)h.get("id")).longValue()).max().orElseThrow();
                persistEvidence(r,historyId,version,p.uploadSha());
            }
        }
        return new IntakeResult(true,preview,saved.rowsPersisted(),saved.rowsIgnored(),List.of(),false,
                saved.notice()+" typedRouteRows="+typed+"; official approval remains false.");
    }

    private Parsed parse(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>10L*1024*1024)throw new InvalidWorkbookException("Excel 파일이 비었거나 10 MB 한도를 초과했습니다.");
        ledger.assertSafeWorkbookPackage(bytes);String upload=sha256(bytes);
        try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            if(wb.getNumberOfSheets()!=6)throw new InvalidWorkbookException("Step 7-6A 6시트 형식이 아닙니다.");
            for(int i=0;i<6;i++)if(!SHEETS[i].equals(wb.getSheetName(i)))throw new InvalidWorkbookException("시트 순서/이름 불일치: "+wb.getSheetName(i));
            DataFormatter f=new DataFormatter(Locale.ROOT);
            for(var sheet:wb)for(Row row:sheet)for(Cell cell:row)if(cell.getCellType()==CellType.FORMULA)throw new InvalidWorkbookException("수식 셀은 허용하지 않습니다: "+sheet.getSheetName()+"!"+cell.getAddress());
            ParsedSheet candidateSheet=sheet(wb,0,List.of("candidate_id","case_key","snapshot_id","source_sha256","db_source_row","source_equipment","target_equipment","official_drawing_file","official_drawing_sheet","source_cell_hint","target_cell_hint","connector_candidate_ids","origin_class","source_db_mass_raw_reference","source_db_mass_value_reference","source_db_material_type_reference","candidate_status","evidence_status","calculation_included"),f);
            ParsedSheet routeSheet=sheet(wb,1,ROUTE_HEADERS,f);ParsedSheet quantitySheet=sheet(wb,2,QUANTITY_HEADERS,f);ParsedSheet reviewSheet=sheet(wb,3,REVIEW_HEADERS,f);
            String snapshot="";Set<String> ids=new LinkedHashSet<>();List<IntakeIssue> issues=new ArrayList<>();
            for(Map<String,Object> row:candidateSheet.rows()){
                String id=str(row,"candidate_id");if(id.isBlank())continue;
                if(!ids.add(id))issues.add(new IntakeIssue(row.get("excel_row") instanceof Integer n?n:0,"candidate_id","DUPLICATE_CANDIDATE","후보 ID 중복"));
                String current=str(row,"snapshot_id");if(snapshot.isBlank())snapshot=current;else if(!snapshot.equals(current))issues.add(new IntakeIssue(0,"snapshot_id","MIXED_SNAPSHOTS","여러 snapshot 혼합"));
                if(!"아니오".equalsIgnoreCase(str(row,"calculation_included")))issues.add(new IntakeIssue(0,"calculation_included","CALCULATION_FLAG_FORBIDDEN","계산망 포함은 항상 아니오여야 합니다."));
                var src=ledger.candidateSource(current,id);
                if(src==null||src.sourceRow()!=intVal(row.get("db_source_row"))||!src.source().equals(str(row,"source_equipment"))||!src.target().equals(str(row,"target_equipment"))||!src.drawingSheet().equals(str(row,"official_drawing_sheet"))||!src.sourceCell().equals(str(row,"source_cell_hint"))||!src.targetCell().equals(str(row,"target_cell_hint"))||!src.connectorIds().equals(str(row,"connector_candidate_ids")))issues.add(new IntakeIssue(0,id,"SOURCE_TRACE_MISMATCH","원본 행/endpoint/도면/셀/connector 힌트가 snapshot과 다릅니다."));
                if(!current.equals(ledger.latestSnapshotId()))issues.add(new IntakeIssue(0,id,"STALE_SNAPSHOT","현재 최신 snapshot과 다릅니다."));
            }
            Map<String,Map<String,Object>> candidateById=new HashMap<>();for(var r:candidateSheet.rows())candidateById.put(str(r,"candidate_id"),r);
            Map<String,Map<String,Object>> quantities=new LinkedHashMap<>();
            for(var row:quantitySheet.rows()){
                String route=str(row,"route_id");if(route.isBlank())continue;String key=str(row,"candidate_id")+"|"+route;
                if(quantities.putIfAbsent(key,row)!=null)issues.add(new IntakeIssue(0,"route_id","DUPLICATE_ROUTE_QUANTITY","route_id별 물량 행 중복"));
            }
            List<RouteRow> routes=new ArrayList<>();Set<String> routeKeys=new HashSet<>();
            for(var r:routeSheet.rows()){
                String id=str(r,"candidate_id"),route=str(r,"route_id");if(route.isBlank())continue;
                String key=id+"|"+route;if(!routeKeys.add(key))issues.add(new IntakeIssue(0,"route_id","DUPLICATE_ROUTE_ID","동일 후보에서 route_id 중복"));
                Map<String,Object> q=quantities.get(key);if(q==null){issues.add(new IntakeIssue(0,"route_id","ROUTE_QUANTITY_ROW_MISSING","route_id에 대응하는 유량 시트 행이 없습니다."));continue;}
                Map<String,Object> candidate=candidateById.get(id);
                if(candidate==null)issues.add(new IntakeIssue(0,"candidate_id","UNKNOWN_CANDIDATE","배관 route가 33 후보 sheet에 없습니다."));
                else for(String identity:List.of("case_key","snapshot_id"))if(!str(r,identity).equals(str(candidate,identity))||!str(q,identity).equals(str(candidate,identity)))issues.add(new IntakeIssue(0,identity,"ROUTE_IDENTITY_MISMATCH","route/quantity의 "+identity+"가 후보 원본과 다릅니다."));
                if(candidate!=null&&intVal(r.get("expected_review_version"))!=expectedVersion(str(r,"snapshot_id"),id)&&!versionMatches(str(r,"snapshot_id"),id,intVal(r.get("expected_review_version")),upload))issues.add(new IntakeIssue(0,"expected_review_version","REVIEW_VERSION_CONFLICT","route 입력이 최신 review version과 다릅니다."));
                routes.add(route(r,q));
            }
            for(String key:quantities.keySet())if(!routeKeys.contains(key))issues.add(new IntakeIssue(0,"route_id","ORPHAN_QUANTITY_ROW","유량 행에 대응하는 route 행이 없습니다."));
            Map<String,Map<String,Object>> reviewRows=new LinkedHashMap<>();
            for(var r:reviewSheet.rows()){
                String id=str(r,"candidate_id");if(id.isBlank())continue;
                if(reviewRows.putIfAbsent(id,r)!=null)issues.add(new IntakeIssue(0,"candidate_id","DUPLICATE_REVIEW_ROW","후보별 담당자 검토 행 중복"));
                Map<String,Object> c=candidateById.get(id);if(c==null){issues.add(new IntakeIssue(0,"candidate_id","UNKNOWN_CANDIDATE","전체 후보 시트에 없는 ID"));continue;}
                for(String k:List.of("case_key","snapshot_id","source_sha256"))if(!str(r,k).equals(str(c,k)))issues.add(new IntakeIssue(0,k,"REVIEW_IDENTITY_MISMATCH","담당자 검토 sheet의 "+k+"가 후보 원본과 다릅니다."));
                if(!versionMatches(str(r,"snapshot_id"),id,intVal(r.get("expected_review_version")),upload))issues.add(new IntakeIssue(0,"expected_review_version","REVIEW_VERSION_CONFLICT","최신 review version과 다릅니다."));
                if(!str(r,"official_approval_created").equalsIgnoreCase("아니오"))issues.add(new IntakeIssue(0,"official_approval_created","APPROVAL_FLAG_FORBIDDEN","공식 승인 플래그는 아니오여야 합니다."));
            }
            List<ReviewRow> reviewInputs=new ArrayList<>();
            for(var r:reviewRows.values()){
                if(!hasReviewInput(r))continue;
                ReviewRow item=review(r);if(item.decision().equals("EVIDENCE_VERIFIED")&&!evidenceComplete(item,routes)){
                    item=item.withDecision("NEEDS_MORE_INFO");
                }
                reviewInputs.add(item);
            }
            int referenceRows=countReferenceRows(wb.getSheetAt(4),f);
            return new Parsed(snapshot,upload,Set.copyOf(ids),List.copyOf(reviewInputs),List.copyOf(routes),referenceRows,List.copyOf(issues));
        }catch(InvalidWorkbookException e){throw e;}catch(Exception e){throw new InvalidWorkbookException("6시트 현장 검토 파일을 안전하게 읽지 못했습니다.",e);}
    }

    private ParsedSheet sheet(Workbook wb,int index,List<String> required,DataFormatter f){
        var s=wb.getSheetAt(index);Row h=s.getRow(1);if(h==null)throw new InvalidWorkbookException("machine header missing: "+s.getSheetName());
        Map<String,Integer> cols=new HashMap<>();for(Cell c:h)cols.put(f.formatCellValue(c).trim(),c.getColumnIndex());
        if(!cols.keySet().containsAll(required))throw new InvalidWorkbookException("machine headers mismatch: "+s.getSheetName());
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=2;i<=s.getLastRowNum();i++){Row row=s.getRow(i);if(row==null)continue;Map<String,Object> m=new LinkedHashMap<>();m.put("excel_row",i+1);
            for(String k:required){int col=cols.get(k);Cell c=row.getCell(col,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);m.put(k,c==null?"":c.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(c.getNumericCellValue()):f.formatCellValue(c).trim());}rows.add(m);}
        return new ParsedSheet(List.copyOf(rows));
    }

    private RouteRow route(Map<String,Object> r,Map<String,Object> q){
        String rawQty=str(q,"quantity");BigDecimal quantity=null;Object qv=q.get("quantity");if(qv instanceof BigDecimal b)quantity=b;else if(qv instanceof Number n)quantity=BigDecimal.valueOf(n.doubleValue());
        boolean invalidQuantity=qv instanceof String text&&!text.isBlank();
        return new RouteRow(str(r,"candidate_id"),str(r,"case_key"),str(r,"snapshot_id"),intVal(r.get("expected_review_version")),str(r,"route_id"),str(r,"source_equipment"),str(r,"target_equipment"),str(r,"movement_type"),bool(r,"direction_confirmed"),str(r,"material_type"),str(r,"branch_merge_type"),str(r,"recycle_path_id"),str(r,"boundary_scope_id"),str(r,"path_evidence_reference"),str(r,"path_description"),intVal(r.get("scope_expected_route_count")),bool(r,"topology_confirmed"),bool(r,"scope_all_inflows"),bool(r,"scope_all_outflows"),bool(r,"scope_branches_merges"),bool(r,"scope_internal_recycles"),bool(r,"scope_interprocess_moves"),bool(r,"scope_external_boundaries"),bool(r,"scope_generation_consumption"),bool(r,"scope_unit_period"),bool(r,"scope_baseline_time"),quantity,invalidQuantity,str(q,"unit"),str(q,"period"),str(q,"quantity_evidence_reference"),str(q,"baseline_time_reference"),str(q,"source_db_mass_raw_reference"));
    }

    private ReviewRow review(Map<String,Object> r){
        Object raw=r.get("review_actual_quantity");
        BigDecimal q=raw instanceof BigDecimal b?b:null;
        boolean invalidQuantity=raw instanceof String text&&!text.isBlank();
        return new ReviewRow(str(r,"candidate_id"),str(r,"case_key"),str(r,"snapshot_id"),str(r,"source_sha256"),intVal(r.get("expected_review_version")),str(r,"physical_connection_confirmed"),str(r,"review_material_type"),q,invalidQuantity,str(r,"review_unit"),str(r,"review_period"),str(r,"evidence_type"),str(r,"evidence_identifier"),str(r,"route_id"),str(r,"evidence_reference"),str(r,"evidence_location"),str(r,"verification_method"),str(r,"verified_by"),str(r,"verified_at"),str(r,"verification_comment"),str(r,"review_evidence"),str(r,"reviewer_name"),str(r,"reviewed_date"),str(r,"review_comment"),str(r,"review_decision"));
    }

    private void validateRouteRows(Parsed p,List<IntakeIssue> issues){
        Set<String> global=new HashSet<>();
        for(RouteRow r:p.routes()){
            List<String> missing=new ArrayList<>();
            for(String[] pair:new String[][]{{"source_equipment",r.source()},{"target_equipment",r.target()},{"movement_type",r.movementType()},{"branch_merge_type",r.branchMerge()},{"boundary_scope_id",r.scopeId()},{"path_evidence_reference",r.pathEvidence()},{"path_description",r.pathDescription()}})if(pair[1].isBlank())missing.add(pair[0]);
            if(r.invalidQuantity())issues.add(new IntakeIssue(0,"quantity","NUMERIC_CELL_REQUIRED","route 유량은 Excel 숫자형 셀이어야 하며 텍스트 숫자는 허용하지 않습니다."));
            if(r.quantity()!=null&&r.quantity().signum()<0)issues.add(new IntakeIssue(0,"quantity","INVALID_ROUTE_QUANTITY","route 유량은 숫자형 비음수 값이어야 합니다."));
            if(!missing.isEmpty())issues.add(new IntakeIssue(0,"route_id","ROUTE_FIELDS_REQUIRED","route "+r.routeId()+" 필수 입력 누락: "+String.join(",",missing)));
            if(!MOVEMENT_TYPES.contains(r.movementType()))issues.add(new IntakeIssue(0,"movement_type","INVALID_MOVEMENT_TYPE","이동 유형이 허용 목록에 없습니다."));
            if(!BRANCH_TYPES.contains(r.branchMerge()))issues.add(new IntakeIssue(0,"branch_merge_type","INVALID_BRANCH_MERGE","분기/합류 유형이 허용 목록에 없습니다."));
            if(!r.directionConfirmed())issues.add(new IntakeIssue(0,"direction_confirmed","DIRECTION_NOT_CONFIRMED","경로 방향 미확정 상태에서는 route_id를 등록할 수 없습니다."));
            if(r.pathEvidence().isBlank()||r.pathDescription().isBlank())issues.add(new IntakeIssue(0,"path_evidence_reference","PATH_EVIDENCE_REQUIRED","route_id를 선언하려면 경로 확인 근거와 설명이 필요합니다."));
            if(r.expectedRouteCount()<1)issues.add(new IntakeIssue(0,"scope_expected_route_count","SCOPE_ROUTE_COUNT_REQUIRED","구간 내 예상 경로 수가 필요합니다."));
            if(r.quantity()!=null&&(r.unit().isBlank()||r.period().isBlank()||r.quantityEvidence().isBlank()||r.baselineTime().isBlank()))issues.add(new IntakeIssue(0,"quantity","QUANTITY_PROVENANCE_REQUIRED","유량이 입력되면 단위·기간·근거·기준시점을 함께 입력해야 합니다."));
            if(!global.add(r.snapshotId()+"|"+r.routeId()))issues.add(new IntakeIssue(0,"route_id","DUPLICATE_GLOBAL_ROUTE_ID","snapshot 내 route_id는 전역 고유해야 합니다."));
            var c=ledger.candidateSource(r.snapshotId(),r.candidateId());
            if(c==null||!c.source().equals(r.source())||!c.target().equals(r.target()))issues.add(new IntakeIssue(0,"source_equipment","ROUTE_ENDPOINT_MISMATCH","경로 endpoint는 원본 후보와 같아야 하며 변경은 담당자 확인 절차에서 차단됩니다."));
        }
        Map<String,Long> scopeCounts=p.routes().stream().collect(java.util.stream.Collectors.groupingBy(RouteRow::scopeId,java.util.stream.Collectors.counting()));
        for(RouteRow route:p.routes())if(route.expectedRouteCount()>0&&!route.scopeId().isBlank()&&scopeCounts.getOrDefault(route.scopeId(),0L)!=route.expectedRouteCount())issues.add(new IntakeIssue(0,"scope_expected_route_count","SCOPE_ROUTE_COUNT_MISMATCH","선언된 구간 경로 수와 제출된 route 행 수가 다릅니다."));
        Set<String> reviewedIds=p.reviewInputs().stream().map(ReviewRow::candidateId).collect(java.util.stream.Collectors.toSet());
        for(RouteRow route:p.routes())if(!reviewedIds.contains(route.candidateId()))issues.add(new IntakeIssue(0,"candidate_id","ROUTE_REQUIRES_REVIEW_INPUT","route 입력은 동일 후보 담당자 검토 입력과 함께 제출해야 합니다."));
        for(ReviewRow r:p.reviewInputs()){
            if(!r.physical().isBlank()&&!Set.of("YES","NO","예","아니오").contains(r.physical()))issues.add(new IntakeIssue(0,"physical_connection_confirmed","INVALID_CONNECTION","연결 확인은 YES/NO로 입력"));
            if(!r.evidenceType().isBlank()&&!EVIDENCE_TYPES.contains(r.evidenceType()))issues.add(new IntakeIssue(0,"evidence_type","INVALID_EVIDENCE_TYPE","증빙 유형이 허용 목록에 없습니다."));
            if(r.invalidQuantity())issues.add(new IntakeIssue(0,"review_actual_quantity","NUMERIC_CELL_REQUIRED","담당자 유량은 Excel 숫자 셀이어야 합니다."));
            if(r.quantity()!=null&&r.quantity().signum()<0)issues.add(new IntakeIssue(0,"review_actual_quantity","NEGATIVE_QUANTITY","음수 유량은 허용되지 않습니다."));
            try{LocalDate.parse(r.reviewDate());}catch(Exception e){issues.add(new IntakeIssue(0,"reviewed_date","INVALID_REVIEW_DATE","검토일은 YYYY-MM-DD 형식이어야 합니다."));}
            if(!r.verifiedAt().isBlank())try{LocalDateTime.parse(r.verifiedAt());}catch(Exception e){issues.add(new IntakeIssue(0,"verified_at","INVALID_VERIFIED_AT","증빙 확인 시각은 YYYY-MM-DDTHH:mm:ss 형식이어야 합니다."));}
            if(r.reviewer().isBlank())issues.add(new IntakeIssue(0,"reviewer_name","REVIEWER_REQUIRED","담당자 이름이 필요합니다."));
            if(r.decision().equals("EVIDENCE_VERIFIED")&&!evidenceComplete(r,p.routes()))issues.add(new IntakeIssue(0,"review_decision","EVIDENCE_NOT_VERIFIED","증빙/경로 검증 항목이 부족합니다."));
        }
    }

    private byte[] toSchemaV2(Parsed p,List<IntakeIssue> issues){
        if(p.reviewInputs().isEmpty())return null;
        List<Step7ReviewLedgerWorkflowService.TemplateRow> out=new ArrayList<>();
        var base=ledger.schemaV2Template(p.snapshotId(),p.reviewInputs().stream().map(ReviewRow::candidateId).toList());
        for(var b:base.rows()){
            ReviewRow r=p.reviewInputs().stream().filter(x->x.candidateId().equals(b.candidateId())).findFirst().orElse(null);if(r==null)continue;
            String marker=r.routeId().isBlank()?"":"FIELD_REVIEW_ROUTE_ID="+r.routeId()+"\\n";
            String evidence=marker+String.join(" | ",List.of(r.evidenceReference(),r.evidenceLocation(),r.verificationMethod(),r.verifiedBy(),r.verifiedAt(),r.verificationComment(),r.reviewEvidence()).stream().filter(x->!x.isBlank()).toList());
            String type=mapEvidenceType(r.evidenceType());
            String identifier=r.evidenceIdentifier().isBlank()?r.evidenceReference():r.evidenceIdentifier();
            String decision=r.decision();
            if(decision.equals("EVIDENCE_VERIFIED")&&!evidenceComplete(r,p.routes()))decision="NEEDS_MORE_INFO";
            out.add(new Step7ReviewLedgerWorkflowService.TemplateRow(b.templateVersion(),b.caseKey(),b.snapshotId(),b.baselineVersion(),b.candidateId(),b.sourceSha256(),b.dbSourceRow(),b.drawingFile(),b.drawingSheet(),b.candidateSource(),b.candidateTarget(),b.sourceCell(),b.targetCell(),b.connectorIds(),b.sourceMassRaw(),b.sourceMassValue(),b.sourceMaterialType(),b.expectedVersion(),b.state(),b.candidateSource(),b.candidateTarget(),r.material(),r.quantity(),r.unit(),r.period(),r.physical(),type,identifier,evidence,r.reviewer(),r.reviewDate(),r.comment(),decision));
        }
        return schemaExporter.exportSchemaV2Bridge(new Step7ReviewLedgerWorkflowService.ReviewTemplate(base.snapshotId(),base.baselineVersion(),base.teamSha256(),base.processSha256(),base.masterSha256(),base.schemaVersion(),List.copyOf(out)));
    }

    private boolean evidenceComplete(ReviewRow r,List<RouteRow> routes){
        if(!"YES".equalsIgnoreCase(r.physical())||!EVIDENCE_TYPES.contains(r.evidenceType())||r.evidenceIdentifier().isBlank()||r.evidenceReference().isBlank()||r.evidenceLocation().isBlank()||r.verificationMethod().isBlank()||r.verifiedBy().isBlank()||r.verifiedAt().isBlank()||r.verificationComment().isBlank()||r.material().isBlank()||r.quantity()==null||r.unit().isBlank()||r.period().isBlank())return false;
        try{LocalDateTime.parse(r.verifiedAt());}catch(Exception e){return false;}
        List<RouteRow> matching=routes.stream().filter(x->x.candidateId().equals(r.candidateId())&&(r.routeId().isBlank()||x.routeId().equals(r.routeId()))).toList();
        return matching.size()==1&&matching.get(0).complete()&&matching.get(0).quantity().compareTo(r.quantity())==0&&matching.get(0).unit().equals(r.unit())&&matching.get(0).period().equals(r.period())&&(r.routeId().isBlank()||r.routeId().equals(matching.get(0).routeId()));
    }

    private void persistRoute(RouteRow r,long historyId,long version,String uploadSha){
        int expected=jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_route WHERE snapshot_id=? AND route_id=? AND review_version=?",Integer.class,r.snapshotId(),r.routeId(),version);
        if(expected>0)return;
        jdbc.update("""
          INSERT INTO balance_evidence_review_route
          (snapshot_id,candidate_id,case_key,review_history_id,review_version,route_id,source_equipment,target_equipment,movement_type,direction_confirmed,material_type,quantity,unit,period,quantity_evidence_reference,baseline_time_reference,path_evidence_reference,path_description,branch_merge_type,recycle_path_id,boundary_scope_id,topology_confirmed,scope_expected_route_count,scope_all_inflows,scope_all_outflows,scope_branches_merges,scope_internal_recycles,scope_interprocess_moves,scope_external_boundaries,scope_generation_consumption,scope_unit_period,scope_baseline_time,route_review_state,upload_sha256)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,r.snapshotId(),r.candidateId(),r.caseKey(),historyId,version,r.routeId(),r.source(),r.target(),r.movementType(),r.directionConfirmed(),r.material(),r.quantity(),r.unit(),r.period(),r.quantityEvidence(),r.baselineTime(),r.pathEvidence(),r.pathDescription(),r.branchMerge(),nil(r.recycleId()),r.scopeId(),r.topologyConfirmed(),null,r.allInflows(),r.allOutflows(),r.branches(),r.recycles(),r.interprocess(),r.boundaries(),r.generationConsumption(),r.unitPeriod(),r.baselineTimeComplete(),r.complete()?"ROUTE_DETAILS_RECORDED":"NEEDS_MORE_INFO",uploadSha);
    }

    private void persistEvidence(ReviewRow r,long historyId,long version,String uploadSha){
        String type=r.evidenceType();String event=sha256((r.snapshotId()+"|"+r.candidateId()+"|"+version+"|"+r.routeId()+"|"+type+"|"+r.evidenceReference()+"|"+r.evidenceLocation()+"|"+r.verificationMethod()+"|"+r.verifiedBy()+"|"+r.verifiedAt()+"|"+r.verificationComment()+"|"+uploadSha).getBytes(StandardCharsets.UTF_8));
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_provenance_history WHERE event_sha256=?",Integer.class,event);
        if(duplicate!=null&&duplicate>0)return;
        jdbc.update("""
          INSERT INTO balance_evidence_provenance_history
          (snapshot_id,candidate_id,case_key,review_history_id,review_version,route_id,evidence_type,evidence_reference,evidence_location,verification_method,verified_by,verified_at,verification_comment,provenance_state,upload_sha256,event_sha256)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,r.snapshotId(),r.candidateId(),r.caseKey(),historyId,version,r.routeId(),type,r.evidenceReference(),r.evidenceLocation(),r.verificationMethod(),r.verifiedBy(),timestamp(r.verifiedAt()),r.verificationComment(),"EVIDENCE_TYPED_RECORDED_NOT_OFFICIAL_APPROVAL",uploadSha,event);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> routeProfiles(String snapshot,String candidate){
        if(ledger.candidateSource(snapshot,candidate)==null)return List.of();
        return jdbc.queryForList("""
                SELECT r.id AS routeRecordId,r.snapshot_id AS snapshotId,r.candidate_id AS candidateId,r.case_key AS caseKey,
                       r.route_id AS routeId,r.review_history_id AS reviewHistoryId,r.review_version AS reviewVersion,
                       rc.review_version AS currentReviewVersion,h.review_version AS historyReviewVersion,
                       JSON_UNQUOTE(JSON_EXTRACT(h.after_json,'$.reviewState')) AS historyReviewState,
                       r.route_review_state AS routeReviewState,r.source_equipment AS sourceEquipment,
                       r.target_equipment AS targetEquipment,r.movement_type AS movementType,r.direction_confirmed AS directionConfirmed,
                       r.material_type AS materialType,r.quantity,r.unit,r.period,r.path_evidence_reference AS pathEvidenceReference,
                       r.path_description AS pathDescription,r.topology_confirmed AS topologyConfirmed,r.scope_expected_route_count AS expectedRouteCount,
                       r.upload_sha256 AS uploadSha256,r.created_at AS createdAt
                FROM balance_evidence_review_route r
                LEFT JOIN balance_evidence_review_case rc ON rc.snapshot_id=r.snapshot_id AND rc.candidate_id=r.candidate_id
                LEFT JOIN balance_evidence_review_history h ON h.id=r.review_history_id AND h.case_key=r.case_key
                WHERE r.snapshot_id=? AND r.candidate_id=?
                ORDER BY r.review_version,r.route_id,r.id
                """,snapshot,candidate);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> evidenceHistory(String snapshot,String candidate){
        if(ledger.candidateSource(snapshot,candidate)==null)return List.of();
        return jdbc.queryForList("""
                SELECT e.id AS evidenceRecordId,e.snapshot_id AS snapshotId,e.candidate_id AS candidateId,e.case_key AS caseKey,
                       e.review_history_id AS reviewHistoryId,e.review_version AS reviewVersion,h.review_version AS historyReviewVersion,
                       JSON_UNQUOTE(JSON_EXTRACT(h.after_json,'$.reviewState')) AS historyReviewState,e.route_id AS routeId,e.evidence_type AS evidenceType,
                       e.evidence_reference AS evidenceReference,e.evidence_location AS evidenceLocation,
                       e.verification_method AS verificationMethod,e.verified_by AS verifiedBy,e.verified_at AS verifiedAt,
                       e.verification_comment AS verificationComment,e.provenance_state AS provenanceState,
                       e.upload_sha256 AS uploadSha256,e.event_sha256 AS eventSha256,e.created_at AS createdAt
                FROM balance_evidence_provenance_history e
                LEFT JOIN balance_evidence_review_history h ON h.id=e.review_history_id AND h.case_key=e.case_key
                WHERE e.snapshot_id=? AND e.candidate_id=?
                ORDER BY e.review_version,e.id
                """,snapshot,candidate);
    }

    private Timestamp timestamp(String value){if(value==null||value.isBlank())return null;try{return Timestamp.valueOf(LocalDateTime.parse(value));}catch(Exception e){return null;}}
    private int expectedVersion(String snapshot,String candidate){var x=ledger.existingReview(snapshot,candidate);return x==null?0:(int)x.reviewVersion();}
    private boolean versionMatches(String snapshot,String candidate,int expected,String uploadSha){var x=ledger.existingReview(snapshot,candidate);if(x==null)return expected==0;return x.lastUploadSha256()!=null&&x.lastUploadSha256().equalsIgnoreCase(uploadSha)||x.reviewVersion()==expected;}
    private int countReferenceRows(org.apache.poi.ss.usermodel.Sheet s,DataFormatter f){int n=0;for(Row r:s){Cell c=r.getCell(1);if(c!=null&&f.formatCellValue(c).startsWith("REFERENCE_ONLY"))n++;}return n;}
    private boolean hasReviewInput(Map<String,Object> r){for(String k:REVIEW_HEADERS)if(!Set.of("candidate_id","case_key","snapshot_id","source_sha256","expected_review_version","official_approval_created").contains(k)&&!str(r,k).isBlank())return true;return false;}
    private String mapEvidenceType(String type){return switch(type){case "APPROVED_PID"->"APPROVED_DRAWING_TRACE";case "CALCULATION_BASIS"->"OTHER_DOCUMENT";default->type;};}
    private String str(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":v.toString().trim();}
    private int intVal(Object x){try{return new BigDecimal(x.toString()).intValueExact();}catch(Exception e){return -1;}}
    private boolean bool(Map<String,Object> m,String k){String v=str(m,k);return Set.of("YES","TRUE","예","1").contains(v.toUpperCase(Locale.ROOT));}
    private String nil(String x){return x==null||x.isBlank()?null:x;}
    private String sha256(byte[] bytes){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}

    private record ParsedSheet(List<Map<String,Object>> rows){}
    private record Parsed(String snapshotId,String uploadSha,Set<String> candidateIds,List<ReviewRow> reviewInputs,List<RouteRow> routes,int referenceRows,List<IntakeIssue> issues){}
    private record ReviewRow(String candidateId,String caseKey,String snapshotId,String sha,int expectedVersion,String physical,String material,BigDecimal quantity,boolean invalidQuantity,String unit,String period,String evidenceType,String evidenceIdentifier,String routeId,String evidenceReference,String evidenceLocation,String verificationMethod,String verifiedBy,String verifiedAt,String verificationComment,String reviewEvidence,String reviewer,String reviewDate,String comment,String decision){
        boolean hasEvidenceDetail(){return !evidenceType.isBlank()||!evidenceIdentifier.isBlank()||!routeId.isBlank()||!evidenceReference.isBlank()||!evidenceLocation.isBlank()||!verificationMethod.isBlank()||!verifiedBy.isBlank()||!verifiedAt.isBlank()||!verificationComment.isBlank();}
        ReviewRow withDecision(String x){return new ReviewRow(candidateId,caseKey,snapshotId,sha,expectedVersion,physical,material,quantity,invalidQuantity,unit,period,evidenceType,evidenceIdentifier,routeId,evidenceReference,evidenceLocation,verificationMethod,verifiedBy,verifiedAt,verificationComment,reviewEvidence,reviewer,reviewDate,comment,x);}
    }
    private record RouteRow(String candidateId,String caseKey,String snapshotId,int expectedVersion,String routeId,String source,String target,String movementType,boolean directionConfirmed,String material,String branchMerge,String recycleId,String scopeId,String pathEvidence,String pathDescription,int expectedRouteCount,boolean topologyConfirmed,boolean allInflows,boolean allOutflows,boolean branches,boolean recycles,boolean interprocess,boolean boundaries,boolean generationConsumption,boolean unitPeriod,boolean baselineTimeComplete,BigDecimal quantity,boolean invalidQuantity,String unit,String period,String quantityEvidence,String baselineTime,String sourceMassRaw){
        boolean complete(){return directionConfirmed&&topologyConfirmed&&allInflows&&allOutflows&&branches&&recycles&&interprocess&&boundaries&&generationConsumption&&unitPeriod&&baselineTimeComplete&&quantity!=null&&!unit.isBlank()&&!period.isBlank()&&!quantityEvidence.isBlank()&&!baselineTime.isBlank()&&!pathEvidence.isBlank()&&!pathDescription.isBlank()&&expectedRouteCount>0;}
    }
    public record IntakeIssue(int row,String field,String code,String message){}
    public record IntakePreview(String snapshotId,String uploadSha256,int candidateCount,int reviewInputCount,int routeCount,int referenceOnlyCount,int incompleteCount,boolean valid,List<IntakeIssue> issues,String notice){}
    public record IntakeResult(boolean valid,IntakePreview preview,int rowsPersisted,int rowsIgnored,List<IntakeIssue> issues,boolean officialApprovalCreated,String notice){}
}
