package com.example.processautomation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.material.NumericWorkingCopyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Transactional
class OperatorDiagramModifySyntheticIsolationTest {
    private static final String USER = "modify-synthetic-operator";
    private static final String PASSWORD = "Modify-test-" + UUID.randomUUID() + "-Passphrase!";
    private static final String PASSWORD_HASH = OperatorLoginService.hashForTests(PASSWORD,
            "modify-synthetic-operator-salt".getBytes(StandardCharsets.UTF_8), 210_000);
    private static final String API = "/api/workspace/diagram-editor/modify";

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("PROPOSAL_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("PROPOSAL_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("PROPOSAL_TEST_DB_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.target", () -> "24");
        registry.add("app.operator.username", () -> USER);
        registry.add("app.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NumericWorkingCopyService workingCopies;
    @Autowired private OoxmlPackageInspector inspector;

    @Test
    void p02SyntheticPilotModifyCreatesSessionScopedTestOnlyWorkbook() throws Exception {
        Pilot fixture = createPilot("P-02", "I-PP21039", "SYN-P02-OLD");
        exerciseModify(fixture, "SYN-P02-NEW", "A4");
        TestTransaction.flagForRollback();
    }

    @Test
    void pw02SyntheticPilotModifyCreatesSessionScopedTestOnlyWorkbook() throws Exception {
        Pilot fixture = createPilot("PW-02", "I-PW21007", "SYN-PW02-OLD");
        exerciseModify(fixture, "SYN-PW02-NEW", "A4");
        TestTransaction.flagForRollback();
    }

    private void exerciseModify(Pilot fixture, String replacement, String targetCell) throws Exception {
        Login login = login();
        MvcResult options = mvc.perform(get(API + "/equipment-options").session(login.session())
                        .param("workingDocumentId", String.valueOf(fixture.workingId()))
                        .param("sheetName", fixture.sheetName()))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(options.getResponse().getContentAsByteArray()).toString())
                .contains(fixture.managementNo());
        MvcResult inspected = mvc.perform(get(API + "/equipment").session(login.session())
                        .param("workingDocumentId", String.valueOf(fixture.workingId()))
                        .param("sheetName", fixture.sheetName()).param("managementNo", fixture.managementNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.status").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.fields.businessItemNo.cellAddress").value(targetCell))
                .andExpect(jsonPath("$.mapping.fields.quantity.status").value("REVIEW_REQUIRED"))
                .andReturn();
        String sourceHash = mapper.readTree(inspected.getResponse().getContentAsByteArray()).path("workingSha256").asText();
        byte[] source = workingCopies.getWorkingCopy(fixture.workingId()).bytes();
        mvc.perform(post(API + "/preview").session(login.session()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("workingDocumentId", fixture.workingId(),
                                "sheetName", fixture.sheetName(), "managementNo", fixture.managementNo(),
                                "replacements", Map.of("businessItemNo", replacement)))))
                .andExpect(status().isForbidden());
        MvcResult preview = mvc.perform(post(API + "/preview").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("workingDocumentId", fixture.workingId(),
                                "sheetName", fixture.sheetName(), "managementNo", fixture.managementNo(),
                                "replacements", Map.of("businessItemNo", replacement)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.beforeValues.businessItemNo").value(fixture.itemNo()))
                .andExpect(jsonPath("$.afterValues.businessItemNo").value(replacement)).andReturn();
        String previewId = mapper.readTree(preview.getResponse().getContentAsByteArray()).path("previewId").asText();
        MvcResult generated = mvc.perform(post(API + "/generate").session(login.session())
                        .header("X-CSRF-Token", login.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("previewId", previewId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.officialBaselineModified").value(false)).andReturn();
        String outputHash = mapper.readTree(generated.getResponse().getContentAsByteArray()).path("outputSha256").asText();
        MvcResult downloaded = mvc.perform(get(API + "/download/{previewId}", previewId).session(login.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Scenario-Status", "TEST_ONLY")).andReturn();
        byte[] output = downloaded.getResponse().getContentAsByteArray();
        assertThat(sha256(output)).isEqualTo(outputHash);
        assertThat(sha256(source)).isEqualTo(sourceHash);
        assertThat(inspector.inspect(source).shapeCount()).isEqualTo(inspector.inspect(output).shapeCount());
        try (var before = WorkbookFactory.create(new ByteArrayInputStream(source));
             var after = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var oldSheet = before.getSheet(fixture.sheetName());
            var newSheet = after.getSheet(fixture.sheetName());
            assertThat(newSheet.getNumMergedRegions()).isEqualTo(oldSheet.getNumMergedRegions());
            assertThat(after.getPrintArea(after.getSheetIndex(fixture.sheetName())))
                    .isEqualTo(before.getPrintArea(before.getSheetIndex(fixture.sheetName())));
            assertThat(newSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo(replacement);
            assertThat(newSheet.getRow(2).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(oldSheet.getRow(2).getCell(0).getCellStyle().getIndex());
        }
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(output))) {
            assertThat(zip.getNextEntry()).isNotNull();
            while (zip.getNextEntry() != null) { while (zip.read() != -1) { } }
        }
        MockHttpSession other = new MockHttpSession();
        other.setAttribute(OperatorLoginService.OPERATOR_SESSION_KEY, "other-test-operator");
        mvc.perform(get(API + "/download/{previewId}", previewId).session(other)).andExpect(status().isNotFound());
        mvc.perform(post(API + "/preview").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("workingDocumentId", fixture.workingId(),
                                "sheetName", fixture.sheetName(), "managementNo", fixture.managementNo(),
                                "replacements", Map.of("quantity", "999")))))
                .andExpect(status().isConflict());
    }

    private Pilot createPilot(String process, String managementNo, String itemNo) throws Exception {
        String sheetName = process.equals("P-02") ? "P-02-01(TOP)" : "PW-02-01";
        byte[] workbookBytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var left = workbook.createCellStyle(); left.setBorderLeft(BorderStyle.THIN);
            var right = workbook.createCellStyle(); right.setBorderRight(BorderStyle.THIN);
            var bottom = workbook.createCellStyle(); bottom.setBorderBottom(BorderStyle.THIN);
            var corner = workbook.createCellStyle(); corner.setBorderRight(BorderStyle.THIN); corner.setBorderBottom(BorderStyle.THIN);
            for (int r = 0; r < 8; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < 6; c++) {
                    var cell = row.createCell(c);
                    cell.setCellStyle(r == 7 && c == 5 ? corner : r == 7 ? bottom : c == 5 ? right : left);
                }
            }
            sheet.getRow(0).getCell(0).setCellValue(managementNo);
            sheet.getRow(2).getCell(0).setCellValue("Synthetic Equipment");
            sheet.getRow(3).getCell(0).setCellValue(itemNo);
            sheet.getRow(4).getCell(0).setCellValue("12 m3/h");
            sheet.getRow(5).getCell(0).setCellValue("1ea");
            sheet.createRow(10).createCell(8).setCellValue("outside sentinel");
            workbook.write(output); workbookBytes = output.toByteArray();
        }
        String sourceHash = sha256("synthetic-locked-source-" + process);
        String masterHash = sha256("synthetic-master-" + process);
        jdbc.update("INSERT INTO baseline_release(version_code,description,source_master_sha256,locked) VALUES(?,?,?,TRUE)",
                "V1.0", "Synthetic isolated fixture", masterHash);
        long releaseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO baseline_document(baseline_release_id,document_role,process_code,original_filename,
                    content_type,byte_size,sha256,content_bytes)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, "PROCESS", process, process + "_SYNTHETIC.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes.length,
                sourceHash, workbookBytes);
        long documentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long sheetId;
        jdbc.update("INSERT INTO workbook_sheet(baseline_document_id,sheet_index,sheet_name) VALUES(?,?,?)",
                documentId, 0, sheetName);
        sheetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String workingHash = sha256(workbookBytes);
        jdbc.update("""
                INSERT INTO process_working_document(baseline_document_id,source_sha256,working_sha256,
                    conversion_version,conversion_status,source_filename,working_filename,conversion_report_json,content_bytes)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, documentId, sourceHash, workingHash, "NUMERIC-WORKING-UPLOAD-V1", "CREATED_WITH_REVIEW",
                process + "_SYNTHETIC.xlsx", process + "_WORKING_SYNTHETIC.xlsx", "{}", workbookBytes);
        long workingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO equipment_record(baseline_release_id,source_document_id,source_sheet_name,source_row_number,
                    master_order,permit_management_no,business_item_no,middle_process_code,major_process_code,
                    facility_name,permit_name,permit_category,capacity_text,capacity_unit,quantity_text,raw_values_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, releaseId, documentId, sheetName, 1, 1, managementNo, itemNo, process, process,
                "Synthetic Equipment", "Synthetic Permit Name", "Synthetic Category", "12", "m3/h", "1", "{}");
        long equipmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO equipment_mapping(baseline_release_id,equipment_id,process_document_id,workbook_sheet_id,
                    mapping_status,match_method,evidence_text,expected_process_code)
                VALUES(?,?,?,?,?,?,?,?)
                """, releaseId, equipmentId, documentId, sheetId, "MAPPED_PERMIT_ID", "PERMIT_MANAGEMENT_NO",
                "SYNTHETIC TEST_ONLY exact permit mapping", process);
        return new Pilot(workingId, sheetName, managementNo, itemNo);
    }

    private Login login() throws Exception {
        MvcResult response = mvc.perform(post("/api/session/login").header("X-Login-Request", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("username", USER, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new Login((MockHttpSession) response.getRequest().getSession(false),
                mapper.readTree(response.getResponse().getContentAsByteArray()).path("csrfToken").asText());
    }
    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing isolated test setting: " + key);
        return value;
    }
    private String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String sha256(String text) throws Exception { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private record Pilot(long workingId, String sheetName, String managementNo, String itemNo) { }
    private record Login(MockHttpSession session, String csrf) { }
}
