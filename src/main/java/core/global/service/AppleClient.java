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

    /**
     * public key 요청
     */
    @GetMapping("/auth/keys")
    ApplePublicKeyResponse getApplePublicKeys(); // ApplePublicKeyResponse를 반환

    /**
     * 토큰을 요청
     * 정확히는 (access+refresh+id) token 요청하고 받음
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
