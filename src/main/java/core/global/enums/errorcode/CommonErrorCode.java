package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements AppError {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력입니다."),
    INVALID_JSON(HttpStatus.BAD_REQUEST, "요청 본문을 파싱할 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOTIFICATION_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 설정을 찾을 수 없습니다."),
    USER_NOTIFICATION_SETTING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 알림 설정이 존재합니다."),

    FORBIDDEN_WORD_DETECTED(HttpStatus.BAD_REQUEST, "사용할 수 없는 단어가 포함되었습니다."),

    TRANSLATE_FAIL(HttpStatus.INTERNAL_SERVER_ERROR, "메시지 번역에 실패했습니다."),

    NOTIFICATION_FORBIDDEN(HttpStatus.FORBIDDEN, "알람을 받은 유저와 같은 유저가 아닙니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 알람을 찾을 수 없습니다."),

    FILE_UPLOAD_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드 처리 중 오류가 발생했습니다.");

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