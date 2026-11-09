package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.processautomation.excel.InvalidWorkbookException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.MediaType;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Step7ReviewLedgerWorkflowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private Step7ConnectionEvidenceService evidenceService;
    @Autowired private Step7ConnectionEvidenceWorkbookExporter exporter;
    @Autowired private Step7ReviewLedgerWorkflowService workflow;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Value("${app.baseline.admin-token:}") private String adminToken;

    private Path source() throws Exception {
        Path configured=Path.of(System.getProperty("step7.review.source"));
        if(Files.isDirectory(configured))try(var files=Files.list(configured)){return files.filter(p->p.getFileName().toString().endsWith(".xlsm")).findFirst().orElseThrow();}
        return configured;
    }

    private byte[] realTemplate() throws Exception {
        Path source=source();
        var data=evidenceService.buildWorkbook(Files.readAllBytes(source),source.getFileName().toString());
        var snapshot=workflow.createSnapshot(data);
        assertThat(data.candidates()).hasSize(33);
        assertThat(snapshot.rows()).hasSize(33);
        return exporter.export(data,snapshot);
    }

    private byte[] edit(byte[] input, int rowIndex, String status, boolean completeEvidence, boolean includePeriod) throws Exception {
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(input));var out=new java.io.ByteArrayOutputStream()) {
            var sheet=workbook.getSheet("담당자 검토대장");var row=sheet.getRow(rowIndex);
            row.getCell(20).setCellValue("합성 시험폐수");
            row.getCell(21).setCellValue(0d);
            row.getCell(22).setCellValue("ton/day");
            row.getCell(23).setCellValue(includePeriod?"2026-01-01/TEST_PERIOD":"");
            row.getCell(24).setCellValue(completeEvidence?"YES":"NO");
            row.getCell(25).setCellValue("FIELD_INSPECTION");
            row.getCell(26).setCellValue("SYNTHETIC-TEST-REF-01");
            row.getCell(27).setCellValue("합성 TEST_ONLY 검토 증빙이며 실제 현장 연결을 의미하지 않음");
            row.getCell(28).setCellValue("test-reviewer");
            row.getCell(29).setCellValue("2026-10-23");
            row.getCell(30).setCellValue("합성 검증 fixture");
            row.getCell(31).setCellValue(status);
            row.getCell(33).setCellValue("예");
            row.getCell(34).setCellValue("아니오");
            workbook.write(out);return out.toByteArray();
        }
    }

    private Step7ReviewLedgerWorkflowService.ReviewImportResult importApi(byte[] bytes) throws Exception {
        MockMultipartFile file=new MockMultipartFile("reviewWorkbook","reviewed.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);
        MvcResult result=mvc.perform(multipart("/api/admin/step7-3/pw-01-05/review-ledger").file(file)
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsByteArray(),Step7ReviewLedgerWorkflowService.ReviewImportResult.class);
    }

    private byte[] withZipEntry(byte[] workbookBytes, String entryName, String content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(workbookBytes));
             var output = new ZipOutputStream(bytes)) {
            var buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] malformedZipWorkbook() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("readme.txt"));
            output.write("not an OOXML workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    @EnabledIfSystemProperty(named="step7.review.source",matches=".+")
    void rejectsUnsafeWorkbookPackagePartsAndUnauthorizedOrInvalidUploads() throws Exception {
        byte[] validTemplate = realTemplate();
        byte[] malformed = malformedZipWorkbook();
        assertThatThrownBy(() -> workflow.importWorkbook(new byte[] {'n','o','t','-','z','i','p'}))
                .isInstanceOf(InvalidWorkbookException.class);
        assertThatThrownBy(() -> workflow.importWorkbook(malformed))
                .isInstanceOf(InvalidWorkbookException.class);

        Map<String, String> prohibitedParts = Map.of(
                "xl/vbaProject.bin", "macro",
                "xl/queryTables/queryTable1.xml", "refreshable query",
                "xl/connections.xml", "external connection",
                "xl/externalLinks/externalLink1.xml", "external workbook link",
                "xl/_rels/review-test.rels", "<Relationships><Relationship TargetMode=\"External\" Target=\"https://example.invalid/data\"/></Relationships>");
        for (var prohibited : prohibitedParts.entrySet()) {
            byte[] unsafe = withZipEntry(validTemplate, prohibited.getKey(), prohibited.getValue());
            assertThatThrownBy(() -> workflow.importWorkbook(unsafe))
                    .as("must reject package entry %s", prohibited.getKey())
                    .isInstanceOf(InvalidWorkbookException.class);
        }

        MockMultipartFile validXlsx = new MockMultipartFile("reviewWorkbook", "reviewed.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", validTemplate);
        mvc.perform(multipart("/api/admin/step7-3/pw-01-05/review-ledger").file(validXlsx))
                .andExpect(status().isUnauthorized());
        MockMultipartFile wrongExtension = new MockMultipartFile("reviewWorkbook", "reviewed.xlsm",
                "application/vnd.ms-excel.sheet.macroEnabled.12", validTemplate);
        mvc.perform(multipart("/api/admin/step7-3/pw-01-05/review-ledger").file(wrongExtension)
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isBadRequest());
        assertThat(importApi(validTemplate).valid()).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @EnabledIfSystemProperty(named="step7.review.source",matches=".+")
    void downloadableTemplatePersistsOnlyItsImmutableSnapshot() throws Exception {
        Path source = source();
        byte[] sourceBytes = Files.readAllBytes(source);
        MockMultipartFile team = new MockMultipartFile("teamWorkbook", source.getFileName().toString(),
                "application/vnd.ms-excel.sheet.macroEnabled.12", sourceBytes);
        MvcResult response = mvc.perform(multipart("/api/admin/step7-3/pw-01-05/evidence-workbook").file(team)
                        .header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Auto-Candidate-Count", "33"))
                .andReturn();
        String snapshotId = response.getResponse().getHeader("X-Review-Snapshot-Id");
        assertThat(snapshotId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_snapshot WHERE snapshot_id=?",
                Integer.class, snapshotId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_candidate WHERE snapshot_id=?",
                Integer.class, snapshotId)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_case WHERE snapshot_id=?",
                Integer.class, snapshotId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_evidence_review_history WHERE candidate_id LIKE 'PW0105-AUTO-DBROW-%'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",
                Integer.class)).isZero();

        if (System.getProperty("step7.review.output") != null) {
            Path out = Path.of(System.getProperty("step7.review.output"));
            Files.createDirectories(out);
            Files.write(out.resolve("PW0105_review_template_TEST_ONLY.xlsx"), response.getResponse().getContentAsByteArray());
        }
    }

    @Test
    @EnabledIfSystemProperty(named="step7.review.source",matches=".+")
    void downloadUploadValidationPersistenceIdempotencyAndStatusStayTestOnly() throws Exception {
        Path source=source();byte[] sourceBytes=Files.readAllBytes(source);
        String sourceHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sourceBytes));
        var officialBefore=jdbc.queryForList("SELECT id,sha256 FROM baseline_document WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0') ORDER BY id");
        int confirmedBefore=jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class);
        MockMultipartFile team=new MockMultipartFile("teamWorkbook",source.getFileName().toString(),
                "application/vnd.ms-excel.sheet.macroEnabled.12",sourceBytes);
        MvcResult response=mvc.perform(multipart("/api/admin/step7-3/pw-01-05/evidence-workbook").file(team)
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk()).andExpect(header().exists("X-Review-Snapshot-Id"))
                .andExpect(header().string("X-Auto-Candidate-Count","33")).andReturn();
        byte[] template=response.getResponse().getContentAsByteArray();
        String snapshot=response.getResponse().getHeader("X-Review-Snapshot-Id");
        assertThat(snapshot).hasSize(64);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(template))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            var ledger=workbook.getSheet("담당자 검토대장");
            assertThat(ledger.getLastRowNum()).isEqualTo(34);
            assertThat(ledger.getRow(2).getCell(4).getStringCellValue()).startsWith("PW0105-AUTO-DBROW-");
            assertThat(ledger.getRow(2).getCell(14).getStringCellValue()).isNotBlank();
            assertThat(ledger.getRow(2).getCell(17).getNumericCellValue()).isEqualTo(0d);
            assertThat(ledger.getRow(2).getCell(21).getCellType().name()).isEqualTo("BLANK");
            assertThat(ledger.getRow(2).getCell(31).getStringCellValue()).isEqualTo("UNREVIEWED");
            assertThat(ledger.getRow(2).getCell(33).getStringCellValue()).isEqualTo("예");
            assertThat(ledger.getRow(2).getCell(34).getStringCellValue()).isEqualTo("아니오");
            assertThat(ledger.getRow(2).getCell(18).getCellStyle().getFillForegroundColor())
                    .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            assertThat(workbook.getFontAt(ledger.getRow(2).getCell(18).getCellStyle().getFontIndexAsInt()).getFontName())
                    .isEqualTo("맑은 고딕");
            assertThat(workbook.getFontAt(ledger.getRow(2).getCell(18).getCellStyle().getFontIndexAsInt()).getColor())
                    .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var candidateIds = new java.util.HashSet<String>();
            for (int rowIndex = 2; rowIndex <= 34; rowIndex++) {
                var candidateRow = ledger.getRow(rowIndex);
                assertThat(candidateIds.add(candidateRow.getCell(4).getStringCellValue())).isTrue();
                assertThat(candidateRow.getCell(31).getStringCellValue()).isEqualTo("UNREVIEWED");
                assertThat(candidateRow.getCell(34).getStringCellValue()).isEqualTo("아니오");
            }
            assertThat(candidateIds).hasSize(33);
        }
        String candidateId;
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(template))) {candidateId=workbook.getSheet("담당자 검토대장").getRow(2).getCell(4).getStringCellValue();}

        byte[] unknownId=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(unknownId))) {workbook.getSheet("담당자 검토대장").getRow(2).getCell(4).setCellValue("PW0105-AUTO-DBROW-999999");try(var out=new java.io.ByteArrayOutputStream()){workbook.write(out);unknownId=out.toByteArray();}}
        var invalidId=importApi(unknownId);
        assertThat(invalidId.valid()).isFalse();assertThat(invalidId.rowsPersisted()).isZero();
        assertThat(invalidId.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("UNKNOWN_CANDIDATE"));

        byte[] unknownEquipment=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(unknownEquipment))) {workbook.getSheet("담당자 검토대장").getRow(2).getCell(18).setCellValue("C-PW99999");try(var out=new java.io.ByteArrayOutputStream()){workbook.write(out);unknownEquipment=out.toByteArray();}}
        var invalidEquipment=importApi(unknownEquipment);
        assertThat(invalidEquipment.valid()).isFalse();assertThat(invalidEquipment.rowsPersisted()).isZero();
        assertThat(invalidEquipment.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("UNKNOWN_EQUIPMENT"));

        byte[] duplicateCandidate=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(duplicateCandidate))) {var sheet=workbook.getSheet("담당자 검토대장");sheet.getRow(3).getCell(4).setCellValue(sheet.getRow(2).getCell(4).getStringCellValue());try(var out=new java.io.ByteArrayOutputStream()){workbook.write(out);duplicateCandidate=out.toByteArray();}}
        var duplicateResult=importApi(duplicateCandidate);assertThat(duplicateResult.valid()).isFalse();
        assertThat(duplicateResult.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("DUPLICATE_CANDIDATE"));

        byte[] formula=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(formula))) {workbook.getSheet("담당자 검토대장").getRow(2).getCell(21).setCellFormula("1+2");try(var out=new java.io.ByteArrayOutputStream()){workbook.write(out);formula=out.toByteArray();}}
        var formulaResult=importApi(formula);assertThat(formulaResult.valid()).isFalse();
        assertThat(formulaResult.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("FORMULA_NOT_ALLOWED"));

        byte[] conflict=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        byte[] conflictingSecond=edit(template,3,"REJECTED",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(conflict));var other=WorkbookFactory.create(new ByteArrayInputStream(conflictingSecond));var out=new java.io.ByteArrayOutputStream()) {
            var firstRow=workbook.getSheet("담당자 검토대장").getRow(2);var secondRow=workbook.getSheet("담당자 검토대장").getRow(3);
            secondRow.getCell(18).setCellValue(firstRow.getCell(18).getStringCellValue());secondRow.getCell(19).setCellValue(firstRow.getCell(19).getStringCellValue());
            secondRow.getCell(31).setCellValue("REJECTED");secondRow.getCell(30).setCellValue("synthetic conflicting route decision");workbook.write(out);conflict=out.toByteArray();
        }
        var conflictResult=importApi(conflict);assertThat(conflictResult.valid()).isFalse();
        assertThat(conflictResult.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("CONFLICTING_ROUTE_ROWS"));

        byte[] provisional=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        var first=importApi(provisional);assertThat(first.valid()).isTrue();assertThat(first.rowsPersisted()).isEqualTo(1);
        assertThat(first.officialApprovalCreated()).isFalse();
        var repeated=importApi(provisional);assertThat(repeated.valid()).isTrue();assertThat(repeated.rowsPersisted()).isZero();
        assertThat(repeated.rowsIgnored()).isGreaterThanOrEqualTo(1);
        var ledger=workbookCase(template,2);
        String caseKey=ledger.caseKey();
        Map<String,Object> status=workflow.status(snapshot,candidateId);
        assertThat(((Map<?,?>)status.get("review")).get("reviewState")).isEqualTo("REVIEWED_PROVISIONAL");
        assertThat(((Number)((Map<?,?>)status.get("review")).get("reviewVersion")).longValue()).isEqualTo(1L);
        assertThat(((Map<?,?>)status.get("review")).get("officialApprovalCreated")).isEqualTo(false);
        assertThat(new java.math.BigDecimal(((Map<?,?>)status.get("review")).get("quantity").toString())).isEqualByComparingTo("0");
        assertThat(workflow.history(snapshot,candidateId)).hasSize(1);

        byte[] stale=edit(template,2,"REVIEWED_PROVISIONAL",false,false);
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(stale))) {workbook.getSheet("담당자 검토대장").getRow(2).getCell(30).setCellValue("stale edit");try(var out=new java.io.ByteArrayOutputStream()){workbook.write(out);stale=out.toByteArray();}}
        var versionConflict=importApi(stale);assertThat(versionConflict.valid()).isFalse();
        assertThat(versionConflict.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("REVIEW_VERSION_CONFLICT"));

        byte[] missingEvidence=edit(template,3,"EVIDENCE_VERIFIED",true,false);
        var downgraded=importApi(missingEvidence);assertThat(downgraded.valid()).isTrue();
        assertThat(downgraded.warnings()).anyMatch(warning -> warning.contains("필수 연결 증빙 부족"));
        String secondCandidate;
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(template))) {secondCandidate=workbook.getSheet("담당자 검토대장").getRow(3).getCell(4).getStringCellValue();}
        var secondStatus=workflow.status(snapshot,secondCandidate);
        assertThat(((Map<?,?>)secondStatus.get("review")).get("reviewState")).isEqualTo("NEEDS_MORE_INFO");

        byte[] verifiedSynthetic=edit(template,4,"EVIDENCE_VERIFIED",true,true);
        var verified=importApi(verifiedSynthetic);assertThat(verified.valid()).isTrue();assertThat(verified.officialApprovalCreated()).isFalse();
        String thirdCandidate;
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(template))) {thirdCandidate=workbook.getSheet("담당자 검토대장").getRow(4).getCell(4).getStringCellValue();}
        var thirdStatus=workflow.status(snapshot,thirdCandidate);
        assertThat(((Map<?,?>)thirdStatus.get("review")).get("reviewState")).isEqualTo("EVIDENCE_VERIFIED");
        assertThat(((Map<?,?>)thirdStatus.get("review")).get("officialApprovalCreated")).isEqualTo(false);

        mvc.perform(get("/api/admin/step7-3/pw-01-05/review-snapshots/{snapshot}/candidates/{candidate}/status",snapshot,candidateId)
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/step7-3/pw-01-05/review-snapshots/{snapshot}/candidates/{candidate}/history",snapshot,candidateId)
                        .header("X-Admin-Token",adminToken))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action_code,review_version FROM balance_evidence_review_history WHERE case_key=? ORDER BY id",caseKey))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status='CONFIRMED'",Integer.class)).isEqualTo(confirmedBefore);
        assertThat(jdbc.queryForList("SELECT id,sha256 FROM baseline_document WHERE baseline_release_id=(SELECT id FROM baseline_release WHERE version_code='V1.0') ORDER BY id")).isEqualTo(officialBefore);
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))).isEqualTo(sourceHash);
        if(System.getProperty("step7.review.output")!=null) {
            Path out=Path.of(System.getProperty("step7.review.output"));Files.createDirectories(out);
            Path savedWorkbook=out.resolve("PW0105_review_template_TEST_ONLY.xlsx");Files.write(savedWorkbook,template);
            try(var reopened=WorkbookFactory.create(Files.newInputStream(savedWorkbook))) {
                var reopenedLedger=reopened.getSheet("담당자 검토대장");
                assertThat(reopened.getNumberOfSheets()).isEqualTo(5);
                assertThat(reopenedLedger.getLastRowNum()).isEqualTo(34);
                assertThat(reopenedLedger.getRow(1).getCell(4).getStringCellValue()).isEqualTo("candidate_id");
                assertThat(reopenedLedger.getRow(2).getCell(4).getStringCellValue()).startsWith("PW0105-AUTO-DBROW-");
                assertThat(reopenedLedger.getRow(2).getCell(31).getStringCellValue()).isEqualTo("UNREVIEWED");
                assertThat(reopenedLedger.getRow(2).getCell(34).getStringCellValue()).isEqualTo("아니오");
            }
        }
    }

    private CaseKey workbookCase(byte[] bytes,int rowIndex)throws Exception {try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){var r=workbook.getSheet("담당자 검토대장").getRow(rowIndex);return new CaseKey(r.getCell(1).getStringCellValue());}}
    private record CaseKey(String caseKey){}
}
