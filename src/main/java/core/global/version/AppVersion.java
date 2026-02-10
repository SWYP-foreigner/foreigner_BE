package core.global.version;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "app_version", indexes = {
        @Index(name = "idx_app_version_platform", columnList = "platform", unique = true)
})
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform platform;

    @Column(name = "minimum_version", nullable = false, length = 20)
    private String minimumVersion;

    @Column(name = "latest_version", nullable = false, length = 20)
    private String latestVersion;
    @Column(name = "store_url", nullable = false, length = 500)
    private String storeUrl;

    @Column(columnDefinition = "TEXT")
    private String message;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public AppVersion(Platform platform, String minimumVersion, String latestVersion, String storeUrl, String message) {
        this.platform = platform;
        this.minimumVersion = minimumVersion;
        this.latestVersion = latestVersion;
        this.storeUrl = storeUrl;
        this.message = message;
    }

    public void update(String minimumVersion, String latestVersion, String storeUrl, String message) {
        this.minimumVersion = minimumVersion;
        this.latestVersion = latestVersion;
        this.storeUrl = storeUrl;
        this.message = message;
    }
}

enum Platform {
    ANDROID, IOS
}