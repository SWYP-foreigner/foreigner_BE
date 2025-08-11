package core.domain.chat.dto;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "chat_messages")
public class ChatMessageDoc {
    @Id
    private String id;
    private Long roomId;
    private Long senderId;
    private String senderName;
    private String content;
    private Instant createdAt;

    public ChatMessageDoc() {}
    public ChatMessageDoc(Long roomId, Long senderId, String senderName, String content) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.content = content;
        this.createdAt = Instant.now();
    }
}