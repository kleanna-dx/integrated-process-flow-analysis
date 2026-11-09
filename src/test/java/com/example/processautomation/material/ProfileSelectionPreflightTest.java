package com.example.processautomation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileSelectionPreflightTest {
    private static final String SOURCE = "a".repeat(64);
    private static final String P02_WORKING = "b".repeat(64);
    private static final String OTHER_WORKING = "c".repeat(64);
    private final ProfileSelectionPreflight preflight = new ProfileSelectionPreflight();

    @Test
    void selectsOnlyOneExactVerifiedProfileWhenNoSameShaCandidateCompetes() {
        var verified = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                P02_WORKING, "VERIFIED", true, "P02-V1");
        var differentSha = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                OTHER_WORKING, "VERIFIED", true, "P02-OLD");

        var result = preflight.evaluate(request("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE, P02_WORKING),
                List.of(verified, differentSha));

        assertThat(result.status()).isEqualTo("UNIQUE_EXISTING_PROFILE");
        assertThat(result.selectedCandidateId()).isEqualTo("P02-V1");
        assertThat(result.selectionAuthorizedForPreflightOnly()).isTrue();
    }

    @Test
    void blocksSameShaVerifiedAndReviewCandidateConflictEvenIfExistingProfileIsExplicit() {
        var verified = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                P02_WORKING, "VERIFIED", true, "P02-V1");
        var review = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                P02_WORKING, "REVIEW_REQUIRED", false, "P02-REVIEW");

        var noSelection = preflight.evaluate(request("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE, P02_WORKING),
                List.of(verified, review));
        assertThat(noSelection.status()).isEqualTo("BLOCKED");
        assertThat(noSelection.blockingConditions()).contains("PROFILE_SELECTION_AMBIGUOUS");

        var explicitExisting = preflight.evaluate(new ProfileSelectionPreflight.Request(
                "P-02", "P-02-01(TOP)", "I-PP21005", SOURCE, P02_WORKING, "P02-V1", "1.0.0"),
                List.of(verified, review));
        assertThat(explicitExisting.status()).isEqualTo("BLOCKED");
        assertThat(explicitExisting.blockingConditions()).contains("REVIEW_CANDIDATE_CONFLICT_REQUIRES_REVIEW");
        assertThat(explicitExisting.selectionAuthorizedForPreflightOnly()).isFalse();
    }

    @Test
    void neverSelectsReviewOnlyCandidateAndRequiresExplicitSelection() {
        var review = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                P02_WORKING, "REVIEW_REQUIRED", false, "P02-REVIEW");
        var result = preflight.evaluate(request("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE, P02_WORKING),
                List.of(review));
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockingConditions()).contains("PROFILE_SELECTION_REQUIRED", "REVIEW_CANDIDATE_NOT_SELECTABLE");
    }

    @Test
    void blocksWrongWorkingShaAndWrongSheetIdentity() {
        var verified = candidate("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE,
                P02_WORKING, "VERIFIED", true, "P02-V1");
        var wrongSha = preflight.evaluate(request("P-02", "P-02-01(TOP)", "I-PP21005", SOURCE, OTHER_WORKING),
                List.of(verified));
        assertThat(wrongSha.status()).isEqualTo("BLOCKED");
        assertThat(wrongSha.blockingConditions()).contains("PROFILE_WORKING_SHA_MISMATCH");

        var wrongSheet = preflight.evaluate(request("P-02", "P-02-02(BOTTOM)", "I-PP21005", SOURCE, P02_WORKING),
                List.of(verified));
        assertThat(wrongSheet.status()).isEqualTo("BLOCKED");
        assertThat(wrongSheet.blockingConditions()).contains("PROFILE_IDENTITY_MISMATCH");
    }

    @Test
    void appliesTheSameFailClosedIdentityRuleToP01WithoutPromotingItsCurrentWorkingCopy() {
        String p01Source = "d".repeat(64);
        String p01Working = "e".repeat(64);
        var p01Review = candidate("P-01", "P-01-01(TOP)", "I-PP11001", p01Source,
                p01Working, "REVIEW_REQUIRED", false, "P01-CURRENT-WORKING-REVIEW");
        var result = preflight.evaluate(request("P-01", "P-01-01(TOP)", "I-PP11001", p01Source, p01Working),
                List.of(p01Review));
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockingConditions()).contains("REVIEW_CANDIDATE_NOT_SELECTABLE");
    }

    private ProfileSelectionPreflight.Request request(String process, String sheet, String equipment,
                                                       String source, String working) {
        return new ProfileSelectionPreflight.Request(process, sheet, equipment, source, working, null, null);
    }

    private ProfileSelectionPreflight.Candidate candidate(String process, String sheet, String equipment,
                                                           String source, String working, String status,
                                                           boolean registered, String id) {
        return new ProfileSelectionPreflight.Candidate(id, "1.0.0", process, sheet, equipment,
                source, working, status, registered);
    }
}
