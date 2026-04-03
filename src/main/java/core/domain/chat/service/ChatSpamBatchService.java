package core.domain.chat.service;
import core.domain.admin.service.PerspectiveService;
import core.domain.chat.dto.ChatReportRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.service.ChatMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSpamBatchService {

    private final ChatMessageRepository chatMessageRepository;
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;

    /**
     * 10분마다 실행 (0분, 10분, 20분...)
     * 최근 10분간 쌓인 링크 메시지를 모아서 스팸 검사
     */
    @Scheduled(cron = "0 0/10 * * * *")
    public void processSpamBatch() {
        log.info("🔍 [스팸 배치 작업 시작] 최근 10분간의 링크 메시지를 검사합니다.");

        LocalDateTime targetTime = LocalDateTime.now().minusMinutes(10);
        List<ChatMessage> recentLinkMessages = chatMessageRepository.findRecentMessagesWithLinks(targetTime);

        if (recentLinkMessages.isEmpty()) {
            log.info("✅ 검사할 링크 메시지가 없습니다. 배치 종료.");
            return;
        }

        Map<String, List<ChatMessage>> groupedByContent = recentLinkMessages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getContent));

        int totalSpamDetected = 0;

        for (Map.Entry<String, List<ChatMessage>> entry : groupedByContent.entrySet()) {
            String content = entry.getKey();
            List<ChatMessage> sameMessages = entry.getValue();

            try {
                if (perspectiveService.isHarmful(content)) {
                    for (ChatMessage msg : sameMessages) {
                        ChatReportRequest reportRequest = new ChatReportRequest(
                                msg.getId(), "AI_DETECTED_SPAM", "Perspective API 배치 감지"
                        );
                        chatMemberService.reportChat(null, reportRequest);
                        totalSpamDetected++;
                    }
                }
            } catch (Exception e) {
                log.error("❌ 배치 스팸 검사 중 오류 (내용: {}): {}", content, e.getMessage());
            }
        }

        log.info("🏁 [스팸 배치 작업 완료] 총 {}건의 스팸 메시지를 적발 및 신고했습니다.", totalSpamDetected);
    }
}