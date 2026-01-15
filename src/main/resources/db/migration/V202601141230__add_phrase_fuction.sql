CREATE OR REPLACE FUNCTION pgroonga_extract_phrase(t text, q text, max_words integer)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
SELECT COALESCE(
               btrim(
                       regexp_replace(
                               substring($1 from '(?i)(' || $2 || '\S*(?:\s+\S+){0,' || $3 || '})'),
                               '(?i)\s*[''’]?s\b', '', 'g'
                       ),
                       ' ,.?!'
               ),
               ''
       )
           $$;