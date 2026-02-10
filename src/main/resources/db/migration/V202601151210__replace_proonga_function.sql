-- 1. [필수] 자동완성 추출 함수 정의 (테스트 DB 환경을 위해 반드시 포함해야 함)
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