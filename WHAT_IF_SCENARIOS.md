# PILOT-10A — What-if scenario contract

## Baseline isolation
`simulate(base, scenarios)` calculates the input baseline, then creates a new `MaterialFlowNetwork` with copied immutable process/equipment lists and a newly allocated connection list for each scenario. No scenario mutates `base`; multiple scenarios are independently derived from that same baseline, not chained. Official diagram/master modification flags remain false.

## Supported test-only edits
- `FLOW_CHANGE`, `BRANCH_ALLOCATION_CHANGE`, `INTERNAL_RECYCLE_CHANGE`, `INTERPROCESS_TRANSFER_CHANGE`: replace one existing edge quantity with a nonnegative `BigDecimal`; copied edge is marked `scenarioOnly` and `REVIEW_REQUIRED`.
- `EDGE_ADD`: append one uniquely identified proposed edge, marked scenario-only.
- `EDGE_DELETE`: remove one edge from the cloned graph.
- `PIPE_CHANGE`: currently accepts one addition edge only. It is **not** an atomic replacement/re-route operation and must not be called a completed pipe replacement workflow.

Missing target IDs, duplicate IDs, missing additions, negative quantities, and invalid recalculated graphs are reported; no fallback/guessing occurs.

## Returned comparison
Each scenario result carries the calculated result, validation issues, per-endpoint residual deltas (after minus baseline), direct/upstream/downstream/union node sets, and an external boundary invariant boolean. The invariant is numeric equality (BigDecimal `compareTo`) of per-stream external totals and completeness, not proof of conservation or independent measurement. New/deleted endpoint residuals are compared against zero when absent from one side.

## What is not inferred
Branch ratio validation does not distribute flow. The engine does not calculate unknown recirculation flows or use a guessed split. Edge deletion can leave an incomplete graph and then returns `CALCULATION_UNAVAILABLE`. A nonzero residual is retained, not auto-corrected.

## Synthetic validation
Final selected suite: 12 classes / 57 tests / 0 failures / 0 errors / 0 skipped. The new graph tests exercise series flow, branch/merge sums, material unit separation, internal/interprocess cycle detection, 20→10→50 scenarios, unchanged baseline, residual deltas, directional impact, external boundary comparison, additions/deletions, invalid endpoint/evidence/negative/unknown inputs, and exact decimal flow. These fixtures are synthetic and do not validate a real facility flow map. See `validation-results/pilot-10-20261106/final-run-20261106f/`.
