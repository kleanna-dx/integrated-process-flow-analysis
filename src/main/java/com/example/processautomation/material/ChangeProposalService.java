package com.example.processautomation.material;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Append-only proposal/event workflow for all five process change categories. */
@Service
public class ChangeProposalService {
    private static final Set<String> CHANGE_TYPES = Set.of("ADD", "MODIFY", "DELETE", "PIPE_CHANGE", "FLOW_CHANGE");
    private final ChangeProposalRepository repository;
    private final GeneralDiagramEditService workingCopyGuard;
    private final NetworkWhatIfService whatIf;
    private final DirectedProcessNetworkWorkbookExporter workbookExporter;
    private final ObjectMapper mapper;

    public ChangeProposalService(ChangeProposalRepository repository, GeneralDiagramEditService workingCopyGuard,
                                 NetworkWhatIfService whatIf, DirectedProcessNetworkWorkbookExporter workbookExporter,
                                 ObjectMapper mapper) {
        this.repository = repository;
        this.workingCopyGuard = workingCopyGuard;
        this.whatIf = whatIf;
        this.workbookExporter = workbookExporter;
        this.mapper = mapper;
    }

    @Transactional
    public ProposalView create(String requestUser, ProposalInput input) {
        validateIdentity(requestUser, input);
        var copy = safeCopy(input.workingDocumentId(), requestUser);
        if (!copy.processCode().equals(input.processCode())
                || !copy.workingSha256().equalsIgnoreCase(input.workingSha256())) {
            throw conflict("The proposal process and working-copy SHA must match the stored working copy.");
        }
        requireSheet(copy.bytes(), input.sheetName());
        String type = input.changeType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!CHANGE_TYPES.contains(type)) throw badRequest("Unsupported change type.");
        String proposalId = UUID.randomUUID().toString();
        EventInput event = new EventInput(proposalId, "PROPOSED", type, copy.processCode(), input.sheetName(),
                copy.id(), copy.workingSha256(), requestUser, blankToNull(input.managementNo()),
                json(input.beforeValues()), json(input.afterValues()), input.rationale().trim(),
                json(input.evidence()), "REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE", "REVIEW_REQUIRED",
                false, null, null, null);
        long eventId = repository.append(event);
        return detail(requestUser, proposalId, eventId);
    }

    @Transactional
    public ProposalView addReviewNote(String requestUser, String proposalId, ReviewNote note) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        if (note == null || note.note() == null || note.note().isBlank() || note.note().length() > 4000) {
            throw badRequest("A review note of 1-4000 characters is required.");
        }
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        String beforeJson = stringValue(current.get("before_json"));
        String afterJson = stringValue(current.get("after_json"));
        EventInput event = new EventInput(proposalId, "REVIEW_NOTE", stringValue(current.get("change_type")),
                stringValue(current.get("process_code")), stringValue(current.get("sheet_name")),
                ((Number) current.get("working_document_id")).longValue(), stringValue(current.get("working_sha256")),
                requestUser, stringValue(current.get("equipment_management_no")), beforeJson, afterJson,
                note.note().trim(), json(note.evidence() == null ? Map.of() : note.evidence()),
                stringValue(current.get("mapping_status")), stringValue(current.get("calculation_status")),
                stringValue(current.get("review_status")), Boolean.TRUE.equals(current.get("test_only_excel_created")),
                nullableString(current.get("output_sha256")), nullableString(current.get("validation_result_json")),
                nullableString(current.get("failure_reason")));
        long eventId = repository.append(event);
        return detail(requestUser, proposalId, eventId);
    }

    @Transactional
    public ProposalView recordTestOnlyOutput(String requestUser, String proposalId, TestOnlyOutput output) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        if (output == null || output.workingDocumentId() <= 0 || blank(output.processCode())
                || blank(output.sheetName()) || blank(output.workingSha256()) || blank(output.outputSha256())
                || !output.workingSha256().matches("(?i)[0-9a-f]{64}")
                || !output.outputSha256().matches("(?i)[0-9a-f]{64}") || output.validationResults() == null) {
            throw badRequest("A TEST_ONLY artifact with working/output hashes and validation results is required.");
        }
        var copy = safeCopy(output.workingDocumentId(), requestUser);
        if (!copy.processCode().equals(output.processCode()) || !copy.workingSha256().equalsIgnoreCase(output.workingSha256())) {
            throw conflict("TEST_ONLY artifact does not belong to the current immutable working copy.");
        }
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        if (((Number) current.get("working_document_id")).longValue() != copy.id()
                || !stringValue(current.get("process_code")).equals(copy.processCode())
                || !stringValue(current.get("working_sha256")).equalsIgnoreCase(copy.workingSha256())
                || !stringValue(current.get("sheet_name")).equals(output.sheetName())) {
            throw conflict("TEST_ONLY artifact scope differs from the stored proposal.");
        }
        EventInput event = new EventInput(proposalId, "TEST_ONLY_OUTPUT", stringValue(current.get("change_type")),
                copy.processCode(), output.sheetName(), copy.id(), copy.workingSha256(), requestUser,
                stringValue(current.get("equipment_management_no")), stringValue(current.get("before_json")),
                stringValue(current.get("after_json")), "TEST_ONLY Excel generated; official data remains unchanged.",
                stringValue(current.get("evidence_json")), "REVIEW_REQUIRED", "CALCULATION_UNAVAILABLE",
                "REVIEW_REQUIRED", true, output.outputSha256(), json(output.validationResults()), null);
        long eventId = repository.append(event);
        return detail(requestUser, proposalId, eventId);
    }

    @Transactional
    public ProposalView recordWhatIf(String requestUser, String proposalId, WhatIfComparisonInput input) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        String changeType = stringValue(current.get("change_type"));
        if (!Set.of("DELETE", "PIPE_CHANGE", "FLOW_CHANGE").contains(changeType)) {
            throw badRequest("TEST_ONLY network comparison is currently supported for DELETE, PIPE_CHANGE, and FLOW_CHANGE proposals.");
        }
        if (input == null || input.beforeNetwork() == null || input.afterNetwork() == null
                || !stringValue(current.get("process_code")).equals(input.beforeNetwork().processCode())
                || !stringValue(current.get("process_code")).equals(input.afterNetwork().processCode())) {
            throw conflict("Both synthetic before/after networks must match the proposal process.");
        }
        if (!sameNetworkBasis(input.beforeNetwork(), input.afterNetwork())) {
            throw badRequest("Before/after What-if comparisons require the same material class/type, unit, and measurement period; no unit conversion is inferred.");
        }
        validateComparisonAgainstProposal(changeType, stringValue(current.get("equipment_management_no")),
                parseMap(current.get("before_json")), parseMap(current.get("after_json")), input);
        NetworkWhatIfService.WhatIfReport before = whatIf.calculate(input.beforeNetwork());
        NetworkWhatIfService.WhatIfReport after = whatIf.calculate(input.afterNetwork());
        requireUnavailableReadiness(before, after);
        return appendWhatIfComparison(requestUser, proposalId, current, changeType, before, after);
    }

    /** Internal path for the server-owned PIPE_CHANGE fixture; callers must supply its already-verified reports. */
    ProposalView recordSyntheticPipeChangeWhatIf(String requestUser, String proposalId,
            NetworkWhatIfService.WhatIfReport before, NetworkWhatIfService.WhatIfReport after) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        if (!"PIPE_CHANGE".equals(stringValue(current.get("change_type"))))
            throw conflict("Synthetic drawing What-if results can only be recorded for PIPE_CHANGE proposals.");
        requireUnavailableReadiness(before, after);
        if (!calculated(before) || !calculated(after) || !"TEST_ONLY".equals(before.mode())
                || !"TEST_ONLY".equals(after.mode()) || !before.simulationId().equals(after.simulationId()))
            throw conflict("Server synthetic PIPE_CHANGE reports must be same-scope, calculated TEST_ONLY results.");
        return appendWhatIfComparison(requestUser, proposalId, current, "PIPE_CHANGE", before, after);
    }

    /** Internal report path for the immutable server FLOW_CHANGE fixture; it never queries live readiness. */
    ProposalView recordSyntheticFlowChangeWhatIf(String requestUser, String proposalId,
            NetworkWhatIfService.WhatIfReport before, NetworkWhatIfService.WhatIfReport after,
            NetworkWhatIfRequest beforeNetwork, NetworkWhatIfRequest afterNetwork) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        if (!"FLOW_CHANGE".equals(stringValue(current.get("change_type"))))
            throw conflict("Synthetic numeric-label results can only be recorded for FLOW_CHANGE proposals.");
        if (beforeNetwork == null || afterNetwork == null || before == null || after == null
                || !sameNetworkBasis(beforeNetwork, afterNetwork))
            throw conflict("FLOW_CHANGE comparisons require the same synthetic unit/material/period basis.");
        WhatIfComparisonInput comparison = new WhatIfComparisonInput(beforeNetwork, afterNetwork);
        validateComparisonAgainstProposal("FLOW_CHANGE", stringValue(current.get("equipment_management_no")),
                parseMap(current.get("before_json")), parseMap(current.get("after_json")), comparison);
        requireUnavailableReadiness(before, after);
        if (!calculated(before) || !calculated(after) || !"TEST_ONLY".equals(before.mode())
                || !"TEST_ONLY".equals(after.mode()) || !before.simulationId().equals(after.simulationId()))
            throw conflict("FLOW_CHANGE requires same-simulation, calculated server TEST_ONLY reports.");
        return appendWhatIfComparison(requestUser, proposalId, current, "FLOW_CHANGE", before, after);
    }

    private void requireUnavailableReadiness(NetworkWhatIfService.WhatIfReport before,
                                             NetworkWhatIfService.WhatIfReport after) {
        if (before == null || after == null || before.actualReadiness() == null || after.actualReadiness() == null
                || !"CALCULATION_UNAVAILABLE".equals(before.actualReadiness().get("status"))
                || !"CALCULATION_UNAVAILABLE".equals(after.actualReadiness().get("status"))
                || !Boolean.FALSE.equals(before.actualReadiness().get("calculationAvailable"))
                || !Boolean.FALSE.equals(after.actualReadiness().get("calculationAvailable"))
                || before.officialDiagramModified() || before.officialMasterModified()
                || after.officialDiagramModified() || after.officialMasterModified())
            throw conflict("What-if reports cannot promote official readiness or modify the official baseline.");
    }

    private ProposalView appendWhatIfComparison(String requestUser, String proposalId, Map<String, Object> current,
            String changeType, NetworkWhatIfService.WhatIfReport before, NetworkWhatIfService.WhatIfReport after) {
        boolean allCalculated = calculated(before) && calculated(after);
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("processResidualDeltas", processResidualDeltas(before.baseline().processes(), after.baseline().processes()));
        comparison.put("externalBalanceDeltas", externalBalanceDeltas(before.baseline().external(), after.baseline().external()));
        comparison.put("impactedEquipmentBefore", impactedEquipment(before));
        comparison.put("impactedEquipmentAfter", impactedEquipment(after));
        comparison.put("impactedProcessesBefore", impactedProcesses(before));
        comparison.put("impactedProcessesAfter", impactedProcesses(after));
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("calculationKind", "SYNTHETIC_TEST_ONLY");
        validation.put("proposalId", proposalId);
        validation.put("officialReadiness", "CALCULATION_UNAVAILABLE");
        validation.put("beforeNetwork", before);
        validation.put("afterNetwork", after);
        validation.put("comparison", comparison);
        EventInput event = new EventInput(proposalId, "VALIDATION_RESULT", changeType,
                stringValue(current.get("process_code")), stringValue(current.get("sheet_name")),
                ((Number) current.get("working_document_id")).longValue(), stringValue(current.get("working_sha256")),
                requestUser, stringValue(current.get("equipment_management_no")), stringValue(current.get("before_json")),
                stringValue(current.get("after_json")), "TEST_ONLY synthetic before/after What-if; no official connection was created.",
                stringValue(current.get("evidence_json")), "REVIEW_REQUIRED",
                allCalculated ? "CALCULATED_SYNTHETIC" : "CALCULATION_UNAVAILABLE", "REVIEW_REQUIRED",
                false, null, json(validation), null);
        long eventId = repository.append(event);
        return detail(requestUser, proposalId, eventId);
    }

    @Transactional
    public ProposalView recordPipeChangeDrawingValidation(String requestUser, String proposalId,
                                                           Map<String, Object> validation) {
        requireText(requestUser, "requestUser"); requireText(proposalId, "proposalId");
        Map<String, Object> current = repository.latest(proposalId, requestUser);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        if (!"PIPE_CHANGE".equals(stringValue(current.get("change_type")))
                || !"VALIDATION_RESULT".equals(stringValue(current.get("event_type"))) || validation == null)
            throw conflict("Drawing validation must append to a current PIPE_CHANGE What-if event.");
        Map<String, Object> mapping = parseMap(validation.get("mappingComparison"));
        Map<String, Object> comparison = parseMap(validation.get("calculationComparison"));
        if (!"SYNTHETIC_MAPPING_VERIFIED_TEST_ONLY".equals(mapping.get("status"))
                || !"DRAWING_WHATIF_MATCHED_TEST_ONLY".equals(comparison.get("status"))
                || !Boolean.TRUE.equals(comparison.get("drawingAndWhatIfEndpointsAgree"))
                || !Boolean.TRUE.equals(comparison.get("unchangedEdgesPreserved"))
                || !Boolean.TRUE.equals(comparison.get("bothReportsCalculated"))
                || !"CALCULATION_UNAVAILABLE".equals(validation.get("officialReadiness")))
            throw conflict("PIPE_CHANGE drawing/What-if result is incomplete, inconsistent, or attempts to change live readiness.");
        String rationale = "SYNTHETIC_TEST_ONLY DrawingML/Before-After What-if matched; physical connection remains unverified.";
        EventInput event = new EventInput(proposalId, "VALIDATION_RESULT", "PIPE_CHANGE",
                stringValue(current.get("process_code")), stringValue(current.get("sheet_name")),
                ((Number) current.get("working_document_id")).longValue(), stringValue(current.get("working_sha256")),
                requestUser, stringValue(current.get("equipment_management_no")), stringValue(current.get("before_json")),
                stringValue(current.get("after_json")), rationale, stringValue(current.get("evidence_json")),
                "REVIEW_REQUIRED", "CALCULATED_SYNTHETIC", "REVIEW_REQUIRED", false, null,
                json(validation), null);
        long eventId = repository.append(event);
        return detail(requestUser, proposalId, eventId);
    }

    @Transactional(readOnly = true)
    public ProposalWorkbook exportWhatIf(String requestUser, String proposalId) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        Map<String, Object> event = repository.latestWhatIf(proposalId, requestUser);
        if (event == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved What-if result not found in this user scope.");
        Map<String, Object> validation = parseMap(event.get("validation_result_json"));
        if (!"SYNTHETIC_TEST_ONLY".equals(validation.get("calculationKind"))
                || !"CALCULATION_UNAVAILABLE".equals(validation.get("officialReadiness"))) {
            throw conflict("Stored result is not an allowed TEST_ONLY comparison.");
        }
        Object beforeValue = validation.get("beforeNetwork");
        Object afterValue = validation.get("afterNetwork");
        if (beforeValue == null || afterValue == null) throw conflict("Saved Before/After network results are incomplete.");
        try {
            NetworkWhatIfService.WhatIfReport before = mapper.convertValue(beforeValue, NetworkWhatIfService.WhatIfReport.class);
            NetworkWhatIfService.WhatIfReport after = mapper.convertValue(afterValue, NetworkWhatIfService.WhatIfReport.class);
            Map<String, Object> comparison = mapper.convertValue(validation.get("comparison"), new TypeReference<>() { });
            byte[] bytes = workbookExporter.createProposalComparisonWorkbook(proposalId,
                    stringValue(event.get("change_type")), stringValue(event.get("process_code")),
                    stringValue(event.get("sheet_name")), stringValue(event.get("equipment_management_no")),
                    parseMap(event.get("before_json")), parseMap(event.get("after_json")),
                    parseMap(event.get("evidence_json")), stringValue(event.get("calculation_status")),
                    stringValue(event.get("review_status")), "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE",
                    before, after, comparison == null ? Map.of() : comparison);
            String sha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ProposalWorkbook(bytes, sha256);
        } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to recreate the stored TEST_ONLY comparison workbook.", exception);
        }
    }

    private boolean sameNetworkBasis(NetworkWhatIfRequest before, NetworkWhatIfRequest after) {
        return before.materialClass() == after.materialClass()
                && sameText(before.materialType(), after.materialType())
                && sameText(before.unit(), after.unit())
                && sameText(before.period(), after.period());
    }

    private void validateComparisonAgainstProposal(String changeType, String managementNo,
                                                   Map<String, Object> beforeValues, Map<String, Object> afterValues,
                                                   WhatIfComparisonInput input) {
        if ("DELETE".equals(changeType)) {
            if (blank(managementNo) || !containsEquipment(input.beforeNetwork(), managementNo)
                    || containsEquipment(input.afterNetwork(), managementNo)) {
                throw conflict("DELETE comparison must include the proposed equipment before and remove it from the after network.");
            }
            return;
        }
        if ("PIPE_CHANGE".equals(changeType)) {
            requireSnapshotMatchesEdge(beforeValues, input.beforeNetwork(), false, "PIPE_CHANGE before");
            requireSnapshotMatchesEdge(afterValues, input.afterNetwork(), false, "PIPE_CHANGE after");
            return;
        }
        if ("FLOW_CHANGE".equals(changeType)) {
            requireSnapshotMatchesEdge(beforeValues, input.beforeNetwork(), true, "FLOW_CHANGE before");
            requireSnapshotMatchesEdge(afterValues, input.afterNetwork(), true, "FLOW_CHANGE after");
        }
    }

    private void requireSnapshotMatchesEdge(Map<String, Object> snapshot, NetworkWhatIfRequest network,
                                           boolean compareQuantity, String label) {
        String connectionId = requiredSnapshotText(snapshot, "connectionId", label);
        if (!connectionId.equals(network.changedConnectionId())) {
            throw conflict(label + " connectionId does not match its What-if network.");
        }
        List<NetworkWhatIfRequest.Edge> matches = network.edges().stream()
                .filter(edge -> connectionId.equals(edge.connectionId())).toList();
        if (matches.size() != 1) throw conflict(label + " must identify exactly one changed connection.");
        NetworkWhatIfRequest.Edge edge = matches.get(0);
        requireSameSnapshot(snapshot, "sourceProcess", edge.sourceProcess(), label);
        requireSameSnapshot(snapshot, "sourceEquipment", equipmentForNode(network, edge.sourceNodeId()), label);
        requireSameSnapshot(snapshot, "targetProcess", edge.targetProcess(), label);
        requireSameSnapshot(snapshot, "targetEquipment", equipmentForNode(network, edge.targetNodeId()), label);
        requireSameSnapshot(snapshot, "materialClass", edge.materialClass(), label);
        requireSameSnapshot(snapshot, "materialType", edge.materialType(), label);
        requireSameSnapshot(snapshot, "unit", edge.unit(), label);
        requireSameSnapshot(snapshot, "period", edge.period(), label);
        requireSameSnapshot(snapshot, "connectionType", edge.connectionType(), label);
        if (compareQuantity) {
            Object proposedQuantity = snapshot.get("quantity");
            if (proposedQuantity == null || edge.quantity() == null || !edge.fixed()
                    || new java.math.BigDecimal(String.valueOf(proposedQuantity)).compareTo(edge.quantity()) != 0
                    || network.baseQuantity().compareTo(edge.quantity()) != 0) {
                throw conflict(label + " quantity must match the fixed changed connection and network base quantity.");
            }
        }
    }

    private String requiredSnapshotText(Map<String, Object> snapshot, String field, String label) {
        Object value = snapshot.get(field);
        if (value == null || String.valueOf(value).isBlank()) throw badRequest(label + " requires " + field + ".");
        return String.valueOf(value).trim();
    }

    private void requireSameSnapshot(Map<String, Object> snapshot, String field, Object actual, String label) {
        String expected = requiredSnapshotText(snapshot, field, label);
        if (actual == null || !expected.equalsIgnoreCase(String.valueOf(actual).trim())) {
            throw conflict(label + " " + field + " does not match its What-if connection.");
        }
    }

    private String equipmentForNode(NetworkWhatIfRequest network, String nodeId) {
        return network.nodes().stream().filter(node -> nodeId.equals(node.nodeId()))
                .map(NetworkWhatIfRequest.Node::equipmentNumber).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("");
    }

    private boolean containsEquipment(NetworkWhatIfRequest network, String managementNo) {
        return network.nodes().stream().filter(node -> node.nodeType() == com.example.processautomation.material.DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT)
                .filter(node -> managementNo.equals(node.equipmentNumber())).count() == 1;
    }

    private boolean sameText(String first, String second) {
        return first != null && second != null && first.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(second.trim().replaceAll("\\s+", " "));
    }

    private boolean calculated(NetworkWhatIfService.WhatIfReport report) {
        return "CALCULATED".equals(report.baseline().status())
                && report.scenarios().stream().allMatch(item -> "CALCULATED".equals(item.result().status()));
    }

    private List<String> impactedEquipment(NetworkWhatIfService.WhatIfReport report) {
        return report.scenarios().stream().flatMap(item -> item.result().impactedEquipmentNumbers().stream())
                .distinct().sorted().toList();
    }

    private List<String> impactedProcesses(NetworkWhatIfService.WhatIfReport report) {
        return report.scenarios().stream().flatMap(item -> item.result().impactedProcessCodes().stream())
                .distinct().sorted().toList();
    }

    private List<Map<String, Object>> processResidualDeltas(
            List<com.example.processautomation.material.DirectedProcessNetworkSolver.ProcessBalance> before,
            List<com.example.processautomation.material.DirectedProcessNetworkSolver.ProcessBalance> after) {
        Map<String, com.example.processautomation.material.DirectedProcessNetworkSolver.ProcessBalance> beforeByKey =
                before.stream().collect(java.util.stream.Collectors.toMap(this::processBalanceKey, item -> item, (a, b) -> a));
        Map<String, com.example.processautomation.material.DirectedProcessNetworkSolver.ProcessBalance> afterByKey =
                after.stream().collect(java.util.stream.Collectors.toMap(this::processBalanceKey, item -> item, (a, b) -> a));
        return unionKeys(beforeByKey, afterByKey).stream().map(key -> {
            var old = beforeByKey.get(key);
            var current = afterByKey.get(key);
            var basis = old == null ? current : old;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("processCode", basis.processCode()); result.put("materialType", basis.materialType());
            result.put("unit", basis.unit()); result.put("period", basis.period());
            result.put("beforeResidual", old == null ? null : old.residual());
            result.put("afterResidual", current == null ? null : current.residual());
            result.put("delta", old == null || current == null || old.residual() == null || current.residual() == null
                    ? null : current.residual().subtract(old.residual()));
            result.put("status", old == null ? "NEW_AFTER" : current == null ? "MISSING_AFTER"
                    : old.residual() == null || current.residual() == null ? "CALCULATION_UNAVAILABLE" : "SYNTHETIC_COMPARISON");
            return result;
        }).toList();
    }

    private <T> List<String> unionKeys(Map<String, T> before, Map<String, T> after) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return List.copyOf(keys);
    }

    private String processBalanceKey(com.example.processautomation.material.DirectedProcessNetworkSolver.ProcessBalance value) {
        return value.processCode() + "|" + value.materialClass() + "|" + value.materialType() + "|" + value.unit() + "|" + value.period();
    }

    private List<Map<String, Object>> externalBalanceDeltas(
            List<com.example.processautomation.material.DirectedProcessNetworkSolver.ExternalBalance> before,
            List<com.example.processautomation.material.DirectedProcessNetworkSolver.ExternalBalance> after) {
        Map<String, com.example.processautomation.material.DirectedProcessNetworkSolver.ExternalBalance> beforeByKey =
                before.stream().collect(java.util.stream.Collectors.toMap(this::externalBalanceKey, item -> item, (a, b) -> a));
        Map<String, com.example.processautomation.material.DirectedProcessNetworkSolver.ExternalBalance> afterByKey =
                after.stream().collect(java.util.stream.Collectors.toMap(this::externalBalanceKey, item -> item, (a, b) -> a));
        return unionKeys(beforeByKey, afterByKey).stream().map(key -> {
            var old = beforeByKey.get(key);
            var current = afterByKey.get(key);
            var basis = old == null ? current : old;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("materialClass", basis.materialClass()); result.put("materialType", basis.materialType());
            result.put("unit", basis.unit()); result.put("period", basis.period());
            result.put("externalInflowBefore", old == null ? null : old.externalInflow());
            result.put("externalInflowAfter", current == null ? null : current.externalInflow());
            result.put("externalOutflowBefore", old == null ? null : old.externalOutflow());
            result.put("externalOutflowAfter", current == null ? null : current.externalOutflow());
            result.put("residualBefore", old == null ? null : old.residual());
            result.put("residualAfter", current == null ? null : current.residual());
            result.put("status", old == null ? "NEW_AFTER" : current == null ? "MISSING_AFTER" : "SYNTHETIC_COMPARISON");
            return result;
        }).toList();
    }

    private String externalBalanceKey(com.example.processautomation.material.DirectedProcessNetworkSolver.ExternalBalance value) {
        return value.materialClass() + "|" + value.materialType() + "|" + value.unit() + "|" + value.period();
    }

    @Transactional(readOnly = true)
    public List<ProposalView> list(String requestUser, int limit) {
        requireText(requestUser, "requestUser");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.listLatest(requestUser, safeLimit).stream()
                .map(row -> eventView(row, repository.events(stringValue(row.get("proposal_id")), requestUser),
                        ((Number) row.get("event_id")).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProposalView detail(String requestUser, String proposalId) {
        return detail(requestUser, proposalId, -1);
    }

    private ProposalView detail(String requestUser, String proposalId, long latestEventId) {
        requireText(requestUser, "requestUser");
        requireText(proposalId, "proposalId");
        Map<String, Object> latest = repository.latest(proposalId, requestUser);
        if (latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this user scope.");
        List<Map<String, Object>> events = repository.events(proposalId, requestUser);
        return eventView(latest, events, latestEventId < 0
                ? ((Number) latest.get("event_id")).longValue() : latestEventId);
    }

    private ProposalView eventView(Map<String, Object> row, List<Map<String, Object>> rows, long latestEventId) {
        List<EventView> events = rows.stream().map(this::eventView).toList();
        return new ProposalView(stringValue(row.get("proposal_id")), latestEventId,
                stringValue(row.get("event_type")), stringValue(row.get("change_type")),
                stringValue(row.get("process_code")), stringValue(row.get("sheet_name")),
                ((Number) row.get("working_document_id")).longValue(), stringValue(row.get("working_sha256")),
                stringValue(row.get("request_user")), stringValue(row.get("equipment_management_no")),
                parseMap(row.get("before_json")), parseMap(row.get("after_json")),
                stringValue(row.get("rationale")), parseMap(row.get("evidence_json")),
                stringValue(row.get("mapping_status")), stringValue(row.get("calculation_status")),
                stringValue(row.get("review_status")), Boolean.TRUE.equals(row.get("test_only_excel_created")),
                nullableString(row.get("output_sha256")), parseMap(row.get("validation_result_json")),
                nullableString(row.get("failure_reason")), stringValue(row.get("created_at")), events);
    }

    private EventView eventView(Map<String, Object> row) {
        return new EventView(((Number) row.get("event_id")).longValue(), stringValue(row.get("proposal_id")),
                stringValue(row.get("event_type")), stringValue(row.get("change_type")),
                stringValue(row.get("request_user")), stringValue(row.get("rationale")),
                parseMap(row.get("before_json")), parseMap(row.get("after_json")),
                parseMap(row.get("evidence_json")), stringValue(row.get("mapping_status")),
                stringValue(row.get("calculation_status")), stringValue(row.get("review_status")),
                Boolean.TRUE.equals(row.get("test_only_excel_created")), nullableString(row.get("output_sha256")),
                parseMap(row.get("validation_result_json")), nullableString(row.get("failure_reason")),
                stringValue(row.get("created_at")));
    }

    private NumericWorkingCopyService.WorkingCopy safeCopy(long id) {
        return safeCopy(id, null);
    }

    private NumericWorkingCopyService.WorkingCopy safeCopy(long id, String requestUser) {
        return workingCopyGuard.safeWorkingCopy(id, requestUser);
    }

    private void requireSheet(byte[] bytes, String sheetName) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getSheet(sheetName) == null) throw badRequest("Requested sheet is not in the working copy.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to verify the working-copy sheet.", exception);
        }
    }

    private void validateIdentity(String requestUser, ProposalInput input) {
        requireText(requestUser, "requestUser");
        if (input == null || input.workingDocumentId() <= 0 || blank(input.processCode())
                || blank(input.sheetName()) || blank(input.changeType()) || blank(input.workingSha256())
                || !input.workingSha256().matches("(?i)[0-9a-f]{64}") || blank(input.rationale())
                || input.rationale().length() > 8000) {
            throw badRequest("Working-copy identity, scope, change type, SHA, and rationale are required.");
        }
        if (input.beforeValues() == null || input.afterValues() == null || input.evidence() == null
                || input.beforeValues().size() > 64 || input.afterValues().size() > 64 || input.evidence().size() > 40) {
            throw badRequest("Proposal before/after/evidence payload is missing or too large.");
        }
        if (json(input.beforeValues()).length() + json(input.afterValues()).length() + json(input.evidence()).length() > 120_000) {
            throw badRequest("Combined proposal payload exceeds 120 KB.");
        }
        if (input.managementNo() != null && input.managementNo().length() > 96) throw badRequest("Management number is too long.");
        String type = input.changeType().trim().toUpperCase(java.util.Locale.ROOT);
        if ("PIPE_CHANGE".equals(type) || "FLOW_CHANGE".equals(type)) {
            if (input.evidence().isEmpty()) {
                throw badRequest("PIPE/FLOW proposals require an explicit evidence object; evidence status remains unverified.");
            }
            if ("PIPE_CHANGE".equals(type)) {
                validateSnapshotFields(input.beforeValues(), Set.of("sourceProcess", "sourceEquipment", "targetProcess",
                        "targetEquipment", "connectionId", "connectionType", "materialClass", "materialType", "unit", "period"), "PIPE_CHANGE before");
                validateSnapshotFields(input.afterValues(), Set.of("sourceProcess", "sourceEquipment", "targetProcess",
                        "targetEquipment", "connectionId", "connectionType", "materialClass", "materialType", "unit", "period"), "PIPE_CHANGE after");
            } else {
                validateFlowSnapshot(input.beforeValues(), "FLOW_CHANGE before");
                validateFlowSnapshot(input.afterValues(), "FLOW_CHANGE after");
            }
        }
    }

    private void validateSnapshotFields(Map<String, Object> values, Set<String> fields, String label) {
        for (String field : fields) {
            Object value = values.get(field);
            if (value == null || String.valueOf(value).isBlank()) throw badRequest(label + " requires " + field + ".");
        }
    }

    private void validateFlowSnapshot(Map<String, Object> values, String label) {
        validateSnapshotFields(values, Set.of("sourceProcess", "sourceEquipment", "targetProcess", "targetEquipment",
                "connectionId", "connectionType", "materialClass", "materialType", "unit", "period",
                "branchInfo", "recycleInfo"), label);
        Object quantity = values.get("quantity");
        if (!(quantity instanceof Number) && !(quantity instanceof String)) throw badRequest(label + " requires numeric quantity.");
        try {
            if (new java.math.BigDecimal(String.valueOf(quantity)).signum() < 0) throw badRequest(label + " quantity must be non-negative.");
        } catch (NumberFormatException exception) {
            throw badRequest(label + " quantity must be numeric.");
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Proposal JSON is invalid", exception); }
    }

    private Map<String, Object> parseMap(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return Map.of();
        try { return mapper.readValue(String.valueOf(value), new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored proposal JSON is malformed", exception); }
    }

    private String nullableString(Object value) { return value == null ? null : String.valueOf(value); }
    private String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void requireText(String value, String field) { if (blank(value)) throw badRequest(field + " is required."); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ProposalWorkbook(byte[] bytes, String sha256) { }

    public record ProposalInput(long workingDocumentId, String processCode, String sheetName, String changeType,
                                String managementNo, String workingSha256, Map<String, Object> beforeValues,
                                Map<String, Object> afterValues, String rationale, Map<String, Object> evidence) { }
    public record ReviewNote(String note, Map<String, Object> evidence) { }
    public record WhatIfComparisonInput(@Valid @NotNull NetworkWhatIfRequest beforeNetwork,
                                        @Valid @NotNull NetworkWhatIfRequest afterNetwork) { }
    public record TestOnlyOutput(long workingDocumentId, String processCode, String sheetName,
                                 String workingSha256, String outputSha256,
                                 Map<String, Object> validationResults) { }

    public record ProposalView(String proposalId, long latestEventId, String latestEventType, String changeType,
                               String processCode, String sheetName, long workingDocumentId, String workingSha256,
                               String requestUser, String managementNo, Map<String, Object> beforeValues,
                               Map<String, Object> afterValues, String rationale, Map<String, Object> evidence,
                               String mappingStatus, String calculationStatus, String reviewStatus,
                               boolean testOnlyExcelCreated, String outputSha256,
                               Map<String, Object> validationResults, String failureReason, String createdAt,
                               List<EventView> events) {
        public ProposalView {
            beforeValues = immutable(beforeValues); afterValues = immutable(afterValues);
            evidence = immutable(evidence); validationResults = immutable(validationResults);
            events = List.copyOf(events);
        }
    }

    public record EventView(long eventId, String proposalId, String eventType, String changeType,
                            String requestUser, String rationale, Map<String, Object> beforeValues,
                            Map<String, Object> afterValues, Map<String, Object> evidence,
                            String mappingStatus, String calculationStatus, String reviewStatus,
                            boolean testOnlyExcelCreated, String outputSha256,
                            Map<String, Object> validationResults, String failureReason, String createdAt) {
        public EventView {
            beforeValues = immutable(beforeValues); afterValues = immutable(afterValues);
            evidence = immutable(evidence); validationResults = immutable(validationResults);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public record EventInput(String proposalId, String eventType, String changeType, String processCode,
                             String sheetName, long workingDocumentId, String workingSha256, String requestUser,
                             String managementNo, String beforeJson, String afterJson, String rationale,
                             String evidenceJson, String mappingStatus, String calculationStatus, String reviewStatus,
                             boolean testOnlyExcelCreated, String outputSha256, String validationResultJson,
                             String failureReason) { }
}
