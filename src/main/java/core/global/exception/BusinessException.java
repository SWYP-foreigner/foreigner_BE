package core.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final AppError error;
    private final Object detail;

    public BusinessException(AppError error) {
        super(error.message());
        this.status = error.httpStatus();
        this.error = error;
        this.detail = null;
    }

    public BusinessException(AppError error, Object detail) {
        super(error.message());
        this.status = error.httpStatus();
        this.error = error;
        this.detail = detail;
    }

    public BusinessException(HttpStatus status, AppError error, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.detail = null;
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.error = null;
        this.detail = null;
    }
}
