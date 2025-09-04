package core.global.service;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.ResetToken;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final SmtpMailService smtpService;
    private final PasswordEncoder passwordEncoder;
    private static final String EMAIL_VERIFY_CODE_KEY = "email_verification:code:";     // code 보관
    private static final long CODE_TTL_MIN = 3L;      // 분
    /**
     *이메일 보내주는 로직
     */
    public void sendEmailVerificationCode(String rawEmail, Locale locale) {
        String email = normalizeEmail(rawEmail);

        Duration ttl = Duration.ofMinutes(CODE_TTL_MIN);

        log.info("이메일 보내주는 로직"+String.valueOf(locale));
        String verificationCode = smtpService.sendVerificationEmail(
                email,
                ttl,
                locale   // 여기서 앱이 보낸 언어 사용
        );

        redisTemplate.opsForValue().set(
                EMAIL_VERIFY_CODE_KEY + email,
                verificationCode,
                CODE_TTL_MIN,
                TimeUnit.MINUTES
        );
    }
    @Transactional
    public void verifyCodeAndResetPassword(String rawEmail, String code, String newPassword) {
        String email = normalizeEmail(rawEmail);

        // 1) DB에 해당 유저 존재하는지 확인
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 2) Redis에서 인증코드 꺼내오기
        String storedCode = redisTemplate.opsForValue().get(EMAIL_VERIFY_CODE_KEY + email);
        if (storedCode == null || !storedCode.equals(code)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED); // 잘못된 코드
        }

        // 3) 유저 비밀번호 갱신
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 4) 인증코드 소비 후 삭제
        redisTemplate.delete(EMAIL_VERIFY_CODE_KEY + email);

        log.info("비밀번호 재설정 완료: {}", email);
    }


    private String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

}
