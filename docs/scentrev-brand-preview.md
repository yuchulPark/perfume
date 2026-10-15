# ScentRev 단일 브랜드 후보 개수 Preview

기본 비활성화된 관리자 CLI다. 기존 단일 브랜드 Import를 실행하지 않고 `searchFragrancesFiltered(brandSlug, offset)`만 호출한다. 상세 Profile·Wear Summary·노트·어코드·성능 API, DB 쓰기, AI 임베딩, 공개 REST API, 자동 재시도는 없다.

이번 작업은 로컬 코드와 Mock 검증까지다. 아래 명령은 실행하지 않았다. 운영자가 API 사용을 승인한 후 준비된 관리자 환경에서 수동 실행한다. Git Commit/Push, Jenkins 실행, Ubuntu 접속, 운영 DB/Compose 변경은 수행하지 않는다.

## 실행

Java 17과 빌드된 JAR를 사용한다. 현재 Import용 개발 환경과 같은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SCENTREV_API_KEY`를 실행 환경에 설정한다. 키·비밀번호를 CLI 인수, 문서, 로그에 넣지 않는다. 선택할 브랜드는 curated VERIFIED/enabled 상태이고 DB에 이미 등록되어 있어야 한다. 누락된 브랜드를 생성하거나 API로 브랜드를 자동 탐색하지 않는다.

```bash
java -jar target/perfume-0.0.1-SNAPSHOT.jar \
  --scentrev.brand-preview=true \
  --scentrev.brand-preview.brand-slug=memo-paris \
  --scentrev.brand-preview.max-pages=5 \
  --scentrev.brand-preview.api-call-budget=5 \
  --scentrev.brand-import=false \
  --perfume.ai.embedding.batch.enabled=false
```

Windows PowerShell에서는 위 줄을 한 줄로 입력한다. Eclipse에서는 별도 Preview 실행 구성을 만들고 **Program arguments**에 다음을 넣는다. Preview 활성화는 정확한 `--scentrev.brand-preview=true` 프로그램 인수가 필요하다. VM arguments의 `-Dscentrev.brand-preview=true`나 환경변수만으로는 활성화하지 않는다. 기존 Import 실행 구성은 유지하고 Preview 구성에는 Import/임베딩의 true 옵션을 넣지 않는다.

```text
--scentrev.brand-preview=true
--scentrev.brand-preview.brand-slug=memo-paris
--scentrev.brand-preview.max-pages=5
--scentrev.brand-preview.api-call-budget=5
--scentrev.brand-import=false
--perfume.ai.embedding.batch.enabled=false
```

Preview는 웹 서버 없이 실행하고 결과를 출력한 뒤 application context와 연결을 닫는다. 페이지/예산 제한에 따른 부분 결과는 정상 종료한다. API 오류·잘못된 응답·인터럽트는 부분 결과를 출력하고 실패 종료한다. 브랜드/설정 오류는 외부 목록 호출 전에 중단한다.

현재 Compose의 웹 backend는 `SCENTREV_API_KEY`가 빈 문자열이며 외부 API 호출용 네트워크도 제공하지 않는다. 위 명령이 그 컨테이너에서 바로 작동한다고 가정하지 않는다. 기존 Eclipse/관리자 Java 환경을 이용하며, 이번 기능을 위해 웹 컨테이너에 인증정보를 추가하거나 Compose/Jenkins를 변경하지 않는다. 운영 DB를 사용하는 읽기 전용 Preview도 실제 실행은 별도 승인 후 진행한다.

## 호출 예산과 일일 사용량

| 속성 | 기본값 | 허용 범위 / 의미 |
| --- | --- | --- |
| `scentrev.brand-preview` | 비활성 | 명시적인 true 프로그램 인수로만 실행 |
| `.brand-slug` | 없음 | 정확히 한 canonical slug; 복수형·목록·all·와일드카드 거부 |
| `.max-pages` | 5 | 1–100; 명시적으로 늘려야 5페이지 초과 가능 |
| `.api-call-budget` | 선택한 max-pages | 0–100; 관리자가 정한 이번 Preview의 목록 호출 예산 |

속성의 점으로 시작하는 이름 앞에는 `scentrev.brand-preview`를 붙인다. 두 제한 중 먼저 도달하는 값에서 멈춘다. API 호출 직전 예산을 검사하고 먼저 한 회를 예약한다. 실패한 요청도 포함하며, 초기화/설정 실패로 실제 도구 호출에 도달하지 못한 시도도 보수적으로 한 회로 계산한다. 0이면 로컬 브랜드/건수만 읽고 목록 호출은 하지 않는다. 순차 실행하며 자동 재시도·대기 힌트에 따른 재호출·다중 브랜드 조회를 하지 않는다.

일일 300회는 운영자가 제공한 현재 계정 조건이다. **이번 실행의 예산과 계정의 실제 일일 잔여량은 별개다.** 현재 프로젝트 DTO와 제공된 `search_fragrances_filtered` 등록 명세에서는 공식적인 remaining/일일 잔여량 필드를 확인하지 못했다. `error.retry_after_ms`는 대기 힌트이며 잔여량이 아니다. 과거 오류에 등장한 최상위 `quota`의 의미·단위도 검증되지 않았다. 계정별 인증 응답/대시보드는 이번 작업에서 조회하지 않았다. 따라서 `dailyRemaining = unknown`으로 표시하고 임의의 quota 값을 잔여량으로 해석하지 않는다.

관리자는 계정에서 확인한 최신 사용량과 다른 작업의 예정 소비량을 고려하여 `api-call-budget`을 직접 정한다. 이 프로그램은 다른 프로세스의 Import/Preview 소비를 합산하거나 계정 전체를 잠그지 않는다. 도구 외 MCP 초기화 등의 프로토콜 요청에 대한 제공자의 과금 규칙도 검증하지 않았다. `listApiCalls`는 애플리케이션 목록 메서드의 시도 횟수이며 정확한 청구량을 보장하지 않는다. `RATE_LIMIT`/HTTP 429나 다른 오류가 발생하면 즉시 중단한다.

예를 들어 max-pages=10, api-call-budget=3이면 3회까지만 시도한다. max-pages만 6으로 지정하고 budget을 생략하면 해당 실행 예산도 6이다. **재실행은 항상 offset 0부터 시작하며 이미 봤던 페이지도 다시 소비한다.** 60개 후보를 기본 설정으로 5회 조회한 후 6페이지로 재실행하면 정상 응답 기준 목록 호출은 합계 11회다. 이전 Preview 페이지를 저장하거나 재개하는 캐시는 없다.

## 후보와 완료 상태

| 출력 | 의미 |
| --- | --- |
| `brandName`, `brandSlug` | 기존 curated 정책으로 검증한 선택 브랜드 |
| `dbPerfumeCount` | 현재 DB의 해당 Brand ID 향수 개수; 원본을 변경하지 않음 |
| `rawCandidateCount` | 검증되어 수용한 목록 페이지의 행 수 합계 |
| `candidateCount` | slug와 public ID를 기준으로 중복 제거한 후보 수 |
| `duplicateCount` | rawCandidateCount - candidateCount |
| `foreignBrandCandidateRows` | 검색 응답에서 다른 brand_slug가 명시된 행 수; 중복 포함 |
| `pagesFetched` | 검증을 통과하여 수용한 페이지 수 |
| `listApiCalls` | 실패를 포함한 목록 호출 시도 수 |
| `completed` | 목록 API에서 검증된 마지막 페이지의 truncated=false를 확인했는지 |
| `hasMore` | 마지막으로 수용한 페이지의 truncated; 유효한 페이지가 없으면 unknown |
| `stopReason` | COMPLETED / MAX_PAGES / API_BUDGET / API_ERROR / INVALID_PAGE / INTERRUPTED |
| `nextOffset` | 부분 결과에서 다음 또는 실패한 요청의 위치; 자동 재개 기능은 아님 |
| `failureType`, `retryAfterMs` | 기존 MCP의 안전한 오류 분류/대기 힌트; 대기·재호출하지 않음 |

`total_returned`는 **페이지 행 수와의 일관성 검사**에만 사용하며 브랜드 전체 개수로 합산/표시하지 않는다. 페이지당 최대 10개이고 다음 offset은 응답의 `offset + limit`이다. `truncated=true`이면 10개 미만이어도 계속할 수 있지만 예산에 도달하면 멈춘다.

필수 페이지 정보 누락, 요청과 다른 offset/limit, partial=true, 잘못된 total_returned, 10개 초과, malformed slug/public ID, 같은 slug의 상충하는 ID, 순서만 바꾼 반복 페이지, 동일 public ID로 slug만 바꾼 반복 페이지, truncated 페이지의 진행 정체는 INVALID_PAGE로 중단한다. 잘못된 페이지의 행은 기존 부분 결과에 합치지 않는다. 다른 slug의 같은 public ID와 나중에 확인된 같은 slug의 ID도 중복 제거에 반영한다.

다른 브랜드 행은 검색 후보에 포함하되 별도 표시한다. 브랜드 메타데이터가 없는 후보도 프로필로 검증하지 않는다. **completed=true여도 후보 전체의 소속을 확인한 브랜드 향수 총수는 아니다.** 이 값은 해당 검색 API 목록 조회의 완료를 의미한다. 오류 또는 호출 한도로 끝났다면 관측한 후보 수만 알 수 있으며 전체 후보 수/전체 Import 예상량은 unknown이다.

## 예상 Import 호출량

두 추정치는 비교용이며 서로 합산하지 않는다. DB 건수와 후보의 정확한 중복 관계를 알 수 없어 단순히 candidateCount에서 dbPerfumeCount를 빼지 않는다.

1. `basicImportCallsForObservedCandidates = pagesFetched + candidateCount`: 수용한 목록 페이지 수 + 그 후보들의 상세 프로필 1회씩이라는 단순 가정이다. 완료된 경우에만 같은 값을 `estimatedFullImportCalls`에 출력한다. 부분 결과의 값은 관측 범위 계산일 뿐 전체 호출량이나 보장된 하한이 아니다.
2. `referenceCallsAssumingAllObservedCandidatesNew = ceil(candidateCount × 71 / 60)`: 사용자가 보고한 Santa Maria Novella의 신규 60개/71회 사례를 모두 신규라는 가정으로 환산한다. 약 1.18은 관측 비율이다. 71회 중 60회가 반드시 프로필 호출이었다고 분석하거나 가정하지 않았다. 실제 호출 구성은 당시 Import 로그 없이는 확정할 수 없다.

DB 중복, 외부 브랜드 후보, 응답 누락에 따른 추가 Wear Summary 등으로 실제 사용량이 달라질 수 있다. 추정치는 보장된 호출 횟수·정확한 과금량이 아니다. **향후 Import를 다시 수행할 때의 추정량이며, 이미 소비한 Preview 호출은 별도로 더해야 한다.** Preview가 실행 중인 동안에는 실제 Import를 함께 실행하지 않는다.

다음은 Mock의 **가상 44개/DB 12개** 예시이며 Memo Paris나 특정 실제 브랜드의 조회 결과가 아니다. 실제 출력에는 예산·원시 후보·중복·실패 정보도 포함된다.

```text
SCENTREV BRAND PREVIEW (list candidates; brand membership NOT profile-verified)
dbPerfumeCount = 12
rawCandidateCount = 44
candidateCount = 44
pagesFetched = 5
listApiCalls = 5
completed = true
hasMore = false
stopReason = COMPLETED
dailyRemaining = unknown (no verified response field)
basicImportCallsForObservedCandidates = 49 (list pages + profile candidates; estimate)
estimatedFullImportCalls = 49
referenceCallsAssumingAllObservedCandidatesNew = 53 (71/60, about 1.18; example only)
```

60개 후보/기본 5페이지의 Mock 결과는 candidateCount=50, completed=false, hasMore=true, stopReason=MAX_PAGES, estimatedFullImportCalls=unknown이다. 정상 빈 마지막 페이지는 candidateCount=0, completed=true다. 첫 요청 오류나 예산 0에서 보이는 후보 0은 completed=false여서 빈 브랜드라는 뜻이 아니다.

## 기존 기능 보호와 구현 위치

`ScentRevBrandPreviewRunner`가 환경 준비 시점에 Import/임베딩 동시 활성화를 거부하고 non-web, `ddl-auto=validate`, SQL 초기화 never, Hikari read-only=true를 최우선 적용한다. 이 검사는 DB/context 시작 전이다. 두 기존 실행 모드의 인수나 동작은 변경하지 않는다. 일반 웹 모드에는 Preview bean이 생성되지 않는다.

`ScentRevBrandPreviewLocalBrand`는 `ScentRevCuratedBrandConfiguration.load()`와 `CuratedBrandDefinition.importable()`의 VERIFIED/enabled 정책을 재사용한다. Import와 같이 정확히 하나의 정의, provider query, 이미 등록된 Brand ID/slug를 요구한다. 제외/미해결/중복 정의와 잘못된 curated 구성은 거부한다. BrandRepository의 조회와 새 count 메서드만 호출하는 짧은 읽기 전용 트랜잭션이 끝난 뒤 MCP 요청을 시작한다.

기존 `ScentRevBrandDiscoveryService`는 전체 조회를 목적으로 하고 Preview의 예산/부분 결과 계약이 없다. 기존 Import 동작을 바꾸지 않기 위해 Preview에만 한도가 있는 목록 순회를 두었다. MCP 요청·응답 DTO·제한 오류 처리는 그대로 재사용한다. Import 서비스와 persistence 서비스는 Preview에 주입하거나 호출하지 않는다.

| 추가/수정 파일 | 역할 |
| --- | --- |
| `src/main/java/com/perfume/scentrev/preview/ScentRevBrandPreviewOptions.java` | 단일 slug와 두 호출 상한 검사 |
| `.../ScentRevBrandPreviewLocalBrand.java` | 기존 curated 정책·로컬 브랜드 검증·DB count, 읽기 전용 트랜잭션 |
| `.../ScentRevBrandPreviewService.java` | 예산을 예약한 목록 호출·페이지 검증·중복 제거·부분 결과 |
| `.../ScentRevBrandPreviewResult.java` | 후보/완료 상태·관측 범위 및 전체 추정치 구분 |
| `.../ScentRevBrandPreviewRunner.java` | 명시적 CLI·실행 모드 격리·안전한 결과 출력 |
| `src/main/java/com/perfume/PerfumeApplication.java` | Preview 사전 검사 등록 및 완료 후 context 종료 추가 |
| `src/main/java/com/perfume/repository/PerfumeRepository.java` | `countByBrand_Id` 읽기 메서드 한 개 추가 |
| `src/test/java/com/perfume/scentrev/preview/*Tests.java` 3개 | 목록/예산·브랜드/읽기 전용·실행 모드 Mock 회귀 |
| 이 문서 | 수동 실행·상태·사용량 추정의 한계 |

SQL·엔티티·기존 Import/MCP·curated 정의·RAG·React·Compose·Dockerfile·Jenkins·운영 .env는 변경하지 않는다. 새 Maven 의존성도 없다. 공개 Preview API나 스케줄러, 새 테이블, DB 마이그레이션이 없다.

## Mock 검증

```bash
./mvnw -o -Dtest='com.perfume.scentrev.preview.*Tests,ScentRevSingleBrandFullProfileImportTests' test
./mvnw -o package
python3 -m unittest discover -s tests/deployment -v
```

실제 ScentRev/DB 호출 없이 Mock 클라이언트·Mock 저장소·메모리 트랜잭션 관리자를 사용한다. 44/60/7/0개, 5/100페이지 상한, 실패 포함 호출 예산, public ID/slug 중복과 충돌, 외부 브랜드, 잘못된/반복/진행 정체 페이지, 모든 MCP 실패 분류, VERIFIED/enabled, 쓰기 0회, 네트워크 중 트랜잭션 없음, 기본 비활성화, DB 시작 전 혼합 모드 차단을 검증한다. 기존 full profile 단일 브랜드 Import와 Jenkins DB 보존 테스트는 변경 없이 회귀 실행한다.

최종 로컬 검증(2026-10-15): Java 17/Spring Boot 3.5.16, 오프라인 Maven `package` 성공. 689개 중 674개 통과, 기존 live/manual 15개 skipped, 실패/오류 0이다. Preview 81개(기본 80개 + 늦게 확인된 ID로 기존 후보가 합쳐져도 새 후보가 있는 truncated 페이지는 계속 조회하는 회귀 1개)와 기존 `ScentRevSingleBrandFullProfileImportTests` 12개가 모두 포함되어 통과했다. Jenkins DB 컨테이너 ID/볼륨/네트워크 보존 등 배포 보호 56개, React 8개, 문서 Bash 블록 2개의 문법 검사도 통과했다. 배포 검사의 외부 명령은 전부 모의 도구이며 실제 Docker/Git/SSH 작업은 하지 않았다.

로그는 `target/phase1-validation/brand-preview-final-package.log`, `brand-preview-deployment-tests.log`다. 기존 Windows Maven wrapper 초기화 문제 때문에 로컬 캐시의 Maven 3.9.16으로 검증했으며 wrapper나 pom.xml은 변경하지 않았다. 기존 tracked 파일은 Application/Repository 2개만 변경됐고 나머지 257개는 작업 전 SHA-256과 일치한다. 실제 계정 할당량, Ubuntu CLI, 운영 DB read-only 적용, 실서버 페이지 구조는 이번 작업에서 검증하지 않았다.
