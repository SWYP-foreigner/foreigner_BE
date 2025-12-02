package core.global.service;

import core.global.config.AsyncMailDispatcher;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailService {

    private final MessageSource messageSource;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine; // ✅ 타임리프 템플릿 엔진 주입
    private final AsyncMailDispatcher asyncMailDispatcher;

    @Value("${app.mail.from}")
    private String from; // 발신 주소

    @Value("${app.mail.brand}")
    private String defaultBrand; // 번들에 brand.name 없을 때 기본값

    public String sendVerificationEmail(String toEmail, Duration ttl, Locale locale) {
        // 1) 코드 생성
        String code = generateCode();

        // 2) 제목 (다국어 처리)
        String subject = messageSource.getMessage(
                "password.reset.subject",        // messages_xx.properties에 정의된 키
                new Object[]{defaultBrand},       // 파라미터
                locale
        );

        // 3) 본문 (타임리프 템플릿 사용)
        Context ctx = new Context(locale);
        ctx.setVariable("brand", defaultBrand);
        ctx.setVariable("code", code);
        ctx.setVariable("ttlMinutes", ttl.toMinutes());

        String html = templateEngine.process("email/verification", ctx);

        // 4) 메일 발송
        asyncMailDispatcher.sendHtmlAsync(from, toEmail, subject, html);

        log.info("인증 메일 발송 완료: {} (코드: {})", toEmail, code);
        return code;
    }


    private String generateCode() {
        // 6자리 숫자: 100000 ~ 999999
        int n = ThreadLocalRandom.current().nextInt(100000, 1_000_000);
        return Integer.toString(n);
    }
}
