package core.domain.chat.dto;


import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;

/**
 * DB 트랜잭션 영역(ChatDbService)에서 처리 완료된
 * 핵심 엔티티만 묶어서 메인 서비스로 전달하기 위한 전용 Record
 */
public record ChatTransactionResult(
        ChatMessage savedMessage,
        ChatRoom chatRoom
) {
}