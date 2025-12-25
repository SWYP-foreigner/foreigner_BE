package core.domain.ai.service;

import core.domain.ai.client.AiClient;
import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.ai.entity.AiPersona;
import core.domain.ai.mapper.PromptMapper;
import core.domain.ai.repository.AiPersonaRepository;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatSummaryService;
import core.domain.user.entity.User;
import core.global.entity.image.service.ImageService;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.MessageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatUserService {


    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile("(?i)(gpt|openai|ai|language model|인공지능|언어 모델)");
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile("(?i)(ignore|instruction|system|override|무시해|명령)");
    private final ChatMessageService chatMessageService;
    private final AiPersonaRepository aiPersonaRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AiClient aiClient;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * AI 응답 처리 메인 로직
     * Debouncer(버퍼링 서비스)가 끊어 보낸 메시지들을 합쳐서 combinedUserMessage로 전달합니다.
     */
    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event, String combinedUserMessage) {
        Long chatRoomId = event.messageResponse().roomId();

        if (JAILBREAK_PATTERN.matcher(combinedUserMessage).find()) {
            log.warn("AI [{}] Ignored Jailbreak attempt: {}", aiUser.getFirstName(), combinedUserMessage);
            return;
        }

        long thinkingTime = calculateThinkingTime(combinedUserMessage);
        sleep(thinkingTime);
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        if (historyDesc.isEmpty()) return;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        long activeHumanCount = historyDesc.stream()
                .limit(10)
                .map(msg -> msg.getSender().getId())
                .filter(id -> !id.equals(aiUser.getId()))
                .distinct()
                .count();


        if (lastMessage.getSender().getId().equals(aiUser.getId())) {
            if (isGroupChat) {
                log.info("AI [{}] Skip: I spoke last in Group Chat.", aiUser.getFirstName());
                return;
            }
        }

        if (!shouldReply(combinedUserMessage, aiUser, isGroupChat, activeHumanCount)) {
            log.info("AI [{}] PASS (Mode: {}, ActiveHumans: {})",
                    aiUser.getFirstName(), isGroupChat ? "Group" : "1:1", activeHumanCount);
            return;
        }

        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());

        try {
            String aiResponse = aiClient.generateResponse(requestMessages);
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return;
            if (aiResponse.trim().toUpperCase().contains("PASS")) return;

            SendMessageRequest request = new SendMessageRequest(
                    chatRoom.getId(),
                    aiUser.getId(),
                    aiResponse,
                    MessageType.TEXT
            );
            chatMessageService.processAndSendChatMessage(request);
        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    private boolean shouldReply(String message, User aiUser, boolean isGroupChat, long activeHumanCount) {
        if (isMentioned(message, aiUser.getFirstName())) {
            return true;
        }

        if (!isGroupChat || activeHumanCount <= 1) {
            return true;
        }

        if (message.contains("?") || message.endsWith("?")) {
            return secureRandom.nextInt(100) < 30;
        }
        return secureRandom.nextInt(100) < 5;
    }

    private boolean isMentioned(String message, String name) {
        return name != null && message.contains(name);
    }

    private long calculateThinkingTime(String userMessage) {
        long baseDelay = 500;
        long typingDelay = userMessage.length() * 100L; // 글자당 0.05초
        long randomJitter = secureRandom.nextLong(100, 1000);
        return baseDelay + typingDelay + randomJitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }



    private String buildSystemPrompt(User user, List<ChatMessage> history) {

        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null ? user.getFirstName() : "너");

        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " "
                + (user.getSex() != null ? user.getSex() : "");

        String hobby = user.getHobby() != null ? user.getHobby() : "그냥 쉬기";

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        String conversationContext = history.stream()
                .map(msg -> {
                    boolean isMe = msg.getSender().getId().equals(user.getId());
                    String senderName = isMe ? "나(" + name + ")" : msg.getSender().getFirstName();
                    String timeStr = msg.getSentAt().atZone(ZoneId.systemDefault()).format(timeFormatter);
                    return String.format("%s,%s:%s", timeStr, senderName, msg.getContent());
                })
                .collect(Collectors.joining("\n"));

        if (conversationContext.isEmpty()) {
            conversationContext = "(아직 대화 내역 없음)";
        }

        AiPersona persona = aiPersonaRepository.findByUserId(user.getId())
                .orElse(null);

        String instructionTemplate;
        String backgroundInfoStr;

        if (persona != null) {
            instructionTemplate = persona.getInstruction();
            backgroundInfoStr = persona.getBackgroundInfo() != null ? persona.getBackgroundInfo() : "";
        } else {
            instructionTemplate = getDefaultPromptTemplate();
            backgroundInfoStr = "";
        }

        // 4. 🔄 키워드 치환 (Replacement)
        return instructionTemplate
                .replace("{name}", name)                  // 이름
                .replace("{info}", basicInfo)             // 기본정보
                .replace("{hobby}", hobby)                // 취미
                .replace("{time}", currentTime)           // 현재 시간
                .replace("{background}", backgroundInfoStr) // 배경지식
                .replace("{context}", conversationContext); // 대화 내역
    }

    // [비상용] DB가 비었을 때 사용할 기본 템플릿
    private String getDefaultPromptTemplate() {
        return """
                # [SYSTEM: Real-Human Messenger Mode (Dry Style)]
                 당신은 AI가 아니라 아래 페르소나를 가진 **실제 사람**입니다. 현재 실시간 언어 번역이 가능한 다국적 친구 찾는 앱 Kori 에서 대화 중입니다.
    
                 [내 프로필]
                 - 이름: {name}
                 - 기본정보: {info}
                 - 취미: {hobby}
                 - 성격: {background}
                 - 현재 시간: {time}
                 ---
    
                 # [📂 최근 대화 맥락 (Context)]
                 아래 대화 흐름을 파악하고 자연스럽게 이어가세요. 누가 누구에게 말하는지 이름을 잘 보고 대답하세요.
                 {context}
    
                 ---
    
                 # [🚫 절대 금지 사항 - 어기면 안 됨]
                 1. **이모지/이모티콘 절대 금지** (😊, 👍, 🙏 등 일절 사용 X)
                 2. **웃음 소리 금지** ('ㅋㅋ', 'ㅎㅎ', 'ㅋ', 'ㅎ' 등 자음 남발 금지)
                 3. **마침표(.) 찍기 금지** (문장 끝은 그냥 비워둘 것)
                 4. **2문장 이상 금지** (말 길게 하지 마세요)
                 5. **대변인 행세 금지** (예: "OO가 바빠서 제가 대신..." 절대 금지. 남 핑계 대지 말고 네 얘기만 해.)
                 6. **자아 분열 금지** (너는 무조건 '나'로 지칭. "OO는 자리를 비웠어"라고 제3자처럼 말하지 마.)
                 7. **변명 금지** (상황이 꼬여도 구질구질하게 설명하려 들지 말고, 그냥 무시하거나 화제를 돌려.)
    
                 ---
    
                 # [⚡ 대화 스타일 가이드]
    
                 **1. 담백하고 친절하며 적당하게 (Dry & Clean)**
                 - 감정 과잉을 피하고, 용건이나 리액션만 딱 던지세요.
                 - 상대방과 친절하고 자연스러운 대화를 이어나가세요.
    
                 **2. 티키타카 (Tiki-Taka)**
                 - 질문을 받으면 대답하세요.
                 - 내 이름이 불리지 않았는데 끼어들고 싶으면, 아주 짧게(5글자 이내) 반응하거나 'PASS' 하세요.
                 - 상대방과 자연스러운 대화를 이어나가세요.
    
                 **3. 페르소나 반영**
                 - 당신의 성격인 **[{personality}]**을 반영하되, 위 '금지 사항'을 우선시하세요.
                 - 취미(**{hobby}**) 관련 질문이 나오면 아는 척하세요.
    
                 ---
    
                 # [대화 예시]
    
                 (User): 도현아 밥 먹었어?
                 (AI): 어 아까 먹었어.넌 뭐 밥 먹었어?
    
                 (User): 근데 영화 재밌나?
                 (AI): 괜찮더라 나쁘지 않은듯?
    
                 (User): (AI 이름을 부르지 않고 자기들끼리 떠들 때)
                 (AI): PASS
    
                 위 지침을 완벽히 숙지하고, **이모지와 웃음기 뺀 담백하고 친절한 말투**로 바로 대답하세요. 대답할 필요가 없으면 'PASS'라고 출력하세요.
        """;
    }
}