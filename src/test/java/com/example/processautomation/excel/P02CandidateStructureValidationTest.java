package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.processautomation.material.EquipmentBoxProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** DB-free structural assertions for the exact P-02 working copy; never registers a Profile or edits a workbook. */
class P02CandidateStructureValidationTest {
    private static final String PROCESS = "P-02";
    private static final String SHEET = "P-02-01(TOP)";
    private static final String MANAGEMENT_NO = "I-PP21005";
    private static final String SOURCE_SHA = "28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76";
    private static final String WORKING_SHA = "c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2";

    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(
            new OoxmlPartEditor(), inspector, new EquipmentBoxGeometryLocator());
    private final EquipmentBoxProfileRepository profiles = new EquipmentBoxProfileRepository(new ObjectMapper());

    @Test
    @EnabledIfSystemProperty(named = "pilot06.p02.structure", matches = "true")
    void exactP02WorkingCopyConfirmsI89StructureAndKeepsModifyGuardsFailClosed() throws Exception {
        Path workingCopy = Path.of(System.getProperty("pilot06.p02.working-copy",
                "/home/user/uploaded_files/P-02 제지3호기 공정(수정).xlsx"));
        assertThat(Files.isRegularFile(workingCopy)).isTrue();
        byte[] workbookBytes = Files.readAllBytes(workingCopy);
        assertThat(sha256(workbookBytes)).isEqualTo(WORKING_SHA);

        var profile = profiles.findFor(PROCESS, SHEET, SOURCE_SHA).orElseThrow();
        assertThat(profile.profileId()).isEqualTo("P-02-01-TOP-V1");
        assertThat(profile.status()).isEqualTo("VERIFIED");
        assertThat(profile.evidenceLevel()).isEqualTo("WORKING_COPY_VERIFIED");
        assertThat(profile.sourceSha256()).isEqualTo(SOURCE_SHA);
        assertThat(profile.verifiedWorkingSha256()).isEqualTo(WORKING_SHA);
        assertThat(profile.fields()).containsKeys("managementNo", "equipmentTypeLabel", "equipmentName",
                "businessItemNo", "capacity", "quantity");
        assertThat(profile.fields().get("managementNo").rowOffset()).isZero();
        assertThat(profile.fields().get("managementNo").columnOffset()).isZero();
        assertThat(profile.fields().get("equipmentName").rowOffset()).isEqualTo(3);
        assertThat(profile.fields().get("equipmentName").columnOffset()).isZero();
        assertThat(profile.fields().get("equipmentName").expectedStyleId()).isEqualTo(220);
        assertThat(profile.rowHeightsPt().get(3)).isEqualTo(11.1f);

        DiagramBoxChangeEngine.BoxFieldMapping mapping = engine.mapFieldsByProfile(workbookBytes, PROCESS,
                SHEET, MANAGEMENT_NO, profile, SOURCE_SHA, WORKING_SHA);
        assertThat(mapping.boxTopLeft()).isEqualTo("I86");
        assertThat(mapping.boxBottomRight()).isEqualTo("Z93");
        assertThat(mapping.profileShaMatch()).isTrue();
        assertThat(mapping.workingCopyShaMatch()).isTrue();
        assertThat(mapping.profileFields()).containsExactlyInAnyOrder("managementNo", "equipmentTypeLabel",
                "equipmentName", "businessItemNo", "capacity", "quantity");
        assertThat(mapping.availableFields()).isEmpty();

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(SHEET);
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            var id = sheet.getRow(85).getCell(8);
            assertThat(id.getAddress().formatAsString()).isEqualTo("I86");
            assertThat(formatter.formatCellValue(id)).isEqualTo(MANAGEMENT_NO);
            assertThat(id.getCellStyle().getIndex()).isEqualTo((short) 259);

            assertEquipmentNameCell(sheet, formatter, "I63", "W-1(CHEST)");
            assertEquipmentNameCell(sheet, formatter, "I75", "131 HCC");
            assertEquipmentNameCell(sheet, formatter, "I89", "131 MULTI SORTER#1");

            var actualI89 = sheet.getRow(88).getCell(8);
            var expectedStyleExample = sheet.getRow(583).getCell(8); // actual I584 cell has Profile style 220
            assertThat(actualI89.getCellStyle().getIndex()).isEqualTo((short) 262);
            assertThat(expectedStyleExample.getCellStyle().getIndex()).isEqualTo((short) 220);
            assertThat(actualI89.getCellStyle().getFontIndexAsInt())
                    .isEqualTo(expectedStyleExample.getCellStyle().getFontIndexAsInt());
            assertThat(workbook.getFontAt(actualI89.getCellStyle().getFontIndexAsInt()).getFontName())
                    .isEqualTo("맑은 고딕");
            assertThat(workbook.getFontAt(actualI89.getCellStyle().getFontIndexAsInt()).getFontHeightInPoints())
                    .isEqualTo((short) 7);
            assertThat(actualI89.getCellStyle().getFillForegroundColor())
                    .isEqualTo(expectedStyleExample.getCellStyle().getFillForegroundColor());
            assertThat(actualI89.getCellStyle().getBorderLeft()).isEqualTo(BorderStyle.MEDIUM);
            assertThat(expectedStyleExample.getCellStyle().getBorderLeft()).isEqualTo(BorderStyle.THIN);
            assertThat(sheet.getRow(88).getHeightInPoints()).isEqualTo(12.0f);
            assertThat(profile.rowHeightsPt().get(3)).isEqualTo(11.1f);

            assertThat(profile.fields().get("managementNo").expectedStyleId()).isEqualTo(222);
            assertThat(profile.fields().get("equipmentName").expectedStyleId()).isEqualTo(220);
            assertThat(profile.fields().get("quantity").expectedStyleId()).isEqualTo(327);
            assertThat(mapping.fields().get("managementNo").cellAddress()).isEqualTo("I86");
            assertThat(mapping.fields().get("managementNo").status()).isEqualTo("REVIEW_REQUIRED");
            assertThat(mapping.fields().get("equipmentTypeLabel").cellAddress()).isEqualTo("I88");
            assertThat(mapping.fields().get("equipmentName").cellAddress()).isEqualTo("I89");
            assertThat(mapping.fields().get("equipmentName").currentText()).isEqualTo("131 MULTI SORTER#1");
            assertThat(mapping.fields().get("businessItemNo").cellAddress()).isEqualTo("I90");
            assertThat(mapping.fields().get("capacity").cellAddress()).isEqualTo("I91");
            assertThat(mapping.fields().get("quantity").cellAddress()).isEqualTo("I92");
            assertThat(mapping.fields().values()).allSatisfy(field -> {
                assertThat(field.status()).isEqualTo("REVIEW_REQUIRED");
                assertThat(field.reason()).isEqualTo("PROFILE_STYLE_MISMATCH");
            });

            List<String> mergesInTargetBox = sheet.getMergedRegions().stream()
                    .filter(range -> intersects(range, 85, 92, 8, 25))
                    .map(CellRangeAddress::formatAsString).toList();
            assertThat(mergesInTargetBox).containsExactly("V87:Z93");
            assertThat(profile.mergedRanges()).contains("U582:Z588");
            var actualMergeAnchor = sheet.getRow(86).getCell(21);
            assertThat(actualMergeAnchor.getAddress().formatAsString()).isEqualTo("V87");
            assertThat(formatter.formatCellValue(actualMergeAnchor)).contains("폐수", "W-220116");
            CellRangeAddress actualMerge = CellRangeAddress.valueOf(mergesInTargetBox.get(0));
            CellRangeAddress profileMerge = CellRangeAddress.valueOf("U582:Z588");
            CellRangeAddress profileTemplate = CellRangeAddress.valueOf(profile.templateRange());
            int actualBoxTopRow = new org.apache.poi.ss.util.CellReference("I86").getRow();
            int actualBoxLeftColumn = new org.apache.poi.ss.util.CellReference("I86").getCol();
            int profileBoxTopRow = new org.apache.poi.ss.util.CellReference("I581").getRow();
            int profileBoxLeftColumn = new org.apache.poi.ss.util.CellReference("I581").getCol();
            assertThat(actualMerge.getLastRow() - actualMerge.getFirstRow() + 1).isEqualTo(7);
            assertThat(actualMerge.getLastColumn() - actualMerge.getFirstColumn() + 1).isEqualTo(5);
            assertThat(profileMerge.getLastRow() - profileMerge.getFirstRow() + 1).isEqualTo(7);
            assertThat(profileMerge.getLastColumn() - profileMerge.getFirstColumn() + 1).isEqualTo(6);
            assertThat(actualMerge.getFirstRow() - actualBoxTopRow)
                    .isEqualTo(profileMerge.getFirstRow() - profileBoxTopRow);
            assertThat(actualMerge.getLastRow() - actualBoxTopRow)
                    .isEqualTo(profileMerge.getLastRow() - profileBoxTopRow);
            assertThat(actualMerge.getFirstColumn() - actualBoxLeftColumn)
                    .isEqualTo(profileMerge.getFirstColumn() - profileBoxLeftColumn + 1);
            assertThat(actualMerge.getLastColumn() - actualBoxLeftColumn)
                    .isEqualTo(profileMerge.getLastColumn() - profileBoxLeftColumn);
            assertThat(profileTemplate.getFirstRow()).isEqualTo(profileBoxTopRow);
            assertThat(sheet.getMergedRegions().stream().noneMatch(range -> contains(range, 88, 8))).isTrue();
        }

        var inventory = inspector.inspectSheet(workbookBytes, SHEET);
        assertThat(inventory.drawingObjects()).isNotEmpty();
        assertThat(inventory.drawingObjects()).allSatisfy(object -> {
            assertThat(object.startRow()).as("drawing start row: %s", object.shapeId()).isNotNull();
            assertThat(object.endRow()).as("drawing end row: %s", object.shapeId()).isNotNull();
            assertThat(object.startColumn()).as("drawing start column: %s", object.shapeId()).isNotNull();
            assertThat(object.endColumn()).as("drawing end column: %s", object.shapeId()).isNotNull();
        });
        assertThat(overlapping(inventory.drawingObjects(), 88, 8)).isEmpty();
        assertThat(overlapping(inventory.drawingObjects(), 62, 8)).isEmpty();
        assertThat(overlapping(inventory.drawingObjects(), 74, 8)).isEmpty();

        var targetBoxObjects = overlapping(inventory.drawingObjects(), 85, 92, 8, 25);
        assertThat(targetBoxObjects).extracting(WorkbookInventory.DrawingObject::shapeId).contains("19");
        var boundaryConnector = targetBoxObjects.stream().filter(object -> "19".equals(object.shapeId()))
                .findFirst().orElseThrow();
        assertThat(boundaryConnector.objectIndex()).isEqualTo(184);
        assertThat(boundaryConnector.objectName()).isEqualTo("직선 화살표 연결선 18");
        assertThat(boundaryConnector.objectType()).isEqualTo("CONNECTOR");
        assertThat(boundaryConnector.startColumn()).isEqualTo(15);
        assertThat(boundaryConnector.startColumnOffset()).isZero();
        assertThat(boundaryConnector.startRow()).isEqualTo(84);
        assertThat(boundaryConnector.startRowOffset()).isEqualTo(9_525L);
        assertThat(boundaryConnector.endColumn()).isEqualTo(15);
        assertThat(boundaryConnector.endColumnOffset()).isZero();
        assertThat(boundaryConnector.endRow()).isEqualTo(85);
        assertThat(boundaryConnector.endRowOffset()).isZero();
        assertThat(boundaryConnector.transformXEmu()).isEqualTo(3_067_050L);
        assertThat(boundaryConnector.transformYEmu()).isEqualTo(11_591_925L);
        assertThat(boundaryConnector.transformWidthEmu()).isZero();
        assertThat(boundaryConnector.transformHeightEmu()).isEqualTo(142_875L);
        assertThat(boundaryConnector.headArrowType()).isNull();
        assertThat(boundaryConnector.tailArrowType()).isEqualTo("triangle");
        assertThat(boundaryConnector.startConnectedShapeId()).isNull();
        assertThat(boundaryConnector.endConnectedShapeId()).isNull();
        assertThat(overlapping(inventory.drawingObjects(), 83, 93, 7, 26))
                .extracting(WorkbookInventory.DrawingObject::shapeId)
                .containsExactlyInAnyOrder("69", "205", "7", "19");
        var adjacentConnectors = overlapping(inventory.drawingObjects(), 83, 93, 7, 26);
        assertThat(adjacentConnectors).allSatisfy(object -> {
            assertThat(object.objectType()).isEqualTo("CONNECTOR");
            assertThat(object.startConnectedShapeId()).isNull();
            assertThat(object.endConnectedShapeId()).isNull();
        });
        var precedingConnector = adjacentConnectors.stream().filter(object -> "205".equals(object.shapeId()))
                .findFirst().orElseThrow();
        assertThat(precedingConnector.objectName()).isEqualTo("직선 화살표 연결선 204");
        assertThat(precedingConnector.startColumn()).isEqualTo(15);
        assertThat(precedingConnector.startRow()).isEqualTo(83);
        assertThat(precedingConnector.endColumn()).isEqualTo(15);
        assertThat(precedingConnector.endRow()).isEqualTo(83);
        assertThat(precedingConnector.endRowOffset()).isEqualTo(142_875L);
        assertThat(precedingConnector.tailArrowType()).isEqualTo("triangle");

        var otherEquipmentMapping = engine.mapFieldsByProfile(workbookBytes, PROCESS, SHEET, "I-PP21039",
                profile, SOURCE_SHA, WORKING_SHA);
        assertThat(otherEquipmentMapping.fields().get("equipmentName").cellAddress()).isEqualTo("I584");
        assertThat(otherEquipmentMapping.fields().get("equipmentName").status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(otherEquipmentMapping.fields().get("equipmentName").reason()).isEqualTo("DRAWINGML_FIELD_OVERLAP");
        var connector109 = inventory.drawingObjects().stream().filter(object -> "109".equals(object.shapeId()))
                .findFirst().orElseThrow();
        assertThat(connector109.objectType()).isEqualTo("CONNECTOR");
        assertThat(connector109.startRow()).isEqualTo(583);
        assertThat(connector109.endRow()).isEqualTo(583);
        assertThat(connector109.startColumn()).isEqualTo(2);
        assertThat(connector109.endColumn()).isEqualTo(8);

        assertThatThrownBy(() -> engine.modifyProfileFieldsTestOnly(workbookBytes, PROCESS, SHEET,
                MANAGEMENT_NO, profile, SOURCE_SHA, WORKING_SHA,
                Map.of("equipmentName", "131 MULTI SORTER#1"), Map.of("equipmentName", "TEST_ONLY replacement")))
                .isInstanceOf(InvalidWorkbookException.class)
                .hasMessageContaining("PROFILE_MODIFY_FIELD_SET_INVALID");
        assertThatThrownBy(() -> engine.mapFieldsByProfile(workbookBytes, PROCESS, SHEET, MANAGEMENT_NO,
                profile, SOURCE_SHA, "0".repeat(64)))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_WORKING_SHA_MISMATCH");
        assertThatThrownBy(() -> engine.mapFieldsByProfile(workbookBytes, PROCESS, SHEET, MANAGEMENT_NO,
                profile, "0".repeat(64), WORKING_SHA))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> engine.mapFieldsByProfile(workbookBytes, "P-01", SHEET, MANAGEMENT_NO,
                profile, SOURCE_SHA, WORKING_SHA))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("PROFILE_IDENTITY_MISMATCH");
    }

    private void assertEquipmentNameCell(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter,
                                         String address, String expectedText) {
        var cell = sheet.getRow(new org.apache.poi.ss.util.CellReference(address).getRow())
                .getCell(new org.apache.poi.ss.util.CellReference(address).getCol());
        assertThat(formatter.formatCellValue(cell)).isEqualTo(expectedText);
        assertThat(cell.getCellStyle().getIndex()).isEqualTo((short) 262);
        assertThat(cell.getCellStyle().getBorderLeft()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(sheet.getRow(cell.getRowIndex()).getHeightInPoints()).isEqualTo(12.0f);
        assertThat(sheet.getMergedRegions().stream().noneMatch(range -> contains(range,
                cell.getRowIndex(), cell.getColumnIndex()))).isTrue();
    }

    private List<WorkbookInventory.DrawingObject> overlapping(List<WorkbookInventory.DrawingObject> objects,
                                                                int row, int column) {
        return objects.stream().filter(object -> overlaps(object, row, column)).toList();
    }

    private List<WorkbookInventory.DrawingObject> overlapping(List<WorkbookInventory.DrawingObject> objects,
                                                                int firstRow, int lastRow,
                                                                int firstColumn, int lastColumn) {
        return objects.stream().filter(object -> object.startRow() <= lastRow && object.endRow() >= firstRow
                && object.startColumn() <= lastColumn && object.endColumn() >= firstColumn).toList();
    }

    private boolean overlaps(WorkbookInventory.DrawingObject object, int row, int column) {
        return object.startRow() <= row && object.endRow() >= row
                && object.startColumn() <= column && object.endColumn() >= column;
    }

    private boolean contains(CellRangeAddress range, int row, int column) {
        return row >= range.getFirstRow() && row <= range.getLastRow()
                && column >= range.getFirstColumn() && column <= range.getLastColumn();
    }

    private boolean intersects(CellRangeAddress range, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        return range.getFirstRow() <= lastRow && range.getLastRow() >= firstRow
                && range.getFirstColumn() <= lastColumn && range.getLastColumn() >= firstColumn;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
