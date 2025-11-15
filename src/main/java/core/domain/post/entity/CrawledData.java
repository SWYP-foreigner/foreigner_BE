package core.domain.post.entity;

import core.global.enums.CrawledDataStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "crawled_data",
        indexes = { @Index(name = "idx_crawled_data_status", columnList = "status")},
        uniqueConstraints = { @UniqueConstraint(name = "uk_crawled_data_original_url", columnNames = {"originalUrl"}) }
)
@Getter
@NoArgsConstructor
public class CrawledData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String contentSnippet;

    @Column(nullable = false, length = 1024)
    private String originalUrl;

    @Column(length = 100)
    private String sourceSite;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "crawled_image_urls", joinColumns = @JoinColumn(name = "crawled_data_id"))
    @Column(name = "image_url", length = 1024)
    @OrderColumn(name = "image_order")
    private List<String> imageUrls = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant crawledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrawledDataStatus status = CrawledDataStatus.PENDING;

    private Long publishedPostId;

    public CrawledData(String title, String contentSnippet, String originalUrl, String sourceSite, List<String> imageUrls) {
        this.title = title;
        this.contentSnippet = contentSnippet;
        this.originalUrl = originalUrl;
        this.sourceSite = sourceSite;
        this.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>(); // Null 방지
        this.crawledAt = Instant.now();
        this.status = CrawledDataStatus.PENDING;
    }

    public void updateStatus(CrawledDataStatus newStatus, Long postId) {
        this.status = newStatus;
        if (newStatus == CrawledDataStatus.APPROVED) {
            this.publishedPostId = postId;
        }
    }
}
