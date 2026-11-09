# PILOT-10A — Material balance engine

## Execution and result states
`MaterialFlowNetworkEngine.calculate(network)` performs structural/provenance validation first. Invalid input returns `CALCULATION_UNAVAILABLE` with issues and no partial balance result. Valid input returns per-edge, per-equipment, per-process, and external-boundary records with `TEST_ONLY` scope. It does not call JDBC or the live readiness service.

For each endpoint and stream `(materialClass, materialType, unit, period)`:

`node residual = declared inflow - declared outflow`

A process balance counts only flows entering/leaving the process boundary; transfers whose source and target are in the same process are internal to that aggregate. This record model does not include process generation, storage change, evaporation, reaction yield, or other unprovided terms. Nonzero residuals are reported as `RESIDUAL_UNRESOLVED`; the engine does not force a balance.

External totals separately sum directed external-source inflow and external-sink outflow for each stream. `complete=false` means one or more declared edge quantities were unknown; normal calculation validation rejects unknown quantities instead of inventing values. What-if comparison compares these external totals by stream and reports whether they are unchanged.

## Stream separation and constraints
Material class/type, unit, and period remain distinct keys. No unit conversion, period reconciliation, flow normalization, branch inference, or negative-flow correction is performed. Negative, missing, unresolved, conflicting, unverified, or malformed inputs fail closed. Branch allocation validation requires declared ratios and a total of exactly one for each branch source; it does not apply those ratios to edge quantities.

## Directed topology
The engine maintains forward and reverse adjacency separately. What-if results expose direct endpoints, upstream reachability, downstream reachability, and their union. BFS uses a visited set, so cycles do not cause unbounded impact traversal. Impact discovery is a graph query and is not a quantity solver.

Cycle detection traverses directed edges and reports nodes on detected cycle paths. It annotates `CYCLE_PRESENT_EXPLICIT_FLOWS_ONLY`; already-declared quantities may be summed, but unknown cyclic quantities are not estimated. Detection is not proof that the loop is physically verified or solvable.

## Existing solver BigDecimal change
`DirectedProcessNetworkSolver` now builds a `BigDecimal[][]` conservation matrix and no longer converts RHS or solved values through `double`. It uses partial pivot selection by largest absolute coefficient, 34 significant digits with `HALF_EVEN`, and a `1e-9` absolute tolerance for pivot/consistency/residual decisions. Coefficients at or below the tolerance are treated as zero; rank-deficient systems remain `UNDERDETERMINED`, inconsistent rows remain `INCONSISTENT`, and negative solutions remain constraint violations.

BigDecimal removes binary floating-point conversion, but does not make the model or inputs exact: division rounds under the configured MathContext, and the absolute tolerance is scale-dependent. The solver must not be represented as a physical validation certificate. Residual precision and conditioning still require domain-specific review for very large/small magnitudes.

## Operational boundary
No live network, approval, database, or Excel update is invoked. The existing `NetworkWhatIfService` live `calculate()`/`actualReadiness()` paths remain distinct; `CALCULATION_UNAVAILABLE` remains the actual readiness state. Actual VERIFIED connections and flow mappings: 0.
