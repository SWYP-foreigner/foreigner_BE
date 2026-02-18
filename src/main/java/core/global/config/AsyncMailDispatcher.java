package core.global.config;

import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncMailDispatcher {

    private final JavaMailSender mailSender;
    private final Semaphore smtpGate = new Semaphore(1);
    @PostConstruct // 의존성 주입이 완료된 후 실행됨
    public void checkConfig() {
        if (mailSender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl) {
            log.info("📧 [MAIL CONFIG] Host: {}", impl.getHost());
            log.info("📧 [MAIL CONFIG] User: {}", impl.getUsername());
            log.info("📧 [MAIL CONFIG] Port: {}", impl.getPort());
        }
    }
    @Async("mailExecutor")
    @Retry(name = "mailSend")
    public void sendHtmlAsync(String from, String to, String subject, String html) {
        // 🔍 전달받은 값 최종 확인
        log.info("[DEBUG-MAIL] Dispatcher 실행 시작 - received from: '{}', to: '{}'", from, to);

        try {
            smtpGate.acquire();

            // 🚨 null이나 공백일 경우 미리 에러 로그 출력
            if (from == null || from.trim().isEmpty()) {
                log.error("[DEBUG-MAIL] 🚨 CRITICAL: from 주소가 비어있습니다! Illegal address 에러가 발생할 예정입니다.");
            }

            MimeMessage mm = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mm,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );

            log.info("[DEBUG-MAIL] helper.setFrom('{}') 시도 중...", from);
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(mm);
            log.info("✅ [DEBUG-MAIL] 메일 전송 성공!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MAIL_SEND_INTERRUPTED", e);
        } catch (MailException | MessagingException e) {
            log.error("❌ [DEBUG-MAIL] 메일 전송 중 실제 예외 발생: {}", e.getMessage(), e);
            throw new RuntimeException("MAIL_SEND_FAILED", e);
        } finally {
            smtpGate.release();
        }
    }
}