#!/usr/bin/env bash
set -euo pipefail
ES="${ES_URL:-http://localhost:9200}"
echo "== Alias =="
curl -sS "$ES/_alias/posts"; echo
echo "== Template =="
curl -sS "$ES/_index_template/posts-template"; echo
echo "== Indices =="
curl -sS "$ES/_cat/indices/posts-*?v"; echo
