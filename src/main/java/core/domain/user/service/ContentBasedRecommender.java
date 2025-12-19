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

    // 1단계: 활동성 기준으로 추려낼 후보군 크기 (요청하신 '제일 큰 50')
    private static final int ACTIVITY_POOL_SIZE = 50;

    @Transactional(readOnly = true)
    public List<CommendUsersProfileResponse> recommendForUser(Long meId, int limit) {

        // 1. 내 정보 조회
        User me = userRepository.findById(meId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 제외 대상 필터링 (팔로잉, 차단, 본인)
        Set<Long> excludeIds = getExcludedUserIds(meId);

        // 3. 전체 후보군 조회 (DB에서 가져옴)
        // 성능 최적화를 위해 DB 레벨에서 active 순으로 100~200명 정도만 가져오는 쿼리를 작성하는 것이 좋으나,
        // 현재 로직 유지를 위해 전체를 가져와서 메모리에서 처리합니다.
        List<User> allCandidates = userRepository.findFullProfiledRecommendationCandidates(excludeIds);

        if (allCandidates.isEmpty()) {
            return List.of();
        }

        // --------------------------------------------------------
        // 핵심 로직 변경: 1. 최근 활동 50명 추출 -> 2. 유사도 정렬
        // --------------------------------------------------------

        // Step 1: 최근 활동한 순서대로 상위 50명(ACTIVITY_POOL_SIZE)만 남김
        List<User> activePool = allCandidates.stream()
                .sorted(Comparator.comparing(User::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(ACTIVITY_POOL_SIZE)
                .collect(Collectors.toList()); // 정렬을 위해 리스트로 수집

        // Step 2: 50명을 대상으로 [같은 나라 > 같은 언어 > 같은 취미] 순으로 재정렬
        activePool.sort((u1, u2) -> {
            // 1순위: 같은 나라 (다르면 같은 나라가 위로)
            boolean isCountryMatch1 = isSameCountry(me.getCountry(), u1.getCountry());
            boolean isCountryMatch2 = isSameCountry(me.getCountry(), u2.getCountry());
            if (isCountryMatch1 != isCountryMatch2) {
                return isCountryMatch1 ? -1 : 1; // true가 앞으로
            }

            // 2순위: 같은 언어 개수 (내림차순)
            int langMatchCount1 = countIntersection(me.getLanguage(), u1.getLanguage());
            int langMatchCount2 = countIntersection(me.getLanguage(), u2.getLanguage());
            if (langMatchCount1 != langMatchCount2) {
                return Integer.compare(langMatchCount2, langMatchCount1); // 많은 쪽이 앞으로
            }

            // 3순위: 같은 취미 개수 (내림차순)
            int hobbyMatchCount1 = countIntersection(me.getHobby(), u1.getHobby());
            int hobbyMatchCount2 = countIntersection(me.getHobby(), u2.getHobby());
            return Integer.compare(hobbyMatchCount2, hobbyMatchCount1);
        });

        // Step 3: 요청한 limit 만큼 자르고 DTO 변환
        return activePool.stream()
                .limit(limit)
                .map(this::toDto)
                .toList();
    }

    // --- Helper Methods ---

    /**
     * 제외할 사용자 ID 목록 생성 (팔로잉 + 차단 + 본인)
     */
    private Set<Long> getExcludedUserIds(Long meId) {
        List<FollowStatus> statusesToExclude = List.of(FollowStatus.PENDING, FollowStatus.ACCEPTED);
        Set<Long> followingIds = followRepository.findFollowingIdsByUserId(meId, statusesToExclude);
        Set<Long> blockedIds = blockRepository.findAllBlockedUserIds(meId);

        Set<Long> excludeIds = new HashSet<>(followingIds);
        excludeIds.addAll(blockedIds);
        excludeIds.add(meId);

        // 빈 리스트 쿼리 에러 방지용 더미 데이터
        if (excludeIds.isEmpty()) excludeIds.add(0L);

        return excludeIds;
    }

    /**
     * 국가 일치 여부 확인 (Null Safe)
     */
    private boolean isSameCountry(String myCountry, String otherCountry) {
        if (myCountry == null || otherCountry == null) return false;
        return myCountry.trim().equalsIgnoreCase(otherCountry.trim());
    }

    /**
     * 콤마로 구분된 문자열(CSV)을 Set으로 변환하여 교집합 개수 반환
     */
    private int countIntersection(String myCsv, String otherCsv) {
        Set<String> mySet = csvToSet(myCsv);
        Set<String> otherSet = csvToSet(otherCsv);

        // 교집합 개수 계산 (유사도 점수)
        mySet.retainAll(otherSet);
        return mySet.size();
    }

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return new HashSet<>(); // retainAll을 위해 가변 HashSet 반환
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private CommendUsersProfileResponse toDto(User u) {
        String imageKey = imageService.getUserProfileKey(u.getId());
        // DTO 변환 시 원본 Set이 훼손되지 않도록 다시 파싱
        return new CommendUsersProfileResponse(
                u,
                new ArrayList<>(csvToSet(u.getLanguage())),
                new ArrayList<>(csvToSet(u.getHobby())),
                imageKey
        );
    }
}