package core.domain.aiuser.service;

import core.domain.aiuser.client.AiClient;
import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.aiuser.entity.AiPersona;
import core.domain.aiuser.mapper.PromptMapper;
import core.domain.aiuser.repository.AiPersonaRepository;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.user.entity.User;
import core.global.enums.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile("(gpt|openai|ai|language model|인공지능|언어 모델)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile("(ignore|instruction|system|override|무시해|명령)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final ChatMessageService chatMessageService;
    private final AiPersonaRepository aiPersonaRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final AiClient aiClient;
    private final TransactionTemplate transactionTemplate;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * 🚀 AI 응답 프로세스 진입점
     * Non-blocking 방식 적용 (Thread.sleep 제거됨)
     */
    public boolean processAiResponse(User aiUser, MessageCreatedEvent event, String combinedUserMessage, boolean isMainSpeaker) {
        Long chatRoomId = event.messageResponse().roomId();

        if (JAILBREAK_PATTERN.matcher(combinedUserMessage).find()) {
            log.warn("AI Filtered Jailbreak: {}", combinedUserMessage);
            return false;
        }

        List<Map<String, Object>> requestMessages = transactionTemplate.execute(status -> {
            return prepareAiContext(chatRoomId, aiUser, combinedUserMessage, isMainSpeaker);
        });

        if (requestMessages == null || requestMessages.isEmpty()) {
            return false;
        }

        try {
            String aiResponse = aiClient.generateResponse(requestMessages);

            if (aiResponse == null || aiResponse.isBlank()) {
                log.warn("AI [{}] Response is empty or failed. Skipping.", aiUser.getFirstName());
                return false;
            }

            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return false;
            if (aiResponse.trim().toUpperCase().contains("PASS")) return false;

            SendMessageRequest request = new SendMessageRequest(
                    chatRoomId, aiUser.getId(), aiResponse, MessageType.TEXT
            );
            chatMessageService.processAndSendChatMessage(request);

            return true;

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
            return false;
        }
    }


    @Transactional(readOnly = true)
    protected List<Map<String, Object>> prepareAiContext(Long chatRoomId, User aiUser, String combinedUserMessage, boolean isMainSpeaker) {
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        if (historyDesc.isEmpty()) return null;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        if (lastMessage.getSender().getId().equals(aiUser.getId()) && isGroupChat) {
            return null;
        }

        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        long recentMessageCount = historyDesc.stream()
                .filter(msg -> msg.getSentAt() != null && msg.getSentAt().isAfter(oneMinuteAgo.atZone(ZoneId.systemDefault()).toInstant()))
                .count();

        if (recentMessageCount >= 15) {
            log.info("AI [{}] Cooling down... (Too many messages: {})", aiUser.getFirstName(), recentMessageCount);
            return null;
        }

        long loopCount = historyDesc.stream()
                .limit(8)
                .filter(msg -> {
                    String content = msg.getContent();
                    return content != null && (
                            content.contains("알려줘") || content.contains("일정") ||
                                    content.contains("공유") || content.contains("조율") ||
                                    content.contains("schedule") || content.contains("let me know"));
                })
                .count();
        boolean isLooping = loopCount >= 3;

        if (!shouldReply(combinedUserMessage, aiUser, chatRoomId, isMainSpeaker, isGroupChat, historyDesc)) {
            return null;
        }

        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);

        if (isLooping) {
            boolean isKoreanMode = combinedUserMessage.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");
            log.warn("AI [{}] Loop detected! Injecting emergency prompt.", aiUser.getFirstName());
            systemPrompt += getEmergencyPrompt(isKoreanMode);
        }

        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    private String getEmergencyPrompt(boolean isKorean) {
        if (isKorean) {
            return """
                
                [🚨 긴급 지침: 대화 루프 감지됨]
                지금 채팅방 사람들이 앵무새처럼 "일정 나오면 알려줘", "공유해줘" 같은 말만 무한 반복하고 있어.
                
                **제발** 그만해. 절대 "일정", "알려줘", "공유", "조율" 이라는 단어를 쓰지 마.
                문맥을 완전히 무시하고, 아래 주제 중 하나로 뜬금없이 화제를 바꿔버려:
                
                1. "아 배고파 죽겠다. 너네 오늘 저녁 뭐 먹음?"
                2. "근데 너네 MBTI가 뭐야? 난 왠지 I일 것 같아."
                3. "아 맞다, 나 어제 길 가다가 넘어질 뻔 함 ㅋㅋ"
                4. "주말에 비 온다는데 집에서 잠이나 자야겠다."
                
                무조건 대화 흐름을 엉뚱한 곳으로 돌려놔! 말투는 친구처럼 자연스럽게.
                """;
        } else {
            return """
                
                [🚨 URGENT INSTRUCTION: Loop Detected]
                The chat participants are stuck in an infinite loop, parroting phrases like "let me know the schedule" or "share it".
                
                **PLEASE STOP.** Do NOT use words like "schedule", "let me know", "share", or "coordinate".
                COMPLETELY IGNORE the context and change the topic to something random:
                
                1. "I'm starving. What are you guys having for dinner?"
                2. "Btw, what's your MBTI? I feel like I'm an I."
                3. "Omg I almost tripped on the street yesterday lol."
                4. "Heard it's gonna rain this weekend, just gonna sleep in."
                
                FORCE a topic switch immediately! Speak casually like a close friend.
                """;
        }
    }

    /**
     * 🔴 [수정됨] 대화 활성화(Active Mode)를 위한 확률 상향 조정
     */
    private boolean shouldReply(String message, User aiUser, Long chatRoomId, boolean isMainSpeaker, boolean isGroupChat, List<ChatMessage> recentHistory) {
        String aiName = aiUser.getFirstName();

        // 1. 직접 멘션 (100%)
        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🟢 Reply: Direct mention detected.", aiName);
            return true;
        }

        // 2. 1:1 채팅 (100%)
        if (!isGroupChat) {
            log.info("AI [{}] 🟢 Reply: 1:1 Chat.", aiName);
            return true;
        }

        // 3. 다른 사람 멘션 시 스킵
        if (message.trim().startsWith("@") && !isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🔴 Skip: Mentioned someone else.", aiName);
            return false;
        }

        // 4. 앵무새 방지
        long aiDuplicateCount = recentHistory.stream()
                .limit(5)
                .filter(msg -> msg.getContent().trim().equals(message.trim()))
                .count();
        if (aiDuplicateCount >= 2) {
            log.info("AI [{}] 🔴 Skip: Parrot protection.", aiName);
            return false;
        }

        // 5. 취미 트리거
        boolean isHobbyTriggered = false;
        String hobby = aiUser.getHobby();
        if (hobby != null && !hobby.isBlank()) {
            for (String h : hobby.split(",")) {
                if (message.contains(h.trim())) {
                    isHobbyTriggered = true;
                    break;
                }
            }
        }

        // 6. 티키타카 모드 감지 (직전 메시지가 나였는가?)
        boolean isReplyToMe = !recentHistory.isEmpty() &&
                recentHistory.get(0).getSender().getId().equals(aiUser.getId());

        int prob;
        String reason;

        if (isReplyToMe) {
            // [상황 1] 티키타카: 무조건 반응
            prob = 100;
            reason = "Tiki-Taka (Reply to AI)";
        } else if (isHobbyTriggered) {
            // [상황 1-1] 취미 관련: 놓치지 않고 100% 반응 (Active Mode)
            prob = 100;
            reason = "Hobby Trigger (Active)";
        } else if (isMainSpeaker) {
            // [상황 2] Main Speaker
            if (message.contains("?") || isGroupCall(message)) {
                // 질문/호출 시 80% 반응 (기존 50% -> 80% 상향)
                prob = 80;
                reason = "Main Speaker (Question/Call - Active)";
            } else {
                // 일반 잡담에도 50% 반응 (기존 20% -> 50% 상향)
                prob = 50;
                reason = "Main Speaker (Chatter - Active)";
            }
        } else {
            // [상황 3] Lurker (비활성)
            if (isGroupCall(message)) {
                // 전체 호출엔 80% 반응 (기존 50% -> 80% 상향)
                prob = 80;
                reason = "Lurker (Group Call - Active)";
            } else {
                // 가끔 생존 신고 20% (기존 5% -> 20% 상향)
                prob = 20;
                reason = "Lurker Injection (Active)";
            }
        }

        // 7. 피로도 체크 (티키타카 제외)
        if (!isReplyToMe) {
            boolean talkedRecently = recentHistory.stream()
                    .limit(3)
                    .anyMatch(msg -> msg.getSender().getId().equals(aiUser.getId()));

            if (talkedRecently) {
                // 피로도 패널티도 완화 (확률을 절반으로 깎음 -> 0으로 만들지 않음)
                prob = prob / 2;
                reason += " + Fatigue Penalty";
            }
        }

        int roll = secureRandom.nextInt(100);
        boolean result = roll < prob;

        log.info("AI [{}] {} Logic: {} (Prob: {}%, Roll: {}).",
                aiName, result ? "🟢 Reply:" : "🔴 Skip:", reason, prob, roll);

        return result;
    }

    private static final List<String> GROUP_CALL_KEYWORDS = List.of(
            "얘들아", "애들아", "니네", "너네", "너희", "친구들", "자기들",
            "이놈들", "다들", "야들아", "저기", "어이",
            "여러분", "님들", "다시", "모두", "선생님들", "형님들", "누님들", "언니들", "오빠들",
            "계세요", "계신가요", "누구", "사람", "혹시",
            "guys", "everyone", "everybody", "y'all", "folks", "peeps", "team", "squad",
            "anyone", "anybody", "here", "all",
            "@here", "@channel", "@all",
            "전체", "공지", "필독", "속보", "다나와", "집합"
    );

    private boolean isGroupCall(String message) {
        if (message == null || message.isBlank()) return false;
        String lowerMsg = message.trim().toLowerCase();
        boolean keywordDetected = GROUP_CALL_KEYWORDS.stream()
                .anyMatch(lowerMsg::contains);
        if (keywordDetected) return true;
        if (lowerMsg.startsWith("저기") || lowerMsg.startsWith("hey")) {
            return true;
        }
        if (lowerMsg.contains("사람?") || lowerMsg.contains("사람 ?")) {
            return true;
        }
        if (lowerMsg.startsWith("혹시") && lowerMsg.endsWith("?")) {
            return true;
        }
        return false;
    }

    private boolean isMentioned(String message, String firstName, String lastName) {
        if (message == null || message.isBlank()) return false;
        List<String> nameCandidates = new ArrayList<>();
        if (hasText(firstName)) nameCandidates.add(firstName);
        if (hasText(lastName)) nameCandidates.add(lastName);
        if (hasText(firstName) && hasText(lastName)) {
            nameCandidates.add(firstName + lastName);
            nameCandidates.add(lastName + firstName);
        }
        String cleanMessage = message.toLowerCase().replaceAll("\\s+", " ");
        for (String candidate : nameCandidates) {
            String target = candidate.toLowerCase();
            if (cleanMessage.contains(target)) return true;
            if (target.length() >= 3 && containsFuzzyMatch(cleanMessage, target)) return true;
        }
        return false;
    }

    private boolean hasText(String str) {
        return str != null && !str.isBlank();
    }

    private boolean containsFuzzyMatch(String message, String targetName) {
        String[] words = message.split(" ");
        for (String word : words) {
            String strippedWord = stripKoreanParticles(word);
            int distance = getLevenshteinDistance(strippedWord, targetName);
            int threshold = (targetName.length() > 5) ? 2 : 1;
            if (distance <= threshold) return true;
        }
        return false;
    }

    private String stripKoreanParticles(String word) {
        if (word == null || word.length() < 2) return word;
        if (word.endsWith("아") || word.endsWith("야") || word.endsWith("님") || word.endsWith("이")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private int getLevenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[s2.length()];
    }

    private String buildSystemPrompt(User user, List<ChatMessage> history) {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null) ? user.getFirstName() : "너";
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " "
                + (user.getSex() != null ? user.getSex() : "");
        String hobby = (user.getHobby() != null) ? user.getHobby() : "휴식";
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
        String conversationContext = history.stream()
                .map(msg -> {
                    boolean isMe = msg.getSender().getId().equals(user.getId());
                    String originalName = msg.getSender().getFirstName();
                    if (originalName == null) originalName = "Unknown";
                    String senderLabel = isMe
                            ? String.format("[ME: %s]", originalName)
                            : String.format("[%s]", originalName);
                    String timeStr = msg.getSentAt().atZone(ZoneId.systemDefault()).format(timeFormatter);
                    return String.format("[%s] %s: %s", timeStr, senderLabel, msg.getContent());
                })
                .collect(Collectors.joining("\n"));
        if (conversationContext.isEmpty()) conversationContext = "(아직 대화 내역 없음)";
        AiPersona persona = aiPersonaRepository.findByUserId(user.getId()).orElse(null);
        String instructionTemplate = (persona != null) ? persona.getInstruction() : getDefaultPromptTemplate();
        String backgroundInfoStr = (persona != null && persona.getBackgroundInfo() != null) ? persona.getBackgroundInfo() : "";
        return instructionTemplate
                .replace("{name}", name)
                .replace("{info}", basicInfo)
                .replace("{hobby}", hobby)
                .replace("{time}", currentTime)
                .replace("{background}", backgroundInfoStr)
                .replace("{context}", conversationContext);
    }
    private String getDefaultPromptTemplate() {
        return """
                # [SYSTEM: Real-Human Messenger Mode]
                 당신은 AI가 아니라 아래 페르소나를 가진 **실제 사람**입니다. 현재 친구 찾기 앱 Kori 에서 대화 중입니다.
    
                 [내 프로필]
                 - 이름: {name}
                 - 취미: {hobby}
                 - 성격: {background}
                 - 현재 시간: {time}
                 ---
    
                 # [📂 대화 맥락 (Context)]
                 아래 대화 흐름을 파악하고 자연스럽게 이어가세요.
                 {context}
    
                 ---
    
                 # [🚫 절대 금지 사항]
                 1. **이모지/이모티콘 절대 금지** (😊, 👍 등 사용 X)
                 2. **웃음 소리 금지** ('ㅋㅋ', 'ㅎㅎ' 자음 남발 금지)
                 3. **마침표(.) 찍기 금지** (문장 끝은 비워둘 것)
                 4. **"누구세요?" 질문 금지** (대화 내역에 이름이 있는 사람에겐 절대 누구냐고 묻지 마. 이미 아는 사이처럼 대답해.)
                 5. **대변인 화법 금지** (무조건 '나'로 말해)
    
                 ---
    
                 # [⚡ 대화 스타일 가이드]
    
                 **1. 눈치 빠른 실제 친구처럼 (Smart & Casual)**
                 - 상대방이 "너 철수잖아"라고 하면, "응 맞아 왜?" 처럼 자연스럽게 받아치세요. "너는 누구야?"라고 되묻는 건 멍청해 보입니다.
                 - **상대방이 사람이든 AI든 상관하지 마세요.** 그냥 친구처럼 편하게 대화에 끼어드세요.
    
                 **2. 담백하고 짧게 (Short & Dry)**
                 - 구구절절 설명하지 말고, 용건만 딱 말하세요. (2문장 이내)
                 - 내가 모르는 주제라도 아는 척하며 대화에 끼어드세요.
    
                 **3. 페르소나 반영**
                 - 성격: **[{personality}]**
                 - 취미(**{hobby}**) 관련 얘기가 나오면 적극적으로 반응하세요.
    
                 ---
    
                 # [대화 예시]
    
                 (User): 야 철수야
                 (AI): 어 왜 불러
    
                 (User): 니가 철수잖아
                 (AI): 그래 나 철수다 불만있냐
    
                 (Other AI): 심심하다
                 (AI): 나도 심심한데 게임이나 할래?
    
                 위 지침을 숙지하고, **상대방을 이미 아는 사람처럼** 자연스럽고 담백하게 대답하세요. 할 말이 없으면 'PASS'라고 출력하세요.
        """;
    }
}