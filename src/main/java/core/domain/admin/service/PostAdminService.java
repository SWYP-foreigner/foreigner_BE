package core.domain.admin.service;

import core.domain.post.dto.admin.PostListForAdminResponse;
import core.domain.post.dto.admin.PostReportDto;
import core.domain.post.dto.admin.PostSearchForAdminRequest;
import core.domain.post.entity.Post;
import core.domain.post.entity.PostReport;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.service.UserAdminService;
import core.global.enums.PostReportStatus;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostAdminService {

    private final PostRepository postRepository;
    private final UserAdminService userAdminService;
    private final PostReportRepository postReportRepository;

    @Transactional(readOnly = true)
    public Page<PostListForAdminResponse> searchPosts(PostSearchForAdminRequest request, Pageable pageable) {
        return postRepository.searchPostsByAdmin(request, pageable);
    }

    @Transactional
    public void deletePost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
        postRepository.delete(post);
    }

    @Transactional
    public void deletePostAndBanUser(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        Long authorId = post.getAuthor().getId();

        postRepository.delete(post);
        userAdminService.hardDeleteUser(authorId);
    }

    @Transactional(readOnly = true)
    public Page<PostReportDto> getPendingPostReports(Pageable pageable) {
        return postReportRepository.findByStatus(PostReportStatus.PENDING, pageable)
                .map(PostReportDto::from);
    }

    @Transactional
    public void processPostReport(Long reportId) {
        PostReport report = postReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.REPORT_NOT_FOUND));

        report.processReport();
    }
}
