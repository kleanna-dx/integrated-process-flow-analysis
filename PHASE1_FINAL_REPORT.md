# 통합공정도 자동화 시스템 1차 개발 최종 검증 보고서

- 작성일: 2026-10-16
- 통과 테스트 코드 스냅샷: `88826e1bb706`. 해당 스냅샷부터 보고서 작성 전까지 `src/`, `pom.xml`, `scripts/test-local.sh` 변경은 없음을 Git diff로 확인함.
- 환경: Java 17.0.20.1, Spring Boot 3.5.16, MariaDB 11.8.6
- 범위: 기존 1차 기능 유지 및 PW-01-05 connector shape 149 검증. 2차 개발은 시작하지 않음.

## 최종 판정

**1차 구현 기능과 회귀 테스트 항목은 완료로 판단한다.** 여섯 테스트 클래스를 각각 독립 Maven 실행으로 전부 실행했고 21건 모두 성공했다. 단, 단일 프로세스로 실행한 `scripts/test-local.sh`는 Surefire fork가 exit 137로 종료되어 전체 `BUILD SUCCESS`에 이르지 못했다. 따라서 “분할 실행 기준 전 테스트 통과”와 “한 번의 통합 실행 통과”를 구분한다. 단일 실행을 필수 수용 기준으로 삼는 경우, 메모리 여유가 있는 CI 환경에서 그 실행만 추가 확인해야 한다.

## 테스트 실행 결과

### 전체 테스트 클래스별 실행

각 테스트 클래스를 분리해 `mvnw -Dtest=<class> -DfailIfNoTests=true ... verify`로 실행했다. 앞서 통과한 네 그룹도 최종 집계에 포함해 다시 분리 실행했다. 테스트를 삭제하거나 건너뛰지 않았다.

| 테스트 클래스 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 | 실행 종료 코드 | wall time |
|---|---:|---:|---:|---:|---:|---:|---:|
| MariaDbConnectivityTest | 1 | 1 | 0 | 0 | 0 | 0 | 11초 |
| OoxmlRoundTripTest | 2 | 2 | 0 | 0 | 0 | 0 | 5초 |
| BaselineImmutabilityTest | 1 | 1 | 0 | 0 | 0 | 0 | 7초 |
| RealPw01PreservationTest | 2 | 2 | 0 | 0 | 0 | 0 | 18초 |
| MasterChangeDetectionIntegrationTest | 10 | 10 | 0 | 0 | 0 | 0 | 68초 |
| ConnectorAnalysisIntegrationTest | 5 | 5 | 0 | 0 | 0 | 0 | 22초 |
| **합계** | **21** | **21** | **0** | **0** | **0** | **모든 그룹 0** | **131초 (2분 11초)** |

Maven 각 그룹의 `Total time`을 합치면 약 122초다. 변경 탐지 그룹은 이후 사용자가 요청해 한 번 더 단독 실행했고, 10/10 통과, Maven `BUILD SUCCESS`, fork 종료 코드 0, wall time 69초였다. 이 재실행은 합계 21건에 중복 가산하지 않는다.

상세 실행 로그와 집계표: `validation-results/2026-10-16/maven-final-groups/`, 최신 변경 탐지 단독 재실행: `validation-results/2026-10-16/maven-final-two/`.

### 단일 통합 실행과 분할 실행의 차이

`./scripts/test-local.sh` 한 번 실행은 17분 56초 뒤 `BUILD FAILURE`로 끝났다. Surefire가 `MasterChangeDetectionIntegrationTest`의 Spring context 준비 중 fork 종료 코드 137과 `Killed`를 보고했다. 해당 시점까지 로그상 실행 완료된 테스트 6건은 assertion 실패가 없었지만, 이 단일 실행은 성공으로 처리하지 않는다.

메모리/스왑 압박이 관찰됐고 테스트 서버를 잠시 중지해 실행 공간을 확보했다. 다만 cgroup OOM 기록이나 커널 kill 로그는 확인되지 않아 exit 137의 정확한 OS 원인은 확정하지 않는다. 테스트 전체를 무리하게 한 fork에서 반복하지 않고 6개 테스트 클래스를 독립 실행해 모든 클래스를 검증했다.

## 테스트에서 발견·수정한 사항

1. **HTTP 검증기의 `KeyError: 'evidence'`**: 애플리케이션 API 오류가 아니라 검증기가 응답에 없는 `evidence` key를 참조한 문제였다. 실제 관계 목록 응답은 drawing/OOXML 근거에 `connectorEvidence`, 후보 검토 문구에 `candidateEvidence`를 제공한다. 검증기의 기대 필드를 맞춘 후 실제 Spring API에서 분석 POST, 후보 GET, 근거 PUT, history GET 및 CONFIRMED GET을 확인했다. 관련 응답은 `validation-results/2026-10-15/api-http/`와 `validation-results/2026-10-16/api-http-live/`에 보존했다.
2. **POI 계산 체인 직렬화 지연**: 공식 마스터 파일의 `calcChain.xml`에는 약 109,693개 항목이 있다. 변경 탐지용 테스트 업로드본을 만드는 fixture에서만 calculation chain을 비우도록 했다. 테스트 입력 복사본의 관련 없는 계산 메타데이터만 제외하며 공식 원본이나 MariaDB BLOB은 수정하지 않는다. 변경 탐지 10건이 단독 실행에서 65초 안팎으로 통과했다.
3. **영속 MariaDB 이력과 테스트 기대값 불일치**: 실제 HTTP 검증으로 후보 history 2건이 이미 존재하므로, connector 통합 테스트의 절대 기대값 `history.size()==1`이 3건 관찰로 실패했다. 현재 테스트는 사전 이력 수보다 1건 증가했는지와 신규 action/actor를 확인하도록 바꾸었다. 연결 상태나 승인 규칙을 완화한 수정은 아니며, 해당 수정이 포함된 소스 revision에서 연결 테스트 5건 모두 통과했다.

## 기능 및 실제 데이터 검증

- 기준 V1.0: 공식 문서 16개가 MariaDB에 저장되어 있음. 테스트 종료 후 로컬 원본, `SHA256SUMS`, DB metadata hash, DB BLOB hash가 다시 모두 일치했다(16/16 PASS). 상세 CSV: `validation-results/2026-10-16/baseline-sha256-verification.csv`.
- 설비 마스터: 기준 1,332개 설비 파싱 및 매핑 결과 저장.
- 도면 inventory: 시트, 도형, connector, anchor, OOXML transform/화살표 metadata 추출 및 저장.
- 설비 변경 탐지: 추가·삭제·수정·행 순서 변경, 폐수/폐기물 이동 검토 후보와 미완성/미해결 검증 항목을 지원. 변경 탐지 통합 테스트 10건 통과.
- 관리자 검토 API: connector 후보 조회, evidence-only 수정, 변경 이력 snapshot, relation 검토/생성 경로와 append-only 감사 이력 구현·검증.
- PW-01-05 후보: ID `30579` 한 건, `relation_status=REVIEW_REQUIRED`, `analysis_status=REVIEW_REQUIRED`, `relation_type=UNKNOWN`. 최신 셀 범위는 `D152 → AI148:AV154`; source/target 설비 ID와 reviewer는 null. history 2건.
- 승인 관계: MariaDB 전체 `CONFIRMED` 0건이며 승인 목록 GET도 빈 목록. 테스트/HTTP evidence 수정으로 설비 관계를 승인하지 않았다.
- 도면 증거: shape 149의 OOXML tailEnd triangle, anchor D152→AH152 및 box AI148:AV154를 확인했고 저장된 렌더링 증거에서 화면상 화살표가 오른쪽 박스에 닿는다. 이는 시각 검토 근거일 뿐 논리적 설비 연결 승인은 아니다.
- 애플리케이션: 테스트 후 Spring Boot를 재시작했고 `/api/health` HTTP 200을 확인했다.

## 기술 한계와 미완료 업무

- 제공 자료에 PU-04 공정도 원본이 없어 해당 설비 5개 매핑은 미완료.
- 미매칭 설비 29건과 다중 시트 대상 37개 설비의 수동 분류가 남음.
- Excel 네이티브 렌더링 비교와 도면의 논리적 관계에 대한 담당자 확인이 추가로 필요함. LibreOffice 비교만으로 Excel 렌더링을 완전히 보증하지 않음.
- 운영 로그인/RBAC, 외부 호스팅 및 업무 운영 접근제어는 구현 범위 밖.
- connector anchor/화살표만으로 의미적 설비 관계를 자동 확정하지 않음. 사용자의 관리자 승인 전 후보 상태를 유지.
- 2차 물질수지 계산, 도형 자동 편집, 공정도 자동 수정은 시작하지 않음.

## 남은 선택 검증

- 메모리 여유가 있는 CI/전용 테스트 환경에서 `./scripts/test-local.sh` 단일 실행을 한 번 확인하면 Surefire exit 137 재현 여부까지 해소할 수 있다. 분할 실행 기준의 21개 테스트는 모두 이미 통과했으므로 재실행은 필수 기능 수정이 아니라 통합 실행 환경의 추가 확인이다.
