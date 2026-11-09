# 통합공정도 자동화 시스템 — 2차 2단계 첫 TEST_ONLY 물질수지 엔진

## 목적 및 범위
- **목표**: Java 17 + Spring Boot + MariaDB 기반의 사내 통합공정도 분석·변경탐지 웹서비스.
- **1차 범위**: 최초 기준자료 V1.0 등록, Excel 구조 분석, 설비/공정 매핑, 변경 탐지, 폐수·폐기물 이동 후보 추적, 실제 PW-01 OOXML 보존 테스트.
- **2차 1단계**: PW-01-05 삽입 검토 API, V5 TEST_ONLY 검토 도면 및 시각 검증을 보존.
- **2차 2단계 Step 2**: 비영 잔차 미확정 보고, terminal boundary/저장량 변화 모델, 송수신 물량 대사, cycle-safe 후단 설비·공정 조회, simulation-scoped transferId 및 TEST_ONLY receiver quantity 기록을 추가. 공식 자료 반영은 비활성화.
- **미구현**: 실제 업무자료 연동/승인 연결 자동 수집, 공식 PW-01-05 Excel 유량 변경, 단위 변환·실측 저장량 연속 추적·물리적 전체 보존 증명, 로그인/사원별 권한 및 운영 배포.

## 환경
- Temurin JDK `17.0.20.1` (기본 Java; 기존 OpenJDK 21도 설치된 상태)
- Spring Boot `3.5.16`, Maven Wrapper `3.9.9`
- MariaDB `11.8.6`, MariaDB JDBC `3.5.10`
- Apache POI / `poi-ooxml-full` `5.5.1`, Apache Commons Compress `1.28.0`
- Spring Boot 실행 포트: `3000`

## 현재 기준자료
- 기준 마스터: `전체 목록.xlsx`, **1332개 설비**, SHA-256 `1a08c1b58512f52954156644a61041c57022c3f230d7efbc188ad11615b986b4`
- 공정 원본: 15개, 총 62개 시트
- 실제 공정도 OOXML inventory: drawing 파트 61개, shape 586개, connector 8094개, picture 116개
- `data/reference/` 원본 복사본과 `SHA256SUMS`는 로컬 재등록용이며 Git에서 제외하고 읽기 전용으로 둠.
- 운영 요청 처리 시 파일을 로컬 런타임 폴더에 의존시키지 않고, 기준 원본과 업로드 원본 파일 바이트는 MariaDB `LONGBLOB`에 저장.
- V1.0 기준 마스터 및 원본 문서는 DB 트리거로 수정/삭제를 거부. 새로운 파일 업로드는 별도 `change_run`으로 저장되어 기준자료를 덮어쓰지 않음.

## 실제 기준 매핑 결과
- 관리번호로 매핑된 설비: 1291 (다중 시트 검토 37개 포함)
- 고유 Item No.만으로 매핑된 설비: 7
- 공정도에서 확인되지 않은 설비: 34 (미제공 PU-04 공정 파일 소속 5개 및 개별 미매칭 29개)
- 확인/검토 이슈: 130 (다중 시트 96건, 공정 파일 없음 5건, 시트 미매칭 29건)
- **PU-04 공정 파일은 제공된 15개 파일에 없음**. 해당 설비 5개는 `NO_PROCESS_DOCUMENT`로 남겨 두었고 다른 공정 파일에 임의로 매핑하지 않음.
- 이 값은 파일의 마스터·시트·텍스트 기준으로 계산했으며, 공정도 화면상의 실제 설비 위치를 모두 확인했다는 의미는 아님.

## API
관리 API에는 `.env`에 생성된 `BASELINE_ADMIN_TOKEN`을 `X-Admin-Token` 헤더로 전달해야 함.

- `GET /api/health`
- `POST /api/admin/baselines` — `masterFile` 1개와 `processFiles` 15개 multipart 등록. 같은 SHA-256 파일 재요청은 멱등 처리, 다른 파일로 V1.0을 덮어쓰지 않음.
- `GET /api/admin/baselines/current`
- `GET /api/admin/baselines/documents`
- `GET /api/admin/baselines/documents/{id}/download`
- `GET /api/admin/equipment?processCode=&q=&page=&size=`
- `GET /api/admin/mappings?status=&processCode=&limit=`
- `GET /api/admin/issues?severity=&limit=`
- `POST /api/admin/master-diff` — 수정된 마스터 Excel의 변경사항을 V1.0과 비교하고 새 `change_run`에 기록.
- `GET /api/admin/change-runs`, `/api/admin/change-runs/{id}`, `/api/admin/change-runs/{id}/items`, `/api/admin/change-runs/{id}/issues`, `/api/admin/change-runs/{id}/flows`
- `POST /api/admin/connector-analysis?processCode=PW-01` — connector 좌표·회전·화살표 메타데이터를 V1.0 `process_relation` 후보로 분석·저장. 실행 자체는 확정 관계를 만들지 않음.
- `GET /api/admin/process-relations?processCode=PW-01&status=REVIEW_REQUIRED&limit=500` — 관리자 검토 후보 조회.
- `PUT /api/admin/process-relations/{id}/evidence` — 설비 ID, 셀 범위 및 근거를 후보에 붙임. 상태는 REVIEW_REQUIRED로 유지하며 승인하지 않음.
- `PUT /api/admin/process-relations/{id}` — 기존 후보의 설비/도형 endpoint, A1 셀 범위, 관계 종류 및 검토 상태를 수정.
- `POST /api/admin/process-relations?connectorCandidateId={id}` — 권한 있는 관리자가 승인할 때 별도 관계 저장.
- `GET /api/admin/process-relations/{id}/history` — 후보 근거 추가·관계 생성·수정의 이전/이후 snapshot과 행위자 이력을 조회.
- `POST /api/admin/phase2/pw0105/equipment-insertion/preview` — 신규 설비 제안 검토. 삽입 위치, 전단·후단 설비, 담당자 확인이 없으면 `REVIEW_REQUIRED`를 반환하며 XLSX를 생성하지 않음.
- `POST /api/admin/phase2/material-balance/calculate` — `simulationId`, `scenarioName`, `mode=TEST_ONLY`, 가상 `nodes`, `transfers`를 받아 유입/유출/잔차·수신유형·저장량 변화·후단 설비/공정 범위를 반환. Node optional fields: `storageChange`, `storageFlowType`, `storageUnit`, `boundaryCondition` (`TERMINAL_DISCHARGE` 또는 `TERMINAL_OFFSITE`만 명시 terminal 처리). Transfer optional field: `receiverQuantity`; 제공되면 송수신 불일치는 edge 제외, 미제공이면 한 transfer record로부터 양 끝을 산출했음을 명시(독립 계측 대사가 아님). 비영 잔차는 `RESIDUAL_UNRESOLVED`와 필요한 추가자료 목록을 반환. 단위변환 없음.
- `POST /api/admin/phase2/material-balance/export` — `multipart/form-data`의 `request` JSON + 보존 V5 `workbook` XLSX를 받아 계산 후 새 `TEST_ONLY Results` worksheet만 추가한 XLSX attachment 반환. V5 SHA-256과 TEST_ONLY marker가 맞아야 하며, S/P2/W/D는 설비/셀에 매핑하지 않음. 운영 파일 및 official material balance를 수정하지 않음.
- TEST_ONLY edge는 증거/양/단위/endpoint 필요. CONFIRMED edge는 DB relation 승인 상태·검토자·시각·append-only 승인 이력·endpoint·quantity/unit/flow/edgeType 대조 뒤에만 포함. `simulationId` 같은 payload 재요청은 멱등이고 payload 충돌은 409. transferId는 V10부터 simulation 범위 unique이며, 동일 simulation 중복은 거부. 공식자료/production 유량 미변경 표시 유지.

## 데이터베이스
Flyway 마이그레이션은 `src/main/resources/db/migration/` 아래에 있음.

- `baseline_release`, `baseline_document`: 잠금된 V1.0 버전과 전체 xlsx BLOB, 원본 SHA-256
- `equipment_record`, `equipment_flow`: 헤더 기반 설비 데이터와 명시적 폐수/폐기물 정보
- `workbook_sheet`, `drawing_object`: 원본 시트·shape·connector·anchor·화살표·EMU 변환 좌표·회전·flip inventory
- `equipment_mapping`, `validation_issue`: 시트 매핑 결과와 검토 목록
- `process_relation`: 자동 connector 세그먼트 후보와 승인된 관계를 별도 상태로 저장. V1.0 원본은 start/end shape 연결 ID가 없어 자동 설비관계를 확정하지 않음. 근거 첨부만으로 상태가 바뀌지 않으며, 행위자·이전/이후 snapshot은 `process_relation_review_history`에 append-only로 기록.
- `change_run`, `change_item`, `change_validation_issue`, `change_flow_candidate`: 새 업로드 파일의 hash/원본 BLOB, 변경 전후, 이동 후보와 검토 상태
- `process_relation_review_history`: 관계 후보 증거 추가/생성/수정 감사 기록. reviewer 승인 여부와 무관하게 행위자·기존/변경 snapshot 보존
- `material_balance_simulation`, `material_balance_transfer` (V9–V11): TEST_ONLY simulation/edge, result JSON, request hash, simulation-scoped transferId uniqueness, optional receiving-side quantity 및 excluded reasons 저장. V9 원본 migration은 보존하고 V10에서 복합 unique로 변경, V11에서 nullable receiver_quantity를 추가. 공식 `baseline_document`, `equipment_record`, `equipment_flow`에는 쓰지 않음.

현재 기준 마스터의 폐수·폐기물 입력 열은 모두 공란이므로 기준 `equipment_flow` 수는 0. 공란을 0으로 변환하지 않음. 새 업로드의 `0`은 `EXPLICIT_ZERO`, 정보 일부 누락은 `INCOMPLETE`, 식별되지 않는 이동처는 `UNRESOLVED_DESTINATION`으로 저장하며 모든 후보는 `route_created=false`임. 이동 관계가 검증될 때까지 자동 공정 경로로 확정하지 않음.

## 실행 / 재구성
Debian/Ubuntu 샌드박스에서 `sudo`와 인터넷 패키지 저장소 접근 필요.

1. `scripts/bootstrap-sandbox.sh` — Java 17/Maven/MariaDB 준비, `.env` 생성, 첨부가 있을 때 원본 16개를 읽기 전용으로 `data/reference/`에 복사, MariaDB 계정/DB 설정.
2. `pm2 start ecosystem.config.cjs` — Spring Boot 개발 서버 실행.
3. `scripts/import-baseline.sh` — SHA-256 확인 후 기준 원본 16개를 API로 MariaDB에 등록.
4. `scripts/test-local.sh` — Maven `clean verify` 및 DB/Excel/실제 PW-01 테스트.
5. `http://localhost:3000/api/health` — 상태 확인.

재시작/새 샌드박스에서 원본 파일이 없으면 제공받은 동일 원본 16개를 `/home/user/uploaded_files/`에 다시 넣은 뒤 bootstrap/import를 실행해야 함. `.env`에는 DB 비밀번호와 관리자 토큰이 있으며 Git에서 제외됨. Maven Wrapper는 `./mvnw`로 실행 가능.

## Excel / OOXML 보존 원칙 및 한계
- Apache POI로 셀·값·서식 및 시트 내용을 읽고, POI의 계산 캐시를 사용해 외부 링크 수식 재계산 오류를 피함.
- `OoxmlPartEditor`는 단일 worksheet XML 파트만 바꾸고 다른 ZIP 파트는 raw compressed data로 복사.
- 실제 `PW-01` 원본에서 `PW-01-01!D9` 값을 테스트용으로 수정한 복사본을 생성해 원본 hash, 모든 다른 OOXML 파트, 시트 drawing/shape/connector metadata, 대상 셀 스타일, 병합 수, 인쇄 설정을 확인. 테스트 산출물은 `target/test-artifacts/pw01-direct-ooxml-test.xlsx`.
- 실제 파일 inventory에서 명시적인 connector start/end shape binding은 0개였음. anchor 및 xfrm 좌표만으로 공정의 논리 연결을 자동 확정하지 않음. 관리자가 확인한 관계는 `process_relation`에 저장하는 API/DB 구조를 마련함.
- 2026-10-15 PW-01 시각 비교: 읽기 전용 V1.0 원본을 별도 파일로 복사하고 OOXML 셀 수정 테스트본을 별도 복사, LibreOffice 25.2.3.2로 두 파일을 PDF 렌더링함. 같은 A3 출력 설정/32페이지, 픽셀 차분 31페이지 동일·1페이지의 수정 셀만 차이. 산출물은 `validation-results/2026-10-15/pw01-05/`에 두어 Maven `clean`에도 유지함. Excel 네이티브 렌더링과 동일하다는 뜻은 아님.
- 이 검증은 PW-01의 한 셀 수정 사례에 대한 기술 검증이며 모든 복잡한 도형·관계 유형의 일반적 보존을 보증하지 않음. 도형·화살표 자동 수정 전에는 공정별 원본 대표 파일 렌더링 비교와 사용자 승인 기준이 필요함.

## 검증 결과 / 남은 사항
- 2026-10-19 단계별 검증: Step 3 `MaterialBalanceCalculatorTest` 6/6, `MaterialBalancePersistenceIntegrationTest` 7/7, `MaterialBalanceExcelExportIntegrationTest` 3/3, `PhaseTwoPlanningTest` 1/1 통과(총 17, 실패/오류/skip 0). 전체 Maven suite는 실행하지 않음.
- TEST_ONLY 출력 파일 `validation-results/2026-10-19-step3/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V1.xlsx`; 렌더링 PDF/PNG와 기존 32p 비교는 `PHASE2_2_STEP3_REPORT.md` 참조. V5 및 V1.0 source는 변경되지 않음.
- 가상 시범값 100, 0.5, 2.5, 97 ton/day를 사용했고 수신 공정 후단 이동 2.25 ton/day의 잔차 0.25를 검증. 공식 XLSX와 V1.0 자료를 적용하지 않음.
- MariaDB V9–V11 적용. V10 unique key는 `(simulation_db_id, transfer_id)`; V11 receiver quantity는 nullable. 통합 테스트 transaction rollback 후 simulation rows 0건; baseline docs 16/equipment 1,332/CONFIRMED relation 0/REVIEW_REQUIRED 1 유지.
- 기존 단일 전체 테스트는 Surefire Exit Code 137 이력이 있어 재실행하지 않음. 전체 suite 상태는 미확인.
- 테스트 fixture는 테스트용 편집본에서만 약 109,693개 항목 calcChain을 비우도록 하여 POI 직렬화 비용을 낮춤. V1.0 파일/DB BLOB은 변경하지 않음.
- Connector integration test는 실제 관리자 API 검증으로 이미 생성된 history를 1건만 예상해 실패했음. 기존 이력 수 + 1 및 새 행위자/action을 검증하도록 수정했고 최종 5건 통과.
- 관리자 API의 실제 list 응답 evidence key는 `connectorEvidence`(후보 검토 문구는 `candidateEvidence`). 이전 HTTP 검증기 `evidence` key 오류를 고쳐 HTTP 200 GET/이력 조회를 확인. 후보 30579는 `REVIEW_REQUIRED`; 승인 관계는 0건.
- V1.0 원본 16개 로컬 파일·manifest·MariaDB metadata·BLOB SHA-256 최종 재검증: 16/16 PASS. Flyway V1–V9 적용; MariaDB 11.8이 Flyway 검증 상한 11.2보다 새롭다는 경고는 남음.
- 완료: 기준 16개 파일 BLOB 등록, 1332 설비 파싱, 시트/drawing inventory, 변경탐지 API, 검토용 흐름 후보 저장, 실제 PW-01 XML part-edit test 및 connector 검토 API, 2차 삽입 검토 API, 첫 TEST_ONLY 물질수지 엔진과 V9 persistence/승인 검증.
- 2차 2단계 Step 2 후 미완료: 독립 계측 receiver quantity가 실제 자료에 적용된 사례, 실제 승인 연결 기반 시나리오(현재 CONFIRMED 0), 저장량 실측 및 연속기간 보존 모델, unit conversion, 공식 PW-01-05 유량 수정, downstream migration/change-run 자동 생성. 별도 지시 전 실제 도면은 수정하지 않음.
- 상세 결과: `PHASE2_2_STEP2_REPORT.md`. 다음 단계의 Excel 반영은 별도 지시와 검토 승인 후 TEST_ONLY 복사본에서 시작하며 공식 V1.0은 계속 read-only.
- 2차 2단계 첫 시범 보고서: `PHASE2_2_STEP1_REPORT.md`. V5 파일은 유지했고 7바이트 차이는 V4→V5 sheet XML 공백 placeholder 보존 변화로 확인, backup V5와 현 V5 hash는 일치.
- 외부 서버 배포, GitHub push, 팀원 로그인은 진행하지 않았음. 기존 단일 Maven 전체 실행 Exit 137은 계속 미해결이며, 클래스별 분리 검증을 유지. 실제 업무자료·Excel 수정은 다음 사용자 지시 대기.

## 2차 2단계 Step 4 — C-PW15020 TEST_ONLY 도면 물량 표시 (2026-10-19)
- `POST /api/admin/phase2/material-balance/export-cpw15020-test-only`는 TEST_ONLY V5와 명시된 4-edge scenario를 받아 별도 XLSX를 반환. 자동 도면 탐색으로 C-PW15020 box와 폐수·폐기물 compartment 라벨 셀을 식별하며 주소/shape ID 입력은 받지 않음.
- 기존 우측 compartment는 라벨과 구획선이 있는 좁은 칸이라 직접 기록하지 않음. 새 `TEST_ONLY Results` 시트에 flow map summary를 추가하고, 기존 PW-01-05 worksheet/drawing/arrow/print area는 보존. 기존 V5와 Step 3 V1은 덮어쓰지 않음.
- 시험값 100 ton/day 유입, 2.5 폐수 미지정 수신처 이동, 0.5 폐기물 발생, 97 후단 이동을 표시. `100 = 0.5 + 2.5 + 97` 계산 및 XLSX 재독 값 대조 PASS. 수신처 및 폐기물 처분/반출은 확정하지 않음.
- XLSX: `validation-results/2026-10-19-step4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V2.xlsx`; SHA-256 `90022b35d800d3b0f0944e256e46f5081605afb5ffb4373b22c39171871f0acc`. PDF/PNG 300 dpi 및 V5 vs V2 page 27 pixel identity 비교는 `validation-results/2026-10-19-step4/`.
- Step 4 tests: diagram TEST_ONLY export/API integration 2/2 + calculator 6/6 통과. 기존 전체 Maven suite는 실행하지 않음. 공식 V1.0 원본 16개/manifest/MariaDB metadata/BLOB hash 16/16 PASS; 기준 DB 16문서/1,332설비/CONFIRMED 0/REVIEW_REQUIRED 1 유지.
- 상세 보고 `PHASE2_2_STEP4_REPORT.md`. C-PW15009→C-PW15020 및 C-PW15020→C-PW15010은 시각 connector evidence일 뿐 승인 관계가 아니며 계속 REVIEW_REQUIRED. 이번 변경은 C-PW15020 한 설비의 TEST_ONLY 표시 검증까지만 수행.

## Step 6-1 — 마스터 변경 탐지 및 도면 영향 분석 (2026-10-19)
- 변경된 전체 목록 `.xlsx` 1개는 `POST /api/admin/master-diff`로 업로드하며, 비교 기준은 잠금 V1.0 MariaDB baseline. 업로드는 `change_run.analysis_mode=TEST_ONLY`로 보관되고 기준 원본을 덮어쓰지 않음.
- `/master-diff.html`에서 관리자 토큰을 브라우저 입력 후 단일 파일을 요청에 포함. 토큰은 브라우저 저장소에 저장하지 않음.
- 결과는 추가/삭제/수정/상대 순서 변경, 필드 전후값, 도면/시트 근거 후보 및 `REVIEW_REQUIRED`를 제공. 공정번호 변경은 V1.0 위치를 역사적 근거로만 보고하고, 미매핑·다중 시트·공정코드 후보는 별도 review로 유지.
- 공식 V1.0 폐수·폐기물 입력은 현재 모두 공란. 다른 기준 데이터에 비공란 기존값이 있을 때 업로드 공란은 삭제/0으로 추정하지 않도록 policy 단위 테스트가 있음. 실제 업무 변경본이 도착하면 새 TEST_ONLY run으로 담당자 검토 필요.
- V12는 `change_run`/`change_item`에 TEST_ONLY 모드 및 mapping/impact/review 저장 컬럼 추가. MariaDB schema V12 적용 확인.
- 최종 관련 테스트: `MasterChangeDetectionIntegrationTest` 18/18, `MasterDiffControllerTest` 3/3, `MasterFieldChangePolicyTest` 2/2, `Pw0105EquipmentInsertionEngineTest` 1/1; 총 24건 통과. 전체 Maven suite는 과거 Exit 137 때문에 미실행.
- 실제 로컬 API/MariaDB multipart run 171 저장/조회 PASS; 익명 업로드는 401. TEST_ONLY 파일과 Step 5 DrawingML IDFIX 보호 파일은 `STEP6_1_FINAL_REPORT.md`에 파일 경로/hash 기록.
- API 및 페이지 route를 시험했으나 UI 브라우저에서 실제 파일 선택/업로드/결과 조작은 미검증. 공식 도면 수정/관계 생성/Step 6-2는 하지 않음.
- 상세 검증과 기준 해시/DB 무결성 결과: `STEP6_1_FINAL_REPORT.md`; 최신 상태는 `CHECKPOINT.md`의 Step 6-1 항목 참고.

## 발표용 가상 DEMO — 2026-10-20
- `/master-diff.html`에서 **가상 데이터 시연 → 가상 결과 화면 보기**를 누르면 토큰 없이 합성된 결과 화면을 확인할 수 있음.
- 이 버튼은 고정된 브라우저 fixture만 렌더링하며 파일 전송, 관리자 API 요청, MariaDB 조회/저장, 실제 기준 마스터 비교 또는 도면 수정 요청을 하지 않음. 예시 숫자/ID/공정/파일명은 전부 가상이며 결과 영역에도 “가상 데이터 시연”이 표시됨.
- 실제 Excel 업로드와 저장 run 재조회는 기존과 같이 관리자 API 토큰이 필요함. 기존 토큰 guard와 API contract는 유지.
- DEMO는 UI 체험용이지 실제 비교 알고리즘/DB 저장 테스트가 아님. 현재 임시 발표 URL은 샌드박스 개발 주소이며 운영/회사 공개 URL이 아님.

## 일반 직원 로그인 계획 — 미구현
- 최종 목표(2026-10-29): Spring Security 기반 서버측 세션, username/password, 사용자/관리자 role 구분, Argon2id 또는 Spring `DelegatingPasswordEncoder` hash, HttpOnly/Secure/SameSite 쿠키, CSRF, 로그인 rate limit, session rotation/timeout/logout.
- 일반 직원 권한과 관리자 전용 baseline/account/approval 권한은 서버측 API에서 분리. 기존 `X-Admin-Token` API는 전환·회귀검증 전까지 제거하거나 약화하지 않음.
- 이번 작업에서는 로그인 dependency, auth DB migration, 계정/세션 API를 추가하지 않았음. 단계별 구현·일정·출시 전 테스트 계획은 `CHECKPOINT.md`의 “일반 직원 로그인 설계” 항목 참조.
- 10/22 시연 전 인증 구조 변경은 보류. 10/29 로그인 시연은 HTTPS/계정 운영과 보안·회귀시험을 통과할 때만 수행.

## 최신 인증/DEMO 시험 메모
- headless Chromium: DEMO click 후 `/api/*` 요청 0건, 합성 표지/결과 표시, JS page error 0건.
- 익명 baseline/run/items/issues 조회 및 익명 master-diff upload: HTTP 401. 잘못된 토큰 upload UI도 401이며 token field는 요청 후 비움.
- Java API/MariaDB/migration/공식 원본은 이 변경에서 수정하지 않음. 로그인 시스템은 미구현.
- 전체 Maven suite는 실행하지 않음; 이전 전체-suite Exit 137 이력 때문에 이번 UI 전용 변경에 불필요한 전체 재실행을 하지 않음.
- 현재 배포 URL: 없음. 이 수정은 로컬 샌드박스에서만 확인했으며 외부 배포하지 않음.
- **데이터 보존**: 공식 V1.0 16개 원본과 기준 설비 1,332개, MariaDB 공식 기준자료 및 기존 Step 6-2 LABELFIX는 읽기 전용 보존.
- **사용 방법**: `/master-diff.html` 접속 → 가상 체험은 DEMO 버튼 클릭; 실제 분석은 관리자 토큰을 입력하고 TEST_ONLY Excel 한 개를 선택.
- **다음 단계**: 10/22 발표 전에는 기능 동결 및 smoke test만 수행. 발표 후에만 계획된 로그인 단계를 구현.

## Step 6-5 — 웹 통합 Excel 생성·다운로드 (2026-10-21)

### 구현 및 지원 범위
- `/master-diff.html`에서 저장 분석 run → 실제 변경 항목 → 같은 run/item에 연결된 저장 편집 계획을 조회하고, 선택한 계획의 통합 TEST_ONLY Excel 생성 및 다운로드를 지원.
- 현재 안전 검증 범위는 **run 193 / item 217 / plan 33 / C-PW15020 / PW-01-05** 한 건뿐이며, 범용 도면/설비 자동화로 간주하면 안 됨. 서버는 전달된 run/item/plan 연결, 대상 문서·시트, 계획 상태, TEST_ONLY 및 REVIEW_REQUIRED 조건을 다시 검사.
- 기존 `diagram_edit_plan` 도면 중간 산출물은 덮어쓰지 않음. 완성본은 별도 V14 `diagram_edit_integrated_output` 저장소에 시나리오별 BLOB과 SHA-256을 보관.
- 시험 물질수지 값(유입 100, 후단 97, 폐수 이동 2.5, 폐기물 0.5 ton/day)은 사용자가 가상 TEST_ONLY 값 사용을 명시적으로 선택한 경우에만 출력. 선택하지 않으면 실제자료 부족/검증 불가 상태만 표시하고 유량, 합계, 잔차를 생성하지 않음.
- 관계 상태는 `REVIEW_REQUIRED`로 유지. CONFIRMED 관계, 승인 흐름 변경, 로그인, 다중 도면 ZIP, 공식 도면/공식 물량 갱신은 이 범위에 포함되지 않음.
- 관리자 토큰은 `X-Admin-Token` 요청 헤더로만 전달하며 URL/localStorage/sessionStorage에 저장하지 않음. API 인증은 기존 관리자 guard 사용.

### Step 6-5 API 경로
- `GET /api/admin/diagram-edit-plans/by-run/{changeRunId}/items/{changeItemId}` — 해당 저장 run/item에 연결된 실제 편집 계획 조회.
- `POST /api/admin/diagram-edit-plans/{planId}/generate-integrated-test-only` — JSON `{ "changeRunId": 193, "changeItemId": 217, "includeSyntheticTestValues": true|false }` 제출. XLSX bytes 대신 파일명/SHA-256/scenario/run/item/plan/시트 수를 포함한 JSON metadata 반환.
- `GET /api/admin/diagram-edit-plans/{planId}/download-integrated?includeSyntheticTestValues=true|false` — 사전 생성·검증된 시나리오별 XLSX attachment 다운로드. 관리자 토큰 필수.

### 검증 결과 및 산출물
- 실제 브라우저에서 저장 run 조회, plan 33 선택, 통합 생성, 다운로드 버튼으로 파일 저장까지 수행. 다운로드 파일은 Apache POI로 재열어 8개 시트, active sheet, run/item/plan 및 100/97/2.5/0.5 시험값, 합계 formula/cached value 100, 잔차 formula/cached value 0, print area A1:C25를 검증. `unzip -t` 무결성 검사 통과.
- 브라우저 다운로드 파일 SHA-256 `279452398e84b9cb9466ad62f0310d4cbc15dcc0160ae20103d82219153833f5`. 생성 metadata·MariaDB BLOB·브라우저 다운로드의 해시가 모두 일치. 로컬 산출물: `/tmp/step65-browser-download/TEST_ONLY-run193-item217-plan33-PW-01-05-integrated-synthetic-values.xlsx`.
- 익명 생성/다운로드는 HTTP 401, 다른 run의 plan 연결은 HTTP 409, 잘못된 plan ID는 HTTP 400으로 차단. 실제 승인 상태는 REVIEW_REQUIRED 유지.
- 최종 targeted suite: `DiagramEditPlanIntegratedControllerTest`, `DiagramEditPlanControllerIntegratedWorkbookTest`, `Step6IntegratedWorkbookDeliveryIntegrationTest`, `Step6IntegratedWorkbookStorageIntegrationTest`, `Step6TestOnlyWorkbookEnrichmentIntegrationTest`, `MaterialBalanceExcelExportIntegrationTest` 총 15/15 PASS, failures/errors/skips 0. 전체 Maven suite는 실행하지 않음.
- 배포 URL은 없음. 이 기능은 로컬 샌드박스에서 브라우저/API/MariaDB로 검증했으며 외부 배포하지 않음.

## Step 7-1 — TEST_ONLY 유향 연결망 물질수지 (2026-10-21)

### 구현 범위 및 재사용
- 기존 `MaterialBalanceCalculator`에 directed network 계산 진입점을 추가하고 기존 고정 유량 `MaterialBalanceRequest` 경로는 유지. 새 package-private linear solver는 연결망의 고정·미지 엣지 유량을 함께 푸는 보완 경로이며 DB write/API/UI는 연결하지 않음.
- `MaterialBalanceService`의 TEST_ONLY/승인 경계 확인 구조와 기존 Excel export를 조사해 재사용 범위를 정함. 실제 관계 조회·저장, approval status 변경, V5에 삽입을 하지 않으며 detached 합성 결과/입력양식 전용 writer 사용.
- 모델은 설비·외부 경계 Node, 방향성 Edge, ProcessTerm으로 구분. 설비 관리번호/공정/유형/경계/도면·시트/검토상태와 연결 ID/양 endpoint/물질 구분·종류/단위/유량·입력 basis/user supplied/fixed/evidence/review status를 표현.
- 물질종류와 단위별로 별도 선형 보존식을 구성해 RREF/Gaussian elimination으로 풀이. 미지량이 유일하지 않으면 `UNDERDETERMINED`; 입력 연결/근거가 부족하면 `CALCULATION_UNAVAILABLE`; fixed data residual이면 `RESIDUAL_UNRESOLVED`; 음수 해는 `CONSTRAINT_VIOLATION`으로 보고. 어떤 경우에도 임의 연결 추정, 음수 보정, 단위 환산은 하지 않음.
- 변경 연결의 연결성분에서 영향을 받는 설비번호·공정코드를 추적. wastewater/chemical/waste 및 ton/day·kg/day 등 단위가 다르면 별도 balance로 유지. 발생·소모는 명시적인 `ProcessTerm`만 반영.

### 합성 시나리오 검증
- A: P1/P2 외부 유입 각각 100, P2→P1 이동 20 → P2 후단 80, P1 후단 120, 전체 외부 유입/유출 200/200.
- B: 동일 기준 연결망에서 각 시나리오를 독립 계산. 이동 20/10/50에 따라 P2 후단 80/90/50, P1 후단 120/110/150. 앞 실행 계산값이 다음의 기준에 누적되지 않음.
- C: 이동 120을 지정하면 P2 후단의 algebraic flow가 -20으로 나타나고 제약 위반 표시. 유량을 0 또는 다른 배분값으로 자동 보정하지 않음.
- D: interprocess recycle 20→30에서 P1 process throughput 120→130, P2/P1 수지 균형, 전체 외부 유입/유출 100/100 유지. 단, 재순환 값은 명시적으로 주어진 조건이며 순환식의 미지수가 부족하면 별도로 UNDERDETERMINED.
- E: 도착 설비 미해결/근거 누락은 `CALCULATION_UNAVAILABLE`; 연결 추정 없음. 미지수로만 구성한 닫힌 순환은 `UNDERDETERMINED` 및 미지 edge 값을 null로 남김.
- 추가 검증: 명시 generation 20 + net generation 10 → solved output 30; 고정된 100 in / 90 out → residual 10 보고. 폐수 10 ton/day, 약품 2 kg/day, 슬러지 0.5 ton/day는 따로 계산.

### 산출물
- 상세 scenario A 결과 workbook: [TEST_ONLY-network-scenario-A.xlsx](https://www.genspark.ai/api/files/s/hi7BEk1W)
- 전체 시나리오 비교표(20/10/50, 음수 제약, 재순환, 자료부족, 미정 순환): [TEST_ONLY-network-scenario-matrix.xlsx](https://www.genspark.ai/api/files/s/guEE5w0E)
- 별도 전체목록 연결망 입력 template (`기준 연결망`, `배관·유량변경`, `시뮬레이션 설정`, `작성 안내`): [TEST_ONLY-process-network-input-template.xlsx](https://www.genspark.ai/api/files/s/h3uJU9hw)
- 파일 경로는 `validation-results/2026-10-21-step7-1/`; 해당 폴더는 Git ignore. 공식 V1.0 전체목록/15개 도면에 시트 추가나 쓰기를 하지 않음.

### 검증 및 제한
- `DirectedProcessNetworkSolverTest` 8/8 PASS. Step 6-5 회귀(Controller/Delivery/Storage/Enrichment/V5 export) 15/15 PASS. 최종 합계 **23/23 PASS**, failures/errors/skips 0. 전체 Maven suite는 실행하지 않음.
- `scripts/verify-baseline-hashes.py`: 16/16 공식 V1.0 파일 hash가 local/manifest/MariaDB metadata/DB BLOB과 일치. Step 6-5 plan33 파일 SHA 및 동작 회귀 테스트 유지.
- 이번은 합성 선형 네트워크 계산 prototype이지 실제 전체 공정도 자동화가 아님. 실제 설비 relation 수집/승인, 유량 입력 mapping/검증, 비선형·동적 공정, 저장량/time series, production API/UI, 실제 workbook/도면 반영은 다음 단계 과제. Web UI와 다중 도면 편집도 구현하지 않음.
- 외부 배포 없음. 현재 작업 브랜치는 `step7-1-wip-20261021`; main 미병합.

## Step 7-2 — 팀원 물질수지 DB와 V1.0 비교 (2026-10-23)

### 처리 범위
- `Step7MaterialBalanceComparisonService`는 팀원 XLSM을 읽기 전용으로 파싱한다. VBA·Power Query 새로고침·수식 재계산 없이 입력시트 저장 값과 `비교` 시트의 기존 캐시만 읽는다.
- 출발/도착 ID는 공식 V1.0 전체 목록의 관리번호와 정확 문자열 대조만 한다. 공식 연결 endpoint가 명시적으로 증명되지 않으면 `INSUFFICIENT_EVIDENCE`; 숫자 차이만으로 DB/도면 오류를 확정하지 않는다.
- 공식 PW-01 endpoint binding 0, 공식 `equipment_flow` 0행 상태를 표시하고 기존 `REVIEW_REQUIRED` 연결을 승인하지 않는다. 팀원 파일의 질량 0/공란, 물질 종류 공란, 계산 제외 공란, 반복 endpoint 쌍을 별도 유지한다.
- 저장 비교 캐시는 당시의 계산 결과로만 기록한다. 기존 불일치 잔차와 가상 신규 변경 전후 잔차를 분리해 새 변경 오류로 오분류하지 않는다. PW1 대분류 방향코드의 SCC는 순환 후보로만 표시하며 물량 계산·관계 승인에 사용하지 않는다.

### TEST_ONLY 결과
- `validation-results/2026-10-23-step7-2/TEST_ONLY-step7-2-material-balance-comparison.xlsx` — 전체 요약, 3,082행 연결별 대조, 저장 잔차/검토 캐시, 순환·근거 부족, 전후잔차 합성 시험예시(5개 시트).
- 결과 시트는 맑은 고딕/검정 글자. 공식 V1.0 16개 도면·마스터 및 MariaDB 승인/관계 자료, 팀원 원본은 수정하지 않는다.
- 팀원 원본 SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`.
- 업로드 당시 입력: 3,082행; PW-01 scope 504행/501 endpoint 쌍; 양 endpoint 마스터 정확 매칭 2,777행, 한쪽 305행. 질량 양수 1,975/0 1,106/공란 1, 물질 종류 공란 2,830, 계산 제외 공란 3,082, 중복 endpoint pair group 44(관련 행 95).
- 비교 캐시 1,179행은 저장 시점의 값(비교 TRUE 1,048/FALSE 130/미기재 1, 잔차 공란 30/비영 101). 이는 공식 오류 판정이나 현재 재계산이 아님. 캐시 기준시점·단위가 없어 자료 차이는 원인 미확정/근거 부족.

### 검증·제한
- 새 `Step7MaterialBalanceComparisonServiceTest`: 실제 XLSM 로딩·5시트 XLSX 생성·POI 재열기·맑은 고딕/검정 확인 및 A–H 상태/기준잔차 분리 단위검증 3/3 PASS.
- Step 7-1 solver 8/8 + Step 6-5 회귀 15/15 및 Step 7 단위 테스트 2/2 PASS, 통합 명령 25 PASS / 0 실패 / 0 오류 / 1 환경조건 skip. 실제 XLSM 생성 포함 별도 Step 7 테스트는 3/3 PASS.
- `scripts/verify-baseline-hashes.py`: V1.0 원본/manifest/MariaDB metadata/BLOB 16/16 PASS. 전체 Maven suite는 실행하지 않음.
- 실제 도면 연결 endpoint·유량 및 기간/단위 증거가 없어 연결별 공식 비교 결과를 `MATCHED`, `DB_ERROR_CONFIRMED`, `LEGACY_DIAGRAM_ERROR_CONFIRMED`로 확정한 건은 0. 신규 변경 event 자료도 제공되지 않아 실제 신규 잔차는 산출하지 않음.
- 외부 배포 없음. Step 6-5 다운로드와 Step 7-1 계산 엔진은 보존. 현재 WIP 브랜치 `step7-2-wip-20261021`, main 미병합.

---

## Step 7-4A — PW-01-05 담당자 연결 검토대장 (TEST_ONLY)

### 사용 방법 및 API
- 관리자 `X-Admin-Token`이 필요하다. 실제 팀원 `.xlsm`을 `teamWorkbook` multipart 필드로 `POST /api/admin/step7-3/pw-01-05/evidence-workbook`에 보내면 33개 자동 후보를 포함한 XLSX가 attachment로 반환된다. 응답의 `X-Review-Snapshot-Id` 및 `X-Review-Template-Version`을 검토 시 함께 보존한다.
- `담당자 검토대장`에서 노란 입력 칸을 작성한다. 주요 입력 열은 `source_equipment`, `target_equipment`, `review_material_type`, `review_actual_quantity`(Excel 숫자 셀; 0과 공란 구분), `review_unit`, `review_period`, `physical_connection_confirmed`, `evidence_type`, `evidence_identifier`, `review_evidence`, `reviewer_name`, `reviewed_date`, `review_comment`, `review_decision`, `review_cycle_candidate`이다. 자동 생성 식별/원본/snapshot/version 필드는 수정하지 않는다. `test_only_evidence_accepted=YES`, `official_approval_created=NO`를 유지한다.
- 작성본은 `reviewWorkbook` multipart 필드로 `POST /api/admin/step7-3/pw-01-05/review-ledger`에 업로드한다. 응답의 `valid`, `issues`(행·열·코드·설명), `warnings`, 저장/무시된 행 수를 확인한다. 오류가 하나라도 있으면 업로드 전체를 저장하지 않는다. 이전 버전 파일은 version conflict로 거부되므로 최신 대장을 다시 다운로드한다.
- 후보별 최신 상태: `GET /api/admin/step7-3/pw-01-05/review-snapshots/{snapshotId}/candidates/{candidateId}/status`. 변경 이력: 같은 경로 끝을 `/history`로 요청한다. 관리자 토큰이 두 조회 모두 필요하다.

### 검토 상태와 한계
- `EVIDENCE_VERIFIED`는 담당자가 실제 연결 확인과 구체적 근거, 물질·숫자 유량·단위·기간을 제출한 **검토대장 상태**이며 공식 승인 상태가 아니다. 공식 관계 승인 API 및 검토 절차와 별개이고 이 기능은 `process_relation`을 생성/승인하지 않는다.
- 실제 배관 연결을 확인할 수 없으면 `REVIEWED_PROVISIONAL` 또는 `NEEDS_MORE_INFO`로 남긴다. `EVIDENCE_VERIFIED`를 선택해도 필수 정보가 빠지면 `NEEDS_MORE_INFO`로 낮추며, 증빙 없는 연결은 자동 승인하지 않는다.
- 이번 검증에서 공식 승인 관계 0건, 저장된 `EVIDENCE_VERIFIED` 검토 0건. 이 Excel은 TEST_ONLY 담당자 입력/검토 이력용이며 공식 도면과 기준 데이터는 수정하지 않는다.
- 최종 검증 결과와 산출물 SHA는 `CHECKPOINT.md`의 Step 7-4A CHECKPOINT B–D 기록을 참조한다.

---

## Step 7-4B — 검증된 연결정보의 TEST_ONLY 계산망 연계

### 구현 및 안전 경계
- `Step7ReviewNetworkWorkflowService`는 저장된 V16 snapshot/candidate/case/history와 잠금 V1.0 설비 마스터의 `middle_process_code`(필요 시 `major_process_code`)를 읽기 전용으로 조회한다. 새 API, DB migration, 검토 기록 변경, 승인 관계 생성은 추가하지 않았다.
- `Step7ReviewNetworkAdapter`는 typed review/route/scope 입력을 `DirectedProcessNetwork.Node`, `Edge`, `ProcessTerm`으로 변환하는 fail-closed 경계다. `EVIDENCE_VERIFIED`만으로 edge를 포함하지 않으며 현재 버전·이력·물리 연결·endpoint/mapping·경로/방향·물질·유량·단위/기간·경계/분기/순환/scope 완결성 증빙이 모두 있어야 한다.
- 동일 endpoint의 별도 배관은 경로별 `Edge`/ID로 유지한다. 미검토/거절/추가정보 필요/낡은 이력, 단위 차원/기간/가스 기준조건 부족, 경로·scope 불완전은 편입하지 않고 사유를 반환한다. 단위·기간·물질 환산은 하지 않는다.
- 실제 PW-01-05는 후보 33건, `EVIDENCE_VERIFIED` 0건, 적격 계산망 편입 0건으로 확인했다. 실제 공정 수지는 계산하지 않았다. `EVIDENCE_VERIFIED`는 기술 검토 상태이지 공식 `CONFIRMED` 승인이 아니다.
- 기존 `MaterialBalanceCalculator.calculateDirectedNetwork` 및 `recalculateDirectedNetwork`와 Step 7-1 solver를 그대로 사용했다. 기준 수지 상태, 계산 가능 여부, 신규 변경 영향은 별도 반환하며, stream별 잔차는 물질/단위별로 보존한다.

### 합성 시나리오 및 결과 파일
- 격리된 synthetic TEST_ONLY fixtures로 정상 변환, 불완전 단위·경계, UNREVIEWED 제외, 동일 설비쌍 별도 경로, 전체 scope 부족, 이동 20→10/50, 재순환 20→30, 과도 유량의 음수 제약, 기준 잔차 +5→변경 후 +8 / delta +3, 오래된 이력을 시험했다.
- 결과 통합문서: `validation-results/2026-10-23-step7-4b/Step7-4B_연결망_계산준비_검증_TEST_ONLY.xlsx`. 이 파일의 actual readiness sheet와 synthetic solver 결과는 분리되어 있다. 전체 5개 시트, 맑은 고딕/검정 글자, 수식 없음, ZIP/POI 재열기 검증을 통과했다.
- 이 보고서는 로컬 TEST_ONLY 검증 산출물이며 실제 운영 연결망 또는 실제 물질수지 결과가 아니다. 현장 증빙과 완결된 graph scope를 받은 뒤 별도 검토해야 한다.

### 시험 및 원본 보존
- 신규 `Step7ReviewNetworkIntegrationTest` 1/1 PASS. Step 7-1 solver 및 Step 6-5 targeted 회귀 23/23 PASS. Step 7-3/7-4A 및 Step 7-2 입력 기반 targeted 회귀 9/9 PASS. 전체 Maven suite는 실행하지 않았다.
- 공식 V1.0 원본/manifest/MariaDB metadata/BLOB SHA-256 16/16 PASS. 팀원 XLSM SHA-256은 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`로 기존 값과 일치. 실제 DB 상태: baseline documents 16, equipment 1,332, `process_relation CONFIRMED` 0, 검토 case/history 0.
- 운영 배포, main 병합, stash 변경, 3,082개 전체 계산 및 공식 도면 수정은 수행하지 않았다. 세부 이력·해시·남은 현장 입력은 `CHECKPOINT.md` Step 7-4B CHECKPOINT A–D를 참조한다.

---

## Step 7-5B — 현장 확인 Excel과 schema-v2 검토 이력 연계 (TEST_ONLY)

### API 및 사용 경로
- 모든 요청은 관리자 `X-Admin-Token`이 필요하고 기존 XLSX multipart 보안 검사 및 Step 7-4A/V16 저장 경계를 재사용한다. 입력은 Step 7-5A XLSX의 `fieldWorkbook` multipart 필드다.
- `POST /api/admin/step7-3/pw-01-05/field-review/preview`: 업로드를 읽기 전용 파싱·검증하고 snapshot/candidate mapping, 입력 오류, 변환 가능 건수를 반환한다. DB 검토 case/history를 저장하지 않는다.
- `POST /api/admin/step7-3/pw-01-05/field-review/import`: 충돌/오류가 없고 canonical schema-v2 validation을 통과한 입력만 기존 Step 7-4A importer로 전달한다. 버전 확인, idempotency, transaction 및 append-only history를 재사용한다.
- `POST /api/admin/step7-3/pw-01-05/field-review/report`: 검증 결과를 `PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx` 첨부로 반환한다. HTTP Content-Disposition은 UTF-8 filename encoding을 사용한다.

### 매핑·Excel 안전 규칙
- 현장 workbook은 고정 5개 시트 순서 및 `⑤ 담당자 확인 결과`의 4행 machine-readable headers로 읽는다. 5행부터 데이터이며 candidate ID, case_key, snapshot, source SHA, 원본 DB행, endpoint를 잠긴 V1.0 snapshot과 대조한다.
- macro/XLSM, formula cells, 외부 연결/링크 등 unsafe OOXML parts, 잘못된 ZIP 및 10 MB 초과 workbook은 차단한다. 파서는 수식 평가/refresh를 수행하지 않는다. 수량은 Excel numeric cell만 허용하며 텍스트 숫자를 자동 변환하지 않고 공란과 숫자 0을 구분한다.
- 참고행은 `REFERENCE_ONLY_NOT_MAPPED`로 분리하고 기존 후보와 자동 결합하지 않는다. 현장 파일에 없는 후보는 `NOT_PRESENT_IN_FIELD_WORKBOOK`로 남긴다. 다른 배관은 route ID로 구분하며 모호한 route·candidate·snapshot/version 충돌은 저장 차단.
- 현장 workbook은 schema-v2의 별도 `evidence_type` 필드가 없으므로 `EVIDENCE_VERIFIED` 제안은 이 브리지에서 그 상태로 저장하지 않고 `NEEDS_MORE_INFO`로 낮춘다. 경로 ID는 기존 V16 전용 typed route field가 없어 evidence text marker로 보존할 뿐 Step 7-4B typed topology/scope 요건을 만족시키지 않는다.

### 최종 확인 상태 및 산출물
- 실제 Step 7-5A Excel 검증: 기존 snapshot 후보 33, 기존 후보 mapping 25, 비후보 참고 행 6, 현장 workbook 미포함 후보 8. 실제 담당자 입력 0, schema-v2 변환 0. 합성 TEST_ONLY 입력은 별도 transaction test에서만 수행.
- 결과 Excel: [PW-01-05_현장확인결과_업로드검증_TEST_ONLY.xlsx](https://www.genspark.ai/api/files/s/ZFxMI6Pf). 5 sheets, 748 nonblank cells, 수식 0, 맑은 고딕/검정 글자, ZIP integrity 및 Apache POI 저장본 재열기 검증. SHA-256 `bfba40495220f4d9db5d0bd0465cbc4f78376663025882becc5b921ebf9aae22`.
- `Step7FieldReviewBridgeIntegrationTest` 6/6 PASS. Step 7-1/Step 6-5/Step 7-3/Step 7-4A/Step 7-4B 핵심 targeted 회귀 30/30 PASS; 전체 Maven suite는 실행하지 않았다. 공식 V1.0 파일/manifest/MariaDB metadata/BLOB hash 16/16 PASS; 팀원 XLSM SHA-256은 기대값 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`와 일치.
- DB 상태는 baseline documents 16, equipment 1,332, snapshot candidates 33, `CONFIRMED=0`, `EVIDENCE_VERIFIED=0`, `equipment_flow=0`, 검토 case/history 0. 공식 승인·실제 계산망 편입은 생성되지 않았다.
- 미구현: 실제 현장 담당자의 입력/증빙 제출, 실제 EVIDENCE_VERIFIED 및 관계 승인, typed route/topology completeness, 실제 계산망 편입/수지 계산. 실제 입력을 받기 전 완료/승인으로 취급하지 않는다. 전체 Maven suite/운영 배포는 하지 않았다. 재개 경계 및 단계별 결과는 `CHECKPOINT.md` Step 7-5B 재개 지점을 참조한다.

---

## Step 7-6A 및 최종보고 시연 MVP — 2026-10-26

### 시연 진입점 및 사용 경로
- 새 로컬 시연 페이지: `/demo.html` (현재 확인한 임시 주소는 `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/demo.html`). 기존 `/master-diff.html`의 변경 분석/Step 6-5 흐름을 링크하고, 후보·solver replay를 한 페이지에서 안내한다. 서버 루트 `/`는 404이며 직접 `/demo.html`을 연다.
- Java 17 + Spring Boot 정적 리소스/기존 API를 사용한다. 외부/운영 배포는 하지 않는다. `scripts/run-local.sh` 또는 `pm2 start ecosystem.config.cjs`로 로컬 실행하고 `GET /api/health`로 상태 확인.
- 페이지는 토큰을 URL·브라우저 저장소에 보존하지 않는다. 기존 `X-Admin-Token` guard를 유지하며 Step 6-5와 candidate preview/Excel/preview 요청마다 인증 필요.

### Step 7-6A API 및 입력 제약
- `POST /api/admin/step7-3/pw-01-05/network-review/candidate-preview` — 관리자로 확인된 팀원 XLSM을 읽기 전용 분석. 원본 SHA, 입력 연결행, V1.0 자료 개수, 33 후보의 DB 행/설비/셀·connector 위치 힌트를 반환하며 case/history/route/evidence/승인 저장 없음.
- `POST /api/admin/step7-3/pw-01-05/network-review-workbook` — 기존 Step 7-2/3 분석과 snapshot template exporter를 재사용해 6시트 현장검토 XLSX 제공.
- `POST /api/admin/step7-3/pw-01-05/network-review/preview` / `/import` — 별도 6시트 입력을 검증/수신. 이번 시연은 preview까지만 사용하고, 실제 담당자 입력이 없는 상태에서 import를 호출하지 않는다.
- route/evidence typed records는 V17, case/history/version binding은 V18, `provenance_state` 문자열 용량은 V19에서 관리. 이미 적용된 V17–V19 migration은 변경하지 않는다.
- V16 schema-v2 계약은 유지한다. 동일 endpoint라 해도 route ID를 하나로 합치지 않는다. 증빙 ID만으로 EVIDENCE_VERIFIED/CONFIRMED를 만들지 않는다. 전체 scope 불완전 시 실제 Adapter 계산망 편입은 0 / `CALCULATION_UNAVAILABLE`.

### 실사용 상태·검증 결과
- 읽기 전용 DB 조회: V1.0 baseline 16 XLSX(공정도 15 + 마스터 1), 설비 1,332, 팀원 XLSM 연결행 3,082, PW-01-05 후보 33, verified 0, `CONFIRMED=0`, 기존 relation 1건(`REVIEW_REQUIRED`). CASE/history/route/evidence 0, `equipment_flow=0`.
- 팀원 XLSM SHA-256: `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`. V1.0 원본 로컬 16개/manifest/MariaDB metadata/BLOB SHA 비교 `16/16 PASS`.
- V15–V19 Flyway history success. V17 checksum `722139116`, V18 `521162584`, V19 `1456786393`; 테스트 부팅 시 19 migrations validate PASS.
- Step 6-5/solver/Step 7-2–7-6A targeted 테스트 46건: 46 pass, 0 fail, 0 error, 0 skip. 조건부 실제 XLSM 비교 테스트도 ASCII source 경로를 지정해 실행했다. 전체 Maven suite는 실행하지 않음. 세부 집계·한계는 `validation-results/2026-10-26-step7-6a/FINAL_REPORT_AND_DEMO_GUIDE.md` 및 `CHECKPOINT.md`에 기록.
- 실제 산출물: 8시트 Step 6-5 통합 TEST_ONLY Excel; 6시트 Step 7-6A 후보 33/누락 8/참고 6 검토 Excel. 빈 6시트 template API preview는 valid=true/33 candidates/route 0. 두 파일은 별도 검증 결과 폴더에 있으며 공식 원본이 아니다.
- 시뮬레이션 페이지는 solver가 만든 합성 결과를 재생하고 실시간 계산이라고 표시하지 않는다: 이동 20/10/50, 순환 20/30, 과다 이동 120(-20 constraint), 잔차 +5→+8/delta +3. 실제 공정의 검증/오류 판정 결과가 아니다.
- CHECKPOINT 1–4 진행 상태, 시연 대본, PPT 6장 구성, Q&A, 로드맵은 `validation-results/2026-10-26-step7-6a/FINAL_REPORT_AND_DEMO_GUIDE.md`를 참조.

### 최종 사용자 검증 및 산출물 — 2026-10-26
- 최신 결과 요약/사용 절차/기능 상태표: `validation-results/2026-10-26-step7-6a/MVP_FINAL_VALIDATION_SUMMARY.md`; 수동 리허설 체크리스트: `DEMO_RUNBOOK.txt` 및 요약 파일.
- 별도 browser capture에서 `/demo.html` 로드 및 제목 확인. JavaScript error 0; password field form 관련 verbose 알림 2건. 자동 browser 도구는 file picker 및 버튼 동작을 수행하지 못해 전체 흐름 2회 수동 검증은 미완료.
- PM2 애플리케이션 프로세스 재시작 뒤 `/demo.html`, `/master-diff.html`, `/api/health`, scenario workbook HTTP 200. 전체 sandbox restart와 그 이후 DB persistence는 검증하지 않음. 이 URL은 세션형 sandbox 임시 주소이며 운영 주소/외부 영구 접근/다중 사용자 지원을 뜻하지 않음.
- 관리자 인증이 필요한 기능: Step 6-5 생성·다운로드, team XLSM candidate preview, review workbook 생성 및 preview. token 미제공 요청 401, 인증 후 잘못된 확장자/OOXML 파일 400. token을 화면/URL/문서/산출물에 기록하지 않음.
- 최종 기준 재조회: V1.0 XLSX local/manifest/DB metadata/BLOB 16/16 SHA PASS; team XLSM copy SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa` 일치. Flyway V15–V19 success=1; baseline documents 16, equipment 1,332, review snapshot/candidates 1/33, review case/history/route/provenance 0, relation REVIEW_REQUIRED 1 / CONFIRMED 0, equipment_flow 0.
- 새로 실행한 7개 영향 targeted test classes **22/22 pass, failures/errors/skips 0**. 이전 wider targeted 46/46 기록도 유지. 전체 Maven suite는 실행하지 않음.
- 최종 Excel 독립 재열기 및 ZIP integrity PASS: Step 6-5 8 sheets(기존 수식 10개), solver scenario matrix 4 sheets, candidate review workbook 6 sheets(33/8/6; 수식 0), Step 7-4B report 5 sheets. 배포 ZIP은 산출물·요약/보고/runbook만 포함하며 공식 16개, 팀원 XLSM, `.env`, credentials는 제외.
- DB direct counts는 애플리케이션 프로세스 재시작 전후 동일했고, review test 합성 fixture는 transactional test scope에서 실행. 실제 사용자 review import/공식 승인 관계는 만들지 않음.

---

## 숫자형 작업본 검토와 3단계 TEST_ONLY 작업 — 2026-10-26

### 사용 및 API
- `/`에서 공정도 선택 → 변경사항 입력 → TEST_ONLY 결과 확인/Excel 다운로드의 3단계 화면을 연다. 관리자 토큰은 입력 필드에서 요청 헤더 `X-Admin-Token`으로 전달하며 URL이나 브라우저 저장소에 보관하지 않는다.
- `GET /api/admin/workspace/process-documents`는 잠금 V1.0 문서와 별도 숫자형 작업본 목록을 반환한다.
- `POST /api/admin/workspace/process-documents/{baselineDocumentId}/numeric-working-copy/upload`는 `multipart/form-data`의 `file` 필드로 `.xlsx` 숫자형 작업본을 등록한다. 공정번호 파일명 일치, OOXML 열기, 공식본 대비 시트/셀/병합/DrawingML 검토를 수행하고 업로드 bytes를 별도 보관한다.
- `POST /api/admin/workspace/process-documents/{baselineDocumentId}/numeric-working-copy`는 PW-01 원본에서 별도 숫자/단위 검토 시트를 만드는 별도 경로다. 숫자 정합성이나 공정 경로 승인을 자동 추론하지 않는다.
- `POST /api/admin/workspace/change-preview`, `POST /api/admin/workspace/requests/{requestId}/generate`, `GET /api/admin/workspace/requests/{requestId}/download`는 현재 검증 범위인 PW-01 / PW-01-05 / C-PW15020의 TEST_ONLY 변경 입력→미리보기→생성→다운로드를 지원한다. 실제 수지 계산은 현재 `CALCULATION_UNAVAILABLE`이며 관계는 `REVIEW_REQUIRED`다.

### 자료 정합성 및 구조 제한
- 사용자 숫자형 파일 15개는 업로드 SHA-256과 DB BLOB SHA-256이 모두 15/15 일치한다. 공식 V1.0 공정도 15개와 마스터 1개는 local/manifest/DB metadata/BLOB 16/16 일치한다. 공식 파일, 업로드 원본, V20 migration은 수정하지 않았다.
- PW-01-05에서 여섯 개 숫자형 셀(`BB179`, `AQ164`, `AH51`, `AH97`, `AK147`, `AO41`)이 공식 표시 문자열과 각각 `+0.22` 차이 난다. 영향 셀은 수식이 아닌 literal이며 `톤/일` 표시는 있으나 기간 근거와 변경 원인이 확인되지 않았다. 모두 `REVIEW_REQUIRED`; 값 보정이나 실제 계산 입력으로 편입하지 않는다. 상세: `validation-results/2026-10-26-numeric-working/pw01-numeric-discrepancy-audit.json`.
- PW-01-05는 공식본 병합 84개 대비 숫자형 파일 병합 93개로 늘었고, 공식→작업본 DrawingML anchor 두 개가 달라 `CREATED_WITH_REVIEW`다. 생성 단계의 기존 객체 11개 anchor 이동은 삽입에 따른 정확히 14행 이동으로 확인했으나 시각 경로 승인으로 해석하지 않는다. 병합/anchor 자동 복원은 하지 않았다. 상세: `pw01-structure-audit.json`.
- 새 결과 시트의 글꼴 색상은 `FF000000` RGB 명시적 검정으로 기록한다. 새 font/style 추가만 허용하고 기존 style/font index는 보존한다. 새 TEST_ONLY 산출물을 ZIP 검사와 Apache POI 재열기로 확인했다. Microsoft Excel 네이티브 렌더는 미검증이다.

### 검증 및 한계
- 합성 입력 별도 request 3은 식별 가능한 `SYNTHETIC_TEST_ONLY` 값만 포함한다. 실제 경로/증빙/승인으로 취급하지 않으며, route admitted 0, calculation input 0, `CONFIRMED` relation 0을 유지한다. 다운로드 산출물은 `validation-results/2026-10-26-numeric-working/PW-01-05_C-PW15020_SYNTHETIC_TEST_ONLY.xlsx`.
- 기존 C-PW15020 reference case를 별도로 불러온 request 4는 실제 사례에 존재하는 설비정보만 사용하고 원본의 폐수·폐기물·경로 공란을 보존했다. `CALCULATION_UNAVAILABLE`, route admitted 0, calculation input 0, approval false. 다운로드: `validation-results/2026-10-26-numeric-working/PW-01-05_C-PW15020_CaseReference_TEST_ONLY.xlsx`; SHA-256 `f0e80678b5651d9d37aebb191308f2c69ae7d2e4bdc9e19ffbb19b5243e82aea`.
- 후속 request 1–4 중 3은 합성 TEST_ONLY, 4는 공란을 보존한 reference-case TEST_ONLY. 추가 저장 row는 총 request 두 건뿐이며 baseline/working-copy 자료와 승인 관계는 바뀌지 않았다.
- `MaterialBalanceExcelExplicitBlackFontTest` 1/1, `Pw0105EquipmentInsertionEngineTest` 2/2, `Step7DrawingEvidenceExtractorTest` 2/2 PASS. `./mvnw -q -DskipTests package` PASS. `MaterialBalanceExcelExportIntegrationTest`는 600초 제한으로 완료되지 않았고, Surefire thread dump는 대형 workbook의 `OoxmlPackageInspector.inspect`에서 실행 중임을 보여 준다. 이를 통과로 계산하지 않는다. 전체 Maven suite는 미실행.
- 마지막 브라우저 로드 capture에서 페이지 제목 확인, JavaScript console/page error 0. 파일 선택/버튼의 완전한 브라우저 E2E와 Excel 앱 렌더링은 추가 확인이 필요하다.

---

## 2026-10-27 PW-01 안전 후보 / C-PW15020 request 5 안정화
- 분류 집계 수정: 변환 5, 비허용 단위 2,580, 단일수량 설명문 338, 복수 숫자 복합문구 70; 전체 및 unique 2,993, 분류 중복 없음. compound-only 408이며 정의된 전체 비변환 count `compoundOrUnsupportedCells=2,988`.
- D296 `황산(9.5%) 0.8톤/일`은 원문 문자열 보존, 추출 수량 metadata는 계산 제외. 기존 `+0.22` discrepancy 6셀은 `REVIEW_REQUIRED` 유지.
- V1.0 원본 16/16, 기존 numeric upload 15/15 SHA-256 일치. V20 Flyway 성공/checksum `-1080684895`; 기준자료 16, 설비 1,332, CONFIRMED 관계 0. 후보 ID 36 SHA-256 `0e68fb76192f56749c0ac623bc8a4481e4851a822ec8724169587b0a55964896`; request 5 파일/DB BLOB SHA-256 `9648824d128656c9aca0b093866e228412af12d13fa0974ae83c840f0e168ec8`.
- request 5의 C-PW15020 설비 번호/명칭/Item/용량/수량이 반영됨. 기존 drawing object 11개는 +14행 이동, 신규 visual-only objects는 2개, 삭제 0. 폐수·폐기물 입력 공란, `CALCULATION_UNAVAILABLE`, 실제 route 0 유지.
- 관련 targeted test 43/43 PASS, failure/error/skip 0. 상세 클래스별 수치와 실패 재현/수정은 `CHECKPOINT.md` 최신 항목 참고. 전체 Maven suite는 실행하지 않음.
- PM2 online, health/page HTTP 200, page title 확인 및 browser console 0. 실제 브라우저 버튼 클릭은 미검증. request 5 기존 파일은 GET/다운로드 및 ZIP/POI 검사; 이미 GENERATED이므로 재생성은 하지 않음. Excel native rendering은 미검증.
- 이번 안정화 커밋은 `step7-3-wip-20261023` 전용이며 main 병합/운영 배포 없음. Excel/검증 JSON은 `validation-results/2026-10-26-numeric-working/`에 있고 Git에는 넣지 않음.

---

## 2026-10-27 최종 검증 — PW-01-05 사용자 입력 A/B

### 생성 결과와 보존
- 서로 다른 합성 입력 2건을 ID 36 안전 숫자형 후보 작업본에서 각각 독립 실행해 TEST_ONLY 통합 Excel을 생성했다. 둘 다 PW-01-05 `AI198`에 설비번호, 명칭, Item No., 용량, 수량이 입력값 그대로 표시된다.
  - A: `SYNTH-PW01-TEST-A` / `Synthetic Filter Alpha` / `SYN-ITEM-A` / `475m3/h` / `2 units`; 파일 `validation-results/2026-10-27-pw01-05-user-input-final/PW-01-05_SYNTH-PW01-TEST-A_request-35_TEST_ONLY-integrated.xlsx`; SHA-256 `8b2ec704d8350128f9a047f1b57da4bbc0216af8bc0c1b28f92027b81ceac4d1`.
  - B: `SYNTH-PW01-TEST-B` / `Synthetic Clarifier Beta` / `SYN-ITEM-B` / `820kg/h` / `3 units`; 파일 `validation-results/2026-10-27-pw01-05-user-input-final/PW-01-05_SYNTH-PW01-TEST-B_request-36_TEST_ONLY-integrated.xlsx`; SHA-256 `ebae050096692513d5a10715ea05063df4231ff0080ccc772d6518a1f4d03b67`.
- 두 workbook은 ZIP integrity와 Apache POI 통합 테스트 재열기, 별도 openpyxl 재열기, 값/수량/병합 개수 확인을 통과했다. 기준 작업본 PW-01-05 DrawingML 객체 103개에서 기존 ID·도형 이름·유형·텍스트·화살표 정보를 보존하고, 각 출력에 TEST_ONLY 연결선 1개(ID 160)와 REVIEW_REQUIRED 상태표시 1개(ID 161)를 추가했다. 기존 도형 삭제 0개. 병합은 84→85개이며 새 설비 배치에 따른 의도된 추가다.
- 기존 셀 표시값·스타일 ID, 수식, drawing/arrow, ZIP의 비대상 파트를 보존하는 테스트를 통과했다. `xl/styles.xml`은 새 결과 시트의 명시적 검정 글꼴을 위한 append-only font/XF만 추가한다. 공식 설비/공정 자료 또는 승인 도면으로 취급하지 않는다.

### 테스트·무결성·웹
- 최신 focused 회귀 54/54 통과, 실패 0, 오류 0, skip 0. 기존 skipped 18건도 실제 team XLSM 및 검증 workbook 경로를 지정해 모두 재실행했다. 전체 Maven suite는 실행하지 않았다.
- 공식 V1.0 16개 로컬 파일=manifest=DB metadata=DB BLOB SHA-256 16/16 일치. 기존 숫자형 사용자 업로드 15개 로컬 파일=DB metadata=DB BLOB 15/15 일치. 안전 후보 ID 36은 `CREATED_WITH_REVIEW`로 유지. 기존 +0.22 여섯 셀은 모두 `REVIEW_REQUIRED`; 값 보정 없음.
- DB read-only 사후 상태: 공식 기준 문서 16, 설비 1,332, 관계 `REVIEW_REQUIRED` 1 / `CONFIRMED` 0, Flyway V20 `success=1`, checksum `-1080684895`. 기존 request 5와 output SHA `9648824d128656c9aca0b093866e228412af12d13fa0974ae83c840f0e168ec8` 보존.
- PM2 `process-map-automation` online, `/api/health`와 `/` HTTP 200. 임시 샌드박스 URL: `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/`. 인증 API preview→generate→download smoke는 request 38에서 성공; SHA `2a68b81274233822e079c3c7e77e1ceef59649b4ec2acac7ab2c70dc8a276927`. 브라우저 페이지 로드/title/console은 확인했으나 실제 버튼 클릭 E2E는 미실행. URL은 세션형 샌드박스이며 운영 주소가 아니다.

### 미지원 범위
- 자동 Excel 삽입은 PW-01의 PW-01-05에서 C-PW15009와 C-PW15010 사이의 설비 추가만 지원한다. `MODIFY`/`REMOVE`, 다른 공정·시트·위치 및 일반적인 임의 배치는 차단한다.
- 실제 PW-01 물질수지는 계속 `CALCULATION_UNAVAILABLE`; 합성 경로만 별도 `TEST_ONLY` 계산 가능하다. 실제 연결 증빙·전체 범위·계량/기간 근거, 공식 승인, 단위 환산, 저장량/시간연속·복잡한 순환 수렴은 미완료다. 실제 유량을 도면에 반영하지 않는다.
- 이번 단계에서 운영 배포와 main 병합은 하지 않았다.

---

## 2026-10-27 추가 검증 — UI 간소화·실 ADD·MODIFY 차단

### 화면 및 실행 상태
- 메인 `/`는 공정도 선택 → 변경사항 입력 → 미리보기/TEST_ONLY Excel 생성·다운로드의 3단계 화면으로 정리했다. 관리자 인증/작업본 관리는 접힌 관리 영역, preview 상세 JSON은 접힌 개발·검증 영역에 보관했다. 기존 API, ADD 동선, 관리자 토큰 입력은 유지했다.
- 임시 접속 URL: `https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/`. PM2 `process-map-automation` online, `/` 및 `/api/health` HTTP 200. 브라우저 페이지 로드/title 확인, console 메시지 0. 브라우저 실제 버튼 클릭을 포함한 E2E는 미실행이며 API 흐름을 별도 검증했다.
- 첫 Spring 테스트 호출은 `./mvnw`를 `.env` 로드 없이 직접 실행해 DB_PASSWORD가 비어 있는 기본값으로 접속하면서 `process_dev@localhost` / `using password: NO`로 실패했다. 기존 `.env`와 DB 인증은 유효했으며, `.env`를 읽는 `scripts/test-local.sh`와 동일하게 환경을 로드한 후 DB 접속 및 재실행이 성공했다. 자격정보를 변경/노출하거나 DB를 새로 만들지 않았다.
- 읽기 전용 DB 확인: Flyway V20 success 1, 기준 문서 16, 숫자형 업로드본 15, ID 36 안전 후보 보존. 업로드 BLOB SHA 불일치 0. 공식 원본 검증 스크립트는 local/manifest/DB metadata/BLOB 16/16 PASS. 마이그레이션/공식 원본/업로드 원본은 수정하지 않았다.

### MODIFY 안전판정
- 작업본 ID 36의 PW-01-05 19개 대상 API 재검토: 상자 위치 후보 12, 정체성 확인 10, 실제 MODIFY 적격 0. 코드와 UI가 master-도면 불일치 및 미해결 review note가 있는 대상은 적격으로 인정하지 않으며, preview/generate에서 차단한다.
- C-PW15011의 집합 표기/복수 설비 상자는 `AGGREGATED_MULTI_MASTER_BOX`로 차단. C-PW15016의 상자 미검출 및 집합 박스 내 참조 위치는 미확정으로 유지. I-PW15001의 용량·수량 master/도면 불일치는 임의 해석하지 않았다.
- 검토용 Excel은 4개 시트(설비19건 매핑 23행, 필드별 수정판정 99행, DrawingML 증거 45행, 담당자 확인 입력 99행), ZIP 재열기 통과. 최종 파일 `validation-results/2026-10-27-pw0105-live-api-1793090001/PW-01-05-mapping-review-live-strict.xlsx`, SHA-256 `10e345e89176f3a2ea55a2b64e1ec1c46891867140582417ea43fe5154e40839` ([다운로드](https://www.genspark.ai/api/files/s/L68o27wz)). `X-Official-Baseline-Modified=false`.

### 실 API ADD 결과 A/B
- PW-01 / PW-01-05 / 안전 후보 작업본 ID 36 / 검증 삽입 위치 `C-PW15009`와 `C-PW15010` 사이에서 서로 다른 두 설비를 live preview → generate → download로 검증했다. 두 요청 모두 GENERATED, output hash와 다운로드 hash 일치, ZIP integrity PASS, `X-Official-Baseline-Modified=false`.
  - A: request 50, `SYNTH-PW01-LIVE-1793090001-A`, Live API Alpha Filter, `LIVE-ITEM-1793090001-A`, `417m3/h`, `2 units`; SHA-256 `30c3a0b705ec904318d18ee7f6b0fd7e9b4e5c460903d8cb426033d7f443d0df` ([다운로드](https://www.genspark.ai/api/files/s/NZw4ecBR)).
  - B: request 51, `SYNTH-PW01-LIVE-1793090001-B`, Live API Beta Clarifier, `LIVE-ITEM-1793090001-B`, `835kg/h`, `3 units`; SHA-256 `81e5c778bc89faaac0f07f500e926b6256aef2fbe9bfc5407066f8538ddcaa47` ([다운로드](https://www.genspark.ai/api/files/s/EMC2FLqX)).
- Apache POI 재열기에서 각각 10시트, `PW-01-05!AI198` ID와 AI201 명칭, AI202 Item No., AI203 용량, AI204 수량을 독립 대조했다. DrawingML 기존 객체 103개 ID/이름/유형/텍스트 보존, 의도된 삽입으로 기존 anchor 11개가 14행 이동, 신규 visual connector/status 2개, 누락/중복 ID 0. 실제 도면 관계/승인은 생성하지 않았다.
- 서버 저장본과 일치한 재다운로드 파일: `validation-results/2026-10-27-pw0105-live-api-1793090001/PW-01-05-request-50-server-download-final.xlsx` 및 같은 경로의 `PW-01-05-request-51-server-download-final.xlsx`. 이전 이름의 로컬 실험본은 보존하고 검증용 다운로드본과 구분한다.

### 테스트 및 저장
- `NumericWorkingCopyWorkflowIntegrationTest`: 8 tests, 7 pass, 0 fail, 0 error, 1 skip(미승인 실제 MODIFY 통합 시나리오 비활성).
- ADD 엔진/Step 6-5/Step 7 회귀 묶음: 19 tests, 18 pass, 0 fail, 0 error, 1 conditional skip. Step 7 조건부 테스트는 실제 팀 XLSM 경로를 지정해 별도 실행했고 1/1 PASS. 전체 실행기준 28회, 26 pass, 0 fail, 0 error, 2 skip(그중 Step 7 조건부 1건은 별도 재실행에서 PASS). 전체 Maven suite는 실행하지 않았다.
- `./mvnw package -DskipTests` BUILD SUCCESS. 코드 자동저장 커밋 `0621f45`가 MODIFY eligibility 차단 및 간소 UI/테스트 변경을 보존한다. 문서 업데이트/최종 기록은 현재 브랜치에서 별도 선별 커밋.

---

## 2026-10-28 최종 브라우저 업무 흐름 검증 — PW-01-05 ADD

### 웹 사용 및 브라우저 클릭 결과
- 실제 샌드박스 접속 주소: https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/ (세션형 개발 주소이며 운영 배포 URL이 아님).
- Chromium 브라우저에서 관리자 인증 후 PW-01 / 작업본 ID 36 / PW-01-05를 선택했다. 화면 버튼을 직접 클릭해 각각 설비 입력 → 미리보기 → TEST_ONLY Excel 생성 → Excel 다운로드를 완료했다. 두 입력은 서로 다른 신규 설비이며 검증 삽입 위치는 C-PW15009와 C-PW15010 사이로 고정했다.
  - request 65: `SYNTH-PW01-UX-20261027-B` / `합성 시연 여과설비 B` / `SYNTH-UX-ITEM-B` / `284㎥/일` / `2대`; SHA-256 `8282660bb29420f1e70fcbf44e8944ed70ab1657898adffac8738ee2f8cbc6b7` ([브라우저 다운로드 Excel](https://www.genspark.ai/api/files/s/JN5cRrcP)).
  - request 66: `SYNTH-PW01-UX-20261027-C` / `합성 시연 여과설비 C` / `SYNTH-UX-ITEM-C` / `91㎥/일` / `1대`; SHA-256 `25d8a45f2fdab4149befe98df9fb543bf42a0cf64665480a190a6f267a7d8172` ([브라우저 다운로드 Excel](https://www.genspark.ai/api/files/s/CH8SM4Ey)).
- 두 건 모두 preview에서 `TEST_ONLY_READY`, generate에서 `GENERATED`, 다운로드 버튼 성공 상태를 확인했다. 브라우저 미리보기 표의 설비번호·명칭·Item No.·용량·단위·수량이 입력값과 일치했다. 공식 원본 수정 여부는 false이고, 물질수지는 `CALCULATION_UNAVAILABLE`로 유지됐다.

### 다운로드 파일 재열기·도면 비교
- 각각의 실제 브라우저 다운로드 파일을 독립적으로 Apache POI와 OpenPyXL로 다시 열었다. `PW-01-05!AI198` ID, AI201 설비명, AI202 Item No., AI203 용량/단위, AI204 수량이 브라우저 입력과 완전히 일치했다. 두 파일 모두 10개 시트를 가진다.
- 두 파일 모두 `unzip -t`/ZIP CRC 무결성 검사 PASS. 로컬 브라우저 다운로드 SHA, API 재다운로드 SHA, MariaDB output SHA 및 BLOB 계산 SHA가 각각 일치했다.
- 안전 후보 ID 36의 기존 PW-01-05 DrawingML 객체 103개가 출력에서도 모두 남아 ID·이름·유형·형상·화살표·연결 바인딩이 유지됐다. 기존 connector 91개는 그대로 보존되고 신규 TEST_ONLY connector 1개가 추가(91→92)됐다. 별도 TEST_ONLY status 도형도 추가되어 전체 객체는 103→105개다. 모든 기존 객체의 삭제는 0개.
- 신규 11행 설비상자 셀 스타일 ID는 복제 원본 셀과 일치했다. 추가된 4개 설명 시트 때문에 workbook metadata/스타일 테이블과 PW-01-05 편집 파트는 바뀌며, 기존 비대상 ZIP 파트는 바이트 동일하다. Microsoft Excel 데스크톱 렌더링은 별도 수행하지 않았다.

### MODIFY 차단·보존·검증 경계
- MODIFY 19개 대상 GET 결과 eligible 0. 브라우저에서 MODIFY 선택지는 비활성 상태다. API-only 점검 request 67은 `GENERATION_BLOCKED`, 실제 generate 시 HTTP 409로 차단됐다. MODIFY 도면 편집은 하지 않았다.
- 기존 request 50·51은 그대로 보존됐고 output BLOB SHA 재확인이 통과했다. 공식 V1.0 원본 16개(local/manifest/DB metadata/BLOB)와 숫자형 사용자 업로드 작업본 15개(metadata/BLOB) 모두 SHA 일치했다. 안전 후보 ID 36도 metadata/BLOB SHA 일치.
- request 64는 브라우저 테스트 진입 중 진단용으로 생성된 별도 TEST_ONLY 사례이며 최종 두 다운로드의 일부가 아니다. request 65·66만 최종 비교 산출물로 사용했다. request 67은 MODIFY 차단 검사 기록이다.
- `NumericWorkingCopyWorkflowIntegrationTest`: 8건 중 7 PASS / 1 skip / 실패·오류 0. `Pw0105EquipmentInsertionEngineTest`: 3/3 PASS. `./mvnw -q -DskipTests package` PASS. 전체 Maven suite는 실행하지 않았다. PM2 재시작 후 서비스 online, `/` HTTP 200.
- 운영 배포, main 병합, migration 수정, DB 초기화, stash 변경은 하지 않았다. Excel 산출물은 `validation-results/2026-10-28-step7-3-final/` 아래에 보관하고 Git에 포함하지 않는다.

---

## 2026-10-28 일반 사용자 로그인 및 브라우저 최초 계정 승인 설정
- `/`은 비인증 세션을 `/login.html`로 보낸다. 일반 로그인은 서버 측 세션과 DB에 저장한 PBKDF2-HMAC-SHA256 해시를 사용하며, 이전 세션 로그인·CSRF·30분 만료·`HttpOnly`·`Secure`·`SameSite=Strict`·session ID rotation은 유지한다. 환경변수의 기존 계정 설정도 호환된다. 관리자 API와 `X-Admin-Token` 검증은 바꾸지 않았다.
- 최초 설정은 공개 가입이 아니다. 사용자는 HTTPS 로그인 페이지에서 **관리자 승인 요청**을 누르고 화면의 UUID만 이 대화에 전달하면 된다. 서버 관리자가 승인하면 같은 브라우저 탭에 계정 설정 양식이 나타나며, 사용자가 직접 새 아이디와 비밀번호를 설정한 뒤 일반 로그인한다. 비밀번호나 관리자 토큰은 채팅에 보내지 않는다.
- 등록 요청은 DB에서 15분 만료되고 요청 브라우저 세션 ID의 SHA-256 지문에 묶인다. DB unique slot으로 전체 시스템의 활성 요청을 한 건만 허용하고, CSRF·동일 출처 검증·세션당 5회 rate limit을 적용한다. 사용자 측 터미널 명령 실행은 필요하지 않다.
- 서버 관리자는 `python3 scripts/approve-operator-setup.py <request-id>`를 서버에서 실행한다. 스크립트는 `.env`의 기존 관리자 API 토큰을 프로세스 내부에서만 읽고 보호된 관리자 API로 현재 대기 중인 요청인지 확인 후 승인한다. 토큰은 브라우저·응답·로그에 출력하지 않는다. 다른 브라우저 세션은 요청을 완료할 수 없고, 승인 전/만료 후/사용 후 계정 생성은 차단된다.
- 승인 후 최초 요청을 만든 같은 브라우저에서 사용자 본인이 아이디와 14자 이상 비밀번호를 입력한다. 서버는 해시만 단일 계정 row에 저장하고 요청을 `CONSUMED` 처리하며 해당 세션을 폐기한다. 이후 일반 로그인 화면에서 생성한 계정으로 로그인한다. 계정이 설정되면 초기 설정 endpoint는 닫히고 두 번째 계정을 만들 수 없다.
- 새 DB 객체는 추가 migration V21/V22에만 정의했다. 기존 migration 및 baseline/workbook 테이블은 변경하지 않았다. 기존 관리자 API 경로/토큰 guard는 그대로 유지한다.
- 일반 사용자 업무 API `/api/workspace/*`는 로그인 세션을 요구하며 공정도 목록, PW-01 작업본 ID 36, PW-01-05의 검증 위치 ADD 및 TEST_ONLY Excel 생성·다운로드만 허용한다. MODIFY/REMOVE는 계속 차단한다.
- 테스트 전용 승인 흐름으로 미승인 차단, 다른 브라우저 세션 차단, 만료, 중복 승인/재사용 차단, 계정 해시 저장 후 로그인 및 업무 조회를 검증했다. 로그인 뒤 공정도 15개, PW-01, 작업본 ID 36 및 변환 버전 `PW01-OFFICIAL-NUMERIC-V1` 확인이 통합 테스트에서 통과했다. 테스트 데이터/계정은 트랜잭션 rollback되며 실제 사용자 비밀번호는 요청하지 않았다.
- 사용자는 [로그인 및 최초 설정 화면](https://3000-i3ctdqiuhea24ghipa2vu-0e616f0a.sandbox.novita.ai/login.html)에서 요청을 시작할 수 있다. 주소는 임시 sandbox 개발 URL이며 운영 배포가 아니다. 실제 계정 설정은 사용자의 요청 후 서버 관리자가 승인해야 하므로 아직 수행되지 않았다.
- 터미널이 가능한 서버 관리자를 위한 대체 도구 `scripts/configure-operator-login.py`는 유지하되, 일반 사용자에게 터미널 명령 실행을 요구하지 않는다. 비밀번호·관리자 토큰을 채팅·소스·브라우저 저장소로 옮기지 않는다.

---

## 2026-10-28 최종 ADD 브라우저·Excel 검증

- 최신 코드 `./mvnw -DskipTests package` 성공 후 로컬 PM2 서비스를 시작하고 `/api/health` 200 및 로그인 화면을 확인했다. Chromium에서 로그인 → PW-01(기준 문서 ID 12) → 작업본 36 → PW-01-05 → 지정 위치 ADD → 미리보기 → 생성 → 다운로드를 실제 버튼으로 실행했다. 테스트 로그인은 실행 중 임시 설정만 사용했으며 DB 계정/비밀번호는 수정하지 않았다.
- 설비번호 정규식의 브라우저 호환 escaping을 고친 뒤 유효 ID는 허용, 공백 포함 ID는 거부, console/page errors 0을 확인했다. UI의 변경 유형 선택지는 ADD만 활성화되어 있다.
  - 짧은 정보 / request 94: `QA-SHORT-20261028-04`, `합성 분리 여과장치 A`, `QA-ITEM-SHORT-04`, `125 m³/h`, `1대`; 다운로드 500,888 bytes, SHA-256 `60f37cb84426743984796fc7d5e98fd2746f1455a39a7fd18b6a5b88bbc0f48c`.
  - 긴 정보 / request 95: `QA-LONG-20261028-PW01-FILTER-NORTHWEST-UNIT-0004`, 117자 설비명, 82자 Item No., 50자리 용량값과 긴 단위/수량; 다운로드 501,848 bytes, SHA-256 `4ab7fcc112c0e0c120c04ee366ec8478dad4f4d18a3b8e1562c7af59ff326fca`.
- 파일: `validation-results/2026-10-28-step7-3-final/PW-01-05_short_browser-final_20261028.xlsx`, `.../PW-01-05_long_browser-final_20261028.xlsx`. Apache POI 재열기에서 두 파일 모두 활성 시트 `PW-01-05`, scroll top row 197 / left column 34, 신규 셀 원문 및 `shrinkToFit=true`를 확인했다. DrawingML은 source 103개에서 104개로, connector 91개에서 92개로 늘어 각각 신규 연결선 1개만 추가됐다. `TEST_ONLY / REVIEW_REQUIRED` 상태 콜아웃은 없고, 기존 객체/화살표를 보존했다.
- LibreOffice로 PW-01-05 렌더링 사본을 PDF/PNG로 확인했다(렌더링을 위해 다른 시트만 별도 사본에서 숨김). 짧은 값은 새 녹색 설비상자에 표시되고 기존 C-PW15009/C-PW15010 상자·화살표와 연결이 유지되며, 주변 설비 상자 침범은 보이지 않았다. 긴 값은 상자 안에서 매우 작게 축소되어 표시되므로 원문은 POI에서 확인 가능하지만 화면 크기에서의 가독성 검토가 필요하다. 이것은 LibreOffice 렌더링이지 Microsoft Excel 네이티브 렌더링은 아니다.
- 회귀 결과: `Pw0105EquipmentInsertionEngineTest` 4/4, `DiagramEditPlanIntegrationTest` 1/1, `OperatorWorkspaceSessionIntegrationTest` 1/1, `NumericWorkingCopyWorkflowIntegrationTest` 8건 중 7 pass / 1 의도적 skip / 실패·오류 0. 숫자형 작업본 테스트의 `short`/`int` top-row 비교 및 append-only 스타일 비교를 보정했다. 전체 Maven suite는 실행하지 않았다.
- 기준 SHA 확인: 공식 원본 16/16 local/manifest/DB metadata/BLOB 일치, 숫자형 작업본 ID 1–15 15/15 metadata/BLOB 일치, 안전 후보 ID 36 SHA 일치. 원본 파일, migration, 운영 DB 기준자료는 초기화·수정하지 않았다.
- 운영 배포나 `main` 병합은 하지 않았다. 테스트 Excel은 Git에 포함하지 않고 `validation-results/2026-10-28-step7-3-final/`에 보관한다.

---

## 2026-10-29 설비 유형 직접 입력
- 2단계 `설비 유형 표시`는 고정 `여과시설` 기본값을 제거한 필수 자유 입력 텍스트 필드다. 예시는 placeholder로만 제공하며 초기화 시 공란으로 돌아간다. 비어 있으면 미리보기/서버 검증이 요청을 거부해 옛 유형을 대신 삽입하지 않는다.
- 사용자가 입력한 문자열을 UI 요청·미리보기·저장 요청에 전달한다. 미리보기에는 `설비 유형 표시` 행을 추가해 입력 값을 보여 준다. TEST_ONLY Excel에서는 기존 설비상자 내 유형 셀 `PW-01-05!AI200`에 그대로 기록하며, 추가 셀/도형을 만들지 않는다.
- 신규 유형 셀은 템플릿의 대응 셀 `AI185` 서식을 복제한다. 테스트에서 기존 셀 스타일 ID, 맑은 고딕 7pt, 정렬 일치를 확인했다. 공식 V1.0 및 숫자형 작업본은 수정하지 않는다.
- `저장시설`, `혼합시설` 각각에 대해 로그인 세션 → preview → generate → download → Apache POI 재열기 값 대조를 통합 테스트했다. 공란 유형은 요청 단계에서 차단되며 엔진 단위 테스트에서도 `여과시설` fallback이 없음을 확인했다. 테스트 파일은 `target/equipment-type-validation/` 아래에 생성되고 Git에는 포함하지 않는다.
- 회귀: `OperatorWorkspaceSessionIntegrationTest` 2/2, `Pw0105EquipmentInsertionEngineTest` 6/6 PASS. 같은 세션 ADD 및 다른 세션 차단, 로그인 보호, MODIFY 403 차단을 유지했다. 전체 Maven suite는 실행하지 않았다.
- 샌드박스 앱에 최신 빌드를 반영했으며, 별도 운영 서버 배포는 하지 않았다.

---

## 2026-10-29 연결망 자동화 CHECKPOINT B (진행 범위 제한)
- 합성 directed-network solver의 stream key에 측정 기간을 추가했다. 물질 분류·종류·단위·기간이 모두 같을 때만 계산되며, legacy 생성 경로의 누락 기간은 계산 불가로 끝난다. 검토 adapter는 route에 기록된 period를 network edge에 전달한다.
- TEST_ONLY 결과 workbook에서 연결·설비·공정·외부 수지 및 입력 template에 측정 기간을 함께 표시한다. What-if 전후 잔차 비교도 기간을 key에 포함해 다른 기간끼리 대조하지 않는다.
- 서로 다른 두 기간 흐름이 별도 수지로 남고, 기간이 빠진 edge는 `MEASUREMENT_PERIOD_REQUIRED`로 차단됨을 테스트했다. `DirectedProcessNetworkSolverTest` 9건, `MaterialBalanceCalculatorTest` 6건 및 source-driven Step 7 review/comparison 11건은 통과했다. 실제 후보는 33건 중 EVIDENCE_VERIFIED 0, eligible 0이어서 실제 계산은 여전히 불가하다.
- 기존 일반 `MaterialBalanceRequest`/`material_balance_transfer` 경로에는 period persistence가 아직 없고 schema V22에서 정지했다. V23 migration 추가·자동 적용을 하지 않아 이 legacy API 경로의 기간 안전성은 미완료다. 실제 업무용 What-if UI/API, append-only change proposal(ADD/DELETE/MODIFY/PIPE_CHANGE/FLOW_CHANGE), 다른 공정의 안전한 Excel 반영도 미구현이다.
- 로그인·ADD regression 재실행은 `OperatorWorkspaceSessionIntegrationTest` 중 JVM fork exit 137로 중단되어 이번 실행에서 통과로 집계하지 않는다. 이전 체크포인트의 회귀 통과 이력은 유지한다. 전체 Maven suite와 운영 배포는 수행하지 않았으며, `main`, 공식 원본, 기존 migration은 수정하지 않았다. 세부는 `CHECKPOINT.md`를 참조한다.

---

## 2026-10-29 응답 오류 후 재개 CHECKPOINT C
- What-if API/UI/Excel export는 기존 구현을 유지했다. `OperatorWhatIfSessionIntegrationTest` PASS: 로그인·CSRF, 합성 TEST_ONLY 20→10/50 계산, 기간 누락 거부, Excel export를 확인했다. 실제 공정은 verified connection/evidence가 없어 `CALCULATION_UNAVAILABLE`로 유지한다.
- 공식 공정도 15개/62시트의 기존 구조 보고서를 검토했다. 병합셀·DrawingML 교차가 없는 후보 중 PW-01 비공식 작업본에서 fixed slot 외 PW-01-05 `C-PW15001→C-PW15011` TEST_ONLY Excel 생성·POI/병합/인쇄/DrawingML 확인을 완료했다. 산출물 SHA 및 경로는 CHECKPOINT.md에 기록했다.
- PW-03 작업본의 공간 후보 `AI27:AZ29`는 connector endpoint 증거가 맞지 않아 생성 차단했다. 타 공정·시트용 범용 설비 ADD 엔진, DELETE/MODIFY/PIPE_CHANGE/FLOW_CHANGE 제안 기반은 미완료다.
- DB migration/초기화, 공식 원본 수정, main 병합, 운영 배포는 하지 않았다. 재개 상세와 한계는 CHECKPOINT.md를 참조한다.

## 2026-10-31 검증 결과 보존·P-02/PW-02 구조 편집
- `validation-results/process-diagram-inventory/locked-v1.0-process-inventory-validations.json`은 locked V1.0 fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb`에 연결된 별도 validation sidecar다. P-02-01(TOP) 및 PW-02-01의 BOX_ONLY TEST_ONLY 구조 편집 2건이 기록되어 있고, 각 artifact SHA를 다시 계산해 ledger와 일치함을 확인했다.
- 두 검증 모두 `VERIFIED_BOX_ONLY_TEST_ONLY`이며 실제 배관 연결은 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`다. P-02 artifact SHA-256 `582de0d333538cc7dbaa8939ce103fc731dfe380cdfb51a478c19fb41fbbb562`; PW-02 artifact SHA-256 `d0c49f943993dd47cca210c3b5d45622a18815a5036d6b2fd83708c37694bd68`. 15개 공정도/62개 시트 inventory 원본 JSON은 재작성하지 않는다.
- API token 비설정/불일치 차단 경로 테스트와 sidecar store 테스트, P-02/PW-02 로그인→CSRF→preview→generate→download 통합 검증 및 package build가 완료됐다. 첫 테스트 시도는 `.env` 로드 누락으로 DB 인증이 거부됐고, secret 값을 출력하지 않고 기존 로컬 환경 로더로 재실행한 테스트는 통과했다.
- Playwright로 로컬 로그인 페이지를 다시 열어 페이지 제목과 console 오류 0건을 확인했다. 다만 로그인 후 UI 버튼으로 ADD 흐름을 실행하는 브라우저 E2E는 완료하지 않았다. API 통합 테스트를 E2E로 간주하지 않으며, 이전 `Target page, context or browser has been closed` 원인은 추가 관찰이 필요하다. PDF/LibreOffice 렌더링과 POI/OOXML 검사는 앞선 CHECKPOINT에 기록되어 있다.
- `DiagramBoxChangeEngine`에 합성 XLSX fixture 전용 MODIFY/DELETE 처리기를 추가했다. MODIFY는 지정한 박스 안 셀만, 요청에 명시된 현재값과 일치할 때 교체한다. DELETE는 단일 ID 박스의 셀 값/스타일 및 박스 내부 병합만 제거하고 행을 이동하지 않는다. DrawingML 파트, 대상 시트 relationship, 수식이 있는 박스, 중복 ID, 바깥 셀, stale 값은 fail-closed이며, OOXML 파트 비교와 POI 재열기로 대상 외 파트 보존을 확인한다. 단위 테스트 4건 PASS.
- 이 엔진은 현재 사용자 API/UI에 연결하지 않았고 현장/실제 공정 적용을 허용하지 않는다. common append-only proposal 및 PIPE_CHANGE/FLOW_CHANGE API/UI, synthetic What-if 연계는 아직 미구현. 실제 연결/유량은 별도 증거가 없으면 `CALCULATION_UNAVAILABLE`로 유지한다. 공식 원본·기존 migration·baseline·`main`은 수정하지 않았고 운영 배포하지 않았다.

## 2026-10-31 실제 작업본 MODIFY TEST_ONLY 검증
- 운영 API의 `GET /api/workspace/diagram-editor/modify/equipment`, `POST /preview`, `POST /generate`, `GET /download/{previewId}`는 로그인·CSRF·세션별 다운로드 보호를 적용한다. 수정은 immutable working-copy bytes에서만 수행하고 master/Baseline DB를 갱신하지 않는다.
- P-02 working copy ID 3 / `P-02-01(TOP)` / `I-PP21039`: master mapping `MAPPED_PERMIT_ID` 및 셀 박스의 exact unique fields를 확인했다. `I585` Item No. `WWTK-131-008`을 `TEST-P02-ITEM-01`로 바꾼 TEST_ONLY 산출물을 만들었다. 관리번호, 구분/permit name, 수량은 자동수정에 포함하지 않고 REVIEW_REQUIRED로 둔다.
- PW-02 working copy ID 8 / `PW-02-01` / `I-PW21007`: `J149` Item No. `PI-102`를 `TEST-PW02-ITEM-01`로 바꾼 TEST_ONLY 산출물을 만들었다. 명칭·permit name·capacity/단위는 exact unique cell match, quantity 및 관리번호 변경은 REVIEW_REQUIRED다.
- 결과는 각각 한 셀 텍스트만 바뀌었다. POI 재열기, non-target cells/styles, merge count, print area, DrawingML inventory·파트 byte equality, ZIP 무결성, DB working-copy SHA 불변을 통합 테스트에서 확인했다. LibreOffice 원본/결과 PDF 페이지 수는 P-02 71, PW-02 16으로 같고 diff bbox는 변경한 label 영역에만 있었다(Excel native renderer 아님).
- [P-02 TEST_ONLY Excel](https://www.genspark.ai/api/files/s/I38p7CN3), [PW-02 TEST_ONLY Excel](https://www.genspark.ai/api/files/s/s2UKcik7). 내부 경로는 `target/diagram-modify-validation/`이며 공식 문서가 아니다. 배관/연결은 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`다.
- `GET /api/workspace/diagram-editor/modify/delete-impact`로 삭제 영향 분석을 제공한다. DrawingML 파트가 있거나 근접 라벨이 남는 대상은 `REVIEW_REQUIRED`/삭제 차단이다. UI에는 아직 연결하지 않았다.
- `DiagramBoxChangeEngineTest` 및 `OperatorDiagramModifyIntegrationTest` 선별 회귀 통과. 기존 매핑이 명확하지 않은 필드와 실제 연결/유량은 승격하지 않는다.
- 공통 proposal Controller/Service/Repository 및 V24 additive migration 초안은 작성했지만 migration 적용·DB 통합 테스트는 하지 않았다. 이 파일들은 별도 미검증 작업 상태이며, PIPE/FLOW What-if 연결과 UI는 미완료다.

## 2026-10-31 실제 작업본 MODIFY TEST_ONLY 검증
- `GET /api/workspace/diagram-editor/modify/equipment`, `POST /preview`, `POST /generate`, `GET /download/{previewId}`를 추가했다. 기존 operator login, CSRF, 세션별 다운로드 경계를 재사용하며 working-copy bytes만 수정한다. master table과 공식 baseline은 갱신하지 않는다.
- P-02 작업본 ID 3, `P-02-01(TOP)`, `I-PP21039`: locked master permit-ID mapping 및 박스 내 유일 exact-string 셀을 검증하고 `I585` Item No. `WWTK-131-008`을 `TEST-P02-ITEM-01`로 바꾼 TEST_ONLY 파일을 생성했다. 관리번호 변경·분류 및 수량 값은 불확실해 REVIEW_REQUIRED로 남겼다.
- PW-02 작업본 ID 8, `PW-02-01`, `I-PW21007`: `J149` Item No. `PI-102`를 `TEST-PW02-ITEM-01`로 바꾼 TEST_ONLY 파일을 생성했다. 이름·permit name·capacity/unit은 유일 exact match, 수량·관리번호는 REVIEW_REQUIRED다.
- POI 재열기, 변경 셀 외 값/스타일, merge count, print area, DrawingML object inventory 및 raw drawing part byte-equality, ZIP integrity, working-copy SHA를 검사했다. LibreOffice PDF 71/16페이지가 원본과 동일하며 page diff는 변경한 텍스트 셀 영역에 한정됐다. 이는 LibreOffice 렌더링이지 Excel native rendering은 아니다.
- [P-02 TEST_ONLY Excel](https://www.genspark.ai/api/files/s/I38p7CN3) · [PW-02 TEST_ONLY Excel](https://www.genspark.ai/api/files/s/s2UKcik7). SHA-256: `3db3145937c83df85c25fedfe2fe874cc9efa08917690edd23158c984f47a6ed` / `159bd67712fe48a778bd6dcf0fb79b786b25a05e1bb811ea56052db92ce6a082`. 실제 연결 증거는 계속 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`다.
- 합성 DELETE impact 검사 및 `GET /api/workspace/diagram-editor/modify/delete-impact`를 추가했다. DrawingML이 있는 대상의 실제 삭제는 차단한다. 이 endpoint의 단위/컴파일 확인은 했으나 별도 API 통합검증 및 웹 UI 연결은 아직 안 했다.
- 선별 통합 테스트와 package build가 통과했다. proposal API/controller/repository 및 V24 append-only migration 초안은 작성했지만 migration 적용·DB 통합 테스트하지 않았고 미커밋이다. PIPE/FLOW의 계산 연결 및 UI도 미완료다.

## 2026-10-29 연결망·수지 자동화 CHECKPOINT A
- 기준 상태: Java 17 / Spring Boot 3.5.16 / Maven Wrapper / MariaDB 11.8.6 / Flyway V22. 브랜치는 `step7-3-wip-20261023`; 시작 HEAD `bb0382f`, 작업 트리 clean. `/api/health` 정상. `main`은 변경하지 않았다.
- 공식 V1.0 공정도 15개 + 마스터 1개는 `data/reference/SHA256SUMS` 16/16 PASS, DB metadata/BLOB SHA도 16/16 일치. 기존 숫자형 upload 15/15 BLOB SHA 일치. PW-01 안전 후보 ID 36은 `PW01-OFFICIAL-NUMERIC-V1`, `CREATED_WITH_REVIEW`, metadata/BLOB SHA 일치.
- 데이터베이스에는 `process_relation` 1건이 `REVIEW_REQUIRED`, `CONFIRMED` 0, `equipment_flow` 0. 연결망 review snapshot 후보 33개는 현재 case/history/typed route/provenance가 0건이며 `EVIDENCE_VERIFIED` 0, 실제 연결망 계산은 `CALCULATION_UNAVAILABLE`다.
- 업로드된 `0.3 250808 물질수지 DB.xlsm`은 공식 baseline DB와 분리된 비교 입력이며 SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`. 입력시트 3,082행 중 PW-01 행 504, 고유 PW-01 endpoint 쌍 501, row-level 양 endpoint 마스터 일치 2,777/한쪽만 일치 305. 질량은 양수 1,975, 0 1,106, 공란 1; 물질 종류 공란 2,830, 계산 제외 공란 3,082. 단위·측정 기간 열이 없으므로 이 3,082행을 승인 연결망이나 확정 유량으로 사용하지 않는다.
- 기존 ADD 엔진·로그인 세션 회귀와 solver/검토 네트워크 테스트를 통과했다. 상세 단계별 검증·남은 위험은 CHECKPOINT.md의 2026-10-29 섹션을 참조한다. 회사 「모듈 생성 프롬프트_v4」 파일과 core/app 플랫폼은 현재 프로젝트 입력에서 찾지 못해 가상 플랫폼 의존성을 추가하지 않는다.

---

## 2026-11-01 변경 제안 Before/After What-if 검증
- append-only 변경 제안 API의 `POST /api/workspace/change-proposals/{proposalId}/what-if`는 제안 유형별 before/after 합성 네트워크를 기존 `NetworkWhatIfService`로 계산하고 `VALIDATION_RESULT` 이벤트에 기록한다. 로그인 사용자 범위와 CSRF 보호를 재사용한다.
- 비교 전 제안 snapshot과 계산 네트워크의 연결 ID/유형, 출발·도착 공정·설비, 물질 분류·종류, 단위, 기간을 대조한다. FLOW_CHANGE의 before/after 수량은 고정 연결의 네트워크 기준값과 일치해야 한다. DELETE는 대상 설비가 before에 한 번 존재하고 after에서는 제거됐을 때만 비교를 허용한다.
- before/after 계산 결과는 공정 잔차와 외부 유입·유출 잔차를 기간이 포함된 key로 대조하며, 한쪽에만 있는 공정/외부 스트림도 빠뜨리지 않고 NEW_AFTER/MISSING_AFTER로 표시한다. 미지수·순환 미결정·제약 위반을 임의 보정하지 않는다.
- 격리 DB `process_map_proposal_test_20261031`에서 FLOW_CHANGE 20→10/50, PIPE_CHANGE 경로/영향 설비, DELETE 영향 범위, 분기·합류 수지, 조건이 충분한 내부 재순환 및 조건 부족 순환, 음수/기간 누락·불일치 차단을 통합 테스트했다. `NetworkWhatIfServiceTest` 3, `DirectedProcessNetworkSolverTest` 9, `MaterialBalanceCalculatorTest` 6, `OperatorChangeProposalIntegrationTest` 8, 합성 MODIFY API 테스트 2 — 총 28 PASS, 실패/오류/skip 0.
- 실제 readiness는 계속 `CALCULATION_UNAVAILABLE`; 합성 계산 결과는 사업장 공정 검증이 아니다. 격리 DB는 schema V24 상태로 테스트됐으며 테스트 데이터는 rollback되어 baseline/work-copy/proposal event가 0건으로 확인됐다. `process_map_dev`, 기존 PM2 서버, 공식 Excel 원본 및 운영 배포는 건드리지 않았다.
- **당시 변경 시점의 기록**: 제안 연계 What-if workbook export는 미구현으로 기록됐다. 이후 현재 코드에는 `GET /api/workspace/change-proposals/{proposalId}/what-if/export`가 추가되어 TEST_ONLY workbook을 반환하고 SHA 헤더/파일명 prefix를 설정한다. 2026-11-01 E2E의 다운로드 기능 흐름은 PASS로 기록됐으나, 보존 JSON SHA와 현재 다운로드 파일 SHA가 달라 checksum은 미해결이다. 비교 workbook 산출물은 기존 테스트 경로에서 확인되며, 브라우저 응답 바이트와의 checksum 일치는 별도 확인이 필요하다.

## 2026-11-01 What-if 마감 및 공정도 편집 엔진 준비 상태
- What-if API/코드 구현과 선별 통합 테스트는 기존 실행 기록 기준 30/30 PASS. 브라우저 E2E JSON은 로그인, proposal 목록, PIPE/FLOW 합성 전후, TEST_ONLY 다운로드, 다른 context의 401 및 로그아웃 흐름 PASS를 기록한다. 합성 결과의 readiness는 계속 `CALCULATION_UNAVAILABLE`, 검토 상태 `REVIEW_REQUIRED`이며 실제 연결/유량 증거가 아니다.
- 다운로드 SHA 불일치 미해결: E2E JSON `download.sha256=b7e033ee9ae3b8ea2203c046b076ccb7a9310cb8f221107660af40e37547a8d5`, 반면 같은 파일명의 현재 14,013-byte 파일 SHA는 `cdb360f4471de50ffb689eb42e8f214be3977b732927d99b4e061c95b31ff3d5`. API 코드의 `X-Output-SHA256`와 filename-prefix 구현은 확인했으나 해당 브라우저 응답 headers를 보존한 로그가 없어 원인은 특정하지 않았다. 증거를 덮어쓰거나 PASS 처리하지 않았다.
- 세 TEST_ONLY workbook PDF는 각 13페이지(A3 가로), 8개 시트 구성. 총 39페이지 PNG를 개별 검수했다. 명백한 표/텍스트 잘림·겹침은 관찰하지 않았고 페이지 간 표 분할, 작은 글씨/여백, comparison workbook의 header-only `외부 수지 비교` 2페이지, print_area 미설정을 기록했다. style metadata는 맑은 고딕/검정; LibreOffice는 Noto Sans fallback으로 렌더했다. 상세 페이지 행렬 및 해시 근거: `validation-results/2026-11-01-whatif-closeout/WHATIF_EXCEL_VISUAL_QA_2026-11-01.md`.
- Inventory는 `progress.json` 기준 15 documents/62 sheets 완료, 실패 0, source fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb`. 공정도 합계 1,006 location candidates, 2 VERIFIED, 655 REVIEW_REQUIRED, 349 UNSUPPORTED, 586 DrawingML shapes, 8,094 connectors, 10,625 merges. VERIFIED는 구조 후보이지 물리 연결/편집 승인 아님.
- 다음 구현 순서: (1) 기존 ADD 엔진을 공정별 profile과 공통 guard로 확장, (2) 검증된 MODIFY 필드 mapping 확대, (3) 영향 preview 중심의 보수적 DELETE, (4) evidence 기반 PIPE_CHANGE 도면 반영, (5) 기존 확정 edge의 FLOW_CHANGE 표시, (6) 승인된 물질수지 연결망과 도면 통합. 단일 좌표/템플릿 가정은 금지. 범위·대상·검증·완료 기준은 `validation-results/2026-11-01-whatif-closeout/STEP7_7_PROCESS_DIAGRAM_EDIT_ENGINE_READINESS.md`.
- 이번 마감 정리에서 공식 원본, `process_map_dev`, 기존 PM2 process, DB/migrations, 배포, `main`은 건드리지 않았다. 임시 테스트 app/Chromium은 종료 기록, 기존 PM2는 online 유지. 실제 편집 기능은 추가하지 않았다.

## STEP 7.7 Profile ADD 안정화 결과 (2026-11-01)

### Profile 적용 범위와 실제 작업본 판정
| 공정/시트 | Profile ID / 버전 | 판정 | 기준 원본 SHA-256 | 검증 작업본 SHA-256 | 필드 매핑/결과 |
|---|---|---|---|---|---|
| P-02 / `P-02-01(TOP)` | `P-02-01-TOP-V1` / `1.0.0` | `VERIFIED`는 등록된 정확한 작업본에만 한정 | `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76` | `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2` | managementNo I581, type I583, name I584, item I585, capacity I586, quantity I587; `U582:Z588` merge. 이번 파일 검색에서 검증 작업본 SHA 미발견, 생성 안 함. |
| P-02 / `P-02-05(BROKE)` | `P-02-05-BROKE-REVIEW-V1` / `1.0.0` | `REVIEW_REQUIRED` | `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76` | 미등록 | 필드별 매핑 미검증. 생성 차단. |
| PW-02 / `PW-02-01` | `PW-02-01-LEGACY-CASE-REVIEW-V1` / `1.0.0` | `REVIEW_REQUIRED` | `2fce7fa0cbc6ef687688c62cf26688db81172922d67e65f77a22e5990e4c586a` | 과거 SHA `4250b004c723b9d366d1231ce98d75761a864564402250fe5a5873665b00b8d1`의 파일 미발견 | working copy BLOB/필드 매핑 확인 전 생성 안 함. |
| PW-01 / `PW-01-05` | `PW-01-05-LEGACY-ONLY-V1` / `legacy-fixed-placement` | `LEGACY_ONLY` | `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0` | 별도 검증 작업본 SHA 미확인 | 기존 고정 위치 레거시 경로만 사용. 범용 Profile ADD 우회 차단. |

SHA 검색은 `data/`와 `validation-results/`의 XLSX 180개를 대상으로 수행했다. 세 공식 원본은 각각 카탈로그의 source SHA와 일치하지만, 이는 작업본이 아니며 절대 ADD 입력으로 대체하지 않았다. 실제 공정 TEST_ONLY 생성 및 PDF/시각 검수는 이번 작업에서 수행하지 않았다.

### 합성 레이아웃·보존 검증
- `DiagramEquipmentInsertionEngineTest`: 11/11 PASS. `GeneralDiagramEditProfileStatusTest`: 1/1 PASS. Java compile/test-compile 성공, Surefire 실패 0 / 오류 0 / skip 0. 별도로 PW-01-05 전용 엔진 회귀 5건 및 `DiagramBoxChangeEngineTest` 6건 PASS.
- 합성 artifact run: `validation-results/2026-11-01-step7-7-profile-add-test/20261101-115805-516/`; `artifact-sha256.tsv`의 7개 SHA를 실제 파일과 대조하고 모두 `unzip -t` 통과.
  - `01-rectangle.xlsx` — `57515ca47c54c2b30eb2ca4749f4af42daf0d1449a06256eb891d9ba8c9e539c`
  - `02-merged.xlsx` — `2e902e7cbfa6dbcd4c3b306124f6dc1d8c0f3c1d33958e9b49d02bf8c9e99aa4`
  - `03-id-name-rows.xlsx` — `ddc093cc3fdc5ee682629b24f9cafe08317e14a24dc50a8909ed85f67340627f`
  - `04-item-separate-area.xlsx` — `19c3c804e08245c1a993435452914063f7d8d35ce48b066f1357b4f38c99142a`
  - `05-capacity-unit-split.xlsx` — `494c9cd04a7bf1b1466e152b5abf445bb33b040d728b21d523711afd7a791b90`
  - `06-variable-row-heights.xlsx` — `f86b96af6c7197cf91bd58c0954637a2264c2e81c1c6e0fc22e6972d13763bbe`
  - `07-adjacent-drawing.xlsx` — `dd13144f2e8bd62fa13b81b322755caa6efa615046d623b0fd369d6ac42d2396`
  - `08-connector-crossing` — 생성 차단 `DRAWING_ANCHOR_CROSSES_INSERTION`.
- 01–06은 서로 다른 worksheet XML SHA 및 좌표/값/서식을 검증했고, 07은 인접 DrawingML 객체가 있는 별도 입력으로 object ID·이름·앵커 보존을 검사했다. 08은 삽입 경계를 넘는 Connector가 차단되는 negative case다. POI 재열기에서 신규 필드/스타일, 기존 셀 값·스타일, 병합, 행 높이, 열 너비, 페이지 설정/여백/인쇄 영역, 기존 DrawingML/Connector ID·연결·화살표를 함께 확인했다.
- UI Profile 상태를 `VERIFIED`, `REVIEW_REQUIRED`, `UNSUPPORTED`, `LEGACY_ONLY`로 명시하고, 서버도 미등록 위치를 `UNSUPPORTED`, PW-01-05 고정 후보를 `LEGACY_ONLY`로 반환한다. REVIEW/UNSUPPORTED는 preview 차단, LEGACY_ONLY는 기존 전용 경로로만 허용한다. 화면 inline JS 구문 검사 PASS.
- API DB integration/browser login-to-download E2E는 수행하지 않았다. 격리 DB 환경변수가 없고 애플리케이션 기본 DB가 금지된 `process_map_dev`이므로 통합 테스트에 접속하지 않았다. Chromium/Playwright도 제공되지 않았다. 기존 TEST_ONLY 산출물 overwrite를 피하기 위해 경로가 이미 존재하는 `createsNineCharacterTestOnlyWorkbookWithExistingEquipmentStyle` 테스트는 실행하지 않았다.
- Profile 적용 범위 확대 및 실제 공정 적용은 정확한 source+working SHA로 검증된 독립 작업본을 확보한 뒤 진행한다. 물리 연결 증거가 없으므로 실제 연결/유량 생성 금지, readiness는 `CALCULATION_UNAVAILABLE` 유지.

## STEP 7.8 마감 — 사용자 작업본 Profile ADD (2026-11-02)

### 기능 및 API
- V25는 사용자가 업로드한 `.xlsx` 작업본을 기준 공정도 ID·기준 SHA·작업본 SHA·소유자·버전과 함께 관리하고 TEST_ONLY 출력 이력을 append-only로 저장한다. 로그인 사용자 세션과 CSRF 경계를 재사용한다.
- 현재 작업 경로: `POST /api/workspace/diagram-editor/working-copies` 업로드/등록, `GET /api/workspace/diagram-editor/working-copies?baselineDocumentId=...` 버전 목록, `GET /api/workspace/diagram-editor/working-copies/{id}` 상세, `GET /api/workspace/diagram-editor/working-copies/{id}/sheets` 시트, `GET /api/workspace/diagram-editor/working-copies/{id}/locations?sheetName=...` 위치/프로필 판정, `POST /api/workspace/diagram-editor/preview`, `POST /api/workspace/diagram-editor/generate`, `GET /api/workspace/diagram-editor/test-only-outputs/{id}/download` 출력 다운로드.
- Profile은 기준 SHA와 작업본 SHA가 등록 값과 모두 정확히 일치할 때만 `VERIFIED`로 다룬다. stale SHA는 `REVIEW_REQUIRED`, 미등록 Profile은 `UNSUPPORTED`; 이 상태에서는 preview/generate를 차단한다. PW-01 고정 위치 경로의 `LEGACY_ONLY` 경계도 유지한다.
- 생성 결과는 `TEST_ONLY` / `REVIEW_REQUIRED`; `X-Output-SHA256`, `X-Scenario-Status`, `X-Official-Baseline-Modified`, `X-Connection-Evidence` 헤더를 반환한다. 물리 연결 증거는 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`로 남고 connector·유량은 생성하지 않는다. `USER_WORKING_COPIES_ENABLED` 기본값은 `false`이며 브라우저 시험 앱에서만 임시로 활성화했다.

### 테스트 및 브라우저 확인
- 합성 등록→정확한 SHA Profile 판정→preview→TEST_ONLY 생성→세션 사용자 다운로드 통합 흐름 포함 5개 클래스 총 27/27 PASS, 실패 0·오류 0·skip 0: `OperatorUserWorkingCopyIntegrationTest` 3, `DiagramEquipmentInsertionEngineTest` 11, `GeneralDiagramEditProfileStatusTest` 1, `Pw0105EquipmentInsertionEngineTest` 6, `DiagramBoxChangeEngineTest` 6. 격리 DB 회귀 테스트는 이미 존재하는 V1.0 baseline release와 append-only 출력 이력을 보존하도록 합성 baseline helper/count assertion을 조정했다.
- Chromium CDP 브라우저 UI E2E 8/8 PASS: 로그인 → 작업본 업로드/버전 표시 → Profile `VERIFIED` → 설비 입력 → preview → TEST_ONLY 생성 → 다운로드 SHA 대조 → 로그아웃. console errors 0; logout 뒤 `authenticated=false`. 선택적 `modify/equipment-options` 조회 404가 한 건 관찰됐지만 ADD 화면 흐름과 다운로드는 성공했다.
- E2E는 격리 `process_map_step78_test_20261102`와 합성 baseline/작업본만 사용했다. Flyway V25 적용 확인. 테스트 후 DB에는 합성 baseline release/document 1건씩, 사용자 작업본 1건, TEST_ONLY 출력 2건이 남아 있다(브라우저 반복 산출); 출력의 SHA는 브라우저 다운로드와 일치하고 두 출력 모두 `REVIEW_REQUIRED`다. append-only 및 FK 데이터를 제거하지 않았다. `process_map_dev`/운영 DB는 사용하지 않았다.

### 보존 검증 및 산출물
- 합성 XLSX 3개 및 브라우저에서 받은 별도 다운로드 1개의 SHA/크기/분류는 `validation-results/2026-11-02-step78-closeout/SHA256SUMS.tsv`에 기록했고, 네 파일 모두 manifest 대조와 `sha256sum -c`, ZIP `unzip -t`를 통과했다.
  - `SYNTHETIC_BASELINE_NOT_OFFICIAL.xlsx` — 5,079 bytes, SHA-256 `6d4f5b206cd3fd35defcd17d6d49ee7b5c4bd11fc5bc518f8cc65d503fe1e1bd`
  - `SYNTHETIC_USER_WORKING_COPY.xlsx` — 5,085 bytes, SHA-256 `1404314f9fdb8f3c4a25be16391810fbfac3fb8a852ad47f33a3cd2c72d02c38`
  - `SYNTHETIC_PROFILE_ADD_TEST_ONLY.xlsx` — 5,251 bytes, SHA-256 `00c8f5e4caaf9a4c7348d433be9bc5e2587972de7a604d4e584de932f11d8f67`
  - `browser/downloads-final/P-02_P-02-01_TOP__TEST_ONLY_ADD_A11_F18.xlsx` — 5,262 bytes, SHA-256 `3512a4813a363b76e61d5eea5bc3336b91a8cd5f33ff728580369ba932e57981`; 다운로드 `X-Output-SHA256`와 실제 파일 SHA가 일치.
- 브라우저 다운로드를 Apache POI로 독립 재열기했다. management ID `SYNTH-BROWSER-ADD-01`, 유형 `Storage`, 이름 `Synthetic browser tank`, Item No. `SYNTH-BROWSER-ITEM-01`, 용량 `125m3/h`, 수량 `1ea`가 확인됐다. 해당 필드 글꼴은 맑은 고딕 8pt, indexed color 8(검정); synthetic layout merge 0개, DrawingML 1개 보존, print area 유지. 상세 셀/스타일 결과는 `browser/downloads-final/POI_AUDIT.txt`에 있다.
- 모든 XLSX는 명시적 synthetic/TEST_ONLY 자료다. 실제 P-02 작업본 SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`와 일치하는 파일은 확보하지 못했다. 이 브라우저 시험은 실제 P-02 설비 ADD 검증이 아니다.

### 미완료 및 다음 단계
- 실공정 P-02 ADD는 정확한 실제 작업본과 그 기준본/작업본 SHA 확인, 필드·좌표·서식 독립 확인, 사업장 승인 절차가 끝날 때까지 미완료다. 물리 연결 증거 없이는 실제 물질수지 readiness를 `CALCULATION_UNAVAILABLE`로 유지한다.
- 2026-11-01 What-if 다운로드 JSON SHA와 현재 저장 파일 SHA 불일치는 별도 미해결이며 STEP 7.8 browser ADD의 정상 SHA 확인으로 해결됐다고 처리하지 않는다.
- STEP 7.9 준비: 실제 작업본 확보 후 exact-SHA/Profile 독립 재검증, ADD 영향/보존 검토를 진행하고, 별도 승인 전에는 기능 범위·공식 자료·운영 DB를 변경하지 않는다.
- 시험용 Java 앱과 isolated Chromium 프로세스는 종료했고 시험용 계정/DB 접속정보 임시 파일을 삭제했다. 기존 PM2는 재시작하지 않았다.

## STEP 7.9 Profile 확대·범용 ADD 엔진 검증 (2026-11-01)

### 공정별 Profile 매핑 및 상태
| 공정/시트 | Profile 상태 / Evidence Level | 구조 매핑 | 현재 한계 및 판정 |
|---|---|---|---|
| P-02 / `P-02-05(BROKE)` | `REVIEW_REQUIRED` / `STRUCTURE_ANALYZED` | 박스 `J129:AA136`(8행); ID `J129`, 유형 `J131`, 이름 `J132`, Item No. `J133`, 용량+단위 `J134`, 수량 `J135` | 후보 `J137:AA138`은 2행뿐이다. 전체 8행 삽입 예상 범위 `J137:AA144`의 주변에는 `W140:AA146` 병합이 겹치고 Connector 57(`D143:J143`)이 J열 경계에 닿는다. 아래 설비 `I-PP25008` 박스 `J147:AA153`도 이동/보존 검증이 필요하다. 따라서 generic ADD 허용 근거가 부족하며 실제 사용자 작업본 SHA도 없다. |
| PW-02 / `PW-02-01` | `REVIEW_REQUIRED` / `STRUCTURE_ANALYZED` | 박스 `J145:AA152`(8행); ID `J145`, 유형 `J147`, 이름 `J148`, Item No. `J149`, 용량+단위 `J150`, 수량 `J151` | 과거 TEST_ONLY output SHA `d0c49f943993dd47cca210c3b5d45622a18815a5036d6b2fd83708c37694bd68`와 historical working SHA `4250b004c723b9d366d1231ce98d75761a864564402250fe5a5873665b00b8d1`는 현재 작업본 BLOB 확인과 별개다. 현재 15개 후보 위치에 DrawingML 교차가 있으며 명시 shape binding은 0. 실제 작업본 바이트/SHA 없음. |
| P-01 / `P-01-01(TOP)` | `REVIEW_REQUIRED` / `STRUCTURE_ANALYZED` | 박스 `J4:AA20`(17행); ID `J4`, 유형 `J6`, 이름 `J7`, Item No. `J8`, 용량+단위 `J9`, 수량 `J10` | 8개 후보 모두 DrawingML 교차(각 3–10개), 4개는 merge도 교차한다. W열 폐수/소음 부가 라벨·데이터를 보존할지와 DrawingML 영향이 미검증이고 정확한 작업본 SHA 없음. 합성 17행 성공으로 실제 Profile을 승인하지 않는다. |
| P-02 / `P-02-01(TOP)` | `VERIFIED` / `WORKING_COPY_VERIFIED` | 기존 exact-SHA Profile | 해당 등록된 작업본 SHA에 한정해 유지. |
| PW-01 / `PW-01-05` | `LEGACY_ONLY` / `LEGACY_FIXED_PATH_ONLY` | 기존 고정 경로 전용 | 범용 Profile ADD로 우회하지 않는다. |

구조 매핑의 근거는 `src/main/resources/equipment-box-profiles.json`의 공정 Profile 카탈로그와 잠금된 15개 공정도/62개 시트 inventory fingerprint `ba0cb3fd504363bddaf900b3c16f3f47f2034e68a5990a68e47c10ef50e833cb`이다. 구조 분석이나 과거 합성 output은 현재 사용자 작업본 SHA 검증을 대신하지 않는다.

### 합성 확장 산출물 및 보존 점검
- 신규 테스트 `DiagramEquipmentInsertionEngineTest#generatesDistinctSyntheticProfileExpansionLayoutsWithManifestAndPreservedDrawing` 통과. 8행 split capacity/unit 및 17행 merged layout을 별도 baseline/working copy/ADD 결과로 생성했다. 두 output 모두 명시 `SYNTHETIC_TEST_ONLY`이며 `TEST_ONLY`/`REVIEW_REQUIRED`; 실제 P-01/P-02 작업본이나 공정 성공으로 해석하지 않는다.
- 최신 산출물 경로: `validation-results/2026-11-01-step7-9-profile-expansion/20261101-150951-113/`. `SHA256SUMS.tsv`의 6개 파일을 실제 SHA-256/byte size와 각각 대조했고, 모든 ZIP CRC/무결성 검사를 통과했다.
  - 8행 compact split-unit ADD output: 6,374 bytes, SHA-256 `8e8d0ffb722f0f3c907dca5cdcda697c316a044d08f1880fb0dbc3226bbe4fc2`.
  - 17행 long merged-box ADD output: 7,368 bytes, SHA-256 `4d983d97b6ca931b0964d128f58bfb846886b1a9cfff337f2d6afe41f5104ebb`.
- POI 재열기 후 Profile 상대 셀 값, 용량/단위 분리, 매핑 스타일·맑은 고딕·검정 글씨, row heights, J:AA 열 너비, 삽입 전 기존 값/셀 스타일/행·열 크기, merge, print area/page setup/여백을 확인했다. 두 layout의 workbook/worksheet 구조와 output SHA는 서로 다르다.
- DrawingML Shape/Connector의 고유 ID, 이름/종류, connector endpoint binding, arrow, anchor rows/columns/offset, transform 특성을 비교했고 객체 identity를 유지한 채 삽입 경계 아래로 이동하는 것을 검증했다. 삽입 경계 횡단 Connector negative fixture는 `DRAWING_ANCHOR_CROSSES_INSERTION`으로 차단됐다. 신규 connector/물리 연결/유량은 만들지 않았다.
- 4개 focused 회귀 클래스 결과: `DiagramEquipmentInsertionEngineTest` 12/12, `GeneralDiagramEditProfileStatusTest` 1/1, `Pw0105EquipmentInsertionEngineTest` 6/6, `DiagramBoxChangeEngineTest` 6/6 — 총 **25/25 PASS**, failure/error/skip 0. UI inline JavaScript `node --check`와 `git diff --check` 통과.
- DB-backed `OperatorUserWorkingCopyIntegrationTest`는 이번 실행에서 건너뛰었다. `.env`의 DB target이 금지된 `process_map_dev`이고 명시적 격리 DB 설정이 없어서 접속하지 않았다. API metadata에 대한 통합 테스트 assertion(`profileEvidenceLevel`, `workingCopyShaMatch`, `profileFields`, 기존 `profileStatus`/`profileShaMatch`)은 유지했지만 이번 실행의 runtime API 검증 결과로 주장하지 않는다.

### 다음 확대 조건
- P-02-05, PW-02-01, P-01-01은 정확한 source 및 사용자 작업본 바이트/SHA, 전체 삽입·하단 설비·merge·DrawingML 변화 검토, 사용자 field mapping 근거를 확보할 때까지 `REVIEW_REQUIRED`로 둔다. 현재 값에 대한 Profile의 `VERIFIED` 승격은 하지 않는다.
- 실제 물리 연결 evidence가 없으므로 공정 연결이나 유량을 생성하지 않고 readiness는 `CALCULATION_UNAVAILABLE`을 유지한다. 공식 Excel, 운영 DB, PM2, 배포, `main` 병합은 변경하지 않았다.

## STEP 8.0 Profile 기반 MODIFY (2026-11-01)

### 구현 및 화면 흐름
- 기존 `DiagramBoxChangeEngine`, `DiagramModifyService`, Profile Repository, 로그인 세션/CSRF, 사용자 작업본 경로를 재사용한다. 사용자 작업본일 때 기준 source SHA, 실제 working-copy bytes SHA, 공정/시트/Profile을 다시 일치시킨 뒤 Profile 상대 셀·현재값·문자 셀 유형·서식·행 높이·merge·DrawingML 충돌을 검증한다.
- 실제 Profile에서 조회 가능한 필드는 `equipmentName`, `businessItemNo`, `capacity`, `capacityUnit`, `quantity`, `equipmentTypeLabel`, `note` 중 해당 Profile에 명시되고 해당 작업본에서 `VERIFIED`인 항목만 수정 가능하다. `managementNo`는 설비/연결 관계 remap이 필요하므로 단일 셀 변경을 항상 차단한다. 값은 검증된 TEXT 계열만 받고, 빈 값/제어문자/필드별 길이 초과/동일값 변경을 거부한다. 스키마/값 형식이 확인되지 않은 숫자 환산이나 용량·단위 자동 변환은 하지 않는다.
- P-02/PW-02 기존 master mapping 기반 Item No. MODIFY 경로를 보존한다. Profile 경로는 `expectedCurrentValues`를 미리보기 API까지 전달하며 stale value는 `STALE_VALUE`로 차단한다. Item No. duplicate, 셀/스타일/필드유형 불일치, profile/source/working SHA 불일치, 수식/병합 충돌, 알 수 없는 DrawingML 위치/필드 교차 시 출력하지 않는다. 대상 worksheet 이외 OOXML package parts를 byte 단위로 보존하고 결과를 POI로 재열기한다.
- Profile 기반 UI는 설비별 매핑 상태와 차단 사유를 표시하고 수정 가능한 필드만 입력받는다. 입력값과 조회 당시 값을 함께 `/api/workspace/diagram-editor/modify/preview`에 전달하고 변경 전후 행을 보여준 뒤 기존 `generate`/`download/{previewId}`를 이용한다. TEST_ONLY output 저장 경로는 기존 사용자 작업본 기능 flag에 종속되며 기본 비활성 설정을 변경하지 않았다.

### 합성 XLSX 검증
- 테스트: `DiagramBoxChangeEngineProfileModifyTest`. 8행 합성 레이아웃의 설비명, Item No., 용량 단독, 단위 단독, 용량+단위, 수량 변경; 17행 병합 레이아웃 설비명 변경; 인접 DrawingML/Connector 존재 상태의 셀 수정과 객체 보존을 확인했다. 별도 negative case에서 기존값 불일치, 잘못된 field style, merge/formula 충돌, Profile TEXT 셀과 실제 numeric cell type 불일치, 동일값, 중복 설비번호/Item No., 설비번호 재매핑 요구를 차단한다.
- POI 재열기 후 변경 요청 셀 외 값과 style, 병합 수, 각 셀 스타일, 맑은 고딕 8pt·검정, 행 높이/열 너비, print area/page setup/여백, DrawingML object inventory 및 raw drawing part를 비교한다. 테스트에서 output SHA-256을 계산하고 ZIP을 열어 검증한다. capacity/단위는 TEXT 필드의 독립 표시값으로만 바뀌며 수치 단위 환산은 하지 않는다.
- 합성 산출물 디렉터리: `validation-results/2026-11-01-step8-0-profile-modify/run-7697249810495850175/`. `SHA256SUMS.tsv`의 37개 항목은 파일별 SHA-256 및 byte size와 대조했고 ZIP integrity 검사 모두 통과했다. 각 파일은 `SYNTHETIC_TEST_ONLY`/`TEST_ONLY_REVIEW_REQUIRED`로 분류한다. 설비명 output SHA `7b45cb34ae95767669c912f9a6fda81af46e446e47f9b69ecb16dd9fd3bf1126`; Item No. `5fe03f243278c618b6a8c9884d1a21e6fd53e4d53699904df340585403790fff`; capacity-only `f77351ae8c06fb9b77dcfda4d6412e3b1239edbe45c2ca3631a751664bb5f714`; unit-only `9347b51165ea78f2ff3d183bad44552c8fa9d0e62091ba06366f251035402633`; 17행 merged `afa6cfdd8e79ef4ca57012290e572a536c8db798a4199148e6f05d3d8a0cb402`; DrawingML neighbor `5f734b7c0e1b128d548a5ebe5e712cc71ca06791aed95fee1d7f908d4e9bccc1`. 전체 파일별 digest/size/classification은 manifest에 기록한다.

### 테스트 및 제한
- 집중 회귀 총 **27/27 PASS** (failures/errors/skips 0): `DiagramBoxChangeEngineProfileModifyTest` 1, `OperatorDiagramModifyControllerProfileRequestTest` 1, `DiagramBoxChangeEngineTest` 6, `DiagramEquipmentInsertionEngineTest` 12, `GeneralDiagramEditProfileStatusTest` 1, `Pw0105EquipmentInsertionEngineTest` 6. DB 비의존 MockMvc 계약 테스트에서 Profile 변경 field keys와 `expectedCurrentValues`가 API DTO/service preview로 전달되는 것을 확인했다. index.html inline JavaScript는 Node `--check`를 통과했다.
- 브라우저 E2E 및 실제 Spring/API+DB 런타임은 수행하지 않았다. 격리 DB 접속 변수가 없으므로 DB 통합 테스트를 실행하지 않았다. `.env`/기본 설정의 `process_map_dev`에는 접속하지 않았다. `README`의 UI/API 연동은 구현과 DB 비의존 요청 계약 테스트 범위이며 브라우저 end-to-end 통과 의미가 아니다.
- 저장된 Profile 카탈로그의 P-02-01 exact working SHA 이외 실제 공정 사용자 작업본으로 MODIFY를 검증하지 않았다. P-02-05, PW-02-01, P-01-01은 계속 `REVIEW_REQUIRED`; PW-01-05 `LEGACY_ONLY` ADD 경로는 수정하지 않았다. 합성 8행·17행 검증은 실공정 Profile 승격이나 공식 Excel 수정 근거가 아니다.
- 실제 물질수지 readiness는 `CALCULATION_UNAVAILABLE`을 유지한다. 다음 DELETE 개발에서는 현재 `/modify/delete-impact`의 REVIEW_REQUIRED 영향 분석만 재사용하고, 삭제 대상 셀·merge·drawing/connector·인접 라벨 및 관계 증거가 모두 확인될 때까지 actual delete 및 공식 자료 수정은 열지 않는다.

---

## STEP 8.1 — DELETE 영향 분석 및 합성 TEST_ONLY 엔진 안정화 (2026-11-02)

### 구현 및 안전 경계
- 기존 `DiagramBoxChangeEngine`, `DeleteImpactAnalyzer`, `DiagramModifyService`, exact-SHA Profile, USER_WORKING_COPY, 세션/CSRF, append-only 변경 제안 및 TEST_ONLY output 저장 경로를 재사용한다.
- `GET /api/workspace/diagram-editor/modify/delete-impact?workingDocumentId=&sheetName=&managementNo=`는 읽기 전용 영향 분석이다. 대상 셀/현재값/셀 유형/스타일, Profile·원본·작업본 SHA, 병합 범위, 전체 DrawingML/Connector metadata, 주변 설비, row/column layout, print area/page setup 및 차단 사유를 반환한다.
- `analysisFingerprint`는 canonical 순서로 Profile 속성 전체, process/sheet/equipment 식별자, expected/actual SHA, 대상 셀·값·유형·스타일, merge, 전체 도형/Connector(binding/anchor 포함), 인접 설비, 레이아웃 및 blockers를 digest한다. 동일 입력은 안정적으로 재현되며 Profile/작업본/대상/구조 변화는 fingerprint를 무효화한다.
- Preview는 사용자 소유 작업본과 기능 플래그를 확인한 뒤 서버에서 현재 파일/Profile을 다시 읽고 SHA 및 fingerprint를 비교한다. Generate도 다시 분석해 미리보기와 동일한 `SAFE_BOX_ONLY_TEST_ONLY` 상태인지 확인하고, 최신 assessment를 DELETE 엔진에 전달한다. 엔진은 assessment status/허용 여부 및 현재 bytes SHA를 대조한다. stale 또는 비SAFE 요청은 XLSX 생성과 TEST_ONLY output/proposal 결과 기록 전에 차단한다.
- SAFE 범위는 합성 fixture에 명시된 `BOX_ONLY_SYNTHETIC_TEST_ONLY` 또는 `BOX_AND_PROFILE_MERGES_SYNTHETIC_TEST_ONLY` 정책으로 제한한다. 기존 Profile 카탈로그의 다섯 `deletePolicy`는 모두 `REVIEW_REQUIRED`이며 승격하지 않았다. 실제 설비 삭제 승인·연결 증거로 간주하지 않는다.
- 외부/경계 병합, formula, duplicate ID, Profile/SHA 불일치, REVIEW_REQUIRED/UNSUPPORTED, 위치 미상·교차·인접 DrawingML, shape-bound 또는 범위를 관통하는 Connector는 생성 차단이다. 실제 도형/Connector 삭제·재배선은 하지 않는다. 공식 원본과 작업본은 불변이며 실제 연결 evidence/readiness는 각각 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE` / `CALCULATION_UNAVAILABLE`을 유지한다.
- UI는 사용자 작업본과 선택 설비를 재사용하고 read-only 분석 결과/fingerprint/차단 사유를 표시한다. SAFE이면 preview→generate→session-scoped download 버튼 흐름을 제공한다. 인증, CSRF, 소유자 검사, 기능 flag default false를 유지한다.

### 합성 Excel 산출물
- 신규 `DeleteImpactAnalyzerSyntheticTest`는 8행 BOX_ONLY 및 17행 승인된 합성 내부 merge fixture를 분석·삭제·재열기하고, 비대상 셀/스타일, merge 정책, DrawingML/Connector parts, row heights/column widths, print area/setup 및 ZIP package 보존을 확인한다. 추가 negative case로 boundary merge, 인접 도형, 원거리 bound/crossing Connector, duplicate ID, source/working SHA, Profile 상태와 delete policy를 차단한다.
- 최신 재검증 디렉터리: `validation-results/2026-11-02-step8-1-delete/run-4444900034801975078/`. `SHA256SUMS.tsv`의 6개 XLSX 각각 실제 SHA-256 및 byte size가 manifest와 일치하고 ZIP CRC 검사도 통과했다. 과거 run 디렉터리는 덮어쓰지 않고 보존했다.
  - `SYNTHETIC_TEST_ONLY_DELETE_8_ROW_BASELINE.xlsx` — SHA-256 `993204e0dd73ff50956ac8a8f10867537a56f1033780b8f36524c324a95b92fc`.
  - `SYNTHETIC_TEST_ONLY_DELETE_8_ROW_USER_WORKING_COPY.xlsx` — SHA-256 `3a0bef9f2c18348d4bed77aded5fc695e4ff623a5b2e8a693ba59510d1b3b6ce`.
  - `SYNTHETIC_TEST_ONLY_DELETE_8_ROW_OUTPUT.xlsx` — 4,231 bytes; SHA-256 `23cc57eb4cbbbc0e4f80138b8f4af4023f45323358c18f790b6a825b2573e08f`.
  - `SYNTHETIC_TEST_ONLY_DELETE_17_ROW_BASELINE.xlsx` — SHA-256 `721d05047b2a8cb7eca6b581d26f712be9fe1566da2936d588ff1d176a5e476f`.
  - `SYNTHETIC_TEST_ONLY_DELETE_17_ROW_USER_WORKING_COPY.xlsx` — SHA-256 `7f1f4640e69f257b56b723050e5be2411829d9a76c42e3de7882ef94d5247170`.
  - `SYNTHETIC_TEST_ONLY_DELETE_17_ROW_OUTPUT.xlsx` — 4,591 bytes; SHA-256 `629937f97df43c680a52f76954bd77f31be91a3fea22206b28c8e7a1fb2f2c48`.
- 산출물은 synthetic/TEST_ONLY/REVIEW_REQUIRED이며 gitignored `validation-results/`에 있다. 어느 것도 실제 사업장 설비 삭제 결과가 아니다.

### 최종 회귀 및 제한
- 최종 assessment-guarded DELETE overload 변경 후 `./mvnw -q -DskipTests test-compile` 성공 및 focused suite **36/36 PASS** (failures/errors/skips 0): `DeleteImpactAnalyzerSyntheticTest` 4, `DiagramModifyServiceDeleteGateTest` 4, `OperatorDiagramModifyControllerProfileRequestTest` 2, `DiagramBoxChangeEngineTest` 6, `DiagramBoxChangeEngineProfileModifyTest` 1, `DiagramEquipmentInsertionEngineTest` 12, `GeneralDiagramEditProfileStatusTest` 1, `Pw0105EquipmentInsertionEngineTest` 6. 합성 성공 경로는 public assessment overload를 통과하고, stale-SHA assessment는 overload에서 거부된다.
- 요청 예시의 `DiagramBoxChangeEngineDeleteTest` 클래스는 저장소에 없어 해당 이름 실행은 “No tests matching pattern”으로 종료됐다. 실제 DELETE 합성 클래스는 `DeleteImpactAnalyzerSyntheticTest`이며 이 클래스를 포함한 위 8개 클래스의 Surefire XML 합계가 36/36이다.
- inline JavaScript `node --check` PASS. UI 코드에는 작업본 선택→설비 선택→영향 분석→상태/차단 표시→preview→TEST_ONLY generate→세션 download 흐름이 연결되어 있다. API/서비스 검증은 DB 비의존 MockMvc 및 mock 기반 owner/feature-flag/fingerprint/stale-generate gate 테스트 범위다. 로그인·CSRF 호출부는 코드 검토했으나 live runtime API/browser E2E로 검증한 것은 아니다.
- 격리 DB 환경변수 3개가 unset이므로 DB integration 및 Spring+DB runtime은 미실행. 브라우저 E2E도 미실행. `process_map_dev`, 운영 DB/migration, 공식 Excel, 기존 PM2, deployment 및 `main`은 건드리지 않았다. 실제 사업장 설비 DELETE는 미검증이며 연결 증거 없음/물질수지 `CALCULATION_UNAVAILABLE`을 유지한다.
- STEP 8.2에서 제안의 synthetic What-if와 PIPE_CHANGE 연결검증을 수행했다. 실제 DELETE 승인, 물리 연결 생성 및 실제 물질수지 계산은 별도 증거/검토 전까지 차단 상태로 유지한다.

---

## STEP 8.2 — 합성 PIPE_CHANGE / What-if 검증 마감 (2026-11-02)

### 범위·지원 제한
- 이번 단계는 합성 fixture 한 건만 대상으로 한다: DrawingML `xdr:cxnSp` `straightConnector1`, 동일 시트의 단순 Shape endpoint, port `idx=0`, 단일 셀 anchor. Before `SYN-EQ-A → SYN-EQ-B`(shape 1→2)에서 After `SYN-EQ-A → SYN-EQ-C`(shape 1→3)로 변경했다. 그 외 Connector 유형·그룹/복합 Shape·다른 시트는 지원한다고 주장하지 않는다.
- 엔진은 Connector ID 4와 endpoint binding/anchor, `a:xfrm`을 검사한다. 변경 대상 밖 Shape/Connector, 비대상 OOXML part, 셀 값/서식, merge, 인쇄 영역, row/column geometry의 보존을 테스트한다. 서버 fixture Connector는 진한 파란 선과 삼각형 tail arrow로 렌더된다.
- Before/After network edge는 `SYN-A-B`/`SYN-A-C`로 별도 매핑한다. 설비·공정, 물질/종류, 단위, 기간, 연결 유형과 명시된 고정 수량을 비교하고, edge 중복·누락·변경, 미변경 edge drift 및 계산 불가 상태를 차단한다. 합성 수치 20 ton/day는 실제 운전값이 아니다.

### What-if·보안 경계
- `NetworkWhatIfService.calculateTestOnly`는 package-scoped 내부 진입점으로 낮췄다. `OperatorWhatIfController` 및 공개 사용자 제안 경로는 입력 DTO를 `calculate()`에 보내며 해당 경로는 실제 readiness를 조회한 뒤 검증한다. `ChangeProposalService.recordWhatIf`도 user DTO를 `calculate()`로 처리한다. PIPE_CHANGE 서버 workflow만 `PipeChangeSyntheticNetworkFactory`의 서버 생성 A→B/A→C pair를 `calculateTestOnly`로 실행한다.
- 합성 PIPE_CHANGE 결과 기록은 내부 `recordSyntheticPipeChangeWhatIf`로 분리했다. 해당 메서드는 제안 소유자/유형, 동일 시뮬레이션 TEST_ONLY 보고서, 계산 가능 상태, `CALCULATION_UNAVAILABLE`, baseline 미변경을 검사한다. 일반 요청 본문에서 `TEST_ONLY`/boolean만 제시해 우회할 수 없다.
- API: `GET /api/workspace/diagram-editor/pipe-change/fixture`, `POST .../preview`, `POST .../generate`, `GET .../download/{previewId}`. 기존 Operator 로그인, POST CSRF, session-scoped preview/output, user working-copy ownership 및 `registeredBy` 대조를 적용한다. Preview의 ID/SHA, 서버 fixture SHA·바이트, drawing fingerprint를 Generate 직전 재검증한다. 잘못된 fixture ID·SHA·소유자·세션·stale preview는 차단한다. PIPE_CHANGE와 user-working-copy 기능 flag 기본값은 모두 OFF다.
- 실제 연결 증빙은 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`이며 공식 readiness는 모든 경로에서 `CALCULATION_UNAVAILABLE`. 공식 도면/마스터 변경 또는 실제 유량/승인은 생성하지 않았다.

### XLSX·PDF/PNG 결과
- 최종 서버 workflow 테스트 산출물: `target/test-artifacts/step8-2-pipe-change/server-fixture-run-17089997718434367208/`; manifest `SHA256SUMS` 일치, ZIP CRC PASS. Before XLSX SHA-256 `b3665efa6e20403a3005b445be0b3a1171971c6485dba7c034b4487fc7e68317`; After XLSX SHA-256 `e9652c82c941fd004a1041afbb06ef0f102877ae5eb882733141b8077814fbae`.
- OOXML part map에서 두 파일의 유일한 차이는 `xl/drawings/drawing1.xml`. Connector ID 4는 Before 1:0→2:0, After 1:0→3:0이고 anchor/xfrm 좌표가 변경됐다. POI 재열기와 test-only generate의 part/cell/layout 보존 검증도 통과했다.
- LibreOffice 렌더링 run: `target/test-artifacts/step8-2-pipe-change/visual-review-run-PsHpFX/`. 둘 다 A4 1페이지. PDF SHA: Before `cc6573b72170e203d4b5223c8cd3730ba227fce8e7b79240e8ee09e6b52ccaa9`, After `e161d11beee5fce8e3c1486f63f2af4f118b662cf0efd190c6bd999614a820f8`. PNG SHA: Before `5e2970774a8a28c0238eaf1a700e44310381a93b6527effdd8d8b096262d0571`, After `8a26fa251c3b0b1fc219ad8ad28730edd439691856c33614aa9171cffa8841e7`.
- 화면에서는 Before 수평 화살표가 B 쪽, After 대각 화살표가 C 쪽으로 이동한 것을 확인했다. 180dpi 렌더 비교 크기 `(1489,2105)`, pixel-difference box `(298,182,781,345)`, changed pixels 4,933. 그러나 After 화살촉이 C 라벨/port의 정확한 접점에 있는지 LibreOffice 렌더만으로 확정할 수 없고 도형 텍스트와 겹쳐 보여 시각 판정은 **`VISUAL_REVIEW_REQUIRED`**다. Geometry/OOXML 검증 PASS와 시각 접점 PASS를 혼동하지 않는다. 저장소를 mock한 `workflow.download()` test에서 반환 bytes SHA-256 `e9652c82c941fd004a1041afbb06ef0f102877ae5eb882733141b8077814fbae`가 생성 SHA와 같음을 확인했지만, 실제 DB BLOB/HTTP에서 내려받은 bytes는 미검증이다.

### 회귀·미완료
- 지정 12개 Surefire 클래스 **66/66 PASS**, failures/errors/skips 0: PIPE_CHANGE 18, solver 9, calculator 6, equipment insertion 12, PW0105 insertion 6, box change 6, profile modify 1, delete impact 4, delete gate 4.
- DB 접속변수(`SPRING_DATASOURCE_URL`, `DB_URL`, `DATABASE_URL`, `PROPOSAL_TEST_DB_URL`)가 모두 unset이므로 DB integration/실제 저장·다운로드 검증은 미실행. Browser E2E 및 실제 Operator API runtime 검증도 미실행. `process_map_dev`, migration, 공식 Excel, 기존 PM2, deployment, `main`은 건드리지 않았다.
- 실제 공정 PIPE_CHANGE는 계속 차단이다. 다음 `STEP 8.3 FLOW_CHANGE`는 별도 사용자 요청 후 시작하고, 승인된 물리 연결·유량 근거와 범위를 먼저 확보한다. 이번 단계에서 FLOW_CHANGE 기능을 추가하거나 실제 공정 값을 추정하지 않았다.

---

## STEP 8.3 — 합성 FLOW_CHANGE 계산·Excel 표시 검증 (2026-11-02)

### 재사용 범위 및 실제 inventory 분석
- 기존 FLOW_CHANGE proposal create/What-if API, `ChangeProposalService`, `NetworkWhatIfService`, `DirectedProcessNetworkSolver`, 기존 TEST_ONLY exporter와 append-only schema를 재사용했다. 새 계산기나 DB migration을 추가하지 않았다.
- `EquipmentBoxProfileRepository`는 설비 박스 필드 전용이며 유량 표시 binding은 없다. 공식 15개 `.xlsx`/62개 시트는 `data/reference/`에서 read-only로 조사하고 기존 inventory JSON을 재사용했다. 읽기 전용 셀 스캔에서 측정량·단위와 일치할 수 있는 텍스트 3,812개(병합 anchor 2,680, 일반 셀 1,132), 그중 명시 문자열 `유량` 174개를 찾았지만 edge, 기간, 물질과의 관계 증거는 없다. 이 값 전부는 `REVIEW_REQUIRED`; 숫자·`톤/일`·`m³` 표기만으로 유량으로 확정하지 않았다.
- inventory DrawingML 텍스트를 읽기 전용 검사했을 때 위 단위/유량 패턴과 일치하는 Shape text는 0개였다. 이는 실제 공정 Shape에 매핑이 없다는 승인 증명이 아니라, 이번 자동 편집 후보를 특정하지 못했다는 뜻이다. 수식·표시 텍스트·설비 용량/처리량/수량은 실제 연결망 유량과 분리했다. 공식 workbook은 수정·저장하지 않았다.

### 합성 FlowLabelBinding 및 계산 workflow
- 새 `FlowLabelBinding`은 fixture-bound process/sheet/edge, target type/address, 기존 수량, material class/type, unit, period, workbook SHA, mapping status와 evidence ID를 함께 보유한다. 실제 Profile catalog에 flow binding을 넣거나 실제 mapping status를 VERIFIED로 올리지 않았다.
- 서버 생성 fixture `SERVER_SYNTHETIC_FLOW_FIXTURE_V1`: `SYN-FLOW-P2X-P1N` edge ↔ `Synthetic Flow!B8` (병합 numeric anchor) 및 `B17` (일반 numeric cell), 20 synthetic ton/day, `WASTEWATER / synthetic process wastewater`, `2026-11-02/SYNTHETIC_TEST_PERIOD`. 두 표시 객체를 모두 매핑했다. 같은 edge 기준으로 20, 10, 50을 각각 독립 계산했고, 직전 scenario 결과를 다음 scenario의 base로 사용하지 않았다.
- 기존 solver를 재사용하여 분기·합류·전단/후단 및 interprocess flow, recycle 및 외부 경계의 계산/잔차를 검증했다. 새 내부 재순환 test에서는 20→30 변경에 따라 내부 두 edge가 20→30으로 바뀌고 외부 inflow/outflow는 각 100으로 유지되며 node/process residual은 0이다. 근거가 부족한 순환은 기존 solver test와 같이 `UNDERDETERMINED`로 남고 보정 유량을 만들지 않는다. 물질/단위/기간은 key로 분리하며 변환하지 않는다.
- `FlowChangeSyntheticWorkflowService.previewServerSyntheticFixture()`는 서버 fixture 및 source SHA를 재구성해 분석 fingerprint를 발급한다. Generate는 제출 fingerprint만 믿지 않고 현재 서버 fixture·binding·계산을 다시 구성해 일치할 때만 진행한다. 이 workflow는 DB/JDBC readiness를 조회하지 않고, 응답 readiness는 `CALCULATION_UNAVAILABLE`, 결과는 `TEST_ONLY_REVIEW_REQUIRED`다.

### Excel 편집 지원 및 생성 결과
- `FlowChangeDrawingEngine`은 OOXML worksheet의 정확한 numeric `<v>`만 part-level로 수정한다. 일반 numeric cell 및 병합 범위의 좌상단 numeric cell을 각각 검증/편집한다. 현재 문자열 cell, formula, 병합 비좌상단, DrawingML Shape text 및 불명확한 target은 차단한다. 표시용 `0.00` 형식과 계산 숫자를 분리해 출력 display string을 함께 기록하고 unit conversion/rounding correction은 하지 않는다.
- 시작 SHA, exact edge/binding, 기존 raw numeric value, target 중복, 병합 종류 및 source fingerprint를 확인한다. 수식·drawing/connector·비대상 OOXML part, 병합, 셀 style, 행/열·print settings를 보존하고 POI 재열기 값도 확인한다. 변경 작업은 원본을 덮어쓰지 않고 새 synthetic output bytes로 생성한다. 차단 오류는 XLSX 결과를 반환하지 않는다.
- 테스트 artifact run: `target/test-artifacts/step8-3-flow-change/review-run-13676778295918236172/`. SHA256SUMS 검증 및 XLSX ZIP/POI 재열기 PASS. Before `b0613c36a93ff568fa019e4686712dfdddd02ed0ae5388ae7efb1ea21da4dd71`; After 20 `4b599fe32240f02c1880d8b138ba2d0e7433a9c601684df46a0b8d38c6815ac5`; After 10 `81f37b11d8b0291eaf55041567d453f795629fb9143be1f5834be7950b446893`; After 50 `5c72a60379340e8f468a1d4a1816691b0cc97eaddae8d8468f5c925b75076e61`.
- 각 run은 도면 screenshot/PDF가 아니라 numeric worksheet fixture다. Numeric cell rendering은 Excel number format과 POI `DataFormatter`로 확인했지만 LibreOffice/Excel PDF·PNG 시각 검수는 미실행이다. STEP 8.2 `VISUAL_REVIEW_REQUIRED`는 그대로 유지한다.

### API·회귀·미완료
- FLOW_CHANGE proposal create 및 generic network What-if API는 이미 있으나, 새 합성 flow-label preview/generate/download를 controller route에 연결하지 않았다. `UserWorkingCopyService.saveTestOnlyOutput()`의 허용 변경 유형에도 FLOW_CHANGE가 아직 없으므로, 실사용 작업본·proposal owner-scoped 저장/다운로드 경로는 미구현 상태다. 서비스-level fixture API 외부 노출, DB BLOB 저장, HTTP download를 완료로 주장하지 않는다. 임의 payload나 실제 작업본은 이 새 workflow에서 사용할 수 없다.
- 집중 회귀 13개 클래스 **69/69 PASS**, failures/errors/skips 0. 기존 PIPE_CHANGE, solver, material balance, ADD/MODIFY/DELETE 관련 regression과 flow workflow를 포함한다. `./mvnw -q -DskipTests test-compile`도 통과했다.
- `PROPOSAL_TEST_DB_URL`, `PROPOSAL_TEST_DB_USER`, `PROPOSAL_TEST_DB_PASSWORD` 미설정으로 DB integration/API persistence 미실행. Browser E2E 미실행. 공식 실제 edge↔label binding은 모두 `REVIEW_REQUIRED`; 실제 물리 연결 증빙 없음, readiness `CALCULATION_UNAVAILABLE`, 실공정 FLOW_CHANGE/유량 편집은 계속 차단.
- 다음 안전한 과제: isolated DB가 준비된 뒤 FlowLabelBinding을 server profile/catalog와 실제 working-copy SHA/owner에 연결하고, controller preview→generate→user-scoped download와 API/브라우저 검증을 별도 승인·테스트한다. 공식 물질수지/실공정 readiness 승격은 여전히 별도 evidence/approval 전까지 금지한다.

---

## STEP 8.5 — STEP 8.6 매핑 검토 웹 기능 및 보고서 (2026-11-03)

### 검토 기능/API
- 로그인 세션 기반 `/api/workspace/flow-mapping-review/overview`, `/candidates`, `/equipment-occurrences`, `/equipment-occurrences/batch` 및 읽기 전용 XLSM/XLSX 분석 `POST /material-balance-comparison`을 제공한다. 업로드는 CSRF 보호, 15MB 제한, 사용자/session 범위의 5분 임시 캐시이며 원본 업로드 bytes와 분석 결과를 DB에 저장하지 않는다.
- 분석 행은 `GET /api/workspace/flow-mapping-review/material-balance-comparison/{analysisId}/rows`에서 서버 검색·품질 필터·pagination으로 조회한다. 전체 검토 XLSX/CSV/JSON은 `GET /api/workspace/flow-mapping-review/material-balance-comparison/{analysisId}/export?format=xlsx|csv|json`으로 받으며 세션 소유권을 확인하고 SHA-256 응답 헤더를 제공한다. UI는 다운로드 bytes와 헤더 SHA를 확인한다.
- 검색/필터/페이지 변경 중 이전 응답이 뒤늦게 도착해 현 화면을 덮지 않도록 요청 ID를 확인하고, 실패 시 이전 결과를 지운다. API/브라우저 파일 다운로드 실사용은 별도 환경 검증이 필요하다.
- 원본 파일 식별은 `source SHA-256 + source row`를 사용한다. 도면 ID/셀 위치/Connector anchor는 후보 근거일 뿐 물리 연결로 확정하지 않는다. 검토 그룹은 서로 겹칠 수 있으며 group count를 고유 전체 행으로 합산하지 않는다.

### 실제 XLSM 결과와 검토 산출물
- 읽기 전용 원본 `0.3 250808 물질수지 DB.xlsm` SHA-256: `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`; 입력 worksheet `입력시트`, 3,082행.
- 양 endpoint 문자열 후보 2,672행, 한쪽 334행, 후보 없음 76행, 복수 위치 후보 2,362행. 중복 endpoint 44그룹/95행, 물질 종류 누락 2,830행, 단위/기간 누락 각 3,082행. quality 그룹들은 중복 집계될 수 있다.
- 실제 숫자 셀 저장값과 Excel 표시 문자열을 따로 보존한다. 이전 crosswalk 대비 수치 차이 0행, 표시형식 차이 214행. 표시 반올림을 원본 수치 수정으로 해석하지 않는다. 공란, NaN/Infinity 및 잘못된 숫자는 상태를 분리한다.
- 9개 시트 검토 XLSX: `validation-results/2026-11-03-step8-5/run-07/flow-mapping-review-actual.xlsx`; SHA-256 `60ddeed7056f8c5ccf221faa856c57e4e752ed31c1e0d6530f7dfad7ed43802c`. POI 재열기, ZIP integrity, 9시트, formula cells 0, 맑은 고딕/검정, 원본 행 추적을 통과했다. 검증 JSON과 비교 보고서도 같은 run의 `SHA256SUMS`에 있으며 manifest 확인 PASS.
- 이전 checkpoint의 62개 시트 및 공식 공정도 15개 파일 SHA와의 일치는 확인했으나 이번 검증에서 DB fingerprint는 재계산하지 않았다: `CHECKPOINT_CONTENT_SHA_MATCHED_DB_FINGERPRINT_UNVERIFIED`.

### 테스트·안전 한계
- 관련 regression 21개 Surefire 클래스, 81/81 PASS, failures 0, errors 0, skipped 0 (2026-11-03 실행). 이는 선택한 관련 테스트의 결과이며 전체 Maven suite 결과가 아니다.
- DB 의존 테스트 및 process_map_dev 접근은 미실행. Browser E2E 및 실제 Spring+DB HTTP download도 미검증. API MockMvc, 정적 JavaScript 검사 및 POI/XLSX 파일 검증과 구분한다.
- 실제 Connector endpoint binding 0건, VERIFIED 유량 매핑 0건, 물리 연결 `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`, 실제 readiness `CALCULATION_UNAVAILABLE`. STEP 8.2 `VISUAL_REVIEW_REQUIRED` 유지. 실제 공정 FLOW_CHANGE 생성/승인이나 공식 원본 수정은 하지 않았다.
- 다음: 격리 DB와 브라우저 환경이 제공된 경우에만 저장/API 실사용 경로를 별도로 확인한다. DB fingerprint, 실제 물리 연결 증거, 단위·기간은 추정하지 않는다.
- 운영 배포 없음; 현재 개발 브랜치 `step7-7-wip-profile-add-20261101`에서만 변경했다.

---

## STEP 8.6 — 검토 우선순위 대시보드 및 필터 다운로드 (2026-11-03)

- 검토 카드들은 `reviewSummary` API 집계와 연결된다: 원본 전체 입력, 양/한쪽/미일치 endpoint 후보, 복수 위치, 중복 endpoint 그룹, 물질·단위·기간 누락, Connector binding 필요, 실제 VERIFIED 유량. 품질 상태 수는 서로 겹칠 수 있으며 합계로 전체 입력 수를 대신하지 않는다. 후보 인벤토리가 현재 hash-matched가 아니면 후보 기반 카운트/버튼을 비활성화하고 서버도 해당 필터를 거부한다.
- 카드, 검색 및 품질 상태 filter는 server-side pagination을 사용한다. 필터·검색은 첫 페이지부터 다시 조회하며 빠르게 바뀐 이전 응답은 무시하고 오류 때 오래된 행/카드를 지운다.
- `GET /api/workspace/flow-mapping-review/material-balance-comparison/{analysisId}/export?format=xlsx|csv|json&statusFilter=...&q=...`는 현재 페이지가 아니라 선택 필터/검색의 **전체 일치 행**을 반환한다. 세션 소유권 확인과 필터 enum·행수·출력 크기 제한을 적용하고 `X-Review-Only`, `X-Official-Baseline-Modified`, `X-Review-SHA256`, `X-Review-Exported-Rows`, `X-Review-Status-Filter`를 돌려준다. UI는 다운로드 직전/직후 분석 ID와 필터 경쟁 상태 및 실제 응답 bytes SHA를 다시 확인한다.
- XLSX는 전체 원본 행 수, 선택 필터, 검색어, 내보낸 행 수를 따로 기록하며 필터 출력 worksheet에서 원본 sheet/row/SHA를 보존한다. CSV formula injection은 기존 quote/escape 보호를 유지하고, XLSX 문자열은 문자열 셀로 쓴다.
- 실제 원본 및 행 통계는 기존 STEP 8.5 read-only 결과를 그대로 사용한다. 기존 `run-08/flow-mapping-review-actual.xlsx`는 기록된 SHA-256 `5e2444a9df8abc9ccf4e53e8c9afb93e35284ef37a6b374a92564441082b89f3`와 실제 파일이 일치하며 덮어쓰지 않았다. 신규 실제 원본 재검증 산출물은 `validation-results/2026-11-03-step8-5/run-11/flow-mapping-review-actual.xlsx`, SHA-256 `d327c69cd4fa78d0c06485e28d80d62b82754bff0b7da14ba7efdc6994865488`; 비교 XLSX SHA-256 `3a6a7d464d8bd24f15a641afdb0f629509f6d41ad9243f23222fef5d89978890`. run-11 manifest 세 파일 모두 검증 PASS. XLSX ZIP CRC, POI 재열기, 9 sheets, 3,082 input rows, source hash/row 추적, formula cells 0, 맑은 고딕/검정 검증 통과.
- 이번 최종 UI/CSV parity 수정 이후 선택 회귀 21개 클래스, **76 tests, failures 0, errors 0, skipped 2**. 생략 2건은 별도 실제 파일 경로를 지정해야 하는 actual-data test 메서드로, 같은 STEP 8.6 검증에서 실제 파일 경로를 지정해 5/5 PASS로 별도 실행했다. 전체 Maven suite 및 DB-backed tests는 미실행. Browser E2E/실제 브라우저 download 미검증; API 계약은 MockMvc 및 exporter/filter workflow tests로만 확인했다.
- 필터 parity 테스트는 동일 필터·검색에 대해 API 전체 필터 결과 2행과 CSV 레코드 2행, JSON rows/exportedRows 2행, XLSX data rows 2행 및 export rowCount 2를 비교한다. 페이지 크기가 1이어도 export는 현재 페이지가 아닌 전체 일치 결과를 출력한다. UI 코드 검사는 inline JavaScript 1개 `node --check`, literal DOM 참조 123개/누락 0개이며 브라우저 E2E를 뜻하지 않는다.
- 검토 기능은 후보 찾기와 자료 수출을 돕는 것이며 실제 물리 연결 확인이 아니다. VERIFIED flow mapping 0, endpoint Connector binding 0, readiness `CALCULATION_UNAVAILABLE`, STEP 8.2 `VISUAL_REVIEW_REQUIRED`; 원본 XLSM, 공식 공정도, `process_map_dev`, migration 및 기존 PM2는 변경하지 않았다.

---

## STEP 8.7 — 담당자 검토 초안 CSV/XLSX 왕복 검증 (2026-11-03)

### 기능 및 경로
- `/`의 `연결·유량 매핑 검토` 카드 안에서 STEP 8.7 검토 입력 XLSX/CSV를 받고, 검토 파일을 업로드해 검증 Preview를 확인한 후 CSV/JSON/XLSX로 재출력할 수 있다.
- `GET /api/workspace/flow-mapping-review/material-balance-comparison/{analysisId}/draft-template?format=xlsx|csv`는 검토 양식 다운로드, `POST .../{analysisId}/draft-import`는 인증·CSRF·5MB 제한을 적용하는 multipart import, `GET .../{analysisId}/draft-preview/{previewId}`는 Preview 조회, `GET .../{analysisId}/draft-preview/{previewId}/export?format=xlsx|csv|json`은 검토 결과 재출력이다.
- 식별 계약은 원본 XLSM SHA-256 + 시트 + 행 번호 및 원본 설비 ID·유량·물질·단위·기간·후보 snapshot이다. 원본 필드와 담당자 입력 필드를 따로 유지한다. 충돌(원본 SHA/행/설비 ID), 중복행, 잘못된 상태/수치 및 formula-like 입력은 구분해 반환한다.
- 초안은 사용자·세션·analysis ID에 결합된 제한된 5분 메모리 cache에만 존재하며 로그아웃 시 해당 세션 cache를 삭제한다. DB 저장/공식 원본 수정은 하지 않는다. 검토 완료 상태는 실제 물리 연결 승인 또는 VERIFIED 승격이 아니다.

### 검증 및 시연 산출물
- XLSX 및 CSV 각각 양식→합성 의견 입력→import→재출력→재import 왕복을 실제 합성 파일로 실행했다. 쉼표/따옴표/개행 CSV, XLSX 수식 셀 차단, CSV formula-like 입력 차단, SHA·행·설비 ID 충돌, 중복, 상태/유량 오류, 다른 사용자 접근, 세션 종료 정리, source bytes 불변 assertion을 검사했다.
- 집중 회귀 22개 Surefire classes, **87 tests, failures 0, errors 0, skipped 0**. 선택한 테스트는 API mapping/controller, STEP 8.7 workflow/service, ADD/MODIFY/DELETE/Profile, PIPE_CHANGE/FLOW_CHANGE, NetworkWhatIf/solver/material balance 및 inventory 관련 unit tests다. 전체 Maven suite와 DB 의존 integration tests는 미실행이다.
- 정적 웹 점검: 기존 `index.html` inline JavaScript `node --check` 성공, literal DOM 참조 131개/누락 0, STEP 8.7 필수 DOM control 확인. MockMvc로 anonymous/CSRF/preview/export header·SHA 계약 확인. 실제 Spring+DB runtime 및 Browser E2E는 수행하지 않았다.
- 합성 왕복 파일과 STEP 8.6 실데이터 시연 산출물: `validation-results/2026-11-03-step8-7/run-final-071742-6658/`. `demo.html`은 정적 read-only 시연 페이지, `demo-screen.png`는 SVG 렌더 미리보기이며 browser screenshot이 아니다. 이 run의 `DEMO_README.md`와 `SHA256SUMS`에서 실행 방법/각 파일 SHA/검증 한계를 확인한다.
- 실제 자료 사본은 기존 검증 `run-11`의 9시트 XLSX 및 비교 XLSX다. 원본은 SHA-256 `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`, 입력 3,082행. 공정도 15개·62시트 및 후보 통계는 저장된 STEP 8.6 checkpoint를 재사용했으며 이번에는 DB fingerprint를 재계산하지 않았다.

### 안전 상태 및 한계
- 합성 Preview/재출력은 `persisted=false`, 실제 VERIFIED flow mapping 0, physical connection `NOT_VERIFIED_NO_PHYSICAL_CONNECTION_EVIDENCE`, readiness `CALCULATION_UNAVAILABLE`을 유지한다. 실제 검토 자료에 합성 의견을 쓰지 않았다.
- 공식 원본 15개와 원본 물질수지 XLSM, `process_map_dev`, 운영 DB/migration, 기존 PM2, `main`을 변경/접근하지 않았다. 실제 물리 연결·유량을 추정하지 않았으며 STEP 8.2 `VISUAL_REVIEW_REQUIRED`도 유지한다.
- 현재 repository는 Java 17 + Spring Boot + MariaDB다. Hono/Cloudflare Pages preview/deploy stack과 호환되지 않으므로 Cloudflare 배포 URL은 만들지 않았다.
- 다음 단계는 격리 DB 및 브라우저 환경에서만 Spring API 로그인·실업로드·실다운로드 E2E를 확인하는 것이다. DB가 준비되기 전 운영 서버/PM2를 재사용하거나 우회하지 않는다.

---

## PILOT-01 — 실제 통합공정도 TEST_ONLY MODIFY 검증 (2026-11-04)

- 실제 공식 Excel 복사본 두 공정에서 기존 `DiagramBoxChangeEngine.modifyBoxFieldsTestOnly`를 검증했다. P-02 `P-02-01(TOP)` / `I-PP21005` / `I89`: `131 MULTI SORTER#1` → `SYNTHETIC_TEST_ONLY 131 MULTI SORTER#1`, After SHA-256 `9e3a4a5087567656bc3a938c69ca30c0ea902b46fd13d6166846a848d867fb50`. P-01 `P-01-01(TOP)` / `I-PP11001` / `J7`: `40㎥ PULPER (TOP, IV)` → `SYNTHETIC_TEST_ONLY 40㎥ PULPER (TOP, IV)`, After SHA-256 `f0f19a674b809d6ef5ba34e72fe1fd4d7e4afbc4ea2d1eea663f3dab83a5145f`. 두 값 모두 합성 텍스트이고 실제 공정 변경이 아니다.
- P-02 공식 원본 SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`; P-01 `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`. 공식 15개 체크섬은 모두 OK. 원본을 수정하지 않았다.
- P-02는 POI 재열기/ZIP 검사 통과. 변경은 대상 worksheet XML의 I89뿐이며 나머지 worksheet 내용, 병합 333개, 스타일, 행·열, 인쇄 설정 및 DrawingML 패키지 파트 바이트가 동일하다. 대상 DrawingML은 266 Connector와 19 Shape. P-01의 다른 17행 박스도 J7만 달라졌고 DrawingML 56 Connector, 병합·스타일·인쇄 설정·행/열을 보존했다.
- PDF/PNG 검수물: `validation-results/pilot-01-20261104/final-run-20261104-r1/`의 P-02 전체/상세 비교 이미지; `.../final-run-20261104-r3-p01/`의 P-01 비교 이미지. Before/After PDF는 P-02 각 71페이지, P-01 각 32페이지(A3). 대상 페이지의 동일 셀 영역을 비교했으나 모든 PDF 페이지 간 시각 검수를 완료했다는 뜻은 아니다.
- 선택 회귀 11개 클래스, 43 tests, 실패 0/오류 0/skip 0; P-01 보강 테스트 재실행 1/1 PASS. ADD는 P-02 Profile의 검증 working SHA 불일치로 차단, DELETE는 주변 DrawingML 3건 및 인접 텍스트 3건으로 차단했다. 출력본을 만들지 않았다.
- 실제 사용자 작업본의 DB/API Preview→Generate→Download는 미검증이다. DB integration은 `DB_URL`/`PROPOSAL_TEST_DB_URL` 등 격리 DB 접속이 필요하므로 `process_map_dev` 금지 조건에 따라 실행하지 않았다. VERIFIED 유량 매핑 0, readiness `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. 상세 절차와 JSON/해시는 `validation-results/pilot-01-20261104/PILOT_README.md`, `pilot-selection.json`, `SHA256SUMS`를 참조한다.

## PILOT-02 — 실제 MODIFY 결과 브라우저 시연 및 API 계약 검토 (2026-11-04)

- 기존 DEMO-03 및 PILOT-01은 보존하고 `validation-results/pilot-02-20261104/`에 별도 로컬 전용 시연 패키지를 구성했습니다. P-02/P-01을 선택하면 셀/박스/Before·TEST_ONLY 값/Before·After PNG와 상세 확대, Excel 다운로드를 확인할 수 있습니다. 브라우저는 사전 생성된 TEST_ONLY Excel만 보여주며 실시간 Generate 하지 않습니다.
- Chromium 156에서 P-02/P-01 전환, 확대 이미지, Before/After Excel 4개 다운로드 SHA, DEMO-03의 물질수지 검토 XLSX 다운로드 SHA, I-PP11001 검색(8행)·필터·초기화(3,082행)를 검증했습니다. loopback only, 외부 요청 0, console/page error 0.
- P-02 Profile 필수 working SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`와 PILOT 공식 복사본 SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76` 불일치를 확인했습니다. P-01 Profile은 `REVIEW_REQUIRED`; 승격·guard 우회는 하지 않았습니다. 별도 preview fingerprint 필드는 없고 세션 preview + working SHA/mapping/expected cell 재검증이 stale guard입니다. MODIFY 경로는 현재 `ChangeProposalService` create/record를 호출하지 않습니다.
- DB 비의존 선택 회귀 10 classes / 37 tests PASS. 격리 DB가 없고 `process_map_dev` 사용이 금지되어 Spring+DB Preview→Generate→Download와 DB 저장/HTTP body 검증은 실행하지 않았습니다. 실제 VERIFIED 매핑 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지. 세부는 `validation-results/pilot-02-20261104/PILOT_README.md`, `MODIFY_API_REVIEW.md`, `TEST_RESULTS.json`을 참조합니다.

## PILOT-03 — 실제 도면 복사본의 입력값 기반 TEST_ONLY MODIFY (2026-11-04)

- 기존 `DiagramBoxChangeEngine.modifyBoxFieldsTestOnly`를 호출하는 테스트 범위 Preview→Generate→Download 경로를 `src/test/java/.../Pilot03DynamicModifyIntegrationTest.java`에 추가했습니다. 테스트 Controller/Workflow는 운영 앱에 등록되지 않으며 입력으로는 P-01/P-02 공정 선택과 시험 문자열만 받습니다. 시트/설비/셀/파일 경로는 서버 allowlist에 고정되어 있습니다.
- 실제 PILOT-02 고정 Before 사본의 SHA와 셀 값을 Preview 및 Generate에서 재확인했습니다. P-02 `P-02-01(TOP)` / `I-PP21005` / `I89`에서 서로 다른 `SYNTHETIC_TEST_ONLY PILOT03 P02 A/B` 입력 2개를, P-01 `P-01-01(TOP)` / `I-PP11001` / `J7`에서 `SYNTHETIC_TEST_ONLY PILOT03 P01 A/B` 2개를 각각 새 XLSX로 생성했습니다. 별도 세션 격리 입력 1개를 포함해 신규 출력 5개입니다. 파일별 정확 입력값과 SHA-256은 `validation-results/pilot-03-20261104/TEST_RESULTS.json` 및 `SHA256SUMS`를 참조합니다.
- 각 output의 POI 셀 값·스타일·merge·행/열·print 설정을 다시 확인했습니다. ZIP/POI 정상, 대상 셀 외 worksheet 내용 동일, 허용된 OOXML 변경 파트는 `xl/worksheets/sheet1.xml`뿐이고 DrawingML 파트 byte-identical, Shape/Connector inventory 보존. Preview fingerprint, source/current-cell stale guard, no-op/입력 검증, Preview 위조 차단, owner/session 소유권, 로그인·CSRF, 기본 비활성 test controller, 다운로드 body와 SHA 헤더 일치를 검증했습니다. 입력 파일·공식 원본은 덮어쓰지 않았습니다.
- 최종 선별 회귀 11 Surefire 클래스 **42 tests, failures/errors/skips 0**; `test-compile` 및 BUILD SUCCESS. P-01/P-02 실제 도면 복사본 편집, 기존 Profile MODIFY/ADD, legacy ADD, BOX_ONLY DELETE 차단, synthetic PIPE_CHANGE/FLOW_CHANGE 보안 회귀를 포함합니다. 공식 원본 manifest 16/16 OK.
- 이 결과는 DB 비의존 standalone MockMvc + 실제 Excel 편집 엔진 검증입니다. Spring Boot 서버+격리 DB, 실제 운영 API·변경제안 이력 저장, 웹 UI/Chromium E2E는 실행하지 않았고 구현 범위에도 포함하지 않았습니다. `process_map_dev` 접속, migration, PM2, `main`, 배포 변경은 없습니다. 기존 Profile working-copy SHA guard를 우회/승격하지 않았으며 `USER_WORKING_COPIES_ENABLED` 기본 OFF 유지.
- 운영 MODIFY와 `ChangeProposalService` append-only 변경제안 경로는 자동 연결되지 않습니다. 운영 연계 시 사용자/대상/source·working SHA/Preview fingerprint/전후값/출력 SHA를 제안 snapshot과 출력 소유권·감사 이력에 결합하는 별도 설계와 격리 DB 검증이 필요합니다. 실제 VERIFIED 유량 매핑 0, readiness `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 자세한 범위·제한은 `validation-results/pilot-03-20261104/PILOT_README.md` 및 `TEST_RESULTS.json`을 참조합니다. 결과 XLSX와 JSON/문서는 private gitignored validation output으로 보관하며 Git에 포함하지 않습니다.

## PILOT-04 — P-01/P-02 실제 HTTP·Chromium A/B 및 Excel 보존 (2026-11-04)

- 기존 24개 HTTP 보안/세션 시나리오를 보존하고, 새 run에서 TEST_ONLY loopback HTTP Preview→Generate→Download를 수행했습니다. P-02/I89의 `SYNTHETIC_TEST_ONLY PILOT04 P02 A/B`, P-01/J7의 `SYNTHETIC_TEST_ONLY PILOT04 P01 A/B` 총 4개를 실제 `DiagramBoxChangeEngine`으로 생성했습니다. 결과는 `validation-results/pilot-04-20261104/http-ab-20261104T082949Z/HTTP_AB_RESULTS.json`에 기록되어 있습니다. 생성 SHA와 HTTP 다운로드 bytes SHA는 각각 P-02 A `9a80697b170245dc428f125525f4092bb276a5f17bb4a281fd3de8cd74aaa3ba`, P-02 B `0153171112867b4cb3694bb48db145442816e7b404997757da255856f47fd1a6`, P-01 A `be1ac586dc7af3d11cad26974e9d5c71cc869cfd2d34201c567d9567c50e0873`, P-01 B `229c2abbe2ce3158895de0cc13295986cd5e6694eb202a8056d2ca5e3e0e2e95`입니다.
- Chromium 156 UI에서 로그인→입력→Preview→Generate→실제 다운로드를 4/4 PASS로 재검증했습니다. Chromium/서버가 같은 network namespace임을 확인했고 dynamic port 33579에 직접 연결, same-origin 요청과 기존 세션/CSRF 검증을 유지했습니다. 이전에 기록된 35069는 stale port였습니다. 자동 favicon 404를 test-only HTML의 빈 data favicon으로 제거한 뒤 console/page error 0. 보고서/화면 캡처는 `validation-results/pilot-04-20261104/CHROMIUM_RESULTS-20261104T084003Z.json` 및 `browser-captures/20261104T084003Z/`에 있습니다.
- 새 4개 HTTP 파일과 Chromium 다운로드를 각각 검증했습니다. ZIP 및 Apache POI 재열기, 실제 셀 값, 대상 셀 외 worksheet XML 불변, merge/style/row/column/print 설정, DrawingML byte-identical, Shape/Connector 보존 및 source SHA 불변 PASS. 결과 상세는 `OOXML_PRESERVATION.json`; 해시 매니페스트는 `SHA256SUMS`입니다. 새 결과와 기존 P-02 HTTP artifact(sha `225629856cd52e2fbfe5b663dc69b742ce45cf7ef738857d6725072ebda2eb4b`)를 구분했습니다.
- 선택 DB 비의존 Surefire 16 classes / 54 tests, failures 0 / errors 0 / skipped 0. 전체 Maven suite 및 Spring+DB 통합은 실행하지 않았습니다. test-scope 격리 서버는 종료했고 일회성 credential 파일은 삭제했습니다. 공식 원본 15개와 전체 목록 체크섬 16/16 PASS, `process_map_dev`/운영 DB 미접속. `USER_WORKING_COPIES_ENABLED` 기본 OFF, ADD/DELETE 차단, VERIFIED flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 운영 Spring+DB API/변경제안 DB 저장은 미검증입니다. test-only controller는 `src/test/java`에만 있고 프로덕션 artifact에 추가되지 않습니다. 상세 범위, Surefire별 수치, Chromium 진단은 `validation-results/pilot-04-20261104/PILOT_README.md` 및 `TEST_RESULTS.json`을 참조하세요. 결과 파일과 캡처는 Git에 포함하지 않습니다.

## PILOT-05 — 사용자 작업본 Profile MODIFY 최종 검증 (2026-11-05)

- 최신 선택 회귀는 13개 Surefire 클래스, **48 tests / failures 0 / errors 0 / skipped 0**이다. CSV와 Surefire XML을 대조해 집계했다. 전체 Maven suite는 실행하지 않았다.
- 합성 Profile/workbook 전용 격리 DB 흐름은 Preview→proposal→TEST_ONLY output/history→소유자 다운로드와 비소유자 차단·rollback을 통과했다. 이는 합성 fixture 결과이며 실제 P-02 MODIFY 성공을 뜻하지 않는다.
- 실제 P-02 source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, working-copy SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`를 확인했다. I89는 style ID 262 대 Profile 기대 220으로 내부 매핑 `PROFILE_STYLE_MISMATCH`이며, 외부 Preview 응답은 HTTP 400 `PROFILE_MODIFY_FIELD_SET_INVALID`이다. I584는 Connector ID 109의 DrawingML 겹침으로 mapping reason `DRAWINGML_FIELD_OVERLAP`, 외부 응답 HTTP 400 `PROFILE_FIELD_NOT_EDITABLE`이다. 두 건 모두 proposal/output 0이며 guard를 완화하지 않았다.
- 실제 P-02 Preview 성공/Generate/Download, 운영 Spring+DB 경로, 브라우저 E2E 및 배포는 미검증이다. 격리 테스트 DB는 검증용 임시 환경에 한정되며, 이번 문서 정리에서는 DB 연결이나 테스트 재실행을 하지 않았다.
- 공식 공정도 15개와 물질수지 XLSM 원본, `process_map_dev`, 운영 DB, PM2, migration, `main`, 배포는 변경하지 않았다. 실제 VERIFIED 유량 매핑 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`를 유지한다.
- 다음 재개는 정확한 source/working SHA에 맞는 사용자 작업본 및 field/DrawingML 근거의 별도 검토부터다. 기존 Profile SHA·값·권한·세션·CSRF·DrawingML guard를 우회하거나 완화하지 않는다. 상세: `validation-results/pilot-05-20261105/PILOT_README.md`, `P02_PREVIEW_400_DIAGNOSIS.md`.

---

## PILOT-07 — Profile 후보 사전검증·선택 충돌 최종 회귀 (2026-11-06)

- ProfileCandidatePreflight는 source/working SHA, identity, 셀/박스, 스타일·행 높이·병합, DrawingML anchor를 byte-array 기반으로 읽기 전용 검사한다. 운영 Spring Bean·Controller·기존 Profile resolver에 자동 연결되지 않았으며 `modifyAuthorized=false`와 후보 `REVIEW_REQUIRED`를 유지한다. 실제 연결/유량을 추정하지 않는다.
- P-02 source/working SHA는 각각 `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`. I89 값·SHA 구조 일치로 `STRUCTURE_CHECK_PASS`이지만 실제 style 262 vs Profile 220, 행 높이 12.0pt vs 11.1pt, 병합 구조 미해결로 결정은 `BLOCKED`; I584 Connector 109 직접 겹침 및 Connector 19 박스 경계 검토가 남아 MODIFY 차단이다. P-02 실제 MODIFY 성공으로 해석하지 않는다.
- P-01은 별도 source SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`, working SHA `a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb`, 17행 `J4:AA20` 구조를 별도로 검사했다. DrawingML 간섭 검토 전 `REVIEW_REQUIRED` 및 차단 상태다.
- Profile 선택 충돌 회귀 5/5 PASS: 동일 SHA의 review 후보가 있으면 VERIFIED를 explicit 선택해도 충돌로 차단하고 review-only 후보 자동 선택을 금지한다. 최종 선별 회귀는 16 Surefire classes / **57 tests, failures 0, errors 0, skipped 0**; test-compile PASS. 전체 Maven suite는 실행하지 않았다.
- 기본 DB가 `process_map_dev`이므로 DB-backed proposal/output history는 미실행하고 해당 DB/운영 DB에 접속하지 않았다. Mock 기반 DB 비의존 경계 테스트 4건만 통과했으며 이는 DB 영속 이력의 검증이 아니다. 실제 Spring Preview 통합/운영 Preview·Generate·Download, 후보 승인·등록, 실제 MODIFY는 미검증이다.
- 공식 reference manifest 16/16 PASS. 공식 Excel 15개, 사용자 작업본, 물질수지 XLSM, 운영 DB, PM2, migration, `main`, 배포 변경 없음. 실제 VERIFIED 유량 매핑 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 상세 결과 및 SHA는 private validation run `validation-results/pilot-07-20261106/final-run-003813/`의 `PILOT_README.md`, `PROFILE_SELECTION_RULES.md`, `TEST_RESULTS.json`, `CHECKPOINT_STATUS.md`, `SHA256SUMS`를 참고한다. 결과물은 Git에 포함하지 않는다.

---

## PILOT-08 — 기존 Spring Boot 앱의 읽기 전용 Profile 사전검증 (2026-11-06)

- 기존 `ProfileCandidatePreflight` 및 `ProfileSelectionPreflight`를 재사용하는 `ProfilePreflightService`와 GET-only `/api/workspace/diagram-editor/profile-preflight` Controller를 추가했다. 호출 입력은 사용자 작업본 ID, 공정, 시트, 관리번호이며 로그인 세션에서 username을 얻는다. 기존 resolver, ADD/MODIFY/DELETE 실행 서비스는 변경하지 않았다.
- 서비스는 `UserWorkingCopyService.getForUser(id, username)`의 owner-scoped 로더만 사용하고 `USER_WORKING_COPY` 유형을 다시 확인한다. 잠긴 V1.0 PROCESS 원본, 사용자 작업본, 후보 snapshot의 SHA 및 identity를 검증한 뒤 필드별 값/style/행 높이, 병합, DrawingML, 충돌·차단·승인 필요 사유를 DTO로 반환한다. 원본 bytes와 절대 경로는 응답하지 않고 GET 응답은 `Cache-Control: no-store`다. Preview/Generate/POST 핸들러는 없다.
- 기능은 기본 OFF: `PROFILE_PREFLIGHT_ENABLED=false`, `USER_WORKING_COPIES_ENABLED=false`. Service와 Controller 모두 두 flag가 true일 때만 등록된다. 세션 인증 검사는 standalone MockMvc에서 확인했으나 실제 Spring+DB 세션·소유권 통합은 실행하지 않았다. DB 비의존 mock service 테스트만으로 owner loader 호출을 검증했다. 기존 작업본 상세 UI 연결은 미추가.
- 실제 P-02 `P-02-01(TOP) / I-PP21005`: source SHA `28716f1f26433d825e2e3220b9623a7d102b4454b45f4f106ab175a2348d9f76`, 사용자 작업본 SHA `c3379f2eca9d235af994045be02bb1cae650e22baf75cb8886456848c881a3d2`; 세 SHA 검증 및 I89 구조 검사는 통과했지만 실제 Profile style 262 vs 기대 220, 행 높이 12.0 vs 11.1pt, Profile template 대비 병합 차이로 차단. Connector 19 박스 경계 검토와 I584/Connector 109 reference-field 간섭, Profile 선택 충돌이 반환됐다. 최종 `decisionStatus=BLOCKED`, `modifyAuthorized=false`.
- 실제 P-01 `P-01-01(TOP) / I-PP11001`: source SHA `426cbd28de4a66a2201f1cffd8f4aff87cca1b37c3daab0be10be9aa09faf391`, 작업본 SHA `a2ab51944a4c2d0d77cc91e675c6e36199c426bbf87e8a1f598cbb85986907bb`; SHA 확인 후에도 후보/기존 Profile REVIEW_REQUIRED, DrawingML 겹침·경계 및 reference-field 검토, 선택 불가로 차단. `modifyAuthorized=false` 유지.
- Profile/선택/실제 P-01·P-02/기존 Profile MODIFY·ADD·BOX_ONLY DELETE/OOXML/PIPE_CHANGE·FLOW_CHANGE 관련 **18 classes, 65 tests PASS**, failures/errors/skips 0. 결과는 Surefire XML 합산. 전체 Maven suite와 실제 Spring+DB 통합은 실행하지 않았다. P-01/P-02 입력 파일 SHA는 테스트 assertion 및 종료 후 fingerprint에서 기준값과 일치.
- 상세 JSON/log/checksum은 무시 경로 `validation-results/pilot-08-20261106/final-run-014000/`에 보존하며 Git에는 포함하지 않는다. 운영 DB 및 `process_map_dev` 접속, PM2, migration, `main`, 배포, 원본 Excel 수정은 없었다. 실제 MODIFY 전 독립 DrawingML/병합/style 검토, 명시적 후보 검토·승인, 격리 DB 통합시험, UI/API 통합 결정이 남아 있다.

### PILOT-08 최종 재검증 — 2026-11-06
- 최신 경로 수정 포함 코드 기준: `./mvnw -q -DskipTests test-compile` PASS, UTF-8 locale/JVM encoding의 Surefire **18 classes / 65 pass / 0 failures / 0 errors / 0 skipped**. 전체 Maven suite 및 Spring+DB 통합은 미실행.
- 경로는 P-01/P-02별 독립된 system property key와 `/home/user/webapp` 기준 상대경로 fallback을 사용. 실행 전 네 파일 존재 및 SHA를 확인하고, opt-in 테스트 내부에서도 literal SHA assertion을 통과. P-01/P-02 source/workbook hashes는 테스트 후에도 기준값 일치.
- CHECKPOINT 1 당시 실제 Git 검사에서 branch `step7-7-wip-profile-add-20261101`, HEAD `5bb99d94f94bcd673e8b0153fb2b5df05630eccb`, clean tree였다. 구현 9개 파일은 이미 `genspark auto-backup`에 포함되어 중복 코드 커밋은 만들지 않았다. README/CHECKPOINT 최종 재검증 기록은 별도 문서 커밋에만 반영했고, 구현은 변경하지 않았다. 최종 run `validation-results/pilot-08-20261106/final-run-020000/`에는 TEST_RESULTS.json, CHECKPOINT_01~04, 입력 SHA, Surefire XML 및 SHA256SUMS가 있다. 모든 `validation-results/` 산출물은 Git 제외.
- 인증/소유권은 standalone MockMvc 및 Service mock 수준의 검증이며 실제 DB 소유권 성공을 의미하지 않는다. 기본 flags OFF; UI 미구현; 미승인 P-01/P-02 BLOCKED 및 `modifyAuthorized=false` 유지.

---

## PILOT-09A — 최신 Spring MVC 인증·소유권 테스트 마감 (2026-11-06)

- 마지막 MVC 테스트 수정은 테스트 범위에 한정됐다. `mallory` 계정으로 foreign-owner 사례를 실행할 때 MVC의 `OperatorLoginService`도 `mallory`로 구성해 로그인한 identity와 owner lookup username이 일치하도록 맞췄다. 운영 인증·소유권 코드, Profile resolver 및 기능 플래그 기본값은 변경하지 않았다.
- 최신 컴파일 `./mvnw -q -DskipTests test-compile` PASS. 최신 `OperatorProfilePreflightMvcIntegrationTest` 단독 실행은 **7/7 PASS**; 전체 선택 회귀는 Surefire XML 19개를 새 실행에서 복사·집계해 **19 classes / 72 tests / failures 0 / errors 0 / skipped 0** PASS. Profile preflight/selector, 실제 SHA-pinned P-01/P-02, MODIFY/ADD/BOX_ONLY DELETE gate, OOXML, synthetic PIPE_CHANGE/FLOW_CHANGE를 포함한다. 전체 Maven suite는 실행하지 않았다.
- 테스트는 실제 `POST /api/session/login`과 기존 `OperatorLoginService`/`HttpSession`(`operator.username`) 경로를 사용했다. client `username` query는 identity를 바꾸지 않으며, unauthenticated GET 401, owner mismatch 404, malformed ID 400, unknown ID 404, POST 405, no-store, flags OFF/ON, SHA mismatch 및 REVIEW_REQUIRED 차단을 확인했다. Foreign-owner 케이스는 실제 `ProfilePreflightService`에 `UserWorkingCopyService.getForUser(id, "mallory")`의 404 mock을 연결했고 source repository read가 호출되지 않음을 검증했다.
- **인증 검증 한계:** 프로젝트에 Spring Security dependency 및 `SecurityFilterChain`이 없다. 실제 Spring Security filter-chain/principal 테스트를 했다고 주장하지 않으며, 존재하지 않는 chain을 비활성화하거나 우회하지 않았다. DB 연결 없이 mock loader를 사용했으므로 database-backed 소유권 테스트도 미실행이다.
- P-01/P-02 실제 source/working SHA 검증 결과는 `REVIEW_REQUIRED`/`BLOCKED`, `modifyAuthorized=false`로 유지된다. 공식 reference manifest 16/16(P-01~PW-03의 15 process Excel + 전체 목록) PASS. P-01/P-02 작업본은 테스트 전후 SHA assertion 일치. 물질수지 XLSM은 read-only SHA `565a9bd4c86f39adc959dd2ef8ab13c2ac7aac2efe0df4bc5e41f353b3a7daaa`로 기록했다.
- DB/서버/PM2/migration/배포/Cloudflare Preview는 실행하지 않았다. `PROFILE_PREFLIGHT_ENABLED` 및 `USER_WORKING_COPIES_ENABLED` 기본 OFF, 미승인 Profile 차단, 실제 ADD/DELETE 차단, VERIFIED flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 상태를 유지한다.
- 최신 증빙은 `validation-results/pilot-09-20261106/final-run-041500/`에 보존: `TEST_RESULTS.json`, `CHECKPOINT_STATUS.md`, `SUREFIRE_SUMMARY.tsv`, 최신 선택 Surefire XML 19개, MVC/선택 실행 로그, P-01/P-02 JSON 및 `WORKBOOK_SHA256_AFTER.txt`, `SHA256SUMS`. 모든 validation 산출물은 Git 제외.

---

## PILOT-10A — DB-free 물질수지 유향망 최종 검증 (2026-11-06)

- 기존 `DirectedProcessNetworkSolver`를 재사용하고, in-memory `MaterialFlowNetwork`/`MaterialFlowNetworkEngine` 모델을 추가했다. 설비·공정·유향 연결, 물질 구분·종류·단위·기간·증빙 상태와 provenance를 다룬다. edge 유량을 material class/type + unit + period별로 합산하며 설비·공정 잔차와 외부 source/sink 합계를 반환한다.
- 기존 solver의 선형 소거 경로에서 `double` 변환을 제거했다. BigDecimal 34 significant digits/HALF_EVEN, largest-absolute pivot, absolute `1e-9` tolerance를 사용한다. 이 tolerance와 division rounding은 정확한 현장 보존 증명의 한계로 남는다. rank 부족은 UNDERDETERMINED, 모순은 INCONSISTENT, 음수 풀이/잔차는 자동 수정 없이 보고한다.
- 신규 What-if는 immutable baseline 복제 기반이며 FLOW_CHANGE, branch/recycle/공정간 transfer quantity change, edge add/delete 시나리오를 처리한다. direct/upstream/downstream impact, residual delta 및 per-stream external totals equality를 반환한다. What-if는 계산/탐색과 별도이며 실제 VERIFIED/운영 상태로 승격하지 않는다.
- 제한: 분기 비율은 입력·합계를 검증하지만 비율 기반 후단 유량 배분은 미구현. 순환 탐지는 하지만 미지 순환 유량은 풀지 않는다. `PIPE_CHANGE`는 단일 추가 edge만 지원하며 원자적 제거+재배선은 미구현이다. generation/storage term, 운영 FLOW_CHANGE/PIPE_CHANGE 연결, 실제 Excel 자동 편집도 미구현이다.
- 실제 VERIFIED 연결·유량 매핑 0건, live readiness `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`, 기존 인증·소유권·CSRF/Profile 및 실제 ADD/DELETE/MODIFY guard를 유지한다. 공식 도면·XLSM·P-01/P-02 source/working files는 수정하지 않았다.
- 최종 UTF-8 컴파일 PASS; 선택 Surefire 12 classes / 57 tests / failures 0 / errors 0 / skipped 0. P-01/P-02 검증은 opt-in, read-only SHA assertion 기준으로 수행. 전체 suite 및 DB integration은 미실행.
- 상세 모델/엔진/시나리오/schema/Excel 연계 계약은 `NETWORK_MODEL.md`, `MATERIAL_BALANCE_ENGINE.md`, `WHAT_IF_SCENARIOS.md`, `INPUT_SCHEMA.md`, `EXCEL_ENGINE_INTEGRATION_PLAN.md`. 증빙은 `validation-results/pilot-10-20261106/final-run-20261106f/`에 보관하며 `/validation-results/`는 Git 제외.

---

## PILOT-10B — 분기 유량 배분·후단 연쇄 전파 최종 검증 (2026-11-06)

- `MaterialFlowNetwork.TransferRule`를 연결망 계산에 적용한다. 분기 비율 기반 계산은 BRANCH_RATIO, 직렬 전달은 PASS_THROUGH, 같은 stream 합류는 SUM_INPUTS, 증빙된 물질/유량 변환은 TRANSFORM만 사용한다. basis Edge ID·경로·물질/단위/기간·factor/evidence 조건을 통과하지 못하면 계산을 차단한다.
- BigDecimal 34 유효숫자/HALF_EVEN, allocation 합계 tolerance `1e-9`; 분기 반올림 잔여량을 임의 분기에 배정하지 않는다. 직렬·분기·합류 및 다중 공정 전파, 누락 규칙·미상 수량·순환 dependency 차단을 synthetic TEST_ONLY에서 검증했다.
- What-if는 baseline 복제형이며 분기 비율 변경을 지원한다. 설비/공정 잔차와 baseline 대비 증분, external totals 및 불변 비교, direct/downstream Edge 목록과 계산 불가 후단 목록을 반환한다. incomplete/empty external totals는 불변으로 표시하지 않는다.
- `MaterialFlowExcelChangeContract`는 구현돼 있지만 실제 파일·시트·셀·Shape·배관 매핑은 `UNKNOWN`/`REVIEW_REQUIRED`; 수정 허용 false다. 실제 Excel 편집 또는 공식 도면 수정은 지원하지 않는다.
- 최신 UTF-8 `test-compile` PASS; 선택 Surefire **9 classes / 58 tests / failures 0 / errors 0 / skipped 0**. 범위에는 엔진/DirectedProcessNetworkSolver/MaterialBalanceCalculator, What-if, synthetic FLOW_CHANGE 및 PIPE_CHANGE, authenticated FLOW_CHANGE service, OOXML round-trip가 포함된다. 전체 Maven suite 및 Profile 전용 테스트는 미실행; P-01/P-02 SHA opt-in도 이번 run에서 실행하지 않았다.
- PILOT-10A의 실제 VERIFIED 연결·유량 매핑 0건, 운영 `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED`를 유지했다. 미지 순환 유량, 원자적 PIPE_CHANGE 재배선, 운영 FLOW_CHANGE/PIPE_CHANGE 연결, 15개 공정도 전체 편집 및 실제 업무 승인 미완료.
- PILOT-10B 코드 변경은 auto-backup `2fb50a3`에 이미 포함됐다. 최종 검증 추가 변경은 `MaterialFlowNetworkEngine`과 해당 테스트에 한정한다. 증빙: `validation-results/pilot-10b-20261106/final-run-20261106-0710/` (Git ignored). DB/서버/PM2/migration/deploy/Cloudflare Preview는 실행하지 않았다.

---

## PILOT-11A — 전체 도면 배치 후보 분석 및 TEST_ONLY 위치 검증 (2026-11-08)

- 실제 재개 기준은 branch `step7-7-wip-profile-add-20261101`, HEAD `9dab23444917b92c48f459aa09be565992872726`; 당시 worktree clean이었다. 기존 Mapping/Preflight/TEST_ONLY 구현은 이미 HEAD에 포함돼 있어 재작성하지 않고 테스트 계약과 스캔 증빙을 보완했다.
- 현재 공식 reference manifest 16/16(15개 공정도 + `전체 목록.xlsx`) PASS. 이전 인벤토리 SHA와 15개 공정도 SHA도 일치한다. 전체 읽기 전용 scan은 15 workbook / 62 sheets로 완료됐고 파일별 SHA checkpoint를 재사용한다. inventory totals: 37,018 non-empty cells, 10,625 merges, 586 shapes, 8,094 connectors, 116 pictures, 0 explicit connector bindings.
- 설비 text 후보 5,044건 중 1,142 geometry box 후보, 3,899 text-only, 3 ambiguous. 위치 후보 1,006건 전부 `REVIEW_REQUIRED` / `automaticEditAllowed=false`; physical pipeline/flow mapping은 0. Profile catalog lookup은 62개 시트 중 5개 match(1 VERIFIED, 3 REVIEW_REQUIRED, 1 LEGACY_ONLY)이며 나머지 57개는 match 없음. Profile 단독 상태를 위치/편집 승인으로 간주하지 않는다.
- 잘못된 replay 테스트를 계약에 맞게 분리했다. 같은 source bytes/SHA/Profile/values의 순수 TEST_ONLY 재호출은 허용되고 결정적 동일 결과를 내며, 편집된 output에 이전 source SHA request를 적용하는 경우는 차단된다. stale prior field value도 기존 MODIFY guard로 차단된다. self-closing OOXML blank cell과 mapping identifier cell 검증을 추가했다.
- 예약된 사전서식 영역의 값 입력은 **RESERVED_RANGE_VALUE_ENTRY_ONLY**이며 full equipment-box ADD가 아니다. synthetic SHA-bound Profile fixture의 위치기반 MODIFY는 기존 Profile/source/working SHA/stale/style guard를 유지한다. 합성 XLSX 3개를 ZIP/POI 재열기, non-target/drawing/merge/style/row/column 보존 검사했고 LibreOffice PDF/PNG로 렌더링했다. Excel native render 및 실제 공정도 시각 검토는 미검증.
- UTF-8 test-compile PASS; 최종 선택 회귀 8 classes / 41 tests / failures 0 / errors 0 / skipped 0. 전체 Maven suite는 미실행. 관련 범위: 위치 preflight, Profile MODIFY, existing gap ADD, synthetic DELETE guard, OOXML, PIPE_CHANGE synthetic/security.
- full arbitrary-position box ADD, global official-layout DELETE, physical PIPE_CHANGE mapping은 미검증. Existing gap ADD가 행/anchor를 이동시키는 것은 no-move reserved cell writer와 별도다. 실제 flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 실행 증빙은 Git-ignored `validation-results/pilot-11a-20261108/` 아래; 최종 run `final-run-20261108-050359/`에 15개 per-file checkpoint, JSON inventory/mapping/progress, 비교 보고, TEST_ONLY XLSX, PDF/PNG, Surefire XML, 결과 문서 및 SHA256SUMS를 둔다. DB/MariaDB, 서버/PM2, migration, deploy/Cloudflare Preview는 실행하지 않았다.

---

## PILOT-11B — 셀 기반 위치형 설비 박스 ADD 최종 검증 (2026-11-08)

- 기존 `DiagramBoxAddEngine` / `DiagramPlacementPreflight` 구현은 TEST_ONLY 셀 레이아웃 경로다. 지정된 기존 blank target에 템플릿 스타일/병합과 허용 필드만 쓰며 행·열을 이동하지 않고 DrawingML/Connector를 생성·수정하지 않는다. 기존 gap-based insertion은 별도 경로다.
- synthetic `A1:C3 → E1:G3` 두 케이스(병합 없음 / 기존 `C2:C3` 및 target `G2:G3` 병합·테두리)를 생성했다. 값·스타일·병합·비대상 셀·row/column geometry·`A1:J10` dimension, POI 재열기·ZIP, source/output SHA, 비대상 OOXML parts와 DrawingML part byte equality를 검증했다. 이 결과는 실제 공정도나 실제 설비 ADD 승인에 해당하지 않는다.
- 마지막 UTF-8 test-compile PASS. 최신 선택 회귀는 9 classes / **43 tests, failures 0, errors 0, skipped 0**: `DiagramBoxAddEngineTest`, `DiagramPlacementPreflightTest`, `DiagramBoxChangeEngineTest`, `DiagramBoxChangeEngineProfileModifyTest`, `DiagramEquipmentInsertionEngineTest`, `Pw0105EquipmentInsertionEngineTest`, `DiagramModifyServiceDeleteGateTest`, `GeneralDiagramEditProfileStatusTest`, `OoxmlRoundTripTest`. Full Maven suite는 실행하지 않았다.
- 초기 LibreOffice 25.2.3.2 출력에서 빈 2페이지가 관찰되어 합성 fixture만 조사했다. Explicit print area는 `A1:J10`이고 target은 그 안이며 `pageSetup`/수동 pageBreak 없음. 원거리 synthetic DrawingML anchor를 제거/가까이 이동한 diagnostic은 같은 broad print area에서 1페이지가 됐다. 최종 TEST_ONLY fixture의 Shape/Connector anchor를 인쇄영역 안의 가까운 비간섭 셀로 옮긴 후 두 PDF 모두 1페이지다. Microsoft Excel native renderer는 실행하지 않았으며 공식 print 설정은 변경하지 않았다.
- 실제 도면 위치 후보 1,006건은 `REVIEW_REQUIRED`; actual full-box ADD 미승인. 필수 DrawingML Shape 생성 미지원, 기존 Connector의 physical endpoint 미확정, 배관/유량 자동 반영 미구현, 운영 API/DB 미연결. verified physical flow mapping 0, `CALCULATION_UNAVAILABLE`, `PIPE_CHANGE VISUAL_REVIEW_REQUIRED` 유지.
- 최종 증빙(문서 6종, JSON, SHA256SUMS, source/output XLSX 2쌍, PDF/PNG, 진단 변형 및 Surefire XML/log)은 Git-ignored `validation-results/pilot-11b-20261108/final-run-20261108-0619/`. 공식 reference SHA manifest 16/16 PASS. DB/MariaDB, server/PM2, migration, deployment/Cloudflare Preview를 실행하지 않았다.

---
