# ScentRev on-demand Phase 1 cache/import

The supported application flow is:

```text
LOCAL SEARCH
    -> REMOTE SEARCH ONLY ON MISS
    -> USER SELECTS ONE CANDIDATE
    -> LOCAL EXACT SLUG LOOKUP
    -> REMOTE PROFILE ONLY ON MISS
    -> EXISTING PHASE 1 IMPORT
    -> LOCAL CACHE
```

Previously imported Creed/Aventus data is used directly. Search candidates are display records, not JPA entities, and are never automatically persisted. Cached perfumes return their local version with zero profile requests. There is no automatic refresh or prefetch.

## Public application API

```java
PerfumeSearchResult search(String query, int limit);
Perfume getOrImportBySlug(String fragranceSlug);
```

These methods belong to `ScentRevOnDemandPerfumeService`. This layer has no dependency on catalog, brand-discovery/import, or batch orchestration. There are no controllers, schedulers, startup import runners, or Phase 2 writes.

Search strips surrounding whitespace and requires at least two Unicode characters and a caller limit of 1–10. It first performs a bounded, case-insensitive JPA substring query against perfume name, brand name, or canonical fragrance slug, ordered by perfume name and row ID. SQL LIKE wildcard characters in the user query are escaped and parameters are bound. A scalar projection returns only the five display fields, with no description/entity graph or full catalog loaded. Any nonempty local result ends the search; remaining slots are not filled with remote results. A database failure is an error, not a remote-search miss.

On a local miss, exactly one `search_fragrances` call is made. Results are deduplicated by exact canonical fragrance slug in first-seen order and bounded to the requested limit. One bounded repository query identifies any candidates already cached by slug. Each application candidate exposes `fragranceSlug`, `publicId`, `name`, `brandName`, `brandSlug`, `cached`, and `source` (`LOCAL` or `SCENTREV`). A remote candidate can have `cached=true` while retaining `source=SCENTREV`.

Selection strips surrounding whitespace, converts the requested slug to lowercase, and requires a canonical ASCII slug of at most 255 characters (`[a-z0-9]+(?:-[a-z0-9]+)*`). The existing row is returned immediately if present. Otherwise one profile is fetched, its identity must match the requested canonical slug exactly, its public ID must be present, and its brand slug must be valid. The existing Phase 1 importer is invoked once; the committed perfume is then reloaded by slug and returned.

## Verified connected MCP metadata contract

The current connected `search_fragrances` tool declaration was inspected before implementation, including its declared response schema. No live provider request was executed during this task, so the declaration has not been independently checked against a new production response here.

Its supported inputs are:

| Argument | Contract |
| --- | --- |
| `query` | Required search text; minimum 2 characters; name/brand/slug-prefix lookup |
| `limit` | Optional provider argument, range 1–10; always supplied by this client |
| `result_offset` | Optional 0-based offset; omitted in the new flow |
| `next_cursor` | Optional opaque continuation; omitted in the new flow |
| `filter_brand_slug` | Optional brand filter; omitted in the new flow |
| `verbosity` | Optional `compact`, `standard`, or `full`; omitted in the new flow |
| `conversation_id` | Optional server-issued conversation metadata; not supplied by this new client method |

The new typed client method sends exactly:

```json
{"query":"Aventus","limit":5}
```

The limit parameter for this tool is **`limit`**. The previous filtered discovery's `result_limit` parameter is a different tool contract and is not copied into this method.

The declared response uses `results`, whose entries require `fragrance_slug`, `public_id`, and `name`; `brand_name` and `brand_slug` are optional. It also declares optional rating/display metadata, pagination (`truncated`, `offset`, `limit`, `next_cursor`, `total_returned`), errors, unresolved inputs, warnings, and presentation/conversation structures. `ScentRevSearchResponse` maps only `results` and the five candidate display fields. Unknown fields are ignored at both response and candidate levels. MCP structured content is preferred; valid textual JSON is the fallback. Missing arrays/required text fields, malformed JSON, `isError=true`, or provider error payloads produce safe failures. Error payloads, including `no_results`, are surfaced as provider errors; a normal empty `results` array returns an empty search result.

`ScentRevMcpClient.searchFragrances(query, limit)` makes one tool invocation. It never follows `truncated` or a cursor, never paginates, and never retries automatically. Existing profile requests, Bearer authentication, SDK initialization/session reuse, and error handling remain intact. Protocol initialization is separate from the one search/profile tool invocation.

## Request guarantees

| Operation | Search tool calls | Profile tool calls | Imports |
| --- | --- | --- | --- |
| Local search hit | 0 | 0 | 0 |
| Remote search fallback | 1 | 0 | 0 |
| Cached selected slug | 0 | 0 | 0 |
| Uncached selected slug | 0 | At most 1 | At most 1 selected perfume |

No `list_brands`, filtered search, brand import, catalog pagination, background preload, or bulk execution occurs in this flow. Existing catalog/batch code and operational tables remain in place as historical tools; full-catalog crawling is no longer normal application architecture. Their live execution flags are not enabled by the new tests or service.

## Transactions, identifiers, and concurrency

Application orchestration uses `NOT_SUPPORTED`, so even an ambient transaction is suspended around provider requests. Database-only cache reads use short `REQUIRES_NEW` read-only transactions. Local search returns scalar data; exact selection initializes the perfume's lazy brand while the read transaction is open.

The one selected perfume import uses a short, write-enabled transaction shared with the unchanged `ScentRevPhase1ImportService` mapper. Public-ID ownership is checked before the mapper, and its returned slug/public-ID/brand are checked before commit. A detected identity race rolls back that selected transaction, including associations. After commit the service reloads by exact slug. The mapper's existing identifier rules, unique constraints, optional associations, and Phase 1 persistence remain unchanged.

A reference-counted in-process lock serializes requests for the same canonical slug. After the first successful import, waiting callers find the cache and issue no profile request. Lock entries are removed when no caller holds/waits on them. A failed caller releases its lock; a later caller can explicitly retry and may make its own single profile request. There are no automatic retry loops.

Database unique constraints remain the ultimate protection across instances or other import paths. If a unique-constraint race rolls back the selected import, a fresh read returns a matching committed winner when available, without another provider request. A conflicting winner or a race involving a shared master row surfaces a safe, recoverable persistence error. No distributed coordination is added. A future multi-instance deployment may need stronger coordination to avoid duplicate remote requests or master-row races; this task does not guarantee one profile request globally across instances.

## Errors

`ScentRevOnDemandException.getFailureType()` distinguishes invalid query/limit/slug, missing provider configuration, authentication failure, provider search failure, profile lookup failure, canonical identity mismatch, identifier conflict, and persistence/cache failure. Messages are fixed application text. Raw provider/SQL messages, API keys, Authorization headers, DB passwords, and secret-bearing causes are not attached or logged. Failures are surfaced; a failed search does not trigger profile requests, and a failed selection does not start an unrelated import.

## Offline validation and gated smoke tests

Ordinary tests mock the SDK/repository boundaries. They exercise exact provider request counts, first-seen deduplication, cached-candidate flags, local query normalization/bounds, real Phase 1 mapping, conflicts, concurrency, safe failures, and actual Spring transaction interceptors with a recording transaction manager. The existing no-JDBC application-context test validates repository JPQL creation. No ScentRev or PostgreSQL connections are needed for those tests. Existing Phase 1/Aventus/Creed tests remain intact.

Two optional tests are available; neither is run automatically:

1. **Read-only provider search:** run only `ScentRevOnDemandSearchSmokeTests` as an Eclipse JUnit test. In **Run Configurations > Environment**, set `SCENTREV_API_KEY`. In **Arguments > VM arguments**, set `-Dscentrev.ondemand-search-live-test=true`. It sends one `Aventus` search with limit 5, prints at most five candidates, and uses no Spring/JPA/PostgreSQL, profile request, or import.
2. **Existing cached DB path:** run only `ScentRevOnDemandDatabaseSmokeTests`. Supply `SCENTREV_API_KEY` and `DB_PASSWORD` in the environment and `-Dscentrev.ondemand-db-live-test=true` in VM arguments. Set `DB_URL`/`DB_USERNAME` only if needed for the intended database. The test validates the existing eight Phase 1 tables, requires the previously imported `creed-aventus` row, tests cached selection and local `Aventus` search, and checks zero provider/mapper interactions. Provider and mapper beans are mocked to prohibit network/import calls even in this opt-in DB test. It does not require the historical operational tables and performs no schema updates or cleanup.

The uncached path is covered offline; no brittle live uncached fragrance assumption is introduced. Do not enable the historical catalog discovery/import/batch/manual flags when running these tests. No schema migration, entity mapping change, application configuration change, frontend, controller, deployment, or Phase 2 work is required for the on-demand layer.
