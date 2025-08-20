package core.domain.bookmark.service.impl;

import core.domain.bookmark.service.BookmarkService;
import org.springframework.stereotype.Service;

@Service
public class BookmarkServiceImpl implements BookmarkService {
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
}
