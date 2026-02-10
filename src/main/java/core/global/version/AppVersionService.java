package core.global.version;

import core.global.enums.errorcode.VersionErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // 로그 라이브러리
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j // 롬복 로그 사용
@Service
@RequiredArgsConstructor
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;

    @Transactional(readOnly = true)
    public VersionCheckDto.Response checkVersion(VersionCheckDto.Request request) {

        // [LOG] 1. 들어온 값 (요청) 확인
        log.info("👉 [VersionCheck] REQ | Platform: {}, ClientVer: {}",
                request.getPlatform(), request.getCurrentVersion());

        Platform platform;
        try {
            String safePlatform = request.getPlatform()
                    .replace("\"", "")
                    .replace("'", "")
                    .trim()
                    .toUpperCase();

            platform = Platform.valueOf(safePlatform);
        } catch (Exception e) {
            log.error("❌ Invalid Platform: {}", request.getPlatform());
            throw new BusinessException(VersionErrorCode.INVALID_PLATFORM);
        }

        AppVersion serverVersion = appVersionRepository.findByPlatform(platform)
                .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_INFO_NOT_FOUND));

        String currentVer = request.getCurrentVersion();

        // 1. 강제 업데이트 확인
        if (compareVersion(currentVer, serverVersion.getMinimumVersion()) < 0) {
            // [LOG] 2. 응답값 (강제 업데이트)
            log.info("👈 [VersionCheck] RES | FORCE_UPDATE (Client: {} < ServerMin: {})",
                    currentVer, serverVersion.getMinimumVersion());

            return VersionCheckDto.Response.builder()
                    .status(VersionCheckDto.UpdateStatus.FORCE_UPDATE)
                    .title("Update Required")
                    .message(serverVersion.getMessage())
                    .storeUrl(serverVersion.getStoreUrl())
                    .build();
        }

        // 2. 권장 업데이트 확인
        if (compareVersion(currentVer, serverVersion.getLatestVersion()) < 0) {
            // [LOG] 2. 응답값 (권장 업데이트)
            log.info("👈 [VersionCheck] RES | RECOMMEND_UPDATE (Client: {} < ServerLatest: {})",
                    currentVer, serverVersion.getLatestVersion());

            return VersionCheckDto.Response.builder()
                    .status(VersionCheckDto.UpdateStatus.RECOMMEND_UPDATE)
                    .title("Update Available")
                    .message("새로운 버전이 출시되었습니다.")
                    .storeUrl(serverVersion.getStoreUrl())
                    .build();
        }

        // 3. 통과
        // [LOG] 2. 응답값 (통과)
        log.info("👈 [VersionCheck] RES | PASS (Client: {} is Up-to-date)", currentVer);

        return VersionCheckDto.Response.builder()
                .status(VersionCheckDto.UpdateStatus.PASS)
                .build();
    }

    private int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        String[] v1Parts = v1.split("\\.");
        String[] v2Parts = v2.split("\\.");
        int length = Math.max(v1Parts.length, v2Parts.length);

        for (int i = 0; i < length; i++) {
            int part1 = parsePart(v1Parts, i);
            int part2 = parsePart(v2Parts, i);
            if (part1 < part2) return -1;
            if (part1 > part2) return 1;
        }
        return 0;
    }

    private int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}