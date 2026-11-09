package com.example.processautomation.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperatorSetupService {
    private static final String REQUEST_SESSION_KEY = "operator.setup.request-id";
    private static final int REQUEST_TTL_SECONDS = 15 * 60;
    private static final int MAX_REQUESTS_PER_SESSION = 5;
    private static final long REQUEST_WINDOW_SECONDS = 15 * 60L;
    private static final String ATTEMPTS_SESSION_KEY = "operator.setup.request-attempts";

    private final JdbcTemplate jdbc;
    private final OperatorLoginService login;

    public OperatorSetupService(JdbcTemplate jdbc, OperatorLoginService login) {
        this.jdbc = jdbc;
        this.login = login;
    }

    public Map<String, Object> status(HttpSession session) {
        boolean configured = login.isConfigured();
        boolean available = login.isInitialSetupAllowed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("configured", configured);
        result.put("csrfToken", available ? login.csrfToken(session) : "");
        if (!available) {
            if (!configured) result.put("setupBlocked", true);
            return result;
        }
        String requestId = requestId(session);
        if (requestId == null) {
            result.put("requestState", "NONE");
            result.put("requestId", "");
            result.put("expiresAt", "");
            return result;
        }
        expireRequests();
        jdbc.query("SELECT state, expires_at FROM operator_setup_request WHERE request_id = ? AND browser_session_sha256 = ?",
                rs -> {
                    if (rs.next()) {
                        result.put("requestState", rs.getString("state"));
                        result.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
                    }
                    return null;
                }, requestId, sessionFingerprint(session));
        result.putIfAbsent("requestState", "NONE");
        result.put("requestId", requestId);
        result.putIfAbsent("expiresAt", "");
        return result;
    }

    @Transactional
    public Map<String, Object> createRequest(HttpServletRequest request, HttpSession session, String csrfToken) {
        login.requireCsrf(request, session, csrfToken);
        requireSetupAvailable();
        expireRequests();
        String fingerprint = sessionFingerprint(session);
        String existing = requestId(session);
        if (existing != null) {
            Map<String, Object> current = findBoundRequest(existing, fingerprint, false);
            if (current != null && ("PENDING".equals(current.get("requestState"))
                    || "APPROVED".equals(current.get("requestState")))) {
                current.put("csrfToken", login.csrfToken(session));
                return current;
            }
        }
        enforceRequestRateLimit(session);
        String requestId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(REQUEST_TTL_SECONDS);
        try {
            jdbc.update("INSERT INTO operator_setup_request (request_id, browser_session_sha256, state, active_slot, expires_at) VALUES (?, ?, 'PENDING', 1, ?)",
                    requestId, fingerprint, java.sql.Timestamp.from(expiresAt));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "다른 초기 계정 등록 요청이 대기 중입니다. 담당 관리자 확인 후 다시 시도해 주세요.");
        }
        request.changeSessionId();
        session.removeAttribute(OperatorLoginService.OPERATOR_SESSION_KEY);
        session.removeAttribute("operator.csrf");
        session.setAttribute(REQUEST_SESSION_KEY, requestId);
        session.setMaxInactiveInterval(REQUEST_TTL_SECONDS);
        String rotatedFingerprint = sessionFingerprint(session);
        jdbc.update("UPDATE operator_setup_request SET browser_session_sha256 = ? WHERE request_id = ? AND state = 'PENDING'",
                rotatedFingerprint, requestId);
        return Map.of("requestState", "PENDING", "requestId", requestId,
                "expiresAt", expiresAt.toString(), "csrfToken", login.csrfToken(session));
    }

    public Map<String, Object> pendingRequests() {
        expireRequests();
        return Map.of("requests", jdbc.query(
                "SELECT request_id, created_at, expires_at FROM operator_setup_request WHERE state = 'PENDING' AND expires_at > CURRENT_TIMESTAMP(6) ORDER BY created_at",
                (rs, rowNum) -> Map.of("requestId", rs.getString("request_id"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString(),
                        "expiresAt", rs.getTimestamp("expires_at").toInstant().toString())));
    }

    @Transactional
    public void approve(String requestId) {
        expireRequests();
        int updated = jdbc.update("UPDATE operator_setup_request SET state = 'APPROVED', approved_at = CURRENT_TIMESTAMP(6), approved_by = 'server-admin' WHERE request_id = ? AND state = 'PENDING' AND expires_at > CURRENT_TIMESTAMP(6)",
                requestId);
        if (updated == 1) return;
        throw requestStateError(requestId);
    }

    @Transactional
    public void reject(String requestId) {
        expireRequests();
        int updated = jdbc.update("UPDATE operator_setup_request SET state = 'REJECTED', active_slot = NULL WHERE request_id = ? AND state = 'PENDING' AND expires_at > CURRENT_TIMESTAMP(6)",
                requestId);
        if (updated != 1) throw requestStateError(requestId);
    }

    @Transactional
    public void complete(HttpServletRequest request, HttpSession session, String csrfToken,
                         String username, char[] password, char[] confirmation) {
        try {
            login.requireCsrf(request, session, csrfToken);
            requireSetupAvailable();
            if (username == null || !username.matches("[A-Za-z0-9._-]{3,80}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아이디는 영문, 숫자, 점, 밑줄, 하이픈으로 3~80자 입력해 주세요.");
            }
            if (password == null || confirmation == null || password.length < 14 || password.length > 128
                    || !Arrays.equals(password, confirmation)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 14~128자이며 확인 값과 같아야 합니다.");
            }
            String requestId = requestId(session);
            if (requestId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 브라우저에서 등록 요청을 찾지 못했습니다.");
            String fingerprint = sessionFingerprint(session);
            Map<String, Object> approved = findBoundRequest(requestId, fingerprint, true);
            if (approved == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 브라우저의 등록 요청을 확인할 수 없습니다.");
            String state = String.valueOf(approved.get("requestState"));
            if ("EXPIRED".equals(state)) throw new ResponseStatusException(HttpStatus.GONE, "등록 승인 시간이 만료됐습니다. 새 요청을 시작해 주세요.");
            if (!"APPROVED".equals(state)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 승인 전에는 계정을 설정할 수 없습니다.");
            String passwordHash = login.encodePassword(password);
            int inserted = jdbc.update("INSERT IGNORE INTO operator_account (id, username, password_hash) VALUES (1, ?, ?)", username, passwordHash);
            if (inserted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "초기 계정이 이미 설정됐거나 아이디를 사용할 수 없습니다.");
            int consumed = jdbc.update("UPDATE operator_setup_request SET state = 'CONSUMED', active_slot = NULL, consumed_at = CURRENT_TIMESTAMP(6) WHERE request_id = ? AND browser_session_sha256 = ? AND state = 'APPROVED' AND expires_at > CURRENT_TIMESTAMP(6)",
                    requestId, fingerprint);
            if (consumed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "등록 요청이 만료되었거나 이미 사용됐습니다.");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private Map<String, Object> findBoundRequest(String requestId, String fingerprint, boolean lock) {
        String sql = "SELECT state, expires_at FROM operator_setup_request WHERE request_id = ? AND browser_session_sha256 = ?"
                + (lock ? " FOR UPDATE" : "");
        BoundRequest row = jdbc.query(sql, rs -> rs.next()
                ? new BoundRequest(rs.getString("state"), rs.getTimestamp("expires_at").toInstant()) : null,
                requestId, fingerprint);
        if (row == null) return null;
        String state = row.state();
        if (!row.expiresAt().isAfter(Instant.now()) && ("PENDING".equals(state) || "APPROVED".equals(state))) {
            jdbc.update("UPDATE operator_setup_request SET state = 'EXPIRED', active_slot = NULL WHERE request_id = ? AND state IN ('PENDING', 'APPROVED')", requestId);
            state = "EXPIRED";
        }
        return new LinkedHashMap<>(Map.of("requestState", state, "expiresAt", row.expiresAt().toString()));
    }

    private void requireSetupAvailable() {
        if (!login.isInitialSetupAllowed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "초기 계정 설정이 이미 완료됐거나 서버 정책에서 차단됐습니다.");
    }

    private void expireRequests() {
        jdbc.update("UPDATE operator_setup_request SET state = 'EXPIRED', active_slot = NULL WHERE state IN ('PENDING', 'APPROVED') AND expires_at <= CURRENT_TIMESTAMP(6)");
    }

    private void enforceRequestRateLimit(HttpSession session) {
        synchronized (session) {
            Instant now = Instant.now();
            Object stored = session.getAttribute(ATTEMPTS_SESSION_KEY);
            RequestAttempts current = stored instanceof RequestAttempts value ? value : new RequestAttempts(now, 0);
            if (current.startedAt().plusSeconds(REQUEST_WINDOW_SECONDS).isBefore(now)) current = new RequestAttempts(now, 0);
            if (current.count() >= MAX_REQUESTS_PER_SESSION) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "등록 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
            }
            session.setAttribute(ATTEMPTS_SESSION_KEY, new RequestAttempts(current.startedAt(), current.count() + 1));
        }
    }

    private String requestId(HttpSession session) {
        Object value = session.getAttribute(REQUEST_SESSION_KEY);
        return value instanceof String id ? id : null;
    }

    private String sessionFingerprint(HttpSession session) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(session.getId().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to bind setup request to browser session", exception);
        }
    }

    private RuntimeException requestStateError(String requestId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM operator_setup_request WHERE request_id = ?", Integer.class, requestId);
        if (count == null || count == 0) return new ResponseStatusException(HttpStatus.NOT_FOUND, "등록 요청을 찾지 못했습니다.");
        String state = jdbc.queryForObject("SELECT state FROM operator_setup_request WHERE request_id = ?", String.class, requestId);
        if ("EXPIRED".equals(state)) return new ResponseStatusException(HttpStatus.GONE, "등록 요청의 유효기간이 만료됐습니다.");
        return new ResponseStatusException(HttpStatus.CONFLICT, "등록 요청이 이미 처리됐습니다.");
    }

    private record BoundRequest(String state, Instant expiresAt) { }
    private record RequestAttempts(Instant startedAt, int count) { }
}
