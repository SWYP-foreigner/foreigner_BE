package core.domain.user.service;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.dto.RecentMessageDto;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.dto.RecentCommentDto;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.repository.NotificationRepository;
import core.domain.post.dto.admin.RecentPostDto;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.apple.service.AppleWithdrawalService;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.FollowStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.userfeedback.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserFeedbackRepository userFeedbackRepository;
    private final ImageService imageService;
    @Transactional(readOnly = true)
    public UserBasicInfoDto getUserBasicInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

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
            return new FollowingInfoDto(friend.getId(), friend.getFirstName()+" "+friend.getLastName(), friend.getEmail());
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
        Page<ChatParticipant> participants = chatParticipantRepository.findByUserId(userId, pageable);

        return participants.map(participant -> {
            ChatRoom room = participant.getChatRoom();
            String roomName = room.getRoomName();

            if (!room.getIsGroup()) {
                List<String> names = chatParticipantRepository.findParticipantNamesByRoomId(room.getId());

                if (!names.isEmpty()) {
                    roomName = String.join(", ", names);
                } else {
                    roomName = "(참여자 없음)";
                }
            }

            return new ChatRoomInfoDto(
                    room.getId(),
                    roomName,
                    room.getIsGroup(),
                    room.getParticipants().size()
            );
        });
    }

    @Transactional
    public void deletePost(Long postId) {
        postRepository.deleteById(postId);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        commentRepository.deleteById(commentId);
    }

    @Transactional
    public void deleteMessage(Long messageId) {
        chatMessageRepository.deleteById(messageId);
    }

    @Transactional
    public void deleteChatRoom(Long chatRoomId) {
        chatMessageRepository.deleteAllByChatRoomId(chatRoomId);
        chatRoomRepository.deleteById(chatRoomId);
    }

    /**
     * 특정 사용자를 DB에서 영구적으로 삭제합니다. (Hard Delete)
     * @param userId 삭제할 사용자의 ID
     */
    @Transactional
    public void hardDeleteUser(Long userId) {
        Optional<User> user =userRepository.findById(userId);
        User NowUser = new User();
        if(user.isPresent()) {
            NowUser = user.get();
        }
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);

        for (ChatRoom chatRoom : ownedChatRooms) {
            List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);

            if (!participants.isEmpty()) {
                User newOwner = participants.get(0).getUser();
                chatRoom.changeOwner(newOwner);
                chatRoomRepository.save(chatRoom);
            } else {
                chatRoomRepository.delete(chatRoom);
            }
        }
        blockPostRepository.deleteAllBlockPostsRelatedToUser(userId);
        List<Post> userPosts = postRepository.findAllByAuthorId(userId);
        if (userPosts != null && !userPosts.isEmpty()) {
            commentRepository.deleteAllByPostIn(userPosts);
            bookmarkRepository.deleteAllByPostIn(userPosts);
            postRepository.deleteAll(userPosts);
        }

        commentRepository.deleteAllByAuthorId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        followRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);
        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        imageService.deleteUserProfileImage(userId);
        blockRepository.deleteAllByUserOrBlocked(NowUser);
        chatParticipantRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllBySenderId(userId);
        userNotificationSettingRepository.deleteAllByUserId(userId);
        userDeviceTokenRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByActorId(userId);
        userFeedbackRepository.deleteAllByUserIdExplicit(userId);
        userRepository.delete(NowUser);
        log.info(">>>> Deleted user entity for userId: {}", userId);
    }

    @Transactional(readOnly = true)
    public Page<RecentPostDto> getRecentPostsForUser(Long userId, Pageable pageable) {
        return postRepository.findByAuthorId(userId, pageable)
                .map(RecentPostDto::from);
    }

    @Transactional(readOnly = true)
    public Page<RecentCommentDto> getRecentCommentsForUser(Long userId, Pageable pageable) {
        return commentRepository.findByAuthorId(userId, pageable)
                .map(RecentCommentDto::from);
    }

    @Transactional(readOnly = true)
    public Page<RecentMessageDto> getRecentMessagesForUser(Long userId, Pageable pageable) {
        return chatMessageRepository.findBySenderId(userId, pageable)
                .map(RecentMessageDto::from);
    }
}
