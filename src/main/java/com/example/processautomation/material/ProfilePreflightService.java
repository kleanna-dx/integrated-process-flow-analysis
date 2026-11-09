package com.example.processautomation.material;

import com.example.processautomation.excel.EquipmentBoxProfile;
import com.example.processautomation.excel.EquipmentBoxGeometryLocator;
import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import com.example.processautomation.excel.ProfileCandidatePreflight;
import com.example.processautomation.excel.ProfileCandidatePreflight.CandidateSpec;
import com.example.processautomation.excel.ProfileCandidatePreflight.DrawingResult;
import com.example.processautomation.excel.ProfileCandidatePreflight.FieldResult;
import com.example.processautomation.excel.ProfileCandidatePreflight.Finding;
import com.example.processautomation.excel.ProfileCandidatePreflight.RelativeRange;
import com.example.processautomation.excel.ProfileCandidatePreflight.StyleSnapshot;
import com.example.processautomation.material.ProfileSelectionPreflight.Candidate;
import com.example.processautomation.material.ProfileSelectionPreflight.Request;
import com.example.processautomation.material.ProfileSelectionPreflight.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicitly opt-in, owner-scoped, read-only presentation adapter for PILOT-07 candidate checks. */
@Service
@ConditionalOnExpression("'${app.profile-preflight.enabled:false}' == 'true' && '${app.user-working-copies.enabled:false}' == 'true'")
public class ProfilePreflightService {
    private static final String CANDIDATE_RESOURCE = "/profile-review-candidates.json";
    private static final String REVIEW_CANDIDATE_NOT_AUTHORIZED = "REVIEW_REQUIRED_CANDIDATE_NOT_AUTHORIZED";
    private static final String MODIFY_NOT_AUTHORIZED = "MODIFY_NOT_AUTHORIZED_BY_PREFLIGHT";
    private static final String CONNECTION_UNVERIFIED = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";

    private final UserWorkingCopyService userWorkingCopies;
    private final DiagramEditWorkingCopyRepository sources;
    private final EquipmentBoxProfileRepository profiles;
    private final OoxmlPackageInspector inspector;
    private final ProfileCandidatePreflight preflight;
    private final ProfileSelectionPreflight selector;
    private final Map<CandidateKey, CandidateSpec> candidates;
    private final boolean enabled;

    public ProfilePreflightService(UserWorkingCopyService userWorkingCopies,
                                   DiagramEditWorkingCopyRepository sources,
                                   EquipmentBoxProfileRepository profiles,
                                   ObjectMapper mapper,
                                   @org.springframework.beans.factory.annotation.Value("${app.profile-preflight.enabled:false}")
                                   boolean enabled) {
        this.userWorkingCopies = Objects.requireNonNull(userWorkingCopies);
        this.sources = Objects.requireNonNull(sources);
        this.profiles = Objects.requireNonNull(profiles);
        this.inspector = new OoxmlPackageInspector();
        this.preflight = new ProfileCandidatePreflight(inspector, new EquipmentBoxGeometryLocator());
        this.selector = new ProfileSelectionPreflight();
        this.candidates = loadCandidates(Objects.requireNonNull(mapper));
        this.enabled = enabled;
    }

    @Transactional(readOnly = true)
    public PreflightResult inspect(long workingDocumentId, String processCode, String sheetName,
                                   String managementNo, String username) {
        if (!enabled || !userWorkingCopies.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Profile 사전검증은 명시적으로 격리 환경에서 활성화해야 합니다.");
        }
        if (workingDocumentId <= 0 || blank(processCode) || blank(sheetName) || blank(managementNo) || blank(username)
                || processCode.length() > 16 || !processCode.matches("(?:P|PU|PW)-\\d{2}(?:-\\d{2})?")
                || sheetName.length() > 128 || sheetName.chars().anyMatch(Character::isISOControl)
                || !managementNo.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업본·공정·시트·설비 요청값이 잘못됐습니다.");
        }

        NumericWorkingCopyService.WorkingCopy copy = userWorkingCopies.getForUser(workingDocumentId, username);
        if (!UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                || !username.equals(copy.registeredBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "현재 로그인 사용자의 사용자 작업본을 찾지 못했습니다.");
        }
        String workingSha = sha256(copy.bytes());
        boolean workingShaVerified = workingSha.equalsIgnoreCase(copy.workingSha256());
        if (!workingShaVerified) {
            return blocked(processCode, sheetName, managementNo, copy, "WORKING_COPY_SHA_MISMATCH",
                    false, false, false);
        }
        if (!processCode.equals(copy.processCode())) {
            return blocked(processCode, sheetName, managementNo, copy, "WORKING_COPY_PROCESS_MISMATCH",
                    false, true, false);
        }

        var source = sources.findLockedSourceWorkbook(copy.baselineDocumentId()).orElse(null);
        if (source == null || !source.locked() || !"V1.0".equals(source.baselineVersion())
                || !"PROCESS".equals(source.documentRole()) || !processCode.equals(source.processCode())
                || !copy.sourceSha256().equalsIgnoreCase(source.sourceSha256()) || source.bytes() == null) {
            return blocked(processCode, sheetName, managementNo, copy, "LOCKED_SOURCE_IDENTITY_INVALID",
                    false, true, false);
        }
        String sourceSha = sha256(source.bytes());
        boolean sourceShaVerified = sourceSha.equalsIgnoreCase(source.sourceSha256());
        if (!sourceShaVerified) {
            return blocked(processCode, sheetName, managementNo, copy, "SOURCE_SHA_MISMATCH",
                    false, true, false);
        }
        CandidateSpec candidate = candidates.get(new CandidateKey(processCode, sheetName, managementNo));
        if (candidate == null) {
            return blocked(processCode, sheetName, managementNo, copy, "PROFILE_CANDIDATE_NOT_SUPPORTED",
                    true, true, false);
        }
        boolean candidateShaVerified = workingSha.equalsIgnoreCase(candidate.workingCopySha256())
                && sourceSha.equalsIgnoreCase(candidate.sourceSha256());
        if (!candidateShaVerified) {
            return blocked(processCode, sheetName, managementNo, copy, "PROFILE_CANDIDATE_SHA_MISMATCH",
                    true, true, false);
        }

        EquipmentBoxProfile existingProfile = profiles.findFor(processCode, sheetName, sourceSha).orElse(null);
        if (existingProfile == null || !candidate.existingProfileId().equals(existingProfile.profileId())
                || !candidate.existingProfileVersion().equals(existingProfile.profileVersion())) {
            return blocked(processCode, sheetName, managementNo, copy, "PROFILE_IDENTITY_MISMATCH",
                    true, true, true);
        }

        ProfileCandidatePreflight.Report report = preflight.inspect(source.bytes(), copy.bytes(),
                new ProfileCandidatePreflight.Request(processCode, sheetName, managementNo, sourceSha),
                candidate, existingProfile);
        List<Candidate> selectionCandidates = List.of(
                selectionCandidate(existingProfile, processCode, sheetName, managementNo, sourceSha),
                selectionCandidate(candidate, processCode, sheetName, managementNo));
        Result selection = selector.evaluate(new Request(processCode, sheetName, managementNo,
                sourceSha, workingSha, null, null), selectionCandidates);

        return present(copy, candidate, existingProfile, report, selection, sourceSha, workingSha,
                sourceShaVerified, workingShaVerified, candidateShaVerified);
    }

    private Candidate selectionCandidate(EquipmentBoxProfile profile, String processCode, String sheetName,
                                         String managementNo, String sourceSha) {
        return new Candidate(profile.profileId(), profile.profileVersion(), processCode, sheetName, managementNo,
                sourceSha, profile.verifiedWorkingSha256(), profile.status(), "VERIFIED".equals(profile.status()));
    }

    private Candidate selectionCandidate(CandidateSpec candidate, String processCode, String sheetName,
                                         String managementNo) {
        return new Candidate(candidate.candidateId(), candidate.existingProfileVersion(), processCode, sheetName,
                managementNo, candidate.sourceSha256(), candidate.workingCopySha256(), candidate.status(),
                candidate.registered());
    }

    private PreflightResult present(NumericWorkingCopyService.WorkingCopy copy, CandidateSpec candidate,
                                    EquipmentBoxProfile profile, ProfileCandidatePreflight.Report report,
                                    Result selection, String sourceSha, String workingSha,
                                    boolean sourceShaVerified, boolean workingShaVerified, boolean candidateShaVerified) {
        List<String> codes = new ArrayList<>();
        List<String> modifyBlockers = new ArrayList<>();
        List<String> approvals = new ArrayList<>();
        List<FindingReview> findingReviews = new ArrayList<>(report.findings().stream()
                .map(finding -> new FindingReview(finding.code(), finding.severity(), finding.detail())).toList());
        report.findings().forEach(finding -> addFinding(finding, codes, modifyBlockers, approvals));
        List<DrawingReview> drawingReviews = new ArrayList<>(report.drawingObjects().stream()
                .map(drawing -> drawingReview(drawing, "TARGET_BOX", null, null)).toList());
        inspectProfileReferenceDrawings(copy.bytes(), candidate, drawingReviews, findingReviews,
                codes, modifyBlockers, approvals);
        selection.blockingConditions().forEach(code -> {
            addUnique(codes, code);
            addUnique(modifyBlockers, code);
        });
        if (!"VERIFIED".equals(candidate.status()) || !candidate.registered() || !candidate.activationAllowed()) {
            addUnique(codes, REVIEW_CANDIDATE_NOT_AUTHORIZED);
            addUnique(modifyBlockers, REVIEW_CANDIDATE_NOT_AUTHORIZED);
            addUnique(approvals, "INDEPENDENT_HUMAN_REVIEW_AND_EXPLICIT_APPROVAL_REQUIRED");
        }
        addUnique(modifyBlockers, MODIFY_NOT_AUTHORIZED);
        boolean selectionBlocked = "BLOCKED".equals(selection.status());
        String decision = "BLOCKED".equals(report.decisionStatus()) || selectionBlocked ? "BLOCKED" : "REVIEW_REQUIRED";
        return new PreflightResult("READ_ONLY_REVIEW_ONLY", copy.id(), copy.processCode(), report.sheetName(),
                report.managementNo(), sourceSha, workingSha, sourceShaVerified, workingShaVerified,
                candidateShaVerified, true, report.candidateId(),
                report.candidateStatus(), profile.profileId(), profile.profileVersion(), profile.status(),
                report.structuralStatus(), decision, selection.status(), selection.consideredCandidateIds(),
                selection.blockingConditions(), false, CONNECTION_UNVERIFIED,
                report.fields().stream().map(this::fieldReview).toList(),
                new MergeReview(candidate.expectedLiveMerges(), report.liveMergedRanges(),
                        candidate.templateRelativeMerges(), candidate.expectedLiveMerges().equals(report.liveMergedRanges()),
                        !candidate.expectedLiveMerges().equals(candidate.templateRelativeMerges())),
                drawingReviews, findingReviews, codes, modifyBlockers, approvals);
    }

    private PreflightResult blocked(String processCode, String sheetName, String managementNo,
                                   NumericWorkingCopyService.WorkingCopy copy, String code,
                                   boolean sourceShaVerified, boolean workingShaVerified, boolean candidateShaVerified) {
        return new PreflightResult("READ_ONLY_REVIEW_ONLY", copy.id(), copy.processCode(), sheetName, managementNo,
                copy.sourceSha256(), copy.workingSha256(), sourceShaVerified, workingShaVerified,
                candidateShaVerified, false, null, "REVIEW_REQUIRED", null, null, null,
                "REVIEW_REQUIRED", "BLOCKED", "BLOCKED", List.of(), List.of(code), false,
                CONNECTION_UNVERIFIED, List.of(), new MergeReview(List.of(), List.of(), List.of(), false, false),
                List.of(), List.of(new FindingReview(code, "BLOCKED", "요청 대상과 현재 사용자 작업본/Profile 기준이 일치하지 않습니다.")),
                List.of(code), List.of(code, MODIFY_NOT_AUTHORIZED),
                List.of("IDENTITY_OR_SHA_MISMATCH_REQUIRES_REVIEW"));
    }

    private FieldReview fieldReview(FieldResult field) {
        return new FieldReview(field.field(), field.cellAddress(), field.observedExpectedCurrentValue(),
                field.actualCurrentValue(), field.currentValueMatches(), field.existingProfileExpectedStyleId(),
                field.actualStyleId(), field.existingProfileExpectedRowHeightPt(), field.actualRowHeightPt(),
                field.actualStyleProperties(), field.existingProfileReferenceStyle(), field.status(),
                field.blockingConditions());
    }

    private DrawingReview drawingReview(DrawingResult drawing, String context, String referenceCell, String field) {
        return new DrawingReview(context, referenceCell, field, drawing.objectId(), drawing.objectType(),
                drawing.startRow(), drawing.startColumn(), drawing.endRow(), drawing.endColumn(),
                drawing.overlappingFields(), drawing.classification());
    }

    private void inspectProfileReferenceDrawings(byte[] bytes, CandidateSpec candidate,
            List<DrawingReview> drawings, List<FindingReview> findings,
            List<String> codes, List<String> blockers, List<String> approvals) {
        try {
            var inventory = inspector.inspectSheet(bytes, candidate.sheetName());
            for (var field : candidate.fields()) {
                if (blank(field.existingProfileReferenceCell())) continue;
                var reference = new org.apache.poi.ss.util.CellReference(field.existingProfileReferenceCell());
                for (WorkbookInventory.DrawingObject drawing : inventory.drawingObjects()) {
                    if (drawing.startRow() == null || drawing.endRow() == null
                            || drawing.startColumn() == null || drawing.endColumn() == null) continue;
                    int top = Math.min(drawing.startRow(), drawing.endRow());
                    int bottom = Math.max(drawing.startRow(), drawing.endRow());
                    int left = Math.min(drawing.startColumn(), drawing.endColumn());
                    int right = Math.max(drawing.startColumn(), drawing.endColumn());
                    if (reference.getRow() < top || reference.getRow() > bottom
                            || reference.getCol() < left || reference.getCol() > right) continue;
                    drawings.add(drawingReview(new DrawingResult(drawing.objectIndex(), drawing.shapeId(),
                            drawing.objectName(), drawing.objectType(), drawing.startRow(), drawing.startColumn(),
                            drawing.startColumnOffset(), drawing.startRowOffset(), drawing.endRow(), drawing.endColumn(),
                            drawing.endColumnOffset(), drawing.endRowOffset(), drawing.startConnectedShapeId(),
                            drawing.endConnectedShapeId(), drawing.headArrowType(), drawing.tailArrowType(),
                            drawing.transformXEmu(), drawing.transformYEmu(), drawing.transformWidthEmu(),
                            drawing.transformHeightEmu(), List.of(field.field()), "PROFILE_REFERENCE_FIELD_OVERLAP"),
                            "PROFILE_REFERENCE_FIELD", field.existingProfileReferenceCell(), field.field()));
                    String code = "DRAWINGML_PROFILE_REFERENCE_FIELD_OVERLAP";
                    addUnique(codes, code);
                    addUnique(blockers, code);
                    addUnique(approvals, "PROFILE_REFERENCE_DRAWING_INTERFERENCE_REVIEW");
                    findings.add(new FindingReview(code, "BLOCKED", "Profile reference cell "
                            + field.existingProfileReferenceCell() + " overlaps DrawingML object "
                            + drawing.shapeId() + "; endpoint or physical connection is not inferred."));
                }
            }
        } catch (RuntimeException exception) {
            String code = "PROFILE_REFERENCE_DRAWING_INSPECTION_FAILED";
            addUnique(codes, code);
            addUnique(blockers, code);
            addUnique(approvals, "DRAWINGML_INSPECTION_REQUIRES_HUMAN_REVIEW");
            findings.add(new FindingReview(code, "BLOCKED",
                    "Profile reference DrawingML could not be inspected; MODIFY remains blocked."));
        }
    }

    private void addFinding(Finding finding, List<String> codes, List<String> blockers, List<String> approvals) {
        addUnique(codes, finding.code());
        if ("BLOCKED".equals(finding.severity())) addUnique(blockers, finding.code());
        if ("REVIEW_REQUIRED".equals(finding.severity())) {
            addUnique(blockers, finding.code());
            addUnique(approvals, finding.code());
        }
    }

    private void addUnique(List<String> list, String value) {
        if (value != null && !list.contains(value)) list.add(value);
    }

    private Map<CandidateKey, CandidateSpec> loadCandidates(ObjectMapper mapper) {
        try (InputStream input = ProfilePreflightService.class.getResourceAsStream(CANDIDATE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Profile candidate catalog is unavailable");
            List<CandidateSpec> loaded = mapper.readValue(input, new TypeReference<>() { });
            Map<CandidateKey, CandidateSpec> indexed = loaded.stream().collect(Collectors.toMap(
                    candidate -> new CandidateKey(candidate.processCode(), candidate.sheetName(), candidate.managementNo()),
                    Function.identity(), (first, second) -> { throw new IllegalStateException("Duplicate Profile candidate identity"); },
                    LinkedHashMap::new));
            return Map.copyOf(indexed);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load read-only Profile candidate catalog", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record CandidateKey(String processCode, String sheetName, String managementNo) { }

    public record PreflightResult(String applicationStatus, long workingDocumentId, String processCode,
                                  String sheetName, String managementNo, String sourceSha256,
                                  String workingCopySha256, boolean sourceShaVerified,
                                  boolean workingCopyShaVerified, boolean candidateShaVerified,
                                  boolean requestIdentityVerified, String candidateId, String candidateStatus, String profileId,
                                  String profileVersion, String profileStatus, String structuralStatus,
                                  String decisionStatus, String profileSelectionStatus,
                                  List<String> consideredCandidateIds, List<String> profileSelectionBlockers,
                                  boolean modifyAuthorized, String connectionEvidenceStatus,
                                  List<FieldReview> fields, MergeReview mergeStructure,
                                  List<DrawingReview> drawingInterference, List<FindingReview> findings,
                                  List<String> errorCodes, List<String> modifyBlockedReasons,
                                  List<String> additionalApprovalRequirements) {
        public PreflightResult {
            consideredCandidateIds = List.copyOf(consideredCandidateIds);
            profileSelectionBlockers = List.copyOf(profileSelectionBlockers);
            fields = List.copyOf(fields);
            drawingInterference = List.copyOf(drawingInterference);
            findings = List.copyOf(findings);
            errorCodes = List.copyOf(errorCodes);
            modifyBlockedReasons = List.copyOf(modifyBlockedReasons);
            additionalApprovalRequirements = List.copyOf(additionalApprovalRequirements);
        }
    }

    public record FieldReview(String field, String cellAddress, String expectedCurrentValue,
                              String actualCurrentValue, boolean currentValueMatches,
                              Integer expectedProfileStyleId, Integer actualStyleId,
                              Float expectedProfileRowHeightPt, Float actualRowHeightPt,
                              StyleSnapshot actualStyle, StyleSnapshot profileReferenceStyle,
                              String status, List<String> blockingConditions) {
        public FieldReview { blockingConditions = List.copyOf(blockingConditions); }
    }

    public record MergeReview(List<RelativeRange> expectedCandidateRanges,
                              List<RelativeRange> actualWorkingCopyRanges,
                              List<RelativeRange> profileTemplateRanges,
                              boolean actualMatchesCandidateSnapshot,
                              boolean candidateDiffersFromProfileTemplate) {
        public MergeReview {
            expectedCandidateRanges = List.copyOf(expectedCandidateRanges);
            actualWorkingCopyRanges = List.copyOf(actualWorkingCopyRanges);
            profileTemplateRanges = List.copyOf(profileTemplateRanges);
        }
    }

    public record DrawingReview(String context, String referenceCell, String field, String objectId,
                                String objectType, Integer startRow, Integer startColumn,
                                Integer endRow, Integer endColumn, List<String> overlappingFields,
                                String classification) {
        public DrawingReview { overlappingFields = List.copyOf(overlappingFields); }
    }

    public record FindingReview(String code, String severity, String detail) { }
}
