package com.example.processautomation.api;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/operator-setup")
public class OperatorSetupAdminController {
    private final ApiAccessGuard access;
    private final OperatorSetupService setup;

    public OperatorSetupAdminController(ApiAccessGuard access, OperatorSetupService setup) {
        this.access = access;
        this.setup = setup;
    }

    @GetMapping("/requests")
    public ResponseEntity<Map<String, Object>> pending(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        access.requireToken(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.pendingRequests());
    }

    @PostMapping("/requests/{requestId}/approve")
    public ResponseEntity<Void> approve(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @PathVariable String requestId) {
        access.requireToken(token);
        setup.approve(requestId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/requests/{requestId}/reject")
    public ResponseEntity<Void> reject(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                       @PathVariable String requestId) {
        access.requireToken(token);
        setup.reject(requestId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
