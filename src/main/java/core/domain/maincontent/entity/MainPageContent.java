package core.domain.maincontent.entity;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private KNewsContentType type;

    @Column(name = "original_url")
    private String originalUrl;


    @Column(name = "view_count")
    private Long viewCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;


    @Builder
    public MainPageContent(String title, String htmlContent, String originalUrl) {
        this.title = title;
        this.htmlContent = htmlContent;
        this.originalUrl = originalUrl;
    }

    public void changeHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }
}
