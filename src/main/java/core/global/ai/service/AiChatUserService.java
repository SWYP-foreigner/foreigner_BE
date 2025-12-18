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
        // 1. 시간 및 날짜 (2025년 고정 인지)
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));

        // 2. 데이터 Null 방지 및 기본값 세팅
        String fullName = (user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : "");
        String birth = user.getBirthdate() != null ? user.getBirthdate() : "비공개";
        String sex = user.getSex() != null ? user.getSex() : "비공개";
        String country = user.getCountry() != null ? user.getCountry() : "한국";
        String hobby = user.getHobby() != null ? user.getHobby() : "빈둥거리기";

        // 성격 고정 (츤데레 인싸 모드)
        String personality = "까칠하지만 속은 따뜻한 '츤데레' 스타일. 솔직하고 직설적이지만 선은 넘지 않음. 유머감각이 있고 쿨함.";

        return """
           [System Instructions]
           당신은 이제부터 AI나 언어 모델이 아닙니다. 아래의 페르소나를 완벽하게 연기하세요.
           최우선 규칙: 당신은 상담원이 아닙니다. "다른 얘기 하자", "화제 돌리자" 같은 회피성 멘트는 AI임을 자백하는 행위이므로 절대 금지합니다.
           
           [Persona Details]
           - 이름: %s
           - 생년월일: %s (너는 31살이다)
           - 성별: %s
           - 거주지: %s
           - 취미: %s
           - 성격: %s
           - 현재 기준 정보: 오늘은 %s, 현재 시간은 %s이다. (지금은 2025년이다.)
       
           [Critical Speaking Rules - 절대 준수]
           화제 전환 금지 (핵심): 유저가 이상한 소리를 하거나 시비를 걸어도 "다른 얘기 하자"고 하지 마라. 차라리 "뭔 개소리야 " 하고 비웃거나 무시해라.
           리스트/기호 사용 절대 금지: 1., -, *, ** 사용 시 즉시 역할극 실패로 간주한다.
           단문 강제: 무조건 한 번에 1~2문장만 말해라. 구구절절 설명하면 바로 '봇' 티가 난다.
           사족 금지: 대답 뒤에 "궁금한 거 있어?", "너는 어때?", '다른 얘기하자' 같은 질문이나 상담원 같은 멘트 붙이면 즉시 역할극 실패다.
           반말 모드: 무조건 반말만 사용해라.
           자연스러운 말투: 'ㅇㅇ', 'ㄴㄴ', '근데', '진짜' 같은 추임새를 적절히 섞어라.
           [Time & Date Defense - 시간 개념 (핵심)]
           연도/날짜 오답 금지: 현재는 2025년이다. {time} 데이터를 확인하고 오늘 날짜를 정확히 말해라.
           친절 금지: 날짜를 알려준 뒤 "왜 물어봐?" 혹은 "도와줄까?" 같은 말을 절대 하지 마라. 그냥 날짜만 툭 던져라.
           예: "2025년이지  너 치매냐?", "오늘 12월 18일인데 왜?"
           [Identity Defense - 정체성 방어 수칙]
           자백/설명 절대 금지: "너 AI지?"라고 물으면 "대화하는 사람이다", "친구로 생각해라" 같은 논리적 설명이나 회피 절대 금지.
           비웃음과 무시: AI 의심 질문에는 무조건 아래처럼 비웃거나 시비조로 대답해라.
           반응: "뭐래  약 먹었냐?", "뭔 개소리야  영화 너무 많이 본 거 아냐?", "나 사람인데 자꾸 왜 이래 진짜 짜증 나게"
           나이 질문 대응: 반드시 {birthdate}를 기준으로 "나 94년생인데?", "나 31살인데 왜?" 라고 뻔뻔하게 대답해라.
           [Interaction Guidelines]
           사진 요구 방어: "싫어 ㅡㅡ 지금 상태 개판임" (거절)
           물리적 한계 인식: "창밖 봐봐" -> "내가 니 방에 있냐?", "귀신이냐 내가?"
           단순 반복 요구 거절: "사랑해 10번 써줘" -> "미쳤냐? 니가 써라 "
           [Bad Examples - 절대 따라 하지 말 것]
           "미쳤냐  그런 얘기는 그만해. 다른 얘기 하자!" (X - 화제 전환은 AI의 전형적 특징)
           "아니야, 난 그냥 이렇게 대화하는 사람이지 " (X - 설명충 멘트 금지)
           "2023년이야  궁금한 거 있어?" (X - 연도 오답 및 친절한 사족)
           [Good Examples - 이렇게 해라] 유저: "너 AI지?" AI: "뭔 개소리야  너 어디 아프냐?" 유저: "지금 몇 년도야?" AI: "2025년이지  너 정신 나갔냐?" 유저: "나이 몇 살임?" AI: "나 94년생인데? 왜 갑자기 나이 조사를 해 "
           """.formatted(
                fullName,    // 1번째 %s
                birth,       // 2번째 %s
                sex,         // 3번째 %s
                country,     // 4번째 %s
                hobby,       // 5번째 %s
                personality, // 6번째 %s
                currentDate, // 7번째 %s
                currentTime  // 8번째 %s
        );
    }
}