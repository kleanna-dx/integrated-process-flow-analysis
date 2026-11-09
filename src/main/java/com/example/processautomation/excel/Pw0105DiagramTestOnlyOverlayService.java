package com.example.processautomation.excel;

import com.example.processautomation.excel.MaterialBalanceExcelOutputModel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

/** Adds explicitly TEST_ONLY DrawingML overlays to the preserved PW-01-05 diagram. */
@Service
public class Pw0105DiagramTestOnlyOverlayService {
    private static final String SHEET = "PW-01-05";
    private static final String DRAWING_PART = "xl/drawings/drawing5.xml";
    private static final Pattern PRINT_AREA = Pattern.compile(".*!\\$?([A-Z]{1,3})\\$?[0-9]+:\\$?([A-Z]{1,3})\\$?[0-9]+.*");
    private static final Pattern SHAPE_ID = Pattern.compile("<xdr:cNvPr\\s+id=\\\"([0-9]+)\\\"");
    private static final String ROOT_CLOSE = "</xdr:wsDr>";

    private final OoxmlPartEditor partEditor;
    private final OoxmlPackageInspector packageInspector;
    private final Pw0105EquipmentInsertionEngine equipmentEngine;

    public Pw0105DiagramTestOnlyOverlayService(OoxmlPartEditor partEditor,
                                               OoxmlPackageInspector packageInspector,
                                               Pw0105EquipmentInsertionEngine equipmentEngine) {
        this.partEditor = partEditor;
        this.packageInspector = packageInspector;
        this.equipmentEngine = equipmentEngine;
    }

    public OverlayResult addCpw15020TestOnlyFlows(byte[] workbookBytes, MaterialBalanceExcelOutputModel model) {
        requireModel(model);
        var upstreamPlan = equipmentEngine.analyze(workbookBytes,
                new Pw0105EquipmentInsertionEngine.TestEditSpec(SHEET, "C-PW15009",
                        "ACTIVATED CARBON FILTER", "C-PW15020", "SCREEN FILTER", "여과시설"));
        var downstreamPlan = equipmentEngine.analyze(workbookBytes,
                new Pw0105EquipmentInsertionEngine.TestEditSpec(SHEET, "C-PW15020",
                        "SCREEN FILTER", "C-PW15010", "FINAL EFFLUENT TANK", "여과시설"));
        var box = upstreamPlan.target();
        if (!"C-PW15020".equals(box.managementNo()) || !"C-PW15020".equals(downstreamPlan.source().managementNo())
                || upstreamPlan.existingConnector().shapeId() == null || downstreamPlan.existingConnector().shapeId() == null) {
            throw new InvalidWorkbookException("C-PW15020 diagram/connector evidence is incomplete; overlay was not written");
        }

        Layout layout = inspectAvailableLayout(workbookBytes, box, downstreamPlan.target());
        byte[] originalDrawing = partEditor.readPart(workbookBytes, DRAWING_PART);
        String originalXml = new String(originalDrawing, StandardCharsets.UTF_8);
        int rootClose = originalXml.lastIndexOf(ROOT_CLOSE);
        if (rootClose < 0 || originalXml.indexOf(ROOT_CLOSE) != rootClose) {
            throw new InvalidWorkbookException("PW-01-05 DrawingML root close tag is missing or ambiguous");
        }
        int firstId = maxShapeId(originalXml) + 1;
        String overlays = buildOverlays(layout, firstId);
        String updatedXml = originalXml.substring(0, rootClose) + overlays + originalXml.substring(rootClose);
        byte[] updatedDrawing = updatedXml.getBytes(StandardCharsets.UTF_8);
        byte[] output = partEditor.replacePackagePart(workbookBytes, DRAWING_PART, updatedDrawing);
        validateOutput(workbookBytes, output, updatedDrawing, overlays, firstId, model, layout);
        return new OverlayResult(output, DRAWING_PART, layout, 4, 2,
                upstreamPlan.existingConnector().shapeId(), downstreamPlan.existingConnector().shapeId());
    }

    private void requireModel(MaterialBalanceExcelOutputModel model) {
        if (model == null || !"TEST_ONLY".equals(model.mode()) || model.officialWorkbookCellsModified()
                || model.officialMaterialBalanceUpdated() || model.transfers().size() != 4) {
            throw new InvalidWorkbookException("Only the isolated four-transfer C-PW15020 TEST_ONLY model can be overlaid");
        }
        requireTransfer(model, "cpw15009-cpw15020-in", "100");
        requireTransfer(model, "cpw15020-waste-0_5", "0.5");
        requireTransfer(model, "cpw15020-unassigned-2_5", "2.5");
        requireTransfer(model, "cpw15020-cpw15010-out", "97");
        boolean balanced = model.balances().stream().anyMatch(balance -> "C-PW15020".equals(balance.nodeId())
                && balance.inflow() != null && balance.inflow().compareTo(new java.math.BigDecimal("100")) == 0
                && balance.outflow() != null && balance.outflow().compareTo(new java.math.BigDecimal("100")) == 0
                && balance.residual() != null && balance.residual().signum() == 0
                && "CALCULATED".equals(balance.balanceStatus()));
        if (!balanced) throw new InvalidWorkbookException("C-PW15020 TEST_ONLY material balance is not 100 in / 100 out / zero residual");
    }

    private void requireTransfer(MaterialBalanceExcelOutputModel model, String transferId, String expectedQuantity) {
        var transfer = model.transfers().stream().filter(item -> transferId.equals(item.transferId())).findFirst().orElse(null);
        if (transfer == null || !transfer.included() || transfer.senderQuantity() == null
                || transfer.senderQuantity().compareTo(new java.math.BigDecimal(expectedQuantity)) != 0
                || transfer.receiverQuantity() == null || transfer.receiverQuantity().compareTo(transfer.senderQuantity()) != 0
                || transfer.sourceEquipmentId() != null || transfer.targetEquipmentId() != null) {
            throw new InvalidWorkbookException("TEST_ONLY flow value or unmapped receiver check failed: " + transferId);
        }
    }

    private Layout inspectAvailableLayout(byte[] bytes, Pw0105EquipmentInsertionEngine.EquipmentBox box,
                                          Pw0105EquipmentInsertionEngine.EquipmentBox downstreamBox) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(SHEET);
            if (sheet == null) throw new InvalidWorkbookException("PW-01-05 sheet is missing");
            CellReference water = locateLabel(sheet, box, "폐수");
            CellReference waste = locateLabel(sheet, box, "폐기물");
            int rightEdge = box.lastColumn();
            int overlayLeft = rightEdge + 2;
            int printRight = readPrintAreaRight(workbook, workbook.getSheetIndex(SHEET));
            int overlayRight = printRight + 1;
            if (overlayRight - overlayLeft < 5) {
                throw new InvalidWorkbookException("Insufficient printed columns to the right of C-PW15020 for non-overlapping TEST_ONLY receivers");
            }
            int boxTop = new CellReference(box.topLeftCell()).getRow();
            int boxBottomMarker = new CellReference(box.bottomRightCell()).getRow() + 1;
            int downstreamTopMarker = new CellReference(downstreamBox.topLeftCell()).getRow();
            int waterTop = water.getRow();
            int waterBottom = waterTop + 6;
            int wasteTop = waterBottom;
            int wasteBottom = boxBottomMarker;
            int inputLeft = box.firstColumn() + 1;
            int inputRight = water.getCol() - 1;
            int inputTop = boxBottomMarker - 3;
            int inputBottom = boxBottomMarker - 1;
            int downstreamLeft = box.firstColumn() + 8;
            int downstreamRight = water.getCol() + 3;
            int downstreamTop = boxBottomMarker;
            int downstreamBottom = downstreamTopMarker;
            List<Rectangle> regions = List.of(
                    new Rectangle(inputLeft, inputTop, inputRight, inputBottom - 1, "TEST_ONLY inflow label"),
                    new Rectangle(overlayLeft, waterTop, overlayRight - 1, waterBottom - 1, "TEST_ONLY wastewater receiver box"),
                    new Rectangle(overlayLeft, wasteTop, overlayRight - 1, wasteBottom - 1, "TEST_ONLY waste receiver box"),
                    new Rectangle(downstreamLeft, downstreamTop, downstreamRight - 1, downstreamBottom - 1, "TEST_ONLY downstream label"));
            if (waterTop < boxTop || waterBottom >= boxBottomMarker || wasteTop < waterBottom
                    || wasteBottom > boxBottomMarker || downstreamBottom <= downstreamTop
                    || downstreamRight >= overlayLeft) {
                throw new InvalidWorkbookException("Detected C-PW15020 compartment layout does not support the planned callout anchors");
            }
            assertNoCellText(sheet, regions);
            assertNoMergedCells(sheet, regions);
            assertNoDrawingCollisions(bytes, regions);
            long[] xPoints = columnPoints(sheet, overlayRight);
            long[] yPoints = rowPoints(sheet, downstreamBottom + 1);
            return new Layout(box.topLeftCell(), box.bottomRightCell(), address(water), address(waste),
                    inputLeft, inputTop, inputRight, inputBottom, overlayLeft, waterTop, overlayRight, waterBottom,
                    overlayLeft, wasteTop, overlayRight, wasteBottom, downstreamLeft, downstreamTop,
                    downstreamRight, downstreamBottom, waterTop + 2, wasteTop + 1, printRight,
                    box.firstColumn() + 7, downstreamBottom, xPoints, yPoints);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to inspect non-overlapping C-PW15020 diagram overlay layout", exception);
        }
    }

    private CellReference locateLabel(org.apache.poi.ss.usermodel.Sheet sheet,
                                      Pw0105EquipmentInsertionEngine.EquipmentBox box, String label) {
        DataFormatter formatter = new DataFormatter();
        List<CellReference> matches = new ArrayList<>();
        for (int rowIndex = box.firstRow(); rowIndex <= box.lastRow(); rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (var cell : row) {
                if (cell.getColumnIndex() >= box.firstColumn() && cell.getColumnIndex() <= box.lastColumn()
                        && label.equals(formatter.formatCellValue(cell).trim())) {
                    matches.add(new CellReference(cell.getRowIndex(), cell.getColumnIndex()));
                }
            }
        }
        if (matches.size() != 1) throw new InvalidWorkbookException("Cannot uniquely locate compartment label " + label);
        return matches.get(0);
    }

    private long[] columnPoints(org.apache.poi.ss.usermodel.Sheet sheet, int finalMarkerColumn) {
        long[] points = new long[finalMarkerColumn + 1];
        for (int column = 0; column < finalMarkerColumn; column++) {
            long width = sheet.isColumnHidden(column) ? 0L : Math.round(sheet.getColumnWidthInPixels(column) * 9525d);
            points[column + 1] = points[column] + width;
        }
        return points;
    }

    private long[] rowPoints(org.apache.poi.ss.usermodel.Sheet sheet, int finalMarkerRow) {
        long[] points = new long[finalMarkerRow + 1];
        for (int rowIndex = 0; rowIndex < finalMarkerRow; rowIndex++) {
            var row = sheet.getRow(rowIndex);
            long height = row != null && row.getZeroHeight() ? 0L : Math.round(
                    (row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * 12700d);
            points[rowIndex + 1] = points[rowIndex] + height;
        }
        return points;
    }

    private int readPrintAreaRight(org.apache.poi.ss.usermodel.Workbook workbook, int sheetIndex) {
        String printArea = workbook.getPrintArea(sheetIndex);
        Matcher matcher = printArea == null ? null : PRINT_AREA.matcher(printArea);
        if (matcher == null || !matcher.matches()) throw new InvalidWorkbookException("PW-01-05 print area could not be parsed for overlay bounds");
        return new CellReference(matcher.group(2) + "1").getCol();
    }

    private void assertNoCellText(org.apache.poi.ss.usermodel.Sheet sheet, List<Rectangle> regions) {
        DataFormatter formatter = new DataFormatter();
        for (Rectangle region : regions) {
            for (int rowIndex = region.topRow(); rowIndex <= region.bottomRow(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (int column = region.leftColumn(); column <= region.rightColumn(); column++) {
                    var cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null && !formatter.formatCellValue(cell).isBlank()) {
                        throw new InvalidWorkbookException("Existing cell text overlaps planned overlay " + region.name()
                                + " at " + cell.getAddress().formatAsString());
                    }
                }
            }
        }
    }

    private void assertNoMergedCells(org.apache.poi.ss.usermodel.Sheet sheet, List<Rectangle> regions) {
        for (var merged : sheet.getMergedRegions()) {
            for (Rectangle region : regions) {
                if (merged.getFirstColumn() <= region.rightColumn() && merged.getLastColumn() >= region.leftColumn()
                        && merged.getFirstRow() <= region.bottomRow() && merged.getLastRow() >= region.topRow()) {
                    throw new InvalidWorkbookException("Merged cell " + merged.formatAsString()
                            + " intersects planned overlay " + region.name());
                }
            }
        }
    }

    private void assertNoDrawingCollisions(byte[] bytes, List<Rectangle> regions) {
        var inventory = packageInspector.inspect(bytes);
        var drawing = inventory.sheets().stream().filter(sheet -> SHEET.equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("PW-01-05 drawing inventory is missing"));
        for (var object : drawing.drawingObjects()) {
            if (object.startColumn() == null || object.endColumn() == null || object.startRow() == null || object.endRow() == null) continue;
            for (Rectangle region : regions) {
                boolean overlaps = object.startColumn() <= region.rightColumn() && object.endColumn() >= region.leftColumn()
                        && object.startRow() <= region.bottomRow() && object.endRow() >= region.topRow();
                if (overlaps) throw new InvalidWorkbookException("Existing DrawingML object " + object.objectName()
                        + " (#" + object.shapeId() + ") intersects planned overlay " + region.name()
                        + " at anchors " + object.startColumn() + ":" + object.startRow() + "–"
                        + object.endColumn() + ":" + object.endRow());
            }
        }
    }

    private String buildOverlays(Layout layout, int firstId) {
        StringBuilder xml = new StringBuilder(12000);
        xml.append(textBox(firstId, "TEST_ONLY C-PW15020 inflow 100", layout, layout.inputLeft(), layout.inputTop(),
                layout.inputRight(), layout.inputBottom(), "FFF2CC", "C00000", "C00000", 700,
                List.of("TEST_ONLY", "유입 100 ton/day")));
        xml.append(textBox(firstId + 1, "TEST_ONLY unassigned wastewater 2.5", layout, layout.waterBoxLeft(), layout.waterBoxTop(),
                layout.waterBoxRight(), layout.waterBoxBottom(), "FFFFFF", "000000", "000000", 600,
                List.of("TEST_ONLY", "폐수 이동처 미확정", "2.5 ton/day")));
        xml.append(line(firstId + 2, "TEST_ONLY wastewater branch arrow", layout, layout.branchStartColumn(), layout.waterArrowRow(),
                layout.waterBoxLeft(), layout.waterArrowRow(), "C00000", true));
        xml.append(textBox(firstId + 3, "TEST_ONLY unassigned waste 0.5", layout, layout.wasteBoxLeft(), layout.wasteBoxTop(),
                layout.wasteBoxRight(), layout.wasteBoxBottom(), "FFFFFF", "000000", "000000", 600,
                List.of("TEST_ONLY", "폐기물 이동처 미확정", "0.5 ton/day 발생")));
        xml.append(line(firstId + 4, "TEST_ONLY waste branch arrow", layout, layout.branchStartColumn(), layout.wasteArrowRow(),
                layout.wasteBoxLeft(), layout.wasteArrowRow(), "C00000", true));
        xml.append(textBox(firstId + 5, "TEST_ONLY C-PW15020 downstream 97", layout, layout.downstreamLeft(),
                layout.downstreamTop(), layout.downstreamRight(), layout.downstreamBottom(), "FFF2CC", "C00000",
                "C00000", 650, List.of("TEST_ONLY", "후단 이동 97 ton/day")));
        return xml.toString();
    }

    private String textBox(int id, String name, Layout layout, int left, int top, int right, int bottom,
                           String fill, String line, String text, int fontSize, List<String> lines) {
        long x1 = layout.xPoints()[left], x2 = layout.xPoints()[right];
        long y1 = layout.yPoints()[top], y2 = layout.yPoints()[bottom];
        StringBuilder xml = new StringBuilder();
        xml.append("<xdr:twoCellAnchor editAs=\"twoCell\">");
        appendMarker(xml, "from", left, top);
        appendMarker(xml, "to", right, bottom);
        xml.append("<xdr:sp macro=\"\" textlink=\"\"><xdr:nvSpPr><xdr:cNvPr id=\"").append(id)
                .append("\" name=\"").append(xml(name)).append("\" descr=\"TEST_ONLY; no approved relation\"/>")
                .append("<xdr:cNvSpPr txBox=\"1\"/></xdr:nvSpPr><xdr:spPr><a:xfrm><a:off x=\"").append(x1)
                .append("\" y=\"").append(y1).append("\"/><a:ext cx=\"").append(Math.max(1, x2 - x1))
                .append("\" cy=\"").append(Math.max(1, y2 - y1)).append("\"/></a:xfrm>")
                .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"")
                .append(fill).append("\"/></a:solidFill><a:ln w=\"6350\"><a:solidFill><a:srgbClr val=\"")
                .append(line).append("\"/></a:solidFill></a:ln></xdr:spPr><xdr:style>")
                .append("<a:lnRef idx=\"1\"><a:schemeClr val=\"dk1\"/></a:lnRef><a:fillRef idx=\"0\"><a:schemeClr val=\"dk1\"/></a:fillRef>")
                .append("<a:effectRef idx=\"0\"><a:schemeClr val=\"dk1\"/></a:effectRef><a:fontRef idx=\"minor\"><a:schemeClr val=\"tx1\"/></a:fontRef>")
                .append("</xdr:style><xdr:txBody><a:bodyPr wrap=\"square\" anchor=\"ctr\" lIns=\"45720\" rIns=\"45720\" tIns=\"0\" bIns=\"0\"/><a:lstStyle/>");
        for (int index = 0; index < lines.size(); index++) {
            xml.append("<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr lang=\"ko-KR\" sz=\"").append(fontSize)
                    .append("\" b=\"").append(index == 0 ? "1" : "0").append("\"><a:solidFill><a:srgbClr val=\"")
                    .append(text).append("\"/></a:solidFill><a:latin typeface=\"맑은 고딕\"/><a:ea typeface=\"맑은 고딕\"/></a:rPr><a:t xml:space=\"preserve\">")
                    .append(xml(lines.get(index))).append("</a:t></a:r><a:endParaRPr lang=\"ko-KR\" sz=\"")
                    .append(fontSize).append("\"/></a:p>");
        }
        xml.append("</xdr:txBody></xdr:sp><xdr:clientData/></xdr:twoCellAnchor>");
        return xml.toString();
    }

    private String line(int id, String name, Layout layout, int fromColumn, int row,
                        int toColumn, int toRow, String color, boolean arrow) {
        long x1 = layout.xPoints()[fromColumn], x2 = layout.xPoints()[toColumn], y1 = layout.yPoints()[row], y2 = layout.yPoints()[toRow];
        boolean vertical = fromColumn == toColumn;
        long cx = vertical ? 0L : Math.max(1L, x2 - x1);
        long cy = vertical ? Math.max(1L, y2 - y1) : 0L;
        StringBuilder xml = new StringBuilder();
        xml.append("<xdr:twoCellAnchor editAs=\"oneCell\">");
        appendMarker(xml, "from", fromColumn, row);
        appendMarker(xml, "to", toColumn, toRow);
        xml.append("<xdr:cxnSp macro=\"\"><xdr:nvCxnSpPr><xdr:cNvPr id=\"").append(id)
                .append("\" name=\"").append(xml(name)).append("\" descr=\"TEST_ONLY; visual only; not an approved route\"/>")
                .append("<xdr:cNvCxnSpPr/></xdr:nvCxnSpPr><xdr:spPr><a:xfrm><a:off x=\"").append(x1)
                .append("\" y=\"").append(y1).append("\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"straightConnector1\"><a:avLst/></a:prstGeom>")
                .append("<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"").append(color)
                .append("\"/></a:solidFill>");
        if (arrow) xml.append("<a:tailEnd type=\"triangle\"/>");
        xml.append("</a:ln></xdr:spPr><xdr:style>")
                .append("<a:lnRef idx=\"1\"><a:schemeClr val=\"dk1\"/></a:lnRef><a:fillRef idx=\"0\"><a:schemeClr val=\"dk1\"/></a:fillRef>")
                .append("<a:effectRef idx=\"0\"><a:schemeClr val=\"dk1\"/></a:effectRef><a:fontRef idx=\"minor\"><a:schemeClr val=\"tx1\"/></a:fontRef>")
                .append("</xdr:style></xdr:cxnSp><xdr:clientData/></xdr:twoCellAnchor>");
        return xml.toString();
    }

    private void appendMarker(StringBuilder xml, String name, int column, int row) {
        xml.append("<xdr:").append(name).append("><xdr:col>").append(column).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>")
                .append(row).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:").append(name).append(">");
    }

    private int maxShapeId(String xml) {
        Matcher matcher = SHAPE_ID.matcher(xml);
        int maximum = 0;
        while (matcher.find()) maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
        return maximum;
    }

    private void validateOutput(byte[] before, byte[] after, byte[] updatedDrawing, String overlays, int firstId,
                                MaterialBalanceExcelOutputModel model, Layout layout) {
        byte[] originalDrawing = partEditor.readPart(before, DRAWING_PART);
        String originalXml = new String(originalDrawing, StandardCharsets.UTF_8);
        String expectedXml = originalXml.substring(0, originalXml.lastIndexOf(ROOT_CLOSE)) + overlays + ROOT_CLOSE;
        if (!java.util.Arrays.equals(updatedDrawing, expectedXml.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWorkbookException("Unexpected mutation outside appended TEST_ONLY DrawingML shapes");
        }
        var beforeParts = packageParts(before);
        var afterParts = packageParts(after);
        if (!beforeParts.keySet().equals(afterParts.keySet())) {
            throw new InvalidWorkbookException("Diagram overlay added or removed unexpected OOXML package parts");
        }
        for (String part : beforeParts.keySet()) {
            if (DRAWING_PART.equals(part)) continue;
            if (!java.util.Arrays.equals(beforeParts.get(part), afterParts.get(part))) {
                throw new InvalidWorkbookException("Non-drawing OOXML part changed while adding diagram overlays: " + part);
            }
        }
        if (!java.util.Arrays.equals(afterParts.get(DRAWING_PART), updatedDrawing)) {
            throw new InvalidWorkbookException("Updated TEST_ONLY DrawingML part cannot be reread");
        }
        try (var sourceWorkbook = WorkbookFactory.create(new ByteArrayInputStream(before));
             var workbook = WorkbookFactory.create(new ByteArrayInputStream(after))) {
            var resultSheet = workbook.getSheet(MaterialBalanceExcelExportService.RESULTS_SHEET);
            var sourceDiagram = sourceWorkbook.getSheet(SHEET);
            var outputDiagram = workbook.getSheet(SHEET);
            if (resultSheet == null || sourceDiagram == null || outputDiagram == null
                    || outputDiagram.getNumMergedRegions() != sourceDiagram.getNumMergedRegions()
                    || !java.util.Objects.equals(workbook.getPrintArea(workbook.getSheetIndex(SHEET)),
                    sourceWorkbook.getPrintArea(sourceWorkbook.getSheetIndex(SHEET)))
                    || workbook.getNumberOfSheets() != sourceWorkbook.getNumberOfSheets()) {
                throw new InvalidWorkbookException("Diagram overlay changed existing PW-01-05 workbook layout metadata");
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Edited diagram workbook cannot be reopened", exception);
        }
        var beforeInventory = packageInspector.inspect(before);
        var inventory = packageInspector.inspect(after);
        if (inventory.shapeCount() != beforeInventory.shapeCount() + 4
                || inventory.connectorCount() != beforeInventory.connectorCount() + 2) {
            throw new InvalidWorkbookException("TEST_ONLY overlay shape/connector counts do not match the intended 4 shapes and 2 connectors");
        }
        var sheet = inventory.sheets().stream().filter(item -> SHEET.equals(item.sheetName())).findFirst().orElseThrow();
        if (sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId).equals(item.shapeId())
                && "TEST_ONLY C-PW15020 inflow 100".equals(item.objectName())
                && item.textContent().contains("TEST_ONLY") && item.textContent().contains("유입 100 ton/day"))
                || sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId + 1).equals(item.shapeId())
                && "TEST_ONLY unassigned wastewater 2.5".equals(item.objectName())
                && item.textContent().contains("2.5 ton/day"))
                || sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId + 3).equals(item.shapeId())
                && "TEST_ONLY unassigned waste 0.5".equals(item.objectName())
                && item.textContent().contains("0.5 ton/day 발생"))
                || sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId + 2).equals(item.shapeId())
                && "TEST_ONLY wastewater branch arrow".equals(item.objectName())
                && "triangle".equals(item.tailArrowType()))
                || sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId + 4).equals(item.shapeId())
                && "TEST_ONLY waste branch arrow".equals(item.objectName())
                && "triangle".equals(item.tailArrowType()))
                || sheet.drawingObjects().stream().noneMatch(item -> Integer.toString(firstId + 5).equals(item.shapeId())
                && "TEST_ONLY C-PW15020 downstream 97".equals(item.objectName())
                && item.textContent().contains("TEST_ONLY") && item.textContent().contains("후단 이동 97 ton/day"))) {
            throw new InvalidWorkbookException("TEST_ONLY flow labels, receiver boxes, or branch arrows were not found in DrawingML inventory");
        }
        if (!layout.wastewaterLabelAddress().equals(model.layoutAudit().wastewaterLabelCell())
                || !layout.wasteLabelAddress().equals(model.layoutAudit().wasteLabelCell())) {
            throw new InvalidWorkbookException("Dynamically discovered compartment labels changed before overlay");
        }
    }

    private java.util.Map<String, byte[]> packageParts(byte[] bytes) {
        try (var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)).get()) {
            java.util.Map<String, byte[]> parts = new java.util.LinkedHashMap<>();
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { parts.put(entry.getName(), input.readAllBytes()); }
            }
            return parts;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read package parts for diagram overlay verification", exception);
        }
    }

    private int columnWidthIndex(org.apache.poi.ss.usermodel.Sheet sheet, int index) {
        return Math.round(sheet.getColumnWidthInPixels(index) * 9525f);
    }

    private String address(CellReference ref) { return ref.formatAsString().replace("$", ""); }
    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public record Layout(String candidateRange, String downstreamRange, String wastewaterLabelAddress, String wasteLabelAddress,
                         int inputLeft, int inputTop, int inputRight, int inputBottom,
                         int waterBoxLeft, int waterBoxTop, int waterBoxRight, int waterBoxBottom,
                         int wasteBoxLeft, int wasteBoxTop, int wasteBoxRight, int wasteBoxBottom,
                         int downstreamLeft, int downstreamTop, int downstreamRight, int downstreamBottom,
                         int waterArrowRow, int wasteArrowRow, int printRightColumn,
                         int downstreamConnectorColumn, int downstreamLeaderRow,
                         long[] xPoints, long[] yPoints) {
        int branchStartColumn() { return waterBoxLeft - 1; }
    }
    public record Rectangle(int leftColumn, int topRow, int rightColumn, int bottomRow, String name) { }
    public record OverlayResult(byte[] workbookBytes, String drawingPart, Layout layout, int addedShapes, int addedConnectors,
                                String upstreamVisualConnectorId, String downstreamVisualConnectorId) { }
}
