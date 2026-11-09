package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/**
 * Part-level OOXML editor. Only the named ZIP member is reserialized; all other compressed members are copied raw.
 */
@Component
public class OoxmlPartEditor {
    private static final int MAX_CELL_REFERENCE_LENGTH = 16;

    public byte[] replaceCellText(byte[] sourceWorkbook, String sheetName, String cellAddress,
                                  String expectedCurrentText, String replacementText) {
        if (cellAddress == null || cellAddress.isBlank() || cellAddress.length() > MAX_CELL_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("Invalid Excel cell reference");
        }
        if (sheetName == null || sheetName.isBlank() || replacementText == null) {
            throw new IllegalArgumentException("Sheet name and replacement text are required");
        }
        CellReference reference;
        try {
            reference = new CellReference(cellAddress);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Excel cell reference: " + cellAddress, exception);
        }
        String normalizedAddress = reference.formatAsString().replace("$", "");
        String worksheetPart = findWorksheetPart(sourceWorkbook, sheetName);
        byte[] originalWorksheetXml = readPart(sourceWorkbook, worksheetPart);
        String xml = new String(originalWorksheetXml, StandardCharsets.UTF_8);
        Pattern cellPattern = Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\"" + Pattern.quote(normalizedAddress)
                + "\")[^>]*(?<!/)>.*?</c>", Pattern.DOTALL);
        Matcher matcher = cellPattern.matcher(xml);
        if (!matcher.find()) {
            cellPattern = Pattern.compile("<c(?=[\\s>])(?=[^>]*\\br=\"" + Pattern.quote(normalizedAddress)
                    + "\")[^>]*/\\s*>", Pattern.DOTALL);
            matcher = cellPattern.matcher(xml);
            if (!matcher.find()) {
                throw new InvalidWorkbookException("Cell " + normalizedAddress + " is not present as an OOXML cell on " + sheetName);
            }
        }
        String matchedCell = matcher.group();
        if (matcher.find()) {
            throw new InvalidWorkbookException("Cell reference is ambiguous in the worksheet XML: " + normalizedAddress);
        }
        verifyCurrentText(sourceWorkbook, sheetName, normalizedAddress, expectedCurrentText);
        if (matchedCell.contains("<f")) {
            throw new InvalidWorkbookException("Formula cells must not be directly replaced by this cell-text editor");
        }
        int openTagEnd = matchedCell.indexOf('>');
        String openTag = matchedCell.substring(0, openTagEnd + 1);
        if (openTag.endsWith("/>")) openTag = openTag.substring(0, openTag.length() - 2) + ">";
        String adjustedOpenTag = openTag.replaceAll("\\s+t=\"[^\"]*\"", "");
        adjustedOpenTag = adjustedOpenTag.substring(0, adjustedOpenTag.length() - 1) + " t=\"inlineStr\">";
        String inlineText = "<is><t xml:space=\"preserve\">" + escapeXml(replacementText) + "</t></is>";
        String replacementCell = adjustedOpenTag + inlineText + "</c>";
        String updatedXml = matcher.replaceFirst(Matcher.quoteReplacement(replacementCell));
        byte[] changedWorksheetXml = updatedXml.getBytes(StandardCharsets.UTF_8);
        return replacePackagePart(sourceWorkbook, worksheetPart, changedWorksheetXml);
    }

    public byte[] readPart(byte[] packageBytes, String partName) {
        try (ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes))
                .get()) {
            ZipArchiveEntry entry = zip.getEntry(partName);
            if (entry == null) {
                throw new InvalidWorkbookException("OOXML part is missing: " + partName);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read OOXML part: " + partName, exception);
        }
    }

    public byte[] replacePackagePart(byte[] packageBytes, String targetPart, byte[] replacementBytes) {
        return replacePackageParts(packageBytes, java.util.Map.of(targetPart, replacementBytes));
    }

    public byte[] replacePackageParts(byte[] packageBytes, java.util.Map<String, byte[]> replacements) {
        if (packageBytes == null || packageBytes.length == 0 || replacements == null || replacements.isEmpty()
                || replacements.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Package bytes and non-empty replacement parts are required");
        }
        java.util.Set<String> pending = new java.util.HashSet<>(replacements.keySet());
        try (ZipFile source = ZipFile.builder()
                     .setSeekableByteChannel(new SeekableInMemoryByteChannel(packageBytes)).get();
             ByteArrayOutputStream outputBytes = new ByteArrayOutputStream(packageBytes.length + 1024);
             ZipArchiveOutputStream output = new ZipArchiveOutputStream(outputBytes)) {
            Enumeration<ZipArchiveEntry> entries = source.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                byte[] replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    if (!pending.remove(entry.getName())) {
                        throw new InvalidWorkbookException("Duplicate OOXML package part: " + entry.getName());
                    }
                    ZipArchiveEntry changedEntry = new ZipArchiveEntry(entry.getName());
                    changedEntry.setMethod(ZipArchiveEntry.DEFLATED);
                    changedEntry.setTime(entry.getTime());
                    output.putArchiveEntry(changedEntry);
                    output.write(replacement);
                    output.closeArchiveEntry();
                } else {
                    try (InputStream rawPart = source.getRawInputStream(entry)) {
                        output.addRawArchiveEntry(new ZipArchiveEntry(entry), rawPart);
                    }
                }
            }
            if (!pending.isEmpty()) {
                throw new InvalidWorkbookException("OOXML package parts are missing: " + pending);
            }
            output.finish();
            return outputBytes.toByteArray();
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to update OOXML package parts " + replacements.keySet(), exception);
        }
    }

    private String findWorksheetPart(byte[] bytes, String sheetName) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int sheetIndex = workbook.getSheetIndex(sheetName);
            if (sheetIndex < 0) {
                throw new InvalidWorkbookException("Worksheet not found: " + sheetName);
            }
            return "xl/worksheets/sheet" + (sheetIndex + 1) + ".xml";
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to locate worksheet " + sheetName, exception);
        }
    }

    private void verifyCurrentText(byte[] bytes, String sheetName, String cellAddress, String expectedText) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet(sheetName);
            CellReference reference = new CellReference(cellAddress);
            Row row = sheet.getRow(reference.getRow());
            var cell = row == null ? null : row.getCell(reference.getCol(), Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
            if (cell == null) {
                throw new InvalidWorkbookException("Cell is missing: " + sheetName + "!" + cellAddress);
            }
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                throw new InvalidWorkbookException("Formula cells must not be directly replaced by this cell-text editor");
            }
            String current = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK ? ""
                    : new DataFormatter().formatCellValue(cell, workbook.getCreationHelper().createFormulaEvaluator());
            if (!current.equals(expectedText)) {
                throw new InvalidWorkbookException("The target cell changed since it was inspected: " + sheetName
                        + "!" + cellAddress);
            }
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to verify the current target cell value", exception);
        }
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
