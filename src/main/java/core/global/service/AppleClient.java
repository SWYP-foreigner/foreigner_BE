package core.global.service;

import core.global.dto.ApplePublicKey;
import core.global.dto.ApplePublicKeyResponse;
import core.global.dto.AppleTokenResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@FeignClient(name = "appleClient", url = "https://appleid.apple.com")
public interface AppleClient {

    /*
    퍼블릭 키 조회
    RestTemplate , OpenFeign 중 OpenFeign 방법 선택
     */
    @GetMapping("/auth/keys")
    ApplePublicKeyResponse getApplePublicKeys(); // ApplePublicKeyResponse를 반환
    /**
     * 기본적으로 토큰 검즘
     * @param form
     * @return
     */
    @PostMapping(value = "/auth/token", consumes = "application/x-www-form-urlencoded")
    AppleTokenResponse findAppleToken(@RequestBody MultiValueMap<String, String> form);

    /**
     * 발급 받은 엑세스나 리프레쉬 토큰을 무효화 시킨다.
     * @param form
     */
    @PostMapping(value = "/auth/revoke", consumes = "application/x-www-form-urlencoded")
    void revoke(@RequestBody MultiValueMap<String, String> form);

}
