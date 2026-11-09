# 통합공정도 자동화 — Step 5 DrawingML ID 최종 품질 검증

- 검증일: 2026-10-19
- 검사 범위: 공식 V1.0 원본 16개, 보존 V5, 기존 V4, 별도 ID 수정 사본
- 기능 범위: 구조 검증 및 필요한 경우의 최소 TEST_ONLY 파일 보정만 수행. Step 6 / 다른 공정도 개발은 시작하지 않음.

## 최종 판정

**drawing5.xml 안에서 cNvPr ID 159의 실제 중복을 확인했습니다.** 서로 다른 drawing part 사이의 정상 재사용만이 아니었습니다. 이 중복은 공식 V1.0에는 없고 V5 생성 단계에서 TEST_ONLY 하향 connector를 복제할 때 발생했으며, 기존 V5와 그 파생 V4에 남아 있었습니다.

V5/V4는 덮어쓰지 않았습니다. V4에서 TEST_ONLY 하향 connector의 ID 한 곳만 159에서 166으로 바꾼 별도 `V4-DRAWING-IDFIX` 파일을 만들었습니다. 연결선의 anchor, 선·화살표 XML, 다른 도형, 관계, 셀·시트 데이터는 변경하지 않았습니다. 수정본은 모든 DrawingML ID 고유성, 관계 대상, Apache POI 재열기, LibreOffice PDF 렌더 및 300dpi 도면 영역 pixel comparison을 통과했습니다. 따라서 **Step 5는 이 별도 ID 수정본을 최종 산출물로 하여 구조 검증 완료**로 판정합니다.

## 1. 모든 Drawing part ID 검사

- 공식 `data/reference/`의 XLSX 16개를 모두 열어 내부 `xl/drawings/drawing*.xml`을 검색했습니다. 공식 원본의 모든 개별 drawing part에서 cNvPr ID 중복은 **0건**이었습니다.
- TEST_ONLY V5와 V4에는 각각 6개 drawing part가 있고, 같은 drawing part 안의 ID 중복은 두 파일 모두 `xl/drawings/drawing5.xml`의 ID 159 한 건입니다.
- 공식 워크북 안에서도 ID 159 값은 서로 다른 drawing part(예: drawing3, drawing5, drawing6)에 각각 나타납니다. 이는 ID의 범위가 drawing part 내부이므로 정상적인 재사용입니다. 하지만 drawing5.xml 내부에서 같은 ID를 두 번 쓴 V5/V4 사례는 정상 재사용이 아닙니다.

## 2. ID 159 중복 위치 및 발생 시점

`PW-01-05 / xl/drawings/drawing5.xml` 안의 두 `xdr:cNvPr`:

| Drawing child 위치 | ID / 도형명 | 앵커 (from → to) | 구분 |
|---|---|---|---|
| `/xdr:wsDr/*[103]` | `159 / 원호 158` | `(col 34, colOff 82830, row 135, rowOff 99395)` → `(col 36, colOff 3246, row 136, rowOff 73132)` | 공식 V1.0 도형에서 보존된 원호 |
| `/xdr:wsDr/*[104]` | `159 / TEST_ONLY C-PW15020 flow connector` | `(40,0,208,0)` → `(40,0,211,0)` | V5에서 추가한 수직 TEST_ONLY connector |

원인: V5 생성의 `Pw0105EquipmentInsertionEngine.patchDrawing`은 새 ID를 정할 때 기존 전체 shape가 아닌 `xdr:cxnSp` connector만 대상으로 최대값을 조사했습니다. 그래서 기존 connector 최대값 다음인 159를 발급했지만, 비-connector 원호가 이미 ID 159를 사용 중이었습니다. 공식 V1.0 해당 drawing5에는 원호 `id=159`만 있고 복제 connector가 없어 중복이 없습니다. 중복 발생 시점은 V5의 TEST_ONLY connector 추가입니다.

두 도형 모두 `xdr:cNvPr/@id`만 갖고 있으며 다른 shape ID를 가리키는 내부 연결 reference는 발견되지 않았습니다. drawing5.xml에는 별도 `.rels`도 없습니다. sheet5.xml의 관계는 기존처럼 drawing5.xml을 가리킵니다. 따라서 connector ID 한 곳 변경으로 갱신해야 하는 별도 relationship/참조는 없었습니다.

## 3. 신규 ID 160–165 및 수정 ID 166

- V4 신규 `160–165`: 각 ID가 한 번씩만 사용되며 V5의 기존 ID와 충돌하지 않습니다.
  - 160 TEST_ONLY 유입 100 라벨
  - 161 TEST_ONLY 미확정 폐수 2.5 박스
  - 162 폐수 분기 화살표
  - 163 TEST_ONLY 미확정 폐기물 0.5 박스
  - 164 폐기물 분기 화살표
  - 165 TEST_ONLY 후단 이동 97 라벨
- 별도 수정본에서는 기존 중복 connector만 ID 166으로 바꿨습니다. 최종 drawing5.xml의 기존 `원호 158 / 159`, connector `166`, 신규 overlays `160–165` 모두 고유합니다.
- 최종 수정본의 6개 drawing part 전부에서 part-local cNvPr ID 중복은 0건입니다. OOXML relationship 대상으로 해석되는 모든 내부 `.rels` Target이 패키지 안에 존재하며 누락/미해결 target 0건입니다.

## 4. 수정 파일의 보존 및 재열기

- 보존 V5: `validation-results/2026-10-16/pw01-05-test-only/PW-01-05-C-PW15020-TEST_ONLY-V5.xlsx`
  - 536,808 bytes
  - SHA-256 `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`
- 보존 V4: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.xlsx`
  - 539,721 bytes
  - SHA-256 `cd55e21d5bd75f95adb3793be116197fb18b90e456c3dd8f69bd264126194a08`
- 최종 ID 수정 사본: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4-DRAWING-IDFIX.xlsx`
  - 429,603 bytes
  - SHA-256 `d7ebc9f43c6f0ac4e34992dc4a21e53f26676134187110db333e4f4b0e2f2235`
- V5와 V4 원본은 변경하지 않았습니다. 수정본과 V4를 ZIP part 내용으로 비교했을 때 변경된 part는 `xl/drawings/drawing5.xml` 하나뿐입니다. 이 part도 유일한 속성 차이는 `TEST_ONLY C-PW15020 flow connector`의 `cNvPr/@id: 159 → 166`입니다. 모든 anchor와 비주얼 도형 설정은 보존됐습니다.
- XLSX ZIP CRC 검증 PASS, Apache POI 재열기 PASS(7 sheets, `PW-01-05` 및 `TEST_ONLY Results` 확인), LibreOffice 25.2.3.2 재열기/PDF 변환 PASS.

## 5. 300dpi 시각 비교

- V5 32페이지 PDF 및 V4-IDFIX 33페이지 PDF를 LibreOffice로 렌더링했습니다. page 27은 두 파일 모두 A3 `PW-01-05` 공정도입니다. V4에는 결과 시트 1페이지가 추가되어 있습니다.
- V4와 ID 수정본의 page 27 동일 crop(300dpi, 1842×647 px)은 **pixel-identical**입니다. ID 변경이 도면 배치/표시를 바꾸지 않았음을 확인했습니다.
- 비교 이미지: `validation-results/2026-10-19-step5/V5-vs-V4-IDFIX-C-PW15020-300dpi.png` (300dpi metadata, 3752×743 px).
- 확인한 표기: C-PW15020, SCREEN FILTER, TEST_ONLY, 유입 100 ton/day, 폐수 이동처 미확정 2.5 ton/day, 폐기물 이동처 미확정 0.5 ton/day 발생, 후단 이동 97 ton/day. V4 ID 수정본과 V4의 렌더 crop은 pixel-identical.
- Microsoft Excel 애플리케이션에서 직접 열어 렌더하는 검증은 이 실행 환경에서 불가능했습니다. 검증은 Apache POI와 LibreOffice 기준입니다.

## 6. 테스트 및 기준자료 상태

- 이번 검증에서 실행한 `MaterialBalanceCalculatorTest` + `OoxmlRoundTripTest`: **8 tests, failures 0, errors 0, skipped 0**.
- 직전 Step 5 구현 검증에서 `Cpw15020DiagramTestOnlyExportIntegrationTest`는 **2/2 PASS**였습니다. 통합 테스트는 원본 V5를 입력으로 V4를 다시 쓰는 경로이므로, 이번에는 기존 V4 보존을 위해 재실행하지 않았습니다. 별도 ID 수정본은 POI 재열기, 패키지/relationship 검사 및 LibreOffice 렌더로 개별 검증했습니다.
- 공식 V1.0 원본 16개: local / SHA256SUMS / MariaDB metadata / DB BLOB **16/16 PASS**. 상세 파일 `validation-results/2026-10-15/baseline-sha256-verification.csv`.
- MariaDB 상태: V1.0 baseline documents 16, `equipment_record` 1,332, CONFIRMED relations 0, REVIEW_REQUIRED 1, Flyway V11. 변경 없음.
- 공식 V1.0, V5, 기존 V4는 수정하지 않았고 ID 수정본은 TEST_ONLY 사본입니다. 실제 승인 관계는 0건입니다.

## 최종 상태 및 잔여 위험

- **완료:** V5/V4 중복의 part-local 성격, 원인 및 발생 시점을 확인하고 별도 수정본에서 connector ID 하나를 안전하게 바꿨으며 구조/시각/재열기 검증을 통과했습니다.
- **보존:** 기존 V5/V4와 공식 16개 원본은 그대로입니다. 최종 사용 시 ID 중복이 없는 `V4-DRAWING-IDFIX` 사본을 선택해야 합니다.
- **잔여:** Microsoft Excel 네이티브 렌더는 확인하지 못했습니다. 허가상의 실제 수신처/관계 승인 검증은 이번 구조검증 범위가 아니며 계속 미확정입니다.
- Step 6, 다른 공정도 수정, 공식 물량·관계 변경은 수행하지 않았습니다.
