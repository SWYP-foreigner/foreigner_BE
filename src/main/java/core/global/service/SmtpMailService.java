package core.global.service;

import core.global.config.AsyncMailDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailService {

    private final MessageSource messageSource;
    private final TemplateEngine templateEngine;
    private final AsyncMailDispatcher asyncMailDispatcher;

    @Value("${app.mail.from}")
    private String from; // 발신 주소

    @Value("${app.mail.brand}")
    private String defaultBrand;

    public void sendAdminOtpResetEmail(String toEmail, String code, Duration ttl) {
        log.info("[DEBUG-MAIL] AdminOtp - from(YAML): '{}', to: '{}'", from, toEmail);

        String subject = "[Kori] 관리자 OTP 초기화 인증번호입니다.";
        Locale locale = Locale.KOREA;

        Context ctx = new Context(locale);
        ctx.setVariable("brand", defaultBrand);
        ctx.setVariable("code", code);
        ctx.setVariable("ttlMinutes", ttl.toMinutes());

        String html = templateEngine.process("email/verification", ctx);

        asyncMailDispatcher.sendHtmlAsync(from, toEmail, subject, html);
        log.info("[Admin OTP] 초기화 메일 발송 요청 완료");
    }

    public String sendVerificationEmail(String toEmail, Duration ttl, Locale locale) {
        // 🔍 주입된 from 값 로그 찍기
        log.info("[DEBUG-MAIL] Verification - from(YAML): '{}', to: '{}'", from, toEmail);

        String code = generateCode();

        String subject = messageSource.getMessage(
                "password.reset.subject",
                new Object[]{defaultBrand},
                locale
        );

        Context ctx = new Context(locale);
        ctx.setVariable("brand", defaultBrand);
        ctx.setVariable("code", code);
        ctx.setVariable("ttlMinutes", ttl.toMinutes());

        String html = templateEngine.process("email/verification", ctx);

        // 🔍 Dispatcher로 넘기기 직전의 값 확인
        log.info("[DEBUG-MAIL] Dispatcher 호출 직전 from 파라미터 확인: '{}'", from);
        asyncMailDispatcher.sendHtmlAsync(from, toEmail, subject, html);

        log.info("인증 메일 발송 완료: {} (코드: {})", toEmail, code);
        return code;
    }

    private String generateCode() {
        int n = ThreadLocalRandom.current().nextInt(100000, 1_000_000);
        return Integer.toString(n);
    }
}