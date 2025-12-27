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
import core.domain.chat.service.ChatMessageService;
import core.domain.user.entity.User;
import core.global.enums.MessageType;
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
    private final AiClient aiClient;
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * 🚀 [DB 누수 해결 로직]
     * 1. 기존에 있던 @Transactional을 제거했습니다. (긴 대기 시간 동안 DB 잡는 문제 해결)
     * 2. DB 조회가 필요한 '준비 단계'만 별도 트랜잭션 메서드(prepareAiContext)로 분리했습니다.
     * 3. 외부 AI 호출(aiClient.generateResponse)은 트랜잭션 없이 실행됩니다.
     */
    public void processAiResponse(User aiUser, MessageCreatedEvent event, String combinedUserMessage) {
        Long chatRoomId = event.messageResponse().roomId();

        // 1. [No DB] 탈옥/해킹 시도 필터링 (메모리 연산)
        if (JAILBREAK_PATTERN.matcher(combinedUserMessage).find()) {
            log.warn("AI [{}] Ignored Jailbreak attempt: {}", aiUser.getFirstName(), combinedUserMessage);
            return;
        }

        // 2. [No DB] 사람처럼 생각하는 척 대기 (Thread Sleep)
        // 이 구간에서 DB 커넥션을 물고 있으면 안 됨 -> 트랜잭션 없음 OK
        long thinkingTime = calculateThinkingTime(combinedUserMessage);
        sleep(thinkingTime);

        // 3. [DB Read Transaction] 대화 컨텍스트 준비
        // 짧게 DB를 조회하여 프롬프트를 만들고 즉시 커넥션을 반환합니다.
        List<Map<String, Object>> requestMessages = prepareAiContext(chatRoomId, aiUser, combinedUserMessage);

        // 대답할 필요가 없거나(SKIP), 오류가 있었다면 중단
        if (requestMessages == null || requestMessages.isEmpty()) {
            return;
        }

        try {
            // 4. [No DB] 외부 AI API 호출 (가장 오래 걸리는 구간 - 수 초 소요)
            // 여기서는 트랜잭션이 없으므로 DB 커넥션 풀이 안전합니다.
            String aiResponse = aiClient.generateResponse(requestMessages);

            // 필터링
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) return;
            if (aiResponse.trim().toUpperCase().contains("PASS")) return;

            // 5. [DB Write Transaction] 결과 전송 및 저장
            // chatMessageService 내부에서 새로운 트랜잭션이 시작되어 안전하게 저장됩니다.
            SendMessageRequest request = new SendMessageRequest(
                    chatRoomId,
                    aiUser.getId(),
                    aiResponse,
                    MessageType.TEXT
            );
            chatMessageService.processAndSendChatMessage(request);

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    /**
     * 🔒 [DB Read Transaction]
     * 대화 내역 조회, 페르소나 조회, 프롬프트 빌드까지만 수행하고 트랜잭션을 종료합니다.
     */
    @Transactional(readOnly = true)
    protected List<Map<String, Object>> prepareAiContext(Long chatRoomId, User aiUser, String combinedUserMessage) {
        // 1. 최근 대화 내역 조회
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        if (historyDesc.isEmpty()) return null;

        ChatMessage lastMessage = historyDesc.get(0);
        ChatRoom chatRoom = lastMessage.getChatRoom();
        boolean isGroupChat = Boolean.TRUE.equals(chatRoom.getIsGroup());

        // 2. 내가 마지막으로 말했으면 스킵 (그룹챗)
        if (lastMessage.getSender().getId().equals(aiUser.getId()) && isGroupChat) {
            log.info("AI [{}] Skip: I spoke last in Group Chat.", aiUser.getFirstName());
            return null;
        }

        long activeHumanCount = historyDesc.stream()
                .limit(10)
                .map(msg -> msg.getSender().getId())
                .filter(id -> !id.equals(aiUser.getId()))
                .distinct()
                .count();

        // 3. 대답 여부 확률 계산
        if (!shouldReply(combinedUserMessage, aiUser, isGroupChat, activeHumanCount)) {
            log.info("AI [{}] PASS (Mode: {}, ActiveHumans: {})",
                    aiUser.getFirstName(), isGroupChat ? "Group" : "1:1", activeHumanCount);
            return null;
        }

        // 4. 프롬프트 조립 (Persona 조회 포함)
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        String systemPrompt = buildSystemPrompt(aiUser, historyAsc);

        // 최종 메시지 리스트 반환
        return PromptMapper.buildInput(systemPrompt, historyAsc, combinedUserMessage, aiUser.getId());
    }

    // --- Helper Methods (DB 접근 없음) ---

    private boolean shouldReply(String message, User aiUser, boolean isGroupChat, long activeHumanCount) {
        // 1. 이름이 불리면 무조건 대답 (100%)
        if (isMentioned(message, aiUser.getFirstName(), aiUser.getLastName())) {
            return true;
        }

        // 2. 1:1 채팅이거나, 그룹방인데 말하는 사람이 1명뿐이면 (사실상 1:1) 무조건 대답
        if (!isGroupChat || activeHumanCount <= 1) {
            return true;
        }

        // 3. [NEW] 내 취미(Hobby) 관련 키워드가 나오면 높은 확률로 끼어듦 (85%)
        // 예: 취미가 "영화"인데 "영화 볼래?" 하면 거의 무조건 반응
        String hobby = aiUser.getHobby();
        if (hobby != null && !hobby.isBlank() && message.contains(hobby)) {
            log.info("AI [{}] Interest Triggered! Keyword: {}", aiUser.getFirstName(), hobby);
            return secureRandom.nextInt(100) < 85;
        }

        // 4. 질문형 메시지일 때 확률 대폭 상향 (30% -> 60%)
        // 그룹챗이어도 질문에는 절반 이상 반응해줘야 '티키타카'가 됨
        if (message.contains("?") || message.endsWith("?")) {
            return secureRandom.nextInt(100) < 60;
        }

        // 5. 일반 평서문일 때 확률 상향 (5% -> 15%)
        // 너무 높으면 AI끼리만 떠들 수 있으니 적당히 올림
        return secureRandom.nextInt(100) < 15;
    }
    /**
     * 🕵️‍♂️ 강력한 멘션 감지 메서드
     * 1. 성(Last), 이름(First), 전체 이름(Full) 모두 체크
     * 2. 대소문자 구분 없음 (Doehyn == doehyn)
     * 3. 오타 허용 (Levenshtein Distance: 도혅 -> 도현 인식)
     * 4. 한국어/영어 혼용 대응을 위해 닉네임 필드 활용 권장
     */
    /**
     * 🕵️‍♂️ 강력한 멘션 감지 메서드 (닉네임 제외)
     * 1. 성(Last), 이름(First), 전체 이름(Full) 조합 체크
     * 2. 대소문자 구분 없음
     * 3. 오타 허용 (Fuzzy Match)
     */
    private boolean isMentioned(String message, String firstName, String lastName) {
        if (message == null || message.isBlank()) return false;

        // 1. 비교할 이름 후보군 생성
        List<String> nameCandidates = new ArrayList<>();

        if (hasText(firstName)) nameCandidates.add(firstName);
        if (hasText(lastName)) nameCandidates.add(lastName);

        if (hasText(firstName) && hasText(lastName)) {
            nameCandidates.add(firstName + lastName);
            nameCandidates.add(firstName + " " + lastName);
            nameCandidates.add(lastName + firstName);
            nameCandidates.add(lastName + " " + firstName);
        }

        String cleanMessage = message.toLowerCase().replaceAll("\\s+", " ");

        for (String candidate : nameCandidates) {
            String target = candidate.toLowerCase();

            if (cleanMessage.contains(target)) return true;

            if (target.length() >= 3) {
                if (containsFuzzyMatch(cleanMessage, target)) return true;
            }
        }
        return false;
    }

    private boolean hasText(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * 메시지 내의 단어들을 쪼개서 이름과 '비슷한지' 검사 (오타 허용)
     */
    private boolean containsFuzzyMatch(String message, String targetName) {
        String[] words = message.split(" ");

        for (String word : words) {
            String strippedWord = stripKoreanParticles(word);
            int distance = getLevenshteinDistance(strippedWord, targetName);
            int threshold = (targetName.length() > 5) ? 2 : 1;

            if (distance <= threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 한국어 조사 제거 (아/야/님/이/가 등)
     * 예: "도현아" -> "도현", "도현님" -> "도현"
     */
    private String stripKoreanParticles(String word) {
        if (word == null || word.length() < 2) return word;
        // 끝글자가 조사인지 확인하고 자름
        if (word.endsWith("아") || word.endsWith("야") || word.endsWith("님") || word.endsWith("이")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /**
     * 레벤슈타인 거리 알고리즘 (두 문자열의 차이 계산)
     * - 외부 라이브러리(Apache Commons) 없이 구현
     */
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

    /**
     * ⏱️ 생각하는 시간 계산
     * - 1:1 채팅: 빠릿하게 반응 (0.5 ~ 1.5초)
     * - 그룹 채팅: 서로 겹치지 않게 텀을 길게 둠 (2초 ~ 12초 랜덤)
     */
    private long calculateThinkingTime(String userMessage, boolean isGroupChat) {
        long baseDelay = 500;
        long typingDelay = userMessage.length() * 50L; // 글자당 0.05초 (조금 더 빠르게)

        if (isGroupChat) {
            long randomDelay = secureRandom.nextLong(2000, 12000);
            return baseDelay + typingDelay + randomDelay;
        } else {

            long randomJitter = secureRandom.nextLong(100, 1000);
            return baseDelay + typingDelay + randomJitter;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 이 메서드는 prepareAiContext(트랜잭션 안)에서 호출되므로 Lazy Loading 안전함
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

        // DB 조회
        AiPersona persona = aiPersonaRepository.findByUserId(user.getId()).orElse(null);

        String instructionTemplate;
        String backgroundInfoStr;

        if (persona != null) {
            instructionTemplate = persona.getInstruction();
            backgroundInfoStr = persona.getBackgroundInfo() != null ? persona.getBackgroundInfo() : "";
        } else {
            instructionTemplate = getDefaultPromptTemplate();
            backgroundInfoStr = "";
        }

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