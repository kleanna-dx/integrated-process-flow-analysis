# 작업 체크포인트 — 2026-10-16 (1차 테스트 검증 완료)

## 진행 범위 및 상태
- 기존 Java 17 + Spring Boot + MariaDB 프로젝트와 1차 기능을 유지. `PW-01 / PW-01-05 / connector shape ID 149` 후보 한 건을 검증함.
- 1차 테스트 클래스 6개를 별도 Maven 실행으로 모두 통과시킴. 총 21 tests, failures 0, errors 0, skipped 0; 자세한 실행 기록은 아래에 있음.
- 2차 개발, 임의 승인, 외부 배포는 시작하지 않음.

## 확인된 완료 사항
- 로컬 Spring Boot 서버는 포트 3000에서 실행 중이고 `/api/health`는 HTTP 200 및 정상 상태를 반환함.
- 관리자 API의 단일 connector 분석 요청은 HTTP 200, `connectorsScannedThisRun=1`, `reviewRequired=1`, `confirmed=0` 반환.
- 후보는 MariaDB `process_relation`에 1건 저장됨: ID `30579`, `PW-01 / PW-01-05 / shape 149`, `relation_status=REVIEW_REQUIRED`, `analysis_status=REVIEW_REQUIRED`, `relation_type=UNKNOWN`.
- idempotency 재분석 뒤에도 같은 후보 1건과 ID를 유지함. 설비 ID는 지정하지 않았고 `CONFIRMED` 관계는 0건임. 관리자 API의 `status=CONFIRMED` GET도 빈 목록을 반환함.
- 관리자 API evidence PUT 2회와 GET history를 실제 실행함. 처음 셀 범위 `D152 → AH152`를 기록한 뒤 `D152 → AI148:AV154`로 수정. 두 요청 모두 `REVIEW_REQUIRED`를 유지하고 이력 2건에는 이전/이후 snapshot이 기록됨.
- 공식 XLSX의 원시 OOXML에서 shape 149는 `tailEnd type=triangle`, 시작 anchor D152, 끝 anchor AH152, 도형 연결 바인딩 없음으로 확인됨. 보존된 렌더링 crop은 선이 왼쪽에서 오른쪽으로 박스에 닿는 모습을 보임. 따라서 화면상 방향은 앵커·화살표와 일치하지만, 논리적 설비 관계는 승인/확정하지 않음.
- V1.0 원본 16개 각각의 로컬 파일, `SHA256SUMS`, MariaDB metadata hash, MariaDB BLOB hash가 전부 일치함. 최종 비교 결과 CSV는 `validation-results/2026-10-16/baseline-sha256-verification.csv`.
- MariaDB schema migration V1–V8 적용 상태이며 기존 원본 BLOB을 수정하지 않음.

## 테스트 결과 — 2026-10-16
- 기존 API 검증 harness는 JSON의 `evidence` key를 읽다가 실패했음. 실제 list/detail 응답은 `connectorEvidence`와 별도 `candidateEvidence` key를 제공함. harness field expectation을 `connectorEvidence`로 바로잡아 실제 Spring API GET을 재검증했고 HTTP 200 및 ID 30579를 받음. 등록 재호출은 idempotent이며 같은 후보 한 건을 유지.
- 실제 관리자 API에서 evidence PUT 2회, 후보 GET, history GET을 완료한 기록이 `validation-results/2026-10-15/api-http/`에 있음. 두 변경 모두 REVIEW_REQUIRED 유지, 변경 이력 2건. 오늘 GET 재검증에서도 candidate `D152 → AI148:AV154`, history 2, CONFIRMED GET 빈 목록.
- 단일 `./scripts/test-local.sh` 실행은 17:56분 뒤 Surefire fork의 `Process Exit Code: 137`과 `Killed`로 중단됨. 중단 위치는 `MasterChangeDetectionIntegrationTest` Spring context 준비 중이었고, 앞선 6개 테스트는 assertions 실패 없이 통과. OOM stack/kernel log는 확인되지 않아 자원 압박 가능성이 높은 강제 종료로 기록하며 정확한 OS kill 원인은 미확정.
- 테스트 fixture의 변경탐지용 업로드 생성에서만 약 109,693개 OOXML calcChain 항목을 비워 직렬화 병목을 줄임. V1.0 원본 파일이나 DB BLOB은 건드리지 않음.
- 추가적으로 `ConnectorAnalysisIntegrationTest.managerCanAttachEvidenceToCandidateWithoutApprovingIt`가 영속 DB에 API 실검증 history 2건이 이미 있어 `history.size()==1` assertion에서 실패했음. 테스트를 사전 history count + 1로 검증하고 새 항목의 action/actor(`test-operator`)도 확인하도록 수정. 이건 테스트 isolation/expectation 수정이며 승인/상태 규칙은 바꾸지 않음.
- 최종 검증은 6개 테스트 클래스를 각각 별도 Maven `verify`로 모두 실행. **21 tests, failures 0, errors 0, skipped 0; 각 그룹 exit code 0.** 그룹별 총 wall time 131초(2분 11초), Maven 출력 합산 약 122초. 결과 로그 및 `summary.tsv`: `validation-results/2026-10-16/maven-final-groups/`.
  - MariaDbConnectivityTest: 1 / 0 / 0 / 0, 11초
  - OoxmlRoundTripTest: 2 / 0 / 0 / 0, 5초
  - BaselineImmutabilityTest: 1 / 0 / 0 / 0, 7초
  - RealPw01PreservationTest: 2 / 0 / 0 / 0, 18초
  - MasterChangeDetectionIntegrationTest: 10 / 0 / 0 / 0, 68초
  - ConnectorAnalysisIntegrationTest: 5 / 0 / 0 / 0, 22초
- 앞선 네 그룹도 최종 집계 일관성을 위해 한 번 더 개별 실행했으며 모두 재통과함. 테스트 삭제나 skip은 없음.
- 테스트 후 공식 V1.0 16개 로컬 파일·manifest·MariaDB metadata hash·BLOB hash 모두 재일치. 후보 ID 30579 1건 REVIEW_REQUIRED, history 2, CONFIRMED 0 확인.
- Spring Boot 3.5.16 / Java 17.0.20.1 / MariaDB 11.8 환경을 유지. Flyway의 MariaDB 11.8 지원 상한 11.2 경고는 있으나 8개 migration 검증·적용은 정상.

## 현재 완료 상태와 남은 보완
- 1차 개발의 전체 테스트 클래스는 분할 실행 기준으로 모두 통과했고, 검증된 API·DB 상태가 유지됨. 단일 전체-suite fork는 17분 56초 뒤 exit 137로 끝나지 않았음. 더 많은 메모리가 있는 CI에서 단일 `./scripts/test-local.sh` 실행을 추가 확인하는 것은 선택적이며, 분할 검증 21건은 전부 통과.
- PW-01-05 shape 149는 후보 상태일 뿐 설비관계 승인/확정이 아님. 관리자 승인 관계 0건 유지.
- 남은 사업 보완: PU-04 원본 파일 제공·매핑, 미매칭/다중 시트 설비 분류, Excel 네이티브 렌더링 확인, 도면 논리 관계의 담당자 검토, 운영 로그인/RBAC와 배포.
- 다음에는 이 결과를 기준으로 필요한 잔여 1차 보완만 진행. 승인 생성 금지. 2차 물질수지/자동 수정 개발 시작 금지.

## 백업 및 비밀정보 취급
- 백업 파일 위치: `/home/user/backups/process-map-automation-backup-2026-10-15.tar.gz` (생성 후 크기·복구 검증 결과는 사용자 보고에 기록).
- SHA-256 sidecar: `/home/user/backups/process-map-automation-backup-2026-10-15.tar.gz.sha256`.
- 백업에 Java 소스/빌드 설정/스크립트, 공식 V1.0 원본 16개, 검증 산출물, `CHECKPOINT.md`, MariaDB 논리 SQL dump를 포함.
- `.env`, 비밀번호, 관리자 토큰, `.git` 이력, `target/`, 로컬 의존성은 백업에서 제외. `.env.example`도 보안상 포함하지 않음.
- DB dump는 로컬 MariaDB의 credential 환경변수만 이용해 생성하며 SQL 결과에 비밀번호를 쓰지 않음. 별도 scratch DB로 import 성공.
- 복원본에서 V1.0 문서 16개, 후보 ID 30579 1건(REVIEW_REQUIRED, D152→AI148:AV154), 이력 2건, 승인 관계 0건을 확인한 뒤 scratch DB를 삭제. 운영 DB는 복원 검증 동안 읽기만 함.
- dump 생성 전후 운영 DB 상태는 V1.0 문서 16개, 후보 1건, 승인 0건, 이력 2건으로 일치.
- 공식 원본 파일은 읽기 전용 상태와 내용 그대로 보존되었고, staging에도 16개 파일과 `SHA256SUMS`가 복사됨.
- 압축 파일 크기와 최종 SHA-256은 백업 옆 `.sha256` sidecar에 기록함. 내일 풀기 전에 sidecar로 무결성을 확인할 것.
- scratch 복원 검증 결과: V1.0 문서 16개, 후보 1건과 검토 상태/셀 범위, 이력 2건, 승인 0건 일치. 검증용 DB는 삭제 완료.
- 내일 시작 시 압축파일 SHA-256을 sidecar와 대조한 뒤 풀어 사용.

## 2차 1단계 개발 상태 — 2026-10-16
- 기존 Java 17/Spring Boot/MariaDB 프로젝트를 유지했으며 2차 신규 API/모델/서비스를 추가함. DB migration은 추가하지 않았고 운영 DB는 읽기 전용 확인만 수행함.
- `POST /api/admin/phase2/pw0105/equipment-insertion/preview`: 예시 설비의 위치·전단·후단·담당자 확인 누락 시 `REVIEW_REQUIRED`, `xlsxGenerated=false` 반환. 실제 셀 수정/파일 생성/승인 없음.
- `POST /api/admin/phase2/material-balance/calculate`: 요청에 명시된 edge 중 `CONFIRMED`/`TEST_ONLY`와 해석 가능한 양 endpoint만 계산. 고유 transferId 중복은 요청 안에서 거부하고 유량유형·단위별 유입/유출/잔차·후단 노드를 제공. 단위변환 없음. `CONFIRMED` 및 검토 근거는 요청값일 뿐 DB 승인 이력 대조는 아직 없고 API에 경고 필드 포함.
- 신규 단위 테스트 6건 통과. 1차 기존 클래스 6개도 `.env`를 로드해 개별 `verify` 실행, 21건 모두 재통과. 전체 27건 모두 실패/오류/skip 0. 로그: `validation-results/2026-10-16/phase2-groups-valid/`.
- 최초 검증 스크립트 실행은 `.env`를 source 하지 않아 MariaDB 자격증명 없는 오류로 통합 테스트가 실패했고, SHA256SUMS도 저장소 루트 기준 경로 문제로 파일을 찾지 못함. 로그는 `phase2-groups/`에 보존함. 이후 올바르게 `.env` 로드 및 `data/reference/` 기준으로 7개 클래스를 재실행해 전부 통과했고 manifest 16개 모두 OK.
- 최종 로컬 파일·manifest·DB metadata hash·DB BLOB hash 16/16 PASS. MariaDB에 기준문서 16개 유지. 후보 `30579`는 `REVIEW_REQUIRED`, 시작/도착 설비 ID NULL, 전체 CONFIRMED 0.
- **2차 단계 미완료**: 사용자 제공 위치 셀 범위와 전단·후단 설비가 없어 도면에 자동 배치하지 않았으며, 실제 PW-01-05 XLSX 결과/연결선 편집/시각 비교 산출물은 생성하지 않음. DrawingML 편집기와 style-cloning 엔진, 영속 물질수지 그래프 및 마스터 차이의 movement 자동 연결도 아직 없음.
- 다음 작업은 사용자에게 PW-01-05 내 삽입 셀 범위, 전단 설비, 후단 설비, 담당자 검토명을 확인받은 뒤 별도 변경 검토용 복사본 구현을 진행해야 함. 공식 V1.0은 읽기 전용 유지. 다음 단계는 본 보고 후 승인 전 대기.
- 이전 단일 Maven 전체 실행 Surefire Exit 137은 미해결로 유지하고 이번에도 전체-suite 단일 실행은 시도하지 않음.

## 2차 1단계 최종 체크포인트 — 2026-10-16
- 최종 검토 산출물: `validation-results/2026-10-16/pw01-05-test-only/PW-01-05-C-PW15020-TEST_ONLY-V5.xlsx`.
- C-PW15009 원본 박스의 행/열 스타일, 12pt 행높이, 병합 및 테두리, 맑은 고딕, 정렬을 비교하여 적용. 원본 blank placeholder 보존으로 폐수/폐기물 세로선과 separator 가로선 연결을 LibreOffice 이미지에서 확인함.
- 관리번호는 헤더 왼쪽, TEST_ONLY는 헤더 오른쪽. 메인 설비 5개 텍스트는 AI:AU 중앙, 우측 폐수/폐기물 라벨은 각 칸의 중앙. 미확인 발생량·이동처는 입력하지 않음.
- 전체 삽입 배치 및 인접 설비/화살표/공정띠/도형 ID·anchor·관계 참조·인쇄영역 보존 검증. 공식 V1.0 파일은 읽기 전용 유지. 신규 설비/관계는 계속 TEST_ONLY / REVIEW_REQUIRED.
- 관련 `Pw0105EquipmentInsertionEngineTest`만 실행: 1 test, failures 0, errors 0, skipped 0. 전체 Maven suite 및 과거 Exit Code 137 실행은 재시도하지 않음.
- LibreOffice 원본/수정본 동일 조건 32p A3 PDF와 300dpi 페이지/확대 비교 PNG 생성. 파일은 `validation-results/2026-10-16/pw01-05-final/` 아래.
- 공식 원본 16개, `SHA256SUMS`, MariaDB 기준문서 metadata hash/BLOB hash 대조 16/16 PASS. 검증표 `validation-results/2026-10-16/baseline-sha256-verification-final.csv`.
- 최종 보고서 `PHASE2_1_FINAL_REPORT.md`. 민감정보 제외 백업은 `/home/user/backups/process-map-automation-final-2026-10-16/process-map-automation-final-2026-10-16.tar.gz`에 저장, SHA-256은 같은 디렉터리 `.sha256` sidecar에서 확인. DB scratch 복원/기준 counts 비교 PASS, archive 추출 및 원본 manifest·V5·보고서·체크포인트 복원 PASS. `.env`/토큰 제외. 회사 승인 private 저장소 여부는 확인 불가하여 로컬 제한 경로에만 보관.
- 미완료/주의: 사용자/담당자에 의한 실제 설비·흐름 승인과 발생정보 확인은 미수행. 전체 Maven suite Exit Code 137의 OS 원인은 미확정. 회사 승인 private storage 여부는 이 환경에서 확인할 수 없으므로 외부 공유 금지.
- 다음 개발 단계는 **폐수·폐기물 이동과 물질수지 자동계산을 실제 공정도에 연결**하는 2차 2단계이며 오늘 시작하지 않음.
- 종료 시점: 추가 기능 개발 없음. 최종 보관물과 산출물 검증 후 PC 종료 가능.

## 2차 2단계 첫 TEST_ONLY 시범 — 2026-10-19
- 사용자 지시에 따라 실제 물질수지와 분리된 계산 엔진을 구현. V1.0 16개 원본, 기준 마스터 1,332개, V5 XLSX는 읽기 전용 유지.
- `POST /api/admin/phase2/material-balance/calculate`는 이제 `mode=TEST_ONLY`만 허용. nodes/transfers로 분기·합류, 폐기물 발생 차감, 폐수 타 공정 이동/수신 유입, 후단 흐름 변경을 계산하고 노드별 flowType/unit 잔차, 이동처와 후단 영향 범위를 출력.
- V9 `material_balance_simulation` / `material_balance_transfer`를 추가. simulationId는 request hash로 멱등 재요청, transferId는 전역 unique DB 제약. TEST_ONLY 결과는 이 별도 테이블에만 남기며 공식 `equipment_flow`/원본/도면 쓰기는 금지.
- CONFIRMED는 요청 status로 인정하지 않음: DB relation row의 CONFIRMED + MANUALLY_RESOLVED + reviewer/reviewed_at + immutable 승인 snapshot, 양 endpoint/quantity/unit/flow/edgeType 일치를 확인. 현재 기준 DB relation은 REVIEW_REQUIRED 1, CONFIRMED 0이라 이 후보를 `CONFIRMED`라고 주장한 테스트는 제외됨.
- 지정한 합성값 100 ton/day 유입, 0.5 폐기물 발생, 2.5 타 공정 이동, 97 후단 유출, 수신 후단 변경 2.25를 검증. 가상 원천 잔차 0, 수신 공정 잔차 0.25, waste sink 유입/잔차 0.5; 최종 외부 배출량은 제공되지 않아 임의 입력하지 않음.
- 분리 테스트: `MaterialBalanceCalculatorTest` 4/4 PASS, `MaterialBalancePersistenceIntegrationTest` 6/6 PASS, `PhaseTwoPlanningTest` 1/1 PASS. 전체 Maven suite/Exit 137 테스트는 재실행하지 않음. 로그 `validation-results/2026-10-19-*.log`.
- 테스트 뒤 baseline documents/equipment/relation counts 유지, TEST_ONLY simulation rows 0건(테스트 transaction rollback) 확인. Flyway V9 schema만 적용.
- V5 크기 조사: V4 536,801 bytes → V5 536,808 bytes(+7). `xl/worksheets/sheet5.xml`의 blank placeholder 8개 보존으로 비압축 XML +440 bytes/ZIP payload +7 bytes. 현재 V5와 backup V5 모두 SHA-256 `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485` 일치. V5를 다시 생성/변경하지 않음.
- 시범 보고서 `PHASE2_2_STEP1_REPORT.md`; 현 시범 구현만 완료. 실제 업무자료·공식 공정 연결·PW-01-05 유량 변경·단위 변환/보존 모델은 미완료. 다음 단계는 사용자 지시 대기.

## 2차 2단계 Step 2 — 물질수지 정합성·후단 영향 보완 (2026-10-19)
- 공식 V1.0 원본 16개, DB 원본 BLOB/기준자료, TEST_ONLY V5는 수정하지 않음. V5 SHA-256은 `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485` 그대로.
- P2 2.5 유입 − 2.25 후단 유출 = `0.25 ton/day` 잔차를 `RESIDUAL_UNRESOLVED`로 표시하고, 저장/실제 이동처/기타 수지 자료를 요청. 이를 0으로 보정하거나 다른 노드에 가산하지 않음. Waste receiver 0.5 ton/day도 disposition/storage change/terminal 여부 미확정 상태로 남김.
- Node optional fields `storageChange`, `storageFlowType`, `storageUnit`, `boundaryCondition` 추가. 저장량 증가는 양수, 감소는 음수. `FINAL_DISCHARGE + TERMINAL_DISCHARGE` 또는 `FINAL_OFFSITE + TERMINAL_OFFSITE`를 명시한 경우에만 해당 유입을 terminal boundary outflow로 별도 표시. 저장 변화가 없으면 수신시설 잔차를 보존.
- 후단 scope는 node ID뿐 아니라 label/equipmentId/processCode/role을 반환. 유효/승인된 edge만 graph에 포함하며 visited set BFS가 branch/merge/cycle을 한 번씩 방문하고 cycle graph 전체 거부를 제거. Cycle 당 물질 재순환 수렴/유량 계산은 여전히 미지원.
- Transfer optional `receiverQuantity` 추가. 수신량이 명시되고 송출량과 다르면 `SENDER_RECEIVER_QUANTITY_MISMATCH`로 edge를 완전히 제외. 미입력 시 동일 transfer record의 동일 물량을 양 끝에 한 번씩 기록하므로 `MATCHED_BY_SINGLE_TRANSFER_RECORD_NOT_INDEPENDENTLY_MEASURED`로 한계 명시. 별도 센서/증거 기반 대사는 별도 입력이 있어야 함.
- V9는 수정하지 않음. V10에서 `UNIQUE(transfer_id)`를 `UNIQUE(simulation_db_id, transfer_id)`로 대체해 독립 simulation 간 동일 route 재사용을 허용하고, 한 simulation 내 중복은 계산기/DB 모두 차단. V11 nullable `receiver_quantity`를 추가. 현재 migration 11까지 적용 확인.
- 분리 테스트: calculator 6/6, persistence integration 7/7, planning regression 1/1 — 총 14, failures/errors/skips 0. 독립 receiverQuantity 불일치 edge 제외 및 DB evidence 저장도 통합 테스트 완료. 전체 Maven suite는 실행하지 않음. DB baseline documents 16, equipment 1,332, CONFIRMED relation 0, REVIEW_REQUIRED 1, TEST_ONLY simulation 0.
- 원본 16개 local/manifest/DB metadata/DB BLOB SHA-256 16/16 PASS. Migration schema unique key 복합 인덱스 확인. Flyway는 MariaDB 11.8 지원상한 11.2 경고를 냈으나 V10/V11 적용·검증 성공.
- 상세 결과 `PHASE2_2_STEP2_REPORT.md`. 남은 것은 실측 저장량/기간별 재고·물리 보존, 실제 승인 관계(현재 0건) 데이터 적용, cycle flow convergence, 공식 Excel 반영 및 단위변환. Excel 유량 자동 수정은 계속 사용자 지시 대기.
- 다음 단계는 계산 결과를 검토용 PW-01-05 TEST_ONLY 사본에 매핑하는 준비와 변경 전후 검증 기준 수립부터 시작하고, 결과 반영은 별도 승인 후 진행. 공식 V1.0은 계속 read-only.

## 2차 2단계 Step 3 — TEST_ONLY 결과 시트 출력 (2026-10-19)
- `POST /api/admin/phase2/material-balance/export` 추가: 관리자 token + multipart `request` JSON / `workbook` V5를 받아 TEST_ONLY 계산 후 XLSX attachment 반환. exact V5 SHA-256과 TEST_ONLY marker 확인 후에만 계산을 실행.
- 계산 결과를 별도 `TEST_ONLY Results` 시트로 출력. TEST_ONLY 표시, sender/receiver 이동 물량·flowType·unit·잔차·W/D 경계 상태·미매핑 node 목록·기존 V5 layout 분석 정보를 포함. S/P2/W/D는 실설비/셀과 연결하지 않음.
- 기존 PW-01-05 sheet5, 모든 worksheet, drawing/style 등 기존 OOXML parts는 raw byte 비교로 보존 확인. 변경 package part는 workbook/sheet metadata와 app props 및 신규 sheet7만 허용.
- V5 유지 hash `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`. 신규 workbook `validation-results/2026-10-19-step3/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V1.xlsx`, SHA-256 `f4101c5247dfed68a80c316b1e44a08b3495f40a3f5408946c77a952bf7040c9`.
- LibreOffice PDF 33 pages(기존 V5 32 + 신규 결과 1), 신규 페이지 A3 landscape. V5와 신규 PDF의 기존 32 pages 36dpi pixel compare 차이 없음. PDF/PNG 경로는 `validation-results/2026-10-19-step3/render-final4/`.
- P2 잔차 0.25, W 잔차 0.5, D 유입/잔차 99.25는 미확정 그대로 표시. 최종 D 유입을 방류량으로 합산하지 않으며 receiver boundary 미확정.
- 최종 관련 테스트 17/17 pass: calculator 6, persistence 7, Excel export/API 3, planning 1. 전체 Maven suite/과거 Exit 137 suite 미실행. 공식 V1.0 원본/DB hashes 16/16 PASS; DB baseline docs 16/equipment 1,332/CONFIRMED 0/REVIEW_REQUIRED 1.
- 상세 보고 `PHASE2_2_STEP3_REPORT.md`. 공식 물량/새 공식 버전/실제 승인 연결은 생성하지 않았고 다음 단계를 자동으로 시작하지 않음.

## 2차 2단계 Step 4 — C-PW15020 TEST_ONLY 표시 (2026-10-19)
- C-PW15020 main box와 폐수/폐기물 label cell은 `Pw0105EquipmentInsertionEngine.analyze`로 automatic discovery. 실제 box range 및 labels are emitted from workbook scan; workbook coordinates/shapes are not caller-supplied.
- 원본 우측 compartment에 직접 유량을 기록하면 기존 라벨/구획선과 겹칠 가능성이 있어 기존 셀은 수정하지 않고 `TEST_ONLY Results`의 별도 flow summary 영역 사용. V5 diagram page 27 remains pixel-identical after same-condition 300dpi render. V5 arrows/drawing/package parts/print area untouched.
- Scenario: 100 inflow C-PW15009→C-PW15020; 0.5 waste generation to unnamed TEST_ONLY waste output; 2.5 wastewater to `UNASSIGNED_WASTEWATER_RECEIVER`; 97 downstream C-PW15010. All four edges relationMode=TEST_ONLY, relationId=NULL, no equipmentId/processCode inference. C-PW15020 calc inflow/outflow 100/100, residual 0; unassigned receiver remains calculation unavailable and unconfirmed.
- New API `POST /api/admin/phase2/material-balance/export-cpw15020-test-only`; returns separate V2 attachment; response marks official diagram cells unchanged and relation status REVIEW_REQUIRED. The prior Step 3 export route/artifact was not overwritten.
- Artifact `validation-results/2026-10-19-step4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V2.xlsx`; SHA-256 `90022b35d800d3b0f0944e256e46f5081605afb5ffb4373b22c39171871f0acc`. PDF 33 pages, page 27 existing diagram exact pixel match at 300dpi; comparison PNG `validation-results/2026-10-19-step4/cpw15020-before-after-300dpi.png`.
- Tests: `Cpw15020DiagramTestOnlyExportIntegrationTest` 2/2 PASS (workflow + admin API, values re-read, parts/hash/DB relation counts); `MaterialBalanceCalculatorTest` 6/6 PASS. One combined invocation hit tool timeout while redundant full-workbook analysis was repeated; redundant layout scan was removed, then the dedicated integration tests passed in 39.6s. The full suite/Exit 137 path was not run.
- V1.0 hashes vs local/manifest/DB metadata/BLOB 16/16 PASS. DB state V1.0 docs=16, equipment=1,332, CONFIRMED=0, REVIEW_REQUIRED=1, schema V11; baseline relations unchanged.
- Detailed report `PHASE2_2_STEP4_REPORT.md`. Remaining: workbook display is an adjacent TEST_ONLY summary, not a label inside the main diagram box, because the inspected compartment cells have no safe unoccupied label area. Connector evidence remains visual-only; live flow, approvals, destination, and waste disposition remain unconfirmed. No other flow diagram or official quantity was edited; wait for next user instruction.

## 2차 2단계 Step 5 — 실제 PW-01-05 TEST_ONLY 도면 오버레이 검증 (2026-10-19)
- 기존 V3를 먼저 검증: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V3.xlsx`, 539,650 bytes, SHA-256 `9c2210310834624365215838df8595ac487e127aef15707e10c92d4aa0246135`. V3는 보존하고 덮어쓰지 않음.
- V3 page 27 실제 도면에 유입 100, 폐수 2.5, 폐기물 0.5, 후단 97이 DrawingML 텍스트로 존재했음. 문제: 유입 100과 후단 97을 한 라벨에 같이 넣어 후단 97이 독립 연결 표시처럼 명확하지 않음.
- 기존 오버레이 서비스의 해당 라벨만 분리. V4는 C-PW15020 box 안의 `TEST_ONLY / 유입 100 ton/day`와 기존 하향 TEST_ONLY connector 옆의 독립 `TEST_ONLY / 후단 이동 97 ton/day`를 표시. 우측 새 흰색 박스 2개는 폐수 2.5/폐기물 0.5 ton/day 및 이동처 미확정을 표시하고, 각각 빨간 방향 화살표로 분기. 미확정 실제 수신처 ID/승인 관계는 만들지 않음.
- 신규 V4: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.xlsx`, 539,721 bytes, SHA-256 `cd55e21d5bd75f95adb3793be116197fb18b90e456c3dd8f69bd264126194a08`. 별도 V4로 저장, V3와 V5 보존.
- V5 SHA-256 보존: `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`. Step 3 V1/Step 4 V2 해시 통합 테스트 전후 유지. V1.0 원본 local/manifest/MariaDB metadata/BLOB SHA-256 16/16 PASS. MariaDB baseline documents 16, equipment_record 1,332, CONFIRMED 0, REVIEW_REQUIRED 1, Flyway V11 유지.
- V5 sheet5 XML/relationship는 V4와 byte-identical. 기존 drawing5 104개 anchor element가 변경 없이 prefix로 유지되고 신규 shape/connector ID 160–165는 상호/기존 ID 중복 없음. 기존 V5에서 이미 존재하던 ID 159 중복 1건은 보존 요구상 수정하지 않음. 병합 셀 85개, 인쇄영역 `$A$1:$BJ$230`, 스타일 및 원본 drawings 보존. 신규 connector 2개/shape 4개, 열린 관계 오류 없음.
- V5와 V4 LibreOffice 25.2.3.2 A3 PDF 동일 조건 렌더: V5 32p, V4 33p(결과 sheet 1p 추가). page 27 crop 300dpi, V4 전체 페이지 150dpi. 기존 나머지 31개 페이지는 36dpi pixel-identical. 확대 비교: `validation-results/2026-10-19-step5/V5-vs-V4-C-PW15020-detail-300dpi.png`; V4 확대 `V4-PW-01-05-cpw15020-detail-300dpi.png`; PDF `render-v4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.pdf`.
- V4 `TEST_ONLY Results` 재독: 100/100, 0.5/0.5, 2.5/2.5, 97/97 ton/day; `100 = 0.5 + 2.5 + 97`, C-PW15020 잔차 0. 시험 edge 전부 TEST_ONLY, 승인 관계 0건.
- 선택 테스트: `Cpw15020DiagramTestOnlyExportIntegrationTest` 2/2, `MaterialBalanceCalculatorTest` 6/6, `OoxmlRoundTripTest` 2/2 PASS. 총 10 tests, failures/errors/skips 0. 전체 Maven/Exit 137 suite는 실행하지 않음.
- 세부 보고 `PHASE2_2_STEP5_REPORT.md`. 다른 공정도, 공식 V1.0, 공식 유량, relation 승인 및 다음 단계는 수정하지 않음. Microsoft Excel native renderer는 미검증.

## 통합공정도 자동화 Step 6-1 최종 테스트 — 2026-10-19
- Step 6-1 작업 시작 시 branch `main`, HEAD `20c6de9 genspark auto-backup`, working tree clean. 기존 미커밋 코드는 폐기/초기화하지 않았음.
- V12 migration `label master change runs test only`는 MariaDB 11.8 `process_map_dev`에 `success=1`로 적용. `change_run=171`에 TEST_ONLY 변경 분석 저장 및 run/items/issues 조회 성공.
- 실제 Spring Boot API로 TEST_ONLY XLSX multipart upload: 인증 누락 요청 HTTP 401, 인증된 요청 HTTP 200. `changeRunId=171`, baseline 1,332/upload 1,333, ADDED 1, REMOVED 0, MODIFIED 1, MOVED 0, mapping `REVIEW_REQUIRED`. 저장된 change items 2건, flow candidates 2건 모두 route_created=false. MariaDB baseline/relations는 변경되지 않음.
- 실제 변경 마스터 미제공으로 공식 전체목록 복사본에서 신규 중간 행, 설비명/용량/폐수/폐기물 입력 변경을 합성한 `validation-results/2026-10-19-step6-1/TEST_ONLY-master-change-scenario.xlsx`를 생성. SHA-256 `f1519bac2c2f05b75220d8391bf3526da93a46dc7fe6b8f59a205c30ed84bfe5`.
- 관련 테스트 분리 실행 최종 PASS: `MasterChangeDetectionIntegrationTest` 18/18; `MasterDiffControllerTest` 3/3; `MasterFieldChangePolicyTest` 2/2; `Pw0105EquipmentInsertionEngineTest` 1/1. 합계 24, failures/errors/skips 0. test-compile도 성공. 전체 Maven suite는 기존 Exit 137 우려로 실행하지 않음.
- 신규 중간행 삽입 테스트에서 ADDED 1, MOVED 0 확인. 상대 순서 변경은 안정적 관리번호 기준으로 서로 바뀐 두 설비만 MOVED 2. 중복/누락 ID, 공정번호 변화, 4 flow 입력값, unmatched, 다중 시트 검토도 통과. V1.0의 flow 열은 모두 공란이므로 비공란 기준값→공란 사례는 합성 policy 단위 테스트에서 검증.
- Drawings regression output `validation-results/2026-10-19-step6-1/PW-01-05-DRAWING-ID-REGRESSION-TEST-ONLY.xlsx`; SHA-256 `e28394df335b94a3a088b581234d00cc9942cadae74d63bf156fd79461739f4a`. 신규 connector ID 160, part-local xdr:cNvPr ID 중복 없음, 기존 drawing object identity/shape bindings/arrow/anchor 보존 테스트 PASS.
- Official local files/manifest/MariaDB metadata/BLOB SHA-256 16/16 PASS. MariaDB baseline docs 16, master equipment 1,332, process documents 15, process sheets 62, CONFIRMED 0, REVIEW_REQUIRED 1. Step 5 V4 DRAWING-IDFIX SHA-256 `d7ebc9f43c6f0ac4e34992dc4a21e53f26676134187110db333e4f4b0e2f2235` 유지.
- `/master-diff.html` HTTP 200 및 Playwright 문서 제목/form selector 로드. Playwright report에는 404 resource 1건이 남아 실제 화면에서 파일 업로드/결과 표시를 완전 검증한 것으로 간주하지 않음. API→MariaDB 흐름은 실제 완료.
- 최종 상세 보고서: `STEP6_1_FINAL_REPORT.md`. 실제 업무 변경본 검증 및 Step 6-2는 미착수. 공식 V1.0/Step 5 파일/도면/관계는 수정하지 않음.
- Step 6-1 마무리 커밋 기록은 보고서와 함께 다음 commit entry에 추가.

## 통합공정도 자동화 Step 6-2 최종 완료 — 2026-10-20

### 완료·보존 상태
- Step 6-2는 TEST_ONLY 편집 계획과 최종 LABELFIX 구조 감사를 완료. 공식 V1.0 원본/기준 도면 변경 및 업무상 관계 승인은 없음.
- Step 6-1 변경 마스터 업로드 API `POST /api/admin/master-diff`의 저장된 `changeRunId`/`changeItemId`를 `POST /api/admin/diagram-edit-plans`에 연결. 계획 조회는 `GET /api/admin/diagram-edit-plans/{planId}`, 명시적 시험 출력은 `POST /api/admin/diagram-edit-plans/{planId}/generate-test-only`, 파일 재다운로드는 `GET /api/admin/diagram-edit-plans/{planId}/download`.
- `changeRunId=173`, `changeItemId=173`; 1,332→1,333, ADDED 1, 삭제/수정/이동 0. 신규 C-PW15020의 업로드·계획·출력 필드는 일치. 정확 설비 매핑이 없어 매핑은 REVIEW_REQUIRED.
- 계획 5는 `TEST_ONLY_READY` / `TEST_ONLY_ARTIFACT_ONLY`이며 relation은 REVIEW_REQUIRED, `officialApprovalCreated=false`. 실제 승인 관계 0건, 기존 검토 대기 관계 1건. TEST_ONLY 설정 없는 계획은 REVIEW_REQUIRED로 생성 차단.
- 최신 시험 기준 파일: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`; SHA-256 `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc`. 원본 PLAN-5는 수정 전 이력으로 보존. 공식 원본은 `data/reference/` 16개와 `SHA256SUMS`.
- Step 6-2 최종 보고서 `STEP6_2_FINAL_REPORT.md`; 구조 감사 `STEP6_2_STRUCTURAL_AUDIT.md`. 감사 결과 16/16 원본 무결성, 설비 1,332개/도면 15개/시트 62개 보존, DrawingML ID 중복 0, 승인 0/검토 대기 1.
- 이번 문서 정리에서 Maven·Excel 생성·PDF 렌더·전체 도면 분석·DB 쓰기·Step 6-3은 수행하지 않음. 테스트/렌더 결과는 이전 실행 기록만 보고서에 분리 기재.

### Git 및 WIP 보존
- 사용자 기준 HEAD `54f6eeea6a313b9f43890a485254f36596dd2f73` 이후 main의 `ceb421f` 자동 백업이 구조 감사 보고서를 포함. 해당 main 개발 코드를 유지하고 WIP를 merge하지 않음.

---

## Step 7-3 복구 — 시작점 및 보존 상태 (2026-10-23)
- 사용자 요청에 따라 Step 7-2 상태에서 전용 브랜치 `step7-3-wip-20261023`를 생성. 시작 HEAD `648b69c` (`genspark auto-backup`)이며 지정된 `step7-2-wip-20261021` 원본 브랜치와 `main`은 checkout/수정/merge하지 않음.
- 시작 전 실제 `git status --short --branch`는 clean. Step7ConnectionEvidenceService.java 및 Step7MaterialBalanceComparisonService.java의 변경은 시작 시점 HEAD의 자동 백업 커밋 `648b69c`에 이미 포함되어 추적 중. 즉 이번 재개 시 미추적 파일이나 Step7-2 tracked diff는 없었음. 기존 stash `stash@{0}` `WIP step6-4 workbook enrichment not complete` 보존.
- 실제 팀원 XLSM SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`; 로컬 공식 PW-01 XLSX `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0`; 전체 목록 XLSX `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`. 읽기 전용 fingerprint 확인만 수행.
- Step7ConnectionEvidenceService 초안의 시작 시 test-compile은 이전 재개에서 PASS로 확인됐고, 이번에도 초기 재검증을 위해 compile 게이트 실행 예정. 실제 16개 전체 manifest/DB BLOB 불변성, PW-01-05 shape/셀의 실증 매핑 및 Excel 산출물 검증은 아직 미완료. 이 체크포인트는 작업 시작 기록이며 구현 완료를 의미하지 않음.
- 저장소에는 기존 Step7 초안이 모두 커밋되어 있었으므로, 이어서 해당 기능을 확장하고 하드코딩 후보는 실제 extract 결과로 간주하지 않도록 분리할 것. 공식 원본/DB baseline/relation 변경 금지.

### Step 7-3 실측 조사 — 초안의 7개 하드코딩 후보와 DrawingML (진행 중)
- 기존 `OoxmlPackageInspector`의 Apache POI + DrawingML 분석기를 사용해 official V1.0 PW-01 workbook의 실제 `PW-01-05` (`sheet5.xml`, `drawing5.xml`)을 읽기 전용 검사. 해당 시트 객체 103개.
- 하드코딩 참조 ID 10, 53, 76, 90, 114, 125, 149는 각자 실제 CONNECTOR 도형 ID로 존재하며 anchors/arrow type은 읽힘. 단 7개 모두 `startConnectedShapeId`와 `endConnectedShapeId`가 null이고, 이 도형 조사에서 endpoint binding은 0건. Anchor는 선의 화면상 시작/끝 셀일 뿐 설비 논리 endpoint 증거가 아님.
- 셀 주소/설비 라벨/도면 숫자 표기의 실측 매핑 및 DB 후보 일치 여부, 5-sheet TEST_ONLY workbook, 테스트/16개 원본 재검증은 미완료. 다음 작업에서 7개 ID가 실제 배관 관계라는 주장 없이 `MANUAL_UNVERIFIED`/후보 근거로만 노출하고, 자동 추출 사실과 위치 추정 데이터를 별도 필드로 보존한다.
- WIP `step6-2-wip-20261020` 및 체크포인트 커밋 `a8a41ec`는 별도 브랜치에 보존. WIP의 계획/API/테스트/300초 중단 진행 이력은 위 최종 판정과 대조해 필요한 사실만 이 체크포인트와 최종 보고서에 반영. WIP의 이전 체크포인트를 덮어쓰거나 브랜치를 변경하지 않음.
- 최종 보고서 및 체크포인트 본문 커밋: `a21e950` (`Complete Step 6-2 final report and checkpoint`). 구조 감사 보고서는 이미 main의 `ceb421f`에 포함되어 있었음.

### 이전 테스트 기록·제한 및 미검증
- 기존 `Pw0105EquipmentInsertionEngineTest` 2/2(2026-10-20 00:24:45Z), `test-compile` BUILD SUCCESS(00:26:22Z), `DiagramEditPlanIntegrationTest` 1/1(00:31:26Z, 292.8초) 로그 보존. 전체 Maven suite는 미실행.
- 전체 mapping/index 재분석의 반복 실행이 300초 도구 제한에 걸리거나 근접한 문제는 미해결. 재실행·최적화하지 않음.
- 실제 브라우저 최종 사용자 업로드/결과 확인과 Microsoft Excel 네이티브 렌더·인쇄는 미검증. 실제 업무 마스터·공정 관계 및 C-PW15020의 위치/흐름/이동처에 대한 담당자 확인 필요.
- 기술적 후속 확인: 전체 workbook 분석 비용/300초 제한을 줄일 영향 도면 선별 방식, 실무 검토/승인 절차, 네이티브 Excel 검증.

### 다음 목표 — Step 6-3 (시작하지 않음)
- 영향받는 도면만 TEST_ONLY 수정본으로 생성하고, 필요한 파일만 선택 다운로드하도록 개발. 원본 V1.0 불변 및 불확실 관계 REVIEW_REQUIRED 원칙 유지. 이번 작업에서는 착수하지 않음.

---

## Step 6-2 문서 정리 이후 커밋 기록
- Step 6-2 최종 보고서·체크포인트 본문 커밋: `a21e950` (`Complete Step 6-2 final report and checkpoint`).
- 체크포인트 최종 이력 커밋: `6deba2a638fe700af4fd59b595d20f4fabcc35f2` (`Record Step 6-2 final checkpoint commit`).
- 커밋 브랜치: `main`. 최종 Step 6-2 종료 시 작업 트리는 clean.
- 포함 범위: 최종 보고서와 CHECKPOINT. 구조 감사 보고서는 이미 앞선 main의 `ceb421f`에 포함. XLSX/PDF/PNG, 비밀, DB dump 및 공식 도면은 미포함.

## 통합공정도 자동화 Step 6-3A 최종 검증 — 2026-10-20

### 상태와 기능
- Step 6-3A 영향 도면 선별·파일별 실행계획 API 및 관련 테스트를 검증 완료. API: `POST /api/admin/change-runs/{changeRunId}/selective-edit-plan` (`X-Admin-Token` 필수).
- `SelectiveEditExecutionPlanService`는 저장된 change run/item 및 `affected_diagrams_json`, 기존 TEST_ONLY `diagram_edit_plan`을 재사용한다. V1.0 master 및 후보 파일 해시/시트 인벤토리 확인 후 명시적으로 선택·검증된 plan만 파일별 실행계획에 포함한다.
- 동일 공식 파일의 여러 변경 item/시트는 파일 하나로 묶고, 다른 공식 파일은 별도 그룹으로 만든다. 미선택 plan은 B_REVIEW_REQUIRED, 기준 파일/시트가 없거나 맞지 않는 후보는 C_AUTOMATIC_EDIT_UNAVAILABLE이며 출력 대상이 아님. A_TEST_ONLY_PLAN_READY는 명시적 TEST_ONLY artifact plan에 대한 자격일 뿐 공식 수정/승인이 아님.
- 서로 다른 changeRunId 혼합, 다른 run의 item/plan, 중복 plan ID는 거부. 모든 관계는 REVIEW_REQUIRED; 승인 생성·CONFIRMED 변경은 없음.
- API는 read-only 계획 생성 전용. XLSX, ZIP, 다운로드 산출물, 공식 도면 변경은 생성하지 않음.

### 검증 결과
- `changeRunId=173`, `changeItemId=173`, 기존 plan ID 5 이용. plan 미선택/REVIEW plan 2는 output file 0개, 기존 plan 5를 명시 선택하면 PW-01 파일 1개 및 PW-01-05 sheet 1개만 계획 대상. PW-01의 나머지 5 후보 sheet와 다른 공식 workbook 14개는 출력에서 제외.
- 분리 TEST_ONLY transactional fixture로 PW-01 내 변경 2건/2 sheet를 파일 1개로 묶고 P-01 변경을 별도 파일 group으로 반환하는 통합 시험 통과. review-only item B, 등록되지 않은 도면 후보 C로 제외. cross-run item/plan 및 duplicate plan 선택 거부. 테스트 픽스처는 transaction rollback되어 DB에 남지 않음.
- 마지막 선택 실행: 7 tests, failures 0, errors 0, skipped 0, BUILD SUCCESS (`validation-results/2026-10-20-step6-3a/final-targeted-tests.log`). 전체 Maven suite는 실행하지 않음.
- run173 plan/API 단계 timing은 `STEP6_3A_REPORT.md` 및 `validation-results/2026-10-20-step6-3a/final-targeted-tests.log`에 기록. 직접 서비스 66ms, MockMvc API wall-clock 178ms/내부 서비스 43ms. 저장 분석·시트 인덱스 재사용 확인, DrawingML 전체 분석은 호출하지 않음.
- 기존 `scripts/verify-baseline-hashes.py` 읽기 전용 검증 결과 local/manifest/MariaDB metadata/BLOB 16/16 PASS (`validation-results/2026-10-20-step6-3a/final-baseline-sha256-verification.csv`). DB documents 16, equipment 1,332, process workbooks 15, sheets 62, CONFIRMED 0, REVIEW_REQUIRED 1; synthetic test runs persisted 0; schema 13.
- Step 6-2 최종 TEST_ONLY 기준: `validation-results/2026-10-19-step6-2/PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`, SHA-256 `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc`; 수정/재생성하지 않음.

### 기술 제한 및 다음 단계
- 기존 run의 저장 매핑/시트 인덱스 재사용으로 Step 6-3A planning call에서 전체 15 workbook/62 sheet DrawingML 재분석을 회피. 새 업로드 run의 초기 `DiagramImpactAnalysisService` cold-index 작업은 남아 있어 과거 300초 제한 위험을 완전히 해소한 것은 아님. 이번에는 cold index를 실행하지 않음.
- 다음 Step 6-3B에서는 실행계획을 입력으로 받아 선택된 공식 workbook별 TEST_ONLY 파일을 생성하고 해당 sheet만 수정하도록 설계·검증. 동일 파일의 여러 plan 충돌/삽입 순서, 실패 시 원본 보존, 선택 파일만 다운로드를 확인. ZIP 및 전체 재분석은 별도 승인 전 금지.
- 이번 결과 보고서: `STEP6_3A_REPORT.md`. Git 코드/테스트/보고서/체크포인트 commit ID는 최종 응답에 기록; Step 6-3B는 미착수.

## 발표 시연본 준비 — 2026-10-20

### 단계 1 — 한글 변경 분석 화면 (완료)
- 기존 `POST /api/admin/master-diff` 응답을 이용하는 `/master-diff.html` 표시 계층만 개선. Java 서비스/DB schema/API contract는 변경하지 않음.
- 실제 API 응답의 기준 설비 수·업로드 수·변경 건수·검증 오류·run ID 표시. 변경 전후 값 및 한글 변경 유형, 영향 도면 후보, 매핑 근거, REVIEW_REQUIRED/담당자 확인 경고를 표시. 원본 JSON은 접힌 상세 항목에 보존.
- 저장 run을 조회하는 UI 경로를 기존 GET API로 추가해 업로드 재실행 없이 시연 가능. 토큰은 저장하지 않고 요청 후 입력란을 비움.
- UI 컴파일 전 JS syntax check, 서버 재시작 후 `/api/health` 200 및 `/master-diff.html` 200 확인.

### 단계 2 — 실제/저장 시나리오 검증 (완료)
- 실제 브라우저 업로드: TEST_ONLY 신규설비 마스터 업로드 후 `changeRunId=192`, 추가 1, 업로드 설비 1,333, 기준 1,332, 검증 오류 0 확인. 후보는 PW-01-01~06 6개 시트, `PROCESS_CODE_ONLY_REVIEW`, 자동매핑 false, REVIEW_REQUIRED로 표시됨.
- 저장 결과 조회: run 187 변경 없음(0/0/0/0), run 188 삭제 1, run 190 수정 1, run 191 순서변경 2를 예상대로 표와 요약에 표시. run 186 원래 추가 결과도 저장 자료로 유지.
- headless Chromium UI checks: 5개 시나리오 모두 통과, 토큰 입력란 요청 후 비움, JS/page console errors 0. 캡처 6장(추가 live run 192 및 저장 run 186, 무변경/삭제/수정/순서변경)을 `validation-results/2026-10-20-demo-prep/`에 저장.
- 이 작업에서는 Step 6-2 초기 전체 인덱스 분석을 재실행하지 않음. run 192 업로드 경로는 실제 사용자가 요청한 UI validation 1회만 실행. 발표 중 지연될 경우 저장 run 조회/스크린샷으로 대체.

### 보안 및 보존
- 사용자 메시지에서 관리자 토큰이 노출된 것으로 간주해 값을 재표시하지 않고 `.env` 내 토큰을 새 임의값으로 교체, 권한 600·gitignore 유지, PM2 앱 재시작. 새 값은 편집기에서 확인 필요; 기존 값은 더 이상 사용하지 않음.
- V1.0 공식 원본/기준 DB와 승인 관계는 읽기 전용 유지. 시험 업로드 결과는 TEST_ONLY run 192만 새로 생성.
### 단계 3 — 비교자료·발표자료·백업 (완료)
- 기존 Step 6-2 `PW-01-05-C-PW15020-TEST_ONLY-ANALYSIS-173-PLAN-5-LABELFIX.xlsx`와 기존 PLAN5-vs-LABELFIX 300dpi 비교 이미지 2개를 그대로 복사해 재사용. 재생성·재렌더링하지 않음. LABELFIX SHA-256 `3329abe71a4a8dbc4a04dcfe4927d30cdaf86d8ed64aec134ae270ba189188bc`.
- 신규 TEST_ONLY 업로드용 전체 목록 Excel, 5개 한글 UI 화면 캡처, 5–7분 시연 순서 및 구현/미완료 1페이지 요약을 `validation-results/2026-10-20-demo-prep/`에 저장.
- 화면 캡처에는 토큰 값/마스킹 문자열이 없고, 캡처 시 토큰 입력란은 비어 있음. 자세한 데이터 보기 기본 접힘.
- private local backup: `/home/user/backups/process-map-automation-demo-2026-10-20.tar.gz`, SHA-256 sidecar 동봉. archive mode 600; `.env`, `data/reference/` 공식 원본 및 `.git` 제외. 원본 화면의 별도 600-mode 사본도 같은 backup directory에 보존.

### 잔여 위험 및 발표 권고
- Step 6-3B, 선택 도면 실제 편집/다운로드, ZIP, 업무 승인, 공식 도면 수정은 미구현. LABELFIX는 기존 별도 TEST_ONLY plan 산출물이며 당일 마스터 업로드 결과로 생성된 것이 아님.
- cold-index 재분석이 필요한 신규 change run은 과거 300초 제한 위험이 남음. 발표에서는 이미 준비된 run 192/187/188/190/191의 저장 결과 조회를 우선하고, 업로드가 지연되면 반복하지 말고 캡처로 전환.
- 2026-10-22 오전에는 신규 기능 개발 대신 서버/health/UI/auth, 토큰 재확인, 짧은 저장-run smoke test와 발표 연습만 수행.
- Git commit `cdea268`에 화면 및 단계 1/2 체크포인트를 저장. 발표자료 파일 및 최종 체크포인트는 별도 후속 commit; 공식 원본·시험 XLSX·스크린샷·백업은 Git에 포함하지 않음.

## 토큰 없는 발표용 DEMO 모드 — 2026-10-20

### 구현 범위와 격리
- `/master-diff.html`의 기존 관리자 토큰 입력, Excel 업로드, 저장 결과 조회 UI와 API는 유지. `BaselineController`, `ApiAccessGuard`, `ChangeDetectionService`, MariaDB schema 및 업로드/분석 API는 변경하지 않음.
- 별도 “가상 데이터 시연” 영역과 버튼을 추가. 버튼은 HTML/JavaScript의 고정 합성 TEST_ONLY fixture만 `renderResults`에 전달하며 `fetch`/XHR, 관리자 API, 파일 전송, DB 조회/저장, 공식 마스터 비교 및 도면 수정 요청을 하지 않음.
- 결과 영역에도 “가상 데이터 시연” 표지와 실제 비교/저장/도면 수정 결과가 아니라는 안내를 표시. 예시 설비·공정·파일명·ID는 가상값.
- 정적 `/master-diff.html` 접속만 공개. API 접근은 서버의 기존 토큰 guard 그대로 유지.

### 시험 결과
- inline JavaScript `node --check`, `git diff --check` 통과.
- 로컬 `/master-diff.html` HTTP 200. Headless Chromium에서 DEMO 버튼으로 합성 결과/표시 확인, 결과 렌더까지 `/api/*` 요청 0건, page error 0건.
- 잘못된 토큰으로 기존 upload UI 실행 시 `POST /api/admin/master-diff` 401, 입력란은 요청 후 비움. 무토큰 직접 upload POST도 401.
- 무토큰 `GET /api/admin/baselines/current`, `/api/admin/change-runs/186`, `/items`, `/issues` 각각 401.
- 첫 Playwright harness 시도는 응답 문구에 `401` 숫자가 포함된다고 잘못 가정해 timeout; HTTP response status로 재시험해 401 및 정상 UI 동작을 확인.
- DB 쓰기, Flyway migration, 기존 저장 run 재실행/수정 없음. 공식 원본 16개, 1,332 설비, MariaDB 기준자료, Step 6-2 LABELFIX 산출물에 쓰기 작업 없음.

### 발표 사용법 및 한계
1. `/master-diff.html`에서 “가상 데이터 시연” 영역의 **가상 결과 화면 보기** 클릭. 토큰 없이 사용할 수 있음.
2. 실제 Excel 분석/저장 run 재조회는 별도 관리자 영역에서 기존 토큰 필요.
3. DEMO는 고정 샘플로 결과 UI를 보여줄 뿐 실제 비교 알고리즘이나 서버 저장 검증이 아님. 실제 upload 시연은 기존 토큰 인증 TEST_ONLY 경로를 사용.
4. 기존 임시 접속 링크는 샌드박스 개발 주소이며 회사 운영 공개 URL이 아님.

## 일반 직원 로그인 설계 — 구현 보류, 최종 목표 2026-10-29

### 목표 구조
- Spring Boot 3.5.16에 Spring Security를 도입해 username/password 로그인과 서버측 `HttpSession`을 사용. 초기 범위는 로그인/로그아웃, ROLE_USER/ROLE_ADMIN, 세션 idle timeout.
- 별도 사용자 테이블에 정규화된 unique username, password hash(Argon2id 또는 Spring Security `DelegatingPasswordEncoder`), role, enabled, 감사용 생성/수정 metadata 저장. 평문 비밀번호/초기 비밀번호를 DB·로그·응답에 남기지 않고 관리자 계정 초기 생성·비밀번호 재설정 절차 정의.
- HTTPS 운영 쿠키: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, host-only. 신뢰할 프록시의 forwarded header만 사용. 로그인 성공 시 session ID 회전, 로그아웃 시 서버 세션 무효화 및 쿠키 만료. 기본 예시 idle 30분, 절대 수명 예시 8시간은 업무정책 확인 후 결정. localStorage/sessionStorage에 인증 비밀 저장 금지.
- 일반 직원은 필요 범위의 마스터 변경 업로드/검토 결과 조회, 관리자만 기준자료 등록·원본 다운로드·계정관리·승인 기능. UI 표시가 아닌 서버측 endpoint별 authorization으로 집행.
- 공개 route만 명시 allowlist하고 나머지는 기본 차단. CSRF는 세션 쿠키 인증을 사용하는 모든 상태변경 요청(멀티파트 업로드 포함)에 적용. same-origin 원칙, CSP/XSS 방어, frame 제한, Referrer-Policy, 운영 HTTPS/HSTS 검토.
- 로그인 실패 rate limit(계정/IP별, 점진 지연, 과도한 계정 잠금 DoS 고려), 균일한 오류 메시지, 감사로그 비밀값 배제. 비밀번호 정책·재설정·세션 동시성도 확정.
- 기존 `X-Admin-Token` 경로는 병행 유지. 사용자와 모든 기존 호출자를 인벤토리한 뒤 session 기반 인증을 추가하고 회귀검증을 완료하기 전에는 기존 guard를 제거하거나 약화하지 않음.

### 순차 일정 및 출시 게이트
1. 10/22 시연 전에는 로그인 코드/DB migration/보안 필터 변경 금지. DEMO 또는 검증된 기존 토큰 경로 사용.
2. 10/23–26: 요구사항/역할 matrix와 계정 복구 절차 확정, auth 전용 migration·Spring Security filter chain·로그인/logout 구현 및 별도 테스트 환경 검증.
3. 10/27–28: 익명차단·role·CSRF·쿠키·session fixation/timeout/logout·rate limit·기존 토큰/API 회귀 테스트, baseline 무결성/민감 로그 검증.
4. 10/29: 필수 보안/기능 시험이 통과하고 HTTPS·계정 운영 준비가 된 경우에만 로그인 흐름을 시연. 미통과 항목이 있으면 기존 토큰/가상 DEMO로 대체하고 운영 가능하다고 주장하지 않음.

### 필수 시험 및 미완료
- 익명 요청 차단, user/admin 권한 분리, disabled user, 계정 열거 방지, raw password 미저장/hash 검증.
- 로그인 후 session ID 회전, HttpOnly/Secure/SameSite, idle/absolute timeout, logout invalidate, CSRF 누락 요청 거부 및 정상 token 허용(멀티파트 포함).
- rate limit 동작, 로그의 password/token/cookie/session ID 비노출, 기존 토큰 기반 upload/analysis/saved-run의 회귀, baseline 16개/1,332 설비 및 DB BLOB hash 불변성.
- 이번 작업에서는 Spring Security dependency, 사용자 테이블/migration, 로그인 시스템을 구현하지 않음. 계획만 수립.

## 이전 발표 DEMO 커밋 및 이번 작업 보안 상태
- 발표용 브라우저 DEMO 및 10/29 로그인 설계는 `main` 커밋 `6b8c56a` (`Add isolated synthetic master-diff demo`)에 포함.
- 사용자가 선택한 `.env` 비밀값이 노출된 것으로 취급해 새 임의 토큰으로 교체, 값은 어떤 로그에도 출력하지 않음, `.env` mode 600 유지. `process-map-automation` PM2 재기동 후 health 200 및 신규 토큰 API 인증 200 확인.
- 노출된 값은 호출/검증에 사용하지 않음. 외부 배포는 하지 않음.

## Step 6-4 웹 다운로드 연동 — 단계 1 완료 (2026-10-20)

### Run 193 및 새 계획
- 관리자 인증 read-only 조회에서 `changeRunId=193`, `analysisMode=TEST_ONLY`, 1,333 uploaded records, ADDED 1, 삭제/수정/이동 0, 검증 오류 0 확인.
- 실제 저장 `changeItemId=217`은 `C-PW15020`, `SCREEN FILTER`, Item No. `WW-F-AFT10`, 용량 `1,500 ㎥/시`, 수량 `1ea`, PW-01-05, 업로드 행 1501. 폐수/폐기물 양·종류·이동처 필드는 공란이며 합성 시험값과 분리.
- run193 대상 편집계획은 없음을 먼저 MariaDB read-only 확인. 다른 실행의 PLAN-5 ID 5는 API로 조회만 하고 재사용하지 않음.
- run193/item217 전용 신규 계획 ID 32 생성. `TEST_ONLY_READY`, `automaticEditAllowed=true`, `relationStatus=REVIEW_REQUIRED`, `officialApprovalCreated=false`. 대상은 V1.0 PW-01 workbook의 PW-01-05 sheet, 범위 `AI198:AZ208`, row 198 삽입, 도면 연결 shape 90/triangle은 시각 후보만 사용. 시험 선행/후행은 각각 C-PW15009 / C-PW15010으로 계획에 명시.
- 기존 Step 6-2 엔진 API `POST /api/admin/diagram-edit-plans/32/generate-test-only` 실행 결과 HTTP 200. 신규 산출물 `validation-results/2026-10-20-step6-4-web-integration/TEST_ONLY-changeRun-193-plan-32-C-PW15020-PW-01-05.xlsx`, 537,119 bytes, SHA-256 `b809624d7010ec374d5891acff630459fd6bd6cb47f5a491271bb4af92b6fe57`. engine response: TEST_ONLY, REVIEW_REQUIRED, baseline modified false.
- 새 plan/output은 해당 run 및 TEST_ONLY `diagram_edit_plan`에 저장. 기존 PLAN-5/LABELFIX와 공식 기준 파일에 덮어쓰기 없음.

### 단계 1 완료 범위와 다음 단계
- 기존 편집 엔진의 도면 1장 생성 가능성을 확인했으며 신규 파일은 run193/item217을 실제 참조. 전체 15개 도면 cold-index 또는 일괄 생성은 실행하지 않음.
- 단계 1 산출물은 Step 6-2 도면 삽입 엔진만 거친 XLSX이며 변경내역/물질수지 두 시트는 아직 추가되지 않음.
- 단계 2에서 run 193 저장결과 → plan 생성/삽입범위 확인 → 사용자 동의 → 생성 → 재인증 download 버튼 흐름 구현 및 브라우저 시험 완료. 최종 3-sheet 통합 산출물은 단계 3 뒤 새 plan으로 다시 생성해야 함.
- 토큰은 각 API의 `X-Admin-Token` header에만 전달하고 작업 직후 입력칸을 비움. URL/query, localStorage/sessionStorage에는 넣지 않음.

## Step 6-4 웹 다운로드 연동 — 단계 2 완료 (2026-10-20)
- `/master-diff.html`의 saved run 193 화면에서 `changeItemId=217`, `C-PW15020 / SCREEN FILTER` 실제 업로드 정보를 바인딩. run/item ID는 upload/save API 응답에서 가져오고, 버튼은 이번 발표 범위인 run193 + C-PW15020에만 나타남.
- 생성 전 checkbox 두 단계: ① 가상 C-PW15009 → C-PW15020 → C-PW15010 시나리오에 동의해야 계획을 만듦, ② API가 반환한 실제 삽입범위·상태·검토사항을 확인해야 생성 버튼 활성화. 계획은 `TEST_ONLY_READY`, `REVIEW_REQUIRED`, 승인 미생성 조건을 만족하지 않으면 생성 중단.
- 브라우저에서 실제 API 경로 실행: POST plan 33 (`changeRunId=193`, `changeItemId=217`) → `TEST_ONLY_READY`; `AI198:AZ208` 삽입위치 및 C-PW15009/C-PW15010 후보 확인 → TEST_ONLY 생성 HTTP 200 → 인증 GET 다운로드 버튼으로 XLSX 저장.
- 다운로드 산출물 `validation-results/2026-10-20-step6-4-web-integration/TEST_ONLY-web-flow-plan-33.xlsx`, 537,119 bytes, SHA-256 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`. MariaDB plan33 output hash와 local browser download hash 일치.
- 토큰을 매 요청 header로 전달하고 요청 뒤 입력칸을 비움. URL에 토큰 포함 0건, page JS errors 0건, 첨부파일 저장 완료. 최종 console 요약에서 테스트 harness의 `request.url` 문자열을 함수처럼 호출한 문제만 별도 발생; 계획/생성/download는 이미 완료됐고 파일 hash 재검증 통과.
- 단계 2 산출물은 현재 도면만 수정된 XLSX로 변경내역/물질수지 시트가 아직 없음. 다음 단계에서 기존 계산 엔진과 OOXML part-appender를 붙인 최종 새 plan을 생성하며, plan32/33 산출물은 보존하고 덮어쓰지 않음.

---

## Step 6-4 통합 XLSX 재개 — 작업 A 체크포인트 (2026-10-21)
- 재개 시 main `32892eb` 및 working tree clean 확인. 기존 stash `stash@{0}` 설명 `WIP step6-4 workbook enrichment not complete`와 patch/파일 목록 전체 검토. stash에는 `MaterialBalanceExcelExportService.java` 변경 및 untracked `Step6TestOnlyWorkbookEnrichmentService.java`가 보존되어 있었음.
- stash는 적용 후에도 삭제하지 않고 유지. `step6-4-wip-20261021` 브랜치를 main에서 분기해 stash patch를 비파괴적으로 복구. main의 완료 이력과 run193/plan33 다운로드 기능은 변경하지 않음.
- `stringValue(Object)` 중복 원인을 대조: 보존된 기존 helper는 null→null 반환이며 기존 export 계산 모델 호출부에서 사용됨. 신규 중복 helper는 null→빈 문자열 변환이었으나 신규 변경내역 호출부는 `safe(...)`로 null을 처리하므로 중복 선언만 제거하고 기존 반환 동작을 유지.
- `./mvnw -q -DskipTests test-compile` 성공 (2026-10-21; 약 11초). 이는 작업 A의 컴파일 게이트만 통과한 것이며 작업 B/C/D나 통합 XLSX 완료를 뜻하지 않음. 전체 Maven suite는 실행하지 않음.
- 아직 미완료: 저장 plan33 hash/DB 관계를 새 통합 작업 경로에서 다시 검증, 변경내역·물질수지 시트 생성 및 검증, 공식 원본 hash 검증. 검증 전까지 WIP 브랜치의 코드만 보유하고 완료 상태로 main에 반영하지 않음.
- 기존 plan33 산출물은 SHA-256 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc` 그대로 확인. 기존 도면-only XLSX 및 plan32 파일은 보존.

### 작업 B 1차 검증 중단점 (2026-10-21)
- run193/plan33/item217 DB 연결과 `output_content` / `output_sha256`는 read-only SQL로 재확인: plan status `TEST_ONLY_READY`, relation `REVIEW_REQUIRED`, run `TEST_ONLY`, item `ADDED`, DB 저장 content SHA-256과 metadata hash 모두 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`, 길이 537,119 bytes. DB 업로드 master BLOB도 확인했으며 바이트는 출력하지 않음.
- 신규 targeted test `Step6TestOnlyWorkbookEnrichmentIntegrationTest#addsChangeHistoryOnlyToHashVerifiedPlan33`는 Spring context 준비 후 실패. 1 test, 0 failures, 1 error, 0 skipped; 오류: `Generated diagram does not contain the expected TEST_ONLY C-PW15020 insertion` (`validateGeneratedEditDiagram`). 이는 WIP 검증의 고정 셀 좌표 가정이 저장된 plan33 XLSX와 맞지 않는 것으로 확인된 것이며 아직 원인 좌표를 조사 중.
- 첫 오류 뒤 저장된 plan33 worksheet를 read-only로 확인: 신규 설비는 AI198, SCREEN FILTER는 AI201, Item No. AI202, 규격 AI203=`1,500㎥/시`, 수량 AI204=`1ea`. 상태는 셀 AI197이 아니라 DrawingML shape `TEST_ONLY / REVIEW_REQUIRED status`의 text `TEST_ONLY /REVIEW_REQUIRED`로 표시됨. 기존 flow connector 신규 ID 160, 상태 shape ID 161.
- 해당 실물 위치 기준으로 검증 로직을 수정했고 `test-compile` 통과. 재시도한 history-only targeted test: 1 test, 0 failures, 1 error, 0 skipped; 오류 `Unexpected TEST_ONLY worksheet print dimension` (`findWorksheetLastRow`가 A1:M 범위만 허용했으나 변경내역 시트 dimension은 A1:C...임). 이 오류는 OOXML 시트 생성 이전에 차단됨. 출력 파일은 아직 생성되지 않았음.
- 테스트는 read-only DB transaction이었고, DB migration/계산 저장/공식자료 쓰기 없음. 재개 위치: 범용 dimension parser를 `A1:<열><행>`으로 허용하되 시트 열 범위를 임의로 바꾸지 않고 수정한 뒤 단일 history test 재검증.
- WIP 코드의 최신 상태는 미커밋이며 `CHECKPOINT.md`만 별도 커밋으로 기록. stash는 유지, main은 변경되지 않음.

### 작업 B 완료 — plan33 변경내역 시트 (2026-10-21)
- plan33 저장 출력 hash를 DB BLOB/hash 및 파일 `TEST_ONLY-web-flow-plan-33.xlsx`에서 재확인한 뒤 저장된 업로드 master BLOB에서 item 217을 재파싱. 새 파일은 plan33 XLSX를 입력으로 사용했으며 run/item/plan 관계가 일치하지 않거나 출력 hash 불일치면 중단하도록 경로를 한정.
- `변경내역` worksheet를 새 part로 추가. 실제 저장값: run 193, item 217, plan 33, V1.0, TEST_ONLY, ADDED, C-PW15020 / WW-F-AFT10 / SCREEN FILTER / 1,500㎥/시 / 1ea, 대상 PW-01-05, AI198:AZ208, 실제 승인 아니오 / REVIEW_REQUIRED. beforeValues는 저장자료에서 NULL/빈 값으로 표시, afterValues는 저장된 JSON 원문. 실제 폐수/폐기물 관련 필드는 업로드 마스터 공란일 때만 검증을 계속하도록 차단검사.
- 파일: `validation-results/2026-10-21-step6-4-integrated/TEST_ONLY-run193-plan33-change-history-only.xlsx`, SHA-256 `d4f21099d3c2e1049876bbf18e0e88604b936475c2b0c508a695165e813451ef` (중간 B 산출물; 최종 통합본 아님). plan33 원본 XLSX hash는 변경 없이 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`.
- 선택 테스트 `Step6TestOnlyWorkbookEnrichmentIntegrationTest#addsChangeHistoryOnlyToHashVerifiedPlan33`: 1/1 PASS, failures/errors/skips 0. Apache POI 재열기 성공, 6개 기존 시트+신규 변경내역 시트 확인, 기존 모든 OOXML part(허용된 workbook metadata를 제외한 XML/도면/관계/style 포함) byte-identical, 신규 part 정확히 1개 `xl/worksheets/sheet7.xml` 확인. print area `변경내역!$A$1:$C$30`.
- 신규 시트에서 사용된 style 1/79/160은 모두 맑은 고딕이며 글꼴 색은 검정(default/theme black). 도면-only 원본 및 계획 파일은 덮어쓰지 않음.
- B 완료는 변경내역 시트 전용 중간본에 대한 검증이며 물질수지 검토 시트와 최종 통합본은 미완료. 기존 실패 원인은 CHECKPOINT에 보존. 해당 dimension parser 개선은 일반 A1:<끝열><행> 표기를 받되 별도 시트 print area를 그대로 유지.
- 다음 작업 C/D 검증 전까지 main 반영/배포/웹 다운로드 UI 연결 금지. stash는 보존, main은 변경 없음.

### 작업 C 통합 검증 중단점 (2026-10-21)
- `enrichStoredPlan(33)` 호출 targeted test는 DB의 실제 plan33 output hash를 확인하고 변경내역/물질수지 worksheet 생성 후 Apache POI 재열기 단계까지 도달했으나 test assertion 실패: expected sheet count 8 (기존 6 + 신규 2), 실제 반환 7 (기존 6 + 변경내역 1). 1 test, failures 1, errors 0, skipped 0.
- 원인 확인: 물질수지 시트 append 결과 `balanceSheet.workbookBytes()`가 아닌 변경내역만 포함한 `withChangeHistory`를 반환 record에 실수로 전달. 이후 이 반환부를 수정해 최종 byte array와 SHA-256이 balance sheet append 결과를 사용하도록 고침.
- 이 실패는 파일 저장 assertion 전에 발생하여 통합본 파일은 생성되지 않았음. DB transaction은 read-only, `material_balance_simulation` 전후 건수 확인을 테스트하도록 구성했고 계산기는 in-memory pure calculation. 공식 baseline/relation 데이터 변경 없음.
- 실패 원인을 바로잡기 위해 최종 return을 `balanceSheet.workbookBytes()`와 그 hash로 교체했으나, 이 마지막 1-line 변경은 `test-compile`/통합 재실행 전이라 미검증. 현재 중단 지점은 이 수정 상태이며 다음 실행은 이 지점에서 시작할 것.
- 통합검증 성공 전 C 완료나 최종 산출물로 보고하지 않음. 최종 result 파일 이름은 코드에 지정됐으나 생성되지 않았음. B 완료 중간본은 `TEST_ONLY-run193-plan33-change-history-only.xlsx`만 존재.

### Step 6-4C 완료 — 최종 TEST_ONLY 통합 XLSX (2026-10-21)
- 시작 시 실제 branch `step6-4-wip-20261021`, HEAD `c0bd6f4` (`genspark auto-backup`), working tree clean, stash `stash@{0}` 유지 확인. stash 재적용·삭제, main checkout/수정/merge는 하지 않음. `c0bd6f4` diff는 이전 미검증 반환 수정만 포함하며 `balanceSheet.workbookBytes()` 및 최종 bytes SHA-256을 반환함.
- `./mvnw -q -DskipTests test-compile`: PASS. 지정된 통합 전용 테스트만 실행: `Step6TestOnlyWorkbookEnrichmentIntegrationTest#enrichesOnlyStoredPlan33AndPreservesOriginalWorkbookParts`, 1 run / 1 pass / 0 failures / 0 errors / 0 skipped. 전체 Maven suite, 전체 도면 인덱스 분석 및 PDF 렌더링은 실행하지 않음.
- 최종 파일 `validation-results/2026-10-21-step6-4-integrated/TEST_ONLY-run193-plan33-C-PW15020-integrated.xlsx`, 542,492 bytes, SHA-256 `216ba9923fca133f831694d0a5b7f03694bb50bf1563d27c3decaefa530d2447`.
- Apache POI로 생성 바이트를 열고 8개 worksheet를 확인: 기존 `PW-01-01`~`PW-01-06` 여섯 장 + `변경내역` + `물질수지 검토`. 파일에 저장한 뒤 다시 열어도 총 8장과 두 신규 시트가 존재.
- `변경내역`에 저장된 run 193 / item 217 / plan 33, V1.0, TEST_ONLY, ADDED, C-PW15020, WW-F-AFT10, SCREEN FILTER, 1,500㎥/시, 1ea, AI198:AZ208 및 REVIEW_REQUIRED/승인 미생성 상태를 확인. beforeValues 공란, afterValues 저장 JSON과 일치.
- 물질수지 시트에서 시험 전용 100, 0.5, 2.5, 97 ton/day와 `100 = 0.5 + 2.5 + 97 ton/day`, C-PW15020 잔차 0을 확인. 실제 자료 부족 문구 `실제 발생량·이동처·승인 연결 근거 부족으로 검증 불가` 및 가상 TEST_ONLY 경고 확인. 실제 유량/이동처를 채우지 않았음.
- 입력 plan33 파일 SHA-256 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`는 전후 동일. 기존 XLSX OOXML part는 workbook metadata part 4개를 제외하고 모두 byte-identical, 정확히 신규 worksheet part 2개만 추가. 공식 도면 셀/도면 part 변경 없음. DB baseline_document, equipment_record, CONFIRMED/REVIEW_REQUIRED relation, material_balance_simulation counts의 테스트 전후 동등 assertion 통과; migration V13 상태 유지.
- `변경내역` 및 `물질수지 검토` 사용 style은 맑은 고딕이며 검정색(테마 검정 또는 기본 검정). 변경내역 전용 중간본은 유지(`d4f21099d3c2e1049876bbf18e0e88604b936475c2b0c508a695165e813451ef`), plan33/plan32도 덮어쓰지 않음.
- Step 6-4C 완료. 코드 및 테스트는 WIP 브랜치에만 보존하고 main에 병합하지 않음. Step 6-4D 전체 구조 검증, 웹 다운로드 버튼 변경, 다중 도면 ZIP은 이번 작업에서 시작하지 않음.

### Step 6-4C 요약 레이아웃 회귀 재현 및 수정 (2026-10-21)
- 재개 branch `step6-4-wip-20261021`. 수정 전 focused test 실행에서 `MaterialBalanceExcelExportIntegrationTest` 3건 중 2건 실패(1 failure, 1 error), 두 시트 통합 경로 `Step6TestOnlyWorkbookEnrichmentIntegrationTest`는 2건 통과.
- 재현 원인: `buildResultsWorksheet`의 새 요약 헤더("물질수지 검토 결과", 자료 부족 경고)를 공통 적용해 기존 고정 V5 `TEST_ONLY Results` export가 요구하는 기존 TEST_ONLY 제목/경고 문구를 덮어씀. 고정 V5 validator는 첫 셀의 `TEST_ONLY` 및 `공식 물량 미변경` 문구를 검사했으며 API 경로도 동일 예외로 HTTP 400을 반환.
- 수정: `actualDataStatus == null`인 기존 V5 경로는 기존 제목 `TEST_ONLY 계산 결과 — 공식 물량 미변경`과 기존 도면 보존 경고를 유지. 저장 plan33 통합 생성처럼 actual-data 상태를 전달하는 경로는 새 `물질수지 검토 결과` / 실제 자료 부족 / TEST_ONLY 가상값 3행 요약을 사용. 두 시트 통합 전용 경로는 기존 요약 레이아웃을 유지.
- 로컬 MariaDB 권한을 `scripts/configure-local-db.sh`로 복구한 뒤 `.env`를 test process 환경에 로드해 실행. DB schema version 13, migration 변경 없음.
- 최종 targeted 명령: `./mvnw -Dtest=MaterialBalanceExcelExportIntegrationTest,Step6TestOnlyWorkbookEnrichmentIntegrationTest test` — 총 5 tests, failures 0, errors 0, skipped 0, `BUILD SUCCESS` (72초).
- 전체 Maven suite는 실행하지 않음. 수정은 `step6-4-wip-20261021`에만 있으며 main merge/deploy는 수행하지 않음.

### Step 6-4C 사용자용 XLSX 재생성·실물 검증 (2026-10-21)
- 브랜치 시작 상태 `step6-4-wip-20261021` / HEAD `0c58db9` / clean, `stash@{0}` 유지 확인. main, stash, 원본 파일, 기존 통합 파일 및 기존 plan33 파일은 수정하지 않음.
- 새 파일 `validation-results/2026-10-21-step6-4-integrated/TEST_ONLY-run193-plan33-C-PW15020-PW-01-05-user-verified-20261021.xlsx` 생성. Apache POI 저장 후 재열기 확인: 기존 도면 6장과 `변경내역`, `물질수지 검토` 포함 8장. 마지막 `물질수지 검토` 시트를 active tab으로 지정.
- plan33 저장 DB row 재조회: plan 33 / run 193 / item 217 / TEST_ONLY_READY / REVIEW_REQUIRED / ADDED / C-PW15020. 같은 run의 업로드 master 재파싱과 출력 셀 대조: WW-F-AFT10, SCREEN FILTER, 1,500㎥/시, 1ea. `변경내역`은 86개 nonempty cell. POI/OOXML: A5 신규 설비 추가, B6 C-PW15020, B7 WW-F-AFT10, B8 SCREEN FILTER, B9 1,500㎥/시, B10 1ea, B13 PW-01-05, B14 AI198:AZ208, B15 run 193, B17 plan 33, B18 TEST_ONLY / REVIEW_REQUIRED.
- `물질수지 검토` 첫 화면에 C-PW15020 / SCREEN FILTER, 실제 물질수지 검증 불가, TEST_ONLY 가상 계산, 자료 추가 필요를 한글로 명시. 표에는 유입 100, 후단 이동 97, 별도 폐수 2.5, 폐기물 0.5, 출구합계 100, 잔차 0 ton/day. 합계/잔차 공식 및 cached numeric values를 POI·OOXML에서 확인. 시뮬레이션 ID와 상세 송수신·노드·매핑 자료는 뒤쪽 상세 영역에 보존.
- OOXML part map에서 시트 7/8이 신규 `xl/worksheets/sheet7.xml`, `sheet8.xml`로 등록됨. 기존 도면 worksheet 1–6 및 관련 part 보존 테스트 통과. DB baseline/equipment/relation/simulation count 전후 동등. Migration V13 유지.
- 신규 2개 시트에 대해 LibreOffice PDF 35페이지 전체 렌더: 변경내역 33–34페이지, 물질수지 35페이지 확인. 새 preview PNG `rendered-user-verified/change-history-preview-user-verified.png`, `rendered-user-verified/material-balance-preview-user-verified.png`. 스타일 맑은 고딕, 검정(테마 black), wrap 및 지정 열 너비. 물질수지 요약 print area A1:C25 / landscape / 2페이지 높이.
- 파일 SHA-256 `fd0ab457b2efeebc1086893e54e3fdfd79c4e83924082d2ee0fc2482732a6804`. 공유 URL 다운로드 후 SHA-256 동일, ZIP integrity PASS, 다운로드 파일 POI 재열기 및 8시트 확인.
- 최종 targeted 명령 `./mvnw -Dtest=MaterialBalanceExcelExportIntegrationTest,Step6TestOnlyWorkbookEnrichmentIntegrationTest test`: 5/5 PASS, failure/error/skipped 0. V5 회귀 클래스 3/3 PASS: 기존 제목/경고, 셀 보존, API 첨부 다운로드 경로 확인.
- 보호 hash 전후 동일: official V1.0 `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0`; plan33 원본 `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`. 전체 Maven suite 및 전체 대상은 이번에도 실행하지 않음. Step 6-4D / Step 6-5 시작 안 함.

### Step 6-4D 중단 후 재개 — G POI 재열기 (2026-10-21)
- 기존 POI audit의 `balancePhysical` 예상값 268은 잘못된 기준이었다. Apache POI 재실행에서 파일을 read-only로 열어 8 sheets / active index 7, `변경내역` nonempty 86, `물질수지 검토` physical cells 418 및 nonblank 250을 구분 확인. OOXML/openpyxl 기준의 value-bearing/non-None 268은 물리 셀 수와 다른 지표이며, 최초 실패는 이 기준 혼동 때문으로 판단. XLSX는 수정하지 않음.
- formula `SUM(B10:B12)` cached 100, `B9-B13` cached 0, print area `'물질수지 검토'!$A$1:$C$25`를 재확인. 최종 `POI_AUDIT=PASS`. 첫 재시도는 셸 환경변수 전달 누락으로 파일 경로를 못 읽었고, 두 번째 계수 실행에서 nonblank=250을 반영해 PASS; 파일 손상 판정 근거가 아니며 최종 결과는 정상.
- 기존 focused JUnit 5/5 PASS 및 LibreOffice 35페이지 렌더/페이지 33–35 시각확인 결과를 재활용하고 전체 suite/PDF 재실행은 하지 않음. 물질수지 상세 검토 자료가 worksheet에 남아 있으나 인쇄 범위 A1:C25 바깥에 놓인 사실을 감사 보고서에 명시.

### Step 6-4D 최종 A–H 감사 기록 (2026-10-21)
- A 완료: 공식 V1.0 XLSX 16개 각각 로컬 SHA = manifest = MariaDB metadata = BLOB SHA (16/16). 대표 파일 SHA `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0`.
- B 완료: read-only DB snapshot baseline_document 16 / equipment_record 1,332 / schema V13. 통합 테스트의 기준 데이터 count 전후 동등 assertion PASS.
- C 완료: `CONFIRMED=0`, `REVIEW_REQUIRED=1`, 통합 테스트 전후 관계 수 동등. plan33은 run 193/item 217/TEST_ONLY_READY/REVIEW_REQUIRED/ADDED이며 승인 관계 생성 없음.
- D 완료: plan33 원본 대비 worksheet part 1–6 모두 byte-identical, 추가 part는 sheet7.xml/sheet8.xml 두 개, 제거 없음. 신규 sheet 등록을 위한 공통 workbook metadata part 네 개만 변경.
- E 완료: drawing5 105 objects/anchors, connector 92, ordinary shape 13, duplicate ID 없음, source/final counts와 ID 동일.
- F 완료: PW-01-05 part byte-identical, merges 85, print area A1:BJ230, titles rows 2:3, portrait/paper 8/scale 89/fitToHeight 0 보존. 물질수지 A1:C25 유지; 상세 데이터 26–69행의 non-None 217개는 인쇄 범위 밖이며 인쇄영역은 수정하지 않음.
- G 완료: 기존 POI/화면 검증 근거 재사용 후 POI 재열기 `POI_AUDIT=PASS`; 8 sheets, active 7, history nonempty 86, balance physical 418/nonblank 250, 합계 공식 cached 100, 잔차 공식 cached 0, print area A1:C25. 잘못된 268 physical-cell assertion은 감사 코드 기준 오류로 정정.
- H 완료: 저장본 및 공유 다운로드 XLSX SHA-256 `fd0ab457b2efeebc1086893e54e3fdfd79c4e83924082d2ee0fc2482732a6804` 일치, ZIP integrity PASS.
- 전체 결과와 검사 정의는 `STEP6_4_FINAL_AUDIT.md` 참조. 공식 XLSX/DB/plan33/통합 XLSX read-only 유지. 전체 Maven suite/PDF 재실행, main merge, stash 조작, Step 6-5 개발은 하지 않음.

### Step 6-5 착수 — 현재 상태 및 구현 범위 (2026-10-21)
- 시작 상태: branch `step6-4-wip-20261021`, HEAD `c1a4bdd`, clean tree, `stash@{0}` 보존. `CHECKPOINT.md`와 `STEP6_4_FINAL_AUDIT.md`를 읽고 Step 6-4 A–H 및 보호 해시를 확인. main 미병합.
- 구조 확인: `master-diff.html`에는 저장 run 조회, 편집계획 생성, 도면 전용 `generate-test-only`/`download` 흐름이 존재. `DiagramEditPlanService`는 계획/도면 결과를 처리하며, `Step6TestOnlyWorkbookEnrichmentService`는 별도지만 HTTP에 연결되지 않고 plan 33/run193/item217만 허용한다. 현재 웹 다운로드가 통합본이 아니라 도면 전용 결과를 반환하는 원인을 확인.
- 구현 범위는 우선 검증된 run 193 / item 217 / PW-01-05 TEST_ONLY 시나리오로 제한한다. UI에서 받은 실제 run/item/plan ID를 서버에 전달하고 서로 일치하는지 재검증한다. 기존 도면 전용 output BLOB은 덮어쓰지 않고 최종 통합본을 별도 보존하는 방안을 구현할 것.
- 물질수지 시험값 100/97/2.5/0.5는 별도의 명시적 선택에서만 사용. 선택되지 않으면 숫자를 생성하지 않고 실제자료 부족/검증 불가만 표시한다. 승인 관계는 REVIEW_REQUIRED 유지.
- B/C 1차 구현 완료, targeted 검증 대기: 새 V14 테이블 `diagram_edit_integrated_output`에 도면 중간본과 분리하여 검증된 통합 XLSX·SHA·run/item/plan·시나리오를 저장. `POST /api/admin/diagram-edit-plans/{planId}/generate-integrated-test-only`와 인증된 `GET .../download-integrated` 연결. API가 실제 run/item/plan을 확인하고 기존 `DiagramEditPlanService.generate` 호출 후 Step 6-4 enrichment/POI 재열기/hash 저장을 수행. 기존 `output_content`를 덮지 않음.
- UI는 선택된 실제 run/item/plan ID를 전송하고 scope 제한을 표시. 가상 시험값은 별도 checkbox 선택 시만 사용하며, 미선택 생성은 수량·합계·잔차를 채우지 않는 실제자료 검토 전용 시트로 구성. 토큰은 계속 memory-only input에서 X-Admin-Token 헤더로만 보내고 생성/다운로드 요청 후 비움. Demo는 실제 API 경로에 진입하지 않음.
- `./mvnw -q -DskipTests test-compile`: PASS. V14 migration 포함. Targeted suite `./mvnw -Dtest=DiagramEditPlanIntegratedControllerTest,Step6IntegratedWorkbookDeliveryIntegrationTest,Step6TestOnlyWorkbookEnrichmentIntegrationTest,MaterialBalanceExcelExportIntegrationTest test`: 10/10 PASS, failures/errors/skips 0 (약 1m45s). no-synthetic path에서 시험숫자·잔차 미생성, opt-in TEST_ONLY 값, 8시트/변경내역/실제자료 검증불가 시트, DB 저장/재다운로드 SHA 및 run/item mismatch 차단 검증. V5 회귀 3/3 PASS. 익명 integrated generation/download MockMvc 401 PASS. Flyway V14가 local schema에 적용됨; official baseline/relations 데이터 변경 검증은 suite assertions 범위 내.
- B/C API/UI와 서비스 테스트까지 진행. D/E는 아래 후속 완료 기록에서 갱신.

---

### Step 6-5 D/E 브라우저 다운로드 및 POI 검증 완료 (2026-10-21)
- 실제 브라우저 화면에서 저장 run 193 조회 → 실제 item 217 / 저장 계획 33 조회·선택 → TEST_ONLY 통합 XLSX 생성 → 다운로드 버튼으로 파일 저장까지 완료. 지원 범위는 run 193 / item 217 / C-PW15020 / PW-01-05에 한정하며 일반화하지 않음.
- 통합 생성 API는 `POST /api/admin/diagram-edit-plans/{planId}/generate-integrated-test-only`에서 JSON metadata를 반환하고, 다운로드 API `GET /api/admin/diagram-edit-plans/{planId}/download-integrated?includeSyntheticTestValues=true|false`가 저장된 XLSX attachment를 반환. 계획 조회는 `GET /api/admin/diagram-edit-plans/by-run/{changeRunId}/items/{changeItemId}`. 브라우저는 실제 run/item/plan 값을 전송하고 서버가 연결을 재검증.
- 실제 다운로드 `/tmp/step65-browser-download/TEST_ONLY-run193-item217-plan33-PW-01-05-integrated-synthetic-values.xlsx`: 542,898 bytes, SHA-256 `279452398e84b9cb9466ad62f0310d4cbc15dcc0160ae20103d82219153833f5`. 생성 API metadata, MariaDB integrated-output BLOB, 브라우저 다운로드 파일 SHA가 일치. 기존 도면 전용 plan33 산출물 hash `d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc` 및 BLOB은 보존.
- 실제 브라우저 다운로드를 Apache POI로 다시 열어 `POI_DOWNLOAD=PASS`: 8개 시트, active index 7, C-PW15020/run193/plan33, 시나리오·실제 물질수지 검증 불가 안내, 합성 선택값 100/97/2.5/0.5, 합계 formula `SUM(B10:B12)` cached 100, 잔차 formula `B9-B13` cached 0, print area A1:C25 확인. `unzip -t`도 오류 없음. 공식 V1.0 대표 파일 SHA `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0` 보존.
- 브라우저 시험에서 익명 generation/download 각 HTTP 401, run 불일치 HTTP 409, 다른 plan ID HTTP 400. JS page error 0건, 토큰은 브라우저 저장소에 저장되지 않음. 승인 상태는 REVIEW_REQUIRED 유지, CONFIRMED 관계 생성 없음. 합성 물질수지 값은 checkbox 명시 선택 시에만 포함하며 미선택 경로는 값·합계·잔차를 임의 생성하지 않음.
- `DiagramEditPlanControllerIntegratedWorkbookTest`가 구 API 계약(raw XLSX/header)을 기대하던 오류를 현재 생성 API의 JSON metadata 계약으로 수정. 해당 클래스 4/4 PASS. 최종 합산 명령 `./mvnw -q -Dtest=DiagramEditPlanIntegratedControllerTest,DiagramEditPlanControllerIntegratedWorkbookTest,Step6IntegratedWorkbookDeliveryIntegrationTest,Step6IntegratedWorkbookStorageIntegrationTest,Step6TestOnlyWorkbookEnrichmentIntegrationTest,MaterialBalanceExcelExportIntegrationTest test`: 총 15/15 PASS, failures/errors/skips 0. 테스트 process에 `.env`를 로드하지 않은 첫 호출은 DB 비밀번호 미설정으로 Spring context가 시작되지 않았으며, 환경변수를 정상 로드해 재실행한 최종 명령은 성공.
- storage test는 formula type assertion 포함. 과거 한 차례 180초 timeout은 assertion 실패가 아니었으며 PM2 중지 후 재실행 37.9초에 1/1 PASS. V5 회귀 3/3 PASS. 전체 Maven suite는 실행하지 않음.
- 최종 시점 Git: branch `step6-4-wip-20261021`, 작업은 해당 WIP에 한정, main 미병합. 기존 `stash@{0}` `WIP step6-4 workbook enrichment not complete` 보존. 전체 Maven suite, 다중 도면 ZIP, 승인 관계 변경, 로그인, 공식 도면 갱신 및 배포는 수행하지 않음.
- 브라우저 다운로드 XLSX 공유 파일 wrapper를 발급함: `https://www.genspark.ai/api/files/s/9pM2LKcS`. 업로드 파일 SHA-256은 `279452398e84b9cb9466ad62f0310d4cbc15dcc0160ae20103d82219153833f5`와 일치.

---

## Step 7-1 — 1단계 분석·설계 체크포인트 (2026-10-21)
- 시작 위치는 Step 6-5 검증 커밋 `c1904db877d52457f47b0be8e93cf5d584ff8117`. 현재 개발 브랜치 `step7-1-wip-20261021`을 분리 생성했으며 main은 `32892eb27f993cc05685a3cd3dd8030275dec5f1` 그대로. 작업 트리는 조사 시 clean. 기존 stash `stash@{0}` (`WIP step6-4 workbook enrichment not complete`)를 보존.
- 기존 `MaterialBalanceCalculator`는 이미 명시된 TEST_ONLY edge의 노드별 물질/단위 집계, residual, mixed-unit 계산불가, transfer mismatch exclusion, downstream graph reachability/cycle-safe traversal을 구현. 다만 edge quantity를 입력으로 받는 고정 유량 calculator이며 unknown flows를 연립식으로 풀거나 제약 위반 음수 edge를 진단하지 않음.
- `MaterialBalanceService`는 TEST_ONLY simulation payload hash/idempotency와 transfer 저장, CONFIRMED relation의 DB reviewer/status/history/endpoints/quantity/unit 검증을 수행. `MaterialBalanceExcelExportService`는 기존 V5/TEST_ONLY XLSX 시트 추가에 사용되나 현재 output model은 기존 request 형태와 엮여 있음. `DiagramEditPlanService` / `EquipmentInsertionPlanningService`는 도면 추가 계획이며 물질수지 연결망 solver가 아님.
- 설계 결정: 실제 도면/관계 DB 및 API에는 쓰지 않는 별도 pure TEST_ONLY 유향-network model 및 linear solver를 추가. 기존 고정-edge `MaterialBalanceCalculator`, `MaterialBalanceService`, Step 6-5 웹/API/DB migration은 수정하지 않고, 새 solver는 이와 상호보완적으로 둠. solver는 모든 입력 edge와 node/endpoint/evidence/review scope를 검증하고 누락 시 숫자 추정 없이 unresolved status 반환.
- 새 계산 모델은 equipment-number node, process/type/boundary/source document/sheet/review metadata와 고유 directed edge, material class/type/unit/quantity/source basis/user-supplied/fixed-vs-solved/evidence/review status를 분리한다. 폐수/약품/폐기물과 각 unit을 독립적인 conservation system으로 취급, unit conversion 없음. 내부 process node는 edge 물량 합계 보존식, external boundary는 전체 external inflow/outflow 집계만 함.
- 미지 edge flow는 각 substance/unit별 선형 보존식으로 풀고, full rank가 아니면 `UNDERDETERMINED`로 추가 조건 요청. 해가 음수면 실제 유량으로 수락하지 않고 offending edge/node, algebraic result, nonnegative constraint violation을 보고. cycles는 연립방정식으로 한 번에 풀고 단순 반복 누적하지 않음. Web UI, production API, DDL, relation approvals 및 도면 편집은 Step 7-1 범위에서 제외.
- 시나리오 설계: A 외부 유입 100+100과 transfer 20, P2 residual outlet 80, P1 receiving downstream 120, 전체 external out 200. B 같은 base graph에서 20/10/50 독립 solver invocation (잔존 scenario state 없음). C fixed transfer 120와 source 100에서 downstream algebraic -20을 nonnegative violation으로 명시. D fixed recirculation pair 20/30에서 P1 external input/out 100/100 유지 및 process throughput 120/130, P2 loop balance 확인; 미지수 cycle 미지정이면 underdetermined. E unresolved endpoint/evidence/review는 calculation unavailable.
- 산출물 초안은 (1) 합성 결과 workbook과 (2) 기존 전체목록/공식 V1.0을 건드리지 않는 별도 xlsx 템플릿으로 분리. 템플릿 sheets: `기준 연결망`, `배관·유량변경`, `시뮬레이션 설정` 및 셀/열 검증 안내. 공식 V1.0 전체목록 파일과 15개 도면은 읽기 전용으로 둠.
- 이 1단계 체크포인트 시점에는 모델/solver/test/Excel 산출물이 아직 구현되지 않았음.

---

## Step 7-1 — 2·3·4단계 합성 엔진 및 Excel 산출물 체크포인트 (2026-10-21)
- 새 `DirectedProcessNetwork` 모델은 설비/외부 경계 node 및 고유 방향 edge로 구성. Edge에 출발·도착 설비/공정, wastewater/chemical/waste 구분, material type/unit, connection type, quantity basis, user-supplied/fixed, evidence, review status를 담음. Node에 관리번호, 공정, 유형, 경계, 원본 도면/시트, 상태를 기록. ProcessTerm은 검증된 generation/consumption을 부호 있는 항으로 표현.
- 기존 `MaterialBalanceCalculator`를 API facade로 재사용하고 새 solver는 고정 edge quantity 계산과 구분되는 선형 network solving capability로 패키지 private helper로 위임. 기존 `MaterialBalanceService`, DB schema, relation/approval API, Step 6-5 controller/UI/migrations는 수정하지 않음. Step 6-5 workbook output generator는 재사용하지 않음(고정된 run193/plan33 전용), 별도 detached TEST_ONLY workbook writer로 시나리오 결과/입력 템플릿을 생성.
- 각 물질 구분·종류·단위별 노드 보존식을 Gaussian elimination/RREF로 한 번 풀고 unknown edge가 full rank가 아니면 UNDERDETERMINED, 서로 맞지 않는 보존식이면 INCONSISTENT, 음수 solution이면 CONSTRAINT_VIOLATION으로 표시. 음수를 재분배·0 보정하지 않음. Source/sink 외부 경계는 separate external inflow/outflow로 합산. 폐수·약품·폐기물과 서로 다른 단위를 한 수지로 합산하지 않고 환산하지 않음.
- source/target equipment IDs, material/unit/type, TEST_ONLY review scope, evidence, connection ID uniqueness, fixed-vs-unknown consistency를 solver에서 검증. 불명확 endpoint/evidence/status는 `CALCULATION_UNAVAILABLE`로 종료. Impact scope는 변경 edge endpoint의 연결성분에서 설비 관리번호와 공정 코드를 추적; 위치/마스터 행 순서는 쓰지 않음.
- `DirectedProcessNetworkSolverTest` 8/8 PASS, failures/errors/skips 0; test-compile PASS. 검증: scenario A P1/P2 external input 100+100, moved 20, P2 downstream 80, P1 downstream 120, external output total 200; scenario B same immutable base에서 20/10/50 재실행해 downstream pairs (80,120)/(90,110)/(50,150), 모든 경우 total external 200; C moved120 gives P2 downstream -20, status CONSTRAINT_VIOLATION, no correction; D recycle20→30 adjusts P1 throughput 120→130 while external input/output stays100 and process balances zero; E unresolved ID/evidence returns CALCULATION_UNAVAILABLE; unspecified closed cycle returns UNDERDETERMINED with null quantities. Also verifies explicit generation 20+10→30, fixed imbalance residual 10, and wastewater 10 ton/day, chemical 2 kg/day, sludge 0.5 ton/day remain separate.
- Generated test files (all in gitignored validation-results, not official master/documents): `TEST_ONLY-network-scenario-A.xlsx` SHA-256 `5ca735a2eae305a2a08a57f52e699961805927bdcfa1338c009087b351729dc3`; `TEST_ONLY-network-scenario-matrix.xlsx` SHA-256 `e17c906945faeaa57d7373fb4696b70cd9adbce2402199a1ac7b91abeb8f226f`; `TEST_ONLY-process-network-input-template.xlsx` SHA-256 `9088249220140200c5fe76876501864f462a91cdc23644dac8d9018897bb13a0`. POI re-open of scenario, matrix and template, required sheet headers and scenario statuses passed; all three ZIP integrity checks passed. Share wrappers: scenario A `https://www.genspark.ai/api/files/s/hi7BEk1W`, matrix `https://www.genspark.ai/api/files/s/guEE5w0E`, template `https://www.genspark.ai/api/files/s/h3uJU9hw`.
- Template includes `기준 연결망`, `배관·유량변경`, `시뮬레이션 설정`, `작성 안내`; 18/16/10 data columns respectively. It is a detached template and does not change or copy into official V1.0 master. No DB writes or migrations were made.
- 최종 합산 targeted suite: solver 8 + Step 6-5 controller/delivery/storage/enrichment/V5 회귀 15 = 23/23 PASS, failures/errors/skips 0. 전체 Maven suite는 기존 과거 resource exit 제한 때문에 실행하지 않음. `scripts/verify-baseline-hashes.py` PASS: 공식 V1.0 local/manifest/MariaDB metadata/BLOB 16/16 동일. Step 6-5 run193/plan33 파일은 재차 보존 검증됨.
- Step 7-1 기능과 합성 Excel을 구현·검증 완료. 미수행 범위: UI/API 연결, actual process-network DB import, 실제 도면/공식 물량 반영, 승인 관계 생성, 다중 도면 편집 및 외부 배포. README/CHECKPOINT 갱신 후 검증된 소스·테스트·문서를 WIP 브랜치에 커밋한다.

---

## Step 7-2 시작 — 실제 팀원 물질수지 DB 파일 대기 (2026-10-21)
- 시작 시 Step 7-1 HEAD `95ebcf17f991396825d758d4bf0892b64fc1ef23`, branch `step7-1-wip-20261021`, clean. 새 분리 branch `step7-2-wip-20261021`을 생성. main은 `32892eb27f993cc05685a3cd3dd8030275dec5f1`, 기존 stash `stash@{0}` `WIP step6-4 workbook enrichment not complete` 보존.
- 지정 파일명 `0.3 250808 물질수지 DB.xlsm`은 `/home/user/webapp`의 recursive glob, `/home/user`의 지정 filename glob 및 `/home/user/uploaded_files`에서 발견되지 않음. `/home/user/uploaded_files`에는 공식 기준자료용 XLSX 16개만 있음. `/mnt/data`와 `/mnt/user-data`는 이 환경에 없고 `/mnt`에는 aidrive만 보임. 따라서 팀원 XLSM의 sheet/schema/Power Query/행·유량 데이터는 실제로 읽지 않았으며 row count, DB 잔차, 중복/0/blank 및 DB 연결을 보고하지 않음.
- 기준자료 확인은 읽기 전용: 공식 `data/reference/`는 15개 공정 XLSX + `전체 목록.xlsx` 및 SHA256SUMS. `scripts/verify-baseline-hashes.py`: local/manifest/MariaDB metadata/BLOB 16/16 PASS. DB snapshot: V1.0 locked, baseline_document 16(1 MASTER+15 PROCESS), equipment_record 1,332, workbook_sheet 62, CONFIRMED relation 0, REVIEW_REQUIRED relation 1, equipment_flow 0.
- PW-01 인덱스는 workbook 1개 / 6 sheets, drawing object inventory 73 SHAPE + 986 CONNECTOR; 전체 PW-01 connector의 explicit start/end shape bindings 0. PW-01-05 기존 connector shape 149 관련 `process_relation` row 30579는 source/target equipment NULL, relation type UNKNOWN, quantity/unit NULL, status/analysis REVIEW_REQUIRED, reviewer 없음. 이 row는 후보일 뿐 도면 endpoint나 물량을 확정하지 않음.
- Step 7-1 directed solver와 `process_relation`/`drawing_object`/`equipment_mapping`/`equipment_flow` schema를 읽기 전용 조사. 팀원 XLSM 없이는 실제 열명, Power Query 정의, 물량·단위·기간·0/blank 의미 및 공식도면 연결 비교를 구현·검증하지 않음. 매크로는 실행하지 않았고 공식 XLSX/DB/관계에는 쓰기 작업, migration, 승인 변경이 없음.
- 현재 Step 7-2는 입력자료 대기 상태. 다음 단계는 사용자가 원본이 아닌 읽기 전용 사본 `0.3 250808 물질수지 DB.xlsm`을 업로드한 뒤, sheet/header/Power Query/행 데이터를 재조사하고 파서 및 비교 report를 테스트하는 것. 이 단계에서는 Step 7-2 코드/Excel report/test가 생성되지 않았고 해당 자료를 분석했다고 주장하면 안 됨.

---

## Step 7-2 실제 물질수지 DB 대조 — 구현·검증 완료 (2026-10-23)

### 보호 상태 및 입력 원본
- 재개 시 `step7-2-wip-20261021`, HEAD `4d65331b7f4fed9eda61e9ac467094680b3a18bf`, 작업 트리 clean. `main`은 `32892eb27f993cc05685a3cd3dd8030275dec5f1`; `stash@{0}` `WIP step6-4 workbook enrichment not complete` 그대로 보존. main 병합, stash 조작, 외부 배포 없음.
- 실제 업로드 경로 `/home/user/uploaded_files/0.3 250808 물질수지 DB.xlsm`, 973,318 bytes. SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`, 사용자 기대값과 일치.
- XLSM을 읽기 전용으로 처리. VBA 실행, Power Query refresh, Excel formula 재계산 없이 `입력시트`의 저장 값과 `비교` sheet의 기존 캐시만 파싱. DataMashup/쿼리는 텍스트로 읽은 이전 조사만 사용. 팀원 원본 파일 쓰기 없음.

### 구현 및 비교 판정
- 신규 `Step7MaterialBalanceComparisonService`는 원본 XLSM/공식 전체목록의 관리번호를 읽기 전용 비교, 연결 정규화, 저장 비교 캐시 판독, 대분류 방향의 SCC 순환 후보 추출, 근거기반 상태 분류를 수행. `Step7MaterialBalanceComparisonWorkbookExporter`는 공식 자료와 분리된 5-sheet TEST_ONLY XLSX 생성. `Step7MaterialBalanceComparisonServiceTest`는 A–H 상태/잔차 구분 및 실제 입력 로딩·산출물을 확인.
- 입력 데이터: 3,082 연결행; 1,149 출발 ID 종류/1,124 도착 ID 종류, 고유 endpoint pair 3,031, 중복 endpoint 그룹 44/관련 95행. 양 endpoint 공식 마스터 정확 일치 2,777행, 한쪽 일치 305행, 양쪽 미일치 0행(외부 경계 문자열은 별도 미확정). 유량 양수 1,975 / 0 1,106 / 공란 1; 물질 종류 공란 2,830; 제외 필드 공란 3,082. 단위·측정기간 열 없음.
- PW-01 대분류 scope 504행, 고유 endpoint pair 501, 양 endpoint 마스터 ID 일치 461행. 이 매칭은 설비 ID 식별일 뿐 연결선 endpoint 확증은 아님.
- 공식 V1.0 read-only snapshot: 문서 16, 설비 1,332, equipment_flow 0, PW-01 connector 986/explicit endpoint binding 0, CONFIRMED relation 0/REVIEW_REQUIRED 1. 기존 PW-01-05 shape 149 후보는 승인되지 않았으며 endpoint/quantity/unit 미지정.
- 따라서 3,082개 입력 연결은 개별 도면 연결 근거 부족으로 `INSUFFICIENT_EVIDENCE`. `MATCHED`, `SOURCE_DIFFERENCE_UNRESOLVED`, `DB_ERROR_CONFIRMED`, `LEGACY_DIAGRAM_ERROR_CONFIRMED`를 실제 행에 부여하지 않았고 확정 오류 0건. 숫자 차이만으로 DB 또는 공식 도면 오류라 단정하지 않음.
- 저장된 비교 시트 캐시 1,179행: TRUE 1,048 / FALSE 130 / 비교 공란 1; 잔차 공란 30 / 비영 잔차 101. 저장 당시 결과이며 새 계산이 아님. `비교` 시트 잔차가 공란인 설비도 구분. PW1 대분류 방향코드 SCC 순환 후보 3개(SCC 노드 64; 큰 그룹 60)는 연결선·유량 확정 또는 실제 순환 계산으로 사용하지 않음.
- 전후잔차 시험은 합성 A–D만 포함. 기준 잔차 0→2는 인과관계를 가정한 TEST_ONLY 신규 오류; 기존 +262.32 잔차 보존은 기존 불일치; 인과 증거 없는 가상 차이는 `INSUFFICIENT_EVIDENCE`; 기준 잔차 누락도 `INSUFFICIENT_EVIDENCE`. 실제 변경 event/변경 전후 자료가 없어 실제 신규 잔차는 생성하지 않음.

### 결과물 및 검증
- `validation-results/2026-10-23-step7-2/TEST_ONLY-step7-2-material-balance-comparison.xlsx`, 5 sheets: `전체 요약`, `연결별 대조`(3,082 data rows), `기존 수지 불일치`, `순환·근거 부족`, `전후잔차 시험예시`. 맑은 고딕/검정 글자, POI 재열기 및 ZIP integrity 통과. SHA-256 `59da095b71d348ab520eb336c77030c1e82242a8e91f0bd7a42b0cef999e760e`.
- 실제 XLSM 비교 테스트: 3/3 PASS, failure/error/skip 0. Step 7-1 solver 8/8, Step 6-5 회귀 15/15, Step 7 unit 2/2와 함께 실행한 targeted suite: 총 26, PASS 25, failures 0, errors 0, skip 1(실물 XLSM 테스트는 그 호출에서 입력 property가 없어 skip; 별도 3/3 실제 실행 PASS).
- `scripts/verify-baseline-hashes.py`: V1.0 16개 로컬 파일 = manifest = MariaDB metadata = DB BLOB, 16/16 PASS. Step 6-5 download 기능 및 Step 7-1 solver 회귀 PASS. 전체 Maven suite는 과거 exit 137 위험 때문에 미실행.
- README Step 7-2 설명을 추가. 남은 업무자료 요구: 동일 시점/기간·단위가 확인된 공식 flow 값, 설비 endpoint에 연결된 도면 증거/담당자 확인, 제외 기준 정의, 측정/저장 시점, 필요 시 신규 변경 전후 자료. 해당 자료가 없으므로 실제 일치/오류 확정·연결 승인·도면 수정은 범위 밖으로 남김.
- Step 6-5·Step 7-1 기존 파일/코드는 보존. 코드·테스트·README·CHECKPOINT 커밋 `2cfe4d7fcc799d7c5e2cb8d77719b4d4ae521bdd` (`Complete Step 7-2 read-only material balance comparison`) 완료. 산출물 공유 링크는 작업 결과 보고에서 전달. 최종 확인 시 main 미병합, stash 보존, 작업 트리 clean.

---

## Step 7-3 최종 검증 및 종료 — 2026-10-23

### Git 이력 및 보존 경계
- 전용 브랜치 `step7-3-wip-20261023`는 `648b69cb3ec6c4c0579c57e1044b84fcfcb69932`에서 분기됨. 사용자가 앞서 기록한 HEAD `229aef2`는 당시 확인 시점의 실제 HEAD였고, 그 뒤 Step 7-3 파일 변경이 자동 백업 커밋 `10e2ffd6e248a2af1d6d4cf716fd7712d40156b3`에 기록되어 최종 검증 시작 시 HEAD가 `10e2ffd`로 진행된 것. 이력은 `648b69c → 229aef2 → 10e2ffd`이며 이전 커밋 삭제/되돌림 없음.
- `229aef2`에는 Step 7-3 service, extractor, exporter, API controller, V15 migration, tests 및 중간 CHECKPOINT 내용이 포함. `10e2ffd`에는 service/exporter/integration test의 최종 수정 3개 파일이 포함. 최종 검증 시 tracked worktree는 clean이었으며, 두 커밋에 포함된 코드가 2026-10-23 최종 Step 7-3 테스트 대상이다.
- 기존 `stash@{0}` `WIP step6-4 workbook enrichment not complete` 보존. `main` checkout/merge, 운영 배포, stash 조작, 공식 도면·팀원 원본 수정 없음.

### 실측 근거와 상태 분리
- 공식 PW-01-05에서 실제 읽은 설비번호 라벨 24종. 실제 DB endpoint ID pair와 라벨 문자열에 근거한 자동 연결 후보 33건. 별도 수작업 fixture 감사 7건은 기존 수작업 정의/위치 힌트의 존재 점검이며, 자동 후보와 겹치는 행이 있어 서로 독립적인 40개 연결로 합산하지 않음.
- 후보 상태는 `RAW_EXTRACTED` / `CONNECTION_CANDIDATE` / `EVIDENCE_INSUFFICIENT` / `UNIT_OR_PERIOD_UNVERIFIED`로 분리. fixture는 `HAND_AUTHORED_TEST_FIXTURE` / `MANUAL_UNVERIFIED`. 도면 DrawingML endpoint binding 0, 구조적으로 검증된 실제 연결 0, 승인/CONFIRMED 연결 0.
- 동일 기준 유량 비교 가능 0. 팀원 DB 질량 값은 단위 및 측정기간 정보가 없어 공식 도면 숫자와의 차이를 계산하지 않음. 저장된 기존 DB 캐시 잔차는 원인 미확정 상태로 보존. 기존 도면 오류 확정 0, DB 오류 확정 0.
- 합성 TEST_ONLY 잔차 시험만 별도 수행: baseline 10, after 15, delta 5. 실제 업무 공정 잔차나 원인으로 해석하지 않음. 공식 원본 XLSX의 수식/매크로/Power Query 실행 또는 재계산 없음.

### TEST_ONLY Excel 및 독립 검증
- 최종 파일: `validation-results/2026-10-23-step7-3/PW-01-05_연결증빙_검토_TEST_ONLY.xlsx`.
- SHA-256 `88821e8f9e541145199d13780759aed352091c620bac7b2a93ef46b5e0b10944`, 크기 25,538 bytes. ASCII 파일명 감사 사본과 원본 최종 파일 SHA가 일치.
- Apache POI 독립 재열기 시트 5개/정확한 순서 확인: `연결 후보`, `검토 필요 연결`, `도면·DB 유량 비교`, `기존 물질수지 불일치`, `담당자 검토대장`.
- POI의 비어 있지 않은 행 수(첫 표제행 포함; 담당자 대장은 두 번째 machine-readable header도 포함): 연결 후보 34행(후보 33), 검토 필요 연결 45행(헤더 외 44건: system/candidate/fixture/warning row 포함), 도면·DB 유량 비교 61행(헤더 외 60), 기존 물질수지 불일치 6행(헤더 외 5), 담당자 검토대장 35행(2개 header + 자동후보 기록 33).
- 자동후보 33건 모두 원본 추출/연결 후보/증빙 부족/단위·기간 미확정으로 기록. 검토 필요 시트의 fixture 7건 모두 `MANUAL_UNVERIFIED`; confirmed 표기 0건. 비교 차이 셀 채움 0건, 계산망 포함 Yes 0건, formula cell 0건. 숫자 질량 셀의 numeric type 유지. 비어 있지 않은 3,335개 셀은 전부 맑은 고딕·검정 글자.
- `unzip -t` ZIP integrity PASS. 이 최종 파일은 앞서 발급한 다운로드 파일과 동일 바이트/동일 SHA.

### DB migration 및 기준자료 무결성
- Flyway V15 `store test only connection evidence reviews`: 이력 version 15/success 1. `balance_evidence_review_case`, `balance_evidence_review_history`의 `information_schema` columns, PK/unique/FK/check constraints 직접 확인. 두 테이블은 검토 이력 TEST_ONLY 용도이며 승인 관계 생성/`process_relation` 변경 없음.
- Step 7-3 통합/추출 테스트 3/3 PASS(실제 XLSM 입력), 동일 명령 반복 실행에서도 3/3 PASS, failures/errors/skips 0. 반복 시 Flyway가 schema version 15를 인식하고 “No migration necessary”로 종료.
- Step 7-1 solver + Step 6-5 회귀 23/23 PASS, failures/errors/skips 0. `./mvnw -q -DskipTests test-compile` PASS.
- 공식 V1.0 XLSX 16개 로컬 파일 = `SHA256SUMS` manifest = MariaDB metadata hash = MariaDB BLOB hash, 16/16 PASS. 팀원 XLSM 원본 SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` 일치. V1.0 기준 DB 문서 16, 설비 1,332, CONFIRMED 0, REVIEW_REQUIRED 1 유지.

### 남은 업무 제약 및 마무리
- 실제 설비 endpoint/배관 경로의 담당자·현장 증빙, DB 값의 단위·측정기간·기준 시점, 공식 도면과 같은 물질/기간의 유량값이 없으므로 전체 공정의 자동 연결·동일 기준 수지 판정·업무 승인까지 완료된 것은 아님. 자동 후보와 fixture는 검토자료일 뿐 승인 관계가 아님.
- 최종 독립 Excel 감사와 테스트 후 신규 코드 변경 없음. 이 CHECKPOINT 정리 커밋을 제외하고 Step 7-4, 전체 도면 재분석, UI 확장, 운영 배포는 시작하지 않음. 최종 상태·커밋 ID는 이번 작업 완료 보고에 기재.

---

## Step 7-4A — CHECKPOINT A: 검토 Excel 계약 및 저장 모델 설계 (2026-10-23)
- 시작 branch `step7-3-wip-20261023`, HEAD `3329f2f79bc85e063bcf2985206903a6aa199450`, 시작 worktree clean, stash `stash@{0}` 보존.
- 기존 Step 7-3은 실제 XLSM/공식 V1.0에서 33개 자동 후보와 7개 수동 fixture audit을 생성하며, fixture는 자동 후보와 중첩될 수 있는 별도 감사 정보. 연결/승인 확정 0건 원칙을 유지.
- V15는 검토 case/history 보관에 재사용 가능하지만, 상태 CHECK는 `TEST_ONLY_EVIDENCE_REVIEWED/NEEDS_MORE_EVIDENCE/REJECTED`에 한정되고, 검토 버전·기준 snapshot 참조·기간·실제 연결 확인·증빙 종류/ID를 저장하는 필드가 없음. 따라서 V15 수정은 금지하고 V16 확장이 필요하다고 판정.
- V16 설계: 불변 review template snapshot과 snapshot별 후보 원본 행/endpoint/도면 셀/connector/DB 원문을 보존. snapshot ID 및 후보 ID로 재업로드 내용을 대조. review case에는 snapshot/candidate 출처, 실제 endpoint/물질/유량/단위/기간, 실제 연결 확인, 증빙 종류·식별자, reviewer/date/comments, review version, 마지막 업로드 SHA를 추가. history에 before/after와 upload fingerprint를 남기고 버전 낙관적 잠금을 적용.
- 검토 상태는 `UNREVIEWED`(case 미저장 시 파생), `REVIEWED_PROVISIONAL`, `EVIDENCE_VERIFIED`, `REJECTED`, `NEEDS_MORE_INFO`. 미확정 단위·기간·경로는 `NEEDS_MORE_INFO`로 보존. 입력 필드/증빙이 제출됐다는 이유만으로 실제 승인 관계를 생성하지 않으며 `process_relation` 쓰기 없음.
- Excel 계약은 기존 5개 sheet와 `담당자 검토대장` sheet/machine-readable key 구조를 유지하면서 필수 source metadata 및 version/snapshot key를 추가. 입력 칸은 시각적으로 구분; DB 원문은 참고 표시, 실제 유량 입력은 별도 blank. POI는 값만 읽고 formula/external link/connection 실행 및 refresh 금지.
- CHECKPOINT A 성공: 스키마·Excel/API 계약 확정 및 본 체크포인트 기록 완료. 구현·통합 검증은 다음 CHECKPOINT B/C에 따로 기록.

### Step 7-4A — CHECKPOINT B: 구현·업로드 검증·검토이력
- 재개 시 branch `step7-3-wip-20261023`, 시작 HEAD `3329f2f79bc85e063bcf2985206903a6aa199450`. 이전 응답 뒤 자동 백업 commit `f400560`에 초기 Step 7-4A 구현이 들어와 현재 시작 시 worktree clean/HEAD `f4005604147080fc7cfc417f5340f102699d5def`였음. `stash@{0}` 보존. V15, Step 7-1 solver, Step 6-5 구현은 수정하지 않음.
- V16 migration은 V15를 변경하지 않고 snapshot/candidate 테이블과 case/history 확장 필드를 추가. API는 `POST /api/admin/step7-3/pw-01-05/evidence-workbook`, `POST /api/admin/step7-3/pw-01-05/review-ledger`, candidate status/history GET 경로를 제공. 전부 기존 관리자 `X-Admin-Token` guard 아래임.
- 검토대장 machine header는 두 번째 행, 35열. 후보 ID는 열 index 4(`candidate_id`), source snapshot/row/version과 함께 검사. 신규 Excel의 33 후보 ID는 중복 없이 유지되고 상태는 모두 `UNREVIEWED`, `official_approval_created=아니오`. 검토 입력칸은 노란색, 맑은 고딕/검정 글자.
- 검토 업로드 검사: 정상 XLSX 허용; malformed/non-OOXML ZIP, `.xlsm` 유형/확장자, VBA, `xl/queryTables`, `xl/connections.xml`, externalLinks, 외부 relationship, formula cell 거부. 검토된 파일을 실행·재계산·새로고침하지 않음. schema/source/snapshot/version 및 설비 ID를 잠금 V1.0 master와 대조. 파일 내 중복 후보·같은 endpoint 충돌·음수/텍스트 유량·잘못된 상태/증빙을 점검. 검증 오류는 파일 전체 저장을 막음.
- 업로드 통합 테스트는 정상 XLSX 파싱, 잘못된 후보·설비·formula·중복·route 충돌·stale version 거부, 동일 업로드 재시도 무저장, 검토 상태/이력 조회, 실제 필수 증빙 미달 시 `NEEDS_MORE_INFO` 하향, 합성 완성 증빙 상태 저장 및 공식 승인 미생성을 검증. 테스트 transaction 종료 후 case/history 0건. 별도 authenticated download API test에서 불변 snapshot 1건/후보 33건은 유지되고 review case/history는 0건.
- 같은 source SHA 업로드 재시도는 idempotent `rowsPersisted=0` 처리. 검토 상태·이력은 transaction 내에서 before/after, upload SHA, version 저장하며 단일 파일의 오류가 있으면 부분 저장하지 않음. 테스트 입력은 `@Transactional` rollback으로 격리; snapshot-only API 시험은 읽기전용 출처 스냅샷/후보 보존을 확인.
- 실제 `EVIDENCE_VERIFIED` 검토와 공식 `process_relation/CONFIRMED` 승인 관계는 각각 0건. 업로드 작성자 진술과 공식 승인 상태를 구분하며, 이 workflow는 `process_relation`을 생성·수정하지 않음.

### Step 7-4A — CHECKPOINT C: 최신 테스트·DB·원본 검증
- 최신 명령 `./mvnw -q -DskipTests test-compile` 성공. 직전 테스트 assertion에서 로컬 변수 이름 중복 compile error 1회가 있었고 test variable을 rename한 뒤 위 컴파일 gate 성공. 실패 원인을 숨기지 않으며 최종 compile은 성공.
- 최신 Step 7-4A/7-3 대상 테스트: `Step7ReviewLedgerWorkflowIntegrationTest` 3, `Step7ConnectionEvidenceIntegrationTest` 1, `Step7DrawingEvidenceExtractorTest` 2; 총 6, failures 0, errors 0, skipped 0. ZIP 차단 케이스(매크로/queryTables/connections/external link/외부 relationship/불량 ZIP), 잘못된 확장자, 무권한 401, 정상 템플릿 업로드를 포함해 최종 통과. 전체 Maven suite는 실행하지 않음.
- Step 7-1/Step 6-5 회귀: `DirectedProcessNetworkSolverTest` 8, `DiagramEditPlanIntegratedControllerTest` 3, `DiagramEditPlanControllerIntegratedWorkbookTest` 4, `Step6IntegratedWorkbookDeliveryIntegrationTest` 2, `Step6IntegratedWorkbookStorageIntegrationTest` 1, `Step6TestOnlyWorkbookEnrichmentIntegrationTest` 2, `MaterialBalanceExcelExportIntegrationTest` 3; 합계 23, failures/errors/skips 0.
- Flyway V15 success=1, V16 success=1. V16 case fields (`snapshot_id`, `candidate_id`, `review_period`, `review_state`, `review_version`, `last_upload_sha256`) 및 history fields (`candidate_id`, `upload_sha256`, `review_version`) 존재. 테스트/다운로드 후 MariaDB: snapshot 1, snapshot candidates 33, review cases 0, review history 0, stored `EVIDENCE_VERIFIED` cases 0, `process_relation CONFIRMED` 0. Snapshot는 최종 Excel 다운로드 API의 source fingerprint snapshot이며 담당자 검토 이력/승인이 아님.
- `scripts/verify-baseline-hashes.py`: 공식 V1.0 16/16 local = manifest = MariaDB metadata = DB BLOB PASS. V1.0 documents 16, equipment master 1,332, confirmed relation 0 유지. 팀원 XLSM SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` expected value와 일치. 입력 원본과 공식 기준자료 변경 없음.
- 최신 생성 Excel `validation-results/2026-10-23-step7-4a/PW-01-05_담당자_연결검토대장_TEST_ONLY.xlsx`, 28,358 bytes, SHA-256 `51acd18aec429210b105bcf702cdb845d1f553ba8dac4113b1abc881b23c0ccb`. 생성 테스트가 이를 POI로 재열어 5 sheets, machine header, 33 rows, UNREVIEWED 및 no official approval 확인. `unzip -t` PASS. 기존 ASCII-named test file과 bytes-identical.
- 보안 로그 검사에서는 현재 `.env`의 실제 비밀값 일치 항목 0. 이 응답에서 Maven stdout/stderr를 파일로 보존하지 않았고 새 test reports에서 실제 토큰 값 발견 없음. 이전 실패 진단 로그 노출 가능성은 별도 남은 위험이며, 로그 외부 공유 여부 확인 후 필요 시 사용자가 운영 토큰 회전을 결정해야 함. 토큰 회전은 수행하지 않음.

### Step 7-4A — CHECKPOINT D: 문서·산출물·Git 최종화
- README에 검토 Excel 다운로드/기입/upload/status/history API, 검증 오류 확인 방법, `EVIDENCE_VERIFIED`와 공식 승인 차이 및 미확인 연결 처리 방식을 추가.
- 최종 파일 `PW-01-05_담당자_연결검토대장_TEST_ONLY.xlsx`, 28,358 bytes, SHA-256 `51acd18aec429210b105bcf702cdb845d1f553ba8dac4113b1abc881b23c0ccb`; `unzip -t`와 Apache POI 재열기 PASS. 다운로드: https://www.genspark.ai/api/files/s/Zxoloaku . UploadFileWrapper에서 내려받은 사본과 local file SHA-256이 동일함.
- Step 7-4A 구현/V16/API/exporter/초기 integration test는 자동 보관 commit `f4005604147080fc7cfc417f5340f102699d5def`에 이미 존재. 이번 최종 점검에서 보강한 ZIP/권한/POI 검증 테스트와 README/CHECKPOINT B–D는 관련 3개 파일만 선택하여 `1f8bff3` (`Finalize Step 7-4A review validation`)으로 커밋. 이 CHECKPOINT D의 마지막 commit/status 갱신은 CHECKPOINT 파일만 별도 커밋하고, 최종 branch/HEAD/clean status는 완료 보고에 기록.
- main merge, deploy, stash deletion/application, baseline data edits, Step 7-4B 및 3,082-connection recalc는 수행하지 않음.

---

## Step 7-4B — CHECKPOINT A: 검토 데이터 매핑 및 계산망 편입 조건 (2026-10-23)
- 시작 상태: branch `step7-3-wip-20261023`, HEAD `f2a7c072922cb19b6ed5b5e69e0f5e5cacfc5fce`, 시작 worktree clean. `stash@{0}` 보존.
- Step 7-1 `DirectedProcessNetwork.Node/Edge/ProcessTerm`, `MaterialBalanceCalculator.calculateDirectedNetwork/recalculateDirectedNetwork`, 그리고 기존 solver의 음수·미정수·잔차 상태를 그대로 재사용한다. Solver 및 V15/V16 source는 수정하지 않는다.
- V16 `balance_evidence_review_case`에 review state/version/quantity/unit/period/physical confirmation/evidence ID·text, V16 candidate snapshot에 source row/도면 file·sheet/candidate endpoint/cell/connector 및 DB 질량 참고값이 있다. 최신 candidate와 해당 case의 최신 history ID, V1.0 equipment master의 `middle_process_code`를 adapter 입력으로 연결한다.
- V16에는 topology class, physical path ID, 외부경계/분기/순환 완결 선언, unit dimension/gas reference condition, 전체 scope membership/ProcessTerm completeness가 영속되지 않는다. 이 정보는 V1.0/XLSM의 이름·질량 숫자만으로 추정하지 않는다. Adapter에는 version-keyed typed topology/scope evidence 입력을 추가하며, 이 입력이 빠지거나 검토 version/baseline과 불일치하면 edge를 제외하고 계산을 막는다. Profile payload는 계산요청 범위에서만 사용하고 V15/V16, official relation, source review history에 쓰지 않는다.
- 편입 최소요건: `EVIDENCE_VERIFIED`, V1.0 locked source alignment, current review/history version, physical connection YES, source/target/process, explicit route/path ID+direction, material class/type, numeric quantity+raw unit+period, typed topology/boundary/branch/recycle evidence, gas condition where required, complete declared scope and explicit ProcessTerm coverage. UNREVIEWED/MANUAL_UNVERIFIED/REJECTED/NEEDS_MORE_INFO, stale version, missing scope evidence, unknown dimension, mixed unit/period remain excluded.
- Edge identity is unique review candidate/path ID, never endpoint pair; same endpoints can produce distinct edges only when separate route identifiers/evidence exist. No unit/density/time/gas conversion. For an expected scope, any missing member/topology/term/boundary or solver incompleteness returns `CALCULATION_UNAVAILABLE`/`UNDERDETERMINED`, never an all-balanced partial graph.
- Actual PW-01-05 candidates remain report-only: current DB state `EVIDENCE_VERIFIED=0`; their real network will be unavailable/incomplete. All solver execution for this step uses separate synthetic fixtures only; no process_relation approval or official relation state change.

### Step 7-4B — CHECKPOINT B: Adapter 및 합성 TEST_ONLY 실행
- resume 시 branch는 `step7-3-wip-20261023`, HEAD는 `ae703a5cd5e13f41f49648d9b97d5b93488cccfa` (`genspark auto-backup`)였고 worktree clean이었다. 과거 A에 기록한 기준 HEAD `f2a7c072922cb19b6ed5b5e69e0f5e5cacfc5fce` 및 `stash@{0}`는 변경하지 않았다.
- 신규 `Step7ReviewNetworkAdapter`는 검토 이력/상태·snapshot·V1.0 lock·endpoint/공정 mapping·route id/path/direction·material·quantity/unit/dimension/period·gas reference·경계·branch/merge·cycle·scope completeness를 검사한다. 빠진/충돌한 조건은 제외 사유로 남기고 eligible expected edge가 scope 전체와 일치하지 않으면 network 자체를 solver에 전달하지 않는다.
- `EdgeProvenance`와 CandidateAssessment에 source row, 공식 도면/시트/cell hints/connector IDs, candidate·route·history ID/version, reviewer 및 검토 증빙/유량 증빙/route·물질·유량/unit/period/fixed 정보를 보존한다. Excel에 actual candidate trace 및 synthetic Node/Edge provenance를 나눠 출력한다.
- 합성 fixture에서 A 정상 계산, B 미확정 단위, C UNREVIEWED, D endpoint 동일 병렬 route 2 Edge, E partial scope, F interprocess 20→10/50, G recycle 20→30, H 120 과도 이동에 따른 음수 제약, I baseline residual +5→after +8/delta +3, J stale route history를 확인. Solver는 기존 Step 7-1 계산/재계산만 사용.
- 첫 실행에서 source를 비ASCII 경로로 시스템 property에 직접 전달해 JVM `InvalidPathException`이 발생했고 ASCII directory를 지정해 재실행했다. 첫 기능 시험에서 recycle fixture가 잘못된 Edge ID를 변경 대상으로 사용한 것을 발견해 올바른 ID로 고치고 재시험했다. 마지막 Step 7-4B integration run은 1/1 PASS, failures/errors/skips 0.

### Step 7-4B — CHECKPOINT C: Excel·회귀·원본 무결성
- 최종 TEST_ONLY Excel: `validation-results/2026-10-23-step7-4b/Step7-4B_연결망_계산준비_검증_TEST_ONLY.xlsx`. 5 sheet, POI 재열기, 모든 셀 맑은 고딕/검정, 수식 없음, `unzip -t` 정상. SHA-256 `cf521fc5270c30d237695d1c96f82247ca61bc9c5ec05466319cb9bad155a03b`.
- Step 7-1 solver 및 Step 6-5 regression classes 7개 23/23 PASS. Step 7-3/7-4A/7-2 source-driven regression 9/9 PASS. Step 7-4B 신규 통합시험 1/1 PASS. 합산 targeted test execution 33, failures 0, errors 0, skips 0. 전체 Maven suite는 실행하지 않음.
- `scripts/verify-baseline-hashes.py` PASS: V1.0 16 local files = 16 manifest entries = 16 MariaDB metadata hashes = 16 BLOB hashes. 검증 CSV `validation-results/2026-10-23-step7-4b/baseline-sha256-verification.csv` SHA-256 `4e597b3014d128cdd06bb371ba9c526afe6e2e93a1a6eae5c89ab43068eaf5fb`.
- team XLSM SHA-256 재확인 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`, Step7-2 기록값과 동일. DB read-only 최종 counts: V1.0 baseline docs 16, equipment records 1,332, CONFIRMED 0, review EVIDENCE_VERIFIED 0, review case/history 0, snapshot 1/candidate 33. 테스트 후 이력/case/승인 수는 변하지 않았고 migration은 V16 up-to-date.
- 실제 network `CALCULATION_UNAVAILABLE`, actual eligible 0. 실제 PW-01-05 수지·잔차는 계산하지 않았다. 공식 원본, 팀원 XLSM, Step 6-5 산출물, V15/V16, Step 7-1 solver/migrations는 편집하지 않았다.

### Step 7-4B — CHECKPOINT D: 문서·커밋 및 종료 경계
- README에 Step 7-4B adapter, fail-closed eligibility, unit/period 제한, 테스트/Excel 및 actual-vs-synthetic 한계를 기록.
- CHECKPOINT A–D 완료 결과는 이 절에 남긴다. 구현/문서 파일만 선별 커밋한다. 산출물 XLSX/CSV 및 원본 workbook은 Git에 넣지 않는다.
- 구현/README/CHECKPOINT A–D 및 통합 테스트의 선별 커밋: `cad5873d10e0cc9313b920672f7d3f8a25231250` (`Complete Step 7-4B TEST_ONLY network adapter validation`). 이어서 이 커밋 ID를 보존하기 위한 checkpoint-only 커밋을 추가한다. 최종 branch/HEAD/clean status는 종료 시점에 재확인한다.
- main 병합, 기존 stash 변경, 운영 배포, 3,082개 전체 계산은 수행하지 않는다.

---

## Step 7-5A — PW-01-05 현장 확인자료 정리 (2026-10-23)

### 범위·대상 선정
- Step 7-2/7-3/7-4A/7-4B의 기존 XLSX 결과를 읽기 전용으로 재사용. Java 코드, 계산 엔진, migration, 공식 V1.0 공정도, 팀원 XLSM, MariaDB 업무자료는 수정하지 않음.
- 우선 현장 확인 범위는 `C-PW13022 + C-PW14032 → C-PW15001 → C-PW15011 → C-PW15017 → C-PW15018 ↔ C-PW15007`, 후단 후보 `C-PW15011 → I-PW25002`. 상류 후보 DB 행 2638/2683, 연결 후보 2688/2711/2710/2712/2713/2714/2698/2715/2716/2717 등을 묶어 유입·이동·분기·순환 가능성·경계 확인을 시작하기 위한 우선순위로 선정.
- 이 범위는 완결된 실제 연결망 또는 계산 가능 범위가 아님. connector ID와 anchor/셀 주소는 위치 힌트에 그치며 논리 endpoint binding은 확인되지 않음. 추가 유입·유출, 경계 alias(예: row 3024), P7/P8/P9/P10 확장, 실제 분기 배분/순환 및 저장·발생 항목과 단위·기간이 현장/승인 도면/계량자료로 확인되어야 함. 실제 물질수지는 계산하지 않음.
- DB 행 2638=23,589.432, 2683=4,950.738 및 2710=0.22는 원문 후보값일 뿐이다. 단위·기간·물질 및 같은 물리적 배관 귀속이 불명확하여 서로 더하거나 도면 숫자와 차이를 계산하지 않음.

### 현장 확인 Excel
- `validation-results/2026-10-23-step7-5a/PW-01-05_실제연결망_현장확인요청_TEST_ONLY.xlsx` 생성/보완. 5개 시트(① 우선 검토 구간, ② 설비·배관 연결 확인, ③ 물량·단위 확인, ④ 기존 물질수지 불일치, ⑤ 담당자 확인 결과). ②/③/⑤ 시트에는 기존 검토 후보 및 보조행에 더해 2638/2683/2710 상류·후단 후보를 추가; candidate ID/case_key/snapshot_id/source SHA 및 원본 행·셀·connector 힌트를 기존 Step 7-4A 기록과 대조하여 유지.
- 현장 확인 결과 시트는 노란 입력 필드와 상태 선택 목록을 제공하지만 Step 7-4A schema-v2 업로드 템플릿은 아님. 담당자는 결과·증빙 식별정보를 최신 Step 7-4A 검토대장으로 옮겨 실제 업로드/이력 저장해야 함. 이 파일의 형식을 재업로드 지원 형식이라고 주장하지 않음.
- Excel 재열기/시트·열 수·추가 ID/case_key/snapshot_id 검증, 맑은 고딕 및 검정 글꼴, 수식 0개, 입력 validation 범위 확장, ZIP 무결성 PASS. SHA-256 `af9a9113850e6cc412786f7735b994288500e6cab1aaf28e351653d9ee092c50`.
- 기존 DB 잔차 캐시는 저장 당시 값으로만 제시. C-PW14004 `+262.32`, C-PW14005 `-349.44`는 기존 불일치 후보이며 원인 미확정. 캐시 0/TRUE도 공식 수지 정상 근거가 아니다. 공식 V1.0 값, 팀원 DB 원문, 담당자 확인값, 신규 변경 영향은 별도 구분하며 원본 자동 수정/오류 확정은 없음.

### 무결성 및 DB 상태
- `scripts/verify-baseline-hashes.py` PASS: 공식 V1.0 로컬 원본 16개 = SHA256SUMS manifest 16개 = MariaDB metadata SHA 16개 = BLOB SHA 16개.
- 팀원 XLSM `/home/user/uploaded_files/0.3 250808 물질수지 DB.xlsm` 시작/종료 SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` 일치.
- MariaDB read-only 최종 확인: 후보 33, 검토 case 0, history 0, 실제 EVIDENCE_VERIFIED case 0, official approval flag 0, CONFIRMED relation 0, 전체 relation 1(REVIEW_REQUIRED), equipment_flow 0. DB 쓰기 없음.
- 실제 연결 검증 0건, 실제 계산망 편입 0건, 실제 수지/잔차 미계산, 공식 승인 관계 생성 없음. 합성 TEST_ONLY 결과와 실제 자료는 분리 유지.

### Git·마무리 제한
- 시작 branch `step7-3-wip-20261023`, HEAD `33809ee15c8adc0ac4f295738a15b084e5f94224`, worktree clean; 기존 `stash@{0}` 보존. Step 7-5A 동안 앱 코드/공식 원본/팀원 XLSM/DB 변경은 없음.
- CHECKPOINT 기록만 선별 커밋 완료(현장 확인 XLSX 및 해시 검증 산출물은 Git에 stage하지 않음). 운영 배포, main 병합, stash 적용/삭제, 3,082행 전체 확정 및 전체 도면 재분석은 수행하지 않음.

---

## Step 7-5B 미완료 작업 복구 — 재개 지점 (2026-10-23)
- 요청된 기준 브랜치는 `step7-3-wip-20261023`, 기준 HEAD `ecbed79d494b973552917696180d1b979745d0be`였으나 실제 재개 시 branch는 동일하고 HEAD는 자동 보관 이후 `f927c4e26dbde9b4b497a879faf44e04554cd8e7`이다. 현재 working tree는 CHECKPOINT 수정 전 clean이며, 기존 stash `stash@{0}: On main: WIP step6-4 workbook enrichment not complete`를 보존한다. `ecbed79..HEAD` 변경은 이력에서 확인 후 보존하며 되돌리지 않는다.
- 기존 Surefire 결과 `target/surefire-reports/com.example.processautomation.material.Step7FieldReviewBridgeIntegrationTest.txt`: 6 tests, 4 failures, 0 errors, 0 skipped. A는 UTF-8 `filename*=`와 RFC 2047 fallback을 포함한 정상 Content-Disposition 응답에 대해 테스트가 raw 한글 파일명을 단순 contains 검사한 것으로 확인. B/C는 합성 fixture의 입력 셀/실제 매핑·schema-v2 validator·DB 저장 단계까지 구분 조사 전. D의 테스트 fixture가 필수 연결 결과/확인자/나머지 값을 채우지 않아 numeric assertion보다 필수 입력 오류가 먼저 발생함.
- 복구 명령의 우선 순위: 현재 code/diff/history를 보호한 채 A–D 실제 원인을 추적하고, 오래 걸리는 실행 전까지 본 재개 지점을 남김. 공식 V1.0, 팀원 XLSM, MariaDB 승인 관계 및 원본은 수정 금지. main merge, stash 변경/삭제, 배포, Step 7-6은 금지.
- 실패 A 원인/수정: HTTP 응답의 `Content-Disposition`은 RFC 2047 fallback과 RFC 5987 `filename*=UTF-8''...` 형식으로 적법하게 인코딩되어 있었다. 테스트를 `ContentDisposition.parse(header).getFilename()`으로 디코딩한 뒤 지정 한글 파일명과 대조하도록 수정.
- 실패 B/C 원인/수정: 기존 합성 테스트 helper는 고정 열 숫자로 작성했고, header-driven 재작성 뒤에도 기존 입력 템플릿 셀을 in-place 갱신하면 직전 값(`미확인`/안내문)이 POI 읽기에서 유지되는 현상을 즉시 read-back assertion으로 재현했다. 실제 machine-readable header 이름으로 열을 찾고, 기존 cell style을 보존하면서 해당 입력 cell을 제거 후 올바른 STRING/NUMERIC type으로 재생성하도록 수정. 이로써 파서의 입력 감지→snapshot 후보 매핑→schema-v2 canonical validation→기존 importer 저장→동일 트랜잭션 status/history 재조회가 한 테스트에서 통과했고, 동일 파일 재업로드는 rowsPersisted=0/rowsIgnored=1 및 history 1건 유지로 확인.
- 실패 D 원인/수정: 합성 fixture의 다른 필수 입력도 템플릿 기본/빈 값으로 남아 숫자 오류 assertion을 가렸음. helper 변경과 함께 다른 필수 입력을 정상 구성하고 수량 셀만 String `0`으로 교체해 텍스트 숫자를 거부하는 `NUMERIC_CELL_REQUIRED`를 독립 검사하도록 수정. 구현 검증 로직과 필수조건은 완화하지 않음.
- 검증: `./mvnw -q -DskipTests test-compile` PASS. 이후 `Step7FieldReviewBridgeIntegrationTest` 최신 실행 6 tests, failures 0, errors 0, skipped 0 PASS. 이 클래스는 `@Transactional` test scope이며 합성 case/history를 같은 테스트 내에서 확인하고 프레임워크 rollback으로 정리하도록 구성. 아직 별도 실행 후 DB 상태 확인 및 주요 회귀, 보호 원본 hash 검증, final workbook 독립검증은 남아 있음.
- 후속 targeted 회귀: Step 7-1/6-5/7-3/7-4A/7-4B 연관 11개 클래스 30 tests 실행, failures=0/errors=0, 5 skipped. Step7ConnectionEvidenceIntegrationTest(1), Step7ReviewLedgerWorkflowIntegrationTest(3), Step7ReviewNetworkIntegrationTest(1)의 source property를 제공하지 않아 입력 기반 시험 5건이 조건부 skip된 사실을 확인. 나머지 25건 pass.
- 별도 read-only 검증: official V1.0 local/manifest/MariaDB metadata/BLOB 16/16 PASS. 팀원 XLSM SHA-256은 기대값 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`와 일치. DB: V15/V16 success=1, baseline docs 16, equipment 1,332, CONFIRMED 0, REVIEW_REQUIRED 1, equipment_flow 0, snapshot 1/candidates 33, review case/history/EVIDENCE_VERIFIED 0. 다음은 source path를 설정해 해당 Step 7 회귀를 skip 없이 재실행하고 report workbook 파일 저장 후 독립 검증.
- Step 7 입력 기반 회귀 재실행은 첫 시도에서 통합 테스트 원인이 아닌 JVM 비ASCII 경로 처리 오류로 5 errors가 발생했다. 테스트 process가 `.../uploaded_files/0.3 250808 물질수지 DB.xlsm`을 `?`로 변환해 `InvalidPathException`을 발생시켰으며, 공식 원본 XLSM 내용/DB 변경은 없었다. 다음에는 원본 바이트를 지정된 ASCII 경로로 읽기 전용 복사하고 복사본 SHA가 기대값과 일치함을 확인한 후, 그 ASCII 경로로 해당 4개 Step 7 클래스를 다시 실행한다.
- ASCII source copy SHA가 팀원 XLSM 기대 hash와 일치함을 확인하고 source-backed 회귀 재실행 완료: `Step7ConnectionEvidenceIntegrationTest`, `Step7DrawingEvidenceExtractorTest`, `Step7ReviewLedgerWorkflowIntegrationTest`, `Step7ReviewNetworkIntegrationTest` 총 7/7 PASS, failures/errors/skips 0. 이전 첫 실패는 JVM property의 한글 경로 인코딩만의 문제였고 source copy로 해소. 전체 Step 7-1/6-5/7-3/7-4A/7-4B coverage는 30 unique tests 기준 30 pass, 0 fail/error/skip(첫 집계 중 조건부 5 skip을 source가 필요한 5 tests의 ASCII 경로 재실행으로 대체, Drawing test 두 건은 재실행 포함).
- 최종 결과 workbook의 저장 파일을 직접 다시 열어 5개 정확한 시트명, 담당자 입력 0, non-formula cells의 맑은 고딕/검정 글꼴, 수식 0을 검사하도록 통합 테스트도 보강했다. 아직 이 추가 검증을 포함한 compile/final integration run은 미실행.
- `./mvnw -q -DskipTests test-compile` 성공 후 결과 파일 경로에 workbook을 생성하고 저장본을 Apache POI로 재개방하는 최종 bridge test 재실행: 6/6 PASS, failures/errors/skips 0. 별도 workbook 감사에서 실제 시트 순서 5개 확인, mapped 25 ID는 현장 원본의 candidate ID 집합과 일치, missing 8 ID는 기존 33 candidate 전체에서 mapped set과 상호 배타적, reference-only 6행은 분리. Actual 담당자 입력 0, schema-v2 변환 0, 실제 EVIDENCE_VERIFIED 0, 계산망 적격 0.
- 최종 결과 `validation-results/2026-10-23-step7-5b/PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx`, 11,785 bytes, SHA-256 `bfba40495220f4d9db5d0bd0465cbc4f78376663025882becc5b921ebf9aae22`. 5 sheets·748 nonblank cells, 수식 0, 맑은 고딕/검정 글꼴 검사 0 이슈, ZIP integrity PASS. openpyxl은 POI 생성물의 default style 정보에 경고를 냈지만 cell별 글꼴/색은 전체 검증에 통과했고 Apache POI 저장본 재열기 통합 검사도 성공했다.
- 재개 지점의 최신 상태: 브랜치 `step7-3-wip-20261023`, 현재 HEAD `f927c4e26dbde9b4b497a879faf44e04554cd8e7` (이 HEAD는 복구 시작 전에 이미 Step 7-5B 소스가 포함된 자동 보관 커밋); 기존 stash 유지. 현재 최종 결과 artifact는 위 경로에 생성됨. 다음은 최종 DB read-only/원본 무결성 재확인, wrapper 업로드/다운로드 hash 비교, README 및 이 CHECKPOINT의 A–D 완료기록 정리, 관련 코드·테스트·문서만 선별 커밋, final git status 확인.
- 아직 하지 않은 것: 전체 Maven suite, 실제 담당자 자료 입력/실제 review 저장, 공식 승인/EVIDENCE_VERIFIED/계산망 편입, Step 7-6, main 병합, stash 조작, 운영 배포.

### Step 7-5B 최종 체크포인트 A–D — 검증 완료

#### CHECKPOINT A — 현장 Excel 파싱
- Step 7-5A 실제 파일의 5개 시트/순서와 `⑤ 담당자 확인 결과` 4행 machine-readable header, 데이터 행, 기본 안내문을 확인했다. 5개 시트 formula cell 및 unsafe package 차단은 기존 package 검사와 bridge parser를 통해 확인.
- 고정 안내문을 실제 입력으로 세지 않도록 파싱한다. 최종 workbook 및 preview에서 현장 담당자 입력 0건. 파서/테스트는 수식을 평가하거나 매크로·Power Query·external connection을 실행하지 않는다.

#### CHECKPOINT B — 후보 매핑 및 schema-v2 연계
- V16 snapshot candidates 33건을 기준으로 기존 후보 자동 매핑 25건, 참고행 6건 분리, 현장 파일에 없는 기존 후보 8건을 확인. 25 candidate ID 집합은 7-5A workbook의 ID 집합과 같고, 누락 8건은 매핑 집합과 상호 배타적이며 union은 기존 33건. Reference-only rows는 6건이고 승인으로 세지 않음.
- 현장 입력 0건이므로 실데이터 schema-v2 변환 0건/저장 0건. 합성 fixture로 유효 1건은 canonical schema-v2 검사·기존 importer 호출·transaction 중 DB status/history 재조회까지 확인; 같은 source 재업로드 rowsPersisted=0/rowsIgnored=1 및 history count 1 유지. `@Transactional` test scope 종료 rollback 후 DB case/history는 0건.
- `EVIDENCE_VERIFIED` field suggestion은 evidence_type 컬럼 부재로 `NEEDS_MORE_INFO` downgrade. 실제 승인 관계·계산망 편입을 만들지 않는다.

#### CHECKPOINT C — 통합·회귀·원본 무결성
- 최종 compile PASS. `Step7FieldReviewBridgeIntegrationTest`: 6/6 PASS, failure/error/skip 0. Step 7-1/Step 6-5/Step 7-3/Step 7-4A/Step 7-4B 핵심 targeted tests 30/30 PASS, failure/error/skip 0. 전체 Maven suite는 실행하지 않음.
- Flyway V15/V16 success=1. Read-only DB counts: baseline documents 16, equipment 1,332, snapshot 1/candidates 33, CONFIRMED relation 0, REVIEW_REQUIRED relation 1, equipment_flow 0, review case/history 0, EVIDENCE_VERIFIED 0.
- V1.0 로컬 XLSX 16개 = manifest = MariaDB metadata hash = BLOB SHA, 16/16 PASS. 팀원 XLSM SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` 일치.

#### CHECKPOINT D — Excel 전달·문서·Git
- 최종 결과 `validation-results/2026-10-23-step7-5b/PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx`, 11,785 bytes, SHA-256 `bfba40495220f4d9db5d0bd0465cbc4f78376663025882becc5b921ebf9aae22`. 5개 시트, 748 nonblank cells, POI 저장본 재열기, 수식 0, 맑은 고딕/검정 글꼴, ZIP 무결성 PASS. 다운로드: https://www.genspark.ai/api/files/s/ZFxMI6Pf; wrapper 다운로드 byte-for-byte 및 SHA 일치.
- README에 Step 7-5B API·입력/보안·매핑 경계·산출물·검증·미완료 범위를 추가했다. 선별 커밋 범위는 `README.md`, `CHECKPOINT.md`, `Step7FieldReviewBridgeIntegrationTest.java`이며, 생성된 XLSX/CSV/다운로드 사본은 Git에 넣지 않는다.
- 공식 원본/팀원 원본, V15/V16 migration, 승인관계 및 도면은 수정하지 않았다. main 병합, stash 변경/삭제, 운영 배포, Step 7-6은 수행하지 않았다.

---

## Step 7-6A — CHECKPOINT A: 전체 후보·입력 계약 조사 (2026-10-26)
- 시작 상태 확인: branch `step7-3-wip-20261023`, HEAD `f2067271ced051aec851540325de4495c2141917`, worktree clean. `stash@{0}: On main: WIP step6-4 workbook enrichment not complete` 보존. main/ stash/운영 배포는 조작하지 않는다.
- Step 7-2 비교 서비스는 팀원 XLSM의 입력/비교 시트 캐시만 읽고 원본 VBA·Power Query를 실행하지 않는다. 질량 단위·기간이 원본 열에 없음을 명시하고, 숫자 차이만으로 오류를 확정하지 않는 classifier/change classifier를 재사용한다.
- Step 7-3 추출기는 기존 DB 행·endpoint, PW-01-05 설비 라벨, label-cell과 connector anchor/shape ID를 읽기 전용 후보 근거로 기록한다. 이 힌트는 논리 연결/방향 확정이 아니다. 기존 추출 대상은 33 candidate이며, 이 단계의 7-5A workbook은 그중 25개 ID만 포함하고 다음 8개를 빠뜨린 것으로 기존 7-5B source-driven 테스트에서 확인: `2621, 2623, 2636, 2637, 2689, 2690, 2691, 2693`. reference-only 6행은 별도이며 후보/승인이 아니다.
- V15/V16은 이번에 read-only로 재확인했고 수정 금지. V16은 `evidence_type`, `evidence_identifier`, `evidence_text`를 이미 저장한다. 따라서 evidence_type 자체가 없다는 가정은 하지 않는다. 현재 부족한 항목은 증빙 위치·검증 방법·검증자/시각·의견 및 route ID별 endpoint/direction/material/quantity/unit/period/topology/boundary/scope의 typed 영속 구조다.
- Step 7-4A schema-v2 계약은 5개 시트 + `담당자 검토대장` 35개 machine headers이며 canonical validation, snapshot/version/source 대조, 행 잠금, 멱등 fingerprint와 append-only review history를 제공한다. 기존 계약은 그대로 둔다. Step 7-5B 5시트는 read-only preview/report bridge이며 제출 양식 자체로는 재업로드할 수 없다. 새 6시트 양식은 별도 route-aware adapter에서 기존 canonical schema-v2 validator/importer를 재사용해야만 업로드 가능하다고 표시한다.
- Step 7-4B runtime adapter는 route ID와 review case/history/version 바인딩, boundary/branch/cycle/scope/unit/period completeness를 검사하고 불완전하면 network 전체를 unavailable 처리한다. 현재 실제 workflow가 route profile을 빈 목록으로 전달하며 actual eligible 0을 유지하는 것이 fail-closed 기준이다. 추가 route가 같은 candidate에 속할 수 있다는 새 요구에 대해 단순 endpoint pair를 route identity로 삼지 않는다.
- Step 7-6A 구현 전 정책: base candidate review/history와 route/evidence detail을 구분하고, route별 기록을 기존 review version/history에 귀속한다. 증빙 식별값만으로 상태를 올리지 않고 `EVIDENCE_VERIFIED`는 입력된 근거 필수항목·명시적 검토가 모두 통과할 때만 허용하되, official `CONFIRMED` 관계는 만들지 않는다. 미완결 scope는 adapter의 `CALCULATION_UNAVAILABLE`에 남긴다.
- CHECKPOINT A 조사 완료. 해당 기록은 당시 시작 지점이며, 이후 작성된 미커밋 코드가 auto-backup commit `a7c8a49`에 포함된 것을 다음 재개에서 확인했다. 현재 기준 HEAD/DB 결과와 이후 진행은 아래 CHECKPOINT B부터 기록한다.

### Step 7-6A — CHECKPOINT B: 재개 상태 및 V17/V18 안전성 검증 (2026-10-26)
- 재개 시 branch `step7-3-wip-20261023`, HEAD `a7c8a490659f835998b878b8ba8824924aa08dec` (`genspark auto-backup`), worktree clean. `a7c8a49`에 기존 Step 7-6A code/test/migration 초안 6개 파일이 이미 포함되어 있으므로 이를 중복 커밋하지 않는다. `stash@{0}` 및 `main`은 보존.
- Read-only DB audit: V15 success=1 checksum `-1313349794`, V16 success=1 checksum `1737846369`, V17 success=1 checksum `722139116`; V17 source는 Flyway validation 통과. V17 route/provenance tables, indexes, unique key, candidate FK 실제 생성 확인. 당시 DB: baseline docs 16, equipment_record 1,194, equipment_flow 0, process_relation 1 (`REVIEW_REQUIRED`), case/history 0, routes/provenance 0, official approval 0, EVIDENCE_VERIFIED 0. 공식 XLSX 16/16 local/manifest/metadata/BLOB hash PASS; source XLSM ASCII copy SHA expected match.
- V17 route/evidence의 history ID는 application field로 저장되지만 DB에서 review_history id/case/version과 강제 연계하지 않는 공백을 확인. V17은 이미 적용되었으므로 변경하지 않고 V18로 composite FK/index와 허용 상태 제약을 추가. V18는 테스트 bootstrap에서 schema 17→18로 성공 적용됨.
- 첫 V18 integration 실행은 6 tests 중 4 pass / 2 fail, skip 0. A) 중복 route synthetic helper가 두 번째 row에 필수 필드를 복제하지 않아 validation 전에 route error를 만들었고, B) evidence provenance 상태값이 V17 `VARCHAR(32)`를 초과한 것이 원인. 테스트를 완화하지 않고 helper를 고치며, 이미 적용된 V17/V18을 수정하지 않는 V19 `VARCHAR(64)` 확장 migration을 추가했다. V19 적용 및 재검증은 아직 완료 전이며, 다음 재개는 test-compile 후 위 클래스 6개 실행부터 시작.
- 현재 stage는 INCOMPLETE/WIP. 아직 최종 Excel/해시/다운로드본 일치, 회귀, post-test DB counts, README B–D, 선별 커밋은 완료하지 않았다. 운영 배포, main merge, stash 조작은 금지. **이 문장은 02:10 자동보관 시점의 상태이며, 아래 최종 시연 MVP checkpoint가 이후 상태를 갱신한다.**

---

## 최종 보고 시연 MVP 안정화 — CHECKPOINT 1–4 (2026-10-26, 최신)

### 기준점 및 보존
- 현 브랜치 `step7-3-wip-20261023`, 기준 HEAD `8ec4d5f76e01097105f7c44fd3bac0ace49f0b0f` (`genspark auto-backup`), 작업 시작 시 clean. Step 7-6A 기존 code/tests와 **V18/V19 migration 파일은 이미 이 커밋에 포함되고 Git 추적 중**임을 확인. `stash@{0}` 보존, main 병합/운영 배포 없음.
- V17/V18/V19 적용 DB에서 Flyway 기록 확인: V15 `-1313349794`, V16 `1737846369`, V17 `722139116`, V18 `521162584`, V19 `1456786393`, 모두 success=1. Spring test 부팅 Flyway `19 migrations validated` 및 최신 v19 확인. 이미 적용된 migration은 수정하지 않음.

### CHECKPOINT 1 — Step 7-6A와 핵심 경로 안정화
- V1.0 자료 read-only DB count: baseline XLSX 16(공정도 15 + master 1), 설비 `COUNT(*)=1,332`, `process_relation` 1 (`REVIEW_REQUIRED` 1 / `CONFIRMED` 0), `equipment_flow=0`.
- Step 7-6A typed route/evidence workflow, unauthenticated block, same-endpoint distinct route, EVIDENCE_VERIFIED completeness/downgrade, version/snapshot/input guards, 6시트 preview API 및 schema-v2 호환성을 targeted 통합테스트에 포함. 최신 `Step7WholeNetworkReviewWorkflowIntegrationTest` 7/7 PASS.
- Step 6-5/Step 7-1/7-2/7-3/7-4A/7-4B/7-5B/7-6A 주요 targeted 클래스 46 tests, **46 pass, failures/errors/skips 0**. 실제 source path를 준 Step 7-2 비교 테스트는 조건부 skip 없이 수행. 전체 Maven suite 미실행.

### CHECKPOINT 2 — 시연 페이지 및 A/B/C 흐름
- 새 `/demo.html`은 Java/Spring Boot 정적 resource이며 기존 `/master-diff.html`, Step 6-5 API, 7-6A API를 사용. 신규 UI framework 없음. candidate preview는 신규 관리자 인증 route로 XLSM을 읽기 전용 비교하고 3,082 rows / 33 candidates / hint columns를 반환. 인증 없는 요청은 401 테스트.
- A: DB에서 run 193 / item 217 / plan 33 / `C-PW15020` / `PW-01-05`가 `TEST_ONLY_READY`, `REVIEW_REQUIRED`임을 재확인. 기존 8시트 통합 XLSX 생성 API 응답 `reused=true`; 저장된 TEST_ONLY 출력의 다운로드 SHA와 API metadata 일치.
- B: **기록된 solver result replay**. `DirectedProcessNetworkSolverTest`가 생성한 scenario matrix의 20→80/120, 10→90/110, 50→50/150, 재순환 20/30 및 -20 constraint 결과를 읽어 UI scenario JSON으로 만들었고 workbook row/value assertions 통과. UI는 실시간 solver를 호출하지 않음. Step 7-4B actual report의 baseline residual +5 / after +8 / delta +3도 재검증했으며 합성 `RESIDUAL_UNRESOLVED` 상태로 표시.
- C: 실제 팀원 XLSM SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`, 3,082 input rows, 33 후보, `evidenceVerified=0`, `CONFIRMED=0`, connector endpoint binding 0 확인. 빈 6시트 현장검토 workbook preview `valid=true`, candidate 33, route 0, reference-only 6. UI는 real import를 호출하지 않음.

### CHECKPOINT 3 — Excel/시연 리허설/원본 무결성
- 샌드박스 페이지: `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/demo.html`. Spring Boot `/api/health`, `/demo.html` HTTP 200. Playwright page-load capture에서 JS error 0 및 리소스 404 0 (password field가 form에 속하지 않는다는 verbose 알림만). Playwright UI interaction automation은 제공되지 않아 실제 브라우저 file-picker/버튼 전체 E2E 및 2회 수동 리허설은 **미실행**.
- API/backend rehearsal: Step 6-5 generate/download, candidate preview, 6시트 workbook export, preview API, POI 재열기/header/33 rows/missing 8/reference 6/formula 0, XLSX ZIP integrity 검사. API-level flow 검증이며 end-user full browser rehearsal로 과장하지 않는다.
- V1.0 16개 local = manifest = MariaDB metadata = BLOB SHA, **16/16 PASS** (`scripts/verify-baseline-hashes.py`). team XLSM expected SHA 일치. 공식 기준 원본 XLSX/XLSM은 다운로드 패키지에 넣지 않는다.
- output artifacts: 8-sheet integrated TEST_ONLY workbook, 6-sheet review workbook, solver matrix workbook, Step 7-4B TEST_ONLY report workbook. 상세 SHA는 `validation-results/2026-10-26-step7-6a/FINAL_REPORT_AND_DEMO_GUIDE.md` 참조.

### CHECKPOINT 4 — 최종 보고 자료
- 6장 PPT outline, 지정 핵심 문구, 3분/5분 대본, 완료/미완료 비교표, Q&A 5개, roadmap 작성: `validation-results/2026-10-26-step7-6a/FINAL_REPORT_AND_DEMO_GUIDE.md`.
- PM2 `process-map-automation` online. sandbox URL은 세션형이라 10/28까지 지속성을 보장하지 않음. 세션/인터넷 중단 대비 offline Excel과 발표 원고를 별도 전달.

### 당시 남은 일 및 금지 범위
- 이후 최종 사용자 확인을 추가해 아래 CHECKPOINT 5로 갱신한다. 실제 담당자 review import 미실행, verified evidence/CONFIRMED/실제 계산망/전체 수지 미완료. 전체 Maven suite 미실행. main merge, stash 변경, 운영 배포 금지.

---

## 최종 사용자 검증 및 전달 산출물 — CHECKPOINT 5 (2026-10-26)

### 보존·무결성·DB
- 시작 상태는 `step7-3-wip-20261023` branch, HEAD `a9b3120e90b6790f5a0c6b2d3f18716d282c71e6`, clean. `stash@{0}: On main: WIP step6-4 workbook enrichment not complete` 유지. 작업 중 DB 초기화, migration 수정, stash 삭제, main merge, 운영 배포 없음.
- `V18__bind_route_and_evidence_to_review_history.sql` SHA-256 `74e6aa7f80c0d848ec522a0be54d6c41651d304697a67c3c489b611cb1c873c7`; V19 SHA-256 `0ba09b7c0569bb13b28158a1ce6314500638ad7e4030c99a669fb67f80c0bdaa`. 두 migration Git 추적 상태이며 DB Flyway V15–V19 모두 success=1, 최신 version 19. 파일은 변경하지 않음.
- 요청 직후 공식 V1.0 XLSX 16개 local/manifest/MariaDB metadata/BLOB SHA 재대조 **16/16 PASS**. 팀원 XLSM 검증 복사본 SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` 일치. 공식 파일과 팀원 원본은 배포 파일에 넣지 않음.
- 최종 DB read-only counts: baseline documents 16, equipment 1,332, process relation `REVIEW_REQUIRED` 1 / `CONFIRMED` 0, review snapshot 1 / candidate 33, review case/history/route/provenance 0, `equipment_flow` 0. 앞선 중간 조회에서 잘못된 table alias로 실패한 질의는 실제 스키마명으로 바로잡아 재조회했고, 위의 성공 조회값만 채택.
- PM2 앱 프로세스를 재시작한 뒤 Flyway v19 validation 완료, HTTP 정상 및 DB count 보존을 확인. 전체 sandbox restart는 수행하지 않았으므로 그 경우의 DB 영속성은 미확인.

### 웹·브라우저 조작 확인
- 별도 browser session에서 `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/demo.html` 로드 및 title 확인. Console JS error 없음; password input이 form 바깥에 있다는 verbose 안내 2건만 관찰.
- 재시작 후 `/demo.html`, `/master-diff.html`, `/api/health`, `/demo-scenario-matrix.xlsx` 모두 HTTP 200. 서버 루트 `/`는 HTTP 404이므로 demo URL을 직접 연다. 해당 sandbox URL은 임시 세션 주소로 운영 도메인·영구 외부 접속·다중 사용자 지원을 보장하지 않음.
- API request smoke test: candidate preview token 누락 401, 인증 후 잘못된 확장자 400, OOXML signature가 없는 입력 400. 실제 token 값은 로그·화면/문서/ZIP에 기록하지 않음.
- browser tool은 페이지 이동/로드 capture만 제공하고 file picker 및 버튼 조작을 수행하지 못함. 따라서 사용자가 요청한 단계별 전체 브라우저 E2E 2회는 **미완료**. 이전 API/backend rehearsal과 targeted integration test를 browser interaction으로 과장하지 않음. 직접 2회 확인할 checklist는 `validation-results/2026-10-26-step7-6a/DEMO_RUNBOOK.txt` 및 `MVP_FINAL_VALIDATION_SUMMARY.md`에 제공.
- 실제 browser에서 8시트/6시트 파일 내려받아 Excel에서 여는 최종 UI 동작은 사용자 확인 필요. 생성 파일은 API-level/POI/openpyxl 재열기와 ZIP 검증을 통과.

### 타깃 회귀·파일 검증
- 최신 변경 영향 targeted 7개 클래스: `Step7WholeNetworkReviewWorkflowIntegrationTest` 7, `Step7MaterialBalanceComparisonServiceTest` 3, Step 6-5 delivery/storage/enrichment 5, integrated controllers 7; 합계 **22 tests / 22 pass / 0 failure / 0 error / 0 skipped**. 조건부 Step 7 workflow tests는 `step7.whole.source` 속성을 지정해 skip 없이 실행. 이전 wider Step 6/Step 7 regression 46/46 PASS도 있음. 전체 Maven suite는 실행하지 않음.
- 독립 재열기/ZIP integrity: Step 6-5 integrated Excel 8 sheets, existing formulas 10개; solver scenario matrix 4 sheets/formula 0; PW-01-05 candidate review 6 sheets/formula 0; Step 7-4B report 5 sheets/formula 0. 33 candidates/8 missing/6 reference-only 상태와 기존 보고서 숫자를 유지.
- simulation section은 저장된 solver TEST_ONLY 결과를 재생하며 실시간 solver 계산 아님. candidate preview는 읽기 전용; 검토 workbook 생성은 snapshot/candidate source를 생성 또는 재사용할 수 있으나 case/history, 공식 approval, `equipment_flow`를 만들지 않음. 실제 담당자 import는 미실행.

### 사용자 전달 파일·다음 확인
- 전달 디렉터리 `validation-results/2026-10-26-step7-6a/`에는 최종 보고·시연 안내, 기능 상태/검증 요약, 실행 runbook, 3개 요청 Excel, 보조 Step 7-4B report 및 checksum manifest를 정리한다. 공식 원본 16개, 팀원 XLSM, `.env`, credentials는 제외한다.
- 파일별 SHA-256과 ZIP SHA는 `SHA256SUMS.txt` 및 별도 ZIP hash를 통해 보고한다. 사용자에게 report 및 개별 Excel/ZIP 다운로드 링크를 제공한다.
- 미완료: 사용자가 실제 브라우저로 동일한 주요 흐름을 새로고침 포함 2회 실행, 전체 sandbox restart 후 DB persistence, 회사 정식 URL/다중 사용자 확인, 전체 Maven suite, 실제 담당자 review/import 및 공식 승인, 실제 전체 공정 연결/물질수지 검증.
- 최종 선별 commit: README.md와 CHECKPOINT.md만 반영하는 문서 커밋을 생성했다. 커밋 후 작업 트리 clean 여부 및 최종 HEAD는 완료 확인에서 기록한다. 검증 report/runbook/summary 및 다운로드 패키지는 ignored `validation-results/` 아래 전달 산출물로 유지한다. 요청 금지사항은 계속 유지.

---

## 숫자형 작업본·PW-01 C-PW15020 안전 검증 — 재개 CHECKPOINT A–F (2026-10-26)

### CHECKPOINT A — 재개 시 기준점 및 무결성
- 재개 직후 branch `step7-3-wip-20261023`, HEAD `44b1968ba2e4c93f7be6a54fcbb90e2dcffd8eb9`, 작업트리 clean. 직전 기능 코드/테스트는 이미 이 commit에 포함됐고 기존 `stash@{0}`는 건드리지 않았다.
- Flyway V19/V20 success=1; V20 checksum `-1080684895`, DB는 version 20. migration 파일 수정 없음. baseline document 16개, `process_working_document` 15개, 기존 requests 2개로 시작.
- `scripts/verify-baseline-hashes.py`: V1.0 local/manifest/MariaDB metadata/BLOB SHA `16/16 PASS`. 각 숫자형 업로드의 파일 SHA = 등록 응답 SHA = MariaDB BLOB SHA `15/15 PASS`. 공식 16개, 사용자 제공 숫자형 15개, V20 migration을 수정하지 않았다.

### CHECKPOINT B — PW-01 숫자 차이
- 공식 `PW-01 폐수처리공정 ★변경허가.xlsx`와 사용자가 제공한 `PW-01_폐수처리공정 (수정).xlsx`의 `PW-01-05` 같은 좌표를 재열어 비교했다. 여섯 차이는 모두 literal 값이며 영향 시트에서 formula cell은 0개. `톤/일` 표시 외 기간 근거는 확인하지 못했다.
- `BB179`, `AQ164`: 공식 `일반폐수 16,439.95 톤/일`, 숫자형 `16,440.17`, 차이 `+0.22`.
- `AH51`, `AH97`, `AK147`: 공식 `18,458.53`, 숫자형 `18,458.75`, 각 `+0.22`. `AO41`: 공식 `28,458.53`, 숫자형 `28,458.75`, `+0.22`.
- 수식/참조 셀 이동, 단순 변환 오류, 수기 변경 중 어느 것이 원인인지 증거로 확정하지 못했다. 값 수정 0건; `REVIEW_REQUIRED` 유지, 실측 계산에 포함하지 않음. 세부 좌표/merge 표기는 `validation-results/2026-10-26-numeric-working/pw01-numeric-discrepancy-audit.json`.

### CHECKPOINT C — 병합·도면 구조
- PW-01-05 공식 병합 84개, 숫자형 작업본 93개. 숫자형에서 추가된 9개 범위는 `F80:H80`, `AH51:AU51`, `AH97:AU97`, `AI182:AV182`, `AK147:AX147`, `AO20:BB20`, `AO41:BB41`, `AP197:BB197`, `AQ164:BB164`; 삭제는 0개.
- 공식→작업본 DrawingML object 수는 103→103, 그러나 기존 ID 95 및 103 anchor가 달라졌다. 사용자가 만든 숫자형 workbook 내부의 변경이므로 원인·의도는 `REVIEW_REQUIRED`; 병합/anchor를 자동 복원하거나 업로드 원본을 변경하지 않았다.
- 작업본→기존 request 2 생성본 비교: 기존 object anchor 11개가 모두 정확히 +14행 이동, 삭제 0, 신규 ID 160(시각 connector)과 161(status callout) 2개. 새 object는 실제 relation 승인/route 증거가 아니다. 생성에 따른 병합 범위 변경도 별도 기록. 화면상/Excel native render로 기존 연결 의미까지 확인된 것은 아니다.
- 전체 차이와 판단은 `pw01-structure-audit.json`. 기존 객체 이동은 삽입 엔진의 14행 삽입 효과와 일치하지만, 숫자형 업로드의 사전 구조 변경은 계속 `REVIEW_REQUIRED`.

### CHECKPOINT D — 합성 입력 및 실제 생성/다운로드
- 기존 request 1–2는 보존. 서로 다른 명시 합성 수량·기간·경로 입력으로 request 3을 별도 preview→generate→download했다. 모든 합성 필드는 `SYNTHETIC_TEST_ONLY` 또는 `SYNTHETIC_NO_EVIDENCE`로 표시했고 실제 자료로 쓰지 않았다.
- request 3 상태 `GENERATED`, `CALCULATION_UNAVAILABLE`, `REVIEW_REQUIRED`; `actualRoutesAdmitted=0`, `calculationInputTransferCount=0`, 공식 approval false, 공식 기준수정 false. 저장된 출력 SHA와 실제 다운로드 응답 헤더/파일 SHA 동일: `cc7912a3c5d41527e351e6468779179639ce3e60873c97a916d984a7ab576e22`. 출력 10 sheets; ZIP integrity 및 openpyxl 재열기 PASS. 합성 숫자·경로는 `사용자 입력사항`에만 있고 물질수지 결과가 아니다.
- request 3 생성은 의도된 분리 TEST_ONLY request/output 저장이며 DB 초기화/공식 기준자료 갱신/관계 승인은 하지 않았다. 이어서 기존 C-PW15020 reference case를 다시 불러와 request 4를 생성하고, source에 있던 폐수·폐기물·경로 공란을 유지한 채 TEST_ONLY workbook을 내려받았다. request 4도 `CALCULATION_UNAVAILABLE`, actual routes 0, calculation inputs 0, relation `REVIEW_REQUIRED`, official approval false.
- request 4 저장 출력/다운로드 SHA `f0e80678b5651d9d37aebb191308f2c69ae7d2e4bdc9e19ffbb19b5243e82aea`, 10 sheets, ZIP integrity/openpyxl 재열기/explicit black 스타일 확인. 실제 폐수·폐기물 수량 및 경로는 결과 시트에서 공란이다. 파일: `validation-results/2026-10-26-numeric-working/PW-01-05_C-PW15020_CaseReference_TEST_ONLY.xlsx`.
- post-action baseline 16, working copies 15, process change requests 4 (기존 1–2 유지, 합성 TEST_ONLY 3, 공란 보존 reference-case TEST_ONLY 4), equipment 1,332, `CONFIRMED` relation 0.

### CHECKPOINT E — 웹·Excel 산출물
- `/`의 공정도 선택→입력→결과/다운로드 3단계 화면을 최신 build로 재확인. 인증 token 입력은 `<form>` 내부로 옮겨 password-field browser warning을 제거. Playwright page load에서 title `공정도 변경 작업`, console/page errors 0; UI에서 file-picker 및 모든 버튼을 실제 클릭한 full browser E2E는 미검증.
- 출력 writer는 새로운 표 worksheet의 text/number cells에 명시적 ARGB `FF000000` font를 부여하고 original fonts/cellXfs의 앞부분을 수정하지 않는 append-only 검증을 추가했다. 생성 결과의 4개 부가 시트 전체 cell style에서 명시 RGB 검정, ZIP integrity, POI/openpyxl 재열기 PASS. LibreOffice/Excel native visual render는 미검증.
- 주요 전달 대상 `validation-results/2026-10-26-numeric-working/PW-01-05_C-PW15020_CaseReference_TEST_ONLY.xlsx`는 기존 사례를 유지하고 실제 물량/경로 공란을 보존한 request 4 출력이며, 최신 black-style 구현을 거쳤다. 별도 합성 테스트 request 3 산출물 `PW-01-05_C-PW15020_SYNTHETIC_TEST_ONLY.xlsx`도 구분해 보존한다. 이전 request 2 산출물은 과거 결과로 보존한다.

### CHECKPOINT F — 테스트·문서·작업 보존
- `./mvnw -q -DskipTests package` PASS. `MaterialBalanceExcelExplicitBlackFontTest` 1/1, `Pw0105EquipmentInsertionEngineTest` 2/2, `Step7DrawingEvidenceExtractorTest` 2/2 PASS.
- `MaterialBalanceExcelExportIntegrationTest`는 credentials를 로드한 실행에서 600초 제한에 걸려 PASS로 계산하지 않는다. Surefire thread dump는 `OoxmlPackageInspector.inspect`/`absoluteYEmu`의 대형 workbook anchor 처리 중이었음. 초기에 `.env` 미로드 실행은 DB 인증 실패였고, 재실행은 DB 연결/Flyway V20 validation 후 위 timeout이었다. 전체 Maven suite는 미실행.
- 최종 reference-case 및 합성 artifact는 download/DB BLOB SHA 일치, workbook ZIP/reopen, 4개 새 시트의 explicit black style 및 `CALCULATION_UNAVAILABLE`를 검증. 최종 V1.0 local/manifest/DB metadata/BLOB 해시 16/16 및 숫자형 업로드/DB BLOB 해시 15/15 재확인.
- README 및 CHECKPOINT에 현재 결과·한계 반영. 변경 코드는 `step7-3-wip-20261023` 브랜치에서 선별 WIP commit으로 보존한다. main 병합, stash 조작, 운영 배포, DB 초기화 없음. 커밋 SHA와 post-commit 작업트리 상태는 최종 응답에 기록한다.

---

## PW-01 숫자형 변환 오류 복구 — CHECKPOINT A (2026-10-26)

### 현재 시작 상태 및 보존
- 브랜치 `step7-3-wip-20261023`, HEAD `65bd2313f53b9a3afbe71d00e518edb73eeb4fc2` (`genspark auto-backup`), 시작 시 작업트리 clean. 이전 턴의 converter/API/UI/test 관련 10개 파일은 이 WIP commit에 이미 포함되어 있어 이번 시작점에서 미커밋 파일은 없었다. 기존 stash, `main`, 공식 원본, 사용자 숫자형 파일 및 migration을 변경하지 않았다.
- Flyway V20 `success=1`, checksum `-1080684895`; DB version 20. Baseline 16개, `process_working_document` 15개, 기존 change request 4개. 이전의 `NUMERIC-WORKING-UPLOAD-V1` 15개 각각 로컬 업로드 파일 SHA = metadata SHA = DB BLOB SHA **15/15 PASS**.
- 공식 V1.0 원본은 local/manifest/DB metadata/BLOB SHA **16/16 PASS** (`python3 scripts/verify-baseline-hashes.py`). PM2 `process-map-automation`은 중지 상태로 시작했다.

### 실패 원인 및 재개 위치
- 이전 integration test에서 변환기가 `PW-01-01!D296` 복합 문구 `황산(9.5%) 0.8톤/일`을 숫자형으로 변환하려 시도했다. 저장된 custom format은 `"황산(9.5%) "0.0"톤/일"`이었지만 POI 재열기 표시값은 `0.8`만 반환해 converter가 정확 표시 검증에서 중단했다. 이는 검증 실패이며 후보는 DB에 저장되지 않았다.
- 기존 구현의 prefix 그룹이 임의 문구를 허용해 순수 수량과 혼합 설명을 분리하지 못했다. 재개 시 엄격한 `수량 + 허용 단위` 셀만 numeric 전환하고, 물질명/농도 등 복합 문구는 원본 텍스트 유지 및 별도 `REVIEW_REQUIRED` 판정으로 전환한다. 복합 문구를 변환한 뒤 오류를 무시하는 방식은 금지.
- 마지막 재현 명령: `set -a && . ./.env && set +a && ./mvnw -q -Dtest=NumericWorkingCopyWorkflowIntegrationTest test`; 4 tests 중 converter 후보 생성 1건 실패. 독립 합성 converter fixture는 D296 오류 전에 통과했으나, 숫자형 전체 official file 검증은 미통과.
- 다음 순서: B) converter classify/preserve + fixture/official regression → C) 공식 PW-01 별도 후보 생성 및 구조/표시/ZIP/POI 검증 → D) 검증 후보를 사용한 C-PW15020 TEST_ONLY 출력 → E) PM2/UI/API 확인 → F) 회귀 및 official/upload hashes 재검증 → G) artifact manifest, README/CHECKPOINT, WIP 커밋. 필수 검증이 남으면 BLOCKED로 명시.

---

## PW-01-05 범용 사용자 입력 복구 작업 시작 — 2026-10-27

### 시작 기준과 보호 상태
- 작업 시작 시 branch `step7-3-wip-20261023`, HEAD `4359cfe68b18bc7f652bf9c30ebfe8cd26df6138`였고, 기존 3개 파일(`NumericWorkingCopyService.java`, `ProcessWorkspaceService.java`, `static/index.html`)의 미커밋 변경을 보존했다. 앞선 세션 종료 시 자동 백업 WIP commit `3b89321f3e7aeca4fea7c2c2caacc7d9021d8383`으로 이 변경과 컴파일 수정을 저장했고, 작업 재개 시 clean worktree였다. 이를 reset/stash하지 않음.
- Flyway V20 success=1/checksum `-1080684895`; migration 파일 변경 0. DB 읽기 전용 확인: V1.0 문서 16, 작업본 16(숫자형 업로드 15 + 공식 안전 후보 1), 설비 1,332, 요청 5, `REVIEW_REQUIRED` 관계 1.
- 공식 V1.0 local/manifest/MariaDB metadata/BLOB 해시 16/16 일치. 후보 ID 36은 baseline document 12 기반, `PW01-OFFICIAL-NUMERIC-V1` / `CREATED_WITH_REVIEW`, 작업본 SHA `0e68fb76192f56749c0ac623bc8a4481e4851a822ec8724169587b0a55964896`; 승인본 아님. 기존 request 5는 `GENERATED` / `CALCULATION_UNAVAILABLE`, SHA `9648824d128656c9aca0b093866e228412af12d13fa0974ae83c840f0e168ec8`로 보존.
- 기존 업로드본 15개와 공식 원본은 이후 각 SHA 검증 예정. 기존 +0.22 여섯 셀(`BB179`, `AQ164`, `AH51`, `AH97`, `AK147`, `AO41`)의 `REVIEW_REQUIRED` 상태 유지.

### 실패 재현
- 새 생성 소스 컴파일에서 `ProcessWorkspaceService.generate()`의 중복 `storedPlan` 선언과 `Map.getOrDefault`의 `Object` 반환을 String으로 사용하는 컴파일 오류 4건 재현. 중복 선언 제거 및 `String.valueOf` 명시로 컴파일 오류를 수정해 WIP commit `3b89321`에 보존.
- 최신 focused suite 재실행에서 `NumericWorkingCopyWorkflowIntegrationTest.previewsGeneratesAndDownloadsTestOnlyRequestWithoutChangingRequestFive`가 HTTP 400을 반환. 고정 C-PW15020 payload에 새 필수값 `placementId`, `equipmentTypeLabel`이 누락돼 기존 validation이 거부한 것으로 응답 본문에서 확인. validation 완화는 하지 않고 fixture를 유효하게 수정할 것.
- `Step6TestOnlyWorkbookEnrichmentIntegrationTest` 2개 테스트가 새 `xl/styles.xml`을 기존 parts 전체와 byte-equal 비교하며 실패. 신규 결과 시트 코드가 새 sheet cell의 명시적 검정 글꼴을 위해 기존 `fonts`와 `cellXfs` 뒤에 append-only 항목을 추가함을 코드에서 확인. `validateStyleExtension`은 기존 font/XF가 순서·값 그대로 유지되는지 검사하지만, integration test의 `allowedChanged` 목록에 styles part가 빠져 있음. 후속으로 styles XML 구조 차이, style ID 참조 및 기존 도면 셀 표기/서식을 검증하고 테스트 계약을 구체적으로 수정.
- 이 실행은 전체 43개 회귀를 통과하지 못했으며 성공으로 기록하지 않는다. 실패 재현 시 신규 미리보기는 validation 단계에서 중단되어 request row를 만들지 않았고, Step 6 검증은 read-only output 생성임. 기존 요청 5 재생성/수정은 수행하지 않음.

### 다음 재개 지점
- 유효한 `placementId=PW01-05-BETWEEN-C-PW15009-C-PW15010`, `equipmentTypeLabel=여과시설`을 고정 fixture에 추가하고, 서로 다른 synthetic equipment 2건으로 preview→generate→download를 각각 검증한다.
- 스타일 테스트에는 `xl/styles.xml`의 기존 `numFmts/fonts/fills/borders/cellXfs` 정의 보존과 신규 결과 스타일만 허용된 추가인지, 새 시트 셀 스타일 참조가 유효한지, 기존 도면 셀 style ID/표시값 불변인지 명시한다. assertion을 삭제하거나 무차별 비교를 건너뛰지 않는다.
- 두 출력은 트랜잭션 롤백 테스트에서 bytes로 검증하고 별도 TEST_ONLY 산출물로 보존. 실제 공정 수지는 근거 미달 `CALCULATION_UNAVAILABLE`; 실제 도면 유량 라벨/관계 승인은 만들지 않는다.

---

## PW-01 안전 후보 / C-PW15020 request 5 최종 안정화 — 2026-10-27

### 보존 상태 및 무결성
- 실제 시작 branch `step7-3-wip-20261023`; HEAD `5cd7a6b92393abac60426798a83d1e32fb61946b` (`genspark auto-backup`), 그 이전에 사용자 기준 `de64db9` 및 `65bd231`이력. 이번 수정 전 worktree clean. main/stash/migration/공식 XLSX/업로드 입력 변경 및 운영 배포 없음.
- V20 Flyway `success=1`, checksum `-1080684895`. 최종 DB 조회: V1.0 baseline 16, equipment 1,332, 기존 NUMERIC-WORKING-UPLOAD 15, CONFIRMED 관계 0.
- 공식 원본 local/manifest/DB metadata/BLOB SHA 16/16 PASS. 등록 업로드본 15개 입력 파일/DB metadata/BLOB 15/15 PASS. 후보 ID 36 metadata/BLOB/파일 SHA 일치 `0e68fb76192f56749c0ac623bc8a4481e4851a822ec8724169587b0a55964896`. request 5 metadata/BLOB/다운로드 SHA 일치 `9648824d128656c9aca0b093866e228412af12d13fa0974ae83c840f0e168ec8`.

### 분류 집계 오류 수정
- Converter/service가 셀 주소당 하나의 분류만 집계하고 중복 (sheet,cell)을 거부. 상태별 결과: `CONVERTED=5`, `REVIEW_REQUIRED_UNSUPPORTED_UNIT=2,580`, `PRESERVED_COMPOUND_TEXT_REVIEW_REQUIRED=338`, `REVIEW_REQUIRED_COMPOUND_MULTIPLE_NUMBERS=70`; total/unique 2,993, disjoint=true.
- 복합 문구만 408(338+70), unsupported unit 2,580, `compoundOrUnsupportedCells=2,988`(전체에서 CONVERTED 5 제외). JSON 정의를 코드 분류와 일치시킴.
- D296 `황산(9.5%) 0.8톤/일`은 STRING 및 원문 표시 유지. 0.8톤/일 추출값은 metadata만이며 수지 계산 제외, 상태는 `CALCULATION_UNAVAILABLE`.

### 안전 후보 및 request 5 Excel 확인
- 후보 36의 6시트/순서, POI 재열기, ZIP integrity, drawing parts byte identity, PW-01-05 병합 84, D296 텍스트 및 5개 conversion 확인. 기존 +0.22 차이 6셀(`BB179`, `AQ164`, `AH51`, `AH97`, `AK147`, `AO41`) 모두 REVIEW_REQUIRED. 기존 입력본의 사전 병합/anchor 편차는 수정하지 않음.
- request 5는 설비 `C-PW15020 / SCREEN FILTER / WW-F-AFT10 / 1,500㎥/시 / 1ea`; 폐수·폐기물 입력은 공란. 기존 객체 11개 anchor가 각 +14행, 삭제 0, 신규 ID 160/161은 시각 표시뿐이며 논리 binding 없음. `CALCULATION_UNAVAILABLE`, route admitted 0, transfer input 0, 승인 false.
- 기존 후보/요청 산출물은 재생성하지 않음. 보호 Excel 7개 테스트 전·후 SHA 동일. Candidate 36 및 request 5를 인증된 GET API로 다운로드, 응답 hash와 DB BLOB 일치 및 ZIP 검증. request 5 재생성 POST는 이미 GENERATED 상태라 HTTP 409로 차단됨(저장본 불변).
- 테스트 산출물 격리: `Pw0105EquipmentInsertionEngineTest`와 `DirectedProcessNetworkSolverTest`는 고정 경로 write 없이 byte[]에서 검사; `MaterialBalanceExcelExportIntegrationTest`는 `@TempDir` + transactional rollback.

### 회귀 테스트
- 최종 Surefire 보고서: Converter 1/1; Numeric workflow 5/5; insertion 2/2; Excel export 3/3; Step 6-5 controller/delivery/storage/enrichment 12/12; Step 7 solver/review/network/ledger 19/19; explicit font 1/1. 총 **43/43 PASS, failures=0, errors=0, skipped=0**. 전체 Maven suite는 실행하지 않음.
- Numeric workflow에 transactional MockMvc preview→generate→download 테스트를 추가: 별도 request는 rollback, request 5 SHA 보존, POI 값 및 공란, 0 route 검증. 첫 Maven 실행은 `.env` 미로드로 DB 인증 단계에서 실패했고, 자격증명 로드 재실행에서 새 assertion의 worksheet row offset 오류를 발견해 실제 표 구조에 맞춰 수정했다. 최종 5/5 PASS; 중간 실패는 pass 집계에서 제외.

### 웹 확인 및 미완료
- PM2 재시작 후 `process-map-automation` online, `/api/health` HTTP 200, `/` HTTP 200/title `공정도 변경 작업`; browser page capture console message 0. 3단계 업무 흐름 안내 확인.
- 실제 request 5 GET과 candidate/request download는 API 확인. preview→generate→download는 transactional MockMvc로 검증; 기존 request 5를 새로 생성하지 않기 위해 live preview 버튼은 누르지 않았다. Playwright는 페이지 load만 수행했으므로 브라우저 실제 버튼 클릭 E2E 미검증. Excel native renderer/인쇄 검증 및 전체 Maven suite는 미완료.
- 최종 수정본의 Excel·JSON 결과 파일은 validation-results에 그대로 보존하되 Git에 포함하지 않는다. 운영 배포 및 main 병합 금지 유지.

---

## PW-01-05 사용자 입력 A/B 최종 검증 — CHECKPOINT A–E (2026-10-27)

### CHECKPOINT A — 조건부 skipped 테스트 재실행
- 직전 기록의 18 skipped는 입력 자료 경로 system property가 설정되지 않아 비활성화된 테스트였다. 입력 자료를 합성 데이터로 대체하지 않고 실제 기존 파일로 재실행했다.
- `step7.connection.source`: `validation-results/2026-10-23-step7-5b/team-source.xlsm`, SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` — 1 test.
- `step7.bridge.source`, `step7.review.source`, `step7.whole.source`: 기존 `validation-results/2026-10-23-step7-5a/PW-01-05_실제연결망_현장확인요청_TEST_ONLY.xlsx`의 읽기 전용 ASCII 경로 사본 `/tmp/step7-field-review-source-20261027.xlsx`, SHA-256 `af9a9113850e6cc412786f7735b994288500e6cab1aaf28e351653d9ee092c50` — 각각 6, 4(ledger 3 + review network 1), 7 tests. 원본과 ASCII 사본 hash 동일.
- 재실행한 정확한 18 tests: `Step7ConnectionEvidenceIntegrationTest.extractsTargetSheetEvidenceAndWritesFiveSheetReviewWorkbook`; `Step7FieldReviewBridgeIntegrationTest`의 `existingStep74UploadAndAuthRoutesRemainCompatible`, `actualStep75aWorkbookMapsOnlyExistingSnapshotCandidatesAndExportsReadOnlyReport`, `missingUnitPeriodAndEvidenceDowngradeVerifiedSuggestionToNeedsMoreInfo`, `versionConflictsAndUnsafeWorkbookPackagesAreRejected`, `mapsFieldInputToSchemaV2AndPersistsHistoryIdempotentlyWithoutApproval`, `invalidEndpointNumericTextNewCandidateAndDuplicateOrUnknownRouteNeverPersist`; `Step7ReviewLedgerWorkflowIntegrationTest`의 `downloadUploadValidationPersistenceIdempotencyAndStatusStayTestOnly`, `rejectsUnsafeWorkbookPackagePartsAndUnauthorizedOrInvalidUploads`, `downloadableTemplatePersistsOnlyItsImmutableSnapshot`; `Step7ReviewNetworkIntegrationTest.adaptsActualReviewsReadOnlyAndRunsSeparatedSyntheticScenarios`; `Step7WholeNetworkReviewWorkflowIntegrationTest`의 `rejectsStaleVersionDuplicateRouteUnknownCandidateAndUnsafePackages`, `exportsAllCandidatesAndKeepsReferenceRowsSeparate`, `persistsTypedEvidenceToReviewVersionAndHistoryButNotOfficialApproval`, `rejectsStaleSnapshotMissingQuantityProvenanceAndConflictingEndpoints`, `acceptsTwoDistinctRoutesAndPersistsTypedHistoryButNeverApprovesOrCalculates`, `blocksTextQuantityAndStaleVersionAndLeavesActualNetworkUnavailable`, `downgradesVerifiedSuggestionWhenRequiredEvidenceIsIncomplete`.
- 이 18건은 모두 PASS, skip 0. 최신 focused 보고서 mtime 기준 묶음은 17개 테스트 클래스, **54/54 PASS, failures 0, errors 0, skipped 0**. 전체 Maven suite는 실행하지 않았다.

### CHECKPOINT B — 두 Excel 입력·DrawingML·OOXML 확인
- A: `SYNTH-PW01-TEST-A` / `Synthetic Filter Alpha` / `SYN-ITEM-A` / `475m3/h` / `2 units`; 결과 `validation-results/2026-10-27-pw01-05-user-input-final/PW-01-05_SYNTH-PW01-TEST-A_request-35_TEST_ONLY-integrated.xlsx`; SHA-256 `8b2ec704d8350128f9a047f1b57da4bbc0216af8bc0c1b28f92027b81ceac4d1`.
- B: `SYNTH-PW01-TEST-B` / `Synthetic Clarifier Beta` / `SYN-ITEM-B` / `820kg/h` / `3 units`; 결과 `validation-results/2026-10-27-pw01-05-user-input-final/PW-01-05_SYNTH-PW01-TEST-B_request-36_TEST_ONLY-integrated.xlsx`; SHA-256 `ebae050096692513d5a10715ea05063df4231ff0080ccc772d6518a1f4d03b67`.
- 두 파일 모두 `PW-01-05!AI198`의 설비번호, AI201 명칭, AI202 Item No., AI203 용량, AI204 수량이 입력과 정확히 일치한다. TSV `input-to-diagram-comparison.tsv`를 산출물 workbook과 별도로 대조했다. 각 파일 ZIP integrity, OpenPyXL 독립 재열기, 통합 테스트 Apache POI 재열기 PASS; 각 10시트, PW-01-05 병합 85개.
- 후보 ID 36의 PW-01-05 DrawingML 객체 103개를 기준으로 비교: 기존 객체/ID/이름/유형/텍스트/화살표 보존, 기존 객체 삭제 0. 의도된 추가는 A/B별 TEST_ONLY visual connector 1개(ID 160), REVIEW_REQUIRED status 1개(ID 161)이며 논리 연결 승인 아님. 각 workbook의 기존 worksheet 셀 표시값/type/style ID, 수식, 병합 외 기존 DrawingML 및 ZIP 비대상 파트 보존. 병합 84→85는 새 삽입 위치의 의도된 변화. `styles.xml`은 결과 시트의 명시적 검정 글꼴을 위한 append-only font/XF 추가만 허용·검증.
- 수량·합성 연결망은 실제 설비자료나 공식 승인/수지 결과가 아니다. A의 합성 fixture는 분리된 TEST_ONLY 계산 상태이며 실제 PW-01 balance는 `CALCULATION_UNAVAILABLE`.

### CHECKPOINT C — 공식·작업본·DB 보호자료 사후 상태
- 공식 V1.0 파일 16개 local SHA = `SHA256SUMS` 16개 = DB metadata SHA 16개 = DB BLOB SHA 16개, **16/16 PASS**. 비교는 read-only로 실행했으며 고정 검증 CSV를 덮어쓰지 않았다.
- `NUMERIC-WORKING-UPLOAD-V1` 15개 업로드 파일 각각 local upload SHA = DB `working_sha256` = BLOB SHA, **15/15 PASS**.
- 안전 후보 ID 36은 `PW01-OFFICIAL-NUMERIC-V1` / `CREATED_WITH_REVIEW`, SHA `0e68fb76192f56749c0ac623bc8a4481e4851a822ec8724169587b0a55964896` 유지. 기존 request 5는 `GENERATED`이며 파일/metadata/DB BLOB SHA `9648824d128656c9aca0b093866e228412af12d13fa0974ae83c840f0e168ec8` 일치.
- 사후 read-only counts: V1.0 문서 16, 설비 1,332, `process_relation` REVIEW_REQUIRED 1 / CONFIRMED 0, Flyway V20 `success=1`, checksum `-1080684895`. 기존 discrepancy `+0.22` 6건은 `REVIEW_REQUIRED` 유지. 공식 원본·숫자형 업로드·migration은 수정하지 않았다.

### CHECKPOINT D — PM2·HTTP/API 흐름·샌드박스 접근
- `./mvnw -q -DskipTests package` PASS 후 PM2 `process-map-automation` 재시작, online 확인. `/api/health` HTTP 200; `/`의 3단계 업무 화면 HTTP 200. 브라우저 page load/title `공정도 변경 작업`/console 0 확인. 익명 관리자 목록 요청은 401.
- 최초 smoke의 한 요청은 재시작 전 오래 실행 중이던 JVM 코드에서 `GENERATION_BLOCKED`로 저장되어 request 37로 남았고 output은 없음. 이는 삭제/변경하지 않고 실패/검토 요청 상태로 보존했다. 최신 package/PM2 재시작 후 새 합성 ID로 재검증하여 request 38 preview→GET 확인→generate→download 모두 성공; 상태 GENERATED, 다운로드 hash와 DB SHA `2a68b81274233822e079c3c7e77e1ceef59649b4ec2acac7ab2c70dc8a276927` 일치, `X-Official-Baseline-Modified=false`, ZIP integrity PASS. request 37 및 38은 검증 과정의 추가 사용자 입력 요청이며 공식 기준 설비로 취급하지 않는다.
- A/B request 35/36은 별도의 transactional integration test가 저장한 artifact 명칭이며 DB 영속 request record로 보지 않는다. DB의 기존 request 5는 보존. 현재 DB request count 7에는 원래 5건과 이번 API smoke 37/38이 포함된다.
- 현재 접속 가능한 세션형 sandbox URL: `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/`. 외부 운영/영구 도메인이 아니다. Browser tool은 페이지 capture만 가능하고 버튼 클릭을 지원하지 않아 end-user full browser E2E는 미완료; API-level 흐름은 완료.

### CHECKPOINT E — 문서·Git·중단 경계
- README에 A/B 값·SHA, 54-test 요약, 해시/DB 보존, PM2/API 확인, 임시 sandbox URL, 미지원 기능을 추가했다. 본 CHECKPOINT A–E에도 원본 입력 경로·SHA, 18개 테스트명, Excel/도면 검증, request 37/38 결과 및 browser E2E 제한을 기록했다.
- Excel 및 TSV는 `validation-results/` 아래에 보존하며 Git에 넣지 않는다. 코드 변경은 기존 `d4b17f4`에 보존됐고, 문서 최종 업데이트는 별도 선별 커밋한다. `main` 병합·stash 조작·DB 초기화·원본/migration 변경·운영 배포를 하지 않는다.
- 미지원: 설비 변경/삭제, PW-01-05 외 시트/공정/삽입 위치, 임의 자동 배치. 실제 PW-01 전체 물질수지는 계산 불가하며 승인된 실제 route/증빙/전 기간 scope가 없다. 단위 변환, 실측 저장량·기간연속 보존, 복잡한 cycle 수렴, 공식 유량/도면 수정 및 로그인/RBAC/운영 배포도 미완료.
- 이 단계에서는 추가 기능 개발을 시작하지 않는다.

---

## UI 간소화·실제 ADD·MODIFY 최종 확인 — 2026-10-27

### 보존 시작 상태 및 DB 접속 원인
- 기준 branch `step7-3-wip-20261023`, WIP 기준 commit `6834ef6` 및 기존 5개 파일의 작업 내용은 보존했다. 기존 파일 변경은 자동 백업 commit에 포함됐고, 이번 UI/서비스 안전 변경은 `0621f45`에 저장되어 있다. stash/main/기준자료/기존 생성 Excel을 초기화하거나 변경하지 않았다.
- 첫 직접 Maven 테스트는 `scripts/test-local.sh`가 하는 `.env` 로드 과정을 생략해 `application.properties`의 빈 `DB_PASSWORD` 기본값으로 접속했다. MariaDB는 `process_dev@localhost` 접속을 `using password: NO`로 거부했다. 기존 `.env` 인증값은 존재하고 유효하며, 값 추정/변경 없이 환경을 안전하게 로드한 재실행에서 테스트/DB 접속이 정상화되었다. 토큰 및 비밀번호는 본문/커밋에 기록하지 않았다.
- DB read-only: Flyway V20 `success=1`, baseline 16, 숫자형 업로드본 15, 후보 작업본 ID 36 / `PW01-OFFICIAL-NUMERIC-V1`, `CREATED_WITH_REVIEW`. 15개 업로드의 DB BLOB SHA mismatch 0.

### 화면 및 서비스
- 메인 `/`를 공정도 선택 → 변경사항 입력 → 미리보기/TEST_ONLY Excel 생성·다운로드의 3단계 업무 화면으로 정리. 관리자 인증·목록 새로고침·작업본 관리는 접힌 관리 영역, 요청 상세 JSON은 접힌 개발·검증 영역으로 이동. 기존 API, ADD 기능, 관리자 token 흐름은 유지.
- 최신 `./mvnw package -DskipTests` BUILD SUCCESS 후 PM2 `process-map-automation` 재시작, online. `/` HTTP 200, `/api/health` HTTP 200. browser page load/title 확인, console message 0. 임시 URL `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/`; 세션형 샌드박스 URL이며 운영 주소가 아니다. 브라우저 자동화는 실제 버튼 클릭을 하지 않아 button-click E2E는 미완료, 아래 ADD는 실제 HTTP API로 검증했다.

### ADD 실제 API A/B 및 Excel 확인
- 안전 후보 ID 36 / `PW-01` / `PW-01-05` / 검증 위치 C-PW15009와 C-PW15010 사이에서 서로 다른 실제 HTTP 요청 50, 51을 preview→generate→download 실행. 둘 다 `TEST_ONLY_READY` / `GENERATED`, output·download SHA가 일치하고 `X-Official-Baseline-Modified=false`.
  - A request 50: `SYNTH-PW01-LIVE-1793090001-A`, Live API Alpha Filter, `LIVE-ITEM-1793090001-A`, 417 m3/h, 2 units. SHA-256 `30c3a0b705ec904318d18ee7f6b0fd7e9b4e5c460903d8cb426033d7f443d0df`; 501,290 bytes; [다운로드](https://www.genspark.ai/api/files/s/NZw4ecBR).
  - B request 51: `SYNTH-PW01-LIVE-1793090001-B`, Live API Beta Clarifier, `LIVE-ITEM-1793090001-B`, 835 kg/h, 3 units. SHA-256 `81e5c778bc89faaac0f07f500e926b6256aef2fbe9bfc5407066f8538ddcaa47`; 501,295 bytes; [다운로드](https://www.genspark.ai/api/files/s/EMC2FLqX).
- DB 저장본을 API로 다시 다운로드한 검증 사본 `validation-results/2026-10-27-pw0105-live-api-1793090001/PW-01-05-request-50-server-download-final.xlsx` 및 `PW-01-05-request-51-server-download-final.xlsx`를 사용했다. share link를 다시 받아 SHA가 원본과 같은지 재검증했다. 두 파일 모두 ZIP CRC PASS, Apache POI 재열기 PASS, 시트 10개. 입력과 도면 결과는 ID `AI198`, 명칭 `AI201`, Item No. `AI202`, 용량 `AI203`, 수량 `AI204`에서 양쪽 사례 모두 일치.
- 작업본 DrawingML 103개 원본 객체 모두 ID/이름/유형/텍스트 유지; 삽입 위치에 따른 기존 anchor 11개는 +14행 이동. 신규 TEST_ONLY connector/status 2개 추가, 누락/중복 ID 0. 검토용 매핑 workbook의 기존 산출물은 덮어쓰지 않고 새 strict workbook을 생성했다.

### MODIFY 최종 차단 및 매핑 검토 Excel
- live GET `/api/admin/workspace/modify-targets` 최종 결과: 19건, box 위치 후보 12, identity verified 10, 실제 MODIFY 적격 0. 미해결 review note와 master/diagram mismatch를 `eligible` 판정의 blocker에 포함하도록 서비스 게이트를 강화했고, UI에서 적격 대상만 선택할 수 있게 했다. MODIFY preview 통합 테스트는 `GENERATION_BLOCKED`, `automaticEditAllowed=false`를 확인. 실제 설비 MODIFY/generate는 수행하지 않았다.
- C-PW15011~15016 집합 표기/타 설비 ID 혼재는 `AGGREGATED_MULTI_MASTER_BOX` 또는 위치 미확정으로 남겼다. I-PW15001 용량·수량은 master/diagram 불일치 및 review note를 유지했다. 임의 정규화나 설비 해석을 하지 않았다.
- 최종 검토 Excel: `validation-results/2026-10-27-pw0105-live-api-1793090001/PW-01-05-mapping-review-live-strict.xlsx`; 4개 시트, 설비19건 23행, 필드판정 99행, DrawingML 증거 45행, 담당자 입력 99행. OpenPyXL 재열기 PASS, SHA-256 `10e345e89176f3a2ea55a2b64e1ec1c46891867140582417ea43fe5154e40839`, `X-Official-Baseline-Modified=false`; [다운로드](https://www.genspark.ai/api/files/s/L68o27wz).

### 테스트·무결성·Git
- `NumericWorkingCopyWorkflowIntegrationTest`: 8 tests, 7 pass, 0 fail/error, 1 skip(실제 MODIFY의 필드 수정은 미승인 상태로 비활성화). 매핑 검토 테스트는 ASCII `/tmp/pw0105-mapping-review-*` 임시 경로 사용, InvalidPathException 재발 없음.
- ADD 엔진 + Step 6-5 controller/delivery/storage/enrichment + MaterialBalanceExcelExport + Step7ConnectionEvidence targeted 묶음: 19 tests, 18 pass, 0 fail/error, 1 conditional skip. Step 7 실제 XLSM 경로를 지정해 재실행: 1/1 PASS, skip 0. 위 focused 실행 합계 28 tests, 26 pass, 0 fail/error, 2 skips(조건부 Step7 테스트는 별도 지정 실행에서 통과). 전체 Maven suite는 실행하지 않았다.
- 공식 V1.0 local/manifest/DB metadata/BLOB SHA 16/16 PASS. numeric upload본 15개 SHA BLOB 불일치 0. candidate36 hash `0e68fb76192f56749c0ac623bc8a4481e4851a822ec8724169587b0a55964896`; V20 유지.
- 앱 코드/UI 안전 변경 commit `0621f45` (`genspark auto-backup`); README/CHECKPOINT 최종 기록은 별도 선별 커밋으로 저장한다. test output 및 Excel은 `validation-results/`에 보존하고 Git에는 포함하지 않는다. main merge, stash 조작, DB 초기화, migration rewrite, 공식 자료 수정, 운영 배포 없음.

---

## 3단계 실제 브라우저 E2E 최종 마감 — 2026-10-28

### 브라우저 버튼 흐름 및 결과물
- 실제 접속 주소: `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/`. sandbox session URL이며 운영 배포 URL이 아니다. PM2 `process-map-automation` online, `/` HTTP 200.
- Chromium에서 관리자 인증 후 PW-01(기준문서 ID 12), 안전 후보 작업본 ID 36, `PW-01-05`를 선택했다. 두 합성 사례를 각각 브라우저에서 입력하고 미리보기 → `TEST_ONLY Excel 생성` → `생성된 Excel 다운로드` 버튼까지 클릭했다. 두 사례 모두 미리보기 `TEST_ONLY_READY`, 생성 `GENERATED`, 공식 기준 수정 false, 수지 상태 `CALCULATION_UNAVAILABLE`.
  - B / request 65: `SYNTH-PW01-UX-20261027-B` / `합성 시연 여과설비 B` / `SYNTH-UX-ITEM-B` / `284㎥/일` / `2대`; browser file `validation-results/2026-10-28-step7-3-final/PW-01-05_SYNTH-PW01-UX-20261027-B_ADD_request-65_TEST_ONLY-integrated-browser-download.xlsx`; 501,316 bytes; SHA-256 `8282660bb29420f1e70fcbf44e8944ed70ab1657898adffac8738ee2f8cbc6b7`; [다운로드](https://www.genspark.ai/api/files/s/JN5cRrcP).
  - C / request 66: `SYNTH-PW01-UX-20261027-C` / `합성 시연 여과설비 C` / `SYNTH-UX-ITEM-C` / `91㎥/일` / `1대`; browser file `validation-results/2026-10-28-step7-3-final/PW-01-05_SYNTH-PW01-UX-20261027-C_ADD_request-66_TEST_ONLY-integrated-browser-download.xlsx`; 501,314 bytes; SHA-256 `25d8a45f2fdab4149befe98df9fb543bf42a0cf64665480a190a6f267a7d8172`; [다운로드](https://www.genspark.ai/api/files/s/CH8SM4Ey).
- 미리보기의 변경 전후 표에서 설비번호·명칭·Item No.·용량/단위·수량·삽입 위치를 입력과 대조했다. 실제 브라우저 다운로드 파일은 별도 OpenPyXL 및 Apache POI로 각각 다시 열어 `PW-01-05!AI198` ID, AI201 명칭, AI202 Item No., AI203 용량/단위, AI204 수량을 재확인했다. B/C 모두 정확히 일치.

### OOXML·보존·해시
- B/C 각 파일 `unzip -t` 및 ZIP CRC PASS, Apache POI 재열기 PASS, 10시트. 브라우저 다운로드 bytes SHA = API 재다운로드 SHA = DB의 output_sha256 = DB output_content의 SHA-256.
- 기준 작업본 ID 36의 `PW-01-05` DrawingML 103개를 출력과 비교. 기존 객체 모두 존속하며 ID·이름·유형·텍스트·형상·화살표·shape binding 메타데이터 보존. 기존 connector 91개와 화살표는 남고 신규 시각 connector 1개가 추가되어 92개. 신규 TEST_ONLY 상태 도형 1개 포함, 전체 DrawingML 객체 103→105, 기존 객체 삭제 0. 신규 설비상자 11행×18열의 style ID는 source template과 동일.
- 원본 xlsx의 34개 ZIP part는 제거되지 않았고, 추가된 4개 설명 sheet 외에 workbook metadata, `xl/styles.xml`, `PW-01-05` sheet/drawing 파트만 예상대로 변경됐다. 비대상 part는 byte-identical. 새 설명 시트 글꼴/서식 추가가 style table을 append-only로 확장하는 것은 허용된 결과 시트 출력이다.
- 공식 V1.0 local/manifest/DB metadata/DB BLOB SHA 16/16 PASS. 숫자형 사용자 업로드 작업본 15개 metadata/BLOB SHA 15/15 PASS. 안전 후보 ID 36 metadata/BLOB SHA 일치. request 50·51은 status/output SHA/BLOB을 재확인해 변동 없이 보존.

### MODIFY 및 검증 구분
- 실제 MODIFY 후보 GET은 19개, 적격 0개. API-only 기존 blocked request 67에 대해 generate 시 HTTP 409 및 `GENERATION_BLOCKED` 확인. 브라우저 변경 유형 메뉴의 MODIFY는 비활성 상태이며 실제 MODIFY 편집/생성은 하지 않았다.
- ADD 입력·preview·generate·download와 결과 파일 저장은 브라우저에서 실제 버튼 클릭으로 검증. MODIFY 대상 목록 및 generate 409, 원본/DB BLOB hash 검사, POI/DrawingML 비교는 API/코드/로컬 파일 검증이며 브라우저 클릭 검증으로 주장하지 않는다.
- 이전 브라우저 trace 중 진단용 합성 ADD request 64가 추가 저장됐고 별도 TEST_ONLY 결과로 유지된다. 최종 산출물은 request 65·66 두 건이다. request 67은 차단된 MODIFY 검증 row이며 출력 파일은 없다. request 50·51은 변경하지 않았다.

### 회귀·저장
- `NumericWorkingCopyWorkflowIntegrationTest`: 8 total / 7 pass / 1 skip / 0 failures / 0 errors. `Pw0105EquipmentInsertionEngineTest`: 3/3 pass. skip은 승인되지 않은 필드 수정 흐름에 한정한다. `./mvnw -q -DskipTests package` PASS. 전체 Maven suite는 실행하지 않았다.
- `.env`/관리자 토큰을 문서·로그·커밋에 기록하지 않았다. 테스트 Excel은 `validation-results/2026-10-28-step7-3-final/`에 보존하고 Git에는 포함하지 않는다. 이번 문서 갱신은 README와 CHECKPOINT만 선별 커밋한다.
- 기존 ADD 동작 및 MODIFY 차단을 유지했다. 공식 원본·15개 사용자 작업본·candidate36·request50/51 보존. migration 수정, DB 초기화, stash 조작, main 병합, 운영 배포 없음.

---

## 2026-10-28 브라우저 최초 계정 설정 — 서버 관리자 승인 필수

### 정책 및 변경 범위
- 최초 operator 가입 화면은 공개 signup이 아니다. `/login.html`은 현재 `app.operator.*` env credential 또는 DB singleton account의 상태를 읽어, 이미 계정이 있으면 로그인만, 초기 설정 가능 시 계정 요청 UI만 표시한다.
- 사용자는 로그인 페이지에서 CSRF 보호된 요청을 생성한다. DB에는 UUID, 요청 상태, 15분 만료, 요청자 브라우저 session ID의 SHA-256 fingerprint가 기록된다. session ID를 회전하고 요청 완료 시간에 만료하도록 같은 브라우저 세션에 고정한다. DB unique `active_slot=1`로 전체 시스템의 활성 요청은 한 건만 허용한다. 요청 rate는 browser session당 15분 5건으로 제한한다.
- 운영자 승인 경로는 기존 `ApiAccessGuard`와 `X-Admin-Token`을 요구하는 `/api/admin/operator-setup/requests`, `.../{requestId}/approve`, `.../{requestId}/reject`. 관리자 작업은 server helper `scripts/approve-operator-setup.py <UUID>`가 `.env`에서 token을 메모리로만 읽어 loopback API를 호출한다. 토큰은 브라우저/응답/로그/문서/소스에 쓰지 않는다. 아직 실제 사용자 request가 없으므로 승인 실행은 하지 않았다.
- 승인된 15분짜리 요청은 동일 browser fingerprint에서만 비밀번호 14~128자 설정을 허용. 계정 DB에는 PBKDF2-HMAC-SHA256 310,000 iteration hash만 저장하고 단일 account row insert와 request CONSUMED 처리를 같은 transaction으로 묶는다. 완료 후 session invalidate; 이후 같은 사용자명/해시 인증을 통해 기존 일반 login으로 사용한다. 미승인·타 브라우저·만료·재사용·이미 계정 존재 시 차단.
- 기존 로그인 세션·rate-limit·CSRF·쿠키 보안 속성, 기존 관리자 API token guard, 기존 PW-01 ADD/MODIFY 경계는 보존했다. 기존 migrations는 수정하지 않고 V21 singleton tables, V22 unique active request slot만 신규 추가. 공식 XLSX/DB baseline 및 request 50/51을 수정하지 않았다.

### 검증
- `OperatorSetupIntegrationTest`: 2 tests PASS. 승인 없는 complete 차단, 다른 브라우저/session fingerprint 차단, 전체 활성 요청 1건 제한, 관리자 API unauthenticated 401, 승인·중복승인 409, expiry 410, one-use consumed, 단일 DB 계정 해시 저장, 새 일반 login 후 업무 API 조회까지 검사. Tests transaction rollback.
- `OperatorWorkspaceSessionIntegrationTest`: PASS. 세션 로그인, 15개 공정도, PW-01 및 작업본 ID 36/`PW01-OFFICIAL-NUMERIC-V1`, 기존 ADD TEST_ONLY Excel 생성/download hash 일치를 재검증. `Pw0105EquipmentInsertionEngineTest` 2/2, `DiagramEditPlanIntegratedControllerTest` 및 `DiagramEditPlanControllerIntegratedWorkbookTest`도 PASS. `./mvnw -q -DskipTests package` PASS. 전체 Maven suite는 실행하지 않음.
- Flyway V21/V22는 local dev DB에 정상 적용; schema version 22. Test-only request/account rows rollback. 실제 최초 계정은 아직 없음. 따라서 test-only flow에서만 로그인 후 문서·ID36을 확인했으며 사용자 계정으로의 live login은 사용자 등록 후에 가능하다.
- 사용자 절차: ① HTTPS 로그인 링크 열기 → ② “관리자 승인 요청” 클릭 → ③ 화면의 UUID만 이 대화에 전달(비밀번호/토큰 금지) → ④ 서버 관리자 승인 후 같은 탭에서 승인 표시 대기 → ⑤ 브라우저에 직접 아이디/비밀번호 설정 → ⑥ 로그인 화면에서 로그인. 요청은 15분 후 만료되며 새로 로그인/브라우저 변경 시 완료 불가.
- 이 변경은 현 브랜치 `step7-3-wip-20261023`에서 검증 중이다. README 최신 절차 포함. 선별 커밋 전 상태 확인 예정; main 병합·운영 배포·stash 조작·DB 초기화는 수행하지 않았다.

---

## 2026-10-28 최종 PW-01-05 ADD 검증 마감

### 실행·브라우저
- 시작 시 branch `step7-3-wip-20261023`, HEAD `ddc344bb7110fb55d5bf01a69dcf93af914485f4`, working tree clean. 최신 main/test 빌드 성공 후 로컬 PM2 및 `/api/health` 200 확인. 앱은 마지막에 기본 `.env` 설정으로 재시작한다.
- Chromium 실제 UI에서 로그인, 공정도 PW-01(기준 문서 12), 안전 후보 작업본 ID 36, 시트 PW-01-05, 지정 위치 `C-PW15009`와 `C-PW15010` 사이를 선택하고 짧은 값과 긴 값 각각 미리보기→TEST_ONLY Excel 생성→다운로드를 완료했다. 두 workflow 결과 request 94/95, API 단계 응답 200. UI change type 선택은 ADD만 있었다.
- 브라우저 input pattern의 `\-` escaping을 수정한 후 유효 ID 통과, 공백 포함 ID 거부, console/page error 0을 확인했다. 테스트 임시 로그인 값은 PM2 런타임에만 사용했고 DB operator 계정, password hash, `.env`는 수정하지 않았다.
- 파일: `validation-results/2026-10-28-step7-3-final/PW-01-05_short_browser-final_20261028.xlsx` (500,888 bytes, SHA-256 `60f37cb84426743984796fc7d5e98fd2746f1455a39a7fd18b6a5b88bbc0f48c`) 및 `.../PW-01-05_long_browser-final_20261028.xlsx` (501,848 bytes, SHA-256 `4ab7fcc112c0e0c120c04ee366ec8478dad4f4d18a3b8e1562c7af59ff326fca`). 둘 다 별도 다운로드한 브라우저 산출물.

### 재열기·도면 렌더링
- Apache POI로 두 다운로드를 재열기: 활성 시트 `PW-01-05`, scroll top row 197 / left column 34, 원문 셀값 및 `shrinkToFit=true`, 병합된 도면 상자 확인. 신규 연결선 1개만 추가(전체 drawing object 103→104, connector 91→92); `TEST_ONLY / REVIEW_REQUIRED` 도형 0. 기존 도형/화살표 수·ID 보존.
- LibreOffice 렌더링 사본에서 PW-01-05를 PDF 42페이지로 렌더링하고 diagram page 27을 PNG로 확인했다. 짧은 설비값은 상자 안에서 읽히고 상하 연결 화살표와 기존 C-PW15009/C-PW15010 상자가 유지된다. 신규 callout은 없다. 기존 도면의 노란 표시(일반폐수 라벨)는 남아 있는 원본 요소로 새 상태 callout과 구별했다.
- 긴 입력의 ID/name/Item No./capacity는 workbook 셀에 정확히 보존된다. LibreOffice 렌더링에서 ID와 Item No.는 PDF 텍스트로 추출됐지만, 117자 설비명은 너무 작아 PDF 텍스트 추출에서 전체 문자열이 확인되지 않았고 정상 배율 가독성은 확인 불가다. 확대 화면에서는 텍스트가 신규 상자 안에서 축소되어 있고 인접 상자 경계를 넘는 침범은 보이지 않았다. 긴 값의 상자 내 시각 가독성은 미해결 표시 한계로 기록한다. LibreOffice 렌더링은 Excel 네이티브 화면의 완전한 대체가 아니다.

### 회귀·무결성·저장
- 회귀: `Pw0105EquipmentInsertionEngineTest` 4/4, `DiagramEditPlanIntegrationTest` 1/1, `OperatorWorkspaceSessionIntegrationTest` 1/1, `NumericWorkingCopyWorkflowIntegrationTest` 8건 중 7 pass / 1 승인 전 MODIFY 시나리오 skip / 실패·오류 0. 테스트 보정은 `getTopRow()` Short/Integer 비교와 POI append-only style의 alignment/readability 속성 확인에 한정.
- 해시: 공식 원본 local/manifest/DB metadata/BLOB 16/16 PASS. 숫자형 작업본 ID 1–15 metadata/BLOB 15/15 PASS. 안전 후보 ID 36 metadata/BLOB SHA 일치. 공식 baseline 16개 및 V1.0 release 잠금 유지. 로컬 검증 스크립트가 baseline hash CSV를 갱신했는지 working tree status에서 확인한다.
- 최종 운영 상태: 사용자가 요청한 로컬 PM2 서비스만 유지하고 `/api/health`/페이지 200 확인. Excel 산출물은 validation-results에 보관하고 Git에 추가하지 않는다. 이번 변경 commit에는 regression test 설명, README, CHECKPOINT 및 HTML pattern 수정만 포함한다. 원본 Excel·DB 초기화·migration 수정·main 병합·운영 배포는 하지 않았다.

---

## 2026-10-29 설비 유형 직접 입력 기능

### 변경 사항
- `src/main/resources/static/index.html`: 설비 유형 필드를 직접 입력하는 required text input으로 바꾸고 고정값을 제거했다. 예시는 placeholder만 제공. 초기화도 공란이며 입력문자열을 프런트엔드에서 자동 trim/변환하지 않고 preview 요청에 전달한다. preview 표에 설비 유형 표시 항목을 보인다.
- `ProcessWorkspaceService`: reference case의 설비유형 기본값을 공란으로 변경, afterSummary에 사용자가 입력한 유형을 포함한다. 유형 미입력은 기존 검증대로 HTTP 400/preview 차단이며 구형 `여과시설`로 대체하지 않는다.
- Excel에는 지정된 기존 상자 유형 표시 셀 `PW-01-05!AI200`에 값이 들어간다. 새 셀/도형은 만들지 않는다. 신규 셀의 기존 템플릿 스타일 ID가 대응 원본 `AI185`와 같고, 맑은 고딕 7pt와 정렬도 동일함을 POI로 확인했다.

### 검증 및 산출물
- `OperatorWorkspaceSessionIntegrationTest`: 2/2 PASS. 로그인 세션에서 각기 `저장시설`, `혼합시설`을 넣어 preview → 생성 → 다운로드를 각각 완료했다. preview 요약에 입력 값이 그대로 전달되고, 다운로드 결과를 Apache POI로 다시 열어 `AI200`에서 두 원문을 각각 확인했다. 다운로드 SHA-256: 저장시설 `419f509f6d5ed5cc81b6c41fcc1c5b4596c4e000e0757b59b8732f0bb49ea2786`, 혼합시설 `ea43f0f6d5ed5cc81b6c41fcc1c5b4596c4e000e0757b59b8732f0bb49ea2786`.
- `Pw0105EquipmentInsertionEngineTest`: 6/6 PASS. 공란 유형 입력은 엔진에서 `여과시설`을 삽입하지 않으며, 새 유형 셀은 기존 스타일/서식을 유지한다.
- 로그인 보호, 기존 세션에 묶인 ADD 생성, 타 세션 차단, MODIFY 403 차단 검증 유지. 공식 baseline 및 숫자형 작업본 원본은 수정하지 않았다. 테스트 트랜잭션은 rollback.
- 테스트 파일: `target/equipment-type-validation/storage-facility.xlsx`, `target/equipment-type-validation/mixing-facility.xlsx` (Git 제외). 전체 Maven suite는 실행하지 않았다.
- README 최신화. `./mvnw -q -DskipTests package` 빌드 및 PM2 로컬 서비스 재시작 후 `/api/health`와 새 UI placeholder 제공을 확인했다. 운영 서버 배포와 `main` 병합은 하지 않았다.

---

# 2026-10-29 물질수지 연결망 확장

## CHECKPOINT A — 원본·DB·서비스·연결자료 기준 확인
- 시작 Git: branch `step7-3-wip-20261023`, HEAD `bb0382f` (`Allow custom PW-01 equipment type labels`), tracked worktree clean. `main`은 바꾸지 않았다.
- 실행환경: Java 17.0.20.1, Spring Boot 3.5.16, Maven Wrapper 3.9.9, MariaDB 11.8.6, Flyway V22. 재시작 없이 시작 시 `/api/health` `ok`; 기존 서비스/UI/Login 코드는 이 CHECKPOINT에서 변경하지 않았다.
- 공식 `data/reference/SHA256SUMS`: 공정도 15개 + `전체 목록.xlsx` 1개, 16/16 PASS. 실제 DB V1.0 locked baseline `baseline_document` metadata hash = BLOB SHA 16/16. Numeric upload working copy 15/15 SHA 일치. PW-01 안전 후보 ID 36은 baseline document 12, `PW01-OFFICIAL-NUMERIC-V1`, `CREATED_WITH_REVIEW`, metadata/BLOB SHA 일치. Official baseline, uploaded source, ID36 BLOB를 변경하지 않았다.
- 기존 비즈니스 데이터: `process_relation` 1개 `REVIEW_REQUIRED` / `CONFIRMED` 0; `equipment_flow` 0. Review snapshot 후보 33개 / case 0 / history 0 / typed route 0 / provenance 0. Persisted material-balance simulation/transfer 0. 기존 change request/산출 39개는 테스트 뒤 읽기 조회에서 변함없이 남았다. 기존 V1–V22 migrations 파일을 변경·재작성하지 않았고 DB schema migration을 추가/적용하지 않았다.
- `0.3 250808 물질수지 DB.xlsm`은 `/home/user/uploaded_files/`에만 있는 별도 user upload이며 공식 V1.0 baseline에 등록되지 않았다. SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`; read-only `입력시트` 3,082 rows. 기존 독립 비교는 PW-01 504 rows / 501 unique pair, row-level exact master endpoint match 2,777, 한 endpoint match 305, 양 endpoint mismatch 0(외부 경계/alias 해석은 여전히 미확정), 양수 질량 1,975 / 0 1,106 / 공란 1 / negative 0, 종류 공란 2,830, 제외 여부 공란 3,082. 새 read-only 직접 확인에서는 3,031 unique pairs, 51 duplicate rows (44 duplicate pair groups; 기존 comparison report), unique endpoints 1,178. 이들은 **연결 후보**이지 검증 관계가 아니다. 파일에는 unit/measurement period/branch-cycle evidence 필드가 없다.
- 기존 실제 도면 drawing database: connector endpoint binding 0으로 기존 보고서 유지. PW-01 spatial/nearby hints도 logical source-target proof로 올리지 않는다. 현 review tables에서 EVIDENCE_VERIFIED/route=0이므로 실제 verified graph edge 0, actual material balance 0; 실제 변경 전후 수지 계산 불가.
- CHECKPOINT A regression: `OperatorWorkspaceSessionIntegrationTest` 2/2 + `Pw0105EquipmentInsertionEngineTest` 6/6 PASS (기존 로그인/ADD/Excel; recent 설비 유형 변경 포함). `DirectedProcessNetworkSolverTest` 8/8 + `MaterialBalanceCalculatorTest` 6/6 PASS. Source-driven tests는 업로드 XLSM을 ASCII symlink를 통해 지정해 수행: `Step7ReviewNetworkIntegrationTest` 1/1, `Step7WholeNetworkReviewWorkflowIntegrationTest` 7/7, `Step7MaterialBalanceComparisonServiceTest` 3/3 PASS. Solver/actual review 선별 묶음에서 전부 통과; 전체 Maven suite 미실행. Source-driven integration transaction rollback 후 CASE/route/evidence/simulation rows 0 유지.
- Generated read-only/TEST_ONLY validation workbooks: `validation-results/2026-10-29-network-build/material-balance-comparison.xlsx`, `whole-network-review.xlsx`, `Step7_4B_network_validation_TEST_ONLY.xlsx`; 각각 POI/통합 테스트 출력. 실제 DB 후보 모두 review-only이며 synthetic solver workbook rows are not plant facts.
- 회사 「모듈 생성 프롬프트_v4」 파일과 회사 core/app platform은 repo/uploaded input에서 찾지 못했다. 이 checkpoint에서는 core dependency/fictional classes를 만들지 않았다. 향후 규칙은 controller/DTO/service/repository 분리, 명시적 append-only proposal behavior, 기존 Operator session+CSRF reuse, no platform assumptions로 적용한다.

## CHECKPOINT B — 기간 기준 보존 및 합성 계산망 회귀 (2026-10-29)
- `DirectedProcessNetwork.Edge`와 `ProcessTerm`에 측정 기간을 명시적으로 보존하고, solver의 Stream key를 물질 구분·종류·단위·기간으로 분리했다. 기간이 없는 legacy constructor 값은 빈 값으로 남기며 solver는 `MEASUREMENT_PERIOD_REQUIRED`로 계산 불가 처리한다. 기간 필드는 연결 adapter에서 검토된 route period를 전달한다.
- Scenario/결과 workbook의 연결·설비·공정·외부 수지 및 입력 template에 기간을 포함했다. What-if 외부 잔차 대조 키에도 기간을 추가해 서로 다른 기간의 잔차를 비교·합산하지 않는다. 공식 원본, 기존 migration, MariaDB schema는 건드리지 않았다.
- 추가 unit test로 같은 물질·단위지만 2026-10/2026-11 기간이 다른 흐름을 별도 balance 2개로 계산하고, period 누락 edge를 `CALCULATION_UNAVAILABLE`로 거부함을 확인. Existing 합성 분기/합류·재순환·20→10/50 What-if·음수 제약과 exporter/template test 포함 `DirectedProcessNetworkSolverTest` 9건 및 `MaterialBalanceCalculatorTest` 6건 통과.
- Source-driven DB suite `Step7ReviewNetworkIntegrationTest` 1, `Step7WholeNetworkReviewWorkflowIntegrationTest` 7, `Step7MaterialBalanceComparisonServiceTest` 3건 통과. 실제 PW-01-05 후보 33, EVIDENCE_VERIFIED 0, 실제 eligible 연결 0, 계산 불가 상태를 유지하고, synthetic 결과는 분리했다. 테스트 중 Flyway V22가 up to date로 확인됐으며 migration 적용/DB 초기화 없음.
- 기존 로그인·ADD 회귀를 다시 실행한 명령은 `OperatorWorkspaceSessionIntegrationTest` 시작 중 JVM fork exit 137로 종료되어 이 실행의 ADD 재검증은 완료되지 않았다(Assertion failure로 판정하지 않음). 이전 체크포인트의 로그인/ADD 회귀 통과 이력은 보존. 전체 Maven suite 미실행.
- `./mvnw -q -DskipTests compile` 통과. CHECKPOINT B 범위의 기간 모델·어댑터·결과 출력만 진행했다. 일반 `MaterialBalanceRequest`/`material_balance_transfer`의 영속 기간 모델은 V23 및 안전한 적용 절차가 없어 변경하지 않았고, 해당 legacy API 계산 경로에는 기간 보존이 아직 없다.
- CHECKPOINT C–E 미완료: What-if는 기존 합성 directed-network 계산기 내 시험 범위에 한정되고 사용자 업무 API/승인 플로우 없음. append-only ADD/DELETE/MODIFY/PIPE_CHANGE/FLOW_CHANGE proposal 모델·API, 추가 공정의 실제 매핑, 증빙된 실제 Excel 변경은 구현하지 않았다. 기존 지정 PW-01-05 ADD만 유지한다.
- CHECKPOINT B 수정은 WIP 브랜치 `step7-3-wip-20261023`에만 해당한다. main 병합, 운영 배포, DB migration, 공식 Excel 수정 없음. 테스트용 XLSX는 `validation-results/2026-10-29-network-build/`에 두었으며 TEST_ONLY 산출물이지 공식 연결망/현장 결과가 아니다.

## CHECKPOINT C — 응답 오류 후 What-if·삽입 후보 재개 (2026-10-29)

- 시작 상태: branch `step7-3-wip-20261023`, HEAD `a5bd4ebf0a57d92d7141d0c13a052882189f1e4a`; 세 tracked 변경물은 reset/stash/덮어쓰기 없이 보존했고 신규 untracked 파일은 없었다. 변경 파일은 `ProcessDiagramStructureAnalyzer.java`, `static/index.html`, `Pw0105InsertionCandidateCompatibilityIntegrationTest.java`였다.
- DB read-only 조회에서 현재 Flyway 성공 버전은 V23. `.env`는 기존 `scripts/run-local.sh`/`test-local.sh` 경로로 source됨을 확인했고 키 이름만 확인했다. 비밀값을 출력하지 않았다. 이번 두 테스트는 `-Dspring.flyway.enabled=false`로 실행했으며 migration 적용·DB 초기화는 없었다.
- 기존 What-if API/세션·CSRF 보호/UI/Excel export를 중복 구현하지 않았다. `OperatorWhatIfSessionIntegrationTest` PASS: 인증/CSRF 거부 및 허용, TEST_ONLY 합성 20→10/50 계산, 기간 누락 거부, Excel 4시트/공식 baseline 미수정 응답을 확인했다. UI readiness는 실제 계산이 `CALCULATION_UNAVAILABLE`임을 표시한다. 실제 사업장 물질수지 계산은 계속 불가다.
- 기존 구조 보고서(`official-process-diagram-inventory_TEST_ONLY.json`)의 15개 공식 파일·62개 시트 전부를 검토했다. 병합셀 겹침 및 DrawingML 교차가 없는 공간 후보로 P-02 1개, PW-03 2개를 찾았으나 모두 `REVIEW_REQUIRED`; 물리 연결이나 마스터 필드 매핑을 뜻하지 않는다.
- 비공식 PW-01 작업본 ID 36에서 기존 고정 위치 밖 `PW-01-05 / C-PW15001→C-PW15011` TEST_ONLY Excel 생성 및 POI 재열기, 병합 수, 인쇄 영역, 신규 설비 ID, DrawingML connector +1/shape 보존을 확인했다. `validation-results/2026-10-29-process-diagram-inventory/PW-01-05_TEST_ONLY_ADD_C-PW15001_to_C-PW15011.xlsx`, SHA-256 `84806a0664693881d5972cea3e71e40c97260f57e0398eddba3174afd786f9ee`; 입력 작업본 SHA는 변하지 않았다.
- 범공정 후보 시도: 비공식 PW-03 작업본 ID 15, SHA `8040df3287caaee9dcdcb523baaf1fc0338922352901c5df67e6c586d0cd8cae`; `PW-03-01!AI27:AZ29`는 병합 겹침 0 / DrawingML 교차 0이었지만 해당 작업본에서 기존 connector가 endpoint와 일치하지 않아 보수적으로 생성 차단. 결과 `general-insertion-probe_TEST_ONLY.json`; PW-03 Excel은 생성되지 않았다.
- 범용 ADD 엔진은 미완료다. 현재 편집 엔진에는 PW-01-05 레이아웃/스타일 가정이 있으므로 타 공정·시트의 boxed equipment/DrawingML을 일반 생성할 수 있다고 주장하지 않는다. ADD 외 DELETE/MODIFY/PIPE_CHANGE/FLOW_CHANGE 제안 기반도 미완료이며 MODIFY 차단을 유지한다. 전체 Maven suite·실제 브라우저 버튼 E2E·운영 배포·main 병합은 하지 않았다. 공식 도면이나 DB baseline을 쓰지 않았다.

---

## CHECKPOINT D — 검증 ledger 확정 및 재개 (2026-10-31)

### 상태 보존
- 브랜치 `step7-3-wip-20261023`, 기준 HEAD `74113fa`에서 작업을 이어갔다. 강제 reset, stash 변경, main 병합은 하지 않았다.
- sidecar `validation-results/process-diagram-inventory/locked-v1.0-process-inventory-validations.json`을 다시 읽었다. locked V1.0 source fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb` 아래 P-02-01(TOP), PW-02-01 두 건이 존재한다. artifact 파일 SHA를 각각 재계산하여 ledger 기록과 일치함을 확인했다.
  - P-02: source document 2, working document 3, official source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, working SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`, location `P-02|P-02-01_TOP_|I-PP21039->I-PP21040`, TEST_ONLY artifact SHA `582de0d333538cc7dbaa8939ce103fc731dfe380cdfb51a478c19fb41fbbb562`.
  - PW-02: source document 13, working document 8, official source SHA `2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a`, working SHA `4250b004c723b9d366d1231ce98d75761a864564402250fe5a5873665b00b8d1`, location `PW-02|PW-02-01|I-PW21007->I-PW21008`, TEST_ONLY artifact SHA `d0c49f943993dd47cca210c3b5d45622a18815a5036d6b2fd83708c37694bd68`.
- 두 row 모두 구조 편집 `VERIFIED_BOX_ONLY_TEST_ONLY`, 실제 연결 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`다. 이는 셀 기반 BOX_ONLY 편집의 구조 검증이며 배관·화살표·실제 유량/관계를 입증하지 않는다. 인벤토리 원본 11MB JSON은 매 등록마다 재직렬화하지 않도록 sidecar로 분리했다.

### 코드·테스트
- validation sidecar store의 저장/재조회 및 다른 source fingerprint 차단을 테스트했다. API `POST /api/admin/workspace/process-diagram-inventory/validations`에 대해 token 없음/불일치 401 테스트를 추가했다. endpoint는 서버에서 구조/연결 상태를 고정하며 service가 locked source, working copy, 현재 VERIFIED location, artifact 경로/SHA를 다시 검사한다.
- `.env`를 source하지 않은 최초 Maven 실행은 DB 사용자 비밀번호가 전달되지 않아 MariaDB authentication failure로 실패했다. 이건 테스트 assertion 실패가 아니며, 비밀값을 출력하지 않고 기존 local env 로더와 동일하게 `.env`를 source한 뒤 재실행했다.
- 통과: `ProcessDiagramInventoryCheckpointStoreTest` + P-02/PW-02 두 TEST_ONLY API workflow test (2 methods), `ProcessDiagramInventoryCheckpointStoreTest` + 신규 token guard test, `./mvnw -q -DskipTests package`. 전부 `-Dspring.flyway.enabled=false`; migration 적용·DB 초기화·운영 데이터 쓰기는 하지 않았다. 첫 성공 suite는 3개 테스트 메서드, 다음 suite는 2개 메서드였다.
- 로컬 서비스는 PM2로 재기동했다. `/api/health` 200, `/login.html` 200과 로그인 문서 제목 확인. 브라우저 preview URL에서 Playwright가 `body`를 로드했고 page title을 확인했으며 console message 0건이었다. 과거 `Page.goto: Target page, context or browser has been closed` 문제는 이 단순 페이지 로드에서 재발하지 않았다.
- 로그인 후 실제 UI에서 ADD 버튼 흐름을 수행하는 브라우저 E2E는 미완료다. API 통합 테스트 및 과거 PDF/LibreOffice 비교를 브라우저 E2E로 주장하지 않는다. 로그인 자격 증명을 사용해 workflow를 수행하지 않았고, token/password를 브라우저에 주입하지 않았다.
- README에 이번 검증 결과와 현재 구현/미구현 범위를 추가했다. 현재 수정 파일은 sidecar store/service, `DiagramBoxChangeEngine`, `DiagramBoxChangeEngineTest`, OperatorWhatIfSessionIntegrationTest, ProcessDiagramInventoryCheckpointStoreTest, README.md, CHECKPOINT.md다. 검증 artifact와 `.env`는 stage/commit 대상이 아니다.
- 신규 `DiagramBoxChangeEngine`은 합성 fixture 전용이다. MODIFY는 고유 관리번호로 특정된 셀 박스 안에서 명시한 A1 필드만, 이전 값 일치 시 바꾼다. DELETE는 단일 박스의 셀 값/서식과 내부 병합만 제거하고 행 이동을 하지 않는다. drawing 파트/대상 worksheet rels 존재, 수식 포함, 복수 ID, 박스 외 셀, stale 값, 비-anchor merge는 거부한다. 대상 worksheet 외 package part는 byte-equal인지 비교하고 POI 재열기로 결과를 확인한다.
- `DiagramBoxChangeEngineTest`: synthetic fixture 4/4 PASS (MODIFY 값/외부 셀 보존, stale/out-of-box 차단, merge anchor 및 duplicate ID 차단, DELETE 내용·스타일·merge 제거/행 이동 없음). 실제 도면/운영 API에 연결하지 않았다.

### 미완료 및 다음 작업
- common append-only proposal API/UI, PIPE_CHANGE/FLOW_CHANGE 및 synthetic What-if 연계는 아직 구현하지 않았다. 현재 synthetic MODIFY/DELETE engine만으로 이를 완료했다고 주장하지 않는다.
- PW-03: 12 후보 중 10 `UNSUPPORTED`, 2 `REVIEW_REQUIRED`(`QUANTITY_FIELD_NOT_UNIQUE`); 신규 ADD 반영 안 함. 기타 공정 후보도 명확한 template/connection evidence가 없는 이상 승격하지 않는다.
- 다음 개발에서는 기존 Spring MVC/Controller/DTO/Service/Repository, session+CSRF 모델을 따른다. 실제 DB schema가 필요한 기능은 기존 migration을 수정하지 않고 신규 migration 필요성·버전·배포 안전성을 별도 검토하되 이번에 migration 추가/적용은 하지 않는다. PIPE/FLOW proposal도 synthetic model에서 먼저 검증하고 실제 경로는 증거 전까지 `CALCULATION_UNAVAILABLE`다.
- 운영 배포, main 병합, 공식 workbook/baseline 수정, DB 초기화는 금지 상태 그대로 유지한다.

---

## CHECKPOINT E — P-02/PW-02 실제 작업본 MODIFY TEST_ONLY (2026-10-31)

### 시작 및 무결성
- 시작 branch `step7-3-wip-20261023`, HEAD `2353353`, worktree clean. `python3 scripts/verify-baseline-hashes.py` PASS: 공식 baseline 16/16 local/manifest/DB metadata/BLOB 일치. Read-only DB 조회에서 Flyway schema version 23, working copies ID 1–15 및 PW-01 candidate 36의 `SHA2(content_bytes,256)=working_sha256` true, `equipment_flow=0`, `CONFIRMED process_relation=0` 확인. PM2 local app online, `/api/health` 200.
- inventory validation sidecar 2건(P-02/PW-02)과 산출물 SHA 일치를 재확인. 기존 inventory JSON·official workbook·migration V1–V23은 수정하지 않았다.

### 실제 작업본 MODIFY
- `DiagramBoxChangeEngine`의 exact master field mapping과 `DiagramModifyService`/`OperatorDiagramModifyController` TEST_ONLY workflow를 추가했다. locked source와 `MAPPED_PERMIT_ID`, working-copy SHA, 유일 styled box, box 내 master 문자열의 유일 exact cell match를 확인한다. 미확인 field는 `REVIEW_REQUIRED`; 관리번호는 global remap 필요성 때문에 자동 수정하지 않는다.
- P-02 ID 3, sheet `P-02-01(TOP)`, equipment `I-PP21039`: `I585` `WWTK-131-008` → 합성 변경값 `TEST-P02-ITEM-01`. 분류/permit-name·quantity 등 정확한 단일 셀 매핑이 아닌 필드는 수정하지 않았다.
- PW-02 ID 8, sheet `PW-02-01`, equipment `I-PW21007`: `J149` `PI-102` → 합성 변경값 `TEST-PW02-ITEM-01`. 이름/permit name/capacity-unit은 정확한 필드 매칭; quantity 및 관리번호는 REVIEW_REQUIRED.
- 결과 다운로드: [P-02 TEST_ONLY MODIFY Excel](https://www.genspark.ai/api/files/s/I38p7CN3), SHA-256 `3db3145937c83df85c25fedfe2fe874cc9efa08917690edd23158c984f47a6ed`; [PW-02 TEST_ONLY MODIFY Excel](https://www.genspark.ai/api/files/s/s2UKcik7), SHA-256 `159bd67712fe48a778bd6dcf0fb79b786b25a05e1bb811ea56052db92ce6a082`. Files in `target/diagram-modify-validation/` are build outputs and not committed.
- POI, ZIP CRC, target-only cell change, non-target cell values/styles, merge ranges, print areas, DrawingML object inventory/raw drawing bytes, source working-copy SHA preserved. LibreOffice source/output PDFs have matching page counts (P-02 71, PW-02 16) and page image diff bbox limited to changed label area. Excel native renderer not used.

### DELETE·제안·남은 범위
- Added synthetic DELETE impact assessment and authenticated `GET /api/workspace/diagram-editor/modify/delete-impact`; DrawingML/nearby labels remain REVIEW_REQUIRED, and actual diagram DELETE is blocked if DrawingML is present. New impact endpoint itself was not covered by a dedicated integration test in this slice. No real workbook DELETE was performed.
- Drafted common change-proposal controller/service/repository plus V24 append-only schema and triggers. This draft was not integration-tested; migration was not applied; Flyway remains 23. These files are intentionally left uncommitted until a migration-enabled transactional integration test verifies CRUD reads, per-user scoping, append-only triggers, and rollback. Proposal API, UI, and PIPE/FLOW What-if linkage are not complete.
- UI was not connected to MODIFY/DELETE; browser button E2E not run. The prior Playwright result only covered loading the login page, not this workflow.

### 검증·저장 상태
- `DiagramBoxChangeEngineTest` (6 tests) and `OperatorDiagramModifyIntegrationTest` (2 tests) PASS with `-Dspring.flyway.enabled=false`; `./mvnw -q -Dspring.flyway.enabled=false -DskipTests package` PASS. Full Maven suite not run.
- No DB migration applied, DB rows changed, source workbook modified, deployment or main merge occurred. The V24 proposal prototype remains uncommitted.

---

## CHECKPOINT F — 변경 제안 Before/After What-if 검증 (2026-11-01)

### Git·기준 보존
- 시작 브랜치 `step7-3-wip-20261023`, HEAD `e3c9745`; `git status --short`는 clean이었다. HEAD에는 `42a705c` 이후 controller/service/integration test/synthetic MODIFY test 4파일의 auto-backup diff가 포함됐고, 수정분을 삭제하거나 reset하지 않았다.
- 공식 Excel 원본, V1–V24 migration 파일, 기존 process DB, 기존 PM2 서버는 수정/재시작/배포하지 않았다. 이번 작업의 코드 수정은 WIP 브랜치 작업분이다.

### 구현 보완
- `POST /api/workspace/change-proposals/{proposalId}/what-if`의 기존 append-only `VALIDATION_RESULT` 연결을 재사용했다. 계산 전 proposal snapshot과 selected changed-edge를 대조하도록 강화했다: PIPE/FLOW의 연결 ID·유형, 출발/도착 공정·설비, 물질 분류·종류, 단위, 기간은 네트워크 edge와 일치해야 하며, FLOW 수량은 고정 변경 연결과 `baseQuantity`에 정확히 일치해야 한다.
- DELETE 비교는 management number가 before graph에 정확히 한 번 있고 after graph에는 없어야 한다. 조건 불일치 시 409로 차단한다. 분기·이동 경로 등 네트워크 입력 변경은 기존 solver와 비교 경로를 재사용한다.
- residual 비교를 before/after key의 합집합으로 바꿔 한쪽 네트워크에만 존재하는 process/external stream을 누락하지 않고 `NEW_AFTER` / `MISSING_AFTER`로 표기한다. 잔차 delta는 같은 물질/단위/기간 key 안에서만 계산한다.
- What-if 입력 검증에서 필수 network identity/basis/period 및 모든 edge의 기간을 검사하고, 음수 유량/시나리오를 거부한다. `WhatIfComparisonInput`에서 before/after와 내부 DTO 검증을 cascade하도록 했다. 단위 변환이나 연결 추정은 추가하지 않았다.
- 결과에 `actualReadiness.status=CALCULATION_UNAVAILABLE`를 보존하고, proposal review는 `REVIEW_REQUIRED`로 남긴다. 부족한 조건의 합성 루프는 `UNDERDETERMINED` 계산상태로 구분하며 실제 계산 가능으로 승격하지 않는다.

### 실제 실행·테스트
- 요청된 첫 명령 통과: `./mvnw -q -DskipTests compile` 및 `./mvnw -q -Dtest=NetworkWhatIfServiceTest,DirectedProcessNetworkSolverTest,MaterialBalanceCalculatorTest test`; 해당 시점 총 17 tests pass.
- 최종 실행: `./mvnw -q -DskipTests compile` 다음 격리 DB 변수를 source하고 대상 URL이 `process_map_proposal_test_20261031`인지 guard한 뒤 `./mvnw -q -Dtest=NetworkWhatIfServiceTest,DirectedProcessNetworkSolverTest,MaterialBalanceCalculatorTest,OperatorChangeProposalIntegrationTest,OperatorDiagramModifySyntheticIsolationTest test`; 종료 코드 0.
- 최종 선택 테스트 28건: NetworkWhatIfServiceTest 3, DirectedProcessNetworkSolverTest 9, MaterialBalanceCalculatorTest 6, OperatorChangeProposalIntegrationTest 8, OperatorDiagramModifySyntheticIsolationTest 2 — 실패 0, 오류 0, skip 0.
- 합성 TEST_ONLY 시나리오에서 20→10/50, PIPE 경로/영향 설비, DELETE before/after 설비/영향 범위, 분기·합류 잔차 및 전체 외부 유입·유출, 조건을 갖춘 내부 재순환, 미결정 순환, 기간 불일치/누락, 음수 유량 거부를 테스트했다. 테스트 네트워크는 실제 공정 근거가 아니다.
- proposal 통합 테스트의 두 수정 iteration은 기록한다. (1) 분기 네트워크 edge 유형과 제안 snapshot이 불일치하여 예상한 409가 반환된 점을 테스트 데이터 snapshot과 일치시켜 해결했다. (2) JSON으로 직렬화된 solver 수량 `20.0`을 정수 `20`으로 기대한 assertion을 숫자형 `20.0`에 맞춰 수정했다. 최종 선택 suite는 전부 통과했다.
- 격리 DB 로그에서 Flyway 24 migration validation 및 schema V24 up-to-date를 확인했다. 테스트 후 read-only row count: `baseline_release=0`, `baseline_document=0`, `process_working_document=0`, `process_change_proposal_event=0` (rollback 확인). 연결은 `127.0.0.1:3306/process_map_proposal_test_20261031`만 사용했다. 기존 DB에 접속하지 않았다.

### 남은 범위
- proposal 비교 workbook 다운로드 endpoint와 신규 UI는 미구현이며 브라우저 E2E도 수행하지 않았다. 결과는 JSON append-only validation event에 저장된다.
- 실제 P-02/PW-02·폐수·폐기물 연결은 검증된 물리 연결 증빙을 사용하지 않았다. 실제 연결/유량 기반 수지 계산은 `CALCULATION_UNAVAILABLE`로 유지한다.
- 전체 Maven suite는 실행하지 않았다. 이번 범위는 위 28개 focused test로 한정했다.

---

## 2026-11-01 What-if 마감 정리 및 15개 공정도 편집 엔진 분석

### 실제 시작 상태와 보존 확인
- 재개 세션에서 실제 확인한 브랜치는 `step7-3-wip-20261023`, 문서 작업 전 HEAD는 `8968c1d8112b3ab638f1f4f88a3370ede46d68f1`; 사용자가 보고한 시작점 `4c95d1a` 이후 auto-backup commit이 이미 HEAD에 포함돼 있었다. 작업 전 status는 clean. 최근 commit은 `8968c1d genspark auto-backup`; `main`에 병합하지 않았다.
- `DirectedProcessNetworkWorkbookExporter.java` working-tree diff/status 없음. browser-e2e 및 excel-render-review 증거는 모두 tracked되어 있었고 보존했다. 공식 원본 및 target test artifact 위치를 확인했으며 이 문서화 작업에서는 원본을 수정하지 않았다.
- 기존 PM2 `process-map-automation`은 online (초기 확인 PID 2109). 기존 PM2를 재시작하지 않았다. 임시 Java test app/Chromium은 이전 실행에서 종료된 상태를 이어받았으며, 재개 작업에서는 서버를 시작하지 않았다.
- 시험 DB `process_map_whatif_test_20261101_2ca79b6`, `process_map_dev`, migration, 운영 DB에 이 재개 작업 중 접속/변경하지 않았다. DB migration 및 배포 없음.

### What-if/API/E2E 및 해시
- 기존 실행 기록 기준 What-if 집중 테스트 30/30 PASS, failure/error/skip 0. 브라우저 E2E JSON은 로그인, 제안 목록, PIPE/FLOW 합성 before/after, TEST_ONLY download, 별도 context 401, logout 흐름 PASS를 기록한다. 이는 browser flow PASS이지 다운로드 바이트 checksum PASS가 아니다.
- API 코드 `GET /api/workspace/change-proposals/{proposalId}/what-if/export`는 workbook SHA-256, `X-Output-SHA256`, TEST_ONLY/baseline 미수정 헤더, SHA prefix 기반 attachment filename을 구성한다. 당시 실제 브라우저 HTTP response headers는 보존되지 않았다.
- JSON `download.sha256`은 `b7e033ee9ae3b8ea2203c046b076ccb7a9310cb8f221107660af40e37547a8d5`; 현재 동명 다운로드 파일(14,013 bytes, 2026-11-01 09:58:44 UTC)의 SHA는 `cdb360f4471de50ffb689eb42e8f214be3977b732927d99b4e061c95b31ff3d5`. JSON 기록/파일명 prefix는 일치하나 현재 파일 digest와 불일치. 원인은 특정하지 않았고 파일/JSON을 수정하지 않았다.
- 두 target test XLSX도 각각 13,998 bytes/SHA `d350355c7901005875f27a77d47d790a629858b9e596d5a5b6e5a4549807704c`, 14,364 bytes/SHA `02ab068ee94d29192a4b9afc68cad974827574a259fdac9015862f46eb170913`; browser 파일과 같은 proposal response라는 연결 증거가 없고 셀/XML 내용도 다르므로 불일치 원인을 설명하지 않는다. 세 파일은 ZIP 16 entries, 무결성 확인 정상. **Checksum은 unresolved이며 PASS 처리하지 않는다.**

### Excel 시각 검수 및 보고서
- 세 대상 workbook은 각각 8 sheets, 대응 PDF 각각 13페이지 A3 landscape. 기존 PDF 3개와 개별 PNG 39개를 사용해 각 페이지를 개별 확인했다. 표/텍스트의 명백한 잘림·겹침은 보이지 않았으며, 표 분할/작은 글씨/여백, comparison workbook의 header-only 외부 수지 비교 11–12쪽, 명시적 print area 미설정을 기록했다.
- workbook style metadata의 글꼴은 맑은 고딕, indexed color 8(검정). LibreOffice에는 맑은 고딕이 없어 Noto Sans 대체 폰트로 렌더링했다. 이 font rendering은 metadata와 구분한다.
- 검수표, 다운로드 해시 근거 및 마감 판정: `validation-results/2026-11-01-whatif-closeout/WHATIF_EXCEL_VISUAL_QA_2026-11-01.md`.

### 15개 공정도 분석과 다음 개발 범위
- progress.json 실제 기록: 15 documents / 62 of 62 sheets / 0 failures, source fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb`.
- 상세 inventory workbook의 실제 재집계: 위치 후보 1,006 (VERIFIED 2, REVIEW_REQUIRED 655, UNSUPPORTED 349), DrawingML shapes 586, Connectors 8,094, merged ranges 10,625. 인벤토리의 VERIFIED는 구조 후보 상태이며 실제 물리 연결이나 편집 승인으로 해석하지 않는다. 명시 connector shape binding은 inventory에서 0.
- 공정/시트별 설비 box·후보·도형·connector·merge 분포, P-02/PW-02 field mapping 근거와 미확인 필드, ADD/MODIFY/DELETE 및 PIPE/FLOW 안전 경계, 단계별 권고와 개발 착수 범위는 `validation-results/2026-11-01-whatif-closeout/STEP7_7_PROCESS_DIAGRAM_EDIT_ENGINE_READINESS.md`에 있다.
- 권고 순서: 1) 공정별 profile을 갖춘 기존 ADD 엔진 범용화, 2) MODIFY 필드 매핑 확대, 3) 안전한 DELETE(초기에는 impact preview/차단), 4) 증거 있는 PIPE_CHANGE 도면 반영, 5) 승인된 edge의 FLOW_CHANGE 표시, 6) 승인된 물질수지 연결망과 도면 통합. 공식 파일 전 범위 자동 편집은 미완료.
- 물리 연결 증거가 없는 edge/connector/유량을 생성하지 않으며 실제 공정 readiness는 `CALCULATION_UNAVAILABLE`, 제안/관계는 `REVIEW_REQUIRED` 유지.

### 마감 파일과 실행/커밋 상태
- README는 현재 export endpoint 존재 사실과 과거 “미구현” 시점 기록을 구분하고, 최신 검수/미해결 checksum/편집 엔진 준비 범위를 추가했다.
- 이번 재개 작업에서 새로 만든 보고서는 위 두 Markdown 파일이며, 기존 E2E/PDF/PNG/XLSX 증거는 수정하거나 삭제하지 않았다.
- 문서 변경은 README, CHECKPOINT, 두 Markdown 분석·검수 보고서로 제한한다. 최종 커밋 hash, HEAD, clean/dirty status, 미추적 증거 및 PM2 상태는 작업 완료 시 최종 전달 기록에 남긴다.

---

## STEP 7.7 Profile ADD 안정화 후속 (2026-11-01)

### 시작 상태 및 보존
- 실제 첫 Bash 확인: branch `step7-7-wip-profile-add-20261101`, HEAD `9b9a4b688f90fea8ec68068e5fb3543775562ef0`, worktree clean. 사용자가 기재한 `d07a3918...`가 아니라, 해당 Profile 코드가 포함된 auto-backup commit이 현재 실제 HEAD였다. 기존 파일 초기화/삭제/stash/reset은 하지 않았다.
- 공식 Excel, `process_map_dev`, 기존 PM2 운영 서비스, DB/migration, `main`, 배포는 변경하지 않았다. 기존 TEST_ONLY 파일도 덮어쓰지 않았다.

### Fixture 수정 및 결과
- 기존 문제가 된 artifact test가 03~06에 동일 fixture를 재사용해 같은 XLSX bytes/hash를 생성하던 것을 고쳤다. 각각 독립 셀 위치/값/프로필 매핑을 갖는다: 03 management/name 분리행(B15 name), 04 Item No. 별도 영역(E13), 05 capacity/unit 분리(C16/D16), 06 상자 행 높이 10/12/14/16/18/20/22/24pt.
- 01 기본 사각형, 02 병합(D2:E2 및 복제 D12:E12), 07 인접 DrawingML textbox, 08 삽입 경계를 가로지르는 Connector 차단도 함께 유지했다. 08은 `DRAWING_ANCHOR_CROSSES_INSERTION`로 XLSX를 만들지 않는 negative test.
- 최종 artifact run `validation-results/2026-11-01-step7-7-profile-add-test/20261101-115805-516/`. manifest SHA와 실제 파일 digest 일치, 7개 모두 `unzip -t` PASS. manifest SHA: 01 `57515ca47c54c2b30eb2ca4749f4af42daf0d1449a06256eb891d9ba8c9e539c`; 02 `2e902e7cbfa6dbcd4c3b306124f6dc1d8c0f3c1d33958e9b49d02bf8c9e99aa4`; 03 `ddc093cc3fdc5ee682629b24f9cafe08317e14a24dc50a8909ed85f67340627f`; 04 `19c3c804e08245c1a993435452914063f7d8d35ce48b066f1357b4f38c99142a`; 05 `494c9cd04a7bf1b1466e152b5abf445bb33b040d728b21d523711afd7a791b90`; 06 `f86b96af6c7197cf91bd58c0954637a2264c2e81c1c6e0fc22e6972d13763bbe`; 07 `dd13144f2e8bd62fa13b81b322755caa6efa615046d623b0fd369d6ac42d2396`; 08 blocked as above.
- POI 재열기에서 각 mapped field value/style, 기존 비어 있지 않은 셀 값/스타일, 행 높이, 열 너비, 기존 merge 이동/보존, 새 merge, print area, paper/orientation/fit/margins, 글꼴 맑은 고딕 8pt/검정, DrawingML ID·이름·connector binding·화살표·anchor delta를 검사한다. 이들은 synthetic fixture 검증이며 실제 공정 파일 검증이 아니다.
- `DiagramEquipmentInsertionEngineTest` 11/11 PASS, `GeneralDiagramEditProfileStatusTest` 1/1 PASS; compile/test-compile 성공, 총 12 tests, 실패 0/오류 0/skip 0. PW-01-05 기존 전용 엔진의 안전한 5개 selected test PASS, `DiagramBoxChangeEngineTest` 6/6 PASS. fixed-output 경로가 이미 존재하던 `createsNineCharacterTestOnlyWorkbookWithExistingEquipmentStyle`는 overwrite 방지를 위해 실행하지 않았다.

### Status·UI·기존 API 경계
- `GeneralDiagramEditService`에서 미등록/source SHA 불일치 profile은 `UNSUPPORTED`, P-02-05 등 등록 review profile은 `REVIEW_REQUIRED`, PW-01-05의 정확한 fixed placement는 `LEGACY_ONLY`로 반환한다. PW-01-05 generic preview는 차단하고 화면의 legacy 전용 처리만 허용한다. Profile catalog의 status도 `LEGACY_ONLY`로 일치시켰다.
- `GeneralDiagramEditProfileStatusTest`에서 REVIEW_REQUIRED/UNSUPPORTED/LEGACY_ONLY API-service 판정, generic preview 409 차단, 범용 엔진 미호출을 DB 없이 검사했다. 화면은 네 상태를 각각 표시하고 LEGACY_ONLY만 고정 후보에서 기존 레거시 경로로 연결한다. inline JavaScript `node --check` PASS.
- DB integration 대상은 격리 URL/user/password가 설정되지 않았고 기본 datasource가 금지된 `process_map_dev`이므로 `OperatorWhatIfSessionIntegrationTest`, `OperatorWorkspaceSessionIntegrationTest`, `Pw0105InsertionCandidateCompatibilityIntegrationTest` 및 API DB E2E는 실행하지 않았다. Chromium/Playwright가 없어 브라우저 UI E2E도 미실행. PM2 운영 서비스를 시작/재시작하지 않았다.

### 실제 작업본 확인 및 적용 범위
- `data/`와 `validation-results/`의 XLSX 180개를 SHA 비교했다. P-02 official source SHA `28716f...`는 공식 파일과 일치하지만 P-02-01 검증 working SHA `c3379f...` 파일은 없음. PW-02 official source `2fce7f...`는 파일과 일치하지만 과거 working SHA `4250b0...`는 없음. PW-01 official source `784dada...`만 일치하는 파일은 공식 원본 복사본이며 검증 working copy가 아니다. 따라서 실제 공정 TEST_ONLY ADD, PDF 렌더, 시각 검사 모두 수행하지 않았다.
- P-02-05 `REVIEW_REQUIRED`, PW-02-01 `REVIEW_REQUIRED`, PW-01-05 `LEGACY_ONLY` 유지. 다른 공정 Profile을 새로 추가하지 않았다. exact working SHA, 독립 field mapping, 삽입 geometry evidence 확보 후 확장한다.
- README에 mapping/status matrix, source/working SHA 조사 결과, 합성 artifact 경로와 digest, 테스트·차단·미완료 범위를 별도 업데이트했다. `CALCULATION_UNAVAILABLE`과 실제 물리 연결 증거 없음은 유지한다.

### 실행 상태
- Java-focused suite는 actual application default DB를 사용하지 않았다. 별도 API workflow/browser E2E, 실제 작업본, Excel/PDF visual QA는 미완료로 기록한다. `git diff --check`, staged diff, final HEAD/status와 commit 결과는 이 checkpoint closeout 이후 같은 작업의 최종 검증으로 기록한다.

---

## STEP 7.8 마감 — 사용자 작업본 Profile ADD (2026-11-02)

### Git 기준·범위 보존
- 첫 Bash 결과: branch `step7-7-wip-profile-add-20261101`, HEAD `cfc57c839e07473399942f56862e2da5e9d592ec`, status clean, `git diff --check` 및 `./mvnw -q -DskipTests test-compile` PASS. 사용자가 제시한 직전 HEAD `3ee0cd2...`와 실제 HEAD가 달랐으나 현재 상태를 기준으로 진행했고 reset/stash 삭제/main 병합은 하지 않았다.
- 기존 STEP 7.8의 UserWorkingCopyService OOXML 제한, 다운로드 증거 헤더 및 통합 시나리오는 시작 HEAD에 이미 포함돼 있었다. 이번 회귀 실행에서 시험 DB에 이미 남은 V1.0 합성 baseline과 append-only output 기록을 지우지 않고 재검증하기 위해 `OperatorUserWorkingCopyIntegrationTest`의 합성 baseline release 재사용 helper와 현재 output row 수 대비 assertion을 보완했다. 운영 코드 기능을 추가하지 않았다.

### 산출물·POI/OOXML 검증
- artifact 위치: `validation-results/2026-11-02-step78-closeout/`. 기존 파일을 덮어쓰지 않고 browser download 행을 `SHA256SUMS.tsv`에 추가했다. manifest 4행 모두 실제 SHA-256 대조 OK, `unzip -t` OK.
  - `SYNTHETIC_BASELINE_NOT_OFFICIAL.xlsx` — 5,079 bytes, SHA-256 `6d4f5b206cd3fd35defcd17d6d49ee7b5c4bd11fc5bc518f8cc65d503fe1e1bd`.
  - `SYNTHETIC_USER_WORKING_COPY.xlsx` — 5,085 bytes, SHA-256 `1404314f9fdb8f3c4a25be16391810fbfac3fb8a852ad47f33a3cd2c72d02c38`.
  - `SYNTHETIC_PROFILE_ADD_TEST_ONLY.xlsx` — 5,251 bytes, SHA-256 `00c8f5e4caaf9a4c7348d433be9bc5e2587972de7a604d4e584de932f11d8f67`.
  - 브라우저 실제 다운로드 `browser/downloads-final/P-02_P-02-01_TOP__TEST_ONLY_ADD_A11_F18.xlsx` — 5,262 bytes, SHA-256 `3512a4813a363b76e61d5eea5bc3336b91a8cd5f33ff728580369ba932e57981`. `X-Output-SHA256`와 실제 bytes SHA 일치.
- Browser download를 Apache POI로 다시 열었다. ID `SYNTH-BROWSER-ADD-01`, name `Synthetic browser tank`, type `Storage`, Item No. `SYNTH-BROWSER-ITEM-01`, capacity `125m3/h`, quantity `1ea`; 해당 셀 글꼴 맑은 고딕 8pt·indexed color 8(검정). 합성 시트 merge 0, DrawingML shape 1 보존, print area `'P-02-01(TOP)'!A1:H50`. 전체 셀/스타일 목록은 `browser/downloads-final/POI_AUDIT.txt`.
- 세 번째 DB BLOB 조회 후 다시 계산한 source SHA `6d4f5b...` 및 working SHA `140431...`는 synthetic artifact/manifest와 각각 일치했다. 브라우저 output의 저장 SHA는 `3512a481...`이며 두 output row에서 `TEST_ONLY`/`REVIEW_REQUIRED` 유지.

### 브라우저 E2E 및 DB 경계
- Chromium cached binary와 Python `websocket-client`를 이용한 CDP UI E2E를 실제 수행했다. 최종 보고서 `browser/e2e-report-final.json`, 네트워크 `browser/cdp-events-final.jsonl`, 화면 캡처 `browser/final-01-profile.png`–`final-04-logout.png`. 로그인→upload→버전 목록→정확한 합성 Profile `VERIFIED`→입력→preview→TEST_ONLY generate→download/hash compare→logout 총 8/8 PASS, console errors 0, logout 이후 `authenticated=false`. network에서 선택적 `modify/equipment-options` GET 404 1건을 확인했으나 ADD 흐름은 성공.
- Browser app은 port 3010, Chromium CDP 9222, synthetic classpath Profile 및 `USER_WORKING_COPIES_ENABLED=true`로 격리 구동했다. app log 및 DB URL은 `process_map_step78_test_20261102`를 가리켰고 Flyway V25 성공/up-to-date. 기본 flag는 `application.properties`의 `${USER_WORKING_COPIES_ENABLED:false}`로 그대로 false.
- 테스트 후 read-only 확인: Flyway V25 success=1; `baseline_release=1`, `baseline_document=1`, `process_working_document=1`, `process_test_only_output=2`. 브라우저 합성 fixture/output만 남았으며 append-only history 및 FK가 있는 데이터를 삭제하지 않았다. `process_map_dev`는 접속하지 않았다.
- 격리 app PID 66707과 isolated Chromium tree만 종료했다. port 3010/9222는 닫힘, 기존 PM2 daemon/process는 유지. `/tmp/step78-browser-test.env`와 `/tmp/process_map_step78_test.env`는 secure-unlink 처리했다. 공식 Excel 원본, 운영 DB, 운영 migration, 배포는 변경하지 않았다.

### 집중 회귀
- 최종 명령은 격리 `DB_URL`을 `process_map_step78_test_20261102`와 정확히 대조한 뒤 수행: `./mvnw -q -DforkCount=0 -Dtest=OperatorUserWorkingCopyIntegrationTest,DiagramEquipmentInsertionEngineTest,GeneralDiagramEditProfileStatusTest,Pw0105EquipmentInsertionEngineTest,DiagramBoxChangeEngineTest test`.
- 최종 Surefire: `OperatorUserWorkingCopyIntegrationTest` 3/3, `DiagramEquipmentInsertionEngineTest` 11/11, `GeneralDiagramEditProfileStatusTest` 1/1, `Pw0105EquipmentInsertionEngineTest` 6/6, `DiagramBoxChangeEngineTest` 6/6 — 총 27 PASS, failure/error/skip 0. 전체 Maven suite는 수행하지 않았다.
- 재실행 전 합성 DB에 고정 버전 V1.0이 이미 있어 기존 test setup이 duplicate key에 부딪혔고, 단순 회귀 재실행 한 번은 test JVM timeout으로 중단됐다. 테스트를 기존 V1.0 release 재사용 방식으로 바꾸고, append-only output 개수는 기존값 대비 증가를 확인하도록 수정했다. 이후 최종 suite에서 위 27건 모두 통과했다.

### 적용 한계·다음 STEP 7.9
- 합성 fixture는 실제 P-02 작업본이 아니다. 등록 Profile이 참조하는 실제 P-02 working SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`와 일치하는 사용자 작업본은 확보되지 않았다. 실제 공정 ADD 성공/검증으로 주장하지 않는다.
- 실제 물리 연결 자료가 없으므로 connector·flow를 만들지 않았고 실제 material-balance readiness는 계속 `CALCULATION_UNAVAILABLE`이다.
- 2026-11-01 What-if E2E JSON SHA와 현재 다운로드 파일 SHA 간 기존 불일치는 별도 unresolved로 보존한다. 이번 ADD browser SHA 일치와 연결하거나 해결 처리하지 않는다.
- STEP 7.9는 정확한 실제 작업본과 source/working SHA 확보를 선행하고 Profile field/slot 및 workbook 보존을 독립 확인한다. 승인이 없는 공식 workbook 수정, operating DB 변경, 연결/유량 생성은 금지.
- 검증 artifact와 screenshots는 gitignored `validation-results/`에 보존한다. credential 파일은 삭제했다. 마감 문서와 테스트 회귀 변경은 현재 브랜치에서 선별 커밋했으며, 최종 commit ID/HEAD와 worktree 상태는 작업 종료 보고에 기록한다.

---

## STEP 7.9 Profile 확대·범용 ADD 엔진 안정화 (2026-11-01)

### 기준 및 보존
- 실제 시작 Bash: branch `step7-7-wip-profile-add-20261101`, HEAD `e1b89b0` (`genspark auto-backup`), `git status --short` clean. STEP 7.9의 이전 편집은 이 auto-backup commit에 이미 포함되어 있었다. 그 상태를 유지했으며 reset/stash deletion/main merge는 하지 않았다.
- P-02/PW-02/P-01 공식 원본 및 locked inventory는 읽기 전용으로만 분석했다. process_map_dev, 운영 DB/migration, 기존 PM2, deployment를 사용하지 않았고 process flow/connector를 만들지 않았다.
- `equipment-box-profiles.json`은 schemaVersion 2, 현재 5 Profiles. 기존 P-02-01 `VERIFIED`와 PW-01-05 `LEGACY_ONLY`는 그대로 유지한다. 확장 Profile은 exact current working-copy SHA가 없는 상태라 전부 `REVIEW_REQUIRED`/`STRUCTURE_ANALYZED`다.

### 공정별 Profile 상태 및 매핑 근거
| 공정/시트 | Evidence Level / Profile Status | 조사한 셀·구조 | 남은 blocker |
|---|---|---|---|
| P-02 / `P-02-05(BROKE)` | `STRUCTURE_ANALYZED` / `REVIEW_REQUIRED` | `I-PP22061` 박스 `J129:AA136` 8행. ID `J129`, 유형 `J131`, 이름 `J132`, Item No. `J133`, capacity+unit `J134`, quantity `J135`. 위치 `I-PP22061→I-PP25008` candidate band `J137:AA138`은 2행만임. | 8행 삽입 예상 `J137:AA144`는 해당 후보 band 증거로 승인할 수 없다. 전체 neighborhood를 inventory상 별도로 보면 `W140:AA146` merge와 `D143:J143` Connector 57(끝 anchor가 J 경계), 하단 `I-PP25008` box `J147:AA153`이 있어 전체 row insertion 후 merge/drawing/lower box shift를 사용자 작업본에서 검증해야 한다. Profile은 차단 상태 유지. |
| PW-02 / `PW-02-01` | `STRUCTURE_ANALYZED` / `REVIEW_REQUIRED` | `I-PW21007` 박스 `J145:AA152` 8행. ID `J145`, 유형 `J147`, 이름 `J148`, Item No. `J149`, capacity+unit `J150`, quantity `J151`. | 과거 TEST_ONLY 기록은 historical working SHA `4250b004c723b9d366d1231ce98d75761a864564402250fe5a5873665b00b8d1`, output SHA `d0c49f943993dd47cca210c3b5d45622a18815a5036d6b2fd83708c37694bd68`에 한정. 현재 그 작업본 바이트는 확보되지 않았다. Inventory의 15 candidate locations는 DrawingML 교차를 포함하며 명시 shape binding 0. 과거 성공 기록과 현재 사용자 작업본 검증을 합치지 않는다. |
| P-01 / `P-01-01(TOP)` | `STRUCTURE_ANALYZED` / `REVIEW_REQUIRED` | `I-PP11001` box `J4:AA20` 17행. ID `J4`, 유형 `J6`, 이름 `J7`, Item No. `J8`, capacity+unit `J9`, quantity `J10`. | 8 candidate locations 모두 DrawingML 교차(각 3–10개); 그중 4개는 merge 교차. W열 폐수/소음 label/data 보존 및 도형 교차가 검증되지 않았고 정확한 사용자 작업본 SHA/bytes 없음. 17행 합성 테스트는 실제 Profile 허용 근거가 아니다. |
| P-02 / `P-02-01(TOP)` | `WORKING_COPY_VERIFIED` / `VERIFIED` | 기존 exact working-SHA bound Profile | 유지. |
| PW-01 / `PW-01-05` | `LEGACY_FIXED_PATH_ONLY` / `LEGACY_ONLY` | 고정 위치 전용 legacy flow | 유지. 범용 ADD 엔진으로 우회하지 않음. |

공통 provenance는 재사용한 locked inventory `validation-results/process-diagram-inventory/locked-v1.0-process-inventory.json`, 15 documents/62 sheets fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb`다. Inventory의 구조 분석·historical output은 실제 사용자 작업본 SHA 검증과 다르다.

### 합성 8행/17행 XLSX 및 보존 검사
- 신규 targeted test `DiagramEquipmentInsertionEngineTest#generatesDistinctSyntheticProfileExpansionLayoutsWithManifestAndPreservedDrawing` 최종 PASS. 두 fixture는 별도 `SYNTHETIC_TEST_ONLY` baseline/working-copy/output이며 8행 compact split capacity/unit과 17행 merged layout이다. 산출물 경로: `validation-results/2026-11-01-step7-9-profile-expansion/20261101-150951-113/`.
- `SHA256SUMS.tsv` 6행의 SHA-256와 byte size를 실제 파일에 각각 대조했고 six XLSX ZIP package의 CRC 테스트가 모두 PASS. ADD output:
  - `SYNTHETIC_TEST_ONLY_COMPACT_SPLIT_UNIT_ADD_OUTPUT.xlsx` — 6,374 bytes; SHA-256 `8e8d0ffb722f0f3c907dca5cdcda697c316a044d08f1880fb0dbc3226bbe4fc2`.
  - `SYNTHETIC_TEST_ONLY_LONG_MERGED_BOX_ADD_OUTPUT.xlsx` — 7,368 bytes; SHA-256 `4d983d97b6ca931b0964d128f58bfb846886b1a9cfff337f2d6afe41f5104ebb`.
- POI 재열기 및 테스트 assertion: Profile 상대 셀/필드 값, split capacity `125` + unit `m3/h`, Item No./quantity, expected cell styles, 맑은 고딕 7pt·black, 기존 값/스타일/행높이·열너비 보존, 삽입 row heights/column widths, merge count/ranges, print area, orientation/paper/fit/margins. 두 layout worksheet/merge/파일 구조 및 output hash는 서로 다름.
- DrawingML Shape와 Connector는 각각 1개씩 유지. 객체 ID 중복 없음; object type/name/text/geometry, connector endpoint binding, arrow types, column/row anchor 및 offsets, transform/rotation/flip 확인. 삽입 뒤 anchor row만 삽입량만큼 이동하고 객체 identity는 보존. Connector가 삽입 band를 횡단하는 별도 negative fixture는 `DRAWING_ANCHOR_CROSSES_INSERTION`으로 차단. Connector 생성·연결 추정·유량 기록 없음.
- 이 산출물은 순수 합성/TEST_ONLY 증거이며 실제 P-01/P-02 작업본 검증이나 공정 ADD 성공이 아니다.

### 회귀/API/UI 결과 및 한계
- 최종 focused suite **27/27 PASS**, failures/errors/skips 0: `DiagramBoxChangeEngineProfileModifyTest` 1, `OperatorDiagramModifyControllerProfileRequestTest` 1, `DiagramBoxChangeEngineTest` 6, `DiagramEquipmentInsertionEngineTest` 12, `GeneralDiagramEditProfileStatusTest` 1, `Pw0105EquipmentInsertionEngineTest` 6. 기존 Profile ADD와 PW-01-05 legacy regression 유지.
- `GeneralDiagramEditProfileStatusTest`는 Profile states/evidence, profile fields는 표시되나 `availableFields` 비어 있고 preview 차단되는 경계를 검증한다. `OperatorUserWorkingCopyIntegrationTest` 소스에는 `profileEvidenceLevel`, `workingCopyShaMatch`, `profileFields`, `profileStatus`, `profileShaMatch` 응답 검증이 있으나 이 실행에서는 runtime integration을 실행하지 않았다.
- DB 통합 테스트 설정은 `.env`의 `DB_URL=jdbc:mariadb://127.0.0.1:3306/process_map_dev`로 확인됐다. 이는 금지 대상이자 isolated DB가 아니므로 `OperatorUserWorkingCopyIntegrationTest`를 실행하지 않았다. 격리 DB가 없음을 근거로 DB/API 통합 런타임 결과를 주장하지 않는다.
- index.html inline JavaScript의 중복 `const evidenceLabel` 선언을 검사 중 발견해 리뷰 리스트의 변수를 `reviewEvidenceLabel`로 분리했다. 최종 `node --check /tmp/step79-index-inline.js` PASS. `git diff --check`도 문서/코드 stage 전 통과.

### 미검증·다음 근거
- P-02-05, PW-02-01, P-01-01은 실제 사용자 작업본 exact SHA, 상대 field mapping 확인, 전체 삽입/하단 설비/merge/DrawingML 보존 근거가 확보되기 전까지 계속 `REVIEW_REQUIRED`; 어떤 것도 `VERIFIED`로 승격하지 않는다.
- readiness는 `CALCULATION_UNAVAILABLE`. 물리 연결/유량은 임의 생성하지 않는다. `.env`/official Excel/DB/migration/PM2/deploy/main은 수정하지 않았다. 전체 Maven suite 및 DB 통합은 실행하지 않았다.
- STEP 7.9 코드는 Profile catalog/repository/status metadata, ADD test, API/UI status representation, 문서화 대상으로만 변경한다. 선택 stage에 포함할 파일과 커밋 ID는 작업 완료 요약에 기록한다.

---

## STEP 8.0 Profile 기반 범용 MODIFY 안정화 (2026-11-01)

### 시작 기준 및 안전 경계
- Bash 시작점: branch `step7-7-wip-profile-add-20261101`, 초기 HEAD `1ce529c` (`genspark auto-backup`), worktree clean. HEAD history에는 STEP 7.8의 `60c2282c9b7c75fc12c83e654a68836e928839cb`가 ancestor로 포함되어 `git merge-base --is-ancestor` exit 0. 사용자가 제공한 STEP 7.9 HEAD `e1053b0` 이후 미커밋 MODIFY implementation/test/UI 변경이 auto-backup에 보존되어 이어서 수정했다. `main` merge/reset/stash 삭제하지 않았다.
- `.env` 또는 기본 DB를 믿지 않았으며 `PROPOSAL_TEST_DB_URL/USER/PASSWORD`가 모두 unset임을 확인했다. `process_map_dev` 접속, DB integration, migration, 공식 workbook 수정, PM2 재시작, deploy는 하지 않았다.

### 구현 및 API/UI
- 기존 `DiagramBoxChangeEngine`/`DiagramModifyService`/immutable `EquipmentBoxProfileRepository`/`UserWorkingCopyService`를 재사용. Profile MODIFY 경로는 process, sheet, official source SHA, exact working-copy Profile SHA와 실제 input bytes SHA를 비교한다. Profile에서 매핑된 상대 셀 주소, 기존 값, style/font/size/color, row height, field valueMode 및 DrawingML/merge 충돌을 확인한다. `TEXT`/`TEXT_WITH_UNIT`이면서 실제 `STRING` 셀만 허용한다.
- 허용 대상 필드 키: `equipmentName`, `businessItemNo`, `capacity`, `capacityUnit`, `quantity`, `equipmentTypeLabel`, `note` 중 Profile의 명시 매핑 및 현재 작업본 검증이 성공한 필드만. `managementNo`는 연결/설비 remap 영향 검토 없이는 단일 셀 변경을 허용하지 않는다. blank, control chars, 최대 길이 초과, 동일값, stale old value, Profile field/style/type/address 충돌, formula/merge/DrawingML 교차를 차단한다. Item No. duplicate value 및 동일 설비번호 다중 box도 차단한다. 기존 raw OOXML cell edit는 요청한 셀만 변경하며 다른 package part/DrawingML은 byte-preserve한다.
- `/api/workspace/diagram-editor/modify/preview`에 UI field key/replacement와 lookup 당시 `expectedCurrentValues`를 전달하고, Preview 단계에서도 엔진이 제안 결과를 메모리에서 실제 생성·재열기 검증한다. Generate는 다시 exact working SHA/Profile/current mapping/value를 확인한 뒤 기존 TEST_ONLY output/session/download 경로를 사용한다. 기존 P-02/PW-02 Item No. master mapping fallback을 유지한다.
- index.html은 Profile mapping 필드별 입력/읽기 전용 차단 이유, Profile version/evidence/SHA 검증 표시, field 단위 before/after 행을 렌더한다. dynamic field edit 때 이전 preview/generate/download 상태를 무효화하고, 레거시 Item No. 경로도 유지한다. inline JS `node --check /tmp/step8-profile-modify-inline.js` PASS. feature flag default false 유지.
- API DB 비의존 standalone MockMvc 테스트 `OperatorDiagramModifyControllerProfileRequestTest`에서 capacity+unit field map과 corresponding expected current values가 controller JSON→`ModifyInput` service call에 정확히 전달되고, CSRF guard 호출이 유지됨을 확인했다. DB-backed API runtime 및 브라우저 E2E는 미실행으로 둔다.

### 8행/17행 합성 XLSX 및 manifest
- `DiagramBoxChangeEngineProfileModifyTest`에서 fixture capacity의 기존값 `120`, unit의 기존값 `m3/h`를 확인하고 실제 다른 값(`140`, `L/min`)으로 수정했다. 별도 capacity-only/unit-only test로 각 필드가 독립 셀 변경이며 다른 필드는 유지되는지 검증; unit change는 text label만 수정하고 숫자 용량을 환산하지 않는다. 실제 schema/range 증거가 없으므로 숫자 허용범위/환산을 임의 추가하지 않는다.
- 합성 성공 case 8: 8행 설비명, Item No., capacity 단독, unit 단독, capacity+unit, quantity, 17행 merged layout 설비명, 인접 DrawingML/Connector 상태 설비명. Negative case: wrong style, stale old value, merge overlap, formula box, TEXT Profile field의 numeric cell type, unchanged value, duplicate management ID, duplicate Item No., management number remap 요구. 매 성공 결과는 POI 재열기 후 요청 셀만 값 변경, 요청 외 셀 값/style, merge, 맑은 고딕 8pt/black, row height/column width, print area/페이지 설정/여백, shape/connector inventory/raw drawing bytes를 비교한다.
- 최신 무손실 산출물: `validation-results/2026-11-01-step8-0-profile-modify/run-7697249810495850175/`. 37행 `SHA256SUMS.tsv`의 각 XLSX SHA-256/크기를 실제 파일과 대조하고 ZIP integrity를 검사해 PASS. 결과물은 모두 합성 baseline/working copy 또는 `TEST_ONLY_REVIEW_REQUIRED`로 분류한다.
  - 8행 설비명 output SHA `7b45cb34ae95767669c912f9a6fda81af46e446e47f9b69ecb16dd9fd3bf1126`
  - Item No. output SHA `5fe03f243278c618b6a8c9884d1a21e6fd53e4d53699904df340585403790fff`
  - capacity-only output SHA `f77351ae8c06fb9b77dcfda4d6412e3b1239edbe45c2ca3631a751664bb5f714`
  - unit-only output SHA `9347b51165ea78f2ff3d183bad44552c8fa9d0e62091ba06366f251035402633`
  - 17행 merged output SHA `afa6cfdd8e79ef4ca57012290e572a536c8db798a4199148e6f05d3d8a0cb402`
  - DrawingML neighbor output SHA `5f734b7c0e1b128d548a5ebe5e712cc71ca06791aed95fee1d7f908d4e9bccc1`
- 초기 실행은 같은 unit(`m3/h`)을 교체 요청해 unchanged guard가 막았고, fixture를 다른 값으로 바로잡았다. 다음 실행은 duplicate management fixture가 ID를 style 초기화에서 지워 잘못된 negative test였음을 찾아 fixture 생성 순서를 수정했다. 수정 후 synthetic test PASS이며 37개 manifest 모두 완성.

### 회귀·미완료 범위
- `./mvnw -q '-Dtest=DiagramBoxChangeEngineProfileModifyTest,OperatorDiagramModifyControllerProfileRequestTest,DiagramBoxChangeEngineTest,DiagramEquipmentInsertionEngineTest,GeneralDiagramEditProfileStatusTest,Pw0105EquipmentInsertionEngineTest' test`: **27/27 PASS**, failures/errors/skips 0. 테스트별 count: 1 + 1 + 6 + 12 + 1 + 6. 최종 코드 기준 Maven `test-compile` PASS. DB-backed MODIFY/API tests 및 browser E2E는 isolated DB와 browser runtime이 확인되지 않아 수행하지 않았다. Node inline JS syntax check PASS.
- P-02-01 카탈로그는 `capacity` 한 셀을 `TEXT_WITH_UNIT`으로 map하고 있다. Capacity/unit 분리 test는 synthetic Profile layout에서만 검증했다. P-02-05/PW-02-01/P-01-01은 exact user working copy SHA 및 current field/DrawingML evidence를 확보하지 못해 `REVIEW_REQUIRED` 유지. PW-01-05 `LEGACY_ONLY` ADD 경로는 수정하지 않았다. 합성 결과는 actual production worksheet verification이나 실제 작업본 MODIFY 성공이 아니다.
- 웹 코드는 dynamic Profile fields에서 preview→generate→download 흐름으로 연결했고 controller contract만 DB 없이 검증했다. 실제 browser E2E/runtime API는 아직 미검증. 로그인/CSRF 요구와 user-copy ownership 경계는 코드에서 유지되며 feature flag 기본 비활성이다.
- 실제 연결 증거가 없으므로 connector/flow를 만들지 않았고 material balance readiness는 `CALCULATION_UNAVAILABLE` 유지.
- 다음 DELETE 준비: 기존 `/modify/delete-impact` 영향 preview 및 box assessment를 활용하되, 실제 delete는 별도 승인 전 차단 상태를 유지한다. 대상 박스 유일성, merge 경계, formula, adjacent text, DrawingML/connector binding 및 stale-value/SHA를 독립적으로 test하고, package/worksheet delta와 exact unaffected-cell preservation 증거를 확보한 뒤에만 TEST_ONLY delete를 고려한다.
- STEP 8.0 코드/test/docs 수정분 `git diff --check`는 PASS. 파일별 diff와 staged diff 검토 후 검증된 변경만 선별 커밋했다. 이 기록에 기재된 artifact는 gitignored validation output이며 공식 원본/운영 DB/PM2/deploy/main 변경은 없다. 최종 커밋 ID와 worktree 상태는 완료 보고에 기록한다.

---

## STEP 8.1 DELETE overload 최종 재검증 및 커밋 준비 (2026-11-02)

### Git 기준 및 작업 보존
- 첫 실제 Bash 결과: branch `step7-7-wip-profile-add-20261101`, HEAD `0201dcf7a7ae69a238beca59c4b87808f4a9629d` (`0201dcf`), 시작 worktree는 사용자 제시 `d41553b`와 다르게 README 및 `DiagramModifyServiceDeleteGateTest.java` 두 파일에 기존 미커밋 변경이 있었다. 현재 HEAD/history와 기존 변경을 보존했고 reset/stash/checkout을 사용하지 않았다.
- 해당 실제 시작 명령에서 `git diff --check` 및 `./mvnw -q -DskipTests test-compile` 성공. DB 격리 환경변수 `SPRING_DATASOURCE_URL`, `DB_URL`, `DATABASE_URL`은 unset이었다.

### DELETE 안전 경로 검토
- `DeleteImpactAnalyzer`는 현재 bytes의 actual SHA, source/working SHA, Profile identity/status/evidence/deletePolicy, 고유 대상 box, 셀 값/type/style/formula, 병합, DrawingML 전체 metadata/anchor/binding, 주변 설비, row/column layout, print area/page setup과 blockers를 분석한다. `analysisFingerprint()`는 이 입력과 분석 결과 목록을 canonical/token 방식으로 SHA-256 해시하며 동일 분석의 재현성과 상태 차이에 따른 fingerprint 변화를 테스트했다. 전체 파일 bytes SHA도 fingerprint 대상이므로 분석 이후 XLSX 바이트 변경은 바뀐 SHA로 식별된다.
- `DiagramModifyService.previewDelete`는 소유자·USER_WORKING_COPY·기능 flag·기대 working SHA·현재 재분석 fingerprint·SAFE 상태를 확인한 뒤에만 제안을 기록한다. `generateDelete`는 현재 작업본/공정/소유자를 다시 읽고 analyzer를 재실행해 preview fingerprint와 status를 대조한다. 최신 assessment만 public assessment-guarded `deleteBoxTestOnly(source, assessment)` overload에 전달하고, 엔진은 SAFE status/허용값 및 actual/source/working bytes SHA를 다시 비교한다.
- DELETE 엔진/Analyzer/Service/Controller 구현은 시작 HEAD에 이미 포함되어 있어 이 마감에서는 기능을 추가하지 않았다. 합성 positive test를 실제 public guarded overload를 통과하도록 보강했고, 다른 working SHA assessment는 엔진 overload에서 `DELETE_ASSESSMENT_REQUIRED`로 거부됨을 추가 검증했다. Service gate는 stale fingerprint, 다른 사용자 소유권 및 기본 OFF feature flag가 engine 호출·XLSX output 저장·append-only output event 전에 차단됨을 검사한다. `REVIEW_REQUIRED`, `UNSUPPORTED`, formula, duplicate ID, merge boundary, 인접/교차 도형, 위치 미상 및 shape-bound/관통 Connector는 negative case로 유지한다.
- Profile catalog의 기존 다섯 정책은 모두 `REVIEW_REQUIRED`. SAFE는 별도 합성 Profile의 `BOX_ONLY_SYNTHETIC_TEST_ONLY` 또는 `BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY`에 한정한다. 도형/Connector는 보존하거나 불확실 영향 시 차단하며 자동 삭제·재배선하지 않는다. 물리 연결 근거가 없으므로 연결 상태 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`, 실제 계산 readiness `CALCULATION_UNAVAILABLE` 유지.

### 컴파일·focused 회귀 및 실제 테스트 클래스
- 사용자가 예시로 지정한 `DiagramBoxChangeEngineDeleteTest`는 저장소에 없어 `./mvnw -q -Dtest=DiagramBoxChangeEngineDeleteTest test`가 `No tests matching pattern`으로 종료했다. 실제 analyzer/합성 DELETE 클래스는 `DeleteImpactAnalyzerSyntheticTest`다. 이를 포함한 실제 8개 클래스를 실행했다.
- 마지막 테스트 코드 보강(성공 사례가 guarded overload 사용, wrong-SHA assessment rejection) 이후 `./mvnw -q -DskipTests test-compile` 및 아래 focused suite 최종 재실행이 모두 exit 0.
  - `DeleteImpactAnalyzerSyntheticTest` 4, `DiagramModifyServiceDeleteGateTest` 4, `OperatorDiagramModifyControllerProfileRequestTest` 2
  - `DiagramBoxChangeEngineTest` 6, `DiagramBoxChangeEngineProfileModifyTest` 1, `DiagramEquipmentInsertionEngineTest` 12, `GeneralDiagramEditProfileStatusTest` 1, `Pw0105EquipmentInsertionEngineTest` 6
  - 합계 **36 tests, failures 0, errors 0, skipped 0**. Surefire XML 집계와 일치. 기존 ADD/MODIFY/legacy 회귀 포함.
- DB 통합 테스트는 격리 접속 설정이 없고 기본 DB가 금지 범위이므로 미실행. `process_map_dev`/운영 DB 접속, migration, 공식 Excel 변경, 기존 PM2 재시작은 하지 않았다.

### 합성 XLSX / SHA / ZIP·POI 검증
- 최종 재생성 run은 `validation-results/2026-11-02-step8-1-delete/run-4444900034801975078/`. 기존 `run-*`를 덮어쓰지 않았으며 모든 manifest 및 XLSX ZIP integrity를 실제 해시/크기와 대조했다. 생성 테스트에서 Apache POI 재열기, 삭제 대상 박스 셀 비움/스타일 제거, 비대상 셀 값·스타일 유지, 병합 정책, DrawingML/Connector part 및 metadata 보존, row height·column width, print area/setup을 검사했다.
  - 8행 `SYNTHETIC_TEST_ONLY_DELETE_8_ROW_OUTPUT.xlsx`: 4,231 bytes, SHA-256 `23cc57eb4cbbbc0e4f80138b8f4af4023f45323358c18f790b6a825b2573e08f`.
  - 17행 `SYNTHETIC_TEST_ONLY_DELETE_17_ROW_OUTPUT.xlsx`: 4,591 bytes, SHA-256 `629937f97df43c680a52f76954bd77f31be91a3fea22206b28c8e7a1fb2f2c48`.
  - 두 출력은 `TEST_ONLY_REVIEW_REQUIRED` 합성 결과이며 실제 P-02/PW-02 또는 사업장 설비 DELETE 성공으로 간주하지 않는다. block 기대 test는 엔진 호출/저장 전에 예외를 검사하며 output artifact를 만들지 않는다.

### API/UI 확인 및 미완료 범위
- DELETE UI는 사용자 작업본/시트 선택과 MODIFY 설비 선택 재사용, 대상 입력, read-only `delete-impact`, fingerprint·SAFE/차단 표시, rationale 포함 preview, 서버 재검증 generate, session-scoped download를 연결한다. 대상/수정/입력 변화 때 preview·generated state를 무효화한다. inline JavaScript는 Node `--check` 통과.
- Controller/API 코드는 로그인 검사 및 POST CSRF 검사, session-scoped preview/output map을 적용한다. MockMvc contract test는 fingerprint 전달 및 다른 session에서 preview 접근 차단을 확인한다. Service gate mock test는 owner, feature flag, stale preview 생성 차단을 확인한다. 실 Spring+DB runtime API 및 브라우저 버튼 E2E는 미실행이므로 이를 runtime/E2E PASS로 주장하지 않는다.
- browser E2E 미실행: 이 실행에서 격리 DB와 브라우저 상호작용 환경이 제공되지 않았다. 실제 사업장 설비 DELETE 및 현장 연결 증빙 검증도 미실행. 기존 ADD/MODIFY/legacy focused 회귀는 36개 중 8개 클래스로 재통과했다.
- 다음 STEP 8.2는 append-only proposal과 synthetic What-if를 별도로 검토한다. actual DELETE, connector 변경, 물리 연결/유량 생성, `REVIEW_REQUIRED` Profile 승격 및 readiness 상향은 실제 근거와 별도 검토 전까지 금지한다.

### 문서·Git 종료 기록
- README와 CHECKPOINT에 시작 HEAD 차이, 실제 테스트명/36개 결과, 최신 산출물 SHA, DB/browser 미실행 사유 및 미완료 경계를 반영했다. `validation-results/`는 `.gitignore` 대상이므로 artifact를 stage하지 않는다.
- 선별 stage/commit은 `README.md`, `CHECKPOINT.md`, `DeleteImpactAnalyzerSyntheticTest.java`, `DiagramModifyServiceDeleteGateTest.java`에 한정하고, staged diff를 검토한 뒤 진행한다. 최종 commit ID/HEAD/status는 마감 응답에 기록한다.
- `main` 병합, 강제 reset/stash 삭제, 공식 workbook 수정, 운영 DB migration, `process_map_dev` 접속, 기존 PM2 재시작, deployment는 하지 않았다.

---

## STEP 8.2 PIPE_CHANGE 마감 — 회귀·보안·XLSX/PDF (2026-11-02)

### 시작 상태 및 현재 회귀
- 사용자가 제시한 `bee0e2b` 대신 시작 당시 실제 HEAD는 `9d6b26a0969de7a04f43dafd63429cbc2a21d962`, branch `step7-7-wip-profile-add-20261101`, worktree clean이었다. 현 HEAD를 기준으로 진행했고 기존 이력/자료를 reset, checkout, stash하지 않았다.
- `git diff --check` 및 `./mvnw -q -DskipTests test-compile` 통과. 마지막 코드 수정 뒤 지정 12개 클래스 Surefire 재실행: **66 tests, failures 0, errors 0, skipped 0**. 세부는 README STEP 8.2.
- 전체 Maven suite, DB 통합 테스트는 미실행. `SPRING_DATASOURCE_URL`, `DB_URL`, `DATABASE_URL`, `PROPOSAL_TEST_DB_URL` unset. `process_map_dev` 및 운영 DB에 접속하지 않았다.

### What-if 보안 경계 조사 및 최소 보완
- 실제 호출 그래프 확인: `OperatorWhatIfController`의 사용자 DTO는 `NetworkWhatIfService.calculate()`를 호출한다. `OperatorChangeProposalController`는 `ChangeProposalService.recordWhatIf()`를 호출하며 proposal/process/edge snapshot/기간·물질·단위 검증 뒤 `calculate()`를 사용한다. 이 두 공개 경로는 `actualReadiness()` 평가를 우회하지 않는다.
- `calculateTestOnly()`를 public에서 package-scoped로 제한하고 사용자 DTO는 일반 `calculate()`에 남겼다. 현재 내부 caller는 `PipeChangeSyntheticWorkflowService`뿐이며, 해당 workflow는 클라이언트 본문을 계산망으로 받지 않고 `PipeChangeSyntheticNetworkFactory`가 구성한 서버 전용 A→B/A→C pair를 계산한다.
- PIPE_CHANGE proposal event는 user-input network 계산과 분리해 내부 `recordSyntheticPipeChangeWhatIf()` 경로로 기록한다. 서버 계산 보고서의 TEST_ONLY, 동일 simulation scope, 계산된 baseline/scenarios, readiness status `CALCULATION_UNAVAILABLE`, `calculationAvailable=false`, official baseline 미변경을 확인한다. Public proposal API에는 `calculateTestOnly()` 직접 연결이 없다.
- fixture API ID `SERVER_SYNTHETIC_FIXTURE_V1`, 사용자 작업본 owner/`registeredBy`, kind, 요청 SHA, 서버 fixture SHA 및 실제 bytes SHA를 검증한다. fixture 및 작업본 기능 flag는 기본 OFF. Preview→Generate에서 현재 작업본·SHA/fingerprint·매핑/계산 결과를 재검증한다. CSRF, 로그인, user/session scope 및 소유권 부정 테스트 통과.
- 물리 연결 evidence는 계속 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`; 공식 readiness는 `CALCULATION_UNAVAILABLE`. 실제 공정 변경·유량 생성·공식 승인 없음.

### 서버 workflow XLSX 및 PDF/PNG 검증
- 최종 workflow test run `target/test-artifacts/step8-2-pipe-change/server-fixture-run-17089997718434367208/`; `SHA256SUMS`에 기록된 두 XLSX SHA/bytes 재검증 PASS, ZIP CRC PASS. Before XLSX SHA `b3665efa6e20403a3005b445be0b3a1171971c6485dba7c034b4487fc7e68317`; After XLSX SHA `e9652c82c941fd004a1041afbb06ef0f102877ae5eb882733141b8077814fbae`.
- DrawingML connector ID 4, start `shape 1 / idx 0`; Before end `shape 2 / idx 0`, After end `shape 3 / idx 0`. Before anchor `(1,1,266765,95250)→(3,1,266765,95250)`, After `(1,1,266765,95250)→(5,5,266765,95250)`. Straight connector, solid fill `1F4E79`, `tailEnd triangle`. 두 package part map의 유일 차이는 `xl/drawings/drawing1.xml`; 나머지 part는 동일. 엔진 test에서 POI 재열기, ID 고유성, 비대상 도형/Connector, 셀/서식/merge/인쇄·행열 레이아웃 보존을 검증했다.
- 최종 LibreOffice review run `target/test-artifacts/step8-2-pipe-change/visual-review-run-PsHpFX/`. Before/After 각 A4 1 page, 180dpi PNG `(1489,2105)`. 화면상 Before 수평 A→B, After 대각 A→C이며 pixel-difference box `(298,182,781,345)`, changed pixels 4,933. PDF SHA: Before `cc6573b72170e203d4b5223c8cd3730ba227fce8e7b79240e8ee09e6b52ccaa9`, After `e161d11beee5fce8e3c1486f63f2af4f118b662cf0efd190c6bd999614a820f8`. PNG SHA: Before `5e2970774a8a28c0238eaf1a700e44310381a93b6527effdd8d8b096262d0571`, After `8a26fa251c3b0b1fc219ad8ad28730edd439691856c33614aa9171cffa8841e7`.
- 화면에서 After 화살표는 C를 향하지만 라벨과 겹쳐 보이고 exact connector port 접점을 LibreOffice 렌더로 확정하지 못했다. 따라서 구조/geometry PASS와 분리해 최종 판정은 **`VISUAL_REVIEW_REQUIRED`**이며 시각 접점 PASS로 주장하지 않는다. 모든 PDF/PNG SHA는 해당 run의 `SHA256SUMS`에서 `sha256sum -c` 확인.
- workflow `download()`는 mock storage에서 반환된 bytes가 생성 bytes와 같고 output SHA `e9652c82c941fd004a1041afbb06ef0f102877ae5eb882733141b8077814fbae`와 일치하는 단위검증을 통과했다. 격리 DB가 없어 실제 DB BLOB 저장 후 HTTP 다운로드 바이트는 검증되지 않았다.

### 완료 경계·재개 지점
- 실제 PDF 화면에서 A→B에서 A→C로 route가 바뀌는 것은 확인했다. 정확한 도형 port 접점은 Microsoft Excel 렌더 및 실제 DB/HTTP download에서 추가 검수할 것. Browser E2E/API runtime, DB persistence는 미완료로 둔다.
- 실제 업무 workbook의 PIPE_CHANGE는 서버 synthetic fixture 이외 모두 차단 상태. 공식 baseline/DB/migration/PM2/`main`/deployment는 변경하지 않았다.
- `STEP 8.3 FLOW_CHANGE`는 별도 사용자 지시 이후 시작한다. 기존 확정 edge·실제 물리 연결/유량 증빙 없이 공정 흐름이나 값을 변경하지 않는다.
- 문서화 뒤 `README.md`, `CHECKPOINT.md`, 관련 3개 production source 및 2개 test만 선별 stage/commit할 예정. 최신 commit/HEAD/worktree 결과는 최종 종료 기록에 추가한다.

---

## STEP 8.3 FLOW_CHANGE — 합성 계산/Excel label workflow 진행 기록 (2026-11-02)

### 시작 상태
- 실제 시작 branch `step7-7-wip-profile-add-20261101`, HEAD `a70459bd64e00917e87ca3a09b365662718e6ec2`, 기존 worktree clean; `./mvnw -q -DskipTests test-compile` PASS.
- `process_map_dev`, 운영 DB, 공식 Excel 원본, 기존 PM2, `main`, deploy/migration에 접근/변경하지 않았다. 격리 DB 변수 `PROPOSAL_TEST_DB_URL`, `PROPOSAL_TEST_DB_USER`, `PROPOSAL_TEST_DB_PASSWORD` 모두 unset.

### 현행 기능 조사 / 안전 경계
- FLOW_CHANGE proposal 생성과 generic `/api/workspace/change-proposals/{proposalId}/what-if`가 존재하며 `ChangeProposalService`는 입력 snapshot을 검증하고 `NetworkWhatIfService.calculate()`를 통해 before/after 계산, proposal workbook export까지 한다. append-only schema에 FLOW_CHANGE 유형도 있다.
- 재사용 계산 경로는 `DirectedProcessNetwork` / `DirectedProcessNetworkSolver` / `MaterialBalanceCalculator`다. `NetworkWhatIfService.calculateTestOnly()`는 package-scoped, in-memory이며 TEST_ONLY 외 상태를 거부하고 DB readiness query를 호출하지 않는다. Profile repository/catalog에는 설비 박스용 Profile만 있고 flow-label binding이 없다.
- `DiagramBoxChangeEngine`은 text field·설비 박스 편집용이며 숫자 유량 editor가 아니다. PIPE_CHANGE drawing engine은 connector geometry 전용이다. 일반 `UserWorkingCopyService.saveTestOnlyOutput()` allowlist는 ADD/MODIFY/DELETE/PIPE_CHANGE이며 FLOW_CHANGE artifact 저장은 현재 거부한다(기존 SQL check는 FLOW_CHANGE 허용). 임의 결과의 DB 저장을 넓히지 않았다.
- 기존 network tests가 단일 연결·branch/multi-inflow+merge·interprocess cascade·external boundary·interprocess recycle·negative residual·UNDERDETERMINED cycle를 커버한다. 이번 추가 test는 내부 recycle 20→30시 external inflow/outflow 100 유지와 node residual 0을 확인한다.

### 공식 workbook read-only 조사
- 기존 inventory `validation-results/2026-10-29-process-diagram-inventory/official-process-diagram-inventory_TEST_ONLY.json`의 15 documents/62 sheets 및 `data/reference/`의 15 process `.xlsx` 파일을 읽기 전용으로 확인했다. `openpyxl` inspection에 파일을 저장하지 않았다. 상위 inventory의 기존 aggregate: 10,625 merge, 260 formula cells.
- 셀 scan aggregates: numeric 131, formula 260, text 38,212, merged regions 10,625. 단위/유량 관련 문자열 token이 있는 셀 3,812개: unmerged 1,132, merged anchor 2,680. 명시 문자열 `유량`은 174개. 이들은 candidate text일 뿐 연결망 edge↔label, 기간, 물질, unit binding evidence가 없어 전부 REVIEW_REQUIRED다. 처리량/용량/수량/농도/유량 의미를 숫자/단위만으로 승격하지 않았다.
- inventory drawings 및 DrawingML text read-only scan에서 이 regex 단위/유량 label candidate 0개. Shape/Connector와 label 관계 증거는 없다. 실제 공정 label type별 확정 매핑은 미완료이며 formula/string/unknown target 자동수정 안 함.

### 구현
- 추가 `FlowLabelBinding`: source SHA-bound process/sheet/edge, exact cell, expected-before, class/type/unit/period, mapping status/evidence 및 `NUMERIC_CELL`, `MERGED_NUMERIC_CELL`, `DRAWINGML_TEXT` target type.
- 추가 `FlowChangeSyntheticFixtureFactory` 및 `FlowChangeSyntheticWorkflowService`: 서버만 생성하는 fixture ID `SERVER_SYNTHETIC_FLOW_FIXTURE_V1`, edge `SYN-FLOW-P2X-P1N`, synthetic 20 ton/day, period `2026-11-02/SYNTHETIC_TEST_PERIOD`, 시트 `Synthetic Flow`의 B8(merged anchor)과 B17(ordinary numeric) 두 표시 binding. Independent 20/10/50 cases는 모두 동일 base network에서 계산된다.
- preview fingerprint는 서버 fixture SHA/network/bindings/report에서 만들어지고 generate는 현재 fixture를 다시 계산한 뒤 fingerprint 비교 후 편집한다. client source/network/fingerprint를 trusted input으로 받지 않는다. 서버 workflow는 readiness `CALCULATION_UNAVAILABLE`, official baseline unchanged=false flag(즉 `officialBaselineModified=false`), result `TEST_ONLY_REVIEW_REQUIRED`를 유지한다.
- 추가 `FlowChangeDrawingEngine`은 exact numeric worksheet `<v>`만 part-level 수정. Ordinary + merged top-left numeric cells supported; formula/text/string/non-anchor merged cell/DrawingML text unsupported. exact SHA, current/raw value, binding, duplicate target, merge type를 검사하고 style/display format 유지, output display string/precision을 기록한다. 다른 OOXML package parts, drawing/connector, merge, formula count, print setup 보존과 POI reopen을 검사한다. 결과는 새로운 byte[]이며 원본 덮어쓰기/DB 저장 없음.

### 검증 및 산출물
- `./mvnw -q -DskipTests test-compile` PASS.
- 집중 회귀 13 classes **69 tests, failures 0, errors 0, skipped 0**. 기존 PIPE_CHANGE/controller/network/balance/ADD-MODIFY-DELETE regression 67개와 FLOW_CHANGE workflow 2개 포함. `DirectedProcessNetworkSolverTest`는 신규 internal recycle regression 포함 10 tests.
- 생성 artifact run `target/test-artifacts/step8-3-flow-change/review-run-13676778295918236172/`; SHA256SUMS `sha256sum -c` PASS. Before `b0613c36a93ff568fa019e4686712dfdddd02ed0ae5388ae7efb1ea21da4dd71`; After20 `4b599fe32240f02c1880d8b138ba2d0e7433a9c601684df46a0b8d38c6815ac5`; After10 `81f37b11d8b0291eaf55041567d453f795629fb9143be1f5834be7950b446893`; After50 `5c72a60379340e8f468a1d4a1816691b0cc97eaddae8d8468f5c925b75076e61`.
- Scenario 20/10/50 generated network target edge and both B8/B17 numeric values agree exactly; style 0.00 displays matching two decimals. Per output: ZIP CRC, POI reopen, merge/style/row/column/non-target cell checks PASS. Solver scenario balances assert process residual zero and external inflow/outflow 200 equal.
- `git diff --check` 및 `git diff --cached --check` PASS. No API runtime or browser E2E. DB integration skipped due missing isolated DB variables. LibreOffice/Excel PDF/PNG rendering for these new numeric-cell outputs not run.

### 미완료 / 재개 지점
- FLOW_CHANGE cell editor and network what-if are connected at internal server-owned synthetic service/test level, not yet through an authenticated controller endpoint, existing user-working-copy owner workflow, DB output storage or HTTP download. Existing proposal create/generic What-if API remains unchanged; no arbitrary user file/workbook is accepted by this new synthetic workflow.
- Actual flow-label mapping on all production sheets is unresolved; candidate strings remain REVIEW_REQUIRED. Numeric cells under test are TEST_ONLY only. DrawingML Shape text, formulas and actual-workbook cell editing unsupported.
- Actual physical connection evidence absent and official readiness remains `CALCULATION_UNAVAILABLE`; no actual process FLOW_CHANGE allowed. STEP 8.2 arrow connection remains `VISUAL_REVIEW_REQUIRED`.
- Next tasks, only after isolated DB/runtime approved: bind eligible user working copies by owner/SHA and reviewed flow-label Profiles, add proposal-scoped controller preview/generate/download, persist outputs only with mapping/readiness gate, run isolated DB+browser E2E, and separately visually inspect outputs. Do not apply migrations or promote actual readiness as part of this test-only slice.
- Current new/modified source/tests/docs are uncommitted; `target/test-artifacts/` outputs are generated ignored test artifacts. Selectively stage only the 8 related source/test/documentation files after final diff review. No commit has been created in STEP 8.3 yet.

---

## STEP 8.5 안정화 / STEP 8.6 매핑 검토 시작 기록 — 2026-11-03

### 재개 상태 및 기준 보존
- 실제 시작 branch `step7-7-wip-profile-add-20261101`; `git status --short`, untracked files, `git diff --check` 모두 clean. 실제 HEAD는 사용자가 기록한 직전 값과 달리 `631a4b0688ffe0f7324f738502958f1b413103ef` (`genspark auto-backup`)였고, `git show --stat HEAD`로 전 기록의 4개 변경 파일이 해당 자동 백업에 이미 포함됐음을 확인했다. 중복 코드 커밋은 만들지 않는다.
- 공식 15개 공정도와 원본 물질수지 XLSM은 읽기 전용. `process_map_dev`, 운영 DB, migration, 기존 PM2, `main`, deploy는 접근/변경하지 않았다. 기존 FLOW_CHANGE 및 STEP 8.2 시각 검토 경계 유지.

### 재검증 및 실제 산출물
- Flow mapping Controller/Workflow/Service: 9/9 PASS. 21개 관련 Surefire 클래스 회귀: 81 tests, failures 0, errors 0, skipped 0. 이 집계는 Profile ADD/MODIFY, PW-01-05 ADD, DELETE, PIPE_CHANGE, FLOW_CHANGE, What-if/solver, mapping 및 실제 XLSM export를 대상으로 한 선택 회귀이며 전체 Maven suite는 아니다.
- 최종 관련 테스트 명령은 `FlowMappingReviewActualDataExportTest`와 `Step7MaterialBalanceComparisonServiceTest`에 실제 XLSM/reference 경로를 제공해 실행했다. source-aware export 및 comparison에서 각 1건이 skip 없이 포함됨.
- 실제 source SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`; 3,082 input rows, 양 endpoint 후보 2,672, 한쪽 334, 후보 없음 76, 복수 위치 후보 2,362. 중복 endpoint 44그룹·95행, material type 공란 2,830, unit/period 공란 각 3,082.
- 저장 숫자 대 crosswalk 차이 0행, 표시 형식 차이 214행. 수치 및 display 문자열을 분리 보존; 공란·NaN·Infinity·비숫자 테스트도 추가해 상태를 구분한다.
- 새로운 실제 workbook 결과는 `validation-results/2026-11-03-step8-5/run-07/flow-mapping-review-actual.xlsx` (1,598,137 bytes), SHA-256 `60ddeed7056f8c5ccf221faa856c57e4e752ed31c1e0d6530f7dfad7ed43802c`. `validation.json`, `material-balance-comparison.xlsx`, `SHA256SUMS`가 같은 run에 있다. Manifest 및 XLSX ZIP check PASS; test에서 POI reopen, 9 sheets, source row/SHA, formula 0, 맑은 고딕/검정 확인.
- `run-05` 요청 산출물 SHA-256 `124b74ff308ce4b953b6c9f9af35fe6e271e90fb8dc622b530de0fd21b75944b` 및 기존 manifest도 재검증 PASS.
- 15개 공식 process 파일 SHA와 62-sheet checkpoint 내용은 확인했으나 DB fingerprint 재계산은 수행하지 않았다. 상태는 `CHECKPOINT_CONTENT_SHA_MATCHED_DB_FINGERPRINT_UNVERIFIED`.

### 웹/API 검토 범위 및 남은 검증
- 현재 authenticated routes: overview, candidate search, equipment occurrence, read-only multipart comparison, session-scoped rows pagination 및 CSV/JSON/XLSX download. Upload는 15 MB 제한, CSRF 요구; 결과는 사용자/session scoped 임시 캐시. 다운로드 응답에 `X-Review-SHA256` 제공. UI는 검색·필터/페이지 경쟁 응답을 무시하고 SHA header/bytes를 검증한다.
- 현재 단계에서 inline JavaScript 한 개를 HTML에서 추출해 `node --check`로 검사했다. Browser E2E/실제 브라우저 다운로드는 실행하지 않았으며 Spring+DB runtime download도 미검증.
- actual connector binding 0, VERIFIED flow mapping 0, physical connection `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`, readiness `CALCULATION_UNAVAILABLE`. STEP 8.2 `VISUAL_REVIEW_REQUIRED` 유지. 입력에 없는 단위·기간·증빙을 추정하지 않는다.
- DB-backed tests는 격리 DB 확인 전이라 실행하지 않았다. `process_map_dev`에 접속하지 않았다. 전체 Maven suite도 실행하지 않았다.

### 연속 개발 재개 지점
- HEAD `631a4b0`에는 기존 STEP 8.5 code/test 변경이 auto-backup으로 이미 포함됐으므로 그 변경을 다시 커밋하지 않는다. 이번에 README/CHECKPOINT STEP 8.5 내용만 별도 문서 commit 후 STEP 8.6 현황 카드와 quality filter, selected-filter XLSX report API의 미구현/기존 기능 경계를 확인·개선한다.
- STEP 8.6에서 새 기능이 추가되면 API/Exporter 기존 구현을 재사용하고 인증·CSRF·session ownership·output count·SHA·formula-free ZIP 검증을 적용한다. 브라우저/DB 검증이 불가능하면 선택 테스트와 분리해서 미검증으로 기록한다.
- 커밋은 구현과 검증 뒤 선별 stage한다. 공식 Excel, validation-results 산출물, 민감정보는 Git에 포함하지 않는다.

---

## STEP 8.6 최종 검증 및 STEP 8.7 진입 기준 — 2026-11-03

### 시작 HEAD 및 안전 경계
- 실제 branch `step7-7-wip-profile-add-20261101`, 시작 HEAD `84a7be0f0e798fa76b1c9c936ed89d9b7a521463` (`genspark auto-backup`). `FlowMappingReviewService`, `FlowMappingReviewWorkbookExporter`, `OperatorFlowMappingReviewController` 및 관련 두 workflow/controller test는 이미 해당 HEAD에 포함되어 있어 중복 코드 커밋 대상에서 제외했다. 기존 변경은 reset·삭제하지 않았다.
- `./mvnw -q -DskipTests test-compile` 성공. 기존 미커밋 index debounce 수정, README 내용, parity test를 검토 후 문서/관련 test와 index만 별도 선별 커밋 대상으로 둔다.
- 공식 공정도 15개·62시트와 입력 XLSM은 read-only. `process_map_dev`, 운영 DB, migration, 기존 PM2, `main` 및 배포 경로는 사용하지 않았다.

### 대시보드 / 필터 출력 정합성
- 대시보드 요약은 원본 분석 snapshot과 필터 결과에서 계산한다. 양/한쪽/후보 없음 endpoint 및 복수 위치 카운트는 hash-matched checkpoint/index가 확인된 경우에만 제공한다. 품질 그룹은 중복 가능해 합계로 전체 입력을 계산하지 않는다. 실제 VERIFIED 유량 매핑은 명시적으로 0이다.
- 페이지/필터 API와 XLSX/CSV/JSON export 모두 같은 서버 필터·검색 전체 행 집합을 사용한다. export는 현재 페이지만이 아니라 전체 조건 일치 행을 내보낸다. 추가 parity assertion은 pageSize 1, 전체 filtered count 2 조건에서 API count, CSV data rows, JSON `rows`/`exportedRows`, XLSX data rows 및 `ReviewExport.rowCount`를 일치 확인한다. CSV formula injection 보호와 XLSX 문자열/수식 셀 0 검증을 유지한다.
- UI는 inline script 1개를 추출해 `node --check` 통과, literal DOM ID reference 123개, 누락 0개. 품질 필터 변경 시 pending search debounce를 취소하고 page 0부터 재조회하며 request ID 검사를 유지한다. SHA header와 다운로드 bytes 불일치 또는 Web Crypto 부재 시 저장을 차단한다. 이는 정적 검사이며 Browser E2E 완료를 뜻하지 않는다.

### 실제 데이터 / 테스트 결과
- 기존 `run-08/flow-mapping-review-actual.xlsx` SHA-256은 기록값 `5e2444a9df8abc9ccf4e53e8c9afb93e35284ef37a6b374a92564441082b89f3`와 일치했고 파일을 덮어쓰지 않았다.
- 신규 read-only actual-data run `validation-results/2026-11-03-step8-5/run-11/`: XLSX 1,602,153 bytes, SHA-256 `d327c69cd4fa78d0c06485e28d80d62b82754bff0b7da14ba7efdc6994865488`; comparison XLSX SHA-256 `3a6a7d464d8bd24f15a641afdb0f629509f6d41ad9243f23222fef5d89978890`. XLSX/JSON/comparison workbook manifest 3/3 검증, ZIP integrity, POI re-open, 9 sheet, source SHA/행 추적, 0 formula cells, 맑은 고딕·검정 확인. 원본 SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`, 3,082 rows, 15 official file hashes 및 62-sheet checkpoint content hash 대조. DB fingerprint 재계산은 하지 않았고 상태는 `CHECKPOINT_CONTENT_SHA_MATCHED_DB_FINGERPRINT_UNVERIFIED`.
- 선택 관련 회귀 21 classes: **76 tests, failures 0, errors 0, skipped 2**. skip 2개는 기본 실행에서 경로 property가 없는 실제 파일 테스트다. 해당 두 메서드를 실제 `/home/user/uploaded_files` 및 read-only reference/crosswalk 경로로 별도 실행하여 `FlowMappingReviewActualDataExportTest` 1/1, `Step7MaterialBalanceComparisonServiceTest` 4/4 PASS(총 5 tests, 실패/오류/skip 0). regression 목록은 API mapping/controller, ADD/MODIFY/DELETE, PIPE_CHANGE/FLOW_CHANGE, solver/What-if, numeric 및 review를 포함한다.
- DB-backed integration tests 및 전체 Maven suite는 격리 DB가 확인되지 않아 미실행. Browser E2E 및 실제 브라우저 다운로드도 미실행. Maven 테스트는 `process_map_dev` 또는 운영 DB에 연결하지 않았다.

### 상태 및 STEP 8.7 범위
- 실제 물리 연결 증빙은 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`, Connector binding 0, VERIFIED flow mapping 0, readiness `CALCULATION_UNAVAILABLE`, STEP 8.2 `VISUAL_REVIEW_REQUIRED`를 유지한다. 문자열 ID·도면 위치·Connector anchor로 연결을 확정하지 않는다.
- STEP 8.7은 기존 `담당자 검토 입력 양식`과 exporter/service를 재사용하는 파일 기반 검토 초안 입력·검증·재출력으로 제한한다. 안정 식별은 원본 SHA + sheet + row, endpoint snapshot을 대조하고, `SOURCE_SHA_MISMATCH`, `SOURCE_ROW_MISMATCH`, `ORIGINAL_EQUIPMENT_ID_CHANGED`, `DUPLICATE_REVIEW_ROW`, `INVALID_ROW`, `MISSING_FROM_UPLOAD`로 결과를 구분한다. 업로드는 요청/세션 단위로 처리하며 DB 저장·공식 원본 수정·실제 VERIFIED 승격·PIPE/FLOW_CHANGE 생성은 금지한다.
- STEP 8.6 선별 변경은 `README.md`, `CHECKPOINT.md`, `src/main/resources/static/index.html`, `FlowMappingReviewWorkflowTest.java`에 한정한다. `validation-results/`는 생성 검증물이며 Git stage 대상이 아니다.

---

## STEP 8.7 파일 기반 초안 왕복 검증 — 2026-11-03

### 시작 상태 및 복구
- 실제 실행 시작 branch `step7-7-wip-profile-add-20261101`, HEAD `e936c16903d99a4871bc0f8eb4835075983dde0a` (`genspark auto-backup`). 첫 `git status --short`에는 `FlowMappingReviewWorkflowTest.java`만 수정으로 표시됐고 untracked 파일은 없었다. STEP 8.7 production source와 기존 web UI/API 변경은 이미 HEAD에 포함되어 있어 중복 commit하지 않는다.
- 처음 `./mvnw -q -DskipTests test-compile`은 workflow test fixture의 Java `Map.of(...)` 10-pair overload 제한 때문에 실패했다. `Map.ofEntries(...)`로 고쳐 재컴파일 PASS. 이후 합성 XLSX round-trip에서 CSV formula protection apostrophe escaping된 원본 설비 ID를 충돌로 오판하는 문제가 드러나, source ID 비교도 CSV escape-aware match를 사용하도록 고쳤다. 이를 CSV/XLSX 재import 테스트로 재검증했다.
- 기존 변경을 reset/삭제하지 않았으며 branch/main/production/database/PM2/deploy는 변경하지 않았다.

### STEP 8.7 구현 및 검증
- 기존 `/`의 연결·유량 매핑 검토 section에 양식 XLSX/CSV download, 5MB CSV/XLSX 업로드, 결과 summary/filter/preview, CSV/JSON/XLSX 재출력 UI가 연결되어 있다. Controller routes: `GET .../{analysisId}/draft-template?format=...`, CSRF 필요 `POST .../{analysisId}/draft-import`, `GET .../{analysisId}/draft-preview/{previewId}`, `GET .../{analysisId}/draft-preview/{previewId}/export?format=...`.
- 29열 file contract에서 원본 SHA, sheet, row ID/number, endpoint IDs 및 source candidate/quantity/material/unit/period snapshot을 대조한다. XLSX formula cell 및 external/unsafe OOXML 입력을 차단하고, CSV UTF-8/BOM/quotes/newlines/formula-like input을 검증한다. SHA/row/equipment changes, duplicate, invalid status/quantity는 disposition으로 분리한다. 입력 snapshot은 불변으로 보존하며 `VERIFIED`로 승격하지 않는다.
- 합성 XLSX 및 CSV 각각 생성→의견 입력→Import→결과 재출력→재Import를 실행했다(입력 3행). CSV quote/comma/newline 왕복, SHA mismatch, source row mismatch, equipment ID change, duplicate row, invalid status/quantity, CSV formula-like input, XLSX formula cell 차단, 다른 사용자/세션 접근, logout cache cleanup 및 source bytes SHA 불변을 확인했다. OOXML ZIP entry read/CRC 및 POI reopen 성공, 결과 XLSX formula cells 0.
- 가장 최근 STEP 8.7 targeted regression: **22 Surefire classes, 87 tests, failures 0, errors 0, skipped 0**. 대상은 API mapping/controller·workflow/service, ADD/MODIFY/DELETE/Profile, PIPE_CHANGE/FLOW_CHANGE, What-if/solver/material balance 및 관련 inventory/Excel unit tests. `./mvnw -q -DskipTests test-compile` PASS. 전체 Maven suite 및 DB-backed integration은 실행하지 않았다.
- `FlowMappingReviewWorkflowTest`가 합성 source bytes SHA가 실행 전후 동일한지 확인한다. import preview는 `persisted=false`, `verifiedFlowMappingCount=0`, `officialReadiness=CALCULATION_UNAVAILABLE`, physical evidence `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`다. 검토완료 상태는 실제 승인/VERIFIED mapping을 의미하지 않는다.

### UI/API 및 브라우저 검증 범위
- `src/main/resources/static/index.html`의 마지막 inline JavaScript를 추출해 `node --check` 통과. literal `byId`/`mappingById` 참조 131개, DOM ID 누락 0; 필수 STEP 8.7 controls와 API endpoint path/controller mapping을 확인했다.
- `OperatorFlowMappingReviewControllerTest` 7/7 PASS: unauthenticated/CSRF denial, authenticated upload response, session-scoped preview/export, read-only headers 및 output SHA 계약을 검증했다. Service/workflow tests는 다른 owner/session 및 logout 이후 거부를 검증한다.
- 격리 DB 및 browser automation runtime이 없어 실제 Spring API runtime, 로그인 후 파일 선택·업로드·다운로드 E2E는 미실행. 기존 PM2/운영 서버를 열거나 변경하지 않았다. 정적 JS/DOM 및 MockMvc 검증을 browser E2E 성공으로 표현하지 않는다.

### 실제자료 시연 및 산출물
- 새 비덮어쓰기 run: `validation-results/2026-11-03-step8-7/run-final-071742-6658/`.
- 기존 STEP 8.6 read-only run-11의 실제 데이터 사본을 동봉했다: 9시트 `flow-mapping-review-actual.xlsx` SHA-256 `d327c69cd4fa78d0c06485e28d80d62b82754bff0b7da14ba7efdc6994865488`; `material-balance-comparison.xlsx` SHA-256 `3a6a7d464d8bd24f15a641afdb0f629509f6d41ad9243f23222fef5d89978890`; `validation.json`. ZIP/POI/9-sheet 검증 결과는 이전 STEP 8.6 run-11 실제 검증을 재사용했다.
- `demo.html`은 정적 read-only 화면이며 실제 XLSM 요약 3,082행, 기존 15개 공식 공정도/62개 시트 checkpoint, endpoint/위치/중복/공란 통계 및 결과 Excel 링크를 표시한다. `demo-screen.png`는 SVG로 렌더한 정적 이미지이지 browser screenshot은 아니다. 실제 행 예시와 aggregate workbook은 구분한다.
- `synthetic-review-input.xlsx/.csv`, `validated-review-output.xlsx/.csv`, `import-validation.json`은 이번 STEP 8.7 테스트가 실제 생성한 합성 왕복 결과다. `DEMO_README.md`에는 실행 안내, 실제/합성 분리, 미검증 범위가 기록돼 있다. `SHA256SUMS` 16개 항목을 재검증해 전부 `OK`를 받았다. 핵심 해시: synthetic XLSX `39a47620d605a4cf26b279d58b9ad546c862129178699eb837aee40d4a746ebe`; validated XLSX `fdc89f29a8ba3276ae5c13270281915fa8911b94c36839f204bb8a8d0248ad13`; static PNG `ccbad35eb0410b9f962fc07315b871e948ec553810cec4100c40f691614c5d5a`.
- 이 run의 실제 검토 XLSX, 비교 XLSX, synthetic input/output XLSX 4개 모두 ZIP 검사 통과. POI round-trip test는 synthetic XLSX를 재개봉하고 수식 없는 export를 확인했으며 실제 workbook는 9시트로 다시 열리는 것을 확인했다. XLSX의 `openpyxl` 스타일 경고는 검증 방식에서 기본 style 부재를 알리는 경고이며 원본 XLSX 바이트는 수정하지 않았다.
- 원본 XLSM SHA-256은 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`. 공식 15개 XLSX/62시트 수치 및 3,082행 집계는 기존 STEP 8.6 checkpoint snapshot이다. DB fingerprint는 이번 작업에서 다시 계산하지 않았다.

### 안전 경계 / 남은 일
- 실제 VERIFIED flow mapping 0, `CALCULATION_UNAVAILABLE`, physical connection evidence 없음 및 STEP 8.2 `VISUAL_REVIEW_REQUIRED`를 유지. 실제 파일에 임의 의견을 넣지 않았고, 연결·유량을 추정하지 않았다.
- 공식 공정도 15개 및 원본 물질수지 DB XLSM은 read-only. `process_map_dev`, 운영 DB, migration, 기존 PM2, `main`, 배포는 사용하지 않았다.
- Repository는 Java 17/Spring Boot/MariaDB여서 Hono/Cloudflare Pages preview/deploy 대상이 아니다. 이번에는 외부 배포를 하지 않았다.
- 최종 게이트: run manifest 16/16 및 XLSX ZIP 재검증 PASS, README/CHECKPOINT diff·`git diff --check` 검토 완료. HEAD `e936c16`의 자동 백업 코드/수정 이력을 중복 stage하지 않고 이 실행에서 실제 변경된 DTO enum/UI filter/test/service escape fix/docs만 선별 stage한다. DB/browser E2E는 환경 부족으로 미검증 상태를 유지한다.

---

## PILOT-01 — 실제 통합공정도 TEST_ONLY 편집 확대·마감 기록 (2026-11-04)

### 시작 상태 및 공식 원본 보호
- 현재 branch `step7-7-wip-profile-add-20261101`. 사용자가 기재한 `eb0dd953` 이후 자동 backup commit `0194d2ab11e609f42dafb50ea11adb5b92298bed`가 생성되어 기존 `Pilot01RealWorkbookTest.java`를 이미 포함했음을 `git show --stat HEAD`로 확인했다. HEAD는 강제 변경하지 않았으며 기존 자동 백업 이력을 보존했다.
- 실행 시작 worktree clean, untracked none, `git diff --check` PASS. 공식 `data/reference/SHA256SUMS` 15개 공정 파일 모두 OK. P-02 official SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, P-01 official SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`는 편집 전후 동일.
- `DEMO-03` 미수정. `process_map_dev`, 운영 DB, migration, 기존 PM2, `main`, 배포 경로 사용/변경 없음.

### P-02 실제 공식 도면 복사본 MODIFY
- P-02 / `P-02-01(TOP)` / `I-PP21005` / box `I86:Z93` / cell `I89`. Original `131 MULTI SORTER#1`, 합성 TEST_ONLY replacement `SYNTHETIC_TEST_ONLY 131 MULTI SORTER#1`.
- official-copy Before SHA 및 공식 원본 SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`. After SHA `9e3a4a5087567656bc3a938c69ca30c0ea902b46fd13d6166846a848d867fb50`.
- `modifyBoxFieldsTestOnly`를 재사용해 격리 출력 생성. POI reopen 및 ZIP 검사 PASS. OOXML package에서 `xl/worksheets/sheet1.xml` 하나만 바뀌었고 worksheet XML의 target I89 셀 subtree 이외는 동일. 셀 style 동일, merged range 333개 및 `U582:Z588`, 인쇄 영역/페이지 설정, row heights, column widths 보존.
- `xl/drawings/drawing1.xml` byte-identical; 266 Connector + 19 Shape (285 drawing objects) 모두 유지. Connector anchor가 변경되지 않아 물리 관계를 새로 만들거나 증명하지 않는다.
- 기존 Profile `P-02-01-TOP-V1`의 working SHA `c3379f2e...c881a3d2`는 현재 official copy SHA와 달라 Profile-based ADD를 시도 출력하지 않았다. ADD `PROFILE_WORKING_SHA_MISMATCH`로 차단. DELETE assessment `REVIEW_REQUIRED`, DrawingML 영향 3개 및 주변 텍스트 3개로 출력 없음.

### PDF/PNG 검수
- 기존 P-02 Before/After PDF를 재사용; 둘 다 71 pages / A3. Page 1의 같은 PDF 좌표영역에서 `I89` row를 찾아 red outline을 둔 comparison image를 생성했다. Full overview와 enlarged detail은 `validation-results/pilot-01-20261104/final-run-20261104-r1/`.
- PDF 페이지 수/규격 및 대상 페이지 비교는 수행했지만 71개 전체 PDF pages를 서로 시각 대조한 것은 아니다. Comparison annotation은 파생 PNG에만 있으며 Excel/PDF 원본 render를 수정하지 않았다.

### 다른 구조 P-01 추가 공정
- 읽기 전용 구조 분석에서 `P-01-01(TOP)` / `I-PP11001` box `J4:AA20`은 P-02 8-row box와 다른 17-row geometry. `J7` (`40㎥ PULPER (TOP, IV)`)는 non-merged, style 100, selected cell을 가로지르는 DrawingML anchor 0건. 시트의 56개 DrawingML object는 56 Connector, 0 Shape.
- 별도 source copy에서 `J7`만 `SYNTHETIC_TEST_ONLY 40㎥ PULPER (TOP, IV)`로 시험 편집했다. P-01 official SHA `426cbd28...faf391` 불변. After SHA `f0f19a674b809d6ef5ba34e72fe1fd4d7e4afbc4ea2d1eea663f3dab83a5145f`.
- POI reopen/ZIP entry 검증, worksheet package part 외 byte-identical, same worksheet XML target-cell-only, target style/merges/print setup/row height/column width 보존. `xl/drawings/drawing1.xml` byte-identical; 56 Connector 보존. P-01 Profile는 `REVIEW_REQUIRED` 그대로 두고 승격하지 않았다.
- P-01 Before/After PDF 각 32 pages / A3. Page 1의 exact J7 영역에 동일 위치를 표시한 overview/detail PNG를 생성해 확인했다. P-01 PDF의 모든 페이지를 서로 시각 검수했다는 의미는 아니다.

### 검증 산출물 및 테스트
- `validation-results/pilot-01-20261104/PILOT_README.md`, `pilot-selection.json`, `PILOT-01-final-validation.json`, `SHA256SUMS`에 시험 범위·SHA·차단 사유·실행 결과 기록. P-02 primary result는 기존 root xlsx/pdf/png 재사용. P-02 comparison PNG는 `final-run-20261104-r1/`; JSON verification은 `final-run-20261104-r2/`. P-01 Before/After xlsx/pdf/png/JSON은 `final-run-20261104-r3-p01/`.
- 최종 선택 회귀: 11 Surefire classes, **43 tests, failures 0, errors 0, skips 0**. P-02 profile/editor/delete/add/legacy tests group 30/30 PASS; OOXML roundtrip/API DTO/PIPE_CHANGE/FLOW_CHANGE group 12/12 PASS; P-01 distinct-process TEST_ONLY test 1/1 PASS. P-02와 P-01 actual-workbook tests는 별도 명령으로도 pass 재확인했다. 이는 선택 회귀이며 전체 Maven suite 통과 보고가 아니다.
- DB-dependent `OperatorUserWorkingCopyIntegrationTest` (`DB_URL`) 및 `OperatorDiagramModifySyntheticIsolationTest` (`PROPOSAL_TEST_DB_URL`)는 환경/금지 조건 때문에 실행하지 않았다. API DTO contract test는 MockMvc standalone으로 실행했지만 실제 Spring+DB Preview→Generate→Download는 미검증.
- 첫 P-02 test harness 재실행은 이미 존재한 result JSON을 단순히 “덮어쓰기 금지”로 실패 처리했다. 기존 결과를 덮어쓰지 않고 계산 결과 JSON과 같은지 비교하도록 고친 후 관련 회귀를 다시 실행해 통과했다. 테스트 대상 편집 결과는 변경되지 않았다.

### 확정 safety status 및 다음 개발 재개점
- TEST_ONLY 문자열 변경만 지원 증명. 실설비 운영 변경 아님. actual VERIFIED flow mapping 0, material balance readiness `CALCULATION_UNAVAILABLE`, PIPE_CHANGE `VISUAL_REVIEW_REQUIRED` 유지.
- Profile/working-copy SHA gate, user ownership/auth, feature flag, DB 저장/다운로드 경계는 우회하지 않았다. 실제 사용자 workflow는 미검증.
- 다음은 isolated DB/browser를 마련한 뒤 기존 API 경로에서 owner-bound working copy SHA를 유지한 Preview→Generate→Download 계약을 검증하는 것이다. P-02 ADD는 동일한 검증 working-copy bytes가 제공되기 전까지 계속 차단. DELETE는 DrawingML 영향 검토 전까지 계속 차단. 실제 연결·유량/물질수지는 별도 현장 증빙 전까지 추정하거나 승격하지 않는다.

## PILOT-02 — 실제 MODIFY 결과 웹 시연 및 통합 경로 검토 (2026-11-04)

### 시작 상태 및 원본 보호
- 시작 branch `step7-7-wip-profile-add-20261101`, HEAD `b166842ce527587177ffa61d587cfafa881dad4a` (PILOT-01 완료 commit), 작업 트리 clean, `git diff --check` PASS. 강제 reset, branch 변경, DB 접근, migration, PM2, main, 배포 변경 없음.
- `data/reference/SHA256SUMS` 16개 전체(공식 공정도 15개 및 master 목록 1개) 재검증 PASS. 시험 후에도 manifest 해시 일치; P-02 source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, P-01 `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391` 보존.
- 기존 DEMO-03 폴더/package 및 PILOT-01 산출물은 덮어쓰지 않음. PILOT-02 static package는 `validation-results/pilot-02-20261104/`에 분리하고 private validation 산출물은 Git에서 제외.

### 브라우저 결과 가시화 및 다운로드
- PILOT-01의 사전 생성 Excel 결과를 표시하는 로컬 loopback-only PILOT-02 시연을 구성. P-02/P-01 선택, 공정/시트/설비/박스/셀/Before/After 표시, 나란한 PNG, 확대 비교, Before/After Excel SHA 체크 다운로드를 포함. 페이지에 브라우저 실시간 Generate가 아니라는 경계를 명시.
- Chromium 156.0.8078.4 / Playwright 1.64.1 실제 브라우저 테스트 PASS. P-02/P-01 전환, before/after 이미지 6개 정상 로드, 상세 확대, 파일 다운로드 4개, DEMO-03 actual review XLSX와 material-balance 비교 XLSX 다운로드 SHA를 검증. 기존 I-PP11001 search는 8행, 초기화는 3,082행. 외부 요청 0, console/page error 0; unauthenticated 401, POST 405, traversal 404.
- Before/After 다운로드 SHA는 P-02 `28716f…f4f106ab175a2348d9f76` / `9e3a4a…d867fb50`, P-01 `426cbd…09faf391` / `f0f19a…3a5145f`와 일치. 상세 파일 SHA 및 캡처는 `validation-results/pilot-02-20261104/PILOT02_SHA256SUMS` 및 `TEST_RESULTS.json`.
- 시각 PNG는 PILOT-01의 대상 영역 비교 결과를 그대로 재사용. P-02 PDF 각 71쪽, P-01 각 32쪽의 전체 페이지 간 시각 동일성은 검증하지 않음.

### Excel 및 기존 MODIFY 코드 검토
- 실제 P-02/P-01 Before/After Excel 네 파일 ZIP integrity, Apache POI reopen 및 PILOT 엔진 테스트 PASS. 변경된 OOXML part는 `xl/worksheets/sheet1.xml` 하나, 대상 `I89`/`J7` cell subtree만 변경. 기존 merge/style/print layout 등 보존. P-02 DrawingML byte-identical (266 Connectors + 19 Shapes), P-01 byte-identical (56 Connectors).
- P-02 Profile `P-02-01-TOP-V1` required working SHA는 `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`; 실제 P-02 official copy SHA와 불일치. 따라서 Profile MODIFY 경로는 이 작업본으로 열 수 없으며 guard를 우회하지 않음. P-01 Profile은 `REVIEW_REQUIRED` / `STRUCTURE_ANALYZED`, verified working SHA 없음; Profile 미승격.
- `DiagramModifyService` MODIFY Preview/Generate의 `proposals.create`/`recordTestOnlyOutput` 호출은 확인되지 않음. `ChangeProposalService`의 append-only proposal 경로를 MODIFY 통합으로 과장하지 않음. Preview는 별도 fingerprint가 없고 session-bound preview ID, 작업본 SHA, current Profile mapping 및 engine의 expected cell value 검증으로 stale 상태를 재확인.
- 기존 `UserWorkingCopyService`, profile exact SHA, ownership/session, controller authentication/CSRF, generate-time stale checks, output SHA 헤더 및 download 세션 검사를 확인. `USER_WORKING_COPIES_ENABLED=false` 유지. MODIFY API에 시험 도면을 연결하거나 새 API 경로를 추가하지 않음.
- 기존 source에서 P-02/P-01 user working copy bytes와 owner-bound DB row를 매칭한 자료, Profile-approved SHA 및 DB ID가 없음. 이 정보가 확보되어야 실제 Preview→Generate를 API로 검증 가능. 실제 사용자작업본 HTTP 응답 bytes/DB BLOB hash 비교 또한 아직 미수행.

### 테스트 및 다음 재개 지점
- `./mvnw -q -DskipTests test-compile` PASS. DB 비의존 Surefire 선택 10 classes, 37 tests, failures 0 / errors 0 / skipped 0 (MODIFY/Profile/engine, P-01/P-02, ADD, DELETE gate, controller DTO, FLOW/PIPE synthetic 회귀). XML 원본은 `target/surefire-reports/`. 전체 Maven suite는 미실행.
- DB-backed `OperatorDiagramModifyIntegrationTest`, `OperatorDiagramModifySyntheticIsolationTest`, `OperatorUserWorkingCopyIntegrationTest`는 실행하지 않음. 격리 DB 변수가 unset이고 application 기본은 금지된 `process_map_dev`; Spring+DB runtime 성공으로 보고하지 않음.
- 결과: 실제 VERIFIED 매핑 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`. ADD는 정확한 증빙 작업본 SHA 전까지 차단, DELETE는 DrawingML 영향 검토 전까지 차단. 재개 시 먼저 격리 DB와 P-02/P-01의 증빙된 immutable working-copy SHA/bytes/owner link를 확보하고, 기존 flag/auth/CSRF/ownership/Profile gate를 유지한 targeted Spring test로 진행.
- 시연 안내와 API 근거는 `validation-results/pilot-02-20261104/PILOT_README.md`, `MODIFY_API_REVIEW.md`, `PILOT02_BROWSER_RESULTS.json`, `TEST_RESULTS.json`, `PILOT02_SHA256SUMS`에 기록.

---

## PILOT-03 재개/완료 기록 — 입력값 기반 TEST_ONLY MODIFY (2026-11-04)

### 시작 상태 및 격리 판단
- 실제 시작 Bash: branch `step7-7-wip-profile-add-20261101`, HEAD `034efc6e0f47eeee1efa5a715cd7e71bd803f4ef`. 기존 tracked 변경은 없었고, 이미 작성된 `Pilot03DynamicModifyIntegrationTest.java` 한 파일만 untracked였다. `git diff --check` 및 test-compile PASS.
- DB 격리 불명: test DB 환경변수 unset, Docker 환경 없음, MariaDB listener의 DB 이름/분리성 미확인. DB 연결하지 않고 `process_map_dev` 및 운영 DB를 피했다. DB 비의존 test-scope MockMvc harness만 사용.
- 공식 기준 manifest는 `data/reference/` working directory에서 검증: 15개 공정도 + `전체 목록.xlsx`, 16/16 OK. 처음 repository root에서 실행한 상대경로 명령은 manifest 파일 경로를 잘못 해석해 실패했으며, 자료 불일치가 아니다. 정확한 기준 디렉터리에서의 재실행을 공식 결과로 채택.

### 동적 입력 → Preview → Generate → Download
- 기존 PILOT-02의 고정 Before copy 두 개를 매 요청마다 source SHA와 target text로 검사했다. P-02 `P-02-01(TOP)` / `I-PP21005` / `I86:Z93` / `I89`, SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`; P-01 `P-01-01(TOP)` / `I-PP11001` / `J4:AA20` / `J7`, SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`.
- Test-only `DynamicWorkflow`는 P-01/P-02만 허용하며 client cell/path는 받지 않는다. Preview fingerprint에는 process/source SHA/sheet/equipment/cell/before/after가 포함된다. Generate에서 owner/session-bound Preview identity, fingerprint, pinned source SHA 및 현재 셀값을 다시 확인하고 기존 `DiagramBoxChangeEngine.modifyBoxFieldsTestOnly`로 새 output bytes를 만든다. Profile working-copy SHA guard를 수정하거나 우회하지 않았다.
- 서로 다른 입력 문구 네 개와 별도 세션 입력 한 개로 신규 XLSX 총 5개를 생성. 각 결과는 `CREATE_NEW` 방식, SHA prefix 기반 파일명이며 기존 파일 충돌 시 bytes를 비교한다. POI/OpenPyXL로 target cell, SHA/bytes를 확인했고 Download MockMvc response body가 생성 bytes와 같으며 `X-Output-SHA256`가 실제 response SHA와 일치한다. 각 input-to-output SHA는 `validation-results/pilot-03-20261104/TEST_RESULTS.json` 및 `SHA256SUMS`에 기록.
- 로그인 endpoint/service 로직, CSRF, 익명 거부, 타 세션 Preview 재사용 거부, session ownership, forged Preview/source SHA tamper, invalid input/formula-like text/no-op 차단을 검사. test controller의 기본 생성자는 OFF이며 disabled request는 404. 테스트 본 흐름에서만 명시적으로 활성화. Controller는 test class 내부라 production runtime에는 등록되지 않는다.
- 기존 `OperatorSessionController`와 `OperatorLoginService`를 standalone MockMvc로 호출했지만 `JdbcTemplate`은 mock이며 실제 Spring Boot+DB runtime, DB 저장 이력, 운영 controller/HTTP path는 검증하지 않았다. Chromium/브라우저 UI도 연결하거나 실행하지 않았다. PILOT-02 read-only 시연 패키지는 수정하지 않음.

### Workbook 보존 및 결과
- 모든 5개 XLSX: ZIP integrity 및 POI reopen PASS, target value/string type/style 일치, merge/row/column/print settings 보존. package part 차이는 `xl/worksheets/sheet1.xml`만 허용. 변경 셀 subtree를 정규화하면 나머지 worksheet XML은 동일. DrawingML part byte-identical; Shape/Connector inventory 보존. Source copy hash는 기준값 그대로 유지.
- 결과 경로 `validation-results/pilot-03-20261104/`: 5개 신규 XLSX, `TEST_RESULTS.json`, `PILOT_README.md`, `SHA256SUMS`. 디렉터리 mode 700, 내부 파일 mode 600. `SHA256SUMS` 7개 항목, 전부 재검증 OK. 결과 산출물은 gitignore 대상이며 Git stage에 넣지 않는다.
- 별도 신규 입력 결과와 PILOT-01의 앞선 합성 문자열 수정은 구분한다. 이번에 확인한 문구는 A/B별 동적 입력으로 각자 새로운 XLSX가 만들어진 결과다. Excel 데스크톱 렌더/인쇄 검수는 미실행.

### 회귀·코드·변경 제안 경로
- 마지막 test source 변경 후 `./mvnw -q -DskipTests test-compile` 및 11개 selected Surefire class 실행 결과: **42 tests, failures 0, errors 0, skipped 0; BUILD SUCCESS**. 포함: PILOT-03 5, P-01/P-02 actual workbook 2, Profile MODIFY 1, box engine 6, Profile/Legacy ADD 12, DELETE gate 4, flow-change 4, profile-status 1, controller request 2, PIPE security 5. 전체 Maven suite는 실행하지 않았다.
- `DiagramModifyService`의 일반 Preview/Generate는 `ChangeProposalService` append-only proposal create/record와 자동 연결되지 않음을 확인했다. PILOT-03 test result도 proposal 이력으로 저장하지 않는다. 운영 연계 시 제안 snapshot과 사용자/대상, source·working SHA, Preview fingerprint, 전후값, output SHA 및 다운로드 소유권/감사 이력을 기존 proposal/output 경로에 결합하는 별도 설계와 격리 DB 통합 테스트가 필요하다. 이번에는 migration/DB 저장 구조를 만들거나 임의 변경하지 않았다.

### 안전 상태 및 남은 장애요인
- 공식 Excel 15개 및 전체 목록 원본 변경 없음; `process_map_dev`/운영 DB 미접속; migration/PM2/`main`/배포 변경 없음. `USER_WORKING_COPIES_ENABLED` 기본 OFF 유지, 실제 ADD/DELETE 차단.
- 실제 VERIFIED 유량 매핑 0, physical connection 증빙 없음, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. 수량/연결/유량을 만들거나 추정하지 않았다.
- 실공정 적용 blocker: 승인된 immutable user working copy와 owner link/SHA 부족, 기존 Profile exact SHA gate 및 P-01 Profile `REVIEW_REQUIRED`, 격리 DB runtime/API 저장·다운로드 부재, 변경제안/출력 감사이력 통합 미구현, 실제 UI/브라우저 E2E 미검증, 사용자/현장 변경 승인 필요.
- PILOT-03 코드·README·CHECKPOINT만 선별 stage/commit한다. 실제 XLSX와 validation manifest는 Git에 포함하지 않는다. `main` 병합/운영 배포는 하지 않는다.

## PILOT-04 마무리 검증 기록 — 실제 HTTP·Chromium·Excel A/B (2026-11-04)

### 시작 상태 및 범위
- 요청에서 직전 HEAD `ca688d0`를 제시했으나 실제 Bash 시작 시 branch는 `step7-7-wip-profile-add-20261101`, HEAD는 `6811f05523e01bd5bbb2b2e43f02111694c85e49` (`genspark auto-backup`)였다. `ca688d0`는 바로 이전 이력에 존재했다. 실제 시작 시 수정 상태는 `src/test/resources/pilot04.html` 변경과 신규 `Pilot04DownloadedWorkbookAuditTest.java`뿐이었다. reset/clean은 수행하지 않았다.
- 최초 `git diff --check`, `./mvnw -q -DskipTests test-compile` PASS. TEST_ONLY HTTP server는 `src/test/java` 내 격리 main으로 시작했고 `process_map_dev`/운영 DB는 사용하지 않았다.
- 기존 `HTTP_RESULTS.json`의 24개 PASS 및 P-02 이전 결과 XLSX SHA `225629856cd52e2fbfe5b663dc69b742ce45cf7ef738857d6725072ebda2eb4b`를 보존했다. 이 기존 결과는 이번 A/B 생성으로 대체하지 않았다.

### HTTP Preview → 실제 Generate → Download
- 현재 서버 출력 및 `ss`에서 확인한 ephemeral loopback port `33579`를 사용했다. 서버 코드/엔진 allowlist에 고정된 두 source copy만 선택하며 로그인·token header·세션·CSRF 절차를 변경하지 않았다.
- 신규 run `validation-results/pilot-04-20261104/http-ab-20261104T082949Z/HTTP_AB_RESULTS.json`: P-02/I89 A `SYNTHETIC_TEST_ONLY PILOT04 P02 A` SHA `9a80697b170245dc428f125525f4092bb276a5f17bb4a281fd3de8cd74aaa3ba`; B SHA `0153171112867b4cb3694bb48db145442816e7b404997757da255856f47fd1a6`. P-01/J7 A `SYNTHETIC_TEST_ONLY PILOT04 P01 A` SHA `be1ac586dc7af3d11cad26974e9d5c71cc869cfd2d34201c567d9567c50e0873`; B SHA `229c2abbe2ce3158895de0cc13295986cd5e6694eb202a8056d2ca5e3e0e2e95`.
- 4/4 Preview/Generate/Download HTTP 200. 각 server output SHA, download response SHA header, 저장된 HTTP bytes SHA 일치. POI가 확인한 I89/J7 값은 입력 문자열과 정확히 일치하며 A/B output SHA가 서로 다르다. 원본 source copy SHA P-02 `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, P-01 `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391` 불변.

### Chromium 진단 및 브라우저 E2E
- 과거 35069를 현재 포트로 가정하지 않았다. 127.0.0.1:35069는 refused였고 실제 서버는 33579. 브라우저·서버·shell network namespace 동일(`net:[4026531840]`) 및 직접 Chromium navigation HTTP 200 확인; 기존 Python requests relay는 제거했다. 브라우저 페이지/상대 API URL은 same-origin 직접 연결.
- 직접 Chromium 첫 시도는 A/B 4개 다운로드까지 끝났으나 자동 favicon 요청 404가 console error로 잡혔다. TEST_ONLY HTML에 빈 data favicon을 선언하고 `test-compile` 후 재실행. 최종 `CHROMIUM_RESULTS-20261104T084003Z.json`: Chromium 156.0.8078.4, P-02 A/B→P-01 A/B 각 입력·Preview·Generate·실제 다운로드 PASS 4/4, response 15개 모두 정상 범위, console/page error 0, 캡처 9장 (`browser-captures/20261104T084003Z/`). 세션 cookie/CSRF를 그대로 사용했고 보안 우회 없음. 최종 파일 SHA는 위 HTTP output SHA와 모두 일치.
- 브라우저 인증 경로의 401과 미등록 경로 404는 기존 24개 HTTP 케이스에서 별도로 구분했다. 토큰/암호/CSRF/cookie/preview ID를 결과 report나 캡처 경로에 노출하지 않았다.

### OOXML/회귀 검증
- 신규 HTTP 4개 및 Chromium downloaded 4개를 `Pilot04DownloadedWorkbookAuditTest`로 검사: ZIP integrity, Apache POI reopen, actual target value/type/style, 대상 이외 worksheet XML 정규화 동등, worksheet/merge/row heights/column widths/hidden/print setup 보존, package part inventory 보존, 유일한 변경 part `xl/worksheets/sheet1.xml`, DrawingML/media byte-identical, Shape/Connector inventory 보존, source baseline SHA 불변 모두 PASS. Chromium 파일은 해당 HTTP XLSX와 byte-identical.
- DB 비의존 선택 Surefire 16 classes / **54 tests, failures 0, errors 0, skipped 0**, test-compile PASS. 포함: P-01/P-02, PILOT-03/04, Profile MODIFY 및 Profile/Legacy ADD 제한, BOX_ONLY DELETE gate, PIPE_CHANGE/FLOW_CHANGE, request/auth/CSRF 관련 회귀. 정확한 class별 수치는 `validation-results/pilot-04-20261104/TEST_RESULTS.json`과 Surefire XML.
- 이전 HTTP 24 + 신규 HTTP A/B 4는 별도 기록. 전체 Maven suite는 실행하지 않았다. Spring+DB 통합 테스트는 기본 datasource가 금지된 `process_map_dev`일 수 있어 실행하지 않았으며 운영 Spring+DB API와 DB 변경제안 저장은 미검증.

### 결과·안전 경계
- `PILOT_README.md`, `TEST_RESULTS.json`, `OOXML_PRESERVATION.json`, `SHA256SUMS` 및 개별 HTTP/Chromium 결과를 `validation-results/pilot-04-20261104/`에 기록했다. XLSX와 실제 화면 캡처는 private gitignored 결과로 보존하고 Git에는 넣지 않는다.
- `data/reference/SHA256SUMS` 공식 15개 공정도 및 전체 목록 원본 16/16 OK. 물질수지 XLSM, 기존 DEMO-03/PILOT-01/02/03 산출물 변경 없음. profile exact SHA guard 우회 없음, `USER_WORKING_COPIES_ENABLED` 기본 OFF, 실제 ADD/DELETE 차단, VERIFIED flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 시험 서버 종료 및 일회성 token/password 파일 삭제 완료. migration/PM2/main/deployment 미수행, `process_map_dev` 및 운영 DB 미접속.

---

## PILOT-05 재개 메모 — 사용자 작업본 Profile MODIFY proposal/output 연계

### 재개 시점
- 2026-11-04 재개 시 branch `step7-7-wip-profile-add-20261101`, HEAD `2462fcc39a301563aa963fba55aac3d76f34b1d8`.
- 재개 전부터 아래 4개 파일에 미커밋 변경이 있었다. reset/stash/커밋은 하지 않았고, 이 변경을 PILOT-05 진행 중 상태로 보존한다.
  - `OperatorDiagramModifyController.java`
  - `DiagramModifyService.java`
  - `OperatorUserWorkingCopyIntegrationTest.java`
  - `Pilot04LoopbackTestServer.java`
- 목적은 정확한 SHA Profile이 있는 `USER_WORKING_COPY`의 MODIFY Preview → Generate → owner-only Download를 append-only change proposal 및 저장된 TEST_ONLY output과 연결하는 것. 공식 원본 및 실제 관계/승인 데이터는 수정하지 않는다.

### 현재 코드/테스트 진행 상태
- Profile 기반 사용자 작업본 Preview에서 authenticated proposal을 생성하고 `proposalId`를 반환한다. Generate는 작업본 SHA/Profile mapping을 재검증한 뒤 TEST_ONLY 결과를 저장하고 proposal에 output event를 기록한다. 성공한 Preview는 재사용하지 못하도록 session map에서 제거한다. Download는 저장 artifact를 현재 사용자 소유 범위에서 읽고 output SHA 및 working document ID를 session-generated result와 대조한다.
- 신규 DB integration scenario는 합성 baseline/profile/workbook을 사용하는 `OperatorUserWorkingCopyIntegrationTest.profileModifyStoresOutputAndAppendsProposalHistoryForTheOwnerOnly`다. Preview/Generate 중복 호출, proposal event 순서, 다른 사용자 접근 거부, output bytes/SHA, source/working-copy 불변성을 확인하도록 작성되어 있다.
- `./mvnw -q -DskipTests test-compile` PASS.
- DB 비의존 선택 회귀 5 classes, **14 tests PASS**: `DiagramBoxChangeEngineProfileModifyTest` 1, `DiagramBoxChangeEngineTest` 6, `DiagramModifyServiceDeleteGateTest` 4, `OperatorDiagramModifyControllerProfileRequestTest` 2, `GeneralDiagramEditProfileStatusTest` 1. `git diff --check` PASS.

### 실행 금지/미검증 상태 및 다음 조치
- 재개 환경에서 `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PROPOSAL_TEST_DB_URL`, `PROPOSAL_TEST_DB_USER`, `PROPOSAL_TEST_DB_PASSWORD`는 모두 unset이었다. DB-backed integration test는 실행하지 않았다. application 기본 datasource가 `process_map_dev`일 수 있으므로 그 DB나 운영 DB에 연결해 시험하지 않는다.
- 실제 Preview→Generate→Download DB/API 경로와 proposal/output 영속화는 아직 **미검증**이다. `USER_WORKING_COPIES_ENABLED` 기본 OFF, 격리 DB 전용 명시 설정이 필요한 상태를 유지한다.
- 다음 재개 시 사용자 제공/승인된 전용 격리 DB의 URL/계정 및 migration target을 별도로 확인한 뒤에만 `OperatorUserWorkingCopyIntegrationTest`를 실행한다. 격리성이 확인되지 않으면 DB test를 계속 보류하고, 현재 변경을 커밋/완료로 보고하지 않는다. 테스트 후 baseline SHA·working-copy SHA·proposal event·output row count를 전후 대조하고 `validation-results` 산출물은 Git stage에서 제외한다.
- 전체 Maven suite, migration, `main` 병합, PM2/배포는 하지 않았다.

---

## PILOT-05 최종 마감 — 문서 및 안전 경계 (2026-11-05)

### 검증 근거
- 기준 확인: branch `step7-7-wip-profile-add-20261101`, 시작 HEAD `6a744e6a32f7c3002923c724b132023021afc493`, tracked worktree clean. 기존 테스트 코드 변경은 HEAD에 이미 포함되어 있으며 이번 마감에서는 코드를 수정하거나 중복 stage하지 않는다.
- 최신 증거 run `validation-results/pilot-05-20261105/final-verified-run-20261105T0205Z/`의 `surefire-summary.csv` 및 13개 Surefire XML을 교차 집계했다: **13 classes / 48 tests / failures 0 / errors 0 / skipped 0**, XML 시간 합계 55.417초. 전체 Maven suite는 실행하지 않았다.
- 해당 격리 run은 loopback `127.0.0.1:54305`, 별도 schema `p05w_aljrvwdo` 및 `p05p_aljrvwdo`, 임시 datadir `/tmp/pilot05-final-JRVWDO/data`를 사용했다. run 후 임시 port/datadir 정리 확인 기록이 있다. `process_map_dev` 및 운영 DB는 사용하지 않았다. 이전 MariaDB PID 6526에는 이번 작업에서도 접속·종료·삭제하지 않았다.

### 실제 P-02 결과와 합성 성공의 분리
- 공식 source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, 등록 working-copy SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`; 진단 JSON에서 Profile/working SHA 일치를 확인.
- I89 (`I-PP21005`, `I89`): 실제 style ID 262, Profile 기대 220. 내부 mapping reason은 `PROFILE_STYLE_MISMATCH`, `availableFields=0`; 외부 Preview 응답은 HTTP 400 `PROFILE_MODIFY_FIELD_SET_INVALID`이다. 응답 코드를 내부 mapping reason으로 바꿔 쓰지 않는다.
- I584 (`I-PP21039`, `I584`): Connector ID 109 (object index 176)의 anchor가 zero-based row 583, columns 2–8에서 셀과 교차하며 connected-shape IDs는 null. 이는 DrawingML 시각 겹침만 입증한다. mapping은 `REVIEW_REQUIRED / DRAWINGML_FIELD_OVERLAP`; 외부 Preview는 HTTP 400 `PROFILE_FIELD_NOT_EDITABLE`이다.
- 두 실제 P-02 요청 모두 proposal event 0, TEST_ONLY output 0, official baseline 및 working copy 변경 false. 실제 P-02 MODIFY 성공·운영 Spring+DB 경로·배포는 미검증이다.
- 별도 격리 통합 테스트의 proposal/output/history/owner-download 성공과 rollback은 합성 Profile/workbook에만 해당한다. 실제 P-02 성공으로 해석하지 않는다. 상세 보고서는 `validation-results/pilot-05-20261105/P02_PREVIEW_400_DIAGNOSIS.md`와 `validation-results/pilot-05-20261105/PILOT_README.md`에 있고, JSON 및 Surefire 근거는 `validation-results/pilot-05-20261105/final-verified-run-20261105T0205Z/` 아래에 있다.

### 유지 상태 및 다음 재개 지점
- Profile exact source/working SHA, field allowlist, expected value/style, owner/session/authentication/CSRF, append-only output 이력 및 DrawingML overlap 차단을 유지한다. I89 style 차이 또는 I584 connector 간섭을 별도 구조 검토·승인 없이 완화하지 않는다.
- 실제 VERIFIED 유량 매핑 0, readiness `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. 공식 공정도 15개와 물질수지 XLSM 원본, `process_map_dev`, 운영 DB, PM2, migration, `main`, 배포는 변경하지 않았다.
- 다음 기술 재개는 정확한 SHA-bound 작업본에서 field/style 및 DrawingML 근거를 검토하는 단계부터이며, 별도 승인 및 명시적으로 격리된 DB 환경이 없으면 DB/API 통합 검증을 시작하지 않는다. 이번 최종 문서 작업에서는 DB 연결·테스트 재실행·코드 수정을 하지 않았다.
- `README.md`와 `CHECKPOINT.md`만 tracked 문서 커밋 대상으로 선별한다. `validation-results/`의 새 진단/요약 자료는 로컬 validation 자료이며 Git에는 포함하지 않는다. 최종 commit/HEAD와 status는 종료 응답에 기록한다.

---

## PILOT-07 최종 검증·회귀 및 커밋 체크포인트 (2026-11-06)

### 시작 상태 및 실제 이력
- 브랜치 `step7-7-wip-profile-add-20261101`. 실제 시작 HEAD `0b4024cb3650846740555895aab659428b2f525b`; 요청에 적힌 `9c9f42134b4457f1bc02726c1d8ef13f03402093`보다 자동 백업 커밋 1개 앞서 있었다. `0b4024c`는 ProfileCandidatePreflight / ProfileSelectionPreflight, 두 테스트 및 두 test-only JSON fixture 6개를 이미 포함했다. 시작 worktree clean; reset/clean/stash 또는 기존 코드 삭제는 사용하지 않았다.
- 최종 산출물은 `validation-results/pilot-07-20261106/final-run-003813/`에 별도 보존. 이전 `validation-results/pilot-07-20261105/PROFILE_FIELD_VALIDATION.json`, `CROSS_PROCESS_VALIDATION.json`, `CHECKPOINT_01.md`는 읽기/재사용했으며 덮어쓰지 않았다.

### 코드 경계 및 Profile 선택 결과
- `ProfileCandidatePreflight`는 byte-array 기반 검사로 source 및 working SHA, identity, 설비 박스, 셀값, style/font, 행 높이, 병합, DrawingML anchor/overlap을 읽기 전용 비교한다. Spring stereotype/운영 Controller 연결 없음. 기존 `EquipmentBoxProfileRepository` resolver 변경 없음. `modifyAuthorized=false` 고정; 실제 물리 연결·유량은 추정하지 않는다.
- `ProfileSelectionPreflight`도 순수 evaluator로 운영 resolver에 연결되지 않았다. 동일 SHA의 VERIFIED + REVIEW_REQUIRED 충돌은 자동 및 기존 Profile explicit 선택 모두 차단. review-only 단독 후보도 선택 불가. 선택 충돌 테스트 5/5 PASS.
- P-02 source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, working SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`; 박스 I86:Z93 및 I89 snapshot/SHA 일치, `STRUCTURE_CHECK_PASS`이나 후보 `REVIEW_REQUIRED`, decision `BLOCKED`, modifyAuthorized false. 실제 style 262 대 기대 220, 행 높이 12.0pt 대 11.1pt, 병합 V87:Z93 대 Profile U582:Z588 차이 미해결. I584와 Connector 109는 직접 겹쳐 MODIFY 차단. Connector 19는 박스 경계 추가 시각 검토 대상이며 physical link 증거가 아니다.
- P-01 별도 source SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`, working SHA `a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb`; 17행 박스 J4:AA20, SHA 일치. 별도 후보/기존 Profile은 `REVIEW_REQUIRED`, DrawingML 간섭 검토 전 사용 금지. MODIFY 실행/성공 아님.

### 테스트 및 산출물
- UTF-8 환경 최종 회귀: 16 Surefire classes / **57 tests, failures 0, errors 0, skipped 0**. Surefire XML에서 집계, test-compile PASS. 대상은 Profile preflight/선택 충돌, P-02 실작업본 read-only 구조, Profile MODIFY, Profile/Legacy ADD 정책, BOX_ONLY DELETE gate, OOXML 보존, PIPE_CHANGE/FLOW_CHANGE synthetic 및 controller/service policy다.
- mock 기반 `FlowChangeAuthenticatedWorkflowServiceTest` 4 tests도 PASS. 무효·stale·no-op 입력에서 proposal/output persistence를 시도하지 않는 경계 검사이며 DB append-only 영속 이력 검증과 다르다.
- 최초 Maven 시도에서 UTF-8이 아닌 JVM file-name encoding 때문에 한글 경로 `InvalidPathException` 3건이 발생. UTF-8 locale/JVM option으로 재실행해 모두 통과; 테스트 안전 조건/guard 완화 없음. 전체 Maven suite 미실행.
- 실제 P-02/P-01 byte SHA는 테스트 전후 assertion 일치. 공식 `data/reference/SHA256SUMS` 16/16 OK. 물질수지 XLSM·공식 원본·사용자 작업본 수정 없음.
- 상세 보고서: `validation-results/pilot-07-20261106/final-run-003813/`의 `PILOT_README.md`, `PROFILE_SELECTION_RULES.md`, `TEST_RESULTS.json`, `CHECKPOINT_STATUS.md`, `SHA256SUMS`; P-02/P-01 검사 JSON은 각각 `PROFILE_FIELD_VALIDATION.json`, `CROSS_PROCESS_VALIDATION.json`.

### 미검증 및 유지 상태
- 운영 Spring Preview integration, proposal/output DB 영속 이력, 실제 P-02 MODIFY, 승인/등록/VERIFIED 승격, Browser E2E 미검증. DB 의존 테스트는 기본 datasource가 `process_map_dev`일 수 있어 실행하지 않았으며 해당 DB/운영 DB 접속 없음.
- ADD/DELETE 차단, SHA/style/merge/DrawingML guard 유지. 실제 VERIFIED 유량 매핑 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. PM2, migration, `main`, 배포 변경 없음.
- 다음 단계는 담당자가 P-02 style/height/merge 및 Connector 109/19 경계와 P-01 DrawingML 근거를 검토하는 것이다. 필요 시 그 후 별도 승인된 설계로 Preview integration/격리 DB 경로를 추가 검증한다. 기존 안전 조건 완화나 실제 MODIFY 수행을 전제하지 않는다.
- 안전 커밋은 구현·테스트 코드, README.md, CHECKPOINT.md만 stage 대상으로 삼고, 공식 원본/사용자 작업본/실제 XLSX·PDF·PNG/validation-results 산출물은 제외한다. 커밋 ID와 최종 tree status는 최종 응답에 기록한다.

---

## PILOT-08 — 기존 Spring Boot 앱에 읽기 전용 Profile 사전검증 연결 (2026-11-06)

### 시작/구현 범위
- 기준 branch `step7-7-wip-profile-add-20261101`; 시작 HEAD `4616529206d07122b2c96df25bae639dbfa50b9e`, 초기 worktree clean 및 `git diff --check` PASS. 현재 작업 branch는 유지했다.
- 기존 `ProfileCandidatePreflight` 및 `ProfileSelectionPreflight`를 호출하는 신규 `ProfilePreflightService`와 GET-only `OperatorProfilePreflightController`를 추가. Service 입력은 working document ID, process, sheet, management number, 세션 username이며 출력 DTO에 SHA 검증, 구조·후보·선택 결과, field/style/row-height, merge, DrawingML, finding/error/blocker/approval을 포함한다.
- Service는 `UserWorkingCopyService.getForUser(id, username)` 호출 및 `USER_WORKING_COPY`/owner 재확인 뒤 잠긴 V1.0 PROCESS source bytes를 read-only로 조회한다. source/work/candidate SHA 및 identity를 확인하고, 기존 resolver와 ADD/MODIFY/DELETE 서비스를 호출하거나 변경하지 않는다. 모든 결과 `modifyAuthorized=false`; `REVIEW_REQUIRED` 후보 자동 선택·승격 금지.
- `DiagramEditWorkingCopyRepository.findLockedSourceWorkbook` 추가는 잠긴 V1.0 PROCESS 원본 bytes의 read-only 조회만 한다. `application.properties`의 `app.profile-preflight.enabled=${PROFILE_PREFLIGHT_ENABLED:false}` 및 기존 `app.user-working-copies.enabled=${USER_WORKING_COPIES_ENABLED:false}`를 모두 true로 하지 않으면 Service/Controller bean이 등록되지 않는다.
- HTTP route는 `GET /api/workspace/diagram-editor/profile-preflight` 하나. 세션 인증 후 username을 사용하고 no-store 응답. POST/Preview/Generate 없음. 절대경로, 로그인정보, 원본 bytes는 응답 DTO에 포함되지 않는다. `src/main/resources/static/index.html` UI 연결은 하지 않았다.
- 추가/변경 코드: `ProfilePreflightService.java`, `OperatorProfilePreflightController.java`, `DiagramEditWorkingCopyRepository.java`, `application.properties`, `profile-review-candidates.json`, Service/Controller 테스트. candidate catalog는 P-01/P-02 SHA-bound review-only snapshot이며 등록/활성화 상태를 변경하지 않는다.

### 실제 P-01/P-02 read-only 결과
- 최종 신규 run: `validation-results/pilot-08-20261106/final-run-014000/`. `PROFILE_PREFLIGHT_P01.json`, `PROFILE_PREFLIGHT_P02.json`과 Profile 구조 검증 JSON 및 Maven log를 별도 보존. 이전 run `final-run-010519`는 덮어쓰지 않았다.
- P-02 `P-02-01(TOP) / I-PP21005`: source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, working SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`; source/work/candidate SHA true, `STRUCTURE_CHECK_PASS`, 최종 BLOCKED. I89 구조/value check pass지만 Profile style 262 vs expected 220, 행 높이 12.0pt vs 11.1pt가 불일치하고 병합 범위는 candidate snapshot과 일치해도 Profile template와 다름. Connector 19 `BOX_REGION_REVIEW`; Connector 109는 I584 Profile reference field와 겹침(`DRAWINGML_PROFILE_REFERENCE_FIELD_OVERLAP`); 추가 Connector 2/I587도 reference scan에서 표시. `PROFILE_SELECTION_AMBIGUOUS`, review-only 미승인, MODIFY 미승인 상태를 유지.
- P-01 `P-01-01(TOP) / I-PP11001`: source SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`, working SHA `a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb`; 세 SHA true, 구조 확인 이후에도 후보 및 Profile `REVIEW_REQUIRED`, 최종 BLOCKED. DrawingML Connector 47/7 경계 검토, Connector 12 field overlap 및 J8 reference field overlap, 선택 불가. `modifyAuthorized=false`.
- 테스트 전후 실제 입력 SHA assertion은 통과했고 종료 후 `sha256sum`으로도 source/working 네 파일이 위 고정값과 일치함. 공식 공정도, 물질수지 XLSM, P-01/P-02 작업본은 수정하지 않았다.

### 인증/소유권/API/DB 경계
- Service 단위 테스트는 mock owner loader에서 `getForUser` 호출을 확인하고 foreign-owner/disabled conditions를 차단한다. Controller standalone MockMvc는 authenticated GET, unauthenticated refusal, `Cache-Control: no-store`, POST 405를 확인했다. Preview/Generate가 route/method로 존재하지 않는다.
- 실제 DB-backed owner isolation 및 실제 Spring Boot application context/API 인증은 미검증이다. 테스트는 독립 Spring+DB를 띄우지 않았다. `process_map_dev`·운영 DB 접근, PM2, migration, main, 배포 없음. 기능 flag 기본 OFF 유지. 통합 경계가 실 DB로 입증되기 전에는 UI를 연결하지 않는다.

### 테스트·무결성
- 이전 18-class 실행에서 blocker string의 test expectation 하나가 구현 코드 `REVIEW_REQUIRED_CANDIDATE_NOT_AUTHORIZED`와 달랐다. 테스트 기대값만 정확히 수정하고 안전 조건은 완화하지 않았다.
- UTF-8 JVM 설정으로 해당 18개 클래스 회귀 실행. 최종 Surefire XML 집계: **18 classes / 65 tests / failures 0 / errors 0 / skipped 0**. 선택 범위는 Service/Controller, Profile selector 및 P-01/P-02 preflight, Profile MODIFY/ADD, BOX_ONLY DELETE gate, OOXML, PIPE_CHANGE/FLOW_CHANGE synthetic/security/controller. test-compile도 Maven 실행 중 성공. 전체 Maven suite는 실행하지 않았다.
- 신규 run JSON을 대상으로 절대경로·uploaded_files·bytes·password/token/secret 관련 문자열 scan에서 일치 없음. 출력 응답에는 파일 bytes/절대경로가 없다. `SHA256SUMS`는 해당 run 내 안전한 JSON/log/checkpoint 증빙에 생성한다.
- 테스트 후 현재 기능 flags 기본 false를 확인. 추가 baseline/DB 검증은 하지 않았으며 실제 Spring+DB 통합 결과라고 주장하지 않는다.

### 남은 승인·검증 및 커밋 경계
- 실제 MODIFY 적용 전 P-02 style/row-height/merge 차이와 Connector 109/19 DrawingML을 담당자가 독립 검토하고, P-01 DrawingML 검토 및 candidate의 공식 승인/등록 절차가 필요하다. 현재는 모두 REVIEW_REQUIRED/BLOCKED이며 실사용 불가.
- 별도 격리 MariaDB/실제 Spring session+owner 통합, 기능 flag 격리 활성화 검증, 실제 운영 UI 요구 검토는 후속. 미승인 후보는 운영 resolver로 연결하지 않는다.
- PILOT-08 검증 결과/JSON/SHA256SUMS는 `/validation-results/` ignore 경로로 유지하고 Git stage 대상에서 제외. 코드·테스트·README/CHECKPOINT만 검토 stage 대상으로 하며 실제 Excel 및 민감 산출물은 커밋하지 않는다. 최종 diff 검토 후 안전 커밋하며 정확한 commit ID와 최종 tree status는 최종 응답에 기록한다.

---

## PILOT-08 최종 재검증 기록 — 2026-11-06

### Git 및 경로 수정
- CHECKPOINT 1 실제 확인: branch `step7-7-wip-profile-add-20261101`, HEAD `5bb99d94f94bcd673e8b0153fb2b5df05630eccb` (`genspark auto-backup`), worktree clean, staged/unstaged/untracked 없음. `git diff --check` 및 `git diff --cached --check` PASS.
- PILOT-08 코드/테스트/문서 기본 변경 9개 파일은 위 HEAD에 이미 포함. 기존 HEAD `4616529206d07122b2c96df25bae639dbfa50b9e`로 되돌리거나 기존 구현을 재커밋하지 않았다.
- 최신 테스트 경로 상수는 P-01/P-02를 별도 system property key로 분리하고 `/home/user/webapp` 기준 `../uploaded_files/...` 상대경로 fallback을 사용한다. 파일을 못 읽으면 opt-in 테스트가 예외로 실패하며, 읽은 bytes도 literal SHA assertion이 고정한다. 이번 실행 전 파일 존재와 네 source/work SHA를 검사했고, 실행 후 동일 SHA가 유지됐다.

### 최종 compile 및 Surefire 결과
- `LC_ALL=C.UTF-8 LANG=C.UTF-8 MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' ./mvnw -q -DskipTests test-compile`: PASS.
- 동일 UTF-8 조건에서 신규 Service/Controller, 인증·소유권 Mock, ProfileCandidatePreflight/Selection, P-01/P-02 실제 SHA-bound 작업본, 기존 Profile MODIFY/ADD, BOX_ONLY DELETE, OOXML, PIPE_CHANGE/FLOW_CHANGE synthetic 회귀 실행.
- Surefire XML 실제 선택 클래스 집계: **18 classes / 65 tests / 65 passed / 0 failures / 0 errors / 0 skipped**. 전체 Maven suite는 실행하지 않았고 통과 주장 없음.
- P-01/P-02 결과는 source/work/candidate SHA 확인 후 `STRUCTURE_CHECK_PASS` 범위 및 review findings를 반환하나 모두 `decisionStatus=BLOCKED`, candidate REVIEW_REQUIRED, `modifyAuthorized=false`. P-02 style 262 vs 220, row height 12.0 vs 11.1pt, merge/Profile-template 차이, Connector 19 boundary 및 Connector 109/I584 overlap이 유지된다. P-01 DrawingML box/field/reference overlap 및 선택 불가 유지.

### 보안·환경 경계
- flags `PROFILE_PREFLIGHT_ENABLED=false`, `USER_WORKING_COPIES_ENABLED=false` 기본값 유지. Controller GET-only, unauthenticated GET service 미호출 및 POST 405, no-store 확인. Service mock은 `getForUser` strict owner call 및 foreign-owner 차단을 검증한다. 이는 DB-backed owner proof가 아니다.
- 실제 Spring ApplicationContext, 실제 DB/session ownership, `process_map_dev`, 운영 DB 접근은 없음. UI 미구현. Preview/Generate 및 기존 MODIFY 실행 경로 미변경. API 결과 JSON의 경로/auth/bytes pattern scan에서 일치 없음.
- 공식 process workbook 15개, material-balance XLSM, P-01/P-02 working-copy는 변경하지 않음. PM2, migration, `main`, deploy 변경 없음. ADD/DELETE guards 및 VERIFIED flow 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.

### 실행 증빙 및 커밋 상태
- 최신 산출물: `validation-results/pilot-08-20261106/final-run-020000/` — `TEST_RESULTS.json`, `WORKBOOK_SHA256.txt`, `CHECKPOINT_01_GIT.md`, `CHECKPOINT_02_TESTS.md`, `CHECKPOINT_03_DOCUMENTS.md`, preflight JSON, compile/test logs, `SHA256SUMS`. `/validation-results/` ignore 대상이며 Git에 미포함.
- `README.md` 및 `CHECKPOINT.md`에 최신 경로 수정/compile/Surefire/HEAD 결과를 추가했다. PILOT-08 implementation commit은 자동 백업 HEAD `5bb99d9`에 이미 있음. 문서 변경만 별도 커밋하며 code diff를 중복하지 않는다. 최종 문서 커밋 ID 및 clean tree 상태는 `CHECKPOINT_04_COMMIT.md`와 최종 응답에 기록한다.
- 다음 개발 재개점은 별도 승인 후 격리 MariaDB에서 실제 Spring session + owner integration 검증 설계부터. 담당자 review 전 후보 등록/활성화, VERIFIED 승격, 실제 MODIFY 또는 UI 연결은 하지 않는다.

---

## PILOT-09A — 최신 Spring MVC 인증·소유권 테스트 최종 체크포인트 (2026-11-06)

### 시작 상태와 범위
- 실제 브랜치 `step7-7-wip-profile-add-20261101`, HEAD `d1f57f16acb630e9a48042dd6393c99e39a84f72` (자동 백업 커밋). 요청 기준 `39448a4b71660cb4471701b77f4023cb5666011d`로 reset/checkout하지 않았다. 현재 MVC 테스트 파일은 HEAD에 이미 있고, 재개 시 unstaged diff는 해당 테스트 파일의 `mallory` 로그인 계정 구성 보정 1건뿐이었다. 운영 Java 코드/인증·소유권 서비스는 변경하지 않았다.
- 기존 Spring Security dependency, `SecurityFilterChain`, `SecurityContextHolder`, Spring Security `Authentication` 구현이 없다. 실제 앱 identity는 `OperatorLoginService.requireAuthenticated(HttpSession)`의 `operator.username`을 사용한다. 존재하지 않는 chain을 disable/우회하지 않았고 Security filter-chain 테스트를 완료했다고 주장하지 않는다.

### 최신 MVC 테스트
- UTF-8 locale/JVM encoding으로 `./mvnw -q -DskipTests test-compile` PASS.
- 최신 `OperatorProfilePreflightMvcIntegrationTest` 단독 실행: **7 tests, failures 0, errors 0, skipped 0**. `AnnotationConfigWebApplicationContext + @EnableWebMvc + MockMvcBuilders.webAppContextSetup` 및 실제 `POST /api/session/login` → `HttpSession` 흐름을 검증했다.
- 테스트 계정은 mock 설정과 일치하도록 `mallory`로 MVC login context를 구성한다. 익명 GET 401; foreign owner request는 실제 `ProfilePreflightService`가 mock `UserWorkingCopyService.getForUser(72, "mallory")`의 404를 받고 source workbook repository가 읽히지 않음을 검증. DB owner test는 아니다. client username 무시, flags OFF/ON, malformed/unknown ID, POST 405, no-store, SHA mismatch, privacy, REVIEW_REQUIRED 및 `modifyAuthorized=false` 경계를 확인했다.
- 실제 P-02 service MVC 요청은 고정 source/working SHA로 실행: SHA 검증 true이나 candidate REVIEW_REQUIRED, profile selection/decision BLOCKED, `modifyAuthorized=false`. 응답 privacy assertion 통과. P-01/P-02 실제 service tests 역시 BLOCKED 상태를 유지했다.

### 선택 회귀 및 결과 보관
- 최신 최종 선택 회귀: **19 classes / 72 tests / failures 0 / errors 0 / skipped 0**, Surefire XML 19개를 이번 final invocation 로그의 클래스 목록과 대조해 별도 디렉터리에 복사하고 재합산했다. 포함: ProfilePreflightService/Candidate/Selection, 실제 P-01/P-02 SHA-bound preflight, Profile MODIFY/ADD/BOX_ONLY DELETE gate, OOXML, PIPE_CHANGE/FLOW_CHANGE synthetic 및 신규 MVC.
- 첫 재회귀 시 opt-in property 누락으로 2개 real-workbook 서비스 사례가 skip됐고, 올바른 opt-in 속성을 더한 후 결과 JSON이 이미 생긴 디렉터리를 재사용해 `CREATE_NEW` 충돌이 났다. 두 로그는 보존했고, 별도 `pilot07-evidence/` 및 `pilot08-evidence/` 경로로 재실행한 최종 run은 0 skip으로 통과했다. 실패는 evidence output 경로의 충돌이며 애플리케이션/데이터 오류가 아니었다.
- 최종 결과 경로: `validation-results/pilot-09-20261106/final-run-041500/`. 여기 `TEST_RESULTS.json`, `CHECKPOINT_STATUS.md`, `SUREFIRE_SUMMARY.tsv`, 선택 클래스 목록, 정확한 19개 Surefire XML, compile/MVC/regression logs, P-01/P-02/P02 MVC 결과 JSON, `WORKBOOK_SHA256_AFTER.txt`, `SHA256SUMS`를 보존한다. `/validation-results/`는 Git 제외 대상.
- 공식 reference manifest 16/16(15 process Excel + master) PASS. P-01/P-02 source/working SHA는 실제 opt-in 테스트에서 테스트 전후 일치. 물질수지 XLSM은 SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`로 기록; 입력은 read-only.

### 금지 작업과 남은 검증
- 전체 Maven suite 미실행. DB 접속/기동, `process_map_dev`, migration, Spring Boot server/PM2, UI, deployment/Cloudflare Preview는 수행하지 않았다. DB 기반 ownership test와 실제 Spring Security filter-chain 검증은 미수행이다.
- feature flags 기본 OFF; P-01/P-02 미승인 후보 `REVIEW_REQUIRED`/`BLOCKED`, `modifyAuthorized=false`. 실제 ADD/DELETE 차단, VERIFIED flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 자동 백업 HEAD에서 발견한 테스트 변경과 README/CHECKPOINT 두 문서만 최종 stage 대상으로 검토한다. 공식 Excel, 물질수지 XLSM, P-01/P-02 작업본 및 validation artifacts는 Git에 넣지 않는다. 최종 diff 검사 및 커밋 ID는 아래 종료 기록에 추가한다.

---

## PILOT-10A — 유향 물질수지 엔진 최종 검증 체크포인트 (2026-11-06)

### 실제 Git 상태와 검토
- 재개 시 branch `step7-7-wip-profile-add-20261101`, HEAD `230d10321c9ebc3679ad8353570be00fc5b38821` (`genspark auto-backup`), worktree clean이었다. 이 HEAD에는 직전 보고의 신규 DTO/engine 및 solver/test 파일 5개가 이미 포함되어 있었다. 강제 checkout/reset/clean/stash 없이 기존 commit을 기준으로 검토했고 중복 코드를 다시 커밋하지 않는다.
- 코드 검토 후 최소 변경: directed cycle detector가 cycle 진입 노드만 기록하던 것을 cycle path participants로 확장; non-scenario VERIFIED edge의 process/equipment endpoints도 VERIFIED 요구; CONFLICT/UNKNOWN/review-required 차단 상태 테스트; typed endpoint 키 사용; empty-edge graph unavailable 유지; scenario issues를 scenario result에 보존; BigDecimal pivot 절대 tolerance 경계 보정. 신규 운영 계산 기능은 추가하지 않았다.

### 엔진 경계
- 설비/공정/외부 경계는 방향 edge별로 집계하고 스트림은 material class/type/unit/period별로 분리한다. 단위변환·기간 합산·잔차 강제 조정은 없다. `RESIDUAL_UNRESOLVED`는 그대로 반환된다.
- 영향 범위는 direct/upstream/downstream/union으로 내며 visited-set BFS로 끝난다. Cycle detection은 유향 cycle 경로를 탐지하지만 미지 순환 유량을 풀지 않는다. BigDecimal solver는 34 digit/HALF_EVEN, largest-absolute pivot, absolute 1e-9 tolerance를 쓰며 나눗셈 반올림과 tolerance의 scale sensitivity 한계가 있다.
- 분기 비율은 입력 및 합계 검증만 하고 후단 유량 배분을 계산하지 않는다. `PIPE_CHANGE`는 한 edge addition 시나리오뿐이며 원자적 재배관은 아니다. 새 엔진은 운영 FLOW_CHANGE/PIPE_CHANGE나 Excel 편집에 연결되지 않았다.

### 최종 선택 테스트 및 증빙
- UTF-8/JVM encoding `test-compile` PASS. 최종 선택 Surefire: **12 classes / 57 tests / failures 0 / errors 0 / skipped 0**. P-01/P-02 SHA-bound preflight/MVC opt-in은 read-only로 실행했고 입력 SHA를 `WORKBOOK_SHA256_AFTER.txt`에 기록했다.
- 첫 시도에서 새 interprocess-cycle assertion이 cycle member 누락을 발견했다. detector를 최소 수정한 뒤 전체 선택 집합을 새 run에서 재실행해 통과했다. 실패 run `final-run-20261106a`는 삭제/덮어쓰기 없이 보존.
- 최종 증빙 경로 `validation-results/pilot-10-20261106/final-run-20261106f/`; model/engine/scenario/input/integration plan은 root markdown 5개 파일에 기술했다. `/validation-results/`는 Git ignore.
- 실제 VERIFIED connection/flow mapping 0건, 운영 `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. 전체 Maven suite, DB/MariaDB/process_map_dev, 서버/PM2, migration, deployment/Preview는 하지 않았다. 공식 도면 15개, XLSM, P-01/P-02 사용자 작업본 쓰기 없음.

### 커밋
- 최종 구현·테스트·README 및 기술 문서 5개는 자동 백업 commit `aea2ef76276e32c56982786f90f653137cff8ad9`에 이미 포함되어 있다. 최신 solver 불일치 테스트와 README/CHECKPOINT/WHAT_IF 57건·final-run `20261106f` 경로를 포함한 HEAD를 `git show`로 확인했으므로 코드 변경은 중복 커밋하지 않는다.
- final-run `SHA256SUMS`는 27개 evidence 파일에 대해 생성 후 전부 `OK` 검증했다. 이 체크포인트의 closeout 기록만 별도 문서 커밋에 포함하고, 최종 commit ID는 `git rev-parse HEAD` 및 최종 보고에서 확인한다. 검증 산출물은 Git ignore.
- `aea2ef7` 확인 당시 staged/unstaged/untracked nonignored 변경은 0건이었다. final-closeout 문서 변경은 이번 최종 검토 후 stage한다.

---

## PILOT-10B — 분기 배분·전파·What-if 최종 검증 및 마감 (2026-11-06)

### Git 출처 및 보존
- 실제 재개 HEAD는 `2fb50a3f4f1e5de41f32e88d0bcfa1ad03ca9dc7` (`genspark auto-backup`), parent는 PILOT-10A HEAD `8479d7f326a14f3c050daa2ef3fdbafbf32eabc7`이었다. 시작 worktree clean, staged/unstaged/untracked nonignored 없음. 2fb50a3에는 PILOT-10B 구현 4개 경로(Excel contract 추가, network/engine/test 수정)가 이미 포함돼 있었으므로 재-stage/중복 커밋하지 않는다.
- 이 최종 확인 세션에서는 기존 구현을 재작성하지 않았다. clean 시작 HEAD 이후 추가 변경은 `MaterialFlowNetworkEngine.java`, `MaterialFlowNetworkEngineTest.java`뿐이다: What-if readiness에 transfer rule evidence/scenarioOnly 반영, TRANSFORM 복수 basis의 stream 일치 차단, incomplete/empty external totals의 invariant false 판정과 회귀 테스트.

### 코드·운영 경계
- 명시적 PASS_THROUGH/BRANCH_RATIO/SUM_INPUTS/TRANSFORM, 1e-9 분기 tolerance, BigDecimal 34자리 HALF_EVEN, 임의 잔여량 배정 금지를 유지했다. 미상/누락 규칙 및 unresolved cycle은 계산 불가·후단 미계산으로 처리한다.
- baseline 복제형 What-if, 잔차와 증분, external boundary 비교 및 Excel handoff UNKNOWN/REVIEW_REQUIRED/non-editable 상태를 검증했다. 공식 Excel을 수정하지 않았다.
- 실제 VERIFIED 연결·유량 mapping 0건, live `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`, 운영 FLOW_CHANGE/PIPE_CHANGE 미연결을 유지한다. 미지 순환 유량, 원자적 재배관, 15개 도면 전체 편집, 실제 계산 승인 미완료.

### 테스트·증빙
- UTF-8/JDK 17/Maven 3.9.9 `test-compile` PASS.
- 최신 선택 Surefire: **9 classes / 58 tests / failures 0 / errors 0 / skipped 0**. 엔진·DirectedProcessNetworkSolver·MaterialBalanceCalculator·What-if·synthetic FLOW_CHANGE/PIPE_CHANGE·authenticated FLOW_CHANGE·OOXML을 포함한다. 전체 Maven suite는 미실행; Profile 전용 테스트는 관련 코드 변경이 없어 미실행; P-01/P-02 read-only SHA 검사도 이번 run에서 미실행.
- 결과 경로 `validation-results/pilot-10b-20261106/final-run-20261106-0710/`에 compile/test 로그, 선택 Surefire XML, summary, JSON, calculation notes, Git start 및 checkpoint를 보존한다. `/validation-results/`는 Git ignored.
- DB/MariaDB/process_map_dev, 서버/PM2, migration, 배포/Cloudflare Preview는 실행하지 않았다. 공식 15개 Excel, 물질수지 XLSM, P-01/P-02 작업본은 이 실행에서 접근/수정하지 않았다.

### 커밋
- 2fb50a3에 이미 들어간 PILOT-10B 기능은 중복 커밋하지 않는다. 이번 closeout은 최신 테스트를 통과한 engine/test 2개와 README/CHECKPOINT만 stage 대상이다. 증빙 디렉터리와 공식 파일은 stage하지 않는다. `SHA256SUMS` 검증과 최종 staged/unstaged diff 확인 후 커밋 ID 및 최종 작업 트리 상태를 아래에 기록한다.

---

## PILOT-11A — 전체 도면 영역 분석 및 위치 기반 Excel TEST_ONLY 검증 (2026-11-08)

### 시작 Git 및 실패 테스트 해결
- 실제 재개 확인: branch `step7-7-wip-profile-add-20261101`, HEAD `9dab23444917b92c48f459aa09be565992872726`, staged/unstaged/untracked nonignored 변경 없음, `git diff --check` PASS. 9dab234는 기존 PILOT-11A 코드/테스트 자동 백업 커밋이며 기존 구현은 보존했다.
- 잘못된 assertion은 편집 pure function을 같은 원본 bytes에 다시 호출했는데 차단을 기대한 것이었다. 계약별로 검증: 동일 원본 SHA/Profile/값이면 허용되고 결과 SHA가 결정적으로 같음; 이미 편집된 bytes에 기존 request/source SHA를 쓰면 `SOURCE_SHA_MISMATCH`/BLOCKED; MODIFY의 이전값 stale는 `STALE_VALUE`로 차단. 인증/SHA/Profile/stale guard는 완화하지 않았다.
- self-closing `<c .../>` blank OOXML 셀만 수정하는 경로를 별도 합성 테스트로 추가했다. `OoxmlPartEditor`에서 normal `<c>...</c>` matcher가 self-closing cell을 가로질러 다음 셀까지 소비하던 문제를 고치고, explicit self-closing cell test를 통과시켰다.

### Read-only 전체 도면 분석
- `data/reference/SHA256SUMS` 16/16 PASS (15 process diagrams + `전체 목록.xlsx`); 15개 파일 SHA도 기존 2026-10-29 inventory와 모두 일치.
- 모든 15개 workbook과 62개 sheet를 실제 `ProcessDiagramStructureAnalyzer`로 재분석. 파일 단위 SHA checkpoint 15/15 COMPLETE이며 재실행은 완료 checkpoint를 다시 분석하지 않고 재사용.
- 합계: non-empty cells 37,018; merges 10,625; shapes 586; connectors 8,094; pictures 116; explicit connector bindings 0. 설비 ID 후보 5,044(geometry box candidate 1,142, text-only 3,899, ambiguous box 3), 위치 후보 1,006. 전부 candidate/review-only; automatic edit 허용 0.
- 62개 sheet Profile 비교: current source SHA 기준 catalog match 5(VERIFIED 1, REVIEW_REQUIRED 3, LEGACY_ONLY 1), no match 57. 이 Profile 상태만으로 위치 승인/physical relationship/ADD authorization을 의미하지 않는다.
- 필수 outputs는 Git-ignored `validation-results/pilot-11a-20261108/` root와 final run `final-run-20261108-050359/`: `ALL_SHEET_INVENTORY.json`, `SHEET_LAYOUT_MAPPING.json`, `SCAN_PROGRESS.json`, `INVENTORY_COMPARISON.md`, 15 file checkpoint.

### 위치 preflight 및 XLSX 시험
- 합성 preflight matrix: absent sheet, malformed/out-of-bounds range, source SHA mismatch, occupied target, merge collision, duplicate management number, equipment box overlap, shape/connector anchor collision, empty styled cell with passing shape, unknown/review location, unverified Profile 차단 확인.
- **Reserved-range value entry only**: 이미 서식 있는 blank range의 허용 셀 값만 기록. row insert/move, full-box clone/border creation, DrawingML create, physical pipe/flow 생성 없음. 동일 original bytes 재호출은 허용되나 edited output + old source SHA는 차단.
- **Profile MODIFY**: 별도 synthetic fixture에서 position mapping→기존 `DiagramBoxChangeEngine` Profile mapping/working SHA/style/stale value guards 경로. 잘못된 identifier cell, stale value, unverified mapping 차단.
- 3개의 합성 TEST_ONLY XLSX를 파일로 저장해 ZIP/POI 재열기, target-only changes, merge/style/row-height/column-width, DrawingML inventory 및 drawing-part byte equality, original bytes SHA invariant를 검사. LibreOffice PDF/PNG를 만들고 첫 페이지 이미지를 육안 확인했으나 Excel native renderer/실제 공정도 시각 승인과의 동등성은 확인하지 않음.
- TEST_ONLY reserved values are not reported as full equipment-box ADD. 기존 gap-based `DiagramEquipmentInsertionEngine`은 행/anchor를 이동시킬 수 있으므로 arbitrary placement/full box ADD는 `FULL_BOX_ADD_NOT_VERIFIED`로 유지.

### Coverage 및 안전 상태
- `READ_ONLY_ANALYZED`: 15/15 workbooks, 62/62 sheets.
- `LOCATION_CANDIDATE`: 1,006, all `REVIEW_REQUIRED`; no physical flow verified.
- `PLACEMENT_PREFLIGHT_TESTED`: synthetic matrix pass.
- `TEST_ONLY_VALUE_EDIT_VERIFIED`: reserved preformatted cell value entry only.
- `TEST_ONLY_MODIFY_VERIFIED`: synthetic source-SHA-bound Profile fixture only.
- `FULL_BOX_ADD_NOT_VERIFIED`, global official-layout `DELETE_NOT_VERIFIED`, official diagram-wide `PIPE_CHANGE_NOT_VERIFIED`.
- MaterialFlow Excel mapping remains `UNKNOWN`/non-editable unless independently approved exact mapping exists. Current contract handoff should carry process/workbook/source SHA, sheet/range/equipment field map/anchors/evidence and blockers; never synthesize connectionId/pipe/flow from visual proximity.
- VERIFIED physical flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`; official Excel, master workbook, XLSM, P-01/P-02 working copies unchanged. DB/process_map_dev, server/PM2, migration, deployment/Cloudflare Preview were not used.

### Final compile/test and evidence
- UTF-8/JDK17 `test-compile`: PASS.
- Final selected Surefire: **8 classes / 41 tests / failures 0 / errors 0 / skipped 0**. Included `DiagramPlacementPreflightTest` 8, `DiagramEquipmentInsertionEngineTest` 12, `DiagramBoxChangeEngineTest` 6, `DiagramBoxChangeEngineProfileModifyTest` 1, `OoxmlRoundTripTest` 2, official read-only inventory 1, synthetic pipe drawing 6 and pipe security 5. Full Maven suite: NOT RUN.
- Final evidence `validation-results/pilot-11a-20261108/final-run-20261108-050359/`: test results/logs, exact Surefire XML, all-sheets inventory/mapping/checkpoints, TEST_ONLY XLSX+JSON, LibreOffice PDF/PNG, SHA256SUMS. Entire validation-results tree is ignored by Git.
- Commit entry is added after final staged diff review; exact commit/HEAD and final clean status are confirmed in the user-facing closeout.

---

## PILOT-11B — 셀 기반 위치형 설비 박스 ADD 최종 검증 (2026-11-08)

### 1. Git 및 구현
- branch `step7-7-wip-profile-add-20261101`; 재개 HEAD `afae191a471f83b9fb659555e6286ea66e4cd1d3` (`genspark auto-backup`), 시작 worktree clean/staged 없음. `DiagramBoxAddEngine.java`, `DiagramPlacementPreflight.java`, `DiagramBoxAddEngineTest.java` 기존 변경은 이 HEAD에 이미 포함되어 중복 구현하지 않았다.
- 실제 변경은 synthetic-only `DiagramBoxAddEngineTest` fixture·evidence 보완뿐이다. 테스트 fixture에 source XLSX를 따로 저장해 source SHA assertion을 추가했고, distant non-intersecting Shape/Connector fixture anchor를 근접 비간섭 범위로 옮겼다. production Java engine/preflight, authentication/ownership/CSRF/Profile guards는 변경하지 않았다.

### 2. 합성 XLSX·렌더
- no-merge 및 merged-border 3×3 synthetic source/output pair를 `validation-results/pilot-11b-20261108/final-run-20261108-0619/workbooks/`에 보관했다. target `Layout!E1:G3`; output values, style ID/thin borders, source/output SHA, ZIP, POI reopen, dimension `A1:J10`, row/column geometry 및 source/output merges를 확인했다. merged case는 source `C2:C3`를 보존하고 target `G2:G3`만 추가했다.
- DrawingML part `xl/drawings/drawing1.xml`은 source/output byte-identical. Shape/Connector inventory도 보존했다. no-merge에는 Shape, merged-border fixture에는 Shape와 Connector가 들어 있다. target을 가로지르는 Shape/Connector는 차단된다.
- 기존 2-page LibreOffice PDF의 2쪽은 빈 페이지였다. 해당 synthetic workbook print area는 `A1:J10`, target `E1:G3`은 안쪽이다. worksheet에 `pageSetup`, `rowBreaks`, `colBreaks`는 없었고 `pageMargins`만 있었다. synthetic 진단에서 far-anchor DrawingML을 제거/근접 이동하면 같은 broad print area로도 1 page가 나와, 확인된 현상은 **LibreOffice 25.2.3.2 pagination/export와 distant DrawingML anchor의 상호작용**이다. target이 print area 밖인 경우가 아니며 Excel native renderer의 페이지 결과는 미확인. 최종 test-only anchor를 가까운 비간섭 위치에 두고 재생성한 두 LibreOffice PDF는 각각 1 page다. 공식 print setup은 변경하지 않았다.

### 3. 최종 테스트
- UTF-8 `./mvnw ... -DskipTests test-compile`: PASS.
- 최신 선택 회귀: **9 classes / 43 tests / failures 0 / errors 0 / skipped 0**. `DiagramBoxAddEngineTest` 3, `DiagramPlacementPreflightTest` 8, `DiagramBoxChangeEngineTest` 6, `DiagramBoxChangeEngineProfileModifyTest` 1, `DiagramEquipmentInsertionEngineTest` 12, `Pw0105EquipmentInsertionEngineTest` 6, `DiagramModifyServiceDeleteGateTest` 4, `GeneralDiagramEditProfileStatusTest` 1, `OoxmlRoundTripTest` 2. `surefire/`에는 실행 XML 9개, `SELECTED_REGRESSION.log`에 전체 명령/결과가 있다. 전체 Maven suite는 미실행이다.
- 회귀는 successful add 2종, shape/connector/merge/occupied/duplicate/stale SHA/replay 차단, source SHA와 deterministic output, legacy gap ADD/DELETE 안전 guard, profile status guard, OOXML roundtrip를 포함했다.

### 4. 증빙 및 적용 한계
- final run: `validation-results/pilot-11b-20261108/final-run-20261108-0619/` (Git ignored). 계획·템플릿·결과·DrawingML·cross-process 문서, JSON/TSV, source/output XLSX, PDF/PNG, render diagnosis, compile/test 로그, Surefire XML 및 SHA256SUMS 포함.
- reference `data/reference/SHA256SUMS`: 16/16 PASS; 공식 Excel 15개, `전체 목록.xlsx`, XLSM 및 사용자 작업본 수정 없음.
- 합성 데이터의 셀 기반 ADD만 검증했다. 필수 DrawingML Shape 신규 생성 미지원; 실제 위치 1,006건 `REVIEW_REQUIRED`; 기존 Connector 물리적 endpoint 미확정; 설비 추가에 따른 배관/유량 자동 반영 미구현; 실제 공정도 전체 박스 ADD 미승인; 운영 API/DB 미연결.
- DB/MariaDB, server/PM2, migration, deploy/Cloudflare Preview 미실행. verified physical flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.

### 5. Git closeout
- `README.md`, `CHECKPOINT.md`, 최종 fixture/test change만 정확한 경로로 stage. Validation artifacts/XLSX/PDF/PNG/official files는 stage하지 않는다. `git diff --check`, cached diff, full staged diff와 unstaged diff를 확인하고 통과 후 커밋 메시지 `Finalize PILOT-11B cell layout box ADD validation`을 사용한다. 최종 commit/HEAD 및 clean worktree는 closeout에서 보고한다.

---
