package core.domain.bookmark.service.impl;

import core.domain.bookmark.dto.BookmarkCursorPageResponse;
import core.domain.bookmark.dto.BookmarkListResponse;
import core.domain.bookmark.entity.Bookmark;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.bookmark.service.BookmarkService;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.LikeType;
import core.global.exception.BusinessException;
import core.global.enums.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.repository.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookmarkServiceImpl implements BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final LikeRepository likeRepository;
    private final CommentRepository commentRepository;
    private final ImageRepository imageRepository;

    private static final ImageType IMAGE_TYPE_USER = ImageType.USER;
    private static final ImageType IMAGE_TYPE_POST = ImageType.POST;
    private static final LikeType LIKE_TYPE_POST   = LikeType.POST;

    @Transactional(readOnly = true)
    @Override
    public BookmarkCursorPageResponse<BookmarkListResponse> getMyBookmarks(String username, int size, Long cursorId) {
        Pageable pageable = PageRequest.of(0, size + 1);
        Slice<Bookmark> slice = (cursorId == null)
                ? bookmarkRepository.findByUserNameOrderByIdDesc(username, pageable)
                : bookmarkRepository.findByUserNameAndIdLessThanOrderByIdDesc(username, cursorId, pageable);

        List<Bookmark> content = slice.getContent();
        boolean hasNext = content.size() > size;
        if (hasNext) {
            content = content.subList(0, size);
        }

        if (content.isEmpty()) {
            return new BookmarkCursorPageResponse<>(List.of(), null, false);
        }


        List<Post> posts = content.stream().map(Bookmark::getPost).toList();
        List<Long> postIds = posts.stream().map(Post::getId).toList();

        List<Long> authorIds = posts.stream()
                .map(p -> p.getAuthor() != null ? p.getAuthor().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Long> likeMap = likeRepository.countByRelatedIds(LIKE_TYPE_POST, postIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        Map<Long, Long> commentMap = commentRepository.countByPostIds(postIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        Map<Long, String> userImageMap = authorIds.isEmpty() ? Map.of()
                : imageRepository.findFirstUrlByRelatedIds(IMAGE_TYPE_USER, authorIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));

        Map<Long, List<String>> postImagesMap = imageRepository.findAllUrlsByRelatedIds(IMAGE_TYPE_POST, postIds).stream()
                .collect(Collectors.groupingBy(
                        row -> (Long) row[0],
                        Collectors.mapping(row -> (String) row[1], Collectors.toList())
                ));

        List<BookmarkListResponse> items = new ArrayList<>(content.size());
        for (Bookmark b : content) {
            items.add(toResponse(b, likeMap, commentMap, userImageMap, postImagesMap));
        }

        Long nextCursor = hasNext ? content.getLast().getId() : null;

        return new BookmarkCursorPageResponse<>(items, nextCursor, hasNext);
    }

    private BookmarkListResponse toResponse(
            Bookmark b,
            Map<Long, Long> likeMap,
            Map<Long, Long> commentMap,
            Map<Long, String> userImageMap,
            Map<Long, List<String>> postImagesMap
    ) {
        Post p = b.getPost();

        String authorName = Boolean.TRUE.equals(p.getAnonymous())
                ? "익명"
                : (p.getAuthor() != null ? p.getAuthor().getName() : null);

        Long postId = p.getId();
        Long likeCount    = likeMap.getOrDefault(postId, 0L);
        Long commentCount = commentMap.getOrDefault(postId, 0L);
        Long checkCount   = p.getCheckCount();

        String userImage = (p.getAuthor() == null) ? null
                : userImageMap.get(p.getAuthor().getId());

        List<String> postImages = postImagesMap.getOrDefault(postId, List.of());

        return new BookmarkListResponse(
                b.getId(),
                authorName,
                safeTrim(p.getContent()),
                likeCount,
                commentCount,
                checkCount,
                true,
                userImage,
                postImages
        );
    }

    private static String safeTrim(String s) {
        if (s == null) return null;
        int len = s.codePointCount(0, s.length());
        if (len <= 200) return s;
        int endIndex = s.offsetByCodePoints(0, 200);
        return s.substring(0, endIndex);
    }


    @Override
    @Transactional
    public void addBookmark(String username, Long postId) {
        Optional<Bookmark> bookmark = bookmarkRepository.findByUserNameAndPostId(username, postId);
        if (bookmark.isPresent()) {
            throw new BusinessException(ErrorCode.BOOKMARK_ALREADY_EXIST);
        }

        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Post post = postRepository.findById(postId).
                orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        bookmarkRepository.save(Bookmark.createBookmark(user, post));
    }

    @Override
    @Transactional
    public void removeBookmark(String username, Long postId) {
        bookmarkRepository.deleteByUserNameAndPostId(username, postId);
    }


}
