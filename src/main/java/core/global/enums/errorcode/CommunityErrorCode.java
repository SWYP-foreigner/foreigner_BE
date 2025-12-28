package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CommunityErrorCode implements AppError {
    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 게시판입니다."),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 게시물입니다."),
    POST_EDIT_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 글을 수정할 권한이 없습니다."),
    POST_DELETE_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 글을 삭제할 권한이 없습니다."),
    NOT_AVAILABLE_ANONYMOUS(HttpStatus.CONFLICT, "익명이 허용되지 않는 카테고리입니다."),
    INVALID_BOARD_CATEGORY(HttpStatus.BAD_REQUEST, "유효하지 않은 카테고리입니다."),
    NOT_AVAILABLE_WRITE(HttpStatus.CONFLICT, "쓰기가 불가능한 카테고리입니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 댓글입니다."),
    INVALID_PARENT_COMMENT(HttpStatus.BAD_REQUEST, "대댓글하려는 댓글이 적합하지 않습니다."),
    INVALID_COMMENT_INPUT(HttpStatus.BAD_REQUEST, "댓글 입력값이 잘못됐습니다."),
    COMMENT_DELETE_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 댓글을 삭제할 권한이 없습니다."),
    COMMENT_EDIT_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 댓글을 수정할 권한이 없습니다."),
    COMMENT_ALREADY_DELETED(HttpStatus.GONE, "삭제된 댓글입니다."),
    LIKE_NOT_FOUND(HttpStatus.NOT_FOUND, "좋아요가 존재하지 않습니다."),
    LIKE_ALREADY_EXIST(HttpStatus.CONFLICT, "좋아요가 이미 존재합니다."),
    BOOKMARK_ALREADY_EXIST(HttpStatus.CONFLICT, "북마크가 이미 존재합니다."),
    BOOKMARK_NOT_FOUND(HttpStatus.NOT_FOUND, "북마크가 존재하지 않습니다."),
    BLOCKED_USER_POST(HttpStatus.FORBIDDEN, "접근할 수 없는 글입니다."),
    NOT_AVAILABLE_LINK(HttpStatus.BAD_REQUEST, "링크가 불가능한 카테고리입니다."),
    CRAWLED_DATA_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 크롤링 데이터입니다."),
    DUPLICATE_CATEGORY(HttpStatus.FORBIDDEN, "중복된 카테고리입니다."),
    DUPLICATE_CONTENT(HttpStatus.BAD_REQUEST, "동일한 내용을 반복해서 작성할 수 없습니다."),
    TOO_MANY_POSTS(HttpStatus.TOO_MANY_REQUESTS, "게시글 도배를 방지합니다."),
    TOO_MANY_COMMENTS(HttpStatus.TOO_MANY_REQUESTS, "댓글 도배를 방지합니다."),
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "이미 신고한 게시물입니다."),
    CANNOT_REPORT_SELF(HttpStatus.BAD_REQUEST, "본인의 게시물은 신고할 수 없습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고 내역입니다."),
    INAPPROPRIATE_CONTENT(HttpStatus.BAD_REQUEST, "부적절한 콘텐츠(성인/폭력/혐오)가 감지되어 업로드가 차단되었습니다."),
    POLL_NOT_FOUND(HttpStatus.NOT_FOUND, "투표&퀴즈가 존재하지 않습니다."),
    POLL_ALREADY_CLOSED(HttpStatus.BAD_REQUEST, "투표 기간이 종료됐습니다."),
    ALREADY_PARTICIPATED(HttpStatus.CONFLICT, "투표에 이미 참가했습니다."), OPTION_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 선택지입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
