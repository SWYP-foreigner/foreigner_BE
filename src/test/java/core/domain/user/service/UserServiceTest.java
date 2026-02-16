package core.domain.user.service;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.repository.NotificationRepository;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.ProfileCompletionResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.apple.service.AppleWithdrawalService;
import core.global.dto.*;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.Oauthplatform;
import core.global.enums.user.Role;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.redis.service.RedisService;
import core.global.security.JwtTokenProvider;
import core.global.service.SmtpMailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    BlockPostRepository blockPostRepository;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    BlockRepository blockRepository;
    @Mock
    SmtpMailService smtpService;
    @Mock
    RedisTemplate<String, String> redisTemplate;
    @Mock UserRepository userRepository;
    @Mock
    ImageService imageService;
    @Mock
    RedisService redisService;
    @Mock
    JwtTokenProvider jwtTokenProvider;
    @Mock
    CommentRepository commentRepository;
    @Mock
    BookmarkRepository bookmarkRepository;
    @Mock
    ChatMessageRepository chatMessageRepository;
    @Mock
    ChatParticipantRepository chatParticipantRepository;
    @Mock
    PostRepository postRepository;
    @Mock
    ImageRepository imageRepository;
    @Mock
    FollowRepository followRepository;
    @Mock
    LikeRepository likeRepository;
    @Mock
    AppleWithdrawalService appleWithdrawalService;
    @Mock
    ChatRoomRepository chatRoomRepository;
    @Mock
    UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock
    NotificationRepository notificationRepository;
    @Mock
    UserNotificationSettingRepository userNotificationSettingRepository;

    @Mock
    ValueOperations<String, String> valueOperations;

    @InjectMocks
    UserService userService;

    private User createUserWithAllProfileFilled() {
        User user = new User();
        user.updateBirthdate("1999-05-27");
        user.updatePurpose("study");
        user.updateIntroduction("안녕하세요, 자기소개입니다.");
        user.updateLanguage("ko,en");
        user.updateHobby("reading,coding");
        user.updateSex("MALE");
        user.updateCountry("KOREA");
        return user;
    }

    // hobby만 비어 있는 유저
    private User createUserWithMissingHobby() {
        User user = new User();
        user.updateBirthdate("1999-05-27");
        user.updatePurpose("study");
        user.updateIntroduction("안녕하세요, 자기소개입니다.");
        user.updateLanguage("ko,en");
        user.updateSex("MALE");
        user.updateCountry("KOREA");
        // hobby는 설정하지 않음
        return user;
    }

    @Test
    @DisplayName("checkProfileCompletion - 유저가 없으면 USER_NOT_FOUND 예외를 던진다")
    void checkProfileCompletion_shouldThrow_whenUserNotFound() {
        // given
        Long userId = 1L;
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.checkProfileCompletion(userId)
        );

        // then
        assertEquals(UserErrorCode.USER_NOT_FOUND, ex.getError());
    }

    @Test
    @DisplayName("checkProfileCompletion - 모든 필드가 채워져 있으면 completed=true 를 반환한다")
    void checkProfileCompletion_shouldReturnTrue_whenAllFieldsNotNull() {
        // given
        Long userId = 1L;
        User user = createUserWithAllProfileFilled();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // when
        ProfileCompletionResponse res = userService.checkProfileCompletion(userId);

        // then
        assertEquals(userId, res.userId());
        assertTrue(res.profileCompleted());
    }

    @Test
    @DisplayName("checkProfileCompletion - 필드 중 하나라도 null이면 completed=false 를 반환한다")
    void checkProfileCompletion_shouldReturnFalse_whenAnyFieldIsNull() {
        // given
        Long userId = 1L;
        User user = createUserWithMissingHobby();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // when
        ProfileCompletionResponse res = userService.checkProfileCompletion(userId);

        // then
        assertEquals(userId, res.userId());
        assertFalse(res.profileCompleted());
    }


    @Test
    @DisplayName("signup - 약관에 동의하지 않으면 AGREEMENT_INPUT 예외를 던진다")
    void signup_shouldThrow_whenTermsNotAgreed() {
        // given
        SignupRequest req = new SignupRequest();
        req.setEmail("test@email.com");
        req.setPassword("Abcd1234!");
        req.setAgreedToTerms(false);

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.signup(req)
        );

        // then
        assertEquals(UserErrorCode.AGREEMENT_INPUT, ex.getError());
        // 유저가 저장되면 안 됨
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup - email이 중복되면 DUPLICATE_RESOURCE 예외를 던진다")
    void signup_shouldThrow_whenEmailAlreadyExists() {
        // given
        SignupRequest req = new SignupRequest();
        req.setEmail("test@email.com");
        req.setPassword("Abcd1234!");
        req.setAgreedToTerms(true);

        when(userRepository.existsByEmail("test@email.com")).thenReturn(true);

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.signup(req)
        );

        // then
        assertEquals(UserErrorCode.DUPLICATE_RESOURCE, ex.getError());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup - email이 인증되지 않으면 AUTHENTICATION_FAILED 예외를 던진다")
    void signup_shouldThrow_whenEmailNotVerified() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // given
        SignupRequest req = new SignupRequest();
        req.setEmail("Test@Email.Com");
        req.setPassword("Abcd1234!");
        req.setAgreedToTerms(true);

        String normalizedEmail = "test@email.com";

        when(userRepository.existsByEmail(normalizedEmail))
                .thenReturn(false);
        when(valueOperations.get("email_verification:verified:" + normalizedEmail))
                .thenReturn(null);

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.signup(req)
        );


        // then
        assertEquals(UserErrorCode.AUTHENTICATION_FAILED, ex.getError());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup - 정상 회원 가입")
    void signup_shouldCreateUserAndTokens_whenValidRequest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // given
        SignupRequest req = new SignupRequest();
        req.setEmail("Test@Email.Com");
        req.setPassword("Abcd1234!");
        req.setAgreedToTerms(true);

        String normalizedEmail = "test@email.com";
        String rawPw = "Abcd1234!";

        when(userRepository.existsByEmail(normalizedEmail))
                .thenReturn(false);

        // 이메일 인증 완료 상태
        when(valueOperations.get("email_verification:verified:" + normalizedEmail))
                .thenReturn("1");

        when(passwordEncoder.encode(rawPw)).thenReturn("encodedPw");

        // save 시 User 반환 (id는 신경 안 쓰고 넘어가도 됨)
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);

                    try {
                        Field idField = User.class.getDeclaredField("id"); // 필드명이 진짜 "id"라고 가정
                        idField.setAccessible(true);
                        idField.set(user, 1L);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }

                    return user;
                });

        when(jwtTokenProvider.createAccessToken(any(), anyString(), anyString()))
                .thenReturn("accessToken");

        when(jwtTokenProvider.createRefreshToken(any()))
                .thenReturn("refreshToken");

        // refresh 토큰 만료시간
        Date refreshExp = new Date(System.currentTimeMillis() + 1000L * 60 * 60);
        when(jwtTokenProvider.getExpiration("refreshToken"))
                .thenReturn(refreshExp);

        // when
        LoginResponseDto res = userService.signup(req);

        // then: 응답 값 간단 체크
        assertNotNull(res);
        assertNotNull(res.accessToken());
        assertNotNull(res.refreshToken());
        assertTrue(res.isNewUser());

        // then: 부수효과 검증
        // 이메일 인증 플래그 삭제
        verify(redisTemplate).delete("email_verification:verified:" + normalizedEmail);

        // refresh 토큰 Redis 저장
        verify(redisService).saveRefreshToken(anyLong(), eq("refreshToken"), anyLong());

        // 로그인 이벤트 발행
        verify(eventPublisher).publishEvent(any(UserLoggedInEvent.class));
    }

    @Test
    @DisplayName("login - 유저가 존재하지 않으면 USER_NOT_FOUND 예외를 던진다")
    void login_shouldThrow_whenUserNotFound() {
        // given
        EmailLoginDto req = new EmailLoginDto();
        req.setEmail("test@email.com");
        req.setPassword("Test1234!");

        User user = new User();
        user.updateEmail("test@example.com");
        user.updateProvider(Oauthplatform.local.toString());
        user.updatePassword("encodedPw");

        when(userRepository.findByEmail(req.getEmail()))
                .thenReturn(Optional.empty());

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.login(req)
        );

        // then
        assertEquals(UserErrorCode.USER_NOT_FOUND, ex.getError());
    }

    @Test
    @DisplayName("login - provider가 local이 아니면 AUTHENTICATION_FAILED 예외를 던진다")
    void login_shouldThrow_whenProviderIsNotLocal() {
        // given
        EmailLoginDto req = new EmailLoginDto();
        req.setEmail("test@email.com");
        req.setPassword("Test1234!");

        // provider가 local이 아닌 유저
        User user = new User();
        user.updateEmail("test@email.com");
        user.updateProvider(Oauthplatform.APPLE.toString());
        user.updatePassword("encodedPw");

        when(userRepository.findByEmail("test@email.com"))
                .thenReturn(Optional.of(user));

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.login(req)
        );

        // then
        assertEquals(UserErrorCode.AUTHENTICATION_FAILED, ex.getError());
    }

    @Test
    @DisplayName("login - 비밀번호 불일치일 때 AUTHENTICATION_FAILED 예외를 던진다")
    void login_shouldThrow_whenPasswordNotMatch() {
        EmailLoginDto req = new EmailLoginDto();
        req.setEmail("test@email.com");
        req.setPassword("WrongPw!");

        // provider가 local이 아닌 유저
        User user = new User();
        user.updateEmail("test@email.com");
        user.updateProvider(Oauthplatform.local.toString());
        user.updatePassword("encodedPw");

        when(userRepository.findByEmail("test@email.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("WrongPw!", "encodedPw"))
                .thenReturn(false);

        // when
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> userService.login(req)
        );

        // then
        assertEquals(UserErrorCode.AUTHENTICATION_FAILED, ex.getError());
    }

    @Test
    @DisplayName("login - 정상 로그인")
    void login_shouldReturnTokensAndSaveRefreshToken_whenCredentialsValid() {
        // given
        EmailLoginDto req = new EmailLoginDto();
        req.setEmail("test@email.com");
        req.setPassword("CorrectPw1!");

        User user = new User();
        user.updateEmail("test@email.com");
        user.updateProvider(Oauthplatform.local.toString());
        user.updatePassword("encodedPw");
        user.changeUserRole(Role.USER);
        user.updateIsNewUser(false);

        setUserId(user, 1L);

        when(userRepository.findByEmail("test@email.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("CorrectPw1!", "encodedPw"))
                .thenReturn(true);

        when(jwtTokenProvider.createAccessToken(anyLong(), anyString(), anyString()))
                .thenReturn("accessToken");

        when(jwtTokenProvider.createRefreshToken(anyLong()))
                .thenReturn("refreshToken");

        // access 토큰 만료시간 (응답의 expiresInMs 계산용)
        Date accessExp = new Date(System.currentTimeMillis() + 1000L * 60 * 30);
        when(jwtTokenProvider.getExpiration("accessToken"))
                .thenReturn(accessExp);

        // refresh 토큰 만료시간 (Redis TTL 계산용)
        Date refreshExp = new Date(System.currentTimeMillis() + 1000L * 60 * 60);
        when(jwtTokenProvider.getExpiration("refreshToken"))
                .thenReturn(refreshExp);

        // when
        AuthResponse res = userService.login(req);

        // then: 응답 값 검증
        assertEquals("Bearer", res.tokenType());
        assertEquals("accessToken", res.accessToken());
        assertEquals("refreshToken", res.refreshToken());
        assertEquals("test@email.com", res.email());
        assertFalse(res.isNewUser());


        // then: 부수효과 검증
        verify(redisService).saveRefreshToken(anyLong(), eq("refreshToken"), anyLong());
        verify(eventPublisher).publishEvent(any(UserLoggedInEvent.class));
    }




    private void setUserId(User user, Long id) {
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}