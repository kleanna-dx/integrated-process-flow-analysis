package com.example.processautomation.api;

import com.example.processautomation.material.GeneralDiagramEditService;
import com.example.processautomation.material.UserWorkingCopyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.processautomation.material.GeneralDiagramEditService.EditInput;
import com.example.processautomation.material.GeneralDiagramEditService.Generated;
import com.example.processautomation.material.GeneralDiagramEditService.Preview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated, CSRF-protected TEST_ONLY diagram box editing, isolated per login session. */
@RestController
@RequestMapping("/api/workspace/diagram-editor")
public class OperatorDiagramEditController {
    private static final String PREVIEW = "operator.diagram-editor.preview";
    private static final String GENERATED = "operator.diagram-editor.generated";
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OperatorLoginService login;
    private final GeneralDiagramEditService edits;
    private final UserWorkingCopyService userWorkingCopies;
    private final ObjectMapper mapper;

    public OperatorDiagramEditController(OperatorLoginService login, GeneralDiagramEditService edits,
                                         UserWorkingCopyService userWorkingCopies, ObjectMapper mapper) {
        this.login = login;
        this.edits = edits;
        this.userWorkingCopies = userWorkingCopies;
        this.mapper = mapper;
    }

    @GetMapping("/working-copies")
    public ResponseEntity<List<UserWorkingCopyService.WorkingCopySummary>> userWorkingCopies(
            HttpSession session, @RequestParam long baselineDocumentId) {
        userWorkingCopies.requireEnabled();
        String username = login.requireAuthenticated(session);
        return noStore(userWorkingCopies.list(username, baselineDocumentId));
    }

    @PostMapping(path = "/working-copies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserWorkingCopyService.Registration> registerWorkingCopy(
            HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @RequestParam long baselineDocumentId, @RequestParam("file") MultipartFile file) {
        userWorkingCopies.requireEnabled();
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업본 파일을 선택하세요.");
        try {
            return noStore(userWorkingCopies.register(username, baselineDocumentId, file.getOriginalFilename(), file.getBytes()));
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드 작업본을 읽지 못했습니다.", exception);
        }
    }

    @GetMapping("/working-copies/{workingDocumentId}")
    public ResponseEntity<Map<String, Object>> workingCopy(HttpSession session, @PathVariable long workingDocumentId) {
        String username = login.requireAuthenticated(session);
        var copy = edits.safeWorkingCopy(workingDocumentId, username);
        return noStore(Map.ofEntries(Map.entry("workingDocumentId", copy.id()),
                Map.entry("baselineDocumentId", copy.baselineDocumentId()), Map.entry("processCode", copy.processCode()),
                Map.entry("filename", copy.filename()), Map.entry("conversionVersion", copy.conversionVersion()),
                Map.entry("conversionStatus", copy.conversionStatus()), Map.entry("sourceSha256", copy.sourceSha256()),
                Map.entry("workingSha256", copy.workingSha256()), Map.entry("workingCopyKind", copy.workingCopyKind()),
                Map.entry("registeredBy", copy.registeredBy()), Map.entry("versionNumber", copy.versionNumber()),
                Map.entry("previousWorkingDocumentId", copy.previousWorkingDocumentId() == null ? 0L : copy.previousWorkingDocumentId()),
                Map.entry("byteSize", copy.byteSize()), Map.entry("verificationStatus", copy.verificationStatus()),
                Map.entry("sheetMetadata", copy.sheetMetadataJson() == null ? List.of() : parseSheetMetadata(copy.sheetMetadataJson())),
                Map.entry("officialBaselineModified", false)));
    }

    @GetMapping("/working-copies/{workingDocumentId}/sheets")
    public ResponseEntity<List<Map<String, Object>>> sheets(HttpSession session, @PathVariable long workingDocumentId) {
        String username = login.requireAuthenticated(session);
        var copy = edits.safeWorkingCopy(workingDocumentId, username);
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(copy.bytes()))) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                var sheet = workbook.getSheetAt(index);
                result.add(Map.of("sheetName", sheet.getSheetName(), "sheetIndex", index,
                        "visibility", workbook.getSheetVisibility(index).name(),
                        "usedRange", sheet.getPhysicalNumberOfRows() == 0 ? "EMPTY" : "STRUCTURE_ANALYZED"));
            }
            return noStore(List.copyOf(result));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "작업본 시트 목록을 읽지 못했습니다.", exception);
        }
    }

    @GetMapping("/working-copies/{workingDocumentId}/locations")
    public ResponseEntity<List<GeneralDiagramEditService.VerifiedLocation>> locations(
            HttpSession session, @PathVariable long workingDocumentId, @RequestParam String sheetName) {
        String username = login.requireAuthenticated(session);
        if (sheetName == null || sheetName.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sheetName이 필요합니다.");
        List<GeneralDiagramEditService.VerifiedLocation> locations = edits.assessLocations(workingDocumentId, sheetName, username);
        return noStore(locations);
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Preview> preview(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody EditPreviewRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        EditInput input = new EditInput(body.workingDocumentId(), body.locationId(), body.sheetName(), body.managementNo(),
                body.equipmentName(), body.businessItemNo(), body.capacity(), body.capacityUnit(), body.quantity(),
                body.equipmentTypeLabel(), body.note());
        Preview preview = edits.preview(input, username);
        session.setAttribute(PREVIEW, preview);
        session.removeAttribute(GENERATED);
        return noStore(preview);
    }

    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> generate(HttpServletRequest request, HttpSession session,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody PreviewIdRequest body) {
        String username = login.requireAuthenticated(session);
        login.requireCsrf(request, session, csrfToken);
        Preview preview = (Preview) session.getAttribute(PREVIEW);
        if (preview == null || !preview.previewId().equals(body.previewId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 로그인 세션의 미리보기 요청을 찾지 못했습니다.");
        }
        Generated generated = edits.generate(preview, username);
        session.setAttribute(GENERATED, generated);
        Map<String, Object> generatedMetadata = new java.util.LinkedHashMap<>();
        generatedMetadata.put("previewId", generated.previewId());
        generatedMetadata.put("workingDocumentId", generated.workingDocumentId());
        generatedMetadata.put("processCode", generated.processCode());
        generatedMetadata.put("sheetName", generated.sheetName());
        generatedMetadata.put("insertedCellRange", generated.insertedCellRange());
        generatedMetadata.put("filename", filename(generated));
        generatedMetadata.put("sourceSha256", generated.sourceSha256());
        generatedMetadata.put("outputSha256", generated.outputSha256());
        generatedMetadata.put("mode", generated.mode());
        generatedMetadata.put("relationStatus", generated.relationStatus());
        generatedMetadata.put("connectionEvidenceStatus", generated.connectionEvidenceStatus());
        generatedMetadata.put("officialBaselineModified", false);
        generatedMetadata.put("interpretation", generated.interpretation());
        if (userWorkingCopies.enabled()) {
            var copy = edits.safeWorkingCopy(generated.workingDocumentId(), username);
            if (UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())) {
                var saved = userWorkingCopies.saveTestOnlyOutput(username, copy.id(), generated.sourceSha256(),
                        generated.outputSha256(), "ADD", filename(generated), generated.bytes());
                generatedMetadata.put("testOnlyOutputId", saved.outputId());
                generatedMetadata.put("testOnlyOutputStatus", saved.artifactKind());
            }
        }
        return noStore(generatedMetadata);
    }

    @GetMapping("/test-only-outputs/{outputId}/download")
    public ResponseEntity<byte[]> downloadSavedOutput(HttpSession session, @PathVariable long outputId) {
        userWorkingCopies.requireEnabled();
        String username = login.requireAuthenticated(session);
        var artifact = userWorkingCopies.downloadTestOnlyOutput(outputId, username);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(artifact.bytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", artifact.outputSha256());
        headers.set("X-Source-SHA256", userWorkingCopies.getForUser(artifact.workingDocumentId(), username).sourceSha256());
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Connection-Evidence", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(artifact.bytes());
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(HttpSession session, @RequestParam String previewId) {
        login.requireAuthenticated(session);
        Generated generated = (Generated) session.getAttribute(GENERATED);
        if (generated == null || !generated.previewId().equals(previewId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 로그인 세션의 TEST_ONLY 결과를 찾지 못했습니다.");
        }
        Preview preview = (Preview) session.getAttribute(PREVIEW);
        if (preview == null || !preview.previewId().equals(previewId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 로그인 세션의 미리보기 요청을 찾지 못했습니다.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename(generated), StandardCharsets.UTF_8).build());
        headers.setContentLength(generated.bytes().length);
        headers.setCacheControl("no-store");
        headers.set("X-Output-SHA256", generated.outputSha256());
        headers.set("X-Source-SHA256", generated.sourceSha256());
        headers.set("X-Official-Baseline-Modified", "false");
        headers.set("X-Scenario-Status", "TEST_ONLY");
        headers.set("X-Connection-Evidence", "NOT_VERIFIED");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(generated.bytes());
    }

    private String filename(Generated generated) {
        return safeFilename(generated.processCode()) + "_" + safeFilename(generated.sheetName())
                + "_TEST_ONLY_ADD_" + safeFilename(generated.insertedCellRange()) + ".xlsx";
    }

    private String safeFilename(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private List<UserWorkingCopyService.SheetMetadata> parseSheetMetadata(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "작업본 시트/Profile 메타데이터를 읽지 못했습니다.", exception); }
    }

    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    public record EditPreviewRequest(@Positive long workingDocumentId, @NotBlank String locationId,
                                     @NotBlank String sheetName, @NotBlank String managementNo,
                                     @NotBlank String equipmentName, @NotBlank String businessItemNo,
                                     @NotBlank String capacity, @NotBlank String capacityUnit,
                                     @NotBlank String quantity, @NotBlank String equipmentTypeLabel,
                                     String note) { }
    public record PreviewIdRequest(@NotBlank String previewId) { }
}
