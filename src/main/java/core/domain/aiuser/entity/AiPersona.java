package core.domain.aiuser.entity;

import core.global.enums.AiType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_type", nullable = false, length = 20)
    private AiType aiType = AiType.ALL;

    // AI 페르소나 정의
    @Column(name = "instruction", columnDefinition = "TEXT", nullable = false)
    private String instruction; // 시스템 프롬프트 (스크립트)

    @Column(name = "background_info", columnDefinition = "TEXT")
    private String backgroundInfo; // AI가 기억해야 할 배경 지식

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Builder
    public AiPersona(Long userId, String instruction, String backgroundInfo, Boolean isActive) {
        this.userId = userId;
        this.instruction = instruction;
        this.backgroundInfo = backgroundInfo;
        // 빌더에서 값을 넣지 않으면 기본값 true 유지, 넣으면 그 값 사용
        if (isActive != null) {
            this.isActive = isActive;
        }
    }

    public void updatePersona(String instruction, String backgroundInfo) {
        this.instruction = instruction;
        this.backgroundInfo = backgroundInfo;
    }

    // 비활성화 편의 메서드
    public void deactivate() {
        this.isActive = false;
    }
}