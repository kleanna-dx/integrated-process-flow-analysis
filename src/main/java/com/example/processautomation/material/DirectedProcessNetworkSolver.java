package com.example.processautomation.material;

import static com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_INFLOW;
import static com.example.processautomation.material.DirectedProcessNetwork.ConnectionType.EXTERNAL_OUTFLOW;
import static com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SINK;
import static com.example.processautomation.material.DirectedProcessNetwork.NodeType.EXTERNAL_SOURCE;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Linear conservation solver for an isolated directed TEST_ONLY process network. */
final class DirectedProcessNetworkSolver {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000000001");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private DirectedProcessNetworkSolver() { }

    static Result solve(DirectedProcessNetwork base, String requestedScenarioId,
                        String changedConnectionId, BigDecimal replacementQuantity) {
        if (base == null) return unavailable("", "", "", List.of("NETWORK_REQUIRED"));
        String scenarioId = blank(requestedScenarioId) ? base.networkId() : requestedScenarioId;
        String scenarioName = base.scenarioName();
        List<String> errors = validate(base);
        List<DirectedProcessNetwork.Edge> edges = new ArrayList<>(base.edges());
        BigDecimal previous = null;
        if (changedConnectionId != null) {
            int index = -1;
            for (int i = 0; i < edges.size(); i++) {
                if (changedConnectionId.equals(edges.get(i).connectionId())) index = i;
            }
            if (index < 0) errors.add("CHANGED_CONNECTION_NOT_FOUND:" + changedConnectionId);
            else if (replacementQuantity == null || replacementQuantity.signum() < 0) {
                errors.add("REPLACEMENT_QUANTITY_MUST_BE_NONNEGATIVE:" + changedConnectionId);
            } else {
                DirectedProcessNetwork.Edge old = edges.get(index);
                previous = old.quantity();
                edges.set(index, new DirectedProcessNetwork.Edge(old.connectionId(), old.sourceNodeId(),
                        old.targetNodeId(), old.sourceProcess(), old.targetProcess(), old.materialClass(),
                        old.materialType(), old.unit(), replacementQuantity, old.connectionType(),
                        DirectedProcessNetwork.QuantityBasis.USER_INPUT, true, true, old.evidence(), old.reviewStatus(), old.period()));
            }
        }
        if (!errors.isEmpty()) return unavailable(scenarioId, scenarioName,
                changedConnectionId == null ? "" : changedConnectionId, errors);

        Map<String, DirectedProcessNetwork.Node> nodes = new LinkedHashMap<>();
        base.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        Map<Stream, List<DirectedProcessNetwork.Edge>> grouped = new LinkedHashMap<>();
        for (var edge : edges) grouped.computeIfAbsent(Stream.of(edge), ignored -> new ArrayList<>()).add(edge);
        Map<String, BigDecimal> resolved = new LinkedHashMap<>();
        Map<Stream, String> streamStatuses = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            StreamSolution solution = solveStream(entry.getKey(), entry.getValue(), base.nodes(), base.processTerms());
            streamStatuses.put(entry.getKey(), solution.status());
            issues.addAll(solution.issues());
            resolved.putAll(solution.quantities());
        }

        List<EdgeResult> edgeResults = new ArrayList<>();
        for (var edge : edges) {
            BigDecimal quantity = resolved.get(edge.connectionId());
            String status = quantity == null ? streamStatuses.getOrDefault(Stream.of(edge), "CALCULATION_UNAVAILABLE") : "CALCULATED";
            if (quantity != null && quantity.signum() < 0) status = "NEGATIVE_FLOW_CONSTRAINT_VIOLATION";
            edgeResults.add(new EdgeResult(edge.connectionId(), endpoint(nodes.get(edge.sourceNodeId())),
                    endpoint(nodes.get(edge.targetNodeId())), edge.sourceProcess(), edge.targetProcess(),
                    edge.materialClass(), edge.materialType(), edge.unit(),
                    quantity, edge.connectionId().equals(changedConnectionId) ? previous : edge.quantity(),
                    edge.quantityBasis(), edge.userSupplied(), edge.fixed(), edge.connectionType(), status,
                    quantity != null && quantity.signum() < 0 ? "NONNEGATIVE_FLOW_REQUIRED" : null,
                    edge.reviewStatus(), edge.evidence(), edge.period()));
        }
        edgeResults.sort(Comparator.comparing(EdgeResult::connectionId));

        List<NodeBalance> nodeBalances = calculateNodeBalances(base.nodes(), edges, resolved, streamStatuses, base.processTerms());
        List<ProcessBalance> processBalances = calculateProcessBalances(base.nodes(), edges, resolved,
                streamStatuses, base.processTerms());
        List<ExternalBalance> externalBalances = calculateExternalBalances(base.nodes(), edges, resolved, streamStatuses);
        String status = overallStatus(streamStatuses, edgeResults, nodeBalances, processBalances, externalBalances, errors);
        List<String> impacts = impactedEquipment(changedConnectionId, edges, nodes);
        List<String> impactedProcesses = impacts.stream().map(number -> nodes.values().stream()
                .filter(node -> number.equals(node.equipmentNumber())).map(DirectedProcessNetwork.Node::processCode)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        BigDecimal oldValue = previous;
        BigDecimal newValue = changedConnectionId == null ? null : replacementQuantity;
        return new Result(scenarioId, scenarioName, "TEST_ONLY", status, changedConnectionId,
                oldValue, newValue, List.copyOf(edgeResults), List.copyOf(nodeBalances),
                List.copyOf(processBalances), List.copyOf(externalBalances), List.copyOf(impacts),
                List.copyOf(impactedProcesses), List.copyOf(new LinkedHashSet<>(issues)), false, false);
    }

    private static List<String> validate(DirectedProcessNetwork network) {
        List<String> errors = new ArrayList<>();
        if (!"TEST_ONLY".equalsIgnoreCase(network.mode())) errors.add("TEST_ONLY_MODE_REQUIRED");
        if (blank(network.networkId())) errors.add("NETWORK_ID_REQUIRED");
        if (network.nodes().isEmpty()) errors.add("NETWORK_NODES_REQUIRED");
        Map<String, DirectedProcessNetwork.Node> nodes = new LinkedHashMap<>();
        Set<String> equipmentNumbers = new HashSet<>();
        for (var node : network.nodes()) {
            if (node == null || blank(node.nodeId()) || nodes.putIfAbsent(node.nodeId(), node) != null) {
                errors.add("NODE_ID_MISSING_OR_DUPLICATE");
                continue;
            }
            if (node.nodeType() == null || blank(node.reviewStatus())) errors.add("NODE_TYPE_OR_REVIEW_STATUS_REQUIRED:" + node.nodeId());
            if (!"TEST_ONLY".equalsIgnoreCase(node.reviewStatus())) errors.add("NODE_NOT_TEST_ONLY:" + node.nodeId());
            if (node.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT) {
                if (blank(node.equipmentNumber()) || blank(node.processCode())) {
                    errors.add("EQUIPMENT_NUMBER_AND_PROCESS_REQUIRED:" + node.nodeId());
                } else if (!equipmentNumbers.add(node.equipmentNumber())) {
                    errors.add("EQUIPMENT_NUMBER_DUPLICATE:" + node.equipmentNumber());
                }
            }
            if ((node.nodeType() == EXTERNAL_SOURCE || node.nodeType() == EXTERNAL_SINK)
                    && !blank(node.equipmentNumber())) errors.add("EXTERNAL_BOUNDARY_MUST_NOT_MASQUERADE_AS_EQUIPMENT:" + node.nodeId());
        }
        Set<String> connectionIds = new HashSet<>();
        for (var edge : network.edges()) {
            if (edge == null || blank(edge.connectionId()) || !connectionIds.add(edge.connectionId())) {
                errors.add("CONNECTION_ID_MISSING_OR_DUPLICATE");
                continue;
            }
            DirectedProcessNetwork.Node source = nodes.get(edge.sourceNodeId());
            DirectedProcessNetwork.Node target = nodes.get(edge.targetNodeId());
            if (source == null || target == null) errors.add("UNRESOLVED_ENDPOINT:" + edge.connectionId());
            if (blank(edge.sourceNodeId()) || blank(edge.targetNodeId())) errors.add("ENDPOINT_REQUIRED:" + edge.connectionId());
            if (edge.materialClass() == null || blank(edge.materialType()) || blank(edge.unit())) {
                errors.add("MATERIAL_TYPE_AND_UNIT_REQUIRED:" + edge.connectionId());
            }
            if (blank(edge.period()) || "TEST_ONLY_UNSPECIFIED".equals(edge.period())) {
                errors.add("MEASUREMENT_PERIOD_REQUIRED:" + edge.connectionId());
            }
            if (edge.connectionType() == null || edge.quantityBasis() == null || blank(edge.reviewStatus())) {
                errors.add("EDGE_TYPE_BASIS_AND_REVIEW_STATUS_REQUIRED:" + edge.connectionId());
            }
            if (!"TEST_ONLY".equalsIgnoreCase(edge.reviewStatus())) errors.add("EDGE_NOT_TEST_ONLY:" + edge.connectionId());
            if (edge.fixed() && edge.quantity() == null) errors.add("FIXED_FLOW_REQUIRES_QUANTITY:" + edge.connectionId());
            if (edge.fixed() && edge.quantity() != null && edge.quantity().signum() < 0) {
                errors.add("NEGATIVE_FIXED_FLOW_NOT_ALLOWED:" + edge.connectionId());
            }
            if (!edge.fixed() && edge.quantity() != null) errors.add("UNKNOWN_FLOW_MUST_NOT_HAVE_SEED_QUANTITY:" + edge.connectionId());
            if (blank(edge.evidence())) errors.add("CONNECTION_EVIDENCE_REQUIRED:" + edge.connectionId());
            if (source != null && !boundaryConnectionConsistent(edge, source, target)) {
                errors.add("CONNECTION_PROCESS_OR_BOUNDARY_MISMATCH:" + edge.connectionId());
            }
        }
        for (var term : network.processTerms()) {
            if (term == null || blank(term.processCode()) || term.materialClass() == null
                    || blank(term.materialType()) || blank(term.unit()) || blank(term.period())
                    || "TEST_ONLY_UNSPECIFIED".equals(term.period()) || term.netGeneration() == null
                    || term.quantityBasis() == null || blank(term.evidence()) || blank(term.reviewStatus())) {
                errors.add("PROCESS_TERM_INCOMPLETE");
            } else if (!"TEST_ONLY".equalsIgnoreCase(term.reviewStatus())) {
                errors.add("PROCESS_TERM_NOT_TEST_ONLY:" + term.processCode());
            } else if (term.nodeId() != null && !nodes.containsKey(term.nodeId())) {
                errors.add("PROCESS_TERM_NODE_UNRESOLVED:" + term.nodeId());
            } else if (term.nodeId() != null && !term.processCode().equals(nodes.get(term.nodeId()).processCode())) {
                errors.add("PROCESS_TERM_PROCESS_MISMATCH:" + term.nodeId());
            }
        }
        return errors;
    }

    private static boolean boundaryConnectionConsistent(DirectedProcessNetwork.Edge edge,
                                                        DirectedProcessNetwork.Node source,
                                                        DirectedProcessNetwork.Node target) {
        if (source == null || target == null) return true;
        if (source.nodeType() == EXTERNAL_SOURCE && edge.connectionType() != EXTERNAL_INFLOW) return false;
        if (target.nodeType() == EXTERNAL_SINK && edge.connectionType() != EXTERNAL_OUTFLOW) return false;
        if (edge.connectionType() == EXTERNAL_INFLOW && source.nodeType() != EXTERNAL_SOURCE) return false;
        if (edge.connectionType() == EXTERNAL_OUTFLOW && target.nodeType() != EXTERNAL_SINK) return false;
        if (source.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT
                && !java.util.Objects.equals(source.processCode(), edge.sourceProcess())) return false;
        if (target.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT
                && !java.util.Objects.equals(target.processCode(), edge.targetProcess())) return false;
        return true;
    }

    private static StreamSolution solveStream(Stream stream, List<DirectedProcessNetwork.Edge> edges,
                                              List<DirectedProcessNetwork.Node> nodes,
                                              List<DirectedProcessNetwork.ProcessTerm> terms) {
        List<DirectedProcessNetwork.Edge> unknown = edges.stream().filter(edge -> !edge.fixed()).toList();
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        edges.stream().filter(DirectedProcessNetwork.Edge::fixed)
                .forEach(edge -> quantities.put(edge.connectionId(), edge.quantity()));
        if (unknown.isEmpty()) return new StreamSolution("SOLVED", quantities, List.of());
        Map<String, DirectedProcessNetwork.Node> nodeById = new LinkedHashMap<>();
        nodes.forEach(node -> nodeById.put(node.nodeId(), node));
        List<Equation> equations = new ArrayList<>();
        nodes.stream().filter(node -> node.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT)
                .filter(node -> edges.stream().anyMatch(edge -> edge.sourceNodeId().equals(node.nodeId())
                        || edge.targetNodeId().equals(node.nodeId())))
                .forEach(node -> equations.add(new Equation(node.processCode(), node.nodeId())));
        edges.stream().flatMap(edge -> java.util.stream.Stream.of(edge.sourceProcess(), edge.targetProcess()))
                .filter(process -> !blank(process)).distinct().sorted()
                .forEach(process -> equations.add(new Equation(process, null)));
        int n = unknown.size();
        BigDecimal[][] matrix = new BigDecimal[equations.size()][n + 1];
        for (int row = 0; row < matrix.length; row++) {
            java.util.Arrays.fill(matrix[row], ZERO);
        }
        for (int row = 0; row < equations.size(); row++) {
            Equation equation = equations.get(row);
            BigDecimal knownIn = ZERO;
            BigDecimal knownOut = ZERO;
            for (var edge : edges) {
                if (!edge.fixed()) continue;
                boolean inbound = equation.nodeId() != null
                        ? edge.targetNodeId().equals(equation.nodeId())
                        : processInflow(edge, equation.processCode(), nodeById);
                boolean outbound = equation.nodeId() != null
                        ? edge.sourceNodeId().equals(equation.nodeId())
                        : processOutflow(edge, equation.processCode(), nodeById);
                if (inbound) knownIn = knownIn.add(edge.quantity());
                if (outbound) knownOut = knownOut.add(edge.quantity());
            }
            BigDecimal generation = terms.stream().filter(term -> sameStream(stream, term)
                            && (equation.nodeId() != null ? equation.nodeId().equals(term.nodeId())
                            : equation.processCode().equals(term.processCode())))
                    .map(DirectedProcessNetwork.ProcessTerm::netGeneration).reduce(ZERO, BigDecimal::add);
            BigDecimal rhs = knownOut.subtract(knownIn).subtract(generation);
            for (int column = 0; column < n; column++) {
                var edge = unknown.get(column);
                boolean inbound = equation.nodeId() != null
                        ? edge.targetNodeId().equals(equation.nodeId())
                        : processInflow(edge, equation.processCode(), nodeById);
                boolean outbound = equation.nodeId() != null
                        ? edge.sourceNodeId().equals(equation.nodeId())
                        : processOutflow(edge, equation.processCode(), nodeById);
                if (inbound) matrix[row][column] = matrix[row][column].add(BigDecimal.ONE);
                if (outbound) matrix[row][column] = matrix[row][column].subtract(BigDecimal.ONE);
            }
            matrix[row][n] = rhs;
        }
        LinearSolution solved = gaussianElimination(matrix, n);
        if (solved.inconsistent()) return new StreamSolution("INCONSISTENT", quantities,
                List.of("INCONSISTENT_CONSERVATION_EQUATIONS:" + stream.id()));
        if (solved.rank() < n) return new StreamSolution("UNDERDETERMINED", quantities,
                List.of("ADDITIONAL_FLOW_CONDITION_REQUIRED:" + stream.id()));
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal value = solved.values()[i].round(MC).stripTrailingZeros();
            quantities.put(unknown.get(i).connectionId(), value);
            if (value.compareTo(ZERO) < 0) issues.add("NEGATIVE_FLOW_CONSTRAINT_VIOLATION:" + unknown.get(i).connectionId()
                    + ":" + value.toPlainString());
        }
        return new StreamSolution(issues.isEmpty() ? "SOLVED" : "NEGATIVE_FLOW_CONSTRAINT_VIOLATION",
                quantities, issues);
    }

    private static boolean processInflow(DirectedProcessNetwork.Edge edge, String process,
                                         Map<String, DirectedProcessNetwork.Node> nodes) {
        boolean external = nodes.get(edge.sourceNodeId()).nodeType() == EXTERNAL_SOURCE
                || edge.connectionType() == EXTERNAL_INFLOW;
        return process.equals(edge.targetProcess()) && (!process.equals(edge.sourceProcess()) || external);
    }

    private static boolean processOutflow(DirectedProcessNetwork.Edge edge, String process,
                                          Map<String, DirectedProcessNetwork.Node> nodes) {
        boolean external = nodes.get(edge.targetNodeId()).nodeType() == EXTERNAL_SINK
                || edge.connectionType() == EXTERNAL_OUTFLOW;
        return process.equals(edge.sourceProcess()) && (!process.equals(edge.targetProcess()) || external);
    }

    private static LinearSolution gaussianElimination(BigDecimal[][] input, int variableCount) {
        int rows = input.length;
        BigDecimal[][] a = new BigDecimal[rows][variableCount + 1];
        for (int r = 0; r < rows; r++) a[r] = input[r].clone();
        int pivotRow = 0;
        int[] pivotForColumn = new int[variableCount];
        java.util.Arrays.fill(pivotForColumn, -1);
        for (int col = 0; col < variableCount && pivotRow < rows; col++) {
            int best = pivotRow;
            for (int r = pivotRow + 1; r < rows; r++) {
                if (a[r][col].abs().compareTo(a[best][col].abs()) > 0) best = r;
            }
            if (a[best][col].abs().compareTo(TOLERANCE) <= 0) continue;
            BigDecimal[] swap = a[pivotRow]; a[pivotRow] = a[best]; a[best] = swap;
            BigDecimal pivot = a[pivotRow][col];
            for (int c = col; c <= variableCount; c++) a[pivotRow][c] = a[pivotRow][c].divide(pivot, MC);
            for (int r = 0; r < rows; r++) {
                if (r == pivotRow) continue;
                BigDecimal factor = a[r][col];
                if (factor.signum() == 0) continue;
                for (int c = col; c <= variableCount; c++) {
                    a[r][c] = a[r][c].subtract(factor.multiply(a[pivotRow][c], MC), MC);
                }
            }
            pivotForColumn[col] = pivotRow++;
        }
        for (int r = 0; r < rows; r++) {
            boolean zeroCoefficients = true;
            for (int c = 0; c < variableCount; c++) {
                if (a[r][c].abs().compareTo(TOLERANCE) > 0) zeroCoefficients = false;
            }
            if (zeroCoefficients && a[r][variableCount].abs().compareTo(TOLERANCE) > 0) {
                return new LinearSolution(pivotRow, true, zeroValues(variableCount));
            }
        }
        BigDecimal[] values = zeroValues(variableCount);
        for (int col = 0; col < variableCount; col++) {
            if (pivotForColumn[col] >= 0) values[col] = a[pivotForColumn[col]][variableCount];
        }
        return new LinearSolution(pivotRow, false, values);
    }

    private static BigDecimal[] zeroValues(int length) {
        BigDecimal[] values = new BigDecimal[length];
        java.util.Arrays.fill(values, ZERO);
        return values;
    }

    private static List<NodeBalance> calculateNodeBalances(List<DirectedProcessNetwork.Node> nodes,
                                                            List<DirectedProcessNetwork.Edge> edges,
                                                            Map<String, BigDecimal> quantities,
                                                            Map<Stream, String> statuses,
                                                            List<DirectedProcessNetwork.ProcessTerm> terms) {
        List<NodeBalance> results = new ArrayList<>();
        for (var node : nodes) {
            if (node.nodeType() != DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT) continue;
            Set<Stream> streams = new LinkedHashSet<>();
            edges.stream().filter(edge -> edge.sourceNodeId().equals(node.nodeId()) || edge.targetNodeId().equals(node.nodeId()))
                    .forEach(edge -> streams.add(Stream.of(edge)));
            for (Stream stream : streams) {
                BigDecimal inflow = ZERO, outflow = ZERO;
                BigDecimal generation = terms.stream().filter(term -> node.nodeId().equals(term.nodeId())
                                && Stream.of(term).equals(stream))
                        .map(DirectedProcessNetwork.ProcessTerm::netGeneration).reduce(ZERO, BigDecimal::add);
                boolean complete = true, negative = false;
                for (var edge : edges) {
                    if (!Stream.of(edge).equals(stream)) continue;
                    BigDecimal q = quantities.get(edge.connectionId());
                    boolean incident = edge.sourceNodeId().equals(node.nodeId()) || edge.targetNodeId().equals(node.nodeId());
                    if (q == null) { if (incident) complete = false; continue; }
                    if (incident && q.signum() < 0) negative = true;
                    if (edge.targetNodeId().equals(node.nodeId())) inflow = inflow.add(q);
                    if (edge.sourceNodeId().equals(node.nodeId())) outflow = outflow.add(q);
                }
                BigDecimal residual = complete ? inflow.add(generation).subtract(outflow) : null;
                String status = !complete ? statuses.getOrDefault(stream, "UNDERDETERMINED")
                        : negative ? "NEGATIVE_FLOW_CONSTRAINT_VIOLATION"
                        : residual.abs().compareTo(TOLERANCE) <= 0 ? "BALANCED" : "RESIDUAL_UNRESOLVED";
                results.add(new NodeBalance(node.equipmentNumber(), node.processCode(), node.label(),
                        node.sourceDocument(), node.sourceSheet(), node.reviewStatus(),
                        stream.materialClass(), stream.materialType(), stream.unit(), inflow, outflow,
                        generation, residual, status, stream.period()));
            }
        }
        results.sort(Comparator.comparing(NodeBalance::equipmentNumber).thenComparing(balance -> balance.materialType()));
        return results;
    }

    private static List<ProcessBalance> calculateProcessBalances(List<DirectedProcessNetwork.Node> nodes,
                                                                  List<DirectedProcessNetwork.Edge> edges,
                                                                  Map<String, BigDecimal> quantities,
                                                                  Map<Stream, String> statuses,
                                                                  List<DirectedProcessNetwork.ProcessTerm> terms) {
        Set<String> processes = new LinkedHashSet<>();
        nodes.stream().filter(node -> node.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT)
                .map(DirectedProcessNetwork.Node::processCode).forEach(processes::add);
        List<ProcessBalance> results = new ArrayList<>();
        for (String process : processes) {
            Set<Stream> streams = new LinkedHashSet<>();
            edges.stream().filter(edge -> process.equals(edge.sourceProcess()) || process.equals(edge.targetProcess()))
                    .forEach(edge -> streams.add(Stream.of(edge)));
            terms.stream().filter(term -> process.equals(term.processCode())).forEach(term -> streams.add(Stream.of(term)));
            for (Stream stream : streams) {
                BigDecimal inflow = ZERO, outflow = ZERO, generation = ZERO;
                boolean complete = true, negative = false;
                for (var edge : edges) {
                    if (!Stream.of(edge).equals(stream)) continue;
                    boolean relevant = process.equals(edge.sourceProcess()) || process.equals(edge.targetProcess());
                    BigDecimal q = quantities.get(edge.connectionId());
                    if (q == null) { if (relevant) complete = false; continue; }
                    if (relevant && q.signum() < 0) negative = true;
                    if (process.equals(edge.targetProcess()) && (!process.equals(edge.sourceProcess()) || edge.connectionType() == EXTERNAL_INFLOW)) inflow = inflow.add(q);
                    if (process.equals(edge.sourceProcess()) && (!process.equals(edge.targetProcess()) || edge.connectionType() == EXTERNAL_OUTFLOW)) outflow = outflow.add(q);
                }
                for (var term : terms) {
                    if (process.equals(term.processCode()) && Stream.of(term).equals(stream)) generation = generation.add(term.netGeneration());
                }
                BigDecimal residual = complete ? inflow.add(generation).subtract(outflow) : null;
                String status = !complete ? statuses.getOrDefault(stream, "UNDERDETERMINED")
                        : negative ? "NEGATIVE_FLOW_CONSTRAINT_VIOLATION"
                        : residual.abs().compareTo(TOLERANCE) <= 0 ? "BALANCED" : "RESIDUAL_UNRESOLVED";
                results.add(new ProcessBalance(process, stream.materialClass(), stream.materialType(), stream.unit(),
                        inflow, outflow, generation, residual, status, stream.period()));
            }
        }
        results.sort(Comparator.comparing(ProcessBalance::processCode).thenComparing(balance -> balance.materialType()));
        return results;
    }

    private static List<ExternalBalance> calculateExternalBalances(List<DirectedProcessNetwork.Node> nodes,
                                                                    List<DirectedProcessNetwork.Edge> edges,
                                                                    Map<String, BigDecimal> quantities,
                                                                    Map<Stream, String> statuses) {
        Map<String, DirectedProcessNetwork.Node> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.nodeId(), node));
        Set<Stream> streams = new LinkedHashSet<>();
        edges.forEach(edge -> streams.add(Stream.of(edge)));
        List<ExternalBalance> results = new ArrayList<>();
        for (Stream stream : streams) {
            BigDecimal inflow = ZERO, outflow = ZERO;
            boolean complete = true;
            for (var edge : edges) {
                if (!Stream.of(edge).equals(stream)) continue;
                BigDecimal q = quantities.get(edge.connectionId());
                if (q == null) {
                    if (byId.get(edge.sourceNodeId()).nodeType() == EXTERNAL_SOURCE
                            || byId.get(edge.targetNodeId()).nodeType() == EXTERNAL_SINK) complete = false;
                    continue;
                }
                if (byId.get(edge.sourceNodeId()).nodeType() == EXTERNAL_SOURCE || edge.connectionType() == EXTERNAL_INFLOW) inflow = inflow.add(q);
                if (byId.get(edge.targetNodeId()).nodeType() == EXTERNAL_SINK || edge.connectionType() == EXTERNAL_OUTFLOW) outflow = outflow.add(q);
            }
            BigDecimal residual = complete ? inflow.subtract(outflow) : null;
            String status = !complete ? statuses.getOrDefault(stream, "UNDERDETERMINED")
                    : residual.abs().compareTo(TOLERANCE) <= 0 ? "BALANCED" : "RESIDUAL_UNRESOLVED";
            results.add(new ExternalBalance(stream.materialClass(), stream.materialType(), stream.unit(), inflow, outflow, residual, status, stream.period()));
        }
        results.sort(Comparator.comparing(balance -> balance.materialType()));
        return results;
    }

    private static List<String> impactedEquipment(String changedId, List<DirectedProcessNetwork.Edge> edges,
                                                   Map<String, DirectedProcessNetwork.Node> nodes) {
        if (changedId == null) return List.of();
        DirectedProcessNetwork.Edge changed = edges.stream().filter(edge -> changedId.equals(edge.connectionId())).findFirst().orElse(null);
        if (changed == null) return List.of();
        Map<String, Set<String>> adjacent = new HashMap<>();
        for (var edge : edges) {
            adjacent.computeIfAbsent(edge.sourceNodeId(), ignored -> new LinkedHashSet<>()).add(edge.targetNodeId());
            adjacent.computeIfAbsent(edge.targetNodeId(), ignored -> new LinkedHashSet<>()).add(edge.sourceNodeId());
        }
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(changed.sourceNodeId(), changed.targetNodeId()));
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) continue;
            queue.addAll(adjacent.getOrDefault(id, Set.of()));
        }
        return visited.stream().map(nodes::get).filter(java.util.Objects::nonNull)
                .filter(node -> node.nodeType() == DirectedProcessNetwork.NodeType.PROCESS_EQUIPMENT)
                .map(DirectedProcessNetwork.Node::equipmentNumber).filter(value -> !blank(value)).distinct().sorted().toList();
    }

    private static String overallStatus(Map<Stream, String> streamStatuses, List<EdgeResult> edges,
                                        List<NodeBalance> nodes, List<ProcessBalance> processes,
                                        List<ExternalBalance> external, List<String> errors) {
        if (!errors.isEmpty()) return "CALCULATION_UNAVAILABLE";
        if (streamStatuses.containsValue("INCONSISTENT")) return "INCONSISTENT";
        if (edges.stream().anyMatch(edge -> "NEGATIVE_FLOW_CONSTRAINT_VIOLATION".equals(edge.status()))) return "CONSTRAINT_VIOLATION";
        if (streamStatuses.containsValue("UNDERDETERMINED")) return "UNDERDETERMINED";
        if (edges.stream().anyMatch(edge -> edge.quantity() == null)) return "CALCULATION_UNAVAILABLE";
        if (nodes.stream().anyMatch(balance -> "RESIDUAL_UNRESOLVED".equals(balance.status()))
                || processes.stream().anyMatch(balance -> "RESIDUAL_UNRESOLVED".equals(balance.status()))
                || external.stream().anyMatch(balance -> "RESIDUAL_UNRESOLVED".equals(balance.status()))) {
            return "RESIDUAL_UNRESOLVED";
        }
        return "CALCULATED";
    }

    private static Result unavailable(String scenarioId, String scenarioName, String changedId, List<String> errors) {
        return new Result(scenarioId, scenarioName, "TEST_ONLY", "CALCULATION_UNAVAILABLE", changedId,
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(errors), false, false);
    }

    private static String endpoint(DirectedProcessNetwork.Node node) {
        if (node == null) return "UNRESOLVED";
        return blank(node.equipmentNumber()) ? "EXTERNAL:" + node.nodeId() : node.equipmentNumber();
    }

    private static boolean sameStream(Stream stream, DirectedProcessNetwork.ProcessTerm term) {
        return stream.materialClass() == term.materialClass() && stream.materialType().equals(term.materialType())
                && stream.unit().equals(term.unit()) && stream.period().equals(normalizePeriod(term.period()));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalizePeriod(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }

    private record Stream(DirectedProcessNetwork.MaterialClass materialClass, String materialType, String unit, String period) {
        static Stream of(DirectedProcessNetwork.Edge edge) {
            return new Stream(edge.materialClass(), edge.materialType().trim(), edge.unit().trim(), normalizePeriod(edge.period()));
        }
        static Stream of(DirectedProcessNetwork.ProcessTerm term) {
            return new Stream(term.materialClass(), term.materialType().trim(), term.unit().trim(), normalizePeriod(term.period()));
        }
        String id() { return materialClass + ":" + materialType + ":" + unit + ":" + period; }
    }
    private record Equation(String processCode, String nodeId) { }
    private record StreamSolution(String status, Map<String, BigDecimal> quantities, List<String> issues) { }
    private record LinearSolution(int rank, boolean inconsistent, BigDecimal[] values) { }

    public record EdgeResult(String connectionId, String sourceEquipment, String targetEquipment,
                             String sourceProcess, String targetProcess,
                             DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                             String unit, BigDecimal quantity, BigDecimal previousQuantity,
                             DirectedProcessNetwork.QuantityBasis quantityBasis, boolean userSupplied, boolean fixed,
                             DirectedProcessNetwork.ConnectionType connectionType, String status,
                             String constraintViolation, String reviewStatus, String evidence, String period) { }
    public record NodeBalance(String equipmentNumber, String processCode, String label,
                              String sourceDocument, String sourceSheet, String reviewStatus,
                              DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                              String unit, BigDecimal inflow, BigDecimal outflow, BigDecimal generation,
                              BigDecimal residual, String status, String period) { }
    public record ProcessBalance(String processCode, DirectedProcessNetwork.MaterialClass materialClass,
                                 String materialType, String unit, BigDecimal inflow, BigDecimal outflow,
                                 BigDecimal generation, BigDecimal residual, String status, String period) { }
    public record ExternalBalance(DirectedProcessNetwork.MaterialClass materialClass, String materialType,
                                  String unit, BigDecimal externalInflow, BigDecimal externalOutflow,
                                  BigDecimal residual, String status, String period) { }
    public record Result(String scenarioId, String scenarioName, String mode, String status,
                         String changedConnectionId, BigDecimal previousQuantity, BigDecimal newQuantity,
                         List<EdgeResult> edges, List<NodeBalance> nodes, List<ProcessBalance> processes,
                         List<ExternalBalance> external, List<String> impactedEquipmentNumbers,
                         List<String> impactedProcessCodes, List<String> issues,
                         boolean officialDiagramModified, boolean officialMasterModified) { }
}
