package core.global.initializer;

import core.domain.post.entity.HotKeywords;
import core.domain.post.repository.HotKeywordRepository;
import core.domain.post.service.search.SuggestMemoryIndex;
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
    private final SuggestMemoryIndex memoryIndex;

    @Bean
    ApplicationRunner suggestWarmupRunner() {
        return args -> {
            log.info("[Warmup] Starting Warmup from hot_keywords table...");

            // 90일치 게시글 뒤지는 대신, 미리 저장된 테이블만 조회 (매우 빠름)
            List<HotKeywords> savedKeywords = hotKeywordRepository.findAll();

            for (HotKeywords hk : savedKeywords) {
                memoryIndex.upsert(hk.getKeyword(), 1);
            }

            log.info("[Warmup] Loaded {} keywords into MemoryIndex.", savedKeywords.size());
        };
    }
}
