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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowService {
    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    @Transactional
    public void follow(String requesterUsername, Long targetUserId) {
        User follower = userRepository.findByName(requesterUsername)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow existing = followRepository.findByUserAndFollowing(follower, targetUser).orElse(null);
        if (existing != null) {
            // 멱등성 보장: 이미 PENDING이면 조용히 통과, ACCEPTED면 에러
            switch (existing.getStatus()) {
                case PENDING -> { return; }
                case ACCEPTED -> { throw new BusinessException(ErrorCode.FOLLOW_ALREADY_EXISTS); }
            }
        }

        Follow follow = Follow.builder()
                .user(follower)
                .following(targetUser)
                .status(FollowStatus.PENDING)
                .build();

        followRepository.save(follow);
    }

    @Transactional
    public void acceptFollow(Long fromUserId, String toUserName) {
        User follower = userRepository.findById(fromUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        User targetUser = userRepository.findByName(toUserName)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // PENDING 상태인 팔로우 요청을 찾습니다.
        Follow follow = followRepository.findByUserAndFollowingAndStatus(follower, targetUser, FollowStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));

        // 상태를 ACCEPTED로 변경하는 메서드 호출
        follow.accept();
    }

    @Transactional
    public void unfollow(String followerName, Long targetUserId) {
        User follower = userRepository.findByName(followerName)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Follow follow = followRepository.findByUserAndFollowing(follower, targetUser)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));

        followRepository.delete(follow);
    }


    @Transactional(readOnly = true)
    public List<FollowDTO> getFollowingListByStatus(String followingName, FollowStatus status) {
        User user = userRepository.findByName(followingName)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return followRepository.findByUserAndStatus(user, status).stream()
                .map(Follow::getFollowing)
                .map(following -> new FollowDTO(
                        following.getId(),
                        following.getName(),
                        following.getAge(),
                        following.getNationality(),
                        following.getSex()
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public void declineFollow(Long fromUserId, Long toUserId) {
        // PENDING 상태의 팔로우 요청을 찾아옵니다.
        Follow followRequest = followRepository.findByUser_IdAndFollowing_IdAndStatus(fromUserId, toUserId, FollowStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWER_NOT_FOUND));
        // 팔로우 요청을 삭제합니다.
        followRepository.delete(followRequest);
    }
}