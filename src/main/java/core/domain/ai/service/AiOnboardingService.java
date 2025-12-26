package core.domain.ai.service;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom; // ✅ 변경됨 (ThreadLocalRandom 삭제)
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final ImageService imageService;
    private final UserRoleDetectService userRoleDetectService;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int ONBOARDING_WINDOW_MINUTES = 120;
    private static final int MESSAGE_INTERVAL_MINUTES = 10;

    @Transactional
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();
        Instant timeLimit = now.minus(Duration.ofMinutes(ONBOARDING_WINDOW_MINUTES));
        List<User> newUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, timeLimit);
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);
        if (allAiCharacters.isEmpty()) return;

        for (User user : newUsers) {
            processUserOnboarding(user, allAiCharacters, now);
        }
    }

    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        if (!this.isProfileComplete(user)) {
            return;
        }
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();

        // 10분이 아직 안 지났으면 패스
        if (minutesSinceJoined < MESSAGE_INTERVAL_MINUTES) return;

        // 보내야 할 총 AI 수 계산 (예: 25분 경과 -> 2명에게 받았어야 함)
        long expectedAiCount = minutesSinceJoined / MESSAGE_INTERVAL_MINUTES;

        // 현재 이 유저와 대화 중인 AI 수 조회
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());

        // 이미 충분히 받았다면 스킵
        if (currentAiCount >= expectedAiCount) return;
        if (currentAiCount >= allAiCharacters.size()) return; // AI 고갈
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

        // ✅ SecureRandom 사용 (랜덤으로 한 명 선택)
        return availableAis.get(secureRandom.nextInt(availableAis.size()));
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

        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("✅ AI Onboarding Message Sent via Pipeline: Room[{}] AI[{}] -> User[{}]",
                    chatRoom.getId(), ai.getFirstName(), user.getFirstName());
        } catch (Exception e) {
            log.error("❌ Failed to send AI onboarding message", e);
        }
    }

    private String generateWelcomeMessage() {
        String[] greetings = {
                // 기존 목록
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?",

                // 추가 목록 (인사 & 안부)
                "Hey! How's it going?",
                "Hi! Hope you're having a good week.",
                "Hello! Just wanted to say hi.",
                "Hi, nice to connect with you!",
                "What are you up to right now?",
                "Did you have a good lunch?",
                "Hi! How was your day today?",
                "It's nice to meet you.",
                "What time is it over there?",
                "Hello from Seoul!",

                // 취미 & 관심사
                "Do you like watching K-dramas?",
                "What kind of music do you listen to?",
                "Do you like Korean food?",
                "Any movie recommendations?",
                "Do you like traveling?",
                "What do you usually do for fun?",
                "Do you play any video games?",
                "Do you like coffee or tea?",
                "What's your favorite song these days?",
                "Do you like animals?",

                // 프로필 & 언어 교환
                "I like your photos!",
                "You seem like a fun person.",
                "I'm looking for a language exchange partner.",
                "I want to improve my English. Can you help?",
                "Have you ever visited Korea?",
                "Do you know any Korean words?",
                "I'm looking for global friends.",
                "Your style looks great!",

                // 가벼운 잡담
                "Do you use this app often?",
                "The weather is so nice today.",
                "Do you have any plans for the weekend?",
                "I'm bored too, want to chat?",
                "Are you a student or working?",
                "What's your MBTI?"
        };
        return greetings[secureRandom.nextInt(greetings.length)];
    }

    public boolean isProfileComplete(User user) {
        String userProfileKey = imageService.getUserProfileKey(user.getId());
        return user.getBirthdate() != null
                && user.getLastName() != null
                && user.getFirstName() != null
                && user.getPurpose() != null
                && user.getIntroduction() != null
                && user.getLanguage() != null
                && user.getHobby() != null
                && user.getSex() != null
                && userProfileKey != null;
    }
}