# Full ScentRev catalog Phase 1 import

Historical bulk workflow retained for reference. The supported application architecture is now [local-first on-demand search and selected-perfume caching](scentrev-on-demand-import.md). Full-catalog crawling is not part of that flow; do not enable this document's bulk live tests for normal application use.

`ScentRevCatalogPhase1ImportService.importCatalog()` discovers all canonical brands, deduplicates/validates them, and calls the unchanged `ScentRevBrandPhase1ImportService.importBrand(slug)` sequentially for each brand.

The existing brand importer remains responsible for filtered fragrance discovery (`min_rating_votes=0`), slug deduplication, profile fetching, brand/fragrance/public-ID checks, and delegation to the unchanged `ScentRevPhase1ImportService`. The existing mapper alone handles the eight Phase 1 entities, canonical identifier conflicts, optional sections, and additive persistence. The proven Aventus and Creed paths remain unchanged.

## Verified list_brands contract

The connected MCP metadata and real pages at offsets 0 and 10 were inspected before implementation. `list_brands` accepts `result_limit` (hard cap 10), `result_offset`, optional `next_cursor`, optional `query`, and optional `include_unbranded`. Its response uses:

```json
{
  "brands": [{"brand_slug": "creed", "brand_name": "Creed", "fragrance_count": 112}],
  "limit": 10,
  "offset": 0,
  "total_returned": 1,
  "truncated": true,
  "next_cursor": "optional-opaque-cursor"
}
```

This illustrates the verified field names, not a fixed brand/fragrance count or a copied Creed listing page. `total_returned` is the number of brands in that page, not the catalog total. `fragrance_count` is not used to control imports or calculate discovery totals.

The production call sends exactly:

```json
{"result_limit": 10, "result_offset": 0, "include_unbranded": false}
```

There is no query filter. The unbranded/unknown bucket is excluded because the pipeline requires a valid canonical brand slug. Every canonical named brand is traversed. The small typed response maps `brands` with `brand_slug`/`brand_name`, `truncated`, `offset`, `limit`, and `total_returned`; unrelated fields and cursors are ignored.

Start at offset 0, advance by the returned `offset + limit` while `truncated=true`, and stop immediately at `truncated=false`. The current schema independently confirms this offset path for `list_brands`; fragrance-search pagination was not assumed. Cursors are never sent or used as continuation tokens.

Brand slugs are stripped of surrounding whitespace, then validated as lowercase ASCII alphanumeric segments separated by single hyphens, with the existing 255-character database bound. Display names, including non-Latin names, are preserved. First occurrence wins for duplicate slugs, maintaining provider order.

Missing/inconsistent metadata, unexpected offsets/limits, oversized pages, repeated pages (including reordered pages), and truncated pages with no new brands abort discovery before any imports. The safety ceiling is 10,000 pages/100,000 raw brands, not a historical catalog count. No automatic retry is performed.

## Transactions, failures, and reruns

Both catalog and existing brand orchestration use `NOT_SUPPORTED`, suspending a caller's ambient transaction. Remote discovery/profile calls run outside database transactions. Each existing mapper call retains its own transaction and commit. A brand failure preserves earlier successful fragrances in that brand and all earlier brands; later brands still run.

The catalog records each failed brand with its canonical slug, one-based position/total, a safe category/message, and any known discovery/commit progress. It never logs raw exception messages, causes, SQL/provider payloads, authorization headers, keys, or passwords. Brand-list discovery failures abort the catalog before persistence because the complete brand list is unknown. Runtime failures inside an individual brand are recorded and processing continues; JVM errors are not swallowed.

A valid brand returning zero fragrances is a successful empty brand. The existing importer makes no database writes for it, so it may have no local Brand row.

The immutable in-memory result reports raw/unique/duplicate brand counts, pages, attempted/succeeded/failed brands, successful brand results, failed-brand summaries, and fragrance discovery/unique/processed totals. Fragrance totals sum per-brand counts, including reported partial progress in failed brands; they do not perform global identity merging. Unknown progress from an unexpected exception contributes zero and is explicitly reported as unavailable. Processed counts include both new and reused rows.

Rerunning rediscovers brands and fragrances and delegates to the existing idempotent mapper. Previously stored metadata is retained and only missing masters/associations are added. There is no persistent checkpoint, history table, deletion, reassignment of existing perfumes, automatic retry, or metadata-refresh redesign. Existing canonical ID/slug conflict checks remain active; names are never used to merge perfumes.

SLF4J logs one discovery summary, brand start/completion/failure with position/total, and a final summary. There is no note/accord-row logging.

## Offline validation

Run Java 17 compilation (`mvn -DskipTests compile`) and ordinary `mvn test`. In this workspace the cached Maven equivalent is:

```powershell
& '.\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd' -B -o '-Dmaven.repo.local=C:\dev\projects\perfume\target\phase1-validation\repository' -DskipTests compile
& '.\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd' -B -o '-Dmaven.repo.local=C:\dev\projects\perfume\target\phase1-validation\repository' test
```

Ordinary tests use mocked MCP and repository boundaries, with the existing context test disabling JDBC metadata/DDL. Catalog transaction tests use real Spring interceptors and the existing lightweight in-memory transaction strategy, not PostgreSQL or H2. All live tests require their own explicit gates and skip normally; neither catalog flag enables the old Aventus/Creed tests.

## Eclipse: read-only complete brand discovery

1. Refresh the project, use Java 17, and run **Maven > Update Project** if needed.
2. Select `src/test/java/com/perfume/scentrev/integration/ScentRevCatalogBrandDiscoverySmokeTests.java`, then **Run As > JUnit Test** to create its launch configuration. With gates unset it skips.
3. Open **Run > Run Configurations > JUnit**, select that class, and on **Environment** set `SCENTREV_API_KEY`. On **Arguments > VM arguments**, add `-Dscentrev.catalog-discovery-live-test=true`.
4. Run that configuration. It calls `list_brands` only, fully paginates, and prints raw/unique/duplicate/page counts plus the first/last three slugs. It creates no Spring application context, fragrance requests, database connection, or writes. No brand total is hard-coded.

## Eclipse: full real database import

This is an intentionally large run: approximately one listing call per ten brands, fragrance-search pages for every brand, and one profile request per unique fragrance within a brand. It can take a long time. It makes one complete import pass, never an automatic second pass.

1. Select `src/test/java/com/perfume/scentrev/integration/ScentRevCatalogDatabaseSmokeTests.java`, then **Run As > JUnit Test** to create a separate launch configuration. With gates unset it skips.
2. Open that class under **Run > Run Configurations > JUnit**. On **Environment**, set both `SCENTREV_API_KEY` and `DB_PASSWORD`. The existing PostgreSQL URL/username are used; set `DB_URL`/`DB_USERNAME` only if your configured database differs from the application defaults.
3. On **Arguments > VM arguments**, add `-Dscentrev.catalog-db-live-test=true`, then run this configuration explicitly.
4. The test uses Hibernate `validate`, SQL initialization `never`, and no test transaction/cleanup. Successful rows remain committed even if the final test fails. It reports catalog counts and each failed brand before final assertions.
5. The test verifies successful-brand row uniqueness (empty brands may have zero rows), every successful brand's discovered perfume identity/ownership, global perfume slug/non-null public-ID uniqueness, valid Brand references, three association uniqueness rules, and retention of previous Creed rows/association IDs and Aventus. Creed's expected slugs come from this same catalog run; no extra discovery/profile requests and no permanent 112 assertion are used.
6. Any recorded brand failure fails the JUnit test after reporting the summary, exposing gaps while retaining successful data. A later explicit manual rerun revisits prior brands safely through existing idempotency.

Keep real credentials in local launch environment variables, never source, VM arguments, logs, or shared launch files. Implemented validation does not imply that either live test was executed by Codex; live execution requires credentials in that process.

## Deferred work

No Phase 2 tables/metrics/images/collections, controller/frontend, scheduler/startup import/background refresh, checkpoint/history tables, deletion synchronization, Docker/Jenkins/Nginx/deployment/CI changes, concurrent fan-out, provider rate-limit assumptions, or refresh/update policy changes are included.
