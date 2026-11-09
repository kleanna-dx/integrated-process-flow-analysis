package com.example.processautomation.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

/** Read-only geometry locator. It describes visual boxes only; it does not map them to a master or process flow. */
@Component
public class EquipmentBoxGeometryLocator {
    public List<Box> locateByManagementNo(Sheet sheet, String managementNo) {
        if (sheet == null || managementNo == null || managementNo.isBlank()) return List.of();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<Box> boxes = new ArrayList<>();
        for (Row row : sheet) for (Cell idCell : row) {
            if (!managementNo.equals(formatter.formatCellValue(idCell).trim())) continue;
            int right = findRight(sheet, idCell);
            int bottom = findBottom(sheet, idCell, right);
            int width = right - idCell.getColumnIndex();
            int height = bottom - idCell.getRowIndex();
            if (width < 5 || width > 80 || height < 6 || height > 60) continue;
            boxes.add(new Box(managementNo, idCell.getColumnIndex(), idCell.getRowIndex() + 1,
                    right, bottom + 1, idCell.getAddress().formatAsString(), address(right, bottom), height));
        }
        return List.copyOf(boxes);
    }

    private int findRight(Sheet sheet, Cell topLeft) {
        Row row = topLeft.getRow();
        for (int column = topLeft.getColumnIndex(); column < Math.min(16_383, topLeft.getColumnIndex() + 160); column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
            if (cell != null && cell.getCellStyle().getBorderRight() != org.apache.poi.ss.usermodel.BorderStyle.NONE) return column;
        }
        return -1;
    }

    private int findBottom(Sheet sheet, Cell topLeft, int right) {
        if (right < topLeft.getColumnIndex()) return -1;
        for (int rowIndex = topLeft.getRowIndex() + 1; rowIndex < Math.min(1_048_575, topLeft.getRowIndex() + 80); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            boolean fullBottom = true;
            for (int column = topLeft.getColumnIndex(); column <= right; column++) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                if (cell == null || cell.getCellStyle().getBorderBottom() == org.apache.poi.ss.usermodel.BorderStyle.NONE) {
                    fullBottom = false;
                    break;
                }
            }
            if (fullBottom) return rowIndex;
        }
        return -1;
    }

    private String address(int column, int row) {
        return new org.apache.poi.ss.util.CellReference(row, column).formatAsString().replace("$", "");
    }

    public record Box(String managementNo, int firstColumn, int firstRow, int lastColumn, int lastRow,
                      String topLeftCell, String bottomRightCell, int height) { }
}
