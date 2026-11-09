# PILOT-10A — MaterialFlowNetwork model

## Scope
`MaterialFlowNetwork` and `MaterialFlowNetworkEngine` are an in-memory, conservative accounting contract for TEST_ONLY or explicitly verified inputs. They are not connected to the existing live FLOW_CHANGE/PIPE_CHANGE request path, database persistence, or approval workflow.

## Graph records
- `MaterialFlowNetwork`: `networkId`, `scenarioName`, `mode`, immutable lists of `Process`, `Equipment`, and `Connection`.
- `Process`: process code, label, source document, and `EvidenceStatus`.
- `Equipment`: stable equipment ID, owning process code, label, source document, and `EvidenceStatus`.
- `Connection`: connection ID; typed source and target endpoint; flow type; material class/type; unit; period; optional `BigDecimal` quantity; source/evidence; evidence status; explicit branch allocation metadata; and `scenarioOnly` marker.
- `Scenario`: scenario ID/description, change type, existing connection ID/new quantity, or proposed addition/replacement edge.

`EndpointType` distinguishes `PROCESS`, `EQUIPMENT`, `EXTERNAL_SOURCE`, and `EXTERNAL_SINK`. External endpoints require a nonblank endpoint ID but are not mapped to equipment. External source edges must be `INFLOW`; external sink edges must be `OUTFLOW`.

## Provenance and status
Connection statuses are `VERIFIED`, `REVIEW_REQUIRED`, `UNKNOWN`, and `CONFLICT`. Non-scenario calculations reject a non-VERIFIED edge; CONFLICT edges are rejected even if flagged scenario-only. A non-scenario VERIFIED edge also requires VERIFIED process/equipment endpoints. The TEST_ONLY scenario path marks copied/added edges as scenario-only and never converts them into real verified evidence. `scenarioOnly` is a calculation scope marker, not approval evidence.

Every edge needs a data source, evidence description, period, unit, material class/type, endpoints, and a declared quantity. Null/negative quantities are not imputed or corrected. Identical endpoints can have multiple connection records, each with its own ID and stream attributes.

## Stream identity
Balances are partitioned by material class, material type, unit, and measurement period. Units and periods are not converted or combined. If one node has the same material and period in multiple units, the engine emits `MATERIAL_UNIT_MISMATCH_SEPARATED` and keeps the streams separate.

## Explicit limits
The model has no storage-change or process-generation term. It balances declared connection flows only. Branch ratios are validation metadata: the engine checks declared ratios and sum-to-one but does **not** calculate downstream edge quantities from those ratios. A cycle is identified in the directed topology; unknown cycle flows are not solved by this engine. Real VERIFIED connection/flow mapping remains zero; all synthetic fixtures are illustrative only.
