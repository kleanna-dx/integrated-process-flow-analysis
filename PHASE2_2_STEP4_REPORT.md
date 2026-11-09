# 통합공정도 자동화 — 2차 2단계 Step 4 보고서

- 작업일: 2026-10-19
- 범위: TEST_ONLY V5에서 C-PW15020 박스/우측 폐수·폐기물 구획 자동 탐색, 시험용 유량 표시, 전용 XLSX 출력 및 보존·시각 검증
- 제한: 공식 V1.0 원본 16개, MariaDB 기준자료, 기존 V5 및 Step 3 V1은 수정하지 않음. 실제 승인 관계를 생성하지 않음.

## 완료 결과 — 요청 항목 9개

1. **신규 설비 박스 자동 매핑**  
   `Pw0105EquipmentInsertionEngine.analyze(...)`에서 관리번호/설비명을 기준으로 자동 탐색했다. V5 상 C-PW15020 메인 박스는 `AI198:AZ208`; 우측 폐수 라벨은 `AV200`, 폐기물 라벨은 `AV206`으로 발견했다. 다운스트림 C-PW15010 box도 자동 분석했다. 이 범위/셀 주소는 런타임 탐색 결과이며 사용자가 주소나 shape ID를 입력하지 않는다.

2. **폐수·폐기물 물량 표시 위치**  
   우측 구획에는 기존 라벨, 외곽 테두리, 내부 분리선만 있고 표시 가능한 텍스트 공간이 충분하지 않았다. 기존 도면 구획 안에 숫자를 기입하면 기존 라벨과 겹칠 위험이 있어, V5 도면 셀을 건드리지 않고 새 `TEST_ONLY Results` 시트의 C-PW15020 전용 요약 영역에 표시했다. 라벨 셀 주소와 자동 탐색된 box range도 함께 기록했다. 미지정 수신처는 `TEST_ONLY 미지정 수신처 (실설비 미지정)`으로 표시하고, 폐기물 처분/반출은 미확정으로 남겼다.

3. **계산값과 XLSX 재독 값**  
   표기/계산한 값은 100 ton/day 유입, 0.5 ton/day 폐기물 발생, 2.5 ton/day 폐수 이동, 97 ton/day 후단 이동. 엔진의 C-PW15020 유입 100, 유출 100, 잔차 0과 질량수지 `100 = 0.5 + 2.5 + 97`을 확인했다. 저장된 XLSX를 다시 열어 네 시험값 및 연결 문자열을 재독해 계산 결과와 대조했다. 2.5 ton/day 목적지에는 실제 설비/공정 ID를 부여하지 않았다.

4. **신규 TEST_ONLY XLSX**  
   파일: `validation-results/2026-10-19-step4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V2.xlsx`  
   SHA-256: `90022b35d800d3b0f0944e256e46f5081605afb5ffb4373b22c39171871f0acc`  
   출력은 보존된 V5에서 새 결과 worksheet만 추가한 별도 파일이다. V5 및 Step 3 V1을 덮어쓰지 않았다.

5. **원본/수정본 시각 비교**  
   LibreOffice로 같은 조건에서 PDF를 만들었다. V5 PDF 32페이지, V2 PDF 33페이지(새 TEST_ONLY 결과 시트 1페이지 추가), A3. 기존 도면 page 27을 300 dpi(3508×4961 px)로 렌더링해 비교했고 픽셀 차분 결과가 없었다. 설비 구간 확대 비교 PNG는 `validation-results/2026-10-19-step4/cpw15020-before-after-300dpi.png`; 새 요약 영역 crop PNG는 `validation-results/2026-10-19-step4/render-output/test-only-summary-page33-crop-300dpi.png`.

6. **관련 테스트**  
   - `Cpw15020DiagramTestOnlyExportIntegrationTest`: 2 tests, failures 0, errors 0, skipped 0. Workflow/API, XLSX 재독 값, 원본 sheet/drawing 보존, DB relation count 불변 확인.
   - `MaterialBalanceCalculatorTest`: 6 tests, failures 0, errors 0, skipped 0.
   - 실행 시 `.env`를 읽어야 MariaDB에 연결된다. 전체 Maven suite 및 과거 Exit Code 137 경로는 재실행하지 않았다. 반복된 전체 OOXML layout scan으로 한 번의 실행이 timeout된 뒤 중복 scan을 제거하고 위의 전용 테스트를 다시 실행해 통과시켰다.

7. **기존 도형/연결선 보존**  
   V5와 V2 사이 `xl/worksheets/sheet5.xml` 및 `xl/drawings/drawing5.xml` byte equality 확인. 기존 workbook의 merged region, C-PW15020 TEST_ONLY marker, print area도 동일. page 27이 300 dpi pixel-identical이므로 기존 관리번호, 본문 정렬, 구획선, 화살표 및 인접 설비가 시각적으로 변경되지 않았다. 신규 TEST_ONLY 연결선은 만들지 않았다.

8. **공식 V1.0 원본 16개 무결성**  
   `scripts/verify-baseline-hashes.py` 결과: 로컬 원본/manifest/MariaDB metadata/MariaDB BLOB SHA-256 16/16 PASS. DB 기준 상태는 V1.0 document 16, equipment 1,332, CONFIRMED relation 0, REVIEW_REQUIRED relation 1, Flyway V11. 관련 relation status 수는 테스트 전후 동일했다.

9. **남은 기술적 문제**  
   - 실제 수신처가 지정되지 않은 2.5 ton/day의 위치는 미지정으로 보존한다. 자동 추정하거나 잔차를 보정하지 않는다.
   - 0.5 ton/day 폐기물의 저장/반출/처분 근거는 제공되지 않아 미확정이다.
   - 기존 도면 compartment 내부에는 안전하게 숫자를 넣을 빈 표시 위치가 확인되지 않았다. 따라서 이 출력은 실제 설비 상자의 셀을 변경하는 대신 같은 workbook의 전용 TEST_ONLY 결과 영역에 표시한다. 숫자를 도면 box 안에 직접 넣는 방식은 미구현이다.
   - C-PW15009→C-PW15020 및 C-PW15020→C-PW15010은 시험 입력과 기존 시각 connector evidence일 뿐이며 실제 승인 관계가 아니다. DB 상태는 REVIEW_REQUIRED 유지.
   - LibreOffice 렌더 검증이며 Excel 네이티브 렌더러별 차이는 검증하지 않았다.

## API

`POST /api/admin/phase2/material-balance/export-cpw15020-test-only`

`multipart/form-data` 필드 `request` (JSON)와 `workbook` (보존된 TEST_ONLY V5 XLSX)를 입력으로 받는다. 응답 헤더는 TEST_ONLY, 공식 도면 셀 미수정, 공식 물량 미수정 및 `REVIEW_REQUIRED`를 표시한다. 고정된 scenario 수치/transfer ID/미지정 receiver 형태만 허용한다.

## 보존된 입력/출력 SHA-256

- V5 input: `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`
- Step 3 V1: `f4101c5247dfed68a80c316b1e44a08b3495f40a3f5408946c77a952bf7040c9`
- Step 4 V2: `90022b35d800d3b0f0944e256e46f5081605afb5ffb4373b22c39171871f0acc`

범위는 C-PW15020 단일 TEST_ONLY 표시 기능까지다. 다른 도면 확대, 공식 유량 수정, 관계 승인, 웹페이지 개발 및 다음 단계 자동 실행은 하지 않았다. 다음 지시를 기다린다.
