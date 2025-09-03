#!/usr/bin/env bash
set -euo pipefail

ES="${ES_URL:-http://localhost:9200}"
TEMPLATE_NAME="${TEMPLATE_NAME:-posts-template-v1}"

# 스크립트 기준 상대경로(서버에 git이 없어도 OK)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TPL="$ROOT_DIR/posts-template.json"

AUTH_OPT=()
[ -n "${ES_AUTH:-}" ] && AUTH_OPT=(-u "$ES_AUTH")

curl -fsS -X PUT "$ES/_index_template/$TEMPLATE_NAME" \
  -H 'Content-Type: application/json' \
  "${AUTH_OPT[@]}" \
  --data-binary @"$TPL"

echo "[OK] applied index template '$TEMPLATE_NAME' to $ES"
