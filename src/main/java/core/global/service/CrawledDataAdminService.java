package core.global.service;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.CrawledDataDto;
import core.domain.post.entity.CrawledData;
import core.domain.post.entity.Post;
import core.domain.post.repository.CrawledDataRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.CrawledDataStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Transactional(readOnly = true)
    public Page<CrawledDataDto> getPendingCrawledData(Pageable pageable) {
        return crawledDataRepository.findByStatus(CrawledDataStatus.PENDING, pageable)
                .map(CrawledDataDto::from);
    }

    @Transactional
    public void approveAndPost(Long crawledDataId, Long boardId) {
        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CRAWLED_DATA_NOT_FOUND));

        Board targetBoard = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

        // TODO: 게시글 작성자를 특정 관리자 계정으로 설정해야 합니다. 여기서는 ID 1번 유저를 관리자로 가정합니다.
        User adminUser = userRepository.findById(1L)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Post newPost = new Post(
                crawledData.getTitle() + "\n\n" + crawledData.getContentSnippet(),
                adminUser,
                targetBoard
        );
        Post savedPost = postRepository.save(newPost);

        List<String> imageUrls = crawledData.getImageUrls();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            IntStream.range(0, imageUrls.size())
                    .mapToObj(i -> Image.of(ImageType.POST, savedPost.getId(), imageUrls.get(i), i))
                    .forEach(imageRepository::save);
        }

        crawledData.updateStatus(CrawledDataStatus.APPROVED, savedPost.getId());
    }

    @Transactional
    public void rejectCrawledData(Long crawledDataId) {
        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CRAWLED_DATA_NOT_FOUND));

        crawledData.updateStatus(CrawledDataStatus.REJECTED, null);
    }
}
