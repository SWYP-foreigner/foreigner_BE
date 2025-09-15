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
        if (!isAbsoluteUrl(urlOrKey)) {
            return trimSlashes(urlOrKey);
        }
        String ep = endPoint.replaceAll("/+$", "");
        String pathStylePrefix = ep + "/" + trimSlashes(bucket) + "/";
        if (urlOrKey.startsWith(pathStylePrefix)) {
            String rest = urlOrKey.substring(pathStylePrefix.length());
            return trimSlashes(urlDecode(rest));
        }

        String host = ep.replaceFirst("^https?://", "");
        String vhostPrefix = "https://" + trimSlashes(bucket) + "." + host + "/";
        if (urlOrKey.startsWith(vhostPrefix)) {
            String rest = urlOrKey.substring(vhostPrefix.length());
            return trimSlashes(urlDecode(rest));
        }

        return trimSlashes(urlDecode(urlOrKey));
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
