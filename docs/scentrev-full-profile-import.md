# Full ScentRev profile import

The existing single-brand application importer now requests every supported profile section. The operator runs it explicitly; no automatic catalog import or database migration is added. Static official documentation and connected tool metadata were inspected during implementation. No live tool invocation, real PostgreSQL connection, or fragrance import was performed.

## Provider requests

`get_fragrance_profile` receives:

```json
{
  "fragrance_slug": "<discovered-canonical-fragrance-slug>",
  "sections": ["identity", "performance", "appreciation", "notes", "note_pyramid", "accords", "perfumers", "pros_cons", "reminds_of", "price_value"],
  "include_perfumer_portfolio": false
}
```

If the accepted profile lacks season or time-of-day metrics, the same MCP client makes one sequential `get_wear_summary` request with `{"fragrance_slug":"<same-slug>"}`. Its public ID/slug/brand identity must match the profile before persistence. No extra wear request is made when both kinds of metrics are already present. A confirmed foreign-brand profile is skipped before this fallback, so it causes no wear request or database write. Other provider errors, including missing tool, 401/403/429 and restrictions, still stop immediately without retries. There is no dedicated season, appreciation, or similar-fragrance refetch and no recursive import of related fragrances.

Discovery pagination, duplicate detection, the 100-page/1,000-unique-fragrance safety guards, and the existing request delay are unchanged. The delay applies between discovery, profile, and optional wear requests; calls are sequential. Network calls run outside transactions. Accepted profiles and every new storage row share the existing page `REQUIRES_NEW` transaction; the extension requires that transaction with `MANDATORY`. Earlier committed pages survive later failures.

## Stored data

| Table | Added or retained information |
| --- | --- |
| `perfumes` | Existing description (`TEXT`), release year, review count and identifiers remain; new nullable `image_url TEXT` is updated only when actually supplied. |
| Existing `perfumers`, `notes`, `accords` and join tables | Existing masters, layered note positions, accord percentages/scores, and perfumer metadata remain in use. An actual layered `notes` object is accepted as a pyramid when `note_pyramid` is absent. |
| `perfume_metrics` | One metric per perfume and JSON field path: exact `NUMERIC` score, category, `BIGINT n_records`, reliability, scale, derived source, review count, label and complete metric details (`JSONB`). Includes identity gender/rating, longevity/sillage/projection, appreciation, each returned season, time of day, and price-value metrics. Other returned metric objects are also captured. |
| `perfume_opinions` | PRO/CON, original supplied opinion text, provider source, list position, like ratio/count when present, and full item details (`JSONB`). |
| `perfume_similarities` | Related provider public ID/slug/name, position, like ratio/count and full item details (`JSONB`). References can exist before a related perfume is imported; no extra Brand or Perfume is created. |
| `perfume_unlayered_notes` | Existing Note master plus perfume/position for truly flat notes, without inventing a TOP/MIDDLE/BASE layer. |
| `perfume_provider_payloads` | Received profile and optional wear JSON objects, tool name, SHA-256 content hash, and first received timestamp. Each distinct response is retained; unknown provider fields are included. |

Unique constraints start with `perfume_id`, so no duplicate standalone index on that column is added. Reverse lookups get `perfume_unlayered_notes(note_id)` and `perfume_similarities(related_fragrance_slug)` indexes. All new relationships are lazy and unidirectional with no delete cascade; existing constraints/indexes and primary keys remain intact.

JSONB preserves the complete domain response structurally, including fields not yet normalized. It is not a byte-for-byte HTTP/MCP envelope capture: JSONB does not preserve whitespace or object-key ordering, and authentication headers, SDK envelope/UI transport content, and credentials are not stored. Hibernate uses a dedicated decimal-preserving JSON format mapper, so JSONB reads and later sparse updates retain the exact source precision without changing the application's ObjectMapper or application properties. Identical responses with different object-key order or numeric scale have the same content hash; arrays retain their provider order. Existing distinct snapshots are never deleted. Synthetic test fixtures verify conditional fields but are not evidence of production API availability.

## Availability and limits

Official references: [ScentRev documentation](https://mcp.scentrev.com/docs), [complete public documentation](https://mcp.scentrev.com/llms-full.txt), and the connected `get_fragrance_profile` / `get_wear_summary` tool metadata.

Description, release year, gender/rating metrics, performance, appreciation, notes/pyramid, accords, perfumers, pros/cons, similar fragrances, and price-value are supported. Wear metadata exposes seasons and time-of-day objects. Optional fields may still be null or absent for a particular fragrance. Metric scores retain their source value and precision without converting normalized ratings into five-star scores.

Image URL availability is **unconfirmed** in current documentation/metadata; only an actual textual `identity.image_url` or root `image_url` is stored. No URL is synthesized. `price_value` represents perceived value, not a monetary price or currency. Exact structured occasion categories are unconfirmed; returned metric objects and the complete source JSON preserve any actually supplied information without manufacturing categories. Raw like/dislike/vote dumps are not exposed by the documented metric API. No full review-body endpoint is requested or claimed. Perfumer portfolios remain disabled to avoid expanding the request beyond per-fragrance data.

## Enriching existing imports

With `scentrev.brand-import.skip-existing=false` (the default), rerun the same explicitly selected verified brand from offset zero. Existing perfume IDs and Brand relationships are preserved using the current public-ID/slug conflict checks. Supplied values update managed rows; missing/null/blank optional fields retain known prior values. Zero is a real supplied value. Existing child relationships, opinions, similarities, metric rows, and source snapshots are retained even when a later response is sparse. Stable keys prevent duplicate rows; similar-reference keys can upgrade from slug to public ID without replacing the row. No deletion/reconciliation occurs.

A repeat of an identical response adds no snapshot or relationship duplicates. A changed response adds a historical source snapshot and updates supplied normalized values; this counts as a perfume update even if its core identity fields did not change. Existing final perfumer/note/accord link counters retain their original semantics; new extension rows do not pretend to be Phase-1 link counts. `totalMcpCalls` includes optional wear requests.

## 기존 DB 향수 상세 API 생략 옵션

기존 단일 브랜드 Import에 선택적인 `scentrev.brand-import.skip-existing` 옵션을 추가했다. **기본값 false**이며 미설정/false에서는 위 기존 갱신 동작을 유지하고 사전 중복 조회도 하지 않는다. true일 때만 DB에서 안전하게 확인한 기존 향수의 Profile/Wear 호출과 저장을 모두 생략한다. Preview와 별도인 새로운 Import 프로세스를 만들지 않는다.

기존 Eclipse 실행 구성의 **VM arguments**에 마지막 줄을 추가한다. 아래 예시는 실행하지 않았다. 기존 환경의 DB_URL/DB_USERNAME/DB_PASSWORD/SCENTREV_API_KEY는 실행 환경으로 전달하며 로그나 명령 인수에 비밀 값을 넣지 않는다.

```text
-ea
-Dscentrev.brand-import=TRUE
-Dscentrev.brand-import.brand-slug=penhaligon-s
-Dscentrev.brand-import.delay-ms=250
-Dscentrev.brand-import.skip-existing=true
```

기존 프로그램 인수 방식에는 `--scentrev.brand-import.skip-existing=true`를 추가할 수도 있다. true/false는 대소문자를 구분하지 않으며, 오타·빈 값 등 다른 값은 실행 전 거부한다. 기존 단일 slug·VERIFIED/enabled 정책·non-web 실행·DDL validate/SQL 초기화 never·지연·자동 재시도 금지 정책은 그대로다. Preview 또는 임베딩과 동시에 활성화하지 않는다.

true 실행 흐름:

1. 기존 브랜드 검증과 10개씩 목록 조회, slug/public ID 중복 제거를 수행한다.
2. 해당 페이지의 중복 제거된 후보들을 **한 번의 scalar DB 조회**로 비교한다. 조회는 짧은 `REQUIRES_NEW`, read-only 트랜잭션이며 전체 엔티티/설명/관계를 로딩하지 않는다. 충돌 탐지를 위해 slug 또는 public ID가 맞는 다른 브랜드의 행도 조회한다. 빈 후보 페이지는 조회하지 않는다.
3. 같은 DB 행의 fragranceSlug, public ID(제공된 경우), brandId와 brandSlug가 모두 맞고 검색 응답의 brandSlug도 모순되지 않을 때만 기존 향수로 판정한다. 이름은 판정에 사용하지 않는다. 검색 public ID가 없으면 정확한 slug와 같은 브랜드, 유효한 DB ID/public ID를 확인하고 생략할 수 있다. 이때 DB public ID를 실행 중 중복 검사에도 반영한다.
4. 확인된 기존 향수는 Profile/Wear/페이지 저장 대상에서 제외한다. 기존 향수만 있는 페이지도 다음 offset으로 계속 진행하고, 마지막 truncated=false에서 completed=true로 끝난다. 빈 저장 트랜잭션은 만들지 않는다.
5. 나머지는 기존 Profile·identity 검증·외부 브랜드 제외·필요한 Wear Summary·페이지 검증을 수행한다. 네트워크 호출 동안 DB 트랜잭션을 유지하지 않는다. 신규 저장 시에도 기존의 최종 DB 충돌 조회와 페이지별 독립 트랜잭션을 유지한다.

public ID만 맞고 slug가 다르거나, 두 ID가 서로 다른 행을 가리키거나, 브랜드가 다르면 조용히 생략하지 않는다. 이런 모호한 후보는 기존 프로필 검증 경로로 넘긴다. 해당 프로필이 실제 외부 브랜드이면 기존 skippedForeignBrand로 제외하며, 기존 DB 브랜드를 바꾸지 않는다.

true 저장 경로는 insert-only 보호를 추가로 적용한다. API 호출 중 다른 작업이 같은 향수를 등록했거나, public ID가 없던 검색 후보의 프로필에서 기존 행/slug 변경이 확인되어도 **기존 향수 및 그 상세/관계/응답 이력을 갱신하지 않는다.** 기존 식별자/브랜드 충돌은 그대로 거부하고, 같은 브랜드의 기존 행이 뒤늦게 발견된 경우에도 현재 페이지를 안전하게 실패시킨다. 현재 페이지는 rollback하고 앞서 커밋한 페이지는 보존한다. 자동 재시도나 식별자 교정은 하지 않으므로 원인을 확인하고 재실행 여부를 판단한다. 마지막 DB 검사 직후 동시 INSERT 경쟁은 기존 unique 제약으로 실패할 수 있다.

**true에서는 기존 데이터의 누락된 필드도 보완하지 않는다.** 설명·노트·평가·Wear 정보가 비어 있어도 식별자가 확실히 일치하면 그대로 보존한다. 기존 향수를 갱신하려면 `-Dscentrev.brand-import.skip-existing=false` 또는 옵션을 생략하여 실행한다. true일 때 기존 향수로 확인된 항목은 새 향수 통계나 외부 브랜드 통계에 포함하지 않는다.

추가/기존 FINAL RESULT 항목:

| 항목 | 의미 |
| --- | --- |
| existingSkipped | DB에서 확실히 확인하여 실제 생략한 고유 향수 수; 검색 중복 수와 별개 |
| newCandidates | true일 때 페이지 DB 조회에서 제공된 두 식별자 모두 매칭 행이 없던 후보 수; false에서는 미측정(not evaluated) |
| skipExistingEnabled | 이번 실행의 옵션 값 |
| profilesFetched | 성공적으로 받은 상세 프로필 수; 실패한 프로필 시도는 totalMcpCalls에 포함 |
| skippedForeignBrand | 프로필로 확인한 외부 브랜드 수; existingSkipped와 분리 |
| perfumesInserted / perfumesUpdated | 실제 커밋한 저장 통계; true의 기존 행 UPDATE는 0 |
| pagesCommitted / perfumesProcessed | 실제 저장한 페이지/향수만 집계; DB 읽기 트랜잭션과 existingSkipped는 포함하지 않음 |
| totalMcpCalls | 목록·프로필·Wear의 실패 포함 실제 호출 시도 수; DB 조회는 포함하지 않음 |

newCandidates는 관측한 식별자 기준의 **후보**이며 삽입 보장 수가 아니다. 모호한 매칭 후보는 이 값에 포함하지 않지만 프로필 검증은 수행할 수 있다. ID가 없는 후보가 나중에 기존 행으로 확인되거나, 외부 브랜드로 제외되거나, 페이지가 실패하면 삽입 건수와 달라진다. 실패 보고에는 그 시점까지 확인한 후보/호출/커밋 통계를 유지한다.

가상 데이터 70개 중 같은 브랜드 기존 60개, 신규 10개, 중복/외부 브랜드/추가 Wear 없음의 경우:

```text
FINAL RESULT
discoveryPages             = 7
pagesCommitted             = 1
rawResults                 = 70
uniqueFragrancesDiscovered = 70
profilesFetched            = 10
skippedForeignBrand        = 0
perfumesInserted           = 10
perfumesUpdated            = 0
perfumesUnchanged          = 0
perfumesProcessed          = 10
totalMcpCalls              = 17
existingSkipped            = 60
newCandidates              = 10
skipExistingEnabled        = true
completed                  = true
```

위 pagesCommitted=1은 기존 60개가 먼저, 신규 10개가 마지막 한 페이지에 있는 Mock 사례다. 섞여 있으면 신규가 포함된 저장 페이지 수만큼 달라진다. 목록 7 + 신규 프로필 10 = 17회이며 필요한 Wear 호출은 추가된다. false이면 같은 70개 후보에 목록 7 + 프로필 70 = 77회와 필요한 Wear 호출이 발생할 수 있다. 이는 해당 조건의 예시이며 정확한 계정 청구량을 보장하지 않는다. 실제 API 시도 수는 totalMcpCalls로 확인한다. 기존 71회/신규 60개 관측 사례의 호출 구성은 이번 변경에서 추정하지 않는다.

구현 파일은 기존 Options/Runner/Result/SingleBrandImportService, 기존 페이지 persistence의 insert-only 보호 분기, PerfumeRepository의 페이지 조회, 새 `PerfumeImportIdentityProjection`/`ScentRevExistingPerfumeLookup`이다. 기존 Preview·RAG·SQL·Compose·Jenkins는 변경하지 않는다. 테스트는 기존 full-profile Import 테스트의 생성자 주입 갱신과 새 `ScentRevSkipExistingImportTests`, `ScentRevExistingPerfumeLookupTests`, `ScentRevSkipExistingRunnerTests`, `ScentRevSkipExistingTransactionTests`다.

skip-existing 최종 검증(2026-10-15): Java 17/Spring Boot 3.5.16, Maven 3.9.16 오프라인 `package` 성공. 전체 740개 중 725개 통과, 기존 live/manual 15개 skipped, 실패/오류 0이다. 새 회귀 51개, 기존 full-profile 단일 브랜드 Import 12개, Preview 81개가 모두 통과했다. 배포 보호 56개와 React 8개도 통과했다. 운영 연결 없이 Mock 저장소/API와 메모리 트랜잭션 관리자로 검증했으며, `REQUIRES_NEW`/read-only/롤백은 실제 Spring 트랜잭션 interceptor를 사용했다. 배포 테스트는 외부 Docker/Git/SSH 명령을 모의 도구로 대체한다.

로그는 `target/phase1-validation/skip-existing-package.log`, `skip-existing-focused.log`, `skip-existing-transactions.log`, `skip-existing-deployment-tests.log`다. 기존 Windows wrapper 초기화 문제로 준비된 동일 Maven과 캐시를 사용했으며 wrapper/pom.xml은 변경하지 않았다. 작업 전 파일 SHA-256을 대조해 Preview 및 Application의 앞선 작업을 보존했고 SQL·Compose·Jenkins·RAG 파일도 일치함을 확인했다. 실제 ScentRev 호출, DB 연결/쿼리/데이터 변경, Ubuntu 접속, Git Commit/Push, Jenkins 실행은 수행하지 않았다. 실서버 조회 쿼리·실제 트랜잭션·계정 청구량은 운영 환경에서 검증하지 않았다.

## Eclipse launch and schema prerequisite

First review and manually apply [the additive migration](sql/scentrev-full-profile.sql) to your existing database using your normal SQL tool. It adds one nullable column and five tables; it does not drop, replace, or delete existing data. Codex has **not executed it**. The application runner still uses `ddl-auto=validate` and SQL initialization `never`, so it cannot create these tables on startup. Migration reruns use `IF NOT EXISTS`; preexisting tables with conflicting shapes must be reviewed, not silently repaired by this script.

Refresh the Eclipse project / Maven project, then run `com.perfume.PerfumeApplication` as a Spring Boot App. Example for the existing approved Kierin NYC provider mapping:

```text
-ea
-Dscentrev.brand-import=true
-Dscentrev.brand-import.brand-slug=kierin
-Dscentrev.brand-import.delay-ms=250
```

Set `SCENTREV_API_KEY` and `DB_PASSWORD` in the launch Environment, retaining the current `DB_URL` / `DB_USERNAME` settings. To enrich another brand, change only the singular slug to that brand's verified curated slug. Do not use `brand-slugs`, `all`, a batch/test gate, or `ddl-auto=create`. Existing schema validation and execution gates are unchanged.

## Offline validation

The ordinary Maven suite keeps all live/manual gates disabled. Mocked client tests check exact ten-section arguments, source precision/unknown-field preservation, wear request/session behavior, and safe errors. Storage tests check normalized fields, JSONB snapshots, idempotence, sparse-response preservation, similarity identity upgrades, and existing-perfume enrichment. Single-brand tests verify fallback request count/order/delay, foreign-only page continuation, deduplication, restriction stops, wear identity integrity, and actual Spring transaction propagation with mocked repositories. Hibernate schema generation uses a connection provider that throws on any JDBC acquisition and writes only a local SQL artifact.

Initial full-profile implementation validation: offline Maven `package` succeeded with **458 tests, 443 passed, 15 gated live/manual tests skipped, zero failures/errors**. This includes 20 added offline cases and the unchanged previous cases. The JSONB format-mapper test verifies long decimals and BIGINT counts through Hibernate JSON serialization/deserialization without JDBC. Generated DDL retains all original core tables/columns except additive `image_url`, all nine original UNIQUE constraints, all seven original foreign keys, and the existing indexes. No standalone `perfume_id` index was introduced. Build/schema logs and the task-baseline file audit are under `target/phase1-validation/full-profile-*`; curated JSON, source labels, `application.properties`, and `pom.xml` are unchanged.
