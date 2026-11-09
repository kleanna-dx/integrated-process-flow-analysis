package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.InvalidWorkbookException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Step7FieldReviewBridgeIntegrationTest {
    private static final String ROUTE="/api/admin/step7-3/pw-01-05/field-review";
    private static final String OUTPUT_NAME="PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx";
    @Autowired private Step7FieldReviewBridgeService bridge;
    @Autowired private Step7ReviewLedgerWorkflowService ledger;
    @Autowired private Step7ConnectionEvidenceWorkbookExporter schemaExporter;
    @Autowired private Step7FieldReviewBridgeWorkbookExporter resultExporter;
    @Autowired private Step7ReviewNetworkWorkflowService network;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    private Path source() { return Path.of(System.getProperty("step7.bridge.source")); }
    private byte[] actualWorkbook() throws Exception { return Files.readAllBytes(source()); }
    private byte[] edit(byte[] bytes,String candidateId,Map<String,Object> fields) throws Exception {
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            var sheet=workbook.getSheet("⑤ 담당자 확인 결과");
            DataFormatter formatter=new DataFormatter();
            Map<String,Integer> columns=new LinkedHashMap<>();
            for(var cell:sheet.getRow(3))columns.put(formatter.formatCellValue(cell).trim(),cell.getColumnIndex());
            int idColumn=columns.get("candidate_id (Step7-4A)");
            for(int rowIndex=4;rowIndex<=sheet.getLastRowNum();rowIndex++) {
                var row=sheet.getRow(rowIndex);if(row==null)continue;
                if(candidateId.equals(formatter.formatCellValue(row.getCell(idColumn)))) {
                    for(var entry:fields.entrySet()) {
                        Integer column=columns.get(entry.getKey());
                        if(column==null)throw new IllegalArgumentException("field header missing: "+entry.getKey());
                        var previous=row.getCell(column);
                        var style=previous==null?null:previous.getCellStyle();
                        if(previous!=null)row.removeCell(previous);
                        Object value=entry.getValue();
                        var cell=row.createCell(column,value instanceof Number?CellType.NUMERIC:CellType.STRING);
                        if(style!=null)cell.setCellStyle(style);
                        if(value instanceof Number n)cell.setCellValue(n.doubleValue());else if(value!=null)cell.setCellValue(value.toString());
                        if(value instanceof Number)assertThat(cell.getCellType()).as(entry.getKey()).isEqualTo(CellType.NUMERIC);
                        else assertThat(cell.getStringCellValue()).as("immediate field write: %s col=%d cell=%s",entry.getKey(),column,cell.getAddress()).isEqualTo(value==null?"":value.toString());
                    }
                    workbook.write(out);
                    byte[] edited=out.toByteArray();
                    try(var verified=WorkbookFactory.create(new ByteArrayInputStream(edited))) {
                        var verifiedSheet=verified.getSheet("⑤ 담당자 확인 결과");
                        for(int rowNumber=4;rowNumber<=verifiedSheet.getLastRowNum();rowNumber++) {
                            var verifiedRow=verifiedSheet.getRow(rowNumber);
                            if(verifiedRow!=null&&candidateId.equals(formatter.formatCellValue(verifiedRow.getCell(idColumn)))) {
                                for(var entry:fields.entrySet()) {
                                    var cell=verifiedRow.getCell(columns.get(entry.getKey()));
                                    if(entry.getValue() instanceof Number)assertThat(cell.getCellType()).as(entry.getKey()).isEqualTo(CellType.NUMERIC);
                                    else {
                                        String expected=entry.getValue()==null?"":entry.getValue().toString();
                                        String actual=formatter.formatCellValue(cell);
                                        assertThat(actual).as("field=%s, expectedCodePoints=%s, actualCodePoints=%s",entry.getKey(),expected.codePoints().mapToObj(Integer::toHexString).toList(),actual.codePoints().mapToObj(Integer::toHexString).toList()).isEqualTo(expected);
                                    }
                                }
                                return edited;
                            }
                        }
                    }
                    throw new IllegalStateException("edited candidate row was not found after POI round trip: "+candidateId);
                }
            }
            throw new IllegalArgumentException("candidate row missing: "+candidateId);
        }
    }
    private Map<String,Object> completeProvisional(String source,String target,String route) {
        return fields("실제 연결 확인","예","실제 출발 endpoint",source,"실제 도착 endpoint",target,"물리 경로/배관 ID",route,"방향 확인 근거","승인된 P&ID 배관 표식 및 현장 양끝 추적 기록","실제 물질 종류","일반폐수","확인 유량(숫자)",0d,"확인 단위","ton/day","기준 기간/시작-종료","2026-10-01/2026-10-02","유량 출처/계량기 ID","SYNTHETIC-METER-01","증빙 문서 ID/페이지","SYNTHETIC-EVIDENCE-01 p.1","증빙 설명/현장 위치","합성 TEST_ONLY fixture; 실제 현장 근거 아님","확인자","synthetic-reviewer","확인일(YYYY-MM-DD)","2026-10-23","담당자 검토결과(제안)","REVIEWED_PROVISIONAL","추가 확인사항/불일치 이유","합성 시험값; 실제 연결 승인 아님");
    }
    private MockMultipartFile file(byte[] bytes,String name,String contentType) {
        return new MockMultipartFile("fieldWorkbook",name,contentType,bytes);
    }
    private byte[] withZipEntry(byte[] input,String name,String value) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(input));ZipOutputStream out=new ZipOutputStream(bytes)) {
            byte[] buffer=new byte[8192];ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {out.putNextEntry(new ZipEntry(entry.getName()));int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.closeEntry();}
            out.putNextEntry(new ZipEntry(name));out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
        }
        return bytes.toByteArray();
    }
    private Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void actualStep75aWorkbookMapsOnlyExistingSnapshotCandidatesAndExportsReadOnlyReport() throws Exception {
        byte[] input=actualWorkbook();String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        var preview=bridge.preview(input);
        assertThat(preview.snapshotCandidateCount()).isEqualTo(33);
        assertThat(preview.mappingSuccessCount()).isEqualTo(25);
        var mappedIds=preview.mappingRows().stream().filter(Step7FieldReviewBridgeService.MappingResult::mapped).map(Step7FieldReviewBridgeService.MappingResult::candidateId).collect(java.util.stream.Collectors.toSet());
        var missingIds=preview.mappingRows().stream().filter(row->"NOT_PRESENT_IN_FIELD_WORKBOOK".equals(row.status())).map(Step7FieldReviewBridgeService.MappingResult::candidateId).collect(java.util.stream.Collectors.toSet());
        assertThat(mappedIds).hasSize(25);
        assertThat(missingIds).hasSize(8).doesNotContainAnyElementsOf(mappedIds);
        assertThat(java.util.stream.Stream.concat(mappedIds.stream(),missingIds.stream()).collect(java.util.stream.Collectors.toSet())).hasSize(33);
        assertThat(preview.unmappedCount()).isZero();
        assertThat(preview.referenceOnlyCount()).isEqualTo(6);
        assertThat(preview.missingFromFieldWorkbookCount()).isEqualTo(8);
        assertThat(preview.inputRowCount()).isZero();
        assertThat(preview.schemaV2ConvertibleCount()).isZero();
        assertThat(preview.sourceUploadSha256()).isEqualTo(sha);
        for(int row:List.of(2638,2683,2710))assertThat(preview.mappingRows()).anySatisfy(m->{assertThat(m.dbRow()).isEqualTo(row);assertThat(m.mapped()).isTrue();assertThat(m.candidateId()).isEqualTo("PW0105-AUTO-DBROW-"+row);});
        assertThat(bridge.importResults(input).rowsPersisted()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case",Integer.class)).isZero();
        var reportBytes=bridge.resultWorkbook(input,null);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(reportBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("① 현장 확인 입력 현황");
            assertThat(wb.getSheet("④ schema-v2 변환 결과").getRow(1).getCell(4).getStringCellValue()).isEqualTo("NO_FIELD_INPUT");
            for(var sheet:wb)for(var row:sheet)for(var cell:row)assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
        }
        if(System.getProperty("step7.bridge.output")!=null) {
            Path output=Path.of(System.getProperty("step7.bridge.output"));
            Files.write(output,reportBytes);
            byte[] saved=Files.readAllBytes(output);
            try(var reopened=WorkbookFactory.create(new ByteArrayInputStream(saved))) {
                assertThat(java.util.stream.IntStream.range(0,reopened.getNumberOfSheets()).mapToObj(reopened::getSheetName).toList())
                        .containsExactly("① 현장 확인 입력 현황","② 기존 후보 자동 매핑 결과","③ 입력 오류 및 추가 확인 필요","④ schema-v2 변환 결과","⑤ 검토 이력 반영 결과");
                assertThat(reopened.getNumberOfSheets()).isEqualTo(5);
                assertThat(new DataFormatter().formatCellValue(reopened.getSheetAt(0).getRow(9).getCell(1))).isEqualTo("0");
                int formulas=0;
                for(var sheet:reopened)for(var row:sheet)for(var cell:row) {
                    if(cell.getCellType()==CellType.FORMULA)formulas++;
                    if(cell.getCellType()!=CellType.BLANK) {
                        var font=reopened.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                        assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                        assertThat(font.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
                    }
                }
                assertThat(formulas).isZero();
            }
        }
        var actual=network.buildIntegrationReport(preview.snapshotId());
        assertThat(actual.actualCandidateCount()).isEqualTo(33);
        assertThat(actual.actualEvidenceVerifiedCount()).isZero();
        assertThat(actual.actualEligibleConnectionCount()).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void mapsFieldInputToSchemaV2AndPersistsHistoryIdempotentlyWithoutApproval() throws Exception {
        byte[] original=actualWorkbook();
        byte[] input=edit(original,"PW0105-AUTO-DBROW-2638",completeProvisional("C-PW13022","C-PW15001","P2638-LINE-A"));
        var preview=bridge.preview(input);
        assertThat(preview.inputRowCount()).isEqualTo(1);
        assertThat(preview.mappingSuccessCount()).isEqualTo(25);
        assertThat(preview.schemaV2Valid()).isTrue();
        assertThat(preview.schemaV2ConvertibleCount()).as("conversion rows=%s; input issues=%s; schema issues=%s",preview.conversionRows(),preview.validationIssues(),preview.schemaV2Validation()).isEqualTo(1);
        var imported=bridge.importResults(input);
        assertThat(imported.valid()).isTrue();assertThat(imported.rowsPersisted()).isEqualTo(1);assertThat(imported.officialApprovalCreated()).isFalse();
        String snapshot=preview.snapshotId();String candidate="PW0105-AUTO-DBROW-2638";
        var status=ledger.status(snapshot,candidate);
        assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("REVIEWED_PROVISIONAL");
        assertThat(ledger.history(snapshot,candidate)).hasSize(1);
        var repeated=bridge.importResults(input);
        assertThat(repeated.valid()).isTrue();assertThat(repeated.rowsPersisted()).isZero();assertThat(repeated.rowsIgnored()).isEqualTo(1);
        assertThat(ledger.history(snapshot,candidate)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_flow",Integer.class)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void missingUnitPeriodAndEvidenceDowngradeVerifiedSuggestionToNeedsMoreInfo() throws Exception {
        byte[] input=edit(actualWorkbook(),"PW0105-AUTO-DBROW-2683",fields("실제 연결 확인","예","실제 출발 endpoint","C-PW14032","실제 도착 endpoint","C-PW15001","물리 경로/배관 ID","P2683-TEST","방향 확인 근거","방향 추적 fixture","실제 물질 종류","일반폐수","확인 유량(숫자)",0d,"유량 출처/계량기 ID","TEST-METER","증빙 문서 ID/페이지","TEST-EVIDENCE","증빙 설명/현장 위치","합성만; 미검증","확인자","synthetic-reviewer","확인일(YYYY-MM-DD)","2026-10-23","담당자 검토결과(제안)","EVIDENCE_VERIFIED","추가 확인사항/불일치 이유","합성 입력"));
        var preview=bridge.preview(input);
        assertThat(preview.evidenceInsufficientCount()).as("conversion rows=%s; input issues=%s; schema issues=%s",preview.conversionRows(),preview.validationIssues(),preview.schemaV2Validation()).isEqualTo(1);
        assertThat(preview.schemaV2ConvertibleCount()).as("conversion rows=%s; input issues=%s; schema issues=%s",preview.conversionRows(),preview.validationIssues(),preview.schemaV2Validation()).isEqualTo(1);
        assertThat(preview.conversionRows()).anySatisfy(r->{assertThat(r.reviewDecision()).isEqualTo("EVIDENCE_VERIFIED");assertThat(r.status()).isEqualTo("READY_AS_NEEDS_MORE_INFO");});
        var imported=bridge.importResults(input);
        assertThat(imported.valid()).isTrue();assertThat(imported.rowsPersisted()).isEqualTo(1);
        var status=ledger.status(preview.snapshotId(),"PW0105-AUTO-DBROW-2683");
        assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(((Map<?,?>)status.get("review")).get("officialApprovalCreated")).isEqualTo(false);
        assertThat(ledger.history(preview.snapshotId(),"PW0105-AUTO-DBROW-2683")).hasSize(1);
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void invalidEndpointNumericTextNewCandidateAndDuplicateOrUnknownRouteNeverPersist() throws Exception {
        byte[] original=actualWorkbook();
        byte[] invalidEquipment=edit(original,"PW0105-AUTO-DBROW-2638",completeProvisional("C-PW99999","C-PW15001","BAD-EQUIP"));
        var equipmentPreview=bridge.preview(invalidEquipment);
        assertThat(equipmentPreview.inputErrorCount()).isEqualTo(1);
        assertThat(equipmentPreview.validationIssues()).anySatisfy(i->assertThat(i.code()).isEqualTo("UNKNOWN_EQUIPMENT"));
        assertThat(bridge.importResults(invalidEquipment).rowsPersisted()).isZero();

        byte[] textQuantity=edit(original,"PW0105-AUTO-DBROW-2683",completeProvisional("C-PW14032","C-PW15001","TEXT-QUANTITY"));
        textQuantity=edit(textQuantity,"PW0105-AUTO-DBROW-2683",Map.of("확인 유량(숫자)","0"));
        var numericPreview=bridge.preview(textQuantity);
        assertThat(numericPreview.validationIssues()).as("Only quantity is text; other fields: %s",numericPreview.validationIssues()).anySatisfy(i->assertThat(i.code()).isEqualTo("NUMERIC_CELL_REQUIRED"));
        assertThat(bridge.importResults(textQuantity).rowsPersisted()).isZero();

        byte[] newCandidate=edit(original,"REFERENCE_ONLY_NOT_STEP7-4A_CANDIDATE",fields("실제 연결 확인","예","실제 출발 endpoint","C-PW15018","실제 도착 endpoint","C-PW15002","물리 경로/배관 ID","NEW-LINE-1","방향 확인 근거","synthetic","실제 물질 종류","폐수","확인 유량(숫자)",1d,"확인 단위","ton/day","기준 기간/시작-종료","TEST","유량 출처/계량기 ID","M","증빙 문서 ID/페이지","EVID","증빙 설명/현장 위치","not real","확인자","test","확인일(YYYY-MM-DD)","2026-10-23","담당자 검토결과(제안)","REVIEWED_PROVISIONAL","추가 확인사항/불일치 이유","new route"));
        var newPreview=bridge.preview(newCandidate);
        assertThat(newPreview.unmappedCount()).isEqualTo(1);
        assertThat(newPreview.validationIssues()).anySatisfy(i->assertThat(i.code()).isEqualTo("NEW_CANDIDATE_REVIEW_REQUIRED"));
        assertThat(bridge.importResults(newCandidate).rowsPersisted()).isZero();

        assertThat(Step7FieldReviewBridgeService.routeIdentity("C-PW15001","C-PW15011","LINE-A"))
                .isNotEqualTo(Step7FieldReviewBridgeService.routeIdentity("C-PW15001","C-PW15011","LINE-B"));
        assertThat(Step7FieldReviewBridgeService.routeIdentity("C-PW15001","C-PW15011","LINE-A"))
                .isEqualTo(Step7FieldReviewBridgeService.routeIdentity("C-PW15011","C-PW15001","LINE-A"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case",Integer.class)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void versionConflictsAndUnsafeWorkbookPackagesAreRejected() throws Exception {
        byte[] input=actualWorkbook();var preview=bridge.preview(input);
        var template=ledger.schemaV2Template(preview.snapshotId(),List.of("PW0105-AUTO-DBROW-2638"));
        byte[] schema=schemaExporter.exportSchemaV2Bridge(template);
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(schema));var out=new ByteArrayOutputStream()) {
            wb.getSheet("담당자 검토대장").getRow(2).getCell(3).setCellValue("V0.9");wb.write(out);schema=out.toByteArray();
        }
        var stale=ledger.validateWorkbook(schema,null);
        assertThat(stale.valid()).isFalse();assertThat(stale.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("BASELINE_VERSION_MISMATCH"));
        byte[] withFormula=edit(input,"PW0105-AUTO-DBROW-2638",Map.of("확인 유량(숫자)",0d));
        try(var wb=WorkbookFactory.create(new ByteArrayInputStream(withFormula));var out=new ByteArrayOutputStream()) {
            var sheet=wb.getSheet("⑤ 담당자 확인 결과");for(int r=4;r<=sheet.getLastRowNum();r++)if("PW0105-AUTO-DBROW-2638".equals(new DataFormatter().formatCellValue(sheet.getRow(r).getCell(0)))){sheet.getRow(r).getCell(18).setCellFormula("1+2");break;}wb.write(out);withFormula=out.toByteArray();
        }
        byte[] finalFormula=withFormula;
        assertThatThrownBy(()->bridge.preview(finalFormula)).isInstanceOf(InvalidWorkbookException.class);
        byte[] macro=withZipEntry(input,"xl/vbaProject.bin","synthetic macro payload");
        assertThatThrownBy(()->bridge.preview(macro)).isInstanceOf(InvalidWorkbookException.class);
        byte[] external=withZipEntry(input,"xl/externalLinks/externalLink1.xml","<externalLink/>");
        assertThatThrownBy(()->bridge.preview(external)).isInstanceOf(InvalidWorkbookException.class);
    }

    @Test
    @EnabledIfSystemProperty(named="step7.bridge.source",matches=".+")
    void existingStep74UploadAndAuthRoutesRemainCompatible() throws Exception {
        byte[] bytes=actualWorkbook();
        mvc.perform(multipart(ROUTE+"/preview").file(file(bytes,"field.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(status().isUnauthorized());
        MvcResult response=mvc.perform(multipart(ROUTE+"/preview").file(file(bytes,"field.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andReturn();
        var preview=mapper.readValue(response.getResponse().getContentAsByteArray(),Step7FieldReviewBridgeService.BridgePreview.class);
        assertThat(preview.mappingSuccessCount()).isEqualTo(25);
        mvc.perform(multipart(ROUTE+"/report").file(file(bytes,"field.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(result->{
                    String disposition=result.getResponse().getHeader("Content-Disposition");
                    assertThat(disposition).isNotBlank();
                    assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo(OUTPUT_NAME);
                });
        assertThat(ledger.validateWorkbook(schemaExporter.exportSchemaV2Bridge(ledger.schemaV2Template(preview.snapshotId(),List.of("PW0105-AUTO-DBROW-2638"))),null).valid()).isTrue();
    }
}
