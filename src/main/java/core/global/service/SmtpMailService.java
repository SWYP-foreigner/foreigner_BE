package core.global.service;


import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;
    @Value("${app.mail.from}")
    private String from;

    @Value("${app.mail.brand:Your App}")
    private String brand;

    /**
     * 6자리 숫자 인증코드 생성 + 메일 전송 후 코드 반환
     */
    public String sendVerificationEmail(String toEmail) {
        String code = generateCode();
        String subject = String.format("[%s] 이메일 인증코드", brand);
        String html = """
                <div style="font-family:system-ui,Apple SD Gothic Neo,apple-system,sans-serif;line-height:1.6">
                  <h2>%s 이메일 인증</h2>
                  <p>아래 인증코드를 입력해 주세요. 유효시간은 3분입니다.</p>
                  <div style="font-size:24px;font-weight:700;letter-spacing:4px;margin:16px 0">%s</div>
                  <p>만약 본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.</p>
                </div>
                """.formatted(brand, code);

        sendHtml(toEmail, subject, html);
        return code;
    }

    private void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage mm = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mm, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(mm);
        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error("Failed to send mail to {}: {}", to, e.getMessage(), e);
            throw new RuntimeException("MAIL_SEND_FAILED");
        }
    }

    private String generateCode() {
        int n = ThreadLocalRandom.current().nextInt(100000, 1000000); // 6 digits
        return String.valueOf(n);
    }
}
