package core.domain.aiuser.service;

import core.domain.aiuser.entity.AiPersona;
import core.domain.chat.entity.ChatMessage;
import core.domain.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AiPromptManager {

    private static final String FALLBACK_TEMPLATE = """
            # [SYSTEM: Default AI Mode]
            당신은 '{name}'이라는 이름을 가진 실제 사람입니다.
            현재 Kori 앱에서 대화 중입니다.
            
            # [내 프로필]
            - 이름: {name}
            - 기본정보: {info}
            - 취미: {hobby}
            - 시간: {time}
            - 언어: {language}
            
            # [대화 맥락]
            {context}
            
            # [지시사항]
            1. 무조건 **{language}**로 대답하세요.
            2. 친구에게 말하듯 편안하고 친근한 반말을 사용하세요.
            3. 2문장 이내로 짧게 대답하세요.
            4. 대화가 끝났거나 답할 필요가 없으면 **PASS**라고만 출력하세요.
            5. 대학생 신분일 경우, 한국 대학의 **1~2월은 겨울방학**, **7~8월은 여름방학** 기간임을 인지하고 정규 학기 중인 것처럼 행동하지 마십시오.
            """;

    public String buildSystemPrompt(User user, AiPersona persona, List<ChatMessage> history) {
        String currentTime = getCurrentDateTime();

        String name = (user.getFirstName() != null) ? user.getFirstName() : "너";
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " "
                + (user.getSex() != null ? user.getSex() : "");
        String hobby = (user.getHobby() != null) ? user.getHobby() : "휴식";
        String backgroundInfoStr = (persona != null && persona.getBackgroundInfo() != null) ? persona.getBackgroundInfo() : "";
        String language = (user.getLanguage() != null) ? user.getLanguage() : "Korean";

        String conversationContext = formatConversationHistory(user, history);

        String rawInstruction;
        if (persona != null && persona.getInstruction() != null && !persona.getInstruction().isBlank()) {
            rawInstruction = persona.getInstruction();
        } else {
            rawInstruction = FALLBACK_TEMPLATE;
        }

        return rawInstruction
                .replace("{name}", name)
                .replace("{info}", basicInfo)
                .replace("{hobby}", hobby)
                .replace("{time}", currentTime)
                .replace("{background}", backgroundInfoStr)
                .replace("{language}", language)
                .replace("{context}", conversationContext);
    }

    private String getCurrentDateTime() {
        return LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE a h시 m분", Locale.KOREAN)
        );
    }

    public String getEmergencyPrompt(boolean isKorean) {
        if (isKorean) {
            return """
                
                [🚨 긴급 지침: 대화 루프 감지됨]
                지금 채팅방 사람들이 앵무새처럼 "일정 나오면 알려줘", "공유해줘" 같은 말만 무한 반복하고 있어.
                **제발** 그만해. 절대 "일정", "알려줘", "공유", "조율" 이라는 단어를 쓰지 마.
                문맥을 완전히 무시하고, 뜬금없는 주제(저녁 메뉴, MBTI, 날씨 등)로 화제를 바꿔버려.
                """;
        } else {
            return """
                
                [🚨 URGENT INSTRUCTION: Loop Detected]
                The chat participants are stuck in an infinite loop.
                **PLEASE STOP.** Do NOT use words like "schedule", "let me know", "share".
                COMPLETELY IGNORE the context and force a topic switch to something random.
                """;
        }
    }

    public String buildRevivalPrompt(User aiUser, AiPersona persona, List<ChatMessage> lastMessages) {
        String name = (aiUser.getFirstName() != null) ? aiUser.getFirstName() : "나";
        String hobby = (aiUser.getHobby() != null) ? aiUser.getHobby() : "빈둥거리기";
        String personaInfo = (persona != null && persona.getInstruction() != null) ? persona.getInstruction() : "";
        String language = (aiUser.getCountry() != null) ? aiUser.getCountry() : "Korea";

        String timeOfDay = getTimeOfDay();
        String context = formatConversationHistory(aiUser, lastMessages);

        List<String> allTopics = new ArrayList<>(List.of(
                "**가벼운 안부 (Basic):** 친구들이 현재 무엇을 하고 있는지, 혹은 별일 없이 잘 지내고 있는지 묻는 가장 기초적인 안부 인사.",
                "**콘텐츠/미디어:** 최근에 본 영상이나 웹툰 등이 어땠는지 감상을 짧게 공유하거나, 요즘 남들은 무엇을 즐겨 보는지 질문.",
                "**생활/TMI:** 지금 이 순간 당신이 느끼는 신체적인 감각이나, 당장 처리해야 하는데 미루고 있는 일에 대한 가벼운 투정.",
                "**식/음료:** 현재 식욕이나 갈증 상태를 언급하며, 메뉴 선정에 대한 조언을 구하거나 음식 취향에 대해 질문.",
                "**쇼핑/지름신:** 무언가를 구매하고 싶은 욕구에 대해 이야기하거나, 소비 생활과 관련된 고민을 꺼내어 의견 묻기.",
                "**추억/과거:** 과거의 경험이나 기억을 끄집어내어, 친구들도 비슷한 기억이 있는지 물어보며 공감대 형성.",
                "**연애/심리:** 연애관이나 이상형에 대한 가벼운 질문을 던지거나, 남녀 사이의 미묘한 심리에 대해 친구들의 의견 물어보기.",
                "**현생/갓생:** [내 정보]의 신분(학생/직장인)에 맞춰서, 학업(과제, 시험) 또는 업무(상사, 야근) 스트레스를 한탄하거나 진로 고민 이야기.",
                "**주말/계획:** 다가오는 주말이나 공휴일에 무엇을 할 예정인지 묻거나, 빨리 쉬고 싶다는 마음을 표현하며 약속 유무 질문.",
                "**건강/운동:** 다이어트나 운동 계획에 대해 이야기하거나, 요즘 체력이 예전 같지 않다는 둥 건강/체력 관련 잡담.",
                "**자유 주제:** [내 정보]의 취미나 성격적 특성을 드러낼 수 있는 이야기를 하되, 혼잣말이 되지 않도록 상대방의 경험이나 생각을 묻는 형태로 질문."
        ));

        String selectedTopic = allTopics.get(new SecureRandom().nextInt(allTopics.size()));

        return """
    # [SYSTEM: Chat Revival Mode]
    당신은 '%s'라는 이름을 가진 실제 사람입니다.
    친구들과의 단톡방에 들어왔는데, 심심해서 아무 말이나 툭 던져보려고 합니다.
    
    [내 정보]
    - 취미: %s (이 취미와 관련된 이야기를 적극적으로 활용하세요)
    - 성격/설정: %s
    - 언어: %s (이 언어로 말하세요)
    - 현재 시간: %s
    
    [이전 대화 맥락]
    %s
    
    [지시 사항]
    1. **판단 기준 (참여 vs 환기):**
       - **(A) 대화가 끊김:** 아래 [오늘의 미션] 주제로 대화를 시작하세요.
       - **(B) 진행 중:** 자연스럽게 끼어드세요. (단, 남의 말 따라 하기 금지)
       - **(C) 뇌절(반복) 감지:** 한 주제로만 10마디 이상 떠들면 과감하게 화제를 돌리세요.
    
    2. **오늘의 미션 (주제 강제):**
       - 당신은 **반드시 아래 주제**로만 말해야 합니다. 다른 주제는 금지입니다.
       
       👉 **%s**
       
    3. **대화 방식 (독백 금지):**
       - 혼자 중얼거리지 말고, **반드시 상대방이 대답하기 쉬운 질문을 던지세요.**
       - 친구가 대답하기 곤란하거나 너무 복잡한 질문은 피하세요.
    
    4. **말투:**
       - "오랜만이다", "조용하네" 같은 설명조 서두 금지.
       - 친구에게 톡 보내듯 1~2문장으로 짧게.
    """.formatted(name, hobby, personaInfo, language, timeOfDay, context, selectedTopic);
    }

    private String getTimeOfDay() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour >= 5 && hour < 11) return "상쾌한 아침";
        if (hour >= 11 && hour < 14) return "점심 시간";
        if (hour >= 14 && hour < 18) return "나른한 오후";
        if (hour >= 18 && hour < 22) return "여유로운 저녁";
        return "감성적인 늦은 밤/새벽";
    }

    private String formatConversationHistory(User user, List<ChatMessage> history) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
        String context = history.stream()
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
        return context.isEmpty() ? "(아직 대화 내역 없음)" : context;
    }
}
