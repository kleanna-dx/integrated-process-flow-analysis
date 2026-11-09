# 2차 2단계 Step 3 — TEST_ONLY 계산 결과 Excel 반영

- 작업일: 2026-10-19
- 범위: 기존 TEST_ONLY 계산 결과를 V5 기반 복사본의 전용 결과 시트에 기록
- 금지사항 준수: 공식 V1.0 원본 16개, 기준 DB 값, 기존 TEST_ONLY V5, 공식 PW-01-05 물량 및 새 공식 버전은 변경하지 않음.

## 완료 보고 — 요청 10개 항목

1. **계산기와 Excel 편집 엔진 연동**  
   `MaterialBalanceExcelWorkflowService`가 TEST_ONLY 계산 → `MaterialBalanceExcelOutputModel` 변환 → `TEST_ONLY Results` 시트 추가 순서로 동작. `Pw0105EquipmentInsertionEngine.analyze`로 V5의 기존 TEST_ONLY box/주변 시각 연결도 확인하되, 이 분석은 논리적 연결 승인이나 시나리오 노드 매핑으로 취급하지 않음. 현재 확인된 box는 `C-PW15020 AI198:AZ208`, 최종 탱크 box `C-PW15010 AI212:AZ220`, 시각 connector shape 90 및 159; 관계 상태는 REVIEW_REQUIRED로 표시.

2. **기록한 물량 / 보류한 물량**  
   전용 시트에 다섯 이동 edge(0.5 폐수→폐기물, 2.5 폐수 이동, 97 원천 후단, 2.25 P2 후단, 2.25 P3 후단)의 단위·송출/수신 node·송출량/수신량·포함 상태를 기록. 송수신값은 같은 transfer record에서 파생된 값이며 독립 계측값이 아니라는 점을 표시.  
   노드 balance: S WASTEWATER 100/100 잔차 0, W WASTE 0.5/0 잔차 0.5 미확정, P2 2.5/2.25 잔차 **0.25 미확정**, P3 2.25/2.25 잔차 0, D 유입 99.25/유출 0 잔차 99.25. W의 저장/반출, D의 최종 방류는 미확정이며 D의 99.25를 방류량으로 합산하지 않음.

3. **새 TEST_ONLY XLSX**  
   `validation-results/2026-10-19-step3/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V1.xlsx`  
   SHA-256: `f4101c5247dfed68a80c316b1e44a08b3495f40a3f5408946c77a952bf7040c9`.

4. **PDF/PNG 시각 검증**  
   PDF: `validation-results/2026-10-19-step3/render-final4/PW-01-05-C-PW15020-TEST_ONLY-MATERIAL-BALANCE-V1.pdf`  
   추가 시트 PNG: `validation-results/2026-10-19-step3/render-final4/results-sheet-page33.png`  
   LibreOffice 25.2.3.2로 렌더링. PDF 총 33페이지(기존 V5의 32페이지 + 신규 A3 landscape 결과 페이지 1장). V5 PDF와 새 PDF의 기존 32페이지를 36dpi로 픽셀 비교해 차이가 없음을 확인. 결과 시트에는 TEST_ONLY 경고, 이동/송수신량, 잔차, 경계조건 및 매핑 보류 상태가 보임.

5. **실제 설비 매핑에 필요한 추가자료**  
   S/P2/W/D 각각의 실제 V1.0 설비 ID와 공정 코드, 담당자 확인이 있는 TEST_ONLY 매핑 목록, 실제 도면의 셀 범위/앵커 근거, 실제 송수신 흐름의 승인 자료가 필요. 이번 scenario의 equipmentId는 모두 null이며, `TEST_ONLY Results`에 설비 미매핑을 명시. C-PW15020은 기존 TEST_ONLY layout 대상일 뿐 S/P2/W/D 중 어느 것과도 연결하지 않음.

6. **잔차/경계조건**  
   P2 잔차 0.25 ton/day와 W 잔차 0.5 ton/day를 보존. D의 99.25 ton/day는 terminal boundary가 없으므로 final discharge가 아니라 미확정 유입. 결과 시트는 이 세 항목을 별도 노란 경고 스타일로 표시.

7. **테스트 결과**  
   최종 분리 실행: `MaterialBalanceCalculatorTest` 6/6, `MaterialBalancePersistenceIntegrationTest` 7/7, `MaterialBalanceExcelExportIntegrationTest` 3/3, `PhaseTwoPlanningTest` 1/1. 합계 17, 실패 0, 오류 0, skip 0. API multipart export, TEST_ONLY 전용 sheet round-trip, source V5 hash 검사, 기존 OOXML part 보존, 매핑 미실시, 행 값, 인쇄영역/가로 방향을 확인. 전체 Maven suite/기존 Exit 137 suite는 재실행하지 않음.

8. **공식 원본 16개 무결성**  
   `verify-baseline-hashes.py` 재검증: 로컬 원본/manifest/DB metadata/DB BLOB **16/16 PASS**. DB의 V1.0 문서 16, 설비 1,332, CONFIRMED relation 0, REVIEW_REQUIRED 1 상태 유지.

9. **기존 V5 보존**  
   V5 SHA-256은 기존과 같은 `7e20e6598e2875f3c8d249076fd4a2ee9003e21ffdf1492ef748974817f23485`. 새 workbook 생성기는 이 정확한 해시와 `TEST_ONLY` marker를 확인하고, 기존 V5의 모든 worksheet/drawing/style/기타 OOXML part가 byte-for-byte 같음을 검증. 바뀐 것은 workbook/sheet 목록 메타데이터와 app props, 새 `sheet7.xml`뿐.

10. **다음 필요사항 / 현재 경계**  
    실설비 매핑 승인, P2/W/D의 확인된 이동·저장·배출 자료, 별도 송출/수신 계측이 필요. 현재 시트는 V5의 기존 box를 편집하지 않고 계산 결과 sheet만 추가. `POST /api/admin/phase2/material-balance/export`는 관리자 token과 `request` JSON part, 보존된 V5 `workbook` part를 받아 attachment를 반환. 공식 도면 반영 및 다음 자동 단계는 시작하지 않음.

### 기술 검증 세부

- 허용된 package 변화: `xl/workbook.xml`, `xl/_rels/workbook.xml.rels`, `[Content_Types].xml`, `docProps/app.xml`, 새 `xl/worksheets/sheet7.xml`.
- 기존 sheet1–sheet6, 특히 `PW-01-05` sheet5, `drawing5.xml`, styles 및 기존 인쇄영역은 raw OOXML 비교에서 그대로 유지.
- TEST_ONLY box 시각 연결 증거는 기존 layout 분석용입니다. relationStatus/공식 route 승인이 아니며, 시나리오 S/P2/W/D를 box나 실제 설비에 연결하지 않습니다.
- Flyway V1–V11 보존; 신규 DB migration 불필요. 테스트 transaction 종료 후 simulation row는 rollback.
