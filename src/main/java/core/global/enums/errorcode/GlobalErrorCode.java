package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GlobalErrorCode implements AppError {

    // Bean Validation – 필드/파라미터 검증 실패
    INVALID_INPUT(HttpStatus.UNPROCESSABLE_ENTITY, "유효성 검증에 실패했습니다."),

    // JSON 파싱/타입 불일치
    INVALID_JSON(HttpStatus.BAD_REQUEST, "요청 본문을 파싱할 수 없습니다."),

    // 지원하지 않는 HTTP 메서드
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    // 서버 내부 오류
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "알 수 없는 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");


    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        // 에러 코드 문자열은 ENUM 이름 그대로 사용
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}