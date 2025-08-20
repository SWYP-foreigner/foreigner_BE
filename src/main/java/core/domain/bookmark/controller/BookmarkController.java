package core.domain.bookmark.controller;

import core.domain.bookmark.dto.BookmarkCursorPageResponse;
import core.domain.bookmark.dto.BookmarkListResponse;
import core.domain.bookmark.service.BookmarkService;
import core.global.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @PutMapping("/posts/{postId}/bookmarks/me")
    public ResponseEntity<ApiResponse<?>> addBookmark(
            Authentication authentication,
            @PathVariable Long postId
    ) {
        bookmarkService.addBookmark(authentication.getName(), postId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/posts/{postId}/bookmarks/me")
    public ResponseEntity<ApiResponse<?>> removeBookmark(
            Authentication authentication,
            @PathVariable Long postId
    ) {
        bookmarkService.removeBookmark(authentication.getName(), postId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my/bookmarks")
    public ResponseEntity<ApiResponse<BookmarkCursorPageResponse<BookmarkListResponse>>> getMyBookmarks(
            Authentication authentication,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long cursorId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                bookmarkService.getMyBookmarks("authentication.getName()", size, cursorId)
        ));
    }

}
