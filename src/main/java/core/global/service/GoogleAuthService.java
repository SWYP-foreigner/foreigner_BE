package core.global.service;


import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.AccessTokenDto;
import core.global.dto.GoogleProfileDto;
import core.global.dto.LoginResponseDto;
import core.global.dto.UserLoggedInEvent;
import core.global.enums.Ouathplatform;
import core.global.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final GoogleService googleService;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public LoginResponseDto processGoogleLogin(String authCode) {
        log.info("1. 구글과 인증 코드를 교환하여 액세스 토큰을 받는 중...");
        AccessTokenDto accessTokenDto = googleService.exchangeCode(authCode);

        log.info("2. 받은 액세스 토큰으로 구글 사용자 프로필 정보를 조회하는 중...");
        GoogleProfileDto profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());

        log.info("3. 소셜 ID로 사용자 조회 또는 신규 생성...");
        User user = findOrCreateUser(profile);

        log.info("4. 인증된 사용자를 위한 새로운 JWT 토큰을 생성하는 중...");
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);

        boolean isNewUserResponse = user.isNewUser();
        log.info("이 사용자는 새로운 유저입니까? (isNewUser DB 값): {}", isNewUserResponse);

        publisher.publishEvent(new UserLoggedInEvent(user.getId().toString(), "google"));

        return new LoginResponseDto(user.getId(), accessToken, refreshToken, isNewUserResponse);
    }

    private User findOrCreateUser(GoogleProfileDto profile) {
        User user = userService.getUserBySocialIdAndProvider(profile.getSub(), String.valueOf(Ouathplatform.GOOGLE));
        if (user == null) {
            log.info("새로운 사용자입니다. 소셜 ID로 계정 생성");
            user = userService.createOauth(profile.getSub(), profile.getEmail(), String.valueOf(Ouathplatform.GOOGLE));
            log.info("새로운 사용자 계정 생성 완료. 사용자 ID: {}", user.getId());
        } else {
            log.info("기존 사용자 발견. 사용자 ID: {}", user.getId());
        }
        return user;
    }
}