package core.domain.image.service;


import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import core.domain.image.dto.PresignResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NcpS3Service {

    private final AmazonS3 amazonS3;

    @Value("${ncp.s3.bucket}")
    private String bucketName;

    /**
     * Presigned URL을 생성하여 반환합니다.
     * @param fileName 업로드할 파일의 이름 (확장자 포함)
     * @return 생성된 Presigned URL과 파일 경로를 담은 DTO
     */
    public PresignResponse generatePresignedUrl(String fileName, String type) {
        Date expiration = new Date(System.currentTimeMillis() + 1000 * 60 * 5); // 5분

        // 타입별 폴더 지정
        String folder;
        switch (type.toLowerCase()) {
            case "post":
                folder = "images/post/";
                break;
            case "user":
                folder = "images/user/";
                break;
            default:
                folder = "images/etc/"; // 기본값
        }

        // 실제 S3에 저장될 파일명
        String s3FileName = folder + UUID.randomUUID() + "_" + fileName;

        GeneratePresignedUrlRequest generatePresignedUrlRequest =
                new GeneratePresignedUrlRequest(bucketName, s3FileName)
                        .withMethod(HttpMethod.PUT)
                        .withExpiration(expiration);

        URL url = amazonS3.generatePresignedUrl(generatePresignedUrlRequest);
        return new PresignResponse(url.toString(), s3FileName);
    }
}