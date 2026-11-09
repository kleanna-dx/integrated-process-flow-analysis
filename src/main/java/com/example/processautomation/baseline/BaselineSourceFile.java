package com.example.processautomation.baseline;

import com.example.processautomation.excel.WorkbookInventory;

public record BaselineSourceFile(
        String originalFilename,
        String processCode,
        String contentType,
        byte[] content,
        String sha256,
        WorkbookInventory inventory) {

    public BaselineSourceFile {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
