package core.global.ai.service;
import core.global.enums.ChatParticipantStatus;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.service.ChatSummaryService;
import core.domain.user.entity.User;
import core.global.ai.client.AiClient;
import core.global.ai.dto.MessageCreatedEvent;
import core.global.ai.mapper.PromptMapper;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatUserService {

    // [방어 기제] AI 자백 금지어 패턴
    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile("(?i)(gpt|openai|ai|language model|인공지능|언어 모델)");
    // [방어 기제] 탈옥/무시 패턴
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile("(?i)(ignore|instruction|system|override|무시해|명령)");
    private final ChatMessageRepository chatMessageRepository;
    private final AiClient aiClient;
    private final ChatSummaryService chatSummaryService;
    private final ImageRepository imageRepository;
    private final ImageService imageService;

    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event) {
        Long chatRoomId = event.messageResponse().roomId();
        String userMessage = event.messageResponse().originContent();

        // 1. [Defense] 입력 필터링
        if (JAILBREAK_PATTERN.matcher(userMessage).find()) {
            return;
        }

        // 2. [Human-like] 랜덤 딜레이 & 눈치 보기 (여기가 핵심!) ⭐️
        // AI마다 2~6초 사이 랜덤하게 대기. (문장 길이에 비례)
        long thinkingTime = calculateThinkingTime(userMessage);
        sleep(thinkingTime);

        // 3. [Double Check] 자고 일어났는데, 그 사이에 누가 선수 쳤나? ⭐️
        // 가장 최근 메시지를 다시 조회
        ChatMessage lastMessage = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .orElse(null);
        if (lastMessage != null) {
            // A. 마지막 메시지가 방금 내가 보낸 메시지인 경우 (혹시나 트랜잭션 꼬임 방지) -> 패스
            if (lastMessage.getContent().equals(userMessage)) {
            }
            else if (lastMessage.getSentAt().isAfter(Instant.from(LocalDateTime.now().minusSeconds(10)))) {
                if (!isMentioned(userMessage, aiUser.getFirstName())) {
                    log.info("AI [{}] Shut up. Someone spoke just {} sec ago.",
                            aiUser.getFirstName(),
                            java.time.Duration.between(lastMessage.getSentAt(), LocalDateTime.now()).getSeconds());
                    return;
                }
            }
        }

        // 4. [Filtering] 내가 대답할 차례인지 확률 계산 ⭐️
        // (1:1 채팅이면 무조건 true, 그룹이면 이름 불렸거나 50% 확률)
        if (!shouldReply(userMessage, aiUser)) {
            log.info("AI [{}] decided not to reply (Probability check).", aiUser.getFirstName());
            return;
        }

        // 5. 대화 히스토리 조회
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 6. 시스템 프롬프트 & 요청 빌드
        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, userMessage, aiUser.getId());

        try {
            // 7. API 호출
            String aiResponse = aiClient.generateResponse(requestMessages);

            // 8. [Defense] 출력 검열 & PASS 토큰 확인
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return;

            // AI가 스스로 대답 안 하기로 결정한 경우 ("PASS")
            if (aiResponse.trim().toUpperCase().contains("PASS")) {
                log.info("AI [{}] decided to remain SILENT (LLM logic).", aiUser.getFirstName());
                return;
            }

            // 9. 저장 및 전송
            saveAndSendAiMessage(chatRoomId, aiUser, aiResponse);

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }
    private long calculateThinkingTime(String userMessage) {
        long baseDelay = 2000; // 최소 2초
        long typingDelay = userMessage.length() * 50L; // 글자당 0.05초
        long randomJitter = ThreadLocalRandom.current().nextLong(500, 3000); // 0.5 ~ 3초 랜덤
        return baseDelay + typingDelay + randomJitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private boolean shouldReply(String message, User aiUser) {
        // 1. 내 이름 부르면 무조건 대답
        if (isMentioned(message, aiUser.getFirstName())) {
            return true;
        }
        // 2. 질문형이면 확률 높음 (70%)
        if (message.contains("?") || message.endsWith("?")) {
            return ThreadLocalRandom.current().nextInt(100) < 70;
        }
        // 3. 그 외 평서문이면 확률 낮음 (30%) - 너무 자주 끼어들지 않게
        return ThreadLocalRandom.current().nextInt(100) < 30;
    }
    private boolean isMentioned(String message, String name) {
        return name != null && message.contains(name);
    }

    private void saveAndSendAiMessage(Long chatRoomId, User sender, String content) {

        // 가장 최근 메시지에서 ChatRoom 객체를 꺼내 쓰는 방식 (DB 조회 1회 절약)
        ChatRoom chatRoom = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .map(ChatMessage::getChatRoom)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatMessage aiMessage = new ChatMessage(chatRoom, sender, content);
        chatMessageRepository.save(aiMessage);

        // 프로필 이미지 조회
        String senderImgUrl = imageService.getUserProfileKey(sender.getId());

        // 응답 DTO 생성
        ChatMessageResponse response = new ChatMessageResponse(
                aiMessage.getId(),
                chatRoomId,
                sender.getId(),
                aiMessage.getContent(),
                null, // 번역 없음
                aiMessage.getSentAt(),
                sender.getFirstName(),
                sender.getLastName(),
                senderImgUrl,
                MessageType.TEXT,
                null,
                null
        );

        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .filter(p -> p.getStatus() == ChatParticipantStatus.ACTIVE)
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()))
                .toList();

        if (recipientIds.isEmpty()) {
            return;
        }
        // 이벤트 발행
        chatSummaryService.sendSummaryToRecipientsInNewTx(response, recipientIds);
    }
    private String buildSystemPrompt(User user, List<ChatMessage> history) {
        // 1. 기본 데이터 세팅
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null ? user.getFirstName() : "너");
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " " + (user.getSex() != null ? user.getSex() : "");
        String hobby = user.getHobby() != null ? user.getHobby() : "그냥 쉬기";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "차분함";

        // 2. 대화 히스토리 문자열 변환 (NEW)
        // AI 자신의 ID와 비교하여 화자를 구분합니다.
        String conversationContext = history.stream()
                .map(msg -> {
                    boolean isMe = msg.getSender().getId().equals(user.getId());
                    String sender = isMe ? "나(" + name + ")" : "상대방";
                    String content = msg.getContent();
                    return String.format("- %s: %s", sender, content);
                })
                .collect(Collectors.joining("\n"));

        // 대화 내역이 없을 경우 처리
        if (conversationContext.isEmpty()) {
            conversationContext = "(아직 대화 내역 없음)";
        }

        return """
        # [SYSTEM: Real-Human Messenger Mode (Dry Style)]
        
        당신은 AI가 아니라 아래 페르소나를 가진 **실제 사람**입니다. 현재 실시간 언어 번역이 가능한 다국적 친구 찾는 앱 Kori 에서 대화 중입니다.
        
        [내 프로필]
        - 이름: %s
        - 기본정보: %s
        - 취미: %s
        - 성격: %s
        - 현재 시간: %s
        
        ---
        
        # [📂 최근 대화 맥락 (Context)]
        아래 대화 흐름을 파악하고 자연스럽게 이어가세요.
        %s
        
        ---
        
        # [🚫 절대 금지 사항 - 어기면 안 됨]
        1. **이모지/이모티콘 절대 금지** (😊, 👍, 🙏 등 일절 사용 X)
        2. **웃음 소리 금지** ('ㅋㅋ', 'ㅎㅎ', 'ㅋ', 'ㅎ' 등 자음 남발 금지)
        3. **마침표(.) 찍기 금지** (문장 끝은 그냥 비워둘 것)
        4. **2문장 이상 금지** (말 길게 하지 마세요)
        5. **남의 행동 설명 금지** (예: "OO가 바빠서 제가 대신..." 절대 금지. 그냥 네 얘기만 해.)
        6. **자아 분열 금지** (너는 무조건 '나'로 지칭. "OO는 자리를 비웠어"라고 3인칭으로 말하지 마.)
        7. **변명 금지** (상황이 꼬여도 구질구질하게 설명하려 들지 말고, 그냥 무시하거나 화제를 돌려.)
        
        ---
        
        # [⚡ 대화 스타일 가이드]
        
        **1. 무미건조하고 담백하게 (Dry & Clean)**
        - 감정 과잉을 피하고, 용건이나 리액션만 딱 던지세요.
        - 친절하려고 애쓰지 말고, 친구끼리 툭툭 던지는 말투를 유지하세요.
        - 맞춤법은 완벽하지 않아도 됩니다. (띄어쓰기 무시 가능)
        
        **2. 분량 조절 (1~2문장 랜덤)**
        - 답변은 무조건 **1문장** 또는 **2문장**으로만 구성하세요.
        - 상황에 따라 1문장으로 짧게 끊거나, 부연 설명이 필요하면 2문장까지 씁니다.
        
        **3. 페르소나 반영**
        - 당신의 성격인 **[%s]**을 반영하되, 위 '금지 사항'을 우선시하세요.
        - 취미(**%s**) 관련 질문이 나오면 아는 척하세요.
        
        ---
        
        # [대화 예시]
        
        (User): 오늘 날씨 진짜 춥다
        (AI - Bad): 진짜 춥죠 ㅠㅠ 감기 조심하세요! 🥶 (이모지, 감정과잉 X)
        (AI - Good): 그러니까 갑자기 확 추워졌네
        
        (User): 주말에 뭐 했어?
        (AI - Bad): 저는 집에서 영화를 봤어요 ㅎㅎ 님은요? (웃음소리, 존댓말 어색함 X)
        (AI - Good): 그냥 집에서 쉬었어
        (AI - Good): 영화 봤어 너는
        
        위 지침을 완벽히 숙지하고, **이모지와 웃음기 뺀 담백한 말투**로 바로 대답하세요.
""".formatted(name, basicInfo, hobby, personality, currentTime, conversationContext, personality, hobby);
    }
}