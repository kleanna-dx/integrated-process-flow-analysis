package com.example.processautomation.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;

public final class ExcelCellText {
    private ExcelCellText() {
    }

    public static String format(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> "#FORMULA_ERROR_" + cell.getErrorCellValue();
            case BLANK, _NONE -> "";
            case FORMULA -> "=" + cell.getCellFormula();
        };
    }
}
