#!/usr/bin/env bash
set -euo pipefail

ES="${ES_URL:-http://localhost:9200}"
ALIAS_SEARCH="${ALIAS_SEARCH:-posts_search}"
ALIAS_SUGG="${ALIAS_SUGG:-posts_suggest}"
ALIAS_WRITE="${ALIAS_WRITE:-posts_write}"
PREFIX="${INDEX_PREFIX:-posts-lab}"   # 기존 물리 인덱스 접두사

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LAST="$ROOT_DIR/.last_posts_index"

AUTH_OPT=()
[ -n "${ES_AUTH:-}" ] && AUTH_OPT=(-u "$ES_AUTH")

if [ ! -f "$LAST" ]; then
  echo "No .last_posts_index file. Run 02-create_index.sh first."
  exit 1
fi
IDX="$(cat "$LAST")"

# 새 인덱스에 읽기/쓰기 별칭 3종 부여 (기존 모든 인덱스에서 제거 후 ADD)
curl -fsS -X POST "$ES/_aliases" \
  -H 'Content-Type: application/json' \
  "${AUTH_OPT[@]}" \
  -d "{
    \"actions\": [
      { \"remove\": { \"alias\": \"$ALIAS_SEARCH\", \"index\": \"$PREFIX-*\", \"must_exist\": false } },
      { \"remove\": { \"alias\": \"$ALIAS_SUGG\",  \"index\": \"$PREFIX-*\", \"must_exist\": false } },
      { \"remove\": { \"alias\": \"$ALIAS_WRITE\", \"index\": \"$PREFIX-*\", \"must_exist\": false } },

      { \"add\":    { \"alias\": \"$ALIAS_SEARCH\", \"index\": \"$IDX\" } },
      { \"add\":    { \"alias\": \"$ALIAS_SUGG\",  \"index\": \"$IDX\" } },
      { \"add\":    { \"alias\": \"$ALIAS_WRITE\", \"index\": \"$IDX\", \"is_write_index\": true } }
    ]
  }"

echo "[OK] alias '$ALIAS_SEARCH'/'$ALIAS_SUGG'/'$ALIAS_WRITE' -> $IDX"
