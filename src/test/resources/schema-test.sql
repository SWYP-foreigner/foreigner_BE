CREATE EXTENSION IF NOT EXISTS pgroonga;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE OR REPLACE FUNCTION unaccent_immutable(text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$ SELECT unaccent($1) $$;

CREATE OR REPLACE FUNCTION pgroonga_match(t text, q text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$ SELECT ($1 &@~ pgroonga_query_escape($2))::boolean $$;

CREATE OR REPLACE FUNCTION pgroonga_match_prefix(t text, q text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$ SELECT ($1 &@~ (pgroonga_query_escape($2) || '*'))::boolean $$;

-- ✅ post 테이블 생성 이후 실행되므로 여기서 OK
CREATE OR REPLACE FUNCTION pgroonga_score_of(pid bigint)
RETURNS double precision
LANGUAGE sql
STABLE
AS $$
SELECT pgroonga_score(tableoid, ctid)
FROM post
WHERE post_id = $1
    LIMIT 1
$$;
