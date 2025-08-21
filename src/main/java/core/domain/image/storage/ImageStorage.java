package core.domain.image.storage;


import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import core.domain.image.dto.NcpS3Props;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageStorage {

    private final AmazonS3 s3;
    private final NcpS3Props props;

    /** 단일 이미지 업로드 → 퍼블릭 URL 반환 */
    public String uploadProfileImage(MultipartFile file, Long userId) throws IOException {
        if (file == null || file.isEmpty()) return null;

        // 키 규칙: <prefix>/user-<id>/<timestamp>-<uuid>.<ext>
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1) : "bin";
        String key = String.format("%s/user-%d/%d-%s.%s",
                props.getPrefix(), userId, Instant.now().toEpochMilli(), UUID.randomUUID(), ext);

        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentType(file.getContentType());
        meta.setContentLength(file.getSize());

        PutObjectRequest req = new PutObjectRequest(
                props.getBucket(),
                key,
                file.getInputStream(),
                meta
        ).withCannedAcl(CannedAccessControlList.PublicRead); // 공개 URL이 필요할 때

        s3.putObject(req);

        URL url = s3.getUrl(props.getBucket(), key); // NCP + path-style에서 정상 동작
        return url.toString();
    }

    /** 키로 삭제 (URL을 저장했다면, 키만 따로 컬럼에 보관해두는 것도 좋음) */
    public void deleteByKey(String key) {
        s3.deleteObject(props.getBucket(), key);
    }
}
