# Single-brand Phase 1 import

`ScentRevBrandPhase1ImportService.importBrand("creed")` discovers the complete current brand catalog first, then fetches and imports each unique canonical fragrance slug sequentially. There is no all-brand traversal, automatic runner, endpoint, scheduler, retry loop, or Phase 2 persistence.

## Verified MCP contract

The connected `search_fragrances_filtered` metadata and real Creed pages at offsets 0 and 10 were inspected before implementation. The exact request is:

```json
{
  "filter_brand_slug": "creed",
  "min_rating_votes": 0,
  "result_limit": 10,
  "result_offset": 0
}
```

The response uses `results`, `truncated`, `offset`, `limit`, and `total_returned`. Search rows contain `fragrance_slug`, `public_id`, and `name`; `brand_slug` is optional and was absent in the inspected compact rows. The DTO ignores ranking metrics, UI data, and `next_cursor`.

Start at offset 0. While `truncated=true`, request the returned `offset + limit` (normally 0, 10, 20, ...). Stop immediately when `truncated=false`, including on an empty final page. The implementation uses neither cursors nor a fixed catalog count. An earlier observation of approximately 112 fragrances is not an assertion.

Missing/inconsistent pagination metadata, unexpected offsets/limits, provider `partial=true`, repeated pages (including reordered pages), truncated pages with no new canonical slugs, and conflicting public IDs for one slug fail discovery. The safety cap is 1,000 pages, at most 10,000 raw results for one brand.

## Import and failures

Deduplication uses only `fragrance_slug`, preserving first-seen order and counting duplicate search rows. If search supplies a brand slug it must match the requested brand. Every fetched profile must have exactly the requested brand and fragrance slug, and match the search public ID when supplied, before the existing mapper is invoked.

The orchestration method uses `NOT_SUPPORTED`, suspending any caller transaction. The existing `ScentRevPhase1ImportService.importProfile` keeps its unchanged per-profile transaction. Remote calls occur outside those transactions. A later profile, identity, persistence, or commit failure stops the run and preserves earlier commits. Exceptions report stage, brand, failed slug/offset, discovery counts, and successfully processed count without attaching raw provider/SQL causes or credentials. Discovery failures occur before any persistence.

The in-memory summary retains immutable deduplicated discovery results, page/raw/unique/duplicate counts, and the successfully processed count. New and reused rows are counted together because the existing mapper does not expose that distinction.

An explicit rerun discovers and processes the brand again; the unchanged idempotent mapper reuses existing rows and adds missing associations. This is effective recovery through idempotency, not a stored resume checkpoint. There is no deletion or automatic retry. Concurrent imports are outside this task's scope; existing unique constraints still protect the database.

## Offline validation

Run `mvn -DskipTests compile`, then ordinary `mvn test`. In this workspace the cached equivalent is:

```powershell
& '.\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd' -B -o '-Dmaven.repo.local=C:\dev\projects\perfume\target\phase1-validation\repository' -DskipTests compile
& '.\target\phase1-validation\apache-maven-3.9.16\bin\mvn.cmd' -B -o '-Dmaven.repo.local=C:\dev\projects\perfume\target\phase1-validation\repository' test
```

Normal tests mock network/persistence boundaries, and the existing application-context test disables JDBC metadata/DDL access. All live tests require explicit gates and are skipped normally. The transaction test uses real Spring interceptors with an in-memory transaction manager and mocked repositories; it checks independent commit, item rollback, and ambient transaction suspension without PostgreSQL.

## Eclipse: read-only Creed discovery

1. Refresh the project, then **Maven > Update Project** if needed. Use Java 17.
2. Select `src/test/java/com/perfume/scentrev/integration/ScentRevCreedDiscoverySmokeTests.java`, then **Run As > JUnit Test** once to create its launch configuration. With the gates unset this launch skips the test.
3. Open **Run > Run Configurations > JUnit**, and select that class's configuration. On **Environment**, add `SCENTREV_API_KEY` with your real key. On **Arguments > VM arguments**, add `-Dscentrev.creed-discovery-live-test=true`.
4. Run that configuration. It performs Creed filtered search only, with no profile calls, Spring application context, database connection, or writes. Read the console's actual page/raw/unique/duplicate counts. Success does not require exactly 112 results.

## Eclipse: real Creed database import

1. Select `src/test/java/com/perfume/scentrev/integration/ScentRevCreedDatabaseSmokeTests.java`, then **Run As > JUnit Test** to create its separate launch configuration. With gates unset it skips.
2. In **Run > Run Configurations > JUnit**, select this configuration. On **Environment**, set both `SCENTREV_API_KEY` and `DB_PASSWORD`. Set `DB_URL` and `DB_USERNAME` only if your database differs from the existing application defaults. On **Arguments > VM arguments**, add `-Dscentrev.creed-db-live-test=true`.
3. Run that configuration. The test overrides Hibernate to `validate` and SQL initialization to `never`. It performs one Creed discovery/profile/import pass, verifies every discovered slug and its brand/public ID, association uniqueness, and retention of previous Creed rows/association IDs including Aventus. There is no cleanup, schema recreation, or test rollback; successful rows remain committed.
4. Review the console summary. Manually rerunning this same configuration should reuse existing rows through the existing importer. The test never automatically imports the complete brand twice.

Each live class requires its own flag, so enabling either Creed flag does not enable the old Aventus or other live smoke tests. Keep keys/passwords in your local launch environment; do not put them in source, VM arguments, logs, or shared launch files. No credentials are hard-coded or printed by these tests.
