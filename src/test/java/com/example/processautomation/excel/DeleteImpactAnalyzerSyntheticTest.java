package com.example.processautomation.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DeleteImpactAnalyzerSyntheticTest {
    private static final String PROCESS = "SYNTH-DELETE";
    private static final String ID = "SYNTH-DELETE-01";
    private static final String POLICY_BOX_ONLY = "BOX_ONLY_SYNTHETIC_TEST_ONLY";
    private static final String POLICY_MERGES = "BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY";
    private final EquipmentBoxGeometryLocator geometry = new EquipmentBoxGeometryLocator();
    private final OoxmlPackageInspector inspector = new OoxmlPackageInspector();
    private final OoxmlPartEditor parts = new OoxmlPartEditor();
    private final DiagramBoxChangeEngine engine = new DiagramBoxChangeEngine(parts, inspector, geometry);
    private final ProcessDiagramStructureAnalyzer structure = new ProcessDiagramStructureAnalyzer(inspector,
            new Pw0105EquipmentInsertionEngine(parts, inspector));
    private final DeleteImpactAnalyzer analyzer = new DeleteImpactAnalyzer(geometry, structure, inspector, engine);

    @Test
    void eightAndSeventeenRowSyntheticBoxesDeleteWithoutChangingUnrequestedPackageParts() throws Exception {
        Path artifactRoot = Files.createDirectories(Path.of("validation-results", "2026-11-02-step8-1-delete"));
        Path runDirectory = Files.createTempDirectory(artifactRoot, "run-");
        List<String> manifest = new ArrayList<>();

        for (int height : List.of(8, 17)) {
            Fixture fixture = fixture(height, height == 17, Drawing.NONE, false, false);
            EquipmentBoxProfile profile = profile(fixture, height == 17 ? POLICY_MERGES : POLICY_BOX_ONLY);
            DeleteImpactAnalyzer.Assessment assessment = analyze(fixture, profile);
            assertThat(assessment.status()).as("DELETE blockers for %d-row fixture: %s; printArea=%s profilePrintArea=%s",
                    height, assessment.blockers(), assessment.printArea(), profile.printArea())
                    .isEqualTo("SAFE_BOX_ONLY_TEST_ONLY");
            assertThat(assessment.automaticTestOnlyDeleteAllowed()).isTrue();
            assertThat(assessment.connectionEvidenceStatus()).isEqualTo("NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
            assertThat(assessment.materialBalanceReviewRequired()).isTrue();
            assertThat(assessment.analysisFingerprint()).matches("[0-9a-f]{64}");
            assertThat(analyze(fixture, profile).analysisFingerprint()).isEqualTo(assessment.analysisFingerprint());

            DiagramBoxChangeEngine.ChangeResult result = engine.deleteBoxTestOnly(fixture.workingCopy(), assessment);
            assertThat(result.operation()).isEqualTo("DELETE");
            assertThat(result.scenarioStatus()).isEqualTo("TEST_ONLY");
            assertThat(result.sourceSha256()).isEqualTo(fixture.workingSha256());
            assertThat(result.outputSha256()).isEqualTo(sha256(result.workbookBytes()));
            assertOutput(fixture, result.workbookBytes(), height == 17 ? 2 : 0);
            assertPackageDeltaOnlyWorksheet(fixture.workingCopy(), result.workbookBytes(), fixture.sheetName());
            assertZipAndPoi(result.workbookBytes());

            String stem = "SYNTHETIC_TEST_ONLY_DELETE_" + height + "_ROW";
            writeArtifact(runDirectory, manifest, stem + "_BASELINE.xlsx", fixture.baseline(), "SYNTHETIC_BASELINE_NOT_OFFICIAL");
            writeArtifact(runDirectory, manifest, stem + "_USER_WORKING_COPY.xlsx", fixture.workingCopy(), "SYNTHETIC_USER_WORKING_COPY");
            writeArtifact(runDirectory, manifest, stem + "_OUTPUT.xlsx", result.workbookBytes(), "TEST_ONLY_REVIEW_REQUIRED");
        }
        Path sums = runDirectory.resolve("SHA256SUMS.tsv");
        Files.writeString(sums, "artifact\tsha256\tbytes\tclassification\n" + String.join("\n", manifest) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        verifyManifest(runDirectory, manifest);
        assertThat(manifest).hasSize(6);
    }

    @Test
    void analyzerBlocksCrossBoundaryMergeAdjacentDrawingAndBoundOrCrossingConnectors() throws Exception {
        Fixture crossMerge = fixture(8, false, Drawing.NONE, false, false);
        crossMerge = addMerge(crossMerge, "Z8:AB8");
        var mergeAssessment = analyze(crossMerge, profile(crossMerge, POLICY_BOX_ONLY));
        assertThat(mergeAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(mergeAssessment.blockers()).anyMatch(value -> value.startsWith("MERGE_CROSSES_DELETE_BOX_BOUNDARY:"));
        Fixture finalCrossMerge = crossMerge;
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(finalCrossMerge.workingCopy(), finalCrossMerge.sheetName(), ID))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("MERGE_CROSSES_BOX_BOUNDARY");

        Fixture adjacent = fixture(8, false, Drawing.ADJACENT_SHAPE, false, false);
        var adjacentAssessment = analyze(adjacent, profile(adjacent, POLICY_BOX_ONLY));
        assertThat(adjacentAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(adjacentAssessment.blockers()).anyMatch(value -> value.startsWith("DRAWINGML_WITHIN_DELETE_GUARD_BAND:"));
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(adjacent.workingCopy(), adjacent.sheetName(), ID))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("DRAWINGML_DELETE_REVIEW_REQUIRED");

        Fixture remoteBound = fixture(8, false, Drawing.REMOTE_BOUND_CONNECTOR, false, false);
        var boundAssessment = analyze(remoteBound, profile(remoteBound, POLICY_BOX_ONLY));
        assertThat(boundAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(boundAssessment.blockers()).anyMatch(value -> value.startsWith("BOUND_CONNECTOR_RELATIONSHIP_UNVERIFIED:"));
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(remoteBound.workingCopy(), remoteBound.sheetName(), ID))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("BOUND_CONNECTOR_DELETE_REVIEW_REQUIRED");

        Fixture crossingConnector = fixture(8, false, Drawing.CROSSING_CONNECTOR, false, false);
        var crossingAssessment = analyze(crossingConnector, profile(crossingConnector, POLICY_BOX_ONLY));
        assertThat(crossingAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(crossingAssessment.blockers()).anyMatch(value -> value.startsWith("CONNECTOR_INTERSECTS_DELETE_BOX:"));
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(crossingConnector.workingCopy(), crossingConnector.sheetName(), ID))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("DRAWINGML_DELETE_REVIEW_REQUIRED");
    }

    @Test
    void duplicateIdWrongShaUnapprovedProfileAndCellChangesBlockOrInvalidateFingerprint() throws Exception {
        Fixture duplicate = fixture(8, false, Drawing.NONE, true, false);
        var duplicateAssessment = analyze(duplicate, profile(duplicate, POLICY_BOX_ONLY));
        assertThat(duplicateAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(duplicateAssessment.blockers()).contains("EQUIPMENT_BOX_NOT_UNIQUE");
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(duplicate.workingCopy(), duplicate.sheetName(), ID))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("EQUIPMENT_BOX_NOT_UNIQUE");

        Fixture fixture = fixture(8, false, Drawing.NONE, false, false);
        EquipmentBoxProfile permitted = profile(fixture, POLICY_BOX_ONLY);
        var safe = analyze(fixture, permitted);
        var wrongWorkingSha = analyzer.analyze(fixture.workingCopy(), PROCESS, fixture.sheetName(), ID,
                permitted, fixture.sourceSha256(), "f".repeat(64));
        assertThat(wrongWorkingSha.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(wrongWorkingSha.blockers()).contains("WORKING_COPY_SHA_MISMATCH");
        assertThat(wrongWorkingSha.analysisFingerprint()).isNotEqualTo(safe.analysisFingerprint());
        assertThatThrownBy(() -> engine.deleteBoxTestOnly(fixture.workingCopy(), wrongWorkingSha))
                .isInstanceOf(InvalidWorkbookException.class).hasMessageContaining("DELETE_ASSESSMENT_REQUIRED");

        var wrongSourceSha = analyzer.analyze(fixture.workingCopy(), PROCESS, fixture.sheetName(), ID,
                permitted, "0".repeat(64), fixture.workingSha256());
        assertThat(wrongSourceSha.status()).isEqualTo("UNSUPPORTED");
        assertThat(wrongSourceSha.blockers()).contains("PROFILE_PROCESS_SHEET_OR_SOURCE_SHA_MISMATCH");

        var reviewProfile = copyProfile(permitted, "REVIEW_REQUIRED", POLICY_BOX_ONLY);
        var reviewAssessment = analyze(fixture, reviewProfile);
        assertThat(reviewAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reviewAssessment.blockers()).contains("PROFILE_STATUS_NOT_VERIFIED");
        assertThat(reviewAssessment.analysisFingerprint()).isNotEqualTo(safe.analysisFingerprint());

        var realPolicyAssessment = analyze(fixture, copyProfile(permitted, "VERIFIED", "REVIEW_REQUIRED"));
        assertThat(realPolicyAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(realPolicyAssessment.blockers()).contains("PROFILE_DELETE_POLICY_NOT_EXPLICITLY_AUTHORIZED");
        assertThat(realPolicyAssessment.automaticTestOnlyDeleteAllowed()).isFalse();

        Fixture changed = changeCell(fixture, "J4", "Edited after assessment");
        var changedAssessment = analyzer.analyze(changed.workingCopy(), PROCESS, changed.sheetName(), ID,
                permitted, changed.sourceSha256(), changed.workingSha256());
        assertThat(changedAssessment.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(changedAssessment.analysisFingerprint()).isNotEqualTo(safe.analysisFingerprint());
    }

    @Test
    void remoteUnboundDrawingIsPreservedAndFingerprintTracksItsMetadata() throws Exception {
        Fixture fixture = fixture(8, false, Drawing.REMOTE_SHAPE, false, false);
        EquipmentBoxProfile profile = profile(fixture, POLICY_BOX_ONLY);
        var assessment = analyze(fixture, profile);
        assertThat(assessment.status()).isEqualTo("SAFE_BOX_ONLY_TEST_ONLY");
        assertThat(assessment.drawingObjectsToPreserve()).hasSize(1);
        assertThat(assessment.drawingObjectsAnalyzed()).hasSize(1);
        var result = engine.deleteBoxTestOnly(fixture.workingCopy(), fixture.sheetName(), ID);
        assertThat(inspector.inspectSheet(result.workbookBytes(), fixture.sheetName()).drawingObjects())
                .isEqualTo(inspector.inspectSheet(fixture.workingCopy(), fixture.sheetName()).drawingObjects());
        assertThat(sha256(parts.readPart(result.workbookBytes(), "xl/drawings/drawing1.xml")))
                .isEqualTo(sha256(parts.readPart(fixture.workingCopy(), "xl/drawings/drawing1.xml")));
    }

    private DeleteImpactAnalyzer.Assessment analyze(Fixture fixture, EquipmentBoxProfile profile) {
        return analyzer.analyze(fixture.workingCopy(), PROCESS, fixture.sheetName(), ID, profile,
                fixture.sourceSha256(), fixture.workingSha256());
    }

    private Fixture fixture(int height, boolean internalMerges, Drawing drawing, boolean duplicateId, boolean boundaryMerge)
            throws Exception {
        String sheetName = height == 17 ? "Synthetic-17-row" : "Synthetic-8-row";
        byte[] baseline;
        byte[] working;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            int[] columnWidths = new int[40];
            for (int column = 0; column < columnWidths.length; column++) {
                double width = column >= 9 && column <= 26
                        ? switch (org.apache.poi.ss.util.CellReference.convertNumToColString(column)) {
                            case "J", "K", "X" -> 1.375d;
                            case "W", "AA" -> 0.875d;
                            default -> 13d;
                        }
                        : 9.5d;
                columnWidths[column] = (int) Math.round(width * 256d);
                sheet.setColumnWidth(column, columnWidths[column]);
            }
            var font = workbook.createFont();
            font.setFontName("맑은 고딕");
            font.setFontHeightInPoints((short) 8);
            font.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
            var text = workbook.createCellStyle();
            text.setFont(font);
            text.setBorderLeft(BorderStyle.THIN);
            text.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            text.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            var right = workbook.createCellStyle(); right.cloneStyleFrom(text); right.setBorderRight(BorderStyle.THIN);
            var bottom = workbook.createCellStyle(); bottom.cloneStyleFrom(text); bottom.setBorderBottom(BorderStyle.THIN);
            var corner = workbook.createCellStyle(); corner.cloneStyleFrom(text);
            corner.setBorderRight(BorderStyle.THIN); corner.setBorderBottom(BorderStyle.THIN);
            fillBox(sheet, 0, height, ID, text, right, bottom, corner);
            for (int row = 0; row < height; row++) sheet.getRow(row).setHeightInPoints(height == 17 ? 13.5f : 12f);
            var outside = sheet.createRow(40).createCell(0);
            outside.setCellValue("SYNTHETIC_TEST_ONLY_BASELINE");
            outside.setCellStyle(text);
            sheet.getRow(1).createCell(1).setCellValue("OUTSIDE_MERGE");
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("B2:C2"));
            if (internalMerges) {
                sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("M2:N2"));
                sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("M8:N8"));
            }
            if (boundaryMerge) sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("Z8:AB8"));
            if (duplicateId) fillBox(sheet, 50, height, ID, text, right, bottom, corner);
            if (drawing != Drawing.NONE) addDrawing(sheet, drawing);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.LETTER_PAPERSIZE);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            workbook.setPrintArea(0, "A1:AN60");
            workbook.write(output);
            working = output.toByteArray();
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(working));
             var output = new ByteArrayOutputStream()) {
            workbook.getSheet(sheetName).getRow(40).getCell(0).setCellValue("SYNTHETIC_TEST_ONLY_WORKING_COPY");
            workbook.write(output);
            working = output.toByteArray();
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(working));
             var output = new ByteArrayOutputStream()) {
            workbook.getSheet(sheetName).getRow(40).getCell(0).setCellValue("SYNTHETIC_TEST_ONLY_BASELINE");
            workbook.write(output);
            baseline = output.toByteArray();
        }
        return new Fixture(baseline, working, sheetName, sha256(baseline), sha256(working), height, internalMerges);
    }

    private void fillBox(org.apache.poi.ss.usermodel.Sheet sheet, int top, int height, String managementNo,
                         org.apache.poi.ss.usermodel.CellStyle text, org.apache.poi.ss.usermodel.CellStyle right,
                         org.apache.poi.ss.usermodel.CellStyle bottom, org.apache.poi.ss.usermodel.CellStyle corner) {
        for (int rowOffset = 0; rowOffset < height; rowOffset++) {
            var row = sheet.getRow(top + rowOffset);
            if (row == null) row = sheet.createRow(top + rowOffset);
            for (int column = 9; column <= 26; column++) {
                var cell = row.createCell(column);
                boolean isRight = column == 26;
                boolean isBottom = rowOffset == height - 1;
                cell.setCellStyle(isRight && isBottom ? corner : isRight ? right : isBottom ? bottom : text);
            }
        }
        row(sheet, top).getCell(9).setCellValue(managementNo);
        row(sheet, top + 2).getCell(9).setCellValue("Storage");
        row(sheet, top + 2).getCell(11).setCellValue("Synthetic note");
        row(sheet, top + 3).getCell(9).setCellValue("Synthetic Unit");
        row(sheet, top + 4).getCell(9).setCellValue("ITEM-001");
        row(sheet, top + 5).getCell(11).setCellValue("120");
        row(sheet, top + 5).getCell(12).setCellValue("m3/h");
        row(sheet, top + 6).getCell(9).setCellValue("1ea");
    }

    private org.apache.poi.ss.usermodel.Row row(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex) {
        var row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private EquipmentBoxProfile profile(Fixture fixture, String deletePolicy) throws Exception {
        Map<String, EquipmentBoxProfile.FieldCell> fields = new LinkedHashMap<>();
        fields.put("managementNo", field(0, 0));
        fields.put("equipmentTypeLabel", field(2, 0));
        fields.put("equipmentName", field(3, 0));
        fields.put("businessItemNo", field(4, 0));
        fields.put("capacity", field(5, 2));
        fields.put("capacityUnit", field(5, 3));
        fields.put("quantity", field(6, 0));
        fields.put("note", field(2, 2));
        Map<Integer, Float> rowHeights = new LinkedHashMap<>();
        for (int row = 0; row < fixture.height(); row++) rowHeights.put(row, fixture.height() == 17 ? 13.5f : 12f);
        Map<String, Double> widths = new LinkedHashMap<>();
        for (int column = 9; column <= 26; column++) {
            String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column);
            double width = switch (letter) {
                case "J", "K", "X" -> 1.375d;
                case "W", "AA" -> 0.875d;
                default -> 13d;
            };
            widths.put(letter, width);
        }
        Map<String, Integer> styles = new LinkedHashMap<>();
        fields.forEach((name, cell) -> styles.put(name, cell.expectedStyleId()));
        List<String> merges = fixture.internalMerges() ? List.of("M2:N2", "M8:N8") : List.of();
        return new EquipmentBoxProfile("SYNTHETIC-DELETE-" + fixture.height(), PROCESS, fixture.sheetName(),
                "SYNTHETIC_TEST_ONLY_BASELINE.xlsx", fixture.sourceSha256(), fixture.workingSha256(), "0.1.0",
                "VERIFIED", "SYNTHETIC_TESTED", "Synthetic fixture only; no process authorization.",
                "J1:AA" + fixture.height(), fixture.height(), fields, merges, styles, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", rowHeights, widths, "PRESERVE_ONLY", "PRESERVE_ONLY_NO_NEW_CONNECTOR",
                "BOX_ONLY_TEST_ONLY", "'" + fixture.sheetName() + "'!A1:AN60",
                "PRESERVE", null, deletePolicy);
    }

    private EquipmentBoxProfile.FieldCell field(int row, int column) {
        return new EquipmentBoxProfile.FieldCell(row, column, 1, "맑은 고딕", (short) 8,
                "DEFAULT_BLACK", true, "TEXT");
    }

    private EquipmentBoxProfile copyProfile(EquipmentBoxProfile profile, String status, String deletePolicy) {
        return new EquipmentBoxProfile(profile.profileId(), profile.processCode(), profile.sheetName(),
                profile.sourceFilename(), profile.sourceSha256(), profile.verifiedWorkingSha256(), profile.profileVersion(),
                status, profile.evidenceLevel(), profile.evidence(), profile.templateRange(), profile.boxHeight(),
                profile.fields(), profile.mergedRanges(), profile.expectedStyleIds(), profile.fontName(), profile.fontSizePt(),
                profile.fontColor(), profile.rowHeightsPt(), profile.columnWidths(), profile.drawingPolicy(),
                profile.connectorPolicy(), profile.insertionPolicy(), profile.printArea(), profile.pageBreakPolicy(),
                null, deletePolicy);
    }

    private Fixture addMerge(Fixture fixture, String range) throws Exception {
        byte[] updated = changeWorkbook(fixture.workingCopy(), workbook -> workbook.getSheet(fixture.sheetName())
                .addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf(range)));
        return new Fixture(fixture.baseline(), updated, fixture.sheetName(), fixture.sourceSha256(), sha256(updated),
                fixture.height(), fixture.internalMerges());
    }

    private Fixture changeCell(Fixture fixture, String address, String value) throws Exception {
        byte[] updated = changeWorkbook(fixture.workingCopy(), workbook -> {
            var reference = new org.apache.poi.ss.util.CellReference(address);
            workbook.getSheet(fixture.sheetName()).getRow(reference.getRow()).getCell(reference.getCol()).setCellValue(value);
        });
        return new Fixture(fixture.baseline(), updated, fixture.sheetName(), fixture.sourceSha256(), sha256(updated),
                fixture.height(), fixture.internalMerges());
    }

    private byte[] changeWorkbook(byte[] source, WorkbookMutation mutation) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(source));
             var output = new ByteArrayOutputStream()) {
            mutation.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void addDrawing(org.apache.poi.ss.usermodel.Sheet sheet, Drawing type) {
        var drawing = (org.apache.poi.xssf.usermodel.XSSFDrawing) sheet.createDrawingPatriarch();
        if (type == Drawing.ADJACENT_SHAPE) {
            var shape = drawing.createTextbox(drawing.createAnchor(0, 0, 0, 0, 27, 2, 29, 4));
            shape.getCTShape().getNvSpPr().getCNvPr().setId(20L);
            shape.getCTShape().getNvSpPr().getCNvPr().setName("Synthetic adjacent shape");
            return;
        }
        if (type == Drawing.CROSSING_CONNECTOR || type == Drawing.REMOTE_BOUND_CONNECTOR) {
            var shape = drawing.createTextbox(drawing.createAnchor(0, 0, 0, 0, 35, 30, 37, 32));
            shape.getCTShape().getNvSpPr().getCNvPr().setId(10L);
            shape.getCTShape().getNvSpPr().getCNvPr().setName("Synthetic connector endpoint");
            int left = type == Drawing.CROSSING_CONNECTOR ? 15 : 40;
            int top = type == Drawing.CROSSING_CONNECTOR ? 3 : 35;
            var connector = drawing.createConnector(drawing.createAnchor(0, 0, 0, 0, left, top, left + 2, top + 2));
            connector.getCTConnector().getNvCxnSpPr().getCNvPr().setId(11L);
            connector.getCTConnector().getNvCxnSpPr().getCNvPr().setName("Synthetic connector");
            if (type == Drawing.REMOTE_BOUND_CONNECTOR) {
                var start = connector.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr().addNewStCxn();
                start.setId(10L);
                start.setIdx(0L);
            }
            return;
        }
        var shape = drawing.createTextbox(drawing.createAnchor(0, 0, 0, 0, 35, 30, 37, 32));
        shape.getCTShape().getNvSpPr().getCNvPr().setId(12L);
        shape.getCTShape().getNvSpPr().getCNvPr().setName("Synthetic remote shape");
    }

    private void assertOutput(Fixture fixture, byte[] bytes, int expectedInternalMergesRemoved) throws Exception {
        try (var original = WorkbookFactory.create(new ByteArrayInputStream(fixture.workingCopy()));
             var output = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var before = original.getSheet(fixture.sheetName());
            var after = output.getSheet(fixture.sheetName());
            assertThat(after.getNumMergedRegions()).isEqualTo(before.getNumMergedRegions() - expectedInternalMergesRemoved);
            assertThat(after.getMergedRegions().stream().map(org.apache.poi.ss.util.CellRangeAddress::formatAsString))
                    .contains("B2:C2");
            assertThat(output.getPrintArea(output.getSheetIndex(fixture.sheetName())))
                    .isEqualTo(original.getPrintArea(original.getSheetIndex(fixture.sheetName())));
            assertThat(after.getPrintSetup().getLandscape()).isEqualTo(before.getPrintSetup().getLandscape());
            assertThat(after.getPrintSetup().getPaperSize()).isEqualTo(before.getPrintSetup().getPaperSize());
            assertThat(after.getPrintSetup().getFitWidth()).isEqualTo(before.getPrintSetup().getFitWidth());
            assertThat(after.getPrintSetup().getFitHeight()).isEqualTo(before.getPrintSetup().getFitHeight());
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            for (int row = 0; row < fixture.height(); row++) {
                for (int column = 9; column <= 26; column++) {
                    var cell = after.getRow(row).getCell(column);
                    assertThat(cell == null ? "" : formatter.formatCellValue(cell)).isBlank();
                    assertThat(cell == null ? 0 : cell.getCellStyle().getIndex()).isZero();
                }
            }
            for (int rowIndex = 0; rowIndex <= Math.max(before.getLastRowNum(), after.getLastRowNum()); rowIndex++) {
                var oldRow = before.getRow(rowIndex);
                var newRow = after.getRow(rowIndex);
                assertThat(newRow == null ? -1 : newRow.getHeight()).isEqualTo(oldRow == null ? -1 : oldRow.getHeight());
                for (int column = 0; column <= 39; column++) {
                    boolean inTarget = rowIndex < fixture.height() && column >= 9 && column <= 26;
                    if (inTarget) continue;
                    var oldCell = oldRow == null ? null : oldRow.getCell(column);
                    var newCell = newRow == null ? null : newRow.getCell(column);
                    assertThat(newCell == null ? "" : formatter.formatCellValue(newCell))
                            .as("unrequested cell %s", new org.apache.poi.ss.util.CellReference(rowIndex, column))
                            .isEqualTo(oldCell == null ? "" : formatter.formatCellValue(oldCell));
                    assertThat(newCell == null ? 0 : newCell.getCellStyle().getIndex())
                            .isEqualTo(oldCell == null ? 0 : oldCell.getCellStyle().getIndex());
                }
            }
            for (int column = 0; column < 40; column++) assertThat(after.getColumnWidth(column)).isEqualTo(before.getColumnWidth(column));
            var preserved = after.getRow(40).getCell(0);
            assertThat(formatter.formatCellValue(preserved)).isEqualTo("SYNTHETIC_TEST_ONLY_WORKING_COPY");
            assertThat(output.getFontAt(preserved.getCellStyle().getFontIndexAsInt()).getFontName()).isEqualTo("맑은 고딕");
            assertThat(output.getFontAt(preserved.getCellStyle().getFontIndexAsInt()).getColor())
                    .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex());
        }
    }

    private void assertPackageDeltaOnlyWorksheet(byte[] before, byte[] after, String sheetName) {
        String worksheet = inspector.inspectSheet(before, sheetName).worksheetPartName();
        Map<String, byte[]> beforeParts = packageParts(before);
        Map<String, byte[]> afterParts = packageParts(after);
        assertThat(afterParts.keySet()).isEqualTo(beforeParts.keySet());
        beforeParts.forEach((name, bytes) -> {
            if (!name.equals(worksheet)) assertThat(afterParts.get(name)).as("preserved OOXML part %s", name).containsExactly(bytes);
        });
        var beforeDrawing = inspector.inspectSheet(before, sheetName);
        if (beforeDrawing.drawingPartName() != null) {
            assertThat(afterParts.get(beforeDrawing.drawingPartName())).containsExactly(beforeParts.get(beforeDrawing.drawingPartName()));
            assertThat(afterDrawingObjects(before, sheetName)).isEqualTo(afterDrawingObjects(after, sheetName));
        }
    }

    private List<WorkbookInventory.DrawingObject> afterDrawingObjects(byte[] bytes, String sheetName) {
        return inspector.inspectSheet(bytes, sheetName).drawingObjects();
    }

    private Map<String, byte[]> packageParts(byte[] bytes) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        } catch (Exception exception) {
            throw new AssertionError("Unable to read synthetic OOXML parts", exception);
        }
        return result;
    }

    private void assertZipAndPoi(byte[] bytes) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                while (zip.read(buffer) >= 0) { }
                zip.closeEntry();
            }
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        }
    }

    private void writeArtifact(Path directory, List<String> manifest, String filename, byte[] bytes, String classification)
            throws Exception {
        Files.write(directory.resolve(filename), bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        manifest.add(filename + "\t" + sha256(bytes) + "\t" + bytes.length + "\t" + classification);
    }

    private void verifyManifest(Path directory, List<String> entries) throws Exception {
        for (String entry : entries) {
            String[] columns = entry.split("\\t", -1);
            byte[] bytes = Files.readAllBytes(directory.resolve(columns[0]));
            assertThat(sha256(bytes)).isEqualTo(columns[1]);
            assertThat(Integer.parseInt(columns[2])).isEqualTo(bytes.length);
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private enum Drawing { NONE, ADJACENT_SHAPE, REMOTE_SHAPE, CROSSING_CONNECTOR, REMOTE_BOUND_CONNECTOR }
    private record Fixture(byte[] baseline, byte[] workingCopy, String sheetName, String sourceSha256,
                           String workingSha256, int height, boolean internalMerges) { }
    @FunctionalInterface private interface WorkbookMutation { void apply(org.apache.poi.ss.usermodel.Workbook workbook); }
}
