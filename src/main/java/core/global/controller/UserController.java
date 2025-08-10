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
        GoogleService.Platform platform =
                GoogleService.Platform.valueOf(req.getPlatform().toUpperCase());
        AccessTokenDto accessTokenDto = googleService.exchangeCodeWithPkce(
                req.getCode(), req.getCodeVerifier(), platform
        );

        GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        }

        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);
        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }

}
