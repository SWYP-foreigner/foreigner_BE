package core.domain.chat.entity;

import core.domain.user.entity.User;
import core.global.enums.ChatReportStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "chat_report",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reporter_message",
                        columnNames = {"reporter_user_id", "message_id"}
                )
        }
)
@Getter
@NoArgsConstructor
public class ChatReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_user_id", nullable = false)
    private User reporterUser; // 신고한 사람

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id", nullable = false)
    private User reportedUser; // 신고당한 사람

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id", nullable = false)
    private ChatRoom chatRoom; // 신고가 발생한 채팅방

    @Column(nullable = false)
    private Long messageId; // 원본 메시지 ID

    @Column(columnDefinition = "TEXT")
    private String messageContent; // 증거 보존용 텍스트

    @Column(nullable = false)
    private String reasonCategory; // 신고 사유 (선택형)

    @Column(columnDefinition = "TEXT")
    private String reasonDetail; // 신고 상세 사유 (서술형)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatReportStatus status; // 처리 상태

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public ChatReport(User reporterUser, User reportedUser, ChatRoom chatRoom, Long messageId, String messageContent, String reasonCategory, String reasonDetail) {
        this.reporterUser = reporterUser;
        this.reportedUser = reportedUser;
        this.chatRoom = chatRoom;
        this.messageId = messageId;
        this.messageContent = messageContent;
        this.reasonCategory = reasonCategory;
        this.reasonDetail = reasonDetail;
        this.status = ChatReportStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void processReport() {
        this.status = ChatReportStatus.PROCESSED;
    }
}
