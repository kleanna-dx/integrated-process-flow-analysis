package com.example.processautomation.change;

import com.example.processautomation.excel.OoxmlPackageInspector;
import com.example.processautomation.excel.WorkbookInventory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves changed equipment against immutable V1.0 mappings and process drawings. */
@Service
public class DiagramImpactAnalysisService {
    private static final String PROCESS_ROLE = "PROCESS";
    private final JdbcTemplate jdbc;
    private final OoxmlPackageInspector inspector;
    private volatile BaselineIndex cachedIndex;

    public DiagramImpactAnalysisService(JdbcTemplate jdbc, OoxmlPackageInspector inspector) {
        this.jdbc = jdbc;
        this.inspector = inspector;
    }

    public Analysis analyze(long baselineId, String permitNo, String businessItemNo, List<String> processCodes) {
        BaselineIndex index = baselineIndex(baselineId);
        List<ChangeDetectionResult.DiagramImpact> impacts = new ArrayList<>(
                index.mappingsByPermit().getOrDefault(permitNo, List.of()));
        boolean automaticMappingSucceeded = impacts.size() == 1
                && Set.of("MAPPED_PERMIT_ID", "MAPPED_ITEM_NO").contains(impacts.get(0).mappingStatus());
        Set<String> seen = new LinkedHashSet<>();
        impacts.forEach(impact -> seen.add(key(impact.processWorkbook(), impact.sheetName())));
        Set<String> normalizedCodes = new LinkedHashSet<>();
        if (processCodes != null) {
            processCodes.stream().filter(code -> code != null && !code.isBlank())
                    .map(DiagramImpactAnalysisService::normalizeProcessCode).forEach(normalizedCodes::add);
        }
        boolean itemNoUnique = isUniqueBusinessItem(index, businessItemNo);

        for (String code : normalizedCodes) {
            for (ProcessDocument document : index.documentsByCode().getOrDefault(code, List.of())) {
                List<WorkbookInventory.SheetInventory> allSheets = document.inventory().sheets();
                List<WorkbookInventory.SheetInventory> matchingCodeSheets = allSheets.stream()
                        .filter(sheet -> sheetCodeMatches(sheet.sheetName(), code)).toList();
                List<WorkbookInventory.SheetInventory> candidates = matchingCodeSheets.isEmpty() ? allSheets : matchingCodeSheets;
                if (candidates.isEmpty()) {
                    addCandidate(impacts, seen, new ChangeDetectionResult.DiagramImpact(code,
                            document.filename(), null, "NO_SHEETS_REVIEW", "PROCESS_CODE",
                            "Registered process workbook has no indexed sheets.", false));
                    continue;
                }

                List<WorkbookInventory.SheetInventory> permitMatches = candidates.stream()
                        .filter(sheet -> containsToken(sheet.searchableText(), permitNo)).toList();
                List<WorkbookInventory.SheetInventory> itemMatches = permitMatches.isEmpty() && itemNoUnique
                        ? candidates.stream().filter(sheet -> containsToken(sheet.searchableText(), businessItemNo)).toList()
                        : List.of();
                List<WorkbookInventory.SheetInventory> exactMatches = permitMatches.isEmpty() ? itemMatches : permitMatches;
                String matchMethod = permitMatches.isEmpty() ? "BUSINESS_ITEM_NO_TEXT" : "PERMIT_MANAGEMENT_NO_TEXT";
                String evidence = permitMatches.isEmpty() ? businessItemNo : permitNo;
                if (exactMatches.isEmpty()) {
                    for (WorkbookInventory.SheetInventory sheet : candidates) {
                        addCandidate(impacts, seen, new ChangeDetectionResult.DiagramImpact(code,
                                document.filename(), sheet.sheetName(), "PROCESS_CODE_ONLY_REVIEW", "PROCESS_CODE",
                                "Process-code candidate only; exact permit ID/item number was not found in this sheet. No equipment mapping or route was created.", false));
                    }
                    continue;
                }
                boolean singleExactCandidate = exactMatches.size() == 1;
                String status = singleExactCandidate ? "EXACT_IDENTIFIER_CANDIDATE_REVIEW" : "MULTI_SHEET_REVIEW";
                for (WorkbookInventory.SheetInventory sheet : exactMatches) {
                    addCandidate(impacts, seen, new ChangeDetectionResult.DiagramImpact(code,
                            document.filename(), sheet.sheetName(), status, matchMethod,
                            "Exact identifier text found: " + safeEvidence(evidence)
                                    + ". Candidate location only; no mapping, route, or official edit was performed.", false));
                }
            }
        }

        if (impacts.isEmpty()) {
            impacts.add(new ChangeDetectionResult.DiagramImpact(null, null, null, "NO_PROCESS_DIAGRAM_MATCH",
                    "NONE", "No existing equipment mapping or exact process-code document was found. No diagram was inferred.", false));
        }
        List<String> reviews = new ArrayList<>();
        for (ChangeDetectionResult.DiagramImpact impact : impacts) {
            if (!impact.automaticMappingSucceeded()) {
                reviews.add("Review diagram mapping for " + permitNo + ": " + impact.mappingStatus()
                        + (impact.processWorkbook() == null ? "" : " in " + impact.processWorkbook())
                        + (impact.sheetName() == null ? "" : " / " + impact.sheetName()) + ".");
            }
        }
        // A location mapping is not approval to edit a drawing or create a relation.
        reviews.add("Manual confirmation is required before modifying an official drawing; no route or relation was created.");
        return new Analysis(summarizeStatus(impacts, automaticMappingSucceeded), automaticMappingSucceeded,
                List.copyOf(impacts), List.copyOf(new LinkedHashSet<>(reviews)),
                index.documentsById().size(), index.processSheetCount());
    }

    private void addCandidate(List<ChangeDetectionResult.DiagramImpact> impacts, Set<String> seen,
                              ChangeDetectionResult.DiagramImpact candidate) {
        if (seen.add(key(candidate.processWorkbook(), candidate.sheetName()))) impacts.add(candidate);
    }

    private BaselineIndex baselineIndex(long baselineId) {
        BaselineIndex current = cachedIndex;
        if (current != null && current.baselineId() == baselineId) return current;
        synchronized (this) {
            current = cachedIndex;
            if (current != null && current.baselineId() == baselineId) return current;
            List<ProcessDocument> documents = jdbc.query("""
                    SELECT id, original_filename, process_code, content_bytes
                    FROM baseline_document
                    WHERE baseline_release_id = ? AND document_role = ?
                    ORDER BY process_code, original_filename
                    """, (rs, row) -> {
                long id = rs.getLong("id");
                byte[] bytes = rs.getBytes("content_bytes");
                return new ProcessDocument(id, rs.getString("original_filename"), rs.getString("process_code"),
                        bytes, inspector.inspect(bytes));
            }, baselineId, PROCESS_ROLE);
            Map<Long, ProcessDocument> documentsById = new HashMap<>();
            Map<String, List<ProcessDocument>> documentsByCodeMutable = new HashMap<>();
            int sheetCount = 0;
            for (ProcessDocument document : documents) {
                documentsById.put(document.id(), document);
                documentsByCodeMutable.computeIfAbsent(normalizeProcessCode(document.processCode()), ignored -> new ArrayList<>())
                        .add(document);
                sheetCount += document.inventory().sheets().size();
            }
            Map<String, List<ChangeDetectionResult.DiagramImpact>> mappingsByPermit = new HashMap<>();
            jdbc.query("""
                    SELECT e.permit_management_no, m.mapping_status, m.match_method, m.evidence_text,
                           d.process_code, d.original_filename, s.sheet_name
                    FROM equipment_record e
                    JOIN equipment_mapping m ON m.equipment_id = e.id AND m.baseline_release_id = e.baseline_release_id
                    LEFT JOIN baseline_document d ON d.id = m.process_document_id
                    LEFT JOIN workbook_sheet s ON s.id = m.workbook_sheet_id
                    WHERE e.baseline_release_id = ?
                    ORDER BY e.master_order, d.original_filename, s.sheet_index
                    """, rs -> {
                String permitNo = rs.getString("permit_management_no");
                mappingsByPermit.computeIfAbsent(permitNo, ignored -> new ArrayList<>()).add(
                        new ChangeDetectionResult.DiagramImpact(rs.getString("process_code"),
                                rs.getString("original_filename"), rs.getString("sheet_name"),
                                rs.getString("mapping_status"), rs.getString("match_method"),
                                rs.getString("evidence_text"), false));
            }, baselineId);
            Map<String, List<ChangeDetectionResult.DiagramImpact>> immutableMappings = new HashMap<>();
            mappingsByPermit.forEach((permit, values) -> {
                boolean unique = values.size() == 1 && Set.of("MAPPED_PERMIT_ID", "MAPPED_ITEM_NO")
                        .contains(values.get(0).mappingStatus());
                List<ChangeDetectionResult.DiagramImpact> classified = values.stream().map(value ->
                        new ChangeDetectionResult.DiagramImpact(value.processCode(), value.processWorkbook(),
                                value.sheetName(), value.mappingStatus(), value.matchMethod(), value.evidence(), unique)).toList();
                immutableMappings.put(permit, classified);
            });
            Map<String, List<ProcessDocument>> immutableByCode = new HashMap<>();
            documentsByCodeMutable.forEach((code, value) -> immutableByCode.put(code, List.copyOf(value)));
            current = new BaselineIndex(baselineId, Map.copyOf(documentsById), Map.copyOf(immutableByCode),
                    Map.copyOf(immutableMappings), sheetCount);
            cachedIndex = current;
            return current;
        }
    }

    private boolean isUniqueBusinessItem(BaselineIndex index, String itemNo) {
        if (itemNo == null || itemNo.isBlank() || "-".equals(itemNo.trim())) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_record
                WHERE baseline_release_id = ? AND UPPER(TRIM(business_item_no)) = UPPER(TRIM(?))
                """, Integer.class, index.baselineId(), itemNo);
        return count != null && count == 1;
    }

    private String summarizeStatus(List<ChangeDetectionResult.DiagramImpact> impacts, boolean automatic) {
        if (automatic) return "EXISTING_BASELINE_MAPPING_REVIEW_REQUIRED";
        if (impacts.stream().anyMatch(item -> "MULTI_SHEET_REVIEW".equals(item.mappingStatus()))) return "MULTI_SHEET_REVIEW";
        if (impacts.stream().anyMatch(item -> "UNMATCHED".equals(item.mappingStatus())
                || "NO_PROCESS_DOCUMENT".equals(item.mappingStatus()))) return "BASELINE_MAPPING_UNRESOLVED";
        if (impacts.stream().anyMatch(item -> "PROCESS_CODE_ONLY_REVIEW".equals(item.mappingStatus()))) return "PROCESS_CODE_ONLY_REVIEW";
        if (impacts.stream().anyMatch(item -> "NO_PROCESS_DIAGRAM_MATCH".equals(item.mappingStatus()))) return "NO_PROCESS_DIAGRAM_MATCH";
        return "REVIEW_REQUIRED";
    }

    private static String key(String filename, String sheet) {
        return (filename == null ? "" : filename) + "\u0000" + (sheet == null ? "" : sheet);
    }

    private static String normalizeProcessCode(String value) {
        if (value == null) return "";
        String code = value.trim().toUpperCase(Locale.ROOT);
        return code.startsWith("PP-") ? "P-" + code.substring(3) : code;
    }

    private boolean sheetCodeMatches(String sheetName, String processCode) {
        if (sheetName == null || processCode == null) return false;
        String sheet = sheetName.trim().toUpperCase(Locale.ROOT);
        if (!sheet.startsWith(processCode)) return false;
        return sheet.length() == processCode.length() || !Character.isLetterOrDigit(sheet.charAt(processCode.length()));
    }

    private boolean containsToken(String text, String token) {
        if (text == null || token == null || token.isBlank() || "-".equals(token.trim())) return false;
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(token.trim()) + "(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE);
        return pattern.matcher(text).find();
    }

    private String safeEvidence(String value) {
        return value == null ? "" : value.trim();
    }

    private record ProcessDocument(long id, String filename, String processCode, byte[] bytes,
                                   WorkbookInventory inventory) { }
    private record BaselineIndex(long baselineId, Map<Long, ProcessDocument> documentsById,
                                 Map<String, List<ProcessDocument>> documentsByCode,
                                 Map<String, List<ChangeDetectionResult.DiagramImpact>> mappingsByPermit,
                                 int processSheetCount) { }
    public record Analysis(String mappingStatus, boolean automaticMappingSucceeded,
                          List<ChangeDetectionResult.DiagramImpact> affectedDiagrams,
                          List<String> reviewRequiredItems, int processWorkbookCount,
                          int processSheetCount) { }
}
