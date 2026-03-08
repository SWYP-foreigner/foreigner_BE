package core.domain.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * 메시지가 전송되었을 때 발생하는 이벤트
 * (서비스 -> 리스너로 데이터 전달)
 */
public record MessageSentEvent(
        ChatMessageResponse baseResponse,
        Map<String, List<Long>> recipientsByLang, // 온라인 접속자 (언어별)
        List<Long> pushTargetIds,               // 푸시 대상 (나 제외 전원)
        Map<String, String> translations,        // 번역 결과
        ChatRoomSummaryResponse roomSummary
) {}