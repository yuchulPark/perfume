\set ON_ERROR_STOP on
-- Run with psql -X -v expected_server_version_num=<verified source value>.
-- Refuse writes unless BOTH the test database and test bootstrap role match.
SELECT current_database() = 'perfume_pgvector_rehearsal'
   AND current_user = 'perfume_pgvector_admin'
   AND current_setting('server_version_num') = :'expected_server_version_num'
   AND current_setting('server_version_num')::integer / 10000 = 17 AS safe_target
\gset
\if :safe_target
\else
  \echo 'Refusing vector test: unexpected database, role or PostgreSQL version.'
  \quit 1
\endif

BEGIN;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public VERSION '0.8.7';
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                   WHERE e.extname = 'vector' AND e.extversion = '0.8.7' AND n.nspname = 'public') THEN
        RAISE EXCEPTION 'Expected vector 0.8.7 in public';
    END IF;
END $$;

-- Synthetic basis vectors only. No ai schema or persistent embedding table.
CREATE TEMP TABLE pgvector_probe (id integer PRIMARY KEY, embedding public.vector(1024)) ON COMMIT DROP;
INSERT INTO pgvector_probe
SELECT id, array_agg(CASE WHEN dimension = axis THEN magnitude ELSE 0::real END ORDER BY dimension)::public.vector(1024)
FROM (VALUES (1, 1, 1::real), (2, 2, 1::real), (3, 1, -1::real)) AS sample(id, axis, magnitude)
CROSS JOIN generate_series(1, 1024) AS dimension
GROUP BY id;

DO $$
DECLARE
    query_vector public.vector(1024);
    neighbors integer[];
BEGIN
    SELECT embedding INTO STRICT query_vector FROM pgvector_probe WHERE id = 1;
    IF (SELECT count(*) FROM pgvector_probe WHERE public.vector_dims(embedding) = 1024) <> 3 THEN
        RAISE EXCEPTION '1024-dimensional storage failed';
    END IF;
    IF EXISTS (SELECT FROM pgvector_probe
               WHERE abs((embedding OPERATOR(public.<=>) query_vector) - (id - 1)) > 0.00001) THEN
        RAISE EXCEPTION 'Cosine distance expected 0, 1, 2';
    END IF;
    SELECT array_agg(id ORDER BY embedding OPERATOR(public.<=>) query_vector, id) INTO neighbors FROM pgvector_probe;
    IF neighbors IS DISTINCT FROM ARRAY[1, 2, 3] THEN
        RAISE EXCEPTION 'Unexpected cosine nearest-neighbor order';
    END IF;
    BEGIN
        INSERT INTO pgvector_probe VALUES (4, '[1,0]'::public.vector);
        RAISE EXCEPTION 'Expected dimension mismatch to be rejected';
    EXCEPTION WHEN data_exception THEN
        NULL; -- The failed insert is rolled back to this subtransaction's savepoint.
    END;
END $$;

SELECT id, public.vector_dims(embedding) AS dimensions,
       1 - (embedding OPERATOR(public.<=>) (SELECT embedding FROM pgvector_probe WHERE id = 1)) AS cosine_similarity
FROM pgvector_probe
ORDER BY embedding OPERATOR(public.<=>) (SELECT embedding FROM pgvector_probe WHERE id = 1), id;
COMMIT;
-- Expected (id, dimensions, similarity): (1,1024,1), (2,1024,0), (3,1024,-1).
-- Only the vector extension remains in this disposable DB; the probe table is gone.
