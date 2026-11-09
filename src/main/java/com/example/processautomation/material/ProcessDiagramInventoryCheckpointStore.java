package com.example.processautomation.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Atomic local checkpoint storage for the read-only V1.0 inventory job. */
@Component
public class ProcessDiagramInventoryCheckpointStore {
    private static final String CHECKPOINT_FILE = "locked-v1.0-process-inventory.json";
    private static final String VALIDATION_FILE = "locked-v1.0-process-inventory-validations.json";
    private final ObjectMapper mapper;
    private final Path checkpointPath;
    private final Path validationPath;

    public ProcessDiagramInventoryCheckpointStore(
            ObjectMapper mapper,
            @Value("${process-diagram-inventory.checkpoint-directory:validation-results/process-diagram-inventory}")
            String checkpointDirectory) {
        this.mapper = mapper;
        Path directory = Path.of(checkpointDirectory).toAbsolutePath().normalize();
        this.checkpointPath = directory.resolve(CHECKPOINT_FILE);
        this.validationPath = directory.resolve(VALIDATION_FILE);
    }

    public synchronized boolean exists() {
        return Files.exists(checkpointPath);
    }

    public synchronized Snapshot load(String sourceFingerprint) {
        Snapshot snapshot = loadLatest();
        if (snapshot.sourceFingerprint() != null && !sourceFingerprint.equals(snapshot.sourceFingerprint())) {
            throw new IllegalStateException("Stored inventory checkpoint belongs to a different V1.0 source snapshot");
        }
        return snapshot.sourceFingerprint() == null ? Snapshot.empty(sourceFingerprint) : snapshot;
    }

    /** Reads the latest saved checkpoint without treating it as current; callers must compare fingerprints. */
    public synchronized Snapshot loadLatest() {
        if (!Files.exists(checkpointPath)) return Snapshot.empty(null);
        try {
            return mapper.readValue(checkpointPath.toFile(), Snapshot.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read process-diagram inventory checkpoint", exception);
        }
    }

    public synchronized void save(Snapshot snapshot) {
        writeAtomic(checkpointPath, snapshot);
    }

    public synchronized ValidationLedger registerValidation(String sourceFingerprint, EditabilityValidation validation) {
        if (sourceFingerprint == null || sourceFingerprint.isBlank()) throw new IllegalArgumentException("A source fingerprint is required");
        if (validation == null || validation.validationId() == null || validation.validationId().isBlank()) {
            throw new IllegalArgumentException("A stable validation ID is required");
        }
        ValidationLedger current = loadValidations(sourceFingerprint);
        List<EditabilityValidation> validations = new java.util.ArrayList<>(current.validations());
        validations.removeIf(item -> validation.validationId().equals(item.validationId()));
        validations.add(validation);
        ValidationLedger updated = new ValidationLedger(sourceFingerprint, Instant.now(), validations);
        saveValidations(updated);
        return updated;
    }

    public synchronized ValidationLedger loadValidations(String sourceFingerprint) {
        if (!Files.exists(validationPath)) return ValidationLedger.empty(sourceFingerprint);
        try {
            ValidationLedger ledger = mapper.readValue(validationPath.toFile(), ValidationLedger.class);
            if (!sourceFingerprint.equals(ledger.sourceFingerprint())) {
                throw new IllegalStateException("Stored editability validations belong to a different V1.0 source snapshot");
            }
            return ledger;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read process-diagram editability validation checkpoint", exception);
        }
    }

    private synchronized void saveValidations(ValidationLedger ledger) {
        writeAtomic(validationPath, ledger);
    }

    private void writeAtomic(Path destination, Object value) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), "validation-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), value);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist process-diagram validation checkpoint", exception);
        }
    }

    public record Snapshot(String sourceFingerprint, Instant updatedAt,
                           List<ProcessDiagramInventoryService.SheetCheckpoint> checkpoints) {
        public Snapshot {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }

        public static Snapshot empty(String sourceFingerprint) {
            return new Snapshot(sourceFingerprint, Instant.now(), List.of());
        }
    }

    public record ValidationLedger(String sourceFingerprint, Instant updatedAt, List<EditabilityValidation> validations) {
        public ValidationLedger {
            validations = validations == null ? List.of() : List.copyOf(validations);
        }

        public static ValidationLedger empty(String sourceFingerprint) {
            return new ValidationLedger(sourceFingerprint, Instant.now(), List.of());
        }
    }

    /** A generated workbook verifies a layout edit only; it does not attest to a physical connection. */
    public record EditabilityValidation(String validationId, Instant validatedAt, String processCode, String sheetName,
                                        long sourceDocumentId, long workingDocumentId, String officialSourceSha256,
                                        String workingCopySha256, String locationId, String sourceEquipmentId,
                                        String targetEquipmentId, String managementNo, String artifactPath,
                                        String artifactSha256, String structuralEditStatus,
                                        String physicalConnectionStatus, String method) { }
}
