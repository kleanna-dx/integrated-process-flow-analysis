package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhaseTwoPlanningTest {
    private final EquipmentInsertionPlanningService planningService = new EquipmentInsertionPlanningService();

    @Test
    void sampleEquipmentWithoutLocationOrLinksIsReviewRequiredAndNeverGeneratesWorkbook() {
        var proposal = new EquipmentInsertionProposal("C-PW15020", "WW-F-AFT10", "SCREEN FILTER",
                "1,500㎥/시", "1ea", "PW-01-05", null, null, null, false, null);

        Map<String, Object> response = planningService.preview(proposal);

        assertThat(response).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("reviewOnly", true).containsEntry("xlsxGenerated", false)
                .containsEntry("officialBaselineModified", false);
        List<String> requiredInputs = ((List<?>) response.get("requiredInputs")).stream()
                .map(Object::toString).toList();
        assertThat(requiredInputs).contains("설비 박스 삽입 셀 범위(placementRange)",
                "전단 설비(predecessorEquipment)", "후단 설비(successorEquipment)",
                "담당자 확인(reviewerConfirmed=true)");
    }
}
