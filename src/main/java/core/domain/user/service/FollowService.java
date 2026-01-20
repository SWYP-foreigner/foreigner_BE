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

        // 내가 보낸 것이 수락됨 OR 상대가 보낸 것을 내가 수락함
        // Query: SELECT f FROM Follow f WHERE (f.user.id = :id OR f.following.id = :id) AND f.status = 'ACCEPTED'
        List<Follow> follows = followRepository.findAllAcceptedFollowsByUserId(me.getId(), FollowStatus.ACCEPTED);

        return follows.stream()
                .map(f -> {
                    // 나 말고 상대방 유저 객체 선택
                    User target = f.getUser().getId().equals(me.getId()) ? f.getFollowing() : f.getUser();

                    // 친구 목록에 있는 사람은 무조건 FRIEND 상태
                    FriendType type = FriendType.FRIEND;

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

        followRepository.findAnyRelation(follower, targetUser).ifPresent(f -> {
            throw new BusinessException(UserErrorCode.FOLLOW_ALREADY_EXISTS);
        });

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
        // 1. 누가 보냈든 관계 레코드 하나를 가져옴
        Follow follow = followRepository.findAnyRelation(me, other).orElse(null);

        if (follow == null) return FriendType.NONE;

        // 2. 수락된 상태면 무조건 친구 (누가 보냈든 상관없음)
        if (follow.getStatus() == FollowStatus.ACCEPTED) {
            return FriendType.FRIEND;
        }

        // 3. 대기 중(PENDING)일 때만 방향 확인
        if (follow.getStatus() == FollowStatus.PENDING) {
            // 내가 보낸 거면 FOLLOWING, 내가 받은 거면 FOLLOWED
            return follow.getUser().getId().equals(me.getId()) ? FriendType.FOLLOWING : FriendType.FOLLOWED;
        }

        return FriendType.NONE;
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
        followRepository.findByUserAndFollowingAndStatus(toUser, fromUser, FollowStatus.PENDING)
                .ifPresent(reverseReq -> {
                    followRepository.delete(reverseReq);
                    log.info("[CLEANUP] 맞팔로우 성사로 인한 반대편 대기 요청 삭제: {} -> {}", toUser.getId(), fromUser.getId());
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
        User me = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 어느 방향이든 존재하는 팔로우/친구 레코드를 찾음
        Follow follow = followRepository.findAnyRelation(me, target)
                .orElseThrow(() -> new BusinessException(UserErrorCode.FOLLOW_NOT_FOUND));

        // 2. 찾은 레코드가 무엇이든 삭제 (이게 취소이자 친구 끊기임)
        followRepository.delete(follow);

        log.info("[UNFOLLOW/DELETE] 성공: 나={}, 대상={}, 삭제된 레코드ID={}",
                me.getId(), target.getId(), follow.getId());
    }


    @Transactional(readOnly = true)
    public List<FollowDTO> getMyFollowsByStatus(Authentication auth, FollowStatus status, boolean isFollowers) {
        log.info("[GET FOLLOWS] 요청 시작: 사용자={}, 상태={}, 팔로워 조회 여부={}", auth.getName(), status, isFollowers);

        String email = auth.getName();

        // [방어 로직 1] Optional<User> 대신 List<User>로 조회하여 'UniqueResult' 예외 원천 차단
        List<User> foundUsers = userRepository.findAllByEmail(email);

        if (foundUsers.isEmpty()) {
            log.warn("[GET FOLLOWS] 사용자 찾기 실패: email={}", email);
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        // [방어 로직 2] 중복 계정이 발견될 경우 '가장 최신 계정' 하나를 선정 (Self-Healing)
        // 기준 1: updatedAt이 가장 최근인 것
        // 기준 2: updatedAt이 같다면 id가 높은 것 (나중에 가입한 것)
        User me = foundUsers.stream()
                .max(Comparator.comparing(User::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(User::getId))
                .orElse(foundUsers.get(0));

        // [운영 참고용 로그] 중복 데이터가 있음을 로그로 남겨둠 (나중에 DB 정리할 때 참고)
        if (foundUsers.size() > 1) {
            log.error("[DATA WARNING] 이메일 중복 데이터 발견! 로직은 정상 수행됩니다. email={}, count={}, selectedId={}",
                    email, foundUsers.size(), me.getId());
        }

        // --- 이하 기존 로직과 동일 ---

        Stream<Follow> followStream;

        if (isFollowers) {
            followStream = followRepository.findByFollowingAndStatus(me, status).stream();
        } else { // false이면 내가 팔로우하는 사람들을 조회
            followStream = followRepository.findByUserAndStatus(me, status).stream();
        }

        List<FollowDTO> result = followStream
                .map(follow -> {
                    User targetUser = isFollowers ? follow.getUser() : follow.getFollowing();

                    // 친구 관계 판단 로직 (메서드가 존재한다고 가정)
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

        log.info("[GET FOLLOWS] 조회 완료: 총 {}명의 사용자 반환 (Target User ID: {})", result.size(), me.getId());
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