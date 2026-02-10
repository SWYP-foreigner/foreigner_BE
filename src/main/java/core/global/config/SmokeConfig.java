package core.global.config;

import core.global.smoke.utils.SmokeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SmokeProperties.class)
public class SmokeConfig { }