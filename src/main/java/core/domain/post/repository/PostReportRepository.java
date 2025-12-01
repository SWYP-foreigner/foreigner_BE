package core.domain.post.repository;

import core.domain.post.entity.Post;
import core.domain.post.entity.PostReport;
import core.domain.user.entity.User;
import core.global.enums.PostReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostReportRepository extends JpaRepository<PostReport, Long> {

    boolean existsByReporterAndPost(User reporter, Post post);

    Page<PostReport> findByStatus(PostReportStatus status, Pageable pageable);
}
