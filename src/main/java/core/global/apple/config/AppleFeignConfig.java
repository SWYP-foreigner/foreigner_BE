package core.global.apple.config;

import feign.Logger;
import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters; // 1. import 확인
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;

// 2. @Configuration은 여전히 제거된 상태여야 합니다.
public class AppleFeignConfig {


    @Bean
    public Encoder feignFormEncoder(ObjectFactory<HttpMessageConverters> converters) {
        return new SpringFormEncoder(new SpringEncoder(converters));
    }

    @Bean
    Logger.Level feignLoggerLevel() { return Logger.Level.FULL; }

    @Bean
    public HttpMessageConverters feignHttpMessageConverters() {
        return new HttpMessageConverters();
    }
}