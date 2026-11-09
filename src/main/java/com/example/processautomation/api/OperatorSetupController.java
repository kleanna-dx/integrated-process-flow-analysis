package com.example.processautomation.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/operator-setup")
public class OperatorSetupController {
    private final OperatorSetupService setup;

    public OperatorSetupController(OperatorSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        return noStore(setup.status(session));
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> createRequest(
            HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "새로고침 후 다시 요청해 주세요.");
        return noStore(setup.createRequest(request, session, csrfToken));
    }

    @PostMapping(path = "/complete", consumes = "application/json")
    public ResponseEntity<Void> complete(
            HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody SetupRequest payload) {
        HttpSession session = request.getSession(false);
        if (session == null || payload == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록 요청 세션을 확인할 수 없습니다.");
        char[] password = payload.password() == null ? null : payload.password().toCharArray();
        char[] confirmation = payload.passwordConfirmation() == null ? null : payload.passwordConfirmation().toCharArray();
        try {
            setup.complete(request, session, csrfToken, payload.username(), password, confirmation);
            session.invalidate();
            return ResponseEntity.status(HttpStatus.NO_CONTENT).cacheControl(CacheControl.noStore()).build();
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record SetupRequest(String username, String password, String passwordConfirmation) { }
}
