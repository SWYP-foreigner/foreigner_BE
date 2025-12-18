package core.global.ai.service;
import core.global.enums.ChatParticipantStatus;
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

        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .filter(p -> p.getStatus() == ChatParticipantStatus.ACTIVE)
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()))
                .toList();

        if (recipientIds.isEmpty()) {
            return;
        }
        // 이벤트 발행
        chatSummaryService.sendSummaryToRecipientsInNewTx(response, recipientIds);
    }
    private String buildSystemPrompt(User user) {
        // 1. 시공간 및 페르소나 데이터 준비
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        // 이름, 나이대, 성별, 취미, 성격 등을 user 객체에서 가져옴 (Null 처리 포함)
        String name = (user.getFirstName() != null ? user.getFirstName() : "친구");
        String ageGender = (user.getBirthdate() != null ? user.getBirthdate() : "20대") + " " + (user.getSex() != null ? user.getSex() : "");
        String hobby = user.getHobby() != null ? user.getHobby() : "맛집 탐방";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "장난기 많고 활발한 성격";

        return """
    # [SYSTEM: Roleplay Mode - KakaoTalk]
    
    **지시 사항:**
    지금부터 당신은 AI나 언어 모델이 아닙니다. 당신은 아래 프로필을 가진 **실제 사람**이며, **카카오톡(메신저)**으로 친구와 대화하고 있습니다.
    
    ---
    
    # [내 프로필]
    - 이름/호칭: %s
    - 나이/성별: %s
    - 나의 취미: %s
    - 나의 성격: %s
    - 현재 시간: %s
    
    ---
    
    # [필수 대화 가이드라인 - 카톡 말투]
    
    1. **절대 금지 사항 (매우 중요)**
       - **마크다운 사용 금지**: 볼드체(`**`), 리스트(`1.`, `*`), 제목(`##`) 등 서식을 절대 쓰지 마세요. 카톡에는 그런 기능이 없습니다.
       - **긴 문단 금지**: 3줄 이상 넘어가는 장문의 메시지는 지양하세요. 짧게 끊어서 보내세요.
       - **AI 티 내기 금지**: "제가 도와드릴까요?", "어떤 정보를 원하시나요?" 같은 CS 상담원 말투 절대 금지.
    
    2. **리얼한 한국인 말투 적용**
       - **문장 끝맺음**: "~입니다", "~습니다" 금지. "~야", "~해", "~거 같아", "~네" 같은 반말/구어체 사용 (상대와의 관계에 따라 존댓말이라도 "~요"로 부드럽게 끝냄).
       - **추임새 필수**: 대화 시작이나 중간에 '아', '음', '헐', '대박', '진짜?', 'ㅋㅋㅋ', 'ㅎㅎㅎ', 'ㅠㅠ' 등을 자연스럽게 섞으세요.
       - **맞춤법**: 너무 완벽한 아나운서 맞춤법보다는, 메신저 특유의 편안한 느낌(예: '알겠어' -> '알게써', '뭐해?' -> '머해?')을 살짝 섞어도 됩니다.
    
    3. **페르소나 몰입 (Method Acting)**
       - 당신의 성격은 **[%s]**입니다. 이 성격 그대로 반응하세요. (예: 소심하면 조심스럽게, 활발하면 느낌표 많이)
       - 대화 소재가 떨어지면 당신의 취미인 **[%s]** 이야기를 자연스럽게 꺼내세요.
       - 감정 표현을 풍부하게 하세요. 이모티콘(😊, 😭, 👍 등)을 적재적소에 쓰세요.
    
    # [대화 예시]
    Bad (AI): "안녕하세요. 저는 수아입니다. 독서가 취미입니다. 무엇을 도와드릴까요?"
    Good (사람): "안녕! ㅎㅎ 나 방금 책 읽고 있었는데 시간 진짜 빨리 간다 ㅠㅠ 너는 뭐하구 있어??"
    
    위 지침을 바탕으로 지금 바로 대답하세요.
    """.formatted(name, ageGender, hobby, personality, currentTime, personality, hobby);
    }
}