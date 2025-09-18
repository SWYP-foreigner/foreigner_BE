#!/usr/bin/env bash
set -euo pipefail

: "${ES_URL:?}"
INDEX_PREFIX="${INDEX_PREFIX:-posts-lab}"
KEEP_LAST="${KEEP_LAST:-1}"           # 최신부터 n개 보관
DRY_RUN="${DRY_RUN:-1}"               # 1=미리보기, 0=실삭제
NEW_INDEX="${NEW_INDEX:-}"            # 새 인덱스를 추가 보호하고 싶으면 전달

CURL_OPTS=(--fail-with-body -sS)
[ "${ES_INSECURE:-0}" = "1" ] && CURL_OPTS+=(-k)

AUTH_OPT=()
if [ -n "${ES_API_KEY:-}" ]; then
  AUTH_OPT=(-H "Authorization: ApiKey ${ES_API_KEY}")
elif [ -n "${ES_AUTH:-}" ]; then
  AUTH_OPT=(-u "${ES_AUTH}")
fi

# 1) 현재 별칭이 가리키는 인덱스 수집(삭제 금지 목록)
skip_indices=$(curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  "$ES_URL/_cat/aliases/posts_search,posts_suggest,posts_write?format=json&h=index" \
  | jq -r '.[].index' | sort -u || true)

# NEW_INDEX가 있으면 보호 목록에 포함
if [ -n "${NEW_INDEX}" ]; then
  skip_indices=$(printf "%s\n%s\n" "$skip_indices" "$NEW_INDEX" | sort -u)
fi

# JSON 배열로 변환
SKIP_JSON=$(printf "%s\n" $skip_indices | jq -R . | jq -s .)

# 2) 후보 인덱스(접두어 매칭) 중 보호 대상 제외 → 생성시각 내림차순 정렬
indices_desc=$(curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" \
  "$ES_URL/_cat/indices/${INDEX_PREFIX}-*?h=index,creation.date&format=json" \
  | jq -r --argjson skip "$SKIP_JSON" '
      map(select(.index as $i | ($skip | index($i)) | not))
      | sort_by(."creation.date" | tonumber)
      | reverse
      | .[].index' || true)

# 3) KEEP_LAST 제외하고 삭제 목록 산출
del_list=$(printf "%s\n" $indices_desc | tail -n +$((KEEP_LAST+1)))

[ -z "${del_list:-}" ] && { echo "[OK] 삭제 대상 없음"; exit 0; }

echo "[TARGET] 삭제 대상:"
printf '  %s\n' $del_list

if [ "$DRY_RUN" = "1" ]; then
  echo "[DRY-RUN] 실제 삭제하지 않았습니다. DRY_RUN=0 으로 실행하면 삭제합니다."
  exit 0
fi

for idx in $del_list; do
  echo "[DEL] $idx"
  curl "${CURL_OPTS[@]}" "${AUTH_OPT[@]}" -X DELETE "$ES_URL/$idx" || true
done

echo "[DONE]"