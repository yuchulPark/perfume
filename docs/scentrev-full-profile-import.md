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

Rerun the same explicitly selected verified brand from offset zero. Existing perfume IDs and Brand relationships are preserved using the current public-ID/slug conflict checks. Supplied values update managed rows; missing/null/blank optional fields retain known prior values. Zero is a real supplied value. Existing child relationships, opinions, similarities, metric rows, and source snapshots are retained even when a later response is sparse. Stable keys prevent duplicate rows; similar-reference keys can upgrade from slug to public ID without replacing the row. No deletion/reconciliation occurs.

A repeat of an identical response adds no snapshot or relationship duplicates. A changed response adds a historical source snapshot and updates supplied normalized values; this counts as a perfume update even if its core identity fields did not change. Existing final perfumer/note/accord link counters retain their original semantics; new extension rows do not pretend to be Phase-1 link counts. `totalMcpCalls` includes optional wear requests.

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

Validation completed: offline Maven `package` succeeded with **458 tests, 443 passed, 15 gated live/manual tests skipped, zero failures/errors**. This includes 20 added offline cases and the unchanged previous cases. The JSONB format-mapper test verifies long decimals and BIGINT counts through Hibernate JSON serialization/deserialization without JDBC. Generated DDL retains all original core tables/columns except additive `image_url`, all nine original UNIQUE constraints, all seven original foreign keys, and the existing indexes. No standalone `perfume_id` index was introduced. Build/schema logs and the task-baseline file audit are under `target/phase1-validation/full-profile-*`; curated JSON, source labels, `application.properties`, and `pom.xml` are unchanged.
