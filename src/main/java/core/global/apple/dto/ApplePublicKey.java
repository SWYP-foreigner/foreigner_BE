package core.global.apple.dto;

public record ApplePublicKey(String kty, String kid,String use, String alg, String n, String e) {
}
