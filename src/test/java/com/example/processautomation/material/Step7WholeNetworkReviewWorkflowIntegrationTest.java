package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.example.processautomation.excel.InvalidWorkbookException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Step7WholeNetworkReviewWorkflowIntegrationTest {
    private static final String API="/api/admin/step7-3/pw-01-05";
    @Autowired private Step7ConnectionEvidenceService evidenceService;
    @Autowired private Step7ReviewLedgerWorkflowService ledger;
    @Autowired private Step7ConnectionEvidenceWorkbookExporter schemaV2Exporter;
    @Autowired private Step7WholeNetworkReviewWorkbookExporter exporter;
    @Autowired private Step7WholeNetworkReviewWorkflowService workflow;
    @Autowired private Step7ReviewNetworkWorkflowService networkWorkflow;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    private byte[] makeTemplate() throws Exception {
        byte[] team=Files.readAllBytes(Path.of(System.getProperty("step7.whole.source")));
        var data=evidenceService.buildWorkbook(team,"synthetic-ascii-source.xlsm");
        var template=ledger.createSnapshot(data);
        byte[] bytes=exporter.export(data,template);
        String output=System.getProperty("step7.whole.output");if(output!=null)Files.write(Path.of(output),bytes);
        return bytes;
    }

    private byte[] editTwoRoutes(byte[] original,String candidate) throws Exception {
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(original));var out=new ByteArrayOutputStream()) {
            var route=wb.getSheet("② 배관 경로 및 순환 확인");
            var quantity=wb.getSheet("③ 유량·단위·기준 기간 확인");
            var review=wb.getSheet("④ 증빙자료 및 담당자 검토");
            Map<String,Integer> rh=columns(route),qh=columns(quantity),vh=columns(review);
            int routeRow=find(route,rh.get("candidate_id"),candidate);
            int quantityRow=find(quantity,qh.get("candidate_id"),candidate);
            String scope="SYNTHETIC-SCOPE-PARALLEL";
            var firstRoute=route.getRow(routeRow);
            fillRoute(firstRoute,rh,candidate,"SYNTHETIC-ROUTE-A",scope,2);
            int secondRouteIndex=route.getLastRowNum()+1;
            var secondRoute=route.createRow(secondRouteIndex);
            set(secondRoute,rh,"candidate_id",candidate);set(secondRoute,rh,"case_key",value(firstRoute,rh,"case_key"));
            set(secondRoute,rh,"snapshot_id",value(firstRoute,rh,"snapshot_id"));set(secondRoute,rh,"expected_review_version",0d);
            set(secondRoute,rh,"source_equipment",value(firstRoute,rh,"source_equipment"));set(secondRoute,rh,"target_equipment",value(firstRoute,rh,"target_equipment"));
            fillRoute(secondRoute,rh,candidate,"SYNTHETIC-ROUTE-B",scope,2);
            fillQuantity(quantity.getRow(quantityRow),qh,candidate,"SYNTHETIC-ROUTE-A",10d);
            int secondQuantity=quantity.getLastRowNum()+1;
            var qrow=quantity.createRow(secondQuantity);
            set(qrow,qh,"candidate_id",candidate);set(qrow,qh,"case_key",value(quantity.getRow(quantityRow),qh,"case_key"));
            set(qrow,qh,"snapshot_id",value(quantity.getRow(quantityRow),qh,"snapshot_id"));set(qrow,qh,"route_id","SYNTHETIC-ROUTE-B");
            set(qrow,qh,"source_db_mass_raw_reference",value(quantity.getRow(quantityRow),qh,"source_db_mass_raw_reference"));
            set(qrow,qh,"quantity",12d);set(qrow,qh,"unit","ton/day");set(qrow,qh,"period","2026-10-01/2026-10-02");
            set(qrow,qh,"quantity_evidence_reference","SYNTHETIC-METER-B");set(qrow,qh,"baseline_time_reference","SYNTHETIC-BASELINE-TIME");
            int reviewRow=find(review,vh.get("candidate_id"),candidate);var r=review.getRow(reviewRow);
            set(r,vh,"physical_connection_confirmed","YES");set(r,vh,"review_material_type","TEST_ONLY wastewater");
            set(r,vh,"reviewer_name","synthetic-reviewer");set(r,vh,"reviewed_date","2026-10-26");
            set(r,vh,"review_comment","합성 TEST_ONLY 두 경로 검증");set(r,vh,"review_decision","REVIEWED_PROVISIONAL");
            wb.write(out);return out.toByteArray();
        }
    }

    private byte[] setCell(byte[] original,String sheetName,int row,int col,String value) throws Exception {
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(original));var out=new ByteArrayOutputStream()){
            wb.getSheet(sheetName).getRow(row).getCell(col).setCellValue(value);wb.write(out);return out.toByteArray();
        }
    }
    private byte[] addUnsafeZipEntry(byte[] original,String name,String value) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(original));ZipOutputStream zip=new ZipOutputStream(out)){
            byte[] buffer=new byte[8192];ZipEntry entry;
            while((entry=in.getNextEntry())!=null){zip.putNextEntry(new ZipEntry(entry.getName()));int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);zip.closeEntry();}
            zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] editCompleteEvidence(byte[] original,String candidate) throws Exception {
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(original));var out=new ByteArrayOutputStream()) {
            var route=wb.getSheet("② 배관 경로 및 순환 확인");var quantity=wb.getSheet("③ 유량·단위·기준 기간 확인");var review=wb.getSheet("④ 증빙자료 및 담당자 검토");
            Map<String,Integer> rh=columns(route),qh=columns(quantity),vh=columns(review);
            int rr=find(route,rh.get("candidate_id"),candidate);var r=route.getRow(rr);
            set(r,rh,"route_id","SYNTHETIC-EVIDENCE-ROUTE");set(r,rh,"movement_type","INTERPROCESS_MOVE");set(r,rh,"direction_confirmed","YES");
            set(r,rh,"material_type","TEST_ONLY wastewater");set(r,rh,"branch_merge_type","NONE");set(r,rh,"boundary_scope_id","SYNTHETIC-COMPLETE-SCOPE");
            set(r,rh,"path_evidence_reference","SYNTHETIC-PATH-DOC");set(r,rh,"path_description","synthetic independent route inspection");
            set(r,rh,"scope_expected_route_count",1d);set(r,rh,"topology_confirmed","YES");
            for(String key:List.of("scope_all_inflows","scope_all_outflows","scope_branches_merges","scope_internal_recycles","scope_interprocess_moves","scope_external_boundaries","scope_generation_consumption","scope_unit_period","scope_baseline_time"))set(r,rh,key,"YES");
            int qr=find(quantity,qh.get("candidate_id"),candidate);var q=quantity.getRow(qr);
            set(q,qh,"route_id","SYNTHETIC-EVIDENCE-ROUTE");set(q,qh,"quantity",5d);set(q,qh,"unit","ton/day");set(q,qh,"period","2026-10-01/2026-10-02");
            set(q,qh,"quantity_evidence_reference","SYNTHETIC-METER-REF");set(q,qh,"baseline_time_reference","2026-10-01T00:00:00");
            var v=review.getRow(find(review,vh.get("candidate_id"),candidate));
            set(v,vh,"physical_connection_confirmed","YES");set(v,vh,"review_material_type","TEST_ONLY wastewater");set(v,vh,"review_actual_quantity",5d);
            set(v,vh,"review_unit","ton/day");set(v,vh,"review_period","2026-10-01/2026-10-02");set(v,vh,"evidence_type","FIELD_INSPECTION");
            set(v,vh,"evidence_identifier","SYNTHETIC-FIELD-RECORD-1");set(v,vh,"route_id","SYNTHETIC-EVIDENCE-ROUTE");
            set(v,vh,"evidence_reference","SYNTHETIC-FIELD-RECORD-1 p.1");set(v,vh,"evidence_location","synthetic area A, line segment 1");
            set(v,vh,"verification_method","synthetic trace from endpoint to endpoint");set(v,vh,"verified_by","synthetic-reviewer");
            set(v,vh,"verified_at","2026-10-26T10:00:00");set(v,vh,"verification_comment","synthetic test record only");
            set(v,vh,"review_evidence","synthetic source, not actual field proof");set(v,vh,"reviewer_name","synthetic-reviewer");
            set(v,vh,"reviewed_date","2026-10-26");set(v,vh,"review_comment","isolated TEST_ONLY verification");set(v,vh,"review_decision","EVIDENCE_VERIFIED");
            wb.write(out);return out.toByteArray();
        }
    }

    private void fillRoute(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> h,String candidate,String route,String scope,int expected) {
        set(row,h,"route_id",route);set(row,h,"movement_type","INTERPROCESS_MOVE");set(row,h,"direction_confirmed","YES");
        set(row,h,"material_type","TEST_ONLY wastewater");set(row,h,"branch_merge_type","NONE");set(row,h,"boundary_scope_id",scope);
        set(row,h,"path_evidence_reference","SYNTHETIC-PATH-"+route);set(row,h,"path_description","synthetic independent line trace");
        set(row,h,"scope_expected_route_count",expected);set(row,h,"topology_confirmed","YES");
        set(row,h,"scope_all_inflows","NO");set(row,h,"scope_all_outflows","NO");set(row,h,"scope_branches_merges","NO");
        set(row,h,"scope_internal_recycles","NO");set(row,h,"scope_interprocess_moves","NO");set(row,h,"scope_external_boundaries","NO");
        set(row,h,"scope_generation_consumption","NO");set(row,h,"scope_unit_period","YES");set(row,h,"scope_baseline_time","YES");
    }
    private void fillQuantity(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> h,String candidate,String route,double amount) {
        set(row,h,"route_id",route);set(row,h,"quantity",amount);set(row,h,"unit","ton/day");set(row,h,"period","2026-10-01/2026-10-02");
        set(row,h,"quantity_evidence_reference","SYNTHETIC-METER-A");set(row,h,"baseline_time_reference","SYNTHETIC-BASELINE-TIME");
    }
    private Map<String,Integer> columns(org.apache.poi.ss.usermodel.Sheet s){Map<String,Integer> m=new LinkedHashMap<>();var h=s.getRow(1);DataFormatter f=new DataFormatter();for(var c:h)m.put(f.formatCellValue(c).trim(),c.getColumnIndex());return m;}
    private void assertHeadersEqual(org.apache.poi.ss.usermodel.Sheet sheet,String serviceField) throws Exception {
        var field=Step7WholeNetworkReviewWorkflowService.class.getDeclaredField(serviceField);field.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> expected=(List<String>)field.get(null);
        var header=sheet.getRow(1);DataFormatter formatter=new DataFormatter();
        assertThat(java.util.stream.IntStream.range(0,header.getLastCellNum()).mapToObj(i->formatter.formatCellValue(header.getCell(i)).trim()).toList()).containsExactlyElementsOf(expected);
    }
    private int find(org.apache.poi.ss.usermodel.Sheet s,int column,String value){DataFormatter f=new DataFormatter();for(int i=2;i<=s.getLastRowNum();i++){var r=s.getRow(i);if(r!=null&&value.equals(f.formatCellValue(r.getCell(column))))return i;}throw new IllegalArgumentException("candidate row not found: "+value);}
    private String value(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> h,String key){return new DataFormatter().formatCellValue(row.getCell(h.get(key))).trim();}
    private void set(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> h,String key,Object value){var old=row.getCell(h.get(key));if(old!=null)row.removeCell(old);var c=row.createCell(h.get(key),value instanceof Number?CellType.NUMERIC:CellType.STRING);if(value instanceof Number n)c.setCellValue(n.doubleValue());else c.setCellValue(value.toString());}

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void exportsAllCandidatesAndKeepsReferenceRowsSeparate() throws Exception {
        byte[] bytes=makeTemplate();var p=workflow.preview(bytes);
        assertThat(p.candidateCount()).isEqualTo(33);assertThat(p.referenceOnlyCount()).isEqualTo(6);assertThat(p.reviewInputCount()).isZero();
        assertThat(p.valid()).isTrue();assertThat(p.routeCount()).isZero();
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(java.util.stream.IntStream.range(0,wb.getNumberOfSheets()).mapToObj(wb::getSheetName).toList())
                    .containsExactlyElementsOf(Step7WholeNetworkReviewWorkbookExporter.SHEETS);
            var s=wb.getSheetAt(0);Map<String,Integer> h=columns(s);Set<String> ids=new java.util.HashSet<>();int missing=0;
            for(int i=2;i<=s.getLastRowNum();i++){var r=s.getRow(i);if(r==null)continue;ids.add(value(r,h,"candidate_id"));if(value(r,h,"origin_class").contains("누락 8건"))missing++;}
            assertThat(ids).hasSize(33);assertThat(missing).isEqualTo(8);
            for(int i=2;i<=s.getLastRowNum();i++){
                var r=s.getRow(i);if(r==null)continue;
                assertThat(value(r,h,"case_key")).isNotBlank();
                assertThat(value(r,h,"snapshot_id")).hasSize(64);
                assertThat(value(r,h,"source_equipment")).isNotBlank();assertThat(value(r,h,"target_equipment")).isNotBlank();
                assertThat(value(r,h,"db_source_row")).isNotBlank();assertThat(value(r,h,"official_drawing_sheet")).isNotBlank();
            }
            var refs=wb.getSheetAt(4);Map<String,Integer> refsHeader=columns(refs);List<String> referenceRows=new java.util.ArrayList<>();Set<String> referenceDbRows=new java.util.HashSet<>();
            for(int i=2;i<=refs.getLastRowNum();i++){var row=refs.getRow(i);if(row!=null&&value(row,refsHeader,"record_class").startsWith("참고전용")){referenceRows.add(value(row,refsHeader,"candidate_or_reference_id"));referenceDbRows.add(value(row,refsHeader,"db_source_row"));}}
            assertThat(referenceRows).hasSize(6).containsOnly("REFERENCE_ONLY_NOT_STEP7-4A_CANDIDATE");
            assertThat(referenceDbRows).containsExactlyInAnyOrder("3024","2702","2706","2709","3022","3023");
            assertHeadersEqual(wb.getSheetAt(1),"ROUTE_HEADERS");assertHeadersEqual(wb.getSheetAt(2),"QUANTITY_HEADERS");assertHeadersEqual(wb.getSheetAt(3),"REVIEW_HEADERS");
            int formulas=0;for(var sheet:wb)for(var row:sheet)for(var cell:row){if(cell.getCellType()==CellType.FORMULA)formulas++;if(cell.getCellType()!=CellType.BLANK){var font=wb.getFontAt(cell.getCellStyle().getFontIndexAsInt());assertThat(font.getFontName()).isEqualTo("맑은 고딕");assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());}}
            assertThat(formulas).isZero();
        }
        var apiPreview=mvc.perform(multipart(API+"/network-review/preview").file(new MockMultipartFile("reviewWorkbook","review.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.valid").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidateCount").value(33)).andReturn();
        assertThat(apiPreview.getResponse().getContentAsString()).contains("TEST_ONLY");
        byte[] source=Files.readAllBytes(Path.of(System.getProperty("step7.whole.source")));
        var upload=new MockMultipartFile("teamWorkbook","synthetic-ascii-source.xlsm","application/vnd.ms-excel.sheet.macroEnabled.12",source);
        mvc.perform(multipart(API+"/network-review-workbook").file(upload)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(API+"/network-review/candidate-preview").file(upload)).andExpect(status().isUnauthorized());
        var previewResponse=mvc.perform(multipart(API+"/network-review/candidate-preview").file(upload).header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.teamConnectionRows").value(3082))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.officialProcessWorkbookCount").value(15))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.officialMasterWorkbookCount").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.officialEquipmentCount").value(1332))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pw0105CandidateCount").value(33))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidenceVerifiedCandidateCount").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].dbSourceRow").exists())
                .andReturn();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isZero();
        var response=mvc.perform(multipart(API+"/network-review-workbook").file(upload).header("X-Admin-Token",adminToken)).andExpect(status().isOk()).andReturn();
        try(var downloaded=WorkbookFactory.create(new ByteArrayInputStream(response.getResponse().getContentAsByteArray()))) {
            assertThat(java.util.stream.IntStream.range(0,downloaded.getNumberOfSheets()).mapToObj(downloaded::getSheetName).toList())
                    .containsExactlyElementsOf(Step7WholeNetworkReviewWorkbookExporter.SHEETS);
        }
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void acceptsTwoDistinctRoutesAndPersistsTypedHistoryButNeverApprovesOrCalculates() throws Exception {
        byte[] template=makeTemplate();String candidate="PW0105-AUTO-DBROW-2638";byte[] input=editTwoRoutes(template,candidate);
        var p=workflow.preview(input);assertThat(p.valid()).as("issues=%s",p.issues()).isTrue();assertThat(p.routeCount()).isEqualTo(2);
        var result=workflow.importWorkbook(input);assertThat(result.valid()).isTrue();assertThat(result.rowsPersisted()).isEqualTo(1);assertThat(result.officialApprovalCreated()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_route WHERE snapshot_id=? AND candidate_id=?",Integer.class,p.snapshotId(),candidate)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT route_id) FROM balance_evidence_review_route WHERE snapshot_id=? AND candidate_id=?",Integer.class,p.snapshotId(),candidate)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_history WHERE candidate_id=?",Integer.class,candidate)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_flow",Integer.class)).isZero();
        var status=ledger.status(p.snapshotId(),candidate);assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("REVIEWED_PROVISIONAL");
        assertThat(workflow.importWorkbook(input).rowsPersisted()).isZero();
        assertThat(ledger.history(p.snapshotId(),candidate)).hasSize(1);
        var profiles=workflow.routeProfiles(p.snapshotId(),candidate);
        assertThat(profiles).hasSize(2).extracting(x->x.get("routeId")).containsExactly("SYNTHETIC-ROUTE-A","SYNTHETIC-ROUTE-B");
        assertThat(profiles).allSatisfy(x->{assertThat(((Number)x.get("reviewVersion")).longValue()).isEqualTo(((Number)x.get("historyReviewVersion")).longValue());assertThat(((Number)x.get("currentReviewVersion")).longValue()).isEqualTo(((Number)x.get("reviewVersion")).longValue());});
        mvc.perform(get(API+"/review-snapshots/"+p.snapshotId()+"/candidates/"+candidate+"/route-profiles")).andExpect(status().isUnauthorized());
        mvc.perform(get(API+"/review-snapshots/"+p.snapshotId()+"/candidates/"+candidate+"/route-profiles").header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].routeId").value("SYNTHETIC-ROUTE-A"));
        var report=networkWorkflow.buildIntegrationReport(p.snapshotId());
        assertThat(report.actualEligibleConnectionCount()).isZero();
        assertThat(report.actualReadiness().status()).isEqualTo("CALCULATION_UNAVAILABLE");
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void persistsTypedEvidenceToReviewVersionAndHistoryButNotOfficialApproval() throws Exception {
        byte[] template=makeTemplate();String candidate="PW0105-AUTO-DBROW-2638";byte[] input=editCompleteEvidence(template,candidate);
        var p=workflow.preview(input);assertThat(p.valid()).as("issues=%s",p.issues()).isTrue();
        var result=workflow.importWorkbook(input);assertThat(result.valid()).isTrue();assertThat(result.rowsPersisted()).isEqualTo(1);assertThat(result.officialApprovalCreated()).isFalse();
        var status=ledger.status(p.snapshotId(),candidate);assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("EVIDENCE_VERIFIED");
        var profiles=workflow.routeProfiles(p.snapshotId(),candidate);assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0)).containsEntry("routeId","SYNTHETIC-EVIDENCE-ROUTE").containsEntry("routeReviewState","ROUTE_DETAILS_RECORDED");
        var evidence=workflow.evidenceHistory(p.snapshotId(),candidate);assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0)).containsEntry("evidenceType","FIELD_INSPECTION").containsEntry("evidenceReference","SYNTHETIC-FIELD-RECORD-1 p.1")
                .containsEntry("verifiedBy","synthetic-reviewer").containsEntry("verifiedAt",java.sql.Timestamp.valueOf("2026-10-26 10:00:00"))
                .containsEntry("routeId","SYNTHETIC-EVIDENCE-ROUTE").containsEntry("provenanceState","EVIDENCE_TYPED_RECORDED_NOT_OFFICIAL_APPROVAL");
        assertThat(ledger.history(p.snapshotId(),candidate)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case WHERE official_approval_created=TRUE",Integer.class)).isZero();
        mvc.perform(get(API+"/review-snapshots/"+p.snapshotId()+"/candidates/"+candidate+"/route-profiles")).andExpect(status().isUnauthorized());
        mvc.perform(get(API+"/review-snapshots/"+p.snapshotId()+"/candidates/"+candidate+"/route-profiles").header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].routeId").value("SYNTHETIC-EVIDENCE-ROUTE"));
        mvc.perform(get(API+"/review-snapshots/"+p.snapshotId()+"/candidates/"+candidate+"/evidence-provenance-history").header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].evidenceType").value("FIELD_INSPECTION"));
        var report=networkWorkflow.buildIntegrationReport(p.snapshotId());assertThat(report.actualEligibleConnectionCount()).isZero();
        assertThat(report.actualReadiness().status()).isEqualTo("CALCULATION_UNAVAILABLE");
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void downgradesVerifiedSuggestionWhenRequiredEvidenceIsIncomplete() throws Exception {
        byte[] template=makeTemplate();String candidate="PW0105-AUTO-DBROW-2638";byte[] input=editCompleteEvidence(template,candidate);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(input));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("② 배관 경로 및 순환 확인");var h=columns(s);int row=find(s,h.get("candidate_id"),candidate);set(s.getRow(row),h,"scope_all_outflows","NO");wb.write(out);input=out.toByteArray();
        }
        var preview=workflow.preview(input);assertThat(preview.valid()).isTrue();
        var result=workflow.importWorkbook(input);assertThat(result.valid()).isTrue();assertThat(result.rowsPersisted()).isEqualTo(1);
        var status=ledger.status(preview.snapshotId(),candidate);assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(workflow.routeProfiles(preview.snapshotId(),candidate)).singleElement().satisfies(r->assertThat(r.get("routeReviewState")).isEqualTo("NEEDS_MORE_INFO"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void rejectsStaleVersionDuplicateRouteUnknownCandidateAndUnsafePackages() throws Exception {
        byte[] template=makeTemplate();String candidate="PW0105-AUTO-DBROW-2638";
        byte[] stale=template;try(var wb=WorkbookFactory.create(new ByteArrayInputStream(stale));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("④ 증빙자료 및 담당자 검토");var h=columns(s);int row=find(s,h.get("candidate_id"),candidate);set(s.getRow(row),h,"expected_review_version",7d);wb.write(out);stale=out.toByteArray();
        }
        assertThat(workflow.preview(stale).issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("REVIEW_VERSION_CONFLICT"));
        byte[] duplicate=editTwoRoutes(template,candidate);try(var wb=WorkbookFactory.create(new ByteArrayInputStream(duplicate));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("② 배관 경로 및 순환 확인");var h=columns(s);int row=find(s,h.get("route_id"),"SYNTHETIC-ROUTE-B");set(s.getRow(row),h,"route_id","SYNTHETIC-ROUTE-A");
            var q=wb.getSheet("③ 유량·단위·기준 기간 확인");var qh=columns(q);int qrow=find(q,qh.get("route_id"),"SYNTHETIC-ROUTE-B");set(q.getRow(qrow),qh,"route_id","SYNTHETIC-ROUTE-A");wb.write(out);duplicate=out.toByteArray();
        }
        assertThat(workflow.preview(duplicate).issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("DUPLICATE_ROUTE_ID"));
        byte[] unknown=template;try(var wb=WorkbookFactory.create(new ByteArrayInputStream(unknown));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("① 전체 연결 후보 33건");var h=columns(s);set(s.getRow(2),h,"candidate_id","PW0105-AUTO-DBROW-9999");wb.write(out);unknown=out.toByteArray();
        }
        assertThat(workflow.preview(unknown).issues()).anySatisfy(i->assertThat(i.code()).isIn("CANDIDATE_SET_MISMATCH","SOURCE_TRACE_MISMATCH"));
        byte[] formula=setCell(template,"① 전체 연결 후보 33건",2,0,"1+1");
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(formula));var out=new ByteArrayOutputStream()){
            wb.getSheetAt(0).getRow(2).getCell(0).setCellFormula("1+1");wb.write(out);formula=out.toByteArray();
        }
        byte[] formulaInput=formula;assertThatThrownBy(()->workflow.preview(formulaInput)).isInstanceOf(InvalidWorkbookException.class);
        byte[] macro=addUnsafeZipEntry(template,"xl/vbaProject.bin","synthetic");
        assertThatThrownBy(()->workflow.preview(macro)).isInstanceOf(InvalidWorkbookException.class);
        byte[] externalLink=addUnsafeZipEntry(template,"xl/externalLinks/externalLink1.xml","<externalLink/>");
        assertThatThrownBy(()->workflow.preview(externalLink)).isInstanceOf(InvalidWorkbookException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case",Integer.class)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void rejectsStaleSnapshotMissingQuantityProvenanceAndConflictingEndpoints() throws Exception {
        byte[] template=makeTemplate();
        byte[] stale=template;
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(stale));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("① 전체 연결 후보 33건");var h=columns(s);
            for(int i=2;i<=s.getLastRowNum();i++){var row=s.getRow(i);if(row!=null)set(row,h,"snapshot_id","0".repeat(64));}
            wb.write(out);stale=out.toByteArray();
        }
        assertThat(workflow.preview(stale).issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("STALE_SNAPSHOT"));

        String candidate="PW0105-AUTO-DBROW-2638";byte[] missingUnit=editTwoRoutes(template,candidate);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(missingUnit));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("③ 유량·단위·기준 기간 확인");var h=columns(s);int row=find(s,h.get("candidate_id"),candidate);
            set(s.getRow(row),h,"unit","");set(s.getRow(row),h,"period","");wb.write(out);missingUnit=out.toByteArray();
        }
        var missingPreview=workflow.preview(missingUnit);assertThat(missingPreview.valid()).isFalse();
        assertThat(missingPreview.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("QUANTITY_PROVENANCE_REQUIRED"));

        byte[] endpointConflict=editTwoRoutes(template,candidate);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(endpointConflict));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("② 배관 경로 및 순환 확인");var h=columns(s);int row=find(s,h.get("route_id"),"SYNTHETIC-ROUTE-A");
            set(s.getRow(row),h,"source_equipment","UNVERIFIED-OTHER-EQUIPMENT");wb.write(out);endpointConflict=out.toByteArray();
        }
        var conflictPreview=workflow.preview(endpointConflict);assertThat(conflictPreview.valid()).isFalse();
        assertThat(conflictPreview.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("ROUTE_ENDPOINT_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case",Integer.class)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.whole.source",matches=".+")
    void blocksTextQuantityAndStaleVersionAndLeavesActualNetworkUnavailable() throws Exception {
        byte[] template=makeTemplate();String candidate="PW0105-AUTO-DBROW-2638";byte[] input=editTwoRoutes(template,candidate);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(input));var out=new ByteArrayOutputStream()){
            var s=wb.getSheet("③ 유량·단위·기준 기간 확인");var h=columns(s);int row=find(s,h.get("candidate_id"),candidate);s.getRow(row).getCell(h.get("quantity")).setCellValue("10");wb.write(out);input=out.toByteArray();
        }
        var invalid=workflow.preview(input);assertThat(invalid.valid()).isFalse();assertThat(invalid.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("NUMERIC_CELL_REQUIRED"));
        byte[] compatible=schemaV2Exporter.exportSchemaV2Bridge(ledger.schemaV2Template(ledger.latestSnapshotId(),List.of(candidate)));
        assertThat(ledger.validateWorkbook(compatible,null).valid()).isTrue();
        mvc.perform(multipart(API+"/network-review/preview").file(new MockMultipartFile("reviewWorkbook","review.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",template)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_route",Integer.class)).isZero();
    }
}
