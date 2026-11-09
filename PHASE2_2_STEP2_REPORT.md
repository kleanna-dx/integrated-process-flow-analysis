# 통합공정도 자동화 — 2차 2단계 Step 2 결과

- 작업일: 2026-10-19
- 범위: TEST_ONLY 물질수지 잔차/경계조건/수신량 대사/후단 영향 검증
- 제한 준수: 공식 V1.0 원본 16개, MariaDB 공식 기준자료, TEST_ONLY V5 Excel을 수정하지 않음. 계산값의 XLSX 반영은 수행하지 않음.

## 구현 사항

1. **잔차 미확정 상태**
   - 계산 가능한 balance에서 `inflow - outflow - storageChange`가 0이 아니면 노드 상태를 `RESIDUAL_UNRESOLVED`로 표시하고 flowType/unit/잔차 및 필요한 추가정보를 반환.
   - 잔차를 자동으로 0으로 조정하거나 다른 설비에 분배하지 않음.
   - 수신 노드 역할별 추가자료 요청: 목적지/검증된 기타 수지항, flow type/unit/측정기간, 저장시설의 계측 저장량 변화 또는 opening/closing inventory, 폐기물 보관·반출 증빙, terminal 경계 증빙.

2. **수신시설/경계/저장량**
   - 역할 구분: `PROCESS` 일반 처리설비, `WASTEWATER_STORAGE`, `WASTE_STORAGE`, `WASTE_SINK`, `FINAL_DISCHARGE`, `FINAL_OFFSITE`.
   - Node optional fields `storageChange`, `storageFlowType`, `storageUnit`, `boundaryCondition` 추가. 저장량 변화의 양수는 저장 증가, 음수는 저장 감소이며 단위/유형이 유입/유출과 다르면 자동 변환하지 않음.
   - `FINAL_DISCHARGE + TERMINAL_DISCHARGE` 또는 `FINAL_OFFSITE + TERMINAL_OFFSITE`가 명시된 경우에만 수신량을 별도 `EXPLICIT_TERMINAL_BOUNDARY` outflow로 넣음. 경계조건이 없으면 최종 배출/반출이라고 추정하지 않음. Terminal 노드에 추가 후단 이동도 있으면 boundary 충돌로 계산을 불가 처리.
   - 폐기물 수신시설 유입 0.5 ton/day에 저장/반출/terminal 정보가 없으면 `UNCONFIRMED_STORAGE_OR_OFFSITE_DISPOSITION`, 잔차 0.5, `RESIDUAL_UNRESOLVED`로 남김.

3. **공정 간 이동 대사 및 중복 방지**
   - 한 transfer record의 양을 송출 노드에서 한 번 outflow, 수신 노드에서 한 번 inflow로 집계. 분기·합류도 각 edge를 한 번씩 집계하고 후단 탐색은 node visited set으로 중복 방문을 차단.
   - Transfer optional `receiverQuantity`를 추가. 수신량이 별도 제공되고 송출량과 다르면 `SENDER_RECEIVER_QUANTITY_MISMATCH`로 edge 전체를 제외함. receiver quantity가 없으면 같은 단일 record에서 양 끝 값이 파생됐음을 `MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED`로 명시.
   - 독립 송수신 계측 데이터가 없는 경우 수량 일치는 독립 증빙 검증이 아니라 동일 요청량의 양 끝 반영이라는 한계가 있음. 독립 receiverQuantity의 불일치와 DB 기록도 별도 통합 테스트로 확인.
   - 서로 다른 단위는 flow별 별도 유지하고 변환/합산하지 않음. Node에 단위가 혼합되면 계산 불가.

4. **재사용 가능한 transferId**
   - V9 migration은 보존. V10에서 기존 전역 `UNIQUE(transfer_id)`를 `UNIQUE(simulation_db_id, transfer_id)`로 교체.
   - 기존 row를 삭제/재작성하지 않으며 relation FK 등 참조는 그대로 둠. 기존 전역 unique 때문에 이미 허용되지 않았던 cross-simulation 중복 row는 없었음.
   - V11에서 송수신 별도 검증값을 보존하는 nullable `receiver_quantity` 컬럼 추가. `NULL`은 receiver 측 독립 수량 미제공을 뜻함.
   - 같은 simulation 안의 중복 transferId는 계산기에서 거부하고 복합 DB unique도 방어선으로 유지. 독립 simulation 간 동일 transferId 재사용 테스트 통과.

5. **후단 영향 및 승인 상태**
   - 후단 영향 결과는 node ID 외에 label, equipmentId, processCode, role을 반환.
   - 실제 `CONFIRMED` 경로는 기존처럼 DB relation 승인 상태, immutable review history, endpoint, unit/quantity/flow/edge type 등이 모두 일치할 때만 포함. 현재 기준 DB의 `CONFIRMED`는 0건이므로 승인 연결을 새로 확정하지 않음.
   - 미승인/잘못된 edge는 계산 그래프에 들어가지 않고 제외 사유를 반환. cycle 전체 거부를 제거했으며 재순환/분기/합류 downstream BFS는 유한하게 종료하고 각 node를 최대 한 번 방문.
   - 주의: 순환 유량의 수렴/재순환 반복수지 계산은 구현하지 않음. 고정된 edge 양의 영향 범위를 cycle-safe하게 추적하는 것만 지원.

## 요청한 시험 시나리오 결과

단위는 모두 합성 `ton/day`. 가상 노드 표의 유입·유출은 flowType별이며, 잔차는 `유입 - 유출 - 저장량 변화`입니다.

| 설비/공정 | 유입 | 유출 | 저장량 변화 | 잔차 | 상태 |
|---|---:|---:|---:|---:|---|
| 원천 S / P-TEST-A | WASTEWATER 100 | WASTEWATER 100 (폐기물 발생 0.5 + P2 이동 2.5 + 후단 97) | 미입력 | 0 | 균형 계산 가능 |
| 폐기물 수신 W / P-TEST-A | WASTE 0.5 | 0 | 미입력 | 0.5 | `RESIDUAL_UNRESOLVED`; 저장/반출/최종 수신 여부 미확정 |
| 수신 처리설비 P2 / P-TEST-B | WASTEWATER 2.5 | WASTEWATER 2.25 | 미입력 | **0.25** | **`RESIDUAL_UNRESOLVED`** |
| 후단 처리설비 P3 / P-TEST-C | WASTEWATER 2.25 | WASTEWATER 2.25 | 미입력 | 0 | 균형 계산 가능 |
| Outlet D / P-TEST-C | WASTEWATER 99.25 (S의 97 + P3의 2.25) | 0 | 미입력 | 99.25 | terminal boundary 미명시이므로 미확정 |

- P2의 **0.25 ton/day는 그대로 유지**. 현재 모형에는 이동처·저장 변화·기타 수지 항이 없어 원인을 확정하지 않음.
- D는 이름/role만으로 terminal discharge로 간주하지 않음. `TERMINAL_DISCHARGE` 또는 offsite 경계와 실제 경계 증빙이 필요.
- 위 시나리오의 폐기물 `WASTE_GENERATION`은 원천의 WASTEWATER outflow와 수신의 WASTE inflow로 흐름 유형을 분리함.

## 테스트 및 무결성 검증

| 테스트 클래스 | 실행 수 | 성공 | 실패/오류/skip |
|---|---:|---:|---:|
| `MaterialBalanceCalculatorTest` | 6 | 6 | 0 / 0 / 0 |
| `MaterialBalancePersistenceIntegrationTest` | 7 | 7 | 0 / 0 / 0 |
| `PhaseTwoPlanningTest` | 1 | 1 | 0 / 0 / 0 |
| **합계** | **14** | **14** | **0 / 0 / 0** |

추가 검증:
- 두 독립 TEST_ONLY simulation에서 같은 transferId 재사용 성공; 동일 simulation 내 같은 ID 중복은 거부.
- 송수신 별도 수량 불일치는 edge 제외; 저장량 변화와 명시 terminal boundary는 각자 별도 balance term으로 검증.
- 분기/합류 및 cycle-safe downstream 탐색, mixed unit 계산 불가, 미승인 relation 제외, simulation 멱등/충돌 회귀 테스트.
- Flyway V11까지 적용, 복합 unique index `(simulation_db_id, transfer_id)` 확인. 테스트 트랜잭션 종료 뒤 simulation row 0.
- V1.0 DB 문서 16, equipment 1,332, CONFIRMED relation 0, REVIEW_REQUIRED 1 유지.
- `verify-baseline-hashes.py`: 원본 로컬 파일 / manifest / MariaDB metadata hash / BLOB hash **16/16 PASS**.
- TEST_ONLY V5 SHA-256: `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485` — 기존 값과 일치.
- `git diff --check` 통과. 전체 Maven suite는 실행하지 않음.
- Flyway가 MariaDB 11.8이 지원 상한 11.2보다 높다는 경고를 출력했으나 11개 migration validation 및 V10/V11 적용은 성공.

## 기존 기능 영향 및 제한

- 1차 기능 및 공식 기준자료 importer/관계 승인 규칙은 변경하지 않음. 삽입 계획 회귀(`PhaseTwoPlanningTest`) 통과.
- 실제 PW-01-05 TEST_ONLY XLSX 및 공식 V1.0 XLSX는 열어 저장하거나 재생성하지 않았음.
- 현재 기준 DB에 승인된 실제 route가 없으므로 결과는 합성 TEST_ONLY 연결만 사용. TEST_ONLY 모드도 baseline/equipment_flow에 쓰지 않음.
- 저장량 값은 입력으로 받을 수 있으나 센서·시점 기반의 opening/closing inventory 검증, 측정 불확실성, 수분/고형물 수지, 단위 변환 및 전체 공정 보존 증명은 미구현.
- 실제 독립 receiverQuantity 자료를 운영/실자료에서 검증하지 않았음. 현재 시험은 불일치 거부 단위 테스트이며 데이터 도입 전에 단위/기간/계측 신뢰성을 검토해야 함.

## 다음 단계의 XLSX 준비 조건

1. 0.25 P2 잔차의 실제 이동처 또는 저장량 변화/기타 수지 자료를 확보하고 담당자·기간·단위를 근거와 함께 기록.
2. 폐기물 0.5의 실제 저장시설/반출처/최종 처리 경계 중 무엇인지 증빙하고, WASTE_STORAGE 또는 명시 terminal boundary를 시험 payload에 지정.
3. D의 97 및 P3의 2.25가 실제 최종배출인지 downstream 유입인지 확인. outlet이 단순 label이라는 이유로 terminal 처리하지 않음.
4. 송출/수신 계측이 있으면 수량을 별도로 제공하고 측정기간·단위·자료 출처를 확인. unit conversion은 승인된 변환 근거 없이는 하지 않음.
5. 실제 연결은 승인 DB relation과 immutable review history까지 확인. 미승인 관계는 계속 제외.
6. Step 3는 기존 V5를 덮어쓰지 않는 새 TEST_ONLY 산출 파일/버전에서 작업하고, 변경 전 hash·OOXML/셀/도형/수식·출력 페이지 비교 기준을 정함. 승인 후에만 유량 셀 반영.

**Step 3 유량 자동 수정은 이번에 수행하지 않았으며 다음 사용자 지시 전까지 금지 상태입니다.**
