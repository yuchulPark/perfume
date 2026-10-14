\set ON_ERROR_STOP on
-- psql -X -qAt -v expected_db=<source or clone DB> -f this-file.sql
-- JSONL metadata/checksums only; no row contents, passwords or password hashes.
SELECT current_database() = :'expected_db'
   AND current_setting('server_version_num')::integer / 10000 = 17 AS safe_target
\gset
\if :safe_target
\else
  \echo 'Refusing manifest: unexpected database or PostgreSQL major version.'
  \quit 1
\endif

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog;
SET LOCAL timezone = 'UTC';
SET LOCAL datestyle = 'ISO, YMD';
SET LOCAL extra_float_digits = 3;
SET LOCAL bytea_output = 'hex';

SELECT jsonb_build_object('kind', 'server', 'key', 'version', 'value', current_setting('server_version_num'));
-- Normalize the database name, but compare its owner, effective ACL and locale.
SELECT jsonb_build_object('kind', 'database', 'key', 'properties', 'value', jsonb_build_object(
    'owner', pg_get_userbyid(d.datdba), 'encoding', pg_encoding_to_char(d.encoding),
    'collate', d.datcollate, 'ctype', d.datctype, 'locale_provider', d.datlocprovider,
    'locale', d.datlocale, 'icu_rules', d.daticurules, 'collation_version', d.datcollversion,
    'acl', ARRAY(SELECT a::text FROM unnest(coalesce(d.datacl, acldefault('d', d.datdba))) a ORDER BY a::text COLLATE "C")))
FROM pg_database d WHERE datname = current_database();

SELECT jsonb_build_object('kind', 'inventory', 'key', 'tables', 'value', count(*))
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind IN ('r', 'p');

SELECT jsonb_build_object('kind', 'schema', 'key', n.nspname, 'value', jsonb_build_object(
    'owner', pg_get_userbyid(n.nspowner),
    'acl', ARRAY(SELECT a::text FROM unnest(coalesce(n.nspacl, acldefault('n', n.nspowner))) a ORDER BY a::text COLLATE "C")))
FROM pg_namespace n WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' ORDER BY n.nspname;

SELECT jsonb_build_object('kind', 'relation', 'key', format('%I.%I', n.nspname, c.relname), 'value', jsonb_build_object(
    'kind', c.relkind, 'owner', pg_get_userbyid(c.relowner), 'row_security', c.relrowsecurity,
    'force_row_security', c.relforcerowsecurity,
    'acl', ARRAY(SELECT a::text FROM unnest(coalesce(c.relacl, acldefault(CASE WHEN c.relkind = 'S' THEN 's'::"char" ELSE 'r'::"char" END, c.relowner))) a ORDER BY a::text COLLATE "C")))
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
ORDER BY n.nspname, c.relname;

SELECT jsonb_build_object('kind', 'column', 'key', format('%I.%I.%I', n.nspname, c.relname, a.attname), 'value', jsonb_build_object(
    'position', row_number() OVER (PARTITION BY c.oid ORDER BY a.attnum),
    'type', format_type(a.atttypid, a.atttypmod), 'not_null', a.attnotnull,
    'identity', a.attidentity, 'generated', a.attgenerated, 'default', pg_get_expr(d.adbin, d.adrelid),
    'acl', ARRAY(SELECT x::text FROM unnest(a.attacl) x ORDER BY x::text COLLATE "C")))
FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND a.attnum > 0 AND NOT a.attisdropped
AND c.relkind IN ('r', 'p', 'v', 'm', 'f') ORDER BY n.nspname, c.relname, a.attnum;

SELECT jsonb_build_object('kind', 'constraint', 'key', format('%I.%I.%I', n.nspname, c.relname, con.conname),
    'value', jsonb_build_object('definition', pg_get_constraintdef(con.oid), 'validated', con.convalidated))
FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema';

SELECT jsonb_build_object('kind', 'index', 'key', format('%I.%I', n.nspname, c.relname),
    'value', jsonb_build_object('definition', pg_get_indexdef(c.oid), 'valid', i.indisvalid))
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema';

SELECT jsonb_build_object('kind', 'default_acl', 'key', format('%I/%s/%s', pg_get_userbyid(d.defaclrole), coalesce(n.nspname, ''), d.defaclobjtype),
    'value', ARRAY(SELECT a::text FROM unnest(d.defaclacl) a ORDER BY a::text COLLATE "C"))
FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace;

SELECT jsonb_build_object('kind', 'role', 'key', rolname, 'value', jsonb_build_object(
    'superuser', rolsuper, 'inherit', rolinherit, 'create_role', rolcreaterole, 'create_db', rolcreatedb,
    'login', rolcanlogin, 'replication', rolreplication, 'bypass_rls', rolbypassrls,
    'connection_limit', rolconnlimit, 'valid_until', rolvaliduntil,
    'settings_sha256', encode(sha256(convert_to(coalesce(rolconfig::text, ''), 'UTF8')), 'hex')))
FROM pg_roles WHERE rolname !~ '^pg_' AND rolname <> 'perfume_pgvector_admin';

SELECT jsonb_build_object('kind', 'membership', 'key', format('%I/%I/%I', pg_get_userbyid(roleid), pg_get_userbyid(member), pg_get_userbyid(grantor)),
    'value', jsonb_build_object('admin', admin_option, 'inherit', inherit_option, 'set', set_option))
FROM pg_auth_members
WHERE pg_get_userbyid(member) <> 'perfume_pgvector_admin'
  AND pg_get_userbyid(roleid) <> 'perfume_pgvector_admin'
  -- initdb's predefined-role memberships may use the bootstrap role as grantor.
  -- Compare application-role grants, not these cluster initialization internals.
  AND (pg_get_userbyid(member) !~ '^pg_' OR pg_get_userbyid(roleid) !~ '^pg_');

SELECT jsonb_build_object('kind', 'db_role_settings', 'key', coalesce(r.rolname, 'ALL'),
    'value', encode(sha256(convert_to(s.setconfig::text, 'UTF8')), 'hex'))
FROM pg_db_role_setting s LEFT JOIN pg_roles r ON r.oid = s.setrole
WHERE s.setdatabase = (SELECT oid FROM pg_database WHERE datname = current_database());

SELECT jsonb_build_object('kind', 'sequence_definition', 'key', format('%I.%I', n.nspname, c.relname), 'value', jsonb_build_object(
    'type', format_type(s.seqtypid, NULL), 'start', s.seqstart, 'increment', s.seqincrement,
    'min', s.seqmin, 'max', s.seqmax, 'cache', s.seqcache, 'cycle', s.seqcycle,
    'owned_by', (SELECT format('%I.%I.%I', tn.nspname, tc.relname, a.attname)
                 FROM pg_depend d JOIN pg_class tc ON tc.oid = d.refobjid JOIN pg_namespace tn ON tn.oid = tc.relnamespace
                 JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = d.refobjsubid
                 WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i'))))
FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema';

-- Execute SELECTs generated from quoted catalog identifiers. Never call nextval.
SELECT format('SELECT jsonb_build_object(''kind'', ''sequence_state'', ''key'', %L, ''value'', jsonb_build_object(''last_value'', last_value, ''is_called'', is_called)) FROM %I.%I;',
              format('%I.%I', n.nspname, c.relname), n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind = 'S' ORDER BY n.nspname, c.relname
\gexec

-- Order-independent SHA-256 over sorted per-row SHA-256 hashes. ONLY avoids
-- double counting inherited/partitioned rows. Empty tables have a defined hash.
SELECT format($query$
SELECT jsonb_build_object('kind', 'data', 'key', %L, 'value', jsonb_build_object(
    'rows', count(*), 'sha256', encode(sha256(convert_to(coalesce(string_agg(h, '' ORDER BY h COLLATE "C"), ''), 'UTF8')), 'hex')))
FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text, 'UTF8')), 'hex') AS h FROM ONLY %I.%I AS t) hashes;
$query$, format('%I.%I', n.nspname, c.relname), n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind IN ('r', 'p', 'm') ORDER BY n.nspname, c.relname
\gexec

SELECT jsonb_build_object('kind', 'manifest', 'key', 'complete', 'value', 1);
COMMIT;
