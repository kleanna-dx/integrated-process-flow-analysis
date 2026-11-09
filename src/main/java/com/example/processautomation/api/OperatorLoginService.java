package com.example.processautomation.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OperatorLoginService {
    public static final String OPERATOR_SESSION_KEY = "operator.username";
    private static final String CSRF_SESSION_KEY = "operator.csrf";
    private static final int CONFIG_ITERATIONS = 310_000;
    private static final int DERIVED_KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FAILED_LOGINS = 8;
    private static final long LOGIN_WINDOW_SECONDS = 15 * 60L;

    private final Map<String, LoginAttempts> loginAttempts = new LinkedHashMap<>();
    private final String configuredUsername;
    private final String configuredPasswordHash;
    private final JdbcTemplate jdbc;

    public OperatorLoginService(
            @Value("${app.operator.username:}") String configuredUsername,
            @Value("${app.operator.password-hash:}") String configuredPasswordHash,
            JdbcTemplate jdbc) {
        this.configuredUsername = configuredUsername == null ? "" : configuredUsername.trim();
        this.configuredPasswordHash = configuredPasswordHash == null ? "" : configuredPasswordHash.trim();
        this.jdbc = jdbc;
    }

    public boolean isConfigured() {
        return credentials() != null;
    }

    public boolean isInitialSetupAllowed() {
        if (!configuredUsername.isBlank() || !configuredPasswordHash.isBlank()) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM operator_account WHERE id = 1", Integer.class);
        return count != null && count == 0;
    }

    public String configuredUsername() {
        Credentials credentials = credentials();
        return credentials == null ? "" : credentials.username();
    }

    public synchronized boolean loginAllowed(String remoteAddress) {
        cleanupAttempts();
        LoginAttempts attempts = loginAttempts.get(normalizeRemoteAddress(remoteAddress));
        return attempts == null || attempts.failures < MAX_FAILED_LOGINS;
    }

    public synchronized void recordFailedLogin(String remoteAddress) {
        cleanupAttempts();
        String address = normalizeRemoteAddress(remoteAddress);
        LoginAttempts attempts = loginAttempts.computeIfAbsent(address, ignored -> new LoginAttempts(Instant.now()));
        attempts.failures++;
        while (loginAttempts.size() > 512) loginAttempts.remove(loginAttempts.keySet().iterator().next());
    }

    public synchronized void recordSuccessfulLogin(String remoteAddress) {
        loginAttempts.remove(normalizeRemoteAddress(remoteAddress));
    }

    public boolean authenticate(String suppliedUsername, char[] suppliedPassword) {
        if (suppliedPassword == null) return false;
        Credentials credentials = credentials();
        if (credentials == null || suppliedUsername == null) {
            Arrays.fill(suppliedPassword, '\0');
            return false;
        }
        byte[] expectedUser = credentials.username().getBytes(StandardCharsets.UTF_8);
        byte[] actualUser = suppliedUsername.trim().getBytes(StandardCharsets.UTF_8);
        boolean userMatches = MessageDigest.isEqual(expectedUser, actualUser);
        byte[] derived = derive(suppliedPassword, credentials.passwordHash().salt(), credentials.passwordHash().iterations());
        boolean passwordMatches = MessageDigest.isEqual(credentials.passwordHash().digest(), derived);
        Arrays.fill(derived, (byte) 0);
        Arrays.fill(suppliedPassword, '\0');
        return userMatches & passwordMatches;
    }

    public String encodePassword(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] digest = derive(password, salt, CONFIG_ITERATIONS);
        Arrays.fill(password, '\0');
        String encoded = "pbkdf2_sha256:" + CONFIG_ITERATIONS + ":"
                + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(digest);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(digest, (byte) 0);
        return encoded;
    }

    public String csrfToken(HttpSession session) {
        Object current = session.getAttribute(CSRF_SESSION_KEY);
        if (current instanceof String token && !token.isBlank()) return token;
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random, (byte) 0);
        session.setAttribute(CSRF_SESSION_KEY, token);
        return token;
    }

    public void rotateSession(HttpServletRequest request, HttpSession session, String username) {
        request.changeSessionId();
        session.setAttribute(OPERATOR_SESSION_KEY, username);
        session.removeAttribute(CSRF_SESSION_KEY);
        csrfToken(session);
        session.setMaxInactiveInterval(30 * 60);
    }

    public boolean isAuthenticated(HttpSession session) {
        return session != null && session.getAttribute(OPERATOR_SESSION_KEY) instanceof String username
                && !username.isBlank();
    }

    public String requireAuthenticated(HttpSession session) {
        if (!isAuthenticated(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return String.valueOf(session.getAttribute(OPERATOR_SESSION_KEY));
    }

    public void requireCsrf(HttpServletRequest request, HttpSession session, String suppliedToken) {
        if (session == null || suppliedToken == null || suppliedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다. 화면을 새로고침해 주세요.");
        }
        Object expected = session.getAttribute(CSRF_SESSION_KEY);
        if (!(expected instanceof String expectedToken) || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다. 화면을 새로고침해 주세요.");
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 사이트에서 시작한 요청은 허용되지 않습니다.");
        }
    }

    private Credentials credentials() {
        if (!configuredUsername.isBlank() || !configuredPasswordHash.isBlank()) {
            PasswordHash parsed = PasswordHash.parse(configuredPasswordHash);
            return configuredUsername.isBlank() || parsed == null ? null : new Credentials(configuredUsername, parsed);
        }
        return databaseCredentials();
    }

    private Credentials databaseCredentials() {
        return jdbc.query("SELECT username, password_hash FROM operator_account WHERE id = 1", resultSet -> {
            if (!resultSet.next()) return null;
            String username = resultSet.getString("username");
            PasswordHash parsed = PasswordHash.parse(resultSet.getString("password_hash"));
            return username == null || username.isBlank() || parsed == null ? null : new Credentials(username, parsed);
        });
    }

    private synchronized void cleanupAttempts() {
        Instant cutoff = Instant.now().minusSeconds(LOGIN_WINDOW_SECONDS);
        loginAttempts.entrySet().removeIf(entry -> entry.getValue().startedAt.isBefore(cutoff));
    }

    private String normalizeRemoteAddress(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static final class LoginAttempts {
        private final Instant startedAt;
        private int failures;

        private LoginAttempts(Instant startedAt) { this.startedAt = startedAt; }
    }

    private record Credentials(String username, PasswordHash passwordHash) { }

    static String hashForTests(String password, byte[] salt, int iterations) {
        byte[] digest = derive(password.toCharArray(), salt, iterations);
        return "pbkdf2_sha256:" + iterations + ":"
                + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(digest);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, DERIVED_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify operator credential", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private record PasswordHash(int iterations, byte[] salt, byte[] digest) {
        private static PasswordHash parse(String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String[] parts = encoded.trim().split(":", -1);
                if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) return null;
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] digest = Base64.getDecoder().decode(parts[3]);
                if (iterations < 210_000 || iterations > 1_000_000 || salt.length < 16 || digest.length != 32) return null;
                return new PasswordHash(iterations, salt, digest);
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }
}
