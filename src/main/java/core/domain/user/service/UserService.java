package core.domain.user.service;


import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.dto.ChatUserProfileResponse;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.dto.NewUserJoinedEvent;
import core.domain.notification.repository.NotificationRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.apple.dto.AppleLoginByCodeRequest;
import core.global.apple.service.AppleWithdrawalService;
import core.global.dto.*;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.ImageType;
import core.global.enums.Ouathplatform;
import core.global.enums.Role;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.redis.service.RedisService;
import core.global.security.JwtTokenProvider;
import core.global.service.SmtpMailService;
import core.global.userfeedback.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final String EMAIL_VERIFY_CODE_KEY = "email_verification:code:";
    private static final String EMAIL_VERIFIED_FLAG_KEY = "email_verification:verified:";
    private static final String EMAIL_VERIFY_ATTEMPT_KEY = "auth:verify-attempt:";
    private static final long CODE_TTL_MIN = 3L;
    private static final long VERIFIED_TTL_MIN = 10L;

    /**
     * 8~12자, 특수문자(@/!/~) 1+ 포함, 허용문자 제한
     */
    private static final Pattern PW_RULE = Pattern.compile(
            "^(?=.*[@/!/~])[A-Za-z0-9@/!/~]{8,12}$"
    );
    private final BlockPostRepository blockPostRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final BlockRepository blockRepository;
    private final SmtpMailService smtpService;
    private final RedisTemplate<String, String> redisTemplate;
    private final UserRepository userRepository;
    private final ImageService imageService;
    private final RedisService redisService;
    private final JwtTokenProvider jwtTokenProvider;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final PostRepository postRepository;
    private final ImageRepository imageRepository;
    private final FollowRepository followRepository;
    private final LikeRepository likeRepository;
    private final AppleWithdrawalService appleWithdrawalService;
    private final ChatRoomRepository chatRoomRepository;
    private final ApplicationEventPublisher publisher;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserFeedbackRepository userFeedbackRepository;
    Pattern pattern = Pattern.compile("\\[(.*?)\\]");

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
    public void logout(String accessToken) {
        long expiration = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();
        redisService.blacklistAccessToken(accessToken, expiration);
        Long userId = jwtTokenProvider.getUserIdFromAccessToken(accessToken);
        redisService.deleteRefreshToken(userId);

        log.info("사용자 {} 로그아웃 처리 완료 (Service).", userId);
    }

    public TokenRefreshResponse refreshTokens(String refreshToken) {
        log.info("--- [토큰 재발급 Service] 시작 ---");

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("유효하지 않은 리프레시 토큰 요청");
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN); // 적절한 ErrorCode 사용
        }

        Long userId = jwtTokenProvider.getUserIdFromRefreshToken(refreshToken);
        User user = userRepository.getUserById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String storedRefreshToken = redisService.getRefreshToken(userId);

        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            log.warn("Redis의 리프레시 토큰과 불일치. 탈취 가능성. 사용자 ID: {}", userId);
            redisService.deleteRefreshToken(userId);
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }


        redisService.deleteRefreshToken(userId);

        String newAccessToken = jwtTokenProvider.createAccessToken(userId, user.getUserRole().toString(), user.getEmail());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(userId);

        Date expirationDate = jwtTokenProvider.getExpiration(newRefreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(userId, newRefreshToken, expirationMillis);

        log.info("--- [토큰 재발급 Service] 완료. 사용자 ID: {} ---", userId);

        return new TokenRefreshResponse(newAccessToken, newRefreshToken, userId);
    }

    public User create(UserCreateDto memberCreateDto) {
        User user = User.builder()
                .email(memberCreateDto.getEmail())
                .build();
        userRepository.save(user);
        return user;
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional
    public User createOauth(String socialId, String email, String provider) {
        User u = User.builder()
                .socialId(socialId)
                .email(email)
                .provider(provider)
                .createdAt(Instant.now())
                .build();
        return userRepository.save(u);
    }

    @Transactional
    public User createAppleOauth(String socialId, String email, String provider, String appleRefreshToken
            , AppleLoginByCodeRequest.FullNameDto name) {
        User u = User.builder()
                .socialId(socialId)
                .email(email)
                .provider(provider)
                .appleRefreshToken(appleRefreshToken)
                .firstName(name.familyName())
                .lastName(name.givenName())
                .createdAt(Instant.now())
                .build();

        return userRepository.save(u);
    }

    /**
     * 사용자 정보(이름)를 업데이트합니다.
     *
     * @param user     업데이트할 User 엔티티
     * @param fullName Apple 로그인 시 전달받은 이름 정보 DTO
     */
    @Transactional
    public void updateUser(User user, AppleLoginByCodeRequest.FullNameDto fullName) {
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        if (fullName != null) {
            boolean isUpdated = false;
            if (fullName.givenName() != null && !fullName.givenName().isBlank()) {
                user.updateFirstName(fullName.givenName());
                isUpdated = true;
            }
            if (fullName.familyName() != null && !fullName.familyName().isBlank()) {
                user.updateLastName(fullName.familyName());
                isUpdated = true;
            }
            if (isUpdated) {
                userRepository.save(user);
            }
        }
    }

    public User getUserBySocialIdAndProvider(String socialId, String provider) {
        return userRepository.findByProviderAndSocialId(provider.trim(), socialId.trim()).orElse(null);
    }

    @Transactional
    public void setupUserProfile(UserSetupRequest dto) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        log.info("UserSetupRequest dto: {}", dto);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!user.isNewUser()) {
            throw new BusinessException(UserErrorCode.INVALID_PROFILE,
                    "이미 프로필이 설정된 사용자입니다.");
        }

        if (!Objects.equals(user.getProvider(), Ouathplatform.APPLE.toString())) {

            if (notBlank(dto.firstname())) {
                user.updateFirstName(dto.firstname().trim());
            }
            if (notBlank(dto.lastname())) {
                user.updateLastName(dto.lastname().trim());
            }
        }

        user.updateSex(dto.gender());
        user.updateBirthdate(dto.birthday());
        user.updateCountry(dto.country());
        user.updatePurpose(dto.purpose());
        String v = dto.introduction();
        user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v);

// UserSetupRequest dto를 받는 메서드 내부
        if (dto.language() != null && !dto.language().isEmpty()) {

            // 1. 초기 정제: null, 공백 제거 및 trim만 수행. (대소문자/포맷은 유지)
            List<String> rawLanguages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!rawLanguages.isEmpty()) {

                // 2. 번역 언어 (translate_language) 추출 및 저장 (무조건 소문자)
                String firstTranslatedLanguage = rawLanguages.stream()
                        .findFirst() // 첫 번째 언어를 선택
                        .map(s -> normalizeLanguageCode(s).toLowerCase()) // 코드를 추출하고 소문자화
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }

                // 3. 언어 목록 (languages CSV) 추출 및 저장 (무조건 대문자)
                List<String> normalizedLanguagesForCsv = rawLanguages.stream()
                        .map(s -> normalizeLanguageCode(s).toUpperCase()) // 코드를 추출하고 대문자화
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();

                if (!normalizedLanguagesForCsv.isEmpty()) {
                    String userLanguagesCsv = String.join(",", normalizedLanguagesForCsv);
                    user.updateLanguage(userLanguagesCsv);
                }
            }
        }

        if (dto.hobby() != null && !dto.hobby().isEmpty()) {
            String csv = String.join(",", dto.hobby());
            user.updateHobby(csv);
        }

        user.updateIsNewUser(false);
        if (dto.imageKey() != null) {
            imageService.saveUserProfileImage(user.getId(), dto.imageKey());
        }
    }
    /**
     * DTO의 필수 필드가 모두 채워졌는지 검사하는 메서드
     */
    private boolean isAllProfileFieldsFilled(UserSetupRequest dto) {
        return notBlank(dto.firstname()) &&
                notBlank(dto.lastname()) &&
                notBlank(dto.gender()) &&
                notBlank(dto.birthday()) &&
                notBlank(dto.country()) &&
                notBlank(dto.introduction()) &&
                notBlank(dto.purpose()) &&
                notBlank(dto.imageKey()) && // 이미지도 필수
                dto.language() != null && !dto.language().isEmpty() && // 언어도 1개 이상
                dto.hobby() != null && !dto.hobby().isEmpty(); // 취미도 1개 이상
    }
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("templates/email")
                : auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String profileKey = imageService.getUserProfileKey(user.getId());

        return new UserProfileResponse(user, stringToList(user.getTranslateLanguage()), stringToList(user.getHobby()), profileKey);
    }
    private String normalizeLanguageCode(String rawLang) {
        if (rawLang == null) return "";

        String normalized = rawLang.toLowerCase().trim();

        // 1. 정규식 패턴 기반 추출 (예: 'abkhaz [ab]' -> 'ab')
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 2. 대시(-) 처리 (예: 'fr-fr' -> 'fr')
        if (normalized.contains("-")) {
            return normalized.split("-")[0].trim();
        }

        // 3. 단순 코드인 경우 (예: 'ko', 'en')
        // 코드 길이가 2~5자인 경우 (예: zh-CN)는 그대로 반환
        if (normalized.length() >= 2 && normalized.length() <= 5) {
            return normalized;
        }

        return ""; // 그 외 알 수 없는 포맷은 무시
    }
    @Transactional
    public void deleteProfileImage() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("templates/email")
                : auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        imageService.deleteUserProfileImage(user.getId());
    }

    @Transactional
    public LoginResponseDto signup(SignupRequest req) {
        if (!req.isAgreedToTerms()) {
            throw new BusinessException(UserErrorCode.AGREEMENT_INPUT);
        }

        String email = normalizeEmail(req.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_RESOURCE);
        }

        String verified = redisTemplate.opsForValue().get(EMAIL_VERIFIED_FLAG_KEY + email);
        if (!"1".equals(verified)) {
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        String rawPw = req.getPassword();

        User u = new User();
        u.updateProvider(Ouathplatform.local.toString());
        u.updateSocialId(buildLocalSocialId(email));
        u.updateEmail(email);
        u.updatePassword(passwordEncoder.encode(rawPw));
        u.updateIsNewUser(true);
        u.updateAgreedToTerms(req.isAgreedToTerms());
        u.updateAgreedToPushNotification(false);

        Instant now = Instant.now();
        u.updateCreatedAt(now);
        u.updateUpdatedAt(now);
        u.changeUserRole(Role.VISITOR);

        userRepository.save(u);

        redisTemplate.delete(EMAIL_VERIFIED_FLAG_KEY + email);

        String accessToken = jwtTokenProvider.createAccessToken(u.getId(), u.getUserRole().toString(), u.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(u.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(u.getId(), refreshToken, expirationMillis);

        publisher.publishEvent(new UserLoggedInEvent(u.getId().toString(), "local"));
        return new LoginResponseDto(u.getId(), accessToken, refreshToken, u.isNewUser());
    }


    private String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String buildLocalSocialId(String email) {
        return "local:" + sha256Hex(email);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 일반 회원 가입 로직
     */
    @Transactional(readOnly = true)
    public AuthResponse login(EmailLoginDto req) {
        String email = normalizeEmail(req.getEmail());

        User u = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[LOGIN] 사용자 없음: email={}", email);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        log.debug("[LOGIN] 사용자 조회 성공: id={}, provider={}", u.getId(), u.getProvider());

        if (!Ouathplatform.local.toString().equalsIgnoreCase(nullToEmpty(u.getProvider()))) {
            log.warn("[LOGIN] provider 불일치: provider={}", u.getProvider());
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (u.getPassword() == null || !passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            log.warn("[LOGIN] 비밀번호 불일치: email={}", email);
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        String access = jwtTokenProvider.createAccessToken(u.getId(), u.getUserRole().name(), u.getEmail());
        String refresh = jwtTokenProvider.createRefreshToken(u.getId());
        long expiresInMs = jwtTokenProvider.getExpiration(access).getTime() - System.currentTimeMillis();
        Date refreshExpiration = jwtTokenProvider.getExpiration(refresh);
        long refreshExpirationMillis = refreshExpiration.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(u.getId(), refresh, refreshExpirationMillis);
        log.info("[LOGIN] 로그인 성공: id={}, email={}, expiresInMs={}", u.getId(), u.getEmail(), expiresInMs);

        publisher.publishEvent(new UserLoggedInEvent(u.getId().toString(), "email"));

        return new AuthResponse("Bearer", access, refresh, expiresInMs, u.getId(), u.getEmail(), u.isNewUser());
    }

    @Transactional
    public AuthResponse adminLogin(EmailLoginDto req) {

        String email = normalizeEmail(req.getEmail());

        User u = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[ADMIN LOGIN] 사용자 없음: email={}", email);
                    return new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
                });

        log.debug("[ADMIN LOGIN] 사용자 조회 성공: id={}, provider={}", u.getId(), u.getProvider());

        if (!Ouathplatform.local.toString().equalsIgnoreCase(nullToEmpty(u.getProvider()))) {
            log.warn("[ADMIN LOGIN] provider 불일치: provider={}", u.getProvider());
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (u.getPassword() == null || !passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            log.warn("[ADMIN LOGIN] 비밀번호 불일치: email={}", email);
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (u.getUserRole() != Role.ADMIN) {
            log.warn("[ADMIN LOGIN] 관리자 계정이 아님: id={}, role={}", u.getId(), u.getUserRole());
            throw new BusinessException(AuthErrorCode.AUTHENTICATION_ADMIN_FAILED);
        }

        String access = jwtTokenProvider.createAccessToken(u.getId(), u.getUserRole().name(), u.getEmail());
        String refresh = jwtTokenProvider.createRefreshToken(u.getId());
        long expiresInMs = jwtTokenProvider.getExpiration(access).getTime() - System.currentTimeMillis();
        Date refreshExpiration = jwtTokenProvider.getExpiration(refresh);
        long refreshExpirationMillis = refreshExpiration.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(u.getId(), refresh, refreshExpirationMillis);

        log.info("[ADMIN LOGIN] 관리자 로그인 성공: id={}, email={}", u.getId(), u.getEmail());
        publisher.publishEvent(new UserLoggedInEvent(u.getId().toString(), "email"));

        return new AuthResponse("Bearer", access, refresh, expiresInMs, u.getId(), u.getEmail(), u.isNewUser());
    }

    /**
     * 이메일 보내주는 로직
     */
    public void sendEmailVerificationCode(String rawEmail, Locale locale) {
        String email = normalizeEmail(rawEmail);

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_RESOURCE);
        }

        Duration ttl = Duration.ofMinutes(CODE_TTL_MIN);

        String verificationCode = smtpService.sendVerificationEmail(
                email,
                ttl,
                locale
        );
        String redisKey = EMAIL_VERIFY_CODE_KEY + email;
        log.info(">>> [Redis Save] Key: [{}], Code: [{}], TTL: {} min", redisKey, verificationCode, CODE_TTL_MIN);
        redisTemplate.opsForValue().set(
                EMAIL_VERIFY_CODE_KEY + email,
                verificationCode,
                CODE_TTL_MIN,
                TimeUnit.MINUTES
        );
    }


    /**
     * 이메일 인증 코드 검증 (5회 이상 실패 시 재발급 필요)
     */
    public boolean verifyEmailCode(EmailVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        String verificationCode = request.getVerificationCode();

        String codeKey = EMAIL_VERIFY_CODE_KEY + email;
        String attemptKey = EMAIL_VERIFY_ATTEMPT_KEY + email;

        // Redis에서 코드 조회
        String storedCode = redisTemplate.opsForValue().get(codeKey);

        if (storedCode == null) {
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_EXPIRES);
        }

        // 틀린 경우 처리
        if (!storedCode.equals(verificationCode)) {

            // 실패 횟수 증가
            Long attempt = redisTemplate.opsForValue().increment(attemptKey);

            // 실패 카운트 TTL 설정(없으면 기본 10분, 코드 TTL과 같게)
            redisTemplate.expire(attemptKey, VERIFIED_TTL_MIN, TimeUnit.MINUTES);

            // 5회 이상이면 재발급 필요
            if (attempt != null && attempt >= 5) {
                // 인증 코드 삭제
                redisTemplate.delete(codeKey);
                redisTemplate.delete(attemptKey);

                throw new BusinessException(AuthErrorCode.VERIFY_CODE_NEED_RESEND);
            }

            // 5회 미만이면 일반적인 "코드 불일치"
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_NOT_MATCH);
        }

        // ★ 성공한 경우: 코드 및 시도 횟수 삭제
        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptKey);

        // 인증 완료 플래그 저장
        String flagKey = EMAIL_VERIFIED_FLAG_KEY + email;
        redisTemplate.opsForValue().set(
                flagKey,
                "1",
                VERIFIED_TTL_MIN,
                TimeUnit.MINUTES
        );

        log.info(">>> [Redis Save Flag] 인증 완료 도장 저장 성공! Key: [{}], TTL: {} min", flagKey, VERIFIED_TTL_MIN);

        return true;
    }

    /**
     * 쉼표로 구분된 문자열을 List<String>으로 변환
     */
    private List<String> stringToList(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Transactional
    public ProfileEditResponseDto updateUserProfile(UserProfileEditDto dto) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Role oldRole = user.getUserRole();

        if (notBlank(dto.firstname())) user.updateFirstName(dto.firstname().trim());
        if (notBlank(dto.lastname())) user.updateLastName(dto.lastname().trim());
        if (dto.gender() != null) user.updateGender(dto.gender());
        if (dto.birthday() != null) user.updateBirthdate(dto.birthday());
        if (notBlank(dto.country())) user.updateCountry(dto.country().trim());

        if (notBlank(dto.introduction())) {
            String v = dto.introduction().trim();
            user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v);
        }
        if (notBlank(dto.purpose())) {
            user.updatePurpose(dto.purpose());
        }

// UserLanguageDTO dto를 받는 메서드 내부 (updateUserLanguage 로직)

        if (dto.language() != null && !dto.language().isEmpty()) {

            // 1. 초기 정제: null, 공백 제거 및 trim만 수행. (대소문자/포맷은 유지)
            List<String> rawLanguages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!rawLanguages.isEmpty()) {

                // 2. 번역 언어 (translate_language) 추출 및 저장 (무조건 소문자)
                String firstTranslatedLanguage = rawLanguages.stream()
                        .findFirst() // 첫 번째 언어를 선택
                        .map(s -> normalizeLanguageCode(s).toLowerCase()) // 코드를 추출하고 소문자화
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }

                // 3. 언어 목록 (languages CSV) 추출 및 저장 (무조건 대문자)
                List<String> normalizedLanguagesForCsv = rawLanguages.stream()
                        .map(s -> normalizeLanguageCode(s).toUpperCase()) // 코드를 추출하고 대문자화
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();

                if (!normalizedLanguagesForCsv.isEmpty()) {
                    String userLanguagesCsv = String.join(",", normalizedLanguagesForCsv);
                    user.updateLanguage(userLanguagesCsv);
                }
            }
        }

        if (dto.hobby() != null) {
            String csv = dto.hobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.updateHobby(csv);
        }

        String finalImageKey = imageService.getUserProfileKey(user.getId());
        if (notBlank(dto.imageKey())) {
            finalImageKey = imageService.updateUserProfileImage(user.getId(), dto.imageKey());
        }

        Role newRole = user.getUserRole();

        ProfileEditResponseDto responseDto = new ProfileEditResponseDto(
                user,
                stringToList(user.getLanguage()),
                stringToList(user.getHobby()),
                finalImageKey
        );

        if (oldRole == Role.VISITOR && newRole == Role.USER) {

            String accessToken = jwtTokenProvider.createAccessToken(
                    user.getId(),
                    user.getUserRole().name(),
                    user.getEmail()
            );

            redisService.deleteRefreshToken(user.getId());
            String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
            long ttlMs = jwtTokenProvider.getExpiration(refreshToken).getTime() - System.currentTimeMillis();
            redisService.saveRefreshToken(user.getId(), refreshToken, ttlMs);
            responseDto.setNewTokens(accessToken, refreshToken);
        }
        return responseDto;
    }

    @Transactional
    public void updateSkipUserSetup(UserUpdateDto dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (dto.birthday() != null) user.updateBirthdate(dto.birthday());
        if (notBlank(dto.country())) user.updateCountry(dto.country().trim());

        if (notBlank(dto.introduction())) {
            String v = dto.introduction().trim();
            user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v); // 컬럼 길이 보호
        }
        if (notBlank(dto.purpose())) {
            user.updatePurpose(dto.purpose());
        }

        if (dto.language() != null && !dto.language().isEmpty()) {
            List<String> languages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!languages.isEmpty()) {
                // CSV 형태로 저장
                String userLanguagesCsv = String.join(",", languages);
                user.updateLanguage(userLanguagesCsv);

                // 첫 번째 요소에서 번역 코드 추출
                String firstTranslatedLanguage = languages.stream()
                        .map(s -> {
                            Matcher matcher = pattern.matcher(s);
                            return matcher.find() ? matcher.group(1).trim() : "";
                        })
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }
            }
        }

        if (dto.hobby() != null) {
            String csv = dto.hobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.updateHobby(csv);
        }

        if (notBlank(dto.imageKey())) {
            imageService.updateUserProfileImage(user.getId(), dto.imageKey());
        }
        NewUserJoinedEvent event = new NewUserJoinedEvent(user.getId());
        eventPublisher.publishEvent(event);
    }


    /**
     * 회원 탈퇴를 처리하는 메서드.
     * 사용자와 관련된 모든 데이터를 삭제하고, 토큰을 무효화합니다.
     *
     * @param userId      탈퇴할 사용자의 ID
     * @param accessToken 블랙리스트에 추가할 사용자의 Access Token
     */
    /**
     * 회원 탈퇴 메인 메소드 (Orchestrator)
     */
    @Transactional
    public boolean withdrawUser(Long userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        boolean isApple = false;

        if (Ouathplatform.APPLE.toString().equals(user.getProvider())) {
            appleWithdrawalService.revokeAppleToken(user);
            isApple = true;
        }

        cleanupUserData(user); // DB 작업들

        eventPublisher.publishEvent(new UserWithdrawalEvent(userId, accessToken));

        return isApple;
    }


    /**
     * 사용자와 관련된 모든 DB 데이터를 삭제하는 private 메소드
     */
    private void cleanupUserData(User user) {
        Long userId = user.getId();
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);

        for (ChatRoom chatRoom : ownedChatRooms) {
            List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);

            if (!participants.isEmpty()) {
                User newOwner = participants.get(0).getUser();
                chatRoom.changeOwner(newOwner);
                chatRoomRepository.save(chatRoom);
            } else {
                chatRoomRepository.delete(chatRoom);
            }
        }
        blockPostRepository.deleteAllBlockPostsRelatedToUser(userId);
        List<Post> userPosts = postRepository.findAllByAuthorId(userId);
        if (userPosts != null && !userPosts.isEmpty()) {
            commentRepository.deleteAllByPostIn(userPosts);
            bookmarkRepository.deleteAllByPostIn(userPosts);
            postRepository.deleteAll(userPosts);
        }

        commentRepository.deleteAllByAuthorId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        followRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);
        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        imageService.deleteUserProfileImage(userId);
        blockRepository.deleteAllByUserOrBlocked(user);
        chatParticipantRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllBySenderId(userId);
        userNotificationSettingRepository.deleteAllByUserId(userId);
        userDeviceTokenRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByActorId(userId);
        userFeedbackRepository.deleteAllByUserIdExplicit(userId);
        userRepository.delete(user);
    }

    /**
     * 단일 사용자 정보 조회 로직
     */
    public UserProfileResponse findUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));


        String profileKey = imageService.getUserProfileKey(user.getId());

        return new UserProfileResponse(user, stringToList(user.getTranslateLanguage()), stringToList(user.getHobby()), profileKey);
    }

    public UserProfileCardResponse findCardUserProfile(Long userId, Long currentUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String profileKey = imageService.getUserProfileKey(user.getId());
        String followStatus;
        if (userId.equals(currentUserId)) {
            followStatus = "SELF";
        } else {
            Optional<Follow> follow = followRepository.findByUser_IdAndFollowing_Id(currentUserId, userId);
            if (follow.isPresent()) {
                followStatus = follow.get().getStatus().toString();
            } else {
                followStatus = "NOT_FOLLOWING";
            }
        }

        return new UserProfileCardResponse(
                user,
                stringToList(user.getTranslateLanguage()),
                stringToList(user.getHobby()),
                profileKey,
                followStatus
        );
    }

    /**
     * 여러 사용자 정보 일괄 조회 로직 (N+1 문제 해결)
     */
    public List<UserResponseDto> findUsersProfiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = userRepository.findAllById(userIds);
        List<Long> foundUserIds = users.stream().map(User::getId).toList();
        Map<Long, String> imageUrlsMap = imageRepository
                .findAllPrimaryImagesForUsers(ImageType.USER, foundUserIds)
                .stream()
                .collect(Collectors.toMap(Image::getRelatedId, Image::getUrl, (first, second) -> first));
        return users.stream()
                .map(user -> {
                    String imageUrl = imageUrlsMap.get(user.getId());
                    return UserResponseDto.from(user, imageUrl);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChatUserProfileResponse getUserChatProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Image image = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .orElseThrow(() -> new BusinessException(ImageErrorCode.IMAGE_NOT_FOUND));

        return ChatUserProfileResponse.from(user, image.getUrl());
    }

    /**
     * 사용자의 애플 계정 상태를 확인하는 메서드
     *
     * @param userId 확인할 사용자의 ID
     * @return UserAppleStatusResponse 사용자의 애플 계정 상태 정보
     */
    @Transactional(readOnly = true)
    public UserAppleStatusResponse checkUserAppleStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        boolean isApple = Ouathplatform.APPLE.toString().equals(user.getProvider());

        boolean isRejoiningWithoutFullName = false;
        if (isApple) {
            isRejoiningWithoutFullName = (user.getFirstName() == null || user.getFirstName().isBlank());
        }

        return new UserAppleStatusResponse(isApple, isRejoiningWithoutFullName);
    }


    @Transactional
    public void updateUserCountry(Long userId, String country) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.updateCountry(country);
    }

    @Transactional
    public void updateUserResidence(Long userId, String residence) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        user.updateResidence(residence);
    }

    /**
     * 유저 프로필 완료 여부 확인
     *
     * @param userId 확인할 유저 ID
     * @return 프로필이 완료되었으면 true, 아니면 false
     */
    public ProfileCompletionResponse checkProfileCompletion(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        boolean completed = user.getBirthdate() != null
                            && user.getPurpose() != null
                            && user.getIntroduction() != null
                            && user.getLanguage() != null
                            && user.getHobby() != null
                            && user.getSex() != null;
        return new ProfileCompletionResponse(userId, completed);
    }


}