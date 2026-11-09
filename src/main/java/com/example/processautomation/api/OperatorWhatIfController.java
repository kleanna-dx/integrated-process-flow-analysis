package com.example.processautomation.api;

import com.example.processautomation.material.DirectedProcessNetworkWorkbookExporter;
import com.example.processautomation.material.NetworkWhatIfRequest;
import com.example.processautomation.material.NetworkWhatIfService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace/material-balance/what-if")
public class OperatorWhatIfController {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OperatorLoginService login;
    private final NetworkWhatIfService whatIf;
    private final DirectedProcessNetworkWorkbookExporter exporter;

    public OperatorWhatIfController(OperatorLoginService login, NetworkWhatIfService whatIf,
                                    DirectedProcessNetworkWorkbookExporter exporter) {
        this.login = login;
        this.whatIf = whatIf;
        this.exporter = exporter;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness(HttpSession session) {
        login.requireAuthenticated(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(whatIf.actualReadiness());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NetworkWhatIfService.WhatIfReport> calculate(
            HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody NetworkWhatIfRequest payload) {
        login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(whatIf.calculate(payload));
    }

    @PostMapping(path = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(
            HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody NetworkWhatIfRequest payload) {
        login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        var report = whatIf.calculate(payload);
        byte[] bytes = exporter.createWhatIfWorkbook(report);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("what-if-" + safeFilename(payload.simulationId()) + "-TEST_ONLY.xlsx", StandardCharsets.UTF_8).build());
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-store");
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Calculation-Mode", "TEST_ONLY");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(bytes);
    }

    private String safeFilename(String value) {
        return value == null ? "simulation" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
