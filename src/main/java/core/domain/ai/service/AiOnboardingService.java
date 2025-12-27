package core.domain.ai.service;

import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.MessageType;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOnboardingService {

    private final ImageService imageService;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageService chatMessageService;
    private final ChatRoomService chatRoomService;

    // 메시지 문구 랜덤 선택용
    private static final SecureRandom secureRandom = new SecureRandom();

    // 1. 초기 온보딩 기간: 가입 후 48시간 (2880분)
    private static final int INITIAL_ONBOARDING_MINUTES = 48 * 60; // 2880분

    // 2. 확장 참여 유도 기간: 가입 후 3주 (21일)
    private static final int EXTENDED_WINDOW_DAYS = 21;

    // 3. 활동 유저 기준: 최근 1주일 (7일) 내 접속
    private static final int ACTIVE_USER_THRESHOLD_DAYS = 7;

    // 4. 확장 기간 중 메시지 발송 간격: 2시간 (120분)
    private static final int EXTENDED_INTERVAL_MINUTES = 120;

    /**
     * 주기적으로 실행되어 조건에 맞는 유저에게 AI가 선톡을 보냄
     */
    public void sendWelcomeMessagesToNewUsers() {
        Instant now = Instant.now();

        // 검색 범위: 가입한 지 3주(21일) 이내인 유저 전체 조회
        Instant joinTimeLimit = now.minus(Duration.ofDays(EXTENDED_WINDOW_DAYS));

        // 쿼리 최적화: Role이 USER이고, createdAt이 3주 이내인 사람만 fetch
        List<User> targetUsers = userRepository.findByUserRoleAndCreatedAtAfter(Role.USER, joinTimeLimit);
        List<User> allAiCharacters = userRepository.findByUserRole(Role.AI);

        if (allAiCharacters.isEmpty()) return;

        for (User user : targetUsers) {
            try {
                processUserOnboarding(user, allAiCharacters, now);
            } catch (Exception e) {
                log.error("Failed onboarding process for user: {}", user.getId(), e);
            }
        }
    }

    private void processUserOnboarding(User user, List<User> allAiCharacters, Instant now) {
        // 1. 프로필 미완성 유저는 제외
        if (!this.isProfileComplete(user)) return;

        // 2. 가입 후 흐른 시간 (분)
        long minutesSinceJoined = Duration.between(user.getCreatedAt(), now).toMinutes();

        // 3. 최근 활동 여부 체크 (가입 48시간 이후인 유저에게만 적용)
        if (minutesSinceJoined > INITIAL_ONBOARDING_MINUTES) {
            Instant activeThreshold = now.minus(Duration.ofDays(ACTIVE_USER_THRESHOLD_DAYS));
            // lastLoginAt이 없거나(null), 1주일보다 오래전이면 스킵
            if (user.getLastSeenAt() == null || user.getLastSeenAt().isBefore(activeThreshold)) {
                return;
            }
        }

        // ---------------------------------------------------------------
        // ✅ [통합 스케줄링 시뮬레이션]
        // 가입 시점부터 현재까지 타임라인을 돌려보며 "지금까지 총 몇 명의 AI가 말을 걸었어야 하는지" 계산
        // ---------------------------------------------------------------
        int expectedAiCount = 0;
        long simulatedTime = 0; // 가입 직후(0분)부터 시작

        while (true) {
            long interval;

            if (simulatedTime < INITIAL_ONBOARDING_MINUTES) {
                // [Phase 1] 초기 48시간: 5~15분 간격 (랜덤)
                // Seed를 고정하여 서버가 언제 돌든 유저별로 동일한 패턴 유지
                long seed = user.getId() + (expectedAiCount * 997L);
                Random seededRandom = new Random(seed);
                interval = 5 + seededRandom.nextInt(11);
            } else {
                // [Phase 2] 48시간 이후 ~ 3주: 2시간(120분) 고정 간격
                interval = EXTENDED_INTERVAL_MINUTES;
            }

            simulatedTime += interval;

            // 시뮬레이션 시간이 실제 흐른 시간을 넘어서면 루프 종료
            if (simulatedTime > minutesSinceJoined) {
                break;
            }

            // 이 시점까지는 메시지를 보냈어야 함 -> 카운트 증가
            expectedAiCount++;
        }
        // ---------------------------------------------------------------

        // 현재 이 유저와 대화 중인(방이 만들어진) AI 수 조회
        long currentAiCount = chatRoomRepository.countAiChatRoomsByUser(user.getId());

        // 로직상 보내야 할 AI 수보다, 실제로 만난 AI 수가 적다면 -> "새로운 AI 출동!"
        if (currentAiCount < expectedAiCount) {

            // 모든 AI를 다 만났으면 더 이상 못 보냄
            if (currentAiCount >= allAiCharacters.size()) return;

            // 아직 대화 안 해본 AI 찾기
            User selectedAi = findUnusedAi(user, allAiCharacters);

            if (selectedAi != null) {
                createRoomAndSendFirstMessage(user, selectedAi);
                log.info("✅ Auto Msg Sent: AI[{}] -> User[{}] (Joined: {}m, Phase: {})",
                        selectedAi.getFirstName(), user.getFirstName(),
                        minutesSinceJoined,
                        (minutesSinceJoined <= INITIAL_ONBOARDING_MINUTES) ? "Initial(48h)" : "Extended(3w)");
            }
        }
    }

    private User findUnusedAi(User user, List<User> allAiCharacters) {
        List<Long> metAiIds = chatRoomRepository.findPartnerIdsByUserId(user.getId());

        List<User> availableAis = allAiCharacters.stream()
                .filter(ai -> !metAiIds.contains(ai.getId()))
                .toList();

        if (availableAis.isEmpty()) return null;

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
        } catch (Exception e) {
            log.error("❌ Failed to send AI onboarding message", e);
        }
    }

    private String generateWelcomeMessage() {
        String[] greetings = {
                "Hi! Just bored so I thought I'd say hello lol",
                "Your profile looks cool! Nice to meet you.",
                "Are you studying Korean? We can practice together.",
                "Do you like K-pop? Who's your favorite group?",
                "Hi there! How is your day going?",
                "Hi, I'm from Korea! Where are you from?",
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
                "I like your photos!",
                "You seem like a fun person.",
                "I'm looking for a language exchange partner.",
                "I want to improve my English. Can you help?",
                "Have you ever visited Korea?",
                "Do you know any Korean words?",
                "I'm looking for global friends.",
                "Your style looks great!",
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