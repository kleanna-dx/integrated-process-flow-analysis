package com.example.processautomation.excel;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Server-generated and immutable fixture; never populated from an official process workbook. */
@Component
public class PipeChangeSyntheticFixtureFactory {
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private final byte[] bytes;
    private final String sha256;
    private final String drawingPart;
    private final String connectorId;
    private final String startId;
    private final String beforeEndId;
    private final String afterEndId;

    public PipeChangeSyntheticFixtureFactory(OoxmlPackageInspector inspector, OoxmlPartEditor parts) {
        try {
            byte[] base;
            try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("Synthetic");
                sheet.createRow(0).createCell(0).setCellValue("SYNTHETIC_TEST_ONLY");
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(8, 8, 0, 2));
                workbook.setPrintArea(0, "A1:F10");
                var drawing = sheet.createDrawingPatriarch();
                var a = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 1, 1, 2, 2)); a.setText("SYN-EQ-A");
                var b = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 3, 1, 4, 2)); b.setText("SYN-EQ-B");
                var c = drawing.createSimpleShape(drawing.createAnchor(0, 0, 0, 0, 5, 5, 6, 6)); c.setText("SYN-EQ-C");
                var connector = drawing.createConnector(drawing.createAnchor(0, 0, 0, 0, 1, 1, 3, 1));
                connector.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1);
                connector.setLineStyleColor(31, 78, 121);
                connector.setLineWidth(2.25);
                workbook.write(output); base = output.toByteArray();
            }
            var originalInventory = inspector.inspectSheet(base, "Synthetic");
            List<WorkbookInventory.DrawingObject> objects = originalInventory.drawingObjects();
            String start = shapeId(objects, "SYN-EQ-A");
            String beforeEnd = shapeId(objects, "SYN-EQ-B");
            String afterEnd = shapeId(objects, "SYN-EQ-C");
            String originalConnectorId = objects.stream().filter(item -> "CONNECTOR".equals(item.objectType()))
                    .map(WorkbookInventory.DrawingObject::shapeId).findFirst().orElseThrow();
            int nextId = objects.stream().map(WorkbookInventory.DrawingObject::shapeId).mapToInt(Integer::parseInt).max().orElse(0) + 1;
            String connector = Integer.toString(nextId);
            Document xml = parse(parts.readPart(base, originalInventory.drawingPartName()));
            Element connectorElement = findConnector(xml, originalConnectorId);
            if (connectorElement == null) throw new IllegalStateException("Synthetic connector prototype is missing");
            makeConnectorVisible(connectorElement);
            Element nv = first(connectorElement, XDR, "cNvPr"); nv.setAttribute("id", connector);
            Element nonVisual = first(connectorElement, XDR, "cNvCxnSpPr");
            setConnection(nonVisual, "stCxn", start); setConnection(nonVisual, "endCxn", beforeEnd);
            WorkbookInventory.DrawingObject startShape = find(objects, start);
            WorkbookInventory.DrawingObject endShape = find(objects, beforeEnd);
            align(connectorElement, startShape, endShape);
            this.bytes = parts.replacePackagePart(base, originalInventory.drawingPartName(), serialize(xml));
            var finalSheet = inspector.inspectSheet(bytes, "Synthetic");
            var finalConnector = finalSheet.drawingObjects().stream().filter(item -> connector.equals(item.shapeId())).findFirst().orElseThrow();
            if (!start.equals(finalConnector.startConnectedShapeId()) || !beforeEnd.equals(finalConnector.endConnectedShapeId()))
                throw new IllegalStateException("Synthetic fixture endpoint identities failed verification");
            this.sha256 = sha256(bytes);
            this.drawingPart = finalSheet.drawingPartName();
            this.connectorId = connector; this.startId = start; this.beforeEndId = beforeEnd; this.afterEndId = afterEnd;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create the server-managed synthetic PIPE_CHANGE fixture", exception);
        }
    }

    public Fixture fixture() { return new Fixture(bytes.clone(), sha256, drawingPart, connectorId, startId, beforeEndId, afterEndId); }

    private String shapeId(List<WorkbookInventory.DrawingObject> objects, String label) {
        return objects.stream().filter(item -> "SHAPE".equals(item.objectType()) && label.equals(item.textContent()))
                .map(WorkbookInventory.DrawingObject::shapeId).findFirst().orElseThrow();
    }
    private WorkbookInventory.DrawingObject find(List<WorkbookInventory.DrawingObject> objects, String id) {
        return objects.stream().filter(item -> id.equals(item.shapeId())).findFirst().orElseThrow();
    }
    private Element findConnector(Document document, String id) {
        var all = document.getElementsByTagNameNS(XDR, "cxnSp");
        for (int index = 0; index < all.getLength(); index++) {
            Element item = (Element) all.item(index);
            Element nv = first(item, XDR, "cNvPr");
            if (nv != null && id.equals(nv.getAttribute("id"))) return item;
        }
        return null;
    }
    private void setConnection(Element parent, String name, String id) {
        Element connection = first(parent, A, name);
        if (connection == null) { connection = parent.getOwnerDocument().createElementNS(A, "a:" + name); parent.appendChild(connection); }
        connection.setAttribute("id", id); connection.setAttribute("idx", "0");
    }
    private void makeConnectorVisible(Element connector) {
        Element shapeProperties = first(connector, XDR, "spPr");
        Element line = first(shapeProperties, A, "ln");
        if (line == null) {
            line = connector.getOwnerDocument().createElementNS(A, "a:ln");
            shapeProperties.appendChild(line);
        }
        line.setAttribute("w", "28575");
        Element solidFill = first(line, A, "solidFill");
        if (solidFill == null) {
            solidFill = connector.getOwnerDocument().createElementNS(A, "a:solidFill");
            line.appendChild(solidFill);
        }
        Element color = first(solidFill, A, "srgbClr");
        if (color == null) {
            color = connector.getOwnerDocument().createElementNS(A, "a:srgbClr");
            solidFill.appendChild(color);
        }
        color.setAttribute("val", "1F4E79");
        Element tail = first(line, A, "tailEnd");
        if (tail == null) {
            tail = connector.getOwnerDocument().createElementNS(A, "a:tailEnd");
            line.appendChild(tail);
        }
        tail.setAttribute("type", "triangle");
        tail.setAttribute("w", "med");
        tail.setAttribute("len", "med");
    }

    private void align(Element connector, WorkbookInventory.DrawingObject start, WorkbookInventory.DrawingObject end) {
        long width1 = start.endAnchorXEmu() - start.startAnchorXEmu();
        long height1 = start.endAnchorYEmu() - start.startAnchorYEmu();
        long width2 = end.endAnchorXEmu() - end.startAnchorXEmu();
        long height2 = end.endAnchorYEmu() - end.startAnchorYEmu();
        long x1 = start.startAnchorXEmu() + width1 / 2; long y1 = start.startAnchorYEmu() + height1 / 2;
        long x2 = end.startAnchorXEmu() + width2 / 2; long y2 = end.startAnchorYEmu() + height2 / 2;
        Element anchor = (Element) connector.getParentNode();
        Element from = first(anchor, XDR, "from"); Element to = first(anchor, XDR, "to");
        coordinate(from, "col", start.startColumn()); coordinate(from, "row", start.startRow());
        coordinate(from, "colOff", width1 / 2); coordinate(from, "rowOff", height1 / 2);
        coordinate(to, "col", end.startColumn()); coordinate(to, "row", end.startRow());
        coordinate(to, "colOff", width2 / 2); coordinate(to, "rowOff", height2 / 2);
        Element xfrm = first(connector, A, "xfrm"); Element off = first(xfrm, A, "off"); Element ext = first(xfrm, A, "ext");
        off.setAttribute("x", Long.toString(Math.min(x1, x2))); off.setAttribute("y", Long.toString(Math.min(y1, y2)));
        ext.setAttribute("cx", Long.toString(Math.abs(x2 - x1))); ext.setAttribute("cy", Long.toString(Math.abs(y2 - y1)));
    }
    private void coordinate(Element parent, String name, long value) {
        Element element = first(parent, XDR, name);
        if (element == null) { element = parent.getOwnerDocument().createElementNS(XDR, "xdr:" + name); parent.appendChild(element); }
        element.setTextContent(Long.toString(value));
    }
    private Element first(Element parent, String namespace, String local) {
        if (parent == null) return null;
        var nodes = parent.getElementsByTagNameNS(namespace, local);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }
    private Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder(); builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.ByteArrayInputStream(new byte[0])));
        return builder.parse(new java.io.ByteArrayInputStream(xml));
    }
    private byte[] serialize(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); transformer.transform(new DOMSource(document), new StreamResult(output)); return output.toByteArray();
    }
    private String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public record Fixture(byte[] bytes, String sha256, String drawingPart, String connectorId,
                          String startShapeId, String beforeEndShapeId, String afterEndShapeId) {
        public Fixture { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
