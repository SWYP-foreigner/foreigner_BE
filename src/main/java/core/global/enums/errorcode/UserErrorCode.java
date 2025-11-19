package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements AppError {
    FOLLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "팔로우를 찾을수없습니다."),
    INVALID_FOLLOW_STATUS(HttpStatus.CONFLICT, "팔로우상태가 아닙니다."),
    AGREEMENT_INPUT(HttpStatus.CONFLICT, "약관 동의가 필요합니다."),
    INVALID_EMAIL_INPUT(HttpStatus.BAD_REQUEST, "해당 이메일은 소셜 로그인 계정입니다. 소셜 로그인을 이용하세요."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "중복된 이메일입니다."),
    AUTHENTICATION_OVER_FAILED(HttpStatus.TOO_MANY_REQUESTS, "횟수가 넘어갔습니다."),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    PASSWORD_FORM_FAILED(HttpStatus.BAD_REQUEST, "비밀번호는 8~12자, 대/소문자 각 1자 이상 포함하고 특수문자(@/!/~)를 1개 이상 포함해야 합니다."),
    PASSWORD_NOT_CORRECTED(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    CANNOT_FOLLOW_YOURSELF(HttpStatus.BAD_REQUEST, "자기자신은  팔로우가 불가능합니다."),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    FOLLOW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 유저를 팔로우하고 있습니다."),
    PROFILE_SET_NOT_COMPLETED(HttpStatus.PRECONDITION_REQUIRED, "Your profile is incomplete. Please complete your profile setup"),
    INVALID_BIRTHDAY_FORMAT(HttpStatus.UNPROCESSABLE_ENTITY, "birthday는 MM/DD/YYYY 형식이어야 합니다."),
    BIRTHDAY_IN_FUTURE(HttpStatus.UNPROCESSABLE_ENTITY, "생년월일에 미래 날짜는 입력할 수 없습니다."),
    BIRTHDAY_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "생년월일은 1900-01-01 이후여야 합니다."),
    CANNOT_BLOCK(HttpStatus.BAD_REQUEST, "차단할 수 없는 대상입니다."),

    FOLLOWER_NOT_FOUND(HttpStatus.NOT_FOUND, "팔로워를 찾을 수 없습니다."),
    PROFILE_IMAGE_REGISTER_REQUIRED(HttpStatus.BAD_REQUEST, "프로필에 사진 한 장 등록은 필수입니다."),
    PROFILE_IMAGE_ONLY_ONE(HttpStatus.BAD_REQUEST, "프로필은 한장만 등록 가능합니다."),
    UPDATE_NOT_PROCESSED(HttpStatus.BAD_REQUEST, "프로필 수정에 업데이트에 실패했습니다."),
    INVALID_USER_UPDATE_REQUEST(HttpStatus.BAD_REQUEST, "프로필 업데이트가 처리되지 않았습니다."),
    JWT_INVALID_ROLE(HttpStatus.UNAUTHORIZED, "추방된 유저입니다"),
    INVALID_PROFILE(HttpStatus.BAD_REQUEST, "이미 셋업한 유저입니다.");

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
