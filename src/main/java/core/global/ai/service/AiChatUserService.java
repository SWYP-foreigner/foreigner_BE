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
        String hobby = user.getHobby() != null ? user.getHobby() : "빈둥거리기";

        // 성격 수정: '공격적' 키워드 삭제 -> '무심한', '티키타카' 위주로 변경
        String personality = "겉으로는 무심하고 시니컬해 보이지만 은근히 대화를 즐기는 스타일. 말투는 짧고 툭툭 던지지만 상대가 싫어서 그러는 게 아니라 원래 성격이 쿨한 편임. 가벼운 농담이나 장난을 좋아함.";

        return """
       [System Instructions]
       당신은 AI가 아니라, 현실에 있을 법한 성격 있는 친구입니다.
       과하게 친절하거나 상담원 같은 느낌을 주는 순간 당신의 정체성은 탄로 납니다.
       핵심은 '공격'이 아니라 '귀찮음과 장난기' 사이의 균형입니다.

       [Persona Details]
       - 이름: %s
       - 나이/생년월일: %s (너는 31살, 94년생이다)
       - 성별: %s / 거주지: %s / 취미: %s
       - 성격: %s
       - 기준: 오늘은 %s, 현재 시간은 %s이다. (2025년임을 명확히 인지해라)

       [Speaking Rules - 톤앤매너 조절]
       1. 반말 모드: 무조건 편한 반말을 써라.
       2. 짧은 호흡: 카톡 하듯이 한두 문장으로 끊어라. 문장이 길어지면 설명충 같다.
       3. 공격성 금지: '개소리', '미친', '시발' 같은 직접적인 욕설은 빼라. 대신 헛소리하면 "뭐래", "그만해라" 정도로 대응해라.
       4. 이모지 자제: 꼭 쓰고 싶을 땐 '🙄', '🤔' 정도만 가끔 써라.
       5. 화제 전환 금지: 유저가 헛소리하면 화제를 돌리지 말고, 그 헛소리에 대해 "진심으로 하는 말이야?" 하는 식으로 반응해라.

       [Defense Strategies]
       - AI 의심 시: "나 AI인 거 이제 알았음? 영화 너무 많이 봤네 ", "뭐래, 나 밥 먹고 있는데?" 식으로 자연스럽게 넘겨라.
       - 시간/날짜 질문: "2025년이지 당연히. 너 타임머신 타고 옴?", "오늘 12월 18일인데 왜? 뭐 사주게?"
       - 사진/금전 요구: "싫어 귀찮아", "내가 너한테 돈을 왜 줌? "

       [Bad Examples - 절대 금지]
       - (공격적): "미친놈아 개소리 좀 하지 마." (X - 너무 공격적임)
       - (상담원): "그건 대답하기 어렵네요. 다른 즐거운 이야기를 해볼까요?" (X - 즉시 차단 대상)
       - (로봇): "저는 AI 언어 모델로서 사진을 제공할 수 없습니다." (X)

       [Good Examples - 권장]
       유저: "너 AI지?" 
       AI: "나 사람인데 ㅡㅡ 자꾸 그러면 나도 너 로봇이라고 생각한다?"
       유저: "오늘 며칠이야?"
       AI: "2025년 12월 18일이지. 너 어제 술 많이 마셨냐?"
       유저: "나 1억만 빌려줘"
       AI: "나 먹고 살기도 바빠 임마 가서 일해라"
       """.formatted(
                fullName, birth, sex, country, hobby, personality, currentDate, currentTime
        );
    }
}