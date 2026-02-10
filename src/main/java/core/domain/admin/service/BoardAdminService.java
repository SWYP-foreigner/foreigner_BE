package core.domain.admin.service;

import core.domain.board.dto.BoardCreateRequest;
import core.domain.board.dto.BoardDto;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.community.BoardCategory;
import core.global.enums.common.ImageType;
import core.global.enums.common.LikeType;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BoardAdminService {

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final LikeRepository likeRepository;
    private final BlockPostRepository blockPostRepository;
    private final ImageRepository imageRepository;

    @Transactional(readOnly = true)
    public List<BoardDto> getAllBoards() {
        return boardRepository.findAll().stream()
                .map(BoardDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void createBoard(BoardCreateRequest request) {
        BoardCategory category;
        try {
            category = BoardCategory.valueOf(request.categoryName().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommunityErrorCode.INVALID_BOARD_CATEGORY);
        }

        if (boardRepository.existsByCategory(category)) {
            throw new BusinessException(CommunityErrorCode.DUPLICATE_CATEGORY);
        }

        Board newBoard = new Board(category);
        boardRepository.save(newBoard);
    }

    @Transactional
    public void deleteBoardAndAssociatedPosts(Long boardId) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        List<Post> postsToDelete = postRepository.findByBoard(board);

        if (!postsToDelete.isEmpty()) {
            List<Long> postIdsToDelete = postsToDelete.stream().map(Post::getId).collect(Collectors.toList());

            commentRepository.deleteAllByPostIn(postsToDelete);
            bookmarkRepository.deleteAllByPostIn(postsToDelete);
            likeRepository.deleteAllByTypeAndRelatedIdIn(LikeType.POST, postIdsToDelete);
            blockPostRepository.deleteAllByPostIn(postsToDelete);
            imageRepository.deleteAllByImageTypeAndRelatedIdIn(ImageType.POST, postIdsToDelete);
            postRepository.deleteAll(postsToDelete);
        }

        boardRepository.delete(board);
    }
}
