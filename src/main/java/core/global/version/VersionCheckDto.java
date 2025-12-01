package core.global.version;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

public class VersionCheckDto {

    @Data
    @Schema(description = "앱 버전 체크 요청 객체")
    public static class Request {

        @Schema(description = "운영체제 플랫폼 (대소문자 무관)", example = "ANDROID", allowableValues = {"ANDROID", "IOS"})
        private String platform;

        @Schema(description = "현재 설치된 앱 버전 (x.y.z 형식)", example = "1.0.0")
        private String currentVersion;
    }

    @Data
    @Builder
    @Schema(description = "앱 버전 체크 응답 객체")
    public static class Response {

        @Schema(description = "업데이트 상태 (FORCE_UPDATE: 강제, RECOMMEND_UPDATE: 권장, PASS: 통과)", example = "FORCE_UPDATE")
        private UpdateStatus status;

        @Schema(description = "알림 팝업 제목", example = "Update Required")
        private String title;

        @Schema(description = "알림 팝업 내용 (줄바꿈 포함 가능)", example = "Please update to the latest version for more stable service.")
        private String message;

        @Schema(description = "스토어 이동 URL (마켓 스키마 또는 HTTPS 링크)", example = "market://details?id=com.SWYP.kori")
        private String storeUrl;
    }

    public enum UpdateStatus {
        FORCE_UPDATE,
        RECOMMEND_UPDATE,
        PASS
    }
}