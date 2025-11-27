package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum PaymentErrorCode implements AppError {
    IMAGE_STAGING_NOT_FOUND(HttpStatus.BAD_REQUEST, "스테이징 이미지가 존재하지 않습니다. 이미 이동되었을 수 있습니다."),
    ITEM_OUT_OF_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),
    APPLE_WEBHOOK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "IOS 웹훅 실패" ),
    GOOGLE_WEBHOOK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "ANDROID 웹훅 실패" ),

    APPLE_TRANSACTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Apple 트랜잭션 조회에 실패했습니다."),
    APPLE_SIGNED_TRANSACTIONINFO_MISSING(HttpStatus.BAD_REQUEST, "Apple 응답에 signedTransactionInfo가 없습니다."),
    APPLE_PUBLIC_KEY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Apple 공개키를 찾을 수 없습니다."),

    APPLE_JWS_PARSE_FAILED(HttpStatus.BAD_REQUEST, "Apple JWS 형식이 올바르지 않습니다."),
    APPLE_KEY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Apple 공개 키를 찾을 수 없습니다."),
    APPLE_JWK_TYPE_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "Apple 공개 키 타입이 올바르지 않습니다."),
    APPLE_VERIFY_FAILED(HttpStatus.BAD_REQUEST, "Apple JWS signature 부정확합니다."),
    GOOGLE_VERIFY_FAILED(HttpStatus.BAD_REQUEST, "Google 결제 검증에 실패했습니다.");


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
