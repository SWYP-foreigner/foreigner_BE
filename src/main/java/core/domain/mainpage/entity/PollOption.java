package core.domain.mainpage.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "poll_id")
    private Poll poll;

    private String content; // 선택지 텍스트: "BTS", "Blackpink"

    private boolean isCorrect; // 퀴즈 정답 여부 (투표일 경우 무시)

    // 이 선택지에 투표한 수 (선택사항: 조회 성능 최적화용)
    private long voteCount = 0;
}