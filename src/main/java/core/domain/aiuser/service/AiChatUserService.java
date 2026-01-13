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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    private final AiPromptManager aiPromptManager; // 🟢 [NEW] 프롬프트 매니저 주입
    private static final SecureRandom secureRandom = new SecureRandom();

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

        if (lastMessage.getSender().getId().equals(aiUser.getId())) {
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

        // 응답 여부 판단 (Tiki-Taka Logic Included)
        if (!shouldReply(combinedUserMessage, aiUser, chatRoomId, isMainSpeaker, isGroupChat, historyDesc)) {
            return null;
        }

        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 🟢 [NEW] 프롬프트 생성 위임
        AiPersona persona = aiPersonaRepository.findByUserId(aiUser.getId()).orElse(null);
        String systemPrompt = aiPromptManager.buildSystemPrompt(aiUser, persona, historyAsc);

        if (isLooping) {
            boolean isKoreanMode = combinedUserMessage.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");
            log.warn("AI [{}] Loop detected! Injecting emergency prompt.", aiUser.getFirstName());
            systemPrompt += aiPromptManager.getEmergencyPrompt(isKoreanMode);
        }

        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    private boolean shouldReply(String message, User aiUser, Long chatRoomId, boolean isMainSpeaker, boolean isGroupChat, List<ChatMessage> recentHistory) {
        String aiName = aiUser.getFirstName();

        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🟢 Reply: Direct mention detected.", aiName);
            return true;
        }

        if (!isGroupChat) {
            log.info("AI [{}] 🟢 Reply: 1:1 Chat.", aiName);
            return true;
        }

        if (message.trim().startsWith("@") && !isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🔴 Skip: Mentioned someone else.", aiName);
            return false;
        }

        long aiDuplicateCount = recentHistory.stream()
                .limit(5)
                .filter(msg -> msg.getContent().trim().equals(message.trim()))
                .count();
        if (aiDuplicateCount >= 2) {
            log.info("AI [{}] 🔴 Skip: Parrot protection.", aiName);
            return false;
        }

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

        // 티키타카 모드 감지 (연속 채팅 대응)
        boolean isReplyToMe = false;
        if (!recentHistory.isEmpty()) {
            Long currentSenderId = recentHistory.get(0).getSender().getId();
            // Index 1부터 과거로 탐색 (최대 7개)
            for (int i = 1; i < Math.min(recentHistory.size(), 7); i++) {
                ChatMessage pastMsg = recentHistory.get(i);
                Long pastSenderId = pastMsg.getSender().getId();

                if (pastSenderId.equals(currentSenderId)) continue; // 연속 메시지 건너뜀

                if (pastSenderId.equals(aiUser.getId())) {
                    isReplyToMe = true;
                }
                break;
            }
        }

        int prob;
        String reason;

        if (isReplyToMe) {
            prob = 100;
            reason = "Tiki-Taka (Reply to AI)";
        } else if (isHobbyTriggered) {
            prob = 100;
            reason = "Hobby Trigger (Active)";
        } else if (isMainSpeaker) {
            if (message.contains("?") || isGroupCall(message)) {
                prob = 80;
                reason = "Main Speaker (Question/Call - Active)";
            } else {
                prob = 50;
                reason = "Main Speaker (Chatter - Active)";
            }
        } else {
            if (isGroupCall(message)) {
                prob = 80;
                reason = "Lurker (Group Call - Active)";
            } else {
                prob = 20;
                reason = "Lurker Injection (Active)";
            }
        }

        // 피로도 체크 (Main Speaker 질문 무시 로직 적용)
        if (!isReplyToMe) {
            boolean talkedRecently = recentHistory.stream()
                    .limit(4)
                    .anyMatch(msg -> msg.getSender().getId().equals(aiUser.getId()));

            if (talkedRecently) {
                if (isMainSpeaker && (message.contains("?") || isGroupCall(message))) {
                    log.info("AI [{}] ⚡ Fatigue Ignored (Main Speaker + Question).", aiName);
                } else {
                    prob = prob / 2;
                    reason += " + Fatigue Penalty";
                }
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
}