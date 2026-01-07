package core.domain.maincontent.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "main_content_recommendation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MainContentRecommendation {
    @Id
    private String keyword;    // K-food, News 등
    private Integer frequency;
    private Instant updatedAt;
}