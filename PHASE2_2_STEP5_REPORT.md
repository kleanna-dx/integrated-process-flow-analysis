# 통합공정도 자동화 — 2차 2단계 Step 5 검증·오류 수정 보고서

- 작업일: 2026-10-19
- 대상 시트: `PW-01-05`
- 범위: 보존된 TEST_ONLY V5를 바탕으로 C-PW15020의 시험 물량/분기 표시를 실제 공정도 DrawingML에 적용하고 V3를 검증한 뒤 필요한 국소 수정만 V4에 생성
- 보호 범위: 공식 V1.0 원본 16개, V5, Step 3 V1, Step 4 V2, 기존 V3, 공식 물량 및 실제 승인 관계를 변경하지 않음

## 요약

V3는 `PW-01-05` page 27의 실제 도면에 TEST_ONLY 오버레이를 포함하고 있었습니다. 그러나 유입 100과 후단 이동 97을 하나의 박스 안에 넣어 두 수치가 한 흐름처럼 읽히고, 확대 이미지에서 97 라인이 박스 아래 경계에 붙어 보였습니다. 따라서 V3는 보존하고, 해당 라벨만 분리해 기존 C-PW15020→후단 설비의 시험 연결선 곁에 배치하도록 수정했습니다. 새로운 결과는 V4로 만들었습니다.

## 요청 항목별 결과

1. **V3 확인 및 보존**
   - V3: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V3.xlsx`
   - 크기: 539,650 bytes
   - SHA-256: `9c2210310834624365215838df8595ac487e127aef15707e10c92d4aa0246135`
   - 검증 전·후 hash가 같음. V3를 덮어쓰지 않았음.
   - 최초 오버레이 소스는 `validation-results/2026-10-19-step5/Pw0105DiagramTestOnlyOverlayService-preverify-dda000b07366c2d7.java`에 보존. 보존본 SHA-256 `dda000b07366c2d7d0c3cf646556e8085c1db55c0f98fb12e4a73d7dc0c9c26a`.

2. **실제 도면에 표시된 시험 물량**
   - C-PW15020 설비 박스 및 `SCREEN FILTER` 본문 보존.
   - 유입: `TEST_ONLY / 유입 100 ton/day` — C-PW15020 박스 내부의 별도 빨간 글씨/연노란 박스.
   - 폐수: `TEST_ONLY / 폐수 이동처 미확정 / 2.5 ton/day` — 기존 폐수 구획에서 신규 우측 흰색 TEST_ONLY 수신 표시로 연결.
   - 폐기물: `TEST_ONLY / 폐기물 이동처 미확정 / 0.5 ton/day 발생` — 기존 폐기물 구획에서 신규 우측 흰색 TEST_ONLY 수신 표시로 연결.
   - 후단 이동: `TEST_ONLY / 후단 이동 97 ton/day` — 기존 하향 TEST_ONLY 연결선에 인접한 독립 라벨.
   - 수신처가 미확정인 폐수·폐기물은 특정 공정/설비 ID로 연결하지 않았음. 97은 표시 문구상 후단 이동이며 관계 승인을 의미하지 않음.

3. **V3 오류와 제한 수정**
   - V3에서 유입 100과 후단 97이 동일 도형 안에 있어 의미상 구분이 불명확했습니다.
   - 기존 박스 안에는 `TEST_ONLY / 유입 100 ton/day`만 남기고, `TEST_ONLY / 후단 이동 97 ton/day` 도형을 기존 하향 시험 연결선 옆 별도 위치에 추가했습니다.
   - 기존 V3·V5 파일과 다른 공정도는 수정하지 않았음.

4. **분기선·화살표 및 레이아웃**
   - V4에서 DrawingML 텍스트 박스 4개, 분기 화살표 연결선 2개를 추가했습니다. 기존 C-PW15020 하향 연결선은 V5에서 그대로 보존했습니다.
   - 폐수·폐기물의 새 화살표 머리는 모두 오른쪽 TEST_ONLY 미확정 수신 박스를 향하며, 300dpi PDF 확대에서 화살촉·접점을 확인했습니다.
   - 기존 C-PW15020 메인 박스, 관리번호 위치, 본문 가운데 정렬, 폐수·폐기물 내부 경계선 및 기존 방향표시는 시각적으로 유지됩니다.
   - PW-01-05 sheet5 XML 및 sheet5 관계 XML은 V5와 V4 간 byte-identical. 병합 셀 85개, 인쇄영역 `'PW-01-05'!$A$1:$BJ$230` 유지. 행높이/열너비를 늘리거나 행을 삽입하지 않았습니다.
   - PDF는 V5/V4 모두 32개 기존 도면 페이지 + V4 전용 결과 페이지 1개, 총 33페이지(A3)입니다. page 27 내 도형은 페이지 인쇄 범위 안에 렌더링됐습니다.
   - V5 대비 V4의 다른 기존 페이지 1–26, 28–32는 36dpi 렌더에서 pixel-identical. page 27의 300dpi 차이는 신규 오버레이 영역에서만 확인됩니다.

5. **DrawingML/관계/기존 도형 보존**
   - Apache POI 및 LibreOffice에서 V4 재열기/렌더 성공, ZIP integrity 검사 PASS.
   - V5 drawing5.xml의 기존 104 anchor element가 V4 drawing5.xml의 정확한 prefix로 보존됨.
   - 새로 추가한 shape/connector ID는 `160–165`, 서로 중복되지 않고 기존 ID와 충돌하지 않음. 신규 텍스트 및 화살표의 `TEST_ONLY` 명칭/설명/anchor를 검사함.
   - V5 원본 DrawingML에는 기존 ID `159`가 두 번 쓰인 중복이 이미 있었으며 V3와 V4도 이를 그대로 보존했습니다. 이는 이번 편집에서 새로 생긴 중복이 아닙니다. 기존 도형 보존 요구에 따라 수정하지 않았고, V4 재열기 및 PDF 렌더는 정상 완료했습니다.
   - worksheet 관계는 계속 drawing5.xml을 가리킵니다. 새 도형은 내장 DrawingML shape/connector라 별도 외부 relationship을 요구하지 않으며, 새로운 dangling relationship은 발견되지 않았습니다.
   - 공통 OOXML part 중 변경된 것은 workbook/sheet metadata, content types, app properties와 drawing5.xml입니다. sheet5 데이터·관계, 스타일, drawing1–4/6은 변경되지 않았습니다. sheet7은 테스트별 simulation 식별값이 달라져 byte hash는 다를 수 있으나 4개 transfer 행의 물량/상태는 같습니다.

6. **계산값과 XLSX 표시값 재독 대조**
   - V4의 `TEST_ONLY Results` 시트를 openpyxl로 재독해 transfer 값을 확인했습니다.
     - `cpw15009-cpw15020-in`: 100 / 100 ton/day
     - `cpw15020-waste-0_5`: 0.5 / 0.5 ton/day
     - `cpw15020-unassigned-2_5`: 2.5 / 2.5 ton/day
     - `cpw15020-cpw15010-out`: 97 / 97 ton/day
   - DrawingML 텍스트에도 유입 100, 폐기물 0.5, 폐수 2.5, 후단 97이 기록되어 PDF page 27에서 확인됩니다.
   - 질량수지: `100 = 0.5 + 2.5 + 97 ton/day`; 계산 결과 요약 문구 및 표시값 대조 PASS. C-PW15020 계산 balance는 100 유입 / 100 유출 / 잔차 0.
   - 모든 네 이동 edge의 관계 모드는 TEST_ONLY, relation ID 및 미확정 수신 설비 매핑은 없음. 승인 상태는 REVIEW_REQUIRED, 실제 승인 관계 0건.

7. **V4 최종 파일 및 시각 산출물**
   - XLSX: `validation-results/2026-10-19-step5/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.xlsx`
   - V4 XLSX SHA-256: `cd55e21d5bd75f95adb3793be116197fb18b90e456c3dd8f69bd264126194a08`
   - V4 PDF: `validation-results/2026-10-19-step5/render-v4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V4.pdf`
   - V5/V4 300dpi 같은 영역 비교: `validation-results/2026-10-19-step5/V5-vs-V4-C-PW15020-detail-300dpi.png`
   - V4 도면 부분 확대: `validation-results/2026-10-19-step5/V4-PW-01-05-cpw15020-detail-300dpi.png`
   - V4 page 27 전체 미리보기(150dpi): `validation-results/2026-10-19-step5/V4-PW-01-05-page27-full.png`
   - 기존 V3의 시각 검증 산출물도 `V3-PW-01-05-cpw15020-detail-300dpi.png` 및 `render-v3/`에 남겨 비교 가능하게 보존했습니다.

8. **테스트 및 기준 무결성**
   - `Cpw15020DiagramTestOnlyExportIntegrationTest`: 2/2 PASS (새 오버레이 생성, V4 재독, 도형·화살표 확인, V5 sheet5 보존, API REVIEW_REQUIRED 및 DB 승인 상태 불변).
   - `MaterialBalanceCalculatorTest`: 6/6 PASS.
   - `OoxmlRoundTripTest`: 2/2 PASS.
   - 합계 10 tests, failures 0, errors 0, skipped 0. 전체 Maven suite 및 과거 Exit Code 137 실행은 반복하지 않았음.
   - 공식 V1.0 원본: local/manifest/MariaDB metadata/BLOB SHA-256 16/16 PASS. 검증표 `validation-results/2026-10-15/baseline-sha256-verification.csv`.
   - MariaDB: V1.0 baseline documents 16, `equipment_record` 1,332, CONFIRMED relation 0, REVIEW_REQUIRED 1, Flyway V11 유지.
   - V5 SHA-256 유지: `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`.
   - Step 3 V1 및 Step 4 V2는 통합 테스트 전후 hash 보존 확인. V3 hash도 유지.

## 미완료/주의

- V5에 이미 있던 DrawingML shape ID `159` 중복은 원본 도형 보존을 위해 고치지 않았습니다. 신규 ID `160–165`에는 중복이 없습니다.
- 렌더 검증은 LibreOffice 25.2.3.2 기준이며, Microsoft Excel의 제품별 DrawingML 렌더 차이는 확인하지 못했습니다.
- 미확정 폐수·폐기물 실제 수신처, 저장·처분/반출 및 실제 관계 승인 자료는 없습니다. 어떤 공식 설비 연결도 확정하지 않았습니다.
- 공식 V1.0 도면, 공식 유량, 다른 공정도, Step 3/4/V3 결과는 변경하지 않았습니다. Step 6 등 후속 단계는 시작하지 않았습니다.
