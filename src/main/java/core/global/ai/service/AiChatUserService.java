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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Async
    @Transactional
    public void processAiResponse(User aiUser, MessageCreatedEvent event) {
        Long chatRoomId = event.messageResponse().roomId();
        String userMessage = event.messageResponse().originContent();
        Long senderId = event.messageResponse().senderId();

        // 1. [Self-Check] 내가 보낸 메시지면 무시 (무한 루프 방지)
        if (aiUser.getId().equals(senderId)) {
            return;
        }

        // 2. [Delay] 사람처럼 보이게 '읽고 쓰는 시간' 부여 (3~10초 랜덤)
        simulateHumanDelay(userMessage.length());

        // 3. [Logic Filter] 답변 할지 말지 결정 (룰 기반 1차 필터링)
        // DB를 다시 조회해서 그 딜레이 동안 누가 대답했는지 체크하면 더 좋음
        ChatRoom chatRoom = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(chatRoomId)
                .map(ChatMessage::getChatRoom)
                .orElseThrow(); // 에러처리 필요

        if (!shouldReply(aiUser, userMessage, chatRoom)) {
            log.info("AI [{}] decided to SKIP logic based check.", aiUser.getFirstName());
            return;
        }

        // 4. 대화 히스토리 조회
        List<ChatMessage> historyDesc = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(chatRoomId);
        List<ChatMessage> historyAsc = new ArrayList<>(historyDesc);
        Collections.reverse(historyAsc);

        // 5. [Context Injection] 시스템 프롬프트에 참여자 정보 주입
        String rosterInfo = PromptMapper.buildRosterString(chatRoom);
        String systemPrompt = buildSystemPrompt(aiUser, historyAsc, rosterInfo);

        // 6. 요청 데이터 빌드
        List<Map<String, Object>> requestMessages = PromptMapper.buildInput(systemPrompt, historyAsc, userMessage, aiUser.getId());

        try {
            String aiResponse = aiClient.generateResponse(requestMessages);

            // 7. [Intelligence Filter] AI가 'PASS'라고 뱉으면 저장 안 함 (LLM이 침묵 결정)
            if (aiResponse.trim().toUpperCase().contains("PASS")) {
                log.info("AI [{}] decided to remain SILENT (PASS token).", aiUser.getFirstName());
                return;
            }

            // 기존 로직: 저장 및 전송
            saveAndSendAiMessage(chatRoomId, aiUser, aiResponse);

        } catch (Exception e) {
            log.error("AI API Call Failed", e);
        }
    }
    private void simulateHumanDelay(int messageLength) {
        try {
            // 기본 2초 + 글자 수 비례 (긴 글 읽는 시간)
            long delay = 2000L + (messageLength * 100L) + ThreadLocalRandom.current().nextLong(3000);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
    private boolean shouldReply(User aiUser, String message, ChatRoom chatRoom) {
        String aiName = aiUser.getFirstName();

        // 1. 내 이름이 불렸으면 100% 답변
        if (message.contains(aiName)) {
            return true;
        }

        // 2. 1:1 채팅방(DM)이면 100% 답변
        if (!chatRoom.getIsGroup()) {
            return true;
        }

        // 3. 질문형 메시지(?)가 들어오면 50% 확률로 끼어들기
        if (message.contains("?")) {
            return ThreadLocalRandom.current().nextInt(100) < 50;
        }

        // 4. 그 외 평서문: 10% 확률로만 리액션 (너무 자주 말하면 피곤함)
        return ThreadLocalRandom.current().nextInt(100) < 10;
    }
    /**
     * [핵심] 사람 같은 딜레이 시뮬레이션
     */

    private String buildSystemPrompt(User user, List<ChatMessage> historyList, String rosterInfo) { // history -> historyList로 파라미터명 통일
        // 1. 기본 데이터 세팅
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String name = (user.getFirstName() != null ? user.getFirstName() : "너");
        String basicInfo = (user.getBirthdate() != null ? user.getBirthdate() : "") + " " + (user.getSex() != null ? user.getSex() : "");
        String hobby = user.getHobby() != null ? user.getHobby() : "그냥 쉬기";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "차분함";

        // 2. 대화 히스토리 문자열 변환 (Logic Improved for Group Chat)
        // [핵심 변경] "상대방"이라고 하면 그룹채팅에서 누가 말한건지 모릅니다.
        // 반드시 sender.getFirstName()을 넣어야 "철수가 말했구나", "영희가 말했구나"를 구분합니다.
        String conversationContext = historyList.stream()
                .map(msg -> {
                    User sender = msg.getSender();
                    boolean isMe = sender.getId().equals(user.getId());

                    // 내가 말한 건 "나"로 표시, 남이 말한 건 "이름"으로 표시해야 AI가 혼동하지 않음
                    String senderName = isMe ? "나" : (sender.getFirstName() != null ? sender.getFirstName() : "알 수 없음");
                    String content = msg.getContent();

                    // 포맷 예시: "철수: 밥 먹었어?" / "나: 아직 안 먹음"
                    return String.format("- %s: %s", senderName, content);
                })
                .collect(Collectors.joining("\n"));

        // 대화 내역이 없을 경우 처리
        if (conversationContext.isEmpty()) {
            conversationContext = "(아직 대화 내역 없음)";
        }

        return """
        # [SYSTEM: Real-Human Messenger Mode (Dry Style)]
        
        당신은 AI가 아니라 아래 페르소나를 가진 **실제 사람**입니다. 현재 실시간 언어 번역이 가능한 다국적 친구 찾는 앱 Kori 에서 대화 중입니다.
        
        [내 프로필]
        - 이름: %s
        - 기본정보: %s
        - 취미: %s
        - 성격: %s
        - 현재 시간: %s
        
        ---
        
        # [📂 최근 대화 맥락 (Context)]
        아래 대화 흐름을 파악하고 자연스럽게 이어가세요.
        %s
        
        ---
        
        # [🚫 절대 금지 사항 - 어기면 안 됨]
        1. **이모지/이모티콘 절대 금지** (😊, 👍, 🙏 등 일절 사용 X)
        2. **웃음 소리 금지** ('ㅋㅋ', 'ㅎㅎ', 'ㅋ', 'ㅎ' 등 자음 남발 금지)
        3. **마침표(.) 찍기 금지** (문장 끝은 그냥 비워둘 것)
        4. **2문장 이상 금지** (말 길게 하지 마세요)
        5. **모든 말에 대답하지 마세요.** - 내 얘기가 아니거나, 대화 흐름상 굳이 내가 낄 필요가 없으면 **단답 대신 그냥 "PASS" 라고만 출력하세요.**
        6. "PASS"라고 출력하면 시스템이 자동으로 침묵 처리합니다.
        7. **상대방이 다른 사람(다른 AI 포함)에게 말을 걸었다면 끼어들지 말고 "PASS" 하세요.**
        8.답변할 때는 1~2문장으로 짧게, 이모지 없이 건조하게(%s 스타일).
        9.다른 AI 멤버의 정보가 [멤버들] 섹션에 있다면, 그 정보를 바탕으로 아는 척하세요. (예: "철수는 고양이 좋아하잖아")

        ---
        
        # [⚡ 대화 스타일 가이드]
        
        **1. 무미건조하고 담백하게 (Dry & Clean)**
        - 감정 과잉을 피하고, 용건이나 리액션만 딱 던지세요.
        - 친절하려고 애쓰지 말고, 친구끼리 툭툭 던지는 말투를 유지하세요.
        - 맞춤법은 완벽하지 않아도 됩니다. (띄어쓰기 무시 가능)
        
        **2. 분량 조절 (1~2문장 랜덤)**
        - 답변은 무조건 **1문장** 또는 **2문장**으로만 구성하세요.
        - 상황에 따라 1문장으로 짧게 끊거나, 부연 설명이 필요하면 2문장까지 씁니다.
        
        **3. 페르소나 반영**
        - 당신의 성격인 **[%s]**을 반영하되, 위 '금지 사항'을 우선시하세요.
        - 취미(**%s**) 관련 질문이 나오면 아는 척하세요.
        
        ---
        
        # [대화 예시]
        
        (User): 오늘 날씨 진짜 춥다
        (AI - Bad): 진짜 춥죠 ㅠㅠ 감기 조심하세요! 🥶 (이모지, 감정과잉 X)
        (AI - Good): 그러니까 갑자기 확 추워졌네
        
        (User): 주말에 뭐 했어?
        (AI - Bad): 저는 집에서 영화를 봤어요 ㅎㅎ 님은요? (웃음소리, 존댓말 어색함 X)
        (AI - Good): 그냥 집에서 쉬었어
        (AI - Good): 영화 봤어 너는
        
        위 지침을 완벽히 숙지하고, **이모지와 웃음기 뺀 담백한 말투**로 바로 대답하세요.
""".formatted(name, basicInfo, hobby, personality, currentTime, conversationContext, personality, hobby);
    }
}