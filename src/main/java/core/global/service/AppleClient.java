package core.global.service;

import core.global.dto.ApplePublicKeys;
import core.global.dto.AppleTokenResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@FeignClient(name = "appleClient", url = "https://appleid.apple.com")
public interface AppleClient {

    @GetMapping("/auth/keys")
    ApplePublicKeys getApplePublicKeys();

    @PostMapping(value = "/auth/token", consumes = "application/x-www-form-urlencoded")
    AppleTokenResponse findAppleToken(@RequestBody MultiValueMap<String, String> form);

    @PostMapping(value = "/auth/revoke", consumes = "application/x-www-form-urlencoded")
    void revoke(@RequestBody MultiValueMap<String, String> form);

}
