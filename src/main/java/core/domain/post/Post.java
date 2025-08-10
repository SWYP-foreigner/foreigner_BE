package core.domain.post;

import com.foreigner.core.domain.board.Board;
import com.foreigner.core.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "post")
@Getter
@NoArgsConstructor
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(name = "post_title", length = 100, nullable = false)
    private String title;

    @Column(name = "post_content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "is_anonymous", nullable = false)
    private Boolean anonymous;

    @Column(name = "check_count", nullable = false)
    private Long checkCount;
}
