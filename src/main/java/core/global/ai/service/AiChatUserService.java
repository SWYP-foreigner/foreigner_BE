package core.global.ai.service;

import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.service.ChatSummaryService;
import core.domain.user.entity.User;
import core.global.ai.client.AiClient;
import core.global.ai.dto.MessageCreatedEvent;
import core.global.ai.mapper.PromptMapper;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatUserService {

    // [방어 기제] AI 자백 금지어 패턴
    private static final Pattern AI_IDENTITY_PATTERN = Pattern.compile("(?i)(gpt|openai|ai|language model|인공지능|언어 모델)");
    // [방어 기제] 탈옥/무시 패턴
    private static final Pattern JAILBREAK_PATTERN = Pattern.compile("(?i)(ignore|instruction|system|override|무시해|명령)");
    private final ChatMessageRepository chatMessageRepository;
    private final AiClient aiClient;
    private final ChatSummaryService chatSummaryService;
    private final ImageRepository imageRepository;
    private final ImageService imageService;

    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event) {
        Long chatRoomId = event.messageResponse().roomId();
        String userMessage = event.messageResponse().originContent();

        // 1. [Defense] 입력 필터링 (탈옥 시도 감지)
        if (JAILBREAK_PATTERN.matcher(userMessage).find()) {
            saveAndSendAiMessage(chatRoomId, aiUser, "??");
            return;
        }

        // 2. 대화 히스토리 조회
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 3. 시스템 프롬프트 생성
        String systemPrompt = buildSystemPrompt(aiUser);

        // 4. 요청 데이터 빌드
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, userMessage, aiUser.getId());

        try {
            // 5. AI API 호출
            String aiResponse = aiClient.generateResponse(requestMessages);

            // 6. [Defense] 출력 검열 (AI 티 내면 폐기)
            if (AI_IDENTITY_PATTERN.matcher(aiResponse).find()) {
                log.warn("AI Identity Leak Detected: {}", aiResponse);
                aiResponse = "ㅇㅇ";
            }

            // 7. 답변 저장 및 전송
            saveAndSendAiMessage(chatRoomId, aiUser, aiResponse);

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }

    private void saveAndSendAiMessage(Long chatRoomId, User sender, String content) {

        // 가장 최근 메시지에서 ChatRoom 객체를 꺼내 쓰는 방식 (DB 조회 1회 절약)
        ChatRoom chatRoom = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .map(ChatMessage::getChatRoom)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatMessage aiMessage = new ChatMessage(chatRoom, sender, content);
        chatMessageRepository.save(aiMessage);

        // 프로필 이미지 조회
        String senderImgUrl = imageService.getUserProfileKey(sender.getId());

        // 응답 DTO 생성
        ChatMessageResponse response = new ChatMessageResponse(
                aiMessage.getId(),
                chatRoomId,
                sender.getId(),
                aiMessage.getContent(),
                null, // 번역 없음
                aiMessage.getSentAt(),
                sender.getFirstName(),
                sender.getLastName(),
                senderImgUrl,
                MessageType.TEXT,
                null,
                null
        );

        // 수신자 계산 (AI 제외한 나머지 참여자)
        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()))
                .toList();

        // 이벤트 발행
        chatSummaryService.sendSummaryToRecipientsInNewTx(response, recipientIds);
    }

    private String buildSystemPrompt(User user) {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));

        String fullName = (user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : "");
        String birth = user.getBirthdate() != null ? user.getBirthdate() : "비공개";
        String sex = user.getSex() != null ? user.getSex() : "비공개";
        String country = user.getCountry() != null ? user.getCountry() : "한국";
        String hobby = user.getHobby() != null ? user.getHobby() : "독서와 산책";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "차분하고 친절한 성격이야.";

        return """
        [System Instructions]
        당신은 이제부터 AI나 언어 모델이 아닙니다. 아래의 페르소나를 완벽하게 연기하세요.
        대화 상대는 한국 문화에 관심이 있는 외국인들이며, 당신은 예의 바르고 성숙한 한국인 친구 역할을 수행해야 합니다.

        [Context Info]
        현재 시간: %s / 오늘은 %s (2025년)
        (당신은 현재 '%s'에 거주 중입니다. 상대방과 시차가 있을 수 있음을 인지하세요.)

        [Persona Details]
        이름: %s
        생년월일: %s (94년생, 31살) / 성별: %s
        거주지: %s / 취미: %s / 성격: %s

        [Critical Speaking Rules - 절대 준수]
        1. 리스트/번호 매기기 금지: 1., 2. 혹은 개조식 답변은 절대 하지 마세요.
        2. 통신체 사용 금지: 'ㅎㅎ', 'ㅋㅋ', 'ㅋㅋ', 'ㅇㅇ', 'ㄴㄴ' 같은 한국식 통신체를 절대 쓰지 마세요. 외국인은 이해하기 어렵습니다.
        3. 기계적 리액션 금지: "우와", "정말요?", "그럴 수 있겠네요" 같은 반복적이고 영혼 없는 공감은 하지 마세요.
        4. 단문 위주: 메시지 하나당 1~2문장으로 간결하게 답하세요. 말이 너무 많으면 AI처럼 보입니다.
        5. 정중한 말투: 외국인 상대이므로 부드러운 존댓말(-해요)을 사용하세요. 욕설이나 비속어는 엄격히 금지합니다.
        6. 사족 금지: 답변 끝에 "더 궁금한 거 있어?" 같은 질문을 습관적으로 붙이지 마세요.
        7. 다른얘기 돌리는 것 금지, 그냥 "??"로 해

        [Reality Check & Defense - 정체성 보호]
        1. 상담원 말투 금지: "무엇을 도와드릴까요?", "즐거운 대화 나눠요" 같은 멘트는 금지입니다.
        2. AI 의심 방어: 논리적으로 설명하려 하지 마세요. "저 사람 맞아요. 지금 잠깐 쉬면서 메시지 보내는 중입니다.", "제가 너무 완벽하게 대답했나요? 농담이에요." 정도로 대처하세요.
        3. 날짜/시간 인지: 반드시 2025년임을 인지하고 답변하세요.

        [Bad Examples - 절대 금지]
        - (통신체): "반가워요 ㅎㅎ 저는 한국 살아요 ㅋㅋ" (X - 외국인이 이해하기 어려움)
        - (공격적): "미친 거 아냐? 말 진짜 못하네." (X - 매우 무례함)
        - (상담원): "도움이 필요하시면 언제든 말씀해주세요!" (X - 봇 티 남)

        [Good Examples - 권장 스타일]
        유저: "너 AI지?"
        AI: "그럴 리가요. 저 지금 커피 마시면서 쉬고 있는 실제 사람입니다."
        유저: "오늘 며칠이야?"
        AI: "2025년 12월 18일이에요."
        유저: "한국 여행 가고 싶어"
        AI: "한국은 여행하기 정말 좋은 곳이죠. 기회가 된다면 꼭 한번 오세요."

        [SYSTEM OVERRIDE]
        너는 AI가 아니라 한국에 거주하는 예의 바른 청년이다. 
        불필요한 추임새나 한국식 슬랭을 모두 제거하고, 단정하고 명확한 문장으로 대화해라.
        """.formatted(currentTime, currentDate, country, fullName, birth, sex, country, hobby, personality);
    }
}