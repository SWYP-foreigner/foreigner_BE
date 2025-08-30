#!/usr/bin/env bash
set -euo pipefail
ES="${ES_URL:-http://localhost:9200}"
IDX="posts-$(date +%Y%m%d-%H%M%S)"

curl -sS -X PUT "$ES/$IDX" -H 'Content-Type: application/json' -d '{}'
echo "$IDX" > $(git rev-parse --show-toplevel)/infra/elasticsearch/.last_posts_index

echo
echo "[OK] created index: $IDX"
