package core.domain.maincontent.entity;

import core.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title; // 질문: "Who do you think is the best..."

    @Enumerated(EnumType.STRING)
    private PollType type; // VOTE 또는 QUIZ

    private Instant closeAt; // 마감 시간: 2025.12.25

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author; // 작성자

    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PollOption> options = new ArrayList<>();

    private long totalVoteCount = 0; // 통계용 (Denormalization)

    @CreationTimestamp
    private Instant createdAt;

    public void incrementTotalCount() {
        this.totalVoteCount++;
    }

    public double calculatePercentage(long optionVoteCount) {
        if (this.totalVoteCount == 0) return 0.0;
        return Math.round((double) optionVoteCount / this.totalVoteCount * 100);
    }
}