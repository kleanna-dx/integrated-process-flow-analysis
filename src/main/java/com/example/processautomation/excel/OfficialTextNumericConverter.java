package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Converts only literal, explicitly-unitized flow strings while preserving all non-target OOXML parts. */
@Component
public class OfficialTextNumericConverter {
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NUMBER = "[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+)|\\d+)(?:\\.\\d+)?";
    private static final String UNIT = "[\\p{L}㎥³%/·^0-9.-]+";
    private static final Pattern PURE_FLOW_TEXT = Pattern.compile("(?U)^(\\s*)(" + NUMBER + ")(\\s*)(" + UNIT + ")(\\s*)$");
    private static final Pattern COMPOUND_FLOW_SUFFIX = Pattern.compile("(?U)^(\\s*)(.*?)((?:" + NUMBER + "))(\\s*)(" + UNIT + ")(\\s*)$");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?U)[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+)|\\d+)(?:\\.\\d+)?");
    private static final Set<String> ALLOWED_UNITS = Set.of(
            "톤/일", "ton/day", "t/day", "kg/일", "kg/day", "㎥/일", "m³/day", "m3/day", "l/일", "l/day");

    private final OoxmlPartEditor partEditor;
    private final OoxmlPackageInspector inspector;

    public OfficialTextNumericConverter(OoxmlPartEditor partEditor, OoxmlPackageInspector inspector) {
        this.partEditor = partEditor;
        this.inspector = inspector;
    }

    public ConversionResult convert(byte[] officialWorkbook) {
        if (officialWorkbook == null || officialWorkbook.length == 0) {
            throw new InvalidWorkbookException("Official workbook is empty");
        }
        WorkbookInventory sourceInventory = inspector.inspect(officialWorkbook);
        Map<String, String> worksheetParts = new LinkedHashMap<>();
        for (WorkbookInventory.SheetInventory sheet : sourceInventory.sheets()) {
            worksheetParts.put(sheet.sheetName(), sheet.worksheetPartName());
        }
        List<ConvertedCell> converted = new ArrayList<>();
        List<CellAssessment> assessments = new ArrayList<>();
        Map<String, List<String>> sourceMerges = new LinkedHashMap<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(officialWorkbook))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (var sheet : workbook) {
                List<String> ranges = new ArrayList<>();
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                    ranges.add(sheet.getMergedRegion(i).formatAsString());
                }
                sourceMerges.put(sheet.getSheetName(), List.copyOf(ranges));
                if (!sheet.getSheetName().startsWith("PW-01-")) continue;
                for (var row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() != CellType.STRING) continue;
                        String raw = formatter.formatCellValue(cell);
                        String address = new CellReference(cell.getRowIndex(), cell.getColumnIndex()).formatAsString();
                        Matcher pure = PURE_FLOW_TEXT.matcher(raw);
                        if (pure.matches()) {
                            String unit = pure.group(4).trim();
                            BigDecimal quantity = parseQuantity(pure.group(2));
                            if (ALLOWED_UNITS.contains(normalizeUnit(unit))) {
                                String format = excelNumberFormat(pure, pure.group(2));
                                converted.add(new ConvertedCell(sheet.getSheetName(), address, raw, quantity,
                                        unit, format, cell.getCellStyle().getIndex()));
                                assessments.add(new CellAssessment(sheet.getSheetName(), address, raw,
                                        "CONVERTED", quantity, unit, "Pure numeric literal and explicitly allowed unit."));
                            } else {
                                assessments.add(new CellAssessment(sheet.getSheetName(), address, raw,
                                        "REVIEW_REQUIRED_UNSUPPORTED_UNIT", quantity, unit,
                                        "Unit is not on the explicit conversion allowlist; original cell stays text."));
                            }
                            continue;
                        }
                        Matcher compound = COMPOUND_FLOW_SUFFIX.matcher(raw);
                        if (compound.matches()) {
                            String prefix = compound.group(1) + compound.group(2);
                            BigDecimal extracted = parseQuantity(compound.group(3));
                            String unit = compound.group(5).trim();
                            boolean multipleNumbers = NUMBER_TOKEN.matcher(prefix).find();
                            boolean supportedUnit = ALLOWED_UNITS.contains(normalizeUnit(unit));
                            String status = !supportedUnit ? "REVIEW_REQUIRED_UNSUPPORTED_UNIT"
                                    : multipleNumbers ? "REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS"
                                    : "PRESERVED_COMPOUND_TEXT_REVIEW_REQUIRED";
                            String reason = !supportedUnit
                                    ? "Unit is not on the explicit conversion allowlist; original compound cell stays text."
                                    : multipleNumbers
                                    ? "Source text contains additional numeric content (e.g. concentration); original cell stays text and the trailing quantity is metadata only."
                                    : "Material name or explanatory prefix makes this a compound phrase; original cell stays text.";
                            assessments.add(new CellAssessment(sheet.getSheetName(), address, raw,
                                    status, extracted, unit, reason));
                        }
                    }
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read official workbook without recalculating formulas", exception);
        }
        Map<String, byte[]> sourceParts = readParts(officialWorkbook);
        StyleExtension styleExtension = converted.isEmpty() ? null : extendStyles(sourceParts.get("xl/styles.xml"), converted);
        Map<String, List<CellRewrite>> byWorksheet = new LinkedHashMap<>();
        for (ConvertedCell cell : converted) {
            String worksheetPart = worksheetParts.get(cell.sheetName());
            if (worksheetPart == null) throw new InvalidWorkbookException("Worksheet part is missing for " + cell.sheetName());
            int targetStyle = styleExtension.styleIndexes().get(new StyleKey(cell.sourceStyleId(), cell.numberFormat()));
            byWorksheet.computeIfAbsent(worksheetPart, ignored -> new ArrayList<>())
                    .add(new CellRewrite(cell.cellAddress(), cell.sourceText(), cell.quantity(), targetStyle));
        }
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        if (styleExtension != null) {
            replacements.put("xl/styles.xml", styleExtension.stylesXml());
            for (Map.Entry<String, List<CellRewrite>> entry : byWorksheet.entrySet()) {
                replacements.put(entry.getKey(), rewriteCells(readPart(officialWorkbook, entry.getKey()), entry.getValue()));
            }
        }
        byte[] candidate = replacements.isEmpty() ? officialWorkbook.clone() : partEditor.replacePackageParts(officialWorkbook, replacements);
        Map<String, byte[]> candidateParts = readParts(candidate);
        verifyOnlyExpectedPartsChanged(sourceParts, candidateParts, replacements.keySet());
        verifyWorksheetChanges(sourceParts, candidateParts, byWorksheet);

        WorkbookInventory candidateInventory = inspector.inspect(candidate);
        verifyDrawingInventoryPreserved(sourceInventory, candidateInventory);
        Map<String, List<String>> candidateMerges = readMergeRanges(candidate);
        if (!sourceMerges.equals(candidateMerges)) {
            throw new InvalidWorkbookException("Official merge ranges changed while converting literal numeric cells");
        }
        verifyConvertedValues(candidate, converted);
        verifyPreservedCompoundText(candidate, assessments);
        return new ConversionResult(candidate, List.copyOf(converted), List.copyOf(assessments), sourceMerges,
                sourceInventory, candidateInventory, List.copyOf(replacements.keySet()));
    }

    private StyleExtension extendStyles(byte[] stylesXml, List<ConvertedCell> cells) {
        if (stylesXml == null) throw new InvalidWorkbookException("Official workbook styles.xml is missing");
        try {
            Document document = parse(stylesXml, "xl/styles.xml");
            Element root = document.getDocumentElement();
            Element numFmts = firstDirectChild(root, "numFmts");
            if (numFmts == null) {
                numFmts = document.createElementNS(MAIN_NS, "numFmts");
                Element fonts = firstDirectChild(root, "fonts");
                if (fonts == null) throw new InvalidWorkbookException("Workbook fonts collection is missing");
                root.insertBefore(numFmts, fonts);
            }
            Element cellXfs = firstDirectChild(root, "cellXfs");
            if (cellXfs == null) throw new InvalidWorkbookException("Workbook cellXfs collection is missing");
            List<Element> numFmtElements = directChildren(numFmts, "numFmt");
            List<Element> xfs = directChildren(cellXfs, "xf");
            Map<String, Integer> formatIds = new LinkedHashMap<>();
            int nextFormatId = 163;
            for (Element element : numFmtElements) {
                int id = Integer.parseInt(element.getAttribute("numFmtId"));
                nextFormatId = Math.max(nextFormatId, id);
                formatIds.put(element.getAttribute("formatCode"), id);
            }
            int originalXfCount = xfs.size();
            Map<StyleKey, Integer> indexes = new LinkedHashMap<>();
            for (ConvertedCell cell : cells) {
                StyleKey key = new StyleKey(cell.sourceStyleId(), cell.numberFormat());
                if (indexes.containsKey(key)) continue;
                Integer formatId = formatIds.get(cell.numberFormat());
                if (formatId == null) {
                    formatId = ++nextFormatId;
                    Element format = document.createElementNS(MAIN_NS, "numFmt");
                    format.setAttribute("numFmtId", Integer.toString(formatId));
                    format.setAttribute("formatCode", cell.numberFormat());
                    numFmts.appendChild(format);
                    formatIds.put(cell.numberFormat(), formatId);
                }
                if (cell.sourceStyleId() < 0 || cell.sourceStyleId() >= originalXfCount) {
                    throw new InvalidWorkbookException("Source cell references an unknown style index: " + cell.sourceStyleId());
                }
                Element xf = (Element) document.importNode(xfs.get(cell.sourceStyleId()), true);
                xf.setAttribute("numFmtId", Integer.toString(formatId));
                xf.setAttribute("applyNumberFormat", "1");
                int styleIndex = originalXfCount + indexes.size();
                cellXfs.appendChild(xf);
                indexes.put(key, styleIndex);
            }
            numFmts.setAttribute("count", Integer.toString(directChildren(numFmts, "numFmt").size()));
            cellXfs.setAttribute("count", Integer.toString(directChildren(cellXfs, "xf").size()));
            byte[] extendedStyles = serialize(document);
            validateOriginalStylePrefixes(stylesXml, extendedStyles);
            return new StyleExtension(extendedStyles, Map.copyOf(indexes));
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to add number formats without changing original Excel styles", exception);
        }
    }

    private void validateOriginalStylePrefixes(byte[] originalXml, byte[] extendedXml) {
        try {
            Document original = parse(originalXml, "original xl/styles.xml");
            Document extended = parse(extendedXml, "extended xl/styles.xml");
            for (String collectionName : List.of("numFmts", "fonts", "fills", "borders", "cellStyleXfs", "cellXfs")) {
                Element before = firstDirectChild(original.getDocumentElement(), collectionName);
                Element after = firstDirectChild(extended.getDocumentElement(), collectionName);
                if (before == null && after != null && collectionName.equals("numFmts")) continue;
                if (before == null || after == null) {
                    if (before != after) throw new InvalidWorkbookException("Original style collection changed: " + collectionName);
                    continue;
                }
                String childName = switch (collectionName) {
                    case "numFmts" -> "numFmt";
                    case "fonts" -> "font";
                    case "fills" -> "fill";
                    case "borders" -> "border";
                    case "cellStyleXfs", "cellXfs" -> "xf";
                    default -> throw new IllegalStateException(collectionName);
                };
                List<Element> oldItems = directChildren(before, childName);
                List<Element> newItems = directChildren(after, childName);
                if (newItems.size() < oldItems.size()) throw new InvalidWorkbookException("Original styles were removed");
                for (int index = 0; index < oldItems.size(); index++) {
                    if (!oldItems.get(index).isEqualNode(newItems.get(index))) {
                        throw new InvalidWorkbookException("Original " + collectionName + " entry changed at " + index);
                    }
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify append-only styles", exception);
        }
    }

    private byte[] rewriteCells(byte[] worksheetXml, List<CellRewrite> rewrites) {
        String xml = new String(worksheetXml, StandardCharsets.UTF_8);
        for (CellRewrite rewrite : rewrites) {
            Pattern pattern = Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\""
                    + Pattern.quote(rewrite.cellAddress()) + "\")[^>]*>.*?</c>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(xml);
            if (!matcher.find()) throw new InvalidWorkbookException("Source string cell is missing: " + rewrite.cellAddress());
            String original = matcher.group();
            if (matcher.find()) throw new InvalidWorkbookException("Duplicate worksheet cell reference: " + rewrite.cellAddress());
            if (original.contains("<f") || original.contains("<extLst")) {
                throw new InvalidWorkbookException("Refusing to convert formula/extension cell " + rewrite.cellAddress());
            }
            int close = original.indexOf('>');
            String opening = original.substring(0, close + 1);
            String content = original.substring(close + 1, original.length() - 4);
            content = content.replaceAll("(?s)<(?:v|is)(?:\\s[^>]*)?>.*?</(?:v|is)>", "").trim();
            if (!content.isEmpty()) throw new InvalidWorkbookException("Unexpected cell payload at " + rewrite.cellAddress());
            opening = opening.replaceAll("\\s+t=\"[^\"]*\"", "");
            if (opening.matches("(?s).*\\bs=\"[^\"]*\".*")) {
                opening = opening.replaceAll("\\bs=\"[^\"]*\"", "s=\"" + rewrite.targetStyleId() + "\"");
            } else {
                opening = opening.substring(0, opening.length() - 1) + " s=\"" + rewrite.targetStyleId() + "\">";
            }
            String replacement = opening + "<v>" + rewrite.quantity().toPlainString() + "</v></c>";
            xml = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private void verifyOnlyExpectedPartsChanged(Map<String, byte[]> source, Map<String, byte[]> candidate,
                                                Set<String> expectedChanges) {
        if (!source.keySet().equals(candidate.keySet())) throw new InvalidWorkbookException("Candidate changed the OOXML part inventory");
        for (String name : source.keySet()) {
            if (expectedChanges.contains(name)) continue;
            if (!java.util.Arrays.equals(source.get(name), candidate.get(name))) {
                throw new InvalidWorkbookException("Unexpected OOXML part changed: " + name);
            }
        }
    }

    private void verifyWorksheetChanges(Map<String, byte[]> sourceParts, Map<String, byte[]> candidateParts,
                                        Map<String, List<CellRewrite>> rewrites) {
        for (Map.Entry<String, List<CellRewrite>> entry : rewrites.entrySet()) {
            String name = entry.getKey();
            String before = new String(sourceParts.get(name), StandardCharsets.UTF_8);
            String after = new String(candidateParts.get(name), StandardCharsets.UTF_8);
            List<String> addresses = entry.getValue().stream().map(CellRewrite::cellAddress).toList();
            if (!maskTargetCells(before, name, addresses).equals(maskTargetCells(after, name, addresses))) {
                throw new InvalidWorkbookException("A non-target worksheet cell, row, column, or sheet property changed in " + name);
            }
        }
    }

    private String maskTargetCells(String worksheetXml, String worksheetPart, List<String> addresses) {
        String masked = worksheetXml;
        for (String address : addresses) {
            Pattern pattern = Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\\\"" + Pattern.quote(address)
                    + "\\\")[^>]*>.*?</c>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(masked);
            if (!matcher.find()) throw new InvalidWorkbookException("Expected target cell missing from " + worksheetPart + ": " + address);
            if (matcher.find()) throw new InvalidWorkbookException("Duplicate target cell in " + worksheetPart + ": " + address);
            String placeholder = "<c r=\\\"" + address + "\\\" data-verified-target=\\\"1\\\"/>";
            masked = pattern.matcher(masked).replaceFirst(Matcher.quoteReplacement(placeholder));
        }
        return masked;
    }

    private void verifyDrawingInventoryPreserved(WorkbookInventory source, WorkbookInventory candidate) {
        if (source.sheets().size() != candidate.sheets().size()) throw new InvalidWorkbookException("Candidate worksheet count changed");
        for (int i = 0; i < source.sheets().size(); i++) {
            WorkbookInventory.SheetInventory before = source.sheets().get(i);
            WorkbookInventory.SheetInventory after = candidate.sheets().get(i);
            if (!before.sheetName().equals(after.sheetName())
                    || !java.util.Objects.equals(before.drawingPartName(), after.drawingPartName())
                    || !before.drawingObjects().equals(after.drawingObjects())) {
                throw new InvalidWorkbookException("Candidate changed worksheet or DrawingML inventory: " + before.sheetName());
            }
        }
    }

    private BigDecimal parseQuantity(String numberText) {
        try {
            return new BigDecimal(numberText.replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new InvalidWorkbookException("Unable to parse an explicitly formatted numeric literal: " + numberText, exception);
        }
    }

    private void verifyPreservedCompoundText(byte[] candidate, List<CellAssessment> assessments) {
        List<CellAssessment> preserved = assessments.stream()
                .filter(item -> !"CONVERTED".equals(item.status())).toList();
        if (preserved.isEmpty()) return;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(candidate))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (CellAssessment item : preserved) {
                CellReference reference = new CellReference(item.cellAddress());
                Cell cell = workbook.getSheet(item.sheetName()).getRow(reference.getRow()).getCell(reference.getCol());
                if (cell.getCellType() != CellType.STRING || !item.sourceText().equals(formatter.formatCellValue(cell))) {
                    throw new InvalidWorkbookException("Preserved compound text changed at "
                            + item.sheetName() + "!" + item.cellAddress());
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify preserved compound flow text", exception);
        }
    }

    private void verifyConvertedValues(byte[] candidate, List<ConvertedCell> cells) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(candidate))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (ConvertedCell entry : cells) {
                Cell cell = workbook.getSheet(entry.sheetName()).getRow(
                        new CellReference(entry.cellAddress()).getRow()).getCell(new CellReference(entry.cellAddress()).getCol());
                if (cell.getCellType() != CellType.NUMERIC
                        || BigDecimal.valueOf(cell.getNumericCellValue()).compareTo(entry.quantity()) != 0
                        || !entry.sourceText().equals(formatter.formatCellValue(cell))) {
                    throw new InvalidWorkbookException("Numeric value/format does not exactly reproduce official source text at "
                            + entry.sheetName() + "!" + entry.cellAddress() + ": expected='" + entry.sourceText()
                            + "', displayed='" + formatter.formatCellValue(cell) + "', format='"
                            + cell.getCellStyle().getDataFormatString() + "'");
                }
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to reopen numeric candidate for value/format verification", exception);
        }
    }

    private Map<String, List<String>> readMergeRanges(byte[] bytes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (var sheet : workbook) {
                List<String> ranges = new ArrayList<>();
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) ranges.add(sheet.getMergedRegion(i).formatAsString());
                result.put(sheet.getSheetName(), List.copyOf(ranges));
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify candidate merged ranges", exception);
        }
        return result;
    }

    private Map<String, byte[]> readParts(byte[] packageBytes) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes)).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (InputStream input = zip.getInputStream(entry)) { parts.put(entry.getName(), input.readAllBytes()); }
            }
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read candidate OOXML parts", exception);
        }
        return parts;
    }

    private byte[] readPart(byte[] packageBytes, String name) {
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes)).get()) {
            var entry = zip.getEntry(name);
            if (entry == null) throw new InvalidWorkbookException("Worksheet part is missing: " + name);
            try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read worksheet part " + name, exception);
        }
    }

    private String normalizeUnit(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static String excelNumberFormat(Matcher match, String numberText) {
        String integerText = numberText.substring(0, numberText.indexOf('.') < 0
                ? numberText.length() : numberText.indexOf('.'));
        String unsignedIntegerText = integerText.startsWith("+") || integerText.startsWith("-")
                ? integerText.substring(1) : integerText;
        String[] groups = unsignedIntegerText.split(",", -1);
        String firstGroup = groups[0];
        boolean preserveLeadingZeroes = firstGroup.length() > 1 && firstGroup.charAt(0) == '0';
        String integerFormat;
        if (groups.length > 1) {
            integerFormat = (preserveLeadingZeroes ? "0".repeat(firstGroup.length()) : "#") + ",##0";
        } else {
            int minimumDigits = preserveLeadingZeroes ? firstGroup.length() : 1;
            integerFormat = "0".repeat(minimumDigits);
        }
        int decimalPoint = numberText.indexOf('.');
        if (decimalPoint >= 0) integerFormat += "." + "0".repeat(numberText.length() - decimalPoint - 1);
        String explicitPositiveSign = numberText.startsWith("+") ? "+" : "";
        String prefix = match.group(1) + explicitPositiveSign;
        return quoteFormat(prefix) + integerFormat
                + quoteFormat(match.group(3) + match.group(4) + match.group(5));
    }

    private static String quoteFormat(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private Document parse(byte[] xml, String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    private Element firstDirectChild(Element parent, String localName) {
        for (Element child : directChildren(parent, localName)) return child;
        return null;
    }

    private List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && MAIN_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) result.add(element);
        }
        return result;
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

    private record StyleKey(int sourceStyleId, String numberFormat) { }
    private record CellRewrite(String cellAddress, String sourceText, BigDecimal quantity, int targetStyleId) { }
    private record StyleExtension(byte[] stylesXml, Map<StyleKey, Integer> styleIndexes) { }

    public record ConvertedCell(String sheetName, String cellAddress, String sourceText, BigDecimal quantity,
                                String unit, String numberFormat, int sourceStyleId) { }

    public record CellAssessment(String sheetName, String cellAddress, String sourceText, String status,
                                 BigDecimal extractedQuantity, String unit, String reason) { }

    public record ConversionResult(byte[] workbookBytes, List<ConvertedCell> cells, List<CellAssessment> assessments,
                                   Map<String, List<String>> sourceMerges,
                                   WorkbookInventory sourceInventory, WorkbookInventory candidateInventory,
                                   List<String> changedOoxmlParts) {
        public Map<String, Long> classificationCounts() {
            Map<String, Long> counts = new java.util.TreeMap<>();
            assessments.forEach(item -> counts.merge(item.status(), 1L, Long::sum));
            return java.util.Collections.unmodifiableMap(counts);
        }

        public long compoundOrUnsupportedCellCount() {
            return assessments.stream().filter(item -> !"CONVERTED".equals(item.status())).count();
        }
    }
}
