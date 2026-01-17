package core.domain.aiuser.service;

import core.domain.aiuser.client.AiClient;
import core.domain.aiuser.entity.AiPersona;
import core.domain.aiuser.repository.AiPersonaRepository;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.user.entity.User;
import core.global.enums.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGroupChatRevivalService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageService chatMessageService;

    // AI 연동 관련
    private final AiClient aiClient;
    private final AiPromptManager aiPromptManager;
    private final AiPersonaRepository aiPersonaRepository;

    private static final SecureRandom secureRandom = new SecureRandom();

    // ==========================================
    // 🧪 [TEST MODE SETTING]
    // ==========================================

    // 1. 침묵 기준 시간
    private static final long SILENCE_THRESHOLD_MINUTES = 240;

    // 2. 스케줄러 실행 주기
    @Scheduled(cron = "0 0 * * * *")
    public void reviveSilentChatRooms() {

        // 1. AI가 참여 중인 방 ID 목록 확보
        List<Long> aiRoomIds = chatParticipantRepository.findAllAiParticipatedRoomIds();

        if (aiRoomIds.isEmpty()) return;

        if (aiRoomIds.size() > 2000) {
            aiRoomIds = aiRoomIds.subList(0, 2000);
        }

        // 2. 침묵 방 조회 (인덱스 활용)
        Instant threshold = Instant.now().minus(Duration.ofMinutes(SILENCE_THRESHOLD_MINUTES));
        Pageable limit = PageRequest.of(0, 40);

        List<ChatRoom> silentRooms = chatRoomRepository.findSilentRoomsByRoomIds(aiRoomIds, threshold, limit);

        if (silentRooms.isEmpty()) return;

        log.info("📢 Revival Service (TEST): Found {} silent rooms.", silentRooms.size());

        for (ChatRoom room : silentRooms) {
            // 3. 확률 체크
            if (secureRandom.nextInt(100) < 70) {
                tryTriggerRevivalMessage(room);
            }
        }
    }

    private void tryTriggerRevivalMessage(ChatRoom room) {
        // 1. 해당 방의 AI 멤버들 조회
        List<User> aiParticipants = chatRoomRepository.findAiParticipantsByRoomId(room.getId());
        if (aiParticipants.isEmpty()) return;

        // 2. 최근 대화 5개 조회 (일단 DB에서는 최근 5개를 가져옴)
        List<ChatMessage> lastMessages = chatMessageRepository.findTop5ByChatRoomIdOrderBySentAtDesc(room.getId());

        // 시간순 정렬 보정 (과거 -> 최신)
        Collections.reverse(lastMessages);

        // 3. 마지막 발화자가 AI라면, 이번 턴에서는 제외하기 (티키타카 유도)
        if (!lastMessages.isEmpty()) {
            User lastSender = lastMessages.get(lastMessages.size() - 1).getSender();

            if (aiParticipants.size() > 1) {
                List<User> otherAis = aiParticipants.stream()
                        .filter(ai -> !ai.getId().equals(lastSender.getId()))
                        .toList();

                if (!otherAis.isEmpty()) {
                    aiParticipants = otherAis;
                }
            }
        }

        // 4. 발화자 선정
        User initiatorAi = aiParticipants.get(secureRandom.nextInt(aiParticipants.size()));

        // 5. 멘트 생성 (24시간 필터링은 이 메서드 안에서 수행)
        String revivalMessage = generateDynamicRevivalMessage(initiatorAi, lastMessages);

        SendMessageRequest request = new SendMessageRequest(
                room.getId(),
                initiatorAi.getId(),
                revivalMessage,
                MessageType.TEXT
        );

        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("🚑 CPR Success: Room[{}] AI[{}] Msg[{}]", room.getId(), initiatorAi.getFirstName(), revivalMessage);
        } catch (Exception e) {
            log.error("❌ Revival failed for room {}", room.getId(), e);
        }
    }

    /**
     * AI 페르소나와 이전 대화를 기반으로 '살아있는' 멘트 생성
     * (24시간 지난 대화는 문맥에서 제외)
     */
    private String generateDynamicRevivalMessage(User aiUser, List<ChatMessage> lastMessages) {
        try {
            // 🟢 [핵심] 24시간 필터링: 너무 오래된 메시지는 문맥에서 제거
            Instant oneDayAgo = Instant.now().minus(Duration.ofHours(24));

            List<ChatMessage> validContext = lastMessages.stream()
                    .filter(msg -> msg.getSentAt().isAfter(oneDayAgo))
                    .collect(Collectors.toList());

            // (참고) validContext가 비어있으면 프롬프트 매니저가 "대화 내역 없음"으로 처리하여
            // AI가 "새로운 주제"를 꺼내도록 유도하게 됨.

            // 1. 페르소나 조회
            AiPersona persona = aiPersonaRepository.findByUserId(aiUser.getId()).orElse(null);

            // 2. 프롬프트 생성 (필터링된 validContext 사용)
            String prompt = aiPromptManager.buildRevivalPrompt(aiUser, persona, validContext);

            // 3. API 호출
            List<Map<String, Object>> input = List.of(
                    Map.of("role", "system", "content", prompt)
            );

            String response = aiClient.generateResponse(input);

            if (response != null && !response.isBlank()) {
                return response.replace("\"", "").trim();
            }

        } catch (Exception e) {
            log.warn("⚠️ LLM Revival Failed (Using Fallback): {}", e.getMessage());
        }

        // 🚨 실패 시 하드코딩 멘트 사용
        return FALLBACK_TOPICS[secureRandom.nextInt(FALLBACK_TOPICS.length)];
    }

    private static final String[] FALLBACK_TOPICS = {
            "여기 너무 조용한 거 아니야? 다들 뭐해?",
            "심심한 사람 있어? 나랑 놀자",
            "다들 밥은 먹었어?",
            "넷플릭스에서 볼만한거 추천좀 해줘",
            "Hi guys! It's so quiet here. Anyone awake?"
    };
}
