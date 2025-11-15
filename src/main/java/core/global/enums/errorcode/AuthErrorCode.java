package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter @AllArgsConstructor
public enum AuthErrorCode implements AppError {
    NO_MATCHING_APPLE_KEY(HttpStatus.BAD_REQUEST, "일치하는 Apple 공개키가 없습니다."),
    PUBLIC_KEY_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "공개키 생성에 실패했습니다."),
    INVALID_JWT_ISSUER(HttpStatus.UNAUTHORIZED, "토큰 발급자(iss)가 유효하지 않습니다."),
    INVALID_JWT_AUDIENCE(HttpStatus.UNAUTHORIZED, "토큰 수신자(aud)가 유효하지 않습니다."),
    INVALID_JWT_NONCE(HttpStatus.UNAUTHORIZED, "Nonce 값이 일치하지 않습니다."),
    INVALID_JWT_APPLE(HttpStatus.UNAUTHORIZED, "APPLE JWT 값이 일치하지 않습니다."),
    INVALID_PRIVATE_KEY_APPLE(HttpStatus.UNAUTHORIZED, "APPLE 개인키가 유효하지 않습니다."),
    INVALID_APPLE_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "APPLE 리프레쉬 토큰을 지니고 있지 않습니다."),
    INVALID_APPLE_REQUEST(HttpStatus.BAD_REQUEST, "APPLE 서버에 요청에 실패했습니다"),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "보안에 취약합니다. 다시 재설정 바랍니다."),
    USER_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인 되어있지 않습니다."),
    TOKEN_NOT_FOUND(HttpStatus.BAD_REQUEST, "토큰이 없습니다."),
    JWT_EXPIRED(HttpStatus.BAD_REQUEST, "jwt 토큰이 만료되었습니다. "),
    INVALID_JWT(HttpStatus.BAD_REQUEST, "jwt 토큰을 찾을 수 없습니다."),
    INVALID_OR_EXPIRED_SESSION(HttpStatus.BAD_REQUEST, "세션이 만료되었습니다."),
    INVALID_OR_EXPIRED_TOKEN(HttpStatus.BAD_REQUEST, "토큰 시간이 만료되었습니다."),
    INVALID_OAUTH_CODE_MISSING(HttpStatus.BAD_REQUEST, "인가 코드가 누락되었습니다."),
    INVALID_OAUTH_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 인가 코드입니다."),
    UNSUPPORTED_SOCIAL_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인입니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "해당 이메일은 이미 다른 소셜 계정으로 가입되어 있습니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth 공급자에서 에러가 발생했습니다."),
    MISSING_SOCIAL_INFO(HttpStatus.BAD_REQUEST, "소셜 로그인 필수 정보가 누락되었습니다."),
    JWT_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "인증 정보(JWT)가 필요합니다."),
    JWT_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다."),
    JWT_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    JWT_TOKEN_BLACKLISTED(HttpStatus.UNAUTHORIZED, "블랙리스트에 등록된 토큰입니다."),
    AUTHENTICATION_ADMIN_FAILED(HttpStatus.UNAUTHORIZED, "관리자 계정이 아닙니다. 접근 권한이 없습니다.");


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
