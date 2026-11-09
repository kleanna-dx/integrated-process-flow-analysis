package com.example.processautomation.material;

import com.example.processautomation.excel.InvalidWorkbookException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only parser and fail-closed bridge from the Step 7-5A field workbook to the existing Step 7-4A schema-v2 workflow. */
@Service
public class Step7FieldReviewBridgeService {
    private static final String BASELINE_VERSION="V1.0";
    private static final String FIELD_SHEET="⑤ 담당자 확인 결과";
    private static final String REVIEWER_GUIDANCE="Step 7-4A 담당자 검토대장 최신 schema-v2에 결과를 옮겨 업로드. 이 파일 자체는 재업로드 불가.";
    private static final String PROGRAM_GUIDANCE="최신 7-4A schema-v2 대장으로 옮길 것; 이 workbook은 review-ledger 업로드 형식이 아님";
    private static final List<String> SHEETS=List.of("① 우선 검토 구간","② 설비·배관 연결 확인","③ 물량·단위 확인","④ 기존 물질수지 불일치",FIELD_SHEET);
    private static final List<String> REQUIRED_HEADERS=List.of("candidate_id (Step7-4A)","case_key (Step7-4A)","snapshot_id","source_sha256","DB 원본 행","후보 출발 설비","후보 도착 설비","실제 연결 확인","실제 출발 endpoint","실제 도착 endpoint","물리 경로/배관 ID","방향 확인 근거","실제 물질 종류","확인 유량(숫자)","확인 단위","기준 기간/시작-종료","측정시점/신고시점","유량 출처/계량기 ID","증빙 문서 ID/페이지","증빙 설명/현장 위치","분기·합류·순환 및 다른 유출","확인자","확인일(YYYY-MM-DD)","담당자 검토결과(제안)","추가 확인사항/불일치 이유");
    private static final List<String> INPUT_HEADERS=List.of("실제 연결 확인","실제 출발 endpoint","실제 도착 endpoint","물리 경로/배관 ID","방향 확인 근거","실제 물질 종류","확인 유량(숫자)","확인 단위","기준 기간/시작-종료","측정시점/신고시점","유량 출처/계량기 ID","증빙 문서 ID/페이지","증빙 설명/현장 위치","분기·합류·순환 및 다른 유출","확인자","확인일(YYYY-MM-DD)","담당자 검토결과(제안)","추가 확인사항/불일치 이유");
    private static final Pattern CANDIDATE_ID=Pattern.compile("PW0105-AUTO-DBROW-[1-9][0-9]{0,7}");
    private static final Pattern ROUTE_ID=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private final Step7ReviewLedgerWorkflowService ledger;
    private final Step7ConnectionEvidenceWorkbookExporter schemaV2Exporter;
    private final Step7FieldReviewBridgeWorkbookExporter resultExporter;

    public Step7FieldReviewBridgeService(Step7ReviewLedgerWorkflowService ledger,
                                         Step7ConnectionEvidenceWorkbookExporter schemaV2Exporter,
                                         Step7FieldReviewBridgeWorkbookExporter resultExporter) {
        this.ledger=ledger;this.schemaV2Exporter=schemaV2Exporter;this.resultExporter=resultExporter;
    }

    @Transactional(readOnly=true)
    public BridgePreview preview(byte[] bytes) {
        return analyze(bytes).preview();
    }

    @Transactional
    public BridgeImportResult importResults(byte[] bytes) {
        Analysis analysis=analyze(bytes);
        BridgePreview preview=analysis.preview();
        if(preview.inputRowCount()==0) return new BridgeImportResult(true,preview,0,0,0,List.of(),false,"현장 입력이 없습니다. 검토 이력을 생성하지 않았습니다.");
        if(preview.inputErrorCount()>0||preview.conflictCount()>0||!preview.schemaV2Validation().isEmpty())
            return new BridgeImportResult(false,preview,0,0,preview.inputRowCount(),preview.schemaV2Validation(),false,"오류 또는 충돌 항목이 있어 파일 전체를 저장하지 않았습니다. 근거 부족 항목은 NEEDS_MORE_INFO로만 반영할 수 있습니다.");
        if(analysis.schemaV2Bytes()==null||preview.schemaV2ConvertibleCount()==0)
            return new BridgeImportResult(false,preview,0,0,preview.inputRowCount(),preview.schemaV2Validation(),false,"schema-v2 변환 대상이 없어 저장하지 않았습니다.");
        var result=ledger.importWorkbook(analysis.schemaV2Bytes(),preview.sourceUploadSha256());
        return new BridgeImportResult(result.valid(),preview,result.rowsPersisted(),result.rowsIgnored(),result.rowsRejected(),result.issues(),result.officialApprovalCreated(),result.notice());
    }

    @Transactional(readOnly=true)
    public byte[] resultWorkbook(byte[] bytes, BridgeImportResult importResult) {
        Analysis analysis=analyze(bytes);
        List<HistoryResult> history=new ArrayList<>();
        for(MappingResult row:analysis.preview().mappingRows()) {
            if(!row.mapped()||row.candidateId()==null||row.candidateId().isBlank())continue;
            var status=ledger.status(analysis.preview().snapshotId(),row.candidateId());
            Object review=status.get("review");
            String state="UNREVIEWED";long version=0;String caseKey=row.caseKey();
            if(review instanceof Map<?,?> m) {
                Object raw=m.get("reviewState");if(raw!=null)state=raw.toString();
                Object v=m.get("reviewVersion");if(v instanceof Number n)version=n.longValue();
                Object k=m.get("caseKey");if(k!=null)caseKey=k.toString();
            }
            int count=ledger.history(analysis.preview().snapshotId(),row.candidateId()).size();
            history.add(new HistoryResult(row.candidateId(),caseKey,state,version,count,row.status()));
        }
        return resultExporter.export(analysis.preview(),importResult,history);
    }

    private Analysis analyze(byte[] bytes) {
        if(bytes==null||bytes.length==0)throw new InvalidWorkbookException("현장 확인 Excel이 비어 있습니다.");
        if(bytes.length>10L*1024*1024)throw new InvalidWorkbookException("현장 확인 Excel은 10 MB를 초과할 수 없습니다.");
        ledger.assertSafeWorkbookPackage(bytes);
        String uploadSha=sha256(bytes);
        try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if(workbook.getNumberOfSheets()!=5||!workbook.getSheetName(0).equals(SHEETS.get(0)))throw new InvalidWorkbookException("Step 7-5A 현장확인 Excel의 5개 시트 구조가 아닙니다.");
            for(int i=0;i<SHEETS.size();i++)if(!SHEETS.get(i).equals(workbook.getSheetName(i)))throw new InvalidWorkbookException("예상하지 못한 현장확인 시트: "+workbook.getSheetName(i));
            var sheet=workbook.getSheet(FIELD_SHEET);if(sheet==null)throw new InvalidWorkbookException("담당자 확인 결과 시트가 없습니다.");
            if(sheet.getLastRowNum()>5000)throw new InvalidWorkbookException("현장확인 Excel의 행 수 한도를 초과했습니다.");
            DataFormatter formatter=new DataFormatter(Locale.ROOT);
            Map<String,Integer> columns=new HashMap<>();Row header=sheet.getRow(3);
            if(header==null)throw new InvalidWorkbookException("현장확인 Excel의 입력 헤더가 없습니다.");
            for(Cell cell:header){String label=formatter.formatCellValue(cell).trim();if(!label.isBlank())columns.put(label,cell.getColumnIndex());}
            if(!columns.keySet().containsAll(REQUIRED_HEADERS))throw new InvalidWorkbookException("현장확인 Excel의 열 구성이 Step 7-5A 템플릿과 다릅니다.");
            for(var s:workbook)for(Row row:s)for(Cell cell:row)if(cell.getCellType()==CellType.FORMULA)
                throw new InvalidWorkbookException("수식 셀은 현장확인 업로드에서 허용되지 않습니다: "+s.getSheetName()+"!"+cell.getAddress());

            Map<String,String> snapshotValues=new LinkedHashMap<>();List<FieldRow> parsed=new ArrayList<>();List<MappingResult> mapping=new ArrayList<>();List<ValidationIssue> issues=new ArrayList<>();
            Set<String> workbookCandidateIds=new LinkedHashSet<>();Set<String> mappedCandidateIds=new LinkedHashSet<>();Set<String> candidateRowsSeen=new HashSet<>();
            int inputCount=0,referenceCount=0,matchedCount=0,unmappedCount=0,insufficientCount=0,conflictCount=0;
            String snapshotId="";String sourceHash="";
            for(int rowIndex=4;rowIndex<=sheet.getLastRowNum();rowIndex++) {
                Row row=sheet.getRow(rowIndex);if(row==null||isEntirelyBlank(row,formatter))continue;
                int excelRow=rowIndex+1;
                String candidateId=value(row,columns,"candidate_id (Step7-4A)",formatter);
                String caseKey=value(row,columns,"case_key (Step7-4A)",formatter);
                String rowSnapshot=value(row,columns,"snapshot_id",formatter);
                String rowSha=value(row,columns,"source_sha256",formatter);
                String rowNumberText=value(row,columns,"DB 원본 행",formatter);
                String sourceCandidate=value(row,columns,"후보 출발 설비",formatter);
                String targetCandidate=value(row,columns,"후보 도착 설비",formatter);
                boolean hasInput=hasFieldInput(row,columns,formatter);
                if(!rowSnapshot.isBlank())snapshotValues.put(rowSnapshot,rowSha);
                if(!hasInput&&"미확인".equals(value(row,columns,"실제 연결 확인",formatter))) {
                    // The default displayed value is not a field observation.
                } else if(hasInput) inputCount++;
                if(candidateId.startsWith("REFERENCE_ONLY")||!CANDIDATE_ID.matcher(candidateId).matches()) {
                    referenceCount++;
                    String classification=hasInput?"NEW_CANDIDATE_REVIEW_REQUIRED":"REFERENCE_ONLY_NOT_MAPPED";
                    if(hasInput){unmappedCount++;issues.add(new ValidationIssue(excelRow,candidateId,"candidate_id","NEW_CANDIDATE_REVIEW_REQUIRED","기존 33개 snapshot 후보 식별자가 없습니다. 신규 후보 등록 검토로 분리하며 자동 생성/연결하지 않았습니다.","ERROR"));}
                    mapping.add(new MappingResult(excelRow,candidateId,caseKey,rowSnapshot,safeInt(rowNumberText),sourceCandidate,targetCandidate,false,classification,hasInput?"미매핑 — 신규 후보 검토 필요":"비후보 참조행", ""));
                    continue;
                }
                workbookCandidateIds.add(candidateId);
                if(!candidateRowsSeen.add(candidateId)) {
                    unmappedCount++;conflictCount++;issues.add(new ValidationIssue(excelRow,candidateId,"candidate_id","DUPLICATE_CANDIDATE_ROW","동일 후보 ID가 현장확인 Excel에 여러 번 있습니다.","ERROR"));
                    mapping.add(new MappingResult(excelRow,candidateId,caseKey,rowSnapshot,safeInt(rowNumberText),sourceCandidate,targetCandidate,false,"DUPLICATE_CANDIDATE_ROW","후보 중복", ""));continue;
                }
                Step7ReviewLedgerWorkflowService.CandidateSource candidate=ledger.candidateSource(rowSnapshot,candidateId);
                if(candidate==null) {
                    unmappedCount++;conflictCount++;issues.add(new ValidationIssue(excelRow,candidateId,"candidate_id","UNKNOWN_CANDIDATE","후보가 지정 snapshot에 없으므로 자동 연결하지 않았습니다.","ERROR"));
                    mapping.add(new MappingResult(excelRow,candidateId,caseKey,rowSnapshot,safeInt(rowNumberText),sourceCandidate,targetCandidate,false,"UNKNOWN_CANDIDATE","미매핑", ""));continue;
                }
                mappedCandidateIds.add(candidateId);
                String expectedCase=expectedCaseKey(rowSnapshot,candidate.sourceRow());
                List<ValidationIssue> identityIssues=new ArrayList<>();
                if(!expectedCase.equals(caseKey))identityIssues.add(new ValidationIssue(excelRow,candidateId,"case_key","CASE_KEY_MISMATCH","case_key가 snapshot/원본 행으로 계산한 기존 Step 7-4A 값과 다릅니다.","CONFLICT"));
                if(safeInt(rowNumberText)!=candidate.sourceRow())identityIssues.add(new ValidationIssue(excelRow,candidateId,"DB 원본 행","SOURCE_ROW_MISMATCH","DB 원본 행번호가 snapshot과 다릅니다.","CONFLICT"));
                if(!candidate.source().equals(sourceCandidate)||!candidate.target().equals(targetCandidate))identityIssues.add(new ValidationIssue(excelRow,candidateId,"후보 출발/도착 설비","CANDIDATE_ENDPOINT_MISMATCH","출발·도착 후보 설비가 snapshot과 다릅니다.","CONFLICT"));
                if(!candidate.sourceMassRaw().isBlank()&&!rowSha.isBlank()&&!rowSha.matches("[0-9a-fA-F]{64}"))identityIssues.add(new ValidationIssue(excelRow,candidateId,"source_sha256","INVALID_SOURCE_HASH","원본 SHA-256 형식이 잘못되었습니다.","ERROR"));
                if(hasInput && (rowSha.isBlank()||!snapshotSourceHashMatches(rowSnapshot,rowSha,snapshotValues)))identityIssues.add(new ValidationIssue(excelRow,candidateId,"source_sha256","SOURCE_HASH_MISMATCH","현장확인 파일의 source SHA가 snapshot과 일치하지 않습니다.","CONFLICT"));
                var metadata=ledger.snapshotMetadata(rowSnapshot);
                if(metadata==null||!metadata.locked()||!BASELINE_VERSION.equals(metadata.baselineVersion()))identityIssues.add(new ValidationIssue(excelRow,candidateId,"baseline_version","BASELINE_VERSION_MISMATCH","잠긴 V1.0 snapshot을 확인할 수 없습니다.","CONFLICT"));
                if(metadata!=null&&!metadata.teamSha256().equalsIgnoreCase(rowSha))identityIssues.add(new ValidationIssue(excelRow,candidateId,"source_sha256","SOURCE_HASH_MISMATCH","snapshot의 팀원 원본 SHA와 다릅니다.","CONFLICT"));
                if(!snapshotId.isBlank()&&!snapshotId.equals(ledger.latestSnapshotId()))identityIssues.add(new ValidationIssue(excelRow,candidateId,"snapshot_id","STALE_SNAPSHOT","현재 최신 후보 snapshot이 아닙니다.","CONFLICT"));
                var savedReview=ledger.existingReview(rowSnapshot,candidateId);
                if(hasInput&&savedReview!=null&&!uploadSha.equalsIgnoreCase(savedReview.lastUploadSha256()))identityIssues.add(new ValidationIssue(excelRow,candidateId,"review_version","EXISTING_REVIEW_CONFLICT","기존 schema-v2 검토 이력이 있습니다. 최신 검토대장을 확인하고 Step 7-4A 경로로 처리하세요.","CONFLICT"));
                issues.addAll(identityIssues);
                if(!identityIssues.isEmpty())conflictCount++;
                else matchedCount++;
                String state=hasInput?"FIELD_INPUT_PRESENT":"NO_FIELD_INPUT";
                if(!identityIssues.isEmpty())state="IDENTITY_CONFLICT";
                mapping.add(new MappingResult(excelRow,candidateId,caseKey,rowSnapshot,candidate.sourceRow(),sourceCandidate,targetCandidate,identityIssues.isEmpty(),state,identityIssues.isEmpty()?"기존 snapshot 후보와 식별자 일치":"식별 충돌 — 자동 매핑 차단", ""));
                FieldRow field=readFieldRow(row,columns,formatter,excelRow,candidateId,caseKey,rowSnapshot,rowSha,candidate,hasInput);
                if(hasInput) {
                    List<ValidationIssue> rowIssues=validateField(field,candidate,metadata);
                    issues.addAll(rowIssues);
                    if(rowIssues.stream().anyMatch(i->"EVIDENCE_INSUFFICIENT".equals(i.severity())))insufficientCount++;
                    if(rowIssues.stream().anyMatch(i->"CONFLICT".equals(i.severity())))conflictCount++;
                    if(rowIssues.stream().anyMatch(i->"ERROR".equals(i.severity())||"CONFLICT".equals(i.severity())))state="INPUT_ERROR_OR_CONFLICT";
                    else if(rowIssues.stream().anyMatch(i->"EVIDENCE_INSUFFICIENT".equals(i.severity())))state="EVIDENCE_INSUFFICIENT";
                }
                parsed.add(field.withStatus(state));
            }
            if(snapshotValues.size()>1){conflictCount++;issues.add(new ValidationIssue(0,"","snapshot_id","MIXED_SNAPSHOTS","여러 snapshot이 혼합되어 자동 매핑/저장을 차단합니다.","CONFLICT"));}
            if(snapshotId.isBlank()&&!snapshotValues.isEmpty())snapshotId=snapshotValues.keySet().iterator().next();
            if(!snapshotValues.isEmpty())sourceHash=snapshotValues.get(snapshotId);
            if(snapshotId.isBlank())snapshotId=ledger.latestSnapshotId();
            if(snapshotId==null||snapshotId.isBlank())throw new InvalidWorkbookException("현재 연결 후보 snapshot을 찾을 수 없습니다.");
            var metadata=ledger.snapshotMetadata(snapshotId);
            if(metadata==null)throw new InvalidWorkbookException("현장확인 Excel의 snapshot_id가 현재 저장된 Step 7-4A snapshot과 일치하지 않습니다.");
            if(snapshotValues.isEmpty())sourceHash=metadata.teamSha256();
            if(!BASELINE_VERSION.equals(metadata.baselineVersion())||!metadata.locked())issues.add(new ValidationIssue(0,"","baseline_version","BASELINE_VERSION_MISMATCH","현재 snapshot은 잠긴 V1.0이 아닙니다.","CONFLICT"));
            int missingCount=Math.max(0,metadata.candidateCount()-mappedCandidateIds.size());
            for(String candidateId:loadCandidateIds(snapshotId,metadata.candidateCount())) {
                if(!mappedCandidateIds.contains(candidateId))mapping.add(new MappingResult(0,candidateId,"",snapshotId,0,"","",false,"NOT_PRESENT_IN_FIELD_WORKBOOK","현장확인 Excel에 행이 없습니다.",""));
            }

            if(!snapshotId.equals(ledger.latestSnapshotId())){conflictCount++;issues.add(new ValidationIssue(0,"","snapshot_id","STALE_SNAPSHOT","현재 최신 후보 snapshot이 아니므로 자동 저장을 차단합니다.","CONFLICT"));}
            List<ConversionResult> conversions=new ArrayList<>();List<String> eligibleIds=new ArrayList<>();Map<String,FieldRow> routeClaims=new HashMap<>();
            for(FieldRow field:parsed) {
                if(!field.hasInput()||!Set.of("FIELD_INPUT_PRESENT","EVIDENCE_INSUFFICIENT").contains(field.status()))continue;
                Step7ReviewLedgerWorkflowService.CandidateSource candidate=ledger.candidateSource(field.snapshotId(),field.candidateId());
                if(candidate==null)continue;
                List<ValidationIssue> errs=issues.stream().filter(i->i.excelRow()==field.excelRow()).toList();
                boolean hardBlocked=errs.stream().anyMatch(i->"ERROR".equals(i.severity())||"CONFLICT".equals(i.severity()));
                boolean needsMore=errs.stream().anyMatch(i->"EVIDENCE_INSUFFICIENT".equals(i.severity()));
                String status=hardBlocked?"BLOCKED":needsMore?"READY_AS_NEEDS_MORE_INFO":"READY_FOR_SCHEMA_V2";
                if(!hardBlocked&&"YES".equals(field.physicalConnection())&&!field.routeId().isBlank()) {
                    String pair=List.of(field.candidateSource(),field.candidateTarget()).stream().sorted().reduce((a,b)->a+"|"+b).orElse("");
                    String routeKey=routeIdentity(field.candidateSource(),field.candidateTarget(),field.routeId());FieldRow old=routeClaims.putIfAbsent(routeKey,field);
                    if(old!=null&&!old.candidateId().equals(field.candidateId())) {
                        ValidationIssue duplicate=issue(field,"물리 경로/배관 ID","DUPLICATE_ROUTE_ID_FOR_ENDPOINT_PAIR","같은 설비쌍·경로 ID가 서로 다른 기존 후보에서 중복 선언되었습니다.","CONFLICT");issues.add(duplicate);conflictCount++;hardBlocked=true;status="BLOCKED";
                        conversions.add(new ConversionResult(field.excelRow(),field.candidateId(),field.caseKey(),field.dbRow(),status,field.routeId(),field.reviewDecision(),duplicate.code()));continue;
                    }
                }
                conversions.add(new ConversionResult(field.excelRow(),field.candidateId(),field.caseKey(),field.dbRow(),status,field.routeId(),field.reviewDecision(),errs.stream().map(ValidationIssue::code).distinct().reduce((a,b)->a+";"+b).orElse("")));
                if(!hardBlocked)eligibleIds.add(field.candidateId());
            }
            byte[] schemaBytes=null;Step7ReviewLedgerWorkflowService.ReviewImportResult schemaCheck=null;
            if(!eligibleIds.isEmpty()) {
                var template=ledger.schemaV2Template(snapshotId,eligibleIds);
                List<Step7ReviewLedgerWorkflowService.TemplateRow> transformed=new ArrayList<>();
                for(var base:template.rows()) {
                    FieldRow f=parsed.stream().filter(x->x.candidateId().equals(base.candidateId())).findFirst().orElseThrow();
                String evidence=buildEvidence(f);
                String convertedState="REJECTED".equals(f.reviewDecision())?"REJECTED":("EVIDENCE_VERIFIED".equals(f.reviewDecision())||issues.stream().anyMatch(i->i.excelRow()==f.excelRow()&&"EVIDENCE_INSUFFICIENT".equals(i.severity())))?"NEEDS_MORE_INFO":f.reviewDecision();
                transformed.add(new Step7ReviewLedgerWorkflowService.TemplateRow(base.templateVersion(),base.caseKey(),base.snapshotId(),base.baselineVersion(),base.candidateId(),base.sourceSha256(),base.dbSourceRow(),base.drawingFile(),base.drawingSheet(),base.candidateSource(),base.candidateTarget(),base.sourceCell(),base.targetCell(),base.connectorIds(),base.sourceMassRaw(),base.sourceMassValue(),base.sourceMaterialType(),base.expectedVersion(),base.state(),f.actualSource().isBlank()?base.candidateSource():f.actualSource(),f.actualTarget().isBlank()?base.candidateTarget():f.actualTarget(),f.materialType(),f.quantity(),f.unit(),f.period(),f.physicalConnection(),"",f.evidenceId(),evidence,f.reviewer(),f.reviewDate(),f.comment(),convertedState));
                }
                var converted=new Step7ReviewLedgerWorkflowService.ReviewTemplate(template.snapshotId(),template.baselineVersion(),template.teamSha256(),template.processSha256(),template.masterSha256(),template.schemaVersion(),List.copyOf(transformed));
                schemaBytes=schemaV2Exporter.exportSchemaV2Bridge(converted);
                schemaCheck=ledger.validateWorkbook(schemaBytes,uploadSha);
                if(!schemaCheck.valid()) {
                    for(var i:schemaCheck.issues())issues.add(new ValidationIssue(i.row(),"","schema-v2:"+i.column(),i.code(),i.message(),"ERROR"));
                    conflictCount++;
                    conversions=conversions.stream().map(c->eligibleIds.contains(c.candidateId())?new ConversionResult(c.excelRow(),c.candidateId(),c.caseKey(),c.dbRow(),"SCHEMA_V2_VALIDATION_FAILED",c.routeId(),c.reviewDecision(),"CANONICAL_SCHEMA_V2_VALIDATION_FAILED"):c).toList();
                }
            }
            int errorCount=(int)issues.stream().filter(i->"ERROR".equals(i.severity())).map(ValidationIssue::excelRow).filter(r->r>0).distinct().count();
            int conflictRows=(int)issues.stream().filter(i->"CONFLICT".equals(i.severity())).map(ValidationIssue::excelRow).filter(r->r>0).distinct().count();
            int insRows=(int)issues.stream().filter(i->"EVIDENCE_INSUFFICIENT".equals(i.severity())).map(ValidationIssue::excelRow).filter(r->r>0).distinct().count();
            BridgePreview report=new BridgePreview(snapshotId,metadata.baselineVersion(),metadata.candidateCount(),matchedCount,unmappedCount,referenceCount,missingCount,inputCount,errorCount,insRows,conflictRows,
                    conversions.stream().filter(c->c.status().startsWith("READY_")).count(),sourceHash,uploadSha,List.copyOf(mapping),List.copyOf(issues),List.copyOf(conversions),schemaCheck==null?List.of():schemaCheck.issues(),schemaCheck==null||schemaCheck.valid(),"현장 Excel은 읽기 전용으로 파싱됩니다. 증빙 ID의 존재는 진위/배관 연결을 증명하지 않습니다. 이 결과 workbook 자체는 재업로드 형식이 아닙니다.");
            return new Analysis(report,schemaBytes);
        }catch(InvalidWorkbookException e){throw e;}catch(Exception e){throw new InvalidWorkbookException("현장확인 Excel을 수식 평가 없이 읽지 못했습니다.",e);}
    }

    private List<String> loadCandidateIds(String snapshotId,int expected) {
        return ledger.candidateIds(snapshotId);
    }

    private List<ValidationIssue> validateField(FieldRow f,Step7ReviewLedgerWorkflowService.CandidateSource candidate,
                                                 Step7ReviewLedgerWorkflowService.SnapshotMetadata metadata) {
        List<ValidationIssue> out=new ArrayList<>();
        if(metadata==null||!metadata.locked()||!BASELINE_VERSION.equals(metadata.baselineVersion()))out.add(issue(f,"snapshot_id","BASELINE_VERSION_MISMATCH","현재 기준 버전과 snapshot이 다릅니다.","CONFLICT"));
        if(!f.actualSource().isBlank()&&!ledger.equipmentExists(f.actualSource()))out.add(issue(f,"실제 출발 endpoint","UNKNOWN_EQUIPMENT","V1.0 잠금 마스터에 없는 출발 설비번호입니다.","ERROR"));
        if(!f.actualTarget().isBlank()&&!ledger.equipmentExists(f.actualTarget()))out.add(issue(f,"실제 도착 endpoint","UNKNOWN_EQUIPMENT","V1.0 잠금 마스터에 없는 도착 설비번호입니다.","ERROR"));
        if(f.connection().isBlank())out.add(issue(f,"실제 연결 확인","CONNECTION_CONFIRMATION_REQUIRED","YES/NO 현장 확인 결과가 필요합니다.","ERROR"));
        if(!Set.of("YES","NO").contains(f.physicalConnection()))out.add(issue(f,"실제 연결 확인","INVALID_CONNECTION_CONFIRMATION","현장 연결 확인은 명시적 예/아니오여야 합니다.","ERROR"));
        if("NO".equals(f.physicalConnection())) {
            if(!"REJECTED".equals(f.reviewDecision()))out.add(issue(f,"담당자 검토결과(제안)","NO_CONNECTION_REQUIRES_REJECTED","실제 연결이 아니오이면 검토결과는 REJECTED여야 합니다.","CONFLICT"));
            if(f.comment().isBlank())out.add(issue(f,"추가 확인사항/불일치 이유","REJECTION_COMMENT_REQUIRED","연결 아님 판정 사유가 필요합니다.","ERROR"));
        } else if("YES".equals(f.physicalConnection())) {
            if(f.actualSource().isBlank()||f.actualTarget().isBlank())out.add(issue(f,"실제 출발/도착 endpoint","ENDPOINTS_REQUIRED","실제 양 endpoint가 미확정입니다. 기존 후보 endpoint를 참고값으로만 보존하고 NEEDS_MORE_INFO로 처리합니다.","EVIDENCE_INSUFFICIENT"));
            if(!candidate.source().equals(f.actualSource())||!candidate.target().equals(f.actualTarget()))out.add(issue(f,"실제 출발/도착 endpoint","ENDPOINT_DIRECTION_MISMATCH","실제 endpoint/방향이 기존 후보 방향과 다릅니다. 후보 방향을 자동 변경하지 않습니다.","CONFLICT"));
            if(f.routeId().isBlank())out.add(issue(f,"물리 경로/배관 ID","ROUTE_ID_REQUIRED","경로 ID가 미확정입니다. 경로별로 별도 관리하기 전까지 NEEDS_MORE_INFO로 유지합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.directionEvidence().isBlank())out.add(issue(f,"방향 확인 근거","DIRECTION_EVIDENCE_REQUIRED","방향 확인 근거가 없어 NEEDS_MORE_INFO로 유지합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.materialType().isBlank())out.add(issue(f,"실제 물질 종류","MATERIAL_REQUIRED","물질 종류가 필요합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.quantity()==null&&!f.quantityText().isBlank())out.add(issue(f,"확인 유량(숫자)","NUMERIC_CELL_REQUIRED","입력된 유량은 숫자형 Excel 셀이 아닙니다. 텍스트 숫자를 자동 변환하지 않습니다.","ERROR"));
            else if(f.quantity()==null)out.add(issue(f,"확인 유량(숫자)","NUMERIC_QUANTITY_REQUIRED","확인 유량은 숫자형 셀이어야 하며 공란과 0은 구분됩니다.","EVIDENCE_INSUFFICIENT"));
            if(f.quantity()!=null&&f.quantity().signum()<0)out.add(issue(f,"확인 유량(숫자)","NEGATIVE_QUANTITY","음수 유량을 수정하지 않고 검토 차단 상태로 남깁니다.","ERROR"));
            if(f.unit().isBlank())out.add(issue(f,"확인 단위","UNIT_REQUIRED","단위가 없어 검토/저장을 차단합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.period().isBlank())out.add(issue(f,"기준 기간/시작-종료","PERIOD_REQUIRED","기준 기간이 없어 검토/저장을 차단합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.flowSource().isBlank())out.add(issue(f,"유량 출처/계량기 ID","FLOW_SOURCE_REQUIRED","확인 유량의 출처/계량기 ID가 필요합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.evidenceId().isBlank())out.add(issue(f,"증빙 문서 ID/페이지","EVIDENCE_ID_REQUIRED","증빙 문서 식별정보가 필요합니다.","EVIDENCE_INSUFFICIENT"));
            if(f.evidenceDescription().isBlank())out.add(issue(f,"증빙 설명/현장 위치","EVIDENCE_DESCRIPTION_REQUIRED","근거 설명 또는 현장 위치가 필요합니다.","EVIDENCE_INSUFFICIENT"));
            if("EVIDENCE_VERIFIED".equals(f.reviewDecision()))out.add(issue(f,"담당자 검토결과(제안)","EVIDENCE_TYPE_NOT_PRESENT_IN_FIELD_WORKBOOK","Step 7-5A 열 구조에는 근거 유형 선택 필드가 없습니다. 이 경로에서는 EVIDENCE_VERIFIED로 변환하지 않고 Step 7-4A 검토 절차로 보냅니다.","EVIDENCE_INSUFFICIENT"));
        }
        if(f.reviewer().isBlank())out.add(issue(f,"확인자","REVIEWER_REQUIRED","확인 담당자 이름이 필요합니다.","ERROR"));
        if(f.reviewer().length()>128)out.add(issue(f,"확인자","REVIEWER_TOO_LONG","확인 담당자 이름은 128자 이하여야 합니다.","ERROR"));
        if(f.reviewDate().isBlank())out.add(issue(f,"확인일(YYYY-MM-DD)","REVIEW_DATE_REQUIRED","확인일이 필요합니다.","ERROR"));
        else try{LocalDate.parse(f.reviewDate());}catch(Exception e){out.add(issue(f,"확인일(YYYY-MM-DD)","INVALID_REVIEW_DATE","확인일은 YYYY-MM-DD여야 합니다.","ERROR"));}
        if(!Set.of("REVIEWED_PROVISIONAL","EVIDENCE_VERIFIED","NEEDS_MORE_INFO","REJECTED").contains(f.reviewDecision()))out.add(issue(f,"담당자 검토결과(제안)","INVALID_REVIEW_DECISION","Step 7-4A schema-v2 상태 값이 아닙니다.","ERROR"));
        if("REJECTED".equals(f.reviewDecision())&&f.comment().isBlank())out.add(issue(f,"추가 확인사항/불일치 이유","REJECTION_COMMENT_REQUIRED","REJECTED에는 사유가 필요합니다.","ERROR"));
        if(!f.routeId().isBlank()&&!ROUTE_ID.matcher(f.routeId()).matches())out.add(issue(f,"물리 경로/배관 ID","INVALID_ROUTE_ID","경로 ID 형식 오류입니다.","ERROR"));
        return List.copyOf(out);
    }

    private FieldRow readFieldRow(Row row,Map<String,Integer> columns,DataFormatter formatter,int rowNum,String candidateId,String caseKey,String snapshot,String sha,Step7ReviewLedgerWorkflowService.CandidateSource candidate,boolean hasInput) {
        String connection=value(row,columns,"실제 연결 확인",formatter);
        String physical=normalizeConnection(connection);
        String rawDecision=value(row,columns,"담당자 검토결과(제안)",formatter).toUpperCase(Locale.ROOT);
        String decision=rawDecision;
        if("미확인".equals(connection))connection="";
        BigDecimal quantity=null;String rawQuantity="";Integer qcol=columns.get("확인 유량(숫자)");Cell qcell=qcol==null?null:row.getCell(qcol,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if(qcell!=null&&qcell.getCellType()!=CellType.BLANK) {
            rawQuantity=formatter.formatCellValue(qcell).trim();
            if(qcell.getCellType()==CellType.NUMERIC&&Double.isFinite(qcell.getNumericCellValue()))quantity=BigDecimal.valueOf(qcell.getNumericCellValue());
        }
        return new FieldRow(rowNum,candidateId,caseKey,snapshot,sha,candidate.sourceRow(),candidate.source(),candidate.target(),connection,physical,
                value(row,columns,"실제 출발 endpoint",formatter),value(row,columns,"실제 도착 endpoint",formatter),value(row,columns,"물리 경로/배관 ID",formatter),
                value(row,columns,"방향 확인 근거",formatter),value(row,columns,"실제 물질 종류",formatter),quantity,rawQuantity,
                value(row,columns,"확인 단위",formatter),value(row,columns,"기준 기간/시작-종료",formatter),value(row,columns,"측정시점/신고시점",formatter),
                value(row,columns,"유량 출처/계량기 ID",formatter),value(row,columns,"증빙 문서 ID/페이지",formatter),value(row,columns,"증빙 설명/현장 위치",formatter),
                value(row,columns,"분기·합류·순환 및 다른 유출",formatter),value(row,columns,"확인자",formatter),dateValue(row,columns.get("확인일(YYYY-MM-DD)"),formatter),decision,
                value(row,columns,"추가 확인사항/불일치 이유",formatter),hasInput,"FIELD_INPUT_PRESENT");
    }

    private boolean hasFieldInput(Row row,Map<String,Integer> columns,DataFormatter formatter) {
        for(String header:INPUT_HEADERS) {
            String v=value(row,columns,header,formatter);
            if(!v.isBlank()&&!isTemplateGuidance(header,v)&&!("실제 연결 확인".equals(header)&&Set.of("미확인","미확인 — 현장 확인 필요").contains(v)))return true;
        }
        return false;
    }

    private boolean isEntirelyBlank(Row row,DataFormatter formatter){for(Cell c:row)if(!formatter.formatCellValue(c).isBlank())return false;return true;}
    private String value(Row row,Map<String,Integer> columns,String header,DataFormatter formatter){Integer index=columns.get(header);if(index==null)return "";Cell cell=row.getCell(index,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);String value=cell==null?"":formatter.formatCellValue(cell).trim();return isTemplateGuidance(header,value)?"":value;}
    private boolean isTemplateGuidance(String header,String value){return ("확인자".equals(header)&&REVIEWER_GUIDANCE.equals(value))||("추가 확인사항/불일치 이유".equals(header)&&PROGRAM_GUIDANCE.equals(value));}
    private String dateValue(Row row,Integer index,DataFormatter formatter){if(index==null)return "";Cell cell=row.getCell(index,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(cell==null)return "";if(cell.getCellType()==CellType.NUMERIC&&DateUtil.isCellDateFormatted(cell))return cell.getLocalDateTimeCellValue().toLocalDate().toString();return formatter.formatCellValue(cell).trim();}
    private String normalizeConnection(String value){if(value==null)return "";return switch(value.trim().toUpperCase(Locale.ROOT)){case "YES","예","현장확인(예)","현장 확인(예)","현장확인(YES)"->"YES";case "NO","아니오","현장확인(아니오)","현장 확인(아니오)","현장확인(NO)"->"NO";default->"";};}
    private boolean snapshotSourceHashMatches(String snapshot,String rowHash,Map<String,String> hashes){return hashes.containsKey(snapshot)&&hashes.get(snapshot)!=null&&hashes.get(snapshot).equalsIgnoreCase(rowHash);}
    private String expectedCaseKey(String snapshot,int sourceRow){return "PW0105-RVW-"+snapshot.substring(0,12).toUpperCase(Locale.ROOT)+"-"+sourceRow;}
    static String routeIdentity(String source,String target,String routeId){if(source==null||target==null||routeId==null||source.isBlank()||target.isBlank()||routeId.isBlank())return "";return List.of(source.trim(),target.trim()).stream().sorted().reduce((a,b)->a+"|"+b).orElse("")+"|"+routeId.trim();}
    private int safeInt(String value){try{return Integer.parseInt(value);}catch(Exception e){return -1;}}
    private ValidationIssue issue(FieldRow row,String column,String code,String message,String severity){return new ValidationIssue(row.excelRow(),row.candidateId(),column,code,message,severity);}
    private String buildEvidence(FieldRow row){StringBuilder b=new StringBuilder();if(!row.routeId().isBlank())b.append("FIELD_REVIEW_ROUTE_ID=").append(row.routeId()).append('\n');b.append("현장/담당자 입력 — 증빙 진위 및 배관 연결은 별도 검토 필요. ");if(!row.directionEvidence().isBlank())b.append("방향 근거: ").append(row.directionEvidence()).append(". ");if(!row.flowSource().isBlank())b.append("유량 출처: ").append(row.flowSource()).append(". ");if(!row.evidenceDescription().isBlank())b.append("근거 설명: ").append(row.evidenceDescription()).append(". ");if(!row.branchAndOtherOutflows().isBlank())b.append("분기/다른 유출: ").append(row.branchAndOtherOutflows()).append(". ");if(!row.measurementDate().isBlank())b.append("측정/신고 시점: ").append(row.measurementDate()).append(". ");return b.toString().trim();}
    private String sha256(byte[] bytes){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}

    private record Analysis(BridgePreview preview,byte[] schemaV2Bytes){}
    public record FieldRow(int excelRow,String candidateId,String caseKey,String snapshotId,String sourceSha,int dbRow,String candidateSource,String candidateTarget,String connection,String physicalConnection,String actualSource,String actualTarget,String routeId,String directionEvidence,String materialType,BigDecimal quantity,String quantityText,String unit,String period,String measurementDate,String flowSource,String evidenceId,String evidenceDescription,String branchAndOtherOutflows,String reviewer,String reviewDate,String reviewDecision,String comment,boolean hasInput,String status){FieldRow withStatus(String state){return new FieldRow(excelRow,candidateId,caseKey,snapshotId,sourceSha,dbRow,candidateSource,candidateTarget,connection,physicalConnection,actualSource,actualTarget,routeId,directionEvidence,materialType,quantity,quantityText,unit,period,measurementDate,flowSource,evidenceId,evidenceDescription,branchAndOtherOutflows,reviewer,reviewDate,reviewDecision,comment,hasInput,state);}}
    public record MappingResult(int excelRow,String candidateId,String caseKey,String snapshotId,int dbRow,String sourceEquipment,String targetEquipment,boolean mapped,String status,String message,String routeId){}
    public record ValidationIssue(int excelRow,String candidateId,String column,String code,String message,String severity){}
    public record ConversionResult(int excelRow,String candidateId,String caseKey,int dbRow,String status,String routeId,String reviewDecision,String issueCodes){}
    public record HistoryResult(String candidateId,String caseKey,String reviewState,long reviewVersion,int historyCount,String fieldRowStatus){}
    public record BridgePreview(String snapshotId,String baselineVersion,int snapshotCandidateCount,int mappingSuccessCount,int unmappedCount,int referenceOnlyCount,int missingFromFieldWorkbookCount,int inputRowCount,int inputErrorCount,int evidenceInsufficientCount,int conflictCount,long schemaV2ConvertibleCount,String snapshotSourceSha256,String sourceUploadSha256,List<MappingResult> mappingRows,List<ValidationIssue> validationIssues,List<ConversionResult> conversionRows,List<Step7ReviewLedgerWorkflowService.ReviewIssue> schemaV2Validation,boolean schemaV2Valid,String notice){}
    public record BridgeImportResult(boolean valid,BridgePreview preview,int rowsPersisted,int rowsIgnored,int rowsRejected,List<Step7ReviewLedgerWorkflowService.ReviewIssue> issues,boolean officialApprovalCreated,String notice){}
}
