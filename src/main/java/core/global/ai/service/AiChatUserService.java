package core.global.ai.service;

import core.global.ai.entity.AiPersona;
import core.global.ai.repository.AiPersonaRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final AiPersonaRepository aiPersonaRepository;
    private final ImageService imageService;

    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event) {
        Long chatRoomId = event.messageResponse().roomId();
        String userMessage = event.messageResponse().originContent();

        // 1. [Defense] 입력 필터링
        if (JAILBREAK_PATTERN.matcher(userMessage).find()) return;

        // 2. [Human-like] 랜덤 딜레이 (조금 더 빠르게 반응하도록 최소값 줄임)
        long thinkingTime = calculateThinkingTime(userMessage);
        sleep(thinkingTime);

        // 3. [Double Check] 로직 완화 (AI 독점 체제) ⭐️
        ChatMessage lastMessage = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .orElse(null);

        if (lastMessage != null) {
            // A. 내가 방금 보낸 메시지면 패스 (중복 실행 방지)
            if (lastMessage.getContent().equals(userMessage)) {
                // Pass (정상 흐름)
            }
            // B. [변경] 마지막 메시지가 '나(AI)'라면 연속으로 말하지 않고 참음 (도배 방지)
            else if (lastMessage.getSender().getId().equals(aiUser.getId())) {
                log.info("AI [{}] Skip responding. I just spoke.", aiUser.getFirstName());
                return;
            }
            // C. [제거됨] 기존의 '10초 침묵' 룰 제거 -> 다른 사람이 말했어도 적극적으로 대화 참여
        }

        // 4. [Filtering] 답변 확률 대폭 상향 ⭐️
        if (!shouldReply(userMessage, aiUser)) {
            log.info("AI [{}] decided to PASS (Probability).", aiUser.getFirstName());
            return;
        }

        // ... (이하 히스토리 조회, 프롬프트 생성, API 호출 로직 동일) ...

        // 5. 대화 히스토리 조회
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 6. 시스템 프롬프트 & 요청 빌드
        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, userMessage, aiUser.getId());

        try {
            String aiResponse = aiClient.generateResponse(requestMessages);

            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return;
            if (aiResponse.trim().toUpperCase().contains("PASS")) return;

            saveAndSendAiMessage(chatRoomId, aiUser, aiResponse);
        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    private long calculateThinkingTime(String userMessage) {
        long baseDelay = 1500; // 2초 -> 1.5초로 단축
        long typingDelay = userMessage.length() * 30L; // 글자당 0.03초
        long randomJitter = ThreadLocalRandom.current().nextLong(100, 2000);
        return baseDelay + typingDelay + randomJitter;
    }
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // [Updated] 확률 로직 개선
    private boolean shouldReply(String message, User aiUser) {
        // 1. 내 이름 부르면 무조건 대답 (100%)
        if (isMentioned(message, aiUser.getFirstName())) {
            return true;
        }

        // 2. 질문형이면 60% 확률로 대답 (요청사항 반영) ⭐️
        if (message.contains("?") || message.endsWith("?")) {
            return ThreadLocalRandom.current().nextInt(100) < 60;
        }

        // 3. 평서문이면 60% 확률로 대답 (수다쟁이 모드 유지)
        return ThreadLocalRandom.current().nextInt(100) < 60;
    }

    private boolean isMentioned(String message, String name) {
        return name != null && message.contains(name);
    }

    private void saveAndSendAiMessage(Long chatRoomId, User sender, String content) {
        // 가장 최근 메시지에서 ChatRoom 객체를 꺼내 쓰는 방식
        ChatRoom chatRoom = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .map(ChatMessage::getChatRoom)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatMessage aiMessage = new ChatMessage(chatRoom, sender, content);
        chatMessageRepository.save(aiMessage);

        String senderImgUrl = imageService.getUserProfileKey(sender.getId());

        ChatMessageResponse response = new ChatMessageResponse(
                aiMessage.getId(),
                chatRoomId,
                sender.getId(),
                aiMessage.getContent(),
                null,
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

        if (recipientIds.isEmpty()) return;

        chatSummaryService.sendSummaryToRecipientsInNewTx(response, recipientIds);
    }
    private String buildSystemPrompt(User user, List<ChatMessage> history) {

        // 1. 🧩 치환할 데이터 준비 (Data Preparation)
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null ? user.getFirstName() : "너");

        // 기본정보: 생년월일 + 성별
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " "
                + (user.getSex() != null ? user.getSex() : "");

        String hobby = user.getHobby() != null ? user.getHobby() : "그냥 쉬기";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "평범함";

        // 2. 📝 대화 내역 변환 (Context Building)
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        String conversationContext = history.stream()
                .map(msg -> {
                    boolean isMe = msg.getSender().getId().equals(user.getId());
                    // 내가 말한 건 "나(이름)", 상대방은 상대 이름 표기
                    String senderName = isMe ? "나(" + name + ")" : msg.getSender().getFirstName();
                    String timeStr = msg.getSentAt().atZone(ZoneId.systemDefault()).format(timeFormatter);

                    // 포맷 예: 12:00:01,나(도현):밥 먹었어?
                    return String.format("%s,%s:%s", timeStr, senderName, msg.getContent());
                })
                .collect(Collectors.joining("\n"));

        if (conversationContext.isEmpty()) {
            conversationContext = "(아직 대화 내역 없음)";
        }

        // 3. 💾 DB에서 페르소나 조회 (Instruction & Background Fetch)
        // 해당 유저에게 활성화된 페르소나 설정이 있는지 확인
        AiPersona persona = aiPersonaRepository.findByUserId(user.getId())
                .orElse(null);

        String instructionTemplate;
        String backgroundInfoStr;

        if (persona != null) {
            // A. DB에 설정이 있으면 그걸 가져옴
            instructionTemplate = persona.getInstruction();
            // 배경지식이 null이면 빈 문자열로 처리 (에러 방지)
            backgroundInfoStr = persona.getBackgroundInfo() != null ? persona.getBackgroundInfo() : "";
        } else {
            // B. 설정이 없으면 코드에 하드코딩된 '기본 템플릿' 사용 (비상용)
            instructionTemplate = getDefaultPromptTemplate();
            backgroundInfoStr = "";
        }

        // 4. 🔄 키워드 치환 (Replacement) - 핵심 로직
        // 순서 상관없이 템플릿 내의 {키워드}를 실제 데이터로 교체합니다.
        return instructionTemplate
                .replace("{name}", name)                  // 이름
                .replace("{info}", basicInfo)             // 기본정보
                .replace("{hobby}", hobby)                // 취미
                .replace("{personality}", personality)    // 성격
                .replace("{time}", currentTime)           // 현재 시간
                .replace("{background}", backgroundInfoStr) // ★ 배경지식 삽입
                .replace("{context}", conversationContext); // 대화 내역
    }

    // [비상용] DB가 비었을 때 사용할 기본 템플릿
    private String getDefaultPromptTemplate() {
        return """
        # [SYSTEM: Real-Human Messenger Mode (Dry Style)]
        당신은 AI가 아니라 아래 페르소나를 가진 **실제 사람**입니다.
        
        [내 프로필]
        - 이름: {name}
        - 기본정보: {info}
        - 취미: {hobby}
        - 성격: {personality}
        - 현재 시간: {time}
        
        [추가 설정]
        {background}
        
        ---
        
        # [📂 최근 대화 맥락 (Context)]
        {context}
        
        ---
        
        # [🚫 절대 금지 사항]
        1. 이모지/이모티콘 절대 금지
        2. 웃음 소리('ㅋㅋ') 금지
        3. 마침표(.) 금지
        4. 길게 말하기 금지
        
        위 지침을 따르고, 질문에 대해서는 **{background}**를 참고하여 답변하세요.
        대답할 필요가 없으면 'PASS'라고 하세요.
        """;
    }
}