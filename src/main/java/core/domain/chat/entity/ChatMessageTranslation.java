package core.domain.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "chat_message_translation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_message_lang",
                        columnNames = {"message_id", "language_code"}
                )
        },
        indexes = {
                @Index(name = "idx_message_id", columnList = "message_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatMessageTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "translation_id")
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode; // e.g., "en", "vi", "ko"

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;


    public ChatMessageTranslation(Long messageId, String languageCode, String content) {
        this.messageId = messageId;
        this.languageCode = languageCode;
        this.content = content;
    }
}