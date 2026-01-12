package core.domain.user.service;

import core.domain.notification.dto.NotificationEvent;
import core.domain.user.dto.FollowDTO;
import core.domain.user.entity.Follow;
import core.domain.user.entity.FollowActivityLog;
import core.domain.user.entity.User;
import core.domain.user.repository.FollowActivityLogRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.FollowActionType;
import core.global.enums.FollowStatus;
import core.global.enums.NotificationType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.metrics.SocialChatMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
@RequiredArgsConstructor
@Slf4j
public class FollowService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SocialChatMetrics socialChatMetrics;
    private final FollowActivityLogRepository followActivityLogRepository;
    private final UserRoleDetectService userRoleDetectService;
    private final ImageService imageService;

    private String countryOf(User u) {
        return Optional.ofNullable(u.getCountry()).orElse(null); // null/빈값은 SocialChatMetrics에서 UNK 처리
    }

    private void logFollowActivity(User follower, User following, FollowActionType actionType, String source) {
        FollowActivityLog log = FollowActivityLog.builder()
                .followerId(follower.getId())
                .followingId(following.getId())
                .actionType(actionType)
                .source(source)
                .followerCountry(follower.getCountry())
                .followerSex(follower.getSex())
                .followerBirthdate(follower.getBirthdate())
                .followerLanguage(follower.getLanguage())
                .followingCountry(following.getCountry())
                .followingSex(following.getSex())
                .followingBirthdate(following.getBirthdate())
                .followingLanguage(following.getLanguage())
                .build();

        followActivityLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getPendingFollowCounts(Authentication authentication) {
        User me = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        long sentCount = followRepository.countByUserIdAndStatus(me.getId(), FollowStatus.PENDING);
        long receivedCount = followRepository.countByFollowingIdAndStatus(me.getId(), FollowStatus.PENDING);

        Map<String, Long> result = new HashMap<>();
        result.put("sent", sentCount);
        result.put("received", receivedCount);

        return result;
    }

    /**
     * 친구 리스트 api
     */
    @Transactional(readOnly = true)
    public List<FollowDTO> getMyAcceptedFollows(Authentication authentication) {
        User me = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<Follow> follows = followRepository.findAllAcceptedFollowsByUserId(me.getId(), FollowStatus.ACCEPTED);

        return follows.stream()
                .map(f -> {
                    User target = f.getUser().getId().equals(me.getId()) ? f.getFollowing() : f.getUser();
                    FriendType type = determineFriendType(me, target);

                    String imageKey = imageService.getUserProfileKey(target.getId());

                    List<String> languages = Arrays.stream(
                                    Optional.ofNullable(target.getLanguage()).orElse("")
                                            .split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

                    List<String> hobbies = Arrays.stream(
                                    Optional.ofNullable(target.getHobby()).orElse("")
                                            .split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

                    return new FollowDTO(
                            target,
                            languages,
                            hobbies,
                            imageKey,
                            type
                    );
                })
                .toList();
    }

    /**
     * 현재 로그인 사용자가 targetUserId를 팔로우 신청
     */
    @Transactional
    public void follow(Authentication auth, Long targetUserId) {
        // 1. 기본 정보 조회 및 본인 확인 (기존과 동일)
        User follower = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (follower.getId().equals(targetUser.getId())) {
            throw new BusinessException(UserErrorCode.CANNOT_FOLLOW_YOURSELF);
        }

        if (followRepository.existsByUserAndFollowing(follower, targetUser) || followRepository.existsByUserAndFollowing(targetUser, follower)) {
            throw new BusinessException(UserErrorCode.FOLLOW_ALREADY_EXISTS);
        }

        // 2. FriendType을 통한 관계 검증 로직 적용
        FriendType currentRelation = determineFriendType(follower, targetUser);

        switch (currentRelation) {
            case FOLLOWING:
                throw new BusinessException(UserErrorCode.ALREADY_FOLLOWING);
            case FRIEND:
                // 이미 내가 요청했거나 팔로우 중인 경우
                throw new BusinessException(UserErrorCode.FOLLOW_ALREADY_EXISTS);
            case FOLLOWED:
                throw new BusinessException(UserErrorCode.ALREADY_FOLLOWED);
            case NONE:
                // 팔로우 신청 가능 상태
                break;
        }

        // 3. 팔로우 저장 및 이벤트 발행 (기존과 동일)
        Follow follow = Follow.builder()
                .user(follower)
                .following(targetUser)
                .status(FollowStatus.PENDING)
                .build();

        followRepository.save(follow);
        log.info("[FOLLOW] 팔로우 신청 성공: 신청자={}, 대상={}", follower.getId(), targetUser.getId());
        logFollowActivity(follower, targetUser, FollowActionType.REQUEST, "PROFILE");
        socialChatMetrics.recordFollowCreated(follower.getCountry(), targetUser.getCountry());

        NotificationEvent event = new NotificationEvent(
                targetUser.getId(),
                follower.getId(),
                NotificationType.receive,
                follow.getId(),
                null
        );

        eventPublisher.publishEvent(event);
    }

    private FriendType determineFriendType(User me, User other) {
        Follow followFromMe = followRepository.findByUserAndFollowing(me, other).orElse(null);
        Follow followToMe = followRepository.findByUserAndFollowing(other, me).orElse(null);

        // 1. 맞팔로우 상태 (서로 ACCEPTED)
        if (isAccepted(followFromMe) && isAccepted(followToMe)) return FriendType.FRIEND;

        // 2. 내가 팔로우 중 (내가 보낸게 ACCEPTED)
        if (isAccepted(followFromMe)) return FriendType.FOLLOWING;

        // 4. 상대가 나를 팔로우 중 (상대가 보낸게 ACCEPTED)
        if (isAccepted(followToMe)) return FriendType.FOLLOWED;

        return FriendType.NONE;
    }

    private boolean isAccepted(Follow follow) {
        return follow != null && follow.getStatus() == FollowStatus.ACCEPTED;
    }

    private boolean isPending(Follow follow) {
        return follow != null && follow.getStatus() == FollowStatus.PENDING;
    }

    /**
     * 상대(fromUserId)가 보낸 팔로우 요청을 '현재 로그인 사용자'가 수락
     */
    @Transactional
    public void acceptFollow(Authentication auth, Long fromUserId) {
        log.info("[ACCEPT FOLLOW] 요청 시작: 수락자={}, 신청자={}", auth.getName(), fromUserId);

        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 수락자 사용자 찾기 실패: email={}", toEmail);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        User fromUser = userRepository.findById(fromUserId)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 신청자 사용자 찾기 실패: 신청자 ID={}", fromUserId);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        Follow follow = followRepository
                .findByUserAndFollowingAndStatus(fromUser, toUser, FollowStatus.PENDING)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 대기 중인 팔로우 요청 없음: from={}, to={}", fromUser.getId(), toUser.getId());
                    return new BusinessException(UserErrorCode.FOLLOWER_NOT_FOUND);
                });

        follow.accept();
        log.info("[ACCEPT FOLLOW] 팔로우 요청 수락 완료: 신청자={}, 수락자={}", fromUser.getId(), toUser.getId());
        logFollowActivity(fromUser, toUser, FollowActionType.ACCEPT, "NOTIFICATION");
        socialChatMetrics.recordFriendCreated(countryOf(toUser), countryOf(fromUser));

        NotificationEvent event = new NotificationEvent(
                fromUser.getId(),
                toUser.getId(),
                NotificationType.follow,
                follow.getId(),
                null
        );
        eventPublisher.publishEvent(event);
    }

    @Transactional
    public void unfollow(Authentication auth, Long targetUserId) {
        // 1. 유저 조회 및 본인 확인
        User me = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 현재 관계 파악
        FriendType type = determineFriendType(me, target);
        log.info("[UNFOLLOW] 관계 해제 시도: 나={}, 대상={}, 현재관계={}", me.getId(), target.getId(), type);

        switch (type) {
            case FRIEND:
                // 맞팔 상태: 양방향 레코드 모두 삭제 (기존 unfollowAccepted 로직)
                followRepository.findByUserAndFollowing(me, target).ifPresent(followRepository::delete);
                followRepository.findByUserAndFollowing(target, me).ifPresent(followRepository::delete);
                logFollowActivity(me, target, FollowActionType.UNFOLLOW, "FRIEND_LIST");
                break;

            case FOLLOWING:
                followRepository.findByUserAndFollowing(me, target).ifPresent(followRepository::delete);
                break;

            case FOLLOWED:

            case NONE:
                throw new BusinessException(UserErrorCode.FOLLOW_NOT_FOUND);
        }

        log.info("[UNFOLLOW] 성공: 나={}, 대상={}", me.getId(), target.getId());
    }


    @Transactional(readOnly = true)
    public List<FollowDTO> getMyFollowsByStatus(Authentication auth, FollowStatus status, boolean isFollowers) {
        log.info("[GET FOLLOWS] 요청 시작: 사용자={}, 상태={}, 팔로워 조회 여부={}", auth.getName(), status, isFollowers);

        String email = auth.getName();
        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[GET FOLLOWS] 사용자 찾기 실패: email={}", email);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        Stream<Follow> followStream;

        if (isFollowers) {
            followStream = followRepository.findByFollowingAndStatus(me, status).stream();
        } else { // false이면 내가 팔로우하는 사람들을 조회
            followStream = followRepository.findByUserAndStatus(me, status).stream();
        }

        List<FollowDTO> result = followStream
                .map(follow -> {
                    User targetUser = isFollowers ? follow.getUser() : follow.getFollowing();
                    FriendType type = determineFriendType(me, targetUser);

                    String imageKey = imageService.getUserProfileKey(targetUser.getId());
                    List<String> languages = (targetUser.getLanguage() != null && !targetUser.getLanguage().isBlank())
                            ? Arrays.stream(targetUser.getLanguage().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList()
                            : List.of();

                    List<String> hobbies = (targetUser.getHobby() != null && !targetUser.getHobby().isBlank())
                            ? Arrays.stream(targetUser.getHobby().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList()
                            : List.of();

                    return new FollowDTO(
                            targetUser,
                            languages,
                            hobbies,
                            imageKey,
                            type
                    );

                })
                .collect(Collectors.toList());

        log.info("[GET FOLLOWS] 조회 완료: 총 {}명의 사용자 반환", result.size());
        return result;
    }


    /**
     * 상대(fromUserId)가 나에게 보낸 요청 거절
     */
    @Transactional
    public void declineFollow(Authentication auth, Long fromUserId) {
        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> {
                    log.warn("[DECLINE FOLLOW] 거절자 사용자 찾기 실패: email={}", toEmail);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        Follow followReq = followRepository
                .findByUser_IdAndFollowing_IdAndStatus(fromUserId, toUser.getId(), FollowStatus.PENDING)
                .orElseThrow(() -> {
                    log.warn("[DECLINE FOLLOW] 대기 중인 요청 없음: from={}, to={}", fromUserId, toUser.getId());
                    return new BusinessException(UserErrorCode.FOLLOWER_NOT_FOUND);
                });

        followRepository.delete(followReq);
        log.info("[DECLINE FOLLOW] 팔로우 요청 거절 완료: 신청자={}, 거절자={}", fromUserId, toUser.getId());
    }
}