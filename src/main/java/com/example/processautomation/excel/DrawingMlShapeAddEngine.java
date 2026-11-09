package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
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

/**
 * TEST_ONLY append-only DrawingML engine. It adds one literal-color rectangle to an existing drawing part.
 * It never creates package relationships/parts and never moves, rewrites, or reconnects an existing object.
 */
@Component
public class DrawingMlShapeAddEngine {
    public static final String SUPPORTED_TEMPLATE_ID = "SYNTHETIC_RECTANGLE_V1";
    public static final String REQUIRED_TEMPLATE_APPROVAL = "APPROVED_SYNTHETIC_TEST_ONLY";
    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");
    private static final Pattern COLOR = Pattern.compile("(?i)^[0-9a-f]{6}$");
    private static final Pattern EQUIPMENT_ID = Pattern.compile(
            "(?i)(?:[A-Z]{1,4}-[A-Z]{1,5}\\d{2,8}|[A-Z]{1,4}-\\d{2,8})(?:[-.][A-Z0-9]{1,8})*");
    private final OoxmlPackageInspector inspector;
    private final OoxmlPartEditor parts;
    private final EquipmentBoxGeometryLocator geometry;

    @Autowired
    public DrawingMlShapeAddEngine(OoxmlPackageInspector inspector, OoxmlPartEditor parts,
                                  EquipmentBoxGeometryLocator geometry) {
        this.inspector = Objects.requireNonNull(inspector);
        this.parts = Objects.requireNonNull(parts);
        this.geometry = Objects.requireNonNull(geometry);
    }

    public DrawingMlShapeAddEngine(OoxmlPackageInspector inspector, OoxmlPartEditor parts) {
        this(inspector, parts, new EquipmentBoxGeometryLocator());
    }

    public PreflightResult analyze(byte[] sourceBytes, ShapeAddRequest request) {
        return analyzeInternal(sourceBytes, request, null, null);
    }

    public ShapeAddResult addShapeTestOnly(byte[] sourceBytes, ShapeAddRequest request) {
        return addShape(sourceBytes, request, null, null);
    }

    ShapeAddResult addShapeForCellLayoutTestOnly(byte[] cellLayoutBytes, ShapeAddRequest request,
                                                  CellLayoutEvidence evidence) {
        return addShape(cellLayoutBytes, request, Objects.requireNonNull(evidence), evidence.cellLayoutOutputSha256());
    }

    private ShapeAddResult addShape(byte[] sourceBytes, ShapeAddRequest request, CellLayoutEvidence evidence,
                                    String expectedWorkingSha256) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(request, "request");
        PreflightResult preflight = analyzeInternal(sourceBytes, request, evidence, expectedWorkingSha256);
        if (!preflight.testOnlyReady()) {
            throw new InvalidWorkbookException("DRAWINGML_SHAPE_ADD_BLOCKED:" + String.join(";", preflight.blockers()));
        }
        String originalDrawingXml = new String(parts.readPart(sourceBytes, preflight.drawingPartName()), StandardCharsets.UTF_8);
        String anchorXml = renderAnchor(request, preflight.shapeId(), preflight.shapeName());
        String updatedDrawingXml = insertBeforeDrawingClose(originalDrawingXml, anchorXml);
        if (!updatedDrawingXml.replace(anchorXml, "").equals(originalDrawingXml)) {
            throw blocked("EXISTING_DRAWING_XML_NOT_BYTE_PRESERVED");
        }
        byte[] updatedDrawingBytes = updatedDrawingXml.getBytes(StandardCharsets.UTF_8);
        byte[] output = parts.replacePackagePart(sourceBytes, preflight.drawingPartName(), updatedDrawingBytes);
        validateOutput(sourceBytes, output, request, preflight, originalDrawingXml, anchorXml, evidence);
        return new ShapeAddResult(output, sha256(sourceBytes), sha256(output), preflight.drawingPartName(),
                preflight.shapeId(), preflight.shapeName(), "twoCellAnchor", "rect", "TEST_ONLY",
                preflight.existingObjectIds(), evidence == null ? "DRAWINGML_SHAPE_ADD_TEST_ONLY"
                        : "CELL_LAYOUT_AND_DRAWINGML_SHAPE_ADD_TEST_ONLY; no physical connection or operational approval");
    }

    private PreflightResult analyzeInternal(byte[] sourceBytes, ShapeAddRequest request, CellLayoutEvidence evidence,
                                            String expectedWorkingSha256) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(request, "request");
        List<String> blockers = new ArrayList<>();
        String actualSha = sha256(sourceBytes);
        if (!request.testOnly()) blockers.add("TEST_ONLY_REQUIRED");
        if (!validText(request.processId()) || !validText(request.sourceFileIdentifier()) || !validText(request.sheetName()))
            blockers.add("FILE_SHEET_IDENTITY_REQUIRED");
        if (request.sourceWorkbookSha256() == null || !SHA256.matcher(request.sourceWorkbookSha256()).matches())
            blockers.add("SOURCE_SHA_INVALID");
        if (evidence == null) {
            if (!actualSha.equalsIgnoreCase(request.sourceWorkbookSha256())) blockers.add("SOURCE_SHA_MISMATCH");
        } else {
            if (!Objects.equals(request.sourceWorkbookSha256(), evidence.sourceWorkbookSha256())) blockers.add("CELL_LAYOUT_SOURCE_SHA_MISMATCH");
            if (!Objects.equals(actualSha, expectedWorkingSha256)
                    || !Objects.equals(actualSha, evidence.cellLayoutOutputSha256())) blockers.add("CELL_LAYOUT_OUTPUT_SHA_MISMATCH");
            if (!Objects.equals(request.processId(), evidence.processId())
                    || !Objects.equals(request.sourceFileIdentifier(), evidence.sourceFileIdentifier())
                    || !Objects.equals(request.sheetName(), evidence.sheetName())
                    || !sameRange(request.targetBoxRange(), evidence.targetBoxRange())) blockers.add("CELL_LAYOUT_EVIDENCE_IDENTITY_MISMATCH");
        }
        if (request.shapeKind() != ShapeKind.RECTANGLE) blockers.add("SHAPE_KIND_UNSUPPORTED");
        if (!SUPPORTED_TEMPLATE_ID.equals(request.shapeTemplateId())) blockers.add("SHAPE_TEMPLATE_UNSUPPORTED");
        if (!REQUIRED_TEMPLATE_APPROVAL.equals(request.templateApprovalStatus())) blockers.add("SHAPE_TEMPLATE_NOT_APPROVED_TEST_ONLY");
        if (!validText(request.shapeName()) || request.shapeName().length() > 128) blockers.add("SHAPE_NAME_INVALID");
        if (request.style() == null || !validColor(request.style().lineColorHex())
                || request.style().lineWidthEmu() < 127 || request.style().lineWidthEmu() > 127_000
                || request.style().fillColorHex() != null && !validColor(request.style().fillColorHex()))
            blockers.add("SHAPE_STYLE_INVALID");
        if (evidence != null && request.style() != null && request.style().fillColorHex() != null)
            blockers.add("CELL_LAYOUT_SHAPE_MUST_NOT_OBSCURE_CELLS");
        if (request.includeText() && !validText(request.text())) blockers.add("EXPLICIT_SHAPE_TEXT_REQUIRED");
        if (!request.includeText() && request.text() != null && !request.text().isBlank()) blockers.add("UNREQUESTED_SHAPE_TEXT");
        if (request.text() != null && (request.text().length() > 160 || containsInvalidXmlControl(request.text())))
            blockers.add("SHAPE_TEXT_INVALID");
        if (request.from() == null || request.to() == null) blockers.add("SHAPE_ANCHOR_REQUIRED");
        CellRangeAddress target = parseRange(request.targetBoxRange(), blockers);
        if (blockers.stream().anyMatch(code -> code.equals("SOURCE_SHA_INVALID") || code.equals("TARGET_RANGE_INVALID")))
            return blockedResult(actualSha, request, null, blockers);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            if (sheet == null) {
                blockers.add("TARGET_SHEET_MISSING");
                return blockedResult(actualSha, request, target, blockers);
            }
            if (target != null && (target.getLastRow() >= 1_048_576 || target.getLastColumn() >= 16_384))
                blockers.add("TARGET_RANGE_OUT_OF_BOUNDS");
            if (target != null) {
                validateCellsAndMerges(sheet, target, evidence, blockers);
                findExistingEquipmentBoxCollisions(sheet, target, blockers);
                Rect targetBounds = cellRangeBounds(sheet, target);
                Rect requestedBounds = requestedBounds(sheet, request, blockers);
                if (targetBounds == null || requestedBounds == null || !targetBounds.contains(requestedBounds))
                    blockers.add("SHAPE_ANCHOR_OUTSIDE_TARGET_BOX");
                if (evidence != null && (requestedBounds == null || !sameRect(targetBounds, requestedBounds)))
                    blockers.add("CELL_LAYOUT_SHAPE_MUST_MATCH_BOX_BOUNDS");
                WorkbookInventory.SheetInventory sheetInventory = inspector.inspectSheet(sourceBytes, request.sheetName());
                if (sheetInventory.drawingPartName() == null) {
                    blockers.add("DRAWING_PART_CREATION_UNSUPPORTED");
                    return blockedResult(actualSha, request, target, blockers);
                }
                DrawingSnapshot snapshot = inspectDrawing(sourceBytes, sheetInventory.drawingPartName(), sheet, requestedBounds, blockers);
                if (snapshot != null) {
                    if (snapshot.names().contains(request.shapeName())) blockers.add("SHAPE_NAME_COLLISION:" + request.shapeName());
                    long nextId = snapshot.ids().values().stream().mapToLong(Long::longValue).max().orElse(0) + 1;
                    if (nextId <= 0 || nextId > Integer.MAX_VALUE) blockers.add("SHAPE_ID_EXHAUSTED");
                    if (!blockers.isEmpty()) return blockedResult(actualSha, request, target, blockers);
                    return new PreflightResult(request.processId(), request.sourceFileIdentifier(), request.sheetName(),
                            target.formatAsString(), actualSha, sheetInventory.drawingPartName(), Long.toString(nextId),
                            request.shapeName(), List.copyOf(snapshot.ids().keySet()), List.of(), true,
                            "RECTANGLE + existing drawing part only; relationships unchanged; TEST_ONLY");
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to preflight TEST_ONLY DrawingML shape", exception);
        }
        return blockedResult(actualSha, request, target, blockers);
    }

    private void findExistingEquipmentBoxCollisions(org.apache.poi.ss.usermodel.Sheet sheet,
                                                    CellRangeAddress target, List<String> blockers) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                String candidate = formatter.formatCellValue(cell).trim();
                if (!EQUIPMENT_ID.matcher(candidate).matches() || !seen.add(candidate)) continue;
                for (EquipmentBoxGeometryLocator.Box box : geometry.locateByManagementNo(sheet, candidate)) {
                    CellRangeAddress existing = new CellRangeAddress(box.firstRow() - 1, box.lastRow() - 1,
                            box.firstColumn(), box.lastColumn());
                    if (intersects(existing, target)) blockers.add("EXISTING_EQUIPMENT_BOX_COLLISION:" + candidate);
                }
            }
        }
    }

    private void validateCellsAndMerges(org.apache.poi.ss.usermodel.Sheet sheet, CellRangeAddress target,
                                        CellLayoutEvidence evidence, List<String> blockers) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int rowIndex = target.getFirstRow(); rowIndex <= target.getLastRow(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            for (int column = target.getFirstColumn(); column <= target.getLastColumn(); column++) {
                Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                String address = new CellReference(rowIndex, column).formatAsString().replace("$", "");
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                    blockers.add("FORMULA_IN_SHAPE_TARGET:" + address);
                String value = cell == null ? "" : formatter.formatCellValue(cell);
                if (evidence == null && !value.isBlank()) blockers.add("DATA_CELL_COLLISION:" + address);
                if (evidence != null && !Objects.equals(evidence.expectedValuesByCell().get(address), value))
                    blockers.add("CELL_LAYOUT_VALUE_CHANGED:" + address);
                if (evidence != null && (cell == null || cell.getCellStyle() == null || cell.getCellStyle().getIndex() == 0))
                    blockers.add("CELL_LAYOUT_STYLE_MISSING:" + address);
            }
        }
        List<String> overlapping = sheet.getMergedRegions().stream().filter(region -> intersects(region, target))
                .map(CellRangeAddress::formatAsString).toList();
        if (evidence == null && !overlapping.isEmpty()) blockers.add("MERGED_RANGE_COLLISION:" + String.join(",", overlapping));
        if (evidence != null && !new LinkedHashSet<>(overlapping).equals(new LinkedHashSet<>(evidence.expectedTargetMerges())))
            blockers.add("CELL_LAYOUT_MERGE_EVIDENCE_MISMATCH");
    }

    private DrawingSnapshot inspectDrawing(byte[] sourceBytes, String drawingPart,
                                          org.apache.poi.ss.usermodel.Sheet sheet, Rect requestedBounds,
                                          List<String> blockers) {
        byte[] xmlBytes = parts.readPart(sourceBytes, drawingPart);
        Document document = parse(xmlBytes, drawingPart);
        Element root = document.getDocumentElement();
        if (!XDR.equals(root.getNamespaceURI()) || !"wsDr".equals(root.getLocalName())) {
            blockers.add("DRAWING_ROOT_UNSUPPORTED");
            return null;
        }
        String relationshipPart = relationshipPart(drawingPart);
        byte[] relationBytes = packagePartExists(sourceBytes, relationshipPart)
                ? parts.readPart(sourceBytes, relationshipPart) : new byte[0];
        Map<String, Relationship> relationships = parseRelationships(relationBytes, drawingPart, sourceBytes, blockers);
        Map<String, Long> ids = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        int anchorCount = 0;
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element anchor)) continue;
            if (XDR.equals(anchor.getNamespaceURI()) && "extLst".equals(anchor.getLocalName())) continue;
            anchorCount++;
            String anchorType = anchor.getLocalName();
            if (!XDR.equals(anchor.getNamespaceURI()) || !("twoCellAnchor".equals(anchorType) || "oneCellAnchor".equals(anchorType))) {
                blockers.add("DRAWING_ANCHOR_UNSUPPORTED:" + anchor.getNodeName());
                continue;
            }
            Element object = null;
            int objectCount = 0;
            NodeList anchorChildren = anchor.getChildNodes();
            for (int childIndex = 0; childIndex < anchorChildren.getLength(); childIndex++) {
                Node child = anchorChildren.item(childIndex);
                if (!(child instanceof Element element)) continue;
                String local = element.getLocalName();
                if (XDR.equals(element.getNamespaceURI()) && Set.of("sp", "cxnSp", "pic").contains(local)) {
                    object = element;
                    objectCount++;
                } else if (XDR.equals(element.getNamespaceURI()) && Set.of("from", "to", "ext", "clientData", "extLst").contains(local)) {
                    // Known anchor scaffolding; extension payload is retained byte-for-byte.
                } else {
                    blockers.add("DRAWING_OBJECT_STRUCTURE_UNSUPPORTED:" + element.getNodeName());
                }
            }
            if (objectCount != 1 || object == null) {
                blockers.add("DRAWING_ANCHOR_OBJECT_COUNT_INVALID:" + anchorType);
                continue;
            }
            String objectType = object.getLocalName();
            List<Element> nonVisual = descendants(object, XDR, "cNvPr");
            if (nonVisual.size() != 1) {
                blockers.add("DRAWING_NONVISUAL_ID_UNVERIFIABLE:" + objectType);
                continue;
            }
            Element identity = nonVisual.get(0);
            String idText = identity.getAttribute("id");
            String name = identity.getAttribute("name");
            long id;
            try {
                id = Long.parseLong(idText);
                if (id < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                blockers.add("DRAWING_NONVISUAL_ID_INVALID:" + idText);
                continue;
            }
            String normalizedId = Long.toString(id);
            if (ids.putIfAbsent(normalizedId, id) != null) blockers.add("DRAWING_NONVISUAL_ID_DUPLICATE:" + normalizedId);
            if (name == null || name.isBlank()) blockers.add("DRAWING_OBJECT_NAME_MISSING:" + idText);
            else names.add(name);
            validateObjectRelationships(object, relationships, blockers);
            Rect bounds = anchorBounds(anchor, sheet, blockers);
            if (requestedBounds != null && bounds != null && requestedBounds.intersects(bounds))
                blockers.add("DRAWING_ANCHOR_COLLISION:" + objectType + ":" + idText);
        }
        if (anchorCount == 0) blockers.add("DRAWING_PART_EMPTY_OR_UNSUPPORTED");
        if (root.getElementsByTagNameNS(XDR, "cNvPr").getLength() != ids.size())
            blockers.add("DRAWING_NONVISUAL_ID_UNVERIFIABLE");
        if (root.getElementsByTagNameNS(XDR, "grpSp").getLength() > 0) blockers.add("GROUP_SHAPE_UNSUPPORTED");
        if (root.getElementsByTagNameNS(XDR, "graphicFrame").getLength() > 0) blockers.add("GRAPHIC_FRAME_UNSUPPORTED");
        if (root.getElementsByTagNameNS(XDR, "contentPart").getLength() > 0) blockers.add("CONTENT_PART_UNSUPPORTED");
        return new DrawingSnapshot(ids, names, relationshipPart);
    }

    private Map<String, Relationship> parseRelationships(byte[] xmlBytes, String drawingPart, byte[] packageBytes,
                                                          List<String> blockers) {
        Map<String, Relationship> relationships = new HashMap<>();
        if (xmlBytes.length == 0) return relationships;
        Document document = parse(xmlBytes, relationshipPart(drawingPart));
        NodeList nodes = document.getElementsByTagNameNS(P, "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String id = element.getAttribute("Id");
            String target = element.getAttribute("Target");
            String type = element.getAttribute("Type");
            if (id.isBlank() || target.isBlank() || type.isBlank() || relationships.containsKey(id)) {
                blockers.add("DRAWING_RELATIONSHIP_IDENTITY_INVALID:" + id);
                continue;
            }
            if ("External".equalsIgnoreCase(element.getAttribute("TargetMode"))) {
                blockers.add("EXTERNAL_DRAWING_RELATIONSHIP_UNSUPPORTED:" + id);
                continue;
            }
            String resolved = resolveRelationshipTarget(drawingPart, target);
            if (!packagePartExists(packageBytes, resolved)) blockers.add("DRAWING_RELATIONSHIP_TARGET_MISSING:" + id);
            relationships.put(id, new Relationship(type, target, resolved));
        }
        return relationships;
    }

    private void validateObjectRelationships(Element object, Map<String, Relationship> relationships,
                                             List<String> blockers) {
        NodeList nodes = object.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            var attributes = element.getAttributes();
            for (int attrIndex = 0; attrIndex < attributes.getLength(); attrIndex++) {
                Node attribute = attributes.item(attrIndex);
                if (!R.equals(attribute.getNamespaceURI())) continue;
                String id = attribute.getNodeValue();
                if (!relationships.containsKey(id)) blockers.add("DRAWING_RELATIONSHIP_REFERENCE_UNRESOLVED:" + id);
            }
        }
    }

    private Rect anchorBounds(Element anchor, org.apache.poi.ss.usermodel.Sheet sheet, List<String> blockers) {
        String type = anchor.getLocalName();
        try {
            Element from = directChild(anchor, XDR, "from");
            if (from == null) throw new IllegalArgumentException("from missing");
            Position start = position(from);
            long left = globalX(sheet, start.column(), start.columnOffsetEmu());
            long top = globalY(sheet, start.row(), start.rowOffsetEmu());
            long right;
            long bottom;
            if ("twoCellAnchor".equals(type)) {
                Element to = directChild(anchor, XDR, "to");
                if (to == null) throw new IllegalArgumentException("to missing");
                Position end = position(to);
                right = globalX(sheet, end.column(), end.columnOffsetEmu());
                bottom = globalY(sheet, end.row(), end.rowOffsetEmu());
            } else {
                Element extent = directChild(anchor, XDR, "ext");
                if (extent == null) throw new IllegalArgumentException("ext missing");
                right = left + positiveLong(extent.getAttribute("cx"));
                bottom = top + positiveLong(extent.getAttribute("cy"));
            }
            if (right <= left || bottom <= top) throw new IllegalArgumentException("non-positive anchor bounds");
            return new Rect(left, top, right, bottom);
        } catch (RuntimeException exception) {
            blockers.add("DRAWING_ANCHOR_COORDINATES_UNVERIFIABLE:" + type);
            return null;
        }
    }

    private Rect requestedBounds(org.apache.poi.ss.usermodel.Sheet sheet, ShapeAddRequest request,
                                 List<String> blockers) {
        try {
            Position from = request.from();
            Position to = request.to();
            validatePosition(sheet, from);
            validatePosition(sheet, to);
            long left = globalX(sheet, from.column(), from.columnOffsetEmu());
            long top = globalY(sheet, from.row(), from.rowOffsetEmu());
            long right = globalX(sheet, to.column(), to.columnOffsetEmu());
            long bottom = globalY(sheet, to.row(), to.rowOffsetEmu());
            if (right <= left || bottom <= top) throw new IllegalArgumentException("non-positive extent");
            return new Rect(left, top, right, bottom);
        } catch (RuntimeException exception) {
            blockers.add("SHAPE_ANCHOR_INVALID");
            return null;
        }
    }

    private Rect cellRangeBounds(org.apache.poi.ss.usermodel.Sheet sheet, CellRangeAddress range) {
        long left = globalX(sheet, range.getFirstColumn(), 0);
        long top = globalY(sheet, range.getFirstRow(), 0);
        long right = globalX(sheet, range.getLastColumn() + 1, 0);
        long bottom = globalY(sheet, range.getLastRow() + 1, 0);
        if (right <= left || bottom <= top) return null;
        return new Rect(left, top, right, bottom);
    }

    private void validatePosition(org.apache.poi.ss.usermodel.Sheet sheet, Position point) {
        if (point == null || point.column() < 0 || point.column() >= 16_384 || point.row() < 0 || point.row() >= 1_048_576
                || point.columnOffsetEmu() < 0 || point.rowOffsetEmu() < 0) throw new IllegalArgumentException("coordinate out of range");
        long width = Math.max(1L, Math.round(sheet.getColumnWidthInPixels(point.column()) * 9525d));
        Row row = sheet.getRow(point.row());
        long height = Math.max(1L, Math.round((row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * 12700d));
        if (point.columnOffsetEmu() >= width || point.rowOffsetEmu() >= height)
            throw new IllegalArgumentException("anchor offset exceeds cell geometry");
    }

    private long globalX(org.apache.poi.ss.usermodel.Sheet sheet, int column, long offset) {
        if (column < 0 || column > 16_384) throw new IllegalArgumentException("column out of range");
        long value = offset;
        for (int index = 0; index < column; index++) {
            long width = sheet.isColumnHidden(index) ? 0L : Math.round(sheet.getColumnWidthInPixels(index) * 9525d);
            value = Math.addExact(value, width);
        }
        return value;
    }

    private long globalY(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, long offset) {
        if (rowIndex < 0 || rowIndex > 1_048_576) throw new IllegalArgumentException("row out of range");
        long value = offset;
        for (int index = 0; index < rowIndex; index++) {
            Row row = sheet.getRow(index);
            long height = row != null && row.getZeroHeight() ? 0L : Math.round(
                    (row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * 12700d);
            value = Math.addExact(value, height);
        }
        return value;
    }

    private void validatePosition(org.w3c.dom.Element coordinate, Position point) {
        setDirectChildText(coordinate, "col", Integer.toString(point.column()));
        setDirectChildText(coordinate, "row", Integer.toString(point.row()));
        setDirectChildText(coordinate, "colOff", Long.toString(point.columnOffsetEmu()));
        setDirectChildText(coordinate, "rowOff", Long.toString(point.rowOffsetEmu()));
    }

    private Position position(Element from) {
        return new Position(integerChild(from, "col"), integerChild(from, "row"),
                longChild(from, "colOff"), longChild(from, "rowOff"));
    }

    private String renderAnchor(ShapeAddRequest request, String id, String name) {
        ShapeStyle style = request.style();
        String fill = style.fillColorHex() == null ? "<a:noFill/>"
                : "<a:solidFill><a:srgbClr val=\"" + style.fillColorHex().toUpperCase(Locale.ROOT) + "\"/></a:solidFill>";
        String text = request.includeText() ? renderText(request.text()) : "";
        return "<xdr:twoCellAnchor editAs=\"twoCell\" xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\">"
                + "<xdr:from>" + renderPoint(request.from()) + "</xdr:from>"
                + "<xdr:to>" + renderPoint(request.to()) + "</xdr:to>"
                + "<xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"" + id + "\" name=\"" + escapeAttribute(name) + "\"/>"
                + "<xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr>"
                + "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" + fill
                + "<a:ln w=\"" + style.lineWidthEmu() + "\"><a:solidFill><a:srgbClr val=\""
                + style.lineColorHex().toUpperCase(Locale.ROOT) + "\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
                + "</xdr:spPr>" + text + "</xdr:sp><xdr:clientData/></xdr:twoCellAnchor>";
    }

    private String renderPoint(Position position) {
        return "<xdr:col>" + position.column() + "</xdr:col><xdr:colOff>" + position.columnOffsetEmu()
                + "</xdr:colOff><xdr:row>" + position.row() + "</xdr:row><xdr:rowOff>"
                + position.rowOffsetEmu() + "</xdr:rowOff>";
    }

    private String renderText(String value) {
        return "<xdr:txBody><a:bodyPr wrap=\"square\" anchor=\"ctr\"/><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/>"
                + "<a:r><a:rPr lang=\"en-US\" sz=\"1100\"><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill></a:rPr>"
                + "<a:t>" + escapeText(value) + "</a:t></a:r><a:endParaRPr lang=\"en-US\"/></a:p></xdr:txBody>";
    }

    private String insertBeforeDrawingClose(String original, String addition) {
        Document document = parse(original.getBytes(StandardCharsets.UTF_8), "drawing part");
        Element root = document.getDocumentElement();
        if (!XDR.equals(root.getNamespaceURI()) || !"wsDr".equals(root.getLocalName())) throw blocked("DRAWING_ROOT_UNSUPPORTED");
        String qname = root.getPrefix() == null || root.getPrefix().isBlank() ? "wsDr" : root.getPrefix() + ":wsDr";
        Pattern close = Pattern.compile("</" + Pattern.quote(qname) + "\\s*>");
        Matcher matcher = close.matcher(original);
        if (!matcher.find()) throw blocked("DRAWING_ROOT_CLOSE_MISSING");
        int start = matcher.start();
        if (matcher.find()) throw blocked("DRAWING_ROOT_CLOSE_AMBIGUOUS");
        return original.substring(0, start) + addition + original.substring(start);
    }

    private void validateOutput(byte[] before, byte[] after, ShapeAddRequest request, PreflightResult preflight,
                                String oldDrawingXml, String addedAnchor, CellLayoutEvidence evidence) {
        String changedDrawingXml = new String(parts.readPart(after, preflight.drawingPartName()), StandardCharsets.UTF_8);
        if (!changedDrawingXml.replace(addedAnchor, "").equals(oldDrawingXml)) throw blocked("EXISTING_DRAWING_XML_CHANGED");
        if (countOccurrences(changedDrawingXml, addedAnchor) != 1) throw blocked("NEW_SHAPE_ANCHOR_COUNT_INVALID");
        if (sha256(before).equals(sha256(after))) throw blocked("OUTPUT_NOT_CHANGED");
        assertPackagePartsUnchangedExceptDrawing(before, after, preflight.drawingPartName());
        try (Workbook oldWorkbook = WorkbookFactory.create(new ByteArrayInputStream(before));
             Workbook newWorkbook = WorkbookFactory.create(new ByteArrayInputStream(after))) {
            var oldSheet = oldWorkbook.getSheet(request.sheetName());
            var newSheet = newWorkbook.getSheet(request.sheetName());
            if (oldSheet == null || newSheet == null) throw blocked("POI_REOPEN_SHEET_MISSING");
            if (oldSheet.getLastRowNum() != newSheet.getLastRowNum()
                    || oldSheet.getPhysicalNumberOfRows() != newSheet.getPhysicalNumberOfRows()) throw blocked("WORKSHEET_ROWS_CHANGED");
            for (int column = 0; column < 16_384; column++) {
                if (oldSheet.getColumnWidth(column) != newSheet.getColumnWidth(column)
                        || oldSheet.isColumnHidden(column) != newSheet.isColumnHidden(column)) throw blocked("WORKSHEET_COLUMNS_CHANGED");
                if (column > Math.max(oldSheet.getLastRowNum(), newSheet.getLastRowNum()) + 64) break;
            }
            if (!oldSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList()
                    .equals(newSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList()))
                throw blocked("WORKSHEET_MERGES_CHANGED");
            if (evidence != null) validateCellLayoutEvidence(newSheet, request, evidence);
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen and verify DrawingML TEST_ONLY output", exception);
        }
        WorkbookInventory beforeInventory = inspector.inspect(before);
        WorkbookInventory afterInventory = inspector.inspect(after);
        WorkbookInventory.SheetInventory oldSheet = sheet(beforeInventory, request.sheetName());
        WorkbookInventory.SheetInventory newSheet = sheet(afterInventory, request.sheetName());
        if (afterInventory.shapeCount() != beforeInventory.shapeCount() + 1
                || afterInventory.connectorCount() != beforeInventory.connectorCount()
                || afterInventory.pictureCount() != beforeInventory.pictureCount()
                || afterInventory.otherObjectCount() != beforeInventory.otherObjectCount())
            throw blocked("DRAWINGML_OBJECT_INVENTORY_MISMATCH");
        if (!Objects.equals(oldSheet.drawingPartName(), newSheet.drawingPartName())
                || newSheet.drawingObjects().size() != oldSheet.drawingObjects().size() + 1
                || !newSheet.drawingObjects().subList(0, oldSheet.drawingObjects().size()).equals(oldSheet.drawingObjects()))
            throw blocked("EXISTING_DRAWING_OBJECT_IDENTITY_CHANGED");
        WorkbookInventory.DrawingObject added = newSheet.drawingObjects().get(newSheet.drawingObjects().size() - 1);
        if (!Objects.equals(added.shapeId(), preflight.shapeId()) || !Objects.equals(added.objectName(), preflight.shapeName())
                || !"SHAPE".equals(added.objectType()) || !"rect".equalsIgnoreCase(added.geometryPreset()))
            throw blocked("NEW_SHAPE_INVENTORY_MISMATCH");
        validateNewShapeXml(changedDrawingXml, request, preflight);
    }

    private void validateCellLayoutEvidence(org.apache.poi.ss.usermodel.Sheet sheet, ShapeAddRequest request,
                                            CellLayoutEvidence evidence) {
        CellRangeAddress target = CellRangeAddress.valueOf(request.targetBoxRange().replace("$", ""));
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int row = target.getFirstRow(); row <= target.getLastRow(); row++) {
            for (int col = target.getFirstColumn(); col <= target.getLastColumn(); col++) {
                String address = new CellReference(row, col).formatAsString().replace("$", "");
                Cell cell = sheet.getRow(row) == null ? null
                        : sheet.getRow(row).getCell(col, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                String actual = cell == null ? "" : formatter.formatCellValue(cell);
                if (!Objects.equals(actual, evidence.expectedValuesByCell().get(address)))
                    throw blocked("CELL_LAYOUT_VALUE_CHANGED_AFTER_SHAPE_ADD:" + address);
                if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                    throw blocked("CELL_LAYOUT_STRUCTURE_CHANGED_AFTER_SHAPE_ADD:" + address);
            }
        }
        if (!sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList().equals(evidence.expectedAllMerges()))
            throw blocked("CELL_LAYOUT_MERGE_CHANGED_AFTER_SHAPE_ADD");
    }

    private void validateNewShapeXml(String drawingXml, ShapeAddRequest request, PreflightResult preflight) {
        Document document = parse(drawingXml.getBytes(StandardCharsets.UTF_8), preflight.drawingPartName());
        Element found = null;
        NodeList shapes = document.getElementsByTagNameNS(XDR, "sp");
        for (int index = 0; index < shapes.getLength(); index++) {
            Element candidate = (Element) shapes.item(index);
            Element cNvPr = firstDescendant(candidate, XDR, "cNvPr");
            if (cNvPr != null && preflight.shapeId().equals(cNvPr.getAttribute("id"))) {
                if (found != null) throw blocked("NEW_SHAPE_ID_DUPLICATE_AFTER_ADD");
                found = candidate;
            }
        }
        if (found == null) throw blocked("NEW_SHAPE_NOT_FOUND_AFTER_ADD");
        Element geom = firstDescendant(found, A, "prstGeom");
        if (geom == null || !"rect".equals(geom.getAttribute("prst"))) throw blocked("NEW_SHAPE_GEOMETRY_INVALID");
        Element line = firstDescendant(found, A, "ln");
        Element lineColor = firstDescendant(line, A, "srgbClr");
        if (line == null || !request.style().lineWidthEmuEquals(line.getAttribute("w"))
                || lineColor == null || !request.style().lineColorHex().equalsIgnoreCase(lineColor.getAttribute("val")))
            throw blocked("NEW_SHAPE_LINE_STYLE_INVALID");
        Element fill = firstDescendant(found, A, "solidFill");
        if (request.style().fillColorHex() == null) {
            if (firstDescendant(found, A, "noFill") == null) throw blocked("NEW_SHAPE_FILL_STYLE_INVALID");
        } else if (fill == null || !request.style().fillColorHex().equalsIgnoreCase(firstDescendant(fill, A, "srgbClr").getAttribute("val"))) {
            throw blocked("NEW_SHAPE_FILL_STYLE_INVALID");
        }
        Element textBody = firstDescendant(found, XDR, "txBody");
        if (request.includeText()) {
            if (textBody == null || !request.text().equals(textBody.getTextContent())) throw blocked("NEW_SHAPE_TEXT_INVALID");
        } else if (textBody != null) throw blocked("UNREQUESTED_SHAPE_TEXT_CREATED");
    }

    private void assertPackagePartsUnchangedExceptDrawing(byte[] before, byte[] after, String drawingPart) {
        Map<String, byte[]> oldParts = readPackage(before);
        Map<String, byte[]> newParts = readPackage(after);
        if (!oldParts.keySet().equals(newParts.keySet())) throw blocked("OOXML_PACKAGE_PART_SET_CHANGED");
        for (String partName : oldParts.keySet()) {
            if (!drawingPart.equals(partName) && !Arrays.equals(oldParts.get(partName), newParts.get(partName)))
                throw blocked("UNEXPECTED_OOXML_PART_CHANGED:" + partName);
        }
    }

    private Map<String, byte[]> readPackage(byte[] bytes) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    if (result.putIfAbsent(entry.getName(), input.readAllBytes()) != null)
                        throw blocked("DUPLICATE_OOXML_PART:" + entry.getName());
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to audit DrawingML package parts", exception);
        }
        return result;
    }

    private boolean packagePartExists(byte[] packageBytes, String partName) {
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes)).get()) {
            return zip.getEntry(partName) != null;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to resolve DrawingML relationship target", exception);
        }
    }

    private String resolveRelationshipTarget(String sourcePart, String target) {
        if (target.startsWith("/")) return target.substring(1);
        String parent = sourcePart.substring(0, sourcePart.lastIndexOf('/') + 1);
        String combined = parent + target;
        List<String> parts = new ArrayList<>();
        for (String segment : combined.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (parts.isEmpty()) throw blocked("DRAWING_RELATIONSHIP_TARGET_ESCAPES_PACKAGE");
                parts.remove(parts.size() - 1);
            } else parts.add(segment);
        }
        return String.join("/", parts);
    }

    private String relationshipPart(String drawingPart) {
        int slash = drawingPart.lastIndexOf('/');
        return drawingPart.substring(0, slash + 1) + "_rels/" + drawingPart.substring(slash + 1) + ".rels";
    }

    private PreflightResult blockedResult(String actualSha, ShapeAddRequest request, CellRangeAddress target,
                                         List<String> blockers) {
        return new PreflightResult(request.processId(), request.sourceFileIdentifier(), request.sheetName(),
                target == null ? request.targetBoxRange() : target.formatAsString(), actualSha, null, null,
                request.shapeName(), List.of(), List.copyOf(new LinkedHashSet<>(blockers)), false,
                "BLOCKED; no workbook bytes were returned");
    }

    private CellRangeAddress parseRange(String text, List<String> blockers) {
        try {
            if (!validText(text) || text.contains("!")) throw new IllegalArgumentException();
            return CellRangeAddress.valueOf(text.replace("$", ""));
        } catch (RuntimeException exception) {
            blockers.add("TARGET_RANGE_INVALID");
            return null;
        }
    }

    private boolean intersects(CellRangeAddress left, CellRangeAddress right) {
        return left.getLastRow() >= right.getFirstRow() && left.getFirstRow() <= right.getLastRow()
                && left.getLastColumn() >= right.getFirstColumn() && left.getFirstColumn() <= right.getLastColumn();
    }

    private boolean sameRange(String left, String right) {
        try {
            return CellRangeAddress.valueOf(left.replace("$", "")).formatAsString()
                    .equalsIgnoreCase(CellRangeAddress.valueOf(right.replace("$", "")).formatAsString());
        } catch (RuntimeException exception) { return false; }
    }

    private WorkbookInventory.SheetInventory sheet(WorkbookInventory inventory, String name) {
        return inventory.sheets().stream().filter(item -> name.equals(item.sheetName())).findFirst()
                .orElseThrow(() -> blocked("TARGET_SHEET_MISSING"));
    }

    private Document parse(byte[] bytes, String part) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Malformed or unsafe XML in " + part, exception);
        }
    }

    private List<Element> descendants(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) result.add((Element) nodes.item(index));
        return result;
    }

    private Element directChild(Element parent, String namespace, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private Element firstDescendant(Element parent, String namespace, String localName) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private int integerChild(Element parent, String name) {
        String value = childText(parent, name);
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException("negative coordinate");
        return parsed;
    }

    private long longChild(Element parent, String name) {
        String value = childText(parent, name);
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalArgumentException("negative offset");
        return parsed;
    }

    private long positiveLong(String text) {
        long value = Long.parseLong(text);
        if (value <= 0) throw new IllegalArgumentException("extent must be positive");
        return value;
    }

    private String childText(Element parent, String name) {
        Element child = directChild(parent, XDR, name);
        if (child == null || child.getTextContent().isBlank()) throw new IllegalArgumentException("coordinate missing");
        return child.getTextContent();
    }

    private void setDirectChildText(Element parent, String name, String value) {
        Element child = directChild(parent, XDR, name);
        if (child == null) {
            child = parent.getOwnerDocument().createElementNS(XDR, "xdr:" + name);
            parent.appendChild(child);
        }
        child.setTextContent(value);
    }

    private int countOccurrences(String source, String value) {
        int count = 0, offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) { count++; offset += value.length(); }
        return count;
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean validColor(String value) { return value != null && COLOR.matcher(value).matches(); }
    private boolean validText(String value) { return value != null && !value.isBlank(); }
    private boolean containsInvalidXmlControl(String value) {
        return value.codePoints().anyMatch(cp -> !(cp == 0x09 || cp == 0x0A || cp == 0x0D
                || cp >= 0x20 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD
                || cp >= 0x10000 && cp <= 0x10FFFF));
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private InvalidWorkbookException blocked(String reason) { return new InvalidWorkbookException(reason); }

    private boolean sameRect(Rect left, Rect right) {
        return left != null && right != null && left.left() == right.left() && left.top() == right.top()
                && left.right() == right.right() && left.bottom() == right.bottom();
    }

    public enum ShapeKind { RECTANGLE }

    public record Position(int column, int row, long columnOffsetEmu, long rowOffsetEmu) { }

    public record ShapeStyle(String lineColorHex, long lineWidthEmu, String fillColorHex) {
        boolean lineWidthEmuEquals(String value) {
            try { return lineWidthEmu == Long.parseLong(value); }
            catch (RuntimeException exception) { return false; }
        }
    }

    public record ShapeAddRequest(String sourceWorkbookSha256, String processId, String sourceFileIdentifier,
                                  String sheetName, String targetBoxRange, ShapeKind shapeKind,
                                  Position from, Position to, ShapeStyle style, boolean includeText, String text,
                                  String shapeName, String shapeTemplateId, String templateApprovalStatus,
                                  boolean testOnly) { }

    public record PreflightResult(String processId, String sourceFileIdentifier, String sheetName,
                                  String targetBoxRange, String actualSourceSha256, String drawingPartName,
                                  String shapeId, String shapeName, List<String> existingObjectIds,
                                  List<String> blockers, boolean testOnlyReady, String scope) {
        public PreflightResult {
            existingObjectIds = existingObjectIds == null ? List.of() : List.copyOf(existingObjectIds);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record ShapeAddResult(byte[] workbookBytes, String sourceSha256, String outputSha256,
                                 String drawingPartName, String shapeId, String shapeName,
                                 String anchorType, String geometryPreset, String status,
                                 List<String> existingObjectIds, String note) {
        public ShapeAddResult {
            workbookBytes = workbookBytes.clone();
            existingObjectIds = List.copyOf(existingObjectIds);
        }
        @Override public byte[] workbookBytes() { return workbookBytes.clone(); }
    }

    record CellLayoutEvidence(String sourceWorkbookSha256, String cellLayoutOutputSha256,
                              String processId, String sourceFileIdentifier, String sheetName,
                              String targetBoxRange, String profileId, Map<String, String> expectedValuesByCell,
                              List<String> expectedTargetMerges, List<String> expectedAllMerges) {
        CellLayoutEvidence {
            expectedValuesByCell = Map.copyOf(expectedValuesByCell);
            expectedTargetMerges = List.copyOf(expectedTargetMerges);
            expectedAllMerges = List.copyOf(expectedAllMerges);
        }
    }

    private record Rect(long left, long top, long right, long bottom) {
        boolean intersects(Rect other) {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
        }
        boolean contains(Rect other) {
            return other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom;
        }
    }
    private record Relationship(String type, String target, String resolvedTarget) { }
    private record DrawingSnapshot(Map<String, Long> ids, Set<String> names, String relationshipPartName) { }
}
