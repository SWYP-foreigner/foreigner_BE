package core.domain.like;

import com.foreigner.core.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "like") // 예약어 → 백틱 필요할 수 있음
@Getter
@NoArgsConstructor
public class Like {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "like_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private LikeType type;

    // ※ 원문에 어떤 대상(post/comment 등)을 가리키는 FK(예: target_id)가 없음 → 실사용 불가. 스키마 보완 필요.
}
