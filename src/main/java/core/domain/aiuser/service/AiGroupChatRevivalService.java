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
import org.springframework.transaction.support.TransactionTemplate;

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

    private final TransactionTemplate transactionTemplate;


    private static final SecureRandom secureRandom = new SecureRandom();

    // 1. 침묵 기준 시간
    private static final long SILENCE_THRESHOLD_MINUTES = 720;

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
        RevivalContext context = transactionTemplate.execute(status -> {
            // 1. AI 참여자 조회
            List<User> aiParticipants = chatRoomRepository.findAiParticipantsByRoomId(room.getId());
            if (aiParticipants.isEmpty()) return null;

            // 2. 메시지 조회 및 정렬
            List<ChatMessage> lastMessages = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(room.getId());
            Collections.reverse(lastMessages);

            // 3. 발화자 선정
            User initiatorAi = aiParticipants.get(secureRandom.nextInt(aiParticipants.size()));

            // 🚨 [핵심] Lazy Loading 강제 초기화 (Hibernate 초기화)
            // 프롬프트 만들 때 필요한 정보를 여기서 미리 다 건드려서 로딩해둡니다.
            String hobby = initiatorAi.getHobby();
            String country = initiatorAi.getCountry();
            AiPersona persona = aiPersonaRepository.findByUserId(initiatorAi.getId()).orElse(null);
            if (persona != null) {
                persona.getInstruction(); // Lazy 로딩 트리거
            }

            return new RevivalContext(initiatorAi, persona, lastMessages);
        });

        if (context == null) return;

        // 🧠 [2단계] AI 생성 (트랜잭션 X - DB 연결 없이 맘 편히 오래 걸려도 됨)
        // 이 구간에서는 DB 커넥션을 점유하지 않습니다.
        String revivalMessage = generateDynamicRevivalMessage(context.aiUser, context.persona, context.lastMessages);

        // 💾 [3단계] 메시지 전송 (트랜잭션 O - 이미 ChatMessageService에 걸려있음)
        SendMessageRequest request = new SendMessageRequest(
                room.getId(),
                context.aiUser.getId(),
                revivalMessage,
                MessageType.TEXT
        );

        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("🚑 CPR Success: Room[{}] AI[{}] Msg[{}]", room.getId(), context.aiUser.getFirstName(), revivalMessage);
        } catch (Exception e) {
            log.error("❌ Revival failed for room {}", room.getId(), e);
        }
    }

    private record RevivalContext(User aiUser, AiPersona persona, List<ChatMessage> lastMessages) {}

    /**
     * AI 페르소나와 이전 대화를 기반으로 '살아있는' 멘트 생성
     * (24시간 지난 대화는 문맥에서 제외)
     */
    private String generateDynamicRevivalMessage(User aiUser, AiPersona persona, List<ChatMessage> lastMessages) { // 👈 파라미터 3개로 변경
        try {
            // 🟢 [핵심] 24시간 필터링: 너무 오래된 메시지는 문맥에서 제거
            Instant oneDayAgo = Instant.now().minus(Duration.ofHours(24));

            List<ChatMessage> validContext = lastMessages.stream()
                    .filter(msg -> msg.getSentAt().isAfter(oneDayAgo))
                    .collect(Collectors.toList());

            // (참고) validContext가 비어있으면 프롬프트 매니저가 "대화 내역 없음"으로 처리하여
            // AI가 "새로운 주제"를 꺼내도록 유도하게 됨.

            // 1. 프롬프트 생성 (받아온 persona 사용)
            String prompt = aiPromptManager.buildRevivalPrompt(aiUser, persona, validContext);

            // 2. API 호출 (System / User 메시지 분리 권장)
            List<Map<String, Object>> input = List.of(
                    Map.of("role", "system", "content", prompt),
                    Map.of("role", "user", "content", "지금 대화 맥락에 맞춰 자연스럽게 첫 마디를 건네주세요.")
            );

            String response = aiClient.generateResponse(input);

            if (response != null && !response.isBlank()) {
                return response.replace("\"", "").trim();
            }

        } catch (Exception e) {
            log.warn("⚠️ LLM Revival Failed (Using Fallback): {}", e.getMessage());
        }

        return FALLBACK_TOPICS[secureRandom.nextInt(FALLBACK_TOPICS.length)];
    }

    private static final String[] FALLBACK_TOPICS = {
            "뭐해?",
            "뭐하니",
            "hmmm",
            "hi",
            "Hi guys!",
            "Anyone awake?",
            // Korean Version
            "다들 자니?",
            "심심하다",
            "하이",
            "반가워요!",
            "다들 뭐해요?",
            "누구 없나",
            "배고프다...",
            "오늘 날씨 어때요?",
            "안녕 안녕",
            "다들 밥 먹었어?",
            "심심한 사람",
            "하이",
            "좋은 아침!",
            "굿밤",
            "졸리다",
            "누구 대화하자",
            "반가워",
            "인사해줘요",
            "헬로",
            "궁금한게 있어",
            "다들 뭐함",
            "처음 온 사람?",
            "뭐하고 놀까",
            "노래 추천좀",
            "다들 오늘 하루 어땠어?",
            "다들 있는 곳 날씨 어때?",
            "다들 자나보네",
            "안뇽",
            "zzz",
            "좋은날!!",
            "뭐 재미있는거 없나",
            "웃긴 얘기 해줄 사람",
            "배고프다",
            "게임 추천좀",
            "드라마 추천 해 줄 사람?",
            "안녕들 하신가",
            "심심하네",
            "같이 놀자",
            "질문 있어!",
            "하이요",
            "반갑습니다",
            "뭐해 다들?",
            "깨어있는 사람?",
            // English Version
            "Hey!",
            "What's up?",
            "Hi there",
            "Hello everyone!",
            "Anyone here?",
            "How's it going?",
            "Bored...",
            "Hi guys!",
            "Good morning",
            "Good night",
            "What are you doing?",
            "Anybody awake?",
            "How are you?",
            "Hey hey",
            "Need someone to talk to",
            "Anyone active?",
            "What's the vibe?",
            "Hellooo",
            "Yo",
            "Sup",
            "Feeling bored",
            "How's your day?",
            "Any plans today?",
            "Where is everyone?",
            "Let's chat",
            "What's everyone up to?",
            "Can you hear me?",
            "Anyone online?",
            "Vibe check",
            "Hope you're well",
            "Busy?",
            "Talk to me",
            "How's life?",
            "Hey friends",
            "Just saying hi",
            "What's new?",
            "Peace",
            "Having a good day?",
            "Can't sleep",
            "Hi hi",
            "What's the tea?",
            "So bored right now",
            "Still awake?",
            "Say something!",
            "Helloooo?",
            "Anyone want to talk?",
            "Is it just me or is it quiet?",
            "Yo yo",
            "Have a nice day!"
    };
}
