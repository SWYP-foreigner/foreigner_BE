package core.domain.post.service.search;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 매우 단순한 prefix 사전:
 * - 키: 텍스트(제목/검색어/태그 등 짧은 후보)
 * - 값: 인기(pop) 점수
 * - prefix 조회는 정렬맵의 tailMap으로 근사
 */
@Component
public class SuggestMemoryIndex {

    // AtomicReference를 사용하여 맵 전체를 원자적으로 교체 가능하게 함
    private final AtomicReference<ConcurrentSkipListMap<String, Integer>> dictRef =
            new AtomicReference<>(new ConcurrentSkipListMap<>());

    public List<String> suggestPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) return List.of();

        // 현재 시점의 맵 스냅샷을 가져옴
        ConcurrentSkipListMap<String, Integer> currentDict = dictRef.get();

        var it = currentDict.tailMap(prefix, true).entrySet().iterator();
        List<Map.Entry<String, Integer>> buf = new ArrayList<>(limit * 4);

        while (it.hasNext() && buf.size() < limit * 8) {
            var e = it.next();
            String key = e.getKey();
            if (!key.startsWith(prefix)) break;
            buf.add(e);
        }

        buf.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue()); // pop desc
            if (c != 0) return c;
            return Integer.compare(a.getKey().length(), b.getKey().length()); // length asc
        });

        return buf.stream().limit(limit).map(Map.Entry::getKey).toList();
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
}