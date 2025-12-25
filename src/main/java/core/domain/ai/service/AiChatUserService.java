package core.domain.ai.service;

import core.domain.ai.client.AiClient;
import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.ai.entity.AiPersona;
import core.domain.ai.mapper.PromptMapper;
import core.domain.ai.repository.AiPersonaRepository;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
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

    /**
     * AI 응답 처리 메인 로직
     * Debouncer(버퍼링 서비스)가 끊어 보낸 메시지들을 합쳐서 combinedUserMessage로 전달합니다.
     */
    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event, String combinedUserMessage) {
        Long chatRoomId = event.messageResponse().roomId();

        // 1. [Defense] 입력 필터링 (합쳐진 메시지 기준 검사)
        if (JAILBREAK_PATTERN.matcher(combinedUserMessage).find()) {
            log.warn("AI [{}] Ignored Jailbreak attempt: {}", aiUser.getFirstName(), combinedUserMessage);
            return;
        }

        // 2. [Human-like] 타이핑/고민 시간 연출
        // (Debouncer에서 3초를 이미 기다렸지만, AI가 "생성하는 시간"을 연출하기 위해 약간의 딜레이 유지)
        long thinkingTime = calculateThinkingTime(combinedUserMessage);
        sleep(thinkingTime);

        // 3. [Context & Mood Check] 분위기 파악을 위해 최근 대화 조회
        // 최근 20개를 가져와서 상황을 판단합니다.
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);

        // 대화 내역이 없으면 종료 (방금 생성된 메시지가 있어야 정상이므로 희박함)
        if (historyDesc.isEmpty()) return;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        // 4. [Active User Count] 최근 10개 메시지 내에서 '떠들고 있는 사람' 수 카운트 ⭐️
        // (나 자신(AI)은 제외하고 사람만 셉니다)
        long activeHumanCount = historyDesc.stream()
                .limit(10)
                .map(msg -> msg.getSender().getId())
                .filter(id -> !id.equals(aiUser.getId()))
                .distinct()
                .count();

        // 5. [Spam Prevention] 내가 마지막으로 말했는데 또 말해야 하나?
        if (lastMessage.getSender().getId().equals(aiUser.getId())) {
            // 그룹 채팅에서 내가 방금 말했는데 또 끼어드는 건 자제
            if (isGroupChat) {
                log.info("AI [{}] Skip: I spoke last in Group Chat.", aiUser.getFirstName());
                return;
            }
            // 1:1이면 내가 말했어도 유저가 연타(Debounce 뚫고)했거나 하면 대답해줌
        }

        // 6. [Filtering] 답변 여부 결정 (activeHumanCount 전달) ⭐️
        if (!shouldReply(combinedUserMessage, aiUser, isGroupChat, activeHumanCount)) {
            log.info("AI [{}] PASS (Mode: {}, ActiveHumans: {})",
                    aiUser.getFirstName(), isGroupChat ? "Group" : "1:1", activeHumanCount);
            return;
        }

        // 7. [Prompt] 시스템 프롬프트 및 요청 생성
        // DB에서 가져온 건 최신순(Desc)이므로, 프롬프트용으로 과거순(Asc) 정렬
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);

        // ★ 여기서 '합쳐진 메시지(combinedUserMessage)'를 사용해야 AI가 문맥을 한 번에 이해합니다.
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());

        try {
            // AI API 호출
            String aiResponse = aiClient.generateResponse(requestMessages);

            // 8. [Post-Validation] 응답 검증
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return; // AI 티내면 무시
            if (aiResponse.trim().toUpperCase().contains("PASS")) return; // PASS 토큰이면 무시

            // 9. 저장 및 전송
            saveAndSendAiMessage(chatRoom, aiUser, aiResponse);
        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    // [Updated] 확률 로직: 그룹 채팅 내 '사람 수'에 따라 눈치 챙기기
    private boolean shouldReply(String message, User aiUser, boolean isGroupChat, long activeHumanCount) {

        // 1. 내 이름 부르면 무조건 대답 (100%)
        if (isMentioned(message, aiUser.getFirstName())) {
            return true;
        }

        // 2. 1:1 채팅이거나, 그룹이지만 사실상 1:1(나랑 유저 한 명뿐)인 경우 ⭐️
        if (!isGroupChat || activeHumanCount <= 1) {
            return true;
        }

        // 3. 그룹 채팅 + 여러 명이 떠드는 중 (눈치 모드 ON) ⭐️
        // 질문형: 30% 확률 (끼어들기 자제)
        if (message.contains("?") || message.endsWith("?")) {
            return ThreadLocalRandom.current().nextInt(100) < 30;
        }

        // 평서문: 5% 확률 (거의 침묵, 리액션만 가끔)
        return ThreadLocalRandom.current().nextInt(100) < 5;
    }

    private boolean isMentioned(String message, String name) {
        return name != null && message.contains(name);
    }

    private long calculateThinkingTime(String userMessage) {
        // Debouncer에서 3초를 기다렸으므로, 여기서는 타이핑하는 느낌의 짧은 시간만 부여
        long baseDelay = 500;
        long typingDelay = userMessage.length() * 50L; // 글자당 0.05초
        long randomJitter = ThreadLocalRandom.current().nextLong(100, 1000);
        return baseDelay + typingDelay + randomJitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 채팅방 ID 대신 이미 조회한 ChatRoom 객체를 받도록 최적화
    private void saveAndSendAiMessage(ChatRoom chatRoom, User sender, String content) {
        // 메시지 저장
        ChatMessage aiMessage = new ChatMessage(chatRoom, sender, content);
        chatMessageRepository.save(aiMessage);

        String senderImgUrl = imageService.getUserProfileKey(sender.getId());

        // 응답 DTO 생성
        ChatMessageResponse response = new ChatMessageResponse(
                aiMessage.getId(),
                chatRoom.getId(),
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

        // 수신자 필터링 (나 제외, 활성 유저만)
        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .filter(p -> p.getStatus() == ChatParticipantStatus.ACTIVE)
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()))
                .toList();

        if (recipientIds.isEmpty()) return;

        // 소켓 전송 (트랜잭션 분리)
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