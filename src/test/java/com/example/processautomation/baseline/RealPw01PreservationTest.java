package com.example.processautomation.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.OoxmlPartEditor;
import com.example.processautomation.excel.WorkbookInventory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RealPw01PreservationTest {
    private static final String ORIGINAL_FILENAME = "PW-01 폐수처리공정 ★변경허가.xlsx";
    private static final String ORIGINAL_CELL_TEXT = "일반폐수 0.12톤/일";

    @Autowired
    private OoxmlPackageInspector inspector;

    @Autowired
    private OoxmlPartEditor editor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void inventoriesActualPw01SheetsDrawingAndConnectorParts() throws Exception {
        byte[] original = loadOriginalPw01();
        WorkbookInventory inventory = inspector.inspect(original);

        assertThat(inventory.sheets()).hasSize(6);
        assertThat(inventory.drawingPartCount()).isEqualTo(6);
        assertThat(inventory.shapeCount()).isEqualTo(73);
        assertThat(inventory.connectorCount()).isEqualTo(986);
        assertThat(inventory.pictureCount()).isZero();
        assertThat(inventory.explicitConnectorBindings()).isZero();
        assertThat(inventory.sheets()).allSatisfy(sheet -> assertThat(sheet.drawingPartName()).isNotBlank());
        assertThat(inventory.sheets().stream().flatMap(sheet -> sheet.drawingObjects().stream())
                .filter(object -> object.objectType().equals("CONNECTOR")))
                .allSatisfy(connector -> {
                    assertThat(connector.transformXEmu()).isNotNull();
                    assertThat(connector.transformYEmu()).isNotNull();
                    assertThat(connector.transformWidthEmu()).isNotNull();
                    assertThat(connector.transformHeightEmu()).isNotNull();
                });
        assertThat(inventory.sheets().stream().flatMap(sheet -> sheet.drawingObjects().stream())
                .filter(object -> object.objectType().equals("CONNECTOR") && object.rotation60000() != null))
                .isEmpty();
    }

    @Test
    void directEditOfRealPw01CellPreservesAllOtherOoxmlPartsAndDrawingMetadata() throws Exception {
        byte[] original = loadOriginalPw01();
        String originalHash = sha256(original);
        Map<String, byte[]> originalParts = readParts(original);
        byte[] edited = editor.replaceCellText(original, "PW-01-01", "D9", ORIGINAL_CELL_TEXT,
                "통합공정도 보존 검증용 임시값");
        Path outputPath = Path.of("target", "test-artifacts", "pw01-direct-ooxml-test.xlsx");
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, edited);
        Map<String, byte[]> editedParts = readParts(edited);

        assertThat(sha256(loadOriginalPw01())).isEqualTo(originalHash);
        assertThat(editedParts.keySet()).containsExactlyInAnyOrderElementsOf(originalParts.keySet());
        originalParts.forEach((name, bytes) -> {
            if (!name.equals("xl/worksheets/sheet1.xml")) {
                assertThat(editedParts.get(name)).as("unchanged OOXML part %s", name).containsExactly(bytes);
            }
        });

        assertCellAndSheetSettings(original, ORIGINAL_CELL_TEXT, outputPath, "통합공정도 보존 검증용 임시값");
        WorkbookInventory originalInventory = inspector.inspect(original);
        WorkbookInventory editedInventory = inspector.inspect(Files.readAllBytes(outputPath));
        assertThat(editedInventory.drawingPartCount()).isEqualTo(originalInventory.drawingPartCount());
        assertThat(editedInventory.shapeCount()).isEqualTo(originalInventory.shapeCount());
        assertThat(editedInventory.connectorCount()).isEqualTo(originalInventory.connectorCount());
        assertThat(editedInventory.pictureCount()).isEqualTo(originalInventory.pictureCount());
        for (int sheetIndex = 0; sheetIndex < originalInventory.sheets().size(); sheetIndex++) {
            WorkbookInventory.SheetInventory before = originalInventory.sheets().get(sheetIndex);
            WorkbookInventory.SheetInventory after = editedInventory.sheets().get(sheetIndex);
            assertThat(after.drawingPartName()).isEqualTo(before.drawingPartName());
            assertThat(after.drawingObjects()).containsExactlyElementsOf(before.drawingObjects());
        }
    }

    private byte[] loadOriginalPw01() {
        return jdbcTemplate.queryForObject(
                "SELECT content_bytes FROM baseline_document WHERE document_role = 'PROCESS' AND process_code = 'PW-01'",
                byte[].class);
    }

    private void assertCellAndSheetSettings(byte[] original, String originalText, Path output,
                                            String expectedText) throws Exception {
        try (Workbook before = WorkbookFactory.create(new java.io.ByteArrayInputStream(original));
             Workbook after = WorkbookFactory.create(Files.newInputStream(output))) {
            var originalSheet = before.getSheet("PW-01-01");
            var editedSheet = after.getSheet("PW-01-01");
            var originalCell = originalSheet.getRow(8).getCell(3);
            var editedCell = editedSheet.getRow(8).getCell(3);
            DataFormatter formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(originalCell)).isEqualTo(originalText);
            assertThat(formatter.formatCellValue(editedCell)).isEqualTo(expectedText);
            assertThat(editedCell.getCellStyle().getIndex()).isEqualTo(originalCell.getCellStyle().getIndex());
            assertThat(editedSheet.getNumMergedRegions()).isEqualTo(originalSheet.getNumMergedRegions());
            assertThat(editedSheet.getPrintSetup().getPaperSize()).isEqualTo(originalSheet.getPrintSetup().getPaperSize());
            assertThat(editedSheet.getPrintSetup().getScale()).isEqualTo(originalSheet.getPrintSetup().getScale());
            assertThat(editedSheet.getPrintSetup().getFitWidth()).isEqualTo(originalSheet.getPrintSetup().getFitWidth());
            assertThat(editedSheet.getPrintSetup().getFitHeight()).isEqualTo(originalSheet.getPrintSetup().getFitHeight());
            assertThat(after.getPrintArea(0)).isEqualTo(before.getPrintArea(0));
        }
    }

    private Map<String, byte[]> readParts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get()) {
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

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
