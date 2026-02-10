package core.domain.post.service;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 도메인별 자동완성 메모리 인덱스 베이스
 */
public abstract class DomainSuggestIndex {

    private final AtomicReference<ConcurrentSkipListMap<String, Integer>> dictRef =
            new AtomicReference<>(new ConcurrentSkipListMap<>());

    public List<String> suggestPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) return List.of();

        String normalizedPrefix = norm(prefix);
        ConcurrentSkipListMap<String, Integer> currentDict = dictRef.get();

        var it = currentDict.tailMap(normalizedPrefix, true).entrySet().iterator();
        List<Map.Entry<String, Integer>> buf = new ArrayList<>();

        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey();
            if (!key.startsWith(normalizedPrefix)) break;
            buf.add(e);
        }

        return buf.stream()
                .sorted((a, b) -> {
                    // [1순위] 정확도: 입력값과 완전히 일치하는게 있으면 최상단 (구글 방식)
                    boolean aExact = a.getKey().equals(normalizedPrefix);
                    boolean bExact = b.getKey().equals(normalizedPrefix);
                    if (aExact != bExact) return aExact ? -1 : 1;

                    // [2순위] 길이: 짧은 단어 우선
                    int lenCompare = Integer.compare(a.getKey().length(), b.getKey().length());
                    if (lenCompare != 0) return lenCompare;

                    // [3순위] 빈도수: 길이가 같다면 인기도가 높은 순
                    return Integer.compare(b.getValue(), a.getValue());
                })
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    public void replaceAll(Map<String, Integer> newMap) {
        ConcurrentSkipListMap<String, Integer> nextDict = new ConcurrentSkipListMap<>();
        newMap.forEach((k, v) -> {
            String normalizedKey = norm(k);
            if (normalizedKey != null) nextDict.put(normalizedKey, v);
        });
        dictRef.set(nextDict);
    }

    public void upsert(String text, int deltaPop) {
        String k = norm(text);
        if (k == null || k.isBlank()) return;
        dictRef.get().merge(k, deltaPop, Integer::sum);
    }

    private static String norm(String s) {
        if (s == null) return null;
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    public void clear() {
        dictRef.get().clear();
    }

    public void putAll(Map<String, Integer> newMap) {
        if (newMap == null || newMap.isEmpty()) return;

        ConcurrentSkipListMap<String, Integer> currentDict = dictRef.get();

        newMap.forEach((k, v) -> {
            String normalizedKey = norm(k);
            if (normalizedKey != null && !normalizedKey.isBlank()) {
                // 기존에 있으면 빈도수를 합산하거나, 요청하신 대로 '1'로 유지(merge 사용)
                currentDict.merge(normalizedKey, v, (oldVal, newVal) -> oldVal);
            }
        });
    }
}