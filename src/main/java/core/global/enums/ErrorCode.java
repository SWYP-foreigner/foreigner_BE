package core.global.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 장소입니다."),
    INVALID_PLACE_CATEGORY(HttpStatus.BAD_REQUEST, "유효하지 않은 category입니다."),
    INVALID_TRIP_ORDER_INDEX(HttpStatus.BAD_REQUEST, "이동할 수 없는 Index입니다."),

    FOLLOW_ALREADY_EXISTS(HttpStatus.BAD_REQUEST,"이미 팔로우 한 대상입니다."),
    USERSPOT_IS_ALREADY_CREATED(HttpStatus.BAD_REQUEST, "이미 존재하는 나만의 장소입니다."),
    INVALID_TRIP_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 status입니다."),
    FOLLOWER_NOT_FOUND(HttpStatus.NOT_FOUND,"팔로워를 찾을 수 없습니다."),


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

    CHAT_ROOM_JOIN_FAILED(HttpStatus.BAD_REQUEST, "채팅방에 들어가지 못했습니다."),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_PARTICIPANT_MINIMUM(HttpStatus.BAD_REQUEST, "채팅방에는 최소 1명(개설자)이 포함되어야 합니다."),
    CHAT_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "참여자 중 존재하지 않는 사용자가 있습니다.");
    private final HttpStatus  errorCode;
    private final String message;

}
