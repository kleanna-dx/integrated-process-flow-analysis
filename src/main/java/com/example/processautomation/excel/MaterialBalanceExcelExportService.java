package com.example.processautomation.excel;

import com.example.processautomation.material.MaterialBalanceRequest;
import com.example.processautomation.model.MasterEquipment;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Adds calculated TEST_ONLY data as a new sheet without editing any existing worksheet or drawing. */
@Component
public class MaterialBalanceExcelExportService {
    public static final String RESULTS_SHEET = "TEST_ONLY Results";
    public static final String CHANGE_HISTORY_SHEET = "변경내역";
    public static final String GENERATED_RESULTS_SHEET = "물질수지 검토";
    public static final String EXPECTED_V5_SHA256 = "7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485";
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CONTENT_TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String APP_NS = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties";
    private static final String VT_NS = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes";
    private static final Pattern SHEET_PART = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml");
    private static final Pattern REL_ID = Pattern.compile("rId(\\d+)");

    private final OoxmlPackageInspector packageInspector;
    private final Pw0105EquipmentInsertionEngine pw0105EquipmentInsertionEngine;

    public MaterialBalanceExcelExportService(OoxmlPackageInspector packageInspector,
                                             Pw0105EquipmentInsertionEngine pw0105EquipmentInsertionEngine) {
        this.packageInspector = packageInspector;
        this.pw0105EquipmentInsertionEngine = pw0105EquipmentInsertionEngine;
    }

    public MaterialBalanceExcelOutputModel createOutputModel(MaterialBalanceRequest request,
                                                             Map<String, Object> calculationResult,
                                                             byte[] sourceWorkbook) {
        if (!"TEST_ONLY".equalsIgnoreCase(request.mode())
                || !"TEST_ONLY".equals(String.valueOf(calculationResult.get("mode")))) {
            throw new IllegalArgumentException("Excel calculation export accepts TEST_ONLY data only");
        }
        if (!Boolean.FALSE.equals(calculationResult.get("officialBaselineModified"))
                || !Boolean.FALSE.equals(calculationResult.get("productionMaterialBalanceUpdated"))) {
            throw new IllegalArgumentException("Calculation result is not marked as isolated from official data");
        }
        Map<String, MaterialBalanceRequest.Node> nodes = new LinkedHashMap<>();
        for (var node : request.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) throw new IllegalArgumentException("Duplicate node id: " + node.id());
        }
        Map<String, MaterialBalanceRequest.Transfer> requestTransfers = new LinkedHashMap<>();
        for (var transfer : request.transfers()) {
            if (requestTransfers.putIfAbsent(transfer.transferId(), transfer) != null) {
                throw new IllegalArgumentException("Duplicate transferId in simulation: " + transfer.transferId());
            }
        }
        List<MaterialBalanceExcelOutputModel.TransferLine> transfers = new ArrayList<>();
        for (Map<String, Object> result : mapRows(calculationResult.get("transfers"))) {
            String id = String.valueOf(result.get("transferId"));
            MaterialBalanceRequest.Transfer requested = requestTransfers.get(id);
            if (requested == null) throw new IllegalArgumentException("Calculated transfer is not present in request: " + id);
            MaterialBalanceRequest.Node source = nodes.get(requested.sourceId());
            MaterialBalanceRequest.Node target = nodes.get(requested.targetId());
            boolean included = Boolean.TRUE.equals(result.get("included"));
            BigDecimal sender = requested.quantity();
            BigDecimal receiver = requested.receiverQuantity() == null ? sender : requested.receiverQuantity();
            if (included && receiver != null && sender.compareTo(receiver) != 0) {
                throw new IllegalArgumentException("Calculation included a sender/receiver quantity mismatch: " + id);
            }
            String edgeType = upper(requested.edgeType());
            String sourceFlow = "WASTE_GENERATION".equals(edgeType) ? "WASTEWATER" : upper(requested.flowType());
            String reason = result.get("reason") == null ? null : String.valueOf(result.get("reason"));
            transfers.add(new MaterialBalanceExcelOutputModel.TransferLine(id, sourceFlow, upper(requested.flowType()),
                    requested.sourceId(), source == null ? null : source.label(), source == null ? null : source.processCode(),
                    source == null ? null : source.equipmentId(), requested.targetId(), target == null ? null : target.label(),
                    target == null ? null : target.processCode(), target == null ? null : target.equipmentId(), sender,
                    receiver, requested.unit(), String.valueOf(result.getOrDefault("reconciliationStatus",
                    included ? "NOT_INDEPENDENTLY_RECONCILED" : "NOT_RECONCILED_EXCLUDED")), included, reason));
        }

        List<MaterialBalanceExcelOutputModel.BalanceLine> balances = new ArrayList<>();
        Map<String, Map<String, Object>> nodeResults = new LinkedHashMap<>();
        for (Map<String, Object> nodeResult : mapRows(calculationResult.get("nodes"))) {
            String nodeId = String.valueOf(nodeResult.get("nodeId"));
            nodeResults.put(nodeId, nodeResult);
            for (Map<String, Object> balance : mapRows(nodeResult.get("balanceByFlowTypeAndUnit"))) {
                balances.add(new MaterialBalanceExcelOutputModel.BalanceLine(nodeId,
                        stringValue(nodeResult.get("label")), stringValue(nodeResult.get("processCode")),
                        longValue(nodeResult.get("equipmentId")), stringValue(nodeResult.get("role")),
                        stringValue(nodeResult.get("receiverType")), stringValue(nodeResult.get("receiverDispositionStatus")),
                        stringValue(balance.get("flowType")), stringValue(balance.get("unit")),
                        decimalValue(balance.get("inflow")), decimalValue(balance.get("outflow")),
                        decimalValue(balance.get("storageChange")), decimalValue(balance.get("residualInMinusOut")),
                        stringValue(nodeResult.get("balanceStatus")), stringValue(nodeResult.get("boundaryCondition"))));
            }
        }
        List<MaterialBalanceExcelOutputModel.NodeMappingLine> mappings = request.nodes().stream().map(node ->
                new MaterialBalanceExcelOutputModel.NodeMappingLine(node.id(), node.label(), node.processCode(),
                        node.equipmentId(), "미기록—실제 설비 sheet 셀 변경 없음", node.equipmentId() == null
                        ? "UNMAPPED_NO_EXPLICIT_EQUIPMENT_ID" : "UNVERIFIED_ID_NO_APPROVED_TEST_ONLY_CELL_MAPPING"))
                .toList();
        String sourceHash = sha256(sourceWorkbook);
        MaterialBalanceExcelOutputModel.LayoutAudit layoutAudit = analyzeTestOnlyLayout(sourceWorkbook);
        return new MaterialBalanceExcelOutputModel(request.simulationId(), request.scenarioName(), "TEST_ONLY", sourceHash,
                layoutAudit, List.copyOf(transfers), List.copyOf(balances), mappings, false, false);
    }

    public ExportArtifact addResultsSheet(byte[] sourceWorkbook, MaterialBalanceExcelOutputModel model) {
        return addResultsSheet(sourceWorkbook, model, false, true, null, true, RESULTS_SHEET);
    }

    public ExportArtifact addDiagramTestOnlyResultsSheet(byte[] sourceWorkbook, MaterialBalanceExcelOutputModel model) {
        return addResultsSheet(sourceWorkbook, model, true, true, null, true, RESULTS_SHEET);
    }

    public ExportArtifact addGeneratedEditResultsSheet(byte[] sourceWorkbook, MaterialBalanceExcelOutputModel model,
                                                        String actualDataStatus) {
        return addGeneratedEditResultsSheet(sourceWorkbook, model, actualDataStatus, true);
    }

    public ExportArtifact addGeneratedEditResultsSheet(byte[] sourceWorkbook, MaterialBalanceExcelOutputModel model,
                                                        String actualDataStatus, boolean includeSyntheticTestValues) {
        return addResultsSheet(sourceWorkbook, model, includeSyntheticTestValues, false, actualDataStatus,
                includeSyntheticTestValues, GENERATED_RESULTS_SHEET);
    }

    public byte[] appendTableWorksheet(byte[] sourceWorkbook, String sheetName, String title,
                                        String notice, List<String> headers, List<List<?>> rows) {
        if (sourceWorkbook == null || sourceWorkbook.length == 0 || sheetName == null || sheetName.isBlank()
                || title == null || title.isBlank() || headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("A source workbook, worksheet name, title, and headers are required");
        }
        if (headers.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Worksheet headers cannot be blank");
        }
        for (List<?> row : rows) {
            if (row == null || row.size() != headers.size()) {
                throw new IllegalArgumentException("Every worksheet table row must match the header width");
            }
        }
        try {
            Document document = newDocument();
            Element root = document.createElementNS(MAIN_NS, "worksheet");
            root.setAttribute("xmlns", MAIN_NS);
            document.appendChild(root);
            Element dimension = child(document, root, MAIN_NS, "dimension");
            dimension.setAttribute("ref", "A1:" + columnName(headers.size()) + (rows.size() + 4));
            Element views = child(document, root, MAIN_NS, "sheetViews");
            Element view = child(document, views, MAIN_NS, "sheetView");
            view.setAttribute("showGridLines", "0");
            view.setAttribute("workbookViewId", "0");
            Element selection = child(document, view, MAIN_NS, "selection");
            selection.setAttribute("activeCell", "A1");
            selection.setAttribute("sqref", "A1");
            Element format = child(document, root, MAIN_NS, "sheetFormatPr");
            format.setAttribute("defaultRowHeight", "18");
            Element columns = child(document, root, MAIN_NS, "cols");
            for (int i = 0; i < headers.size(); i++) {
                Element column = child(document, columns, MAIN_NS, "col");
                column.setAttribute("min", Integer.toString(i + 1));
                column.setAttribute("max", Integer.toString(i + 1));
                column.setAttribute("width", i < 2 ? "28" : "22");
                column.setAttribute("customWidth", "1");
            }
            Element data = child(document, root, MAIN_NS, "sheetData");
            writeRow(document, data, 1, List.of(title), 0, 26);
            writeRow(document, data, 2, List.of(notice == null ? "" : notice), 0, 36);
            writeRow(document, data, 3, List.of(""), 0, 8);
            writeRow(document, data, 4, headers, 0, 24);
            int rowNumber = 5;
            for (List<?> row : rows) writeRow(document, data, rowNumber++, row, 0, 20);
            Element margins = child(document, root, MAIN_NS, "pageMargins");
            margins.setAttribute("left", "0.3"); margins.setAttribute("right", "0.3");
            margins.setAttribute("top", "0.5"); margins.setAttribute("bottom", "0.5");
            margins.setAttribute("header", "0.2"); margins.setAttribute("footer", "0.2");
            return appendWorksheet(sourceWorkbook, sheetName, serialize(document));
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to append TEST_ONLY table worksheet " + sheetName, exception);
        }
    }

    public byte[] addChangeHistorySheet(byte[] sourceWorkbook, MasterEquipment equipment,
                                        Map<String, Object> storedPlan, long changeRunId, long changeItemId) {
        validateGeneratedEditDiagram(sourceWorkbook);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceWorkbook))) {
            if (workbook.getSheet(CHANGE_HISTORY_SHEET) != null) {
                throw new InvalidWorkbookException("The input already contains the change-history sheet");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen the run-matched TEST_ONLY edit workbook", exception);
        }
        try {
            byte[] sheetBytes = buildChangeHistoryWorksheet(equipment, storedPlan, changeRunId, changeItemId);
            validateWorksheetPayload(CHANGE_HISTORY_SHEET, sheetBytes);
            return appendWorksheet(sourceWorkbook, CHANGE_HISTORY_SHEET, sheetBytes);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to add the run-matched TEST_ONLY change-history sheet", exception);
        }
    }

    private byte[] buildChangeHistoryWorksheet(MasterEquipment equipment,
                                               Map<String, Object> storedPlan,
                                               long changeRunId, long changeItemId) throws Exception {
        Map<String, Object> plan = objectMap(storedPlan.get("plan"));
        Map<String, Object> target = objectMap(plan.get("target"));
        Map<String, Object> insertion = objectMap(plan.get("insertion"));
        Map<String, Object> predecessor = objectMap(plan.get("predecessorCandidate"));
        Map<String, Object> successor = objectMap(plan.get("successorCandidate"));
        Document document = newDocument();
        Element root = document.createElementNS(MAIN_NS, "worksheet");
        root.setAttribute("xmlns", MAIN_NS);
        document.appendChild(root);
        Element properties = child(document, root, MAIN_NS, "sheetPr");
        Element tabColor = child(document, properties, MAIN_NS, "tabColor"); tabColor.setAttribute("rgb", "FF4472C4");
        Element pageSetupProperties = child(document, properties, MAIN_NS, "pageSetUpPr");
        pageSetupProperties.setAttribute("fitToPage", "1");
        pageSetupProperties.setAttribute("autoPageBreaks", "1");
        Element views = child(document, root, MAIN_NS, "sheetViews");
        Element view = child(document, views, MAIN_NS, "sheetView");
        view.setAttribute("showGridLines", "0"); view.setAttribute("workbookViewId", "0");
        view.setAttribute("zoomScale", "95");
        Element selection = child(document, view, MAIN_NS, "selection");
        selection.setAttribute("activeCell", "A1"); selection.setAttribute("sqref", "A1");
        Element format = child(document, root, MAIN_NS, "sheetFormatPr"); format.setAttribute("defaultRowHeight", "20");
        Element columns = child(document, root, MAIN_NS, "cols");
        double[] widths = {24, 30, 48};
        for (int index = 0; index < widths.length; index++) {
            Element column = child(document, columns, MAIN_NS, "col");
            column.setAttribute("min", Integer.toString(index + 1)); column.setAttribute("max", Integer.toString(index + 1));
            column.setAttribute("width", Double.toString(widths[index])); column.setAttribute("customWidth", "1");
        }
        Element data = child(document, root, MAIN_NS, "sheetData");
        List<String> merges = new ArrayList<>();
        int row = 1;
        writeMergedTitle(document, data, merges, row++, "A", "C", "설비 변경내역 및 도면 반영 결과", 1);
        writeMergedTitle(document, data, merges, row++, "A", "C",
                "TEST_ONLY / REVIEW_REQUIRED — 검토용 사본이며 업무 승인 또는 공식 도면 변경이 아닙니다.", 160);
        row++;
        writeRow(document, data, row++, List.of("핵심 변경 정보", "확인 내용", "자료 및 상태"), 160, 28);
        String capacity = safe(equipment.capacityText()) + safe(equipment.capacityUnit());
        String status = safe(stringValue(plan.get("scenarioStatus"))) + " / "
                + safe(stringValue(storedPlan.get("relationStatus")));
        List<List<String>> summary = List.of(
                List.of("변경 유형", "신규 설비 추가", "저장 유형: " + safe(stringValue(storedPlan.get("changeType")))),
                List.of("설비 관리번호", safe(equipment.permitManagementNo()), "실제 업로드 마스터"),
                List.of("Item No.", safe(equipment.businessItemNo()), "실제 업로드 마스터"),
                List.of("설비명", safe(equipment.facilityName()), "실제 업로드 마스터"),
                List.of("규격", capacity, "설비 처리능력; 물질수지 시험값과 단위 비교 안 함"),
                List.of("수량", safe(equipment.quantityText()), "실제 업로드 마스터"),
                List.of("변경 전", "기준 설비목록에 없음", "저장 변경 유형 ADDED"),
                List.of("변경 후", "신규 설비 추가", "TEST_ONLY 사본에 반영"),
                List.of("반영 대상", safe(stringValue(target.get("sheetName"))), "V1.0 도면에서 생성한 새 사본"),
                List.of("삽입 위치", safe(stringValue(insertion.get("plannedCellRange"))),
                        "삽입 전 행 " + safe(stringValue(insertion.get("insertBeforeRow")))),
                List.of("분석 실행", "run " + changeRunId, "저장된 change run"),
                List.of("변경 항목", "item " + changeItemId, "저장된 동일 run의 설비 변경"),
                List.of("편집 계획", "plan " + safe(stringValue(storedPlan.get("planId"))), "저장된 run/item 연결 계획"),
                List.of("상태", status, "승인 관계 미생성"));
        for (List<String> item : summary) writeRow(document, data, row++, item, 79, 26);

        row++;
        writeMergedTitle(document, data, merges, row++, "A", "C", "도면에 반영된 내용", 1);
        writeRow(document, data, row++, List.of("항목", "반영 내용", "자동 적용 및 검토 상태"), 160, 28);
        List<List<String>> applied = List.of(
                List.of("신규 설비 박스", safe(equipment.permitManagementNo()),
                        "자동 적용 — TEST_ONLY 사본에만 생성; 공식 원본 미수정"),
                List.of("설비명·규격·수량", safe(equipment.facilityName()) + " / " + capacity + " / "
                                + safe(equipment.quantityText()),
                        "업로드 값으로 적용; 실제 발생량·이동처를 뜻하지 않음"),
                List.of("시험 연결선", safe(stringValue(predecessor.get("managementNo"))) + " / "
                                + safe(stringValue(predecessor.get("facilityName"))) + " → "
                                + safe(equipment.permitManagementNo()) + " → "
                                + safe(stringValue(successor.get("managementNo"))) + " / "
                                + safe(stringValue(successor.get("facilityName"))),
                        "시험 표시만 적용; 실제 설비 관계는 REVIEW_REQUIRED"),
                List.of("검토 필요 표식", "TEST_ONLY / REVIEW_REQUIRED", "도면에 표시; 승인 관계 아님"));
        for (List<String> item : applied) writeRow(document, data, row++, item, 79, 32);

        row++;
        writeMergedTitle(document, data, merges, row++, "A", "C", "추가 확인이 필요한 사항", 1);
        writeRow(document, data, row++, List.of("확인 항목", "현재 확인 결과", "다음 검토 전까지의 처리"), 160, 28);
        List<List<String>> reviews = List.of(
                List.of("선행·후행 설비 관계 승인", "미승인 — REVIEW_REQUIRED", "담당자 근거 확인 필요"),
                List.of("실제 폐수 발생량", "업로드 자료 미입력", "실제값을 추정하거나 채우지 않음"),
                List.of("실제 폐기물 발생량", "업로드 자료 미입력", "실제값을 추정하거나 채우지 않음"),
                List.of("실제 이동처 및 처리 경로", "자료 미입력 / 미확정", "실제값과 승인근거 확인 필요"),
                List.of("실제 물질수지 검증", "실제 발생량·이동처·승인 연결 근거 부족으로 검증 불가", "TEST_ONLY 합성값과 분리"),
                List.of("용량 단위 주의", capacity + " vs ton/day", "환산·비교하지 않음; 설비 처리능력과 시험 물량은 별개"));
        for (List<String> item : reviews) writeRow(document, data, row++, item, 79, 34);
        writeMergedTitle(document, data, merges, row++, "A", "C",
                "본 파일은 run " + changeRunId + " / item " + changeItemId
                        + "에 연결된 TEST_ONLY 검토 자료입니다. 공식 V1.0 도면·유량 및 승인 관계는 수정하지 않았습니다.", 160);

        Element dimension = child(document, root, MAIN_NS, "dimension"); dimension.setAttribute("ref", "A1:C" + (row - 1));
        Element margins = child(document, root, MAIN_NS, "pageMargins");
        margins.setAttribute("left", "0.3"); margins.setAttribute("right", "0.3");
        margins.setAttribute("top", "0.5"); margins.setAttribute("bottom", "0.5");
        margins.setAttribute("header", "0.2"); margins.setAttribute("footer", "0.2");
        Element setup = child(document, root, MAIN_NS, "pageSetup");
        setup.setAttribute("paperSize", "9"); setup.setAttribute("orientation", "landscape");
        setup.setAttribute("fitToWidth", "1"); setup.setAttribute("fitToHeight", "2");
        if (!merges.isEmpty()) {
            Element mergeCells = child(document, root, MAIN_NS, "mergeCells");
            mergeCells.setAttribute("count", Integer.toString(merges.size()));
            for (String range : merges) { Element merge = child(document, mergeCells, MAIN_NS, "mergeCell"); merge.setAttribute("ref", range); }
        }
        return serialize(document);
    }

    private byte[] appendWorksheet(byte[] sourceWorkbook, String sheetName, byte[] worksheet) throws Exception {
        int sheetCount;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceWorkbook))) {
            if (workbook.getSheet(sheetName) != null) throw new InvalidWorkbookException("Worksheet already exists: " + sheetName);
            sheetCount = workbook.getNumberOfSheets();
        }
        validateWorksheetPayload(sheetName, worksheet);
        Map<String, byte[]> parts = readParts(sourceWorkbook);
        String worksheetPart = nextWorksheetPart(parts.keySet());
        String relationshipId = nextRelationshipId(parts.get("xl/_rels/workbook.xml.rels"));
        int lastRow = findWorksheetLastRow(worksheet);
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Map<String, byte[]> styledParts = applyExplicitBlackFont(worksheet, parts.get("xl/styles.xml"));
        worksheet = styledParts.get("worksheet");
        replacements.put("xl/styles.xml", styledParts.get("styles"));
        replacements.put("xl/workbook.xml", patchWorkbook(parts.get("xl/workbook.xml"), sheetCount,
                relationshipId, sheetName, lastRow));
        replacements.put("xl/_rels/workbook.xml.rels", patchWorkbookRelationships(
                parts.get("xl/_rels/workbook.xml.rels"), worksheetPart, relationshipId));
        replacements.put("[Content_Types].xml", patchContentTypes(parts.get("[Content_Types].xml"), worksheetPart));
        if (parts.containsKey("docProps/app.xml")) {
            replacements.put("docProps/app.xml", patchAppProperties(parts.get("docProps/app.xml"), sheetName));
        }
        byte[] result = addPackageParts(sourceWorkbook, replacements, Map.of(worksheetPart, worksheet));
        validateAppendedWorksheet(sourceWorkbook, result, worksheetPart, sheetName, sheetCount);
        return result;
    }

    private void validateAppendedWorksheet(byte[] source, byte[] result, String worksheetPart,
                                           String sheetName, int originalSheetCount) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            if (workbook.getNumberOfSheets() != originalSheetCount + 1 || workbook.getSheet(sheetName) == null) {
                throw new InvalidWorkbookException("The requested TEST_ONLY output sheet was not registered");
            }
            DataFormatter formatter = new DataFormatter();
            var sheet = workbook.getSheet(sheetName);
            if (nonEmptyCellCount(sheet, formatter) == 0 || !containsSheetText(sheet, formatter, "TEST_ONLY")) {
                throw new InvalidWorkbookException("The added worksheet is empty or missing its TEST_ONLY labeling");
            }
            if (CHANGE_HISTORY_SHEET.equals(sheetName)
                    && (!containsSheetText(sheet, formatter, "설비 변경내역 및 도면 반영 결과")
                    || !containsSheetText(sheet, formatter, "C-PW15020")
                    || !containsSheetText(sheet, formatter, "run 193"))) {
                throw new InvalidWorkbookException("Change-history worksheet did not retain its required summary values");
            }
            if (GENERATED_RESULTS_SHEET.equals(sheetName)
                    && (!containsSheetText(sheet, formatter, "물질수지 검토 결과")
                    || !containsSheetText(sheet, formatter, "실제 물질수지: 자료 부족으로 검증 불가")
                    || !containsSheetText(sheet, formatter, "100 = 0.5 + 2.5 + 97"))) {
                throw new InvalidWorkbookException("Material-balance worksheet did not retain its summary or TEST_ONLY equation");
            }
        }
        Map<String, byte[]> before = readParts(source), after = readParts(result);
        java.util.Set<String> allowedChanged = java.util.Set.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "[Content_Types].xml", "docProps/app.xml");
        if (!after.keySet().containsAll(before.keySet())) throw new InvalidWorkbookException("An original OOXML part disappeared");
        validateStyleExtension(before.get("xl/styles.xml"), after.get("xl/styles.xml"));
        java.util.Set<String> added = new java.util.HashSet<>(after.keySet()); added.removeAll(before.keySet());
        if (!added.equals(java.util.Set.of(worksheetPart))) throw new InvalidWorkbookException("Unexpected worksheet OOXML parts: " + added);
        for (String part : before.keySet()) {
            if (allowedChanged.contains(part)) continue;
            if (!java.util.Arrays.equals(before.get(part), after.get(part))) {
                throw new InvalidWorkbookException("Existing OOXML part changed while adding " + sheetName + ": " + part);
            }
        }
        if (!after.containsKey(worksheetPart)) throw new InvalidWorkbookException("Appended worksheet part is missing");
        var beforeInventory = packageInspector.inspect(source);
        var afterInventory = packageInspector.inspect(result);
        if (beforeInventory.shapeCount() != afterInventory.shapeCount()
                || beforeInventory.connectorCount() != afterInventory.connectorCount()
                || beforeInventory.pictureCount() != afterInventory.pictureCount()) {
            throw new InvalidWorkbookException("Drawing inventory changed while adding a result worksheet");
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private ExportArtifact addResultsSheet(byte[] sourceWorkbook, MaterialBalanceExcelOutputModel model,
                                           boolean includeDiagramTestOnlySummary, boolean fixedV5Source,
                                           String actualDataStatus, boolean includeSyntheticTestValues,
                                           String resultsSheetName) {
        if (!"TEST_ONLY".equals(model.mode()) || model.officialWorkbookCellsModified()
                || model.officialMaterialBalanceUpdated()) {
            throw new IllegalArgumentException("Refusing to write non-isolated material-balance data");
        }
        if (includeDiagramTestOnlySummary) validateCpw15020TestOnlySummary(model);
        String actualHash = sha256(sourceWorkbook);
        if (!actualHash.equals(model.sourceWorkbookSha256())) {
            throw new InvalidWorkbookException("Output model was prepared from a different workbook version");
        }
        if (fixedV5Source) validateTestOnlySource(sourceWorkbook);
        else validateGeneratedEditDiagram(sourceWorkbook);
        int existingSheetCount;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceWorkbook))) {
            if (workbook.getSheet(resultsSheetName) != null) {
                throw new InvalidWorkbookException("The input already contains the requested results sheet");
            }
            existingSheetCount = workbook.getNumberOfSheets();
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to open TEST_ONLY V5 source workbook", exception);
        }

        Map<String, byte[]> replacements = new LinkedHashMap<>();
        String worksheetPart;
        int sheetIndex;
        try {
            Map<String, byte[]> packageParts = readParts(sourceWorkbook);
            worksheetPart = nextWorksheetPart(packageParts.keySet());
            sheetIndex = existingSheetCount;
            String relationshipId = nextRelationshipId(packageParts.get("xl/_rels/workbook.xml.rels"));
            byte[] newWorksheet = buildResultsWorksheet(model, includeDiagramTestOnlySummary, actualDataStatus,
                    includeSyntheticTestValues);
            validateWorksheetPayload(resultsSheetName, newWorksheet);
            Map<String, byte[]> styledParts = applyExplicitBlackFont(newWorksheet, packageParts.get("xl/styles.xml"));
            newWorksheet = styledParts.get("worksheet");
            replacements.put("xl/styles.xml", styledParts.get("styles"));
            int lastRow = findWorksheetLastRow(newWorksheet);
            replacements.put("xl/workbook.xml", patchWorkbook(packageParts.get("xl/workbook.xml"),
                    sheetIndex, relationshipId, resultsSheetName, lastRow));
            replacements.put("xl/_rels/workbook.xml.rels", patchWorkbookRelationships(
                    packageParts.get("xl/_rels/workbook.xml.rels"), worksheetPart, relationshipId));
            replacements.put("[Content_Types].xml", patchContentTypes(packageParts.get("[Content_Types].xml"), worksheetPart));
            if (packageParts.containsKey("docProps/app.xml")) {
                replacements.put("docProps/app.xml", patchAppProperties(packageParts.get("docProps/app.xml"), resultsSheetName));
            }
            byte[] result = addPackageParts(sourceWorkbook, replacements, Map.of(worksheetPart, newWorksheet));
            validateOutput(sourceWorkbook, result, model, worksheetPart, existingSheetCount, fixedV5Source,
                    resultsSheetName, includeSyntheticTestValues);
            return new ExportArtifact(result, model, worksheetPart);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to add TEST_ONLY material-balance results sheet", exception);
        }
    }

    public void validateGeneratedEditSource(byte[] bytes) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getSheet(RESULTS_SHEET) != null || workbook.getSheet(GENERATED_RESULTS_SHEET) != null
                    || workbook.getSheet("변경내역") != null) {
                throw new InvalidWorkbookException("Generated edit input already contains an enriched result sheet");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen generated TEST_ONLY edit source", exception);
        }
        validateGeneratedEditDiagram(bytes);
    }

    private void validateGeneratedEditDiagram(byte[] bytes) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("PW-01-05");
            if (sheet == null) throw new InvalidWorkbookException("Generated PW-01-05 worksheet is missing");
            DataFormatter formatter = new DataFormatter();
            if (!"C-PW15020".equals(formatter.formatCellValue(sheet.getRow(197).getCell(34)))
                    || !"SCREEN FILTER".equals(formatter.formatCellValue(sheet.getRow(200).getCell(34)))
                    || !"WW-F-AFT10".equals(formatter.formatCellValue(sheet.getRow(201).getCell(34)))
                    || !"1,500㎥/시".equals(formatter.formatCellValue(sheet.getRow(202).getCell(34)))
                    || !"1ea".equals(formatter.formatCellValue(sheet.getRow(203).getCell(34)))) {
                throw new InvalidWorkbookException("Generated diagram does not contain the expected TEST_ONLY C-PW15020 insertion");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to validate TEST_ONLY diagram after worksheet export", exception);
        }
        var target = packageInspector.inspect(bytes).sheets().stream()
                .filter(sheet -> "PW-01-05".equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Generated PW-01-05 inventory is missing"));
        if (target.drawingObjects().stream().noneMatch(object -> "TEST_ONLY C-PW15020 flow connector".equals(object.objectName()))
                || target.drawingObjects().stream().anyMatch(object -> object.objectName() != null
                && (object.objectName().contains("TEST_ONLY / REVIEW_REQUIRED")
                || object.textContent().contains("TEST_ONLY / REVIEW_REQUIRED")))) {
            throw new InvalidWorkbookException("Generated TEST_ONLY connector is missing or a visual status callout remains");
        }
    }

    public byte[] focusWorksheet(byte[] sourceWorkbook, String sheetName, String focusCell, int zoomScale) {
        if (sourceWorkbook == null || sheetName == null || sheetName.isBlank() || focusCell == null
                || !focusCell.matches("[A-Z]{1,3}[1-9][0-9]*") || zoomScale < 50 || zoomScale > 200) {
            throw new IllegalArgumentException("Valid workbook, worksheet, focus cell and zoom scale are required");
        }
        var sheetInfo = packageInspector.inspect(sourceWorkbook).sheets().stream()
                .filter(sheet -> sheetName.equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Target worksheet is missing: " + sheetName));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceWorkbook))) {
            int sheetIndex = workbook.getSheetIndex(sheetName);
            if (sheetIndex < 0) throw new InvalidWorkbookException("Target worksheet is missing: " + sheetName);
            Map<String, byte[]> replacements = new LinkedHashMap<>();
            Document workbookXml = parse(readPart(sourceWorkbook, "xl/workbook.xml"), "xl/workbook.xml");
            NodeList views = workbookXml.getElementsByTagNameNS(MAIN_NS, "workbookView");
            if (views.getLength() > 0) ((Element) views.item(0)).setAttribute("activeTab", Integer.toString(sheetIndex));
            replacements.put("xl/workbook.xml", serialize(workbookXml));
            Document worksheetXml = parse(readPart(sourceWorkbook, sheetInfo.worksheetPartName()), sheetInfo.worksheetPartName());
            Element view = firstDescendant(worksheetXml.getDocumentElement(), MAIN_NS, "sheetView");
            if (view == null) throw new InvalidWorkbookException("Target worksheet view is missing");
            view.setAttribute("topLeftCell", focusCell);
            view.setAttribute("zoomScale", Integer.toString(zoomScale));
            view.setAttribute("tabSelected", "1");
            Element selection = firstDirectChild(view, MAIN_NS, "selection");
            if (selection != null) {
                selection.setAttribute("activeCell", focusCell);
                selection.setAttribute("sqref", focusCell);
            }
            replacements.put(sheetInfo.worksheetPartName(), serialize(worksheetXml));
            return addPackageParts(sourceWorkbook, replacements, Map.of());
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to focus the generated workbook on " + sheetName, exception);
        }
    }

    private byte[] readPart(byte[] bytes, String partName) {
        return readParts(bytes).get(partName);
    }

    public void validateTestOnlySource(byte[] bytes) {
        if (!EXPECTED_V5_SHA256.equals(sha256(bytes))) {
            throw new InvalidWorkbookException("Only the preserved PW-01-05 TEST_ONLY V5 workbook is accepted as an export source");
        }
        verifyTestOnlyMarker(bytes);
    }

    private void verifyTestOnlyMarker(byte[] bytes) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("PW-01-05");
            if (sheet == null) throw new InvalidWorkbookException("PW-01-05 worksheet is missing");
            var marker = sheet.getRow(197) == null ? null : sheet.getRow(197).getCell(47);
            if (marker == null || !"TEST_ONLY".equals(new DataFormatter().formatCellValue(marker))) {
                throw new InvalidWorkbookException("The source workbook does not contain the expected TEST_ONLY V5 marker");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify the TEST_ONLY V5 source marker", exception);
        }
    }

    private MaterialBalanceExcelOutputModel.LayoutAudit analyzeTestOnlyLayout(byte[] bytes) {
        var sourceToCandidate = pw0105EquipmentInsertionEngine.analyze(bytes,
                new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05", "C-PW15009",
                        "ACTIVATED CARBON FILTER", "C-PW15020", "SCREEN FILTER", "여과시설"));
        var candidateToFinal = pw0105EquipmentInsertionEngine.analyze(bytes,
                new Pw0105EquipmentInsertionEngine.TestEditSpec("PW-01-05", "C-PW15020",
                        "SCREEN FILTER", "C-PW15010", "FINAL EFFLUENT TANK", "여과시설"));
        if (sourceToCandidate.existingConnector().shapeId() == null
                || candidateToFinal.existingConnector().shapeId() == null) {
            throw new InvalidWorkbookException("TEST_ONLY V5 visual connector evidence is incomplete; no workbook export produced");
        }
        String candidateRange = sourceToCandidate.target().topLeftCell() + ":" + sourceToCandidate.target().bottomRightCell();
        String downstreamRange = candidateToFinal.target().topLeftCell() + ":" + candidateToFinal.target().bottomRightCell();
        String wastewaterLabel = findLabelCell(bytes, sourceToCandidate.target(), "폐수");
        String wasteLabel = findLabelCell(bytes, sourceToCandidate.target(), "폐기물");
        return new MaterialBalanceExcelOutputModel.LayoutAudit("C-PW15020", candidateRange,
                "C-PW15010", downstreamRange, sourceToCandidate.existingConnector().shapeId() + ", "
                + candidateToFinal.existingConnector().shapeId(), wastewaterLabel, wasteLabel,
                "VISUAL_CONNECTOR_ONLY_RELATION_NOT_APPROVED; quantity annotations use separate TEST_ONLY Results area");
    }

    private byte[] buildResultsWorksheet(MaterialBalanceExcelOutputModel model, boolean includeDiagramTestOnlySummary,
                                         String actualDataStatus, boolean includeSyntheticTestValues) throws Exception {
        Document document = newDocument();
        Element root = document.createElementNS(MAIN_NS, "worksheet");
        root.setAttribute("xmlns", MAIN_NS);
        document.appendChild(root);
        Element sheetProperties = child(document, root, MAIN_NS, "sheetPr");
        Element tabColor = child(document, sheetProperties, MAIN_NS, "tabColor");
        tabColor.setAttribute("rgb", "FFFFC000");
        Element pageSetupProperties = child(document, sheetProperties, MAIN_NS, "pageSetUpPr");
        pageSetupProperties.setAttribute("fitToPage", "1");
        pageSetupProperties.setAttribute("autoPageBreaks", actualDataStatus == null ? "0" : "1");
        Element dimension = child(document, root, MAIN_NS, "dimension");
        dimension.setAttribute("ref", "A1:M1");
        Element views = child(document, root, MAIN_NS, "sheetViews");
        Element view = child(document, views, MAIN_NS, "sheetView");
        view.setAttribute("showGridLines", "0"); view.setAttribute("workbookViewId", "0");
        view.setAttribute("zoomScale", "85");
        Element selection = child(document, view, MAIN_NS, "selection");
        selection.setAttribute("activeCell", "A1"); selection.setAttribute("sqref", "A1");
        Element format = child(document, root, MAIN_NS, "sheetFormatPr");
        format.setAttribute("defaultRowHeight", "17");
        Element columns = child(document, root, MAIN_NS, "cols");
        double[] widths = {34, 27, 20, 20, 22, 18, 14, 14, 14, 20, 34, 24, 26};
        for (int i = 0; i < widths.length; i++) {
            Element column = child(document, columns, MAIN_NS, "col");
            column.setAttribute("min", Integer.toString(i + 1)); column.setAttribute("max", Integer.toString(i + 1));
            column.setAttribute("width", Double.toString(widths[i])); column.setAttribute("customWidth", "1");
        }
        Element sheetData = child(document, root, MAIN_NS, "sheetData");
        List<String> merges = new ArrayList<>();
        int row = 1;
        String summaryLastColumn = actualDataStatus == null ? "M" : "C";
        if (actualDataStatus == null) {
            writeMergedTitle(document, sheetData, merges, row++, "A", "C",
                    "TEST_ONLY 계산 결과 — 공식 물량 미변경", 1);
            writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                    "가상 시험값 전용입니다. 기존 PW-01-05 설비 셀·원본 물량·연결선은 수정하지 않았습니다.", 160);
        } else {
            writeMergedTitle(document, sheetData, merges, row++, "A", "C",
                    "C-PW15020 / SCREEN FILTER — 물질수지 검토 결과", 1);
            writeMergedTitle(document, sheetData, merges, row++, "A", "C",
                    "실제 물질수지 검증: 불가 — 실제 발생량·이동처·승인 연결 자료 부족", 160);
            writeMergedTitle(document, sheetData, merges, row++, "A", "C",
                    includeSyntheticTestValues
                            ? "TEST_ONLY 가상 계산입니다. 실제 물질수지와 구분해 확인하십시오."
                            : "TEST_ONLY 시험 시나리오 미선택 — 실제 자료 검토 상태만 표시하며 수량을 임의 산정하지 않습니다.", 160);
        }
        writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                includeSyntheticTestValues
                        ? "선택된 가상 시험값의 입력·출구 합계만 확인합니다. 실제 전체 공정·처리시설의 물질수지 검증 결과가 아닙니다."
                        : "실제 자료 검토 상태만 표시합니다. 수량 계산을 수행하지 않았으며 실제 물질수지 검증 결과가 아닙니다.", 79);
        writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                "추가자료 필요: 실제 폐수·폐기물 발생량, 이동·처리 경로, 승인 연결 근거. 1,500㎥/시는 처리능력이며 ton/day로 환산·비교하지 않습니다.", 79);
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                includeSyntheticTestValues ? "요약표 — TEST_ONLY 가상 시험값" : "요약표 — 실제 자료 검토 상태", 1);
        writeRow(document, sheetData, row++, List.of("항목", includeSyntheticTestValues ? "시험 물량" : "확인 상태", "단위 / 근거"), 160, 25);
        if (includeSyntheticTestValues) {
            writeRow(document, sheetData, row++, List.of("유입량", new BigDecimal("100"), "ton/day"), 79, 24);
            writeRow(document, sheetData, row++, List.of("후단 이동", new BigDecimal("97"), "ton/day"), 79, 24);
            writeRow(document, sheetData, row++, List.of("별도 폐수 이동", new BigDecimal("2.5"), "ton/day"), 79, 24);
            writeRow(document, sheetData, row++, List.of("폐기물 발생", new BigDecimal("0.5"), "ton/day"), 79, 24);
            writeFormulaRow(document, sheetData, row++, "출구 합계", "SUM(B10:B12)", "100", "ton/day", 79, 24);
            writeFormulaRow(document, sheetData, row++, "잔차", "B9-B13", "0", "ton/day", 79, 24);
            writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                    "시험 계산: 100 = 0.5 + 2.5 + 97 ton/day — TEST_ONLY 합계만 일치", 160);
            writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                    "시험 잔차는 실제 발생량·이동처·후단 실측값을 확인한 계산이 아닙니다.", 79);
        } else {
            writeRow(document, sheetData, row++, List.of("유입량", "미제공", "실제 계측 자료 없음"), 79, 24);
            writeRow(document, sheetData, row++, List.of("후단 이동", "미제공", "실제 이동량 자료 없음"), 79, 24);
            writeRow(document, sheetData, row++, List.of("별도 폐수 이동", "미제공", "실제 이동처·수량 미확인"), 79, 24);
            writeRow(document, sheetData, row++, List.of("폐기물 발생", "미제공", "실제 발생량·처리처 미확인"), 79, 24);
            writeRow(document, sheetData, row++, List.of("출구 합계", "산출 안 함", "실제값 부족"), 79, 24);
            writeRow(document, sheetData, row++, List.of("잔차", "산출 안 함", "실제값 부족"), 79, 24);
            writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                    "시험 계산: 미선택 — 실제 유입·출구 수량 및 잔차를 산정하지 않았습니다.", 160);
            writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn,
                    "시험 시나리오를 명시적으로 선택하지 않아 가상 수치를 적용하지 않았습니다.", 79);
        }
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", summaryLastColumn, "쉬운 검토 결과", 1);
        writeRow(document, sheetData, row++, List.of("구분", "상태", "설명"), 160, 25);
        List<List<String>> overview = List.of(
                List.of("시험 계산", includeSyntheticTestValues ? "입력과 출력 합계 일치" : "미선택 — 수량 산출 안 함",
                        includeSyntheticTestValues ? "TEST_ONLY 가상 시험값만 계산" : "가상 시험값 미적용"),
                List.of("실제 자료", "발생량과 이동처 미입력", "실제 물질수지는 검증 불가"),
                List.of("후단 공정", "실제 유입·유출량 확인 필요", includeSyntheticTestValues
                        ? "시험 후단 이동량은 실측값이 아님" : "실제 이동량 자료 미제공"),
                List.of("폐수 이동처", "미확정", "실제 수신시설 및 처리 경로 미제공"),
                List.of("폐기물 처리·반출처", "미확정", "실제 처분·반출 자료 미제공"),
                List.of("승인 관계", "REVIEW_REQUIRED", "CONFIRMED 관계는 생성하지 않음"));
        for (List<String> item : overview) writeRow(document, sheetData, row++, item, 79, 28);
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", "M", "상세 검토 자료", 1);
        writeMergedTitle(document, sheetData, merges, row++, "A", "M", "계산 식별 정보 및 자동 탐색 결과", 160);
        writeRow(document, sheetData, row++, List.of("Simulation ID", model.simulationId()), 79);
        writeRow(document, sheetData, row++, List.of("시나리오", model.scenarioName()), 79);
        writeRow(document, sheetData, row++, List.of("입력 workbook SHA-256", model.sourceWorkbookSha256()), 79);
        writeRow(document, sheetData, row++, List.of("시험 설비 및 셀", model.layoutAudit().existingTestOnlyCandidateId()
                + " / " + model.layoutAudit().existingTestOnlyCandidateCellRange()), 79);
        writeRow(document, sheetData, row++, List.of("후단 후보 및 시각 connector", model.layoutAudit().downstreamEquipmentId()
                + " / " + model.layoutAudit().downstreamEquipmentCellRange() + " / shape "
                + model.layoutAudit().visualConnectorShapeId()), 79);
        writeRow(document, sheetData, row++, List.of("관계 승인 상태",
                "TEST_ONLY 시각 표시만 있음; 관계와 실제 설비 매핑은 REVIEW_REQUIRED"), 160);
        if (includeDiagramTestOnlySummary) {
            row = writeDiagramTestOnlySummary(document, sheetData, merges, row, model);
        }
        row++;

        writeMergedTitle(document, sheetData, merges, row++, "A", "M", "송수신 상세 대사 — 기술 검토 자료", 1);
        writeRow(document, sheetData, row++, List.of("transferId", "송출→수신 물질 종류", "송출 노드", "송출 공정",
                "수신 노드", "수신 공정", "송출량", "수신량", "단위", "대사 상태", "포함 여부 / 제외 사유"), 160);
        for (var line : model.transfers()) {
            String routeFlow = flowLabel(line.senderFlowType()) + " → " + flowLabel(line.receiverFlowType());
            String source = joinNode(line.sourceNodeId(), line.sourceLabel(), line.sourceEquipmentId());
            String target = joinNode(line.targetNodeId(), line.targetLabel(), line.targetEquipmentId());
            String included = line.included() ? "포함" : "제외: " + safe(line.exclusionReason());
            List<Object> values = List.of(line.transferId(), routeFlow, source, safe(line.sourceProcessCode()),
                    target, safe(line.targetProcessCode()), numberOrLabel(line.senderQuantity()),
                    numberOrLabel(line.receiverQuantity()), safe(line.unit()), reconciliationLabel(line.reconciliationStatus()), included);
            writeRow(document, sheetData, row++, values, line.included() ? 79 : 149);
        }
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", "M", "설비/공정별 물질수지 — 잔차는 자동 보정하지 않음", 1);
        writeRow(document, sheetData, row++, List.of("node / label", "공정", "설비 역할", "수신 유형",
                "물질 종류", "유입량", "유출량", "저장량 변화", "잔차", "단위", "balance 상태",
                "최종 경계조건", "수신 경계 상태"), 160);
        for (var line : model.balances()) {
            String node = joinNode(line.nodeId(), line.label(), line.equipmentId());
            String boundary = safe(line.boundaryCondition());
            List<Object> values = List.of(node, safe(line.processCode()), roleLabel(line.role()), receiverLabel(line),
                    flowLabel(line.flowType()), numberOrLabel(line.inflow()), numberOrLabel(line.outflow()),
                    numberOrLabel(line.storageChange()), numberOrLabel(line.residual()), safe(line.unit()),
                    balanceLabel(line.balanceStatus()), boundary.isBlank() ? "미명시" : boundary, dispositionLabel(line.dispositionStatus()));
            writeRow(document, sheetData, row++, values,
                    line.residual() != null && line.residual().signum() != 0 ? 149 : 79);
        }
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", "M", "실제 PW-01-05 설비/셀 매핑 확인", 1);
        writeRow(document, sheetData, row++, List.of("nodeId", "label", "공정", "요청 equipmentId",
                "실제 workbook 셀 매핑", "매핑 상태"), 160);
        for (var mapping : model.nodeMappings()) {
            String equipment = mapping.equipmentId() == null ? "미제공" : mapping.equipmentId() + " (미검증)";
            writeRow(document, sheetData, row++, List.of(mapping.nodeId(), mapping.label(), safe(mapping.processCode()),
                    equipment, mapping.workbookCellMapping(), mappingLabel(mapping.mappingStatus())), 79);
        }
        row++;
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                includeSyntheticTestValues
                        ? "주의: 이 시트의 연결은 승인 관계가 아닙니다. 미지정 수신처·폐기물 처분/반출 및 후단 실유량은 현장 확인 전 미확정입니다."
                        : "주의: 실제 발생량·이동량·처리 경계 자료가 없어 계산하지 않았습니다. 잔차나 실제 방류량을 산출하지 않습니다.", 79);
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                actualDataStatus == null || actualDataStatus.isBlank()
                        ? "실제 물질수지: 자료 부족으로 검증 불가 (실제 발생량·이동처·승인 연결 근거 미제공). 가상 시험값과 혼동하지 마십시오."
                        : actualDataStatus, 149);
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                "이 workbook은 새 TEST_ONLY 사본입니다. 공식 도면·기준자료에 반영하지 마십시오.", 79);

        dimension.setAttribute("ref", "A1:M" + (row - 1));
        if (!merges.isEmpty()) {
            Element mergeCells = child(document, root, MAIN_NS, "mergeCells");
            mergeCells.setAttribute("count", Integer.toString(merges.size()));
            for (String range : merges) {
                Element merge = child(document, mergeCells, MAIN_NS, "mergeCell");
                merge.setAttribute("ref", range);
            }
        }
        Element printOptions = child(document, root, MAIN_NS, "printOptions");
        printOptions.setAttribute("gridLines", "0"); printOptions.setAttribute("horizontalCentered", "1");
        Element margins = child(document, root, MAIN_NS, "pageMargins");
        margins.setAttribute("left", "0.25"); margins.setAttribute("right", "0.25");
        margins.setAttribute("top", "0.4"); margins.setAttribute("bottom", "0.4");
        margins.setAttribute("header", "0.2"); margins.setAttribute("footer", "0.2");
        Element setup = child(document, root, MAIN_NS, "pageSetup");
        setup.setAttribute("paperSize", "8"); setup.setAttribute("orientation", "landscape");
        setup.setAttribute("fitToWidth", "1");
        setup.setAttribute("fitToHeight", actualDataStatus == null ? "1" : "2");
        return serialize(document);
    }

    private int writeDiagramTestOnlySummary(Document document, Element sheetData, List<String> merges,
                                             int row, MaterialBalanceExcelOutputModel model) {
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                "C-PW15020 도면용 TEST_ONLY 물량 표시 — 공식 도면 셀은 변경하지 않음", 1);
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                "자동탐색 box " + model.layoutAudit().existingTestOnlyCandidateCellRange()
                        + " / 폐수 구획 라벨 " + model.layoutAudit().wastewaterLabelCell()
                        + " / 폐기물 구획 라벨 " + model.layoutAudit().wasteLabelCell()
                        + " — 실제 표시 공간이 부족하여 별도 TEST_ONLY 영역에 표시", 79);
        writeRow(document, sheetData, row++, List.of("표시유형", "TEST_ONLY 연결/구획", "시험 물량", "단위", "관계 상태"), 160);
        BigDecimal inflow = transferAmount(model, "cpw15009-cpw15020-in");
        BigDecimal waste = transferAmount(model, "cpw15020-waste-0_5");
        BigDecimal unassignedWastewater = transferAmount(model, "cpw15020-unassigned-2_5");
        BigDecimal downstream = transferAmount(model, "cpw15020-cpw15010-out");
        writeRow(document, sheetData, row++, List.of("유입", "C-PW15009 → C-PW15020", inflow, "ton/day", "REVIEW_REQUIRED"), 79);
        writeRow(document, sheetData, row++, List.of("폐수 구획", "TEST_ONLY 미지정 수신처 (실설비 미지정)", unassignedWastewater, "ton/day", "REVIEW_REQUIRED"), 79);
        writeRow(document, sheetData, row++, List.of("폐기물 구획", "발생량 (처분·반출처 미확정)", waste, "ton/day", "미확정"), 79);
        writeRow(document, sheetData, row++, List.of("후단 이동", "C-PW15020 → C-PW15010", downstream, "ton/day", "REVIEW_REQUIRED"), 79);
        writeMergedTitle(document, sheetData, merges, row++, "A", "M",
                "시험 질량수지: " + inflow.toPlainString() + " = " + waste.toPlainString() + " + "
                        + unassignedWastewater.toPlainString() + " + " + downstream.toPlainString()
                        + " ton/day — 계산값 일치; 미확정 목적지/승인관계는 자동 확정하지 않음", 79);
        return row;
    }

    private BigDecimal transferAmount(MaterialBalanceExcelOutputModel model, String transferId) {
        return model.transfers().stream().filter(line -> transferId.equals(line.transferId()))
                .map(MaterialBalanceExcelOutputModel.TransferLine::senderQuantity).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Calculated TEST_ONLY transfer quantity is missing: " + transferId));
    }

    private void validateCpw15020TestOnlySummary(MaterialBalanceExcelOutputModel model) {
        Map<String, MaterialBalanceExcelOutputModel.TransferLine> byId = new LinkedHashMap<>();
        model.transfers().forEach(line -> byId.put(line.transferId(), line));
        assertTransfer(byId, "cpw15009-cpw15020-in", "C-PW15009", "C-PW15020", "100");
        assertTransfer(byId, "cpw15020-waste-0_5", "C-PW15020", "UNASSIGNED_WASTE_OUTPUT", "0.5");
        assertTransfer(byId, "cpw15020-unassigned-2_5", "C-PW15020", "UNASSIGNED_WASTEWATER_RECEIVER", "2.5");
        assertTransfer(byId, "cpw15020-cpw15010-out", "C-PW15020", "C-PW15010", "97");
        if (byId.size() != 4 || model.balances().stream().noneMatch(line -> "C-PW15020".equals(line.nodeId())
                && line.inflow() != null && line.inflow().compareTo(new BigDecimal("100")) == 0
                && line.outflow() != null && line.outflow().compareTo(new BigDecimal("100")) == 0
                && line.residual() != null && line.residual().signum() == 0
                && "CALCULATED".equals(line.balanceStatus()))) {
            throw new InvalidWorkbookException("C-PW15020 TEST_ONLY balance does not equal 100 = 0.5 + 2.5 + 97");
        }
        if (!"C-PW15020".equals(model.layoutAudit().existingTestOnlyCandidateId())
                || model.layoutAudit().wastewaterLabelCell() == null || model.layoutAudit().wasteLabelCell() == null) {
            throw new InvalidWorkbookException("Automatic C-PW15020 compartment discovery is incomplete");
        }
    }

    private void assertTransfer(Map<String, MaterialBalanceExcelOutputModel.TransferLine> transfers, String id,
                                String source, String target, String quantity) {
        var line = transfers.get(id);
        if (line == null || !line.included() || !source.equals(line.sourceNodeId())
                || !target.equals(line.targetNodeId()) || line.senderQuantity() == null
                || line.senderQuantity().compareTo(new BigDecimal(quantity)) != 0
                || line.receiverQuantity() == null || line.receiverQuantity().compareTo(line.senderQuantity()) != 0
                || line.targetEquipmentId() != null || line.sourceEquipmentId() != null) {
            throw new InvalidWorkbookException("Invalid TEST_ONLY diagram transfer: " + id);
        }
    }

    private String findLabelCell(byte[] bytes, Pw0105EquipmentInsertionEngine.EquipmentBox box, String label) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("PW-01-05");
            DataFormatter formatter = new DataFormatter();
            List<String> matches = new ArrayList<>();
            for (int rowIndex = box.firstRow(); rowIndex <= box.lastRow(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (var cell : row) {
                    if (cell.getColumnIndex() >= box.firstColumn() && cell.getColumnIndex() <= box.lastColumn()
                            && label.equals(formatter.formatCellValue(cell).trim())) matches.add(cell.getAddress().formatAsString());
                }
            }
            if (matches.size() != 1) throw new InvalidWorkbookException("Cannot uniquely locate " + label + " compartment label in " + box.managementNo());
            return matches.get(0);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read detected compartment label", exception);
        }
    }

    private void writeMergedTitle(Document document, Element sheetData, List<String> merges,
                                  int row, String firstColumn, String lastColumn, String text, int style) {
        int first = columnNumber(firstColumn), last = columnNumber(lastColumn);
        Element rowElement = child(document, sheetData, MAIN_NS, "row");
        int height = style == 1 ? 26 : (text != null && text.length() > 100 ? 48 : 34);
        rowElement.setAttribute("r", Integer.toString(row)); rowElement.setAttribute("ht", Integer.toString(height));
        rowElement.setAttribute("customHeight", "1");
        for (int column = first; column <= last; column++) {
            String reference = columnName(column) + row;
            String value = column == first ? text : null;
            writeCell(document, rowElement, reference, value, style, false);
        }
        merges.add(firstColumn + row + ":" + lastColumn + row);
    }

    private void writeRow(Document document, Element sheetData, int rowNumber, List<?> values, int defaultStyle) {
        writeRow(document, sheetData, rowNumber, values, defaultStyle, defaultStyle == 0 ? 15 : 22);
    }

    private void writeRow(Document document, Element sheetData, int rowNumber, List<?> values,
                         int defaultStyle, int rowHeight) {
        Element row = child(document, sheetData, MAIN_NS, "row");
        row.setAttribute("r", Integer.toString(rowNumber));
        row.setAttribute("ht", Integer.toString(rowHeight));
        row.setAttribute("customHeight", "1");
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            boolean numeric = value instanceof java.math.BigDecimal;
            writeCell(document, row, columnName(index + 1) + rowNumber,
                    value == null ? "" : value.toString(), defaultStyle, numeric);
        }
    }

    private void writeFormulaRow(Document document, Element sheetData, int rowNumber,
                                 String label, String formula, String cachedValue, String unit,
                                 int style, int rowHeight) {
        Element row = child(document, sheetData, MAIN_NS, "row");
        row.setAttribute("r", Integer.toString(rowNumber));
        row.setAttribute("ht", Integer.toString(rowHeight));
        row.setAttribute("customHeight", "1");
        writeCell(document, row, "A" + rowNumber, label, style, false);
        Element value = child(document, row, MAIN_NS, "c");
        value.setAttribute("r", "B" + rowNumber);
        value.setAttribute("s", Integer.toString(style));
        value.setAttribute("t", "n");
        child(document, value, MAIN_NS, "f").setTextContent(formula);
        child(document, value, MAIN_NS, "v").setTextContent(cachedValue);
        writeCell(document, row, "C" + rowNumber, unit, style, false);
    }

    private int countWorksheetCells(byte[] worksheetXml) {
        try {
            Document document = parse(worksheetXml, "worksheet payload");
            Element data = firstDescendant(document.getDocumentElement(), MAIN_NS, "sheetData");
            if (data == null) return 0;
            return data.getElementsByTagNameNS(MAIN_NS, "c").getLength();
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inspect worksheet payload", exception);
        }
    }

    private void validateWorksheetPayload(String sheetName, byte[] worksheetXml) {
        int cellCount = countWorksheetCells(worksheetXml);
        if (cellCount == 0) throw new InvalidWorkbookException("Refusing to add empty worksheet: " + sheetName);
        try {
            Document document = parse(worksheetXml, sheetName);
            Element data = firstDescendant(document.getDocumentElement(), MAIN_NS, "sheetData");
            NodeList cells = data.getElementsByTagNameNS(MAIN_NS, "c");
            boolean hasValue = false;
            for (int i = 0; i < cells.getLength(); i++) {
                Element cell = (Element) cells.item(i);
                if (cell.getElementsByTagNameNS(MAIN_NS, "v").getLength() > 0
                        || cell.getElementsByTagNameNS(MAIN_NS, "f").getLength() > 0
                        || cell.getElementsByTagNameNS(MAIN_NS, "t").getLength() > 0) {
                    hasValue = true;
                    break;
                }
            }
            if (!hasValue) throw new InvalidWorkbookException("Refusing worksheet without populated values: " + sheetName);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to validate worksheet contents: " + sheetName, exception);
        }
    }

    private void writeCell(Document document, Element row, String address, String value, int style, boolean numeric) {
        Element cell = child(document, row, MAIN_NS, "c");
        cell.setAttribute("r", address);
        if (style != 0) cell.setAttribute("s", Integer.toString(style));
        if (value == null) return;
        if (numeric) {
            cell.setAttribute("t", "n");
            child(document, cell, MAIN_NS, "v").setTextContent(value);
        } else {
            cell.setAttribute("t", "inlineStr");
            Element inline = child(document, cell, MAIN_NS, "is");
            Element text = child(document, inline, MAIN_NS, "t");
            text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            text.setTextContent(value);
        }
    }

    private byte[] patchWorkbook(byte[] xml, int sheetIndex, String relationshipId, int lastRow) throws Exception {
        return patchWorkbook(xml, sheetIndex, relationshipId, RESULTS_SHEET, lastRow);
    }

    private byte[] patchWorkbook(byte[] xml, int sheetIndex, String relationshipId,
                                 String sheetName, int lastRow) throws Exception {
        Document document = parse(xml, "xl/workbook.xml");
        Element root = document.getDocumentElement();
        Element sheets = firstDescendant(root, MAIN_NS, "sheets");
        if (sheets == null) throw new InvalidWorkbookException("Workbook sheet list is missing");
        int maxSheetId = 0;
        for (Element sheet : directChildren(sheets, MAIN_NS, "sheet")) {
            maxSheetId = Math.max(maxSheetId, Integer.parseInt(sheet.getAttribute("sheetId")));
            if (sheetName.equals(sheet.getAttribute("name"))) throw new InvalidWorkbookException("TEST_ONLY worksheet already exists: " + sheetName);
        }
        Element sheet = document.createElementNS(MAIN_NS, "sheet");
        sheet.setAttribute("name", sheetName); sheet.setAttribute("sheetId", Integer.toString(maxSheetId + 1));
        sheet.setAttributeNS(REL_NS, "r:id", relationshipId);
        sheets.appendChild(sheet);
        Element definedNames = firstDescendant(root, MAIN_NS, "definedNames");
        if (definedNames == null) {
            definedNames = document.createElementNS(MAIN_NS, "definedNames");
            Node calcPr = firstDirectChild(root, MAIN_NS, "calcPr");
            root.insertBefore(definedNames, calcPr);
        }
        Element printArea = document.createElementNS(MAIN_NS, "definedName");
        printArea.setAttribute("name", "_xlnm.Print_Area");
        printArea.setAttribute("localSheetId", Integer.toString(sheetIndex));
        String lastColumn = (CHANGE_HISTORY_SHEET.equals(sheetName)
                || GENERATED_RESULTS_SHEET.equals(sheetName)) ? "C" : "M";
        int printLastRow = GENERATED_RESULTS_SHEET.equals(sheetName) ? 25 : lastRow;
        printArea.setTextContent("'" + sheetName + "'!$A$1:$" + lastColumn + "$" + printLastRow);
        definedNames.appendChild(printArea);
        NodeList views = root.getElementsByTagNameNS(MAIN_NS, "workbookView");
        if (views.getLength() > 0) {
            ((Element) views.item(0)).setAttribute("activeTab", Integer.toString(sheetIndex));
        }
        NodeList calculations = root.getElementsByTagNameNS(MAIN_NS, "calcPr");
        if (calculations.getLength() > 0) {
            Element calculation = (Element) calculations.item(0);
            calculation.setAttribute("calcMode", "auto");
            calculation.setAttribute("fullCalcOnLoad", "1");
            calculation.setAttribute("forceFullCalc", "1");
        }
        return serialize(document);
    }

    private byte[] patchWorkbookRelationships(byte[] xml, String worksheetPart, String relationshipId) throws Exception {
        Document document = parse(xml, "xl/_rels/workbook.xml.rels");
        Element root = document.getDocumentElement();
        for (Element current : directChildren(root, PACKAGE_REL_NS, "Relationship")) {
            if (relationshipId.equals(current.getAttribute("Id"))) {
                throw new InvalidWorkbookException("Workbook relationship id already exists: " + relationshipId);
            }
        }
        Element relationship = document.createElementNS(PACKAGE_REL_NS, "Relationship");
        relationship.setAttribute("Id", relationshipId);
        relationship.setAttribute("Type", REL_NS + "/worksheet");
        relationship.setAttribute("Target", worksheetPart.substring("xl/".length()));
        root.appendChild(relationship);
        return serialize(document);
    }

    private byte[] patchContentTypes(byte[] xml, String worksheetPart) throws Exception {
        Document document = parse(xml, "[Content_Types].xml");
        Element root = document.getDocumentElement();
        for (Element override : directChildren(root, CONTENT_TYPE_NS, "Override")) {
            if (("/" + worksheetPart).equals(override.getAttribute("PartName"))) {
                throw new InvalidWorkbookException("New worksheet content type already exists");
            }
        }
        Element override = document.createElementNS(CONTENT_TYPE_NS, "Override");
        override.setAttribute("PartName", "/" + worksheetPart);
        override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
        root.appendChild(override);
        return serialize(document);
    }

    private byte[] patchAppProperties(byte[] xml) throws Exception {
        return patchAppProperties(xml, RESULTS_SHEET);
    }

    private byte[] patchAppProperties(byte[] xml, String addedSheetName) throws Exception {
        Document document = parse(xml, "docProps/app.xml");
        Element root = document.getDocumentElement();
        Element heading = firstDescendant(root, APP_NS, "HeadingPairs");
        Element titles = firstDescendant(root, APP_NS, "TitlesOfParts");
        if (heading == null || titles == null) return serialize(document);
        Element headingVector = firstDescendant(heading, VT_NS, "vector");
        List<Element> variants = directChildren(headingVector, VT_NS, "variant");
        if (variants.size() >= 2) {
            Element sheetCount = firstDescendant(variants.get(1), VT_NS, "i4");
            if (sheetCount != null) sheetCount.setTextContent(Integer.toString(Integer.parseInt(sheetCount.getTextContent()) + 1));
        }
        Element titleVector = firstDescendant(titles, VT_NS, "vector");
        if (titleVector != null) {
            titleVector.setAttribute("size", Integer.toString(Integer.parseInt(titleVector.getAttribute("size")) + 1));
            Element newTitle = document.createElementNS(VT_NS, "vt:lpstr"); newTitle.setTextContent(addedSheetName);
            Element insertBefore = null;
            for (Element item : directChildren(titleVector, VT_NS, "lpstr")) {
                if (item.getTextContent().contains("Print_Area") || item.getTextContent().contains("Print_Titles")) {
                    insertBefore = item; break;
                }
            }
            titleVector.insertBefore(newTitle, insertBefore);
        }
        return serialize(document);
    }

    private Map<String, byte[]> applyExplicitBlackFont(byte[] worksheetXml, byte[] stylesXml) throws Exception {
        if (stylesXml == null) throw new InvalidWorkbookException("Workbook styles part is missing");
        Document styles = parse(stylesXml, "xl/styles.xml");
        Document worksheet = parse(worksheetXml, "new worksheet");
        Element fonts = firstDescendant(styles.getDocumentElement(), MAIN_NS, "fonts");
        Element cellXfs = firstDescendant(styles.getDocumentElement(), MAIN_NS, "cellXfs");
        Element sheetData = firstDescendant(worksheet.getDocumentElement(), MAIN_NS, "sheetData");
        if (fonts == null || cellXfs == null || sheetData == null) {
            throw new InvalidWorkbookException("Workbook font/style catalog or worksheet data is missing");
        }
        int originalFontCount = directChildren(fonts, MAIN_NS, "font").size();
        int originalXfCount = directChildren(cellXfs, MAIN_NS, "xf").size();
        Map<Integer, Integer> blackFontIds = new LinkedHashMap<>();
        Map<Integer, Integer> blackStyleIds = new LinkedHashMap<>();
        NodeList cells = sheetData.getElementsByTagNameNS(MAIN_NS, "c");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            int sourceStyleId = cell.hasAttribute("s") ? Integer.parseInt(cell.getAttribute("s")) : 0;
            if (sourceStyleId < 0 || sourceStyleId >= originalXfCount) {
                throw new InvalidWorkbookException("Worksheet references an unknown cell style: " + sourceStyleId);
            }
            Integer targetStyleId = blackStyleIds.get(sourceStyleId);
            if (targetStyleId == null) {
                List<Element> sourceStyles = directChildren(cellXfs, MAIN_NS, "xf");
                Element sourceStyle = sourceStyles.get(sourceStyleId);
                int sourceFontId = sourceStyle.hasAttribute("fontId")
                        ? Integer.parseInt(sourceStyle.getAttribute("fontId")) : 0;
                Integer blackFontId = blackFontIds.get(sourceFontId);
                if (blackFontId == null) {
                    List<Element> sourceFonts = directChildren(fonts, MAIN_NS, "font");
                    if (sourceFontId < 0 || sourceFontId >= originalFontCount) {
                        throw new InvalidWorkbookException("Cell style references an unknown font: " + sourceFontId);
                    }
                    Element font = (Element) styles.importNode(sourceFonts.get(sourceFontId), true);
                    Element color = firstDirectChild(font, MAIN_NS, "color");
                    if (color == null) {
                        color = styles.createElementNS(MAIN_NS, "color");
                        Node insertionPoint = firstDirectChild(font, MAIN_NS, "sz");
                        if (insertionPoint == null) insertionPoint = firstDirectChild(font, MAIN_NS, "u");
                        if (insertionPoint == null) insertionPoint = firstDirectChild(font, MAIN_NS, "vertAlign");
                        if (insertionPoint == null) insertionPoint = firstDirectChild(font, MAIN_NS, "scheme");
                        if (insertionPoint == null) font.appendChild(color);
                        else font.insertBefore(color, insertionPoint);
                    }
                    for (String attribute : List.of("theme", "indexed", "auto", "tint", "rgb")) {
                        color.removeAttribute(attribute);
                    }
                    color.setAttribute("rgb", "FF000000");
                    blackFontId = originalFontCount + blackFontIds.size();
                    fonts.appendChild(font);
                    blackFontIds.put(sourceFontId, blackFontId);
                }
                Element style = (Element) styles.importNode(sourceStyle, true);
                style.setAttribute("fontId", Integer.toString(blackFontId));
                style.setAttribute("applyFont", "1");
                targetStyleId = originalXfCount + blackStyleIds.size();
                cellXfs.appendChild(style);
                blackStyleIds.put(sourceStyleId, targetStyleId);
            }
            cell.setAttribute("s", Integer.toString(targetStyleId));
        }
        fonts.setAttribute("count", Integer.toString(directChildren(fonts, MAIN_NS, "font").size()));
        cellXfs.setAttribute("count", Integer.toString(directChildren(cellXfs, MAIN_NS, "xf").size()));
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("worksheet", serialize(worksheet));
        result.put("styles", serialize(styles));
        return result;
    }

    private void validateStyleExtension(byte[] sourceStylesXml, byte[] outputStylesXml) {
        try {
            if (sourceStylesXml == null || outputStylesXml == null) {
                throw new InvalidWorkbookException("Workbook styles part is missing after TEST_ONLY export");
            }
            Document source = parse(sourceStylesXml, "source xl/styles.xml");
            Document output = parse(outputStylesXml, "output xl/styles.xml");
            Element sourceFonts = firstDescendant(source.getDocumentElement(), MAIN_NS, "fonts");
            Element outputFonts = firstDescendant(output.getDocumentElement(), MAIN_NS, "fonts");
            Element sourceXfs = firstDescendant(source.getDocumentElement(), MAIN_NS, "cellXfs");
            Element outputXfs = firstDescendant(output.getDocumentElement(), MAIN_NS, "cellXfs");
            if (sourceFonts == null || outputFonts == null || sourceXfs == null || outputXfs == null) {
                throw new InvalidWorkbookException("Workbook font/style catalog is incomplete");
            }
            List<Element> beforeFonts = directChildren(sourceFonts, MAIN_NS, "font");
            List<Element> afterFonts = directChildren(outputFonts, MAIN_NS, "font");
            List<Element> beforeXfs = directChildren(sourceXfs, MAIN_NS, "xf");
            List<Element> afterXfs = directChildren(outputXfs, MAIN_NS, "xf");
            if (afterFonts.size() < beforeFonts.size() || afterXfs.size() < beforeXfs.size()) {
                throw new InvalidWorkbookException("Original Excel styles were removed");
            }
            for (int index = 0; index < beforeFonts.size(); index++) {
                if (!beforeFonts.get(index).isEqualNode(afterFonts.get(index))) {
                    throw new InvalidWorkbookException("An original Excel font was modified at index " + index);
                }
            }
            for (int index = 0; index < beforeXfs.size(); index++) {
                if (!beforeXfs.get(index).isEqualNode(afterXfs.get(index))) {
                    throw new InvalidWorkbookException("An original Excel cell style was modified at index " + index);
                }
            }
            for (int index = beforeFonts.size(); index < afterFonts.size(); index++) {
                Element color = firstDirectChild(afterFonts.get(index), MAIN_NS, "color");
                if (color == null || !"FF000000".equalsIgnoreCase(color.getAttribute("rgb"))
                        || color.hasAttribute("theme")) {
                    throw new InvalidWorkbookException("New result font is not explicit RGB black at index " + index);
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to validate append-only Excel style changes", exception);
        }
    }

    private byte[] addPackageParts(byte[] source, Map<String, byte[]> replacements,
                                   Map<String, byte[]> additions) throws Exception {
        Map<String, byte[]> sourceNames = readParts(source);
        for (String part : additions.keySet()) {
            if (sourceNames.containsKey(part)) throw new InvalidWorkbookException("OOXML part already exists: " + part);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(source.length + 65536);
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(source)).get();
             ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                byte[] replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    ZipArchiveEntry changed = new ZipArchiveEntry(entry.getName());
                    changed.setMethod(ZipArchiveEntry.DEFLATED); changed.setTime(entry.getTime());
                    output.putArchiveEntry(changed); output.write(replacement); output.closeArchiveEntry();
                } else {
                    try (InputStream raw = zip.getRawInputStream(entry)) {
                        output.addRawArchiveEntry(new ZipArchiveEntry(entry), raw);
                    }
                }
            }
            for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
                ZipArchiveEntry created = new ZipArchiveEntry(addition.getKey());
                created.setMethod(ZipArchiveEntry.DEFLATED); created.setTime(0L);
                output.putArchiveEntry(created); output.write(addition.getValue()); output.closeArchiveEntry();
            }
            output.finish();
        }
        return bytes.toByteArray();
    }

    private int nonEmptyCellCount(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter) {
        int count = 0;
        for (var row : sheet) {
            for (var cell : row) if (!formatter.formatCellValue(cell).isBlank()) count++;
        }
        return count;
    }

    private boolean containsSheetText(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter, String expected) {
        for (var row : sheet) {
            for (var cell : row) if (formatter.formatCellValue(cell).contains(expected)) return true;
        }
        return false;
    }

    private void validateOutput(byte[] source, byte[] result, MaterialBalanceExcelOutputModel model,
                                String worksheetPart, int originalSheetCount, boolean fixedV5Source,
                                String resultsSheetName, boolean includeSyntheticTestValues) throws Exception {
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var workbook = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            if (workbook.getNumberOfSheets() != originalSheetCount + 1
                    || workbook.getSheet(resultsSheetName) == null
                    || workbook.getSheetAt(originalSheetCount).getSheetName().equals(resultsSheetName) == false) {
                throw new InvalidWorkbookException("TEST_ONLY results sheet was not registered correctly");
            }
            var oldSheet = sourceWorkbook.getSheet("PW-01-05");
            var newOldSheet = workbook.getSheet("PW-01-05");
            DataFormatter formatter = new DataFormatter();
            if (fixedV5Source) {
                String oldMarker = formatter.formatCellValue(oldSheet.getRow(197).getCell(47));
                String newMarker = formatter.formatCellValue(newOldSheet.getRow(197).getCell(47));
                if (!oldMarker.equals(newMarker) || !"TEST_ONLY".equals(newMarker)) {
                    throw new InvalidWorkbookException("The original PW-01-05 TEST_ONLY marker changed during export");
                }
            } else {
                validateGeneratedEditDiagram(result);
            }
            var resultsSheet = workbook.getSheet(resultsSheetName);
            String title = formatter.formatCellValue(resultsSheet.getRow(0).getCell(0));
            if (fixedV5Source) {
                if (!title.contains("TEST_ONLY") || !title.contains("공식 물량 미변경")) {
                    throw new InvalidWorkbookException("TEST_ONLY warning title is missing from the result worksheet");
                }
            } else if (!title.contains("물질수지 검토 결과")
                    || !containsSheetText(resultsSheet, formatter, "실제 물질수지 검증: 불가")
                    || (includeSyntheticTestValues
                    && (!containsSheetText(resultsSheet, formatter, "TEST_ONLY 가상 계산입니다.")
                    || !containsSheetText(resultsSheet, formatter, "100 = 0.5 + 2.5 + 97")))
                    || (!includeSyntheticTestValues
                    && (!containsSheetText(resultsSheet, formatter, "시험 시나리오 미선택")
                    || !containsSheetText(resultsSheet, formatter, "실제값 부족")))
                    || nonEmptyCellCount(resultsSheet, formatter) < 40) {
                throw new InvalidWorkbookException("Readable TEST_ONLY balance review sheet is missing or empty");
            }
            if (GENERATED_RESULTS_SHEET.equals(resultsSheetName)) {
                var historySheet = workbook.getSheet(CHANGE_HISTORY_SHEET);
                if (historySheet == null || nonEmptyCellCount(historySheet, formatter) < 50
                        || !containsSheetText(historySheet, formatter, "설비 변경내역 및 도면 반영 결과")
                        || !containsSheetText(historySheet, formatter, "변경 항목")
                        || !containsSheetText(historySheet, formatter, "편집 계획")) {
                    throw new InvalidWorkbookException("The final workbook's change-history sheet is empty or incomplete");
                }
            }
            if (resultsSheet.getSheetName().isBlank() || model.officialWorkbookCellsModified()
                    || model.officialMaterialBalanceUpdated()) throw new InvalidWorkbookException("Invalid export isolation status");
        }
        Map<String, byte[]> before = readParts(source), after = readParts(result);
        java.util.Set<String> allowedChanged = java.util.Set.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "[Content_Types].xml", "docProps/app.xml");
        if (!after.keySet().containsAll(before.keySet())) {
            throw new InvalidWorkbookException("An original OOXML package part disappeared during TEST_ONLY export");
        }
        validateStyleExtension(before.get("xl/styles.xml"), after.get("xl/styles.xml"));
        java.util.Set<String> added = new java.util.HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        if (!added.equals(java.util.Set.of(worksheetPart))) {
            throw new InvalidWorkbookException("Unexpected OOXML package additions: " + added);
        }
        for (String part : before.keySet()) {
            if (allowedChanged.contains(part)) continue;
            if (!java.util.Arrays.equals(before.get(part), after.get(part))) {
                throw new InvalidWorkbookException("Unintended OOXML package part changed during TEST_ONLY export: " + part);
            }
        }
        if (!after.containsKey(worksheetPart)) throw new InvalidWorkbookException("New result worksheet part is missing");
        var beforeInventory = packageInspector.inspect(source);
        var afterInventory = packageInspector.inspect(result);
        if (beforeInventory.shapeCount() != afterInventory.shapeCount()
                || beforeInventory.connectorCount() != afterInventory.connectorCount()
                || beforeInventory.pictureCount() != afterInventory.pictureCount()) {
            throw new InvalidWorkbookException("Existing PW-01-05 drawing inventory changed in TEST_ONLY export");
        }
    }

    private String nextWorksheetPart(Iterable<String> parts) {
        int max = 0;
        for (String part : parts) {
            Matcher matcher = SHEET_PART.matcher(part);
            if (matcher.matches()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return "xl/worksheets/sheet" + (max + 1) + ".xml";
    }

    private Map<String, byte[]> readParts(byte[] packageBytes) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (InputStream input = zip.getInputStream(entry)) { parts.put(entry.getName(), input.readAllBytes()); }
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read OOXML package entries", exception);
        }
        return parts;
    }

    private String nextRelationshipId(byte[] relationshipsXml) throws Exception {
        Document document = parse(relationshipsXml, "xl/_rels/workbook.xml.rels");
        int max = 0;
        for (Element relationship : directChildren(document.getDocumentElement(), PACKAGE_REL_NS, "Relationship")) {
            Matcher matcher = REL_ID.matcher(relationship.getAttribute("Id"));
            if (matcher.matches()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return "rId" + (max + 1);
    }

    private Document newDocument() throws Exception {
        DocumentBuilderFactory factory = secureFactory();
        return factory.newDocumentBuilder().newDocument();
    }

    private Document parse(byte[] xml, String name) throws Exception {
        return secureFactory().newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    private DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private byte[] serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private Element child(Document document, Element parent, String namespace, String name) {
        Element element = document.createElementNS(namespace, name);
        parent.appendChild(element);
        return element;
    }

    private Element firstDescendant(Element parent, String namespace, String name) {
        NodeList list = parent.getElementsByTagNameNS(namespace, name);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private Element firstDirectChild(Element parent, String namespace, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private int findWorksheetLastRow(byte[] worksheetXml) throws Exception {
        Element dimension = firstDescendant(parse(worksheetXml, "new TEST_ONLY worksheet").getDocumentElement(), MAIN_NS, "dimension");
        if (dimension == null) throw new InvalidWorkbookException("TEST_ONLY worksheet dimension is missing");
        Matcher matcher = Pattern.compile("A1:[A-Z]{1,3}([0-9]+)").matcher(dimension.getAttribute("ref"));
        if (!matcher.matches()) throw new InvalidWorkbookException("Unexpected TEST_ONLY worksheet dimension");
        return Integer.parseInt(matcher.group(1));
    }

    private List<Element> directChildren(Element parent, String namespace, String name) {
        List<Element> values = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) values.add(element);
        }
        return values;
    }

    private List<Map<String, Object>> mapRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((key, item) -> converted.put(String.valueOf(key), item));
                rows.add(converted);
            }
        }
        return rows;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT); }
    private String safe(String value) { return value == null ? "" : value; }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private java.math.BigDecimal decimalValue(Object value) {
        if (value instanceof java.math.BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString());
        return value == null ? null : new java.math.BigDecimal(value.toString());
    }
    private Object numberOrLabel(java.math.BigDecimal value) { return value == null ? "미입력" : value; }
    private String flowLabel(String flow) {
        return switch (safe(flow)) {
            case "WASTEWATER" -> "폐수";
            case "WASTE" -> "폐기물";
            default -> safe(flow);
        };
    }
    private String reconciliationLabel(String status) {
        return switch (safe(status)) {
            case "MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED" -> "같은 기록값(독립계측 아님)";
            case "INDEPENDENT_QUANTITIES_MATCH" -> "송수신 별도값 일치";
            case "NOT_RECONCILED_EXCLUDED" -> "대사 불가—edge 제외";
            default -> safe(status);
        };
    }
    private String roleLabel(String role) {
        return switch (safe(role)) {
            case "SOURCE" -> "원천";
            case "PROCESS" -> "일반 처리 설비";
            case "WASTEWATER_STORAGE" -> "폐수 저장시설";
            case "WASTE_STORAGE" -> "폐기물 저장시설";
            case "WASTE_SINK" -> "폐기물 수신(처분 미확정)";
            case "OUTLET", "FINAL_DISCHARGE" -> "최종 배출(경계 확인 필요)";
            case "FINAL_OFFSITE" -> "최종 반출(경계 확인 필요)";
            default -> safe(role);
        };
    }
    private String receiverLabel(MaterialBalanceExcelOutputModel.BalanceLine line) {
        return switch (safe(line.role())) {
            case "SOURCE" -> "해당 없음";
            case "PROCESS" -> "일반 처리 설비";
            case "WASTEWATER_STORAGE" -> "폐수 저장시설";
            case "WASTE_STORAGE" -> "폐기물 저장시설";
            case "WASTE_SINK" -> "폐기물 처분 미확정";
            case "OUTLET", "FINAL_DISCHARGE" -> "최종 배출 경계 미확정";
            case "FINAL_OFFSITE" -> "최종 반출 경계 미확정";
            default -> safe(line.receiverType());
        };
    }
    private String dispositionLabel(String status) {
        return switch (safe(status)) {
            case "UNCONFIRMED_STORAGE_OR_OFFSITE_DISPOSITION" -> "저장/반출 미확정";
            case "UNCONFIRMED_FINAL_BOUNDARY" -> "최종 경계 미확정";
            case "EXPLICIT_TERMINAL_BOUNDARY_APPLIED" -> "명시 경계 적용";
            case "ACCOUNTED_BY_MEASURED_STORAGE_CHANGE" -> "저장량 변화로 반영";
            case "NOT_A_RECEIVER_BOUNDARY" -> "해당 없음";
            default -> safe(status);
        };
    }
    private String balanceLabel(String status) {
        return switch (safe(status)) {
            case "CALCULATED" -> "계산 가능";
            case "RESIDUAL_UNRESOLVED" -> "잔차 미확정";
            case "CALCULATION_UNAVAILABLE" -> "계산 불가";
            default -> safe(status);
        };
    }
    private String mappingLabel(String status) {
        return switch (safe(status)) {
            case "UNMAPPED_NO_EXPLICIT_EQUIPMENT_ID" -> "실제 설비 매핑값 없음";
            case "UNVERIFIED_ID_NO_APPROVED_TEST_ONLY_CELL_MAPPING" -> "실제 설비 ID 미검증—셀 매핑 안 함";
            default -> safe(status);
        };
    }
    private String joinNode(String id, String label, Long equipmentId) {
        String node = safe(id) + " / " + safe(label);
        return equipmentId == null ? node + " / 설비 미매핑" : node + " / 설비ID(미검증) " + equipmentId;
    }
    private int columnNumber(String letters) {
        int value = 0;
        for (char letter : letters.toUpperCase(java.util.Locale.ROOT).toCharArray()) value = value * 26 + letter - 'A' + 1;
        return value;
    }
    private String columnName(int number) {
        StringBuilder name = new StringBuilder();
        while (number > 0) { int rem = (number - 1) % 26; name.insert(0, (char) ('A' + rem)); number = (number - 1) / 26; }
        return name.toString();
    }

    public record ExportArtifact(byte[] workbookBytes, MaterialBalanceExcelOutputModel outputModel, String worksheetPart) { }
}
