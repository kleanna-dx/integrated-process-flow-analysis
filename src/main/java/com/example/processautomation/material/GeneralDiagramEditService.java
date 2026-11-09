package com.example.processautomation.material;

import com.example.processautomation.excel.DiagramEquipmentInsertionEngine;
import com.example.processautomation.excel.EquipmentBoxProfile;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.example.processautomation.model.MasterEquipment;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated-workflow support for template-driven layout-only equipment-box TEST_ONLY edits. */
@Service
public class GeneralDiagramEditService {
    private static final String UPLOAD_CONVERSION = "NUMERIC-WORKING-UPLOAD-V1";
    private static final String PW01_CANDIDATE_CONVERSION = "PW01-OFFICIAL-NUMERIC-V1";
    private static final String SUPPORTED_STATUS_UPLOAD = "CREATED_WITH_REVIEW";
    private static final String SUPPORTED_STATUS_CANDIDATE = "CREATED_WITH_REVIEW";
    private final NumericWorkingCopyService workingCopies;
    private final DiagramEditWorkingCopyRepository sources;
    private final ProcessDiagramStructureAnalyzer analyzer;
    private final DiagramEquipmentInsertionEngine engine;
    private final EquipmentBoxProfileRepository profiles;
    private final UserWorkingCopyService userWorkingCopies;

    @Autowired
    public GeneralDiagramEditService(NumericWorkingCopyService workingCopies, DiagramEditWorkingCopyRepository sources,
                                     ProcessDiagramStructureAnalyzer analyzer, DiagramEquipmentInsertionEngine engine,
                                     EquipmentBoxProfileRepository profiles, UserWorkingCopyService userWorkingCopies) {
        this.workingCopies = workingCopies;
        this.sources = sources;
        this.analyzer = analyzer;
        this.engine = engine;
        this.profiles = profiles;
        this.userWorkingCopies = userWorkingCopies;
    }

    public GeneralDiagramEditService(NumericWorkingCopyService workingCopies, DiagramEditWorkingCopyRepository sources,
                                     ProcessDiagramStructureAnalyzer analyzer, DiagramEquipmentInsertionEngine engine,
                                     EquipmentBoxProfileRepository profiles) {
        this(workingCopies, sources, analyzer, engine, profiles, null);
    }

    @Transactional(readOnly = true)
    public List<VerifiedLocation> assessLocations(long workingDocumentId) {
        return assessLocations(workingDocumentId, null, null);
    }

    @Transactional(readOnly = true)
    public List<VerifiedLocation> assessLocations(long workingDocumentId, String onlySheetName) {
        return assessLocations(workingDocumentId, onlySheetName, null);
    }

    @Transactional(readOnly = true)
    public List<VerifiedLocation> assessLocations(long workingDocumentId, String onlySheetName, String username) {
        var copy = safeWorkingCopy(workingDocumentId, username);
        ProcessDiagramStructureAnalyzer.WorkbookReport report = onlySheetName == null
                ? analyzer.analyze(copy.processCode(), copy.filename(), copy.workingSha256(), copy.bytes())
                : analyzer.analyzeSheet(copy.processCode(), copy.filename(), copy.workingSha256(), copy.bytes(), onlySheetName);
        List<VerifiedLocation> result = new ArrayList<>();
        for (var candidate : report.locationCandidates()) {
            if (onlySheetName != null && !onlySheetName.equals(candidate.sheetName())) continue;
            String locationId = locationId(copy.processCode(), candidate.sheetName(),
                    candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate());
            EquipmentBoxProfile profile = profiles.findFor(copy.processCode(), candidate.sheetName(), copy.sourceSha256()).orElse(null);
            boolean legacyFixedPlacement = profile != null && "LEGACY_ONLY".equals(profile.insertionPolicy())
                    && "PW-01".equals(copy.processCode()) && "PW-01-05".equals(candidate.sheetName())
                    && "C-PW15009".equals(candidate.precedingEquipmentCandidate())
                    && "C-PW15010".equals(candidate.followingEquipmentCandidate());
            if (profile == null) {
                String reason = profiles.findForSheet(copy.processCode(), candidate.sheetName()).isEmpty()
                        ? "PROFILE_MISSING_NO_FALLBACK_ALLOWED"
                        : "PROFILE_SOURCE_SHA_MISMATCH_NO_FALLBACK_ALLOWED";
                result.add(location(locationId, copy, candidate, "UNSUPPORTED", "UNSUPPORTED", reason,
                        null, List.of()));
                continue;
            }
            if (legacyFixedPlacement) {
                result.add(location(locationId, copy, candidate, profile.profileId(), "LEGACY_ONLY",
                        "LEGACY_FIXED_PLACEMENT_ONLY_GENERIC_ADD_BLOCKED", profile, profile.fields().keySet().stream().toList()));
                continue;
            }
            if (!candidate.overlappingMergedRanges().isEmpty() || candidate.drawingObjectsCrossingBand() != 0) {
                result.add(location(locationId, copy, candidate, profile.profileId(), "UNSUPPORTED",
                        "MERGED_OR_DRAWING_OBJECTS_OVERLAP_CANDIDATE", profile, profile.fields().keySet().stream().toList()));
                continue;
            }
            if (!legacyFixedPlacement && !profile.enabledForGenericAdd()) {
                result.add(location(locationId, copy, candidate, profile.profileId(), "REVIEW_REQUIRED",
                        profile.blocker() == null ? "PROFILE_NOT_VERIFIED" : profile.blocker(), profile,
                        profile.fields().keySet().stream().toList()));
                continue;
            }
            try {
                var plan = legacyFixedPlacement
                        ? engine.analyze(copy.bytes(), candidate.sheetName(), candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate())
                        : engine.analyze(copy.bytes(), candidate.sheetName(), candidate.precedingEquipmentCandidate(),
                                candidate.followingEquipmentCandidate(), profile, copy.processCode(), copy.sourceSha256());
                result.add(new VerifiedLocation(locationId, copy.processCode(), candidate.sheetName(),
                        candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate(),
                        plan.candidateCellRange(), plan.boxHeight(), plan.originalGapRows(), profile.profileId(),
                        "VERIFIED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", plan.connectionEvidenceStatus(),
                        legacyFixedPlacement ? "기존 고정 위치 레거시 경로만 허용합니다. 범용 엔진은 연결선을 생성하지 않습니다."
                                : "프로필·기준/작업본 SHA·필드 셀·서식·빈 후보·병합·DrawingML을 재검증했습니다. 물리 연결은 검증하지 않았습니다.",
                        profile.profileId(), profile.profileVersion(), profile.status(), profile.evidenceLevel(),
                        profile.evidence(), profile.fields().keySet().stream().toList(),
                        profile.fields().keySet().stream().toList(), plan.candidateCellRange(), profile.blocker()));
            } catch (RuntimeException exception) {
                String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                String status = reason.contains("MERGED") || reason.contains("DRAWING") || reason.contains("ANCHOR")
                        || reason.contains("OVERLAP") || reason.contains("UNSUPPORTED") ? "UNSUPPORTED" : "REVIEW_REQUIRED";
                result.add(location(locationId, copy, candidate, profile.profileId(), status,
                        reason.substring(0, Math.min(500, reason.length())), profile,
                        profile.fields().keySet().stream().toList()));
            }
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<VerifiedLocation> verifiedLocations(long workingDocumentId) {
        return assessLocations(workingDocumentId).stream()
                .filter(location -> "VERIFIED".equals(location.editabilityStatus())).toList();
    }

    public Preview preview(EditInput input) {
        return preview(input, null);
    }

    public Preview preview(EditInput input, String username) {
        validateInput(input);
        var copy = safeWorkingCopy(input.workingDocumentId(), username);
        VerifiedLocation location = assessLocations(copy.id(), input.sheetName(), username).stream()
                .filter(candidate -> candidate.locationId().equals(input.locationId())
                        && "VERIFIED".equals(candidate.editabilityStatus()))
                .findFirst().orElseThrow(() -> conflict("선택 위치가 현재 작업본에서 VERIFIED 상태로 재검증되지 않습니다."));
        if (!location.sheetName().equals(input.sheetName())) throw conflict("삽입 시트와 검증된 위치의 시트가 다릅니다.");
        if ("LEGACY_ONLY".equals(location.profileStatus()))
            throw conflict("PW-01-05는 기존 고정 위치 레거시 API 전용이며 범용 box-only 엔진으로 생성할 수 없습니다.");
        if (equipmentIdExists(copy.bytes(), input.managementNo())) throw conflict("설비번호가 작업본의 어느 시트에서든 이미 사용 중입니다.");
        return new Preview(UUID.randomUUID().toString(), copy.id(), copy.processCode(), copy.workingSha256(),
                input, location, true, "TEST_ONLY_READY", false, false, "TEST_ONLY", "REVIEW_REQUIRED", false,
                "위치·템플릿 기준 박스 추가만 검증했습니다. 신규 연결선은 그리지 않으며 실제 물질 흐름이나 연결 증거로 볼 수 없습니다.");
    }

    public Generated generate(Preview preview) {
        return generate(preview, null);
    }

    public Generated generate(Preview preview, String username) {
        if (preview == null || preview.previewId() == null || preview.previewId().isBlank()) throw conflict("유효한 미리보기 ID가 필요합니다.");
        var copy = safeWorkingCopy(preview.workingDocumentId(), username);
        if (!copy.processCode().equals(preview.processCode()) || !copy.workingSha256().equalsIgnoreCase(preview.workingSha256())) {
            throw conflict("미리보기 이후 작업본 SHA 또는 공정이 바뀌었습니다. 다시 미리보기를 진행하세요.");
        }
        VerifiedLocation location = assessLocations(copy.id(), preview.location().sheetName(), username).stream()
                .filter(candidate -> candidate.locationId().equals(preview.location().locationId())
                        && "VERIFIED".equals(candidate.editabilityStatus()))
                .findFirst().orElseThrow(() -> conflict("미리보기 위치가 생성 직전 재검증되지 않았습니다."));
        if (!location.equals(preview.location())) throw conflict("삽입 위치 구조가 미리보기 이후 달라졌습니다.");
        EditInput input = preview.input();
        if (equipmentIdExists(copy.bytes(), input.managementNo())) throw conflict("설비번호가 작업본에서 이미 사용 중입니다.");
        MasterEquipment equipment = new MasterEquipment(0, 0, input.managementNo(), input.businessItemNo(),
                copy.processCode(), copy.processCode(), "TEST_ONLY", input.equipmentName(), input.equipmentName(),
                input.equipmentTypeLabel(), input.capacity(), input.capacityUnit(), input.quantity(),
                "", "", "", "", "", "", "", "", "", "", "", "", Map.<String, String>of());
        EquipmentBoxProfile profile = profiles.findFor(copy.processCode(), location.sheetName(), copy.sourceSha256())
                .filter(candidate -> candidate.profileId().equals(location.profileId())
                        && candidate.profileVersion().equals(location.profileVersion()))
                .orElseThrow(() -> conflict("미리보기 프로필이 현재 기준 SHA에서 다시 확인되지 않습니다."));
        if (!profile.enabledForGenericAdd()) throw conflict("현재 프로필은 범용 ADD에 적용할 수 없습니다.");
        var result = engine.insertBoxOnlyTestOnly(copy.bytes(), equipment, location.sheetName(),
                location.sourceEquipmentId(), location.targetEquipmentId(), input.equipmentTypeLabel(),
                profile, copy.processCode(), copy.sourceSha256(), input.note());
        if (!copy.workingSha256().equalsIgnoreCase(result.sourceSha256())) throw conflict("생성 입력 SHA가 미리보기 작업본과 일치하지 않습니다.");
        return new Generated(preview.previewId(), copy.id(), copy.processCode(), location.sheetName(),
                result.plan().candidateCellRange(), result.workbookBytes(), result.sourceSha256(), result.outputSha256(),
                "TEST_ONLY", "REVIEW_REQUIRED", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE",
                "설비 박스만 생성했습니다. 연결선/배관을 만들거나 실제 물질 흐름을 승인하지 않았습니다.");
    }

    @Transactional(readOnly = true)
    public NumericWorkingCopyService.WorkingCopy safeWorkingCopy(long id) {
        return safeWorkingCopy(id, null);
    }

    @Transactional(readOnly = true)
    public NumericWorkingCopyService.WorkingCopy safeWorkingCopy(long id, String username) {
        var copy = userWorkingCopies == null ? workingCopies.getWorkingCopy(id)
                : userWorkingCopies.getAccessible(id, username);
        if (UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())) {
            if (username == null || username.isBlank() || userWorkingCopies == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "현재 사용자 소유 작업본만 사용할 수 있습니다.");
        }
        var source = sources.findLockedSource(copy.baselineDocumentId());
        if (source == null || !source.locked() || !"V1.0".equals(source.baselineVersion())
                || !"PROCESS".equals(source.documentRole()) || !source.processCode().equals(copy.processCode())
                || !source.sourceSha256().equalsIgnoreCase(copy.sourceSha256())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "작업본이 잠금 V1.0 공식 PROCESS 원본 및 source SHA에 연결되지 않아 편집하지 않습니다.");
        }
        boolean upload = UPLOAD_CONVERSION.equals(copy.conversionVersion())
                && SUPPORTED_STATUS_UPLOAD.equals(copy.conversionStatus());
        boolean pw01Candidate = PW01_CANDIDATE_CONVERSION.equals(copy.conversionVersion())
                && SUPPORTED_STATUS_CANDIDATE.equals(copy.conversionStatus()) && "PW-01".equals(copy.processCode());
        boolean userUpload = UserWorkingCopyService.CONVERSION_VERSION.equals(copy.conversionVersion())
                && UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                && username != null && username.equals(copy.registeredBy());
        if (!upload && !pw01Candidate && !userUpload) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "별도 검토 작업본 유형·상태가 허용되지 않습니다. 공식 원본은 편집하지 않습니다.");
        }
        return copy;
    }

    private boolean equipmentIdExists(byte[] bytes, String managementNo) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                if (managementNo.equals(formatter.formatCellValue(cell).trim())) return true;
            }
            return false;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "작업본의 중복 설비번호를 검사할 수 없어 편집을 차단했습니다.", exception);
        }
    }

    private void validateInput(EditInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비 추가 입력이 필요합니다.");
        if (input.workingDocumentId() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workingDocumentId가 필요합니다.");
        for (String value : List.of(input.locationId(), input.sheetName(), input.managementNo(), input.equipmentName(),
                input.businessItemNo(), input.capacity(), input.capacityUnit(), input.quantity(), input.equipmentTypeLabel())) {
            if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 설비 입력 필드가 비어 있습니다.");
        }
        if (!input.managementNo().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설비번호 형식이 올바르지 않습니다.");
        if (input.equipmentName().length() > 255 || input.businessItemNo().length() > 128
                || input.capacity().length() > 128 || input.capacityUnit().length() > 64
                || input.quantity().length() > 64 || input.equipmentTypeLabel().length() > 128
                || (input.note() != null && input.note().length() > 255)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 필드 길이 제한을 초과했습니다.");
        }
    }

    private VerifiedLocation location(String locationId, NumericWorkingCopyService.WorkingCopy copy,
                                      ProcessDiagramStructureAnalyzer.LocationCandidate candidate,
                                      String templateProfile, String status, String reason,
                                      EquipmentBoxProfile profile, List<String> fields) {
        return new VerifiedLocation(locationId, copy.processCode(), candidate.sheetName(),
                candidate.precedingEquipmentCandidate(), candidate.followingEquipmentCandidate(),
                candidate.candidateCellRange(), candidate.sourceBoxHeight(), candidate.gapRows(),
                templateProfile, status, "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "NOT_VERIFIED", reason,
                profile == null ? null : profile.profileId(), profile == null ? null : profile.profileVersion(),
                profile == null ? "UNSUPPORTED" : profile.status(), profile == null ? null : profile.evidenceLevel(),
                profile == null ? "" : profile.evidence(), fields == null ? List.of() : List.copyOf(fields),
                "VERIFIED".equals(status) && fields != null ? List.copyOf(fields) : List.of(),
                candidate.candidateCellRange(), profile == null ? reason : profile.blocker());
    }

    private String locationId(String process, String sheet, String source, String target) {
        return process + "|" + sheet.replaceAll("[^A-Za-z0-9._-]", "_") + "|" + source + "->" + target;
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record EditInput(long workingDocumentId, String locationId, String sheetName, String managementNo,
                            String equipmentName, String businessItemNo, String capacity, String capacityUnit,
                            String quantity, String equipmentTypeLabel, String note) { }
    public record VerifiedLocation(String locationId, String processCode, String sheetName,
                                   String sourceEquipmentId, String targetEquipmentId, String candidateCellRange,
                                   int boxHeight, int originalGapRows, String templateProfile,
                                   String editabilityStatus, String connectionEvidenceStatus, String connectionStatus,
                                   String editabilityReason, String profileId, String profileVersion,
                                   String profileStatus, String profileEvidenceLevel, String profileEvidence,
                                   List<String> profileFields, List<String> availableFields,
                                   String expectedChangeRange, String profileBlocker) {
        public VerifiedLocation {
            profileFields = profileFields == null ? List.of() : List.copyOf(profileFields);
            availableFields = availableFields == null ? List.of() : List.copyOf(availableFields);
        }
    }
    public record Preview(String previewId, long workingDocumentId, String processCode, String workingSha256,
                          EditInput input, VerifiedLocation location, boolean automaticEditAllowed,
                          String editPlanStatus, boolean automaticDiagramConnectionCreated,
                          boolean physicalConnectionVerified, String mode, String relationStatus,
                          boolean officialBaselineModified, String notice) { }
    public record Generated(String previewId, long workingDocumentId, String processCode, String sheetName,
                            String insertedCellRange, byte[] bytes, String sourceSha256, String outputSha256,
                            String mode, String relationStatus, String connectionEvidenceStatus, String interpretation) { }
}
