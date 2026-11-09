package com.example.processautomation.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectiveExecutionPlanGroupingTest {
    @Test
    void groupsDifferentChangedItemsIntoOneWorkbookTargetAndRetainsEverySheet() {
        var grouped = SelectiveExecutionPlanGrouping.byWorkbook(List.of(
                new SelectiveExecutionPlanGrouping.PlannedTarget(77, 173, "C-PW15020", 5, 12,
                        "PW-01-05", "PW-01 폐수처리공정.xlsx", "a".repeat(64), "PW-01-05"),
                new SelectiveExecutionPlanGrouping.PlannedTarget(77, 174, "C-PW15021", 6, 12,
                        "PW-01-06", "PW-01 폐수처리공정.xlsx", "a".repeat(64), "PW-01-06"),
                new SelectiveExecutionPlanGrouping.PlannedTarget(77, 175, "TEST-P-001", 7, 1,
                        "P-01", "P-01 제지.xlsx", "b".repeat(64), "P-01-01(TOP)")));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get("PW-01 폐수처리공정.xlsx")).hasSize(2)
                .extracting(SelectiveExecutionPlanGrouping.PlannedTarget::changeItemId)
                .containsExactly(173L, 174L);
        assertThat(grouped.get("PW-01 폐수처리공정.xlsx"))
                .extracting(SelectiveExecutionPlanGrouping.PlannedTarget::sheetName)
                .containsExactly("PW-01-05", "PW-01-06");
        assertThat(grouped.get("P-01 제지.xlsx")).hasSize(1)
                .extracting(SelectiveExecutionPlanGrouping.PlannedTarget::sheetName)
                .containsExactly("P-01-01(TOP)");
        assertThatThrownBy(() -> SelectiveExecutionPlanGrouping.byWorkbook(List.of(
                new SelectiveExecutionPlanGrouping.PlannedTarget(77, 173, "C-PW15020", 5, 12,
                        "PW-01-05", "PW-01 폐수처리공정.xlsx", "a".repeat(64), "PW-01-05"),
                new SelectiveExecutionPlanGrouping.PlannedTarget(78, 174, "C-PW15021", 6, 12,
                        "PW-01-06", "PW-01 폐수처리공정.xlsx", "a".repeat(64), "PW-01-06"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot mix changeRunId");
    }
}
