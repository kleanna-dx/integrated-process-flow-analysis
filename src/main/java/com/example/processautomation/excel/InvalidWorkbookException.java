package com.example.processautomation.excel;

public class InvalidWorkbookException extends RuntimeException {
    public InvalidWorkbookException(String message) {
        super(message);
    }

    public InvalidWorkbookException(String message, Throwable cause) {
        super(message, cause);
    }
}
