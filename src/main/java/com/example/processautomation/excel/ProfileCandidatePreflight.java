package com.example.processautomation.excel;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Read-only, non-operational inspection of a REVIEW_REQUIRED Profile candidate.
 * This class is deliberately not a Spring bean and never returns MODIFY authorization.
 */
public final class ProfileCandidatePreflight {
    private static final String UNVERIFIED_CONNECTION = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";
    private final OoxmlPackageInspector inspector;
    private final EquipmentBoxGeometryLocator geometry;

    public ProfileCandidatePreflight(OoxmlPackageInspector inspector, EquipmentBoxGeometryLocator geometry) {
        this.inspector = Objects.requireNonNull(inspector);
        this.geometry = Objects.requireNonNull(geometry);
    }

    public Report inspect(byte[] sourceBytes, byte[] workbookBytes, Request request, CandidateSpec candidate,
                          EquipmentBoxProfile existingProfile) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        Objects.requireNonNull(workbookBytes, "workbookBytes");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(existingProfile, "existingProfile");

        String actualSourceSha = sha256(sourceBytes);
        String actualWorkingSha = sha256(workbookBytes);
        List<Finding> findings = new ArrayList<>();
        List<String> identityFailures = new ArrayList<>();
        checkIdentity(request, candidate, existingProfile, identityFailures);
        if (!identityFailures.isEmpty()) {
            identityFailures.forEach(code -> findings.add(new Finding(code, "BLOCKED",
                    "Candidate, request, and existing Profile identity do not agree.")));
        }
        boolean workingShaMatch = candidate.workingCopySha256() != null
                && candidate.workingCopySha256().equalsIgnoreCase(actualWorkingSha);
        boolean existingProfileWorkingShaMatch = existingProfile.matchesWorkingCopySha(actualWorkingSha);
        if (!workingShaMatch) {
            findings.add(new Finding("PROFILE_WORKING_SHA_MISMATCH", "BLOCKED",
                    "Workbook bytes do not match the candidate's pinned working-copy SHA-256."));
        }
        boolean sourceShaIdentityMatch = candidate.sourceSha256() != null && request.sourceSha256() != null
                && candidate.sourceSha256().equalsIgnoreCase(request.sourceSha256())
                && existingProfile.sourceSha256() != null
                && existingProfile.sourceSha256().equalsIgnoreCase(request.sourceSha256())
                && actualSourceSha.equalsIgnoreCase(request.sourceSha256());
        if (!sourceShaIdentityMatch) {
            findings.add(new Finding("PROFILE_IDENTITY_MISMATCH", "BLOCKED",
                    "The supplied source SHA does not match both candidate and existing Profile identity."));
        }
        if (!"REVIEW_REQUIRED".equals(candidate.status()) || candidate.registered()
                || candidate.activationAllowed() || candidate.approvalEvidencePresent()) {
            findings.add(new Finding("REVIEW_CANDIDATE_STATE_INVALID", "BLOCKED",
                    "Preflight accepts only an unregistered, unapproved REVIEW_REQUIRED candidate."));
        }

        EquipmentBoxGeometryLocator.Box box;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheet(request.sheetName());
            if (sheet == null) {
                findings.add(new Finding("TARGET_SHEET_MISSING", "BLOCKED", "Requested worksheet is absent."));
                return report(request, candidate, existingProfile, actualSourceSha, actualWorkingSha,
                        sourceShaIdentityMatch, workingShaMatch, null, List.of(), List.of(), findings);
            }
            List<EquipmentBoxGeometryLocator.Box> boxes = geometry.locateByManagementNo(sheet, request.managementNo());
            if (boxes.size() != 1) {
                findings.add(new Finding("EQUIPMENT_BOX_NOT_UNIQUE", "BLOCKED",
                        "Management number did not resolve to exactly one bordered box."));
                return report(request, candidate, existingProfile, actualSourceSha, actualWorkingSha,
                        sourceShaIdentityMatch, workingShaMatch, null, List.of(), List.of(), findings);
            }
            box = boxes.get(0);
            String actualBoxRange = box.topLeftCell() + ":" + box.bottomRightCell();
            boolean boxMatch = actualBoxRange.equalsIgnoreCase(candidate.boxRange())
                    && request.managementNo().equals(candidate.managementNo());
            if (!boxMatch) {
                findings.add(new Finding("PROFILE_BOX_GEOMETRY_MISMATCH", "BLOCKED",
                        "Detected box or equipment identity differs from the candidate snapshot."));
            }

            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<FieldResult> fieldResults = new ArrayList<>();
            for (FieldSpec fieldSpec : candidate.fields()) {
                fieldResults.add(inspectField(sheet, formatter, box, fieldSpec, existingProfile, findings));
            }

            List<RelativeRange> liveMerges = sheet.getMergedRegions().stream()
                    .filter(range -> intersects(range, box))
                    .map(range -> relativeRange(range, box))
                    .sorted(Comparator.comparingInt(RelativeRange::firstRow)
                            .thenComparingInt(RelativeRange::firstColumn)
                            .thenComparingInt(RelativeRange::lastRow)
                            .thenComparingInt(RelativeRange::lastColumn))
                    .toList();
            List<RelativeRange> expectedMerges = candidate.expectedLiveMerges() == null
                    ? List.of() : candidate.expectedLiveMerges().stream().sorted(Comparator
                    .comparingInt(RelativeRange::firstRow).thenComparingInt(RelativeRange::firstColumn)
                    .thenComparingInt(RelativeRange::lastRow).thenComparingInt(RelativeRange::lastColumn)).toList();
            if (!liveMerges.equals(expectedMerges)) {
                findings.add(new Finding("PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED", "BLOCKED",
                        "Live box-relative merged ranges differ from the exact-SHA candidate snapshot."));
            }
            List<RelativeRange> templateMerges = candidate.templateRelativeMerges() == null
                    ? List.of() : candidate.templateRelativeMerges().stream().sorted(Comparator
                    .comparingInt(RelativeRange::firstRow).thenComparingInt(RelativeRange::firstColumn)
                    .thenComparingInt(RelativeRange::lastRow).thenComparingInt(RelativeRange::lastColumn)).toList();
            if (!templateMerges.equals(expectedMerges)) {
                findings.add(new Finding("PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED", "REVIEW_REQUIRED",
                        "Existing template merge geometry differs from the target candidate geometry."));
            }

            List<DrawingResult> drawingResults = inspectDrawingObjects(workbookBytes, request.sheetName(), box,
                    fieldResults, findings);
            boolean structurePass = sourceShaIdentityMatch && workingShaMatch && identityFailures.isEmpty()
                    && boxMatch && liveMerges.equals(expectedMerges)
                    && fieldResults.stream().allMatch(field -> field.currentValueMatches()
                    && field.cellAddress() != null && inside(box, new CellReference(field.cellAddress()).getRow(),
                    new CellReference(field.cellAddress()).getCol()))
                    && drawingResults.stream().noneMatch(drawing -> "UNKNOWN_LOCATION".equals(drawing.classification()));
            String structuralStatus = structurePass ? "STRUCTURE_CHECK_PASS" : "REVIEW_REQUIRED";
            String decisionStatus = findings.stream().anyMatch(f -> "BLOCKED".equals(f.severity()))
                    ? "BLOCKED" : "REVIEW_REQUIRED";
            return new Report(candidate.candidateId(), request.processCode(), request.sheetName(),
                    request.managementNo(), actualBoxRange, request.sourceSha256(), actualSourceSha, actualWorkingSha,
                    sourceShaIdentityMatch, workingShaMatch, existingProfileWorkingShaMatch,
                    existingProfile.profileId(), existingProfile.profileVersion(),
                    existingProfile.status(), candidate.status(), structuralStatus, decisionStatus, false,
                    UNVERIFIED_CONNECTION, fieldResults, liveMerges, drawingResults, List.copyOf(findings));
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to perform read-only Profile candidate preflight", exception);
        }
    }

    private void checkIdentity(Request request, CandidateSpec candidate, EquipmentBoxProfile existingProfile,
                               List<String> failures) {
        if (!Objects.equals(candidate.processCode(), request.processCode())
                || !Objects.equals(candidate.sheetName(), request.sheetName())
                || !Objects.equals(candidate.managementNo(), request.managementNo())
                || !Objects.equals(candidate.existingProfileId(), existingProfile.profileId())
                || !Objects.equals(candidate.existingProfileVersion(), existingProfile.profileVersion())) {
            failures.add("PROFILE_IDENTITY_MISMATCH");
        }
        if (!existingProfile.appliesTo(request.processCode(), request.sheetName(), request.sourceSha256())) {
            failures.add("PROFILE_IDENTITY_MISMATCH");
        }
    }

    private FieldResult inspectField(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter formatter,
                                     EquipmentBoxGeometryLocator.Box box, FieldSpec spec,
                                     EquipmentBoxProfile profile, List<Finding> findings) {
        List<String> blockers = new ArrayList<>();
        CellReference reference;
        try {
            reference = new CellReference(spec.cellAddress());
        } catch (RuntimeException exception) {
            findings.add(new Finding("PROFILE_FIELD_OUTSIDE_BOX", "BLOCKED", "Candidate field address is invalid."));
            return new FieldResult(spec.field(), spec.cellAddress(), spec.observedCurrentValue(),
                    spec.valueSource(), spec.existingProfileReferenceCell(), null, false,
                    null, null, null, null, null, null, spec.proposedForReview(),
                    List.of("PROFILE_FIELD_OUTSIDE_BOX"), "BLOCKED");
        }
        String actualAddress = reference.formatAsString().replace("$", "");
        if (!inside(box, reference.getRow(), reference.getCol())) {
            blockers.add("PROFILE_FIELD_OUTSIDE_BOX");
        }
        Row row = sheet.getRow(reference.getRow());
        Cell cell = row == null ? null : row.getCell(reference.getCol(), Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
        if (cell == null) {
            blockers.add("PROFILE_FIELD_CELL_MISSING");
        }
        String actualText = cell == null ? null : formatter.formatCellValue(cell);
        boolean snapshotMatches = Objects.equals(spec.observedCurrentValue(), actualText);
        if (!snapshotMatches) blockers.add("STALE_VALUE");
        EquipmentBoxProfile.FieldCell profileField = profile.fields().get(spec.field());
        Integer expectedStyle = profileField == null ? null : profileField.expectedStyleId();
        Float expectedHeight = profileField == null ? null : profile.rowHeightsPt().get(profileField.rowOffset());
        Integer actualStyle = cell == null ? null : (int) cell.getCellStyle().getIndex();
        Float actualHeight = row == null ? null : row.getHeightInPoints();
        StyleSnapshot actualStyleProperties = cell == null ? null : styleSnapshot(cell.getCellStyle(), sheet.getWorkbook());
        Cell referenceCell = spec.existingProfileReferenceCell() == null ? null
                : cellAt(sheet, new CellReference(spec.existingProfileReferenceCell()));
        StyleSnapshot referenceStyle = referenceCell == null ? null
                : styleSnapshot(referenceCell.getCellStyle(), sheet.getWorkbook());
        if (spec.existingProfileReferenceCell() != null && referenceCell == null) {
            blockers.add("PROFILE_TEMPLATE_REFERENCE_CELL_MISSING");
        }
        if (spec.field().equals("managementNo")) blockers.add("MANAGEMENT_ID_CHANGE_REQUIRES_GLOBAL_REMAP");
        if (profileField == null) blockers.add("PROFILE_FIELD_NOT_DEFINED");
        if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            blockers.add("FORMULA_FIELD_NOT_EDITABLE");
        }
        if (cell != null && cell.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) {
            blockers.add("PROFILE_CELL_TYPE_MISMATCH");
        }
        if (expectedStyle != null && actualStyle != null && !expectedStyle.equals(actualStyle)) {
            blockers.add("PROFILE_STYLE_MISMATCH");
        }
        if (profileField != null && cell != null) {
            var font = sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt());
            if (profileField.expectedFontName() != null && !profileField.expectedFontName().equals(font.getFontName())) {
                blockers.add("PROFILE_FONT_MISMATCH");
            }
            if (profileField.expectedFontSizePt() != null
                    && profileField.expectedFontSizePt() != font.getFontHeightInPoints()) {
                blockers.add("PROFILE_FONT_SIZE_MISMATCH");
            }
            if (expectedHeight != null && actualHeight != null && Math.abs(expectedHeight - actualHeight) > 0.2f) {
                blockers.add("PROFILE_ROW_HEIGHT_MISMATCH");
            }
        }
        if (cell != null && sheet.getMergedRegions().stream()
                .anyMatch(range -> range.isInRange(reference.getRow(), reference.getCol()))) {
            blockers.add("PROFILE_MERGE_STRUCTURE_REVIEW_REQUIRED");
        }
        if (blockers.isEmpty()) blockers.add("UNAPPROVED_REVIEW_ONLY_CANDIDATE");
        blockers = blockers.stream().distinct().toList();
        String status = blockers.contains("UNAPPROVED_REVIEW_ONLY_CANDIDATE") ? "REVIEW_REQUIRED" : "BLOCKED";
        for (String blocker : blockers) {
            String severity = "UNAPPROVED_REVIEW_ONLY_CANDIDATE".equals(blocker) ? "REVIEW_REQUIRED" : "BLOCKED";
            findings.add(new Finding(blocker, severity, "Field " + spec.field() + " at " + actualAddress
                    + " is inspection-only; no write permission is granted."));
        }
        return new FieldResult(spec.field(), actualAddress, spec.observedCurrentValue(), spec.valueSource(),
                spec.existingProfileReferenceCell(), actualText, snapshotMatches, expectedStyle, actualStyle,
                expectedHeight, actualHeight,
                actualStyleProperties, referenceStyle, spec.proposedForReview(), blockers, status);
    }

    private List<DrawingResult> inspectDrawingObjects(byte[] workbookBytes, String sheetName,
                                                      EquipmentBoxGeometryLocator.Box box,
                                                      List<FieldResult> fields, List<Finding> findings) {
        var inventory = inspector.inspectSheet(workbookBytes, sheetName);
        List<DrawingResult> results = new ArrayList<>();
        for (var drawing : inventory.drawingObjects()) {
            if (drawing.startRow() == null || drawing.endRow() == null
                    || drawing.startColumn() == null || drawing.endColumn() == null) {
                results.add(drawingResult(drawing, List.of(), "UNKNOWN_LOCATION"));
                findings.add(new Finding("DRAWINGML_LOCATION_UNKNOWN", "REVIEW_REQUIRED",
                        "A DrawingML object has incomplete anchor coordinates."));
                continue;
            }
            int top = Math.min(drawing.startRow(), drawing.endRow());
            int bottom = Math.max(drawing.startRow(), drawing.endRow());
            int left = Math.min(drawing.startColumn(), drawing.endColumn());
            int right = Math.max(drawing.startColumn(), drawing.endColumn());
            boolean intersectsBox = top <= box.lastRow() - 1 && bottom >= box.firstRow() - 1
                    && left <= box.lastColumn() && right >= box.firstColumn();
            if (!intersectsBox) continue;
            List<String> overlappingFields = fields.stream().filter(field -> {
                CellReference ref = new CellReference(field.cellAddress());
                return ref.getRow() >= top && ref.getRow() <= bottom
                        && ref.getCol() >= left && ref.getCol() <= right;
            }).map(FieldResult::field).toList();
            boolean directField = !overlappingFields.isEmpty();
            String classification = directField ? "DIRECT_FIELD_OVERLAP" : "BOX_REGION_REVIEW";
            results.add(drawingResult(drawing, overlappingFields, classification));
            if (directField) {
                findings.add(new Finding("DRAWINGML_FIELD_OVERLAP", "BLOCKED",
                        "A drawing anchor overlaps a candidate field cell; visual/physical endpoint is not inferred."));
            } else {
                findings.add(new Finding("DRAWINGML_BOX_BOUNDARY_REVIEW_REQUIRED", "REVIEW_REQUIRED",
                        "A drawing anchor intersects the target box region; preserve and review it, do not infer connectivity."));
            }
        }
        return List.copyOf(results);
    }

    private DrawingResult drawingResult(WorkbookInventory.DrawingObject drawing, List<String> overlappingFields,
                                        String classification) {
        return new DrawingResult(drawing.objectIndex(), drawing.shapeId(), drawing.objectName(), drawing.objectType(),
                drawing.startRow(), drawing.startColumn(), drawing.startColumnOffset(), drawing.startRowOffset(),
                drawing.endRow(), drawing.endColumn(), drawing.endColumnOffset(), drawing.endRowOffset(),
                drawing.startConnectedShapeId(), drawing.endConnectedShapeId(), drawing.headArrowType(),
                drawing.tailArrowType(), drawing.transformXEmu(), drawing.transformYEmu(),
                drawing.transformWidthEmu(), drawing.transformHeightEmu(), overlappingFields, classification);
    }

    private Report report(Request request, CandidateSpec candidate, EquipmentBoxProfile profile,
                          String actualSourceSha, String actualWorkingSha, boolean sourceMatch, boolean workingMatch,
                          String actualBox, List<FieldResult> fields, List<RelativeRange> merges, List<Finding> findings) {
        return new Report(candidate.candidateId(), request.processCode(), request.sheetName(), request.managementNo(),
                actualBox, request.sourceSha256(), actualSourceSha, actualWorkingSha, sourceMatch, workingMatch,
                profile.matchesWorkingCopySha(actualWorkingSha), profile.profileId(),
                profile.profileVersion(), profile.status(), candidate.status(), "REVIEW_REQUIRED", "BLOCKED", false,
                UNVERIFIED_CONNECTION, fields, merges, List.of(), List.copyOf(findings));
    }

    private Cell cellAt(org.apache.poi.ss.usermodel.Sheet sheet, CellReference reference) {
        Row row = sheet.getRow(reference.getRow());
        return row == null ? null : row.getCell(reference.getCol(), Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
    }

    private boolean intersects(CellRangeAddress range, EquipmentBoxGeometryLocator.Box box) {
        return range.getFirstRow() <= box.lastRow() - 1 && range.getLastRow() >= box.firstRow() - 1
                && range.getFirstColumn() <= box.lastColumn() && range.getLastColumn() >= box.firstColumn();
    }

    private RelativeRange relativeRange(CellRangeAddress range, EquipmentBoxGeometryLocator.Box box) {
        return new RelativeRange(range.getFirstRow() - (box.firstRow() - 1),
                range.getFirstColumn() - box.firstColumn(), range.getLastRow() - (box.firstRow() - 1),
                range.getLastColumn() - box.firstColumn());
    }

    private boolean inside(EquipmentBoxGeometryLocator.Box box, int row, int column) {
        return row >= box.firstRow() - 1 && row <= box.lastRow() - 1
                && column >= box.firstColumn() && column <= box.lastColumn();
    }

    private StyleSnapshot styleSnapshot(CellStyle style, org.apache.poi.ss.usermodel.Workbook workbook) {
        var font = workbook.getFontAt(style.getFontIndexAsInt());
        String fontColor = font instanceof org.apache.poi.xssf.usermodel.XSSFFont xssfFont
                ? colorDescriptor(xssfFont.getXSSFColor()) : "INDEXED:" + font.getColor();
        String fillColor = style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle xssfStyle
                ? colorDescriptor(xssfStyle.getFillForegroundXSSFColor())
                : "INDEXED:" + style.getFillForegroundColor();
        return new StyleSnapshot(font.getFontName(), (double) font.getFontHeightInPoints(),
                fontColor, fillColor, style.getFillPattern().name(),
                style.getBorderLeft().name(), style.getBorderRight().name(), style.getBorderTop().name(),
                style.getBorderBottom().name(), style.getAlignment().name(), style.getVerticalAlignment().name(),
                style.getWrapText(), style.getShrinkToFit(), style.getDataFormatString(),
                style.getLocked(), style.getHidden());
    }

    private String colorDescriptor(org.apache.poi.xssf.usermodel.XSSFColor color) {
        if (color == null) return "DEFAULT_OR_UNSPECIFIED";
        if (color.isAuto()) return "AUTO";
        if (color.getARGBHex() != null) return "ARGB:" + color.getARGBHex();
        if (color.isIndexed()) return "INDEXED:" + color.getIndexed();
        if (color.isThemed()) return "THEME:" + color.getTheme() + ":TINT:" + color.getTint();
        return "UNRESOLVED";
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Request(String processCode, String sheetName, String managementNo, String sourceSha256) { }

    public record CandidateSpec(String candidateId, String processCode, String sheetName, String managementNo,
                                String sourceSha256, String workingCopySha256, String boxRange, String status,
                                boolean registered, boolean activationAllowed, boolean approvalEvidencePresent,
                                String templateBoxRange, String existingProfileId, String existingProfileVersion,
                                List<RelativeRange> expectedLiveMerges, List<RelativeRange> templateRelativeMerges,
                                List<FieldSpec> fields) {
        public CandidateSpec {
            expectedLiveMerges = expectedLiveMerges == null ? List.of() : List.copyOf(expectedLiveMerges);
            templateRelativeMerges = templateRelativeMerges == null ? List.of() : List.copyOf(templateRelativeMerges);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record FieldSpec(String field, String cellAddress, String observedCurrentValue, String valueSource,
                            String existingProfileReferenceCell, boolean proposedForReview) { }

    public record RelativeRange(int firstRow, int firstColumn, int lastRow, int lastColumn) { }

    public record Finding(String code, String severity, String detail) { }

    public record StyleSnapshot(String fontName, double fontSizePt, String fontColor,
                                String fillForegroundColor, String fillPattern,
                                String borderLeft, String borderRight, String borderTop, String borderBottom,
                                String horizontalAlignment, String verticalAlignment, boolean wrapText,
                                boolean shrinkToFit, String numberFormat, boolean locked, boolean hidden) { }

    public record FieldResult(String field, String cellAddress, String observedExpectedCurrentValue,
                              String observedValueSource, String existingProfileReferenceCell,
                              String actualCurrentValue, boolean currentValueMatches,
                              Integer existingProfileExpectedStyleId, Integer actualStyleId,
                              Float existingProfileExpectedRowHeightPt, Float actualRowHeightPt,
                              StyleSnapshot actualStyleProperties, StyleSnapshot existingProfileReferenceStyle,
                              boolean proposedForReview, List<String> blockingConditions, String status) {
        public FieldResult {
            blockingConditions = blockingConditions == null ? List.of() : List.copyOf(blockingConditions);
        }
    }

    public record DrawingResult(int objectIndex, String objectId, String objectName, String objectType,
                                Integer startRow, Integer startColumn, Long startColumnOffsetEmu,
                                Long startRowOffsetEmu, Integer endRow, Integer endColumn,
                                Long endColumnOffsetEmu, Long endRowOffsetEmu,
                                String startConnectedShapeId, String endConnectedShapeId,
                                String headArrowType, String tailArrowType,
                                Long transformXEmu, Long transformYEmu, Long transformWidthEmu, Long transformHeightEmu,
                                List<String> overlappingFields, String classification) {
        public DrawingResult { overlappingFields = overlappingFields == null ? List.of() : List.copyOf(overlappingFields); }
    }

    public record Report(String candidateId, String processCode, String sheetName, String managementNo,
                         String actualBoxRange, String sourceSha256Claim, String actualSourceSha256,
                         String actualWorkingCopySha256, boolean sourceShaIdentityMatch, boolean workingCopyShaMatch,
                         boolean existingProfileWorkingShaMatch, String existingProfileId, String existingProfileVersion, String existingProfileStatus,
                         String candidateStatus, String structuralStatus, String decisionStatus,
                         boolean modifyAuthorized,
                         String connectionEvidenceStatus, List<FieldResult> fields,
                         List<RelativeRange> liveMergedRanges, List<DrawingResult> drawingObjects,
                         List<Finding> findings) {
        public Report {
            fields = List.copyOf(fields);
            liveMergedRanges = List.copyOf(liveMergedRanges);
            drawingObjects = List.copyOf(drawingObjects);
            findings = List.copyOf(findings);
        }
    }
}
