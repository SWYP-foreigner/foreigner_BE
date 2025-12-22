package core.domain.user.service;

import core.domain.user.dto.CommendUsersProfileResponse;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.FollowStatus;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentBasedRecommender {

    private final UserRepository userRepository;
    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final ImageService imageService;

    private static final double WEIGHT_ACTIVITY = 85.0;
    // 최소한의 취향 (말은 통해야 하니까)
    private static final double WEIGHT_SIMILARITY = 10.0;
    // 랜덤 비중을 5로 축소 (활동적인 사람이 랜덤 운 때문에 밀려나지 않도록)
    private static final double WEIGHT_RANDOM = 5.0;
    // [핵심] 반감기를 '1일'에서 '0.25일(6시간)' 또는 '0.1일(2.4시간)'으로 단축
    private static final double ACTIVITY_HALF_LIFE_DAYS = 0.1;

    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {
        // 1. 내 정보 조회
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 제외 대상 필터링 (팔로잉, 차단, 본인)
        List<FollowStatus> statusesToExclude = List.of(FollowStatus.PENDING, FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);
        if (excludeIds.isEmpty()) excludeIds.add(0L);

        Instant activeLimit = Instant.now().minus(3, ChronoUnit.DAYS);

        List<User> candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);
        if (candidates.isEmpty()) {
            activeLimit = Instant.now().minus(14, ChronoUnit.DAYS);
            candidates = userRepository.findActiveCandidates(excludeIds, activeLimit);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 4. 내 언어/취미 Set으로 변환 (비교 편의성)
        Set<String> myLanguages = csvToSet(me.getLanguage());
        Set<String> myHobbies = csvToSet(me.getHobby());

        // 5. 점수 계산 및 정렬, 상위 3명 추출
        return candidates.stream()
                .map(candidate -> {
                    double score = calculateTotalScore(candidate, myLanguages, myHobbies);
                    return new UserScore(candidate, score);
                })
                .sorted(Comparator.comparingDouble(UserScore::getScore).reversed()) // 점수 높은 순 정렬
                .limit(limit) // 상위 N명 (여기서는 3명)
                .map(us -> toDto(us.getUser()))
                .collect(Collectors.toList());
    }

    /**
     * [총점 계산 로직]
     * Total = (활동성 * 50) + (유사도 * 30) + (랜덤 * 20)
     */
    private double calculateTotalScore(User candidate, Set<String> myLanguages, Set<String> myHobbies) {
        double activityScore = calculateActivityScore(candidate);
        double similarityScore = calculateSimilarityScore(candidate, myLanguages, myHobbies);
        double randomNoise = Math.random(); // 0.0 ~ 1.0 난수

        return (activityScore * WEIGHT_ACTIVITY)
                + (similarityScore * WEIGHT_SIMILARITY)
                + (randomNoise * WEIGHT_RANDOM);
    }

    /**
     * [활동성 점수] 0.0 ~ 1.0
     * 최근 접속일수록 1.0에 가까움. 시간이 지날수록 지수적으로 감소.
     */
    private double calculateActivityScore(User user) {
        if (user.getLastSeenAt() == null) return 0.0;

        long hoursSinceLastSeen = ChronoUnit.HOURS.between(user.getLastSeenAt(), Instant.now());
        // 미래 시간인 경우 방어 로직
        if (hoursSinceLastSeen < 0) hoursSinceLastSeen = 0;

        double daysSince = hoursSinceLastSeen / 24.0;
        double decayRate = Math.log(2) / ACTIVITY_HALF_LIFE_DAYS;

        return Math.exp(-decayRate * daysSince);
    }

    /**
     * [유사도 점수] 0.0 ~ 1.0
     * 언어와 취미가 얼마나 겹치는지 계산
     */
    private double calculateSimilarityScore(User candidate, Set<String> myLanguages, Set<String> myHobbies) {
        Set<String> candidateLanguages = csvToSet(candidate.getLanguage());
        Set<String> candidateHobbies = csvToSet(candidate.getHobby());

        // 언어 일치 개수
        long langMatchCount = candidateLanguages.stream()
                .filter(myLanguages::contains).count();

        // 취미 일치 개수
        long hobbyMatchCount = candidateHobbies.stream()
                .filter(myHobbies::contains).count();

        // 정규화: (일치 개수 / 전체 개수)로 하면 너무 점수가 작아지므로,
        // 단순히 일치하는게 하나라도 있으면 점수를 후하게 주는 방식 사용 (Jaccard 유사도 변형)

        double totalMatches = langMatchCount + hobbyMatchCount;
        double maxPossibleMatches = Math.max(1, myLanguages.size() + myHobbies.size());

        // 최대 1.0을 넘지 않도록
        return Math.min(1.0, totalMatches / maxPossibleMatches);
    }

    // --- Helper Methods ---

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private CommendUsersProfileResponse toDto(User u) {
        String imageKey = imageService.getUserProfileKey(u.getId());
        return new CommendUsersProfileResponse(
                u,
                csvToSet(u.getLanguage()).stream().toList(),
                csvToSet(u.getHobby()).stream().toList(),
                imageKey
        );
    }

    // 내부 점수 래퍼 클래스
    @lombok.AllArgsConstructor
    @lombok.Getter
    private static class UserScore {
        private User user;
        private double score;
    }
}