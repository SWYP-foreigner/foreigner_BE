package core.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final AppError error;

    public BusinessException(AppError error) {
        super(error.message());
        this.status = error.httpStatus();
        this.error = error;
    }

    public BusinessException(HttpStatus status, AppError error, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.error = null;
    }
}
