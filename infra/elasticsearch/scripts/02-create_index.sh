#!/usr/bin/env bash
set -euo pipefail

# === required ===
: "${ES_URL:?Set ES_URL (e.g. http://10.0.1.25:9200)}"
# === optional ===
INDEX_PREFIX="${INDEX_PREFIX:-posts-lab}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60s}"   # 클러스터/인덱스 헬스 대기 타임아웃
WAIT_FOR_STATUS="${WAIT_FOR_STATUS:-yellow}"  # 최소 yellow까지 대기

ES="$ES_URL"
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

# === 헬스 대기(재발 방지 핵심) ===
echo "[ES] Waiting cluster to reach ${WAIT_FOR_STATUS} (timeout=${HEALTH_TIMEOUT})"
curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  "${ES}/_cluster/health?wait_for_status=${WAIT_FOR_STATUS}&timeout=${HEALTH_TIMEOUT}" >/dev/null

echo "[ES] Waiting index ${NEW_INDEX} to reach ${WAIT_FOR_STATUS} (timeout=${HEALTH_TIMEOUT})"
if ! curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  "${ES}/_cluster/health/${NEW_INDEX}?wait_for_status=${WAIT_FOR_STATUS}&timeout=${HEALTH_TIMEOUT}" >/dev/null; then
  echo "[!] Shard allocation seems stuck. Allocation explain:"
  curl -sS -X GET ${AUTH_OPT[@]} "${ES}/_cluster/allocation/explain" \
    -H 'Content-Type: application/json' \
    -d "{\"index\":\"${NEW_INDEX}\",\"shard\":0,\"primary\":true}" || true
  exit 1
fi

echo "[OK] index ${NEW_INDEX} is ready (${WAIT_FOR_STATUS}+)"
