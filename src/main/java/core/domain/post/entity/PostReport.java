package core.domain.post.entity;

import core.domain.user.entity.User;
import core.global.enums.PostReportStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "post_report",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reporter_post",
                        columnNames = {"reporter_id", "post_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PostReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter; // 신고한 사람

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id", nullable = false)
    private User reportedUser; // 신고당한 사람 (글 작성자)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post; // 신고된 게시글

    @Column(name = "post_title_snapshot", nullable = false)
    private String postTitleSnapshot;

    @Column(name = "post_content_snapshot", columnDefinition = "TEXT", nullable = false)
    private String postContentSnapshot;

    @Column(name = "reason_category", nullable = false)
    private String reasonCategory;

    @Column(name = "reason_detail", columnDefinition = "TEXT")
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostReportStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PostReport(User reporter, User reportedUser, Post post, String reasonCategory, String reasonDetail) {
        this.reporter = reporter;
        this.reportedUser = reportedUser;
        this.post = post;
        this.reasonCategory = reasonCategory;
        this.reasonDetail = reasonDetail;
        this.status = PostReportStatus.PENDING;
        this.createdAt = Instant.now();

        extractSnapshotFromPost(post);
    }

    private void extractSnapshotFromPost(Post post) {
        String fullContent = post.getContent();
        this.postContentSnapshot = fullContent;

        if (fullContent != null && !fullContent.isEmpty()) {
            int firstLineIndex = fullContent.indexOf("\n");
            String extractedTitle;

            if (firstLineIndex > -1) {
                extractedTitle = fullContent.substring(0, firstLineIndex);
            } else {
                extractedTitle = fullContent;
            }

            this.postTitleSnapshot = extractedTitle.replaceAll("^#+\\s*", "").trim();

            if (this.postTitleSnapshot.length() > 200) {
                this.postTitleSnapshot = this.postTitleSnapshot.substring(0, 200);
            }
        } else {
            this.postTitleSnapshot = "(내용 없음)";
            this.postContentSnapshot = "";
        }
    }

    public void processReport() {
        this.status = PostReportStatus.PROCESSED;
    }
}
