package com.example.processautomation.material;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Creates detached TEST_ONLY review workbooks; never edits an official master or process diagram. */
@Component
public class DirectedProcessNetworkWorkbookExporter {
    public byte[] createResultWorkbook(DirectedProcessNetworkSolver.Result result) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            Sheet summary = workbook.createSheet("시나리오 요약");
            writeRows(summary, header, List.of(
                    row("항목", "결과", "검토 메모"),
                    row("시나리오 ID", result.scenarioId(), "합성 TEST_ONLY"),
                    row("시나리오", result.scenarioName(), "실제 운전값이 아님"),
                    row("모드", result.mode(), "공식 기준자료·도면 미수정"),
                    row("계산 상태", result.status(), "미정/음수/불일치 상태를 보정하지 않음"),
                    row("변경 연결 ID", result.changedConnectionId(), ""),
                    row("변경 전 유량", result.previousQuantity(), ""),
                    row("변경 후 유량", result.newQuantity(), ""),
                    row("영향 설비 관리번호", String.join(", ", result.impactedEquipmentNumbers()), "연결망 양방향 영향 범위"),
                    row("공식 도면 수정", result.officialDiagramModified(), "false 유지"),
                    row("공식 마스터 수정", result.officialMasterModified(), "false 유지")));
            size(summary, 52, 62, 72);

            Sheet connections = workbook.createSheet("연결 유량");
            writeRows(connections, header, rows(
                    new String[]{"연결 ID", "출발 설비/경계", "도착 설비/경계", "출발 공정", "도착 공정", "물질 구분", "물질 종류", "유량", "단위", "연결 유형", "입력 근거", "계산 상태", "제약 위반", "검토 상태", "근거", "사용자 입력", "고정값", "측정 기간"},
                    result.edges().stream().map(edge -> new Object[]{edge.connectionId(), edge.sourceEquipment(), edge.targetEquipment(),
                            edge.sourceProcess(), edge.targetProcess(), edge.materialClass(), edge.materialType(), edge.quantity(),
                            edge.unit(), edge.connectionType(), edge.quantityBasis(), edge.status(), edge.constraintViolation(),
                            edge.reviewStatus(), edge.evidence(), edge.userSupplied(), edge.fixed(), edge.period()}).toList()));
            size(connections, 24, 22, 22, 16, 16, 18, 22, 14, 14, 22, 16, 30, 36, 18, 54, 18, 14, 28);

            Sheet equipment = workbook.createSheet("설비별 수지");
            writeRows(equipment, header, rows(
                    new String[]{"설비 관리번호", "공정", "설비명", "물질 구분", "물질 종류", "유입량", "유출량", "발생/소모", "단위", "잔차(유입+발생-유출)", "상태", "측정 기간"},
                    result.nodes().stream().map(node -> new Object[]{node.equipmentNumber(), node.processCode(), node.label(),
                            node.materialClass(), node.materialType(), node.inflow(), node.outflow(), node.generation(), node.unit(),
                            node.residual(), node.status(), node.period()}).toList()));
            size(equipment, 22, 16, 28, 18, 22, 16, 16, 16, 14, 26, 36, 28);

            Sheet processes = workbook.createSheet("공정별 수지");
            writeRows(processes, header, rows(
                    new String[]{"공정", "물질 구분", "물질 종류", "단위", "공정 유입", "공정 유출", "발생/소모 순량", "잔차", "상태", "측정 기간"},
                    result.processes().stream().map(process -> new Object[]{process.processCode(), process.materialClass(),
                            process.materialType(), process.unit(), process.inflow(), process.outflow(), process.generation(),
                            process.residual(), process.status(), process.period()}).toList()));
            size(processes, 16, 18, 22, 14, 16, 16, 20, 18, 36, 28);

            Sheet external = workbook.createSheet("전체 외부 수지");
            writeRows(external, header, rows(
                    new String[]{"물질 구분", "물질 종류", "단위", "외부 유입 합계", "외부 유출 합계", "잔차", "상태", "측정 기간"},
                    result.external().stream().map(balance -> new Object[]{balance.materialClass(), balance.materialType(),
                            balance.unit(), balance.externalInflow(), balance.externalOutflow(), balance.residual(), balance.status(), balance.period()}).toList()));
            size(external, 18, 24, 14, 22, 22, 18, 36, 28);

            Sheet issues = workbook.createSheet("검토 및 제약");
            writeRows(issues, header, List.of(row("검토 항목", "상태"),
                    row("모드", "TEST_ONLY — 합성 데이터 전용"),
                    row("계산 제한", "단위 환산 없음; 같은 물질 종류·단위끼리만 계산"),
                    row("미정 변수", "추가 조건 없이는 임의 배분하지 않음"),
                    row("음수 유량", "계산 오류/제약 위반으로 표시하며 자동 보정하지 않음"),
                    row("공식 도면/마스터", "수정하지 않음")));
            int rowIndex = issues.getLastRowNum() + 1;
            for (String issue : result.issues()) text(issues, rowIndex++, 0, issue);
            size(issues, 54, 72);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create TEST_ONLY network result workbook", exception);
        }
    }

    public byte[] createWhatIfWorkbook(NetworkWhatIfService.WhatIfReport report) {
        java.util.ArrayList<DirectedProcessNetworkSolver.Result> results = new java.util.ArrayList<>();
        results.add(report.baseline());
        report.scenarios().forEach(scenario -> results.add(scenario.result()));
        return createScenarioMatrixWorkbook(results);
    }

    public byte[] createScenarioMatrixWorkbook(List<DirectedProcessNetworkSolver.Result> results) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            Sheet scenarios = workbook.createSheet("시나리오 비교");
            List<Object[]> scenarioRows = results.stream().map(result -> new Object[]{result.scenarioId(),
                    result.scenarioName(), result.status(), result.changedConnectionId(), result.previousQuantity(),
                    result.newQuantity(), String.join(", ", result.impactedEquipmentNumbers()),
                    String.join(", ", result.impactedProcessCodes()), externalSummary(result),
                    String.join("; ", result.issues())}).toList();
            writeRows(scenarios, header, rows(new String[]{"시나리오 ID", "설명", "계산 상태", "변경 연결 ID",
                    "변경 전", "변경 후", "영향 설비", "영향 공정", "외부 유입/유출/잔차", "검토/제약"}, scenarioRows));
            size(scenarios, 22, 34, 32, 24, 16, 16, 32, 24, 32, 58);

            Sheet processes = workbook.createSheet("공정별 수지");
            List<Object[]> processRows = results.stream().flatMap(result -> result.processes().stream()
                    .map(balance -> new Object[]{result.scenarioId(), balance.processCode(), balance.materialClass(),
                            balance.materialType(), balance.unit(), balance.inflow(), balance.outflow(), balance.generation(),
                            balance.residual(), balance.status(), balance.period()})).toList();
            writeRows(processes, header, rows(new String[]{"시나리오 ID", "공정", "물질 구분", "물질 종류", "단위",
                    "유입", "유출", "발생/소모", "잔차", "상태", "측정 기간"}, processRows));
            size(processes, 22, 16, 18, 23, 14, 16, 16, 18, 18, 34, 28);

            Sheet edges = workbook.createSheet("연결 유량");
            List<Object[]> edgeRows = results.stream().flatMap(result -> result.edges().stream()
                    .map(edge -> new Object[]{result.scenarioId(), edge.connectionId(), edge.sourceEquipment(), edge.targetEquipment(),
                            edge.sourceProcess(), edge.targetProcess(), edge.quantity(), edge.previousQuantity(), edge.unit(),
                            edge.connectionType(), edge.status(), edge.constraintViolation(), edge.quantityBasis(), edge.fixed(), edge.userSupplied(), edge.period()})).toList();
            writeRows(edges, header, rows(new String[]{"시나리오 ID", "연결 ID", "출발 설비", "도착 설비", "출발 공정",
                    "도착 공정", "계산 유량", "변경 전 유량", "단위", "연결 유형", "상태", "제약 위반", "유량 근거", "고정값", "사용자 입력", "측정 기간"}, edgeRows));
            size(edges, 22, 24, 22, 22, 16, 16, 16, 16, 14, 24, 34, 40, 20, 14, 18, 28);

            Sheet equipment = workbook.createSheet("설비별 수지");
            List<Object[]> nodeRows = results.stream().flatMap(result -> result.nodes().stream()
                    .map(node -> new Object[]{result.scenarioId(), node.equipmentNumber(), node.processCode(), node.label(),
                            node.sourceDocument(), node.sourceSheet(), node.reviewStatus(), node.materialClass(),
                            node.materialType(), node.inflow(), node.outflow(), node.generation(), node.unit(), node.residual(), node.status(), node.period()})).toList();
            writeRows(equipment, header, rows(new String[]{"시나리오 ID", "설비 관리번호", "공정", "설비", "원본 도면",
                    "원본 시트", "검토 상태", "물질 구분", "물질 종류", "유입", "유출", "발생/소모", "단위", "잔차", "상태", "측정 기간"}, nodeRows));
            size(equipment, 22, 23, 16, 26, 30, 20, 18, 18, 22, 16, 16, 18, 14, 18, 34, 28);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create TEST_ONLY scenario matrix workbook", exception);
        }
    }

    private String externalSummary(DirectedProcessNetworkSolver.Result result) {
        return result.external().stream().map(balance -> balance.materialType() + " " + balance.unit() + " " + balance.period() + ": "
                + balance.externalInflow() + "/" + balance.externalOutflow() + "/" + balance.residual()
                + " [" + balance.status() + "]").collect(java.util.stream.Collectors.joining("; "));
    }

    public byte[] createProposalComparisonWorkbook(String proposalId, String changeType, String processCode,
                                                   String sheetName, String managementNo,
                                                   Map<String, Object> beforeValues, Map<String, Object> afterValues,
                                                   Map<String, Object> evidence, String calculationStatus,
                                                   String reviewStatus, String connectionEvidenceStatus,
                                                   NetworkWhatIfService.WhatIfReport before,
                                                   NetworkWhatIfService.WhatIfReport after,
                                                   Map<String, Object> comparison) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = comparisonHeaderStyle(workbook);
            CellStyle body = comparisonBodyStyle(workbook);
            Sheet summary = workbook.createSheet("제안 요약");
            List<List<Object>> summaryRows = new java.util.ArrayList<>();
            summaryRows.add(row("항목", "내용", "검토 상태"));
            summaryRows.add(row("산출 유형", "TEST_ONLY / SYNTHETIC_TEST_ONLY", "실제 사업장 공정 결과가 아님"));
            summaryRows.add(row("변경 제안 ID", proposalId, "Append-only validation event 기반"));
            summaryRows.add(row("변경 유형", changeType, ""));
            summaryRows.add(row("공정 / 시트", processCode + " / " + sheetName, ""));
            summaryRows.add(row("설비 관리번호", managementNo, ""));
            summaryRows.add(row("계산 상태", calculationStatus, "시나리오 계산 요약"));
            summaryRows.add(row("검토 상태", reviewStatus, ""));
            summaryRows.add(row("물리 연결 증빙", connectionEvidenceStatus, "증빙 없는 실제 연결은 미확정"));
            summaryRows.add(row("실제 계산 readiness", "CALCULATION_UNAVAILABLE", "합성 계산은 실제 수지를 대체하지 않음"));
            summaryRows.add(row("변경 전 네트워크", before.simulationId() + " / " + before.baseline().status(), before.scenarioName()));
            summaryRows.add(row("변경 후 네트워크", after.simulationId() + " / " + after.baseline().status(), after.scenarioName()));
            summaryRows.add(row("변경 전 설비 수", before.baseline().nodes().size(), "baseline result"));
            summaryRows.add(row("변경 후 설비 수", after.baseline().nodes().size(), "baseline result"));
            summaryRows.add(row("증빙 객체", compactValue(evidence), "입력 증빙은 자동 승인되지 않음"));
            summaryRows.add(row("변경 전 snapshot", compactValue(beforeValues), "제안 입력"));
            summaryRows.add(row("변경 후 snapshot", compactValue(afterValues), "제안 입력"));
            writeComparisonTable(summary, summaryRows, header, body, 26, 54, 42);

            Sheet connections = workbook.createSheet("연결망 전후");
            List<Object[]> connectionData = new java.util.ArrayList<>();
            for (NetworkWhatIfService.ScenarioResult entry : scenariosWithBaseline(before)) {
                appendConnectionRows(connectionData, "변경 전", entry.scenarioId(), entry.result());
            }
            for (NetworkWhatIfService.ScenarioResult entry : scenariosWithBaseline(after)) {
                appendConnectionRows(connectionData, "변경 후", entry.scenarioId(), entry.result());
            }
            writeComparisonTable(connections, rows(new String[]{"구분", "시나리오", "연결 ID", "출발 공정", "출발 설비/경계",
                    "도착 공정", "도착 설비/경계", "물질 분류", "물질 종류", "연결 유형", "유량", "단위", "기간",
                    "계산 상태", "제약", "유량 근거", "고정", "증빙"}, connectionData), header, body,
                    14, 24, 24, 16, 24, 16, 24, 18, 22, 22, 16, 14, 28, 30, 34, 18, 12, 48);

            Sheet changed = workbook.createSheet("변경 연결");
            List<Object[]> changes = compareConnections(before.baseline().edges(), after.baseline().edges());
            writeComparisonTable(changed, rows(new String[]{"비교 상태", "연결 ID", "변경 전 출발", "변경 전 도착", "변경 후 출발",
                    "변경 후 도착", "변경 전 유량", "변경 후 유량", "변경 전 단위", "변경 후 단위", "변경 전 유형", "변경 후 유형",
                    "변경 전 물질", "변경 후 물질", "변경 전 기간", "변경 후 기간"}, changes),
                    header, body, 20, 24, 26, 26, 26, 26, 18, 18, 14, 14, 22, 22, 24, 24, 28, 28);

            Sheet processBalances = workbook.createSheet("공정 수지");
            List<Object[]> processRows = new java.util.ArrayList<>();
            appendProcessRows(processRows, "변경 전", before);
            appendProcessRows(processRows, "변경 후", after);
            writeComparisonTable(processBalances, rows(new String[]{"구분", "시나리오", "공정", "물질 분류", "물질 종류", "단위",
                    "유입", "유출", "발생/소모", "잔차", "상태", "측정 기간"}, processRows), header, body,
                    14, 24, 16, 18, 24, 14, 16, 16, 18, 18, 32, 28);

            Sheet residuals = workbook.createSheet("잔차 비교");
            List<Object[]> residualRows = mapRows(comparison.get("processResidualDeltas")).stream()
                    .map(row -> new Object[]{row.get("processCode"), row.get("materialType"), row.get("unit"), row.get("period"),
                            row.get("beforeResidual"), row.get("afterResidual"), row.get("delta"), row.get("status")}).toList();
            writeComparisonTable(residuals, rows(new String[]{"공정", "물질 종류", "단위", "측정 기간", "변경 전 잔차", "변경 후 잔차", "잔차 delta", "비교 상태"}, residualRows),
                    header, body, 16, 24, 14, 28, 18, 18, 18, 28);

            Sheet external = workbook.createSheet("전체 외부 수지");
            List<Object[]> externalRows = new java.util.ArrayList<>();
            for (NetworkWhatIfService.ScenarioResult entry : scenariosWithBaseline(before)) {
                appendExternalRows(externalRows, "변경 전", entry.scenarioId(), entry.result());
            }
            for (NetworkWhatIfService.ScenarioResult entry : scenariosWithBaseline(after)) {
                appendExternalRows(externalRows, "변경 후", entry.scenarioId(), entry.result());
            }
            writeComparisonTable(external, rows(new String[]{"구분", "시나리오", "물질 분류", "물질 종류", "단위", "외부 유입 합계",
                    "외부 유출 합계", "잔차", "상태", "기간"}, externalRows), header, body,
                    14, 24, 18, 24, 14, 22, 22, 18, 30, 28);

            Sheet externalComparison = workbook.createSheet("외부 수지 비교");
            List<Object[]> externalDeltaRows = mapRows(comparison.get("externalBalanceDeltas")).stream()
                    .map(row -> new Object[]{row.get("status"), row.get("materialClass"), row.get("materialType"), row.get("unit"),
                            row.get("period"), row.get("externalInflowBefore"), row.get("externalInflowAfter"),
                            row.get("externalOutflowBefore"), row.get("externalOutflowAfter"), row.get("residualBefore"),
                            row.get("residualAfter")}).toList();
            writeComparisonTable(externalComparison, rows(new String[]{"비교 상태", "물질 분류", "물질 종류", "단위", "기간",
                    "변경 전 외부 유입", "변경 후 외부 유입", "변경 전 외부 유출", "변경 후 외부 유출", "변경 전 잔차", "변경 후 잔차"}, externalDeltaRows),
                    header, body, 32, 18, 24, 14, 28, 22, 22, 22, 22, 18, 18);

            Sheet impact = workbook.createSheet("영향·검토");
            List<Object[]> impactRows = new java.util.ArrayList<>();
            appendImpactRows(impactRows, "변경 전", before);
            appendImpactRows(impactRows, "변경 후", after);
            impactRows.add(new Object[]{"검토 및 제약", "상태", "상세"});
            impactRows.add(new Object[]{"합성 데이터", "TEST_ONLY", "입력 네트워크는 물리 연결 증빙이 아님"});
            impactRows.add(new Object[]{"실제 공정 readiness", "CALCULATION_UNAVAILABLE", "실제 연결 검증이 완료되지 않음"});
            impactRows.add(new Object[]{"연결 증빙", connectionEvidenceStatus, compactValue(evidence)});
            appendIssues(impactRows, "변경 전", before);
            appendIssues(impactRows, "변경 후", after);
            writeComparisonTable(impact, rows(new String[]{"구분", "항목/상태", "상세"}, impactRows), header, body,
                    18, 36, 84);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create TEST_ONLY proposal comparison workbook", exception);
        }
    }

    private List<NetworkWhatIfService.ScenarioResult> scenariosWithBaseline(NetworkWhatIfService.WhatIfReport report) {
        java.util.ArrayList<NetworkWhatIfService.ScenarioResult> results = new java.util.ArrayList<>();
        results.add(new NetworkWhatIfService.ScenarioResult("BASELINE", "변경 전 기준", null, null, report.baseline()));
        results.addAll(report.scenarios());
        return results;
    }

    private void appendConnectionRows(List<Object[]> rows, String phase, String scenario,
                                      DirectedProcessNetworkSolver.Result result) {
        result.edges().forEach(edge -> rows.add(new Object[]{phase, scenario, edge.connectionId(), edge.sourceProcess(),
                edge.sourceEquipment(), edge.targetProcess(), edge.targetEquipment(), edge.materialClass(), edge.materialType(),
                edge.connectionType(), edge.quantity(), edge.unit(), edge.period(), edge.status(), edge.constraintViolation(),
                edge.quantityBasis(), edge.fixed(), edge.evidence()}));
    }

    private List<Object[]> compareConnections(List<DirectedProcessNetworkSolver.EdgeResult> before,
                                              List<DirectedProcessNetworkSolver.EdgeResult> after) {
        Map<String, DirectedProcessNetworkSolver.EdgeResult> beforeById = before.stream()
                .collect(java.util.stream.Collectors.toMap(DirectedProcessNetworkSolver.EdgeResult::connectionId, edge -> edge, (a, b) -> a));
        Map<String, DirectedProcessNetworkSolver.EdgeResult> afterById = after.stream()
                .collect(java.util.stream.Collectors.toMap(DirectedProcessNetworkSolver.EdgeResult::connectionId, edge -> edge, (a, b) -> a));
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(beforeById.keySet());
        ids.addAll(afterById.keySet());
        List<Object[]> rows = new java.util.ArrayList<>();
        for (String id : ids) {
            var old = beforeById.get(id); var current = afterById.get(id);
            String status = old == null ? "ADDED" : current == null ? "DELETED" : connectionChanged(old, current);
            if ("UNCHANGED".equals(status)) continue;
            rows.add(new Object[]{status, id, old == null ? null : endpoint(old.sourceProcess(), old.sourceEquipment()),
                    old == null ? null : endpoint(old.targetProcess(), old.targetEquipment()),
                    current == null ? null : endpoint(current.sourceProcess(), current.sourceEquipment()),
                    current == null ? null : endpoint(current.targetProcess(), current.targetEquipment()),
                    old == null ? null : old.quantity(), current == null ? null : current.quantity(),
                    old == null ? null : old.unit(), current == null ? null : current.unit(),
                    old == null ? null : old.connectionType(), current == null ? null : current.connectionType(),
                    old == null ? null : old.materialClass() + " / " + old.materialType(),
                    current == null ? null : current.materialClass() + " / " + current.materialType(),
                    old == null ? null : old.period(), current == null ? null : current.period()});
        }
        return rows;
    }

    private String connectionChanged(DirectedProcessNetworkSolver.EdgeResult before,
                                     DirectedProcessNetworkSolver.EdgeResult after) {
        if (!java.util.Objects.equals(before.sourceEquipment(), after.sourceEquipment())
                || !java.util.Objects.equals(before.targetEquipment(), after.targetEquipment())
                || !java.util.Objects.equals(before.sourceProcess(), after.sourceProcess())
                || !java.util.Objects.equals(before.targetProcess(), after.targetProcess())
                || before.connectionType() != after.connectionType()) return "PIPE_OR_TOPOLOGY_CHANGED";
        if (before.materialClass() != after.materialClass()
                || !java.util.Objects.equals(before.materialType(), after.materialType())
                || !java.util.Objects.equals(before.period(), after.period())) return "MATERIAL_OR_PERIOD_CHANGED";
        if (!sameQuantity(before.quantity(), after.quantity()) || !java.util.Objects.equals(before.unit(), after.unit())) return "FLOW_CHANGED";
        return "UNCHANGED";
    }

    private boolean sameQuantity(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private String endpoint(String process, String equipment) {
        return (process == null || process.isBlank() ? "외부" : process) + " / "
                + (equipment == null || equipment.isBlank() ? "외부 경계" : equipment);
    }

    private void appendProcessRows(List<Object[]> rows, String phase, NetworkWhatIfService.WhatIfReport report) {
        for (var entry : scenariosWithBaseline(report)) {
            entry.result().processes().forEach(balance -> rows.add(new Object[]{phase, entry.scenarioId(), balance.processCode(),
                    balance.materialClass(), balance.materialType(), balance.unit(), balance.inflow(), balance.outflow(),
                    balance.generation(), balance.residual(), balance.status(), balance.period()}));
        }
    }

    private void appendExternalRows(List<Object[]> rows, String phase, String scenario,
                                    DirectedProcessNetworkSolver.Result result) {
        result.external().forEach(balance -> rows.add(new Object[]{phase, scenario, balance.materialClass(), balance.materialType(),
                balance.unit(), balance.externalInflow(), balance.externalOutflow(), balance.residual(), balance.status(), balance.period()}));
    }

    private void appendImpactRows(List<Object[]> rows, String phase, NetworkWhatIfService.WhatIfReport report) {
        report.scenarios().forEach(scenario -> {
            rows.add(new Object[]{phase + " / " + scenario.scenarioId(), "영향 공정", String.join(", ", scenario.result().impactedProcessCodes())});
            rows.add(new Object[]{phase + " / " + scenario.scenarioId(), "영향 설비", String.join(", ", scenario.result().impactedEquipmentNumbers())});
        });
    }

    private void appendIssues(List<Object[]> rows, String phase, NetworkWhatIfService.WhatIfReport report) {
        rows.add(new Object[]{phase, "baseline 계산 상태", report.baseline().status()});
        report.baseline().issues().forEach(issue -> rows.add(new Object[]{phase, "baseline issue", issue}));
        report.scenarios().forEach(scenario -> {
            rows.add(new Object[]{phase + " / " + scenario.scenarioId(), "시나리오 계산 상태", scenario.result().status()});
            scenario.result().issues().forEach(issue -> rows.add(new Object[]{phase + " / " + scenario.scenarioId(), "issue", issue}));
        });
    }

    private List<Map<String, Object>> mapRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private String compactValue(Object value) {
        if (value == null) return "—";
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("; "));
        return String.valueOf(value);
    }

    private CellStyle comparisonHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        var font = workbook.createFont();
        font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 10); font.setBold(true);
        font.setColor(IndexedColors.BLACK.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle comparisonBodyStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        var font = workbook.createFont();
        font.setFontName("맑은 고딕"); font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.BLACK.getIndex());
        style.setFont(font);
        return style;
    }

    private void writeComparisonTable(Sheet sheet, List<List<Object>> rows, CellStyle header, CellStyle body, int... widths) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                var cell = row.createCell(c);
                Object value = rows.get(r).get(c);
                if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
                else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else if (value != null) cell.setCellValue(value.toString());
                cell.setCellStyle(r == 0 ? header : body);
            }
            if (r == 0) row.setHeightInPoints(34);
        }
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size() - 1), 0,
                    rows.get(0).size() - 1));
        }
        size(sheet, widths);
        sheet.setFitToPage(true);
        sheet.setAutobreaks(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A3_PAPERSIZE);
        sheet.getPrintSetup().setFitWidth((short) 2);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setMargin(Sheet.LeftMargin, 0.3);
        sheet.setMargin(Sheet.RightMargin, 0.3);
        sheet.setMargin(Sheet.TopMargin, 0.4);
        sheet.setMargin(Sheet.BottomMargin, 0.4);
        sheet.setRepeatingRows(org.apache.poi.ss.util.CellRangeAddress.valueOf("1:1"));
        sheet.setRepeatingColumns(org.apache.poi.ss.util.CellRangeAddress.valueOf("A:C"));
    }

    public byte[] createInputTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            Sheet baseline = workbook.createSheet("기준 연결망");
            writeRows(baseline, header, List.of(row("기준 연결 ID", "출발 공정", "출발 설비 관리번호", "도착 공정",
                    "도착 설비 관리번호", "물질 구분", "물질 종류", "기준 유량", "단위", "연결 유형", "측정/계산",
                    "사용자 입력", "고정 유량", "연결 근거", "확정 여부", "검토 상태", "원본 도면", "원본 시트", "측정 기간")));
            size(baseline, 24, 16, 23, 16, 23, 18, 22, 16, 14, 24, 18, 16, 16, 50, 16, 20, 36, 24, 28);

            Sheet changes = workbook.createSheet("배관·유량변경");
            writeRows(changes, header, List.of(row("시나리오 ID", "변경 유형(추가/변경/삭제)", "연결 ID", "출발 공정",
                    "출발 설비 관리번호", "도착 공정", "도착 설비 관리번호", "변경 전 유량", "변경 후 유량", "단위",
                    "물질 구분", "물질 종류", "연결 유형", "변경 근거", "검토 상태", "연결 근거 상태", "측정 기간")));
            size(changes, 24, 24, 24, 16, 23, 16, 23, 18, 18, 14, 18, 22, 24, 50, 20, 24, 28);

            Sheet setup = workbook.createSheet("시뮬레이션 설정");
            writeRows(setup, header, List.of(row("시나리오 ID", "대상 연결 ID", "시험 유량", "단위", "외부 유입 고정조건",
                    "외부 유출 고정조건", "분기 배분 조건", "재순환 조건", "검토 결과", "검토자/근거", "측정 기간")));
            size(setup, 24, 24, 18, 14, 36, 36, 34, 34, 28, 50, 28);

            Sheet guide = workbook.createSheet("작성 안내");
            writeRows(guide, header, List.of(
                    row("항목", "작성 규칙"),
                    row("목적", "별도 TEST_ONLY 입력 템플릿 초안. 공식 전체목록 또는 공정도에 입력·복사하지 마십시오."),
                    row("설비 식별", "도면 행 번호·좌우 위치가 아니라 설비 관리번호와 검증된 연결 근거를 사용."),
                    row("미확정 연결", "CONFIRMED/확정으로 표시하지 말고 REVIEW_REQUIRED로 남기며 계산에 자동 포함하지 않음."),
                    row("유량 입력", "고정값은 수량과 단위를 입력. 계산값은 기준/근거 연결을 함께 지정. 미지수는 공란으로 두고 추가 조건을 제공."),
                    row("물질/단위", "폐수·약품·폐기물 및 종류를 분리. 단위가 다르면 환산 근거 없이는 합산 금지."),
                    row("외부 경계", "외부 유입·유출은 명시적으로 구분하고 유량 고정 조건을 시뮬레이션 설정에 기록."),
                    row("반복 시뮬레이션", "각 Scenario ID는 동일 기준 연결망에서 독립 실행. 이전 시나리오 계산값을 다음 기준값으로 재사용하지 않음."),
                    row("계산 정책", "불충분·복수해·불일치·음수 유량은 상태로 보고하고 자동 추정·보정하지 않음."),
                    row("상태", "초안; 실제 운영 입력/업로드 검증 및 사용자 승인 흐름은 구현 범위 밖.")));
            size(guide, 24, 100);
            workbook.setActiveSheet(3);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create detached network input template", exception);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setWrapText(true);
        return style;
    }

    private void writeRows(Sheet sheet, CellStyle header, List<List<Object>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                var cell = row.createCell(c);
                Object value = values.get(c);
                if (value instanceof BigDecimal decimal) cell.setCellValue(decimal.doubleValue());
                else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else if (value != null) cell.setCellValue(value.toString());
                if (r == 0) cell.setCellStyle(header);
            }
            if (r == 0) row.setHeightInPoints(32);
        }
        sheet.createFreezePane(0, 1);
        if (sheet.getRow(0) != null && sheet.getRow(0).getLastCellNum() > 0) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size() - 1),
                    0, sheet.getRow(0).getLastCellNum() - 1));
        }
    }

    private void size(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private List<List<Object>> rows(String[] headers, List<Object[]> values) {
        java.util.ArrayList<List<Object>> rows = new java.util.ArrayList<>();
        rows.add(new java.util.ArrayList<>(java.util.Arrays.asList(headers)));
        for (Object[] row : values) rows.add(java.util.Arrays.asList(row));
        return rows;
    }

    private List<Object> row(Object... values) { return java.util.Arrays.asList(values); }

    private void text(Sheet sheet, int rowIndex, int columnIndex, String value) {
        sheet.createRow(rowIndex).createCell(columnIndex).setCellValue(value);
    }
}
