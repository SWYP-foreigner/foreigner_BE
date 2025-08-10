package core.controller;


import com.foreigner.core.common.JwtTokenProvider;
import com.foreigner.core.domain.user.User;
import com.foreigner.core.dto.AccessTokenDto;
import com.foreigner.core.dto.GoogleLoginReq;
import com.foreigner.core.dto.GoogleProfileDto;
import com.foreigner.core.service.GoogleService;
import com.foreigner.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/member")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleService googleService;


    @PostMapping("/google/doLogin")
    public ResponseEntity<?> googleLogin(@RequestBody GoogleLoginReq req) {
        // 1) 플랫폼 파싱
        GoogleService.Platform platform =
                GoogleService.Platform.valueOf(req.getPlatform().toUpperCase());

        // 2) 코드 교환(PKCE)
        AccessTokenDto accessTokenDto = googleService.exchangeCodeWithPkce(
                req.getCode(), req.getCodeVerifier(), platform
        );

        // 3) 사용자 정보 조회 (accessTokenDto.getAccessToken() 주의)
        GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

        // 4) 회원 업서트
        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        }

        // 5) 우리 JWT 발급
        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);
        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }

}
