package com.example.processautomation.excel;

import java.util.List;
import java.util.Map;

/** Immutable, source-bound description of an equipment-box layout; it is not proof of process connectivity. */
public record EquipmentBoxProfile(
        String profileId,
        String processCode,
        String sheetName,
        String sourceFilename,
        String sourceSha256,
        String verifiedWorkingSha256,
        String profileVersion,
        String status,
        String evidenceLevel,
        String evidence,
        String templateRange,
        int boxHeight,
        Map<String, FieldCell> fields,
        List<String> mergedRanges,
        Map<String, Integer> expectedStyleIds,
        String fontName,
        short fontSizePt,
        String fontColor,
        Map<Integer, Float> rowHeightsPt,
        Map<String, Double> columnWidths,
        String drawingPolicy,
        String connectorPolicy,
        String insertionPolicy,
        String printArea,
        String pageBreakPolicy,
        String blocker,
        String deletePolicy) {

    /** Backward-compatible constructor; existing ADD/MODIFY Profiles do not implicitly authorize DELETE. */
    public EquipmentBoxProfile(String profileId, String processCode, String sheetName, String sourceFilename,
                               String sourceSha256, String verifiedWorkingSha256, String profileVersion,
                               String status, String evidenceLevel, String evidence, String templateRange,
                               int boxHeight, Map<String, FieldCell> fields, List<String> mergedRanges,
                               Map<String, Integer> expectedStyleIds, String fontName, short fontSizePt,
                               String fontColor, Map<Integer, Float> rowHeightsPt, Map<String, Double> columnWidths,
                               String drawingPolicy, String connectorPolicy, String insertionPolicy, String printArea,
                               String pageBreakPolicy, String blocker) {
        this(profileId, processCode, sheetName, sourceFilename, sourceSha256, verifiedWorkingSha256, profileVersion,
                status, evidenceLevel, evidence, templateRange, boxHeight, fields, mergedRanges, expectedStyleIds,
                fontName, fontSizePt, fontColor, rowHeightsPt, columnWidths, drawingPolicy, connectorPolicy,
                insertionPolicy, printArea, pageBreakPolicy, blocker, null);
    }

    public EquipmentBoxProfile {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        mergedRanges = mergedRanges == null ? List.of() : List.copyOf(mergedRanges);
        expectedStyleIds = expectedStyleIds == null ? Map.of() : Map.copyOf(expectedStyleIds);
        rowHeightsPt = rowHeightsPt == null ? Map.of() : Map.copyOf(rowHeightsPt);
        columnWidths = columnWidths == null ? Map.of() : Map.copyOf(columnWidths);
    }

    public boolean appliesTo(String process, String sheet, String sourceSha) {
        return processCode != null && processCode.equals(process)
                && sheetName != null && sheetName.equals(sheet)
                && sourceSha256 != null && sourceSha256.equalsIgnoreCase(sourceSha);
    }

    public boolean matchesWorkingCopySha(String workingSha) {
        return verifiedWorkingSha256 != null && verifiedWorkingSha256.equalsIgnoreCase(workingSha);
    }

    public boolean enabledForGenericAdd() {
        return "VERIFIED".equals(status) && blocker == null && !fields.isEmpty()
                && !"LEGACY_ONLY".equals(insertionPolicy);
    }

    /** Zero-based offsets from the top-left of the template box. */
    public record FieldCell(int rowOffset, int columnOffset, Integer expectedStyleId,
                            String expectedFontName, Short expectedFontSizePt,
                            String expectedFontColor, boolean required, String valueMode) { }
}
