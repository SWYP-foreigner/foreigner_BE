package core.global.config;

import feign.Logger;
import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters; // 1. import 확인
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;

// 2. @Configuration은 여전히 제거된 상태여야 합니다.
public class AppleFeignConfig {

    // 3. (기존 빈) feignFormEncoder는 그대로 둡니다.
    @Bean
    public Encoder feignFormEncoder(ObjectFactory<HttpMessageConverters> converters) {
        return new SpringFormEncoder(new SpringEncoder(converters));
    }

    @Bean
    Logger.Level feignLoggerLevel() { return Logger.Level.FULL; }

    // 4. (★새로운 빈 추가★)
    // 이 Feign 클라이언트 전용의 HttpMessageConverters 빈을
    // 바로 이 설정 파일 내에 직접 생성합니다.
    @Bean
    public HttpMessageConverters feignHttpMessageConverters() {
        // 이렇게 하면 메인 컨텍스트의 빈을 사용하지 않고,
        // 이 Feign 설정(AppleFeignConfig) 내에서 독립적인
        // HttpMessageConverters 빈을 생성하여 사용합니다.
        return new HttpMessageConverters();
    }
}