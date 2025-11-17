package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ChatErrorCode implements AppError {
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "메시지가 존재하지 않습니다."),
    FORBIDDEN_MESSAGE_DELETE(HttpStatus.FORBIDDEN, "메세지는 보낸 사람만 삭제할 수 있습니다."),
    CHAT_PARTICIPANT_MINIMUM(HttpStatus.BAD_REQUEST, "채팅방에는 최소 1명(개설자)이 포함되어야 합니다."),
    CHAT_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "참여자 중 존재하지 않는 사용자가 있습니다."),
    CHAT_NOT_GROUP(HttpStatus.BAD_REQUEST, "현재의 채팅방은 그룹채팅방이 아닙니다"),
    ALREADY_CHAT_PARTICIPANT(HttpStatus.UNAUTHORIZED, "이미 현재의 그룹채팅방에 참여하고 있습니다."),
    NOT_CHAT_PARTICIPANT(HttpStatus.FORBIDDEN, "유저는 현재 채팅방에 참여하고 있지 않습니다."),
    CHAT_ROOM_JOIN_FAILED(HttpStatus.BAD_REQUEST, "채팅방에 들어가지 못했습니다."),
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "이미 신고한 메시지입니다."),
    CANNOT_REPORT_SELF(HttpStatus.BAD_REQUEST, "본인의 메시지는 신고할 수 없습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 신고 내역입니다."),
    ALREADY_RECOMMENDED_TODAY(HttpStatus.BAD_REQUEST, "오늘 이미 추천을 받았습니다."),
    NO_RECOMMENDABLE_ROOM(HttpStatus.NOT_FOUND, "추천할 수 있는 채팅방이 없습니다."),
    NO_MORE_RECOMMENDABLE_ROOM(HttpStatus.NOT_FOUND, "더 이상 추천할 채팅방이 없습니다.");
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
