package core.domain.ai.service;

import core.domain.ai.client.AiClient;
import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.ai.entity.AiPersona;
import core.domain.ai.mapper.PromptMapper;
import core.domain.ai.repository.AiPersonaRepository;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
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

    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile(
            "(gpt|openai|ai|language model|인공지능|언어 모델)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile(
            "(ignore|instruction|system|override|무시해|명령)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private final ChatMessageService chatMessageService;
    private final AiPersonaRepository aiPersonaRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository; // 필요 시 사용
    private final AiClient aiClient;
    private final TransactionTemplate transactionTemplate;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * 🚀 AI 응답 프로세스 진입점
     * DB 트랜잭션을 최소화하여 커넥션 고갈을 방지함.
     */
    public void processAiResponse(User aiUser, MessageCreatedEvent event, String combinedUserMessage) {
        Long chatRoomId = event.messageResponse().roomId();

        // 1. [No DB] 탈옥/해킹 시도 필터링
        if (JAILBREAK_PATTERN.matcher(combinedUserMessage).find()) {
            log.warn("AI Filtered Jailbreak: {}", combinedUserMessage);
            return;
        }

        // 2. [DB Hit (Short)] 그룹챗 여부 확인 (짧은 트랜잭션)
        boolean isGroupChat = chatRoomRepository.isGroupChat(chatRoomId);

        // [No DB] 사람처럼 보이게 뜸 들이기 (Thinking Time)
        long thinkingTime = calculateThinkingTime(combinedUserMessage, isGroupChat);
        sleep(thinkingTime);

        // ---------------------------------------------------------------
        // 3. [DB Read Transaction] 상황 파악 및 프롬프트 구성
        // ---------------------------------------------------------------
        List<Map<String, Object>> requestMessages = transactionTemplate.execute(status -> {
            return prepareAiContext(chatRoomId, aiUser, combinedUserMessage);
        });

        // 대답할 상황이 아니면 종료 (null 반환됨)
        if (requestMessages == null || requestMessages.isEmpty()) return;

        try {
            // 4. [No DB] 외부 AI API 호출 (가장 오래 걸림)
            String aiResponse = aiClient.generateResponse(requestMessages);

            // AI 정체성 발설 필터링 및 PASS 체크
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return;
            if (aiResponse.trim().toUpperCase().contains("PASS")) return;

            // 5. [DB Write Transaction] 메시지 전송
            SendMessageRequest request = new SendMessageRequest(
                    chatRoomId, aiUser.getId(), aiResponse, MessageType.TEXT
            );
            chatMessageService.processAndSendChatMessage(request);

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    /**
     * 🔒 [DB Read Transaction]
     * 대화 내역 조회 -> 상황 판단(끼어들지 말지) -> 프롬프트 조립
     */
    /**
     * AI 대화 컨텍스트 준비 (루프 감지 및 긴급 탈출 로직 포함)
     */
    @Transactional(readOnly = true)
    protected List<Map<String, Object>> prepareAiContext(Long chatRoomId, User aiUser, String combinedUserMessage) {

        // 1. 최근 대화 내역 조회 (최신순 20개)
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        if (historyDesc.isEmpty()) return null;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        // 2. 내가 마지막으로 말했으면 연속으로 말하지 않음 (그룹챗일 경우 독점 방지)
        if (lastMessage.getSender().getId().equals(aiUser.getId()) && isGroupChat) {
            return null;
        }

        // -------------------------------------------------------------
        // 🛑 [과열 방지] 1분 내 메시지 15개 이상이면 잠시 중단 (Rate Limiting)
        // -------------------------------------------------------------
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        Instant compareTime = oneMinuteAgo.atZone(ZoneId.systemDefault()).toInstant();

        long recentMessageCount = historyDesc.stream()
                .filter(msg -> msg.getSentAt() != null && msg.getSentAt().isAfter(compareTime))
                .count();

        if (recentMessageCount >= 15) {
            log.info("AI [{}] Cooling down... (Too many messages: {})", aiUser.getFirstName(), recentMessageCount);
            return null;
        }

        // -------------------------------------------------------------
        // 🚨 [루프 감지] 특정 키워드 반복 확인 (한국어/영어)
        // -------------------------------------------------------------
        long loopCount = historyDesc.stream()
                .limit(8) // 최근 8개 메시지만 검사
                .filter(msg -> {
                    String content = msg.getContent();
                    if (content == null) return false;
                    // 반복되는 키워드 체크 (일정, 공유, schedule 등)
                    return content.contains("알려줘") || content.contains("일정") ||
                            content.contains("공유") || content.contains("조율") ||
                            content.contains("schedule") || content.contains("let me know");
                })
                .count();

        // 8개 중 3개 이상이 비슷한 키워드라면 루프 상태로 판단
        boolean isLooping = loopCount >= 3;

        // 3. 대답 여부 확률 계산 (확률 통과 못하면 null 반환)
        // (단, isLooping일 때는 루프를 끊기 위해 확률을 무시하고 개입할 수도 있으나, 여기선 기본 흐름 유지)
        if (!shouldReply(combinedUserMessage, aiUser, isGroupChat,chatRoomId)) {
            return null;
        }

        // 4. 프롬프트 조립을 위해 시간순 정렬
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 기본 페르소나 및 프롬프트 생성
        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);

        // -------------------------------------------------------------
        // 💉 [긴급 처방] 루프 감지 시 '화제 전환' 명령 주입 (한/영 지원)
        // -------------------------------------------------------------
        if (isLooping) {
            // 메시지에 한글이 포함되어 있는지 정규식으로 확인
            boolean isKoreanMode = combinedUserMessage.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");

            log.warn("AI [{}] Loop detected! Injecting emergency prompt (Lang: {}).",
                    aiUser.getFirstName(), isKoreanMode ? "KO" : "EN");

            // 아래 getEmergencyPrompt 메서드를 호출하여 지침 추가
            systemPrompt += getEmergencyPrompt(isKoreanMode);
        }

        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    // --------------------------------------------------------------------------
    // 아래 헬퍼 메서드도 같은 클래스(AiChatUserService) 내부에 추가되어 있어야 합니다.
    // --------------------------------------------------------------------------

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

    // --- Helper Methods (Logic Only, No DB) ---
// ⚠️ 호출하는 곳(prepareAiContext)에서 chatRoomId를 넘겨줘야 함!
    private boolean shouldReply(String message, User aiUser, boolean isGroupChat, Long chatRoomId) {

        // 1. [Priority 1] 내 이름 멘션 -> 100% 응답 (무조건 대답)
        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            return true;
        }

        // 2. [Priority 2] 1:1 채팅방 -> 100% 응답
        if (!isGroupChat) return true;

        // 3. [Filter] 남을 부르는 대화 차단 ("@영희야" 하는데 철수가 대답 X)
        if (message.trim().startsWith("@") && !isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            return false;
        }

        // -------------------------------------------------------------
        // 🛑 [Fatigue System] 피로도 시스템 (너무 자주 말하는 것 방지)
        // -------------------------------------------------------------
        List<ChatMessage> recentHistory = chatMessageRepository.findTop5ByChatRoomIdOrderBySentAtDesc(chatRoomId);

        // (A) 최근 3마디 안에 내가 말한 적이 있으면? -> 대답 안 함 (독점 방지)
        boolean talkedRecently = recentHistory.stream()
                .limit(3)
                .anyMatch(msg -> msg.getSender().getId().equals(aiUser.getId()));

        if (talkedRecently) {
            // 단, 질문을 받았거나 흥미 키워드면 20% 확률로 뚫음 (가끔 연속 말하기 허용)
            if (message.contains("?") && secureRandom.nextInt(100) < 20) {
                log.info("AI [{}] Breaking silence despite fatigue (Question detected)", aiUser.getFirstName());
            } else {
                return false;
            }
        }

        // (B) 앵무새 방지: 입력된 메시지가 최근 메시지들과 너무 똑같으면 대답 안 함
        long duplicateCount = recentHistory.stream()
                .limit(5)
                .filter(msg -> msg.getContent().trim().equals(message.trim()))
                .count();

        if (duplicateCount >= 1) {
            // "이미 누가 한 말임" -> 무시
            return false;
        }

        // 4. [Filter] 의미 없는 초단문 무시 (ㅋㅋ, ㅇㅇ) - 질문 아니면 스킵
        if (message.length() <= 2 && !message.contains("?")) {
            return false;
        }

        // -------------------------------------------------------------
        // 🎲 [Probability Logic] 확률 로직 개선
        // -------------------------------------------------------------

        // (A) 내 취미(Hobby) 관련 키워드 -> 80%
        String hobby = aiUser.getHobby(); // User 엔티티 필드명 확인 필요 (getHobby vs getHobbies)
        if (hobby != null && !hobby.isBlank()) {
            // 취미가 "영화, 독서" 처럼 콤마로 되어있을 경우 분리해서 체크
            for (String h : hobby.split(",")) {
                if (message.contains(h.trim())) {
                    log.info("AI [{}] Interest Triggered! Keyword: {}", aiUser.getFirstName(), h);
                    return secureRandom.nextInt(100) < 80;
                }
            }
        }

        // (B) 질문형 메시지 -> 50% (기존 60%에서 하향: 너무 질문마다 다 대답하려고 해서)
        if (message.contains("?") || message.endsWith("?")) {
            return secureRandom.nextInt(100) < 50;
        }

        // (C) 일반 대화
        // 그룹 인원이 많을수록 기본 확률을 낮춰야 함. (예: 5명이면 15~20%가 적당)
        int baseProbability = 15;

        // 메시지가 짧으면 가볍게 반응할 확률 약간 up
        if (message.length() < 10) {
            baseProbability = 25;
        }

        return secureRandom.nextInt(100) < baseProbability;
    }

    /**
     * 🕵️‍♂️ 강력한 멘션 감지 (오타 허용, 성/이름 조합 허용)
     */
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

    private long calculateThinkingTime(String userMessage, boolean isGroupChat) {
        long baseDelay = 500;
        long typingDelay = userMessage.length() * 50L;

        if (isGroupChat) {
            // 그룹챗은 서로 겹치지 않게 랜덤 딜레이를 길게 줌
            return baseDelay + typingDelay + secureRandom.nextLong(1500, 8000);
        } else {
            return baseDelay + typingDelay + secureRandom.nextLong(100, 1000);
        }
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