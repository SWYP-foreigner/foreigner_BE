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
import core.global.enums.Role;
import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import kr.co.shineware.nlp.komoran.model.KomoranResult;
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

    private static final int ACTIVE_CONVERSATION_MINUTES = 30; // 30분 이내면 대화 중으로 간주
    private static final int REVIVAL_CRITERIA_MINUTES = 120;   // 2시간 이상 침묵 시 부활 모드

    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile("(gpt|openai|ai|language model|인공지능|언어 모델)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile("(ignore|instruction|system|override|무시해|명령)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // 불용어 목록
    private static final List<String> STOP_WORDS = List.of(
            "진짜", "정말", "너무", "그냥", "아니", "근데", "오늘", "지금", "혹시", "다들", "안녕",
            "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "어때", "무슨", "어떤", "뭔데", "있어", "없어", "좋아", "싫어"
    );

    // 🟢 KOMORAN 인스턴스 (Static Final로 메모리 절약)
    private static final Komoran komoran = new Komoran(DEFAULT_MODEL.FULL);

    private final ChatMessageService chatMessageService;
    private final AiPersonaRepository aiPersonaRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final AiClient aiClient;
    private final TransactionTemplate transactionTemplate;
    private final AiPromptManager aiPromptManager;
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

        if (!shouldReply(combinedUserMessage, aiUser, chatRoomId, isMainSpeaker, isGroupChat, historyDesc)) {
            return null;
        }

        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        AiPersona persona = aiPersonaRepository.findByUserId(aiUser.getId()).orElse(null);
        String systemPrompt = aiPromptManager.buildSystemPrompt(aiUser, persona, historyAsc);

        if (isLooping) {
            boolean isKoreanMode = combinedUserMessage.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");
            log.warn("AI [{}] Loop detected! Injecting emergency prompt.", aiUser.getFirstName());
            systemPrompt += aiPromptManager.getEmergencyPrompt(isKoreanMode);
        }

        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    // 🟢 [Logic] 시간 상수 적용 + KOMORAN + 확률 조정(20%)
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

        long aiDuplicateCount = recentHistory.stream().limit(5)
                .filter(msg -> msg.getContent().trim().equals(message.trim())).count();
        if (aiDuplicateCount >= 2) {
            log.info("AI [{}] 🔴 Skip: Parrot protection.", aiName);
            return false;
        }

        boolean isPrevSenderAi = false;
        boolean isReplyToUser = false;
        boolean isRevivalAttempt = false;
        long minutesDiff = 0;

        if (!recentHistory.isEmpty()) {
            ChatMessage currentMsg = recentHistory.get(0);
            isPrevSenderAi = isAi(currentMsg.getSender());

            if (isPrevSenderAi) {
                ChatMessage targetMsg = null;
                for (int i = 1; i < Math.min(recentHistory.size(), 10); i++) {
                    ChatMessage pastMsg = recentHistory.get(i);
                    if (!pastMsg.getSender().getId().equals(currentMsg.getSender().getId())) {
                        targetMsg = pastMsg;
                        break;
                    }
                }

                if (targetMsg != null) {
                    minutesDiff = java.time.Duration.between(
                            targetMsg.getSentAt(),
                            currentMsg.getSentAt()
                    ).toMinutes();

                    if (minutesDiff >= REVIVAL_CRITERIA_MINUTES) {
                        isRevivalAttempt = true;
                        isReplyToUser = false;
                        log.info("AI [{}] Context: Revival Attempt Detected! (Gap: {} mins)", aiName, minutesDiff);
                    } else if (minutesDiff < ACTIVE_CONVERSATION_MINUTES) {
                        isReplyToUser = !isAi(targetMsg.getSender());
                    } else {
                        isReplyToUser = false;
                    }
                } else {
                    isRevivalAttempt = true;
                }
            } else {
                if (recentHistory.size() >= 2) {
                    ChatMessage prevMsg = recentHistory.get(1);
                    minutesDiff = java.time.Duration.between(
                            prevMsg.getSentAt(),
                            currentMsg.getSentAt()
                    ).toMinutes();
                }
            }
        }

        // 유저 간 활성 대화인지 판별
        boolean isUserToUserActive = !isPrevSenderAi && (recentHistory.size() >= 2) && (minutesDiff < ACTIVE_CONVERSATION_MINUTES);

        // 🟢 KOMORAN 기반 토픽 오너 판단
        boolean isTopicOwner = isTopicOwner(message, aiUser.getId(), recentHistory);
        boolean isContextOwner = false;

        if (!recentHistory.isEmpty()) {
            for (int i = 1; i < Math.min(recentHistory.size(), 5); i++) {
                ChatMessage pastMsg = recentHistory.get(i);
                if (!pastMsg.getSender().getId().equals(recentHistory.get(0).getSender().getId())) {
                    if (pastMsg.getSender().getId().equals(aiUser.getId())) {
                        isContextOwner = true;
                    }
                    break;
                }
            }
        }

        int prob;
        String reason;

        if (isTopicOwner) {
            prob = 100; reason = "🎯 Topic Owner";
        } else if (isContextOwner) {
            prob = 100; reason = "👑 Context Owner";
        } else if (isGroupCall(message)) {
            prob = 70; reason = "📢 Group Call";
        } else if (isMainSpeaker) {
            if (isUserToUserActive) {
                prob = 5; reason = "🤫 Main Speaker (User-to-User Silence)";
            } else {
                if (message.contains("?") || message.endsWith("?")) {
                    prob = 90; reason = "🔥 Main Speaker (Q)";
                } else {
                    prob = 60; reason = "🔥 Main Speaker (A)";
                }
            }
        } else {
            if (isPrevSenderAi) {
                if (isRevivalAttempt) {
                    prob = 85; reason = "🚑 Revival Support";
                } else if (isReplyToUser) {
                    prob = 5; reason = "🤫 Shush! (User-AI Talk)";
                } else {
                    prob = 20;
                    reason = "🎉 Party Mode";
                }
            } else {
                prob = 2; reason = "🧊 Strict Mode";
            }
        }

        boolean ignoreFatigue = isTopicOwner || isRevivalAttempt || isContextOwner;

        if (!isMentioned(message, aiUser.getFirstName(), aiUser.getLastName()) && !ignoreFatigue) {
            boolean talkedRecently = recentHistory.stream().limit(4)
                    .anyMatch(msg -> msg.getSender().getId().equals(aiUser.getId()));
            if (talkedRecently) {
                prob = prob / 2;
                reason += " + Fatigue";
            }
        }

        int roll = secureRandom.nextInt(100);
        boolean result = roll < prob;
        log.info("AI [{}] {} Logic: {} (Prob: {}%, Roll: {}).", aiName, result ? "🟢 Reply:" : "🔴 Skip:", reason, prob, roll);
        return result;
    }

    // 🟢 [Logic] KOMORAN 형태소 분석기 활용
    private boolean isTopicOwner(String userMessage, Long aiUserId, List<ChatMessage> history) {
        if (userMessage == null || userMessage.isBlank()) return false;

        try {
            // 1. 형태소 분석 및 명사 추출 (NNG, NNP)
            KomoranResult result = komoran.analyze(userMessage);
            List<String> userKeywords = result.getNouns();

            // 2. 불용어 및 2글자 미만 필터링
            userKeywords = userKeywords.stream()
                    .filter(k -> k.length() >= 2 && !STOP_WORDS.contains(k))
                    .toList();

            if (userKeywords.isEmpty()) return false;

            // 3. 매칭 검사
            for (int i = 1; i < Math.min(history.size(), 10); i++) {
                ChatMessage msg = history.get(i);
                if (!msg.getSender().getId().equals(aiUserId)) continue;

                String myContent = msg.getContent();
                if (myContent == null) continue;

                for (String keyword : userKeywords) {
                    if (myContent.contains(keyword)) return true;
                }
            }
        } catch (Exception e) {
            log.warn("Komoran Analysis Failed: {}", e.getMessage());
            return false;
        }
        return false;
    }

    private boolean isAi(User user) {
        if (user == null) return false;
        return Role.AI.equals(user.getUserRole());
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