package com.example.processautomation.material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EquipmentInsertionPlanningService {
    public Map<String, Object> preview(EquipmentInsertionProposal proposal) {
        List<String> missing = new ArrayList<>();
        if (proposal.targetSheet() == null || !proposal.targetSheet().equals("PW-01-05")) {
            missing.add("targetSheet=PW-01-05 확인");
        }
        if (blank(proposal.placementRange())) missing.add("설비 박스 삽입 셀 범위(placementRange)");
        if (blank(proposal.predecessorEquipment())) missing.add("전단 설비(predecessorEquipment)");
        if (blank(proposal.successorEquipment())) missing.add("후단 설비(successorEquipment)");
        if (!proposal.reviewerConfirmed()) missing.add("담당자 확인(reviewerConfirmed=true)");
        if (proposal.reviewerConfirmed() && blank(proposal.reviewerName())) missing.add("담당자 이름(reviewerName)");

        Map<String, Object> equipment = new LinkedHashMap<>();
        equipment.put("managementNo", proposal.managementNo());
        equipment.put("itemNo", proposal.itemNo());
        equipment.put("facilityName", proposal.facilityName());
        equipment.put("capacity", proposal.capacity());
        equipment.put("quantity", proposal.quantity());
        equipment.put("sheet", proposal.targetSheet() == null ? "PW-01-05" : proposal.targetSheet());
        equipment.put("placementRange", proposal.placementRange());
        equipment.put("predecessorEquipment", proposal.predecessorEquipment());
        equipment.put("successorEquipment", proposal.successorEquipment());
        equipment.put("reviewerName", proposal.reviewerName());
        return Map.of("status", missing.isEmpty() ? "READY_FOR_RENDER_REVIEW" : "REVIEW_REQUIRED",
                "reviewOnly", true, "officialBaselineModified", false,
                "xlsxGenerated", false, "equipment", equipment,
                "requiredInputs", missing,
                "notes", List.of("마스터 행 순서는 위치 또는 연결 관계를 확정하는 근거로 사용하지 않음",
                        "위치·전후 설비·담당자 확인이 완결되기 전에는 원본 XLSX를 수정하지 않음",
                        "이 API는 제안 검토만 하며 관계를 CONFIRMED로 등록하거나 공정도 파일을 생성하지 않음"));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
