package core.global.initializer;

import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.service.SuggestMemoryIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SuggestWarmupConfig {

    private final PostSearchRepositoryCustom searchRepository;
    private final SuggestMemoryIndex memoryIndex;

    @Bean
    ApplicationRunner suggestWarmupRunner() {
    log.info("runner start");
        return args -> {
            int topN = 2000;
            List<String> hotKeys = searchRepository.findHotKeywordsOrTitles(topN);
            // --- 확인 로그 ---
            System.out.println("[Warmup] hotKeys size = " + (hotKeys == null ? 0 : hotKeys.size()));
            hotKeys.stream().limit(10).forEach(k -> System.out.println("[Warmup] sample=" + k));

            for (String k : hotKeys) {
                memoryIndex.upsert(k, 1);
            }
            // memoryIndex 내부 카운트/크기 노출용 임시 메서드가 없다면 추가 권장
            System.out.println("[Warmup] memoryIndex loaded.");
        };

    }
}
