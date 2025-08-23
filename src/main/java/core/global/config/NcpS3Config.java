package core.global.config;


import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NCP S3와 연동하기 위한 설정 클래스
 * application.yml에 정의된 환경 변수를 사용하여 AmazonS3 클라이언트를 빈으로 등록합니다.
 */
@Configuration
public class NcpS3Config {

    @Value("${ncp.s3.access-key}")
    private String accessKey;

    @Value("${ncp.s3.secret-key}")
    private String secretKey;

    @Value("${ncp.s3.region}")
    private String regionName;

    @Value("${ncp.s3.endpoint}")
    private String endpoint;

    /**
     * NCP S3에 접근하기 위한 AmazonS3 클라이언트 빈을 생성합니다.
     * @return AmazonS3 클라이언트 인스턴스
     */
    @Bean
    public AmazonS3 amazonS3() {
        AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);

        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, regionName))
                .build();
    }
}
