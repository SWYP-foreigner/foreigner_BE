package core.global.ai.entity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "ai_personas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AiPersona {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_persona_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // AI 페르소나 정의
    @Column(name = "instruction", columnDefinition = "TEXT", nullable = false)
    private String instruction; // 시스템 프롬프트 (스크립트)

    @Column(name = "background_info", columnDefinition = "TEXT")
    private String backgroundInfo; // AI가 기억해야 할 배경 지식


    @Builder
    public AiPersona(Long userId, String instruction, String backgroundInfo) {
        this.userId = userId;
        this.instruction = instruction;
        this.backgroundInfo = backgroundInfo;
    }
}