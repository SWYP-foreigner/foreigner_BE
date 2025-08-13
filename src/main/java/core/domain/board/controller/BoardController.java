package core.domain.board.controller;

import core.domain.board.dto.BoardResponse;
import core.domain.post.service.PostService;
import core.global.enums.BoardCategory;
import core.global.enums.SortOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/board")
public class BoardController {

    private final PostService postService;

    BoardController(PostService postService) {
        this.postService = postService;
    }


    @Operation(
            summary = "게시글 목록 조회",
            description = """
                    - 카테고리(PathVariable)와 정렬/커서(QueryParam)를 이용해 게시글 목록을 조회합니다.
                    - 무한스크롤용 커서: `cursorCreatedAt`(ISO-8601) + `cursorId`를 함께 전달하면 다음 페이지를 조회합니다.
                    - `boardCategory=ALL`이면 전체 게시글을 조회합니다.
                    """,
            tags = {"Board"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = core.global.dto.ApiResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "성공 예시",
                                            value = """
                                                    {
                                                      "success": true,
                                                      "data": [
                                                        {
                                                          "postId": 123,
                                                          "title": "제목",
                                                          "contentPreview": "내용 프리뷰 ...",
                                                          "authorName": "익명",
                                                          "createdAt": "2025-08-13T07:20:35Z",
                                                          "likeCount": 10,
                                                          "commentCount": 2,
                                                          "viewCount": 345
                                                        },
                                                        {
                                                          "postId": 122,
                                                          "title": "다음 글",
                                                          "contentPreview": "내용 프리뷰 ...",
                                                          "authorName": "홍길동",
                                                          "createdAt": "2025-08-13T07:19:10Z",
                                                          "likeCount": 0,
                                                          "commentCount": 0,
                                                          "viewCount": 12
                                                        }
                                                      ]
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (유효하지 않은 카테고리/커서 조합 등)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "잘못된 카테고리",
                                    value = "{ \"code\": \"INVALID_CATEGORY\", \"message\": \"허용: ALL, NEWS, TIP, QNA, EVENT, FREE_TALK, ACTIVITY\" }"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "실행 실패",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = "{\n  \"code\": \"W001\",\n  \"message\": \"\"}")
                    )
            )
    })
    @GetMapping("/{boardCategory}")
    public ResponseEntity<core.global.dto.ApiResponse<List<BoardResponse>>> getPostList(Authentication authentication,
                                                                                        @PathVariable BoardCategory boardCategory,
                                                                                        @RequestParam(defaultValue = "LATEST") SortOption sort,
                                                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorCreatedAt,
                                                                                        @RequestParam(required = false) Long cursorId,
                                                                                        @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        postService.getPostList(boardCategory, sort, cursorCreatedAt, cursorId, size)
                ));
    }
}
