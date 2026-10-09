# PostgreSQL catalog REST API

The API uses only the existing PostgreSQL entities/repositories. Controllers return detached DTOs, and services use read-only transactions. No endpoint depends on a ScentRev client, cache-miss fetch, importer, or external HTTP request. No import implementation, entity mapping, schema migration, application property, or curated resource is changed.

## Run in Eclipse

Run `com.perfume.PerfumeApplication` as a Spring Boot App using the existing database Environment settings (`DB_PASSWORD`, and your existing `DB_URL` / `DB_USERNAME`). Use a separate API launch from the manual importer:

```text
-Dscentrev.brand-import=false
-Dspring.jpa.hibernate.ddl-auto=validate
-Dspring.sql.init.mode=never
```

The current application port is **8081**. A ScentRev API key is not required for these endpoints. The existing imported schema, including the previously implemented full-profile tables, must already exist. These launch overrides prevent schema updates; no SQL migration is added or executed by this task.

## Routes and parameters

| Method / URL | Purpose / parameters |
| --- | --- |
| `GET /api/brands` | Every stored Brand, ordered by name then ID. |
| `GET /api/perfumes` | Paged perfume summaries. Optional `page`, `size`, `q`, `brandSlug`. |
| `GET /api/perfumes/search` | Same paged search/filter contract as `/api/perfumes`. |
| `GET /api/perfumes/{id}` | Stored perfume details by PostgreSQL numeric ID. |

- `page`: zero-based page, default `0`, minimum `0`.
- `size`: default `20`, accepted range `1..100`.
- `q`: optional case-insensitive literal substring of perfume name, Brand display name, or Brand slug; maximum 255 characters. Trimmed empty input behaves as no keyword filter. `%`, `_` and `!` in the keyword are escaped as literal characters.
- `brandSlug`: optional exact canonical Brand slug, maximum 255 characters. Restricts keyword results to that Brand. An unknown valid slug returns an empty page; malformed/empty supplied slugs return 400.
- Summary ordering is `lower(perfume.name)`, then perfume ID. This stable tie-breaker also applies to search. No arbitrary sort parameter is accepted.
- Missing details return 404. Invalid numeric parameters, negative pages, invalid sizes, invalid IDs, and overlong search values return 400. Errors use Spring Problem Detail responses.
- Nullable stored values serialize as JSON `null`. No child rows produce empty arrays; no metric rows produce an empty object. An out-of-range valid page returns 200 with empty `content` and the actual total count.

Examples:

```text
http://localhost:8081/api/brands
http://localhost:8081/api/perfumes?page=0&size=20
http://localhost:8081/api/perfumes/search?q=Kierin
http://localhost:8081/api/perfumes?q=Aventus&brandSlug=creed&page=0&size=20
http://localhost:8081/api/perfumes/2
```

## Response examples

These illustrate the response shape, not a real database read or a new provider verification. IDs, counts and values depend on the operator's stored data.

Brand list:

```json
[{"id": 1, "name": "Creed", "brandSlug": "creed"}]
```

Perfume page:

```json
{
  "content": [{
    "id": 2,
    "publicId": "provider-id",
    "fragranceSlug": "creed-aventus",
    "name": "Aventus",
    "releaseYear": 2010,
    "imageUrl": null,
    "brand": {"id": 1, "name": "Creed", "brandSlug": "creed"}
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

Perfume detail:

```json
{
  "id": 2,
  "publicId": "provider-id",
  "fragranceSlug": "creed-aventus",
  "name": "Aventus",
  "brand": {"id": 1, "name": "Creed", "brandSlug": "creed"},
  "description": "Stored description",
  "releaseYear": 2010,
  "imageUrl": null,
  "reviewsCount": 123,
  "notes": {
    "top": [{"id": 5, "name": "Pineapple", "position": 0}],
    "middle": [],
    "base": [],
    "unlayered": []
  },
  "accords": [{"id": 6, "name": "fruity", "percentage": 100, "score": 1.0, "position": 0}],
  "perfumers": [{"id": 7, "publicId": "perfumer-id", "name": "Stored perfumer", "company": null, "biography": null, "perfumesCount": null}],
  "metrics": {
    "identity.rating": {
      "score": 0.91,
      "category": "liked",
      "nRecords": 100,
      "reliability": "high",
      "scale": null,
      "derivedFrom": null,
      "reviewsCount": null,
      "label": null,
      "details": {"score": 0.91, "category": "liked", "n_records": 100, "reliability": "high"}
    }
  },
  "pros": [{"text": "Stored pro", "source": null, "position": 0, "likeRatio": null, "nRecords": null, "details": {"text": "Stored pro"}}],
  "cons": [],
  "similarFragrances": [{"publicId": null, "fragranceSlug": "another-fragrance", "name": "Another fragrance", "likeRatio": null, "nRecords": null, "position": 0, "details": {"name": "Another fragrance"}}]
}
```

Every stored normalized metric is included using its original `metric_key` as the key. Relevant keys can include `identity.rating`, `identity.gender`, `performance.longevity`, `performance.sillage`, `performance.projection`, `performance.season.primary`, `performance.season.by_season.spring` (and other returned seasons), `performance.time_of_day`, `appreciation`, and `price_value`. Wear fallback data retains `wear_summary.*` paths. Scores and counts are returned as stored, without rescaling, deriving missing values, or assuming every perfume has every metric. The `details` objects retain stored additional metric/opinion/similarity properties. Related fragrances need not already have their own local Perfume row. Historical raw provider-response snapshots are not loaded or included in the default public detail response.

## Query behavior and validation

The listing uses a scalar projection that joins Brand and has a matching database count query. It does not load child relationships or all perfumes into memory. Depending on Spring Data's count optimization, a page uses up to two queries. Detail uses one perfume/Brand fetch join plus seven bulk child queries. Layered/unlayered notes, accords and perfumers fetch their target masters in the same query. Mapping does not traverse any other lazy association, so the query count does not grow with the number of child rows. Separate child reads avoid a collection Cartesian product and collection-fetch pagination.

Offline tests use real MVC routing/validation/serialization and real service mapping with mocked repositories. They cover default paging, both search routes, keyword wildcard escaping, exact Brand filtering, counts/empty pages, null fields, all normalized detail sections, precision, unknown IDs, invalid inputs, detached JSON details, and bulk reads for many children. The existing application-context test also validates all new repository query definitions with JDBC metadata access and schema actions disabled. No real PostgreSQL read/write, live ScentRev call, or import is required for the ordinary suite.

Validation completed: offline Maven `package` passed with **490 tests: 475 passed, 15 gated live/manual tests skipped, zero failures/errors**. All **32 new API/service test cases** passed and existing tests were preserved. The task-baseline audit confirms that only existing Repository interfaces were extended; all entity mappings, Import code, SQL files, application configuration, curated resources, and existing test files remain unchanged. Logs and the file audit are in `target/phase1-validation/rest-api-build.log` and `rest-api-change-audit.json`.
