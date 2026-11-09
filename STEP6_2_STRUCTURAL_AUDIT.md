# Step 6-2C-2 구조 및 기준자료 무결성 감사

- 검증 범위: 읽기 전용 점검 및 이 감사 보고서 작성
- 기준 커밋: `54f6eeea6a313b9f43890a485254f36596dd2f73`
- Maven 테스트, 도면 재생성, PDF 변환/렌더링, 기준 도면 전체 인덱스 재분석은 실행하지 않음

## 1. Git 브랜치 및 WIP 비교

- 검증 시작 시 현재 브랜치 `main`, HEAD `54f6eee` (`Fix TEST_ONLY status label clipping in PW-01-05`), tracked working tree clean. `main`은 `origin/main`보다 29개 커밋 앞서 있었음.
- `step6-2-wip-20261020`은 커밋 `a8a41ec`를 가리키며, `main`과 공통 부모 `1cc2fae` 이후 갈라진 형제 브랜치임. WIP 고유 커밋의 변경 파일은 `CHECKPOINT.md` 한 개뿐임.
- WIP 체크포인트 파일은 현재 `main`에 없음. 해당 문서는 WIP 브랜치에서 보존되어 있으며, 본 작업에서 병합하거나 수정하지 않음.
- WIP와 `main`의 나머지 엔진/테스트 차이는 LABELFIX 전 상태와 현재 LABELFIX 간 차이임. Step 6-2 엔진·API의 기반 변경은 공통 이력에 있고, `main`은 최신 LABELFIX 코드를 포함함. 누락된 추가 Step 6-2 코드 커밋이나 충돌은 확인되지 않음. WIP 체크포인트를 `main`에 옮기지 않았음.

## 2. PLAN-5 및 LABELFIX 구조 검증

실제 파일 경로:
- PLAN-5: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5.xlsx`
- LABELFIX: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`

검증 결과:

| 항목 | 결과 |
|---|---|
| Apache POI 재열기 | PLAN-5와 LABELFIX 모두 성공, 6개 시트 인식 |
| `PW-01-05` 병합 영역 | 양 파일 85개, 범위 완전 동일 |
| 설비 필드 | 관리번호 `C-PW15020`, 설비명 `SCREEN FILTER`, Item No. `WW-F-AFT10`, 대/중분류 `PW-01` / `PW-01-05`, 규격 `1,500㎥/시`, 수량 `1ea` 일치 |
| 상태 표시 | DrawingML 도형 ID `161`; `TEST_ONLY /` 및 `REVIEW_REQUIRED` 두 줄 모두 있음. 해당 상태 문자열은 더 이상 셀 값이 아님 |
| 기존 셀 값 | 유일한 worksheet 셀 값 변경은 `AV198`: 상태 문구 → 공백. 나머지 셀 값/스타일 동일 |
| DrawingML 도형 | `drawing5.xml` 객체 104 → 105. 기존 객체 104개의 ID·유형·이름·앵커·화살표 정보가 모두 동일 |
| cNvPr ID | 6개 DrawingML part 모두 part별 ID 중복 없음. PLAN-5와 LABELFIX 모두 통과(원호 포함 모든 `xdr:cNvPr` 검사) |
| 연결/관계 참조 | 내부 drawing package relationship 및 `stCxn`/`endCxn` 참조의 dangling ID 없음. 이 파일들에는 해당 connector shape-binding 참조 자체가 없음 |
| 인쇄/페이지 | 인쇄영역 `'PW-01-05'!$A$1:$BJ$230`, 병합, pageSetup, pageMargins, printOptions, sheetViews, row/column breaks 모두 보존 |

PLAN-5와 LABELFIX에서 달라진 OOXML package part는 의도한 `xl/worksheets/sheet5.xml`과 `xl/drawings/drawing5.xml` 두 개뿐임. 기존 병합 범위와 다른 설비·유량·이동처 값은 변경되지 않았음. PLAN-5 원본 SHA-256은 `e3fba34ec6f272a2067fb5b278ebf84038bf5694f5d2aeed8393456413a06837`로 그대로 유지. LABELFIX SHA-256은 `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc`.

## 3. 공식 V1.0 기준자료 및 DB

읽기 전용 DB transaction에서 확인했으며, 변경/승인 쿼리는 수행하지 않음.

- `data/reference/SHA256SUMS` 대조: 공식 XLSX 16/16 `OK`.
- MariaDB `baseline_document`: 16건(마스터 1, 공정도 15). 각 행의 `sha256` 및 `content_bytes` SHA-256이 local manifest와 모두 일치(메타데이터/LOB 16/16).
- V1.0 release 잠금: `locked=1`; master SHA-256 `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`.
- 기준 설비 1,332건, 공식 공정도 15개, workbook sheet 62개.
- 관계: `CONFIRMED=0`, `REVIEW_REQUIRED=1`.
- 기준자료 불일치 없음. 원본 파일·DB 기준 자료는 수정하지 않음.

## 4. changeRun 173 및 편집계획 대조

- DB `change_run=173`: `TEST_ONLY`, 업로드 파일 SHA-256과 `source_content` SHA-256 모두 `487e68ecd59a1ea1859344508d4e88e95057ba3d1667d317162c4ac65d19c4d0`; baseline 1,332 / upload 1,333; ADDED 1, 나머지 변경 0; mapping `REVIEW_REQUIRED`, 자동 매핑 false.
- change item 173은 `C-PW15020`, `ADDED`, source row 1501. 업로드 마스터 실제 행, 응답의 `afterValues`, 저장 편집계획, LABELFIX 출력의 관리번호·설비명·Item No.·용량/단위·수량이 일치함.
- 최종 편집계획 ID 5의 상태는 `TEST_ONLY_READY`, 허용 범위 `TEST_ONLY_ARTIFACT_ONLY`, `relation_status=REVIEW_REQUIRED`, `officialApprovalCreated=false`. 공식 승인은 생성되지 않음.
- DB의 계획 5 XLSX BLOB과 로컬 PLAN-5의 SHA-256은 모두 `e3fba34e…`이며 바이트 및 OOXML package part가 일치. 이전의 별도 계획 ID 1 및 차단된 계획 ID 2도 별개 기록으로 유지됨.
- LABELFIX는 PLAN-5를 덮어쓰지 않은 별도 파일. 공식 V1.0 도면과 기준 마스터, 승인 관계는 변경되지 않음.

## 5. 최종 판정 및 잔여 사항

- 구조/ID/앵커/인쇄 설정, 공식 기준 16개 해시 및 DB 기준자료 무결성 검증: **통과**.
- 예상하지 못한 OOXML 변경 또는 기준자료 불일치: **없음**.
- 이 감사 범위에서 Step 6-2 완료를 막는 구조·무결성 문제: **없음**.
- WIP `CHECKPOINT.md`는 `main`에 포함되지 않았으며 병합하지 않음(다음 지시에서 Git 정리). 본 보고서는 새로 작성되어 현재 Git에서 untracked 상태이며, 요청에 따라 별도 커밋하지 않음.
- 요청 범위대로 Step 6-2 최종 보고서 작성, Git 정리, Step 6-3은 수행하지 않음.
