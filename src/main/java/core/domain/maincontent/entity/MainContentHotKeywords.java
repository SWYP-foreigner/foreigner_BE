package core.domain.maincontent.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "main_content_hot_keywords")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MainContentHotKeywords {
    @Id
    private String keyword;
    private Integer frequency;
    private Instant updatedAt;
}