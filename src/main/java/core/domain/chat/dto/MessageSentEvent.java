package core.domain.chat.dto;

import java.util.List;

/**
 * 메시지가 전송되었을 때 발생하는 이벤트
 * (서비스 -> 리스너로 데이터 전달)
 */
public record MessageSentEvent(
        ChatMessageResponse messageResponse,
        List<Long> recipientIds,
        ChatRoomSummaryResponse roomSummary
) {}