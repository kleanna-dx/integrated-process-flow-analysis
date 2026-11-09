# 통합공정도 자동화 — Step 6-2 최종 보고서

- 작성일: 2026-10-20
- 범위: Step 6-2 기존 구현·기록의 최종 문서화, 기존 TEST_ONLY 산출물 및 2026-10-20 구조 감사 결과 정리, Git 문서 정리
- 이번 문서화에서 새 기능 개발, Excel 생성, PDF 렌더링, 전체 도면 인덱스 분석, Maven 테스트, MariaDB 변경 및 Step 6-3 개발은 수행하지 않음
- 본 보고서의 이전 테스트·렌더링 결과는 각 실행 시점이 기록된 로그/감사 보고서 기준이며, 이번에 새로 실행한 결과로 표현하지 않음

## 1. 최종 판정 및 개발 목표

**Step 6-2는 TEST_ONLY 도면 편집 계획 생성·시험 파일 검증까지 완료**했다. Step 6-1에서 저장한 변경 마스터 업로드의 `changeRunId`/`changeItemId`를 참조해 설비 정보를 다시 확인하고, 대상 도면 및 삽입 계획을 검토한 뒤, 명시적 TEST_ONLY 설정이 있는 경우에만 별도 시험 XLSX를 생성하는 것이 목표였다. 시험 출력은 공식 V1.0 파일이나 승인된 공정 관계를 변경하지 않는다.

본 완료 판정은 계획·파일·구조 감사 결과를 뜻하며, 실제 업무상 공정 연결 승인이나 Microsoft Excel 네이티브 검증을 뜻하지 않는다.

## 2. Step 6-1 변경 마스터 업로드 연동 및 ID 173 분석

- Step 6-1의 `POST /api/admin/master-diff` 업로드 결과를 바탕으로 편집 계획 API `POST /api/admin/diagram-edit-plans`가 저장된 `changeRunId`와 `changeItemId`를 받아 사용한다. 계획 조회는 `GET /api/admin/diagram-edit-plans/{planId}`, 시험 파일 생성은 `POST /api/admin/diagram-edit-plans/{planId}/generate-test-only`, 저장 출력 재다운로드는 `GET /api/admin/diagram-edit-plans/{planId}/download`이다.
- 2026-10-20 기록의 TEST_ONLY 변경 분석은 `changeRunId=173`, `changeItemId=173`, 변경 유형 `ADDED`이다. 기준 1,332개 대비 업로드 1,333개이며 추가 1, 삭제·수정·이동 0건, 입력 검증 이슈 0건이다. `baselineModified=false`, 자동 매핑 성공은 false, 전체 mapping 상태는 `REVIEW_REQUIRED`다.
- change run 173은 편집 계획이 실제 업로드 결과를 참조하는 연결점이다. 편집 계획은 업로드된 `source_content`를 다시 파싱해 변경 설비 데이터를 연결했고, V1.0 baseline 도면에서 별도 시험 출력물을 생성했다.

## 3. 신규 설비 및 PLAN-5 생성 과정

신규 설비 `C-PW15020`의 업로드 행(1501) 및 계획 API 응답 필드와 PLAN-5/LABELFIX 출력 필드를 대조했다.

| 필드 | 업로드/계획/출력 값 |
|---|---|
| 관리번호 | `C-PW15020` |
| 설비명 | `SCREEN FILTER` |
| Item No. | `WW-F-AFT10` |
| 대분류 / 중분류 | `PW-01` / `PW-01-05` |
| 용량 / 단위 | `1,500` / `㎥/시` |
| 수량 | `1ea` |
| 폐수·폐기물 유형, 양, 이동처 | 입력 없음(공란 유지) |

기록된 절차는 TEST_ONLY 마스터 파일을 업로드해 ID 173을 만든 뒤, `changeRunId=173` 및 `changeItemId=173`을 사용해 편집 계획을 생성·조회하고 PLAN-5 시험 출력물을 저장/다운로드한 것이다. 명시한 TEST_ONLY 대상은 V1.0 `PW-01-05` 시트이며, 검토된 삽입 범위는 `AI198:AZ208`, 삽입 전 행은 198이다. 계획 응답은 시각적 기존 connector를 삽입 위치 검토 근거로만 기록했고, 그 선을 승인된 업무 관계로 해석하지 않았다. 충돌 분석은 `NO_CROSSING_OBJECTS_DETECTED`로 기록됐다. 업로드의 설비 필드와 PLAN-5/LABELFIX 출력은 일치하며, 비어 있는 유량·발생량·이동처를 추정해 채우지 않았다.

## 4. 승인 경계 및 TEST_ONLY 생성 제한

- C-PW15020과 다른 설비 사이에 업무상 근거가 확인되지 않은 공정 연결은 `REVIEW_REQUIRED`로 보류했다. 이 결과에서 새로 승인된 관계는 **0건**, 기존 검토 대기 관계는 **1건**이며, 계획 응답의 `officialApprovalCreated=false`다.
- `TEST_ONLY_READY`는 `TEST_ONLY_ARTIFACT_ONLY` 생성 범위에 대한 준비 상태일 뿐, 공정 관계 승인이나 공식 도면 수정 권한이 아니다. 별도의 TEST_ONLY 설정/시나리오가 없는 계획은 `REVIEW_REQUIRED`, `automaticEditAllowed=false`였고, 해당 계획의 생성 요청은 HTTP 409로 차단된 응답이 보존돼 있다.
- 구형 직접 업로드·편집 shortcut은 차단되어 있으며, 먼저 변경 마스터 업로드를 통해 run/item을 만든 뒤 계획 API에서 참조하는 경로를 사용한다.
- 시험 XLSX는 잠금 V1.0 도면을 기반으로 한 별도 산출물이다. 공식 원본 파일·DB 기준 BLOB 및 승인 관계에 쓰지 않았다.

## 5. PLAN-5와 LABELFIX — 최종 기준 산출물

Step 6-2의 최종 기준 시험파일은 표시 오류를 수정한 **LABELFIX**다. 원본 PLAN-5는 수정 전 시험 이력으로 보존한다.

| 구분 | 실제 경로 | SHA-256 | 역할 |
|---|---|---|---|
| 업로드용 합성 TEST_ONLY 마스터 | `validation-results/2026-10-19-step6-2/TEST_ONLY-master-change-C-PW15020.xlsx` | `487e68ecd59a1ea1859344508d4e88e95057ba3d1667d317162c4ac65d19c4d0` | changeRun 173 입력 |
| 원본 PLAN-5 | `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5.xlsx` | `e3fba34ec6f272a2067fb5b278ebf84038bf5694f5d2aeed8393456413a06837` | 표시 수정 전의 시험 이력. LABELFIX로 대체·덮어쓰지 않음 |
| **LABELFIX — Step 6-2 최종 기준** | `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx` | `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc` | 수정 후 최종 검증 대상 |
| Step 5 DRAWING-IDFIX | `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4-DRAWING-IDFIX.xlsx` | `d7ebc9f43c6f0ac4e34992dc4a21e53f26676134187110db333e4f4b0e2f2235` | 이전 단계 시험 기준. Step 6-2 기준 파일과 구분 |
| 공식 V1.0 원본 | `data/reference/` 아래 manifest의 XLSX 16개 | 16개 개별 해시는 §13 참조 | 절대 변경 불가 기준 |

최종 LABELFIX 다운로드: [LABELFIX XLSX](https://www.genspark.ai/api/files/s/o4sBljxF)  
PLAN-5와 LABELFIX의 확대 비교 이미지 다운로드: [PLAN5-vs-LABELFIX-detail-300dpi.png](https://www.genspark.ai/api/files/s/E4osvfyj)

위 다운로드 링크는 이번 보고서 작성 중 파일 업로드가 성공해 발급된 링크다. 링크 발급 사실을 확인했으나 별도 브라우저 세션/외부 계정에서의 다운로드는 확인하지 않았다.

## 6. TEST_ONLY / REVIEW_REQUIRED 표시 오류와 수정

기존 PLAN-5에서는 상태 문자열이 도형 안에 완전히 보이지 않고, 설비 제목 줄 우측에서 `... / REVIEW_`처럼 잘렸다. LABELFIX에서는 셀 `AV198`의 상태 문구를 공백으로 되돌리고, 별도 DrawingML 상태 도형(ID 161)에 2줄로 `TEST_ONLY /` 및 `REVIEW_REQUIRED`를 표시했다. 두 상태가 읽히는 별도 배너로 분리되어 TEST_ONLY와 검토 대기 경계를 동시에 명확하게 표현한다.

구조 감사 비교 결과 PLAN-5와 LABELFIX 사이에서 바뀐 OOXML package part는 의도된 `xl/worksheets/sheet5.xml` 및 `xl/drawings/drawing5.xml`뿐이다. 기존 다른 셀 값·스타일은 동일했다.

## 7. PDF 및 300dpi 이미지 검증 기록

- 기존에 생성된 PLAN-5 PDF는 `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5.pdf`, 32페이지 A3다. SHA-256: `f52082b26936067effea52fe3e957f872420571c22fe4a9f2bf586935ff6aaac`.
- 기존 LABELFIX PDF는 `validation-results/2026-10-19-step6-2/label-fix-render/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.pdf`, 32페이지 A3다. SHA-256: `9bb7864ca1eb81ff53526706f8cfe74c17ca25e4c4c7520ac10572f9957b51f5`.
- 기존 300dpi 비교 산출물은 `label-fix-render/PLAN5-vs-LABELFIX-detail-300dpi.png` (SHA-256 `d9d0572fe8dbba90510a52a22657e4751668ac6fdf614a74185f97ad138b9e1b`) 및 `PLAN5-vs-LABELFIX-page27-300dpi.png` (SHA-256 `a548860c292c3eaa0717d5235c827cd501c11dce4b9450725f6fc52b76f4f91d`)이다. 수정 전·후 비교 이미지에서 도면 내용은 유지되고 별도 상태 라벨이 읽히도록 수정된 것을 확인했다.
- 이번 문서화 작업에서는 PDF를 다시 렌더링하거나 전체 페이지 픽셀 비교를 재수행하지 않았다. 이번에 한 것은 기존 PDF의 페이지 수·A3 페이지 크기 확인 및 기존 비교 이미지 확인이다. 나머지 페이지 pixel-identical 여부를 이번에 새로 검증한 것으로 주장하지 않는다.

## 8. DrawingML 및 시트 구조 보존 — 2026-10-20 구조 감사 결과

구조 감사 보고서 `STEP6_2_STRUCTURAL_AUDIT.md`에서 기록된 PLAN-5/LABELFIX 검증은 다음과 같다.

- Apache POI 재열기: 양 파일 성공, 각 6개 시트 인식.
- `PW-01-05` 병합 영역: 85개, 범위 완전 동일.
- 기존 DrawingML 객체: `drawing5.xml` 104개에서 LABELFIX 105개. 원본 객체 104개의 ID·유형·이름·앵커·화살표 정보 보존.
- 6개 DrawingML part 각각에서 `xdr:cNvPr` ID 중복 0건. 연결 relationship 및 참조 dangling ID 없음.
- 인쇄영역 `'PW-01-05'!$A$1:$BJ$230`, `pageSetup`, `pageMargins`, `printOptions`, `sheetViews`, 행·열 페이지 나눔 보존.
- PLAN-5와 LABELFIX 간 의도하지 않은 셀 값, 도형, 병합, 스타일 변경은 감사에서 확인되지 않음.

이 항목들은 2026-10-20에 완료된 구조·기준자료 감사 결과다. 이번 보고서 작성에서는 해당 대규모 검사를 반복 실행하지 않았다.

## 9. 테스트 이력과 실행 시점

다음은 기존 로그에 성공으로 남아 있는 기록이다. **이번 최종 문서화 턴에서는 Maven 테스트를 실행하지 않았다.**

| 시점(UTC) | 기존 실행 | 결과 |
|---|---|---|
| 2026-10-20 00:24:45 | `Pw0105EquipmentInsertionEngineTest` | 2/2, 실패·오류·skip 0, BUILD SUCCESS (`pw0105-engine-tests.log`) |
| 2026-10-20 00:26:22 | `./mvnw -DskipTests test-compile` | BUILD SUCCESS (`test-compile.log`) |
| 2026-10-20 00:31:26 | `DiagramEditPlanIntegrationTest` | 1/1, 실패·오류·skip 0, 테스트 소요 292.8초, BUILD SUCCESS (`diagram-edit-plan-integration.log`) |
| 2026-10-19 Step 6-1 | 분할 실행 테스트 4개 클래스 | 24/24, 실패·오류·skip 0. 세부 클래스별 결과는 `STEP6_1_FINAL_REPORT.md` 참조 |

전체 Maven 테스트 suite는 Step 6-2에서도 실행하지 않았다. 이 보고서는 실행하지 않은 테스트를 통과로 표기하지 않는다.

## 10. 공식 V1.0 원본 16개 무결성

2026-10-20 구조 감사에서 로컬 파일·`SHA256SUMS`·MariaDB `baseline_document` 메타데이터 SHA-256·MariaDB BLOB SHA-256이 16/16 모두 일치했다. DB의 V1.0 release는 `locked=1`; 기준 마스터 hash는 `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`다. 이 DB 비교 결과는 감사 기록이며, 이번 문서화에서는 MariaDB에 접속하거나 변경하지 않았다.

이번 로컬 확인에서 `data/reference/SHA256SUMS`와 16개 파일 SHA-256을 대조했다. 공식 파일은 다음과 같고, 각 해시는 manifest 및 `data/reference/<파일명>` 기준이다.

| 공식 V1.0 파일 | SHA-256 |
|---|---|
| `P-01 제지2호기공정 ★변경허가 REV3.1.xlsx` | `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391` |
| `P-02 제지3호기 공정 ★변경허가 REV3.xlsx` | `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76` |
| `P-03.xlsx` | `5394aaff9a9f2161a77c69a31fee88826c94de14b4672272570607909868f1fa` |
| `P-04 화장지 생산 공정 ★변경허가 REV3.xlsx` | `9c8fa4b4bcf0dd89cd7ee15251dd31ed8bc8415209b2b27b89ef028fc7e0bb4a` |
| `P-05.xlsx` | `ddebdabbd5bdd8cd3f09b58934b5935de1456c15d337b5bc7f1baf78efaa97ed` |
| `P-06.xlsx` | `d5f17ca9b6246c97a088e7e996dc3120ab38ba4c110b48852ccefb21114f6159` |
| `P-07.xlsx` | `26f11333706c2d236a51f1c5d66eca91d836bbb9d5bf05817ac08c2013ef5466` |
| `P-08.xlsx` | `0564f5f7671fa310944eba0fee0216993b72ded8bdac336e31f42814d1d9f275` |
| `PU-01 용수제조공정 ★변경허가.xlsx` | `ac5500ea6d269026284bd5141ac133ccb72d5ff62348fdecf3a309a660c00f9d` |
| `PU-02 스팀생산 및 공급공정 ★변경허가.xlsx` | `6a4b12df642cca57a08d94a906635a90b7ed20a989bc85c21f547dc33666409b` |
| `PU-03 원료 및 사용물질 저장공정 ★변경허가 REV1.1.xlsx` | `d0b9a30256af3662336f097586bcb2d04e21ee0e4a8f2a5614f8c10dec240e61` |
| `PW-01 폐수처리공정 ★변경허가.xlsx` | `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0` |
| `PW-02 폐기물 소각 및 보관공정 ★변경허가.xlsx` | `2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a` |
| `PW-02-05 폐합성 소각 공정 ★변경허가.xlsx` | `ab385ebd92d53f76b33cebff3c8faa433b9b24d9a9b4b9b91a049fda219c9620` |
| `PW-03.xlsx` | `647ea78e8466165bc7f7fc97bb91ef0fed3700222b51475f99884e7b40a93134` |
| `전체 목록.xlsx` | `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4` |

공식 V1.0 원본은 이 작업에서 수정하거나 Git에 추가하지 않았다.

## 11. 기준 설비·공정도 인벤토리와 관계 상태

2026-10-20 구조 감사 DB 결과 기준으로 기존 **설비 1,332개, 공정도 파일 15개, 시트 62개**가 보존됐다. 기준 관계는 `CONFIRMED=0`, `REVIEW_REQUIRED=1`이다. changeRun 173의 신규 설비는 업로드 비교 분석과 별도 시험 산출물에만 반영되었으며, V1.0 baseline equipment, mapping, relation을 바꾸지 않았다.

## 12. 실행 제한, 미검증 및 잔여 사항

- Step 6-2 중 전체 인덱스/도면 분석을 반복하는 통합 경로는 300초 실행 제한에 여러 차례 근접하거나 제한으로 중단된 기록이 있다. WIP 체크포인트에는 반복적인 전체 workbook mapping/index 분석 중 timeout된 실행 기록이 보존돼 있다. 한편 최종 기록의 `DiagramEditPlanIntegrationTest`는 292.8초로 실제 완료되어 300초 제한에 매우 근접했다. 이 작업의 원인을 코드 결함으로 단정할 근거는 없으며, 실행 시간/요청당 반복 스캔은 기술적 제한으로 남는다. 재실행·최적화는 이번 범위에서 하지 않았다.
- 테스트 중 `.env` 미적용으로 DB 접속이 거부된 별도 시도 기록이 있다. 이는 이후 `.env`를 올바르게 로드한 실행과 분리해 기록하며, 테스트 성공으로 혼합하지 않는다.
- 실제 브라우저에서 최종 사용자가 파일을 선택·업로드하고 결과를 확인하는 end-to-end 경험은 미검증이다. Step 6-1의 form 로딩 및 API 검증도 실제 사용자 업로드 확인과 동일하지 않다.
- Microsoft Excel 네이티브에서 열기·편집·인쇄 및 렌더링은 미검증이다. PDF는 기존 LibreOffice 렌더 산출물이다.
- 실제 업무 변경 마스터가 아니라 합성 TEST_ONLY 마스터를 썼으므로 업무 사실 검증은 아니다. C-PW15020의 위치·연결·유량·이동처 및 공정 관계는 담당자 확인이 필요하다.
- Flyway/MariaDB 환경에서 MariaDB 11.8 지원 범위 경고가 있었던 점과 DB를 읽기 전용으로만 다룬 이번 정리 범위를 유지한다. DB 변경·재검증은 하지 않았다.

## 13. Step 6-3 준비사항 (개발 미착수)

다음 단계의 목표는 **영향받는 도면만 별도 TEST_ONLY 수정본으로 생성하고, 필요한 파일만 선택 다운로드**할 수 있게 하는 것이다. Step 6-3 개발은 이번에 시작하지 않았다. 착수 전에는 다음 기준을 유지한다.

1. 변경된 설비에서 실제 영향받는 도면/시트만 선별하고, 불확실한 mapping은 `REVIEW_REQUIRED`로 유지한다.
2. 원본 V1.0을 변경하지 않고 영향 도면의 TEST_ONLY 복사본만 만든다.
3. 근거 없는 관계를 만들지 않으며 명시적 TEST_ONLY 범위와 승인 경계를 보존한다.
4. 수정된 도면만 선택 다운로드하는 API/UI 경로를 마련하고, 기존의 change run/item → edit plan 연결을 활용한다.
5. 전체 도면 인덱스를 요청마다 되풀이하는 비용과 300초 실행 제한을 고려해 영향 도면 선별·캐시/비용 구조를 설계한다.
6. 파일 단위 구조검증, 이전 도형·인쇄설정 보존, 다운로드 결과 hash 및 브라우저 업로드/다운로드 UX를 별도로 검증한다.
7. Microsoft Excel 네이티브 검증과 실제 담당자 업무 검토는 자동 구조검증과 분리해 수행한다.

## 부록 A. 감사 및 산출물 위치

- 구조 감사 보고서: `STEP6_2_STRUCTURAL_AUDIT.md` (2026-10-20, 읽기 전용 구조·기준자료 감사)
- 최신 시험파일: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`
- PLAN-5 및 LABELFIX PDF와 비교 이미지: `validation-results/2026-10-19-step6-2/` 및 `label-fix-render/`
- 기존 시험 로그와 API 응답 기록: `validation-results/2026-10-19-step6-2/`
- 공식 기준 manifest: `data/reference/SHA256SUMS`
