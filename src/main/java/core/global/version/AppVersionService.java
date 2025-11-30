package core.global.version;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;

    @Transactional(readOnly = true)
    public VersionCheckDto.Response checkVersion(VersionCheckDto.Request request) {
        // 1. 플랫폼 검증 및 DB 조회
        Platform platform;
        try {
            platform = Platform.valueOf(request.getPlatform().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid platform: " + request.getPlatform());
        }

        AppVersion serverVersion = appVersionRepository.findByPlatform(platform)
                .orElseThrow(() -> new IllegalStateException("버전 정보가 설정되지 않았습니다. 관리자에게 문의하세요."));

        // 2. 버전 비교 실행
        String currentVer = request.getCurrentVersion();

        // Case 1: 최소 버전(minimum)보다 낮음 -> 강제 업데이트(FORCE)
        if (compareVersion(currentVer, serverVersion.getMinimumVersion()) < 0) {
            return VersionCheckDto.Response.builder()
                    .status(VersionCheckDto.UpdateStatus.FORCE_UPDATE)
                    .title("Update Required")
                    .message(serverVersion.getMessage()) // DB에 저장된 메시지 전달
                    .storeUrl(serverVersion.getStoreUrl())
                    .build();
        }

        // Case 2: 최신 버전(latest)보다 낮음 -> 권장 업데이트(RECOMMEND)
        if (compareVersion(currentVer, serverVersion.getLatestVersion()) < 0) {
            return VersionCheckDto.Response.builder()
                    .status(VersionCheckDto.UpdateStatus.RECOMMEND_UPDATE)
                    .title("Update Available")
                    .message("A new version is available. Would you like to update?")
                    .storeUrl(serverVersion.getStoreUrl())
                    .build();
        }

        // Case 3: 최신 버전임 -> 통과(PASS)
        return VersionCheckDto.Response.builder()
                .status(VersionCheckDto.UpdateStatus.PASS)
                .build();
    }

    /**
     * 버전 문자열 비교 유틸리티
     * 예: 1.2.4 vs 1.2.5
     * return < 0 : v1이 더 작음 (업데이트 필요)
     * return 0   : 같음
     * return > 0 : v1이 더 큼
     */
    private int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;

        String[] v1Parts = v1.split("\\.");
        String[] v2Parts = v2.split("\\.");

        int length = Math.max(v1Parts.length, v2Parts.length);

        for (int i = 0; i < length; i++) {
            // 숫자가 없으면 0으로 간주 (예: 1.0 vs 1.0.0 은 같음)
            int part1 = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int part2 = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;

            if (part1 < part2) return -1;
            if (part1 > part2) return 1;
        }
        return 0;
    }
}