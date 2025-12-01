package core.global.enums.errorcode;


import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum VersionErrorCode implements AppError {
    INVALID_PLATFORM(HttpStatus.BAD_REQUEST, "지원하지 않는 플랫폼입니다. (허용: ANDROID, IOS)"),
    VERSION_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 플랫폼의 버전 정보를 찾을 수 없습니다.");
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
