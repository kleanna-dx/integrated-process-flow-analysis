package com.example.processautomation.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MasterFieldChangePolicyTest {
    @Test
    void preservesExistingWastewaterAndWasteInputsWhenUploadIsBlank() {
        var result = MasterFieldChangePolicy.compare(
                Map.of("폐수량 (톤/일)", "1.2", "폐기물종류", "폐합성수지"),
                Map.of("폐수량 (톤/일)", "", "폐기물종류", ""));

        assertThat(result.changedFields()).isEmpty();
        assertThat(result.preservedBlankFlowInputs()).containsKeys("폐수량 (톤/일)", "폐기물종류");
        assertThat(result.preservedBlankFlowInputs().values())
                .allSatisfy(detail -> assertThat(detail).contains("not treated as zero or deletion"));
    }

    @Test
    void detectsExplicitFlowChangeAndExplicitZero() {
        var result = MasterFieldChangePolicy.compare(
                Map.of("폐수량 (톤/일)", "1.2", "폐기물량 (톤/일)", "0.5"),
                Map.of("폐수량 (톤/일)", "0", "폐기물량 (톤/일)", "0.8"));

        assertThat(result.changedFields()).containsKeys("폐수량 (톤/일)", "폐기물량 (톤/일)");
        assertThat(result.changedFields().get("폐수량 (톤/일)").after()).isEqualTo("0");
        assertThat(result.preservedBlankFlowInputs()).isEmpty();
    }
}
