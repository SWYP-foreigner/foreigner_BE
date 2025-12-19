package core.global.ai.mapper;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PromptMapper {

    // OpenAI 표준 포맷: { "role": "system", "content": "..." }
    public static Map<String, Object> systemMessage(String content) {
        return Map.of("role", "system", "content", content);
    }

    public static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    public static Map<String, Object> assistantMessage(String content) {
        return Map.of("role", "assistant", "content", content);
    }
    public static String buildRosterString(ChatRoom chatRoom) {
        return chatRoom.getParticipants().stream()
                .map(p -> {
                    String role = p.getUser().getUserRole().name(); // AI인지 USER인지 구분
                    String name = p.getUser().getFirstName();
                    String info = p.getUser().getIntroduction();
                    String hobby = p.getUser().getHobby();
                    return String.format("- %s (%s): %s / 취미: %s", name, role, info, hobby);
                })
                .collect(Collectors.joining("\n"));
    }
    /**
     * @param aiUserId : 히스토리에서 누가 AI(assistant)인지 구분하기 위해 필요함
     */
    public static List<Map<String, Object>> buildInput(
            String systemText,
            List<ChatMessage> historyAsc, // 과거 -> 최신 순
            String userInput,
            Long aiUserId
    ) {
        List<Map<String, Object>> input = new ArrayList<>();

        // 1. 시스템 프롬프트
        input.add(systemMessage(systemText));

        // 2. 대화 내역 (Entity 필드명 content, sender 사용)
        for (ChatMessage m : historyAsc) {
            String text = m.getContent();
            if (m.getSender().getId().equals(aiUserId)) {
                input.add(assistantMessage(text)); // AI가 보낸 말
            } else {
                input.add(userMessage(text));      // 사람이 보낸 말
            }
        }

        // 3. 현재 사용자 입력
        input.add(userMessage(userInput));
        return input;
    }
}