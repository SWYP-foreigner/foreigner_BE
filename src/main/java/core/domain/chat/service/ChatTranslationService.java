package core.domain.chat.service;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageTranslation;
import core.domain.chat.repository.ChatMessageTranslationRepository;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTranslationService {

    private final ChatMessageTranslationRepository translationRepository;
    private final TranslationService externalTranslationService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * [핵심 수정 1] 자기 자신을 주입받습니다 (Self-Injection).
     * 내부 메서드 호출 시에도 AOP 프록시(Async, Transactional)가 적용되도록 하기 위함입니다.
     * 순환 참조 방지를 위해 @Lazy를 사용합니다.
     */
    @Autowired
    @Lazy
    private ChatTranslationService self;

    private static final String CACHE_PREFIX = "trans:";
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    /**
     * [읽기 핵심 로직]
     * 메시지 목록을 받아 번역된 내용을 채워줍니다.
     * Redis -> DB -> API 순서로 조회하여 비용을 절감합니다.
     */
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

    /**
     * [쓰기 핵심 로직]
     * 번역 결과를 DB와 Redis에 비동기로 저장합니다.
     * @Async 어노테이션으로 메인 스레드를 차단하지 않습니다.
     * REQUIRES_NEW: 메인 트랜잭션이 롤백되어도 번역 저장은 성공 시키거나, 반대로 여기서 실패해도 메인 로직은 살리기 위함
     */
    @Async("taskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTranslationAsync(Long messageId, String languageCode, String content) {
        try {
            translationRepository.save(new ChatMessageTranslation(messageId, languageCode, content));
        } catch (DataIntegrityViolationException e) {
            // 에러 메시지를 확인해서 분기 처리
            if (e.getMessage() != null && e.getMessage().contains("violates foreign key constraint")) {
                log.error("번역 저장 실패: 부모 메시지가 존재하지 않음 (FK Violation). msgId={}", messageId);
            } else {
                log.warn("이미 저장된 번역입니다 (중복 저장). msgId={}, lang={}", messageId, languageCode);
            }
        } catch (Exception e) {
            log.error("번역 비동기 저장 중 알 수 없는 오류", e);
        }
    }

    // Redis 저장은 트랜잭션이 필요 없으므로 private 메서드로 동기 처리해도 무방 (Redis 자체가 빠름)
    private void cacheToRedis(Long messageId, String languageCode, String content) {
        try {
            String key = getCacheKey(messageId, languageCode);
            redisTemplate.opsForValue().set(key, content, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 캐싱 실패 (무시됨)", e);
        }
    }

    private String getCacheKey(Long messageId, String languageCode) {
        return CACHE_PREFIX + messageId + ":" + languageCode;
    }

    /**
     * [전송 시 호출]
     * 메시지 전송 시점에 번역을 수행하고 결과만 리턴 (저장은 비동기로 처리)
     */
    public CompletableFuture<String> translateAndCache(Long messageId, String content, String targetLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // API 호출
                List<String> res = externalTranslationService.translateMessages(List.of(content), targetLang);
                if (res.isEmpty()) return content;

                String translated = res.get(0);

                // [핵심 수정 3] 결과 나왔으면 바로 비동기 저장 태우기 (Fire-and-Forget)
                // 여기서도 self를 써야 Async가 먹힙니다.
                self.saveTranslationAsync(messageId, targetLang, translated);

                return translated;
            } catch (Exception e) {
                log.error("Translation failed", e);
                return content; // 실패 시 원문 리턴
            }
        });
    }
}