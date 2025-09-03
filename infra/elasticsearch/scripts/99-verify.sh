#!/usr/bin/env bash
set -euo pipefail

ES="${ES_URL:-http://localhost:9200}"
ALIAS_SEARCH="${ALIAS_SEARCH:-posts_search}"
ALIAS_SUGG="${ALIAS_SUGG:-posts_suggest}"
ALIAS_WRITE="${ALIAS_WRITE:-posts_write}"
PREFIX="${INDEX_PREFIX:-posts-lab}"

AUTH_OPT=()
[ -n "${ES_AUTH:-}" ] && AUTH_OPT=(-u "$ES_AUTH")

echo "== Aliases =="
curl -fsS "${AUTH_OPT[@]}" "$ES/_cat/aliases?v&h=alias,index,is_write_index" ; echo
echo "== Indices ($PREFIX-*) =="
curl -fsS "${AUTH_OPT[@]}" "$ES/_cat/indices/${PREFIX}-*?v" ; echo
echo "== Mapping snapshot (via $ALIAS_SEARCH) =="
curl -fsS "${AUTH_OPT[@]}" "$ES/$ALIAS_SEARCH/_mapping" | head -n 60 ; echo
echo "== Count (via $ALIAS_SEARCH) =="
curl -fsS "${AUTH_OPT[@]}" "$ES/$ALIAS_SEARCH/_count" ; echo

echo "== Quick search smoke =="
curl -fsS "${AUTH_OPT[@]}" -X POST "$ES/$ALIAS_SEARCH/_search" \
  -H 'Content-Type: application/json' -d @- <<'JSON'
{ "size": 1, "_source": ["postId","content"],
  "query": { "match": { "content": "visa accommodation" } } }
JSON
echo

echo "== Quick suggest smoke =="
curl -fsS "${AUTH_OPT[@]}" -X POST "$ES/$ALIAS_SUGG/_search" \
  -H 'Content-Type: application/json' -d @- <<'JSON'
{ "size": 0,
  "suggest": {
    "exact": { "prefix": "vis", "completion": { "field": "contentSuggestExact", "size": 5, "skip_duplicates": true } },
    "fuzzy": { "prefix": "vis", "completion": { "field": "contentSuggest", "size": 5, "skip_duplicates": true, "fuzzy": { "fuzziness": "AUTO" } } }
  }
}
JSON
echo

echo "== Write alias is_write_index check =="
curl -fsS "${AUTH_OPT[@]}" "$ES/_cat/aliases/$ALIAS_WRITE?v&h=alias,index,is_write_index" ; echo
