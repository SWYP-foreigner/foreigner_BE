package core.global.service;

// src/main/java/com/example/apple/config/AppleOAuthProperties.java

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.apple")
public record AppleOAuthProperties(
        String teamId,        // Apple Developer Team ID
        String keyId,         // p8 Key ID
        String clientId,      // iOS 네이티브: "번들ID"
        String redirectUri,   // (옵션) 필요 시
        String privateKeyPem  // p8 PEM (-----BEGIN PRIVATE KEY----- 포함)
) {}
