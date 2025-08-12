package core.domain.user.service;


import core.domain.user.dto.FollowDTO;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.FollowStatus;
import jakarta.persistence.EntityNotFoundException;
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
    public void follow(Long followerId, Long targetUserId) {
        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        if (follower.equals(targetUser)) {
            throw new IllegalArgumentException("자기 자신을 팔로우할 수 없습니다.");
        }

        // 이미 팔로우 중인지 확인
        followRepository.findByUserAndFollowing(follower, targetUser)
                .ifPresent(follow -> {
                    throw new IllegalArgumentException("이미 팔로우하고 있는 사용자입니다.");
                });

        // 새로운 팔로우 관계 생성 및 저장
        Follow follow = Follow.builder()
                .user(follower)
                .following(targetUser)
                .status(FollowStatus.PENDING)
                .build();

        followRepository.save(follow);
    }

    @Transactional
    public void acceptFollow(Long fromUserId, Long toUserId) {
        User follower = userRepository.findById(fromUserId)
                .orElseThrow(() -> new EntityNotFoundException("요청을 보낸 사용자를 찾을 수 없습니다."));

        User targetUser = userRepository.findById(toUserId)
                .orElseThrow(() -> new EntityNotFoundException("요청을 받은 사용자를 찾을 수 없습니다."));

        // PENDING 상태인 팔로우 요청을 찾습니다.
        Follow follow = followRepository.findByUserAndFollowingAndStatus(follower, targetUser, FollowStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 팔로우 요청입니다."));

        // 상태를 ACCEPTED로 변경하는 메서드 호출
        follow.accept();
    }

    @Transactional
    public void unfollow(Long followerId, Long targetUserId) {
        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        Follow follow = followRepository.findByUserAndFollowing(follower, targetUser)
                .orElseThrow(() -> new IllegalArgumentException("팔로우하지 않은 사용자입니다."));

        followRepository.delete(follow);
    }



    @Transactional(readOnly = true)
    public List<FollowDTO> getFollowingListByStatus(Long userId, FollowStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

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
}