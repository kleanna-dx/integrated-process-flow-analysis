# 통합공정도 자동화 시스템 — 2차 1단계 개발 결과 보고

- 작성일: 2026-10-16
- 프로젝트: Java 17 + Spring Boot 3.5.16 + MariaDB 11.8
- 범위: PW-01-05 단일 시트 시범, 1차 기준자료/기능 보존
- 판정: **부분 구현. 2차 1단계 완료로 판정할 수 없음.** 실제 위치·연결 확인 및 XLSX/도형 편집 구현이 남아 있음.

## 1. 기존 결과 및 공식 자료 보존

- `PHASE1_FINAL_REPORT.md`와 `CHECKPOINT.md` 확인 후 작업함.
- V1.0 기준문서 16개는 MariaDB에 유지됨. 작업 후 로컬 파일·`SHA256SUMS`·DB metadata SHA-256·DB BLOB SHA-256이 **16/16 일치**.
- 기존 connector 후보 `30579`는 `REVIEW_REQUIRED`; 출발/도착 설비 ID는 NULL. DB 전체 `CONFIRMED` 관계는 0건.
- 공식 원본 XLSX 및 기존 migration은 수정하지 않음. 2차 DB migration도 추가하지 않았으며 DB는 확인 용도로만 읽음.
- 기존 단일 Maven 전체 실행 Surefire Exit 137은 미해결 기록으로 유지. 이번 작업에서도 메모리 부담을 고려해 모든 클래스를 따로 실행함.

## 2. 구현된 코드

### 신규 설비 삽입 검토 API

`POST /api/admin/phase2/pw0105/equipment-insertion/preview`

- 요청 샘플(C-PW15020 / WW-F-AFT10 / SCREEN FILTER / 1,500㎥/시 / 1ea)을 받아 검토 응답을 생성.
- 셀 위치, 전단 설비, 후단 설비, 담당자 확인이 빠지면 `REVIEW_REQUIRED` 및 `requiredInputs` 반환.
- `xlsxGenerated=false`, `officialBaselineModified=false`를 명시. 위치나 논리 관계를 유추·확정하지 않음.
- 이 API는 검토 제안만 제공하며 candidate DB 저장, 승인, Excel 수정은 하지 않음.

### 물질수지 시험 계산 API

`POST /api/admin/phase2/material-balance/calculate`

- 요청에 직접 포함된 명시 edge만 대상으로 함.
- 상태 `CONFIRMED` 또는 `TEST_ONLY`이며 양쪽 노드가 정의된 edge만 계산. `REVIEW_REQUIRED`와 unresolved endpoint는 계산에서 제외하고 사유를 반환. `CONFIRMED`는 요청의 검토자·근거도 요구하지만, 아직 DB 승인 이력과 대조하지 않으므로 이 API 응답의 `confirmedEdgesVerifiedAgainstDatabase=false` 경고를 유지.
- 중복 `transferId`는 중복 계산 방지를 위해 요청 오류로 거부. ID 중복 검사는 한 요청 본문 안에서만 수행되며 DB 영속/전역 unique 제약은 아직 없음.
- 설비별 유입·유출·동일 stream 잔차(유입−유출), flow type·unit별 구분, 후단 reachable node를 반환.
- ton/day와 m³/hour 등의 변환은 하지 않음. 밀도·가동시간·수분 함량·저장량 변경은 필요 입력/검토로 남음.
- 현재 순수 요청형·메모리 계산이며 movement/graph의 MariaDB 영속 저장, 변경 마스터의 실제 차이를 transferId edge로 자동 대사하는 기능은 구현하지 않음.

## 3. 시험 설비 및 XLSX 삽입 결과

- 시험 데이터는 요청에 주어진 설비 값을 그대로 사용했으며 공식 등록·승인 설비로 취급하지 않음.
- 사용자가 실제 삽입 셀 범위와 전후 설비를 지정하지 않았음. 마스터 행 순서와 기존 도형의 화면상 접점만으로 위치나 관계를 확정하지 않았음.
- 따라서 **실제 PW-01-05 XLSX에 설비를 삽입하지 않았고, 신규 변경 검토용 XLSX도 생성하지 않았음.** 결과 파일 경로는 없음.
- 원본의 `C-PW15007` 설비 셀 영역 `AI148:AV154`, 기존 눈금선/인쇄영역/스타일 구조 등은 앞선 조사 결과를 유지. 실제 스타일 복제·셀영역 생성 로직 및 DrawingML 연결선 추가/부분 편집기는 아직 구현되지 않음.
- 이에 따라 기존 화살표 보존 및 신규 선 생성, 관계 참조 보존, Excel 열기, PDF/이미지 비교는 이번 단계에서 검증하지 않음. 이전 1차의 단일 셀 OOXML round-trip 테스트는 이번 신규 편집의 대체 검증이 아님.

## 4. 물질수지 테스트 결과

신규 `PhaseTwoPlanningTest`: **6 tests, 6 passed, 0 failures, 0 errors, 0 skipped**.

검증 항목:
1. 삽입 위치·관계 누락 시 `REVIEW_REQUIRED`, 파일 미생성.
2. `TEST_ONLY` edge만 적용하고 `REVIEW_REQUIRED` edge 배제.
3. 다른 단위와 flow type을 임의 합산하지 않음; 같은 단위의 WASTE/WASTEWATER를 별도 잔차로 반환.
4. unresolved 외부 이동처는 계산 제외.
5. 중복 transfer ID는 거부.
6. `CONFIRMED` edge는 검토자와 근거가 있어야 계산 대상이 됨.

이 검증은 API 계산 기반의 명시 시험 edge에 관한 것이며 실측 공정수지 검증은 아님.

## 5. 기존 회귀 테스트

`.env`를 로드한 후 각 클래스별 Maven `verify` 실행. 단일 통합 실행은 하지 않음.

| 테스트 클래스 | 총 테스트 | 통과 | 실패 | 오류 | 건너뜀 | 종료 |
|---|---:|---:|---:|---:|---:|---:|
| MariaDbConnectivityTest | 1 | 1 | 0 | 0 | 0 | 0 |
| OoxmlRoundTripTest | 2 | 2 | 0 | 0 | 0 | 0 |
| BaselineImmutabilityTest | 1 | 1 | 0 | 0 | 0 | 0 |
| RealPw01PreservationTest | 2 | 2 | 0 | 0 | 0 | 0 |
| MasterChangeDetectionIntegrationTest | 10 | 10 | 0 | 0 | 0 | 0 |
| ConnectorAnalysisIntegrationTest | 5 | 5 | 0 | 0 | 0 | 0 |
| PhaseTwoPlanningTest | 6 | 6 | 0 | 0 | 0 | 0 |
| **합계** | **27** | **27** | **0** | **0** | **0** | **모두 0** |

로그: `validation-results/2026-10-16/phase2-groups-valid/`.

초기 검증 시도는 테스트 프로세스에 `.env`가 전달되지 않아 DB 비밀번호 없이 접속했고 DB 기반 테스트가 오류로 끝났음. 또한 SHA256SUMS를 저장소 루트 경로 기준으로 읽으려 해 파일을 찾지 못함. 해당 실패 로그는 `validation-results/2026-10-16/phase2-groups/`에 보존. 설정/경로를 바로잡은 후 독립 실행에서 모든 클래스가 통과했으며, 초기 실패를 통과 결과에 포함하거나 감추지 않음.

## 6. 구현·미구현 현황

### 구현됨
- 제안 위치/관계 누락을 감지하는 PW-01-05 preview API.
- 명시 확인/시험 edge만 계산하는 기본 물질수지 서비스.
- 요청 단위 movement ID 중복 검출, 유형·단위 분리, unresolved·review edge 배제. CONFIRMED의 DB 승인 이력 교차검증은 미구현.
- 관리자 토큰 보호가 적용된 API routes.
- 신규 단위 테스트 6건, 1차 회귀 21건 분리 실행 통과.

### 미구현
- worksheet 셀 style/format clone 및 새 설비 박스 삽입.
- DrawingML connector 생성, 기존 connector endpoint/arrow 수정, OOXML drawing/relationships integrity 검사.
- 실제 변경 검토용 PW-01-05 XLSX 생성 및 Excel/PDF/이미지 비교.
- 영속 graph/transfer 데이터 모델과 migration.
- 변경 마스터의 폐기물종류·폐기물량·이동처 및 폐수 이동량·이동처 차이를 고유 transfer ID로 저장/대사.
- source/sink 분기·합류·회수·재순환 및 외부 공정 송수신 연동의 업무용 데이터 확보/검증.
- 단위 환산의 밀도·운전시간·수분량·저장량 등 입력 모델.

## 7. 다음 작업에 필요한 사용자 확인

도면 수정 안전을 위해 다음을 확인받기 전까지 실제 XLSX 출력은 계속 보류해야 함.

- 삽입할 PW-01-05 셀 범위 또는 위치를 지정하는 기준점 및 크기
- 신규 설비의 직접 전단 설비 ID 및 직접 후단 설비 ID
- 기존 화살표를 수정해야 하는 경우 대상 connector shape ID 및 원하는 연결 방향
- 신규 설비에 폐수/폐기물 이동 표기를 넣을지 여부와 실제 입력 자료
- 배치 및 논리 관계를 확인하는 담당자/검토자

확인받은 뒤에도 별도 변경 검토용 파일만 만들고, 원본 V1.0은 그대로 유지해야 함.

## 8. 공식 도면 확대 시 필요한 작업

- PW-01-05의 비어 있는 셀 범위/도형 endpoint를 담당자가 지정하는 입력 UX와 충돌 검사.
- 원본 workbook의 style index, merged ranges, row/column dimensions, print area, hidden gridline, drawing XML/relationship을 보존하는 부분 OOXML patcher 및 package diff 테스트.
- fixture에서 화살표 tail/head, 신규 connector shape, 관계 ID/anchor/shape ID 충돌, Excel openability를 검증.
- 모든 untouched OOXML part byte identity 및 대상 part의 허용 변경 범위 테스트.
- LibreOffice 동일 조건 렌더 diff와 Excel 네이티브 열기 확인을 분리 보고.
- 영속 flow graph/movement 테이블, source/destination 식별 및 master diff 기반 review 후보 생성, 외부 공정 수신/송신 중복 방지.
- PW-01-05 검증 후에만 다른 시트·공정도를 하나씩 확대. 이번 범위에서 나머지 14개 도면 일괄 적용 없음.
