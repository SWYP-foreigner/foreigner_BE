package core.global.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplePublicKey(
        String kty,
        String kid,
        String use,
        String alg,
        String crv,
        String x,
        String y
) {}