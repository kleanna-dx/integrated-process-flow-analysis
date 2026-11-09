# 통합공정도 자동화 — Step 6-3A 최종 검증 보고서

- 검증일: 2026-10-20
- 범위: 저장된 Step 6-1 분석·Step 6-2 편집 계획을 이용한 영향 도면 선별, 파일별 선택 편집 실행계획 생성, 다중 변경 항목 grouping 검증 및 선택 테스트
- 제외: 다중 XLSX 생성, ZIP 생성·다운로드, 공식 도면 수정, 전체 DrawingML 인덱스 재분석, 전체 Maven suite, PDF 렌더, Step 6-3B 개발
- 최종 시험파일은 Step 6-2 LABELFIX이며, Step 6-3A에서는 이를 다시 열어 편집하거나 생성하지 않음

## 1. 최종 판정

**Step 6-3A 영향 도면 선별 및 실행계획 생성 기능의 지정 범위 검증을 완료했다.** 기존 변경 run/item 및 TEST_ONLY plan의 저장 자료를 재사용하고, 잠금 V1.0의 기준 파일 해시·시트 인벤토리를 확인한 뒤, 사용자가 명시적으로 선택한 검증 완료 TEST_ONLY plan만 도면 파일별 출력 계획에 넣는다. 이번 API는 읽기 전용 실행계획만 반환한다. 파일 생성·ZIP·공식 도면 편집·관계 승인은 수행하지 않는다.

## 2. 구현 API 및 서비스

### API

- `POST /api/admin/change-runs/{changeRunId}/selective-edit-plan`
- `X-Admin-Token`을 요구한다. body는 선택적인 `changeItemIds`와 `testOnlyPlanIds`를 받는다. 항목 목록을 생략하면 해당 run의 모든 변경 항목을 대상으로 하지만, TEST_ONLY plan ID를 선택하지 않은 후보는 출력 대상이 되지 않는다.
- 응답에는 run/항목 수, 후보 도면 파일·시트, 파일별 변경 설비, 기존 edit plan ID, 유효성·검토·보류 항목, 예상 파일명, `REVIEW_REQUIRED` 관계 경계, baseline fingerprint, 각 단계 및 전체 소요시간이 포함된다.
- 응답상 `fileGenerationStarted=false`, `zipGenerationStarted=false`, `officialEditAllowed=false`다. 이 endpoint는 DB에 실행계획이나 산출물을 쓰지 않는다.

### 서비스 동작

- `SelectiveEditExecutionPlanService`가 기존 `change_run`, `change_item.affected_diagrams_json`, 선택된 `diagram_edit_plan`을 읽는다. 신규 마스터 업로드나 기존 run의 재계산을 하지 않는다.
- 기존 test plan은 해당 `changeRunId`에 속하고, 선택한 item에 속하며, `ADDED`, `TEST_ONLY`, `TEST_ONLY_READY`, `automatic_edit_allowed=true`, `relation_status=REVIEW_REQUIRED`, `requested_scenario.enabled=true` 조건을 만족해야 한다. plan에 저장된 process/file/sheet와 현재 run의 도면 후보, V1.0 등록 파일·시트, 파일 SHA-256이 일치해야 출력계획 대상으로 인정한다.
- 공식 V1.0 master BLOB 해시, 15개 process workbook의 순서화된 metadata SHA-256 fingerprint, 총 62개 등록 sheet 및 파일별 sheet count 일치를 검사한다. 선택 후보 파일의 DB BLOB SHA-256·byte size 및 저장 sheet inventory는 실제 값으로 재검증한다.
- 계획 분류는 `A_TEST_ONLY_PLAN_READY`(선택·검증된 TEST_ONLY plan에 한정), `B_REVIEW_REQUIRED`(후보는 검증됐으나 선택 plan이 없음), `C_AUTOMATIC_EDIT_UNAVAILABLE`(V1.0 파일/시트 근거가 없거나 해시·파일·매핑 불일치)로 구분한다. A는 시험 산출물 계획 자격일 뿐 공식 편집/승인이 아니다.
- `SelectiveExecutionPlanGrouping`은 하나의 changeRunId 안에서 동일 workbook filename을 한 output group으로 묶고 그 파일의 여러 대상 sheet, item 및 plan ID를 보존한다. 서로 다른 run ID의 target이 들어오면 거부한다. 선택되지 않은 시트·항목과 미검증 후보는 output sheet에 넣지 않는다.
- DB 조회/그룹화 기반 처리로 변경 항목·plan을 생성할 때 15개 workbook 전체 DrawingML 분석을 반복하지 않는다. SQL은 저장된 change analysis와 sheet index를 사용하고, 후보 파일 BLOB만 한 번씩 해시 검증한다.

## 3. run 173 / PLAN-5 결과

DB의 기존 `changeRunId=173`, `changeItemId=173`, `diagram_edit_plan.id=5`를 재사용했다. 변경 마스터 업로드나 Excel 생성 API는 다시 호출하지 않았다.

| 조건 | 실행계획 결과 |
|---|---|
| run 173에서 item 173을 선택하고 TEST_ONLY plan 미선택 | 도면 후보 6시트/파일 1개를 표시, 출력 파일 0개, 실행계획 ready=false |
| 기존 plan 2 (`REVIEW_REQUIRED`, 자동 편집 허용 false) 선택 | 유효 plan으로 인정하지 않음, 출력 파일 0개 |
| 기존 plan 5를 명시적으로 선택 | PW-01 Excel 파일 1개, 출력 대상 시트 `PW-01-05` 1개, plan ID 5 |
| 대상 밖의 PW-01 파일 후보 시트 | 01·02·03·04·06은 review 후보로 유지, 출력 sheet에서 제외 |
| 다른 공식 공정도 | 14개 파일 모두 출력 대상에서 제외 |

run 173의 도면 후보는 `PW-01` 파일 내 `PW-01-01`–`PW-01-06`의 process-code-only 후보 6건이지, 수정 대상 6개 파일이 아니다. plan 5의 명시된 대상만 출력 계획으로 선택된다. 기존 plan 5의 출력 hash는 `e3fba34ec6f272a2067fb5b278ebf84038bf5694f5d2aeed8393456413a06837`로 확인됐으며, plan 5를 재생성하지 않았다.

실제 업무 연결은 계속 `REVIEW_REQUIRED`이고 승인/CONFIRMED 관계는 0건이다. plan 5의 endpoint 값은 TEST_ONLY fixture로 취급했고 승인 관계로 전환하지 않았다.

## 4. 다중 변경 항목 DB 통합 검증

`SelectiveEditExecutionPlanIntegrationTest`의 별도 `@Transactional` TEST_ONLY 픽스처에서만 검증했다. 공식 설비·공식 mapping·관계는 입력이나 수정하지 않았으며, 픽스처 run/item/plan 삽입은 테스트 트랜잭션 종료 시 rollback됐다.

- 동일 PW-01 workbook에 TEST_ONLY item/plan 두 건(PW-01-05 및 PW-01-06)을 주어도 **출력 파일 group은 1개**이며, 파일 안 출력 시트는 `PW-01-05`, `PW-01-06` 각각으로 유지됨.
- 다른 `P-01` workbook의 명시적 plan은 별도 파일 group 1개로 반환됨. 총 출력 계획은 2개 파일(PW-01, P-01)이며, 각 파일은 해당 run용 한 번의 예상 산출물로 그룹화됨.
- PW-01에 후보가 있지만 plan을 선택하지 않은 item은 `B_REVIEW_REQUIRED`, `testOnlyArtifactEligible=false`로 출력 group의 변경 항목 목록에만 남고 자동 실행 대상에 편입되지 않음.
- 등록되지 않은 `NOT-IN-V1.xlsx` 후보는 `C_AUTOMATIC_EDIT_UNAVAILABLE`이고 `includedInOutput=false`; 검증되지 않은 파일은 예상 출력 대상이 되지 않음.
- 다른 run의 item ID를 run 1 요청에 넣으면 거부됨. 다른 run의 plan ID를 넣어도 run별 SQL 조회에서 거부됨. grouping 경계에서도 서로 다른 run ID를 섞는 입력을 거부함.
- 동일 plan ID를 두 번 선택한 요청은 positive/unique 검증에서 거부됨. 동일 change item에 복수의 서로 다른 plan을 지정한 경우도 하나만 허용하고 나머지는 invalid/stale로 기록하도록 구현돼 있음.
- 테스트 종료 후 synthetic run 파일명 2건의 잔존 count는 0이며, V1.0 설비 수·관계 수·master hash는 테스트 전후 동일했다.

## 5. 실제 timing 및 300초 제한

2026-10-20 05:49 UTC 최종 코드 targeted test 로그에서 실제 측정했다. `timingMs` 값은 서비스의 단계별 측정이고, 0ms는 밀리초 단위 반올림으로 1ms 미만일 수 있다.

### run 173 / item 173 / PLAN-5 서비스 직접 호출

- API 서비스 전체: **66 ms**
- 저장 분석 결과 조회: run 9 ms + item/plan 2 ms
- source 및 locked V1.0 baseline 검증: 29 ms
- 저장된 도면 매핑·파일 BLOB SHA-256·시트 검증: 8 ms
- 선택 TEST_ONLY 계획 검증: 11 ms
- 변경 항목 분류: 0 ms
- 도면 파일/시트 그룹화 및 계획 대상 구성: 3 ms
- 실행계획 응답 작성: 0 ms

### HTTP API 경로 (MockMvc + 로컬 Spring service)

- 전체 API 호출 wall time: **178 ms**
- 서비스 내부 총 시간: **43 ms**
- 단계별: run 조회 14 ms, baseline/source 검증 20 ms, item/plan 조회 2 ms, 후보 매핑·해시 검증 3 ms, TEST_ONLY plan 검증 2 ms, 분류·그룹화·응답 작성 각 0 ms(반올림).

이는 로컬 MockMvc/Spring 테스트 환경의 값이며 브라우저/운영 네트워크 지연을 의미하지 않는다. 응답에서 `reusedPersistedChangeAnalysis=true`, `reusedPersistedWorkbookSheetInventory=true`, `drawingMlReanalysisPerformed=false`를 확인했다. run 173 실행에서 저장된 분석 결과와 기존 인덱스를 재사용해 전체 15개 workbook/62개 sheet DrawingML 재분석은 실행하지 않았다. 따라서 저장 run 기반 Step 6-3A 경로에서 해당 재분석을 회피하는 것은 확인했지만, 새 master-diff 업로드의 초기 `DiagramImpactAnalysisService` cold-index는 그대로 남아 있다. 300초 제한의 근본 해소나 cold-index 소요시간은 이번에 측정하지 않았으며 전체 인덱스 재분석도 재실행하지 않았다.

## 6. 선택 테스트 결과

최종 실행 명령은 다음 네 테스트 클래스만 지정했다.

`./mvnw -Dtest=SelectiveExecutionPlanPolicyTest,SelectiveExecutionPlanGroupingTest,SelectiveEditExecutionPlanControllerTest,SelectiveEditExecutionPlanIntegrationTest test`

| 테스트 클래스 | 테스트 수 | 결과 |
|---|---:|---|
| `SelectiveEditExecutionPlanIntegrationTest` | 2 | PASS |
| `SelectiveExecutionPlanGroupingTest` | 1 | PASS |
| `SelectiveExecutionPlanPolicyTest` | 2 | PASS |
| `SelectiveEditExecutionPlanControllerTest` | 2 | PASS |
| **합계** | **7** | **Failures 0, Errors 0, Skipped 0** |

이번 최종 실행은 `BUILD SUCCESS`, Maven 총 시간 8.487초, 종료 시각 `2026-10-20T05:49:15Z`다. `validation-results/2026-10-20-step6-3a/final-targeted-tests.log`에 run 173 service/API 단계 timing이 기록돼 있다. API 인증 누락 401과 유효 토큰 요청 200, TEST_ONLY 계획 선택 및 REVIEW_REQUIRED 정책도 지정 테스트에서 검증했다. 전체 Maven suite, PDF 렌더, 전체 DrawingML 인덱스 재분석은 실행하지 않았다. 생성 XLSX/ZIP 테스트도 의도된 범위 밖이다.

이전에 filename 기대값 assertion 수정과 테스트 컴파일 보정이 있었으나 최종 코드 대상으로 실행한 위 7건이 모두 통과했다. 이 결과와 이전 중간 실패를 혼합해 실패가 없었다고 표현하지 않는다.

## 7. V1.0 및 Step 6-2 기준 보존

기존 `scripts/verify-baseline-hashes.py`를 사용했다. 기존 검증 결과를 보존하고 새 파일 `validation-results/2026-10-20-step6-3a/final-baseline-sha256-verification.csv`에 출력하도록 실행 시 output 경로를 override했다.

- 공식 V1.0 local XLSX 16/16 = `SHA256SUMS` 16/16 = MariaDB `baseline_document.sha256` 16/16 = MariaDB BLOB SHA-256 16/16 일치.
- MariaDB 읽기 전용 재조회: baseline documents 16, equipment 1,332, process workbook 15, process sheets 62, `CONFIRMED=0`, `REVIEW_REQUIRED=1`.
- master release hash는 `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`로 확인. 테스트 이후 synthetic test run은 잔존하지 않았고 run 173/plan 5는 그대로 존재한다.
- Step 6-2 LABELFIX 경로: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`; SHA-256 `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc`. 변경·재생성하지 않았다.
- DBA read-only 조회와 baseline verifier 외에 MariaDB 변경이나 migration은 수행하지 않았으며 schema version 13 유지.

## 8. 파일 및 실행계획 경계

- 출력 파일 예상명은 `TEST_ONLY-<processCode>-CHANGE-RUN-<changeRunId>.xlsx` 규칙으로 계획 응답에만 표시된다. run 173의 대상은 `TEST_ONLY-PW-01-CHANGE-RUN-173.xlsx`로 예상되나, 실제 파일은 생성되지 않았다.
- 하나의 official workbook에 선택된 여러 변경 항목이 있으면 파일 1개로 묶고, 승인된 개별 TEST_ONLY plan이 있는 시트만 outputSheets에 넣는다.
- 실제 생성·압축·선택 다운로드·공식 파일 편집은 미구현/미수행이다. Visual connector 및 test fixture의 연결점은 업무상 승인 근거가 아니다.

## 9. Step 6-3B 준비사항

다음 단계에서는 본 실행계획의 `outputTargets`를 받아 선택된 official workbook별 TEST_ONLY 수정본을 한 번만 생성하고, 해당 workbook 안의 명시적으로 허용된 시트만 편집해야 한다. 실제 다중 XLSX 생성 전에는 다음을 준비한다.

1. 파일별 입력 plan 간 target sheet·행 범위 충돌 검사 및 동일 도면 내 다중 삽입 순서 확정.
2. 여러 계획을 동일 원본 workbook에 적용하는 transactional file pipeline과 생성 전·후 SHA-256 보존 확인.
3. `REVIEW_REQUIRED` 및 미검증 후보가 하나라도 포함된 경우 해당 항목을 제외/보류하는 기본 정책.
4. 파일별 재생성 멱등성, 기존 plan5/OUTPUT artifact hash와 새 run 산출물을 혼동하지 않는 versioned naming.
5. 선택 대상만 내려받는 API/UI 및 브라우저 interaction 테스트; ZIP은 별도 후속 범위로 명시적으로 승인받기 전까지 추가하지 않음.
6. V1.0 절대 불변 및 승인 관계 미생성 원칙, 새 master diff에서 cold-index 300초 잔여 위험의 별도 해소·측정.

이번 완료는 실행계획 단계에 한정된다. Step 6-3B는 시작하지 않았다.

## 부록 A. 검증 자료

- 최종 선택 테스트: `validation-results/2026-10-20-step6-3a/final-targeted-tests.log` (7/7 PASS)
- V1.0 integrity 결과: `validation-results/2026-10-20-step6-3a/final-baseline-sha256-verification.csv` (16/16 PASS)
- 최종 재조회: 기준문서 16, 설비 1,332, 공정도 15파일/62시트, CONFIRMED 0, REVIEW_REQUIRED 1, fixture run 0, schema 13.
- 기존 원본 분석과 edit plan: `changeRunId=173`, `changeItemId=173`, `diagram_edit_plan.id=5`
- Git 착수 시 제공 기준: `6deba2a638fe700af4fd59b595d20f4fabcc35f2`
- 실제 착수 당시 main HEAD: `1a4613784dc71e906060ef91436bbe2b27d28a10` (`genspark auto-backup`). 그 커밋은 이미 앞선 Step 6-3A API·서비스·기본 테스트 8개 파일을 포함하고 있었으며, 이 작업은 해당 이력 위에서 검증을 보강했다.
