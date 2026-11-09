package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** TEST_ONLY cell-layout ADD that never shifts worksheet rows/columns or edits DrawingML. */
@Component
public class DiagramBoxAddEngine {
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private final DiagramPlacementPreflight preflight;
    private final OoxmlPackageInspector inspector;
    private final OoxmlPartEditor parts;
    private final DrawingMlShapeAddEngine shapeAddEngine;

    @Autowired
    public DiagramBoxAddEngine(DiagramPlacementPreflight preflight, OoxmlPackageInspector inspector,
                               OoxmlPartEditor parts, DrawingMlShapeAddEngine shapeAddEngine) {
        this.preflight = Objects.requireNonNull(preflight);
        this.inspector = Objects.requireNonNull(inspector);
        this.parts = Objects.requireNonNull(parts);
        this.shapeAddEngine = Objects.requireNonNull(shapeAddEngine);
    }

    public DiagramBoxAddEngine(DiagramPlacementPreflight preflight, OoxmlPackageInspector inspector,
                               OoxmlPartEditor parts) {
        this(preflight, inspector, parts, new DrawingMlShapeAddEngine(inspector, parts));
    }

    public AddResult addCellLayoutTestOnly(byte[] sourceBytes, DiagramPlacementPreflight.BoxAddRequest request,
                                           Map<String, String> fieldValues) {
        return addCellLayout(sourceBytes, request, fieldValues, false);
    }

    private AddResult addCellLayoutForShapeCompositionTestOnly(
            byte[] sourceBytes, DiagramPlacementPreflight.BoxAddRequest request, Map<String, String> fieldValues) {
        return addCellLayout(sourceBytes, request, fieldValues, true);
    }

    private AddResult addCellLayout(byte[] sourceBytes, DiagramPlacementPreflight.BoxAddRequest request,
                                    Map<String, String> fieldValues, boolean shapeCompositionTestOnly) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(request, "request");
        if (fieldValues == null || fieldValues.isEmpty()) throw blocked("FIELD_VALUES_MISSING");
        DiagramPlacementPreflight.BoxAddResult check = shapeCompositionTestOnly
                ? preflight.analyzeBoxAddForShapeCompositionTestOnly(sourceBytes, request)
                : preflight.analyzeBoxAdd(sourceBytes, request);
        if (!check.testOnlyReady()) throw blocked("BOX_ADD_NOT_TEST_ONLY_READY:" + check.status());
        EquipmentBoxProfile profile = request.templateProfile();
        Set<String> required = profile.fields().entrySet().stream().filter(entry -> entry.getValue().required())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (!fieldValues.keySet().equals(profile.fields().keySet()) || !fieldValues.keySet().containsAll(required))
            throw blocked("FIELD_SET_MUST_MATCH_PROFILE");
        if (!Objects.equals(fieldValues.get("managementNo"), request.equipmentId()))
            throw blocked("EQUIPMENT_ID_VALUE_MISMATCH");
        if (!Objects.equals(fieldValues.get("equipmentName"), request.equipmentName()))
            throw blocked("EQUIPMENT_NAME_VALUE_MISMATCH");
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) throw blocked("FIELD_VALUE_BLANK:" + entry.getKey());
        }

        CellRangeAddress template = CellRangeAddress.valueOf(request.templateRange().replace("$", ""));
        CellRangeAddress target = CellRangeAddress.valueOf(request.targetRange().replace("$", ""));
        WorkbookInventory beforeInventory = inspector.inspect(sourceBytes);
        WorkbookInventory.SheetInventory sourceSheetInventory = beforeInventory.sheets().stream()
                .filter(sheet -> request.sheetName().equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> blocked("TARGET_SHEET_MISSING"));
        String worksheetPart = sourceSheetInventory.worksheetPartName();
        if (worksheetPart == null) throw blocked("WORKSHEET_PART_UNKNOWN");
        byte[] worksheet = parts.readPart(sourceBytes, worksheetPart);
        byte[] changedWorksheet = patchWorksheet(worksheet, template, target, profile, fieldValues);
        byte[] outputBytes = parts.replacePackagePart(sourceBytes, worksheetPart, changedWorksheet);
        validateOutput(sourceBytes, outputBytes, request, fieldValues, beforeInventory, worksheetPart);
        Map<String, String> changedCells = new LinkedHashMap<>();
        for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
            int row = target.getFirstRow() + entry.getValue().rowOffset();
            int column = target.getFirstColumn() + entry.getValue().columnOffset();
            changedCells.put(new CellReference(row, column).formatAsString().replace("$", ""), entry.getKey());
        }
        return new AddResult(outputBytes, check.actualSourceSha256(), sha256(outputBytes), changedCells,
                check.status(), "REVIEW_REQUIRED", "CELL_LAYOUT_BOX_ADD_TEST_ONLY; no row/column movement, no new Shape or Connector, no physical connection or operational approval");
    }

    /**
     * Atomic in-memory TEST_ONLY composition: cell layout first, then one approved synthetic rectangle.
     * No intermediate bytes are returned if any later Shape validation fails.
     */
    public BoxAndShapeAddResult addCellLayoutAndShapeTestOnly(
            byte[] sourceBytes, DiagramPlacementPreflight.BoxAddRequest boxRequest,
            Map<String, String> fieldValues, DrawingMlShapeAddEngine.ShapeAddRequest shapeRequest) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(boxRequest, "boxRequest");
        Objects.requireNonNull(shapeRequest, "shapeRequest");
        String sourceSha = sha256(sourceBytes);
        AddResult cellResult = addCellLayoutForShapeCompositionTestOnly(sourceBytes, boxRequest, fieldValues);
        if (!sourceSha.equals(cellResult.sourceSha256())) throw blocked("COMPOSITE_SOURCE_SHA_CHANGED");
        if (boxRequest.mapping() == null || boxRequest.templateProfile() == null)
            throw blocked("COMPOSITE_MAPPING_OR_PROFILE_MISSING");
        if (!Objects.equals(shapeRequest.sourceWorkbookSha256(), sourceSha)
                || !Objects.equals(shapeRequest.processId(), boxRequest.processId())
                || !Objects.equals(shapeRequest.sourceFileIdentifier(), boxRequest.mapping().diagramId())
                || !Objects.equals(shapeRequest.sheetName(), boxRequest.sheetName())
                || !sameRange(shapeRequest.targetBoxRange(), boxRequest.targetRange()))
            throw blocked("COMPOSITE_SHAPE_REQUEST_IDENTITY_MISMATCH");
        DrawingMlShapeAddEngine.CellLayoutEvidence evidence = cellLayoutEvidence(
                cellResult.workbookBytes(), boxRequest, fieldValues, sourceSha);
        DrawingMlShapeAddEngine.ShapeAddResult shapeResult = shapeAddEngine.addShapeForCellLayoutTestOnly(
                cellResult.workbookBytes(), shapeRequest, evidence);
        if (!sourceSha.equals(sha256(sourceBytes))) throw blocked("COMPOSITE_SOURCE_MUTATED");
        return new BoxAndShapeAddResult(shapeResult.workbookBytes(), sourceSha, shapeResult.outputSha256(),
                boxRequest.processId(), boxRequest.sheetName(), boxRequest.targetRange(),
                cellResult.changedCells(), shapeResult.drawingPartName(), shapeResult.shapeId(), shapeResult.shapeName(),
                "CELL_LAYOUT_AND_DRAWINGML_SHAPE_ADD_TEST_ONLY", "REVIEW_REQUIRED",
                "Cell layout and one simple rectangle validated in memory; no Connector or physical connection was created, and no operational approval is implied.");
    }

    private DrawingMlShapeAddEngine.CellLayoutEvidence cellLayoutEvidence(
            byte[] cellLayoutBytes, DiagramPlacementPreflight.BoxAddRequest request,
            Map<String, String> fieldValues, String sourceSha) {
        CellRangeAddress target = CellRangeAddress.valueOf(request.targetRange().replace("$", ""));
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(cellLayoutBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            if (sheet == null) throw blocked("COMPOSITE_TARGET_SHEET_MISSING");
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            Map<String, String> expected = new LinkedHashMap<>();
            for (int row = target.getFirstRow(); row <= target.getLastRow(); row++) {
                for (int column = target.getFirstColumn(); column <= target.getLastColumn(); column++) {
                    var cell = sheet.getRow(row) == null ? null
                            : sheet.getRow(row).getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    expected.put(new CellReference(row, column).formatAsString().replace("$", ""),
                            cell == null ? "" : formatter.formatCellValue(cell));
                }
            }
            for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : request.templateProfile().fields().entrySet()) {
                var field = entry.getValue();
                String address = new CellReference(target.getFirstRow() + field.rowOffset(),
                        target.getFirstColumn() + field.columnOffset()).formatAsString().replace("$", "");
                if (!Objects.equals(fieldValues.get(entry.getKey()), expected.get(address)))
                    throw blocked("COMPOSITE_CELL_FIELD_EVIDENCE_MISMATCH:" + entry.getKey());
            }
            List<String> allMerges = sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList();
            List<String> targetMerges = sheet.getMergedRegions().stream().filter(region ->
                    region.getLastRow() >= target.getFirstRow() && region.getFirstRow() <= target.getLastRow()
                            && region.getLastColumn() >= target.getFirstColumn() && region.getFirstColumn() <= target.getLastColumn())
                    .map(CellRangeAddress::formatAsString).toList();
            return new DrawingMlShapeAddEngine.CellLayoutEvidence(sourceSha, sha256(cellLayoutBytes),
                    request.processId(), request.mapping().diagramId(), request.sheetName(), request.targetRange(),
                    request.templateProfile().profileId(), expected, targetMerges, allMerges);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to construct trusted TEST_ONLY cell-layout evidence", exception);
        }
    }

    private boolean sameRange(String left, String right) {
        try {
            return CellRangeAddress.valueOf(left.replace("$", "")).formatAsString()
                    .equalsIgnoreCase(CellRangeAddress.valueOf(right.replace("$", "")).formatAsString());
        } catch (RuntimeException exception) { return false; }
    }

    private byte[] patchWorksheet(byte[] worksheet, CellRangeAddress template, CellRangeAddress target,
                                  EquipmentBoxProfile profile, Map<String, String> fieldValues) {
        try {
            Document document = parse(worksheet);
            Element sheetData = firstDirectChild(document.getDocumentElement(), "sheetData");
            if (sheetData == null) throw blocked("SHEET_DATA_MISSING");
            Map<String, String> valuesAt = new HashMap<>();
            for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : profile.fields().entrySet()) {
                var field = entry.getValue();
                String address = new CellReference(target.getFirstRow() + field.rowOffset(),
                        target.getFirstColumn() + field.columnOffset()).formatAsString().replace("$", "");
                valuesAt.put(address, fieldValues.get(entry.getKey()));
            }
            Map<Integer, Element> rows = indexedRows(sheetData);
            for (int rowOffset = 0; rowOffset < template.getLastRow() - template.getFirstRow() + 1; rowOffset++) {
                int sourceRowIndex = template.getFirstRow() + rowOffset;
                int targetRowIndex = target.getFirstRow() + rowOffset;
                Element sourceRow = rows.get(sourceRowIndex + 1);
                Element targetRow = rows.get(targetRowIndex + 1);
                if (sourceRow == null || targetRow == null) throw blocked("SOURCE_OR_TARGET_ROW_XML_MISSING:" + (targetRowIndex + 1));
                Map<Integer, Element> sourceCells = indexedCells(sourceRow);
                Map<Integer, Element> targetCells = indexedCells(targetRow);
                for (int columnOffset = 0; columnOffset < template.getLastColumn() - template.getFirstColumn() + 1; columnOffset++) {
                    int sourceColumn = template.getFirstColumn() + columnOffset;
                    int targetColumn = target.getFirstColumn() + columnOffset;
                    Element sourceCell = sourceCells.get(sourceColumn);
                    Element targetCell = targetCells.get(targetColumn);
                    if (sourceCell == null || targetCell == null) throw blocked("SOURCE_OR_TARGET_CELL_XML_MISSING");
                    String style = sourceCell.getAttribute("s");
                    if (style.isBlank()) targetCell.removeAttribute("s");
                    else targetCell.setAttribute("s", style);
                    targetCell.removeAttribute("t");
                    removeElementChildren(targetCell);
                    String value = valuesAt.get(address(targetRowIndex, targetColumn));
                    if (value != null) {
                        targetCell.setAttribute("t", "inlineStr");
                        Element inline = document.createElementNS(MAIN_NS, "is");
                        Element text = document.createElementNS(MAIN_NS, "t");
                        text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
                        text.appendChild(document.createTextNode(value));
                        inline.appendChild(text);
                        targetCell.appendChild(inline);
                    }
                }
            }
            List<String> targetMerges = new ArrayList<>();
            for (String sourceMergeText : profile.mergedRanges()) {
                CellRangeAddress sourceMerge = CellRangeAddress.valueOf(sourceMergeText.replace("$", ""));
                if (sourceMerge.getFirstRow() < template.getFirstRow() || sourceMerge.getLastRow() > template.getLastRow()
                        || sourceMerge.getFirstColumn() < template.getFirstColumn() || sourceMerge.getLastColumn() > template.getLastColumn())
                    throw blocked("TEMPLATE_MERGE_CROSSES_BOX:" + sourceMergeText);
                CellRangeAddress translated = new CellRangeAddress(
                        target.getFirstRow() + sourceMerge.getFirstRow() - template.getFirstRow(),
                        target.getFirstRow() + sourceMerge.getLastRow() - template.getFirstRow(),
                        target.getFirstColumn() + sourceMerge.getFirstColumn() - template.getFirstColumn(),
                        target.getFirstColumn() + sourceMerge.getLastColumn() - template.getFirstColumn());
                targetMerges.add(translated.formatAsString());
            }
            addMerges(document, targetMerges);
            return serialize(document);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to patch TEST_ONLY cell-layout worksheet", exception);
        }
    }

    private void addMerges(Document document, List<String> mergeRanges) {
        if (mergeRanges.isEmpty()) return;
        Element root = document.getDocumentElement();
        Element mergeCells = firstDirectChild(root, "mergeCells");
        if (mergeCells == null) {
            Element sheetData = firstDirectChild(root, "sheetData");
            if (sheetData == null) throw blocked("SHEET_DATA_MISSING");
            mergeCells = document.createElementNS(MAIN_NS, "mergeCells");
            Node insertionPoint = sheetData.getNextSibling();
            while (insertionPoint != null && insertionPoint.getNodeType() != Node.ELEMENT_NODE)
                insertionPoint = insertionPoint.getNextSibling();
            if (insertionPoint == null) root.appendChild(mergeCells);
            else root.insertBefore(mergeCells, insertionPoint);
        }
        Set<String> existing = new LinkedHashSet<>();
        NodeList children = mergeCells.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && "mergeCell".equals(element.getLocalName()))
                existing.add(element.getAttribute("ref"));
        }
        for (String reference : mergeRanges) {
            if (!existing.add(reference)) throw blocked("TARGET_MERGE_ALREADY_EXISTS:" + reference);
            Element merge = document.createElementNS(MAIN_NS, "mergeCell");
            merge.setAttribute("ref", reference);
            mergeCells.appendChild(merge);
        }
        mergeCells.setAttribute("count", Integer.toString(existing.size()));
    }

    private void validateOutput(byte[] before, byte[] after, DiagramPlacementPreflight.BoxAddRequest request,
                                Map<String, String> fieldValues, WorkbookInventory beforeInventory, String worksheetPart) {
        try (Workbook original = WorkbookFactory.create(new ByteArrayInputStream(before));
             Workbook result = WorkbookFactory.create(new ByteArrayInputStream(after))) {
            var oldSheet = original.getSheet(request.sheetName());
            var newSheet = result.getSheet(request.sheetName());
            if (oldSheet.getLastRowNum() != newSheet.getLastRowNum()) throw blocked("ROW_DIMENSION_CHANGED");
            if (oldSheet.getPhysicalNumberOfRows() != newSheet.getPhysicalNumberOfRows()) throw blocked("PHYSICAL_ROW_COUNT_CHANGED");
            int rows = Math.max(oldSheet.getLastRowNum(), newSheet.getLastRowNum());
            int maximumColumn = 0;
            for (Row row : oldSheet) maximumColumn = Math.max(maximumColumn, row.getLastCellNum());
            for (Row row : newSheet) maximumColumn = Math.max(maximumColumn, row.getLastCellNum());
            DataFormatter formatter = new DataFormatter();
            CellRangeAddress target = CellRangeAddress.valueOf(request.targetRange().replace("$", ""));
            CellRangeAddress template = CellRangeAddress.valueOf(request.templateRange().replace("$", ""));
            Map<String, String> expectedAt = new HashMap<>();
            for (Map.Entry<String, EquipmentBoxProfile.FieldCell> entry : request.templateProfile().fields().entrySet()) {
                var field = entry.getValue();
                expectedAt.put(address(target.getFirstRow() + field.rowOffset(), target.getFirstColumn() + field.columnOffset()),
                        fieldValues.get(entry.getKey()));
            }
            for (int rowIndex = 0; rowIndex <= rows; rowIndex++) {
                Row oldRow = oldSheet.getRow(rowIndex), newRow = newSheet.getRow(rowIndex);
                if ((oldRow == null) != (newRow == null)) throw blocked("ROW_PRESENCE_CHANGED:" + (rowIndex + 1));
                if (oldRow == null) continue;
                if (oldRow.getHeight() != newRow.getHeight() || oldRow.getZeroHeight() != newRow.getZeroHeight())
                    throw blocked("ROW_GEOMETRY_CHANGED:" + (rowIndex + 1));
                for (int column = 0; column < maximumColumn; column++) {
                    Cell oldCell = oldRow.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    Cell newCell = newRow.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    String address = address(rowIndex, column);
                    if (rowIndex >= target.getFirstRow() && rowIndex <= target.getLastRow()
                            && column >= target.getFirstColumn() && column <= target.getLastColumn()) {
                        int sourceRowIndex = template.getFirstRow() + rowIndex - target.getFirstRow();
                        int sourceColumn = template.getFirstColumn() + column - target.getFirstColumn();
                        Row templateRow = oldSheet.getRow(sourceRowIndex);
                        Cell templateCell = templateRow == null ? null
                                : templateRow.getCell(sourceColumn, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                        if (newCell == null || templateCell == null
                                || newCell.getCellStyle().getIndex() != templateCell.getCellStyle().getIndex())
                            throw blocked("TARGET_STYLE_NOT_CLONED:" + address);
                        if (expectedAt.containsKey(address)
                                && !expectedAt.get(address).equals(formatter.formatCellValue(newCell)))
                            throw blocked("OUTPUT_VALUE_MISMATCH:" + address);
                        continue;
                    }
                    String oldValue = oldCell == null ? "" : formatter.formatCellValue(oldCell);
                    String newValue = newCell == null ? "" : formatter.formatCellValue(newCell);
                    if (!oldValue.equals(newValue)) throw blocked("NON_TARGET_VALUE_CHANGED:" + address);
                    if (oldCell != null && newCell != null && oldCell.getCellStyle().getIndex() != newCell.getCellStyle().getIndex())
                        throw blocked("NON_TARGET_STYLE_CHANGED:" + address);
                }
            }
            for (int column = 0; column < maximumColumn; column++) {
                if (oldSheet.getColumnWidth(column) != newSheet.getColumnWidth(column)
                        || oldSheet.isColumnHidden(column) != newSheet.isColumnHidden(column))
                    throw blocked("COLUMN_GEOMETRY_CHANGED:" + column);
            }
            List<String> oldMerges = oldSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList();
            List<String> newMerges = newSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList();
            List<String> expectedMerges = new ArrayList<>(oldMerges);
            for (String sourceMergeText : request.templateProfile().mergedRanges()) {
                CellRangeAddress sourceMerge = CellRangeAddress.valueOf(sourceMergeText.replace("$", ""));
                CellRangeAddress translated = new CellRangeAddress(
                        target.getFirstRow() + sourceMerge.getFirstRow() - CellRangeAddress.valueOf(request.templateRange()).getFirstRow(),
                        target.getFirstRow() + sourceMerge.getLastRow() - CellRangeAddress.valueOf(request.templateRange()).getFirstRow(),
                        target.getFirstColumn() + sourceMerge.getFirstColumn() - CellRangeAddress.valueOf(request.templateRange()).getFirstColumn(),
                        target.getFirstColumn() + sourceMerge.getLastColumn() - CellRangeAddress.valueOf(request.templateRange()).getFirstColumn());
                expectedMerges.add(translated.formatAsString());
            }
            if (!newMerges.equals(expectedMerges)) throw blocked("MERGED_RANGE_OUTPUT_MISMATCH");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("TEST_ONLY box ADD failed POI reopen validation", exception);
        }
        WorkbookInventory afterInventory = inspector.inspect(after);
        if (beforeInventory.sheets().size() != afterInventory.sheets().size()
                || beforeInventory.shapeCount() != afterInventory.shapeCount()
                || beforeInventory.connectorCount() != afterInventory.connectorCount()
                || beforeInventory.pictureCount() != afterInventory.pictureCount())
            throw blocked("DRAWINGML_OR_WORKBOOK_INVENTORY_CHANGED");
        for (int index = 0; index < beforeInventory.sheets().size(); index++) {
            var oldSheet = beforeInventory.sheets().get(index);
            var newSheet = afterInventory.sheets().get(index);
            if (!oldSheet.sheetName().equals(newSheet.sheetName())
                    || !Objects.equals(oldSheet.worksheetPartName(), newSheet.worksheetPartName())
                    || !Objects.equals(oldSheet.drawingPartName(), newSheet.drawingPartName())
                    || !oldSheet.drawingObjects().equals(newSheet.drawingObjects()))
                throw blocked("DRAWINGML_OR_SHEET_IDENTITY_CHANGED:" + oldSheet.sheetName());
        }
        byte[] oldDrawing = beforeInventory.sheets().stream().filter(sheet -> request.sheetName().equals(sheet.sheetName()))
                .findFirst().map(sheet -> sheet.drawingPartName() == null ? null : parts.readPart(before, sheet.drawingPartName())).orElse(null);
        byte[] newDrawing = afterInventory.sheets().stream().filter(sheet -> request.sheetName().equals(sheet.sheetName()))
                .findFirst().map(sheet -> sheet.drawingPartName() == null ? null : parts.readPart(after, sheet.drawingPartName())).orElse(null);
        if (!java.util.Arrays.equals(oldDrawing, newDrawing)) throw blocked("DRAWINGML_PART_CHANGED");
        if (oldDrawing != null && !beforeInventory.sheets().stream().filter(sheet -> request.sheetName().equals(sheet.sheetName()))
                .findFirst().orElseThrow().drawingObjects().equals(afterInventory.sheets().stream()
                        .filter(sheet -> request.sheetName().equals(sheet.sheetName())).findFirst().orElseThrow().drawingObjects()))
            throw blocked("DRAWINGML_OBJECT_INVENTORY_CHANGED");
        assertNonWorksheetPackagePartsIdentical(before, after, worksheetPart);
    }

    private void assertNonWorksheetPackagePartsIdentical(byte[] before, byte[] after, String worksheetPart) {
        try (var left = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                    .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(before)).get();
             var right = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                    .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(after)).get()) {
            var entries = left.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (worksheetPart.equals(entry.getName())) continue;
                var other = right.getEntry(entry.getName());
                if (other == null) throw blocked("OOXML_PART_MISSING:" + entry.getName());
                try (var originalBytes = left.getInputStream(entry); var outputBytes = right.getInputStream(other)) {
                    if (!java.util.Arrays.equals(originalBytes.readAllBytes(), outputBytes.readAllBytes()))
                        throw blocked("NON_WORKSHEET_PART_CHANGED:" + entry.getName());
                }
            }
            var afterEntries = right.getEntries();
            while (afterEntries.hasMoreElements()) {
                var entry = afterEntries.nextElement();
                if (left.getEntry(entry.getName()) == null) throw blocked("OOXML_PART_ADDED:" + entry.getName());
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to compare OOXML package parts", exception);
        }
    }

    private Map<Integer, Element> indexedRows(Element sheetData) {
        Map<Integer, Element> rows = new HashMap<>();
        NodeList nodes = sheetData.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && "row".equals(element.getLocalName())) {
                int rowNumber = Integer.parseInt(element.getAttribute("r"));
                rows.put(rowNumber, element);
            }
        }
        return rows;
    }

    private Map<Integer, Element> indexedCells(Element row) {
        Map<Integer, Element> cells = new HashMap<>();
        NodeList nodes = row.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && "c".equals(element.getLocalName())) {
                CellReference reference = new CellReference(element.getAttribute("r"));
                cells.put((int) reference.getCol(), element);
            }
        }
        return cells;
    }

    private Element firstDirectChild(Element parent, String localName) {
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private boolean hasElementChild(Element element) {
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++)
            if (nodes.item(index) instanceof Element) return true;
        return false;
    }

    private void removeElementChildren(Element element) {
        Node node = element.getFirstChild();
        while (node != null) {
            Node next = node.getNextSibling();
            if (node instanceof Element) element.removeChild(node);
            node = next;
        }
    }

    private Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Malformed worksheet XML", exception);
        }
    }

    private byte[] serialize(Document document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to serialize worksheet XML", exception);
        }
    }

    private String address(int row, int column) {
        return new CellReference(row, column).formatAsString().replace("$", "");
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private InvalidWorkbookException blocked(String reason) { return new InvalidWorkbookException(reason); }

    public record AddResult(byte[] workbookBytes, String sourceSha256, String outputSha256,
                            Map<String, String> changedCells, String layoutStatus, String relationStatus,
                            String note) {
        public AddResult { workbookBytes = workbookBytes.clone(); changedCells = Map.copyOf(changedCells); }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }

    public record BoxAndShapeAddResult(byte[] workbookBytes, String sourceSha256, String outputSha256,
                                       String processId, String sheetName, String targetRange,
                                       Map<String, String> changedCells, String drawingPartName,
                                       String shapeId, String shapeName, String status,
                                       String relationStatus, String note) {
        public BoxAndShapeAddResult {
            workbookBytes = workbookBytes.clone();
            changedCells = Map.copyOf(changedCells);
        }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }
}
