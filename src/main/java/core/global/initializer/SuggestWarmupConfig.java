package core.global.initializer;

import core.domain.maincontent.entity.MainContentHotKeywords;
import core.domain.maincontent.repository.search.MainContentHotKeywordRepository;
import core.domain.post.entity.HotKeywords;
import core.domain.post.repository.HotKeywordRepository;
import core.domain.post.service.MainContentSuggestIndex;
import core.domain.post.service.search.PostSuggestIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SuggestWarmupConfig {

    private final HotKeywordRepository hotKeywordRepository;
    private final MainContentHotKeywordRepository mainContentHotKeywordRepository;
    private final PostSuggestIndex postSuggestIndex;
    private final MainContentSuggestIndex mainContentSuggestIndex;

    @Bean
    ApplicationRunner suggestWarmupRunner() {
        return args -> {
            log.info("[Warmup] Starting Suggestion Index Warmup from dedicated tables...");

            // 1. Post Index Warmup
            List<HotKeywords> postKeywords = hotKeywordRepository.findAll();
            for (HotKeywords hk : postKeywords) {
                postSuggestIndex.upsert(hk.getKeyword(), 1);
            }

            // 2. MainContent Index Warmup
            List<MainContentHotKeywords> mainKeywords = mainContentHotKeywordRepository.findAll();
            for (MainContentHotKeywords mk : mainKeywords) {
                mainContentSuggestIndex.upsert(mk.getKeyword(), 1);
            }

            log.info("[Warmup] Loaded {} Post keywords and {} Main keywords.",
                    postKeywords.size(), mainKeywords.size());
        };
    }
}
