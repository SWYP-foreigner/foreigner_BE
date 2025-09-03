#!/usr/bin/env bash
set -euo pipefail

# === required ===
: "${ES_URL:?Set ES_URL (e.g. http://10.0.1.25:9200)}"
# === optional ===
ALIAS_SEARCH="${ALIAS_SEARCH:-posts_search}"
ALIAS_SUGG="${ALIAS_SUGG:-posts_suggest}"
ALIAS_WRITE="${ALIAS_WRITE:-posts_write}"
INDEX_PREFIX="${INDEX_PREFIX:-posts-lab}"

ES="$ES_URL"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LAST="$ROOT_DIR/.last_posts_index"

[ -f "${LAST}" ] || { echo "No .last_posts_index file. Run 02-create_index.sh first."; exit 1; }
IDX="$(cat "${LAST}")"

CURL_OPTS=(--fail-with-body -sS)
[ "${ES_INSECURE:-0}" = "1" ] && CURL_OPTS+=(-k)

AUTH_OPT=()
if [ -n "${ES_API_KEY:-}" ]; then
  AUTH_OPT=(-H "Authorization: ApiKey ${ES_API_KEY}")
elif [ -n "${ES_AUTH:-}" ]; then
  AUTH_OPT=(-u "${ES_AUTH}")
fi

echo "[ES] Switching aliases to ${IDX}"
read -r -d '' BODY <<JSON
{
  "actions": [
    { "remove": { "alias": "${ALIAS_SEARCH}", "index": "${INDEX_PREFIX}-*", "must_exist": false } },
    { "remove": { "alias": "${ALIAS_SUGG}",  "index": "${INDEX_PREFIX}-*", "must_exist": false } },
    { "remove": { "alias": "${ALIAS_WRITE}", "index": "${INDEX_PREFIX}-*", "must_exist": false } },

    { "add":    { "alias": "${ALIAS_SEARCH}", "index": "${IDX}" } },
    { "add":    { "alias": "${ALIAS_SUGG}",  "index": "${IDX}" } },
    { "add":    { "alias": "${ALIAS_WRITE}", "index": "${IDX}", "is_write_index": true } }
  ]
}
JSON

curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  -X POST "${ES}/_aliases" \
  -H 'Content-Type: application/json' \
  -d "${BODY}"

echo "[OK] alias '${ALIAS_SEARCH}'/'${ALIAS_SUGG}'/'${ALIAS_WRITE}' -> ${IDX}"
