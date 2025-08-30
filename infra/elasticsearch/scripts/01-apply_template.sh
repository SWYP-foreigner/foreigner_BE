set -euo pipefail
ES="${ES_URL:-http://localhost:9200}"

curl -sS -X PUT "$ES/_index_template/posts-template" \
  -H 'Content-Type: application/json' \
  --data-binary @$(git rev-parse --show-toplevel)/infra/elasticsearch/posts-template.json

echo
echo "[OK] posts-template applied to $ES"