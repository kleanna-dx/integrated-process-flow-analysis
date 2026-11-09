package com.example.processautomation.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ApiAccessGuard {
    private final String configuredToken;

    public ApiAccessGuard(@Value("${app.baseline.admin-token:}") String configuredToken) {
        this.configuredToken = configuredToken == null ? "" : configuredToken;
    }

    public void requireToken(String suppliedToken) {
        if (configuredToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "API token is not configured; set BASELINE_ADMIN_TOKEN in .env");
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid X-Admin-Token header is required");
        }
    }
}
