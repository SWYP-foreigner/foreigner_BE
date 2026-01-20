package core.domain.aiuser.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiThinkingStateManager {

    // Key: RoomId, Value: Set of AI User IDs (생성 중인 AI들)
    private final ConcurrentHashMap<Long, Set<Long>> thinkingRegistry = new ConcurrentHashMap<>();

    // 답변 생성 시작 (마킹)
    public void markAsThinking(Long roomId, Long aiUserId) {
        thinkingRegistry.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                .add(aiUserId);
    }

    // 답변 생성 종료 (해제)
    public void finishThinking(Long roomId, Long aiUserId) {
        Set<Long> thinkingAis = thinkingRegistry.get(roomId);
        if (thinkingAis != null) {
            thinkingAis.remove(aiUserId);
            // 메모리 누수 방지: 방에 아무도 생각 중이지 않으면 키 삭제
            if (thinkingAis.isEmpty()) {
                thinkingRegistry.remove(roomId);
            }
        }
    }

    // 현재 생각 중인가?
    public boolean isThinking(Long roomId, Long aiUserId) {
        Set<Long> thinkingAis = thinkingRegistry.get(roomId);
        return thinkingAis != null && thinkingAis.contains(aiUserId);
    }
}
