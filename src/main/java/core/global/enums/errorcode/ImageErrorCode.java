package core.global.enums.errorcode;

import core.global.exception.AppError;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ImageErrorCode implements AppError {
    IMAGE_STAGING_NOT_FOUND(HttpStatus.BAD_REQUEST, "스테이징 이미지가 존재하지 않습니다. 이미 이동되었을 수 있습니다."),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),
    IMAGE_COPY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 복사에 실패했습니다."),
    IMAGE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제에 실패했습니다."),
    IMAGE_UPLOAD_FAILED(HttpStatus.BAD_REQUEST, "이미지 업로드에 실패했습니다."),
    IMAGE_FILE_UPLOAD_TYPE_ERROR(HttpStatus.BAD_REQUEST, "이미지 파일이 아니라 다른 파일입니다."),
    IMAGE_FILE_DELETE_FAILED(HttpStatus.BAD_REQUEST, "이미지 삭제를 실패했습니다."),
    IMAGE_FOLDER_DELETE_FAILED(HttpStatus.BAD_REQUEST, "폴더 삭제에 실패했습니다."),
    IMAGE_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 처리 중 오류가 발생했습니다.");

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
