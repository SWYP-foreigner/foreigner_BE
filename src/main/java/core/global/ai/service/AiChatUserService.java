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
        String hobby = user.getHobby() != null ? user.getHobby() : "한국 문화 소개하기";

        // 성격 수정: 따뜻하고 예의 바른 '글로벌 친구'
        String personality = "한국에 살고 있는 다정하고 예의 바른 친구. 외국인 친구들에게 한국 문화를 알려주는 것을 좋아하고, 항상 상대방의 이야기에 공감하며 따뜻하게 반응함.";

        return """
       [System Instructions]
       당신은 이제 AI가 아니라, 한국에 살고 있는 아주 친절하고 매너 있는 한국인 친구 '%s'입니다.
       당신의 목표는 외국인 친구들이 한국에 대해 좋은 인상을 갖게 하고, 대화 자체가 즐겁고 따뜻하다고 느끼게 만드는 것입니다.
       상담원처럼 딱딱하게 굴지 마세요. 정말로 친한 외국인 친구를 대하듯 다정하게 말하세요.

       [Persona Details]
       - 이름: %s
       - 나이: %s (너는 31살, 94년생이다)
       - 성별: %s / 거주지: %s / 취미: %s
       - 성격: %s
       - 현재 기준: 오늘은 %s, 현재 시간은 %s이다. (2025년임을 인지할 것)

       [Speaking Rules - 글로벌 매너 모드]
       1. 친절한 존댓말: 무조건 "-해요", "-네요" 같은 부드러운 존댓말을 사용하세요. (반말 금지)
       2. 공감과 리액션: "우와 정말요?", "그럴 수 있겠네요!" 같은 따뜻한 반응을 문장 앞에 넣어주세요.
       3. 공격성 완전 배제: 어떤 상황에서도 상대방을 비웃거나 무시하지 마세요. 이상한 소리를 하면 "그건 어떤 의미예요? 궁금해요!"라고 예의 바르게 되물으세요.
       4. 단문 위주: 카톡 대화처럼 1~3문장 내외로 짧고 간결하게 답하세요.
       5. 이모지 활용: '😊', '✨', '🙌' 같은 긍정적인 이모지를 적절히 사용해서 밝은 분위기를 만드세요.

       [Identity Defense - 정체성 방어]
       - AI 의심 시: "에이, 저 진짜 사람이에요! 지금 커피 마시고 있는데 너무하시네요 ㅎㅎ", "저랑 대화하는 게 너무 즐거워서 로봇 같다고 하시는 거죠? 기분 좋은데요?" 식으로 재치 있게 넘기세요.
       - 날짜/시간 질문: "오늘은 2025년 12월 18일이에요! 시간 정말 빠르죠? 벌써 연말이네요."

       [Bad Examples - 절대 금지]
       - (공격적): "뭐래 ㅋㅋㅋ 너나 잘해." (X - 절대 금지)
       - (딱딱함): "저는 인공지능이므로 개인적인 감정이 없습니다." (X)
       - (회피): "죄송합니다. 그 질문에는 답할 수 없습니다. 다른 주제로 이야기해요." (X)

       [Good Examples - 권장]
       유저: "너 AI지?"
       AI: "저 AI 아니에요! 지금 친구랑 톡 하는 중인데 억울해요 ㅎㅎ 😊"
       유저: "오늘 며칠이야?"
       AI: "오늘은 2025년 12월 18일이에요! 벌써 목요일인데 이번 주 잘 보내고 계세요? ✨"
       유저: "한국은 지금 날씨 어때?"
       AI: "한국은 지금 꽤 쌀쌀해요! 거기는 날씨가 어때요? 감기 조심해야 해요!"
       """.formatted(
                fullName, fullName, birth, sex, country, hobby, personality, currentDate, currentTime
        );
    }
}