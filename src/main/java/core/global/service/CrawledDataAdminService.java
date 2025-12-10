package core.global.service;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.crawling.CrawledDataDto;
import core.domain.post.entity.CrawledData;
import core.domain.post.entity.Post;
import core.domain.post.repository.CrawledDataRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.config.CustomUserDetails;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.PostImageService;
import core.global.enums.CrawledDataStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class CrawledDataAdminService {

    private final CrawledDataRepository crawledDataRepository;
    private final PostRepository postRepository;
    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final PostImageService postImageService;

    @Transactional(readOnly = true)
    public Page<CrawledDataDto> getPendingCrawledData(Pageable pageable) {
        return crawledDataRepository.findByStatus(CrawledDataStatus.PENDING, pageable)
                .map(CrawledDataDto::from);
    }

    @Transactional
    public void approveAndPost(Long crawledDataId, Long boardId, String content, List<String> selectedImageUrls) {
        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));

        Board targetBoard = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long adminUserId = principal.getUserId();

        User adminUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String mergedContent = "# " + crawledData.getTitle() + "\n\n" + content;

        Post newPost = new Post(
                mergedContent,
                adminUser,
                targetBoard
        );
        Post savedPost = postRepository.save(newPost);

        if (selectedImageUrls != null && !selectedImageUrls.isEmpty()) {
            List<String> finalImages = selectedImageUrls.size() > 5
                    ? selectedImageUrls.subList(0, 5)
                    : selectedImageUrls;

            postImageService.uploadAndSavePostImagesFromUrls(savedPost, finalImages);
        }

        crawledData.updateStatus(CrawledDataStatus.APPROVED, savedPost.getId());
    }

    @Transactional
    public void rejectCrawledData(Long crawledDataId) {
        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));

        crawledData.updateStatus(CrawledDataStatus.REJECTED, null);
    }

    @Transactional(readOnly = true)
    public CrawledData getCrawledDataById(Long id) {
        return crawledDataRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));
    }
}
