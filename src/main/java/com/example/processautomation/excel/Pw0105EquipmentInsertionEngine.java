package com.example.processautomation.excel;

import com.example.processautomation.model.MasterEquipment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Locates the PW-01-05 equipment cells and creates an isolated TEST_ONLY edit. */
@Component
public class Pw0105EquipmentInsertionEngine {
    private static final String SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String XDR_NS = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final int AUXILIARY_COLUMN_OFFSET = 13;
    private static final Pattern CELL_REF = Pattern.compile("(\\$?[A-Z]{1,3})(\\$?)([1-9][0-9]*)");
    private static final Pattern PRINT_AREA_REF = Pattern.compile(".*!\\$?[A-Z]{1,3}\\$?[0-9]+:\\$?([A-Z]{1,3})\\$?[0-9]+.*");

    private final OoxmlPartEditor partEditor;
    private final OoxmlPackageInspector packageInspector;

    public Pw0105EquipmentInsertionEngine(OoxmlPartEditor partEditor, OoxmlPackageInspector packageInspector) {
        this.partEditor = partEditor;
        this.packageInspector = packageInspector;
    }

    public Plan analyze(byte[] workbookBytes, TestEditSpec specification) {
        Objects.requireNonNull(workbookBytes, "workbookBytes");
        Objects.requireNonNull(specification, "specification");
        String sheetName = specification.sheetName();
        if (sheetName == null || sheetName.isBlank()) throw new InvalidWorkbookException("A target worksheet name is required");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new InvalidWorkbookException("Target worksheet is missing: " + sheetName);
            EquipmentBox source = locateBox(sheet, specification.sourceId(), specification.sourceName(), sheetName);
            EquipmentBox target = locateBox(sheet, specification.targetId(), specification.targetName(), sheetName);
            if (source.firstColumn() != target.firstColumn() || source.lastColumn() != target.lastColumn()) {
                throw new InvalidWorkbookException("The source and target equipment boxes do not share a common diagram column");
            }
            int available = target.firstRow() - source.lastRow() - 1;
            if (available < 0) throw new InvalidWorkbookException("Equipment boxes overlap or are out of order");
            int boxHeight = source.lastRow() - source.firstRow() + 1;
            if (boxHeight < 1 || target.lastRow() < target.firstRow()) {
                throw new InvalidWorkbookException("Source or target equipment box dimensions are invalid");
            }
            int insertedRows = boxHeight + 3;
            int insertionBeforeRow = target.firstRow();
            var sheetInventory = packageInspector.inspect(workbookBytes).sheets().stream()
                    .filter(item -> sheetName.equals(item.sheetName())).findFirst()
                    .orElseThrow(() -> new InvalidWorkbookException("Target worksheet inventory is missing: " + sheetName));
            if (sheetInventory.drawingPartName() == null) throw new InvalidWorkbookException("Target worksheet drawing part is missing");
            ConnectorEvidence connector = findConnectingConnector(workbookBytes, sheetInventory.drawingPartName(), source, target);
            return new Plan(sheetName, sheetInventory.sheetIndex(), specification, source, target, connector,
                    insertionBeforeRow, insertionBeforeRow, insertionBeforeRow + boxHeight - 1,
                    boxHeight, insertedRows, available, source.firstColumn(), source.lastColumn(), false, "TEST_ONLY");
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to analyze worksheet " + sheetName + " layout", exception);
        }
    }

    public EditResult insertTestOnly(byte[] sourceBytes, MasterEquipment equipment, TestEditSpec specification) {
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(specification, "specification");
        if (equipment.permitManagementNo() == null || equipment.permitManagementNo().isBlank()
                || equipment.facilityName() == null || equipment.facilityName().isBlank()) {
            throw new InvalidWorkbookException("TEST_ONLY equipment requires a permit ID and facility name");
        }
        Plan plan = analyze(sourceBytes, specification);
        if (plan.existingConnector().shapeId() == null) {
            throw new InvalidWorkbookException("No existing visual connector joins the declared TEST_ONLY endpoint boxes; no safe split point exists");
        }
        byte[] worksheet = patchWorksheet(sourceBytes, plan, equipment);
        byte[] drawing = patchDrawing(sourceBytes, plan, equipment);
        byte[] workbook = patchWorkbook(sourceBytes, plan);
        var inventory = packageInspector.inspect(sourceBytes);
        String worksheetPart = inventory.sheets().stream().filter(item -> plan.sheetName().equals(item.sheetName()))
                .findFirst().orElseThrow().worksheetPartName();
        String drawingPart = inventory.sheets().stream().filter(item -> plan.sheetName().equals(item.sheetName()))
                .findFirst().orElseThrow().drawingPartName();
        byte[] result = partEditor.replacePackagePart(sourceBytes, worksheetPart, worksheet);
        result = partEditor.replacePackagePart(result, drawingPart, drawing);
        result = partEditor.replacePackagePart(result, "xl/workbook.xml", workbook);
        validateOutput(sourceBytes, result, equipment, plan);
        return new EditResult(result, plan, "TEST_ONLY", "REVIEW_REQUIRED");
    }

    public ModifyResult modifyExistingEquipmentText(byte[] sourceBytes, String sheetName, String managementNo,
            String facilityName, Map<String, String> expectedValues, Map<String, String> replacements) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(expectedValues, "expectedValues");
        Objects.requireNonNull(replacements, "replacements");
        if (replacements.isEmpty() || replacements.keySet().stream()
                .anyMatch(field -> !Set.of("managementNo", "equipmentName", "businessItemNo", "capacity", "quantity").contains(field))) {
            throw new InvalidWorkbookException("Only the uniquely mapped management number, name, item, capacity, and quantity cells may be modified");
        }
        EquipmentBox box;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new InvalidWorkbookException("Target worksheet is missing: " + sheetName);
            List<EquipmentBox> candidates = locateExistingBoxesByManagementNo(sheet, managementNo);
            if (candidates.size() != 1) {
                throw new InvalidWorkbookException("Management number does not resolve to exactly one styled equipment box: " + managementNo);
            }
            box = candidates.get(0);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to resolve the existing equipment box", exception);
        }
        Map<String, String> fieldCells = Map.of(
                "managementNo", box.topLeftCell(),
                "equipmentName", cellOffset(box.topLeftCell(), 3),
                "businessItemNo", cellOffset(box.topLeftCell(), 4),
                "capacity", cellOffset(box.topLeftCell(), 5),
                "quantity", cellOffset(box.topLeftCell(), 6));
        Map<String, String> before = new LinkedHashMap<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            for (String field : fieldCells.keySet()) {
                var ref = new CellReference(fieldCells.get(field));
                var row = sheet.getRow(ref.getRow());
                Cell cell = row == null ? null : row.getCell(ref.getCol(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) {
                    if (replacements.containsKey(field)) {
                        throw new InvalidWorkbookException("Selected target field is missing: " + fieldCells.get(field));
                    }
                    before.put(field, "");
                    continue;
                }
                if (replacements.containsKey(field) && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                    throw new InvalidWorkbookException("Selected target field is formula-driven: " + fieldCells.get(field));
                }
                String current = formatter.formatCellValue(cell);
                if (replacements.containsKey(field) && !Objects.equals(current, expectedValues.get(field))) {
                    throw new InvalidWorkbookException("Selected target changed after preview at " + fieldCells.get(field));
                }
                before.put(field, current);
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to re-read the uniquely mapped equipment fields", exception);
        }
        byte[] output = sourceBytes;
        Map<String, String> changedCells = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String field = entry.getKey();
            String replacement = entry.getValue();
            if (replacement == null || replacement.isBlank()) {
                throw new InvalidWorkbookException("Replacement values must not be blank: " + field);
            }
            String current = before.get(field);
            if (current.equals(replacement)) continue;
            String address = fieldCells.get(field);
            output = partEditor.replaceCellText(output, sheetName, address, current, replacement);
            changedCells.put(address, field);
        }
        if (changedCells.isEmpty()) throw new InvalidWorkbookException("No equipment field changed from its current value");
        var originalSheet = packageInspector.inspect(sourceBytes).sheets().stream()
                .filter(item -> sheetName.equals(item.sheetName())).findFirst().orElseThrow();
        var outputSheet = packageInspector.inspect(output).sheets().stream()
                .filter(item -> sheetName.equals(item.sheetName())).findFirst().orElseThrow();
        if (!Objects.equals(originalSheet.drawingPartName(), outputSheet.drawingPartName())
                || !originalSheet.drawingObjects().equals(outputSheet.drawingObjects())) {
            throw new InvalidWorkbookException("MODIFY must preserve all DrawingML objects, anchors, arrows, and bindings");
        }
        byte[] drawingBefore = partEditor.readPart(sourceBytes, originalSheet.drawingPartName());
        byte[] drawingAfter = partEditor.readPart(output, outputSheet.drawingPartName());
        if (!java.util.Arrays.equals(drawingBefore, drawingAfter)) {
            throw new InvalidWorkbookException("MODIFY changed the DrawingML package part");
        }
        Map<String, String> after = new LinkedHashMap<>(before);
        replacements.forEach((field, value) -> { if (changedCells.containsKey(fieldCells.get(field))) after.put(field, value); });
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            for (String field : replacements.keySet()) {
                var ref = new CellReference(fieldCells.get(field));
                String actual = formatter.formatCellValue(sheet.getRow(ref.getRow()).getCell(ref.getCol()));
                if (!Objects.equals(actual, after.get(field))) {
                    throw new InvalidWorkbookException("Reopened MODIFY output differs at " + fieldCells.get(field));
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Modified workbook cannot be reopened", exception);
        }
        return new ModifyResult(output, box, Map.copyOf(before), Map.copyOf(after),
                Map.copyOf(changedCells), originalSheet.worksheetPartName(), originalSheet.drawingPartName());
    }

    public EquipmentBox locateExistingBox(byte[] workbookBytes, String sheetName, String managementNo,
                                          String facilityName) {
        Objects.requireNonNull(workbookBytes, "workbookBytes");
        if (sheetName == null || sheetName.isBlank()) throw new InvalidWorkbookException("A target worksheet name is required");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new InvalidWorkbookException("Target worksheet is missing: " + sheetName);
            return locateExistingBox(sheet, sheetName, managementNo, facilityName);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to locate existing equipment in " + sheetName, exception);
        }
    }

    public EquipmentBox locateExistingBox(org.apache.poi.ss.usermodel.Sheet sheet, String sheetName,
                                          String managementNo, String facilityName) {
        Objects.requireNonNull(sheet, "sheet");
        if (sheetName == null || sheetName.isBlank()) throw new InvalidWorkbookException("A target worksheet name is required");
        return locateBox(sheet, managementNo, facilityName, sheetName);
    }

    /** Locate boxed equipment records from the exact management-number cell, independent of master text values. */
    public List<EquipmentBox> locateExistingBoxesByManagementNo(org.apache.poi.ss.usermodel.Sheet sheet,
            String managementNo) {
        Objects.requireNonNull(sheet, "sheet");
        DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
        List<EquipmentBox> boxes = new ArrayList<>();
        for (Row row : sheet) for (Cell idCell : row) {
            if (!managementNo.equals(formatter.formatCellValue(idCell).trim())) continue;
            int right = findBoxRight(sheet, idCell);
            int bottom = findBoxBottom(sheet, idCell);
            int width = right - idCell.getColumnIndex();
            int height = bottom - idCell.getRowIndex();
            if (width < 5 || width > 20 || height < 6 || height > 30) continue;
            int populatedDetailRows = 0;
            for (int offset = 2; offset <= 6; offset++) {
                Row detailRow = sheet.getRow(idCell.getRowIndex() + offset);
                Cell detail = detailRow == null ? null
                        : detailRow.getCell(idCell.getColumnIndex(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (detail != null && !formatter.formatCellValue(detail).isBlank()) populatedDetailRows++;
            }
            if (populatedDetailRows < 2) continue;
            boxes.add(new EquipmentBox(managementNo, "", idCell.getColumnIndex(), idCell.getRowIndex() + 1,
                    right, bottom + 1, idCell.getAddress().formatAsString(), cellAddress(right, bottom),
                    idCell.getRowIndex() + 4));
        }
        return List.copyOf(boxes);
    }

    private EquipmentBox locateBox(org.apache.poi.ss.usermodel.Sheet sheet, String managementNo, String facilityName,
                                   String sheetName) {
        DataFormatter formatter = new DataFormatter();
        List<Cell> matches = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (formatter.formatCellValue(cell).trim().equals(managementNo)) matches.add(cell);
            }
        }
        List<EquipmentBox> boxes = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (Cell idCell : matches) {
            int nameRow = findTextInColumn(sheet, idCell.getColumnIndex(), idCell.getRowIndex() + 1,
                    idCell.getRowIndex() + 8, facilityName, formatter);
            diagnostics.add(idCell.getAddress().formatAsString() + " nameRow=" + nameRow);
            if (nameRow < 0) continue;
            int right = findBoxRight(sheet, idCell);
            int bottom = findBoxBottom(sheet, idCell);
            diagnostics.add("boxEdge=" + right + "," + bottom);
            if (right < idCell.getColumnIndex() || bottom < idCell.getRowIndex()) continue;
            boxes.add(new EquipmentBox(managementNo, facilityName, idCell.getColumnIndex(), idCell.getRowIndex() + 1,
                    right, bottom + 1, cellAddress(idCell.getColumnIndex(), idCell.getRowIndex()),
                    cellAddress(right, bottom), nameRow + 1));
        }
        if (boxes.size() != 1) {
            throw new InvalidWorkbookException("Could not uniquely locate equipment " + managementNo + " / "
                    + facilityName + " in " + sheetName + "; matching styled box candidates=" + boxes.size()
                + "; diagnostics=" + diagnostics);
        }
        return boxes.get(0);
    }

    private int findTextInColumn(org.apache.poi.ss.usermodel.Sheet sheet, int column, int from, int to,
                                 String expected, DataFormatter formatter) {
        for (int index = from; index <= to; index++) {
            Row row = sheet.getRow(index);
            Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && formatter.formatCellValue(cell).trim().equals(expected)) return index;
        }
        return -1;
    }

    private byte[] patchWorksheet(byte[] sourceBytes, Plan plan, MasterEquipment equipment) {
        var sheetInventory = packageInspector.inspect(sourceBytes).sheets().stream()
                .filter(item -> plan.sheetName().equals(item.sheetName())).findFirst().orElseThrow();
        String part = sheetInventory.worksheetPartName();
        Element root = parseXml(partEditor.readPart(sourceBytes, part), part);
        if (root.getElementsByTagNameNS(SPREADSHEET_NS, "f").getLength() > 0) {
            throw new InvalidWorkbookException("Refusing row insertion on a worksheet containing formulas");
        }
        Element sheetData = firstDescendant(root, SPREADSHEET_NS, "sheetData");
        if (sheetData == null) throw new InvalidWorkbookException("PW-01-05 sheetData is missing");
        List<Element> templates = new ArrayList<>();
        for (int row = plan.source().firstRow(); row <= plan.source().lastRow(); row++) {
            Element template = findRow(sheetData, row);
            if (template == null) throw new InvalidWorkbookException("Missing source equipment style row " + row);
            templates.add(template);
        }
        Element gapTemplate = findRow(sheetData, plan.source().lastRow() + 1);
        if (gapTemplate == null) throw new InvalidWorkbookException("Missing blank row template below source box");
        for (Element row : directChildren(sheetData, SPREADSHEET_NS, "row")) {
            int oldRow = Integer.parseInt(row.getAttribute("r"));
            if (oldRow >= plan.insertionBeforeRow()) shiftRow(row, plan.insertionBeforeRow(), plan.insertedRows());
        }
        shiftWorksheetRanges(root, plan.insertionBeforeRow(), plan.insertedRows());
        List<Element> inserted = new ArrayList<>();
        for (int offset = 0; offset < plan.insertedRows(); offset++) {
            Element sourceRow = offset < plan.boxHeight() ? templates.get(offset) : gapTemplate;
            Element clone = (Element) root.getOwnerDocument().importNode(sourceRow, true);
            int rowNumber = plan.candidateFirstRow() + offset;
            keepOnlyBoxCells(clone, plan.firstColumn(), plan.lastColumn());
            clearRowValues(clone, 0, 16383);
            setRowNumber(clone, rowNumber);
            if (offset < plan.boxHeight()) {
                setBoxValue(clone, rowNumber, plan.firstColumn(), offset, equipment,
                        plan.specification().equipmentTypeLabel());
            }
            if (offset < plan.boxHeight() && offset != 2 && offset != 8) {
                // Keep the existing compartment placeholder blank; do not add a TEST_ONLY status callout.
                setTextAtColumn(clone, plan.firstColumn() + AUXILIARY_COLUMN_OFFSET, rowNumber, " ");
            }
            inserted.add(clone);
        }
        for (Element row : inserted) sheetData.appendChild(row);

        Element sheetView = firstDescendant(root, SPREADSHEET_NS, "sheetView");
        if (sheetView != null) {
            sheetView.setAttribute("topLeftCell", cellAddress(plan.firstColumn(), plan.candidateFirstRow() - 1));
            sheetView.setAttribute("zoomScale", "90");
            sheetView.setAttribute("tabSelected", "1");
            Element selection = firstChild(sheetView, SPREADSHEET_NS, "selection");
            if (selection != null) {
                String focus = cellAddress(plan.firstColumn(), plan.candidateFirstRow() - 1);
                selection.setAttribute("activeCell", focus);
                selection.setAttribute("sqref", focus);
            }
        }
        sortRows(sheetData);
        return serialize(root.getOwnerDocument());
    }

    private void clearRowValues(Element row, int firstColumn, int lastColumn) {
        List<Element> cells = directChildren(row, SPREADSHEET_NS, "c");
        for (Element cell : cells) {
            int column = columnIndex(cell.getAttribute("r"));
            if (column < firstColumn || column > lastColumn) continue;
            while (cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
            cell.removeAttribute("t");
            cell.removeAttribute("cm");
            cell.removeAttribute("vm");
        }
    }

    private void setBoxValue(Element row, int rowNumber, int firstColumn, int offset, MasterEquipment equipment,
                             String equipmentTypeLabel) {
        String value = switch (offset) {
            case 0 -> equipment.permitManagementNo();
            case 2 -> equipmentTypeLabel;
            case 3 -> equipment.facilityName();
            case 4 -> equipment.businessItemNo();
            case 5 -> capacityText(equipment);
            case 6 -> equipment.quantityText();
            default -> null;
        };
        if (value != null && !value.isBlank()) setTextAtColumn(row, firstColumn, rowNumber, value);
        if (offset == 2) setTextAtColumn(row, firstColumn + AUXILIARY_COLUMN_OFFSET, rowNumber, "폐수");
        if (offset == 8) setTextAtColumn(row, firstColumn + AUXILIARY_COLUMN_OFFSET, rowNumber, "폐기물");
    }

    private String capacityText(MasterEquipment equipment) {
        String capacity = equipment.capacityText() == null ? "" : equipment.capacityText().trim();
        String unit = equipment.capacityUnit() == null ? "" : equipment.capacityUnit().trim();
        if (unit.isBlank() || capacity.contains(unit)) return capacity;
        return capacity + unit;
    }

    private void setTextAtColumn(Element row, int column, int rowNumber, String value) {
        String address = cellAddress(column, rowNumber - 1);
        Element cell = null;
        for (Element candidate : directChildren(row, SPREADSHEET_NS, "c")) {
            if (address.equals(candidate.getAttribute("r"))) { cell = candidate; break; }
        }
        if (cell == null) throw new InvalidWorkbookException("Template does not contain styled cell " + address);
        while (cell.hasChildNodes()) cell.removeChild(cell.getFirstChild());
        cell.setAttribute("t", "inlineStr");
        Document document = cell.getOwnerDocument();
        String prefix = cell.getPrefix() == null ? "" : cell.getPrefix() + ":";
        Element inline = document.createElementNS(SPREADSHEET_NS, prefix + "is");
        Element text = document.createElementNS(SPREADSHEET_NS, prefix + "t");
        text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
        text.setTextContent(value);
        inline.appendChild(text);
        cell.appendChild(inline);
    }

    private Element findRow(Element sheetData, int rowNumber) {
        for (Element row : directChildren(sheetData, SPREADSHEET_NS, "row")) {
            if (Integer.parseInt(row.getAttribute("r")) == rowNumber) return row;
        }
        return null;
    }

    private void shiftRow(Element row, int beforeRow, int delta) {
        int old = Integer.parseInt(row.getAttribute("r"));
        row.setAttribute("r", Integer.toString(old + delta));
        for (Element cell : directChildren(row, SPREADSHEET_NS, "c")) {
            cell.setAttribute("r", shiftCellReference(cell.getAttribute("r"), beforeRow, delta));
        }
    }

    private void shiftWorksheetRanges(Element root, int beforeRow, int delta) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element element)) continue;
            for (String attribute : List.of("ref", "sqref")) {
                if (!element.hasAttribute(attribute)) continue;
                String original = element.getAttribute(attribute);
                String[] ranges = original.split("\\s+");
                List<String> adjusted = new ArrayList<>();
                for (String range : ranges) adjusted.add(shiftRange(range, beforeRow, delta));
                element.setAttribute(attribute, String.join(" ", adjusted));
            }
            if ("brk".equals(element.getLocalName()) && element.hasAttribute("id")) {
                int breakRow = Integer.parseInt(element.getAttribute("id"));
                if (breakRow >= beforeRow) element.setAttribute("id", Integer.toString(breakRow + delta));
            }
        }
    }

    private String shiftRange(String range, int beforeRow, int delta) {
        String[] endpoints = range.split(":", -1);
        if (endpoints.length > 2) throw new InvalidWorkbookException("Unsupported worksheet range reference: " + range);
        for (int i = 0; i < endpoints.length; i++) endpoints[i] = shiftCellReference(endpoints[i], beforeRow, delta);
        return String.join(":", endpoints);
    }

    private String shiftCellReference(String reference, int beforeRow, int delta) {
        Matcher matcher = CELL_REF.matcher(reference);
        if (!matcher.matches()) return reference;
        int row = Integer.parseInt(matcher.group(3));
        if (row >= beforeRow) row += delta;
        return matcher.group(1) + matcher.group(2) + row;
    }

    private void setRowNumber(Element row, int rowNumber) {
        row.setAttribute("r", Integer.toString(rowNumber));
        for (Element cell : directChildren(row, SPREADSHEET_NS, "c")) {
            Matcher matcher = CELL_REF.matcher(cell.getAttribute("r"));
            if (!matcher.matches()) continue;
            cell.setAttribute("r", matcher.group(1) + matcher.group(2) + rowNumber);
        }
    }

    private byte[] patchDrawing(byte[] sourceBytes, Plan plan, MasterEquipment equipment) {
        var info = packageInspector.inspect(sourceBytes).sheets().stream()
                .filter(item -> plan.sheetName().equals(item.sheetName())).findFirst().orElseThrow();
        String part = info.drawingPartName();
        Element drawing = parseXml(partEditor.readPart(sourceBytes, part), part);
        int nextId = nextDrawingObjectId(drawing);
        Element sourceAnchor = null;
        for (Element anchor : directChildren(drawing, XDR_NS, "twoCellAnchor")) {
            Element shape = firstChild(anchor, XDR_NS, "cxnSp");
            if (shape == null) continue;
            Element nv = firstDescendant(shape, XDR_NS, "cNvPr");
            if (nv == null) continue;
            if (nv.getAttribute("id").equals(plan.existingConnector().shapeId())) sourceAnchor = anchor;
        }
        if (sourceAnchor == null) throw new InvalidWorkbookException("Detected connector anchor disappeared before editing");
        for (Element anchor : directChildren(drawing, XDR_NS, "twoCellAnchor")) {
            Element shape = firstChild(anchor, XDR_NS, "cxnSp");
            Element nv = shape == null ? null : firstDescendant(shape, XDR_NS, "cNvPr");
            if (nv != null && nv.getAttribute("id").equals(plan.existingConnector().shapeId())) continue;
            shiftAnchorRows(anchor, plan.insertionBeforeRow(), plan.insertedRows());
        }
        Element clone = (Element) drawing.getOwnerDocument().importNode(sourceAnchor, true);
        Element cloneShape = firstChild(clone, XDR_NS, "cxnSp");
        Element cloneNv = firstDescendant(cloneShape, XDR_NS, "cNvPr");
        cloneNv.setAttribute("id", Integer.toString(nextId));
        cloneNv.setAttribute("name", "TEST_ONLY " + equipment.permitManagementNo() + " flow connector");
        Element from = firstChild(clone, XDR_NS, "from");
        Element to = firstChild(clone, XDR_NS, "to");
        setMarker(from, plan.existingConnector().fromColumn(), plan.candidateLastRow(), 0, 0);
        setMarker(to, plan.existingConnector().toColumn(), plan.target().firstRow() - 1 + plan.insertedRows(), 0, 0);
        Element xfrm = firstDescendant(cloneShape, A_NS, "xfrm");
        Element off = firstChild(xfrm, A_NS, "off");
        Element ext = firstChild(xfrm, A_NS, "ext");
        if (off != null && ext != null) {
            long y = Long.parseLong(off.getAttribute("y"));
            long rowsMoved = plan.candidateLastRow() - plan.existingConnector().fromRow();
            long connectorRows = plan.target().firstRow() - 1L + plan.insertedRows() - plan.candidateLastRow();
            off.setAttribute("y", Long.toString(y + rowsMoved * 153865L));
            ext.setAttribute("cy", Long.toString(connectorRows * 153865L));
        }
        drawing.appendChild(clone);
        return serialize(drawing.getOwnerDocument());
    }

    private Element drawingElement(Document document, String name) {
        return document.createElementNS(XDR_NS, "xdr:" + name);
    }

    private Element mainElement(Document document, String name) {
        return document.createElementNS(A_NS, "a:" + name);
    }

    private int nextDrawingObjectId(Element drawing) {
        NodeList nonVisualProperties = drawing.getElementsByTagNameNS(XDR_NS, "cNvPr");
        Set<Integer> seenIds = new HashSet<>();
        int maximumId = 0;
        for (int index = 0; index < nonVisualProperties.getLength(); index++) {
            Element properties = (Element) nonVisualProperties.item(index);
            String value = properties.getAttribute("id");
            final int id;
            try {
                id = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new InvalidWorkbookException("DrawingML cNvPr has an invalid object ID: " + value, exception);
            }
            if (id < 0) throw new InvalidWorkbookException("DrawingML cNvPr object IDs must not be negative");
            if (!seenIds.add(id)) {
                throw new InvalidWorkbookException("DrawingML cNvPr ID is already duplicated in this drawing part: " + id);
            }
            maximumId = Math.max(maximumId, id);
        }
        if (maximumId == Integer.MAX_VALUE) throw new InvalidWorkbookException("DrawingML cNvPr ID space is exhausted");
        return maximumId + 1;
    }

    private byte[] patchWorkbook(byte[] sourceBytes, Plan plan) {
        Element root = parseXml(partEditor.readPart(sourceBytes, "xl/workbook.xml"), "xl/workbook.xml");
        NodeList workbookViews = root.getElementsByTagNameNS(SPREADSHEET_NS, "workbookView");
        if (workbookViews.getLength() > 0) {
            ((Element) workbookViews.item(0)).setAttribute("activeTab", Integer.toString(plan.sheetIndex() - 1));
        }
        NodeList names = root.getElementsByTagNameNS(SPREADSHEET_NS, "definedName");
        boolean found = false;
        for (int i = 0; i < names.getLength(); i++) {
            Element name = (Element) names.item(i);
            if (!"_xlnm.Print_Area".equals(name.getAttribute("name"))) continue;
            String value = name.getTextContent();
            String qualifiedSheet = "'" + plan.sheetName().replace("'", "''") + "'!";
            if (value.contains(qualifiedSheet)) {
                String range = value.substring(value.indexOf('!') + 1);
                name.setTextContent(qualifiedSheet + shiftRange(range, plan.insertionBeforeRow(), plan.insertedRows()));
                found = true;
            }
        }
        if (!found) throw new InvalidWorkbookException("Print area was not found for worksheet " + plan.sheetName());
        return serialize(root.getOwnerDocument());
    }

    private void shiftAnchorRows(Element anchor, int beforeRow, int delta) {
        for (String markerName : List.of("from", "to")) {
            Element marker = firstChild(anchor, XDR_NS, markerName);
            Element row = marker == null ? null : firstChild(marker, XDR_NS, "row");
            if (row != null && Integer.parseInt(row.getTextContent()) + 1 >= beforeRow) {
                row.setTextContent(Integer.toString(Integer.parseInt(row.getTextContent()) + delta));
            }
        }
    }

    private void setMarker(Element marker, int column, int row, int columnOffset, int rowOffset) {
        setChild(marker, XDR_NS, "col", Integer.toString(column));
        setChild(marker, XDR_NS, "colOff", Integer.toString(columnOffset));
        setChild(marker, XDR_NS, "row", Integer.toString(row));
        setChild(marker, XDR_NS, "rowOff", Integer.toString(rowOffset));
    }

    private void setChild(Element parent, String namespace, String name, String value) {
        Element child = firstChild(parent, namespace, name);
        if (child == null) throw new InvalidWorkbookException("Connector anchor is missing " + name);
        child.setTextContent(value);
    }

    private void keepOnlyBoxCells(Element row, int firstColumn, int lastColumn) {
        for (Element cell : directChildren(row, SPREADSHEET_NS, "c")) {
            int column = columnIndex(cell.getAttribute("r"));
            boolean equipmentBoxCell = column >= firstColumn && column <= lastColumn;
            boolean leftProcessBandCell = column == 1;
            if (!equipmentBoxCell && !leftProcessBandCell) row.removeChild(cell);
        }
    }

    private int columnIndex(String cellRef) {
        Matcher matcher = CELL_REF.matcher(cellRef);
        if (!matcher.matches()) throw new InvalidWorkbookException("Invalid cell reference " + cellRef);
        String letters = matcher.group(1).replace("$", "");
        int value = 0;
        for (int i = 0; i < letters.length(); i++) value = value * 26 + letters.charAt(i) - 'A' + 1;
        return value - 1;
    }

    private List<Element> directChildren(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private void sortRows(Element sheetData) {
        List<Element> rows = directChildren(sheetData, SPREADSHEET_NS, "row");
        rows.sort(Comparator.comparingInt(row -> Integer.parseInt(row.getAttribute("r"))));
        for (Element row : rows) sheetData.appendChild(row);
    }

    private byte[] serialize(Document document) {
        try {
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
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to serialize edited OOXML part", exception);
        }
    }

    private void validateOutput(byte[] originalBytes, byte[] bytes, MasterEquipment equipment, Plan plan) {
        try (Workbook originalWorkbook = WorkbookFactory.create(new ByteArrayInputStream(originalBytes));
             Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(plan.sheetName());
            var originalSheet = originalWorkbook.getSheet(plan.sheetName());
            if (sheet == null || originalSheet == null) throw new InvalidWorkbookException("Edited workbook cannot be reopened");
            DataFormatter formatter = new DataFormatter();
            int firstCol = plan.firstColumn();
            int auxCol = firstCol + AUXILIARY_COLUMN_OFFSET;
            int firstRow = plan.candidateFirstRow();
            Map<String, String> expected = Map.of(
                    cellAddress(firstCol, firstRow - 1), equipment.permitManagementNo(),
                    cellAddress(firstCol, firstRow + 1), plan.specification().equipmentTypeLabel(),
                    cellAddress(firstCol, firstRow + 2), equipment.facilityName(),
                    cellAddress(firstCol, firstRow + 3), equipment.businessItemNo(),
                    cellAddress(firstCol, firstRow + 4), capacityText(equipment),
                    cellAddress(firstCol, firstRow + 5), equipment.quantityText(),
                    cellAddress(auxCol, firstRow + 1), "폐수",
                    cellAddress(auxCol, firstRow + 7), "폐기물",
                    cellAddress(firstCol, plan.target().firstRow() - 1 + plan.insertedRows()), plan.target().managementNo());
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                Cell cell = sheet.getRow(new CellReference(entry.getKey()).getRow())
                        .getCell(new CellReference(entry.getKey()).getCol());
                if (cell == null || !formatter.formatCellValue(cell).equals(entry.getValue())) {
                    throw new InvalidWorkbookException("Edited workbook validation failed at " + entry.getKey());
                }
            }
            if (sheet.getNumMergedRegions() != originalSheet.getNumMergedRegions()
                    || workbook.getPrintArea(plan.sheetIndex() - 1) == null
                    || sheet.isDisplayGridlines() != originalSheet.isDisplayGridlines()
                    || sheet.getPrintSetup().getPaperSize() != originalSheet.getPrintSetup().getPaperSize()) {
                throw new InvalidWorkbookException("Edited workbook changed gridline, merge, or print settings");
            }
            Cell managementNoCell = sheet.getRow(firstRow - 1).getCell(firstCol);
            Cell sourceManagementNoCell = originalSheet.getRow(plan.source().firstRow() - 1).getCell(firstCol);
            var idFont = workbook.getFontAt(managementNoCell.getCellStyle().getFontIndexAsInt());
            if (managementNoCell.getCellStyle().getIndex() != sourceManagementNoCell.getCellStyle().getIndex()
                    || sheet.getRow(firstRow - 1).getHeightInPoints() != originalSheet.getRow(plan.source().firstRow() - 1).getHeightInPoints()
                    || idFont.getFontHeightInPoints() != 7 || idFont.getBold()
                    || managementNoCell.getCellStyle().getWrapText() || managementNoCell.getCellStyle().getShrinkToFit()) {
                throw new InvalidWorkbookException("New equipment number must retain the existing PW-01-05 equipment ID style");
            }
            Cell newNameCell = sheet.getRow(firstRow + 2).getCell(firstCol);
            Cell sourceNameCell = originalSheet.getRow(plan.source().firstRow() + 2).getCell(firstCol);
            var bodyFont = workbook.getFontAt(newNameCell.getCellStyle().getFontIndexAsInt());
            if (newNameCell.getCellStyle().getIndex() != sourceNameCell.getCellStyle().getIndex()
                    || bodyFont.getFontHeightInPoints() != 7 || !"맑은 고딕".equals(bodyFont.getFontName())
                    || !newNameCell.getCellStyle().getShrinkToFit()) {
                throw new InvalidWorkbookException("New equipment details must retain the existing PW-01-05 label styles");
            }
            Element editedSheet = parseXml(partEditor.readPart(bytes, packageInspector.inspect(bytes).sheets().stream()
                    .filter(item -> plan.sheetName().equals(item.sheetName())).findFirst().orElseThrow().worksheetPartName()),
                    plan.sheetName());
            Element sheetView = firstDescendant(editedSheet, SPREADSHEET_NS, "sheetView");
            String expectedFocus = cellAddress(firstCol, firstRow - 1);
            if (sheetView == null || !expectedFocus.equals(sheetView.getAttribute("topLeftCell"))
                    || !"90".equals(sheetView.getAttribute("zoomScale"))) {
                throw new InvalidWorkbookException("PW-01-05 should open focused on the inserted box at 90% zoom");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Edited TEST_ONLY workbook cannot be reopened", exception);
        }
        var originalInventory = packageInspector.inspect(originalBytes);
        var editedInventory = packageInspector.inspect(bytes);
        if (editedInventory.shapeCount() != originalInventory.shapeCount()
                || editedInventory.connectorCount() != originalInventory.connectorCount() + 1) {
            throw new InvalidWorkbookException("Only the TEST_ONLY connector may be added to DrawingML");
        }
        var page = editedInventory.sheets().stream().filter(item -> plan.sheetName().equals(item.sheetName())).findFirst().orElseThrow();
        String connectorName = "TEST_ONLY " + equipment.permitManagementNo() + " flow connector";
        if (page.drawingObjects().stream().noneMatch(object -> connectorName.equals(object.objectName()))
                || page.drawingObjects().stream().anyMatch(object -> object.objectName() != null
                && (object.objectName().contains("TEST_ONLY / REVIEW_REQUIRED")
                || object.textContent().contains("TEST_ONLY / REVIEW_REQUIRED")))) {
            throw new InvalidWorkbookException("The TEST_ONLY connector must be present without a visual status callout");
        }
    }

    private int findBoxRight(org.apache.poi.ss.usermodel.Sheet sheet, Cell topLeft) {
        Row row = topLeft.getRow();
        for (int column = topLeft.getColumnIndex(); column < topLeft.getColumnIndex() + 80; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
            if (cell != null && cell.getCellStyle().getBorderRight() != org.apache.poi.ss.usermodel.BorderStyle.NONE) return column;
        }
        return -1;
    }

    private int findBoxBottom(org.apache.poi.ss.usermodel.Sheet sheet, Cell topLeft) {
        int right = findBoxRight(sheet, topLeft);
        if (right < topLeft.getColumnIndex()) return -1;
        for (int rowIndex = topLeft.getRowIndex() + 1; rowIndex < topLeft.getRowIndex() + 24; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            boolean fullBottom = true;
            for (int column = topLeft.getColumnIndex(); column <= right; column++) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                if (cell == null || cell.getCellStyle().getBorderBottom() == org.apache.poi.ss.usermodel.BorderStyle.NONE) {
                    fullBottom = false;
                    break;
                }
            }
            if (fullBottom) return rowIndex;
        }
        return -1;
    }

    private ConnectorEvidence findConnectingConnector(byte[] workbookBytes, String drawingPart,
                                                       EquipmentBox source, EquipmentBox target) {
        Element drawing = parseXml(partEditor.readPart(workbookBytes, drawingPart), drawingPart);
        NodeList anchors = drawing.getElementsByTagNameNS(XDR_NS, "twoCellAnchor");
        for (int index = 0; index < anchors.getLength(); index++) {
            Element anchor = (Element) anchors.item(index);
            Element shape = firstChild(anchor, XDR_NS, "cxnSp");
            if (shape == null) continue;
            Element nv = firstDescendant(shape, XDR_NS, "cNvPr");
            Element from = firstChild(anchor, XDR_NS, "from");
            Element to = firstChild(anchor, XDR_NS, "to");
            if (nv == null || from == null || to == null) continue;
            int fc = intChild(from, "col"), fr = intChild(from, "row");
            int tc = intChild(to, "col"), tr = intChild(to, "row");
            Element tail = firstDescendant(shape, A_NS, "tailEnd");
            boolean pointsDown = tail != null && "triangle".equals(tail.getAttribute("type"));
            if (fc == tc && fc >= source.firstColumn() && fc <= source.lastColumn()
                    && fr == source.lastRow() && tr == target.firstRow() - 1 && tr > fr && pointsDown) {
                Element startBinding = firstDescendant(shape, A_NS, "stCxn");
                Element endBinding = firstDescendant(shape, A_NS, "endCxn");
                return new ConnectorEvidence(nv.getAttribute("id"), nv.getAttribute("name"), fc, fr, tc, tr,
                        tail.getAttribute("type"), startBinding != null || endBinding != null, index);
            }
        }
        return new ConnectorEvidence(null, null, -1, -1, -1, -1, null, false, -1);
    }

    private Element parseXml(byte[] xml, String partName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml))).getDocumentElement();
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Malformed XML part: " + partName, exception);
        }
    }

    private Element firstChild(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private Element firstDescendant(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private int intChild(Element parent, String name) {
        Element child = firstChild(parent, XDR_NS, name);
        return child == null ? -1 : Integer.parseInt(child.getTextContent());
    }

    private String cellAddress(int column, int row) {
        return new CellReference(row, column).formatAsString().replace("$", "");
    }

    private String cellOffset(String origin, int rowOffset) {
        CellReference reference = new CellReference(origin);
        return cellAddress(reference.getCol(), reference.getRow() + rowOffset);
    }

    public record ModifyResult(byte[] workbookBytes, EquipmentBox box, Map<String, String> beforeValues,
                               Map<String, String> afterValues, Map<String, String> changedCells,
                               String worksheetPartName, String drawingPartName) { }

    public record EquipmentBox(String managementNo, String facilityName, int firstColumn, int firstRow,
                               int lastColumn, int lastRow, String topLeftCell, String bottomRightCell,
                               int facilityNameRow) { }

    public record ConnectorEvidence(String shapeId, String name, int fromColumn, int fromRow,
                                    int toColumn, int toRow, String arrowAtEnd, boolean hasShapeBindings,
                                    int anchorIndex) { }

    public record TestEditSpec(String sheetName, String sourceId, String sourceName, String targetId,
                               String targetName, String equipmentTypeLabel) { }

    public record Plan(String sheetName, int sheetIndex, TestEditSpec specification, EquipmentBox source,
                       EquipmentBox target, ConnectorEvidence existingConnector, int insertionBeforeRow,
                       int candidateFirstRow, int candidateLastRow, int boxHeight, int insertedRows,
                       int originalGapRows, int firstColumn, int lastColumn,
                       boolean relationConfirmed, String scenarioStatus) { }

    public record EditResult(byte[] workbookBytes, Plan plan, String scenarioStatus, String relationStatus) { }
}
