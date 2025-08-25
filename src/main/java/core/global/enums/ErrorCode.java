package core.global.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
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

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 장소입니다."),

    FOLLOW_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "이미 팔로우 한 대상입니다."),
    FOLLOWER_NOT_FOUND(HttpStatus.NOT_FOUND, "팔로워를 찾을 수 없습니다."),
    INVALID_TRIP_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 status입니다."),
    PROFILE_IMAGE_REGISTER_REQUIRED(HttpStatus.BAD_REQUEST, "프로필에 사진 한 장 등록은 필수입니다."),
    PROFILE_IMAGE_ONLY_ONE(HttpStatus.BAD_REQUEST, "프로필은 한장만 등록 가능합니다."),
    UPDATE_NOT_PROCESSED(HttpStatus.BAD_REQUEST, "프로필 수정에 업데이트에 실패했습니다."),
    INVALID_USER_UPDATE_REQUEST(HttpStatus.BAD_REQUEST, "프로필 업데이트가 처리되지 않았습니다."),
    INVALID_OAUTH_CODE_MISSING(HttpStatus.BAD_REQUEST, "인가 코드가 누락되었습니다."),
    INVALID_OAUTH_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 인가 코드입니다."),
    UNSUPPORTED_SOCIAL_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인입니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "해당 이메일은 이미 다른 소셜 계정으로 가입되어 있습니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth 공급자에서 에러가 발생했습니다."),
    MISSING_SOCIAL_INFO(HttpStatus.BAD_REQUEST, "소셜 로그인 필수 정보가 누락되었습니다."),
    USER_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인 되어있지 않습니다."),

    LIKE_NOT_FOUND(HttpStatus.NOT_FOUND, "좋아요가 존재하지 않습니다."),
    LIKE_ALREADY_EXIST(HttpStatus.CONFLICT, "좋아요가 이미 존재합니다."),
    BOOKMARK_ALREADY_EXIST(HttpStatus.CONFLICT, "북마크가 이미 존재합니다."),
    BOOKMARK_NOT_FOUND(HttpStatus.NOT_FOUND, "북마크가 존재하지 않습니다."),
    CHAT_ROOM_JOIN_FAILED(HttpStatus.BAD_REQUEST, "채팅방에 들어가지 못했습니다."),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_PARTICIPANT_MINIMUM(HttpStatus.BAD_REQUEST, "채팅방에는 최소 1명(개설자)이 포함되어야 합니다."),
    CHAT_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "참여자 중 존재하지 않는 사용자가 있습니다."),
    NOT_AVAILABLE_LINK(HttpStatus.BAD_REQUEST, "링크가 불가능한 카테고리입니다."),
    FORBIDDEN_WORD_DETECTED(HttpStatus.BAD_REQUEST, "사용할 수 없는 단어가 포함되었습니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "잘못된 커서값입니다."),

    IMAGE_STAGING_NOT_FOUND(HttpStatus.BAD_REQUEST, "스테이징 이미지가 존재하지 않습니다. 이미 이동되었을 수 있습니다."),
    IMAGE_COPY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 복사에 실패했습니다."),
    IMAGE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제에 실패했습니다."),
    IMAGE_UPLOAD_FAILED(HttpStatus.BAD_REQUEST, "이미지 업로드에 실패했습니다."),
    IMAGE_FILE_UPLOAD_TYPE_ERROR(HttpStatus.BAD_REQUEST, "이미지 파일이 아니라 다른 파일입니다."),
    IMAGE_FILE_DELETE_FAILED(HttpStatus.BAD_REQUEST, "이미지 삭제를 실패했습니다."),
    IMAGE_FOLDER_DELETE_FAILED(HttpStatus.BAD_REQUEST, "폴더 삭제에 실패했습니다."),


    TRANSLATE_FAIL(HttpStatus.INTERNAL_SERVER_ERROR, "메시지 번역에 실패했습니다.");
    private final HttpStatus errorCode;
    private final String message;

}
