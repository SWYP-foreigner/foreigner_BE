package core.global.entity.image.service.impl;

import core.global.entity.image.S3Props;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.stream.Collectors;

import static core.global.entity.image.utils.UrlUtil.toKeyFromUrlOrKey;
import static core.global.entity.image.utils.UrlUtil.trimSlashes;
import static software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ImageStorageClient implements ImageStorageClient {

    private final S3Client s3Client;
    private final S3Props s3Props;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Override
    public void deleteObjectsBulk(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;

        List<String> filtered = keys.stream()
                .filter(k -> !isDefaultUrlOrKey(k))
                .toList();
        if (filtered.isEmpty()) return;

        final int LIMIT = 1000; // S3/NCP 일반 한도
        for (int i = 0; i < keys.size(); i += LIMIT) {
            List<String> chunk = filtered.subList(i, Math.min(i + LIMIT, filtered.size()));
            try {
                var res = s3Client.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(
                        chunk.stream()
                                .map(k -> builder().key(k).build())
                                .toList()
                )));
                if (res != null && res.errors() != null && !res.errors().isEmpty()) {
                    for (var err : res.errors()) {
                        log.warn("[POST IMG] bulk delete error key={}, code={}, msg={}",
                                err.key(), err.code(), err.message());
                    }
                }
            } catch (SdkException e) {
                log.warn("[POST IMG] bulk delete failed size={}, err={}", chunk.size(), e.getMessage());
            }
        }
    }

    /**
     * ✅ 폴더 삭제 (prefix 기준)
     */
    @Override
    public void deleteFolder(String fileLocation) {
        String prefix = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, fileLocation);
        if (!prefix.endsWith("/")) prefix += "/";

        // prefix 자체가 default면 즉시 스킵
        if (isDefaultUrlOrKey(prefix)) return;

        String continuation = null;
        try {
            do {
                var reqBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuation != null) reqBuilder.continuationToken(continuation);
                var res = s3Client.listObjectsV2(reqBuilder.build());

                var toDelete = res.contents().stream()
                        .map(S3Object::key)
                        .filter(k -> !k.endsWith("/"))
                        .filter(k -> !isDefaultUrlOrKey(k))
                        .map(k -> ObjectIdentifier.builder().key(k).build())
                        .collect(Collectors.toList());

                if (!toDelete.isEmpty()) {
                    var delReq = DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(toDelete).build())
                            .build();
                    s3Client.deleteObjects(delReq);
                }

                continuation = res.isTruncated() ? res.nextContinuationToken() : null;
            } while (continuation != null);
        } catch (SdkException e) {
            throw new BusinessException(ImageErrorCode.IMAGE_FOLDER_DELETE_FAILED);
        }
    }

    @Override
    public HeadObjectResponse headObject(String key) {
        try {
            return s3Client.headObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    @Override
    public String extOf(String key) {
        int dot = key.lastIndexOf('.');
        String ext = (dot > -1 && dot < key.length() - 1) ? key.substring(dot + 1) : "jpg";
        if (ext.length() > 8) ext = "jpg";
        return ext.toLowerCase();
    }

    @Override
    public boolean isDefaultUrlOrKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return false;
        String k = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
        k = trimSlashes(k);
        return k.startsWith("default/"); // 예: default/character_03.png
    }

    @Override
    public boolean isStagingKey(String key) {
        String k = UrlUtil.trimSlashes(key);
        return k.startsWith("temp/");
    }
}
