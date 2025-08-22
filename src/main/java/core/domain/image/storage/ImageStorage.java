package core.domain.image.storage;


import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import core.domain.image.dto.NcpS3Props;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${ncp.s3.bucket}")
    private String bucketName;

    /**
     * 프로필 이미지를 S3에 업로드하고, 저장된 URL을 반환합니다.
     * @param image 업로드할 이미지 파일
     * @param userId 이미지 소유자의 ID
     * @return S3에 저장된 이미지의 공개 URL
     * @throws IOException 파일 처리 중 오류 발생 시
     */
    public String uploadProfileImage(MultipartFile image, Long userId) throws IOException {
        String originalFilename = image.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        String s3FileName = "user-profiles/" + userId + "/" + UUID.randomUUID() + "-" + originalFilename;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(image.getContentType());
        metadata.setContentLength(image.getSize());

        try {
            s3.putObject(new PutObjectRequest(bucketName, s3FileName, image.getInputStream(), metadata));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        return s3.getUrl(bucketName, s3FileName).toString();
    }

    public void deleteByKey(String key) {
        s3.deleteObject(props.getBucket(), key);
    }
}
