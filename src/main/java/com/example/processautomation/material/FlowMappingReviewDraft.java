package com.example.processautomation.material;

import java.util.List;

/** In-memory, review-only draft payload. It is not a physical connection approval. */
public final class FlowMappingReviewDraft {
    private FlowMappingReviewDraft() { }

    public enum ReviewStatus {
        NOT_REVIEWED,
        REVIEW_IN_PROGRESS,
        NEEDS_EVIDENCE,
        REVIEW_DRAFT,
        REVIEW_COMPLETED_PENDING_VALIDATION
    }

    public enum RowDisposition {
        VALID_DRAFT,
        REVIEW_REQUIRED,
        INVALID_ROW,
        DUPLICATE_REVIEW_ROW,
        SOURCE_SHA_MISMATCH,
        SOURCE_ROW_MISMATCH,
        ORIGINAL_EQUIPMENT_ID_CHANGED,
        MISSING_FROM_UPLOAD
    }

    public record DraftRow(
            String rowId,
            String sourceSheet,
            int sourceRow,
            String sourceSha256,
            String sourceEquipmentId,
            String targetEquipmentId,
            String processTarget,
            String sheetTarget,
            String processCandidates,
            String sheetCandidates,
            String candidateLocations,
            String selectedCandidateLocation,
            String originalFlowText,
            String reviewedFlowValue,
            String originalMaterialType,
            String reviewedMaterialType,
            String originalUnit,
            String reviewedUnit,
            String originalPeriod,
            String reviewedPeriod,
            String connectionId,
            String internalRecycle,
            String interProcessTransfer,
            String additionalEvidenceRequired,
            String evidenceReference,
            String reviewer,
            String reviewDate,
            String reviewerComment,
            String reviewStatus,
            RowDisposition disposition,
            List<String> issues) {
        public DraftRow {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record ImportPreview(
            String previewId,
            String sourceSha256,
            int uploadedRows,
            int validDraftRows,
            int reviewRequiredRows,
            int conflictRows,
            int missingRows,
            List<DraftRow> rows,
            boolean persisted,
            int verifiedFlowMappingCount,
            String physicalConnectionEvidence,
            String officialReadiness) {
        public ImportPreview {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record ImportResult(
            List<DraftRow> validDrafts,
            List<DraftRow> reviewRequired,
            List<DraftRow> conflicts,
            List<DraftRow> missing,
            int uploadedRows) {
        public ImportResult {
            validDrafts = List.copyOf(validDrafts);
            reviewRequired = List.copyOf(reviewRequired);
            conflicts = List.copyOf(conflicts);
            missing = List.copyOf(missing);
        }

        public List<DraftRow> allRows() {
            return java.util.stream.Stream.of(validDrafts, reviewRequired, conflicts, missing)
                    .flatMap(List::stream).toList();
        }
    }
}
