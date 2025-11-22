package core.global.entity.image.service;

import jakarta.transaction.Transactional;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.List;

public interface ImageStorageClient {

    @Transactional
    void deleteObjectsBulk(List<String> keys);

    @Transactional
    void deleteFolder(String prefix);

    HeadObjectResponse headObject(String key);

    String extOf(String key);

    boolean isDefaultUrlOrKey(String keyOrUrl);

    boolean isStagingKey(String key);
}