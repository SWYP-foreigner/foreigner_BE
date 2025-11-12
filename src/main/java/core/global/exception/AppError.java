package core.global.exception;


import org.springframework.http.HttpStatus;

public interface AppError {
    HttpStatus httpStatus();
    String code();
    String message();
}