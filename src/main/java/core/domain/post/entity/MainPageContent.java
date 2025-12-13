package core.domain.post.entity;

import core.domain.comment.entity.Comment;
import core.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "main_page_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MainPageContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "content_id")
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "html_content", nullable = false)
    private String htmlContent;

    @Column(name = "original_url")
    private String originalUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publisher_id")
    private User publisher;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

//    @OneToMany(mappedBy = "mainPageContent", orphanRemoval = true, cascade = CascadeType.ALL)
//    private List<Comment> comments = new ArrayList<>();

    @Builder
    public MainPageContent(String title, String htmlContent, String originalUrl, User publisher) {
        this.title = title;
        this.htmlContent = htmlContent;
        this.originalUrl = originalUrl;
        this.publisher = publisher;
    }

    public void changeHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }
}
