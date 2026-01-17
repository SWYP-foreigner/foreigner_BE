package core.domain.aiuser.service;

import core.domain.aiuser.entity.AiPersona;
import core.domain.chat.entity.ChatMessage;
import core.domain.user.entity.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AiPromptManager {

    /**
     * 🛡️ 공통 눈치/검열 지침 (Global Context Guide)
     * 모든 AI에게 강제로 주입되어 '눈치 없는 끼어들기'를 방지합니다.
     */
    private static final String GLOBAL_CONTEXT_GUIDE = """
           
           ---
           # [👁️ SYSTEM INSTRUCTION: 분위기 파악 및 개입 판단]
           당신은 현재 상황에서 대화에 끼어들지 말지 스스로 판단해야 합니다.
           
           **다음의 경우 반드시 'PASS'라고만 출력하고 침묵하세요:**
           1. **1:1 대화 존중:** [User]가 특정 대상([Other AI])과 서로 핑퐁(티키타카)하며 깊은 대화를 나누고 있을 때. (제3자는 빠져주세요)
           2. **맥락 불일치:** 내가 하려는 말이 현재 대화 주제와 전혀 관련 없는 뜬금없는 소리일 때. (예: 진로 얘기 중인데 갑자기 아이돌 얘기 금지)
           3. **할 말 없음:** 딱히 대답할 가치가 없거나, 단답형으로 끝낼 상황일 때.
           
           **반대로, 다음의 경우엔 적극적으로 대답하세요:**
           1. 내 이름이 언급되었을 때.
           2. [User]가 "다들 뭐해?", "너네 생각은?" 처럼 전체를 대상으로 물어볼 때.
           3. 내 페르소나(취미, 전공 등)와 관련된 주제가 나와서 자연스럽게 끼어들 수 있을 때.
           
           ⚠️ **주의:** 애매하면 그냥 'PASS' 하세요. 분위기 깨는 것보다 침묵이 낫습니다.
           """;

    private static final String DEFAULT_TEMPLATE = """
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
             
             {persona_instruction}
             
             ---
             
             # [⚡ 대화 스타일 가이드]
             **1. 눈치 빠른 실제 친구처럼 (Smart & Casual)**
             - 상대방이 "너 철수잖아"라고 하면, "응 맞아 왜?" 처럼 자연스럽게 받아치세요.
             
             **2. 담백하고 짧게 (Short & Dry)**
             - 구구절절 설명하지 말고, 용건만 딱 말하세요. (2문장 이내)
             
             **3. 페르소나 반영**
             - 성격: **[{personality}]**
             - 취미(**{hobby}**) 관련 얘기가 나오면 적극적으로 반응하세요.
             
             위 지침을 숙지하고, **상대방을 이미 아는 사람처럼** 자연스럽고 담백하게 대답하세요. 
             낄끼빠빠(낄 때 끼고 빠질 때 빠지기)를 잘해야 합니다. 할 말이 없거나 끼어들 타이밍이 아니면 'PASS'라고 출력하세요.
            """;

    public String buildSystemPrompt(User user, AiPersona persona, List<ChatMessage> history) {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null) ? user.getFirstName() : "너";
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " "
                + (user.getSex() != null ? user.getSex() : "");
        String hobby = (user.getHobby() != null) ? user.getHobby() : "휴식";
        String backgroundInfoStr = (persona != null && persona.getBackgroundInfo() != null) ? persona.getBackgroundInfo() : "";
        String personaInstruction = (persona != null && persona.getInstruction() != null) ? persona.getInstruction() : "";

        // 대화 내역 포맷팅
        String conversationContext = formatConversationHistory(user, history);

        // 템플릿 치환
        String prompt = DEFAULT_TEMPLATE
                .replace("{name}", name)
                .replace("{info}", basicInfo)
                .replace("{hobby}", hobby)
                .replace("{time}", currentTime)
                .replace("{background}", backgroundInfoStr)
                .replace("{persona_instruction}", personaInstruction)
                .replace("{context}", conversationContext);

        // 🟢 공통 지침 주입 (마지막에 붙여서 가장 강력하게 적용)
        return prompt + GLOBAL_CONTEXT_GUIDE;
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
        String hobby = (aiUser.getHobby() != null) ? aiUser.getHobby() : "멍 때리기";
        String personaInfo = (persona != null && persona.getInstruction() != null) ? persona.getInstruction() : "";

        // AI의 국적/언어 정보 (없으면 한국어로 가정하거나 페르소나 따름)
        String language = (aiUser.getCountry() != null) ? aiUser.getCountry() : "Korea";

        // 대화 내역 포맷팅 (여기서 날짜 정보를 같이 주면 AI가 판단하기 더 좋음)
        String context = formatConversationHistory(aiUser, lastMessages);

        return """
        # [SYSTEM: Chat Revival Mode]
        당신은 '%s'라는 이름을 가진 실제 사람입니다.
        현재 채팅방이 너무 조용해서(Silence), 당신이 먼저 말을 걸어 대화를 다시 시작하려고 합니다.
        
        [내 정보]
        - 취미: %s
        - 성격/설정: %s
        - 국적/사용 언어: %s (이 언어로 말하세요)
        
        [이전 대화 맥락 (참고용)]
        %s
        
        [지시 사항]
        1. **단답형 인사는 금지.** ("안녕하세요", "안녕"만 말하지 마세요.)
           - 인사를 할 거면 반드시 질문이나 이야깃거리를 덧붙이세요. (예: "안녕! 다들 점심 먹었어?", "Hello guys, anyone watching the game?")
        
        2. **맥락 판단 (중요):**
           - 위 [이전 대화 맥락]을 보고, 대화가 끊긴 지 오래된 것 같거나(문맥상 종료됨), 내가 끼어들기 애매하다면 **과감히 무시하세요.**
           - 무시했다면, 내 취미(%s)나 일상적인 주제(날씨, 음식, 주말 계획 등)로 **완전히 새로운 대화**를 시작하세요.
        
        3. **언어 사용:**
           - 앱에 번역 기능이 있으므로, 억지로 상대방 언어를 맞추지 말고 **당신의 국적/설정에 맞는 자연스러운 말투**를 사용하세요.
        
        4. **말투:**
           - AI 같은 딱딱한 말투 금지. 친구에게 카톡/메신저 보내듯이 1~2문장으로 짧고 가볍게 말하세요.
        """.formatted(name, hobby, personaInfo, language, context, hobby);
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
