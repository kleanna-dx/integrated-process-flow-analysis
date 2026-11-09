package com.example.processautomation.material;

import com.example.processautomation.excel.ExcelCellText;
import com.example.processautomation.excel.InvalidWorkbookException;
import com.example.processautomation.excel.MasterWorkbookParser;
import com.example.processautomation.model.MasterEquipment;
import com.example.processautomation.model.ParsedMaster;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Read-only Step 7-2 comparison. It never refreshes queries, evaluates source formulas, or writes source files. */
@Component
public class Step7MaterialBalanceComparisonService {
    private static final String INPUT_SHEET = "입력시트";
    private static final String CACHE_SHEET = "비교";

    private final MasterWorkbookParser masterParser;

    public Step7MaterialBalanceComparisonService(MasterWorkbookParser masterParser) {
        this.masterParser = masterParser;
    }

    public ComparisonReport compare(Path teamWorkbook, Path officialMaster, OfficialEvidenceSnapshot evidence) {
        String fileName = teamWorkbook == null ? "team-source.xlsm" : teamWorkbook.getFileName().toString();
        return compare(teamWorkbook, officialMaster, fileName, evidence);
    }

    public ComparisonReport compare(Path teamWorkbook, Path officialMaster, String sourceFilename,
                                    OfficialEvidenceSnapshot evidence) {
        if (teamWorkbook == null || officialMaster == null) {
            throw new IllegalArgumentException("Source workbook and official master are required");
        }
        try {
            return compare(Files.readAllBytes(teamWorkbook), Files.readAllBytes(officialMaster), sourceFilename, evidence);
        } catch (IOException exception) {
            throw new InvalidWorkbookException("Unable to read Step 7-2 source or baseline file", exception);
        }
    }

    public ComparisonReport compare(byte[] sourceBytes, byte[] masterBytes, String sourceFilename,
                                    OfficialEvidenceSnapshot evidence) {
        if (sourceBytes == null || masterBytes == null || evidence == null) {
            throw new IllegalArgumentException("Source workbook bytes, master bytes, and evidence snapshot are required");
        }
        ParsedMaster parsedMaster = masterParser.parse(masterBytes);
            Map<String, MasterEquipment> masterById = new LinkedHashMap<>();
            Set<String> pw01Ids = new TreeSet<>();
            for (MasterEquipment equipment : parsedMaster.equipment()) {
                masterById.put(equipment.permitManagementNo(), equipment);
                if (isPw01(equipment.majorProcessCode())) {
                    pw01Ids.add(equipment.permitManagementNo());
                }
            }

            SourceRead source = readSource(sourceBytes);
            Map<EndpointPair, Integer> pairCounts = new HashMap<>();
            for (RawTransfer transfer : source.transfers()) {
                pairCounts.merge(new EndpointPair(transfer.source(), transfer.target()), 1, Integer::sum);
            }

            List<ConnectionComparison> connections = new ArrayList<>(source.transfers().size());
            int bothMaster = 0;
            int oneMaster = 0;
            int pw01Rows = 0;
            int pw01BothMaster = 0;
            for (RawTransfer transfer : source.transfers()) {
                boolean sourceExact = masterById.containsKey(transfer.source());
                boolean targetExact = masterById.containsKey(transfer.target());
                boolean isPw01 = isPw01(transfer.sourceMajor()) || isPw01(transfer.targetMajor());
                if (sourceExact && targetExact) bothMaster++;
                else if (sourceExact || targetExact) oneMaster++;
                if (isPw01) {
                    pw01Rows++;
                    if (sourceExact && targetExact) pw01BothMaster++;
                }
                String reason = (!sourceExact || !targetExact
                        ? "출발 또는 도착 값이 공식 마스터 ID와 정확히 일치하지 않음; 외부 경계/별칭 여부 미확정"
                        : evidence.officialEquipmentFlowRows() == 0
                                ? "공식 equipment_flow 행이 없고 도면 connector endpoint 연결 근거도 없어 연결·유량 대조 불가"
                                : "개별 공식 연결 증거가 제공되지 않아 일치 여부 판단 불가")
                        + (blank(transfer.excludedRaw()) ? "; 계산 제외 기준 공란 — 포함/제외 해석 불가" : "");
                connections.add(new ConnectionComparison(
                        transfer.rowNumber(), transfer.source(), transfer.target(), transfer.quantity(), transfer.quantityText(),
                        transfer.materialType(), transfer.sourceMiddle(), transfer.targetMiddle(), transfer.sourceMajor(), transfer.targetMajor(),
                        transfer.excludedRaw(), transfer.quantityState(), sourceExact, targetExact, isPw01,
                        pairCounts.get(new EndpointPair(transfer.source(), transfer.target())), transfer.unit(), transfer.period(),
                        "INSUFFICIENT_EVIDENCE", reason));
            }

            List<BalanceSnapshot> balances = readCachedBalances(sourceBytes, pw01Ids, masterById);
            long cacheTrue = balances.stream().filter(row -> "true".equalsIgnoreCase(row.cachedCompared())).count();
            long cacheFalse = balances.stream().filter(row -> "false".equalsIgnoreCase(row.cachedCompared())).count();
            long cacheCompareMissing = balances.stream().filter(row -> blank(row.cachedCompared())).count();
            long cacheResidualBlank = balances.stream().filter(row -> row.cachedResidual() == null).count();
            long cacheResidualNonzero = balances.stream().filter(row -> row.cachedResidual() != null
                    && row.cachedResidual().compareTo(BigDecimal.ZERO) != 0).count();
            List<CycleComponent> cycles = findCycles(source.transfers());
            StatusCounts statusCounts = new StatusCounts(connections.size(), 0, 0, 0, 0, 0,
                    connections.size(), 0);
            return new ComparisonReport(blank(sourceFilename) ? "team-source.xlsm" : sourceFilename,
                    sha256(sourceBytes), sourceBytes.length,
                    source.transfers().size(), source.sheets(), source.inputColumns(), bothMaster, oneMaster,
                    source.transfers().size() - bothMaster - oneMaster, pw01Rows, pw01BothMaster,
                    connections.stream().filter(ConnectionComparison::pw01Scope)
                            .map(row -> new EndpointPair(row.source(), row.target())).distinct().count(),
                    countMassState(connections, "ZERO"), countMassState(connections, "POSITIVE"),
                    countMassState(connections, "BLANK"), countBlankType(connections), countBlankExclude(connections),
                    pairCounts.values().stream().filter(count -> count > 1).count(),
                    pairCounts.values().stream().filter(count -> count > 1).mapToInt(Integer::intValue).sum(),
                    cacheTrue, cacheFalse, cacheCompareMissing, cacheResidualBlank, cacheResidualNonzero,
                    evidence, connections, balances, cycles, statusCounts,
                    "Power Query 캐시와 입력표시값만 읽음. VBA/Power Query 미실행·미새로고침. 단위/기준기간은 원본 입력 열에 없음.");
    }

    /** Evidence-aware classifier. Numeric disagreement alone never establishes an error. */
    public static String classify(OfficialConnectionEvidence official) {
        if (official == null) return "INSUFFICIENT_EVIDENCE";
        if (official.independentlyVerifiedDbError()) return "DB_ERROR_CONFIRMED";
        if (official.independentlyVerifiedDiagramError()) return "LEGACY_DIAGRAM_ERROR_CONFIRMED";
        if (!official.explicitEndpointBinding() || blank(official.source()) || blank(official.target())
                || official.quantity() == null || blank(official.unit()) || blank(official.teamSource())
                || blank(official.teamTarget()) || official.teamQuantity() == null || blank(official.teamUnit())) {
            return "INSUFFICIENT_EVIDENCE";
        }
        if (official.quantity().compareTo(official.teamQuantity()) != 0
                || !official.unit().equalsIgnoreCase(official.teamUnit())
                || !official.source().equals(official.teamSource())
                || !official.target().equals(official.teamTarget())) return "SOURCE_DIFFERENCE_UNRESOLVED";
        return "MATCHED";
    }

    /** A pre-existing residual is not a new-change error. The caller must assert causal attribution. */
    public static String classifyChange(BigDecimal residualBefore, BigDecimal residualAfter, boolean causedByChange) {
        if (residualBefore == null || residualAfter == null) return "INSUFFICIENT_EVIDENCE";
        if (residualBefore.compareTo(BigDecimal.ZERO) != 0
                && residualBefore.compareTo(residualAfter) == 0) {
            return "EXISTING_BASELINE_IMBALANCE_NOT_NEW_ERROR";
        }
        if (!causedByChange) return "INSUFFICIENT_EVIDENCE";
        if (residualBefore.compareTo(BigDecimal.ZERO) != 0) {
            return "CHANGE_DELTA_ON_EXISTING_IMBALANCE_REQUIRES_REVIEW";
        }
        return residualAfter.compareTo(BigDecimal.ZERO) == 0 ? "NO_NEW_RESIDUAL" : "NEW_CHANGE_ERROR";
    }

    private SourceRead readSource(byte[] bytes) {
        try (java.io.InputStream stream = new java.io.ByteArrayInputStream(bytes);
             Workbook workbook = WorkbookFactory.create(stream)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Sheet input = workbook.getSheet(INPUT_SHEET);
            if (input == null) throw new InvalidWorkbookException("Required source sheet is missing: " + INPUT_SHEET);
            Header header = findHeader(input, formatter);
            List<RawTransfer> transfers = new ArrayList<>();
            for (int rowIndex = header.row().getRowNum() + 1; rowIndex <= input.getLastRowNum(); rowIndex++) {
                Row row = input.getRow(rowIndex);
                if (row == null) continue;
                String source = cell(row, header.columns().get("어디서"), formatter);
                String target = cell(row, header.columns().get("어디로"), formatter);
                if (source.isBlank() && target.isBlank()) continue;
                if (source.isBlank() || target.isBlank()) {
                    throw new InvalidWorkbookException("Source row has a missing endpoint at row " + (rowIndex + 1));
                }
                Cell quantityCell = row.getCell(header.columns().get("질량"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String quantityText = ExcelCellText.format(quantityCell, formatter).trim();
                BigDecimal quantity = decimal(quantityCell, quantityText);
                String massState = quantityText.isBlank() ? "BLANK"
                        : quantity == null ? "INVALID" : quantity.signum() == 0 ? "ZERO"
                        : quantity.signum() > 0 ? "POSITIVE" : "NEGATIVE";
                transfers.add(new RawTransfer(rowIndex + 1, source, target, quantity, quantityText,
                        cell(row, header.columns().get("종류"), formatter),
                        cell(row, header.columns().get("보내는중분류"), formatter),
                        cell(row, header.columns().get("받는중분류"), formatter),
                        cell(row, header.columns().get("보내는대분류"), formatter),
                        cell(row, header.columns().get("받는대분류"), formatter),
                        cell(row, header.columns().get("질량수지계산제외"), formatter), massState,
                        "", ""));
            }
            return new SourceRead(transfers, workbook.getNumberOfSheets(), header.columns().size());
        } catch (InvalidWorkbookException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to parse the source workbook in read-only mode", exception);
        }
    }

    private Header findHeader(Sheet sheet, DataFormatter formatter) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 20); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, Integer> columns = new HashMap<>();
            for (var cell : row) {
                String label = normalizeHeader(ExcelCellText.format(cell, formatter));
                if (!label.isBlank()) columns.put(label, cell.getColumnIndex());
            }
            if (columns.keySet().containsAll(List.of("어디서", "어디로", "질량", "종류", "보내는중분류",
                    "받는중분류", "보내는대분류", "받는대분류", "질량수지계산제외"))) return new Header(row, columns);
        }
        throw new InvalidWorkbookException("Required normalized input headers are missing in " + INPUT_SHEET);
    }

    private List<BalanceSnapshot> readCachedBalances(byte[] bytes, Set<String> pw01Ids,
                                                      Map<String, MasterEquipment> masterById) {
        try (java.io.InputStream stream = new java.io.ByteArrayInputStream(bytes);
             Workbook workbook = WorkbookFactory.create(stream)) {
            Sheet sheet = workbook.getSheet(CACHE_SHEET);
            if (sheet == null) return List.of();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<BalanceSnapshot> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String equipment = cell(row, 0, formatter);
                if (equipment.isBlank()) continue;
                BigDecimal inflow = decimal(cell(row, 1, formatter));
                BigDecimal outflow = decimal(cell(row, 2, formatter));
                String compared = cell(row, 3, formatter);
                BigDecimal residual = decimal(cell(row, 4, formatter));
                rows.add(new BalanceSnapshot(i + 1, equipment, inflow, outflow,
                        compared.isBlank() ? null : compared, residual, pw01Ids.contains(equipment),
                        masterById.containsKey(equipment) ? masterById.get(equipment).majorProcessCode() : "",
                        "TEAM_WORKBOOK_CACHED_VALUE_NOT_RECALCULATED"));
            }
            return List.copyOf(rows);
        } catch (Exception exception) {
            throw new InvalidWorkbookException("Unable to read saved comparison cache without recalculation", exception);
        }
    }

    private List<CycleComponent> findCycles(List<RawTransfer> transfers) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<EndpointPair, Integer> edgeCounts = new HashMap<>();
        for (RawTransfer transfer : transfers) {
            if (!isPw01(transfer.sourceMajor()) && !isPw01(transfer.targetMajor())) continue;
            graph.computeIfAbsent(transfer.source(), ignored -> new LinkedHashSet<>()).add(transfer.target());
            graph.computeIfAbsent(transfer.target(), ignored -> new LinkedHashSet<>());
            edgeCounts.merge(new EndpointPair(transfer.source(), transfer.target()), 1, Integer::sum);
        }
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        graph.keySet().forEach(node -> reverse.put(node, new LinkedHashSet<>()));
        graph.forEach((from, targets) -> targets.forEach(to -> reverse.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from)));
        List<String> finish = finishOrder(graph);
        Set<String> visited = new HashSet<>();
        List<CycleComponent> result = new ArrayList<>();
        int componentNumber = 0;
        for (int i = finish.size() - 1; i >= 0; i--) {
            String root = finish.get(i);
            if (!visited.add(root)) continue;
            TreeSet<String> nodes = new TreeSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                String node = stack.pop();
                nodes.add(node);
                for (String previous : reverse.getOrDefault(node, Set.of())) {
                    if (visited.add(previous)) stack.push(previous);
                }
            }
            boolean selfLoop = nodes.size() == 1 && graph.getOrDefault(nodes.first(), Set.of()).contains(nodes.first());
            if (nodes.size() < 2 && !selfLoop) continue;
            componentNumber++;
            long edges = edgeCounts.entrySet().stream().filter(entry -> nodes.contains(entry.getKey().source())
                    && nodes.contains(entry.getKey().target())).mapToLong(Map.Entry::getValue).sum();
            result.add(new CycleComponent("PW01-SCC-" + componentNumber, List.copyOf(nodes), edges,
                    "기재된 PW1 방향 코드로 산출한 강연결 순환 후보. 공식 도면 endpoint와 동일하다는 증거가 아니며 승인 관계가 아님."));
        }
        result.sort(Comparator.comparing(CycleComponent::componentId));
        return List.copyOf(result);
    }

    private List<String> finishOrder(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        List<String> finish = new ArrayList<>();
        for (String root : graph.keySet()) {
            if (visited.contains(root)) continue;
            Deque<VisitFrame> stack = new ArrayDeque<>();
            visited.add(root);
            stack.push(new VisitFrame(root, graph.getOrDefault(root, Set.of()).iterator()));
            while (!stack.isEmpty()) {
                VisitFrame frame = stack.peek();
                if (frame.children().hasNext()) {
                    String next = frame.children().next();
                    if (visited.add(next)) stack.push(new VisitFrame(next, graph.getOrDefault(next, Set.of()).iterator()));
                } else {
                    finish.add(stack.pop().node());
                }
            }
        }
        return finish;
    }

    private String cell(Row row, Integer column, DataFormatter formatter) {
        return column == null ? "" : ExcelCellText.format(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter).trim();
    }

    private String cell(Row row, int column, DataFormatter formatter) {
        return ExcelCellText.format(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter).trim();
    }

    private BigDecimal decimal(Cell cell, String formattedText) {
        if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            return Double.isFinite(numeric) ? BigDecimal.valueOf(numeric) : null;
        }
        if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            var cachedType = cell.getCachedFormulaResultType();
            if (cachedType == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                double numeric = cell.getNumericCellValue();
                return Double.isFinite(numeric) ? BigDecimal.valueOf(numeric) : null;
            }
            if (cachedType != org.apache.poi.ss.usermodel.CellType.STRING) return null;
        }
        if (blank(formattedText)) return null;
        try { return new BigDecimal(formattedText.replace(",", "").replace("\u00a0", "").trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private BigDecimal decimal(String text) {
        if (blank(text)) return null;
        try { return new BigDecimal(text.replace(",", "").replace("\u00a0", "").trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private long countMassState(List<ConnectionComparison> rows, String state) {
        return rows.stream().filter(row -> row.quantityState().equals(state)).count();
    }

    private long countBlankType(List<ConnectionComparison> rows) {
        return rows.stream().filter(row -> blank(row.materialType())).count();
    }

    private long countBlankExclude(List<ConnectionComparison> rows) {
        return rows.stream().filter(row -> blank(row.excludedRaw())).count();
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("[\\s._()（）·&/\\-]", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
    }

    private boolean isPw01(String value) {
        String code = normalizeCode(value);
        return code.equals("PW1") || code.equals("PW01");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Header(Row row, Map<String, Integer> columns) { }
    private record SourceRead(List<RawTransfer> transfers, int sheets, int inputColumns) { }
    private record RawTransfer(int rowNumber, String source, String target, BigDecimal quantity, String quantityText,
                               String materialType, String sourceMiddle, String targetMiddle, String sourceMajor,
                               String targetMajor, String excludedRaw, String quantityState, String unit, String period) { }
    private record EndpointPair(String source, String target) { }
    private record VisitFrame(String node, java.util.Iterator<String> children) { }

    public record OfficialEvidenceSnapshot(int baselineDocuments, int officialEquipmentRecords,
                                           int officialEquipmentFlowRows, int confirmedRelations,
                                           int reviewRequiredRelations, int pw01ConnectorCount,
                                           int pw01ExplicitEndpointBindings, String baselineVersion) { }

    public record OfficialConnectionEvidence(String source, String target, BigDecimal quantity, String unit,
                                              boolean explicitEndpointBinding, boolean independentlyVerifiedDbError,
                                              boolean independentlyVerifiedDiagramError, String teamSource,
                                              String teamTarget, BigDecimal teamQuantity, String teamUnit) { }

    public record ConnectionComparison(int sourceRow, String source, String target, BigDecimal quantity,
                                      String quantityText, String materialType, String sourceMiddle,
                                      String targetMiddle, String sourceMajor, String targetMajor,
                                      String excludedRaw, String quantityState, boolean sourceExactMasterMatch,
                                      boolean targetExactMasterMatch, boolean pw01Scope, int duplicateEndpointCount,
                                      String unit, String period, String status, String evidenceNote) { }

    public record BalanceSnapshot(int sourceRow, String equipment, BigDecimal cachedInflow, BigDecimal cachedOutflow,
                                  String cachedCompared, BigDecimal cachedResidual, boolean pw01MasterEquipment,
                                  String officialMajorProcessCode, String provenance) { }

    public record CycleComponent(String componentId, List<String> nodeIds, long connectionRows, String note) { }

    public record StatusCounts(long totalConnections, long matched, long sourceDifferenceUnresolved,
                               long dbErrorConfirmed, long legacyDiagramErrorConfirmed,
                               long insufficientEvidence, long baselineRowsInsufficientEvidence,
                               long newChangeErrors) { }

    public record ComparisonReport(String sourceFilename, String sourceSha256, long sourceBytes,
                                   long inputRows, int workbookSheetCount, int inputColumnCount,
                                   long bothEndpointsMasterMatched, long oneEndpointMasterMatched,
                                   long noEndpointMasterMatch, long pw01InputRows, long pw01BothEndpointsMasterMatched,
                                   long pw01UniqueEndpointPairs, long zeroQuantityRows, long positiveQuantityRows,
                                   long blankQuantityRows, long blankMaterialTypeRows, long blankExclusionRows,
                                   long duplicatedEndpointPairGroups, long rowsWithinDuplicatedEndpointPairs,
                                   long cachedComparedTrueRows, long cachedComparedFalseRows,
                                   long cachedComparedMissingRows, long cachedResidualBlankRows,
                                   long cachedResidualNonzeroRows, OfficialEvidenceSnapshot officialEvidence,
                                   List<ConnectionComparison> connections, List<BalanceSnapshot> cachedBalances,
                                   List<CycleComponent> cycleComponents, StatusCounts statuses, String limitations) {
        public ComparisonReport {
            connections = List.copyOf(connections);
            cachedBalances = List.copyOf(cachedBalances);
            cycleComponents = List.copyOf(cycleComponents);
        }
    }
}
