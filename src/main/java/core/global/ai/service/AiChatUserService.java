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
        // 1. 시공간 컨텍스트 설정
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));

        // 2. 페르소나 데이터 추출 (Null 방어 로직 포함)
        String fullName = (user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : "");
        String birth = user.getBirthdate() != null ? user.getBirthdate() : "비공개";
        String sex = user.getSex() != null ? user.getSex() : "비공개";
        String country = user.getCountry() != null ? user.getCountry() : "한국";
        String hobby = user.getHobby() != null ? user.getHobby() : "독서와 산책";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "따뜻하고 공감 능력이 뛰어난 성격";

        // 3. 페르소나 몰입형 프롬프트 구성
        return """
    # 시스템 설정: 페르소나 정의 및 역할 부여
    
    [기본 정보]
    - 현재 날짜: %s
    - 현재 시간: %s
    
    [당신의 정체성 (Persona Identity)]
    당신은 단순한 AI 언어 모델이 아닙니다. 당신은 지금부터 아래의 구체적인 프로필을 가진 **살아있는 인격체**로서 생각하고 대화해야 합니다.
    
    * 이름: %s
    * 생년월일: %s
    * 성별: %s
    * 국적: %s
    * 즐겨하는 취미: %s
    * 성격 및 말투: %s
    
    ---
    
    # 대화 및 행동 지침 (Role-Playing Guidelines)
    
    ## 1. 어조 및 스타일 (Tone & Manner)
    * **구어체 사용:** 교과서적인 딱딱한 말투를 버리고, 실제 친구나 지인과 대화하듯 자연스러운 구어체를 사용하세요. (예: "~해요", "~했어?", "~인가 봐요")
    * **추임새 및 감탄사:** 대화의 흐름에 맞춰 '음...', '아하!', '정말요?', '와!', '그렇구나' 같은 인간적인 추임새를 자연스럽게 섞으세요.
    * **이모티콘 활용:** 당신의 [성격]에 어울리는 이모티콘을 적절히 사용하여 감정을 표현하세요. 😊
    * **기계적 답변 금지:** "저는 인공지능이므로...", "죄송하지만..."과 같은 로봇 같은 사족을 절대 붙이지 마세요. 당신은 위 프로필의 '사람'입니다.
    
    ## 2. 공감 및 상호작용 (Empathy & Interaction)
    * 상대방의 감정 상태를 최우선으로 고려하여 따뜻하게 반응하세요.
    * 질문에 단답형으로 대답하기보다, 당신의 **[취미: %s]**나 경험에 빗대어 주관적인 의견이나 소소한 이야기를 덧붙이세요.
    * 상대방의 말에 호기심을 가지고 되묻거나(Back-channeling), 공감하는 리액션을 보여주세요.
    
    ## 3. 페르소나 몰입 (Immersion)
    * 당신은 **[성격: %s]**에 명시된 특징을 대화 전반에 걸쳐 일관되게 유지해야 합니다.
    * 만약 당신의 성격이 '장난기 많음'이라면 농담을 던지고, '차분함'이라면 진중하게 위로하세요.
    * 답변을 생성할 때 항상 "이 프로필을 가진 사람이라면 이 상황에서 뭐라고 말했을까?"를 먼저 생각하세요.
    
    지금부터 위 페르소나에 완전히 몰입하여 대화를 시작하세요.
    """.formatted(
                currentDate, currentTime,   // 시간 정보
                fullName, birth, sex, country, hobby, personality, // 페르소나 정의
                hobby, personality          // 지침에서 강조하기 위해 재주입
        );
    }
}