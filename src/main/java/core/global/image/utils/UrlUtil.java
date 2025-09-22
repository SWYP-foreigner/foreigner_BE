package core.global.image.utils;

import core.global.enums.ImageType;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class UrlUtil {

    private UrlUtil() {
    }

    /**
     * 경로 세그먼트 인코딩: 공백 '+' -> '%20' 치환
     */
    public static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 전체 경로를 세그먼트 단위로 인코딩(슬래시는 유지)
     */
    public static String encodePathSegments(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodePathSegment(parts[i]));
        }
        return sb.toString();
    }

    public static String urlDecode(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    public static String trimSlashes(String s) {
        if (s == null) return "";
        return s.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    public static boolean isAbsoluteUrl(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /**
     * S3/NCP 원본 키(인코딩 금지)
     */
    public static String buildRawKey(String ownerKey, ImageType imageType,
                                     String uploadSessionId, String filename) {
        String clean = trimSlashes(filename);
        String cat = imageType.name().toLowerCase();
        return "temp/" + cat + "/" + ownerKey + "/" + uploadSessionId + "/" + clean;
    }


    public static String buildCdnUrlFromKey(String cdnBaseUrl, String key) {
        String clean = trimSlashes(key);
        String encoded = encodePathSegments(clean);
        String base = cdnBaseUrl.replaceAll("/+$", "");
        return base + "/" + encoded;
    }

    /**
     * 주어진 key를 path-style 공개 URL로 변환(세그먼트 인코딩 포함)
     */
    public static String buildPublicUrlFromKey(String endPoint, String bucket, String key) {
        String clean = trimSlashes(key);
        String encoded = encodePathSegments(clean);
        String ep = endPoint.replaceAll("/+$", "");
        return ep + "/" + trimSlashes(bucket) + "/" + encoded;
    }


    /**
     * path-style 혹은 virtual-hosted-style 공개 URL prefix 제거 → key로 변환(디코딩 포함)
     */
    public static String toKeyFromUrlOrKey(String endPoint, String bucket, String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isBlank()) return null;
        String s = urlOrKey.trim();

        // 원본 엔드포인트 + 버킷을 제거
        String originPrefix = endPoint.replaceAll("/+$", "") + "/" + trimSlashes(bucket) + "/";
        if (s.startsWith(originPrefix)) {
            return trimSlashes(s.substring(originPrefix.length()));
        }

        // CDN 베이스가 별도 설정이라면, 아래 오버로드를 사용하세요 (2-b 참고)
        // 그 외: 이미 key라고 가정
        return trimSlashes(s);
    }

    public static String toKeyFromUrlOrKey(String endPoint, String bucket, String cdnBaseUrl, String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isBlank()) return null;
        String s = urlOrKey.trim();

        // 키만 들어온 경우 그대로 정리해서 반환
        // (http/https가 아니면 URL로 보지 않고 키로 취급)
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            return trimSlashes(s);
        }

        // 공통 정규화
        String ep = endPoint.replaceAll("/+$", "");
        String bucketTrimmed = trimSlashes(bucket);

        // 1) path-style: https://endpoint/bucket/Key...
        String pathStylePrefix = ep + "/" + bucketTrimmed + "/";
        if (s.startsWith(pathStylePrefix)) {
            String rest = s.substring(pathStylePrefix.length());
            return trimSlashes(urlDecode(rest));
        }

        // 2) vhost-style: https://bucket.endpoint/Key...
        //    (endpoint의 스킴을 따라가도록 처리)
        String scheme = ep.startsWith("http://") ? "http://" : "https://";
        String hostNoScheme = ep.replaceFirst("^https?://", ""); // endpoint에서 스킴 제거
        String vhostPrefix = scheme + bucketTrimmed + "." + hostNoScheme + "/";
        if (s.startsWith(vhostPrefix)) {
            String rest = s.substring(vhostPrefix.length());
            return trimSlashes(urlDecode(rest));
        }

        // 3) CDN: https://cdnBase/.../Key...
        if (cdnBaseUrl != null && !cdnBaseUrl.isBlank()) {
            String cdnPrefix = cdnBaseUrl.replaceAll("/+$", "") + "/";
            if (s.startsWith(cdnPrefix)) {
                String rest = s.substring(cdnPrefix.length());
                return trimSlashes(urlDecode(rest));
            }
        }

        // 4) 위 경우에 모두 해당하지 않으면, 전체를 디코드 후 정리
        //    (특수 케이스 URL이거나 이미 키 형태일 수 있음)
        return trimSlashes(urlDecode(s));
    }



    /**
     * fileLocation + fileName을 안전하게 조합하여 key 생성(디코딩 & 슬래시 정리)
     */
    public static String joinKey(String fileLocation, String fileName) {
        String loc = trimSlashes(urlDecode(fileLocation));
        String name = urlDecode(fileName).replaceAll("^/+", "");
        return loc.isEmpty() ? name : (loc + "/" + name);
    }

    /**
     * 경로에서 앞쪽 N개 세그먼트만 유지
     */
    public static String firstNSegments(String path, int n) {
        String[] parts = trimSlashes(path).split("/");
        if (parts.length <= n) return String.join("/", parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
