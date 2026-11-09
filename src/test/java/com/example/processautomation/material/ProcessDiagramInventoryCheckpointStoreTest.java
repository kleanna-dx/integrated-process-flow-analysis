package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessDiagramInventoryCheckpointStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsCompletedAndFailedSheetStatesAndRejectsDifferentSourceSnapshot() {
        var store = new ProcessDiagramInventoryCheckpointStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        var completed = new ProcessDiagramInventoryService.SheetCheckpoint(
                0, 12L, 0, "Sheet A", "COMPLETED", null, null);
        var failed = new ProcessDiagramInventoryService.SheetCheckpoint(
                0, 12L, 1, "Sheet B", "FAILED", null, "OOXML_PARSE_ERROR");
        var snapshot = new ProcessDiagramInventoryCheckpointStore.Snapshot("source-hash", Instant.now(),
                List.of(completed, failed));

        store.save(snapshot);

        var restored = store.load("source-hash");
        assertThat(restored.checkpoints()).containsExactly(completed, failed);
        assertThat(restored.updatedAt()).isEqualTo(snapshot.updatedAt());
        var validation = new ProcessDiagramInventoryCheckpointStore.EditabilityValidation(
                "P-02|P-02-01(TOP)|I-PP21039->I-PP21040", Instant.now(), "P-02", "P-02-01(TOP)",
                2L, 3L, "official-sha", "working-sha", "P-02|P-02-01(TOP)|I-PP21039->I-PP21040",
                "I-PP21039", "I-PP21040", "T-P02-TEST", "validation-results/p02.xlsx", "artifact-sha",
                "VERIFIED_BOX_ONLY_TEST_ONLY", "NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE", "TEST_ONLY");
        var registered = store.registerValidation("source-hash", validation);
        assertThat(store.load("source-hash").checkpoints()).containsExactly(completed, failed);
        assertThat(registered.validations()).containsExactly(validation);
        assertThat(store.loadValidations("source-hash").validations()).containsExactly(validation);
        assertThatThrownBy(() -> store.loadValidations("different-source-hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different V1.0 source snapshot");
        assertThatThrownBy(() -> store.load("different-source-hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different V1.0 source snapshot");
    }
}
