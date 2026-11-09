package com.example.processautomation.excel;

import java.util.List;
import java.util.Map;

public record WorkbookInventory(
        List<SheetInventory> sheets,
        int drawingPartCount,
        int shapeCount,
        int connectorCount,
        int pictureCount,
        int otherObjectCount,
        int explicitConnectorBindings) {

    public WorkbookInventory {
        sheets = List.copyOf(sheets);
    }

    public record SheetInventory(
            int sheetIndex,
            String sheetName,
            String visibility,
            int physicalRowCount,
            int lastColumnIndex,
            String worksheetPartName,
            String drawingPartName,
            List<String> textFragments,
            List<DrawingObject> drawingObjects) {

        public SheetInventory {
            textFragments = List.copyOf(textFragments);
            drawingObjects = List.copyOf(drawingObjects);
        }

        public String searchableText() {
            return String.join("\n", textFragments);
        }
    }

    public record DrawingObject(
            int objectIndex,
            String objectType,
            String shapeId,
            String objectName,
            String textContent,
            String geometryPreset,
            Integer startColumn,
            Integer startRow,
            Long startColumnOffset,
            Long startRowOffset,
            Integer endColumn,
            Integer endRow,
            Long endColumnOffset,
            Long endRowOffset,
            String startConnectedShapeId,
            String endConnectedShapeId,
            String headArrowType,
            String tailArrowType,
            Long transformXEmu,
            Long transformYEmu,
            Long transformWidthEmu,
            Long transformHeightEmu,
            Integer rotation60000,
            Boolean flipHorizontal,
            Boolean flipVertical,
            Long startAnchorXEmu,
            Long startAnchorYEmu,
            Long endAnchorXEmu,
            Long endAnchorYEmu) {
    }
}
