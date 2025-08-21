package core.global.config;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import core.domain.image.dto.NcpS3Props;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NcpS3Props.class)
public class NcpS3Config {

    @Bean
    public AmazonS3 ncpS3(NcpS3Props props) {
        AWSCredentials creds = new BasicAWSCredentials(
                props.getAccessKey(),
                props.getSecretKey()
        );

        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(
                                props.getEndpoint(),   // https://kr.object.ncloudstorage.com
                                props.getRegion()      // kr-standard
                        )
                )
                .withPathStyleAccessEnabled(true) // NCP는 필수
                .withCredentials(new AWSStaticCredentialsProvider(creds))
                .build();
    }
}
