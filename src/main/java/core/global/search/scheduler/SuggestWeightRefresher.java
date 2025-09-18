package core.global.search.scheduler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.json.JsonData;
import core.global.search.SearchConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestWeightRefresher {

    private final ElasticsearchClient es;

    // 매일 03:00
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void refreshWeights() {
        try {
            es.updateByQuery(u -> u
                    .index(SearchConstants.INDEX_POSTS_SUGGEST)
                    .conflicts(Conflicts.Proceed)   // ⬅️ enum 사용
                    .refresh(true)
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source("""
                                        long now = new Date().getTime();
                                        long cc = (ctx._source.containsKey('checkCount') && ctx._source.checkCount != null) ? ctx._source.checkCount : 0;
                                        long created = (ctx._source.containsKey('createdAt') && ctx._source.createdAt != null) ? ctx._source.createdAt : now;
                                        double ageDays = (now - created) / 86400000.0;
                                        double fresh = Math.exp(-ageDays / params.halfLifeDays);
                                        double pop = Math.log1p(cc) / Math.log1p(params.popNorm);
                                        if (pop > 1.0) pop = 1.0;
                                        double score = params.alpha * pop + (1 - params.alpha) * fresh;
                                        int w = (int)Math.round(100.0 * Math.max(0.0, Math.min(1.0, score)));
                                        if (ctx._source.contentSuggest instanceof Map) { ctx._source.contentSuggest.weight = w; }
                                        if (ctx._source.contentSuggestExact instanceof Map) { ctx._source.contentSuggestExact.weight = w; }
                                    """)
                            .params(Map.of(
                                    "halfLifeDays", JsonData.of(30),
                                    "popNorm", JsonData.of(1000),
                                    "alpha", JsonData.of(0.65)
                            ))
                    ))
            );
            log.info("Suggest weights refreshed");
        } catch (IOException e) {
            log.error("Failed to refresh suggest weights", e);
        }
    }
}
