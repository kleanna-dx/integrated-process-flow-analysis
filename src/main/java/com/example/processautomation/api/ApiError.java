package com.example.processautomation.api;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message) {
}
