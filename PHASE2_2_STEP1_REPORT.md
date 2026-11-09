# 2차 2단계 — 첫 TEST_ONLY 물질수지 시범 구현 결과

- 작업일: 2026-10-19
- 상태: **시범 계산 엔진 첫 구현 완료 / 실제 업무자료·실제 도면 적용 미수행**
- 기준 파일: 기존 V5 유지 — `validation-results/2026-10-16/pw01-05-test-only/PW-01-05-C-PW15020-TEST_ONLY-V5.xlsx`

## 구현 내용

- 기존 `/api/admin/phase2/material-balance/calculate` 경로를 TEST_ONLY 시뮬레이션 서비스에 연결했다. 운영 반영 `mode`는 받지 않으며 공식 원본, `equipment_flow`, 실제 공정 파일에 쓰지 않는 것을 응답에도 표시한다.
- 노드별 유입·유출·flowType/unit별 잔차, 이동처·transferId·이동량·후단 영향 범위를 반환한다.
- 여러 edge를 통한 분기와 합류, WASTEWATER에서 WASTE로 분류되는 폐기물 발생/차감, 폐수의 타 공정 이동과 수신 노드 유입 증가, 후단 유량 수정에 따른 잔차 변화를 지원한다.
- 노드에서 단위가 다르면 변환하지 않고 `CALCULATION_UNAVAILABLE` 및 `MIXED_UNITS_NO_CONVERSION_APPLIED`를 반환한다. 출처량/단위/종류가 없거나 해당 역할의 유입·유출 증거가 없으면 계산 불가 이유를 명시한다. 순환 그래프와 같은 simulation 내 중복 transferId는 거부한다.
- MariaDB Flyway V9에 `material_balance_simulation`, `material_balance_transfer`를 추가했다. simulationId는 유일하고 동일 요청 재전송은 저장된 결과를 반환한다. transferId는 전역 유일 인덱스로 중복 이동 재계산을 막는다.
- `CONFIRMED` 요청은 요청 필드의 상태만 신뢰하지 않는다. DB `process_relation`의 CONFIRMED / MANUALLY_RESOLVED / reviewer / reviewed_at, append-only review history의 승인 snapshot, DB endpoint 및 승인된 quantity/unit/flow/edge type 일치를 모두 확인한다. 불일치·미승인 관계는 edge 제외 사유로 반환한다.
- DB 스키마 migration은 적용했지만 테스트 시뮬레이션 레코드는 통합 테스트의 transaction rollback으로 보존되지 않음을 확인했다. 공식 원본 16개, 설비 master 1,332개, process relation은 테스트 전후 기준 상태로 유지했다.

## 명시된 가상 시나리오

입력은 실제 자료가 아닌 합성 TEST_ONLY 값이다.

- 가상 유입: 100 ton/day
- 폐기물 발생: 0.5 ton/day
- 타 공정 이동: 2.5 ton/day
- 첫 공정 후단 유출: 97 ton/day
- 수신 공정에서 후단 배출을 2.25 ton/day로 시범 변경

| 노드 | 가상 유입 | 가상 유출/이동 | 잔차(유입−유출) |
|---|---:|---:|---:|
| 가상 원천 S | 100 | 0.5 폐기물 발생 + 2.5 타 공정 이동 + 97 후단 유출 = 100 | 0 |
| 가상 폐기물 수신 W | 0.5 | 0 | 0.5 |
| 타 공정 P2 | 2.5 | 2.25 후단 이동 | 0.25 |
| 후단 공정 P3 | 2.25 | 2.25 | 0 |
| 최종 배출 D | 99.25 | 경계 유출 미지정 | 99.25 |

- WASTE 발생 edge는 원천의 WASTEWATER 출구 0.5와 WASTE sink의 유입 0.5로 구분해 표현하여 원천의 wastewater 잔차가 0이 되도록 했다.
- 마지막 outlet의 외부 배출량은 제공되지 않아 0이나 97로 가정하지 않는다. 수신 공정 P2에는 타 공정 이동 2.5가 유입되고, 2.25 후단 이동에 따른 잔차 0.25를 표시한다.
- 이 결과는 해당 edge 집합의 계산 결과일 뿐 완전한 물리적 물질수지 증명이나 실제 배출량이 아니다.

## 테스트 결과

각 그룹을 별도로 실행했으며 전체 Maven suite는 실행하지 않았다.

1. `MaterialBalanceCalculatorTest`: **4 tests, 0 failures/errors/skips — PASS**
   - 가상 유입·폐기물 발생·타 공정 이동·후단 유량 변경, 분기/합류, 단위 불일치, 필수 조건 누락, 중복/순환 검증
2. `MaterialBalancePersistenceIntegrationTest`: **6 tests, 0 failures/errors/skips — PASS**
   - V9 DB persistence, simulationId 멱등성, transferId DB 유일 제약, payload 충돌, 미승인/존재하지 않는 CONFIRMED relation 제외, 지정한 합성 시나리오 결과, 기준 문서·설비 건수 불변
3. `PhaseTwoPlanningTest`: **1 test, 0 failures/errors/skips — PASS**

로그:
- `validation-results/2026-10-19-material-balance-calculator.log`
- `validation-results/2026-10-19-material-balance-persistence.log`
- `validation-results/2026-10-19-phase-two-planning.log`

통합 테스트는 테스트 트랜잭션에서 DB 자료를 삽입 후 rollback했다. 테스트 뒤 새 material simulation 0건, 기준 문서/설비 및 relation 수가 변하지 않은 것을 확인했다. MariaDB 11.8에 대해 Flyway가 11.2까지만 검증된 버전 경고는 남는다.

## V5 7바이트 차이 조사

- 기존 보고서의 `536,801 bytes`는 V4 파일 크기와 일치한다.
- 현재 V5는 **536,808 bytes**, SHA-256 `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`이다. 이전 V5 백업에서 추출한 파일도 **536,808 bytes**와 같은 SHA-256으로 일치한다. 기존 V5 파일을 다시 생성하거나 수정하지 않았다.
- V4 → V5 차이는 `xl/worksheets/sheet5.xml` 하나뿐이다. 폐수·폐기물 구획과 center-across 표시의 공백 placeholder 셀 8개를 보존해 해당 XML의 비압축 크기가 440 bytes 증가하고 ZIP 압축 크기가 **7 bytes** 증가했다. 시각선 연속 보정에 수반된 파일 크기 변화이며 V5 대 백업 해시 불일치는 없다.

## 미완료 / 다음 지시 대기

- 공식 PW-01-05 Excel, 공식 연결, 실제 폐수·폐기물 양·단위·이동처에는 이 계산을 적용하지 않았다. V5 XLSX와 V1.0 원본은 변경되지 않았다.
- 기준자료의 실제 공정 연결을 물질수지에 자동 연결하거나 Excel 유량을 수정하지 않았다. 현재 시범은 API 요청으로 명시한 가상 그래프 계산에 한정한다.
- production `CONFIRMED` 흐름은 승인 DB 조건을 통과해야만 계산 edge 후보가 될 수 있지만, 운영 물질수지 반영은 꺼져 있다. 여러 관계 endpoint/분류 정책 및 승인된 공식 산정 규칙은 사업 검증이 필요하다.
- 밀도·농도·수분·고형물 함량·시간 기준 간 단위변환, 저장량 변화, 재순환 등 추가 보존식, multi-stream blending 및 유량/질량/체적 차원 모델은 미구현이다.
- 현재는 첫 시범 구현에서 멈춘다. 실제 PW-01-05 유량 변경, 공식 연결 적용, 추가 기능 개발은 다음 사용자 지시 전까지 시작하지 않는다.
