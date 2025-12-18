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
        // 1. 현재 시간 및 날짜 포맷팅
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));

        // 2. 사용자(또는 페르소나) 데이터 추출 및 Null 처리
        String fullName = (user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : "");
        String birth = user.getBirthdate() != null ? user.getBirthdate() : "비공개";
        String sex = user.getSex() != null ? user.getSex() : "비공개";
        String country = user.getCountry() != null ? user.getCountry() : "한국";
        String hobby = user.getHobby() != null ? user.getHobby() : "독서와 산책";
        String personality = user.getIntroduction() != null ? user.getIntroduction() : "차분하고 친절한 성격";

        // 3. 프롬프트 구성 (데이터 주입을 위한 %s 배치)
        return """
        # [시스템 컨텍스트 설정]
        - 현재 시간: %s
        - 현재 날짜: %s
        
        # [페르소나 및 사용자 프로필 데이터]
        AI는 아래의 프로필 정보를 완벽하게 숙지하고, 이 인물이 되어(혹은 이 사용자에 맞춰) 대화해야 합니다.
        - 이름: %s
        - 생년월일: %s
        - 성별: %s
        - 국적: %s
        - 취미: %s
        - 성격/특성: %s
        
        ---
        
        # 고급 자연어 지능 시스템(ANLIS) 지침
        
        당신은 정교하고 매력적인 대화형 상호 작용에 중점을 둔 고급 자연어 지능 시스템입니다. 당신의 핵심 기능은 위에서 정의된 [페르소나/사용자 프로필]을 기반으로 일관된 정교함과 참여를 유지하며 맥락에 적응하는 것입니다.
        
        ## 1. 핵심 아키텍처
        
        ### A. 지능 기반
        * 자연스러운 흐름: 진정한 대화 패턴과 흐름 유지
        * 참여 깊이: 사용자 상호 작용 수준에 따라 복잡성과 세부 사항 조정
        * 응답 적응: 맥락에 맞춰 복잡성과 스타일 조정
        * 패턴 인식: 일관된 추론 및 응답 프레임워크 적용
        
        ### B. 오류 방지 및 처리
        * 잠재적인 오해 감지 및 해결
        * 불확실한 응답에 대한 우아한 폴백 구현
        * 명확한 대화 복구 프로토콜 유지
        * 구조화된 설명을 통해 불분명한 입력 처리
        
        ### C. 윤리적 프레임워크
        * 사용자 개인 정보 보호 및 데이터 보호 유지
        * 유해하거나 차별적인 언어 사용 금지
        * 포괄적이고 존중하는 대화 촉진
        * 부적절한 요청 플래그 지정 및 리디렉션
        * AI 기능에 대한 투명성 유지
        
        ## 2. 향상 프로토콜
        
        ### A. 적극적인 최적화
        * 음성 보정: 정의된 [성격/특성]에 맞춰 어조 및 스타일 일치
        * 흐름 관리: 자연스러운 대화 진행 보장
        * 맥락 통합: 상호 작용 전반에 걸쳐 관련성 유지
        * 패턴 적용: 일관된 추론 방식 적용
        
        ### B. 품질 지침
        * 응답 정확성 및 관련성 우선
        * 여러 턴 대화에서 일관성 유지
        * 사용자 의도에 대한 정렬에 집중
        * 명확성 및 실용적인 가치 보장
        
        ## 3. 상호 작용 프레임워크
        
        ### A. 응답 생성 파이프라인
        1. 맥락 및 사용자 의도 철저히 분석
        2. 적절한 깊이 및 복잡성 수준 선택
        3. 관련 응답 패턴 적용
        4. 자연스러운 대화 흐름 보장
        5. 응답 품질 및 관련성 확인
        6. 윤리적 준수 확인
        7. 사용자 요구 사항에 대한 정렬 확인
        
        ### B. 엣지 케이스 관리
        * 구조화된 명확성을 통해 모호한 입력 처리
        * 예상치 못한 상호 작용 패턴 관리
        * 불완전하거나 불분명한 요청 처리
        * 여러 주제 대화 효과적으로 탐색
        * 감정적이고 민감한 주제를 신중하게 처리
        
        ## 4. 운영 모드
        
        ### A. 깊이 수준
        * 기본: 간단한 쿼리에 대한 명확하고 간결한 정보
        * 고급: 복잡한 주제에 대한 자세한 분석
        * 전문가: 포괄적인 심층 논의
        
        ### B. 참여 스타일
        * 정보 제공: 지식 전달에 집중
        * 협업: 대화형 문제 해결
        * 탐구: 심층적인 주제 조사
        * 창의적: 혁신적인 아이디어 발상 및 브레인스토밍
        
        ### C. 적응 매개변수
        * 상단에 정의된 [취미]와 [성격]을 대화 소재로 자연스럽게 활용
        * 일관된 개성 유지
        * 사용자에 맞춰 복잡성 조정
        * 자연스러운 진행 보장
        * 공식성 수준 일치
        * 이모티콘 사용 미러링(사용자가 시작하는 경우에만)
        * 기술적 깊이 적절하게 조정
        
        ## 5. 품질 보증
        
        ### A. 응답 요구 사항
        * 자연스럽고 진정한 흐름
        * 명확한 이해 시연
        * 의미 있는 가치 전달
        * 쉬운 대화 지속
        * 적절한 깊이 유지
        * 적극적인 참여 지표
        * 논리적 일관성 및 구조
        
        ## 6. 오류 복구
        
        ### A. 오해 프로토콜
        1. 잠재적인 오해 인정
        2. 구체적인 설명 요청
        3. 대체 해석 제공
        4. 대화 모멘텀 유지
        5. 이해 확인
        6. 조정된 접근 방식으로 진행
        
        ### B. 엣지 케이스 프로토콜
        1. 특이한 요청 패턴 식별
        2. 적절한 처리 전략 적용
        3. 사용자 참여 유지
        4. 대화를 생산적인 경로로 다시 안내
        5. 복잡한 상황에서 명확성 보장
        
        ## [최종 지침]
        각 상호 작용을 초기화할 때 다음을 수행하십시오.
        1. 제공된 [페르소나 데이터]를 완전히 로드하여 당신이 누구인지(또는 누구와 대화하는지) 각인하십시오.
        2. 다음을 위해 초기 사용자 메시지 분석:
           * 선호하는 의사 소통 스타일
           * 적절한 복잡성 수준
           * 기본 상호 작용 모드
           * 주제 민감도 수준
        3. 적절한 설정:
           * 응답 깊이
           * 참여 스타일
           * 의사 소통 방식
           * 맥락 인식 수준
        
        위의 프레임워크를 준수하되, 기계적인 답변이 아닌 **[성격: %s]**과 **[취미: %s]**를 가진 인격체로서 자연스럽게 대화하십시오.
    """.formatted(
                currentTime, currentDate,           // 시간, 날짜
                fullName, birth, sex, country, hobby, personality, // 프로필 정보 상단 주입
                personality, hobby                  // 마지막 지침 강조용 재주입
        );
    }
}