package core.domain.user.service;

import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.FollowStatus;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository; // 사용자 제공 Repository
    private final ChatParticipantRepository chatParticipantRepository; // 사용자 제공 Repository


    @Transactional(readOnly = true)
    public UserBasicInfoDto getUserBasicInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return UserBasicInfoDto.from(user);
    }

    @Transactional(readOnly = true)
    public Page<UserListResponse> searchUsers(UserSearchRequest request, Pageable pageable) {
        Page<User> userPage = userRepository.searchUsers(request, pageable);
        return userPage.map(UserListResponse::from);
    }

    /**
     * 사용자의 친구 목록 (수락된 관계)을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<FollowingInfoDto> getFollowingsForUser(Long userId, Pageable pageable) {
        Page<Follow> acceptedFollowsPage = followRepository.findAllAcceptedFollowsByUserId(userId, FollowStatus.ACCEPTED, pageable);
        return acceptedFollowsPage.map(follow -> {
            User friend = follow.getUser().getId().equals(userId) ? follow.getFollowing() : follow.getUser();
            return new FollowingInfoDto(friend.getId(), friend.getName(), friend.getEmail());
        });
    }

    /**
     * 사용자가 차단한 유저 목록을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<BlockedUserInfoDto> getBlockedUsersForUser(Long userId, Pageable pageable) {
        return blockRepository.findByUserId(userId, pageable)
                .map(BlockedUserInfoDto::from);
    }

    /**
     * 사용자가 참여 중인 채팅방 목록을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<ChatRoomInfoDto> getChatRoomsForUser(Long userId, Pageable pageable) {
        return chatParticipantRepository.findByUserId(userId, pageable)
                .map(ChatRoomInfoDto::from);
    }
}
