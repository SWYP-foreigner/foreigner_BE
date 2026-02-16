package core.domain.payment.service;

public interface AppleJwsVerifier {
    String verifyAndGetPayload(String jws);
}