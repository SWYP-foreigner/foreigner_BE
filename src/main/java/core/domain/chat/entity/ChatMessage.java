package core.domain.chat.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "chat_messages")
@Getter
@NoArgsConstructor
public class ChatMessage {

    @Id
    private String id;

    @Field("room_id")
    private Long chatRoomId;

    @Field("sender_id")
    private Long senderId;

    @Field("content")
    private String content;

    @Field("sent_at")
    private Instant sentAt;

    @Field("read_count")
    private int readCount;

    public ChatMessage(Long chatRoomId, Long senderId, String content, int readCount) {
        this.chatRoomId = chatRoomId;
        this.senderId = senderId;
        this.content = content;
        this.sentAt = Instant.now();
        this.readCount = readCount;
    }
    public ChatMessage(Long chatRoomId, Long senderId, String content) {
        this.chatRoomId = chatRoomId;
        this.senderId = senderId;
        this.content = content;
        this.sentAt = Instant.now();
    }
}