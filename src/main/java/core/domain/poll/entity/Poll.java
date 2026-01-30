package core.domain.poll.entity;

import core.domain.poll.dto.VoteWriteRequest;
import core.domain.poll.dto.QuizWriteRequest;
import core.domain.post.entity.Post;
import core.domain.user.entity.User;
import core.global.enums.PollType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Poll {

    @Id
    private Long id;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private PollType type; // VOTE 또는 QUIZ

    private Instant closeAt; // 마감 시간: 2025.12.25

    @OneToOne
    @MapsId
    @JoinColumn(name = "post_id")
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author; // 작성자

    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PollOption> options = new ArrayList<>();

    private Long totalVoteCount = 0L; // 통계용 (Denormalization)

    @CreationTimestamp
    private Instant createdAt;

    public Poll(User user, Post post, VoteWriteRequest request) {
        this.post = post;
        this.title = request.title();
        this.description = request.description();
        this.type = PollType.VOTE;
        this.closeAt = Instant.now().plus(3, ChronoUnit.DAYS); // 기본 3일 뒤 마감
        this.author = user;

        post.initPoll(this);

        if (request.options() != null) {
            for (String optionContent : request.options()) {
                this.addOption(optionContent, false);
            }
        }
    }

    public Poll(User user, Post post, QuizWriteRequest request) {
        this.post = post;
        this.title = request.title();
        this.description = request.description();
        this.type = PollType.QUIZ;
        this.closeAt = null;
        this.author = user;

        post.initPoll(this);

        if (request.options() != null) {
            for (int i = 0; i < request.options().size(); i++) {
                boolean isCorrect = (i == request.correctOptionIndex());
                this.addOption(request.options().get(i), isCorrect);
            }
        }
    }

    public boolean isClosed() {
        return this.closeAt != null && this.closeAt.isBefore(Instant.now());
    }

    public void incrementTotalCount() {
        this.totalVoteCount++;
    }

    public double calculatePercentage(long optionVoteCount) {
        if (this.totalVoteCount == 0) return 0.0;
        return Math.round((double) optionVoteCount / this.totalVoteCount * 100);
    }

    public void addOption(String content, boolean isCorrect) {
        PollOption option = new PollOption(this, content, isCorrect);
        this.options.add(option);
    }
}