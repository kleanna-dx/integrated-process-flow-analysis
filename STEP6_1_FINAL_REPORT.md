# 통합공정도 자동화 — Step 6-1 최종 테스트 및 마무리 보고서

- 검증일: 2026-10-19
- 범위: 잠금 V1.0 마스터와 변경 Excel의 비교, TEST_ONLY 저장, 도면 영향 후보, 업로드 API/UI 검증, 기준 원본 무결성
- 제외: 공식 공정도 편집, 신규 공식 도면 생성, 관계 승인, Step 6-2

## 1. 최종 판정

**Step 6-1 구현 및 지정한 관련 검증을 완료했습니다.** 기준 파일은 사용자가 올린 변경 파일이 아니라 MariaDB의 잠금 V1.0 `baseline_release`를 사용했습니다. 업무상 변경된 마스터 Excel은 제공되지 않아, 공식 마스터의 별도 TEST_ONLY 복사본으로 API 및 분석 과정을 검증했습니다. 따라서 시험은 완료됐지만 실제 변경본에 대한 업무 검증은 아직 아닙니다.

공식 V1.0, 원본 16개, 기준 마스터 1,332행, Step 5 최종 DRAWING-IDFIX 파일은 수정하지 않았습니다. 변경 분석은 `change_run.analysis_mode='TEST_ONLY'`로 저장되며, 공정도/관계 반영은 이루어지지 않았습니다.

## 2. 구현 기능

- `/master-diff.html`: 전체 목록 `.xlsx` 단일 파일을 업로드하는 화면. 관리자 토큰은 암호 입력값으로만 요청에 보내고 `localStorage`/브라우저 저장소에는 저장하지 않습니다.
- `POST /api/admin/master-diff`: 단일 `masterFile` multipart 요청, `.xlsx` 검증, `X-Admin-Token` 인증 및 JSON 미리보기.
- `ChangeDetectionService`: 잠금 V1.0 비교, 관리번호 기반 추가/삭제/수정/상대 순서 판정, 전후 필드값, 업로드 SHA-256 및 원본 바이트 보관.
- `DiagramImpactAnalysisService`: 기존 V1.0 설비 매핑, 공정 문서·시트 인벤토리 및 정확한 관리번호/ItemNo 텍스트 근거로 후보 표시. 공정코드만 확인된 시트는 `PROCESS_CODE_ONLY_REVIEW`; 다중 시트는 `MULTI_SHEET_REVIEW`; unmatched/no-document/미발견은 별도 review status로 보존합니다.
- 공정번호가 변경된 기존 설비의 V1.0 위치는 과거 근거로만 표시하고 새 위치를 확정하지 않습니다. 인접 행만으로 관계를 만들지 않습니다.
- 빈 폐수·폐기물 입력은 기존 값이 있는 경우 삭제나 0으로 치환하지 않고 `BLANK_FLOW_INPUT_NOT_TREATED_AS_DELETION` 검토로 분리합니다.
- V12 Flyway migration이 `change_run`/`change_item`에 TEST_ONLY 분석 모드, 도면 영향 및 review 정보 컬럼을 추가합니다.

## 3. TEST_ONLY 마스터와 실제 API/MariaDB E2E

실제 변경 마스터가 없어 `/home/user/webapp/data/reference/전체 목록.xlsx`를 읽기 전용 원본으로 열고, 다음 파일을 별도 복사본으로 생성했습니다.

- 시험 파일: `validation-results/2026-10-19-step6-1/TEST_ONLY-master-change-scenario.xlsx`
- SHA-256: `f1519bac2c2f05b75220d8391bf3526da93a46dc7fe6b8f59a205c30ed84bfe5`
- 시나리오: 기존 두 행 사이 신규 설비 1개 삽입, 첫 기존 설비의 명칭·용량·폐수/폐기물 발생량·이동처 변경

실제 로컬 Spring Boot 3.5.16 API와 MariaDB 11.8에 multipart 업로드했습니다. 인증 없는 요청은 HTTP 401로 차단됐습니다. 인증된 단일 파일 업로드는 HTTP 200을 반환했습니다.

- TEST_ONLY `changeRunId`: **171**
- 기준 1,332 / 업로드 1,333
- 추가 1, 삭제 0, 수정 1, 실제 상대 순서 변경 0
- `diagramMappingStatus=REVIEW_REQUIRED`, 영향 후보 9건, 검토 항목 9건
- 인증 GET `/api/admin/change-runs/171`, `/items`, `/issues` 재조회 성공
- DB의 저장 분석은 `analysis_mode=TEST_ONLY`, change item 2건, 해당 run의 flow candidate 2건 모두 `route_created=false`
- baseline `equipment_record`는 계속 1,332건이며 공정 관계도 계속 `CONFIRMED=0`, `REVIEW_REQUIRED=1`

위 파일은 공식 마스터/DB baseline을 대체하지 않았습니다. API 응답 및 DB 저장·조회는 실제 서버 경로로 확인했습니다. API 이외에 실제 화면에서 파일 선택·업로드 버튼을 누르는 브라우저 상호작용은 자동화하지 않았습니다. `/master-diff.html`은 HTTP 200, 브라우저 문서 제목 및 업로드 form selector까지 로드됐지만, Playwright에서 미확인 404 리소스 1건이 남아 UI 상호작용 전체는 **미검증**으로 기록합니다.

## 4. 변경 분류 시나리오 정확성

최종 `MasterChangeDetectionIntegrationTest` 18건에서 다음을 검증했습니다.

| 시나리오 | 확인 결과 |
|---|---|
| 동일 마스터 | 변경 0, baselineModified=false, 1,332건 일치 |
| 신규 설비 | 고유 관리번호 기반 ADDED 1건 |
| 중간 행 삽입 | ADDED 1, MOVED 0, 나머지 기존 행 이동 오탐 없음 |
| 기존 설비 삭제 | REMOVED 1건 |
| 설비명·용량 변경 | 전후 값 포함 MODIFIED 1건 |
| 기존 두 설비의 상대 순서 변경 | MOVED 2건, 데이터 변경 0건 |
| 공정번호 변경 | `PROCESS_CODE_CHANGED_REVIEW_REQUIRED`, V1.0 시트 근거는 historical only |
| 폐수·폐기물 양·이동처 변경 | 변경 필드 4개를 변경 전/후 값으로 반환, 자동 route 생성 없음 |
| 관리번호 중복 / 누락 | 각각 비교 실행을 거부, 결과 run을 만들지 않음 |
| 매핑되지 않은 신규/기존 설비 | 자동 배정 없이 `NO_PROCESS_DIAGRAM_MATCH` 또는 `BASELINE_MAPPING_UNRESOLVED` 및 review |
| 동일 설비 다중 시트 | V1.0 매핑에서 실제 두 시트를 `MULTI_SHEET_REVIEW`로 반환하고 별도 공정코드 후보 시트는 `PROCESS_CODE_ONLY_REVIEW`로 구분 |

### 공란 flow 입력의 의미

공식 V1.0 마스터의 폐수/폐기물 종류·량·이동처 열은 전부 공란입니다(공식 파일 read-only scan). 따라서 실제 baseline 행에서 기존 비공란 값이 지워지는 케이스는 V1.0 DB를 기준으로 한 E2E에서 만들 수 없습니다. 대신 `MasterFieldChangePolicyTest`에서 합성 before 값이 존재하고 upload 값이 공란인 경우를 시험하여 기존 값을 보존하고 변경/삭제/0으로 만들지 않는 정책을 확인했습니다. 실제 업무자료에서 비공란 flow 값이 있는 기준 버전에 대한 별도 검증은 미실시입니다.

## 5. 도면 영향 분석 및 REVIEW_REQUIRED

- V1.0 공식 기준은 공정도 **15개 파일, 62개 시트**입니다.
- TEST_ONLY E2E에서 변경 설비 `I-PU11001`은 기존 단일 `MAPPED_PERMIT_ID` 근거의 workbook/sheet 위치 후보만 반환했습니다. 기존 매핑이 있더라도 `EXISTING_BASELINE_MAPPING_REVIEW_REQUIRED`이며 수동 확인이 필요합니다.
- 신규 `I-TEST-E2E-001`은 관리번호 및 ItemNo가 V1.0에 없어서 8개 시트의 `PROCESS_CODE_ONLY_REVIEW` 후보만 받았습니다. 이는 `P-01` 기반 검색 후보이며 실제 설비 위치 배정이나 연결이 아닙니다.
- 별도 다중시트 테스트는 해당 설비의 기존 매핑 두 시트를 모두 `MULTI_SHEET_REVIEW`로 보고하고 공정코드 후보는 별도 상태로 표시했습니다.
- 매핑되지 않은 설비는 임의 공정도 배정 없이 review. 인접 마스터 행만으로 설비간 전후 관계를 추정하거나 `process_relation`에 신규 관계를 쓰지 않았습니다.

## 6. 최종 컴파일 및 관련 테스트

- `./mvnw -DskipTests test-compile`: **BUILD SUCCESS**
- `MasterChangeDetectionIntegrationTest`: **18/18 PASS** (MariaDB 포함)
- `MasterDiffControllerTest`: **3/3 PASS** (단일 multipart, 잘못된 확장자, 인증 없는 요청 차단)
- `MasterFieldChangePolicyTest`: **2/2 PASS**
- `Pw0105EquipmentInsertionEngineTest`: **1/1 PASS**
- 합계: **24 tests, failures 0, errors 0, skipped 0**
- 과거 Exit Code 137 위험으로 전체 Maven suite는 실행하지 않았습니다. 최종 결과는 위의 분리된 클래스별 테스트 기준입니다.

중간 반복에서 테스트 픽스처가 공식 마스터의 대형 calcChain을 포함해 POI에서 긴 처리 시간을 보인 원인을 확인했습니다. 이를 해결하기 위해 테스트 메모리 내 복사본에서만 calcChain part 및 참조를 제거한 후 편집하도록 변경했습니다. 공식 원본/DB BLOB은 수정하지 않았습니다. 초기 잘못된 assertion도 실제 baseline의 공란 flow 열 및 다중시트+코드 후보 구분에 맞게 조정했고, 최종 18건이 통과했습니다.

## 7. DrawingML ID 회귀 검증

실제 PW-01-05 공식 workbook의 별도 TEST_ONLY 출력으로 도면 편집 엔진을 재실행했습니다.

- 새 connector는 `drawing5.xml`의 기존 비-connector 원호가 사용 중인 **ID 159를 건너뛰고 160**을 받았습니다.
- 같은 drawing part의 모든 `xdr:cNvPr` ID 중복 없음. 원본 104개 drawing object의 식별자·유형·이름·geometry·화살표·shape binding 및 기존 connector ID 90의 anchor가 보존됐습니다.
- 기존 package part와 relationship은 허용된 worksheet/drawing/workbook XML 이외에 byte-identical.
- Apache POI workbook 재열기 및 connector anchor/arrow 위치 테스트 통과.
- 출력: `validation-results/2026-10-19-step6-1/PW-01-05-DRAWING-ID-REGRESSION-TEST-ONLY.xlsx`
- SHA-256: `e28394df335b94a3a088b581234d00cc9942cadae74d63bf156fd79461739f4a`
- 보존된 Step 5 `V4-DRAWING-IDFIX` SHA-256은 검증 전과 같은 `d7ebc9f43c6f0ac4e34992dc4a21e53f26676134187110db333e4f4b0e2f2235`입니다.

## 8. 공식 V1.0 무결성 및 DB 현황

해시 검증은 기존 고정 CSV report를 덮어쓰지 않도록 read-only 비교로 실행했습니다.

- 공식 XLSX: **16/16** 로컬 파일 = SHA256SUMS manifest = MariaDB metadata hash = MariaDB BLOB hash
- `전체 목록.xlsx` SHA-256: `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`
- MariaDB: baseline documents 16, equipment 1,332, process documents 15, process sheets 62
- `process_relation`: `CONFIRMED=0`, `REVIEW_REQUIRED=1`
- Flyway V12 기록: `success=1`, schema current version 12
- 실제 TEST_ONLY `change_run=171`은 별도 저장됐고 V1.0 원본 BLOB/equipment/mapping/relation은 바꾸지 않았습니다.

## 9. 미완료·기술 한계 및 Step 6-2 준비사항

- 실제 사용자가 수정한 마스터 XLSX는 제공되지 않아 업무상 변경 적합성 승인은 아직 아닙니다. 파일이 제공되면 같은 API로 새 TEST_ONLY run을 생성해 사람의 검토를 받아야 합니다.
- 브라우저 화면은 route/title/form 로딩만 확인했으며 실제 UI 업로드/결과 표시 상호작용은 미검증입니다. API-to-MariaDB 경로는 실제 multipart 업로드와 readback까지 확인했습니다.
- 공식 baseline의 폐수·폐기물 입력값은 비공란이 없어 실제 삭제 시나리오는 만들 수 없었고, 정책 단위 테스트만 합성 비공란 값으로 검증했습니다.
- 자동 매핑 boolean은 V1.0의 단일 기존 위치 증거 존재를 의미할 뿐 승인, 논리적 공정 관계, 공식 도면 수정 허가가 아닙니다. 모든 변경 영향은 수동 확인 대상입니다.
- **Step 6-2 착수 전:** 실제 변경본 제출, V1.0과 차이/영향 후보 담당자 확인, 공정도 시트 후보 승인, 기존 연결·전후 관계 근거와 별도 승인 절차 확정이 필요합니다. 본 작업은 Step 6-2를 시작하지 않았습니다.

## 10. 보존·커밋

- 공식 V1.0 원본 16개 및 Step 5 V5/V4/V4-DRAWING-IDFIX는 수정하지 않았습니다.
- Step 6-1 변경 파일 및 본 보고서·README·CHECKPOINT의 커밋은 보고 완료 시 함께 기록합니다.
