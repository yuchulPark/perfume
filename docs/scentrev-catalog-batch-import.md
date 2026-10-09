# Resumable ScentRev Phase 1 catalog import

Historical operational workflow retained for reference. The supported application architecture is now [local-first on-demand search and selected-perfume caching](scentrev-on-demand-import.md). Batch entities/tables/code remain intact; catalog discovery, batch/manual execution, and full-catalog crawling are not part of the normal application path.

The catalog is planned once and executed in explicitly requested, bounded brand batches. The previously observed 7,548 brands and 127,075 reported fragrances are observations, not constants or completion assertions. This implementation does not start an import at application startup and does not add a scheduler, controller, or Phase 2 data.

## Operational schema

The project currently uses `spring.jpa.hibernate.ddl-auto=update` and has no Flyway/Liquibase integration. Application configuration is unchanged. For a controlled addition, manually apply [the additive SQL](sql/scentrev-catalog-import-progress.sql) in pgAdmin's Query Tool against the intended database before running the gated tests. It adds only two tables and one selection index. The new live tests use `ddl-auto=validate` and `spring.sql.init.mode=never`; they cannot create/recreate the schema themselves. No SQL is applied by ordinary offline tests.

| Table | Stored state |
| --- | --- |
| `catalog_import_runs` | Run ID/status, creation/start/finish/update timestamps, snapshot brand total, sum of known reported fragrance counts, unknown-count brand total, completed/failed totals, nullable active batch ownership token |
| `catalog_import_brand_progress` | Run FK, canonical slug, optional name and reported fragrance count, immutable 1-based catalog position, status, latest recorded processed count, attempt count, concise safe failure message, timestamps |

The progress entity has a required lazy run FK, no cascade, and no reverse collection. IDs use PostgreSQL identity generation. Enums persist as strings. Optional counts use nullable `Long`; unknown counts remain distinct from zero. Names use PostgreSQL `text`, failure messages have a 512-character limit, and timestamps preserve time zones.

Each run has unique `(run_id, brand_slug)` and `(run_id, catalog_position)` constraints. The additional non-unique index is `idx_catalog_progress_run_status_position (run_id, status, catalog_position)`, supporting the pending-work query in catalog order. The unique indexes already cover the leading `run_id`; no redundant standalone `run_id` index is added. All eight Phase 1 tables and their existing keys, constraints, and indexes remain unchanged.

## Service operations and snapshot behavior

`ScentRevCatalogBatchImportService` exposes:

```java
createRun();
getRun(runId);
processNextBatch(runId, maxBrands);
processSelectedBatch(runId, selectedCanonicalSlugs);
retryFailedBrands(runId);
recoverInterruptedRun(runId);
```

`createRun()` completes the existing full `list_brands` discovery outside a database transaction, then saves one ordered, unique snapshot in a single atomic database-only transaction. A listing failure creates no run; a persistence failure rolls back the run and its brand plan together. Canonical slugs, names, reported counts, and catalog positions are persisted. Null counts contribute to the unknown-count total, not to the sum of known counts.

Every later batch uses that run's persisted snapshot. It does not list the brand catalog again and does not resume using a provider offset. Restarting Eclipse/computer does not require a new run. Call `createRun()` again only when deliberately planning a new catalog snapshot.

`processNextBatch` selects at most the supplied number of `PENDING` rows in original catalog order. Sizes from 1 through 100 are accepted; the manual launcher defaults to 10. Completed and failed rows are skipped. The existing brand importer still performs each selected brand's current fragrance discovery, profile fetching, and Phase 1 mapping sequentially. Batch size bounds brands, not fragrances or duration: one large brand can still take a long time.

`processSelectedBatch` supports an explicit bounded set of canonical slugs already present in the same snapshot, in catalog order. It does not create a partial/fake catalog plan. Completed/failed entries in that selection are skipped. The small live smoke test uses this operation.

The run row is locked during claims/progress updates. One ownership token prevents overlapping batches and failed-work retries within the same run. All selected rows are marked `RUNNING` when claimed, including those waiting for their turn. `attempt_count` counts claims, which can include reserved rows that were never reached before interruption. Use one executor at a time against Phase 1 data, including across separate run IDs or legacy import paths: the existing mapper does not guarantee concurrent creation of the same perfume/master row.

## Transactions and failures

Catalog/batch and brand orchestration use `NOT_SUPPORTED`: even an ambient transaction is suspended during discovery and profile calls. A separate progress service uses short `REQUIRES_NEW` transactions for snapshot creation, claim, ownership checks, brand results, final totals, recovery, retry, and inspection. Each existing perfume mapper invocation retains its own transaction and commits independently.

A brand import failure is recorded as `FAILED` with its known partial processed count and a fixed application diagnostic. Later selected brands continue. Raw exception messages, headers, credentials, and provider payloads are not persisted or logged. A progress database failure propagates and stops the batch; its claim remains for explicit recovery rather than being mistaken for a provider/brand failure. Prior perfume commits remain in either case.

The batch result reports selected/completed/failed brands, processed fragrances this batch, safe failed-brand diagnostics, and the cumulative run summary. Console logging includes run ID, catalog position, selected brand slug, reported count, and brand outcome. The existing brand importer also logs every 50 successfully processed fragrances and its final count. This introduces no additional MCP requests.

`processed_fragrance_count` describes the latest recorded attempt, including reused perfumes. It is not a count of newly inserted rows or a lifetime sum of all retries. Requeueing resets that count to zero and retains the attempt count. After a crash, individual perfume commits may exist even though no finished brand result was recorded. Thus this operational total can understate persisted work until that brand is reprocessed.

## States, interruption, and explicit retry

| Run status | Meaning |
| --- | --- |
| `PLANNED` | Snapshot saved; no batch has started. An empty snapshot completes immediately. |
| `RUNNING` | Execution has started and eligible/reserved work remains, or a batch is active. It can remain here between batches. |
| `COMPLETED` | No pending, running, or failed brands, and no active batch. `runComplete()` is true only here. |
| `COMPLETED_WITH_FAILURES` | No pending/running work or active batch, but failed brands remain. This is not successful completion. |

Brand states are `PENDING -> RUNNING -> COMPLETED/FAILED`. Only explicit recovery/retry returns interrupted/failed rows to `PENDING`. There are no automatic or infinite retries.

After a crash or interrupted batch, first confirm the previous executor/process has stopped. Then call `recoverInterruptedRun(runId)`. It resets only `RUNNING` rows to `PENDING`, clears the old ownership token, and preserves completed and failed brands. The revoked token rejects late progress writes and further brand claims; it cannot cancel an already in-flight mapper/client call. Never recover while the old executor remains alive. This precondition also applies when a database outage stops progress recording.

Cooperative thread interruption is checked before claiming and between brands. It leaves reserved rows recoverable. It does not promise immediate cancellation midway through the unchanged brand import pipeline. Force-stopping that process preserves already committed perfume transactions; recover its incomplete brands afterward.

Call `retryFailedBrands(runId)` when no batch is active. It resets only `FAILED` rows to `PENDING`; completed rows remain completed. The run returns to `RUNNING` when retry work exists. Then request another bounded batch. A partially imported brand is rediscovered/reprocessed from its beginning; existing Phase 1 rows and associations are reused under the mapper's established sequential idempotency. No per-perfume progress table is introduced.

## Exact Eclipse setup for the small live smoke test

1. Apply the additive SQL to the intended database. Keep existing Phase 1 data intact.
2. Refresh the project and use **Maven > Update Project** if Eclipse has not picked up the new classes.
3. Right-click `src/test/java/com/perfume/scentrev/integration/ScentRevCatalogBatchDatabaseSmokeTests.java` and choose **Run As > JUnit Test** once to create its run configuration. Without all execution gates it skips.
4. Open **Run > Run Configurations > JUnit** and select that class's configuration. Ensure it runs only this class, not the whole project/package.
5. Under **Environment**, add `SCENTREV_API_KEY` and `DB_PASSWORD` with your actual values. Use `DB_URL`/`DB_USERNAME` only if needed for the intended existing database. Keep credentials out of source code and VM arguments.
6. Under **Arguments > VM arguments**, add exactly:

   ```text
   -Dscentrev.catalog-batch-db-live-test=true
   ```

7. Run it. It creates one full current brand snapshot, prints the new run ID, selects two brands with known reported counts between 1 and 5, prints their slugs/counts, and processes only those two. There is no large-brand fallback if fewer than two suitable brands exist. It never blindly imports the first brand.
8. Check the console's batch and run summaries. Only the two selected brands should complete; the other snapshot rows remain pending. The test also checks that existing Creed/Aventus identifiers and Creed association IDs remain. It performs no cleanup, second import, or retry; even a failing final assertion leaves earlier commits intact.

Creating a new run still lists all current brands (the prior catalog required 755 listing pages). This is deliberately separate from importing fragrance profiles. Reported counts are provider estimates and can change; this test limits selected brands based on the saved snapshot, not an absolute provider response-size guarantee. Remember its printed run ID: the remaining pending rows can subsequently be used as your full run rather than creating another snapshot.

Do not enable the legacy `scentrev.catalog-db-live-test` flag or run `ScentRevCatalogDatabaseSmokeTests` for the full catalog. Use the bounded workflow below.

## Manual execution, including after restarting Eclipse

Create a separate JUnit run configuration for **only** `ScentRevCatalogBatchManualTests`, using the same database environment variables. Add:

```text
-Dscentrev.catalog-batch-manual-test=true
```

Every invocation requires one explicit `scentrev.catalog-action`. There is no default import or loop. `create` and `batch` additionally require `SCENTREV_API_KEY`; `inspect`, `retry`, and `recover` only access the database.

1. To start a new plan, add `-Dscentrev.catalog-action=create`, run once, and record the console run ID. Skip creation if continuing the run from the small smoke test or a previous session.
2. To inspect it, use `-Dscentrev.catalog-action=inspect -Dscentrev.catalog-run-id=15`, replacing `15` with that actual run ID.
3. To process a batch, use `-Dscentrev.catalog-action=batch -Dscentrev.catalog-run-id=15 -Dscentrev.catalog-batch-size=10`. Change the size within 1–100 as desired. Start small. Early catalog positions can include very large brands, so ten brands can still take hours.
4. Run that same batch configuration again to process the next pending rows. Each invocation executes exactly one bounded batch. Stop between invocations whenever desired.
5. If the batch reports failures, its console prints safe brand diagnostics and JUnit fails after the batch summary. Successful commits remain. Inspect/fix the underlying cause, use `-Dscentrev.catalog-action=retry -Dscentrev.catalog-run-id=15`, then invoke `batch` again. Retry itself makes no provider calls.
6. After restarting Eclipse/computer, reuse the same run ID. If inspection shows an active batch or `RUNNING` brand rows from an executor that has stopped, use `-Dscentrev.catalog-action=recover -Dscentrev.catalog-run-id=15` once, then run `batch`. A normal completed batch needs no recovery.
7. Repeat explicit batches/inspection/retries until status is `COMPLETED`, pending/running/failed are all zero, and batch active is false. The required brand total comes from that snapshot, not the historical 7,548. `COMPLETED_WITH_FAILURES` still has gaps and requires explicit retry.

Use only one action argument at a time when editing the Eclipse run configuration. The test uses schema validation only and does not automatically add missing tables. No gated execution was run by Codex.

## PostgreSQL inspection

Use read-only queries in pgAdmin, replacing `15` with the recorded run ID:

```sql
SELECT id, status, total_brands, total_reported_fragrances,
       brands_with_unknown_count, brands_completed, brands_failed,
       (active_batch_token IS NOT NULL) AS batch_active,
       created_at, started_at, finished_at, last_updated_at
FROM catalog_import_runs WHERE id = 15;

SELECT status, count(*) AS brands, sum(processed_fragrance_count) AS recorded_processed
FROM catalog_import_brand_progress WHERE run_id = 15
GROUP BY status ORDER BY status;

SELECT catalog_position, brand_slug, brand_name, reported_fragrance_count,
       status, processed_fragrance_count, attempt_count, failure_message, last_updated_at
FROM catalog_import_brand_progress
WHERE run_id = 15 AND status IN ('PENDING', 'RUNNING', 'FAILED')
ORDER BY catalog_position LIMIT 25;
```

Ordinary offline tests cover snapshot validation/count retention, bounded selection, ordering, restart/recovery, explicit retries, safe failures, completion/totals, periodic logging, and real Spring transaction propagation with mocked repositories. Existing Phase 1 mapper tests retain their idempotency coverage. Offline tests do not call ScentRev or PostgreSQL.
