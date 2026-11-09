# PILOT-10A — Input schema and validation rules

The schema below describes the Java records in `MaterialFlowNetwork.java`; it is not a new HTTP endpoint. Records serialize as JSON through the existing Jackson setup, but this class does not currently add Jakarta Bean Validation annotations or controller routing.

## Top-level `MaterialFlowNetwork`
- `networkId`: required nonblank identifier.
- `scenarioName`: descriptive text.
- `mode`: must equal `TEST_ONLY` for this engine.
- `processes[]`: `processCode`, label, `sourceDocument`, evidence status.
- `equipment[]`: `equipmentId`, `processCode` referencing an input process, label, source document, evidence status.
- `connections[]`: explicit typed source/target, flow type, material class/type, unit, period, quantity, provenance/evidence, evidence status, and optional allocation metadata.

## Connection requirements
- Unique nonblank `connectionId`; nonblank endpoint IDs; each internal process/equipment endpoint resolves.
- External source must be the source endpoint and have `INFLOW`; external sink must be the target endpoint and have `OUTFLOW`.
- Material class/type, unit, period, flow type, data source, evidence status, and evidence text are required.
- Quantity must be present and nonnegative. Null quantity produces `UNKNOWN_QUANTITY`; negative quantity is rejected.
- A non-scenario connection requires `VERIFIED` connection evidence. `REVIEW_REQUIRED`, `UNKNOWN`, or `CONFLICT` is never treated as a confirmed input; CONFLICT is blocked even in scenario scope.
- A non-scenario VERIFIED connection also requires VERIFIED process/equipment endpoints. Synthetic scenario edges are explicitly flagged `scenarioOnly` and stay TEST_ONLY.
- Branch edges require explicit allocation ratio in [0,1]; ratios leaving the same branch source must sum exactly to one. This check does not calculate flows.
- Stream grouping is exact by material class, material type, unit, and period. No conversions or implicit normalization.

## What-if `Scenario`
- `scenarioId`: required, unique among the supplied cases.
- `changeType`: one of FLOW_CHANGE, PIPE_CHANGE, EDGE_ADD, EDGE_DELETE, BRANCH_ALLOCATION_CHANGE, INTERNAL_RECYCLE_CHANGE, INTERPROCESS_TRANSFER_CHANGE.
- Quantity changes need existing `connectionId` plus nonnegative `newQuantity`.
- Edge addition needs `replacementOrAddition` with unique connection ID and valid complete fields; edge deletion needs a known connection ID.
- PIPE_CHANGE currently appends one scenario edge; atomic remove/add topology replacement is unsupported.

## Errors and trust boundary
Validation failures produce `CALCULATION_UNAVAILABLE` and issue codes. Missing quantities, units, periods, endpoints, branch ratios, and evidence are never inferred. This in-memory contract does not grant authorization, ownership, or approval and is not exposed as a new operational endpoint. Live readiness remains `CALCULATION_UNAVAILABLE`.
