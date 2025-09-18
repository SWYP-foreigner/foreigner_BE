package core.global.service;

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
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailService {

    private final MessageSource messageSource;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine; // ✅ 타임리프 템플릿 엔진 주입

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
        sendHtml(toEmail, subject, html);

        log.info("인증 메일 발송 완료: {} (코드: {})", toEmail, code);
        return code;
    }

    public void sendPasswordResetSessionEmail(String toEmail,
                                              Duration sessionTtl,
                                              Locale locale,
                                              String startUrl) {

        // 제목 다국어 처리
        String subject = messageSource.getMessage(
                "email.reset.subject",  // messages_xx.properties 키
                new Object[]{defaultBrand},
                locale
        );

        // 템플릿 변수 세팅
        Context ctx = new Context(locale);
        ctx.setVariable("brand", defaultBrand);
        ctx.setVariable("startUrl", startUrl);
        ctx.setVariable("ttlMinutes", sessionTtl.toMinutes());

        // reset_session.html 템플릿 처리
        String html = templateEngine.process("email/reset-password", ctx);

        // 메일 전송
        sendHtml(toEmail, subject, html);
    }

    /** 메시지 필수: 키 없으면 예외 */
    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }



    private void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage mm = mailSender.createMimeMessage();

            // Helper가 멀티파트/인코딩을 모두 세팅합니다.
            MimeMessageHelper helper = new MimeMessageHelper(
                    mm,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);   // UTF-8로 인코딩됨
            helper.setText(html, true);   // HTML 본문


            mailSender.send(mm);
        } catch (MailException | MessagingException e) {
            log.error("Failed to send mail to {}: {}", to, e.getMessage(), e);
            throw new RuntimeException("MAIL_SEND_FAILED", e);
        }
    }

    public void sendPasswordResetEmail(String toEmail, Duration ttl, Locale locale, String resetLink) {
        Locale loc = (locale != null) ? locale
                : (LocaleContextHolder.getLocale() != null ? LocaleContextHolder.getLocale() : Locale.getDefault());

        String brand = "KoriApp";
        String subject = messageSource.getMessage("password.reset.subject", new Object[]{brand}, loc);

        Context ctx = new Context(loc);
        ctx.setVariable("brand", brand);
        ctx.setVariable("ttlMinutes", ttl.toMinutes());
        ctx.setVariable("resetLink", resetLink);

        String html = templateEngine.process("email/reset-password", ctx);
        sendHtml(toEmail, subject, html);
    }

    private String generateCode() {
        // 6자리 숫자: 100000 ~ 999999
        int n = ThreadLocalRandom.current().nextInt(100000, 1_000_000);
        return Integer.toString(n);
    }
}
