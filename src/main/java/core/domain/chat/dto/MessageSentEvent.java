package core.domain.chat.dto;

import java.util.List;

/**
 * 메시지가 전송되었을 때 발생하는 이벤트
 * (서비스 -> 리스너로 데이터 전달)
 */
public record MessageSentEvent(
        ChatMessageResponse messageResponse,    // 전송된 메시지 내용
        List<Long> recipientIds,                // 수신자 ID 목록
        ChatRoomSummaryResponse roomSummary   ,  // 갱신될 채팅방 요약 정보
        long startTime //
) {}