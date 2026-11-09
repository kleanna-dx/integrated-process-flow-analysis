package com.example.processautomation.material;

import com.example.processautomation.excel.MaterialBalanceExcelExportService;
import com.example.processautomation.excel.MaterialBalanceExcelOutputModel;
import com.example.processautomation.excel.Pw0105DiagramTestOnlyOverlayService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MaterialBalanceExcelWorkflowService {
    private final MaterialBalanceService materialBalanceService;
    private final MaterialBalanceExcelExportService excelExportService;
    private final Pw0105DiagramTestOnlyOverlayService diagramOverlayService;

    public MaterialBalanceExcelWorkflowService(MaterialBalanceService materialBalanceService,
                                               MaterialBalanceExcelExportService excelExportService,
                                               Pw0105DiagramTestOnlyOverlayService diagramOverlayService) {
        this.materialBalanceService = materialBalanceService;
        this.excelExportService = excelExportService;
        this.diagramOverlayService = diagramOverlayService;
    }

    public ExportResult exportTestOnly(MaterialBalanceRequest request, byte[] sourceWorkbook) {
        excelExportService.validateTestOnlySource(sourceWorkbook);
        Map<String, Object> calculation = materialBalanceService.calculate(request);
        MaterialBalanceExcelOutputModel outputModel =
                excelExportService.createOutputModel(request, calculation, sourceWorkbook);
        MaterialBalanceExcelExportService.ExportArtifact artifact =
                excelExportService.addResultsSheet(sourceWorkbook, outputModel);
        return new ExportResult(artifact.workbookBytes(), artifact.outputModel(), calculation, artifact.worksheetPart());
    }

    public ExportResult exportCpw15020DiagramTestOnly(MaterialBalanceRequest request, byte[] sourceWorkbook) {
        excelExportService.validateTestOnlySource(sourceWorkbook);
        validateCpw15020Scenario(request);
        Map<String, Object> calculation = materialBalanceService.calculate(request);
        MaterialBalanceExcelOutputModel outputModel =
                excelExportService.createOutputModel(request, calculation, sourceWorkbook);
        MaterialBalanceExcelExportService.ExportArtifact artifact =
                excelExportService.addDiagramTestOnlyResultsSheet(sourceWorkbook, outputModel);
        var overlay = diagramOverlayService.addCpw15020TestOnlyFlows(artifact.workbookBytes(), outputModel);
        return new ExportResult(overlay.workbookBytes(), artifact.outputModel(), calculation, artifact.worksheetPart(), overlay);
    }

    private void validateCpw15020Scenario(MaterialBalanceRequest request) {
        if (!"TEST_ONLY".equals(request.mode()) || request.nodes().size() != 5 || request.transfers().size() != 4) {
            throw new IllegalArgumentException("C-PW15020 diagram export accepts only the defined four-transfer TEST_ONLY scenario");
        }
        Map<String, MaterialBalanceRequest.Transfer> transfers = new java.util.LinkedHashMap<>();
        request.transfers().forEach(transfer -> transfers.put(transfer.transferId(), transfer));
        requireTestTransfer(transfers, "cpw15009-cpw15020-in", "C-PW15009", "C-PW15020", "DOWNSTREAM", "WASTEWATER", "100");
        requireTestTransfer(transfers, "cpw15020-waste-0_5", "C-PW15020", "UNASSIGNED_WASTE_OUTPUT", "WASTE_GENERATION", "WASTE", "0.5");
        requireTestTransfer(transfers, "cpw15020-unassigned-2_5", "C-PW15020", "UNASSIGNED_WASTEWATER_RECEIVER", "WASTEWATER_TRANSFER", "WASTEWATER", "2.5");
        requireTestTransfer(transfers, "cpw15020-cpw15010-out", "C-PW15020", "C-PW15010", "DOWNSTREAM", "WASTEWATER", "97");
        var nodes = request.nodes().stream().collect(java.util.stream.Collectors.toMap(
                MaterialBalanceRequest.Node::id, node -> node));
        if (!nodes.keySet().equals(java.util.Set.of("C-PW15009", "C-PW15020", "C-PW15010",
                "UNASSIGNED_WASTE_OUTPUT", "UNASSIGNED_WASTEWATER_RECEIVER"))
                || nodes.values().stream().anyMatch(node -> node.equipmentId() != null || node.processCode() != null)
                || !"UNASSIGNED_RECEIVER".equals(nodes.get("UNASSIGNED_WASTEWATER_RECEIVER").role())) {
            throw new IllegalArgumentException("C-PW15020 scenario must leave all non-diagram receivers unmapped");
        }
        for (String id : List.of("C-PW15009", "C-PW15020", "C-PW15010")) {
            if (nodes.get(id).equipmentId() != null) throw new IllegalArgumentException("No approved equipment mapping is accepted for " + id);
        }
    }

    private void requireTestTransfer(Map<String, MaterialBalanceRequest.Transfer> transfers, String id,
                                     String source, String target, String edge, String flow, String amount) {
        var transfer = transfers.get(id);
        if (transfer == null || !"TEST_ONLY".equalsIgnoreCase(transfer.relationMode())
                || transfer.relationId() != null || !source.equals(transfer.sourceId()) || !target.equals(transfer.targetId())
                || !edge.equalsIgnoreCase(transfer.edgeType()) || !flow.equalsIgnoreCase(transfer.flowType())
                || transfer.quantity() == null || transfer.quantity().compareTo(new java.math.BigDecimal(amount)) != 0
                || transfer.receiverQuantity() != null
                || !"ton/day".equalsIgnoreCase(transfer.unit()) || transfer.evidence() == null || transfer.evidence().isBlank()) {
            throw new IllegalArgumentException("Invalid TEST_ONLY scenario transfer: " + id);
        }
    }

    public record ExportResult(byte[] workbookBytes, MaterialBalanceExcelOutputModel outputModel,
                               Map<String, Object> calculation, String worksheetPart,
                               com.example.processautomation.excel.Pw0105DiagramTestOnlyOverlayService.OverlayResult diagramOverlay) {
        public ExportResult(byte[] workbookBytes, MaterialBalanceExcelOutputModel outputModel,
                           Map<String, Object> calculation, String worksheetPart) {
            this(workbookBytes, outputModel, calculation, worksheetPart, null);
        }
    }
}
