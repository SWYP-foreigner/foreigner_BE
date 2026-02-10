package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;
import core.domain.user.entity.User;
import core.global.enums.chat.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageDetailDto {

    private Long id;
    private Long senderId;
    private String senderName;
    private String content;
    private MessageType messageType;
    private LocalDateTime sentAt;

    public static ChatMessageDetailDto from(ChatMessage message) {
        return ChatMessageDetailDto.builder()
                .id(message.getId())
                .senderId(message.getSender() != null ? message.getSender().getId() : null)
                .senderName(resolveSenderName(message))
                .content(message.getContent())
                .messageType(message.getMessageType())
                .sentAt(LocalDateTime.ofInstant(message.getSentAt(), ZoneId.of("Asia/Seoul")))
                .build();
    }

    private static String resolveSenderName(ChatMessage message) {
        User sender = message.getSender();

        if (sender == null) {
            return "(알 수 없음)";
        }

        String firstName = sender.getFirstName() != null ? sender.getFirstName() : "";
        String lastName = sender.getLastName() != null ? sender.getLastName() : "";

        String fullName = (firstName + " " + lastName).trim();

        return fullName.isEmpty() ? "(이름 없음)" : fullName;
    }
}
