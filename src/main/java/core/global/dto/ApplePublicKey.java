package core.global.dto;


public record ApplePublicKey(
        String kty,
        String kid,
        String use,
        String alg,
        String crv,
        String x,
        String y
) {}