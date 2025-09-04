#!/usr/bin/env bash
set -euo pipefail

# === required ===
: "${ELASTICSEARCH_HOST:?Set ELASTICSEARCH_HOST (e.g. http://10.0.1.25:9200)}"
# === optional ===
INDEX_PREFIX="${INDEX_PREFIX:-posts-lab}"

ES="$ELASTICSEARCH_HOST"
NEW_INDEX="${INDEX_PREFIX}-$(date +%Y%m%d-%H%M%S)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LAST="$ROOT_DIR/.last_posts_index"

CURL_OPTS=(--fail-with-body -sS)
[ "${ES_INSECURE:-0}" = "1" ] && CURL_OPTS+=(-k)

AUTH_OPT=()
if [ -n "${ES_API_KEY:-}" ]; then
  AUTH_OPT=(-H "Authorization: ApiKey ${ES_API_KEY}")
elif [ -n "${ES_AUTH:-}" ]; then
  AUTH_OPT=(-u "${ES_AUTH}")
fi

echo "[ES] Creating index: ${NEW_INDEX}"
curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  -X PUT "${ES}/${NEW_INDEX}" \
  -H 'Content-Type: application/json' \
  -d '{}'

echo -n "${NEW_INDEX}" > "${LAST}"
echo "[OK] created index: ${NEW_INDEX} (saved to ${LAST})"
