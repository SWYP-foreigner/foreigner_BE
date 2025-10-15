package core.domain.user.service;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.FollowStatus;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.repository.ImageRepository;
import core.global.like.repository.LikeRepository;
import core.global.service.AppleWithdrawalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final LikeRepository likeRepository;
    private final ImageRepository imageRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final BlockPostRepository blockPostRepository;
    private final AppleWithdrawalService appleWithdrawalService;


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

    /**
     * 특정 사용자를 DB에서 영구적으로 삭제합니다. (Hard Delete)
     * @param userId 삭제할 사용자의 ID
     */
    @Transactional
    public void hardDeleteUser(Long userId) {
        // 1. 사용자 엔티티를 조회합니다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 2. 소셜 연동 해제 (Apple 사용자의 경우)
        // Admin이 탈퇴시킬 땐 accessToken이 없으므로, refreshToken 기반 로직만 수행
        if ("APPLE".equals(user.getProvider())) {
            appleWithdrawalService.revokeAppleToken(user);
        }

        // 3. 사용자와 관련된 모든 DB 데이터를 삭제합니다. (cleanupUserData 로직 재사용)
        log.info(">>>> Starting data cleanup for user ID: {}", userId);

        // 채팅방 소유권 이전 또는 삭제
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);
        for (ChatRoom chatRoom : ownedChatRooms) {
            List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);
            if (!participants.isEmpty()) {
                chatRoom.changeOwner(participants.get(0).getUser());
                chatRoomRepository.save(chatRoom);
            } else {
                chatRoomRepository.delete(chatRoom);
            }
        }

        // 게시글 관련 데이터 삭제
        List<Post> userPosts = postRepository.findAllByAuthorId(userId);
        if (userPosts != null && !userPosts.isEmpty()) {
            commentRepository.deleteAllByPostIn(userPosts);
            bookmarkRepository.deleteAllByPostIn(userPosts);
            postRepository.deleteAll(userPosts);
        }

        // 사용자가 직접 작성한 콘텐츠 및 관계 데이터 삭제
        commentRepository.deleteAllByAuthorId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        followRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);
        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        blockRepository.deleteAllByUserOrBlocked(user);
        chatParticipantRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllBySenderId(userId);
        blockPostRepository.deleteAllBlockPostsRelatedToUser(userId);

        // 4. 마지막으로 사용자 자체를 삭제합니다.
        userRepository.delete(user);
        log.info(">>>> Deleted user entity for userId: {}", userId);
    }
}
