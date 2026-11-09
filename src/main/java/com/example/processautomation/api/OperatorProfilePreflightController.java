package com.example.processautomation.api;

import com.example.processautomation.material.ProfilePreflightService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpSession;

/** Owner-scoped read-only inspection endpoint; absent unless the isolated feature flag is explicitly enabled. */
@RestController
@Validated
@RequestMapping("/api/workspace/diagram-editor/profile-preflight")
@ConditionalOnExpression("'${app.profile-preflight.enabled:false}' == 'true' && '${app.user-working-copies.enabled:false}' == 'true'")
public class OperatorProfilePreflightController {
    private final OperatorLoginService login;
    private final ProfilePreflightService preflight;

    public OperatorProfilePreflightController(OperatorLoginService login, ProfilePreflightService preflight) {
        this.login = login;
        this.preflight = preflight;
    }

    @GetMapping
    public ResponseEntity<ProfilePreflightService.PreflightResult> inspect(
            HttpSession session,
            @RequestParam @Positive long workingDocumentId,
            @RequestParam @NotBlank String processCode,
            @RequestParam @NotBlank String sheetName,
            @RequestParam @NotBlank String managementNo) {
        String username = login.requireAuthenticated(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                preflight.inspect(workingDocumentId, processCode, sheetName, managementNo, username));
    }
}
