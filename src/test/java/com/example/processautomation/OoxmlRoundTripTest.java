package com.example.processautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OoxmlRoundTripTest {
    @TempDir
    Path tempDir;

    @Test
    void poiCreatesReadsAndWritesXlsxValuesAndCellStyle() throws Exception {
        byte[] initialWorkbook = createWorkbook();
        byte[] rewrittenWorkbook;

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(initialWorkbook))) {
            Sheet sheet = workbook.getSheet("Equipment");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Press");
            sheet.getRow(1).getCell(1).setCellValue("Press - edited");

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                rewrittenWorkbook = output.toByteArray();
            }
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rewrittenWorkbook))) {
            Sheet sheet = workbook.getSheet("Equipment");
            Cell assetName = sheet.getRow(1).getCell(1);
            assertThat(assetName.getStringCellValue()).isEqualTo("Press - edited");
            assertThat(assetName.getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_BLUE.getIndex());
        }
    }

    @Test
    void directWorkbookXmlEditPreservesEveryOtherOoxmlPart() throws Exception {
        Path original = tempDir.resolve("original.xlsx");
        Path edited = tempDir.resolve("edited.xlsx");
        Files.write(original, createWorkbook());
        Map<String, byte[]> originalParts = readParts(original);

        editWorkbookSheetName(original, edited, "Equipment", "Equipment_Verified");
        Map<String, byte[]> editedParts = readParts(edited);

        assertThat(editedParts.get("xl/workbook.xml"))
                .as("the directly edited OOXML workbook part")
                .isNotNull();
        assertThat(new String(editedParts.get("xl/workbook.xml"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("name=\"Equipment_Verified\"");
        assertThat(editedParts.keySet()).containsExactlyInAnyOrderElementsOf(originalParts.keySet());
        originalParts.forEach((name, bytes) -> {
            if (!name.equals("xl/workbook.xml")) {
                assertThat(editedParts.get(name)).as("unchanged OOXML part %s", name).containsExactly(bytes);
            }
        });

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(edited))) {
            assertThat(workbook.getSheet("Equipment_Verified")).isNotNull();
        }
    }

    private byte[] createWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Equipment");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("asset_id");
            header.createCell(1).setCellValue("asset_name");

            Row equipment = sheet.createRow(1);
            equipment.createCell(0).setCellValue("EQ-01");
            Cell assetName = equipment.createCell(1);
            assetName.setCellValue("Press");
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            assetName.setCellStyle(style);

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void editWorkbookSheetName(Path sourcePath, Path targetPath, String current, String replacement)
            throws Exception {
        boolean editedWorkbookXml = false;
        try (ZipFile source = new ZipFile(sourcePath.toFile());
             ZipArchiveOutputStream target = new ZipArchiveOutputStream(targetPath.toFile())) {
            Enumeration<ZipArchiveEntry> entries = source.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.getName().equals("xl/workbook.xml")) {
                    String xml;
                    try (InputStream input = source.getInputStream(entry)) {
                        xml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    String oldName = "name=\"" + current + "\"";
                    assertThat(xml).contains(oldName);
                    byte[] changed = xml.replace(oldName, "name=\"" + replacement + "\"")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ZipArchiveEntry changedEntry = new ZipArchiveEntry(entry.getName());
                    target.putArchiveEntry(changedEntry);
                    target.write(changed);
                    target.closeArchiveEntry();
                    editedWorkbookXml = true;
                } else {
                    try (InputStream rawPart = source.getRawInputStream(entry)) {
                        target.addRawArchiveEntry(new ZipArchiveEntry(entry), rawPart);
                    }
                }
            }
        }
        assertThat(editedWorkbookXml).isTrue();
    }

    private Map<String, byte[]> readParts(Path file) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                try (InputStream input = zip.getInputStream(entry)) {
                    parts.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return parts;
    }
}
