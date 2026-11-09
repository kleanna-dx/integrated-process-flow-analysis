# Step 6-4D 최종 무결성 감사

- 감사일: 2026-10-21
- 프로젝트: `/home/user/webapp`
- 작업 브랜치: `step6-4-wip-20261021`
- 기준 커밋: `0906d75 Verify user-ready Step 6-4C workbook output`
- 대상 통합 XLSX: `validation-results/2026-10-21-step6-4-integrated/TEST_ONLY-run193-plan33-C-PW15020-PW-01-05-user-verified-20261021.xlsx`
- 기대/실제 SHA-256: `fd0ab457b2efeebc1086893e54e3fdfd79c4e83924082d2ee0fc2482732a6804`
- 결론: 아래 A–H 검증은 모두 완료. 보호 대상에서 비교 차이나 변경은 발견되지 않았다. XLSX 및 기준자료를 수정하지 않았다.

## A–H 검증 결과

| 항목 | 결과 | 검사 방법 및 관찰 결과 |
|---|---|---|
| A. 공식 V1.0 원본 16개 SHA-256 대조 | **완료** | 로컬 공식 기준 XLSX 16개 각각의 SHA-256을 `data/reference/SHA256SUMS`, MariaDB `baseline_document` 메타데이터 및 DB BLOB SHA-256과 대조. 모든 16개가 일치. 공식 대표 파일 `PW-01 폐수처리공정 ★변경허가.xlsx`: `784dada7ec1a2523ac54cef3d5384780eca6a3791bb5b95aaf085a8dc12127f0`. 기존 성공 결과를 재사용했으며 파일은 read-only였다. |
| B. DB 기준자료 및 기준 설비 1,332개 불변성 | **완료** | read-only MariaDB snapshot에서 기준 문서 16, 기준 설비 1,332, migration schema V13 확인. 통합 산출 테스트의 DB 전후 count 동등성 assertion도 통과. 별도 DB 쓰기·migration은 수행하지 않았다. |
| C. 승인 관계 불변성 | **완료** | read-only snapshot에서 `CONFIRMED=0`, `REVIEW_REQUIRED=1` 확인. 통합 테스트의 관계 건수 전후 동등 assertion 통과. plan 33의 run 193/item 217은 `TEST_ONLY_READY`, item은 `REVIEW_REQUIRED`/`ADDED`이며 실제 승인 관계를 만들지 않았다. |
| D. plan 33 원본 도면 시트 6개 OOXML 비교 | **완료** | plan 33 원본(`d604fff6cfca7f4597d7b7f025428d489be9e88cb5c2f7a6f335132e673d06bc`)과 최종본의 ZIP package part를 비교. 기존 worksheet `xl/worksheets/sheet1.xml`–`sheet6.xml`은 모두 byte-identical. 제거 part 없음. 새 worksheet `sheet7.xml`, `sheet8.xml` 두 개만 추가. 변경된 공통 part는 `[Content_Types].xml`, `docProps/app.xml`, `xl/_rels/workbook.xml.rels`, `xl/workbook.xml` 네 개로, 새 시트 등록에 필요한 workbook metadata다. package part 수 34→36. |
| E. DrawingML 도형·연결선·ID | **완료** | `xl/drawings/drawing5.xml`에서 105 anchors/objects, connector 92, 일반 shape 13 확인. duplicate shape ID 없음. ID 160 `TEST_ONLY C-PW15020 flow connector`, ID 161 `TEST_ONLY / REVIEW_REQUIRED status`. 원본과 최종본의 drawing5 object/ID count 동일. |
| F. 인쇄 영역·스타일·병합셀 보존 | **완료** | 기존 대상 `PW-01-05` worksheet part가 원본/최종본 간 byte-identical. 따라서 해당 시트의 셀·style reference·merge·print setup 보존 확인. 병합 셀 85개, dimension `B1:CC611`, print area `'PW-01-05'!$A$1:$BJ$230`, print titles `$2:$3`, portrait, paper size 8, scale 89, fitToHeight 0. 물질수지 시트의 print area는 `A1:C25`로 확인. 상세 검토 내용은 시트에 유지되어 있으나 26–69행의 217개 non-None 값은 A1:C25 인쇄 범위 밖이다. 이를 숨기거나 인쇄 영역을 임의로 넓히지 않았다. |
| G. 통합 XLSX 재열기·시각 검증 | **완료** | 이전 focused JUnit 5/5 PASS 및 LibreOffice PDF 35페이지 생성, 페이지 33–35 시각 확인을 재사용(전체 PDF를 다시 만들지 않음). 이번 재개에서 Apache POI로 최종 XLSX를 read-only 재열기: 8 sheets, active index 7, 변경내역 nonempty 86, 물질수지 physical cell 418/nonblank 250. `SUM(B10:B12)` cached value 100, `B9-B13` cached value 0, print area A1:C25 확인. 최종 `POI_AUDIT=PASS`. |
| H. 다운로드 파일 해시 | **완료** | 공유 URL에서 받은 `/tmp/step64c_shared_download/downloaded-user-verified.xlsx`의 SHA-256이 저장된 대상 XLSX와 동일한 `fd0ab457b2efeebc1086893e54e3fdfd79c4e83924082d2ee0fc2482732a6804`. `unzip -t` 무결성 검사 오류 없음, 다운로드 파일 재열기에서 8 sheets 확인. 기존 다운로드 검증을 재사용했다. |

## POI 검사 재개 중 오류 기록

첫 재실행은 파일 경로 환경변수 전달을 빠뜨려 POI에 파일이 전달되지 않았으므로 유효한 검사 결과가 아니었다. 이어진 계수 재실행은 8개 sheet/수식/인쇄영역을 정상적으로 읽었으나 임시 assertion이 value-bearing cells를 잘못 셌다. 기준을 다시 구분해 마지막 실행에서 PASS를 얻었다.

- `balancePhysical=418`: POI가 worksheet에 저장된 실제 cell 객체 수로 집계한 값(스타일 전용 셀 포함)
- `balanceNonblank=250`: POI 셀 타입 기준 비어 있지 않은 값/수식 수
- worksheet XML `<c>` element 수 418, payload cell 관련 별도 집계 268은 이전 OOXML 검사에서 확인된 별도 정의의 값이며, POI physical count와 같은 개념이 아니다.
- 기존 실패는 XLSX 손상이나 셀 손실의 증거가 아니라 audit 코드의 잘못된 expected count였다. 파일은 읽기만 했다.

응답 생성 오류의 내부 원인은 확인할 수 없다. 그 원인에 대해 추측하지 않는다.

## 자료 보호 및 범위

- 공식 V1.0 XLSX 및 manifest, MariaDB 기준자료/승인 관계, plan 33 원본, 최종 통합 XLSX는 읽기 전용으로 유지했다.
- 대상 XLSX SHA-256은 감사 전후 확인값과 일치한다. 파일을 덮어쓰거나 자동 수정하지 않았다.
- `main` 병합, stash 삭제·재적용, 전체 Maven suite, 전체 공정도 분석, 전체 PDF 재변환은 수행하지 않았다.
- Step 6-5 개발 및 웹 다운로드 UI/ZIP 연결은 시작하지 않았다.
- 이번 커밋 대상은 감사 보고서와 CHECKPOINT 기록 두 파일만이다.
