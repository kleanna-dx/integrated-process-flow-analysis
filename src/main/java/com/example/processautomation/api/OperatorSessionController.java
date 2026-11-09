package com.example.processautomation.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import com.example.processautomation.material.FlowMappingReviewService;
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
@RequestMapping("/api/session")
public class OperatorSessionController {
    private final OperatorLoginService login;
    private final FlowMappingReviewService flowMappingReview;

    public OperatorSessionController(OperatorLoginService login, FlowMappingReviewService flowMappingReview) {
        this.login = login;
        this.flowMappingReview = flowMappingReview;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean authenticated = login.isAuthenticated(session);
        Map<String, Object> result = Map.of(
                "configured", login.isConfigured(),
                "authenticated", authenticated,
                "username", authenticated ? String.valueOf(session.getAttribute(OperatorLoginService.OPERATOR_SESSION_KEY)) : "",
                "csrfToken", authenticated ? login.csrfToken(session) : "");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping(path = "/login", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> signIn(
            HttpServletRequest request,
            @RequestHeader(value = "X-Login-Request", required = false) String loginRequest,
            @RequestBody LoginRequest credentials) {
        requireSameOriginLogin(request, loginRequest);
        if (!login.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "로그인 계정이 설정되지 않았습니다. 관리자에게 계정 등록을 요청하세요.");
        }
        if (!login.loginAllowed(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "로그인 시도가 너무 많습니다. 15분 후 다시 시도해 주세요.");
        }
        if (credentials == null || credentials.username() == null || credentials.password() == null
                || credentials.username().length() > 80 || credentials.password().length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아이디와 비밀번호를 확인해 주세요.");
        }
        char[] password = credentials.password().toCharArray();
        if (!login.authenticate(credentials.username(), password)) {
            login.recordFailedLogin(request.getRemoteAddr());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해 주세요.");
        }
        login.recordSuccessfulLogin(request.getRemoteAddr());
        HttpSession session = request.getSession(true);
        String username = credentials.username().trim();
        login.rotateSession(request, session, username);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "authenticated", true,
                "username", username,
                "csrfToken", login.csrfToken(session)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> signOut(
            HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
        HttpSession session = request.getSession(false);
        login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        flowMappingReview.clearReviewDraftsForSession(session.getId());
        session.invalidate();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void requireSameOriginLogin(HttpServletRequest request, String loginRequest) {
        if (!"same-origin".equalsIgnoreCase(loginRequest)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "같은 화면에서 시작한 로그인만 허용됩니다.");
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 사이트에서 시작한 로그인은 허용되지 않습니다.");
        }
    }

    public record LoginRequest(String username, String password) { }
}
