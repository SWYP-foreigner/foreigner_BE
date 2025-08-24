package core.global.image.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ncp.s3")
public class NcpS3Props {
    private String accessKey;
    private String secretKey;
    private String region;    // "kr-standard"
    private String endpoint;  // "https://kr.object.ncloudstorage.com"
    private String bucket;    // 버킷명
    private String prefix = "profiles"; // 선택: 기본 업로드 폴더
}
