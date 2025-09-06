package core.domain.user.service;

import core.domain.user.dto.FollowDTO;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.FollowStatus;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class FollowService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    /** 현재 로그인 사용자가 targetUserId를 팔로우 신청 */
    @Transactional
    public void follow(Authentication auth, Long targetUserId) {
        log.info("[FOLLOW] 요청 시작: 사용자={}, 대상={}", auth.getName(), targetUserId);

        String email = auth.getName();
        User follower = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[FOLLOW] 팔로워 사용자 찾기 실패: email={}", email);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> {
                    log.warn("[FOLLOW] 대상 사용자 찾기 실패: 대상 ID={}", targetUserId);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        // 자기 자신을 팔로우하는 것을 방지하는 로직 추가
        if (follower.getId().equals(targetUser.getId())) {
            log.warn("[FOLLOW] 자기 자신 팔로우 시도 차단: 사용자={}", follower.getId());
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_YOURSELF);
        }

        Follow existing = followRepository.findByUserAndFollowing(follower, targetUser).orElse(null);
        if (existing != null) {
            switch (existing.getStatus()) {
                case PENDING -> {
                    log.info("[FOLLOW] 이미 팔로우 신청 대기 중: from={}, to={}", follower.getId(), targetUser.getId());
                    return; // 이미 신청중이면 무시
                }
                case ACCEPTED -> {
                    log.warn("[FOLLOW] 이미 팔로우 중: from={}, to={}", follower.getId(), targetUser.getId());
                    throw new BusinessException(ErrorCode.FOLLOW_ALREADY_EXISTS);
                }
            }
        }

        Follow follow = Follow.builder()
                .user(follower)
                .following(targetUser)
                .status(FollowStatus.PENDING)
                .build();

        followRepository.save(follow);
        log.info("[FOLLOW] 팔로우 신청 성공: 신청자={}, 대상={}", follower.getId(), targetUser.getId());
    }

    /** 상대(fromUserId)가 보낸 팔로우 요청을 '현재 로그인 사용자'가 수락 */
    @Transactional
    public void acceptFollow(Authentication auth, Long fromUserId) {
        log.info("[ACCEPT FOLLOW] 요청 시작: 수락자={}, 신청자={}", auth.getName(), fromUserId);

        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 수락자 사용자 찾기 실패: email={}", toEmail);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        User fromUser = userRepository.findById(fromUserId)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 신청자 사용자 찾기 실패: 신청자 ID={}", fromUserId);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        Follow follow = followRepository
                .findByUserAndFollowingAndStatus(fromUser, toUser, FollowStatus.PENDING)
                .orElseThrow(() -> {
                    log.warn("[ACCEPT FOLLOW] 대기 중인 팔로우 요청 없음: from={}, to={}", fromUser.getId(), toUser.getId());
                    return new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND);
                });

        follow.accept();
        log.info("[ACCEPT FOLLOW] 팔로우 요청 수락 완료: 신청자={}, 수락자={}", fromUser.getId(), toUser.getId());
    }

    /** 현재 로그인 사용자가 targetUserId 언팔 */
    @Transactional
    public void unfollow(Authentication auth, Long targetUserId) {
        log.info("[UNFOLLOW] 요청 시작: 사용자={}, 대상={}", auth.getName(), targetUserId);

        String email = auth.getName();
        User follower = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[UNFOLLOW] 팔로워 사용자 찾기 실패: email={}", email);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> {
                    log.warn("[UNFOLLOW] 대상 사용자 찾기 실패: 대상 ID={}", targetUserId);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        Follow follow = followRepository.findByUserAndFollowing(follower, targetUser)
                .orElseThrow(() -> {
                    log.warn("[UNFOLLOW] 팔로우 관계 없음: from={}, to={}", follower.getId(), targetUser.getId());
                    return new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND);
                });

        followRepository.delete(follow);
        log.info("[UNFOLLOW] 언팔로우 성공: from={}, to={}", follower.getId(), targetUser.getId());
    }

    /** 내(현재 로그인 사용자)가 보낸 사람(팔로잉) 나한테 메시지를 보낸사람 조회 */
    @Transactional(readOnly = true)
    public List<FollowDTO> getMyFollowsByStatus(Authentication auth, FollowStatus status, boolean isFollowers) {
        log.info("[GET FOLLOWS] 요청 시작: 사용자={}, 상태={}, 팔로워 조회 여부={}", auth.getName(), status, isFollowers);

        String email = auth.getName();
        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[GET FOLLOWS] 사용자 찾기 실패: email={}", email);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        Stream<Follow> followStream;

        if (isFollowers) {
            followStream = followRepository.findByFollowingAndStatus(me, status).stream();
            log.info("[GET FOLLOWS] 팔로워 목록 조회: 사용자={}", me.getId());
        } else { // false이면 내가 팔로우하는 사람들을 조회
            followStream = followRepository.findByUserAndStatus(me, status).stream();
            log.info("[GET FOLLOWS] 팔로잉 목록 조회: 사용자={}", me.getId());
        }

        List<FollowDTO> result = followStream
                .map(follow -> {
                    User targetUser = isFollowers ? follow.getUser() : follow.getFollowing();
                    return new FollowDTO(
                            targetUser.getId(),
                            targetUser.getFirstName() + targetUser.getLastName(),
                            targetUser.getCountry(),
                            targetUser.getSex()
                    );
                })
                .collect(Collectors.toList());

        log.info("[GET FOLLOWS] 조회 완료: 총 {}명의 사용자 반환", result.size());
        return result;
    }

    /** 상대(fromUserId)가 나에게 보낸 요청 거절 */
    @Transactional
    public void declineFollow(Authentication auth, Long fromUserId) {
        log.info("[DECLINE FOLLOW] 요청 시작: 거절자={}, 신청자={}", auth.getName(), fromUserId);

        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> {
                    log.warn("[DECLINE FOLLOW] 거절자 사용자 찾기 실패: email={}", toEmail);
                    return new BusinessException(ErrorCode.USER_NOT_FOUND);
                });

        Follow followReq = followRepository
                .findByUser_IdAndFollowing_IdAndStatus(fromUserId, toUser.getId(), FollowStatus.PENDING)
                .orElseThrow(() -> {
                    log.warn("[DECLINE FOLLOW] 대기 중인 요청 없음: from={}, to={}", fromUserId, toUser.getId());
                    return new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND);
                });

        followRepository.delete(followReq);
        log.info("[DECLINE FOLLOW] 팔로우 요청 거절 완료: 신청자={}, 거절자={}", fromUserId, toUser.getId());
    }
}