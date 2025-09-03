#!/usr/bin/env bash
set -euo pipefail

ES="${ES_URL:-http://localhost:9200}"
PREFIX="${INDEX_PREFIX:-posts-lab}"   # 물리 인덱스 접두사 유지
NEW_INDEX="${PREFIX}-$(date +%Y%m%d-%H%M%S)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LAST="$ROOT_DIR/.last_posts_index"

AUTH_OPT=()
[ -n "${ES_AUTH:-}" ] && AUTH_OPT=(-u "$ES_AUTH")

# 템플릿이 PREFIX-* 패턴을 잡고 있으므로 빈 바디로 생성
curl -fsS -X PUT "$ES/$NEW_INDEX" \
  -H 'Content-Type: application/json' \
  "${AUTH_OPT[@]}" \
  -d '{}'

echo -n "$NEW_INDEX" > "$LAST"
echo "[OK] created index: $NEW_INDEX (saved to $LAST)"
