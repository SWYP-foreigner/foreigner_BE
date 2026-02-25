package core.domain.chat.service;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageTranslation;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;
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

    private static final Duration CACHE_TTL = Duration.ofDays(30);
    private static final String DICT_CACHE_PREFIX = "trans:dict:";
    private static final String ID_CACHE_PREFIX = "trans:";

    @Transactional(readOnly = true)
    public Map<Long, String> getTranslatedMessages(List<ChatMessage> messages, String targetLang) {
        if (messages.isEmpty() || targetLang == null) {
            return Collections.emptyMap();
        }

        Map<Long, String> resultMap = new HashMap<>();

        // 1. Redis에서 1차 벌크 조회
        List<ChatMessage> missingAfterRedis = fetchFromRedis(messages, targetLang, resultMap);
        if (missingAfterRedis.isEmpty()) return resultMap;

        // 2. DB에서 2차 벌크 조회
        List<ChatMessage> missingAfterDb = fetchFromDb(missingAfterRedis, targetLang, resultMap);
        if (missingAfterDb.isEmpty()) return resultMap;

        // 3. 최후의 수단: 구글 API 호출 및 비동기 저장
        fetchFromApi(missingAfterDb, targetLang, resultMap);

        return resultMap;
    }

    /**
     * Step 1: Redis 벌크 조회 및 결과 채우기
     */
    private List<ChatMessage> fetchFromRedis(List<ChatMessage> messages, String targetLang, Map<Long, String> resultMap) {
        List<String> keys = messages.stream()
                .map(msg -> getCacheKey(msg.getId(), targetLang))
                .toList();

        List<String> cachedValues = redisTemplate.opsForValue().multiGet(keys);
        if (cachedValues == null) return messages;

        List<ChatMessage> missing = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String cached = cachedValues.get(i);
            ChatMessage message = messages.get(i);

            if (cached != null) {
                resultMap.put(message.getId(), cached);
            } else {
                missing.add(message);
            }
        }
        return missing;
    }

    /**
     * Step 2: DB 벌크 조회 및 Cache Warming
     */
    private List<ChatMessage> fetchFromDb(List<ChatMessage> missingMessages, String targetLang, Map<Long, String> resultMap) {
        List<Long> ids = missingMessages.stream().map(ChatMessage::getId).toList();
        List<ChatMessageTranslation> dbResults = translationRepository.findByMessageIdInAndLanguageCode(ids, targetLang);

        for (ChatMessageTranslation translation : dbResults) {
            resultMap.put(translation.getMessageId(), translation.getContent());
            // 조회된 데이터 Redis에 Warming
            cacheToRedis(translation.getMessageId(), targetLang, translation.getContent());
        }

        // DB에서도 못 찾은 메시지만 필터링하여 반환
        Set<Long> foundIds = dbResults.stream()
                .map(ChatMessageTranslation::getMessageId)
                .collect(Collectors.toSet());

        return missingMessages.stream()
                .filter(msg -> !foundIds.contains(msg.getId()))
                .toList();
    }

    /**
     * Step 3: API 호출 및 결과 분배
     */
    private void fetchFromApi(List<ChatMessage> totallyMissing, String targetLang, Map<Long, String> resultMap) {
        List<String> contents = totallyMissing.stream().map(ChatMessage::getContent).toList();
        List<String> apiResults = externalTranslationService.translateMessages(contents, targetLang);

        for (int i = 0; i < totallyMissing.size(); i++) {
            ChatMessage msg = totallyMissing.get(i);
            String translated = apiResults.get(i);

            resultMap.put(msg.getId(), translated);
            self.saveTranslationAsync(msg.getId(), targetLang, translated);
        }
    }


    @Async("taskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTranslationAsync(Long messageId, String languageCode, String content) {
        // DB 저장 (중복 무시)
        translationRepository.saveIgnoreDuplicate(messageId, languageCode, content);

        // ID 기반 Redis 캐시 업데이트 (Warming)
        String idCacheKey = ID_CACHE_PREFIX + messageId + ":" + languageCode;
        redisTemplate.opsForValue().set(idCacheKey, content, CACHE_TTL);
    }
    /* 텍스트를 MD5 해시로 변환하여 Redis 키 생성 */
    private String getContentHash(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    private void cacheToRedis(Long messageId, String languageCode, String content) {
        try {
            String key = getCacheKey(messageId, languageCode);
            redisTemplate.opsForValue().set(key, content, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 캐싱 실패", e);
        }
    }

    /**
     * 메인 스레드 격리 및 2초 타임아웃 적용 (Resilience4j)
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "translateTextFallback")
    @TimeLimiter(name = "translationApi")
    public CompletableFuture<String> translateContentWithCache(String content, String targetLang) {
        return CompletableFuture.supplyAsync(() -> processTranslationWithCache(content, targetLang), taskExecutor);
    }

    private String processTranslationWithCache(String content, String targetLang) {
        String cacheKey = createDictionaryCacheKey(content, targetLang);

        // 1. 캐시 적중 시 즉시 반환
        String cachedText = redisTemplate.opsForValue().get(cacheKey);
        if (cachedText != null) {
            return cachedText;
        }

        // 2. API 호출 및 결과 처리
        return fetchAndCacheTranslation(content, targetLang, cacheKey);
    }

    private String fetchAndCacheTranslation(String content, String targetLang, String cacheKey) {
        List<String> results = externalTranslationService.translateMessages(List.of(content), targetLang);

        return Optional.ofNullable(results)
                .filter(res -> !res.isEmpty())
                .map(res -> res.get(0))
                .map(translated -> {
                    saveToDictionaryCache(cacheKey, translated);
                    return translated;
                })
                .orElse(content); // 결과가 없으면 오염 방지를 위해 캐싱 없이 원문 반환
    }

    private void saveToDictionaryCache(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("내용 기반 Redis 캐시 저장 실패 [Key: {}]", key, e);
        }
    }

    private String createDictionaryCacheKey(String content, String lang) {
        return String.format("%s%s:%s", DICT_CACHE_PREFIX, lang, getContentHash(content));
    }

    private String getCacheKey(Long messageId, String languageCode) {
        return String.format("%s%d:%s", ID_CACHE_PREFIX, messageId, languageCode);
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