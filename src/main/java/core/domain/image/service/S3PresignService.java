package core.domain.image.service;


import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import core.domain.image.dto.NcpS3Props;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class S3PresignService {
    private final AmazonS3 s3;
    private final NcpS3Props props; // bucket/endpoint/region 등

    // 업로드용(클라이언트가 PUT으로 업로드)
    public URL presignPut(String key, String contentType, Duration ttl) {
        Date exp = Date.from(Instant.now().plus(ttl));
        GeneratePresignedUrlRequest req =
                new GeneratePresignedUrlRequest(props.getBucket(), key)
                        .withMethod(HttpMethod.PUT)
                        .withExpiration(exp);

        // 업로드 시 반드시 같은 헤더로 요청해야 함
        req.addRequestParameter("Content-Type", contentType);
        // 공개로 올리고 싶다면(선택): req.addRequestParameter("x-amz-acl", "public-read");

        return s3.generatePresignedUrl(req); // 반환된 URL로 클라이언트가 직접 PUT
    }

    // 다운로드용(GET)
    public URL presignGet(String key, Duration ttl) {
        Date exp = Date.from(Instant.now().plus(ttl));
        GeneratePresignedUrlRequest req =
                new GeneratePresignedUrlRequest(props.getBucket(), key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(exp);
        return s3.generatePresignedUrl(req);
    }
}
