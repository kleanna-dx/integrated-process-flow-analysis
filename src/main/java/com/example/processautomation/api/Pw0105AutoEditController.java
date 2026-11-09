package com.example.processautomation.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Blocks the legacy upload-and-edit shortcut; Step 6-2 requires a persisted analysis item and plan. */
@RestController
@RequestMapping("/api/admin/phase2/pw0105")
public class Pw0105AutoEditController {
    private final ApiAccessGuard accessGuard;

    public Pw0105AutoEditController(ApiAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    @PostMapping("/auto-edit/test-only")
    public void disabledLegacyShortcut(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        accessGuard.requireToken(token);
        throw new ResponseStatusException(HttpStatus.GONE,
                "The direct upload-and-edit shortcut is disabled. Upload through /api/admin/master-diff, then create a plan using changeRunId and changeItemId.");
    }
}
