#!/usr/bin/env bash
set -euo pipefail

# === required ===
: "${ES_URL:?Set ES_URL (e.g. http://127.0.0.1:9200)}"

ALIAS_SEARCH="${ALIAS_SEARCH:-posts_search}"
ALIAS_FALLBACK="${ALIAS_FALLBACK:-posts_lab}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LAST="$ROOT_DIR/.last_posts_index"
: "${LAST:?missing .last_posts_index}"
DEST_INDEX="$(cat "$LAST")"

CURL_OPTS=(--fail-with-body -sS)
[ "${ES_INSECURE:-0}" = "1" ] && CURL_OPTS+=(-k)

AUTH_OPT=()
if [ -n "${ES_API_KEY:-}" ]; then
  AUTH_OPT=(-H "Authorization: ApiKey ${ES_API_KEY}")
elif [ -n "${ES_AUTH:-}" ]; then
  AUTH_OPT=(-u "${ES_AUTH}")
fi

# 원천 별칭 탐색 (posts_search 우선, 없으면 posts_lab)
SRC_ALIAS="__NONE__"
if curl -s -o /dev/null -w "%{http_code}\n" "${AUTH_OPT[@]}" "${ES_URL}/_alias/${ALIAS_SEARCH}" | grep -q '^20'; then
  SRC_ALIAS="${ALIAS_SEARCH}"
elif curl -s -o /dev/null -w "%{http_code}\n" "${AUTH_OPT[@]}" "${ES_URL}/_alias/${ALIAS_FALLBACK}" | grep -q '^20'; then
  SRC_ALIAS="${ALIAS_FALLBACK}"
fi

if [ "$SRC_ALIAS" = "__NONE__" ]; then
  echo "[ES] Reindex skipped (no source alias: ${ALIAS_SEARCH}/${ALIAS_FALLBACK})"
  exit 0
fi

echo "[ES] Reindex from '${SRC_ALIAS}' -> '${DEST_INDEX}'"
curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  -X POST "${ES_URL}/_reindex?wait_for_completion=true&refresh=true&timeout=5m" \
  -H 'Content-Type: application/json' \
  -d "{
    \"source\": { \"index\": \"${SRC_ALIAS}\" },
    \"dest\":   { \"index\": \"${DEST_INDEX}\" },
    \"conflicts\": \"proceed\"
  }"
echo
echo "[ES] _reindex done"
