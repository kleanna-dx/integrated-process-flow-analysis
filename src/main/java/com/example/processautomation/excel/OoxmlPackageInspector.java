package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class OoxmlPackageInspector {
    private static final String SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String DRAWING_MAIN_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    public WorkbookInventory inspect(byte[] workbookBytes) {
        return inspectInternal(workbookBytes, null);
    }

    public WorkbookInventory.SheetInventory inspectSheet(byte[] workbookBytes, String sheetName) {
        Objects.requireNonNull(sheetName, "sheetName");
        if (sheetName.isBlank()) throw new IllegalArgumentException("sheetName must not be blank");
        WorkbookInventory inventory = inspectInternal(workbookBytes, sheetName);
        return inventory.sheets().stream().filter(sheet -> sheetName.equals(sheet.sheetName())).findFirst()
                .orElseThrow(() -> new InvalidWorkbookException("Target worksheet is missing: " + sheetName));
    }

    private WorkbookInventory inspectInternal(byte[] workbookBytes, String onlySheetName) {
        Objects.requireNonNull(workbookBytes, "workbookBytes");
        Document workbookDocument = parseRequired(readPackagePart(workbookBytes, "xl/workbook.xml"), "xl/workbook.xml");
        Map<String, String> workbookRelationships = readRelationships(
                parseRequired(readPackagePart(workbookBytes, "xl/_rels/workbook.xml.rels"), "xl/_rels/workbook.xml.rels"));

        List<WorkbookInventory.SheetInventory> sheets = new ArrayList<>();
        int totalShapes = 0;
        int totalConnectors = 0;
        int totalPictures = 0;
        int otherObjects = 0;
        int connectorBindings = 0;

        try (Workbook poiWorkbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            NodeList sheetNodes = workbookDocument.getElementsByTagNameNS(SPREADSHEET_NS, "sheet");
            for (int sheetOffset = 0; sheetOffset < sheetNodes.getLength(); sheetOffset++) {
                Element sheetElement = (Element) sheetNodes.item(sheetOffset);
                String sheetName = sheetElement.getAttribute("name");
                if (onlySheetName != null && !onlySheetName.equals(sheetName)) continue;
                String relationshipId = sheetElement.getAttributeNS(OFFICE_REL_NS, "id");
                String sheetPart = resolvePart("xl/workbook.xml", workbookRelationships.get(relationshipId));
                String drawingPart = null;
                List<WorkbookInventory.DrawingObject> drawingObjects = new ArrayList<>();
                List<String> textFragments = new ArrayList<>();
                int physicalRowCount = 0;
                int lastColumn = 0;
                Sheet poiSheet = poiWorkbook.getSheet(sheetName);

                if (poiSheet != null) {
                    physicalRowCount = poiSheet.getPhysicalNumberOfRows();
                    for (Row row : poiSheet) {
                        if (row.getLastCellNum() > lastColumn) {
                            lastColumn = row.getLastCellNum();
                        }
                        for (var cell : row) {
                            String value = ExcelCellText.format(cell, formatter);
                            if (!value.isBlank()) {
                                textFragments.add(value);
                            }
                        }
                    }
                }

                String sheetRels = siblingRelationshipsPart(sheetPart);
                byte[] sheetRelationshipBytes = readPackagePart(workbookBytes, sheetRels);
                if (sheetRelationshipBytes != null) {
                    Document relationshipsDocument = parseRequired(sheetRelationshipBytes, sheetRels);
                    Map<String, String> relationships = readRelationships(relationshipsDocument);
                    NodeList relationshipNodes = relationshipsDocument.getElementsByTagNameNS(PACKAGE_REL_NS, "Relationship");
                    for (int relOffset = 0; relOffset < relationshipNodes.getLength(); relOffset++) {
                        Element relationship = (Element) relationshipNodes.item(relOffset);
                        if (relationship.getAttribute("Type").endsWith("/drawing")) {
                            drawingPart = resolvePart(sheetPart, relationships.get(relationship.getAttribute("Id")));
                            break;
                        }
                    }
                }

                int sheetShapes = 0;
                int sheetConnectors = 0;
                int sheetPictures = 0;
                int sheetOtherObjects = 0;
                int sheetBindings = 0;
                byte[] drawingBytes = drawingPart == null ? null : readPackagePart(workbookBytes, drawingPart);
                if (drawingBytes != null) {
                    Document drawingDocument = parseRequired(drawingBytes, drawingPart);
                    NodeList anchors = drawingDocument.getDocumentElement().getChildNodes();
                    List<AnchoredElement> indexedAnchors = new ArrayList<>();
                    int maximumColumn = -1;
                    int maximumRow = -1;
                    for (int anchorIndex = 0; anchorIndex < anchors.getLength(); anchorIndex++) {
                        Node anchor = anchors.item(anchorIndex);
                        if (!(anchor instanceof Element anchorElement)) continue;
                        AnchorCoordinates coordinates = readAnchor(anchorElement);
                        indexedAnchors.add(new AnchoredElement(anchorElement, coordinates));
                        maximumColumn = Math.max(maximumColumn, Math.max(
                                coordinates.startColumn() == null ? -1 : coordinates.startColumn(),
                                coordinates.endColumn() == null ? -1 : coordinates.endColumn()));
                        maximumRow = Math.max(maximumRow, Math.max(
                                coordinates.startRow() == null ? -1 : coordinates.startRow(),
                                coordinates.endRow() == null ? -1 : coordinates.endRow()));
                    }
                    CoordinateIndex coordinateIndex = new CoordinateIndex(poiSheet, maximumColumn, maximumRow);
                    for (AnchoredElement indexedAnchor : indexedAnchors) {
                        Element anchorElement = indexedAnchor.element();
                        AnchorCoordinates coordinates = indexedAnchor.coordinates();
                        NodeList children = anchorElement.getChildNodes();
                        for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                            Node child = children.item(childIndex);
                            if (!(child instanceof Element objectElement) || !DRAWING_NS.equals(objectElement.getNamespaceURI())) {
                                continue;
                            }
                            String localName = objectElement.getLocalName();
                            String objectType = objectType(localName);
                            if (objectType == null) {
                                continue;
                            }
                            Element nonVisual = firstDescendant(objectElement, DRAWING_NS, "cNvPr");
                            String shapeId = nonVisual == null ? null : blankToNull(nonVisual.getAttribute("id"));
                            String objectName = nonVisual == null ? null : blankToNull(nonVisual.getAttribute("name"));
                            String text = joinTexts(objectElement, DRAWING_MAIN_NS, "t");
                            String geometry = attribute(firstDescendant(objectElement, DRAWING_MAIN_NS, "prstGeom"), "prst");
                            Element startConnection = firstDescendant(objectElement, DRAWING_MAIN_NS, "stCxn");
                            Element endConnection = firstDescendant(objectElement, DRAWING_MAIN_NS, "endCxn");
                            Element headEnd = firstDescendant(objectElement, DRAWING_MAIN_NS, "headEnd");
                            Element tailEnd = firstDescendant(objectElement, DRAWING_MAIN_NS, "tailEnd");
                            Element transform = firstDescendant(objectElement, DRAWING_MAIN_NS, "xfrm");
                            Element transformOffset = firstDescendant(transform, DRAWING_MAIN_NS, "off");
                            Element transformExtent = firstDescendant(transform, DRAWING_MAIN_NS, "ext");
                            if (startConnection != null || endConnection != null) {
                                sheetBindings++;
                            }
                            WorkbookInventory.DrawingObject drawingObject = new WorkbookInventory.DrawingObject(
                                    drawingObjects.size(), objectType, shapeId, objectName, text, geometry,
                                    coordinates.startColumn(), coordinates.startRow(), coordinates.startColumnOffset(),
                                    coordinates.startRowOffset(), coordinates.endColumn(), coordinates.endRow(),
                                    coordinates.endColumnOffset(), coordinates.endRowOffset(),
                                    attribute(startConnection, "id"), attribute(endConnection, "id"),
                                    attribute(headEnd, "type"), attribute(tailEnd, "type"),
                                    longAttribute(transformOffset, "x"), longAttribute(transformOffset, "y"),
                                    longAttribute(transformExtent, "cx"), longAttribute(transformExtent, "cy"),
                                    integerAttribute(transform, "rot"), booleanAttribute(transform, "flipH"),
                                    booleanAttribute(transform, "flipV"),
                                    coordinateIndex.x(coordinates.startColumn(), coordinates.startColumnOffset()),
                                    coordinateIndex.y(coordinates.startRow(), coordinates.startRowOffset()),
                                    coordinateIndex.x(coordinates.endColumn(), coordinates.endColumnOffset()),
                                    coordinateIndex.y(coordinates.endRow(), coordinates.endRowOffset()));
                            drawingObjects.add(drawingObject);
                            if (!text.isBlank()) {
                                textFragments.add(text);
                            }
                            switch (objectType) {
                                case "SHAPE" -> sheetShapes++;
                                case "CONNECTOR" -> sheetConnectors++;
                                case "PICTURE" -> sheetPictures++;
                                default -> sheetOtherObjects++;
                            }
                        }
                    }
                }

                totalShapes += sheetShapes;
                totalConnectors += sheetConnectors;
                totalPictures += sheetPictures;
                otherObjects += sheetOtherObjects;
                connectorBindings += sheetBindings;
                sheets.add(new WorkbookInventory.SheetInventory(
                        sheetOffset + 1, sheetName,
                        sheetElement.hasAttribute("state") ? sheetElement.getAttribute("state") : "visible",
                        physicalRowCount, lastColumn, sheetPart, drawingPart,
                        textFragments, drawingObjects));
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read workbook with Apache POI: "
                    + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()), exception);
        }

        int drawingCount = (int) sheets.stream().filter(sheet -> sheet.drawingPartName() != null).count();
        return new WorkbookInventory(sheets, drawingCount, totalShapes, totalConnectors, totalPictures,
                otherObjects, connectorBindings);
    }

    private byte[] readPackagePart(byte[] bytes, String name) {
        try (ZipFile zip = ZipFile.builder().setByteArray(bytes).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (!name.equals(entry.getName())) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
            return null;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read OOXML package part " + name, exception);
        }
    }

    private Document parseRequired(byte[] data, String name) {
        if (data == null) throw new InvalidWorkbookException("Required OOXML part is missing: " + name);
        return parseXml(data, name);
    }

    private Document parseXml(byte[] bytes, String name) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException ignored) {
                // The parser feature flags above still disable external entity access on older JAXP providers.
            }
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Malformed XML in OOXML part " + name, exception);
        }
    }

    private Map<String, String> readRelationships(Document document) {
        Map<String, String> result = new HashMap<>();
        NodeList relationships = document.getElementsByTagNameNS(PACKAGE_REL_NS, "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return result;
    }

    private String resolvePart(String sourcePart, String target) {
        if (target == null || target.isBlank()) {
            throw new InvalidWorkbookException("Missing relationship target for " + sourcePart);
        }
        String normalizedTarget = target.replace('\\', '/');
        if (normalizedTarget.startsWith("/")) {
            return normalizedTarget.substring(1);
        }
        Path sourceDirectory = Path.of(sourcePart).getParent();
        return sourceDirectory.resolve(normalizedTarget).normalize().toString().replace('\\', '/');
    }

    private String siblingRelationshipsPart(String part) {
        int separator = part.lastIndexOf('/');
        String directory = separator < 0 ? "" : part.substring(0, separator + 1);
        String filename = separator < 0 ? part : part.substring(separator + 1);
        return directory + "_rels/" + filename + ".rels";
    }

    private AnchorCoordinates readAnchor(Element anchor) {
        Element start = directChild(anchor, DRAWING_NS, "from");
        Element end = directChild(anchor, DRAWING_NS, "to");
        return new AnchorCoordinates(readCoordinate(start, "col"), readCoordinate(start, "row"),
                readLongCoordinate(start, "colOff"), readLongCoordinate(start, "rowOff"),
                readCoordinate(end, "col"), readCoordinate(end, "row"),
                readLongCoordinate(end, "colOff"), readLongCoordinate(end, "rowOff"));
    }

    private Integer readCoordinate(Element parent, String name) {
        String value = directChildText(parent, DRAWING_NS, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long readLongCoordinate(Element parent, String name) {
        String value = directChildText(parent, DRAWING_NS, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Element directChild(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private String directChildText(Element parent, String namespace, String localName) {
        Element child = directChild(parent, namespace, localName);
        return child == null ? null : child.getTextContent();
    }

    private Element firstDescendant(Element parent, String namespace, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private String joinTexts(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        List<String> values = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            String value = nodes.item(index).getTextContent();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return String.join("\n", values);
    }

    private String objectType(String localName) {
        return switch (localName) {
            case "sp" -> "SHAPE";
            case "cxnSp" -> "CONNECTOR";
            case "pic" -> "PICTURE";
            case "grpSp" -> "GROUP";
            case "graphicFrame" -> "GRAPHIC_FRAME";
            case "contentPart" -> "CONTENT_PART";
            default -> null;
        };
    }

    private String attribute(Element element, String name) {
        return element == null ? null : blankToNull(element.getAttribute(name));
    }

    private static final class CoordinateIndex {
        private final Sheet sheet;
        private final long[] xBeforeColumn;
        private final long[] yBeforeRow;

        private CoordinateIndex(Sheet sheet, int maximumColumn, int maximumRow) {
            this.sheet = sheet;
            this.xBeforeColumn = new long[Math.max(0, maximumColumn) + 1];
            this.yBeforeRow = new long[Math.max(0, maximumRow) + 1];
            if (sheet == null) return;
            for (int column = 0; column < maximumColumn; column++) {
                long width = sheet.isColumnHidden(column) ? 0L
                        : Math.round(sheet.getColumnWidthInPixels(column) * 9525d);
                xBeforeColumn[column + 1] = xBeforeColumn[column] + width;
            }
            for (int rowIndex = 0; rowIndex < maximumRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                long height = row != null && row.getZeroHeight() ? 0L : Math.round(
                        (row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * 12700d);
                yBeforeRow[rowIndex + 1] = yBeforeRow[rowIndex] + height;
            }
        }

        private Long x(Integer column, Long offset) {
            if (sheet == null || column == null || offset == null || column < 0
                    || column >= xBeforeColumn.length) return null;
            return xBeforeColumn[column] + offset;
        }

        private Long y(Integer rowIndex, Long offset) {
            if (sheet == null || rowIndex == null || offset == null || rowIndex < 0
                    || rowIndex >= yBeforeRow.length) return null;
            return yBeforeRow[rowIndex] + offset;
        }
    }

    private record AnchoredElement(Element element, AnchorCoordinates coordinates) { }


    private Long longAttribute(Element element, String name) {
        String value = attribute(element, name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer integerAttribute(Element element, String name) {
        String value = attribute(element, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanAttribute(Element element, String name) {
        String value = attribute(element, name);
        return value == null ? null : "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record AnchorCoordinates(Integer startColumn, Integer startRow, Long startColumnOffset,
                                     Long startRowOffset, Integer endColumn, Integer endRow,
                                     Long endColumnOffset, Long endRowOffset) { }
}
