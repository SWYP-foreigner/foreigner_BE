package core.domain.bookmark.service;

import core.domain.bookmark.dto.BookmarkItem;
import core.global.pagination.CursorPageResponse;

public interface BookmarkService {
    void addBookmark(String name, Long postId);

    void removeBookmark(String name, Long postId);

    CursorPageResponse<BookmarkItem> getMyBookmarks(String name, int size, String cursor);

}
