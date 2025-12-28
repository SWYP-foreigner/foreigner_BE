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


    private boolean shouldReply(String message, User aiUser, boolean isGroupChat, Long chatRoomId) {
        String aiName = aiUser.getFirstName();

        // 1. [Priority 1] 내 이름 멘션 -> 100%
        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🟢 Reply: Direct mention detected.", aiName);
            return true;
        }

        // 2. 1:1 채팅 -> 100%
        if (!isGroupChat) {
            log.info("AI [{}] 🟢 Reply: 1:1 Chat.", aiName);
            return true;
        }

        // 3. [Filter] 남 부르는 대화 차단
        if (message.trim().startsWith("@") && !isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            log.info("AI [{}] 🔴 Skip: Mentioned someone else.", aiName);
            return false;
        }

        List<ChatMessage> recentHistory = chatMessageRepository.findTop5ByChatRoomIdOrderBySentAtDesc(chatRoomId);

        // -------------------------------------------------------------
        // 🛑 [Fatigue] 최근 3마디 내에 내가 말했으면 참기 (독점 방지)
        // -------------------------------------------------------------
        boolean talkedRecently = recentHistory.stream()
                .limit(3)
                .anyMatch(msg -> msg.getSender().getId().equals(aiUser.getId()));

        if (talkedRecently) {
            // 질문이면 20% 확률로 끼어들기 허용
            if (message.contains("?") && secureRandom.nextInt(100) < 20) {
                log.info("AI [{}] 🟡 Pass: Talked recently but question luck triggered (20%).", aiName);
            } else {
                log.info("AI [{}] 🔴 Skip: Fatigue (Talked recently).", aiName);
                return false;
            }
        }

        // -------------------------------------------------------------
        // 🦜 [Fix] 앵무새 방지
        // -------------------------------------------------------------
        long aiDuplicateCount = recentHistory.stream()
                .limit(5)
                .filter(msg -> msg.getContent().trim().equals(message.trim()))
                .count();

        // 2개 이상이면(방금 유저 말 포함해서 또 있으면) 앵무새로 간주
        if (aiDuplicateCount >= 2) {
            log.info("AI [{}] 🔴 Skip: Parrot protection (Duplicate count: {}).", aiName, aiDuplicateCount);
            return false;
        }

        // -------------------------------------------------------------
        // 🔇 [Silence Breaker] 너무 조용하면 대답 잘 하게 하기
        // -------------------------------------------------------------
        if (recentHistory.size() < 2) {
            boolean success = secureRandom.nextInt(100) < 80;
            log.info("AI [{}] {} Silence Breaker (History size < 2, Roll Result).", aiName, success ? "🟢 Reply:" : "🔴 Skip:");
            return success;
        }

        // 4. 단답형 무시 필터 (질문은 통과)
        boolean isQuestion = message.contains("?") || message.endsWith("니") || message.endsWith("까")
                || message.endsWith("가") || message.endsWith("냐");

        if (message.length() <= 2 && !isQuestion) {
            log.info("AI [{}] 🔴 Skip: Short message without question mark.", aiName);
            return false;
        }

        // -------------------------------------------------------------
        // 🎲 [Probability] 확률 계산
        // -------------------------------------------------------------

        // (A) 취미 키워드
        String hobby = aiUser.getHobby(); // or getHobbies()
        if (hobby != null && !hobby.isBlank()) {
            for (String h : hobby.split(",")) {
                if (message.contains(h.trim())) {
                    boolean success = secureRandom.nextInt(100) < 85;
                    log.info("AI [{}] {} Hobby Trigger '{}' (85%, Roll Result).", aiName, success ? "🟢 Reply:" : "🔴 Skip:", h);
                    return success;
                }
            }
        }

        int prob = 20; // 기본 확률
        String reason = "Base Probability";

        // (B) 다국어 호출 감지 (isGroupCall 메서드 필요)
        if (isGroupCall(message)) {
            prob = 60;
            reason = "Group Call Trigger";
        }
        // (C) 질문형
        else if (isQuestion) {
            prob = 50;
            reason = "Question Type";
        }

        // 최종 주사위 굴리기
        int roll = secureRandom.nextInt(100);
        boolean result = roll < prob;

        log.info("AI [{}] {} Logic: {} (Prob: {}%, Roll: {}).", aiName, result ? "🟢 Reply:" : "🔴 Skip:", reason, prob, roll);
        return result;
    }
    // --------------------------------------------------------------------------
    // 🌍 [Global] 그룹 호출 감지 키워드 사전
    // --------------------------------------------------------------------------
    private static final List<String> GROUP_CALL_KEYWORDS = List.of(
            // 1. 한국어 (반말/친구)
            "얘들아", "애들아", "니네", "너네", "너희", "친구들", "자기들",
            "이놈들", "다들", "야들아", "저기", "어이",

            // 2. 한국어 (존대/공손/다수)
            "여러분", "님들", "다시", "모두", "선생님들", "형님들", "누님들", "언니들", "오빠들",
            "계세요", "계신가요", "누구", "사람", "혹시", // "혹시 누구 계신가요?" 패턴 대응

            // 3. 영어 (Global)
            "guys", "everyone", "everybody", "y'all", "folks", "peeps", "team", "squad",
            "anyone", "anybody", "here", "all",

            // 4. 메신저/인터넷 밈 & 특수 문법
            "@here", "@channel", "@all", // 슬랙/디스코드 스타일
            "전체", "공지", "필독", "속보", "다나와", "집합"
    );

    /**
     * 메시지가 특정 개인이 아닌 '그룹 전체'를 부르는 신호인지 감지합니다.
     * 단순 포함(contains)뿐만 아니라 문맥적 뉘앙스를 파악하여 정확도를 높입니다.
     */
    private boolean isGroupCall(String message) {
        if (message == null || message.isBlank()) return false;

        // 1. 정규화: 소문자로 변환 및 앞뒤 공백 제거
        String lowerMsg = message.trim().toLowerCase();

        // 2. [Fast Check] 키워드 포함 여부 검사
        boolean keywordDetected = GROUP_CALL_KEYWORDS.stream()
                .anyMatch(lowerMsg::contains);

        if (keywordDetected) return true;

        // 3. [Advanced] 키워드는 없지만 그룹 호출로 볼 수 있는 패턴 분석

        // Case A: "저기요" 같은 말로 시작할 때 (주목 끌기)
        if (lowerMsg.startsWith("저기") || lowerMsg.startsWith("hey")) {
            return true;
        }

        // Case B: 질문을 던지는데 대상이 명확하지 않은 경우 ("~ 있어?", "~ 아는 사람?")
        // 예: "심심한 사람?", "롤 할 사람?"
        if (lowerMsg.contains("사람?") || lowerMsg.contains("사람 ?")) {
            return true;
        }

        // Case C: "혹시"로 시작해서 물음표로 끝나는 경우 (조심스러운 전체 질문)
        // 예: "혹시 오늘 비 오나요?" -> 대답해주는 게 좋음
        if (lowerMsg.startsWith("혹시") && lowerMsg.endsWith("?")) {
            return true;
        }

        return false;
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