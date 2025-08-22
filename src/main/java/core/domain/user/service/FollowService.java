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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowService {
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    /** 현재 로그인 사용자가 targetUserId를 팔로우 신청 */
    public void follow(Authentication auth, Long targetUserId) {
        String email = auth.getName(); // JWT subject=email 가정
        User follower = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));


        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow existing = followRepository.findByUserAndFollowing(follower, targetUser).orElse(null);
        if (existing != null) {
            switch (existing.getStatus()) {
                case PENDING  -> { return; } // 이미 신청중이면 무시
                case ACCEPTED -> throw new BusinessException(ErrorCode.FOLLOW_ALREADY_EXISTS);
            }
        }

        Follow follow = Follow.builder()
                .user(follower)
                .following(targetUser)
                .status(FollowStatus.PENDING)
                .build();

        followRepository.save(follow);
    }

    /** 상대(fromUserId)가 보낸 팔로우 요청을 '현재 로그인 사용자'가 수락 */
    public void acceptFollow(Authentication auth, Long fromUserId) {
        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        User fromUser = userRepository.findById(fromUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow follow = followRepository
                .findByUserAndFollowingAndStatus(fromUser, toUser, FollowStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));

        follow.accept(); // 엔티티 메서드에서 status = ACCEPTED 등 처리
    }

    /** 현재 로그인 사용자가 targetUserId 언팔 */
    public void unfollow(Authentication auth, Long targetUserId) {
        String email = auth.getName();
        User follower = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow follow = followRepository.findByUserAndFollowing(follower, targetUser)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));

        followRepository.delete(follow);
    }

    /** 내(현재 로그인 사용자)가 팔로우한 목록(=following) 중 특정 상태만 조회 */
    @Transactional(readOnly = true)
    public List<FollowDTO> getMyFollowingByStatus(Authentication auth, FollowStatus status) {
        String email = auth.getName();
        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return followRepository.findByUserAndStatus(me, status).stream()
                .map(Follow::getFollowing)
                .map(u -> new FollowDTO(
                        u.getId(),
                        u.getFirstName()+u.getLastName(),
                        u.getCountry(),
                        u.getSex()
                ))
                .toList();
    }

    /** 상대(fromUserId)가 나에게 보낸 요청 거절 */
    public void declineFollow(Authentication auth, Long fromUserId) {
        String toEmail = auth.getName();
        User toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow followReq = followRepository
                .findByUser_IdAndFollowing_IdAndStatus(fromUserId, toUser.getId(), FollowStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));

        followRepository.delete(followReq);
    }

}