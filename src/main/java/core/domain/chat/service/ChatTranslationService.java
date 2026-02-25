package core.domain.chat.service;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageTranslation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;



import core.domain.chat.repository.ChatMessageTranslationRepository;
import core.global.service.TranslationService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTranslationService {

    private final ChatMessageTranslationRepository translationRepository;
    private final TranslationService externalTranslationService;
    private final RedisTemplate<String, String> redisTemplate;
    @Qualifier("taskExecutor")
    private final Executor taskExecutor;

    @Autowired
    @Lazy
    private ChatTranslationService self;

    private static final String CACHE_PREFIX = "trans:";
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    @Transactional(readOnly = true) // 읽기 전용 트랜잭션 권장
    public Map<Long, String> getTranslatedMessages(List<ChatMessage> messages, String targetLang) {
        if (messages.isEmpty() || targetLang == null) return Collections.emptyMap();

        Map<Long, String> resultMap = new HashMap<>();
        List<ChatMessage> missingMessages = new ArrayList<>();

        // 1. Redis에서 먼저 조회 (Bulk Get)
        List<String> keys = messages.stream()
                .map(msg -> getCacheKey(msg.getId(), targetLang))
                .toList();
        List<String> cachedValues = redisTemplate.opsForValue().multiGet(keys);

        if (cachedValues != null) {
            for (int i = 0; i < messages.size(); i++) {
                String value = cachedValues.get(i);
                if (value != null) {
                    resultMap.put(messages.get(i).getId(), value);
                } else {
                    missingMessages.add(messages.get(i)); // Redis에 없는 것들
                }
            }
        } else {
            missingMessages.addAll(messages);
        }

        if (missingMessages.isEmpty()) return resultMap;

        // 2. Redis에 없는 건 DB에서 조회 (Bulk Select)
        List<Long> missingIds = missingMessages.stream().map(ChatMessage::getId).toList();
        List<ChatMessageTranslation> dbTranslations = translationRepository.findByMessageIdInAndLanguageCode(missingIds, targetLang);

        Set<Long> foundInDbIds = new HashSet<>();
        for (ChatMessageTranslation t : dbTranslations) {
            resultMap.put(t.getMessageId(), t.getContent());
            foundInDbIds.add(t.getMessageId());
            // DB에 있던 건 나중을 위해 Redis에 올려둠 (Cache Warming)
            // 주의: 단순 Redis 저장은 트랜잭션과 무관하므로 this로 호출해도 무방하나, 일관성을 위해 내부 메서드 사용
            cacheToRedis(t.getMessageId(), targetLang, t.getContent());
        }

        // 3. DB에도 없는 건 API 호출 (최후의 수단 - 비용 발생)
        List<ChatMessage> totallyMissing = missingMessages.stream()
                .filter(msg -> !foundInDbIds.contains(msg.getId()))
                .toList();

        if (!totallyMissing.isEmpty()) {
            List<String> originalContents = totallyMissing.stream().map(ChatMessage::getContent).toList();

            // 외부 API 호출 (동기)
            List<String> apiResults = externalTranslationService.translateMessages(originalContents, targetLang);

            for (int i = 0; i < totallyMissing.size(); i++) {
                ChatMessage msg = totallyMissing.get(i);
                String translatedText = apiResults.get(i);

                resultMap.put(msg.getId(), translatedText);

                // 4. [핵심 수정 2] 'self'를 통해 호출하여 프록시를 경유하게 함
                // 이제 별도 스레드 + 별도 트랜잭션에서 실행되므로 메인 로직에 영향을 주지 않음
                self.saveTranslationAsync(msg.getId(), targetLang, translatedText);
            }
        }

        return resultMap;
    }


    @Async("taskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTranslationAsync(Long messageId, String languageCode, String content) {
        translationRepository.saveIgnoreDuplicate(messageId, languageCode, content);
    }

    private void cacheToRedis(Long messageId, String languageCode, String content) {
        try {
            String key = getCacheKey(messageId, languageCode);
            redisTemplate.opsForValue().set(key, content, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 캐싱 실패", e);
        }
    }

    private String getCacheKey(Long messageId, String languageCode) {
        return CACHE_PREFIX + messageId + ":" + languageCode;
    }

    /**
     * 메인 스레드 격리 및 2초 타임아웃 적용 (Resilience4j)
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "translationFallback")
    @TimeLimiter(name = "translationApi")
    public CompletableFuture<String> translateAndCache(Long messageId, String content, String targetLang) {
        // 반드시 주입받은 taskExecutor 안에서 동작하도록 두 번째 파라미터로 지정
        return CompletableFuture.supplyAsync(() -> {
            List<String> res = externalTranslationService.translateMessages(List.of(content), targetLang);
            if (res.isEmpty()) return content;

            String translated = res.get(0);

            // API 성공 시 비동기 저장 호출
            self.saveTranslationAsync(messageId, targetLang, translated);

            return translated;
        }, taskExecutor);
    }

    /**
     * 번역 API가 2초 이상 지연되거나 장애가 날 경우 즉시 원문을 반환하는 대체 로직
     */
    public CompletableFuture<String> translationFallback(Long messageId, String content, String targetLang, Throwable t) {
        log.warn("번역 API 지연 또는 예외 발생. 원문으로 처리합니다. 사유: {}", t.getMessage());
        return CompletableFuture.completedFuture(content);
    }
    /**
     * [신규] 저장 없이 번역만 수행 (서킷 브레이커 및 2초 타임아웃 적용)
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "translateTextFallback")
    @TimeLimiter(name = "translationApi")
    public CompletableFuture<String> translateTextOnly(String content, String targetLang) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> res = externalTranslationService.translateMessages(List.of(content), targetLang);
            return res.isEmpty() ? content : res.get(0);
        }, taskExecutor);
    }

    /**
     * 타임아웃 2초 초과 시 에러 대신 원문을 반환하여 메인 채팅 전송이 멈추지 않게 방어
     * 번역 실패가 **채팅 전송 실패로 이어지는 것을 막기 위한 '플랜 B (대체재)'**입니다.
     *
     * 폴백(Fallback)이 없을 때의 대참사
     * 구글 서버가 아파서 에러를 던지거나 2초 넘게 응답을 안 주면, 예외(Exception)가 발생합니다.
     * 이 예외가 메인 채팅 로직까지 파고들면 트랜잭션이 롤백되고 사용자 화면에는 "메시지 전송 실패" 에러가 뜹니다.
     * 번역 하나 안 됐다고 채팅 서비스 전체가 마비되는 셈입니다.
     *
     * 폴백(Fallback)이 있을 때의 방어 (현재 코드)
     * 구글 서버에서 에러가 터지거나 2초 타임아웃이 발생하는 순간, Resilience4j가 그 에러를 낚아챕니다.
     * 그리고 메인 로직으로 에러를 던지는 대신, 재빨리 translateTextFallback 메서드로 실행 흐름을 돌려버립니다.
     * 이 메서드는 에러를 내뿜지 않고 조용히 **"채팅 원문(content)"**을 결과값인 것처럼 포장해서 반환해 줍니다.
     */
    public CompletableFuture<String> translateTextFallback(String content, String targetLang, Throwable t) {
        log.warn("번역 API 지연. 실시간 전송을 위해 원문을 반환합니다. 언어: {}", targetLang);
        return CompletableFuture.completedFuture(content);
    }
}