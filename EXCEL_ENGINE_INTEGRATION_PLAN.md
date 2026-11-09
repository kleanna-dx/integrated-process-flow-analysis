# PILOT-10A — Excel engine integration plan (not implemented)

## Current reusable pieces
- `DirectedProcessNetworkWorkbookExporter` creates detached TEST_ONLY calculation/scenario workbooks; it does not modify the official master.
- `Step7MaterialBalanceComparisonService` parses official/team workbooks read-only and classifies unsupported matches as `INSUFFICIENT_EVIDENCE` rather than promoting candidates.
- Existing FLOW_CHANGE/PIPE_CHANGE drawing engines work with synthetic fixtures or guarded user-working-copy workflows, not arbitrary real graph records.

No code in this pilot connects `MaterialFlowNetworkEngine` results to a live Excel edit request. Actual automatic diagram update is **not implemented**.

## Required edit contract before integration
A future edit request must be built only from a reviewed and immutable mapping snapshot containing:
1. Process code ↔ workbook file and sheet name.
2. Equipment ID/permit management number ↔ exact drawing shape ID and cell/EMU geometry, with source SHA and version.
3. Connection ID ↔ exact connector shape ID, start/end shape IDs, direction arrow metadata, edge/material/unit/period, evidence and approval history.
4. Flow value ↔ exact cell/formula/range and number format; preserve merged ranges, style, formula, comments, relationships, and workbook-level OOXML parts.
5. Whole-document coverage/region inventory and explicit `UNMAPPED`, ambiguous, overlap, hidden, or stale mapping states. Absence of a mapping must block edits; it must not be filled from arrow geometry or adjacency guesses.

## Change-type controls
- **FLOW_CHANGE**: exact verified edge-to-cell/label mapping; old value, unit, period, evidence, owner and scenario ID must match before writing a detached working copy.
- **PIPE_CHANGE**: a topology diff must include all removed and added verified edges, both endpoint bindings, direction/arrow review, and visual reviewer approval. The current one-edge addition scenario is not a replacement contract.
- **MODIFY/ADD/DELETE**: maintain existing Profile/resolver/authorization/owner/CSRF guards. New calculation DTOs cannot bypass current operation guards or turn a candidate into an approved connection.
- Preserve existing workbook formats, merges, styles, formulas, drawings, relationships, arrows, and untouched package parts. Generate a separate TEST_ONLY output; never overwrite the official baseline or user's source working copy.

## Readiness gate and remaining work
A future integration requires independent verified location mapping for all affected (and required surrounding) drawing regions, complete source/snapshot/hash history, explicit route/scope and period alignment, and human review of the rendered before/after drawing. Current evidence does not provide sufficient connector endpoint or flow mapping: actual VERIFIED connection/flow mappings remain 0 and live readiness remains `CALCULATION_UNAVAILABLE`. Therefore no actual workbook write or production FLOW_CHANGE/PIPE_CHANGE is enabled.
