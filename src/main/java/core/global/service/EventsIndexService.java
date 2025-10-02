package core.global.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventsIndexService {

    private final ElasticsearchClient es;

    public void logLogin(String userId, String plan) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("@timestamp", Instant.now().toString()); // UTC ISO8601
        doc.put("user_id", userId);
        doc.put("event", "login");
        if (plan != null)   doc.put("plan", plan);

        try {
            es.index(i -> i
                            .index("events_write")   // 별칭으로 쓰기
                            .document(doc)
                    // .refresh(Refresh.True) // (테스트 시에만) 즉시 검색에 보이게
            );
        } catch (Exception e) {
            // 로그인 플로우를 막지 않도록 실패는 경고만
            log.warn("ES events_write index failed: {}", e.getMessage());
        }
    }

    public void logActive(String userId, String device) {
        var doc = new HashMap<String, Object>();
        doc.put("@timestamp", Instant.now().toString());
        doc.put("user_id", userId);
        doc.put("event", "active");
        if (device != null) doc.put("device", device);
        try { es.index(i -> i.index("events_write").document(doc)); }
        catch (Exception e) { log.warn("ES active send failed: {}", e.getMessage()); }
    }
}