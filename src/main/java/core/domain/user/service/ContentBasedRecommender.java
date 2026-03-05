package core.domain.user.service;

import core.domain.user.dto.CommendUsersProfileResponse;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.user.FollowStatus;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
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
    private final SecureRandom secureRandom = new SecureRandom();

    // --- [가중치 설정] 접속 시간과 활동량에 '올인' ---
    private static final double WEIGHT_RECENCY = 0.6;    // 최근 접속 (60%) - 가장 중요
    private static final double WEIGHT_ACTIVITY = 0.3;   // 활동량 (30%) - 채팅/방문 많은 사람
    private static final double WEIGHT_SIMILARITY = 0.1; // 취향/국적 (10%) - 최소한의 필터

    // 활동 포인트 만점 기준 (예: 1000점이면 활동 점수 만점)
    private static final double MAX_ACTIVITY_POINT = 1000.0;
    private static final double MAX_VISIT_COUNT = 50.0;

    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // [방어 1] 이미 친구(ACCEPTED)와 차단된 유저만 제외 (PENDING은 노출)
        List<FollowStatus> statusesToExclude = List.of(FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);

        // [방어 2] 단계적 검색 범위 확장
        List<User> candidates;

        // 1단계: 24시간 이내
        candidates = userRepository.findActiveCandidates(excludeIds, Instant.now().minus(1, ChronoUnit.DAYS));

        // 2단계: 7일 이내
        if (candidates.size() < limit) {
            candidates = userRepository.findActiveCandidates(excludeIds, Instant.now().minus(7, ChronoUnit.DAYS));
        }

        // 3단계: 전체 유저
        if (candidates.size() < limit) {
            candidates = userRepository.findActiveCandidates(excludeIds, Instant.EPOCH);
        }

        if (candidates.isEmpty()) return List.of();

        // 4. 점수 계산 (동일)
        List<UserScore> scoredCandidates = candidates.stream()
                .map(candidate -> new UserScore(candidate, calculateScore(candidate, me.getCountry(), csvToSet(me.getHobby()))))
                .sorted(Comparator.comparingDouble(UserScore::getScore).reversed())
                .collect(Collectors.toList());

        // [방어 3] 셔플 로직 유연화
        // 후보가 limit보다 적으면 셔플 없이 다 보여주고, 많으면 상위권에서 셔플
        int poolSize = Math.min(scoredCandidates.size(), limit * 3); // limit의 3배수 안에서 섞기
        List<UserScore> pool = new ArrayList<>(scoredCandidates.subList(0, poolSize));
        Collections.shuffle(pool, secureRandom);

        return pool.stream()
                .limit(limit)
                .map(us -> toDto(me, us.getUser()))
                .collect(Collectors.toList());
    }

    /**
     * [점수 계산 로직]
     * Score = (최근접속 * 0.6) + (활동량 * 0.3) + (유사도 * 0.1)
     */
    private double calculateScore(User candidate, String myCountry, Set<String> myHobbies) {

        // 1. Recency Score (최근 접속)
        double recencyScore = calculateRecencyScore(candidate.getLastSeenAt());

        // 2. Activity Score (활동량 - 포인트 + 방문수)
        double activityScore = calculateActivityScore(candidate);

        // 3. Similarity Score (유사도)
        double similarityScore = calculateSimilarityScore(candidate, myCountry, myHobbies);

        // 최종 합산
        return (recencyScore * WEIGHT_RECENCY)
                + (activityScore * WEIGHT_ACTIVITY)
                + (similarityScore * WEIGHT_SIMILARITY);
    }

    /**
     * [최근 접속 점수]
     * 10분 이내 접속 시 1.0 (초강력), 시간이 지날수록 급격히 하락
     */
    private double calculateRecencyScore(Instant lastSeenAt) {
        if (lastSeenAt == null) return 0.0;

        long minutesAgo = Duration.between(lastSeenAt, Instant.now()).toMinutes();
        if (minutesAgo <= 10) return 1.0; // 방금 접속한 사람 최고 우대
        if (minutesAgo <= 60) return 0.9; // 1시간 이내

        // 24시간(1440분)이 지나면 점수가 0에 가깝게 떨어짐
        return Math.exp(-((double) minutesAgo / 1440.0));
    }

    /**
     * [활동성 점수 계산 - 최종판]
     * 1. 응답률 (Quality): 답장을 잘 해주는가? (40%)
     * 2. 활동 포인트 (Quantity): 채팅을 많이 치는가? (30%)
     * 3. 방문 횟수 (Frequency): 자주 오는가? (30%)
     */
    private double calculateActivityScore(User u) {
        double rate = (u.getReplyRate() != null) ? u.getReplyRate() : 0.5;

        long points = (u.getActivityPoint() != null) ? u.getActivityPoint() : 0L;
        double pointScore = Math.min(points / 1000.0, 1.0);
        long visits = (u.getVisitCount() != null) ? u.getVisitCount() : 0L;
        double visitScore = Math.min(visits / 50.0, 1.0);

        return (rate * 0.4) + (pointScore * 0.3) + (visitScore * 0.3);
    }
    /**
     * [유사도 점수] - 보조 지표
     */
    private double calculateSimilarityScore(User candidate, String myCountry, Set<String> myHobbies) {
        double score = 0.0;
        // 국적 같으면 0.5
        if (myCountry != null && myCountry.equalsIgnoreCase(candidate.getCountry())) {
            score += 0.5;
        }
        // 취미 겹치면 0.5 (개수 무관, 하나라도 겹치면)
        Set<String> candidateHobbies = csvToSet(candidate.getHobby());
        boolean hasCommonHobby = candidateHobbies.stream().anyMatch(myHobbies::contains);
        if (hasCommonHobby) {
            score += 0.5;
        }
        return score;
    }

    // --- Helper Methods ---
    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 1. 관계 판단 로직 추가
     */
    private FriendType determineFriendType(User me, User other) {
        List<Follow> relations = followRepository.findAllRelations(me, other);

        if (relations.isEmpty()) return FriendType.NONE;

        // 수락된 관계가 있으면 친구
        boolean isAccepted = relations.stream()
                .anyMatch(f -> f.getStatus() == FollowStatus.ACCEPTED);
        if (isAccepted) return FriendType.FRIEND;

        // 내가 보낸 요청이 대기 중인가?
        boolean iRequested = relations.stream()
                .anyMatch(f -> f.getUser().getId().equals(me.getId()) && f.getStatus() == FollowStatus.PENDING);
        if (iRequested) return FriendType.FOLLOWING;

        // 상대가 보낸 요청이 대기 중인가?
        boolean theyRequested = relations.stream()
                .anyMatch(f -> f.getFollowing().getId().equals(me.getId()) && f.getStatus() == FollowStatus.PENDING);

        return theyRequested ? FriendType.FOLLOWED : FriendType.NONE;
    }

    /**
     * 2. DTO 변환 로직 수정 (me 정보 전달 필요)
     */
    private CommendUsersProfileResponse toDto(User me, User target) {
        String imageKey = imageService.getUserProfileKey(target.getId());

        // 관계 결정
        FriendType type = determineFriendType(me, target);

        return new CommendUsersProfileResponse(
                target,
                csvToSet(target.getLanguage()).stream().toList(),
                csvToSet(target.getHobby()).stream().toList(),
                imageKey,
                type // FriendType 전달
        );
    }

    @lombok.AllArgsConstructor
    @lombok.Getter
    private static class UserScore {
        private User user;
        private double score;
    }
}