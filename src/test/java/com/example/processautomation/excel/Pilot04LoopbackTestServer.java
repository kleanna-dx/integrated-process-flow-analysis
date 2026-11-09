package com.example.processautomation.excel;

import com.example.processautomation.api.OperatorLoginService;
import com.example.processautomation.api.OperatorSessionController;
import com.example.processautomation.material.FlowMappingReviewService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * PILOT-04 only. This main class lives under src/test/java and starts an isolated,
 * loopback-bound Spring MVC server without component-scanning the production app.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class Pilot04LoopbackTestServer {
    static final String USERNAME = "pilot04-test-operator";
    static final Path PROJECT_ROOT = Path.of("/home/user/webapp");
    static final Path SOURCE_DIR = PROJECT_ROOT.resolve("validation-results/pilot-02-20261104");
    static final Path OUTPUT_DIR = PROJECT_ROOT.resolve("validation-results/pilot-04-20261104/generated");
    static final String API = "/test-only/pilot-04/modify";
    private static final String TOKEN_HEADER = "X-Pilot-04-Token";
    private static volatile String accessToken;

    record GenerateInput(String replacementText) { }

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("PILOT04_ENABLE_TEST_ONLY"))) {
            throw new IllegalStateException("PILOT04_ENABLE_TEST_ONLY=true is required for this test-scope server.");
        }
        String tokenFile = requireEnv("PILOT04_TOKEN_FILE");
        Path tokenPath = Path.of(tokenFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(tokenPath)) throw new IllegalStateException("Temporary token file is missing.");
        accessToken = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
        if (accessToken.length() < 40) throw new IllegalStateException("Temporary token is too short.");
        Path passwordPath = Path.of(requireEnv("PILOT04_TEST_PASSWORD_FILE")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(passwordPath)) throw new IllegalStateException("Temporary test credential file is missing.");
        requirePrivateFile(tokenPath);
        requirePrivateFile(passwordPath);

        SpringApplication app = new SpringApplication(Pilot04LoopbackTestServer.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAddCommandLineProperties(false);
        app.setDefaultProperties(Map.of(
                "server.servlet.session.cookie.http-only", "true",
                "server.servlet.session.cookie.same-site", "strict",
                "server.servlet.session.timeout", "30m",
                "spring.config.location", "optional:file:/dev/null",
                "spring.main.banner-mode", "off",
                "logging.level.root", "WARN",
                "pilot04.access-token", accessToken));
        ConfigurableApplicationContext context = app.run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        System.out.println("PILOT04_LOOPBACK_READY http://127.0.0.1:" + port + "/");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close, "pilot04-test-server-shutdown"));
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required test-only environment is missing: " + key);
        return value;
    }

    private static void requirePrivateFile(Path path) throws IOException {
        try {
            if (Files.getPosixFilePermissions(path).stream().anyMatch(permission ->
                    permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))) {
                throw new IllegalStateException("Temporary credential material must not be group/world accessible.");
            }
        } catch (UnsupportedOperationException exception) {
            throw new IllegalStateException("TEST_ONLY server requires POSIX file permissions.", exception);
        }
    }

    @Bean
    TomcatServletWebServerFactory pilot04ServletContainer() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        factory.setPort(0);
        return factory;
    }

    @Bean
    OperatorLoginService pilot04LoginService() {
        JdbcTemplate noDatabase = Mockito.mock(JdbcTemplate.class);
        String password;
        try {
            Path passwordPath = Path.of(requireEnv("PILOT04_TEST_PASSWORD_FILE")).toAbsolutePath().normalize();
            password = Files.readString(passwordPath, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read isolated test credential file.", exception);
        }
        OperatorLoginService encoder = new OperatorLoginService("", "", noDatabase);
        String hash = encoder.encodePassword(password.toCharArray());
        return new OperatorLoginService(USERNAME, hash, noDatabase);
    }

    @Bean
    OoxmlPackageInspector pilot04Inspector() {
        return new OoxmlPackageInspector();
    }

    @Bean
    DiagramBoxChangeEngine pilot04Engine(OoxmlPackageInspector inspector) {
        return new DiagramBoxChangeEngine(new OoxmlPartEditor(), inspector, new EquipmentBoxGeometryLocator());
    }

    @Bean
    Pilot03DynamicModifyIntegrationTest.DynamicWorkflow pilot04Workflow(
            DiagramBoxChangeEngine engine, OoxmlPackageInspector inspector) {
        return new Pilot03DynamicModifyIntegrationTest.DynamicWorkflow(engine, inspector, SOURCE_DIR, OUTPUT_DIR);
    }

    @Bean
    OperatorSessionController pilot04SessionController(OperatorLoginService login) {
        return new OperatorSessionController(login, Mockito.mock(FlowMappingReviewService.class));
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> pilot04AccessFilter(
            org.springframework.core.env.Environment environment) {
        String token = environment.getRequiredProperty("pilot04.access-token");
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Pilot04AccessFilter(token));
        registration.addUrlPatterns("/api/session/*", API + "/*");
        registration.setName("pilot04LoopbackTokenGate");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static final class Pilot04AccessFilter extends OncePerRequestFilter {
        private final byte[] expectedToken;

        Pilot04AccessFilter(String expectedToken) {
            this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!isLoopback(request.getRemoteAddr()) || !isLoopback(request.getLocalAddr())) {
                response.sendError(HttpStatus.NOT_FOUND.value());
                return;
            }
            if ("/".equals(request.getRequestURI())) {
                chain.doFilter(request, response);
                return;
            }
            String supplied = request.getHeader(TOKEN_HEADER);
            if (supplied == null || !MessageDigest.isEqual(expectedToken, supplied.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpStatus.NOT_FOUND.value());
                return;
            }
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin != null && !origin.equals("http://127.0.0.1:" + request.getServerPort())) {
                response.sendError(HttpStatus.FORBIDDEN.value());
                return;
            }
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (fetchSite != null && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
                response.sendError(HttpStatus.FORBIDDEN.value());
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean isLoopback(String address) {
            try {
                return InetAddress.getByName(address).isLoopbackAddress();
            } catch (Exception exception) {
                return false;
            }
        }
    }

    @RestController
    static final class Pilot04PageController {
        @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
        ResponseEntity<byte[]> page() throws IOException {
            byte[] html;
            try (var input = new ClassPathResource("pilot04.html").getInputStream()) {
                html = StreamUtils.copyToByteArray(input);
            }
            String securedHtml = new String(html, StandardCharsets.UTF_8)
                    .replace("__PILOT04_TOKEN__", accessToken);
            html = securedHtml.getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY")
                    .header("Referrer-Policy", "no-referrer")
                    .contentType(MediaType.TEXT_HTML).body(html);
        }
    }

    @RestController
    @RequestMapping(API)
    static final class Pilot04ModifyController {
        private final OperatorLoginService login;
        private final Pilot03DynamicModifyIntegrationTest.DynamicWorkflow workflow;
        private static final String PREVIEW_TEXTS = "pilot04.test-only.preview-texts";
        private final DiagramBoxChangeEngine engine;
        private final OoxmlPackageInspector inspector;
        private final Pilot03DynamicModifyIntegrationTest.TestOnlyController delegate;

        Pilot04ModifyController(OperatorLoginService login,
                Pilot03DynamicModifyIntegrationTest.DynamicWorkflow workflow,
                DiagramBoxChangeEngine engine, OoxmlPackageInspector inspector) {
            this.login = login;
            this.workflow = workflow;
            this.engine = engine;
            this.inspector = inspector;
            this.delegate = new Pilot03DynamicModifyIntegrationTest.TestOnlyController(login, workflow, true);
        }

        @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Map<String, Object>> preview(HttpServletRequest request, jakarta.servlet.http.HttpSession session,
                @RequestHeader(value = "X-CSRF-Token", required = false) String csrf,
                @RequestBody Pilot03DynamicModifyIntegrationTest.CaseInput input) {
            ResponseEntity<Map<String, Object>> response = delegate.preview(request, session, csrf, input);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("previewId")) {
                @SuppressWarnings("unchecked")
                Map<String, String> texts = (Map<String, String>) session.getAttribute(PREVIEW_TEXTS);
                if (texts == null) texts = new LinkedHashMap<>();
                texts.put(String.valueOf(body.get("previewId")), input.replacementText());
                session.setAttribute(PREVIEW_TEXTS, texts);
            }
            return response;
        }

        @PostMapping(path = "/generate/{previewId}", consumes = MediaType.ALL_VALUE)
        ResponseEntity<Map<String, Object>> generate(HttpServletRequest request, jakarta.servlet.http.HttpSession session,
                @RequestHeader(value = "X-CSRF-Token", required = false) String csrf,
                @PathVariable String previewId, @RequestBody GenerateInput input) {
            @SuppressWarnings("unchecked")
            Map<String, String> texts = (Map<String, String>) session.getAttribute(PREVIEW_TEXTS);
            if (texts == null || !texts.containsKey(previewId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview does not belong to this session.");
            }
            if (!texts.get(previewId).equals(input.replacementText())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Input changed after Preview; preview again.");
            }
            ResponseEntity<Map<String, Object>> response = delegate.generate(request, session, csrf, previewId);
            Map<String, Object> body = new LinkedHashMap<>(response.getBody());
            String timestamp = Instant.now().toString();
            body.put("serverGeneratedAt", timestamp);
            System.out.println("PILOT04_GENERATED at=" + timestamp
                    + " file=" + body.get("filename")
                    + " sha256=" + body.get("outputSha256")
                    + " previewId=" + previewId);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(body);
        }

        @GetMapping("/download/{previewId}")
        ResponseEntity<byte[]> download(jakarta.servlet.http.HttpSession session, @PathVariable String previewId)
                throws java.security.NoSuchAlgorithmException {
            ResponseEntity<byte[]> response = delegate.download(session, previewId);
            String outputSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.getBody()));
            System.out.println("PILOT04_DOWNLOADED at=" + Instant.now()
                    + " sha256=" + outputSha + " previewId=" + previewId);
            return response;
        }

        @PostMapping("/diagnostics/tampered-source")
        ResponseEntity<Map<String, Object>> rejectTamperedSource(HttpServletRequest request,
                jakarta.servlet.http.HttpSession session,
                @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) throws IOException {
            String owner = login.requireAuthenticated(session);
            login.requireCsrf(request, session, csrf);
            Files.createDirectories(OUTPUT_DIR);
            Path scratch = Files.createTempDirectory(OUTPUT_DIR, ".tampered-source-");
            Path source = SOURCE_DIR.resolve("P02-before-official-copy.xlsx");
            Path tampered = scratch.resolve(source.getFileName());
            try {
                byte[] bytes = Files.readAllBytes(source);
                bytes[bytes.length / 2] ^= 0x01;
                Files.write(tampered, bytes);
                var isolated = new Pilot03DynamicModifyIntegrationTest.DynamicWorkflow(engine, inspector, scratch, OUTPUT_DIR);
                isolated.preview(owner, "P-02", "SYNTHETIC_TEST_ONLY PILOT04 TAMPER");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "tampered source was unexpectedly accepted"));
            } catch (ResponseStatusException expected) {
                return ResponseEntity.status(expected.getStatusCode())
                        .cacheControl(CacheControl.noStore()).body(Map.of("status", expected.getStatusCode().value(),
                                "message", "Pinned source SHA mismatch was rejected."));
            } finally {
                Files.deleteIfExists(tampered);
                Files.deleteIfExists(scratch);
            }
        }
    }
}
