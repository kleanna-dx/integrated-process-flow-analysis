package com.example.processautomation.material;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure preflight rule set; does not replace or alter the operational Profile repository resolver. */
public final class ProfileSelectionPreflight {
    public Result evaluate(Request request, List<Candidate> candidates) {
        Objects.requireNonNull(request, "request");
        List<Candidate> all = candidates == null ? List.of() : List.copyOf(candidates);
        List<Candidate> identityMatches = all.stream().filter(candidate -> candidate.matchesIdentity(request)).toList();
        List<Candidate> byteMatches = identityMatches.stream().filter(candidate -> candidate.workingSha256() != null
                && request.workingSha256() != null
                && candidate.workingSha256().equalsIgnoreCase(request.workingSha256())).toList();
        List<String> codes = new ArrayList<>();
        if (!validSha(request.sourceSha256()) || !validSha(request.workingSha256())) {
            codes.add("PROFILE_SHA_INVALID");
            return new Result("BLOCKED", null, List.of(), codes, false);
        }
        if (identityMatches.isEmpty()) {
            codes.add("PROFILE_IDENTITY_MISMATCH");
            return new Result("BLOCKED", null, List.of(), codes, false);
        }
        if (byteMatches.isEmpty()) {
            codes.add("PROFILE_WORKING_SHA_MISMATCH");
            return new Result("BLOCKED", null, identityMatches.stream().map(Candidate::candidateId).toList(), codes, false);
        }

        if (request.explicitCandidateId() != null && !request.explicitCandidateId().isBlank()) {
            List<Candidate> explicitlySelected = byteMatches.stream()
                    .filter(candidate -> request.explicitCandidateId().equals(candidate.candidateId())
                            && (request.explicitVersion() == null || request.explicitVersion().equals(candidate.version())))
                    .toList();
            if (explicitlySelected.size() != 1) {
                codes.add(explicitlySelected.size() > 1 ? "PROFILE_SELECTION_AMBIGUOUS" : "PROFILE_SELECTION_NOT_FOUND");
                return new Result("BLOCKED", null, byteMatches.stream().map(Candidate::candidateId).toList(), codes, false);
            }
            Candidate selected = explicitlySelected.get(0);
            if (!selected.registered() || !"VERIFIED".equals(selected.status())) {
                codes.add("REVIEW_CANDIDATE_NOT_SELECTABLE");
                return new Result("BLOCKED", null, byteMatches.stream().map(Candidate::candidateId).toList(), codes, false);
            }
            if (byteMatches.stream().anyMatch(candidate -> candidate != selected
                    && (!candidate.registered() || !"VERIFIED".equals(candidate.status())))) {
                codes.add("REVIEW_CANDIDATE_CONFLICT_REQUIRES_REVIEW");
                return new Result("BLOCKED", null, byteMatches.stream().map(Candidate::candidateId).toList(), codes, false);
            }
            return new Result("UNIQUE_EXISTING_PROFILE", selected.candidateId(),
                    byteMatches.stream().map(Candidate::candidateId).toList(), List.of(), true);
        }

        if (byteMatches.size() > 1) {
            codes.add("PROFILE_SELECTION_AMBIGUOUS");
            return new Result("BLOCKED", null, byteMatches.stream().map(Candidate::candidateId).toList(), codes, false);
        }
        Candidate only = byteMatches.get(0);
        if (!only.registered() || !"VERIFIED".equals(only.status())) {
            codes.add("PROFILE_SELECTION_REQUIRED");
            codes.add("REVIEW_CANDIDATE_NOT_SELECTABLE");
            return new Result("BLOCKED", null, List.of(only.candidateId()), codes, false);
        }
        return new Result("UNIQUE_EXISTING_PROFILE", only.candidateId(), List.of(only.candidateId()), List.of(), true);
    }

    private boolean validSha(String sha) {
        return sha != null && sha.matches("(?i)[0-9a-f]{64}");
    }

    public record Request(String processCode, String sheetName, String managementNo,
                          String sourceSha256, String workingSha256,
                          String explicitCandidateId, String explicitVersion) { }

    /** status VERIFIED means operational catalog entry; REVIEW_REQUIRED entries are never selectable. */
    public record Candidate(String candidateId, String version, String processCode, String sheetName,
                            String managementNo, String sourceSha256, String workingSha256,
                            String status, boolean registered) {
        boolean matchesIdentity(Request request) {
            return Objects.equals(processCode, request.processCode())
                    && Objects.equals(sheetName, request.sheetName())
                    && Objects.equals(managementNo, request.managementNo())
                    && sourceSha256 != null && sourceSha256.equalsIgnoreCase(request.sourceSha256());
        }
    }

    public record Result(String status, String selectedCandidateId, List<String> consideredCandidateIds,
                         List<String> blockingConditions, boolean selectionAuthorizedForPreflightOnly) {
        public Result {
            consideredCandidateIds = List.copyOf(consideredCandidateIds);
            blockingConditions = List.copyOf(blockingConditions);
            if (selectionAuthorizedForPreflightOnly && !"UNIQUE_EXISTING_PROFILE".equals(status)) {
                throw new IllegalArgumentException("Only one exact existing VERIFIED Profile can pass selection preflight.");
            }
        }
    }
}
