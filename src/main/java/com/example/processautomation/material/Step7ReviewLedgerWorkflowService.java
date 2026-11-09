package com.example.processautomation.material;

import com.example.processautomation.excel.ExcelCellText;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Versioned review-ledger snapshots and append-only review intake, isolated from process_relation. */
@Service
public class Step7ReviewLedgerWorkflowService {
    public static final String TEMPLATE_SCHEMA_VERSION = "2";
    private static final String BASELINE_VERSION = "V1.0";
    private static final Set<String> STATES = Set.of("REVIEWED_PROVISIONAL", "EVIDENCE_VERIFIED", "REJECTED", "NEEDS_MORE_INFO");
    private static final Set<String> EVIDENCE_TYPES = Set.of("FIELD_INSPECTION", "APPROVED_DRAWING_TRACE",
            "OFFICIAL_DRAWING_TRACE", "FLOW_METER_RECORD", "OTHER_DOCUMENT");
    private static final List<String> HEADERS = List.of("template_schema_version", "case_key", "snapshot_id",
            "baseline_version", "candidate_id", "source_sha256", "db_source_row", "official_drawing_file",
            "official_drawing_sheet", "candidate_source_equipment", "candidate_target_equipment",
            "source_diagram_cell_hint", "target_diagram_cell_hint", "connector_candidate_ids", "source_db_mass_text",
            "source_db_mass_value", "source_db_material_type", "expected_review_version", "source_equipment",
            "target_equipment", "review_material_type", "review_actual_quantity", "review_unit", "review_period",
            "physical_connection_confirmed", "evidence_type", "evidence_identifier", "review_evidence", "reviewer_name",
            "reviewed_date", "review_comment", "review_decision", "review_cycle_candidate",
            "test_only_evidence_accepted", "official_approval_created");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Step7ReviewLedgerWorkflowService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public ReviewTemplate createSnapshot(Step7ConnectionEvidenceService.EvidenceWorkbookData data) {
        String snapshotId = sha256((BASELINE_VERSION + "|" + data.teamSha256() + "|" + data.officialProcessSha256()
                + "|" + data.officialMasterSha256() + "|" + TEMPLATE_SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8));
        Long releaseId = jdbc.queryForObject("SELECT id FROM baseline_release WHERE version_code=? AND locked=TRUE", Long.class, BASELINE_VERSION);
        if (releaseId == null) throw new IllegalStateException("Locked V1.0 baseline is missing");
        jdbc.update("""
                INSERT IGNORE INTO balance_evidence_review_snapshot
                (snapshot_id, baseline_release_id, baseline_version, team_source_sha256, official_process_sha256,
                 official_master_sha256, template_schema_version, candidate_count, baseline_imbalance_summary_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, releaseId, BASELINE_VERSION, data.teamSha256(), data.officialProcessSha256(),
                data.officialMasterSha256(), TEMPLATE_SCHEMA_VERSION, data.candidates().size(), json(data.legacyIssues()));
        Map<String,Object> stored = jdbc.query("SELECT baseline_version,team_source_sha256,official_process_sha256,official_master_sha256,template_schema_version,candidate_count FROM balance_evidence_review_snapshot WHERE snapshot_id=?",
                rs -> rs.next() ? Map.of("version",rs.getString(1),"team",rs.getString(2),"process",rs.getString(3),"master",rs.getString(4),"schema",rs.getString(5),"count",rs.getInt(6)) : null, snapshotId);
        if (stored == null || !BASELINE_VERSION.equals(stored.get("version")) || !data.teamSha256().equals(stored.get("team"))
                || !data.officialProcessSha256().equals(stored.get("process")) || !data.officialMasterSha256().equals(stored.get("master"))
                || !TEMPLATE_SCHEMA_VERSION.equals(stored.get("schema")) || ((Number)stored.get("count")).intValue()!=data.candidates().size()) {
            throw new IllegalStateException("Review snapshot fingerprint mismatch");
        }
        for (var c : data.candidates()) {
            String connectorIds = connectorIds(c);
            jdbc.update("""
                    INSERT IGNORE INTO balance_evidence_review_candidate
                    (snapshot_id,candidate_id,source_row_number,official_drawing_file,official_drawing_sheet,
                     source_equipment,target_equipment,source_cell_hint,target_cell_hint,connector_candidate_ids,
                     source_mass_raw,source_mass_value,source_material_type,official_flow_hints_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, snapshotId,c.caseKey(),c.sourceRow(),"PW-01 폐수처리공정 ★변경허가.xlsx","PW-01-05",
                    c.source(),c.target(),c.sourceCellHints(),c.targetCellHints(),connectorIds,c.quantityText(),
                    c.quantity(),c.materialType(),json(c.flowHints()));
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_candidate WHERE snapshot_id=? AND candidate_id=? AND source_row_number=? AND source_equipment=? AND target_equipment=?",
                    Integer.class,snapshotId,c.caseKey(),c.sourceRow(),c.source(),c.target());
            if(count==null||count!=1) throw new IllegalStateException("Candidate snapshot mismatch: "+c.caseKey());
        }
        List<TemplateRow> rows=new ArrayList<>();
        for(var c:data.candidates()) {
            String key=caseKey(snapshotId,c.sourceRow());
            ReviewState saved=jdbc.query("SELECT review_state,review_version,source_equipment,target_equipment,reviewed_material_type,reviewed_quantity,reviewed_unit,review_period,physical_connection_confirmed,evidence_type,evidence_identifier,evidence_text,reviewer_name_declared,reviewed_date,review_comment FROM balance_evidence_review_case WHERE case_key=? AND snapshot_id=?",
                    rs->rs.next()?new ReviewState(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBigDecimal(6),rs.getString(7),rs.getString(8),rs.getObject(9)==null?"":(rs.getBoolean(9)?"YES":"NO"),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15)):null,key,snapshotId);
            rows.add(new TemplateRow(TEMPLATE_SCHEMA_VERSION,key,snapshotId,BASELINE_VERSION,c.caseKey(),data.teamSha256(),c.sourceRow(),
                    "PW-01 폐수처리공정 ★변경허가.xlsx","PW-01-05",c.source(),c.target(),c.sourceCellHints(),c.targetCellHints(),
                    connectorIds(c),c.quantityText(),c.quantity(),c.materialType(),saved==null?0:saved.version(),
                    saved==null?"UNREVIEWED":saved.state(),saved==null?c.source():saved.source(),saved==null?c.target():saved.target(),
                    saved==null?c.materialType():saved.material(),saved==null?null:saved.quantity(),saved==null?"":safe(saved.unit()),
                    saved==null?"":safe(saved.period()),saved==null?"":safe(saved.connectionConfirmed()),saved==null?"":safe(saved.evidenceType()),
                    saved==null?"":safe(saved.evidenceId()),saved==null?"":safe(saved.evidenceText()),saved==null?"":safe(saved.reviewer()),
                    saved==null?"":safe(saved.reviewedDate()),saved==null?"":safe(saved.comment()),saved==null?"UNREVIEWED":saved.state()));
        }
        return new ReviewTemplate(snapshotId,BASELINE_VERSION,data.teamSha256(),data.officialProcessSha256(),data.officialMasterSha256(),TEMPLATE_SCHEMA_VERSION,List.copyOf(rows));
    }

    @Transactional
    public ReviewImportResult importWorkbook(byte[] bytes) {
        return importWorkbook(bytes, null);
    }

    /** Performs the existing schema-v2 validation without persistence; import repeats it under row locks. */
    @Transactional(readOnly=true)
    public ReviewImportResult validateWorkbook(byte[] bytes,String sourceUploadSha256) {
        String uploadSha=sourceUploadSha256==null?sha256(bytes==null?new byte[0]:bytes):sourceUploadSha256.toLowerCase(Locale.ROOT);
        if(!uploadSha.matches("[0-9a-f]{64}"))throw new InvalidWorkbookException("Invalid source upload fingerprint");
        Parsed parsed=parse(bytes);List<ReviewIssue> errors=new ArrayList<>(parsed.issues());
        Map<String,Candidate> candidates=loadCandidates(parsed.snapshotId());
        if(candidates.isEmpty())errors.add(new ReviewIssue(2,"snapshot_id","SNAPSHOT_NOT_FOUND","기준 스냅샷이 없습니다."));
        Set<String> seen=new HashSet<>();List<Prepared> ready=new ArrayList<>();Map<String,ReviewRow> routeClaims=new HashMap<>();
        for(ReviewRow r:parsed.rows()) {
            if(!seen.add(r.candidateId())){errors.add(issue(r,"candidate_id","DUPLICATE_CANDIDATE","후보 ID가 중복되었습니다."));continue;}
            Candidate c=candidates.get(r.candidateId());
            if(c==null){errors.add(issue(r,"candidate_id","UNKNOWN_CANDIDATE","현재 snapshot의 후보 ID와 일치하지 않습니다."));continue;}
            validateSource(r,c,parsed.snapshotId(),errors);if(isUnchanged(r,c))continue;validateReview(r,errors);
            if(!blank(r.source()))checkEquipment(r,"source_equipment",r.source(),errors);
            if(!blank(r.target()))checkEquipment(r,"target_equipment",r.target(),errors);
            ReviewRow normalized=normalize(r,errors);String endpointPair=routeKey(normalized.source(),normalized.target());
            String route=endpointPair.isBlank()?"":endpointPair+"|"+routeIdFromEvidence(normalized.evidence());
            if(!route.isBlank()){ReviewRow old=routeClaims.putIfAbsent(route,normalized);if(old!=null&&!routeSignature(old).equals(routeSignature(normalized)))errors.add(issue(r,"review_decision","CONFLICTING_ROUTE_ROWS","같은 배관 경로의 검토 결과가 상충합니다. 다른 행 "+old.row()+"을 확인하세요."));}
            ready.add(new Prepared(normalized,c,caseKey(parsed.snapshotId(),c.sourceRow())));
        }
        Map<String,Existing> current=loadExisting(ready);
        for(Prepared p:ready) {
            Existing old=current.get(p.key());if(old!=null&&uploadSha.equals(old.uploadSha()))continue;
            long version=old==null?0:old.version();
            if(p.row().expectedVersion()!=version)errors.add(issue(p.row(),"expected_review_version","REVIEW_VERSION_CONFLICT","최신 검토 버전과 다릅니다. 최신 템플릿을 다시 받으세요."));
            validateSavedRoute(p,errors);
        }
        return new ReviewImportResult(errors.isEmpty(),parsed.rows().size(),0,0,(int)errors.stream().map(ReviewIssue::row).filter(i->i>0).distinct().count(),List.copyOf(errors),parsed.warnings(),false,errors.isEmpty()?"schema-v2 검증 통과; 저장은 수행하지 않았습니다.":"검증 오류가 있어 저장 대상에서 제외했습니다.");
    }

    /** Imports the existing schema-v2 ledger while retaining the original upload fingerprint for bridged sources. */
    @Transactional
    public ReviewImportResult importWorkbook(byte[] bytes, String sourceUploadSha256) {
        String uploadSha=sourceUploadSha256==null?sha256(bytes==null?new byte[0]:bytes):sourceUploadSha256.toLowerCase(Locale.ROOT);
        if(!uploadSha.matches("[0-9a-f]{64}"))throw new InvalidWorkbookException("Invalid source upload fingerprint");
        Parsed parsed=parse(bytes);
        List<ReviewIssue> errors=new ArrayList<>(parsed.issues());
        Map<String,Candidate> candidates=loadCandidates(parsed.snapshotId());
        if(candidates.isEmpty()) errors.add(new ReviewIssue(2,"snapshot_id","SNAPSHOT_NOT_FOUND","기준 스냅샷이 없습니다."));
        Set<String> seen=new HashSet<>();
        List<Prepared> ready=new ArrayList<>();
        Map<String,ReviewRow> routeClaims=new HashMap<>();
        for(ReviewRow r:parsed.rows()) {
            if(!seen.add(r.candidateId())) { errors.add(issue(r,"candidate_id","DUPLICATE_CANDIDATE","후보 ID가 중복되었습니다.")); continue; }
            Candidate c=candidates.get(r.candidateId());
            if(c==null) { errors.add(issue(r,"candidate_id","UNKNOWN_CANDIDATE","현재 snapshot의 후보 ID와 일치하지 않습니다.")); continue; }
            validateSource(r,c,parsed.snapshotId(),errors);
            if(isUnchanged(r,c)) continue;
            validateReview(r,errors);
            if(!blank(r.source())) checkEquipment(r,"source_equipment",r.source(),errors);
            if(!blank(r.target())) checkEquipment(r,"target_equipment",r.target(),errors);
            ReviewRow normalized=normalize(r,errors);
            String endpointPair=routeKey(normalized.source(),normalized.target());
            String route=endpointPair.isBlank()?"":endpointPair+"|"+routeIdFromEvidence(normalized.evidence());
            if(!route.isBlank()) {
                ReviewRow old=routeClaims.putIfAbsent(route,normalized);
                if(old!=null&&!routeSignature(old).equals(routeSignature(normalized))) errors.add(issue(r,"review_decision","CONFLICTING_ROUTE_ROWS","같은 배관 경로의 검토 결과가 상충합니다. 다른 행 "+old.row()+"을 확인하세요."));
            }
            ready.add(new Prepared(normalized,c,caseKey(parsed.snapshotId(),c.sourceRow())));
        }
        if(!errors.isEmpty()) return rejected(parsed,errors);
        Map<String,Existing> current=lockExisting(ready);
        for(Prepared p:ready) {
            Existing old=current.get(p.key());
            if(old!=null&&uploadSha.equals(old.uploadSha())) continue;
            long version=old==null?0:old.version();
            if(p.row().expectedVersion()!=version) errors.add(issue(p.row(),"expected_review_version","REVIEW_VERSION_CONFLICT","최신 검토 버전과 다릅니다. 최신 템플릿을 다시 받으세요."));
            validateSavedRoute(p,errors);
        }
        if(!errors.isEmpty()) return rejected(parsed,errors);
        int saved=0,ignored=0;
        List<String> warnings=new ArrayList<>(parsed.warnings());
        for(Prepared p:ready) {
            Existing old=current.get(p.key());
            if(old!=null&&uploadSha.equals(old.uploadSha())) { ignored++; continue; }
            String after=reviewJson(p,old==null?1:old.version()+1,uploadSha);
            if(old!=null&&after.equals(old.snapshotJson())) { ignored++; continue; }
            save(p,old,uploadSha,after); saved++;
            warnings.addAll(p.row().warnings());
        }
        return new ReviewImportResult(true,parsed.rows().size(),saved,ignored,0,List.of(),List.copyOf(warnings),false,
                "검토 이력만 저장했습니다. process_relation/CONFIRMED 승인은 생성하지 않았습니다.");
    }

    @Transactional(readOnly=true)
    public SnapshotMetadata snapshotMetadata(String snapshotId) {
        List<SnapshotMetadata> rows=jdbc.query("SELECT s.snapshot_id,s.baseline_version,s.team_source_sha256,s.official_process_sha256,s.official_master_sha256,s.candidate_count,br.locked FROM balance_evidence_review_snapshot s JOIN baseline_release br ON br.id=s.baseline_release_id WHERE s.snapshot_id=?",
                (rs,n)->new SnapshotMetadata(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getBoolean(7)),snapshotId);
        return rows.isEmpty()?null:rows.get(0);
    }

    @Transactional(readOnly=true)
    public String latestSnapshotId() {
        return jdbc.query("SELECT snapshot_id FROM balance_evidence_review_snapshot ORDER BY created_at DESC,snapshot_id DESC LIMIT 1",
                rs->rs.next()?rs.getString(1):null);
    }

    @Transactional(readOnly=true)
    public List<String> candidateIds(String snapshotId) {
        return jdbc.queryForList("SELECT candidate_id FROM balance_evidence_review_candidate WHERE snapshot_id=? ORDER BY source_row_number,candidate_id",String.class,snapshotId);
    }

    @Transactional(readOnly=true)
    public CandidateSource candidateSource(String snapshotId,String candidateId) {
        List<CandidateSource> rows=jdbc.query("SELECT candidate_id,source_row_number,official_drawing_file,official_drawing_sheet,source_equipment,target_equipment,source_cell_hint,target_cell_hint,connector_candidate_ids,source_mass_raw,source_mass_value,source_material_type FROM balance_evidence_review_candidate WHERE snapshot_id=? AND candidate_id=?",
                (rs,n)->new CandidateSource(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getBigDecimal(11),rs.getString(12)),snapshotId,candidateId);
        return rows.size()==1?rows.get(0):null;
    }

    @Transactional(readOnly=true)
    public boolean equipmentExists(String equipmentNumber) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record er JOIN baseline_release br ON br.id=er.baseline_release_id WHERE br.version_code=? AND br.locked=TRUE AND er.permit_management_no=?",Integer.class,BASELINE_VERSION,equipmentNumber);
        return count!=null&&count==1;
    }

    @Transactional(readOnly=true)
    public ReviewTemplate schemaV2Template(String snapshotId,List<String> candidateIds) {
        SnapshotMetadata metadata=snapshotMetadata(snapshotId);
        if(metadata==null)throw new InvalidWorkbookException("Review snapshot not found");
        Map<String,Candidate> candidates=loadCandidates(snapshotId);
        List<TemplateRow> rows=new ArrayList<>();
        for(String id:new LinkedHashSet<>(candidateIds)) {
            Candidate c=candidates.get(id);
            if(c==null)throw new InvalidWorkbookException("Candidate is not in the selected snapshot");
            String key=caseKey(snapshotId,c.sourceRow());
            ReviewState saved=jdbc.query("SELECT review_state,review_version,source_equipment,target_equipment,reviewed_material_type,reviewed_quantity,reviewed_unit,review_period,physical_connection_confirmed,evidence_type,evidence_identifier,evidence_text,reviewer_name_declared,reviewed_date,review_comment FROM balance_evidence_review_case WHERE case_key=? AND snapshot_id=?",
                    rs->rs.next()?new ReviewState(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBigDecimal(6),rs.getString(7),rs.getString(8),rs.getObject(9)==null?"":(rs.getBoolean(9)?"YES":"NO"),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15)):null,key,snapshotId);
            rows.add(new TemplateRow(TEMPLATE_SCHEMA_VERSION,key,snapshotId,metadata.baselineVersion(),id,c.teamSha(),c.sourceRow(),c.drawingFile(),c.drawingSheet(),c.source(),c.target(),c.sourceCell(),c.targetCell(),c.connectorIds(),c.massRaw(),c.massValue(),c.material(),saved==null?0:saved.version(),saved==null?"UNREVIEWED":saved.state(),saved==null?c.source():saved.source(),saved==null?c.target():saved.target(),saved==null?c.material():saved.material(),saved==null?null:saved.quantity(),saved==null?"":safe(saved.unit()),saved==null?"":safe(saved.period()),saved==null?"":safe(saved.connectionConfirmed()),saved==null?"":safe(saved.evidenceType()),saved==null?"":safe(saved.evidenceId()),saved==null?"":safe(saved.evidenceText()),saved==null?"":safe(saved.reviewer()),saved==null?"":safe(saved.reviewedDate()),saved==null?"":safe(saved.comment()),saved==null?"UNREVIEWED":saved.state()));
        }
        return new ReviewTemplate(metadata.snapshotId(),metadata.baselineVersion(),metadata.teamSha256(),metadata.processSha256(),metadata.masterSha256(),TEMPLATE_SCHEMA_VERSION,List.copyOf(rows));
    }

    @Transactional(readOnly=true)
    public ExistingReviewMetadata existingReview(String snapshotId,String candidateId) {
        Candidate c=loadCandidates(snapshotId).get(candidateId);if(c==null)return null;
        String key=caseKey(snapshotId,c.sourceRow());
        List<ExistingReviewMetadata> rows=jdbc.query("SELECT review_version,last_upload_sha256,review_state FROM balance_evidence_review_case WHERE case_key=? AND snapshot_id=? AND candidate_id=?",
                (rs,n)->new ExistingReviewMetadata(key,candidateId,rs.getLong(1),rs.getString(2),rs.getString(3)),key,snapshotId,candidateId);
        return rows.isEmpty()?null:rows.get(0);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> status(String snapshot,String candidateId) {
        Candidate c=loadCandidates(snapshot).get(candidateId);
        if(c==null)return Map.of("found",false,"snapshotId",snapshot,"candidateId",candidateId);
        String key=caseKey(snapshot,c.sourceRow());
        Map<String,Object> row=jdbc.query("SELECT case_key AS caseKey,candidate_id AS candidateId,review_state AS reviewState,review_version AS reviewVersion,source_equipment AS sourceEquipment,target_equipment AS targetEquipment,reviewed_material_type AS materialType,reviewed_quantity AS quantity,reviewed_unit AS unit,review_period AS reviewPeriod,physical_connection_confirmed AS physicalConnectionConfirmed,evidence_type AS evidenceType,evidence_identifier AS evidenceIdentifier,evidence_text AS evidenceText,reviewer_name_declared AS reviewer,reviewed_date AS reviewedDate,review_comment AS reviewComment,official_approval_created AS officialApprovalCreated,updated_at AS updatedAt FROM balance_evidence_review_case WHERE case_key=? AND snapshot_id=? AND candidate_id=?",
                rs->rs.next()?mapRow(rs):null,key,snapshot,candidateId);
        if(row!=null)return Map.of("found",true,"snapshotId",snapshot,"candidateId",candidateId,"review",row);
        return Map.of("found",true,"snapshotId",snapshot,"candidateId",candidateId,"review",Map.of("reviewState","UNREVIEWED","reviewVersion",0,"officialApprovalCreated",false));
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(String snapshot,String candidateId) {
        Candidate c=loadCandidates(snapshot).get(candidateId); if(c==null)return List.of();
        return jdbc.queryForList("SELECT id,case_key AS caseKey,candidate_id AS candidateId,action_code AS actionCode,before_json AS beforeState,after_json AS afterState,actor_source AS actorSource,upload_sha256 AS uploadSha256,review_version AS reviewVersion,created_at AS createdAt FROM balance_evidence_review_history WHERE case_key=? ORDER BY id",caseKey(snapshot,c.sourceRow()));
    }

    private Parsed parse(byte[] bytes) {
        if(bytes==null||bytes.length==0)throw new InvalidWorkbookException("Review workbook is empty");
        String unsafe=inspectPackage(bytes); if(unsafe!=null)throw new InvalidWorkbookException(unsafe);
        try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if(wb.getNumberOfSheets()!=5)throw new InvalidWorkbookException("Expected the five-sheet Step 7 review template");
            var sheet=wb.getSheet("담당자 검토대장"); if(sheet==null)throw new InvalidWorkbookException("Missing sheet 담당자 검토대장");
            DataFormatter fmt=new DataFormatter(Locale.ROOT); Map<String,Integer> cols=new HashMap<>();
            Row header=sheet.getRow(1); if(header==null)throw new InvalidWorkbookException("Machine header missing");
            for(Cell cell:header)cols.put(ExcelCellText.format(cell,fmt).trim(),cell.getColumnIndex());
            if(!cols.keySet().containsAll(HEADERS))throw new InvalidWorkbookException("Review template headers do not match schema v2");
            List<ReviewIssue> issues=new ArrayList<>();
            for(Row r:sheet)for(Cell cell:r)if(cell.getCellType()==CellType.FORMULA)issues.add(new ReviewIssue(r.getRowNum()+1,cell.getAddress().formatAsString(),"FORMULA_NOT_ALLOWED","수식 셀은 업로드 검토용으로 허용하지 않습니다."));
            List<ReviewRow> rows=new ArrayList<>(); String snapshot="";
            for(int i=2;i<=sheet.getLastRowNum();i++) {
                Row r=sheet.getRow(i); if(r==null||value(r,cols,"candidate_id",fmt).isBlank())continue;
                String currentSnapshot=value(r,cols,"snapshot_id",fmt); if(snapshot.isBlank())snapshot=currentSnapshot;
                if(!snapshot.equals(currentSnapshot))issues.add(new ReviewIssue(i+1,"snapshot_id","MIXED_SNAPSHOTS","한 파일에는 하나의 snapshot만 허용됩니다."));
                String approval=value(r,cols,"official_approval_created",fmt),testOnly=value(r,cols,"test_only_evidence_accepted",fmt);
                if(!Set.of("NO","아니오","FALSE").contains(approval.toUpperCase(Locale.ROOT)))issues.add(new ReviewIssue(i+1,"official_approval_created","APPROVAL_FLAG_FORBIDDEN","공식 승인 생성 값은 아니오여야 합니다."));
                if(!Set.of("YES","예","TRUE").contains(testOnly.toUpperCase(Locale.ROOT)))issues.add(new ReviewIssue(i+1,"test_only_evidence_accepted","TEST_ONLY_SCOPE_REQUIRED","TEST_ONLY 검토 범위 표시를 유지해야 합니다."));
                Integer qcol=cols.get("review_actual_quantity"); String qt=raw(r,qcol,fmt); BigDecimal q=number(r,qcol);
                rows.add(new ReviewRow(i+1,value(r,cols,"template_schema_version",fmt),value(r,cols,"case_key",fmt),currentSnapshot,
                        value(r,cols,"baseline_version",fmt),value(r,cols,"candidate_id",fmt),value(r,cols,"source_sha256",fmt),
                        intValue(r,cols.get("db_source_row")),value(r,cols,"official_drawing_file",fmt),value(r,cols,"official_drawing_sheet",fmt),
                        value(r,cols,"candidate_source_equipment",fmt),value(r,cols,"candidate_target_equipment",fmt),
                        value(r,cols,"source_diagram_cell_hint",fmt),value(r,cols,"target_diagram_cell_hint",fmt),
                        value(r,cols,"connector_candidate_ids",fmt),value(r,cols,"source_db_mass_text",fmt),
                        number(r,cols.get("source_db_mass_value")),value(r,cols,"source_db_material_type",fmt),
                        longValue(r,cols.get("expected_review_version")),
                        value(r,cols,"source_equipment",fmt),value(r,cols,"target_equipment",fmt),value(r,cols,"review_material_type",fmt),q,qt,
                        value(r,cols,"review_unit",fmt),value(r,cols,"review_period",fmt),value(r,cols,"physical_connection_confirmed",fmt),
                        value(r,cols,"evidence_type",fmt),value(r,cols,"evidence_identifier",fmt),value(r,cols,"review_evidence",fmt),
                        value(r,cols,"reviewer_name",fmt),value(r,cols,"reviewed_date",fmt),value(r,cols,"review_comment",fmt),
                        value(r,cols,"review_decision",fmt),value(r,cols,"review_cycle_candidate",fmt)));
            }
            return new Parsed(snapshot,List.copyOf(rows),List.copyOf(issues),List.of());
        }catch(InvalidWorkbookException e){throw e;}catch(Exception e){throw new InvalidWorkbookException("Unable to parse review template without evaluating formulas",e);}
    }

    void assertSafeWorkbookPackage(byte[] bytes) {
        String unsafe=inspectPackage(bytes);
        if(unsafe!=null)throw new InvalidWorkbookException(unsafe);
    }

    private String inspectPackage(byte[] bytes) {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            ZipEntry e; byte[] buf=new byte[8192];
            while((e=zip.getNextEntry())!=null) {
                String name=e.getName().toLowerCase(Locale.ROOT);
                if(name.startsWith("xl/externallinks/")||name.equals("xl/connections.xml")
                        ||name.startsWith("xl/querytables/")||name.startsWith("xl/querytable/")
                        ||name.startsWith("xl/model/")||name.startsWith("xl/pivotcache/")
                        ||name.equals("xl/vbaproject.bin")||name.startsWith("customxml/")) {
                    return "External links, refreshable queries/data models, macros, and custom XML are not accepted: "+e.getName();
                }
                if(name.endsWith(".rels")){
                    ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=zip.read(buf))!=-1)out.write(buf,0,n);
                    String rel=out.toString(StandardCharsets.UTF_8);
                    if(java.util.regex.Pattern.compile("targetmode\\s*=\\s*([\\\"'])external\\1",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(rel).find())
                        return "External relationship is not allowed: "+e.getName();
                }
            }
            return null;
        }catch(Exception e){return "Uploaded file is not a valid OOXML ZIP workbook";}
    }

    private void validateSource(ReviewRow r,Candidate c,String snapshot,List<ReviewIssue> errors) {
        check(r,"template_schema_version",TEMPLATE_SCHEMA_VERSION.equals(r.templateVersion()),"TEMPLATE_VERSION_MISMATCH","템플릿 버전 불일치",errors);
        check(r,"snapshot_id",snapshot.equals(r.snapshot()),"SNAPSHOT_MISMATCH","snapshot ID 불일치",errors);
        check(r,"baseline_version",BASELINE_VERSION.equals(r.baseline()),"BASELINE_VERSION_MISMATCH","기준 버전 불일치",errors);
        check(r,"case_key",caseKey(snapshot,c.sourceRow()).equals(r.caseKey()),"CASE_KEY_MISMATCH","case key 불일치",errors);
        check(r,"source_sha256",c.teamSha().equalsIgnoreCase(r.sourceSha()),"SOURCE_HASH_MISMATCH","원본 SHA 불일치",errors);
        check(r,"db_source_row",c.sourceRow()==r.dbRow(),"SOURCE_ROW_MISMATCH","DB 원본 행번호 불일치",errors);
        check(r,"official_drawing_file",c.drawingFile().equals(r.drawingFile()),"DRAWING_FILE_MISMATCH","도면 파일 불일치",errors);
        check(r,"official_drawing_sheet",c.drawingSheet().equals(r.drawingSheet()),"DRAWING_SHEET_MISMATCH","도면 시트 불일치",errors);
        check(r,"candidate_source_equipment",c.source().equals(r.candidateSource()),"CANDIDATE_SOURCE_MISMATCH","원본 후보 출발 설비 불일치",errors);
        check(r,"candidate_target_equipment",c.target().equals(r.candidateTarget()),"CANDIDATE_TARGET_MISMATCH","원본 후보 도착 설비 불일치",errors);
        check(r,"source_diagram_cell_hint",safe(c.sourceCell()).equals(r.sourceCell()),"SOURCE_CELL_MISMATCH","출발 셀 근거 불일치",errors);
        check(r,"target_diagram_cell_hint",safe(c.targetCell()).equals(r.targetCell()),"TARGET_CELL_MISMATCH","도착 셀 근거 불일치",errors);
        check(r,"connector_candidate_ids",safe(c.connectorIds()).equals(r.connectorIds()),"CONNECTOR_MISMATCH","connector ID 불일치",errors);
        check(r,"source_db_mass_text",safe(c.massRaw()).equals(r.sourceMassRaw()),"SOURCE_MASS_RAW_MISMATCH","원본 DB 질량 문자열이 snapshot과 다릅니다.",errors);
        check(r,"source_db_mass_value",same(c.massValue(),r.sourceMassValue()),"SOURCE_MASS_VALUE_MISMATCH","원본 DB 질량 값이 snapshot과 다릅니다.",errors);
        check(r,"source_db_material_type",safe(c.material()).equals(r.sourceMaterial()),"SOURCE_MATERIAL_MISMATCH","원본 DB 물질 종류가 snapshot과 다릅니다.",errors);
        if(r.expectedVersion()<0)errors.add(issue(r,"expected_review_version","INVALID_VERSION","검토 버전은 0 이상이어야 합니다."));
    }

    private boolean isUnchanged(ReviewRow r,Candidate c) {
        return blank(r.reviewer())&&blank(r.reviewDate())&&blank(r.comment())&&blank(r.evidence())&&blank(r.evidenceId())
                &&blank(r.evidenceType())&&blank(r.connectionConfirmed())&&blank(r.unit())&&blank(r.period())&&r.quantity()==null
                &&(blank(r.decision())||"UNREVIEWED".equals(r.decision()))
                &&c.source().equals(r.source())&&c.target().equals(r.target())&&safe(c.material()).equals(r.material());
    }

    private void validateReview(ReviewRow r,List<ReviewIssue> errors) {
        if(!STATES.contains(r.decision()))errors.add(issue(r,"review_decision","INVALID_STATE","허용되지 않은 review_state입니다."));
        if(blank(r.source()))errors.add(issue(r,"source_equipment","SOURCE_REQUIRED","출발 설비번호 필요"));
        if(blank(r.target()))errors.add(issue(r,"target_equipment","TARGET_REQUIRED","도착 설비번호 필요"));
        if(blank(r.reviewer()))errors.add(issue(r,"reviewer_name","REVIEWER_REQUIRED","검토자 이름 필요"));
        if(r.reviewer().length()>128)errors.add(issue(r,"reviewer_name","REVIEWER_TOO_LONG","검토자 이름은 128자 이하"));
        try{LocalDate.parse(r.reviewDate());}catch(Exception e){errors.add(issue(r,"reviewed_date","INVALID_REVIEW_DATE","YYYY-MM-DD 날짜 필요"));}
        if(!blank(r.quantityText())&&r.quantity()==null)errors.add(issue(r,"review_actual_quantity","NUMERIC_CELL_REQUIRED","유량은 Excel 숫자 셀이어야 합니다. 공란과 0은 구분됩니다."));
        if(r.quantity()!=null&&r.quantity().signum()<0)errors.add(issue(r,"review_actual_quantity","NEGATIVE_QUANTITY","음수 유량은 허용하지 않습니다."));
        String confirmed=normYesNo(r.connectionConfirmed());
        if(!blank(r.connectionConfirmed())&&confirmed.isBlank())errors.add(issue(r,"physical_connection_confirmed","INVALID_BOOLEAN","YES/NO 또는 예/아니오 필요"));
        if(!blank(r.evidenceType())&&!EVIDENCE_TYPES.contains(r.evidenceType().toUpperCase(Locale.ROOT)))errors.add(issue(r,"evidence_type","INVALID_EVIDENCE_TYPE","확인 근거 유형 오류"));
        if("REJECTED".equals(r.decision())&&blank(r.comment()))errors.add(issue(r,"review_comment","REJECTION_COMMENT_REQUIRED","REJECTED에는 사유 필요"));
    }

    private ReviewRow normalize(ReviewRow r,List<ReviewIssue> errors) {
        List<String>warnings=new ArrayList<>(); String state=r.decision();
        if("EVIDENCE_VERIFIED".equals(state)) {
            List<String> missing=new ArrayList<>();
            if(!"YES".equals(normYesNo(r.connectionConfirmed())))missing.add("실제 연결 확인 YES");
            if(!EVIDENCE_TYPES.contains(safe(r.evidenceType()).toUpperCase(Locale.ROOT)))missing.add("근거 유형");
            if(blank(r.evidenceId()))missing.add("증빙 식별정보"); if(blank(r.evidence()))missing.add("근거 설명");
            if(blank(r.material()))missing.add("물질 종류"); if(r.quantity()==null)missing.add("숫자 유량");
            if(blank(r.unit()))missing.add("단위"); if(blank(r.period()))missing.add("기준 기간");
            if(!missing.isEmpty()){state="NEEDS_MORE_INFO";warnings.add("필수 연결 증빙 부족: "+String.join(", ",missing));}
        }
        if("REVIEWED_PROVISIONAL".equals(state)&&(blank(r.unit())||blank(r.period())||blank(r.evidence())))warnings.add("일부 근거/단위/기간이 미기재되어 provisional 상태입니다.");
        return r.withState(state,List.copyOf(warnings));
    }

    private void checkEquipment(ReviewRow r,String column,String equipment,List<ReviewIssue> errors) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record er JOIN baseline_release br ON br.id=er.baseline_release_id WHERE br.version_code=? AND br.locked=TRUE AND er.permit_management_no=?",Integer.class,BASELINE_VERSION,equipment);
        if(count==null||count!=1)errors.add(issue(r,column,"UNKNOWN_EQUIPMENT","V1.0 잠금 마스터에 없는 설비번호: "+equipment));
    }

    private Map<String,Existing> lockExisting(List<Prepared> rows) {
        Map<String,Existing> result=new HashMap<>();
        for(Prepared p:rows){Map<String,Object> m=jdbc.query("SELECT review_version,last_upload_sha256,review_snapshot_json,source_equipment,target_equipment FROM balance_evidence_review_case WHERE case_key=? FOR UPDATE",rs->rs.next()?Map.of("v",rs.getLong(1),"u",safe(rs.getString(2)),"j",rs.getString(3),"s",safe(rs.getString(4)),"t",safe(rs.getString(5))):null,p.key());
            if(m!=null)result.put(p.key(),new Existing(((Number)m.get("v")).longValue(),str(m.get("u")),str(m.get("j")),routeKey(str(m.get("s")),str(m.get("t")))));}
        return result;
    }

    private Map<String,Existing> loadExisting(List<Prepared> rows) {
        Map<String,Existing> result=new HashMap<>();
        for(Prepared p:rows){Map<String,Object> m=jdbc.query("SELECT review_version,last_upload_sha256,review_snapshot_json,source_equipment,target_equipment FROM balance_evidence_review_case WHERE case_key=?",rs->rs.next()?Map.of("v",rs.getLong(1),"u",safe(rs.getString(2)),"j",safe(rs.getString(3)),"s",safe(rs.getString(4)),"t",safe(rs.getString(5))):null,p.key());
            if(m!=null)result.put(p.key(),new Existing(((Number)m.get("v")).longValue(),str(m.get("u")),str(m.get("j")),routeKey(str(m.get("s")),str(m.get("t")))));}
        return result;
    }

    private void validateSavedRoute(Prepared p,List<ReviewIssue> errors) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT case_key,review_state,reviewed_quantity,reviewed_unit,review_period,reviewed_material_type,physical_connection_confirmed,evidence_text FROM balance_evidence_review_case WHERE snapshot_id=? AND case_key<>? AND ((source_equipment=? AND target_equipment=?) OR (source_equipment=? AND target_equipment=?))",p.row().snapshot(),p.key(),p.row().source(),p.row().target(),p.row().target(),p.row().source());
        for(Map<String,Object> old:rows) {
            String currentPath=routeIdFromEvidence(p.row().evidence());
            String oldPath=routeIdFromEvidence(str(old.get("evidence_text")));
            if(!currentPath.isBlank()&&!oldPath.isBlank()&&!currentPath.equals(oldPath))continue;
            if(!routeSignatureWithoutPath(p.row()).equals(routeSignatureWithoutPath(old))) {
                errors.add(issue(p.row(),"review_decision","SAVED_ROUTE_CONFLICT","기존 같은 배관 경로의 검토와 상충: "+old.get("case_key")));break;
            }
        }
    }

    private void save(Prepared p,Existing old,String uploadSha,String after) {
        ReviewRow r=p.row(); long version=old==null?1:old.version()+1;
        String legacy=switch(r.state()){case "REJECTED"->"REJECTED";case "NEEDS_MORE_INFO"->"NEEDS_MORE_EVIDENCE";default->"TEST_ONLY_EVIDENCE_REVIEWED";};
        if(old==null)jdbc.update("""
                INSERT INTO balance_evidence_review_case
                (case_key,snapshot_id,candidate_id,source_row_number,official_drawing_file,official_drawing_sheet,baseline_release_id,
                 source_sha256,source_equipment,target_equipment,reviewed_quantity,reviewed_unit,reviewed_material_type,reviewed_flow_type,
                 review_period,physical_connection_confirmed,evidence_type,evidence_identifier,evidence_text,review_comment,
                 review_status,review_state,reviewer_name_declared,reviewed_date,review_snapshot_json,official_approval_created,
                 imported_by,review_version,last_upload_sha256)
                VALUES (?,?,?,?,?,?,(SELECT id FROM baseline_release WHERE version_code=?),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,'ADMIN_TOKEN_IMPORT',?,?)
                """,p.key(),r.snapshot(),r.candidateId(),p.candidate().sourceRow(),p.candidate().drawingFile(),p.candidate().drawingSheet(),BASELINE_VERSION,
                r.sourceSha(),r.source(),r.target(),r.quantity(),nil(r.unit()),nil(r.material()),nil(r.flowType()),nil(r.period()),bool(r.connectionConfirmed()),
                nil(r.evidenceType()),nil(r.evidenceId()),r.evidence(),nil(r.comment()),legacy,r.state(),r.reviewer(),LocalDate.parse(r.reviewDate()),after,version,uploadSha);
        else {int changed=jdbc.update("""
                UPDATE balance_evidence_review_case SET source_sha256=?,source_equipment=?,target_equipment=?,reviewed_quantity=?,reviewed_unit=?,
                reviewed_material_type=?,reviewed_flow_type=?,review_period=?,physical_connection_confirmed=?,evidence_type=?,evidence_identifier=?,
                evidence_text=?,review_comment=?,review_status=?,review_state=?,reviewer_name_declared=?,reviewed_date=?,review_snapshot_json=?,
                imported_by='ADMIN_TOKEN_IMPORT',review_version=?,last_upload_sha256=?,updated_at=CURRENT_TIMESTAMP WHERE case_key=? AND review_version=?
                """,r.sourceSha(),r.source(),r.target(),r.quantity(),nil(r.unit()),nil(r.material()),nil(r.flowType()),nil(r.period()),bool(r.connectionConfirmed()),nil(r.evidenceType()),nil(r.evidenceId()),r.evidence(),nil(r.comment()),legacy,r.state(),r.reviewer(),LocalDate.parse(r.reviewDate()),after,version,uploadSha,p.key(),old.version());
            if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Review version conflict; transaction rolled back.");}
        String event=sha256((p.key()+"|"+uploadSha+"|"+after).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO balance_evidence_review_history(case_key,candidate_id,action_code,before_json,after_json,actor_source,upload_sha256,review_version,event_sha256) VALUES (?,?,?,?,?,'ADMIN_TOKEN_IMPORT',?,?,?)",p.key(),r.candidateId(),old==null?"CREATED":"UPDATED",old==null?null:old.snapshotJson(),after,uploadSha,version,event);
    }

    private String snapshotJson(Prepared p,long version,String uploadSha) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("snapshotId",p.row().snapshot());m.put("candidateId",p.row().candidateId());m.put("sourceRow",p.candidate().sourceRow());m.put("drawingFile",p.candidate().drawingFile());m.put("drawingSheet",p.candidate().drawingSheet());m.put("sourceCellHint",p.candidate().sourceCell());m.put("targetCellHint",p.candidate().targetCell());m.put("connectorIds",p.candidate().connectorIds());m.put("sourceDbMassRaw",p.candidate().massRaw());m.put("sourceDbMassValue",p.candidate().massValue());m.put("officialFlowHints",p.candidate().flowJson());m.put("review",p.row().reviewMap());m.put("reviewState",p.row().state());m.put("reviewVersion",version);m.put("uploadSha256",uploadSha);m.put("officialApprovalCreated",false);return json(m);
    }

    private String reviewJson(Prepared p,long version,String uploadSha){return snapshotJson(p,version,uploadSha);}
    private Map<String,Candidate> loadCandidates(String snapshot) {
        if(blank(snapshot))return Map.of();
        List<Candidate> rows=jdbc.query("SELECT candidate_id,source_row_number,official_drawing_file,official_drawing_sheet,source_equipment,target_equipment,source_cell_hint,target_cell_hint,connector_candidate_ids,source_mass_raw,source_mass_value,source_material_type,official_flow_hints_json,s.team_source_sha256 FROM balance_evidence_review_candidate c JOIN balance_evidence_review_snapshot s USING(snapshot_id) WHERE c.snapshot_id=?",(rs,n)->new Candidate(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getBigDecimal(11),rs.getString(12),rs.getString(13),rs.getString(14)),snapshot);
        Map<String,Candidate> result=new LinkedHashMap<>();rows.forEach(c->result.put(c.candidateId(),c));return result;
    }

    private Map<String,Object> mapRow(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object> m=new LinkedHashMap<>();for(String k:List.of("caseKey","candidateId","reviewState","reviewVersion","sourceEquipment","targetEquipment","materialType","quantity","unit","reviewPeriod","physicalConnectionConfirmed","evidenceType","evidenceIdentifier","evidenceText","reviewer","reviewedDate","reviewComment","officialApprovalCreated","updatedAt"))m.put(k,rs.getObject(k));return m;}
    private ReviewImportResult rejected(Parsed p,List<ReviewIssue> errors){int n=(int)errors.stream().map(ReviewIssue::row).filter(i->i>0).distinct().count();return new ReviewImportResult(false,p.rows().size(),0,0,n,List.copyOf(errors),List.copyOf(p.warnings()),false,"검증 오류가 있어 파일 전체를 저장하지 않았습니다.");}
    private void check(ReviewRow r,String col,boolean ok,String code,String message,List<ReviewIssue> errors){if(!ok)errors.add(issue(r,col,code,message));}
    private ReviewIssue issue(ReviewRow r,String col,String code,String msg){return new ReviewIssue(r.row(),col,code,msg);}
    private String value(Row r,Map<String,Integer> c,String k,DataFormatter f){Integer i=c.get(k);return i==null?"":ExcelCellText.format(r.getCell(i,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),f).trim();}
    private String raw(Row r,Integer i,DataFormatter f){if(i==null||r.getCell(i)==null||r.getCell(i).getCellType()==CellType.BLANK)return "";Cell c=r.getCell(i);return c.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString():ExcelCellText.format(c,f).trim();}
    private BigDecimal number(Row r,Integer i){if(i==null||r.getCell(i)==null||r.getCell(i).getCellType()==CellType.BLANK)return null;Cell c=r.getCell(i);return c.getCellType()==CellType.NUMERIC&&Double.isFinite(c.getNumericCellValue())?BigDecimal.valueOf(c.getNumericCellValue()):null;}
    private int intValue(Row r,Integer i){try{return Integer.parseInt(raw(r,i,new DataFormatter(Locale.ROOT)));}catch(Exception e){return -1;}}
    private long longValue(Row r,Integer i){try{return Long.parseLong(raw(r,i,new DataFormatter(Locale.ROOT)));}catch(Exception e){return -1;}}
    private String routeKey(String a,String b){if(blank(a)||blank(b))return "";return List.of(a.trim(),b.trim()).stream().sorted().reduce((x,y)->x+"|"+y).orElse("");}
    private String routeSignature(ReviewRow r){return routeSignatureWithoutPath(r)+"|"+routeIdFromEvidence(r.evidence());}
    private String routeSignatureWithoutPath(ReviewRow r){return String.join("|",r.state(),safe(r.material()),r.quantity()==null?"":r.quantity().stripTrailingZeros().toPlainString(),safe(r.unit()).toLowerCase(Locale.ROOT),safe(r.period()).toLowerCase(Locale.ROOT),normYesNo(r.connectionConfirmed()));}
    private String routeSignatureWithoutPath(Map<String,Object> m){Object q=m.get("reviewed_quantity");return String.join("|",safe(m.get("review_state")),safe(m.get("reviewed_material_type")),q==null?"":new BigDecimal(q.toString()).stripTrailingZeros().toPlainString(),safe(m.get("reviewed_unit")).toLowerCase(Locale.ROOT),safe(m.get("reviewed_period")).toLowerCase(Locale.ROOT),normYesNo(safe(m.get("physical_connection_confirmed"))));}
    private String routeIdFromEvidence(String evidence){if(evidence==null)return "";for(String line:evidence.split("\\R"))if(line.startsWith("FIELD_REVIEW_ROUTE_ID="))return line.substring("FIELD_REVIEW_ROUTE_ID=".length()).trim();return "";}
    private String normYesNo(String v){if(v==null)return "";String x=v.toUpperCase(Locale.ROOT);if(Set.of("YES","예","TRUE").contains(x))return "YES";if(Set.of("NO","아니오","FALSE").contains(x))return "NO";return "";}
    private Boolean bool(String v){String n=normYesNo(v);return n.isBlank()?null:"YES".equals(n);}
    private String caseKey(String snapshot,int row){return "PW0105-RVW-"+snapshot.substring(0,12).toUpperCase(Locale.ROOT)+"-"+row;}
    private String connectorIds(Step7ConnectionEvidenceService.ConnectionCandidate c){return c.connectorHints().stream().map(x->Integer.toString(x.shapeId())).distinct().reduce((a,b)->a+";"+b).orElse("");}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalStateException("JSON serialization failed",e);}}
    private String sha256(byte[] b){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    private String nil(String s){return blank(s)?null:s;}
    private String safe(String s){return s==null?"":s;}
    private String safe(Object s){return s==null?"":s.toString();}
    private String str(Object s){return safe(s);}
    private boolean blank(String s){return s==null||s.isBlank();}
    private boolean same(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}

    public record TemplateRow(String templateVersion,String caseKey,String snapshotId,String baselineVersion,String candidateId,String sourceSha256,int dbSourceRow,String drawingFile,String drawingSheet,String candidateSource,String candidateTarget,String sourceCell,String targetCell,String connectorIds,String sourceMassRaw,BigDecimal sourceMassValue,String sourceMaterialType,long expectedVersion,String state,String source,String target,String material,BigDecimal quantity,String unit,String period,String connectionConfirmed,String evidenceType,String evidenceId,String evidenceText,String reviewer,String reviewedDate,String comment,String decision){}
    public record ReviewTemplate(String snapshotId,String baselineVersion,String teamSha256,String processSha256,String masterSha256,String schemaVersion,List<TemplateRow> rows){}
    public record SnapshotMetadata(String snapshotId,String baselineVersion,String teamSha256,String processSha256,String masterSha256,int candidateCount,boolean locked){}
    public record ExistingReviewMetadata(String caseKey,String candidateId,long reviewVersion,String lastUploadSha256,String reviewState){}
    public record CandidateSource(String candidateId,int sourceRow,String drawingFile,String drawingSheet,String source,String target,String sourceCell,String targetCell,String connectorIds,String sourceMassRaw,BigDecimal sourceMassValue,String materialType){}
    public record ReviewIssue(int row,String column,String code,String message){}
    public record ReviewImportResult(boolean valid,int rowsRead,int rowsPersisted,int rowsIgnored,int rowsRejected,List<ReviewIssue> issues,List<String>warnings,boolean officialApprovalCreated,String notice){}
    private record Candidate(String candidateId,int sourceRow,String drawingFile,String drawingSheet,String source,String target,String sourceCell,String targetCell,String connectorIds,String massRaw,BigDecimal massValue,String material,String flowJson,String teamSha){}
    private record Existing(long version,String uploadSha,String snapshotJson,String route){}
    private record Prepared(ReviewRow row,Candidate candidate,String key){}
    private record Parsed(String snapshotId,List<ReviewRow> rows,List<ReviewIssue> issues,List<String>warnings){}
    private record ReviewState(String state,long version,String source,String target,String material,BigDecimal quantity,String unit,String period,String connectionConfirmed,String evidenceType,String evidenceId,String evidenceText,String reviewer,String reviewedDate,String comment){}
    private record ReviewRow(int row,String templateVersion,String caseKey,String snapshot,String baseline,String candidateId,String sourceSha,int dbRow,String drawingFile,String drawingSheet,String candidateSource,String candidateTarget,String sourceCell,String targetCell,String connectorIds,String sourceMassRaw,BigDecimal sourceMassValue,String sourceMaterial,long expectedVersion,String source,String target,String material,BigDecimal quantity,String quantityText,String unit,String period,String connectionConfirmed,String evidenceType,String evidenceId,String evidence,String reviewer,String reviewDate,String comment,String decision,String flowType,String state,List<String>warnings){
        ReviewRow(int row,String templateVersion,String caseKey,String snapshot,String baseline,String candidateId,String sourceSha,int dbRow,String drawingFile,String drawingSheet,String candidateSource,String candidateTarget,String sourceCell,String targetCell,String connectorIds,String sourceMassRaw,BigDecimal sourceMassValue,String sourceMaterial,long expectedVersion,String source,String target,String material,BigDecimal quantity,String quantityText,String unit,String period,String connectionConfirmed,String evidenceType,String evidenceId,String evidence,String reviewer,String reviewDate,String comment,String decision,String flowType){this(row,templateVersion,caseKey,snapshot,baseline,candidateId,sourceSha,dbRow,drawingFile,drawingSheet,candidateSource,candidateTarget,sourceCell,targetCell,connectorIds,sourceMassRaw,sourceMassValue,sourceMaterial,expectedVersion,source,target,material,quantity,quantityText,unit,period,connectionConfirmed,evidenceType,evidenceId,evidence,reviewer,reviewDate,comment,decision,flowType,decision,List.of());}
        ReviewRow withState(String s,List<String>w){return new ReviewRow(row,templateVersion,caseKey,snapshot,baseline,candidateId,sourceSha,dbRow,drawingFile,drawingSheet,candidateSource,candidateTarget,sourceCell,targetCell,connectorIds,sourceMassRaw,sourceMassValue,sourceMaterial,expectedVersion,source,target,material,quantity,quantityText,unit,period,connectionConfirmed,evidenceType,evidenceId,evidence,reviewer,reviewDate,comment,decision,flowType,s,w);}
        Map<String,Object> reviewMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("source",source);m.put("target",target);m.put("material",material);m.put("quantity",quantity);m.put("unit",unit);m.put("period",period);m.put("physicalConnectionConfirmed",connectionConfirmed);m.put("evidenceType",evidenceType);m.put("evidenceId",evidenceId);m.put("evidence",evidence);m.put("reviewer",reviewer);m.put("reviewDate",reviewDate);m.put("comment",comment);return m;}
    }
}
