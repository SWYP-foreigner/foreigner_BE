package core.global.controller;


import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.AccessTokenDto;
import core.global.dto.GoogleLoginReq;
import core.global.dto.GoogleProfileDto;
import core.global.dto.GoogleTestReq;
import core.global.service.GoogleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;



@Tag(name = "User", description = "사용자 관련 API")
@RestController
@RequestMapping("api/v1/member")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleService googleService;



    @PostMapping("/google/doLogin")
    @Operation(summary = "구글 로그인(웹 API"
            , description = "Swagger에서 테스트할 수 있도록 앱 인증 코드를 사용합니다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급")
    public ResponseEntity<?> googleTestLogin(
            @Parameter(description = "구글 Access Token", required = true)
            @RequestBody GoogleTestReq req) {

        // 1. Access Token으로 사용자 프로필 정보 조회
        // 이 엔드포인트는 토큰 교환 과정을 건너뛰고 바로 사용자 프로필 조회를 시작합니다.
        GoogleProfileDto profile = googleService.getGoogleProfile(req.getAccessToken());

        // 2. 소셜 ID로 기존 사용자 찾기 또는 신규 사용자 생성
        User originalUser = userService.getUserBySocialId(profile.getSub());
        if (originalUser == null) {
            originalUser = userService.createOauth(profile.getSub(), profile.getEmail(), "GOOGLE");
        }

        // 3. 사용자 정보로 JWT 토큰 생성
        String jwtToken = jwtTokenProvider.createToken(originalUser.getEmail());

        // 4. 로그인 정보 반환
        Map<String, Object> loginInfo = new HashMap<>();
        loginInfo.put("id", originalUser.getId());
        loginInfo.put("token", jwtToken);
        return new ResponseEntity<>(loginInfo, HttpStatus.OK);
    }


}
