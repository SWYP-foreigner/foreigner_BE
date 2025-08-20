package core.domain.bookmark.service;

import core.domain.bookmark.dto.BookmarkCursorPageResponse;
import core.domain.bookmark.dto.BookmarkListResponse;

public interface BookmarkService {
    void addBookmark(String name, Long postId);

    void removeBookmark(String name, Long postId);

    BookmarkCursorPageResponse<BookmarkListResponse> getMyBookmarks(String name, int size, Long cursorId);

}
