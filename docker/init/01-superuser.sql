DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'testuser') THEN
CREATE ROLE testuser WITH LOGIN PASSWORD 'testpass' SUPERUSER;
END IF;
END $$;

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
