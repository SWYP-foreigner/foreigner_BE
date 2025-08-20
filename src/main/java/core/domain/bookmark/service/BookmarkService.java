package core.domain.bookmark.service;

public interface BookmarkService {
    void addBookmark(String name, Long postId);

    void removeBookmark(String name, Long postId);

    BookmarkCursorPageResponse<BookmarkListResponse> getMyBookmarks(String name, int size, Long cursorId);

}
