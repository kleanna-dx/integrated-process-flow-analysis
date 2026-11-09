package com.example.processautomation.material;

import com.example.processautomation.excel.DiagramBoxChangeEngine;
import com.example.processautomation.excel.DeleteImpactAnalyzer;
import com.example.processautomation.excel.EquipmentBoxProfile;
import com.example.processautomation.excel.ProcessDiagramStructureAnalyzer;
import com.example.processautomation.excel.InvalidWorkbookException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** TEST_ONLY master-linked MODIFY workflow. No business master or official workbook row is updated. */
@Service
public class DiagramModifyService {
    private static final String CONNECTION_UNVERIFIED = "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE";
    private static final Map<String, Integer> MAX_FIELD_LENGTHS = Map.of("businessItemNo", 128);
    private final NumericWorkingCopyService workingCopies;
    private final GeneralDiagramEditService workingCopyGuard;
    private final DiagramEditWorkingCopyRepository sources;
    private final DiagramBoxChangeEngine engine;
    private final JdbcTemplate jdbc;
    private final EquipmentBoxProfileRepository profiles;
    private final ProcessDiagramStructureAnalyzer analyzer;
    private final DeleteImpactAnalyzer deleteAnalyzer;
    private final UserWorkingCopyService userWorkingCopies;
    private final ChangeProposalService proposals;

    @Autowired
    public DiagramModifyService(NumericWorkingCopyService workingCopies, GeneralDiagramEditService workingCopyGuard,
                                DiagramEditWorkingCopyRepository sources, DiagramBoxChangeEngine engine,
                                JdbcTemplate jdbc, EquipmentBoxProfileRepository profiles,
                                ProcessDiagramStructureAnalyzer analyzer, DeleteImpactAnalyzer deleteAnalyzer,
                                UserWorkingCopyService userWorkingCopies, ChangeProposalService proposals) {
        this.workingCopies = workingCopies;
        this.workingCopyGuard = workingCopyGuard;
        this.sources = sources;
        this.engine = engine;
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.analyzer = analyzer;
        this.deleteAnalyzer = deleteAnalyzer;
        this.userWorkingCopies = userWorkingCopies;
        this.proposals = proposals;
    }

    public DiagramModifyService(NumericWorkingCopyService workingCopies, GeneralDiagramEditService workingCopyGuard,
                                DiagramEditWorkingCopyRepository sources, DiagramBoxChangeEngine engine,
                                JdbcTemplate jdbc, EquipmentBoxProfileRepository profiles,
                                ProcessDiagramStructureAnalyzer analyzer) {
        this(workingCopies, workingCopyGuard, sources, engine, jdbc, profiles, analyzer, null, null, null);
    }

    @Transactional(readOnly = true)
    public EquipmentProfile inspect(long workingDocumentId, String sheetName, String managementNo) {
        return inspect(workingDocumentId, sheetName, managementNo, null);
    }

    @Transactional(readOnly = true)
    public EquipmentProfile inspect(long workingDocumentId, String sheetName, String managementNo, String username) {
        var copy = safeCopy(workingDocumentId, username);
        requireText(sheetName, "sheetName");
        requireText(managementNo, "managementNo");
        if (UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())) {
            EquipmentBoxProfile profile = exactModifyProfile(copy, sheetName);
            var mapping = engine.mapFieldsByProfile(copy.bytes(), copy.processCode(), sheetName, managementNo,
                    profile, copy.sourceSha256(), copy.workingSha256());
            return new EquipmentProfile(copy.id(), copy.processCode(), sheetName, managementNo, copy.workingSha256(),
                    current(mapping, "businessItemNo"), current(mapping, "equipmentName"), mapping, "VERIFIED",
                    CONNECTION_UNVERIFIED,
                    "Exact working-copy SHA and relative Profile cells/styles were revalidated. Only available Profile fields may be changed; physical connections remain unverified.");
        }
        MasterEquipmentRow master = findMappedMaster(copy, sheetName, managementNo);
        var mapping = engine.mapFieldsByMaster(copy.bytes(), sheetName, managementNo, master.values());
        return new EquipmentProfile(copy.id(), copy.processCode(), sheetName, managementNo, copy.workingSha256(),
                master.itemNo(), master.equipmentName(), mapping, "REVIEW_REQUIRED", CONNECTION_UNVERIFIED,
                "Master mapping confirms only the listed exact identity mapping; no connection or flow evidence is inferred.");
    }

    @Transactional(readOnly = true)
    public List<ModifyEquipmentOption> listVerifiedPilotEquipment(long workingDocumentId, String sheetName) {
        return listVerifiedPilotEquipment(workingDocumentId, sheetName, null);
    }

    @Transactional(readOnly = true)
    public List<ModifyEquipmentOption> listVerifiedPilotEquipment(long workingDocumentId, String sheetName, String username) {
        var copy = safeCopy(workingDocumentId, username);
        requireText(sheetName, "sheetName");
        if (UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())) {
            EquipmentBoxProfile profile = exactModifyProfile(copy, sheetName);
            var report = analyzer.analyzeSheet(copy.processCode(), copy.filename(), copy.workingSha256(), copy.bytes(), sheetName);
            List<ModifyEquipmentOption> options = new java.util.ArrayList<>();
            for (var candidate : report.equipmentCandidates()) {
                if (candidate.equipmentIdCandidate() == null || candidate.topLeftCell() == null
                        || candidate.boxHeight() != profile.boxHeight()) continue;
                try {
                    var fieldMap = engine.mapFieldsByProfile(copy.bytes(), copy.processCode(), sheetName,
                            candidate.equipmentIdCandidate(), profile, copy.sourceSha256(), copy.workingSha256());
                    if (fieldMap.availableFields().isEmpty()) continue;
                    options.add(new ModifyEquipmentOption(candidate.equipmentIdCandidate(),
                            current(fieldMap, "equipmentName"), current(fieldMap, "businessItemNo"),
                            fieldMap.availableFields(), profile.profileId(), profile.profileVersion(), profile.evidenceLevel()));
                } catch (RuntimeException ignored) {
                    // Unrecognized or stale candidates remain unavailable rather than becoming writable targets.
                }
            }
            return List.copyOf(options);
        }
        if (!("P-02".equals(copy.processCode()) || "PW-02".equals(copy.processCode()))) return List.of();
        return jdbc.query("""
                SELECT e.permit_management_no AS managementNo, e.facility_name AS equipmentName,
                       e.business_item_no AS businessItemNo
                FROM equipment_mapping m
                JOIN equipment_record e ON e.id=m.equipment_id
                JOIN baseline_release b ON b.id=m.baseline_release_id
                JOIN baseline_document d ON d.id=m.process_document_id
                JOIN workbook_sheet ws ON ws.id=m.workbook_sheet_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE AND d.id=? AND ws.sheet_name=?
                  AND m.mapping_status='MAPPED_PERMIT_ID' AND m.match_method='PERMIT_MANAGEMENT_NO'
                  AND ((d.process_code='P-02' AND e.permit_management_no='I-PP21039')
                    OR (d.process_code='PW-02' AND e.permit_management_no='I-PW21007'))
                ORDER BY e.permit_management_no
                """, (rs, rowNum) -> new ModifyEquipmentOption(rs.getString("managementNo"),
                text(rs.getString("equipmentName")), text(rs.getString("businessItemNo"))),
                copy.baselineDocumentId(), sheetName);
    }

    @Transactional(readOnly = true)
    public DeleteImpactAnalyzer.Assessment assessDelete(long workingDocumentId, String sheetName,
                                                         String managementNo, String username) {
        var copy = safeDeleteCopy(workingDocumentId, username, false);
        requireText(sheetName, "sheetName");
        requireText(managementNo, "managementNo");
        EquipmentBoxProfile profile = profiles.findFor(copy.processCode(), sheetName, copy.sourceSha256())
                .orElse(null);
        return deleteAnalyzer.analyze(copy.bytes(), copy.processCode(), sheetName, managementNo, profile,
                copy.sourceSha256(), copy.workingSha256());
    }

    @Transactional
    public DeletePreview previewDelete(DeleteInput input, String username) {
        validateDeleteInput(input);
        var copy = safeDeleteCopy(input.workingDocumentId(), username, true);
        if (!copy.workingSha256().equalsIgnoreCase(input.expectedWorkingSha256()))
            throw conflict("작업본 SHA가 영향 분석 이후 변경되어 DELETE 미리보기를 차단했습니다.");
        DeleteImpactAnalyzer.Assessment assessment = assessDelete(copy.id(), input.sheetName(), input.managementNo(), username);
        if (!assessment.analysisFingerprint().equalsIgnoreCase(input.analysisFingerprint()))
            throw conflict("DELETE 영향 분석 fingerprint가 현재 작업본 결과와 달라 미리보기를 차단했습니다.");
        if (!"SAFE_BOX_ONLY_TEST_ONLY".equals(assessment.status())
                || !assessment.automaticTestOnlyDeleteAllowed()) {
            throw conflict("현재 Profile/작업본의 DELETE 영향 분석이 SAFE_BOX_ONLY_TEST_ONLY가 아닙니다.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("boxRange", assessment.deleteCellRange());
        before.put("cells", assessment.cellsToDelete());
        before.put("mergedRanges", assessment.mergedRangesToRemove());
        Map<String, Object> after = Map.of("equipmentBox", "REMOVED_IN_NEW_TEST_ONLY_COPY",
                "officialReadiness", "CALCULATION_UNAVAILABLE");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("analysisFingerprint", assessment.analysisFingerprint());
        evidence.put("classification", "SYNTHETIC_TEST_ONLY");
        evidence.put("connectionEvidenceStatus", assessment.connectionEvidenceStatus());
        evidence.put("blockers", assessment.blockers());
        var proposal = proposals.create(username, new ChangeProposalService.ProposalInput(copy.id(), copy.processCode(),
                input.sheetName(), "DELETE", input.managementNo(), copy.workingSha256(), before, after,
                input.rationale(), evidence));
        return new DeletePreview(UUID.randomUUID().toString(), proposal.proposalId(), copy.id(), copy.processCode(),
                input.sheetName(), input.managementNo(), copy.workingSha256(), assessment.analysisFingerprint(),
                assessment, "TEST_ONLY_READY", "CALCULATION_UNAVAILABLE");
    }

    @Transactional
    public DeleteGenerated generateDelete(DeletePreview preview, String username) {
        if (preview == null || blank(preview.previewId()) || blank(preview.proposalId()))
            throw conflict("유효한 DELETE 미리보기가 필요합니다.");
        var copy = safeDeleteCopy(preview.workingDocumentId(), username, true);
        if (!copy.processCode().equals(preview.processCode())
                || !copy.workingSha256().equalsIgnoreCase(preview.workingSha256()))
            throw conflict("미리보기 이후 작업본 SHA 또는 공정이 변경되어 DELETE를 차단했습니다.");
        DeleteImpactAnalyzer.Assessment current = assessDelete(copy.id(), preview.sheetName(), preview.managementNo(), username);
        if (!"SAFE_BOX_ONLY_TEST_ONLY".equals(current.status())
                || !current.automaticTestOnlyDeleteAllowed()
                || !current.analysisFingerprint().equalsIgnoreCase(preview.analysisFingerprint()))
            throw conflict("생성 직전 DELETE 영향 분석이 미리보기와 일치하지 않거나 안전하지 않습니다.");
        var change = engine.deleteBoxTestOnly(copy.bytes(), current);
        if (!copy.workingSha256().equalsIgnoreCase(change.sourceSha256()))
            throw conflict("생성된 DELETE의 입력 SHA가 현재 작업본과 다릅니다.");
        if (userWorkingCopies == null) throw conflict("USER_WORKING_COPY 출력 저장소를 사용할 수 없어 TEST_ONLY 생성을 차단했습니다.");
        var stored = userWorkingCopies.saveTestOnlyOutput(username, copy.id(), change.sourceSha256(),
                change.outputSha256(), "DELETE", safeFilename(copy.processCode()) + "_" + safeFilename(preview.sheetName())
                        + "_TEST_ONLY_DELETE_" + safeFilename(preview.managementNo()) + ".xlsx", change.workbookBytes());
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("status", "SYNTHETIC_TEST_ONLY");
        validation.put("analysisFingerprint", current.analysisFingerprint());
        validation.put("deleteCellRange", current.deleteCellRange());
        validation.put("affectedCells", change.affectedCells());
        validation.put("mergedRangesRemoved", current.mergedRangesToRemove());
        validation.put("drawingObjectsPreserved", current.drawingObjectsToPreserve());
        validation.put("outputSha256", change.outputSha256());
        validation.put("connectionEvidenceStatus", current.connectionEvidenceStatus());
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        proposals.recordTestOnlyOutput(username, preview.proposalId(), new ChangeProposalService.TestOnlyOutput(
                copy.id(), copy.processCode(), preview.sheetName(), copy.workingSha256(), change.outputSha256(), validation));
        return new DeleteGenerated(preview.previewId(), preview.proposalId(), stored.outputId(), copy.id(),
                copy.processCode(), preview.sheetName(), preview.managementNo(), change.boxTopLeft(),
                change.boxBottomRight(), change.affectedCells(), change.workbookBytes(), change.sourceSha256(),
                change.outputSha256(), "TEST_ONLY", current.analysisFingerprint(), stored.artifactKind(),
                stored.reviewStatus(), current.connectionEvidenceStatus(), "CALCULATION_UNAVAILABLE");
    }

    private NumericWorkingCopyService.WorkingCopy safeDeleteCopy(long id, String username, boolean requireFeature) {
        if (blank(username)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        if (requireFeature && (userWorkingCopies == null || !userWorkingCopies.enabled()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DELETE TEST_ONLY 생성은 사용자 작업본 기능 플래그가 활성화된 격리 환경에서만 허용됩니다.");
        var copy = safeCopy(id, username);
        if (!UserWorkingCopyService.COPY_KIND.equals(copy.workingCopyKind())
                || !username.equals(copy.registeredBy()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "DELETE는 현재 사용자가 소유한 USER_WORKING_COPY에서만 분석·생성할 수 있습니다.");
        return copy;
    }

    private void validateDeleteInput(DeleteInput input) {
        if (input == null || input.workingDocumentId() <= 0 || blank(input.sheetName())
                || blank(input.managementNo()) || blank(input.expectedWorkingSha256())
                || !input.expectedWorkingSha256().matches("(?i)[0-9a-f]{64}")
                || blank(input.analysisFingerprint()) || !input.analysisFingerprint().matches("(?i)[0-9a-f]{64}")
                || blank(input.rationale()) || input.rationale().length() > 8000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "작업본·시트·설비·기대 SHA·분석 fingerprint·삭제 사유가 필요합니다.");
        }
        if (!input.managementNo().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid equipment management number.");
    }

    private String safeFilename(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    @Transactional(readOnly = true)
    public ModifyPreview preview(ModifyInput input) {
        return preview(input, null);
    }

    @Transactional
    public ModifyPreview preview(ModifyInput input, String username) {
        validateInput(input);
        var workingCopy = safeCopy(input.workingDocumentId(), username);
        var profile = inspect(input.workingDocumentId(), input.sheetName(), input.managementNo(), username);
        if (UserWorkingCopyService.COPY_KIND.equals(workingCopy.workingCopyKind())) {
            if (userWorkingCopies == null || !userWorkingCopies.enabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "USER_WORKING_COPY MODIFY is disabled outside an explicitly enabled isolated test profile.");
            }
            EquipmentBoxProfile exactProfile = exactModifyProfile(workingCopy, input.sheetName());
            var validated = engine.modifyProfileFieldsTestOnly(workingCopy.bytes(), workingCopy.processCode(),
                    input.sheetName(), input.managementNo(), exactProfile, workingCopy.sourceSha256(),
                    workingCopy.workingSha256(), input.expectedCurrentValues(), input.replacements());
            var mapping = validated.mapping();
            String previewId = UUID.randomUUID().toString();
            String proposalId = null;
            if (userWorkingCopies != null && userWorkingCopies.enabled()) {
                if (blank(username) || proposals == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Authenticated proposal/output storage is required for USER_WORKING_COPY MODIFY.");
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("artifactKind", UserWorkingCopyService.COPY_KIND);
                evidence.put("baselineSourceSha256", workingCopy.sourceSha256());
                evidence.put("workingCopySha256", workingCopy.workingSha256());
                evidence.put("profileId", mapping.profileId());
                evidence.put("profileVersion", mapping.profileVersion());
                evidence.put("profileStatus", mapping.profileStatus());
                evidence.put("profileEvidenceLevel", mapping.profileEvidenceLevel());
                evidence.put("profileShaMatch", mapping.profileShaMatch());
                evidence.put("workingCopyShaMatch", mapping.workingCopyShaMatch());
                evidence.put("connectionEvidenceStatus", CONNECTION_UNVERIFIED);
                evidence.put("officialReadiness", "CALCULATION_UNAVAILABLE");
                var proposal = proposals.create(username, new ChangeProposalService.ProposalInput(
                        workingCopy.id(), workingCopy.processCode(), input.sheetName(), "MODIFY",
                        input.managementNo(), workingCopy.workingSha256(),
                        new LinkedHashMap<>(validated.beforeValues()), new LinkedHashMap<>(validated.afterValues()),
                        "TEST_ONLY Profile-based MODIFY Preview; review required.", evidence));
                proposalId = proposal.proposalId();
            }
            return new ModifyPreview(previewId, proposalId, input.workingDocumentId(), profile.processCode(),
                    profile.sheetName(), profile.managementNo(), profile.workingSha256(), mapping,
                    validated.beforeValues(), validated.afterValues(), "TEST_ONLY_READY", "REVIEW_REQUIRED", CONNECTION_UNVERIFIED,
                    mapping.profileId(), mapping.profileVersion(), mapping.profileStatus(),
                    mapping.profileEvidenceLevel(), mapping.profileShaMatch(), mapping.workingCopyShaMatch(),
                    mapping.availableFields(), true,
                    "Exact-SHA Profile fields only; preview reopens and validates the proposed TEST_ONLY workbook in memory. No artifact is saved until generate.");
        }
        if (!isVerifiedPilotTarget(profile.processCode(), profile.managementNo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only the verified P-02/PW-02 Item No. pilot targets are enabled for TEST_ONLY modification.");
        }
        if (input.replacements().keySet().stream().anyMatch(field -> !"businessItemNo".equals(field))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only businessItemNo is enabled. Other fields remain REVIEW_REQUIRED.");
        }
        List<DiagramBoxChangeEngine.FieldChange> changes = resolveChanges(profile.mapping(), input.replacements());
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        for (DiagramBoxChangeEngine.FieldChange change : changes) {
            String field = profile.mapping().fields().values().stream()
                    .filter(item -> change.cellAddress().equals(item.cellAddress()))
                    .map(DiagramBoxChangeEngine.FieldMapping::field).findFirst().orElseThrow();
            before.put(field, change.expectedCurrentText());
            after.put(field, change.replacementText());
        }
        return new ModifyPreview(UUID.randomUUID().toString(), null, input.workingDocumentId(), profile.processCode(),
                profile.sheetName(), profile.managementNo(), profile.workingSha256(), profile.mapping(),
                Map.copyOf(before), Map.copyOf(after), "TEST_ONLY_READY", "REVIEW_REQUIRED", CONNECTION_UNVERIFIED,
                null, null, profile.mapping().profileStatus(), profile.mapping().profileEvidenceLevel(),
                false, false, profile.mapping().availableFields(), false,
                "Only fields that exactly match the locked master value at one unique cell are eligible. TEST_ONLY output does not update master data or verify physical connectivity.");
    }

    @Transactional
    public ModifyGenerated generate(ModifyPreview preview) {
        return generate(preview, null);
    }

    @Transactional
    public ModifyGenerated generate(ModifyPreview preview, String username) {
        if (preview == null || preview.previewId() == null || preview.previewId().isBlank()) {
            throw conflict("A valid preview is required.");
        }
        var copy = safeCopy(preview.workingDocumentId(), username);
        if (!copy.workingSha256().equalsIgnoreCase(preview.workingSha256())
                || !copy.processCode().equals(preview.processCode())) {
            throw conflict("The working copy changed after preview; inspect and preview again.");
        }
        var profile = inspect(copy.id(), preview.sheetName(), preview.managementNo(), username);
        if (!profile.mapping().equals(preview.mapping())) {
            throw conflict("The Profile/master-to-cell mapping changed after preview; inspect and preview again.");
        }
        if (preview.profileBased()) {
            EquipmentBoxProfile exactProfile = exactModifyProfile(copy, preview.sheetName());
            var modified = engine.modifyProfileFieldsTestOnly(copy.bytes(), copy.processCode(), preview.sheetName(),
                    preview.managementNo(), exactProfile, copy.sourceSha256(), copy.workingSha256(),
                    preview.beforeValues(), preview.afterValues());
            if (!copy.workingSha256().equalsIgnoreCase(modified.change().sourceSha256()))
                throw conflict("The generated output source hash differs from the preview working copy.");
            if (userWorkingCopies == null || !userWorkingCopies.enabled() || proposals == null
                    || blank(username) || blank(preview.proposalId())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "USER_WORKING_COPY MODIFY requires its authenticated proposal and output stores.");
            }
            String filename = safeFilename(copy.processCode()) + "_" + safeFilename(preview.sheetName())
                    + "_TEST_ONLY_MODIFY_" + safeFilename(preview.managementNo()) + ".xlsx";
            var stored = userWorkingCopies.saveTestOnlyOutput(username, copy.id(), modified.change().sourceSha256(),
                    modified.change().outputSha256(), "MODIFY", filename, modified.change().workbookBytes());
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("testOnlyOutputId", stored.outputId());
            validation.put("outputFilename", filename);
            validation.put("structuralEditStatus", "STRUCTURE_VERIFIED_PROFILE_FIELDS_ONLY");
            validation.put("affectedCells", modified.change().affectedCells());
            validation.put("profileId", exactProfile.profileId());
            validation.put("profileVersion", exactProfile.profileVersion());
            validation.put("profileStatus", exactProfile.status());
            validation.put("profileEvidenceLevel", exactProfile.evidenceLevel());
            validation.put("profileShaMatch", true);
            validation.put("workingCopyShaMatch", true);
            validation.put("connectionEvidenceStatus", CONNECTION_UNVERIFIED);
            validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
            validation.put("officialBaselineModified", false);
            proposals.recordTestOnlyOutput(username, preview.proposalId(), new ChangeProposalService.TestOnlyOutput(
                    copy.id(), copy.processCode(), preview.sheetName(), copy.workingSha256(),
                    modified.change().outputSha256(), validation));
            return new ModifyGenerated(preview.previewId(), preview.proposalId(), stored.outputId(), copy.id(),
                    copy.processCode(), preview.sheetName(), preview.managementNo(),
                    modified.change().boxTopLeft(), modified.change().boxBottomRight(),
                    modified.change().affectedCells(), modified.change().workbookBytes(), modified.change().sourceSha256(),
                    modified.change().outputSha256(), "TEST_ONLY", "STRUCTURE_VERIFIED_PROFILE_FIELDS_ONLY", CONNECTION_UNVERIFIED,
                    modified.beforeValues(), modified.afterValues(), exactProfile.profileId(), exactProfile.profileVersion(),
                    exactProfile.status(), exactProfile.evidenceLevel(), true, true, null,
                    "Only the requested exact-SHA Profile fields changed. No DrawingML connector, flow or official baseline was modified.");
        }
        List<DiagramBoxChangeEngine.FieldChange> changes = new java.util.ArrayList<>();
        preview.afterValues().forEach((field, replacement) -> {
            var fieldMapping = profile.mapping().fields().get(field);
            if (fieldMapping == null || !"VERIFIED".equals(fieldMapping.status())
                    || !fieldMapping.currentText().equals(preview.beforeValues().get(field))) {
                throw conflict("The field is no longer uniquely mapped to the same current value: " + field);
            }
            changes.add(new DiagramBoxChangeEngine.FieldChange(fieldMapping.cellAddress(),
                    fieldMapping.currentText(), replacement));
        });
        var result = engine.modifyBoxFieldsTestOnly(copy.bytes(), preview.sheetName(), preview.managementNo(), changes);
        if (!copy.workingSha256().equalsIgnoreCase(result.sourceSha256())) {
            throw conflict("The generated output source hash differs from the preview working copy.");
        }
        return new ModifyGenerated(preview.previewId(), null, null, copy.id(), copy.processCode(), preview.sheetName(),
                preview.managementNo(), result.boxTopLeft(), result.boxBottomRight(), result.affectedCells(),
                result.workbookBytes(), result.sourceSha256(), result.outputSha256(), "TEST_ONLY",
                "STRUCTURE_VERIFIED_TEXT_FIELDS_ONLY", CONNECTION_UNVERIFIED, preview.beforeValues(), preview.afterValues(),
                null, null, profile.mapping().profileStatus(), profile.mapping().profileEvidenceLevel(), false, false, null,
                "Only explicit mapped fields changed. All non-worksheet OOXML parts, including DrawingML, were byte-preserved.");
    }

    private NumericWorkingCopyService.WorkingCopy safeCopy(long id) {
        return safeCopy(id, null);
    }

    private NumericWorkingCopyService.WorkingCopy safeCopy(long id, String username) {
        var copy = workingCopyGuard.safeWorkingCopy(id, username);
        var source = sources.findLockedSource(copy.baselineDocumentId());
        if (source == null || !source.locked() || !"V1.0".equals(source.baselineVersion())
                || !"PROCESS".equals(source.documentRole()) || !source.processCode().equals(copy.processCode())
                || !source.sourceSha256().equalsIgnoreCase(copy.sourceSha256())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Working copy is not linked to the locked V1.0 process source.");
        }
        return copy;
    }

    private MasterEquipmentRow findMappedMaster(NumericWorkingCopyService.WorkingCopy copy,
                                                 String sheetName, String managementNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.permit_management_no AS managementNo, e.business_item_no AS businessItemNo,
                       e.facility_name AS facilityName, e.permit_name AS permitName,
                       e.permit_category AS permitCategory, e.process_category AS processCategory,
                       e.capacity_text AS capacityText, e.capacity_unit AS capacityUnit,
                       e.quantity_text AS quantityText, m.mapping_status AS mappingStatus,
                       m.match_method AS matchMethod, ws.sheet_name AS mappedSheet
                FROM equipment_mapping m
                JOIN equipment_record e ON e.id=m.equipment_id
                JOIN baseline_release b ON b.id=m.baseline_release_id
                JOIN baseline_document d ON d.id=m.process_document_id
                JOIN workbook_sheet ws ON ws.id=m.workbook_sheet_id
                WHERE b.version_code='V1.0' AND b.locked=TRUE AND d.id=? AND ws.sheet_name=?
                  AND e.permit_management_no=? AND m.mapping_status='MAPPED_PERMIT_ID'
                  AND m.match_method='PERMIT_MANAGEMENT_NO'
                """, copy.baselineDocumentId(), sheetName, managementNo);
        if (rows.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The selected management number is not uniquely mapped to this locked source worksheet.");
        }
        Map<String, Object> row = rows.get(0);
        String itemNo = text(row.get("businessItemNo"));
        String equipmentName = text(row.get("facilityName"));
        String capacity = text(row.get("capacityText"));
        String unit = text(row.get("capacityUnit"));
        String capacityWithUnit = unit.isBlank() || capacity.contains(unit) ? capacity : capacity + unit;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("managementNo", text(row.get("managementNo")));
        values.put("equipmentName", equipmentName);
        values.put("businessItemNo", itemNo);
        values.put("capacityWithUnit", capacityWithUnit);
        values.put("quantity", text(row.get("quantityText")));
        values.put("permitName", text(row.get("permitName")));
        values.put("permitCategory", text(row.get("permitCategory")));
        values.put("processCategory", text(row.get("processCategory")));
        return new MasterEquipmentRow(text(row.get("managementNo")), itemNo, equipmentName, Map.copyOf(values));
    }

    private EquipmentBoxProfile exactModifyProfile(NumericWorkingCopyService.WorkingCopy copy, String sheetName) {
        EquipmentBoxProfile profile = profiles.findFor(copy.processCode(), sheetName, copy.sourceSha256())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No Profile is bound to the locked source/process/sheet identity."));
        if (!profile.enabledForGenericAdd()
                || !("WORKING_COPY_VERIFIED".equals(profile.evidenceLevel())
                || "SYNTHETIC_TESTED".equals(profile.evidenceLevel()))
                || !profile.matchesWorkingCopySha(copy.workingSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Profile status/evidence and exact working-copy SHA do not authorize MODIFY.");
        }
        return profile;
    }

    private String current(DiagramBoxChangeEngine.BoxFieldMapping mapping, String field) {
        var value = mapping.fields().get(field);
        return value == null ? "" : value.currentText();
    }

    private List<DiagramBoxChangeEngine.FieldChange> resolveChanges(
            DiagramBoxChangeEngine.BoxFieldMapping mapping, Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty() || replacements.size() > MAX_FIELD_LENGTHS.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one explicit field replacement is required.");
        }
        List<DiagramBoxChangeEngine.FieldChange> changes = new java.util.ArrayList<>();
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            Integer maxLength = MAX_FIELD_LENGTHS.get(replacement.getKey());
            if (maxLength == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported modify field: " + replacement.getKey());
            String value = replacement.getValue();
            if (value == null || value.isBlank() || value.length() > maxLength) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Replacement field is blank or exceeds its length limit: " + replacement.getKey());
            }
            var field = mapping.fields().get(replacement.getKey());
            if (field == null || !"VERIFIED".equals(field.status()) || field.cellAddress() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Field is not uniquely mapped and cannot be modified: " + replacement.getKey());
            }
            if (field.currentText().equals(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Replacement is identical to current value: " + replacement.getKey());
            changes.add(new DiagramBoxChangeEngine.FieldChange(field.cellAddress(), field.currentText(), value));
        }
        return List.copyOf(changes);
    }

    private void validateInput(ModifyInput input) {
        if (input == null || input.workingDocumentId() <= 0 || blank(input.sheetName()) || blank(input.managementNo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "working document, sheet, and management number are required.");
        }
        if (!input.managementNo().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid equipment management number.");
        }
    }

    private boolean isVerifiedPilotTarget(String processCode, String managementNo) {
        return ("P-02".equals(processCode) && "I-PP21039".equals(managementNo))
                || ("PW-02".equals(processCode) && "I-PW21007".equals(managementNo));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void requireText(String value, String field) {
        if (blank(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record MasterEquipmentRow(String managementNo, String itemNo, String equipmentName,
                                      Map<String, String> values) { }

    public record DeleteInput(long workingDocumentId, String sheetName, String managementNo,
                              String expectedWorkingSha256, String analysisFingerprint, String rationale) { }

    public record DeletePreview(String previewId, String proposalId, long workingDocumentId, String processCode,
                                String sheetName, String managementNo, String workingSha256,
                                String analysisFingerprint, DeleteImpactAnalyzer.Assessment assessment,
                                String status, String calculationStatus) { }

    public record DeleteGenerated(String previewId, String proposalId, long outputId, long workingDocumentId,
                                  String processCode, String sheetName, String managementNo, String boxTopLeft,
                                  String boxBottomRight, List<String> affectedCells, byte[] workbookBytes,
                                  String sourceSha256, String outputSha256, String mode,
                                  String analysisFingerprint, String artifactKind, String reviewStatus,
                                  String connectionEvidenceStatus, String officialReadiness) {
        public DeleteGenerated { affectedCells = List.copyOf(affectedCells); }
    }

    public record ModifyEquipmentOption(String managementNo, String equipmentName, String businessItemNo,
                                        List<String> availableFields, String profileId, String profileVersion,
                                        String profileEvidenceLevel) {
        public ModifyEquipmentOption(String managementNo, String equipmentName, String businessItemNo) {
            this(managementNo, equipmentName, businessItemNo, List.of("businessItemNo"), null, null, null);
        }
        public ModifyEquipmentOption { availableFields = availableFields == null ? List.of() : List.copyOf(availableFields); }
    }

    public record ModifyInput(long workingDocumentId, String sheetName, String managementNo,
                              Map<String, String> replacements, Map<String, String> expectedCurrentValues) {
        public ModifyInput(long workingDocumentId, String sheetName, String managementNo,
                          Map<String, String> replacements) {
            this(workingDocumentId, sheetName, managementNo, replacements, Map.of());
        }
        public ModifyInput {
            replacements = replacements == null ? Map.of() : Map.copyOf(replacements);
            expectedCurrentValues = expectedCurrentValues == null ? Map.of() : Map.copyOf(expectedCurrentValues);
        }
    }

    public record EquipmentProfile(long workingDocumentId, String processCode, String sheetName,
                                   String managementNo, String workingSha256, String masterItemNo,
                                   String masterEquipmentName, DiagramBoxChangeEngine.BoxFieldMapping mapping,
                                   String editStatus, String connectionEvidenceStatus, String notice) { }

    public record ModifyPreview(String previewId, String proposalId, long workingDocumentId, String processCode, String sheetName,
                                String managementNo, String workingSha256,
                                DiagramBoxChangeEngine.BoxFieldMapping mapping,
                                Map<String, String> beforeValues, Map<String, String> afterValues,
                                String status, String reviewStatus, String connectionEvidenceStatus,
                                String profileId, String profileVersion, String profileStatus,
                                String profileEvidenceLevel, boolean profileShaMatch,
                                boolean workingCopyShaMatch, List<String> availableFields,
                                boolean profileBased, String notice) {
        public ModifyPreview(String previewId, long workingDocumentId, String processCode, String sheetName,
                String managementNo, String workingSha256, DiagramBoxChangeEngine.BoxFieldMapping mapping,
                Map<String, String> beforeValues, Map<String, String> afterValues, String status,
                String reviewStatus, String connectionEvidenceStatus, String profileId, String profileVersion,
                String profileStatus, String profileEvidenceLevel, boolean profileShaMatch,
                boolean workingCopyShaMatch, List<String> availableFields, boolean profileBased, String notice) {
            this(previewId, null, workingDocumentId, processCode, sheetName, managementNo, workingSha256, mapping,
                    beforeValues, afterValues, status, reviewStatus, connectionEvidenceStatus, profileId,
                    profileVersion, profileStatus, profileEvidenceLevel, profileShaMatch, workingCopyShaMatch,
                    availableFields, profileBased, notice);
        }
        public ModifyPreview {
            beforeValues = Map.copyOf(beforeValues); afterValues = Map.copyOf(afterValues);
            availableFields = availableFields == null ? List.of() : List.copyOf(availableFields);
        }
    }

    public record ModifyGenerated(String previewId, String proposalId, Long testOnlyOutputId,
                                  long workingDocumentId, String processCode, String sheetName,
                                  String managementNo, String boxTopLeft, String boxBottomRight,
                                  List<String> affectedCells, byte[] workbookBytes,
                                  String sourceSha256, String outputSha256, String mode,
                                  String structuralEditStatus, String connectionEvidenceStatus,
                                  Map<String, String> beforeValues, Map<String, String> afterValues,
                                  String profileId, String profileVersion, String profileStatus,
                                  String profileEvidenceLevel, boolean profileShaMatch,
                                  boolean workingCopyShaMatch, String reviewStatus, String interpretation) {
        public ModifyGenerated {
            affectedCells = List.copyOf(affectedCells);
            beforeValues = Map.copyOf(beforeValues); afterValues = Map.copyOf(afterValues);
        }
        public boolean profileBased() { return profileId != null && !profileId.isBlank(); }
    }
}
