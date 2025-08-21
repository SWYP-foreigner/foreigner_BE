package core.domain.bookmark.controller;

import core.domain.bookmark.dto.BookmarkCursorPageResponse;
import core.domain.bookmark.dto.BookmarkListResponse;
import core.domain.bookmark.service.BookmarkService;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Bookmark", description = "북마크 API")
@RestController
@RequestMapping("/api/v1")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @Operation(
            summary = "게시글 북마크 추가",
            description = "현재 로그인한 사용자가 지정한 게시글을 북마크에 추가합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공 (내용 없음)")
    @PutMapping("/posts/{postId}/bookmarks/me")
    public ResponseEntity<Void> addBookmark(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "게시글 ID", required = true) @PathVariable Long postId
    ) {
        bookmarkService.addBookmark(authentication.getName(), postId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "게시글 북마크 제거",
            description = "현재 로그인한 사용자가 지정한 게시글을 북마크에서 제거합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공 (내용 없음)")
    @DeleteMapping("/posts/{postId}/bookmarks/me")
    public ResponseEntity<Void> removeBookmark(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "게시글 ID", required = true) @PathVariable Long postId
    ) {
        bookmarkService.removeBookmark(authentication.getName(), postId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "내 북마크 목록 조회(커서 페이지)",
            description = "로그인 사용자의 북마크 목록을 커서 기반 페이징으로 조회합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))
    )
    @GetMapping("/my/bookmarks")
    public ResponseEntity<ApiResponse<BookmarkCursorPageResponse<BookmarkListResponse>>> getMyBookmarks(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "이전 페이지의 마지막 ID(커서)", example = "100") @RequestParam(required = false) Long cursorId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                bookmarkService.getMyBookmarks(authentication.getName(), size, cursorId)
        ));
    }
}