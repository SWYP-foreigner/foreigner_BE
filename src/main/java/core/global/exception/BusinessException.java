package core.global.exception;

import core.global.enums.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode errorCode;

    // 1. ErrorCode 하나만 받는 생성자
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.status = errorCode.getErrorCode();
        this.errorCode = errorCode;
    }

    // 2. HttpStatus + ErrorCode + message + cause
    public BusinessException(HttpStatus status, ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    // 3. Optional: HttpStatus + message만
    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.errorCode = null;
    }
}
