package core.global.ai.service;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;

    // 신규 유저 관리 기간 (가입 후 2시간 동안만 10분마다 말 걸기)
    private static final int ONBOARDING_WINDOW_MINUTES = 120;
    // 메시지 발송 간격 (10분)
    private static final int MESSAGE_INTERVAL_MINUTES = 10;

    @Transactional
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();
        Instant timeLimit = now.minus(Duration.ofMinutes(ONBOARDING_WINDOW_MINUTES));

        // 1. 최근 가입한 신규 유저 조회 (Role.USER, 가입 2시간 이내)
        List<User> newUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, timeLimit);

        // 2. 모든 AI 캐릭터 로드 (Role.AI)
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);
        if (allAiCharacters.isEmpty()) return;

        for (User user : newUsers) {
            processUserOnboarding(user, allAiCharacters, now);
        }
    }

    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        // 가입 후 경과 시간 (분)
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();

        // 10분이 아직 안 지났으면 패스
        if (minutesSinceJoined < MESSAGE_INTERVAL_MINUTES) return;

        // 보내야 할 총 AI 수 계산 (예: 25분 경과 -> 2명에게 받았어야 함)
        // (10분: 1명, 20분: 2명, 30분: 3명...)
        long expectedAiCount = minutesSinceJoined / MESSAGE_INTERVAL_MINUTES;

        // 현재 이 유저와 대화 중인 AI 수 조회 (이미 채팅방이 있는 AI 수)
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());

        // 이미 충분히 받았다면 스킵
        if (currentAiCount >= expectedAiCount) return;
        if (currentAiCount >= allAiCharacters.size()) return; // AI 고갈

        // --- 새로운 AI 매칭 및 발송 ---

        // 1. 아직 대화 안 한 AI 찾기
        User selectedAi = findUnusedAi(user, allAiCharacters);

        if (selectedAi != null) {
            // 2. 채팅방 생성 및 선톡 발송
            createRoomAndSendFirstMessage(user, selectedAi);
            log.info("✅ Sent onboarding message: AI[{}] -> User[{}]", selectedAi.getFirstName(), user.getFirstName());
        }
    }

    private User findUnusedAi(User user, List<User> allAiCharacters) {
        // 유저가 이미 참여 중인 채팅방들의 상대방 ID 목록 조회
        List<Long> metAiIds = chatRoomRepository.findPartnerIdsByUserId(user.getId());

        // 아직 안 만난 AI 필터링
        List<User> availableAis = allAiCharacters.stream()
                .filter(ai -> !metAiIds.contains(ai.getId()))
                .toList();

        if (availableAis.isEmpty()) return null;

        // 랜덤으로 한 명 선택 (자연스러움을 위해)
        return availableAis.get(ThreadLocalRandom.current().nextInt(availableAis.size()));
    }

    private void createRoomAndSendFirstMessage(User user, User ai) {
        ChatRoom chatRoom = chatRoomService.createRoom(user.getId(), ai.getId());
        String firstMessageContent = generateWelcomeMessage();
        SendMessageRequest request = new SendMessageRequest(
                chatRoom.getId(),
                ai.getId(),
                firstMessageContent,
                MessageType.TEXT
        );

        // 4. ChatService에 전송 위임 (저장 + 번역 + 전송 + 알림 다 해줌)
        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("✅ AI Onboarding Message Sent via Pipeline: Room[{}] AI[{}] -> User[{}]",
                    chatRoom.getId(), ai.getFirstName(), user.getFirstName());
        } catch (Exception e) {
            log.error("❌ Failed to send AI onboarding message", e);
        }
    }

    // AI의 페르소나에 맞는 첫마디 생성
    private String generateWelcomeMessage() {
        String[] greetings = {
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?"
        };
        return greetings[ThreadLocalRandom.current().nextInt(greetings.length)];
    }
}