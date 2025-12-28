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
    @Transactional(readOnly = true)
    protected List<Map<String, Object>> prepareAiContext(Long chatRoomId, User aiUser, String combinedUserMessage) {
        // 1. 최근 대화 내역 조회 (최신순 20개)
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        if (historyDesc.isEmpty()) return null;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        // 2. 내가 마지막으로 말했으면 연속으로 말하지 않음 (그룹챗일 경우)
        if (lastMessage.getSender().getId().equals(aiUser.getId()) && isGroupChat) {
            return null;
        }

        // -------------------------------------------------------------
        // 🛑 [과열 방지 시스템] AI끼리 무한 루프 방지
        // 최근 1분 동안 메시지가 15개 이상 쏟아졌다면, AI들은 잠시 휴식
        // -------------------------------------------------------------
        long recentMessageCount = historyDesc.stream()
                .filter(msg -> msg.getSentAt().isAfter(Instant.from(LocalDateTime.now().minusMinutes(1))))
                .count();

        if (recentMessageCount >= 15) {
            log.info("AI [{}] Cooling down... (Too many messages: {})", aiUser.getFirstName(), recentMessageCount);
            return null; // 대화 중단
        }

        // 3. 대답 여부 확률 계산 (티키타카 로직)
        if (!shouldReply(combinedUserMessage, aiUser, isGroupChat)) {
            // 로그 너무 많으면 주석 처리 가능
            // log.info("AI [{}] PASS", aiUser.getFirstName());
            return null;
        }

        // 4. 프롬프트 조립
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc); // 시간순 정렬

        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);

        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    // --- Helper Methods (Logic Only, No DB) ---

    private boolean shouldReply(String message, User aiUser, boolean isGroupChat) {
        // 1. [Priority 1] 내 이름 멘션 -> 100% 응답
        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            return true;
        }

        // 2. [Priority 2] 1:1 채팅방 -> 100% 응답
        if (!isGroupChat) return true;

        // 3. [Filter] 남을 부르는 대화 차단 ("@영희야" 하는데 철수가 대답 X)
        // 메시지가 @로 시작하는데 내 이름은 없다? -> 남한테 하는 말임
        if (message.trim().startsWith("@") && !isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            return false;
        }

        // 4. [Filter] 의미 없는 초단문 무시 (ㅋㅋ, ㅇㅇ) - 단, 질문이면 통과
        if (message.length() <= 2 && !message.contains("?")) {
            return false;
        }

        // -------------------------------------------------------------
        // 🎲 [Probability Logic] 티키타카 확률 부스트
        // -------------------------------------------------------------

        // (A) 내 취미(Hobby) 관련 키워드 등장 -> 85% (급발진)
        String hobby = aiUser.getHobby();
        if (hobby != null && !hobby.isBlank() && message.contains(hobby)) {
            log.info("AI [{}] Interest Triggered! Keyword: {}", aiUser.getFirstName(), hobby);
            return secureRandom.nextInt(100) < 85;
        }

        // (B) 질문형 메시지 -> 60% (AI끼리도 서로 질문하면 대답 잘 함)
        if (message.contains("?") || message.endsWith("?")) {
            return secureRandom.nextInt(100) < 60;
        }

        // (C) 일반 대화 (티키타카)
        int baseProbability = 35; // 기본 35% (기존 15%에서 상향)

        // 메시지가 짧으면(10글자 이하) 가볍게 맞장구칠 확률 더 높임
        if (message.length() < 10) {
            baseProbability = 50;
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