package com.example.processautomation.material;

import com.example.processautomation.excel.ExcelCellText;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** Reads cell values and DrawingML anchors without recalculating formulas or mutating source workbooks. */
@Component
public class Step7DrawingEvidenceExtractor {
    private static final Pattern EQUIPMENT_ID = Pattern.compile("(?<![A-Z0-9])((?:C|I)-PW\\d{5})(~?)(?![A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOW_TEXT = Pattern.compile(
            "^\\s*(.+?)\\s+([-+]?(?:(?:\\d{1,3}(?:,\\d{3})+)|\\d+)(?:\\.\\d+)?)\\s*([\\p{L}㎥³%/·^0-9.-]+)?\\s*$");

    private final OoxmlPackageInspector inspector;

    public Step7DrawingEvidenceExtractor(OoxmlPackageInspector inspector) {
        this.inspector = inspector;
    }

    public SheetEvidence extract(byte[] workbookBytes, String sheetName) {
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new InvalidWorkbookException("Official drawing workbook is empty");
        }
        return extract(workbookBytes, sheetName, inspector.inspect(workbookBytes));
    }

    public SheetEvidence extract(byte[] workbookBytes, String sheetName, WorkbookInventory inventory) {
        if (workbookBytes == null || workbookBytes.length == 0) {
            throw new InvalidWorkbookException("Official drawing workbook is empty");
        }
        WorkbookInventory.SheetInventory indexed = inventory.sheets().stream()
                .filter(sheet -> sheet.sheetName().equals(sheetName)).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Official sheet not found: " + sheetName));
        List<CellEvidence> cells = new ArrayList<>();
        List<EquipmentLabel> labels = new ArrayList<>();
        List<FlowLabel> flows = new ArrayList<>();
        List<CellEvidence> numericCells = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(sheetName);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    CellEvidence evidence = readCell(cell, formatter);
                    if (evidence == null) continue;
                    cells.add(evidence);
                    if (cell.getCellType() == CellType.NUMERIC
                            || (cell.getCellType() == CellType.FORMULA
                            && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
                        numericCells.add(evidence);
                    }
                    Matcher equipment = EQUIPMENT_ID.matcher(evidence.displayValue());
                    while (equipment.find()) {
                        labels.add(new EquipmentLabel(equipment.group(1).toUpperCase(Locale.ROOT),
                                evidence.cellAddress(), evidence.displayValue(), !equipment.group(2).isBlank(),
                                evidence.cellType(), evidence.formula(), evidence.cachedValueType()));
                    }
                    FlowLabel flow = flowLabel(evidence);
                    if (flow != null) flows.add(flow);
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inspect official sheet cells without recalculation", exception);
        }
        List<WorkbookInventory.DrawingObject> connectors = indexed.drawingObjects().stream()
                .filter(object -> "CONNECTOR".equals(object.objectType())).toList();
        List<WorkbookInventory.DrawingObject> shapes = indexed.drawingObjects().stream()
                .filter(object -> "SHAPE".equals(object.objectType())).toList();
        int endpointBindings = (int) connectors.stream().filter(object -> object.startConnectedShapeId() != null
                || object.endConnectedShapeId() != null).count();
        return new SheetEvidence(sheetName, indexed.worksheetPartName(), indexed.drawingPartName(),
                List.copyOf(cells), List.copyOf(labels), List.copyOf(flows), List.copyOf(numericCells),
                connectors, shapes, endpointBindings, indexed.drawingObjects().size());
    }

    private CellEvidence readCell(Cell cell, DataFormatter formatter) {
        CellType type = cell.getCellType();
        CellType cachedType = type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
        String display = ExcelCellText.format(cell, formatter);
        if ((display == null || display.isBlank()) && type != CellType.FORMULA) return null;
        String formula = type == CellType.FORMULA ? cell.getCellFormula() : "";
        String cachedValue = "";
        BigDecimal numeric = null;
        if (cachedType == CellType.NUMERIC) {
            double raw = cell.getNumericCellValue();
            if (Double.isFinite(raw)) {
                numeric = BigDecimal.valueOf(raw);
                cachedValue = numeric.stripTrailingZeros().toPlainString();
            }
        } else if (cachedType == CellType.STRING) {
            cachedValue = cell.getStringCellValue();
        } else if (cachedType == CellType.BOOLEAN) {
            cachedValue = Boolean.toString(cell.getBooleanCellValue());
        }
        return new CellEvidence(new CellReference(cell.getRowIndex(), cell.getColumnIndex()).formatAsString(),
                display == null ? "" : display.trim(), type.name(), cachedType.name(), formula, cachedValue, numeric);
    }

    private FlowLabel flowLabel(CellEvidence cell) {
        if (cell.displayValue().isBlank()) return null;
        Matcher match = FLOW_TEXT.matcher(cell.displayValue().replace('\u00a0', ' ').trim());
        if (!match.matches()) return null;
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(match.group(2).replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
        return new FlowLabel(cell.cellAddress(), cell.displayValue(), match.group(1).trim(), quantity,
                match.group(3) == null ? "" : match.group(3).trim(), cell.cellType(), cell.cachedValueType(),
                cell.formula(), cell.cachedValue());
    }

    public record CellEvidence(String cellAddress, String displayValue, String cellType,
                               String cachedValueType, String formula, String cachedValue,
                               BigDecimal numericValue) { }

    public record EquipmentLabel(String equipmentNumber, String cellAddress, String rawCellText,
                                 boolean approximateLabel, String cellType, String formula,
                                 String cachedValueType) { }

    public record FlowLabel(String cellAddress, String rawCellText, String materialName,
                            BigDecimal quantity, String unit, String cellType,
                            String cachedValueType, String formula, String cachedValue) { }

    public record SheetEvidence(String sheetName, String worksheetPart, String drawingPart,
                                List<CellEvidence> rawCells, List<EquipmentLabel> equipmentLabels,
                                List<FlowLabel> flowLabels, List<CellEvidence> numericCells,
                                List<WorkbookInventory.DrawingObject> connectors,
                                List<WorkbookInventory.DrawingObject> shapes,
                                int connectorEndpointBindings, int drawingObjectCount) {
        public SheetEvidence {
            rawCells = List.copyOf(rawCells);
            equipmentLabels = List.copyOf(equipmentLabels);
            flowLabels = List.copyOf(flowLabels);
            numericCells = List.copyOf(numericCells);
            connectors = List.copyOf(connectors);
            shapes = List.copyOf(shapes);
        }
    }
}
