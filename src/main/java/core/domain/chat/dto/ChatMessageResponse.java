package core.domain.chat.dto;

import core.global.enums.chat.MessageType;

import java.time.Instant;
import java.time.LocalDateTime;
public record ChatMessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String originContent,
        String targetContent,
        Instant sentAt,
        String senderFirstName,
        String senderLastName,
        String senderImageUrl,
        MessageType messageType,
        String mediaUrl,
        String thumbnailUrl
) {
    /**
     * 기존 메시지 정보를 유지하면서 번역된 내용(targetContent)만 교체한 새 객체를 반환합니다.
     */
    public ChatMessageResponse copyWithContent(String newTargetContent) {
        return new ChatMessageResponse(
                this.id,
                this.roomId,
                this.senderId,
                this.originContent,
                newTargetContent, // 이 필드만 변경
                this.sentAt,
                this.senderFirstName,
                this.senderLastName,
                this.senderImageUrl,
                this.messageType,
                this.mediaUrl,
                this.thumbnailUrl
        );
    }
}