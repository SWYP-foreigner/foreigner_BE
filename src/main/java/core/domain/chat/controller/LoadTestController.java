package core.domain.chat.controller;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class LoadTestController {

    private final ChatMessageService chatService;

    // k6가 때릴 HTTP 엔드포인트
    @PostMapping("/test/load/send")
    public ResponseEntity<String> sendTestMessage(@RequestBody SendMessageRequest req) {
        long start = System.currentTimeMillis();

        // 문제의 그 메서드 호출
        chatService.sendBroadCastMessageAntiPattern(req);

        long end = System.currentTimeMillis();
        long duration = end - start;

        return ResponseEntity.ok("Processing Time: " + duration + "ms");
    }
}