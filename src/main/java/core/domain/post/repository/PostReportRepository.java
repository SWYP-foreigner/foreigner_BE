package core.domain.post.repository;

import core.domain.post.entity.Post;
import core.domain.post.entity.PostReport;
import core.domain.user.entity.User;
import core.global.enums.PostReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostReportRepository extends JpaRepository<PostReport, Long> {

    boolean existsByReporterAndPost(User reporter, Post post);

    Page<PostReport> findByStatus(PostReportStatus status, Pageable pageable);
    void deleteAllByReporterId(Long reporterId);      // 내가 신고한 것
    void deleteAllByReportedUserId(Long reportedUserId); // 내가 신고당한 것
    void deleteAllByPostIn(List<Post> posts);         // 내 글에 달린 신고들
}
