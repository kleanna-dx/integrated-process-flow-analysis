package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional(readOnly = true)
class Step6TestOnlyWorkbookEnrichmentIntegrationTest {
    private static final Path SOURCE = Path.of(
            "validation-results/2026-10-20-step6-4-web-integration/TEST_ONLY-web-flow-plan-33.xlsx");
    private static final String EXPECTED_SOURCE_SHA256 =
            "d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc";

    @Autowired private Step6TestOnlyWorkbookEnrichmentService enrichment;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void addsChangeHistoryOnlyToHashVerifiedPlan33(@TempDir Path tempDir) throws Exception {
        byte[] downloadedPlan33 = Files.readAllBytes(SOURCE);
        assertThat(sha256(downloadedPlan33)).isEqualTo(EXPECTED_SOURCE_SHA256);

        var result = enrichment.createChangeHistoryOnly(33);
        assertThat(result.sourcePlanSha256()).isEqualTo(EXPECTED_SOURCE_SHA256);
        assertThat(sha256(result.bytes())).isEqualTo(result.sha256());
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(downloadedPlan33));
             var outputWorkbook = WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))) {
            assertThat(outputWorkbook.getNumberOfSheets()).isEqualTo(sourceWorkbook.getNumberOfSheets() + 1);
            assertThat(outputWorkbook.getSheet("변경내역")).isNotNull();
            assertThat(outputWorkbook.getSheet("물질수지 검토")).isNull();
            DataFormatter formatter = new DataFormatter();
            var history = outputWorkbook.getSheet("변경내역");
            String text = sheetText(history, formatter);
            assertThat(nonEmptyCellCount(history, formatter)).isGreaterThan(50);
            assertThat(formatter.formatCellValue(history.getRow(0).getCell(0)))
                    .isEqualTo("설비 변경내역 및 도면 반영 결과");
            assertThat(text).contains("193", "item 217", "plan 33", "V1.0", "TEST_ONLY / REVIEW_REQUIRED",
                    "ADDED", "C-PW15020", "WW-F-AFT10", "SCREEN FILTER", "1,500", "1ea",
                    "PW-01-05", "AI198:AZ208", "미승인 — REVIEW_REQUIRED");
            assertThat(valueFor(history, formatter, "변경 전")).isEqualTo("기준 설비목록에 없음");
            assertThat(valueFor(history, formatter, "변경 후")).isEqualTo("신규 설비 추가");
            assertThat(valueFor(history, formatter, "설비 관리번호")).isEqualTo("C-PW15020");
            assertThat(valueFor(history, formatter, "Item No.")).isEqualTo("WW-F-AFT10");
        }
        assertPreservedWorkbookAndAppendOnlyStyles(downloadedPlan33, result.bytes());
        Set<String> beforeParts = readParts(downloadedPlan33).keySet();
        Set<String> afterParts = readParts(result.bytes()).keySet();
        Set<String> added = new HashSet<>(afterParts);
        added.removeAll(beforeParts);
        assertThat(added).hasSize(1).allMatch(part -> part.matches("xl/worksheets/sheet\\d+\\.xml"));

        Path savedCopy = tempDir.resolve("change-history-only.xlsx");
        Files.write(savedCopy, result.bytes());
        try (var savedWorkbook = WorkbookFactory.create(Files.newInputStream(savedCopy))) {
            assertThat(savedWorkbook.getSheet("변경내역")).isNotNull();
            assertThat(nonEmptyCellCount(savedWorkbook.getSheet("변경내역"), new DataFormatter())).isGreaterThan(50);
        }
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(EXPECTED_SOURCE_SHA256);
    }

    @Test
    void enrichesOnlyStoredPlan33AndPreservesOriginalWorkbookParts(@TempDir Path tempDir) throws Exception {
        byte[] downloadedPlan33 = Files.readAllBytes(SOURCE);
        assertThat(sha256(downloadedPlan33)).isEqualTo(EXPECTED_SOURCE_SHA256);
        int documentsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM baseline_document", Integer.class);
        int equipmentBefore = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class);
        int confirmedBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class);
        int reviewBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class);
        int simulationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_simulation", Integer.class);
        Map<String, Object> savedPlan = jdbc.queryForMap("""
                SELECT p.id AS plan_id, p.change_run_id AS run_id, p.change_item_id AS item_id,
                       p.plan_status AS plan_status, p.relation_status AS relation_status,
                       i.change_type AS change_type, i.permit_management_no AS management_no,
                       r.analysis_mode AS analysis_mode
                FROM diagram_edit_plan p
                JOIN change_run r ON r.id = p.change_run_id
                JOIN change_item i ON i.id = p.change_item_id AND i.change_run_id = p.change_run_id
                WHERE p.id = 33
                """);
        assertThat(((Number) savedPlan.get("plan_id")).longValue()).isEqualTo(33);
        assertThat(((Number) savedPlan.get("run_id")).longValue()).isEqualTo(193);
        assertThat(((Number) savedPlan.get("item_id")).longValue()).isEqualTo(217);
        assertThat(savedPlan.get("plan_status")).isEqualTo("TEST_ONLY_READY");
        assertThat(savedPlan.get("relation_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(savedPlan.get("change_type")).isEqualTo("ADDED");
        assertThat(savedPlan.get("management_no")).isEqualTo("C-PW15020");
        assertThat(savedPlan.get("analysis_mode")).isEqualTo("TEST_ONLY");

        var result = enrichment.enrichStoredPlan(33);
        assertThat(result.sourcePlanSha256()).isEqualTo(EXPECTED_SOURCE_SHA256);
        assertThat(sha256(result.bytes())).isEqualTo(result.sha256());
            assertThat(result.filename()).contains("run193-item217-plan33").endsWith("-integrated-synthetic-values.xlsx");


        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(downloadedPlan33));
             var outputWorkbook = WorkbookFactory.create(new ByteArrayInputStream(result.bytes()))) {
            assertThat(outputWorkbook.getNumberOfSheets()).isEqualTo(sourceWorkbook.getNumberOfSheets() + 2);
            assertThat(outputWorkbook.getActiveSheetIndex()).isEqualTo(7);
            assertThat(outputWorkbook.getPrintArea(6)).contains("$A$1:$C$");
            assertThat(outputWorkbook.getPrintArea(7)).contains("$A$1:$C$25");
            assertThat(outputWorkbook.getSheet("변경내역")).isNotNull();
            assertThat(outputWorkbook.getSheet("물질수지 검토")).isNotNull();
            DataFormatter formatter = new DataFormatter();
            var history = outputWorkbook.getSheet("변경내역");
            assertThat(nonEmptyCellCount(history, formatter)).isGreaterThan(0);
            assertThat(formatter.formatCellValue(history.getRow(0).getCell(0)))
                    .isEqualTo("설비 변경내역 및 도면 반영 결과");
            assertThat(valueFor(history, formatter, "분석 실행")).isEqualTo("run 193");
            assertThat(valueFor(history, formatter, "편집 계획")).isEqualTo("plan 33");
            var balance = outputWorkbook.getSheet("물질수지 검토");
            assertThat(nonEmptyCellCount(balance, formatter)).isGreaterThan(0);
            assertThat(formatter.formatCellValue(balance.getRow(0).getCell(0)))
                    .isEqualTo("C-PW15020 / SCREEN FILTER — 물질수지 검토 결과");
            assertThat(formatter.formatCellValue(balance.getRow(1).getCell(0)))
                    .contains("실제 물질수지 검증: 불가", "발생량·이동처·승인 연결 자료 부족");
            assertThat(formatter.formatCellValue(balance.getRow(2).getCell(0)))
                    .isEqualTo("TEST_ONLY 가상 계산입니다. 실제 물질수지와 구분해 확인하십시오.");
            assertThat(balance.getRow(1).getCell(0).getCellStyle().getWrapText()).isTrue();
            assertThat(balance.getRow(8).getCell(0).getStringCellValue()).isEqualTo("유입량");
            assertThat(balance.getRow(8).getCell(1).getNumericCellValue()).isEqualTo(100.0);
            assertThat(balance.getRow(9).getCell(0).getStringCellValue()).isEqualTo("후단 이동");
            assertThat(balance.getRow(9).getCell(1).getNumericCellValue()).isEqualTo(97.0);
            assertThat(balance.getRow(10).getCell(0).getStringCellValue()).isEqualTo("별도 폐수 이동");
            assertThat(balance.getRow(10).getCell(1).getNumericCellValue()).isEqualTo(2.5);
            assertThat(balance.getRow(11).getCell(0).getStringCellValue()).isEqualTo("폐기물 발생");
            assertThat(balance.getRow(11).getCell(1).getNumericCellValue()).isEqualTo(0.5);
            assertThat(balance.getRow(12).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
            assertThat(balance.getRow(12).getCell(1).getCellFormula()).isEqualTo("SUM(B10:B12)");
            assertThat(balance.getRow(12).getCell(1).getCachedFormulaResultType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(balance.getRow(12).getCell(1).getNumericCellValue()).isEqualTo(100.0);
            assertThat(balance.getRow(13).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
            assertThat(balance.getRow(13).getCell(1).getCellFormula()).isEqualTo("B9-B13");
            assertThat(balance.getRow(13).getCell(1).getNumericCellValue()).isEqualTo(0.0);
            assertThat(outputWorkbook.getSheet("PW-01-05").getRow(197).getCell(34).getStringCellValue())
                    .isEqualTo(sourceWorkbook.getSheet("PW-01-05").getRow(197).getCell(34).getStringCellValue());

            assertThat(sheetText(history, formatter)).contains(
                    "설비 변경내역 및 도면 반영 결과", "run 193", "item 217", "plan 33", "TEST_ONLY / REVIEW_REQUIRED",
                    "신규 설비 추가", "C-PW15020", "WW-F-AFT10", "SCREEN FILTER", "1,500", "1ea",
                    "기준 설비목록에 없음", "PW-01-05", "AI198:AZ208", "시험 연결선",
                    "미승인 — REVIEW_REQUIRED", "폐수 발생량", "폐기물 발생량",
                    "실제 이동처 및 처리 경로", "실제 물질수지");
            assertThat(nonEmptyCellCount(history, formatter)).isEqualTo(86);
            assertThat(formatter.formatCellValue(history.getRow(4).getCell(0))).isEqualTo("변경 유형");
            assertThat(formatter.formatCellValue(history.getRow(4).getCell(1))).isEqualTo("신규 설비 추가");
            assertThat(formatter.formatCellValue(history.getRow(5).getCell(0))).isEqualTo("설비 관리번호");
            assertThat(formatter.formatCellValue(history.getRow(5).getCell(1))).isEqualTo("C-PW15020");
            assertThat(formatter.formatCellValue(history.getRow(6).getCell(1))).isEqualTo("WW-F-AFT10");
            assertThat(formatter.formatCellValue(history.getRow(7).getCell(1))).isEqualTo("SCREEN FILTER");
            assertThat(formatter.formatCellValue(history.getRow(8).getCell(1))).isEqualTo("1,500㎥/시");
            assertThat(formatter.formatCellValue(history.getRow(9).getCell(1))).isEqualTo("1ea");
            assertThat(formatter.formatCellValue(history.getRow(12).getCell(1))).isEqualTo("PW-01-05");
            assertThat(formatter.formatCellValue(history.getRow(13).getCell(1))).isEqualTo("AI198:AZ208");
            assertThat(formatter.formatCellValue(history.getRow(14).getCell(1))).isEqualTo("run 193");
            assertThat(formatter.formatCellValue(history.getRow(16).getCell(1))).isEqualTo("plan 33");
            assertThat(formatter.formatCellValue(history.getRow(17).getCell(1)))
                    .isEqualTo("TEST_ONLY / REVIEW_REQUIRED");
            assertThat(valueFor(history, formatter, "변경 유형")).isEqualTo("신규 설비 추가");
            assertThat(valueFor(history, formatter, "분석 실행")).isEqualTo("run 193");
            assertThat(valueFor(history, formatter, "변경 항목")).isEqualTo("item 217");
            assertThat(valueFor(history, formatter, "편집 계획")).isEqualTo("plan 33");
            assertThat(valueFor(history, formatter, "변경 전")).isEqualTo("기준 설비목록에 없음");
            assertThat(valueFor(history, formatter, "변경 후")).isEqualTo("신규 설비 추가");
            assertThat(valueFor(history, formatter, "설비 관리번호")).isEqualTo("C-PW15020");
            assertThat(valueFor(history, formatter, "Item No.")).isEqualTo("WW-F-AFT10");
            assertThat(valueFor(history, formatter, "상태")).isEqualTo("TEST_ONLY / REVIEW_REQUIRED");

            String balanceText = sheetText(balance, formatter);
            assertThat(balanceText).contains("C-PW15020 / SCREEN FILTER — 물질수지 검토 결과",
                    "실제 물질수지 검증: 불가 — 실제 발생량·이동처·승인 연결 자료 부족",
                    "TEST_ONLY 가상 계산입니다. 실제 물질수지와 구분해 확인하십시오.",
                    "유입량", "후단 이동", "별도 폐수 이동", "폐기물 발생", "출구 합계", "잔차",
                    "100 = 0.5 + 2.5 + 97 ton/day", "입력과 출력 합계 일치", "후단 공정",
                    "실제 유입·유출량 확인 필요", "미확정", "REVIEW_REQUIRED", "Simulation ID",
                    "transferId", "node / label");
            assertThat(balanceText).contains("추가자료 필요: 실제 폐수·폐기물 발생량, 이동·처리 경로, 승인 연결 근거",
                    "가상 시험값", "실제 물질수지 아님", "TEST_ONLY");
            assertThat(balanceText).doesNotContain("실제 발생량: 0", "실제 폐수량: 0", "실제 폐기물량: 0");
            int candidateBalanceRow = findRowStarting(balance, formatter, "C-PW15020 /");
            assertThat(new java.math.BigDecimal(formatter.formatCellValue(
                    balance.getRow(candidateBalanceRow).getCell(8)))).isEqualByComparingTo("0");
        }

        Map<String, byte[]> beforeParts = readParts(downloadedPlan33);
        Map<String, byte[]> afterParts = readParts(result.bytes());
        assertPreservedWorkbookAndAppendOnlyStyles(downloadedPlan33, result.bytes());
        Set<String> added = new HashSet<>(afterParts.keySet());
        added.removeAll(beforeParts.keySet());
        assertThat(added).hasSize(2).allMatch(part -> part.matches("xl/worksheets/sheet\\d+\\.xml"));

        Path savedOutput = tempDir.resolve("integrated-test-only.xlsx");
        Files.write(savedOutput, result.bytes());
        try (var savedWorkbook = WorkbookFactory.create(Files.newInputStream(savedOutput))) {
            assertThat(savedWorkbook.getNumberOfSheets()).isEqualTo(8);
            DataFormatter savedFormatter = new DataFormatter();
            var savedHistory = savedWorkbook.getSheet("변경내역");
            var savedBalance = savedWorkbook.getSheet("물질수지 검토");
            assertThat(savedHistory).isNotNull();
            assertThat(savedBalance).isNotNull();
            assertThat(nonEmptyCellCount(savedHistory, savedFormatter)).isGreaterThan(0);
            assertThat(nonEmptyCellCount(savedBalance, savedFormatter)).isGreaterThan(0);
            assertThat(nonEmptyCellCount(savedHistory, savedFormatter)).isGreaterThan(50);
            assertThat(sheetText(savedHistory, savedFormatter)).contains(
                    "설비 변경내역 및 도면 반영 결과", "run 193", "item 217", "plan 33", "C-PW15020", "SCREEN FILTER");
            assertThat(nonEmptyCellCount(savedBalance, savedFormatter)).isGreaterThan(100);
            assertThat(sheetText(savedBalance, savedFormatter)).contains(
                    "C-PW15020 / SCREEN FILTER — 물질수지 검토 결과",
                    "실제 물질수지 검증: 불가 — 실제 발생량·이동처·승인 연결 자료 부족",
                    "TEST_ONLY 가상 계산입니다. 실제 물질수지와 구분해 확인하십시오.",
                    "100 = 0.5 + 2.5 + 97 ton/day");
            assertThat(nonEmptyCellCount(savedHistory, savedFormatter)).isEqualTo(86);
        }
        assertThat(sha256(Files.readAllBytes(SOURCE))).isEqualTo(EXPECTED_SOURCE_SHA256);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM baseline_document", Integer.class)).isEqualTo(documentsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_record", Integer.class)).isEqualTo(equipmentBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'CONFIRMED'", Integer.class))
                .isEqualTo(confirmedBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_relation WHERE relation_status = 'REVIEW_REQUIRED'", Integer.class))
                .isEqualTo(reviewBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_balance_simulation", Integer.class)).isEqualTo(simulationsBefore);
    }

    private void assertPreservedWorkbookAndAppendOnlyStyles(byte[] source, byte[] output) throws Exception {
        Map<String, byte[]> before = readParts(source);
        Map<String, byte[]> after = readParts(output);
        Set<String> allowedChanged = Set.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "[Content_Types].xml", "docProps/app.xml");
        assertThat(after.keySet()).containsAll(before.keySet());
        for (String part : before.keySet()) {
            if (!allowedChanged.contains(part)) assertThat(after.get(part)).as(part).containsExactly(before.get(part));
        }
        Set<String> added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertThat(added).allMatch(part -> part.matches("xl/worksheets/sheet\\d+\\.xml"));
        assertStyleCatalogAppendOnly(before.get("xl/styles.xml"), after.get("xl/styles.xml"), output);
        assertExistingSheetValuesAndStyleIds(source, output);
    }

    private void assertStyleCatalogAppendOnly(byte[] sourceBytes, byte[] outputBytes, byte[] workbookBytes) throws Exception {
        var source = parseXml(sourceBytes);
        var output = parseXml(outputBytes);
        var sourceRoot = source.getDocumentElement();
        var outputRoot = output.getDocumentElement();
        for (String catalogName : Set.of("numFmts", "fills", "borders", "cellStyleXfs", "cellStyles", "dxfs", "tableStyles")) {
            var originalCatalog = directChild(sourceRoot, catalogName);
            var outputCatalog = directChild(outputRoot, catalogName);
            assertThat(outputCatalog).as(catalogName + " catalog").isNotNull();
            if (originalCatalog == null) {
                assertThat(outputCatalog).as(catalogName + " remains absent when source omitted it").isNull();
            } else {
                assertThat(outputCatalog.isEqualNode(originalCatalog)).as(catalogName + " catalog is unchanged").isTrue();
            }
        }
        var originalFonts = directChild(sourceRoot, "fonts");
        var outputFonts = directChild(outputRoot, "fonts");
        var originalXfs = directChild(sourceRoot, "cellXfs");
        var outputXfs = directChild(outputRoot, "cellXfs");
        List<org.w3c.dom.Element> beforeFonts = children(originalFonts);
        List<org.w3c.dom.Element> afterFonts = children(outputFonts);
        List<org.w3c.dom.Element> beforeXfs = children(originalXfs);
        List<org.w3c.dom.Element> afterXfs = children(outputXfs);
        assertThat(afterFonts.size()).isGreaterThanOrEqualTo(beforeFonts.size());
        assertThat(afterXfs.size()).isGreaterThanOrEqualTo(beforeXfs.size());
        for (int i = 0; i < beforeFonts.size(); i++) {
            assertThat(afterFonts.get(i).isEqualNode(beforeFonts.get(i))).as("original font ID " + i).isTrue();
        }
        for (int i = 0; i < beforeXfs.size(); i++) {
            assertThat(afterXfs.get(i).isEqualNode(beforeXfs.get(i))).as("original cell style ID " + i).isTrue();
        }
        for (int i = beforeFonts.size(); i < afterFonts.size(); i++) {
            var color = directChild(afterFonts.get(i), "color");
            assertThat(color).as("new font color at ID " + i).isNotNull();
            assertThat(color.getAttribute("rgb")).isEqualToIgnoringCase("FF000000");
            assertThat(color.hasAttribute("theme")).isFalse();
            var name = directChild(afterFonts.get(i), "name");
            assertThat(name).as("new font name at ID " + i).isNotNull();
            assertThat(name.getAttribute("val")).isEqualTo("맑은 고딕");
        }
        for (int i = beforeXfs.size(); i < afterXfs.size(); i++) {
            var xf = afterXfs.get(i);
            int fontId = Integer.parseInt(xf.getAttribute("fontId"));
            assertThat(fontId).isBetween(beforeFonts.size(), afterFonts.size() - 1);
            assertThat(xf.getAttribute("applyFont")).isEqualTo("1");
            assertThat(beforeXfs.stream().anyMatch(original -> sameStyleExceptFont(original, xf)))
                    .as("new xf " + i + " appends only a black font variant of an existing style")
                    .isTrue();
        }
        assertNewResultSheetStyles(workbookBytes, beforeXfs.size());
        System.out.printf("STYLE_XML_APPEND_ONLY numFmts=%d/%d fonts=%d/%d fills=%d/%d borders=%d/%d cellXfs=%d/%d%n",
                countChildren(directChild(sourceRoot, "numFmts")), countChildren(directChild(outputRoot, "numFmts")),
                beforeFonts.size(), afterFonts.size(), countChildren(directChild(sourceRoot, "fills")),
                countChildren(directChild(outputRoot, "fills")), countChildren(directChild(sourceRoot, "borders")),
                countChildren(directChild(outputRoot, "borders")), beforeXfs.size(), afterXfs.size());
    }

    private void assertExistingSheetValuesAndStyleIds(byte[] sourceBytes, byte[] outputBytes) throws Exception {
        try (var source = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes));
             var output = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            for (int sheetIndex = 0; sheetIndex < source.getNumberOfSheets(); sheetIndex++) {
                var beforeSheet = source.getSheetAt(sheetIndex);
                var afterSheet = output.getSheet(beforeSheet.getSheetName());
                assertThat(afterSheet).as("existing sheet " + beforeSheet.getSheetName()).isNotNull();
                assertThat(afterSheet.getNumMergedRegions()).as("merges " + beforeSheet.getSheetName())
                        .isEqualTo(beforeSheet.getNumMergedRegions());
                assertThat(output.getPrintArea(sheetIndex)).as("print area " + beforeSheet.getSheetName())
                        .isEqualTo(source.getPrintArea(sheetIndex));
                for (var beforeRow : beforeSheet) {
                    var afterRow = afterSheet.getRow(beforeRow.getRowNum());
                    assertThat(afterRow).as("row " + beforeSheet.getSheetName() + "!" + beforeRow.getRowNum()).isNotNull();
                    for (var beforeCell : beforeRow) {
                        var afterCell = afterRow.getCell(beforeCell.getColumnIndex(),
                                org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                        String address = beforeSheet.getSheetName() + "!" + beforeCell.getAddress();
                        assertThat(afterCell).as(address).isNotNull();
                        assertThat(afterCell.getCellType()).as("cell type " + address).isEqualTo(beforeCell.getCellType());
                        assertThat(afterCell.getCellStyle().getIndex()).as("style ID " + address)
                                .isEqualTo(beforeCell.getCellStyle().getIndex());
                        assertThat(formatter.formatCellValue(afterCell)).as("display value " + address)
                                .isEqualTo(formatter.formatCellValue(beforeCell));
                        if (beforeCell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                            assertThat(afterCell.getCellFormula()).as("formula " + address).isEqualTo(beforeCell.getCellFormula());
                        }
                    }
                }
            }
        }
    }

    private boolean sameStyleExceptFont(org.w3c.dom.Element original, org.w3c.dom.Element variant) {
        for (String attribute : List.of("numFmtId", "fillId", "borderId", "xfId", "alignmentId",
                "protectionId", "quotePrefix", "pivotButton")) {
            if (!original.getAttribute(attribute).equals(variant.getAttribute(attribute))) return false;
        }
        for (String childName : List.of("alignment", "protection", "extLst")) {
            var originalChild = directChild(original, childName);
            var variantChild = directChild(variant, childName);
            if (originalChild == null ? variantChild != null : variantChild == null || !originalChild.isEqualNode(variantChild)) return false;
        }
        return true;
    }

    private void assertNewResultSheetStyles(byte[] outputBytes, int originalXfCount) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(outputBytes))) {
            for (var sheet : workbook) {
                if (!Set.of("변경내역", "물질수지 검토").contains(sheet.getSheetName())) continue;
                for (var row : sheet) for (var cell : row) {
                    if (new DataFormatter().formatCellValue(cell).isBlank()) continue;
                    assertThat((int) cell.getCellStyle().getIndex()).isGreaterThanOrEqualTo(originalXfCount);
                    var font = (org.apache.poi.xssf.usermodel.XSSFFont)
                            workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                    assertThat(font.getFontName()).isEqualTo("맑은 고딕");
                    assertThat(font.getXSSFColor()).isNotNull();
                    assertThat(font.getXSSFColor().getARGBHex()).isEqualToIgnoringCase("FF000000");
                }
            }
        }
    }

    private org.w3c.dom.Document parseXml(byte[] bytes) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private org.w3c.dom.Element directChild(org.w3c.dom.Element parent, String localName) {
        if (parent == null) return null;
        for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private List<org.w3c.dom.Element> children(org.w3c.dom.Element parent) {
        List<org.w3c.dom.Element> elements = new java.util.ArrayList<>();
        if (parent != null) for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element) elements.add(element);
        }
        return elements;
    }

    private int countChildren(org.w3c.dom.Element parent) {
        return children(parent).size();
    }

    private String sheetText(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter) {
        StringBuilder text = new StringBuilder();
        for (var row : sheet) for (var cell : row) text.append(formatter.formatCellValue(cell)).append('\n');
        return text.toString();
    }

    private int nonEmptyCellCount(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter) {
        int count = 0;
        for (var row : sheet) for (var cell : row) if (!formatter.formatCellValue(cell).isBlank()) count++;
        return count;
    }

    private int findRowStarting(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String prefix) {
        for (var row : sheet) {
            var label = row.getCell(0);
            var residual = row.getCell(8);
            if (label != null && formatter.formatCellValue(label).startsWith(prefix)
                    && residual != null && !formatter.formatCellValue(residual).isBlank()) {
                return row.getRowNum();
            }
        }
        throw new AssertionError("Missing populated balance row starting with: " + prefix);
    }

    private String valueFor(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String label) {
        for (var row : sheet) {
            if (row.getCell(0) != null && label.equals(formatter.formatCellValue(row.getCell(0)))) {
                return row.getCell(1) == null ? "" : formatter.formatCellValue(row.getCell(1));
            }
        }
        throw new AssertionError("Missing expected label: " + label);
    }

    private Map<String, byte[]> readParts(byte[] workbookBytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(workbookBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                input.transferTo(bytes);
                parts.put(entry.getName(), bytes.toByteArray());
            }
        }
        return parts;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
