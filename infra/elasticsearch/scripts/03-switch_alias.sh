#!/usr/bin/env bash
set -euo pipefail
ES="${ES_URL:-http://localhost:9200}"
LAST_FILE="$(git rev-parse --show-toplevel)/infra/elasticsearch/.last_posts_index"
if [ ! -f "$LAST_FILE" ]; then
  echo "No .last_posts_index file. Run 02-create_index.sh first."
  exit 1
fi
IDX="$(cat "$LAST_FILE")"

# posts 별칭을 새 인덱스에 연결하고 is_write_index=true로 전환
curl -sS -X POST "$ES/_aliases" -H 'Content-Type: application/json' -d "{
  \"actions\": [
    { \"remove\": { \"alias\": \"posts\", \"index\": \"posts-*\", \"must_exist\": false } },
    { \"add\":    { \"alias\": \"posts\", \"index\": \"$IDX\", \"is_write_index\": true } }
  ]
}"

echo
echo "[OK] alias 'posts' -> $IDX (is_write_index=true)"
