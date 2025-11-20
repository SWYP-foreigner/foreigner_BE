package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum PaymentErrorCode implements AppError {
    IMAGE_STAGING_NOT_FOUND(HttpStatus.BAD_REQUEST, "스테이징 이미지가 존재하지 않습니다. 이미 이동되었을 수 있습니다."),
    ITEM_OUT_OF_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다.");

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
