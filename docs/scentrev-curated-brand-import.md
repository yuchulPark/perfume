# Optional curated Phase 1 preload

On-demand remains the primary application path: local search, a small remote search on a miss, user selection of one fragrance, local slug lookup, and an uncached profile imported into Phase 1. Curated preload is an optional, manually operated bootstrap. Neither path depends on completion of the other.

## Seed the local Brand table

Curated brand resolution is complete. **161 VERIFIED and enabled definitions** are eligible for this Brand-only step. `ScentRevCuratedBrandDbImportService.importVerifiedBrands()` uses the existing classpath/Jackson configuration loader to read `scentrev/curated-brands.json` and validate its source occurrences against `scentrev/curated-brand-source.json`. Neither resource is modified. Eligibility is exactly `verificationStatus == VERIFIED && enabled == true`; source collections and aliases do not become separate Brand rows.

Before any repository access, the complete selected list is checked for duplicate exact provider slugs. The existing catalog validation rejects missing/blank preferred names or verified slugs, malformed slugs, missing approval evidence, and inconsistent source mappings. Unlike fragrance snapshot creation, this seed **rejects duplicate eligible slugs** instead of silently collapsing them. The current regression test asserts 161 selected definitions; that number is not used for business filtering.

The existing Brand entity has only `name` and `brandSlug` identity fields. Seeding maps `canonicalDisplayName` to `Brand.name` and verified `brandSlug` to `Brand.brandSlug` (`brands.brand_slug`, with existing unique constraint `uk_brands_brand_slug`). Preferred names are retained, including Paco Rabanne, Thierry Mugler, Ed Hardy, D.S. & Durga, Dsquared2, Kierin NYC, and the approved Korean displays. Provider canonical/query identity stays in `resolutionQuery` and approval evidence; there is no separate Brand column to persist it. No schema or JPA mapping changes are required. The added `Brand.updateName()` method changes only the display name.

The operation looks up each row by `BrandRepository.findByBrandSlug(verifiedSlug)`. It inserts missing rows and updates differing names on the existing managed rows through JPA dirty checking. Matching names count as unchanged. Repeating the operation inserts zero duplicates and preserves primary keys. One Spring transaction covers the full invocation; validation or persistence failure leaves no partial seed. No Brand row is deleted, no unrelated row is reconciled away, and perfume relationships are preserved. The returned summary counts selected definitions, inserted, updated, unchanged, and processed brands.

`curated-090`, `curated-096`, `curated-133`, and disabled unresolved Zara collaboration `curated-145` are excluded. This operation makes **zero ScentRev MCP/API calls** and needs **no SCENTREV_API_KEY**. It has no startup runner or automatic invocation. It imports no perfumes, notes, accords, or perfumers; fragrance import is a separate later phase.

### Eclipse: explicitly seed Brand rows

1. Refresh the project and use Java 17. Select **only** `ScentRevCuratedBrandDbImportSmokeTests`, choose **Run As → JUnit Test**, then edit its launch under **Run → Run Configurations**.
2. In **Environment**, provide `DB_PASSWORD` and retain the existing datasource settings. `DB_URL` and `DB_USERNAME` continue to use the application's existing defaults or your configured overrides. No API key is required.
3. In **Arguments → VM arguments**, add:

```text
-Dscentrev.curated-brand-db-import=true
```

4. Run that class once. It calls the seed service once and prints:

```text
Curated brand DB import complete
selected = 161
inserted = ...
updated = ...
unchanged = ...
processed = 161
```

The class skips by default unless both the explicit property and nonblank `DB_PASSWORD` are present. Its database-only Spring context imports the seed service and configuration loader, with no ScentRev client or fragrance-import service. It reuses `application.properties`, sets `ddl-auto=validate` and SQL initialization to `never`, and does not create or modify the schema. The existing schema must already exist. Assertions require 161 selected/processed definitions, all selected preferred names persisted, and no duplicate provider slugs after import. Unrelated existing Brand rows are allowed. The transaction commits the seed; there is no destructive cleanup or test-wide rollback. A subsequent explicit run should report `inserted = 0`, `updated = 0`, and `unchanged = 161` when the names have not changed.

Codex runs only the offline suite during implementation. This PostgreSQL seed test and all live ScentRev tests remain disabled; the operator executes the real seed separately.

## Production manual import: one complete verified brand

The operator has completed and verified the idempotent seed of all **161 VERIFIED enabled Brand rows**. **ONE RUN = ONE VERIFIED BRAND.** The production manual application path is `ScentRevSingleBrandImportRunner` → the existing `ScentRevSingleBrandImportService`. Supply exactly one explicit verified provider slug. The importer validates the selection locally, discovers all available fragrance pages for that brand, fetches the complete supported profiles, and writes accepted profiles directly to PostgreSQL as each page commits. No JUnit invocation, snapshot-creation step, or second persistence step is required.

### Eclipse application launch

Run `com.perfume.PerfumeApplication` as a **Spring Boot App**. In its launch configuration, set the following **VM arguments**, replacing the placeholder with exactly one verified provider slug:

```text
-ea
-Dscentrev.brand-import=true
-Dscentrev.brand-import.brand-slug=<verified-provider-slug>
-Dscentrev.brand-import.delay-ms=250
```

Example only, if the operator explicitly chooses Alessandro:

```text
-ea
-Dscentrev.brand-import=true
-Dscentrev.brand-import.brand-slug=alessandro
-Dscentrev.brand-import.delay-ms=250
```

Set `SCENTREV_API_KEY` and `DB_PASSWORD` in **Environment**, using the existing credentials. The existing `DB_URL` / `DB_USERNAME` settings continue to apply. No credentials or connection details are hard-coded or printed. Run only this application launch; no test invocation is involved.

The only selection property is `scentrev.brand-import.brand-slug`. The removed plural `scentrev.brand-import.brand-slugs` property is explicitly rejected when manual import is enabled, even if the singular property is also supplied. Missing/blank or malformed slugs, comma-separated lists, wildcard-like values, `*`, and `all` fail before datasource initialization. No multi-brand execution, catalog-wide selection, scheduler, or parallel execution is supported.

Without `scentrev.brand-import=true`, the runner bean is absent and normal application startup performs no fragrance import. In manual mode, the main application validates slug syntax/delay before datasource initialization, starts without a web server, validates the existing schema (`ddl-auto=validate`, SQL initialization `never`), and closes the context/client/datasource after the single-brand import and final summary finish. These overrides apply only to manual mode; `application.properties` is unchanged.

### Local identity, pagination, and request rules

Before the first provider request or perfume write, the single-brand service calls its local `validateBrand()`. The selected slug must match exactly one VERIFIED enabled curated definition with a provider/query identity and an existing persisted `BrandRepository.findByBrandSlug()` row. Invalid selection causes zero provider calls and zero perfume writes. The importer uses the approved provider/query identity for reporting and the verified slug for filtering. Preferred names are preserved: Paco Rabanne maps to provider Rabanne, Ed Hardy to Christian Audigier, and D.S. & Durga to DS&Durga. The importer never creates or renames a Brand.

Discovery reuses the existing Java MCP client's `searchFragrancesFiltered(brandSlug, offset)`, which invokes **`search_fragrances_filtered`** with:

```json
{
  "filter_brand_slug": "<verified-provider-slug>",
  "min_rating_votes": 0,
  "result_limit": 10,
  "result_offset": 0
}
```

The connected tool metadata explicitly specifies `offset + limit` for the next page. Start at offset zero; when `truncated=true`, advance by the returned limit (10); stop only on `truncated=false`. All pages for the brand are processed. Ten is the provider's page size, not an import cap. No catalog-count estimate controls traversal. Incomplete/partial pages, inconsistent pagination, conflicting identifiers, and a truncated page with no new identities fail clearly. Maximum safety limits are **100 discovery pages** and **1000 unique fragrances**; if a continuation would exceed a guard, the import stops with `completed=false`, retaining committed pages.

Deduplication prefers `public_id` and also tracks canonical `fragrance_slug` as a fallback, including pages where a public ID becomes available later. Duplicate occurrences count once and receive no second profile request. New profiles are fetched sequentially using `get_fragrance_profile` with all ten supported sections: **identity, performance, appreciation, notes, note_pyramid, accords, perfumers, pros_cons, reminds_of, price_value**. `include_perfumer_portfolio=false` remains unchanged. Accepted profiles missing season/time-of-day metrics receive one `get_wear_summary` fallback through the same delay/error policy; confirmed foreign profiles receive none. See [full-profile storage and the required additive migration](scentrev-full-profile-import.md) before running the updated application.

Search results may include another provider brand despite `filter_brand_slug`. A discovery row's brand label alone is not sufficient to skip it; its one fetched profile confirms ownership. First validate the profile's public ID and fragrance slug against discovery using the existing conflict rules. If its valid `identity.brand_slug` then differs from the selected seeded Brand slug, increment `skippedForeignBrand`, print `SKIPPED FOREIGN BRAND RESULT` with requested brand slug, fragrance slug, and actual brand slug, and continue. That profile and all its children are excluded from persistence. No foreign Brand is created, and no fragrance is reassigned. Public-ID/fragrance-slug conflicts, missing identity, or missing/malformed brand identity remain fatal; they are not counted as foreign skips. Skipped identities remain in discovery deduplication and do not receive another profile request.

The delay defaults to **250 ms**, with a minimum of **100 ms**. It applies before each subsequent discovery/profile/wear request, measured from completion of the previous request; time spent validating/committing a page counts toward that gap. There is no delay before the first request or after the final request. No parallel requests, automatic retries, alternate keys/accounts, or restriction bypass are used. Any provider failure, including 401/403/429, forbidden, rate limit, account restriction, fair-use restriction, or another tool-level error, stops the import immediately. Normal import failures and interruption also stop the run. The console identifies the selected brand, stopped stage, page offset, fragrance slug when applicable, and committed counts.

### Page transactions and upsert semantics

Each page follows this sequence: fetch discovery outside a DB transaction, fetch every new unique profile sequentially outside a DB transaction, classify and skip confirmed foreign profiles, validate the remaining accepted profiles, then call the existing Phase-1 persistence service's `importPageForSeededBrand()` operation in **one REQUIRES_NEW transaction**. The orchestration suspends ambient transactions. Page commits finish before requesting the next discovery page. A network/validation failure writes none of the current page; a persistence failure rolls the entire current page back. Earlier pages remain committed. Rerun the same explicit brand from offset zero to safely revisit prior pages through the existing idempotent upserts.

If a page has no accepted profiles, including a foreign-only page, no persistence call or empty transaction is created. The console prints `PAGE n COMPLETED WITHOUT PERSISTENCE`, accepted count zero, and that page's foreign skip count. Pagination still follows `truncated` and the current offset contract. Such pages do not increase `pagesCommitted`; new foreign discovery identities still constitute discovery progress. The existing repeated/non-progressing truncated-page guard remains in effect.

Perfume upsert uses `scentrev_public_id`, with `fragrance_slug` as a secondary lookup/conflict check. Existing primary keys and Brand relationships are preserved; an existing perfume belonging to a different Brand fails instead of being reassigned. The operation refreshes the provider-owned fragrance slug, name, nullable release year, description, and review count. Perfumers use `scentrev_perfumer_id` and refresh supported name/company/biography/count fields. Note and Accord masters reuse exact stripped names, matching their existing case-sensitive unique constraints. Empty perfumer lists and empty TOP/MIDDLE/BASE note layers are valid. No notes are inferred from description text.

Association identities remain `(perfume, perfumer)`, `(perfume, note, layer)`, and `(perfume, accord)`. Existing links retain their IDs; current note positions and accord percentage/BigDecimal score/positions refresh in place. Duplicate values in a source layer/list keep the first occurrence. Accord percentages need not sum to 100, and nullable scores/counts remain supported. Reruns insert no duplicate masters or links; perfumes report inserted, updated, or unchanged, with child/association changes counting as an update.

The existing model has no separate provider-canonical Brand-name column or reported catalog-count column. Those identities remain in curated metadata. Full-profile import adds nullable `perfumes.image_url` and normalized metric/opinion/similarity/unlayered-note tables plus historical JSONB response snapshots. Missing/null/blank optional fields preserve known prior values. Missing historical child relationships are **retained**; no global masters, perfumes, brands, unrelated relationships, or source records are deleted. The existing page transaction also persists these extensions. The additive schema script must be applied manually before launch because the runner continues to validate the schema only.

### Visible progress and final result

The application prints to `System.err` so Eclipse shows `ScentRev full single-brand import`, the selected preferred/provider names and slug, discovery page/offset/returned count/truncation, every unique profile's position/name/slug, foreign-profile warnings, and each page's committed insert/update/unchanged/processed counts and foreign skip count. The runner prints `FINAL RESULT` on success and failure. It includes brand names/slug, discovery and committed pages, provider-reported total if available, raw results, duplicates, unique fragrances discovered, profiles fetched, `skippedForeignBrand`, perfume insert/update/unchanged/processed counts, distinct committed perfumer/note/accord counts, processed link counts (including reused links), attempted discovery/profile/wear MCP tool calls, and `completed=true/false`. Failure adds the stopped stage, page offset, and profile slug when applicable.

`ScentRevSingleBrandImportResult` uses these semantics: `uniqueFragrancesDiscovered` includes all unique discovery results before foreign filtering; `profilesFetched` includes successfully returned foreign profiles; `skippedForeignBrand` counts unique profiles confirmed to belong to another provider brand; `perfumesProcessed` counts only accepted selected-brand perfumes on committed pages. It equals inserted + updated + unchanged. Per-page skip counts are printed separately; the final skip count is cumulative. Foreign skips do not make an otherwise exhausted import incomplete.

The inspected search response exposes `total_returned` **per page** and no overall brand catalog total. Its page value is validated/printed; `providerReportedTotal` therefore stays unknown rather than treating a page count or the older curated estimate as a complete catalog total. Discovery/profile/wear/skip counts may include an uncommitted failed page; persistence/master/link counts include only committed pages. On failure the runner prints the partial final result and exits with failure; successful manual execution finishes normally after the one selected brand.

No live importer execution, PostgreSQL access, or MCP/API request was performed during implementation. The unwanted batch classes and their dependent offline tests were removed. No new JUnit importer or smoke-test workflow is added. The ordinary offline suite remains the regression check. The older snapshot/test-based preload sections below describe their existing optional historical workflow and are not required for this application import path.

## Source and parent configuration

- `src/main/resources/scentrev/curated-brand-source.json` preserves **187** supplied source occurrences in their original order, with only two operator-approved spelling corrections: occurrence 124 is now `프레드릭 엠` and occurrence 125 is now `알레산드로`. The other 185 labels remain unchanged; original spellings are retained in correction notes and history. Repeated labels must remain repeated if later supplied.
- `src/main/resources/scentrev/curated-brands.json` contains **165 stored parent definitions** and **162 parents whose status is not DISABLED**. Three definitions have DISABLED status: two historical source retirements and terminal provider-unavailable MIKA LOKKA. The stored count includes the unchanged Zara collaboration, whose status remains UNRESOLVED despite `enabled: false`. Source classifications remain 152 BRAND, 23 COLLECTION, 2 ALIAS, 1 COLLABORATION, and 9 UNRESOLVED. There are no identical raw labels in the initial list. These counts describe this configuration, not a required catalog size.
- Parent verification is separate from source classification: after the operator-approved live reviews, **161 verified enabled parents (Creed, Frederic Malle, Byredo, Tom Ford, By Kilian, Louis Vuitton, Maison Francis Kurkdjian, Le Labo, Serge Lutens, Memo Paris, Santa Maria Novella, Penhaligon's, Goutal, Diptyque, Aesop, Atelier Cologne, Acqua di Parma, Maison Margiela, Kiehl's, Nicolai Parfumeur Createur, Jo Malone London, Bvlgari, Hermès, Comme des Garcons, Spectator, The Different Company, Aerin, Chanel, Guerlain, Dior, Giorgio Armani, Yves Saint Laurent, Gucci, Bottega Veneta, Etat Libre d'Orange, Olfactive Studio, Escentric Molecules, L'Artisan Parfumeur, Terry de Gunzburg, Miller et Bertaux, 웨어 미 (UERMI), Les Parfums de Rosine, Akro, Sylvaine Delacourte, Aedes de Venustas, Arquiste, Astier de Villatte, Van Cleef & Arpels, Atkinsons, Juliette Has A Gun, Jusbox, James Heeley, A Lab on Fire, Amouroud, Balmain, Montale, Loewe, Officine Universelle Buly, Bond No. 9, Cartier, Valentino, Etro, Yves Rocher, Jean Paul Gaultier, Histoires de Parfums, Odin, Kenneth Cole, Givenchy, Armaf, Versace, Clean, Paco Rabanne, Elizabeth Arden, Comptoir Sud Pacifique, Malizia, Monotheme, Chabaud, Shanghai Tang, Innisfree, Thierry Mugler, 4711, The Body Shop, Anna Sui, Lanvin, Jimmy Choo, Abercrombie & Fitch, Lacoste, Dsquared2, Ed Hardy, Nautica, Issey Miyake, Kenzo, Carolina Herrera, Mercedes-Benz, Marc Jacobs, Montblanc, John Varvatos, Burberry, Ferrari, Dolce & Gabbana, 프레드릭 엠 (Frederic M), 알레산드로 (Alessandro), Amouage, Clive Christian, 리퀴드 이미지네흐 (Les Liquides Imaginaires), Lollia, Mancera, Reyane Tradition, Paul Smith, Illuminum, Solinotes, Miller Harris, Arrogance, Orlov Paris, 클라우스 포르토 (Claus Porto), 4160 Tuesdays, Eisenberg, Mirko Buffini, Acqua dell'Elba, 메종 사이브라이트 (Maison Sybarite), Rancé 1795, Chloé, Davidoff, Laura Mercier, Parfums de Marly, Roja Dove, Xerjoff, Trudon, Boucheron, Ramon Monegal, Yohji Yamamoto, Azzaro, Electimuss, BDK Parfums, Carner Barcelona, D.S. & Durga, Initio Parfums Privés, Kajal, Kierin NYC, Lalique, Nasomatto, Nishane, Rasasi, Royal Crown, Tiziana Terenzi, Viktor & Rolf, Ex Nihilo, 살롱 드 느바에 (Salon de Nevaeh), Nonfiction, Gritti, The House of Oud, Lorenzo Villoresi, Robert Piguet, Bortnikoff, Swiss Arabian, Ajmal, The Merchant of Venice, Bois 1920, Attar Collection, MDCI Parfums, Frapin), 0 enabled unresolved parents, 1 disabled unresolved parent, and 3 DISABLED parents** (two historical retirements and terminal MIKA LOKKA; 1 UNRESOLVED parent in total). A recognizable brand name is still unresolved until its exact provider slug is approved.
- `canonicalBrandKey` is a stable configuration identifier, **not** a ScentRev slug. `sources` maps each one-based source index and exact label into exactly one parent. Configuration validation rejects missing, changed, or multiply assigned occurrences.
- `brandSlug` stays `null` until verified. VERIFIED requires a valid exact slug and nonblank `verificationEvidence`. UNRESOLVED may not carry a guessed slug. DISABLED or `enabled: false` excludes a parent from future runs.
- `resolutionQuery` is a reviewed canonical name for targeted lookup; it never generates a slug. Display/query names are provisional normalization metadata, not evidence of a provider match.
- `reportedFragranceCount` is nullable. Enter a nonnegative count only when it is actually known from trusted provider/local evidence. No additional API request is made to estimate size. The 153 parents with known counts report **13,097 fragrances combined**. Creed and the seven final approvals have unknown counts; fragrance-level identity evidence does not supply a brand's catalog size. This subtotal is not a total for all 161 verified enabled parents.

Creed's exact `creed` slug uses the user's previously completed live discovery/import validation. The following 160 resolutions were explicitly approved by the operator after completed live reviews; applying them made no additional provider request or PostgreSQL lookup. Verification evidence is retained in each definition. Unknown counts remain null, including all seven final approvals.

| Parent key | Approved provider name | Verified slug | Observed fragrance count |
|---|---|---|---:|
| curated-003 | Frederic Malle | frederic-malle | 68 |
| curated-004 | Byredo | byredo | 90 |
| curated-006 | Tom Ford | tom-ford | 140 |
| curated-007 | By Kilian | by-kilian | 105 |
| curated-009 | Louis Vuitton | louis-vuitton | 59 |
| curated-010 | Maison Francis Kurkdjian | maison-francis-kurkdjian | 65 |
| curated-011 | Le Labo | le-labo | 64 |
| curated-013 | Serge Lutens | serge-lutens | 113 |
| curated-018 | Memo Paris | memo-paris | 75 |
| curated-019 | Santa Maria Novella | santa-maria-novella | 64 |
| curated-020 | Penhaligon's | penhaligon-s | 112 |
| curated-022 | Goutal | goutal | 79 |
| curated-023 | Diptyque | diptyque | 102 |
| curated-024 | Aesop | aesop | 18 |
| curated-025 | Atelier Cologne | atelier-cologne | 59 |
| curated-026 | Acqua di Parma | acqua-di-parma | 106 |
| curated-028 | Maison Martin Margiela | maison-martin-margiela | 47 |
| curated-029 | Kiehl's | kiehl-s | 9 |
| curated-030 | Nicolai Parfumeur Createur | nicolai-parfumeur-createur | 79 |
| curated-031 | Jo Malone London | jo-malone-london | 212 |
| curated-034 | Bvlgari | bvlgari | 151 |
| curated-035 | Hermès | herm-s | unknown |
| curated-037 | Comme des Garcons | comme-des-garcons | 107 |
| curated-038 | Spectator | spectator | 1 |
| curated-039 | The Different Company | the-different-company | 36 |
| curated-040 | Aerin | aerin | 42 |
| curated-041 | Chanel | chanel | 156 |
| curated-043 | Guerlain | guerlain | 575 |
| curated-045 | Dior | dior | 311 |
| curated-048 | Giorgio Armani | giorgio-armani | 257 |
| curated-050 | Yves Saint Laurent | yves-saint-laurent | 278 |
| curated-051 | Gucci | gucci | 136 |
| curated-052 | Bottega Veneta | bottega-veneta | 41 |
| curated-053 | Etat Libre d'Orange | etat-libre-d-orange | 56 |
| curated-054 | Olfactive Studio | olfactive-studio | 23 |
| curated-056 | Escentric Molecules | escentric-molecules | 26 |
| curated-057 | L'Artisan Parfumeur | l-artisan-parfumeur | 131 |
| curated-058 | Terry de Gunzburg | terry-de-gunzburg | 18 |
| curated-059 | Miller et Bertaux | miller-et-bertaux | 21 |
| curated-060 | UERMI | uermi | 26 |
| curated-061 | Les Parfums de Rosine | les-parfums-de-rosine | 58 |
| curated-062 | Akro | akro | 15 |
| curated-063 | Sylvaine Delacourte | sylvaine-delacourte | 15 |
| curated-064 | Aedes de Venustas | aedes-de-venustas | 15 |
| curated-065 | Arquiste | arquiste | 30 |
| curated-066 | Astier de Villatte | astier-de-villatte | 15 |
| curated-067 | Van Cleef & Arpels | van-cleef-arpels | unknown |
| curated-068 | Atkinsons | atkinsons | 79 |
| curated-069 | Juliette Has A Gun | juliette-has-a-gun | 39 |
| curated-071 | Jusbox | jusbox | 22 |
| curated-072 | James Heeley | james-heeley | 31 |
| curated-073 | A Lab on Fire | a-lab-on-fire | 8 |
| curated-074 | Amouroud | amouroud | 29 |
| curated-075 | Balmain Beauty | balmain-beauty | 98 |
| curated-076 | Montale | montale | 166 |
| curated-077 | Loewe | loewe | 132 |
| curated-078 | Buly 1803 | buly-1803 | 24 |
| curated-079 | Bond No 9 | bond-no-9 | 173 |
| curated-080 | Cartier | cartier | 129 |
| curated-081 | Valentino | valentino | 71 |
| curated-082 | Etro | etro | 41 |
| curated-083 | Yves Rocher | yves-rocher | 298 |
| curated-084 | Jean Paul Gaultier | jean-paul-gaultier | 178 |
| curated-085 | Histoires de Parfums | histoires-de-parfums | 51 |
| curated-086 | Odin | odin | 16 |
| curated-087 | Kenneth Cole | kenneth-cole | 32 |
| curated-088 | Givenchy | givenchy | 290 |
| curated-089 | Armaf | armaf | 353 |
| curated-091 | Versace | versace | 87 |
| curated-092 | Clean | clean | 99 |
| curated-093 | Rabanne | rabanne | 152 |
| curated-094 | Elizabeth Arden | elizabeth-arden | 116 |
| curated-095 | Comptoir Sud Pacifique | comptoir-sud-pacifique | 82 |
| curated-097 | Malizia | malizia | 45 |
| curated-098 | Monotheme Venezia | monotheme-venezia | 86 |
| curated-099 | Chabaud Maison de Parfum | chabaud-maison-de-parfum | 29 |
| curated-100 | Shanghai Tang | shanghai-tang | 13 |
| curated-101 | Innisfree | innisfree | 15 |
| curated-102 | Mugler | mugler | 174 |
| curated-103 | 4711 | 4711 | 83 |
| curated-104 | The Body Shop | the-body-shop | 183 |
| curated-105 | Anna Sui | anna-sui | 58 |
| curated-106 | Lanvin | lanvin | 78 |
| curated-107 | Jimmy Choo | jimmy-choo | 48 |
| curated-108 | Abercrombie & Fitch | abercrombie-fitch | unknown |
| curated-109 | Lacoste | lacoste | 75 |
| curated-110 | Dsquared | dsquared | unknown |
| curated-111 | Christian Audigier | christian-audigier | unknown |
| curated-112 | Nautica | nautica | 32 |
| curated-114 | Issey Miyake | issey-miyake | 118 |
| curated-115 | Kenzo | kenzo | 170 |
| curated-116 | Carolina Herrera | carolina-herrera | 227 |
| curated-117 | Mercedes-Benz | mercedes-benz | 49 |
| curated-118 | Marc Jacobs | marc-jacobs | 127 |
| curated-119 | Montblanc | montblanc | 45 |
| curated-120 | John Varvatos | john-varvatos | 26 |
| curated-121 | Burberry | burberry | 104 |
| curated-122 | Ferrari | ferrari | 37 |
| curated-123 | Dolce&Gabbana | dolce-gabbana | 151 |
| curated-124 | Frederic M | frederic-m | 50 |
| curated-125 | Alessandro | alessandro | 1 |
| curated-126 | Amouage | amouage | 151 |
| curated-127 | Clive Christian | clive-christian | 93 |
| curated-128 | Les Liquides Imaginaires | les-liquides-imaginaires | 45 |
| curated-129 | Lollia | lollia | 13 |
| curated-130 | Mancera | mancera | 96 |
| curated-131 | Reyane Tradition | reyane-tradition | 67 |
| curated-132 | Paul Smith | paul-smith | 46 |
| curated-134 | Illuminum | illuminum | 42 |
| curated-135 | Solinotes | solinotes | 37 |
| curated-136 | Miller Harris | miller-harris | 77 |
| curated-137 | Arrogance | arrogance | 24 |
| curated-138 | Orlov Paris | orlov-paris | 22 |
| curated-139 | Claus Porto | claus-porto | 22 |
| curated-140 | 4160 Tuesdays | 4160-tuesdays | 132 |
| curated-141 | Eisenberg | eisenberg | 37 |
| curated-142 | Mirko Buffini Firenze | mirko-buffini-firenze | 41 |
| curated-143 | Acqua dell Elba | acqua-dell-elba | 18 |
| curated-144 | Maison Sybarite | maison-sybarite | 6 |
| curated-146 | Rance 1795 | rance-1795 | 41 |
| curated-147 | Chloé | chlo | unknown |
| curated-148 | Davidoff | davidoff | 111 |
| curated-149 | Laura Mercier | laura-mercier | 23 |
| curated-150 | Parfums de Marly | parfums-de-marly | 46 |
| curated-151 | Roja Dove | roja-dove | 184 |
| curated-152 | Xerjoff | xerjoff | 184 |
| curated-153 | Trudon | trudon | 15 |
| curated-154 | Boucheron | boucheron | 62 |
| curated-155 | Ramon Monegal | ramon-monegal | 61 |
| curated-156 | Yohji Yamamoto | yohji-yamamoto | 29 |
| curated-157 | Azzaro | azzaro | 104 |
| curated-158 | Electimuss | electimuss | 40 |
| curated-159 | BDK Parfums | bdk-parfums | 25 |
| curated-160 | Carner Barcelona | carner-barcelona | 35 |
| curated-161 | DS&Durga | ds-durga | unknown |
| curated-163 | Initio Parfums Prives | initio-parfums-prives | 25 |
| curated-164 | Kajal | kajal | 25 |
| curated-165 | Kierin | kierin | 10 |
| curated-166 | Lalique | lalique | 104 |
| curated-167 | Nasomatto | nasomatto | 14 |
| curated-168 | Nishane | nishane | 53 |
| curated-169 | Rasasi | rasasi | 388 |
| curated-170 | Royal Crown | royal-crown | 54 |
| curated-171 | Tiziana Terenzi | tiziana-terenzi | 126 |
| curated-172 | Viktor&Rolf | viktor-rolf | 104 |
| curated-173 | Ex Nihilo | ex-nihilo | 81 |
| curated-174 | Salon de Nevaeh | salon-de-nevaeh | 22 |
| curated-175 | Nonfiction | nonfiction | 16 |
| curated-176 | Gritti | gritti | 66 |
| curated-177 | The House of Oud | the-house-of-oud | 45 |
| curated-178 | Lorenzo Villoresi | lorenzo-villoresi | 31 |
| curated-179 | Robert Piguet | robert-piguet | 46 |
| curated-180 | Bortnikoff | bortnikoff | 64 |
| curated-181 | Swiss Arabian | swiss-arabian | 251 |
| curated-182 | Ajmal | ajmal | 349 |
| curated-183 | The Merchant of Venice | the-merchant-of-venice | 116 |
| curated-184 | Bois 1920 | bois-1920 | 62 |
| curated-185 | Attar Collection | attar-collection | 45 |
| curated-186 | MDCI Parfums | mdci-parfums | 24 |
| curated-187 | Frapin | frapin | 21 |

The final new-brand batch adds these nine exact provider candidates as VERIFIED and enabled. Their existing display and targeted query names already match the approved provider canonical names. Each definition records operator approval evidence, the exact verified slug, and the observed fragrance count. No ambiguous/manual candidate selection, targeted-query correction, or parent/source/alias/collection/collaboration merge was required or performed.

After the final operator decisions, the configuration contains 161 VERIFIED + enabled parents, one UNRESOLVED parent (the unchanged disabled Zara collaboration), and three DISABLED parents: 165 stored definitions and 162 with status other than DISABLED. The resolver's enabled unresolved list is empty. The source list retains all 187 occurrences with the two prior approved corrections.

All 154 previously VERIFIED mappings and approval evidence, all 187 raw labels and collection/alias/collaboration groupings, both historical retired definitions, the Olfactive Studio / 올팩티브 프로젝트 alias merge, and the disabled Zara collaboration remain unchanged. Only the eight final-decision parents changed. No new or duplicate normalized parent was created. Applying these decisions made no live MCP/API request, PostgreSQL access, or fragrance import.

The operator approved Rasasi, Royal Crown, Tiziana Terenzi, Ex Nihilo, Nonfiction, Gritti, The House of Oud, and Lorenzo Villoresi as eight exact provider candidates, VERIFIED and enabled. Each definition records operator approval evidence, the approved provider canonical name, exact verified slug, and observed fragrance count. No parent, alias, collection, or collaboration merge was performed.

The final unseen unresolved-brand review approved these two existing parents as VERIFIED and enabled: `Viktor&Rolf / viktor-rolf / 104` and `Salon de Nevaeh / salon-de-nevaeh / 22`. Their preferred display names, exact raw sources, and provider/query identities remain unchanged, with operator approval evidence recorded:

| Parent key | Preserved preferred display name | Exact raw label | Reviewed targeted query |
|---|---|---|---|
| curated-172 | Viktor & Rolf | 빅터 & 롤프 | Viktor&Rolf |
| curated-174 | 살롱 드 느바에 | 살롱 드 느바에 | Salon de Nevaeh |

The Korean preferred display/raw label `살롱 드 느바에` remains exact. Viktor & Rolf's former fallbacks are inactive historical guidance only after verification. All unrelated unresolved definitions and queries, previously VERIFIED mappings and approval evidence, source records, and collection/alias/collaboration groupings remain unchanged. The Olfactive Studio / 올팩티브 프로젝트 alias merge and disabled Zara collaboration remain intact. No additional or duplicate normalized parent was created. Applying these approvals made no live MCP/API request, PostgreSQL access, or fragrance import.

The operator approved Electimuss, BDK Parfums, Carner Barcelona, Initio Parfums Privés, Kajal, Lalique, Nasomatto, and Nishane as VERIFIED and enabled: seven exact provider candidates and one explicitly approved spelling variant. `curated-163` retains the preferred display name `Initio Parfums Privés`; its provider/query identity and manual approval evidence use the approved unaccented name `Initio Parfums Prives`, exact slug `initio-parfums-prives`, and observed count 25. Its original raw label `이니시오 퍼퓸 프라이브스` remains exact. Each approved definition records the reviewed provider name, exact slug, observed count, and operator approval evidence; no additional Initio parent was created.

D.S. & Durga (`curated-161`) is now VERIFIED and enabled with provider/query identity `DS&Durga` and slug `ds-durga`, approved from the final direct `get_identity` result for Amber Kiso. Its preferred display remains `D.S. & Durga` and raw source remains `DS & Durga (디에스앤더가)`. Earlier `D.S. and Durga` / `DS and Durga` queries and the `DS Durga` / `디에스앤더가` fallback guidance are inactive history only.

For Kierin NYC (`curated-165`), the operator manually approved that the preferred brand `Kierin NYC` resolves to provider canonical brand `Kierin`. The existing query `KIERIN` returned `Kierin / kierin / 10`; the approved provider/query identity is now exactly `Kierin`. Status is VERIFIED and enabled, while preferred display `Kierin NYC` and raw label `키에린 뉴욕 (Kierin NYC)` remain exact. Explicit manual approval evidence records the identity relationship. No second normalized parent was created. This prior approval remains unchanged.

All earlier unresolved definitions and retry queries, previously VERIFIED mappings and approval evidence, and all 187 raw labels and source groupings remain unchanged. The Olfactive Studio alias merge and disabled Zara collaboration remain intact. No alias, collection, collaboration, or parent merge was performed. Applying this batch made no live MCP/API request, PostgreSQL access, or fragrance import.

The operator approved Davidoff, Laura Mercier, Parfums de Marly, Roja Dove, Xerjoff, Trudon, Boucheron, Ramon Monegal, Yohji Yamamoto, and Azzaro as ten exact provider candidates, VERIFIED and enabled. Their existing display and targeted query names already match the approved provider names. Each definition records operator approval evidence, the exact verified slug, and the observed fragrance count. No ambiguous-candidate selection, query correction, or parent/alias/collection/collaboration merge was required or performed.

All previously VERIFIED mappings and approval evidence remain unchanged. All prior unresolved definitions, retry queries, source classifications, and enabled states remain unchanged, including the Zara collaboration (`curated-145`, query `Zara`, COLLABORATION source, UNRESOLVED, disabled, null slug). All 187 original raw labels and existing collection/alias/collaboration groupings remain exact, including the Olfactive Studio / 올팩티브 프로젝트 alias merge. Applying this batch made no live MCP/API request, PostgreSQL access, or fragrance import.

The operator approved the Arrogance, Orlov Paris, 클라우스 포르토 (Claus Porto), 4160 Tuesdays, Eisenberg, Mirko Buffini, Acqua dell'Elba, and Rancé 1795 mappings. Mirko Buffini, Acqua dell'Elba, and Rancé 1795 retain their preferred display names; their queries and explicit manual approval evidence use the approved provider identities Mirko Buffini Firenze, Acqua dell Elba, and Rance 1795 respectively. Original raw source labels remain exact, and no additional parents were created.

The second retry review approved these two existing parents as VERIFIED and enabled: `Claus Porto / claus-porto / 22` and `Maison Sybarite / maison-sybarite / 6`. Their preferred Korean display/raw labels and existing provider/query names remain unchanged; approval evidence records each provider identity without adding another parent:

| Parent key | Preserved preferred display/raw label | Reviewed targeted query |
|---|---|---|
| curated-139 | 클라우스 포르토 | Claus Porto |
| curated-144 | 메종 사이브라이트 | Maison Sybarite |

Local inspection found no separate Zara parent, so Case B applies to `curated-145`. The existing parent remains active with its exact original display/raw label `조말론 & 자라` and COLLABORATION source classification. Operator source-review notes record that it represents fragrances sold under Zara, created with perfumer Jo Malone CBE. Its targeted query is exactly `Zara`, status remains UNRESOLVED, slug and reported count remain null, and `enabled` is false. It is excluded from both resolver chunks and verified import snapshots; using the recorded query in a future resolution attempt requires explicit operator enablement. No separate Zara parent was created, no slug was guessed, and the source was never merged or associated with Jo Malone London (`curated-031`).

The current configuration contains 165 stored definitions: 161 VERIFIED + enabled, zero enabled UNRESOLVED, one disabled UNRESOLVED collaboration, and three DISABLED definitions (two historical retirements plus terminal MIKA LOKKA). The status-based non-retired count is 162; the full UNRESOLVED count is one. Source records/groupings, earlier verified mappings and evidence, the retired definitions, and the Olfactive Studio alias merge are unchanged.

Clive Christian, 리퀴드 이미지네흐 (Les Liquides Imaginaires), Lollia, Mancera, Reyane Tradition, Paul Smith, Illuminum, Solinotes, and Miller Harris were approved by the operator as exact provider candidates after the completed live review. Their mapping evidence records each provider canonical name, exact slug, observed count, and approval date. Applying the approvals made no provider request, PostgreSQL access, or fragrance import.

The second retry review manually approved `curated-128` as `Les Liquides Imaginaires / les-liquides-imaginaires / 45`, despite the earlier query's canonical-name mismatch. Its provider/query identity now includes `Les`. The final review ends resolution for `curated-133` with DISABLED status, `enabled: false`, and null slug. `MIKA LOKKA` is retained as the intended identity and historical query. The targeted brand check and final `Lady Muscat` product check found no usable ScentRev mapping; this does not establish that the real-world brand does not exist:

| Parent key | Preserved preferred display/raw label | Reviewed targeted query |
|---|---|---|
| curated-128 | 리퀴드 이미지네흐 | Les Liquides Imaginaires |
| curated-133 | 미카로카 | MIKA LOKKA |

Both Korean display/raw labels remain exact, with their existing source classifications and parent keys. No additional parent, alias merge, or collection regrouping was introduced. Les Liquides Imaginaires records explicit manual approval evidence; no alternate spelling or similarly named provider was assigned to MIKA LOKKA. The earlier retry sections below are historical; the final operator decisions supersede their pending queries. The Olfactive Studio / 올팩티브 프로젝트 alias merge remains intact.

The operator approved Mercedes-Benz, Marc Jacobs, Montblanc, John Varvatos, Burberry, Ferrari, and Amouage after the completed live review. Their verification evidence records the approved provider name, exact slug, observed count, and approval date without making another provider request.

Dolce & Gabbana (`curated-123`) is now VERIFIED and enabled after the operator approved `Dolce&Gabbana / dolce-gabbana / 151`. Its provider/query identity remains `Dolce&Gabbana` and preferred display remains `Dolce & Gabbana`. The original base source `돌체 & 가바나` (index 123, BRAND) and Velvet source `돌체 & 가바나 벨벳 컬렉션` (index 162, COLLECTION) stay together under this same parent. Former fallback variants are inactive historical guidance only. No separate Dolce&Gabbana parent was created.

`curated-124` retains the corrected Korean display/source `프레드릭 엠` and query `Frederic M`. From the ambiguous results, the operator deliberately selected `Frederic M / frederic-m / 50` (candidate B) over `Frederic Malle / frederic-malle / 68` (candidate A). It is now VERIFIED and enabled, independent of the unchanged Frederic Malle parent (`curated-003`); no alias merge was performed. `curated-125` retains corrected display/source `알레산드로` and query `Alessandro`. The operator selected standalone `Alessandro / alessandro / 1` (candidate #2) over Alessandro Dell'Acqua (`alessandro-dell-acqua`, 3) and Alessandro Della Torre (`alessandro-della-torre`, 1). It is now VERIFIED and enabled. Both preserve their source indices, provenance classifications, and existing keys; their evidence explicitly records these manual choices and their notes retain the earlier source-correction history.

Earlier direct brand queries for Abercrombie & Fitch, Dsquared2, and Ed Hardy returned no exact candidate. The final operator review now approves all three under their existing keys while preserving their preferred displays and exact raw labels:

| Parent key | Preserved display name |
|---|---|
| curated-108 | Abercrombie & Fitch |
| curated-110 | Dsquared2 |
| curated-111 | Ed Hardy |

Their provider/query identities are now `Abercrombie & Fitch`, `Dsquared`, and `Christian Audigier`. Abercrombie & Fitch and Ed Hardy use direct identity evidence; Dsquared uses explicit manual product-level approval. Local inspection found no Christian Audigier parent, so existing `curated-111` represents that provider while keeping display `Ed Hardy` and actual raw label `에드 하디`. No independent Ed Hardy provider slug or duplicate parent/import is introduced. Earlier association context and fallback guidance are historical only.

Monotheme (`curated-098`) was manually mapped to provider `Monotheme Venezia / monotheme-venezia / 86`; Chabaud (`curated-099`) was manually mapped to `Chabaud Maison de Parfum / chabaud-maison-de-parfum / 29`. Their preferred display names remain Monotheme and Chabaud, while their query names and approval evidence retain the selected provider canonical identities. Both keep their original raw sources and existing parent keys; no additional or duplicate parent was created.

The first unresolved-brand retry review approved the following three parents as VERIFIED and enabled. Thierry Mugler retains its preferred display name; Malizia and 4711 use the approved provider-brand display names under their existing keys. Their exact raw labels and existing source classifications are preserved:

| Parent key | Preferred display name | Approved provider/query identity | Source classification |
|---|---|---|---|
| curated-097 | Malizia | Malizia | COLLECTION (preserved) |
| curated-102 | Thierry Mugler | Mugler | BRAND (unchanged) |
| curated-103 | 4711 | 4711 | COLLECTION (preserved) |

Human review identified 말라지아 봉봉 as the Malizia Bon Bons line under Malizia and 아쿠아 콜로니아 as the Acqua Colonia collection under 4711. Local inspection found no other Malizia or 4711 parent, so Case B applied to both: convert the existing definitions to the approved provider-brand mappings and retain each exact raw label as COLLECTION under the same key. Each provider slug selects exactly one verified snapshot row. No source was moved and no parent was collapsed or added; at that approval, there were 165 stored definitions and 163 non-retired parents. Explicit operator evidence records Malizia / malizia / 45 and 4711 / 4711 / 83. Applying those approvals made no provider request or import.

`curated-096` (`스위스 컬렉션`) is now retired with DISABLED status and `enabled: false`. Its original source occurrence 96, display, classification, null query, and null slug remain stored for traceability. It cannot enter resolver selection or import, and no English provider identity was assigned. Hermès, Van Cleef & Arpels, Buly, Bond No. 9, and Paco Rabanne retain their existing queries and mappings.

The operator manually selected Odin (`curated-086`, `odin`, 16) over Friedemodin and RODIN Olio Lusso; Armaf (`curated-089`, `armaf`, 353) over Profumi d'Art X Armaf and Parmafragrance; and Clean (`curated-092`, `clean`, 99) over The Cleany. Each parent's verification evidence records this explicit selection. No alternative candidate was approved by this update.

`curated-090` (`퍼퓸 드 스퀘어`) is now retired with DISABLED status and `enabled: false`. Its original source occurrence 90, display, classification, null slug, and null query remain stored for traceability. It is excluded from resolver selection and import; no English provider identity was assigned.

For `curated-093`, the operator explicitly approved `Rabanne / rabanne / 152` after the completed retry review. Its preferred display name remains `Paco Rabanne`, its provider/query identity is `Rabanne`, and its raw label remains `파코라반`. It is now VERIFIED and enabled with manual approval evidence. No separate Rabanne parent was created, and applying the approval made no provider request.

Valentino (`curated-081`) was manually selected from the multiple provider candidates: the operator approved `Valentino / valentino / 71` over Mario Valentino (observed count 3). Etro (`curated-082`) was explicitly selected as `Etro / etro / 41` over the fuzzy alternatives Retrouvailles, Le Jardin Retrouve, and Parfums Retro. These manual selections are retained in each parent's verification evidence; no alternative candidate was approved by this update.

The operator explicitly approved the following two retry results as VERIFIED and enabled: `Buly 1803 / buly-1803 / 24` and `Bond No 9 / bond-no-9 / 173`. Their preferred display names and exact raw sources are preserved separately from their provider/query identities, and each records manual approval evidence. No second parent was created, and applying these approvals made no provider request:

| Parent key | Preserved display name | Reviewed targeted query |
|---|---|---|
| curated-078 | Officine Universelle Buly | Buly 1803 |
| curated-079 | Bond No. 9 | Bond No 9 |

Balmain (`curated-075`) was explicitly approved by the operator as provider `Balmain Beauty / balmain-beauty / 98` after the targeted resolver returned no exact `Balmain` canonical-name match. `canonicalDisplayName` remains `Balmain`, while `resolutionQuery` is `Balmain Beauty` and the verification evidence records the manually selected provider identity. The original raw label `발망` remains unchanged under the same parent; no separate Balmain Beauty parent or duplicate import was created. Juliette Has A Gun's base and Luxury Collection sources retain their single-parent grouping.

Van Cleef & Arpels (`curated-067`) is now VERIFIED and enabled with provider/query identity `Van Cleef & Arpels` and slug `van-cleef-arpels`, following operator approval of the final direct identity result for Bois Doré. Its preferred display and exact raw source are unchanged. Earlier `Van Cleef and Arpels` / `Van Cleef & Arpels` brand-query failures are inactive history; this parent is excluded from active retry resolution.

The operator confirmed that raw source occurrence 55, `올팩티브 프로젝트`, refers to Olfactive Studio. Its exact label and source index now belong to `curated-054` as an ALIAS alongside the original BRAND source at index 54. The empty standalone `curated-055` definition was physically removed; its former key and the operator correction are retained only in `curated-054`'s evidence/notes and this history. Remaining parent keys are unchanged. The existing Olfactive Studio provider identity and observed count remain `Olfactive Studio / olfactive-studio / 23`. There is no separate active parent, provider query for `Olfactive Project`, or additional Olfactive Studio import row.

By Kilian's display/query name now matches the manually approved provider name. The earlier UNRESOLVED result was a name mismatch with `Kilian`; it does not override this explicit operator approval. Its two original Korean source labels and collection grouping are unchanged.

Memo Paris's display/query name now matches the manually approved provider name. The query `Memo` returned multiple brands and was reported AMBIGUOUS; the operator explicitly reviewed those candidates and approved only `memo-paris`. The original raw label `메모` is unchanged, and no other candidate entered the mapping. Le Labo's City Exclusives and all Serge Lutens variants retain their existing single-parent grouping.

Goutal's existing display/query name matches the approved provider name. The operator confirmed that this parent corresponds to Annick Goutal / the original raw label `아닉 구딸`, which remains unchanged. Penhaligon's Portraits retains its existing single-parent grouping.

Nicolai Parfumeur Createur's display/query name now matches the operator-approved provider name. The operator reviewed the multiple resolver candidates and explicitly approved only `nicolai-parfumeur-createur` for the original raw label `니콜라이`, which remains unchanged. Acqua di Parma's collection and Jo Malone London's collection labels retain their existing single-parent grouping; the separate Jo Malone/Zara collaboration remains unresolved.

Comme des Garcons now uses the approved unaccented provider identity for its display/query name. The operator explicitly approved this candidate after the resolver reported an accented-name mismatch. Bvlgari's base and Le Gemme collection labels retain their existing single-parent grouping.

Guerlain (`curated-043`) was manually selected from the multiple candidates: the operator approved `Guerlain / guerlain / 575` and excluded `Marcel Guerlain / marcel-guerlain / 24`. No Marcel Guerlain parent or import slug was added. Chanel's exclusive collection, Guerlain's exclusive collection, all three Dior source/alias/collection labels, and Armani Privé retain their existing single-parent grouping.

Maison Margiela (`curated-028`) is now VERIFIED after the operator approved the exact candidate returned for the historical-name retry. The existing model separates `canonicalDisplayName: Maison Margiela` from `resolutionQuery: Maison Martin Margiela`; the verification evidence records the provider's canonical name and confirmed identity. Its original raw label `메종 마르지엘라` is unchanged.

Spectator (`curated-038`) is now VERIFIED after the operator approved the exact `Spectator` candidate. Its display/query name is `Spectator`, slug is `spectator`, and observed count is 1. The original raw label `스펙테이터` is preserved; its source classification changes from UNRESOLVED to BRAND because its brand identity is now confirmed. No collection or alias grouping changes.

Hermès is now VERIFIED and enabled with provider/query identity `Hermès` and exact slug `herm-s`, approved from the final direct identity result for Terre d'Hermes Parfum. Its preferred display spelling, raw labels, and Hermessence grouping are unchanged:

| Parent key | Preserved display name | Reviewed targeted query |
|---|---|---|
| curated-035 | Hermès | Hermès |

Hermès is included in verified snapshots and excluded from active retries. The approved `herm-s` slug comes from operator-reviewed direct identity evidence; no `hermes` or `hermes-paris` slug was guessed.

## First unresolved-brand retry approvals

This section records the first retry batch historically. Its unresolved states, queries, and counts are superseded by the final operator decisions below.

The operator manually approved six provider identities from the completed retry review: `curated-078` (Buly 1803), `curated-079` (Bond No 9), `curated-093` (Rabanne), `curated-097` (Malizia), `curated-102` (Mugler), and `curated-103` (4711). Their exact slugs and observed counts appear in the approval table above; each mapping records explicit operator evidence. Buly, Bond No. 9, Paco Rabanne, and Thierry Mugler retain their preferred display names separately from provider/query identities.

Neither Malizia nor 4711 had another local normalized parent. Case B therefore converts `curated-097` and `curated-103` in place, retaining `말라지아 봉봉` and `아쿠아 콜로니아` exactly as COLLECTION sources under their original keys. No parent merge, new parent, source move, or duplicate provider snapshot row was introduced.

At that batch, these four parents remained enabled, UNRESOLVED, and without provider slugs. Only their reviewed query fields changed; Abercrombie & Fitch and Dsquared2 also recorded fallback guidance in notes:

| Parent key | Preserved preferred display | Current targeted query |
|---|---|---|
| curated-035 | Hermès | Hermès |
| curated-067 | Van Cleef & Arpels | Van Cleef & Arpels |
| curated-108 | Abercrombie & Fitch | Abercrombie and Fitch |
| curated-110 | Dsquared2 | DSQUARED2 |

Bond No. Nine is inactive historical guidance only: the verified Bond No. 9 parent is excluded from unresolved retry selection. The other former fallback guidance is also historical after the final approvals; it was not executed during the offline mapping updates.

The resulting counts are 145 VERIFIED + enabled parents, 18 UNRESOLVED total (17 enabled retry-eligible and the unchanged disabled Zara collaboration), 2 retired DISABLED records, 165 stored definitions, 163 non-retired parents, and 187 source labels. All 139 previously verified definitions and evidence, unrelated unresolved definitions, exact source labels, collection/alias/collaboration relationships, retired records, the Olfactive Studio alias merge, and the prior Korean source corrections are preserved. Applying this batch made no live MCP/API request, PostgreSQL access, or fragrance import.

## Second unresolved-brand retry approvals

This section records the second retry batch historically. Its unresolved states, queries, and counts are superseded by the final operator decisions below.

The operator approved six existing parents as VERIFIED and enabled: `curated-123` (Dolce&Gabbana), `curated-124` (Frederic M), `curated-125` (Alessandro), `curated-128` (Les Liquides Imaginaires), `curated-139` (Claus Porto), and `curated-144` (Maison Sybarite). Their exact provider/query names, slugs, and observed counts appear in the approval table above. All preferred display names remain unchanged, including the Korean labels. Frederic M's evidence explicitly selects candidate B over Frederic Malle; Alessandro's evidence explicitly selects standalone candidate #2 over Alessandro Dell'Acqua and Alessandro Della Torre. Les Liquides Imaginaires records explicit manual approval of the name mismatch.

At that batch, these four parents remained enabled, UNRESOLVED, and without provider slugs:

| Parent key | Preserved preferred display | Current targeted query | Reviewed handling |
|---|---|---|---|
| curated-111 | Ed Hardy | Ed Hardy | No query/mapping change; historical Christian Audigier licensing/association is future investigation context only |
| curated-133 | 미카로카 | MIKA LOKKA | Confirmed English spelling retained; possible provider-catalog absence may be investigated later |
| curated-147 | Chloé | Chloe | Only query changes to the unaccented spelling for a future retry |
| curated-161 | D.S. & Durga | DS and Durga | Advance query after the previous spelling failed; later manual fallbacks remain DS Durga, then 디에스앤더가 |

Dolce & Gabbana's former variants `Dolce and Gabbana`, `Dolce Gabbana`, and `돌체 앤 가바나` are retained only as inactive history. The verified parent is excluded from unresolved retry selection; its base and Velvet sources still select one parent.

The resulting counts are 151 VERIFIED + enabled parents, 12 UNRESOLVED total (11 enabled retry-eligible and the unchanged disabled Zara collaboration), 2 retired DISABLED records, 165 stored definitions, 163 non-retired parents, and 187 source labels. All 145 previously verified definitions and evidence, unrelated unresolved definitions and queries, exact source records/classifications/groupings, retired records, the Olfactive Studio alias merge, and the prior Korean source corrections are unchanged. No normalized parent was added or merged. Applying this batch made no live MCP/API request, PostgreSQL access, or fragrance import; no new query or fallback was executed.

## Final unseen unresolved-brand approvals

The operator approved three existing parents as VERIFIED and enabled: `curated-165` (`Kierin / kierin / 10`), `curated-172` (`Viktor&Rolf / viktor-rolf / 104`), and `curated-174` (`Salon de Nevaeh / salon-de-nevaeh / 22`). All three record explicit operator approval evidence and preserve their preferred display names and exact source records. Kierin NYC's evidence explains the manually approved relationship to provider brand Kierin; its provider/query spelling changes from `KIERIN` to `Kierin`.

Viktor & Rolf's former variants `Viktor and Rolf`, `Viktor Rolf`, and `빅터 앤 롤프` are retained only as inactive history. The verified parent is excluded from unresolved retry selection. The exact Korean display/source `살롱 드 느바에` is preserved under its existing parent.

At the final unseen batch, eight retry-eligible parents remained enabled, UNRESOLVED, and without provider slugs. This table preserves their former query history; none are now retry-eligible:

| Parent key | Preserved preferred display | Unchanged targeted query |
|---|---|---|
| curated-035 | Hermès | Hermès |
| curated-067 | Van Cleef & Arpels | Van Cleef & Arpels |
| curated-108 | Abercrombie & Fitch | Abercrombie and Fitch |
| curated-110 | Dsquared2 | DSQUARED2 |
| curated-111 | Ed Hardy | Ed Hardy |
| curated-133 | 미카로카 | MIKA LOKKA |
| curated-147 | Chloé | Chloe |
| curated-161 | D.S. & Durga | DS and Durga |

That batch resulted in 154 VERIFIED + enabled parents, 9 UNRESOLVED total (8 retry-eligible and the unchanged disabled Zara collaboration), 2 retired DISABLED records, 163 non-retired parents, 165 stored definitions, and 187 source labels. All 151 then-previously verified definitions and evidence, unrelated unresolved entries and queries, retired records, source classifications/groupings, collaboration relationships, the Olfactive Studio alias merge, and the prior Korean source corrections remained unchanged. No parent was added or merged, and applying that batch made no live MCP/API request, PostgreSQL access, or fragrance import.

## Final operator decisions: resolution complete

The operator approved seven remaining mappings. All are VERIFIED and enabled, with unchanged preferred displays and exact source labels. Their provider/query names and approved slugs are:

| Parent key | Preferred display | Provider/query identity | Verified slug | Approval evidence |
|---|---|---|---|---|
| curated-035 | Hermès | Hermès | herm-s | Direct `get_identity`: Terre d'Hermes Parfum, `herm-s-terre-d-hermes-parfum`, public ID `5002fec8-8aed-4c74-835f-3959edfcf645` |
| curated-067 | Van Cleef & Arpels | Van Cleef & Arpels | van-cleef-arpels | Direct `get_identity`: Bois Doré, `van-cleef-arpels-bois-dor`, public ID `54a7bf82-0a09-4458-9cb3-22d754e1264b` |
| curated-108 | Abercrombie & Fitch | Abercrombie & Fitch | abercrombie-fitch | Direct `get_identity`: 8 Boho Blossom, `abercrombie-fitch-8-boho-blossom`, public ID `35645322-bb48-4b34-a06c-c57741b2284a` |
| curated-111 | Ed Hardy | Christian Audigier | christian-audigier | Direct `get_identity`: Ed Hardy Villain for Women, `christian-audigier-ed-hardy-villain-for-women`, public ID `c48f3d19-2603-4ed7-959b-026260b5e691` |
| curated-147 | Chloé | Chloé | chlo | Direct `get_identity`: Love, Chloe Eau Florale, `chlo-love-chloe-eau-florale`, public ID `91bed0a1-4296-4304-ac31-148d1aa75a23` |
| curated-161 | D.S. & Durga | DS&Durga | ds-durga | Direct `get_identity`: Amber Kiso, `ds-durga-amber-kiso`, public ID `ef6f9492-fbb5-4f41-a721-f3cede69538f` |
| curated-110 | Dsquared2 | Dsquared | dsquared | **MANUAL product-level approval**: Original Wood, `dsquared-original-wood`, public ID `7a87dedf-e175-4ec4-a8c1-6724ed478ef8` |

For the six direct identity approvals, the operator supplied results explicitly containing the corresponding provider `brand_name` and `brand_slug`. Applying those decisions did not repeat the calls. Dsquared's approval is distinct: the known Dsquared2 fragrance search returned the exact `dsquared-` canonical fragrance-slug prefix and matched the documented Dsquared fallback. The operator manually approved this provider normalization; **no direct `get_identity` brand confirmation is claimed**. All seven brand fragrance counts remain null because none were supplied.

Local inspection found no existing Christian Audigier normalized parent or `christian-audigier` mapping. Case B therefore uses existing `curated-111`, preserves preferred display `Ed Hardy` and actual Korean raw source `에드 하디` at index 111, and sets provider/query identity `Christian Audigier`. Its source classification remains BRAND; no source move, second parent, independent Ed Hardy provider slug, or duplicate provider snapshot row is introduced.

`curated-133` (`미카로카`) is retained with **DISABLED**, `enabled: false`, null slug, and intended identity/query `MIKA LOKKA` as historical metadata. The targeted `MIKA LOKKA` brand check found no usable mapping; the final `Lady Muscat` product check returned `NO_USABLE_RESULT`. Operator evidence records the terminal decision and both diagnostics. This means only that no usable ScentRev mapping was found in those checks, not that the real-world brand does not exist. The source record and original UNRESOLVED provenance classification remain exact; it cannot enter resolver selection or import.

Final counts: **161 VERIFIED + enabled**, **0 retry-eligible unresolved**, **1 UNRESOLVED total** (unchanged disabled Zara collaboration), **3 DISABLED** (two historical source retirements plus terminal MIKA LOKKA), **162 status-based non-retired parents**, **165 stored definitions**, and **187 source labels**. The existing `disabledCount()` is **4**, including Zara's false enabled flag. No definition was physically removed; exactly two are the earlier historical source retirements. All 154 earlier verified definitions/evidence, all sources/classifications/groupings, both retired records, Zara, the Olfactive Studio alias merge, and the prior Korean corrections are unchanged.

Curated brand resolution is complete: no active fallback queries or further retry batch remains. Earlier unresolved-state tables and counts are history only. Existing live diagnostic tests remain gated for historical/debugging use and were not run live. This offline update made no MCP/API call, PostgreSQL access, or fragrance import.

## Offline cleanup and historical retry guidance

The operator removed `curated-090` (`퍼퓸 드 스퀘어`) and `curated-096` (`스위스 컬렉션`) from the curated preload scope. Both use the existing DISABLED status and `enabled: false`, retain their original source records and retirement notes, and have null queries and slugs. They are excluded from future resolver selection and import. No English provider identity was assigned.

Only these two source-label corrections are authorized; source indices, parent keys, and grouping remain stable:

| Parent/source key | Historical spelling | Current source and display label | Current reviewed query |
|---|---|---|---|
| curated-124 / 124 | 프레데릭 엠 | 프레드릭 엠 | Frederic M |
| curated-125 / 125 | 알렉산드로 | 알레산드로 | Alessandro |

These earlier source corrections did not approve provider identities. The second retry review now explicitly verifies standalone Frederic M and Alessandro, while preserving both corrected Korean displays/sources, indices, classifications, and separate keys. Frederic M remains independent of Frederic Malle, and Alessandro is not expanded to another provider identity. The source resource and normalized source entries both contain the corrected text; the other 185 labels remain unchanged. Tests reverse just these two corrections and check the original independent source checksum, ensuring no other text or order changes.

Current counts are 161 VERIFIED + enabled, one UNRESOLVED (the unchanged disabled Zara collaboration), and three DISABLED definitions. There are 165 stored definitions and 162 parents with status other than DISABLED. The existing manual runner's normalized-parent counter reports all 165 stored definitions; its unresolved counter is zero, and its disabled counter includes both retirements, terminal MIKA LOKKA, and Zara (4). No production counter or selection logic changed. Earlier batch statements are historical; the final decisions supersede pending resolution and active fallback guidance.

Former Abercrombie and Fitch / Abercrombie Fitch, DSQUARED2 / Dsquared, and DS and Durga / DS Durga / 디에스앤더가 query guidance is retained only as inactive history in the approved parents' notes. Bond No. Nine, former Dolce & Gabbana variants, and former Viktor & Rolf variants also remain inactive history. There are no active fallback retries after the final decisions. Preferred displays and all source groupings remain unchanged.

## Collections, aliases, and ambiguity

Creed/Royal Exclusives, Byredo/Night Veils, Chanel/Les Exclusifs, Le Labo/City Exclusives, Dior/Maison Dior/private collection, Bvlgari/Le Gemme, Serge Lutens variants, Jo Malone variants, and Dolce & Gabbana/Velvet each select one parent. Maison Dior is classified as an alias; the other grouped variants are collection metadata. The sole Bottega Veneta collection label selects the now operator-verified Bottega Veneta parent; it creates no additional parent. Attar Collection remains a provisional brand; the word “collection” alone is not a normalization rule.

Collections are metadata only. There is no Collection entity, collection persistence, or second parent import. Repeated verified slugs collapse in configured parent order, keeping the first approved definition and its name/count. Raw duplicate occurrences remain preserved. When consolidating aliases, move their source occurrences into the reviewed parent and remove the now-empty parent definition.

The following **10** source occurrences retain their provenance classifications (9 UNRESOLVED sources and one COLLABORATION). Six belong to VERIFIED parents, three to DISABLED definitions (the two historical retirements and terminal MIKA LOKKA), and one to the disabled UNRESOLVED Zara collaboration. Source provenance is preserved separately from parent verification; only VERIFIED + enabled parents are eligible for future import snapshots:

| Raw label | Handling |
|---|---|
| 퍼퓸 드 스퀘어 | Retired: DISABLED and enabled=false; history only, no retry/import |
| 스위스 컬렉션 | Retired: DISABLED and enabled=false; history only, no retry/import |
| 프레드릭 엠 | Manually VERIFIED as `Frederic M / frederic-m / 50`; independent of Frederic Malle |
| 알레산드로 | Manually VERIFIED as standalone `Alessandro / alessandro / 1` |
| 리퀴드 이미지네흐 | Manually VERIFIED as `Les Liquides Imaginaires / les-liquides-imaginaires / 45` |
| 미카로카 | Terminal DISABLED, enabled=false, null slug; `MIKA LOKKA` retained as history, no retry/import after no usable brand or Lady Muscat product result |
| 클라우스 포르토 | VERIFIED as `Claus Porto / claus-porto / 22` |
| 메종 사이브라이트 | VERIFIED as `Maison Sybarite / maison-sybarite / 6` |
| 조말론 & 자라 | Zara collaboration candidate; query `Zara`, disabled with null slug; separate from Jo Malone London |
| 살롱 드 느바에 | VERIFIED as `Salon de Nevaeh / salon-de-nevaeh / 22`; exact Korean display/source preserved |

There are no retry-eligible unresolved parents after the final decisions; the disabled Zara collaboration remains unapproved and excluded. `프레데릭말` maps to the unchanged approved Frederic Malle parent; corrected source `프레드릭 엠` stays under its independently VERIFIED Frederic M parent. Both verified identities produce distinct snapshot rows; no alias merge is authorized.

`curated-060` is now VERIFIED and enabled after the operator approved the exact candidate from the completed targeted `UERMI` resolution: provider name `UERMI`, slug `uermi`, observed count 26. The preferred display name and original source label remain exactly `웨어 미`; `resolutionQuery` remains `UERMI`, and the verification evidence records the provider canonical identity and operator approval. The confirmed source classification changes from UNRESOLVED to BRAND. This mapping is eligible for future verified snapshots without creating another parent or making a provider request during this update.

## Targeted candidate resolution

Brand resolution is complete and the current eligible list is empty. The resolver and gated diagnostic tests remain available for historical/debugging use; the following describes their existing behavior and is not a request for another live batch.

The currently connected `list_brands` MCP metadata was inspected for this implementation. It supports optional `query` (brand name or slug substring), `result_limit` (hard cap 10), `result_offset`, `include_unbranded`, and optional `next_cursor`. Curated resolution always sends:

```json
{
  "query": "Frederic Malle",
  "result_limit": 10,
  "result_offset": 0,
  "include_unbranded": false
}
```

One attempt makes exactly one queried tool call. There is no query-less fallback, cursor following, offset increment, or global pagination. A parent lacking a reviewed query is printed as unresolved with **zero** requests. An exact case/whitespace/Unicode-normalized name on a single, non-truncated candidate page reports CANDIDATE; it is not autoapproved. Multiple results or any truncated page report AMBIGUOUS. No exact match reports UNRESOLVED. A provider `no_results` payload becomes an empty targeted result. Other errors stop the chunk and surface a safe client diagnostic; there are no retries.

The resolver has no repository/import dependency and never persists, imports fragrances, fetches profiles, writes mapping files, or calls the global discovery service. API initialization/authentication follows the existing MCP client configuration.

### Eclipse: resolve a small chunk

1. Refresh the project and use Java 17. Select `ScentRevCuratedBrandResolutionSmokeTests`, **Run As → JUnit Test**, then edit that JUnit launch in **Run → Run Configurations**.
2. In **Environment**, set `SCENTREV_API_KEY` using your existing credential. No `DB_PASSWORD` is needed; this class does not start a Spring/database context.
3. In **Arguments → VM arguments**, set:

```text
-Dscentrev.curated-resolution-live-test=true
-Dscentrev.curated-resolution-start=0
-Dscentrev.curated-resolution-size=5
```

4. Run only that class. Review the printed provider names, slugs, counts and status. The hard chunk maximum is **10**; default **5**. `start` indexes enabled unresolved **parents**, including entries lacking a reviewed query. Each invocation considers only that slice, with at most one request per parent. Removing verified parents changes subsequent slice indexes, so also track stable parent keys.
5. For an unambiguous reviewed match, edit that parent in `curated-brands.json`: copy the exact returned `brandSlug`, set `verificationStatus` to `VERIFIED`, keep `enabled: true`, and record the verification evidence (provider name/slug and review date). Optionally copy the known `reportedFragranceCount`. If name matching needs investigation, revise the query first and run another explicitly selected chunk. Do not invent slugs.

No live resolution was executed by Codex. Approval is a deliberate configuration edit by the developer.

## Create a frozen curated run

`ScentRevCuratedBrandImportService.createCuratedRun()` loads/validates the resources, selects VERIFIED + enabled parents, deduplicates exact verified slugs in configured order, and calls the existing short `saveSnapshot` transaction. The immutable snapshot carrier has zero discovery pages. Creation makes **zero provider calls**, including `list_brands`, and imports **zero perfumes**. A mapping with no importable brands fails before saving a run.

It reuses `CatalogImportRun` and `CatalogImportBrandProgress`, their existing repositories, indexes, claim tokens, statuses, and transaction boundaries. No new tables or schema changes are required. Each progress row is one approved parent slug. Run scope freezes at creation: candidate results, related brands, later configuration edits, and recommendations cannot expand it.

The manual runner prints the raw-source count, normalized parent count, verified unique slug count, unresolved count, disabled count, rows created, and every unresolved name/raw-label group. Run summaries show known-count estimates separately from unknown counts, completed/failed/pending/running brands, latest recorded processed fragrances, and the persisted run ID.

### Eclipse: create, inspect, and process gradually

Use the existing `ScentRevCatalogBatchManualTests` JUnit launch. It requires `DB_PASSWORD` and:

```text
-Dscentrev.catalog-batch-manual-test=true
```

Keep the existing database URL/user configuration. This launch uses `ddl-auto=validate` and `spring.sql.init.mode=never`; the existing Phase 1 and progress schema must already exist. There is no schema creation, cleanup or data deletion. **Do not select the historical `create` action**, which performs full catalog discovery.

Create a curated snapshot (API key not required for this DB-only action):

```text
-Dscentrev.catalog-action=create-curated
```

Record the printed run ID. With the current approved mapping, this creates **161 progress rows** in order: Creed, Frederic Malle, Byredo, Tom Ford, By Kilian, Louis Vuitton, Maison Francis Kurkdjian, Le Labo, Serge Lutens, Memo Paris, Santa Maria Novella, Penhaligon's, Goutal, Diptyque, Aesop, Atelier Cologne, Acqua di Parma, Maison Margiela, Kiehl's, Nicolai Parfumeur Createur, Jo Malone London, Bvlgari, Hermès, Comme des Garcons, Spectator, The Different Company, Aerin, Chanel, Guerlain, Dior, Giorgio Armani, Yves Saint Laurent, Gucci, Bottega Veneta, Etat Libre d'Orange, Olfactive Studio, Escentric Molecules, L'Artisan Parfumeur, Terry de Gunzburg, Miller et Bertaux, 웨어 미 (UERMI), Les Parfums de Rosine, Akro, Sylvaine Delacourte, Aedes de Venustas, Arquiste, Astier de Villatte, Van Cleef & Arpels, Atkinsons, Juliette Has A Gun, Jusbox, James Heeley, A Lab on Fire, Amouroud, Balmain, Montale, Loewe, Officine Universelle Buly, Bond No. 9, Cartier, Valentino, Etro, Yves Rocher, Jean Paul Gaultier, Histoires de Parfums, Odin, Kenneth Cole, Givenchy, Armaf, Versace, Clean, Paco Rabanne, Elizabeth Arden, Comptoir Sud Pacifique, Malizia, Monotheme, Chabaud, Shanghai Tang, Innisfree, Thierry Mugler, 4711, The Body Shop, Anna Sui, Lanvin, Jimmy Choo, Abercrombie & Fitch, Lacoste, Dsquared2, Ed Hardy, Nautica, Issey Miyake, Kenzo, Carolina Herrera, Mercedes-Benz, Marc Jacobs, Montblanc, John Varvatos, Burberry, Ferrari, Dolce & Gabbana, 프레드릭 엠 (Frederic M), 알레산드로 (Alessandro), Amouage, Clive Christian, 리퀴드 이미지네흐 (Les Liquides Imaginaires), Lollia, Mancera, Reyane Tradition, Paul Smith, Illuminum, Solinotes, Miller Harris, Arrogance, Orlov Paris, 클라우스 포르토 (Claus Porto), 4160 Tuesdays, Eisenberg, Mirko Buffini, Acqua dell'Elba, 메종 사이브라이트 (Maison Sybarite), Rancé 1795, Chloé, Davidoff, Laura Mercier, Parfums de Marly, Roja Dove, Xerjoff, Trudon, Boucheron, Ramon Monegal, Yohji Yamamoto, Azzaro, Electimuss, BDK Parfums, Carner Barcelona, D.S. & Durga, Initio Parfums Privés, Kajal, Kierin NYC, Lalique, Nasomatto, Nishane, Rasasi, Royal Crown, Tiziana Terenzi, Viktor & Rolf, Ex Nihilo, 살롱 드 느바에 (Salon de Nevaeh), Nonfiction, Gritti, The House of Oud, Lorenzo Villoresi, Robert Piguet, Bortnikoff, Swiss Arabian, Ajmal, The Merchant of Venice, Bois 1920, Attar Collection, MDCI Parfums, Frapin. The 153 known counts sum to 13,097; Creed and the seven final approvals have unknown counts. Balmain's provider identity is Balmain Beauty; it produces one snapshot row with the preserved preferred display name Balmain. Olfactive Studio appears once; its two source labels cannot add another row. Only 퍼퓸 드 스퀘어, 스위스 컬렉션, terminal 미카로카, and the unchanged disabled 조말론 & 자라 collaboration remain excluded. Ed Hardy uses the single christian-audigier provider mapping with its preferred display preserved. No further brand-resolution batch is required. Spectator is now eligible for the explicitly gated 1–5-fragrance database smoke test; no smoke test or import runs automatically. Each `create-curated` invocation creates a new snapshot; keep using the original run ID to resume instead of repeatedly creating runs. Existing snapshots retain their original scope and do not gain these approved parents automatically.

Inspect without API requests:

```text
-Dscentrev.catalog-action=inspect
-Dscentrev.catalog-run-id=<printed ID>
```

Execute one bounded batch (also set `SCENTREV_API_KEY` in Environment):

```text
-Dscentrev.catalog-action=batch
-Dscentrev.catalog-run-id=<printed ID>
-Dscentrev.catalog-batch-size=1
```

The live manual default is **1 brand**; accepted override range is **1–5**. Use the run ID created with `create-curated`. Selected work comes only from its persisted snapshot. The historical runner can still inspect old runs; supplying an old global run ID does not convert its scope into curated scope.

Inspect and manually invoke another batch only when ready. Completed parents are skipped. When there are no pending or failed parents, the run completes. No automatic loop, scheduled refresh, startup import, related-brand expansion, or all-curated invocation exists.

## Execution, failure, retry and recovery

The curated facade delegates to the **same existing batch execution loop**, with its stop-on-first-failure policy. Existing historical programmatic batch methods keep their prior continue-on-brand-failure behavior. Per brand, the existing pipeline remains:

```text
verified brand_slug
  → brand-scoped search_fragrances_filtered (min_rating_votes=0)
  → sequential get_fragrance_profile calls
  → existing Phase 1 mapper, one perfume transaction at a time
```

No list_brands request is made during run creation or batch execution. Brand-scoped fragrance pagination remains the unchanged existing importer behavior. The batch logs each stored position/name and canonical slug, and the unchanged brand importer logs every 50 perfumes. Batch logs aggregate observed unique discoveries and processed counts; failures can leave discovery counts partial. Durable summaries aggregate latest recorded processed counts, not distinct lifetime catalog size or total request estimates.

Curated execution stops on **every** brand failure, including authentication, 429/rate limiting, RPC timeout and policy/access errors. Safe failure/stage diagnostics appear in the result and console; raw provider/SQL messages and credentials are suppressed by the existing pipeline. The manual runner fails its assertion after printing the result. No later reserved brand is attempted, no automatic retry occurs, and prior perfume commits remain. Unattempted reservations return to PENDING in a token-checked short transaction; completed and failed rows retain their state. Reservation attempt counters follow the existing claim/requeue semantics.

After investigating and resolving a provider problem, explicitly requeue FAILED rows:

```text
-Dscentrev.catalog-action=retry
-Dscentrev.catalog-run-id=<printed ID>
```

Then choose another `batch` invocation. Retry repeats discovery for that failed parent and uses the existing idempotent importer/identifier protection. Latest attempt counts are reset for requeued failures, so progress summaries are not cumulative across retries.

After an interrupted process or progress-transaction failure, **stop the previous executor first**, inspect, then explicitly recover:

```text
-Dscentrev.catalog-action=recover
-Dscentrev.catalog-run-id=<printed ID>
```

Recovery requeues stranded RUNNING rows and revokes the old ownership token; completed rows stay completed. Do not recover a still-active executor. A progress DB error propagates and retains the claim for recovery, rather than being swallowed. All progress writes retain the existing REQUIRES_NEW boundaries. Orchestration suspends ambient transactions; remote calls have no run-wide transaction and earlier perfume commits survive later failures.

Do not rotate credentials, switch accounts, bypass restrictions, or retry provider failures automatically. Respect the provider's limits and wait/investigate before manually resuming.

## Small database smoke test

`ScentRevCuratedBrandDatabaseSmokeTests` requires all of:

```text
Environment: SCENTREV_API_KEY, DB_PASSWORD
VM arguments:
-Dscentrev.curated-db-live-test=true
-Dscentrev.curated-smoke-brand-key=<explicit approved parent key>
```

The selected configured parent must be VERIFIED + enabled and have a trusted known `reportedFragranceCount` from **1 to 5**. If unavailable, the test skips before Spring/database context initialization; it never substitutes Creed or a large brand. Spectator (`curated-038`, observed count 1) is now eligible if the operator explicitly sets `-Dscentrev.curated-smoke-brand-key=curated-038` and all live gates. No parent is selected by default, and this approval task did not execute that smoke test.

The test snapshots verified curated parents, then explicitly processes **only that one small parent**. Other rows remain pending; it never loops over the whole curated snapshot. Use `Run As → JUnit Test` on this class only after reviewing the selected mapping. As with the manual runner, validation does not modify/recreate the schema and committed test imports remain; there is no destructive cleanup. Codex did not execute this live test.

## Maintaining allowed scope

- Add a genuinely intended raw label exactly to the source array, then add its indexed source occurrence to an existing parent or a new parent. Every raw occurrence must be accounted for; do not use resolver results to expand the user's allowed list automatically.
- Keep existing parent keys stable. Configure the desired deterministic parent order explicitly.
- To disable/remove a parent from future imports, set `enabled: false` or `verificationStatus: DISABLED`. Keep its raw source provenance. Do not delete source labels silently. Preserve verification evidence if retaining an approved slug.
- To resolve ambiguity, establish an explicit canonical identity/query, inspect one targeted page, and manually approve a single parent mapping. Collaborations require explicit scope decisions; they never expand into both brands automatically.
- Configuration edits affect **new** runs only. Existing snapshots keep their scope and stored name/count values; they do not add or remove rows during resume. Disabling a parent does not alter an already-created run; stop that run if its frozen scope is no longer desired.
- No existing perfume, Creed/Aventus row, relationship, sequence or cached on-demand data is deleted or reset. Only current Phase 1 perfume data and existing operational progress are persisted; no Phase 2, frontend, controller, deployment or configuration change is included.

## Offline validation

Ordinary Maven compilation/testing uses Java 17. New offline tests validate the exact raw-source checksum/order, all specified collection collapses, duplicate occurrences/slugs, verification exclusions and evidence, one-page MCP requests, empty/multiple/truncated matches, frozen scope, stop-on-error, retry/recovery/fencing, conservative limits, and real Spring transaction propagation with mocked persistence.

All new live tests are independently gated. The ordinary suite makes no real MCP request or PostgreSQL connection. Existing Creed/Aventus and on-demand tests remain in place. Historical full discovery/import classes remain available in the repository but the curated path never calls `ScentRevCatalogBrandDiscoveryService`. Neither a 7,548-brand traversal nor a 127,075-fragrance import is executed by this layer.

## Implementation inventory and validation report

Created files (15):

```text
docs/scentrev-curated-brand-import.md
src/main/java/com/perfume/scentrev/curated/CuratedBrandCatalog.java
src/main/java/com/perfume/scentrev/curated/CuratedBrandDefinition.java
src/main/java/com/perfume/scentrev/curated/ScentRevCuratedBrandConfiguration.java
src/main/java/com/perfume/scentrev/curated/ScentRevCuratedBrandImportService.java
src/main/java/com/perfume/scentrev/curated/ScentRevCuratedBrandResolver.java
src/main/resources/scentrev/curated-brand-source.json
src/main/resources/scentrev/curated-brands.json
src/test/java/com/perfume/scentrev/client/ScentRevTargetedBrandClientTests.java
src/test/java/com/perfume/scentrev/curated/ScentRevCuratedBrandConfigurationTests.java
src/test/java/com/perfume/scentrev/curated/ScentRevCuratedBrandResolverTests.java
src/test/java/com/perfume/scentrev/curated/ScentRevCuratedBrandResolutionSmokeTests.java
src/test/java/com/perfume/scentrev/integration/ScentRevCuratedBrandDatabaseSmokeTests.java
src/test/java/com/perfume/scentrev/service/ScentRevCuratedBrandImportServiceTests.java
src/test/java/com/perfume/scentrev/service/ScentRevCuratedBrandImportTransactionTests.java
```

Modified existing files (4):

```text
src/main/java/com/perfume/scentrev/client/ScentRevMcpClient.java
src/main/java/com/perfume/scentrev/service/ScentRevCatalogBatchImportService.java
src/main/java/com/perfume/scentrev/service/ScentRevCatalogRunProgressService.java
src/test/java/com/perfume/scentrev/integration/ScentRevCatalogBatchManualTests.java
```

The client addition is the targeted brand operation and its targeted empty-result handling. The batch addition shares the existing executor and adds conservative stop behavior plus observed discovery totals. Progress adds a token-checked stop/requeue operation using its existing transaction boundary. The manual runner adds `create-curated` and uses the conservative default/cap for manual batches.

Java 17 compile passed. Complete offline validation: **411 tests, 398 passed, 13 gated live tests skipped, zero failures/errors**. This includes **68 new passing offline cases** and **2 new gated live cases**, plus the preserved existing suite. Validation logs are under `target/phase1-validation/curated-compile.log` and `curated-final-tests.log`.

The final file-hash audit against the task-start baseline confirmed that Phase 1 entities/repositories, brand discovery/import/mapper, on-demand implementation, identifier protection, progress entities/repositories, application configuration, and existing Creed/Aventus smoke tests were unchanged. No live resolution/import, PostgreSQL access, global catalog traversal, full curated execution, Phase 2 work, frontend/deployment change, or real credential logging occurred.
