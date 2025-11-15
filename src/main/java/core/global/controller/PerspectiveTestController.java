package core.global.controller;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.service.ChatService;
import core.global.exception.BusinessException;
import core.global.service.PerspectiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/test") // 관리자용 테스트 경로
@RequiredArgsConstructor
public class PerspectiveTestController {

    private final PerspectiveService perspectiveService;
    private final ChatService chatService;

    /**
     * Perspective API 유해성 검사를 테스트합니다.
     * 예: /admin/test/perspective?text=안녕하세요
     * 예: /admin/test/perspective?text=you are an idiot
     * 예: /admin/test/perspective?text=Visit my site: example.com
     */
    @GetMapping("/perspective")
    public ResponseEntity<String> testPerspectiveApi(
            @RequestParam(name = "text") String textToTest
    ) {
        // 1. PerspectiveService의 isHarmful 메서드를 호출합니다.
        boolean isHarmful = perspectiveService.isHarmful(textToTest);

        // 2. 결과를 문자열로 포맷팅합니다.
        String result = String.format(
                "<b>Text:</b> \"%s\" <br><br><b>Is Harmful (SPAM >= 0.9 or TOXICITY >= 0.8):</b> %s",
                textToTest,
                isHarmful
        );

        // 3. HTML로 결과를 반환합니다.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(result);
    }

    @PostMapping("/send-message")
    public ResponseEntity<String> testSpamMessage(
            @RequestBody SendMessageRequest request
    ) {
        try {
            // 2. ChatService의 메시지 처리 메서드 호출
            chatService.processAndSendChatMessage(request);

            // 3. AI 검사 여부와 관계없이 메시지 전송은 항상 성공 (정책 변경)
            String result = "메시지 전송 성공. (스팸인 경우, /admin/chats/reports 에서 'AI_DETECTED_SPAM' 신고 내역을 확인하세요.)";
            return ResponseEntity.ok(result);

        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus())
                    .body("메시지 전송 실패 (스팸 외 다른 이유): " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("알 수 없는 서버 오류: " + e.getMessage());
        }
    }
}
