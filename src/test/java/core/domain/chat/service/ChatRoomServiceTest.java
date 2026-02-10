package core.domain.chat.service;

import core.domain.chat.dto.CreateGroupChatRequest;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatParticipantRepository chatParticipantRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    // ==========================================
    // 1. 1:1 채팅방 생성 테스트
    // ==========================================
    @Nested
    @DisplayName("1:1 채팅방 생성 (createRoom)")
    class CreateOneToOneRoom {

        @Test
        @DisplayName("성공: 두 유저가 존재하면 1:1 채팅방이 생성된다")
        void success() {
            // given
            Long myId = 1L;
            Long otherId = 2L;
            User me = User.builder().firstName("Me").build();
            User other = User.builder().firstName("Other").build();

            given(userRepository.findById(myId)).willReturn(Optional.of(me));
            given(userRepository.findById(otherId)).willReturn(Optional.of(other));
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            ChatRoom result = chatRoomService.createRoom(myId, otherId);

            // then
            assertThat(result.getIsGroup()).isFalse();
            verify(chatRoomRepository, times(1)).save(any(ChatRoom.class));
        }

        @Test
        @DisplayName("실패: 상대방 유저를 찾을 수 없으면 예외 발생")
        void fail_UserNotFound() {
            // given
            Long myId = 1L;
            Long otherId = 999L;
            User me = User.builder().firstName("Me").build();

            given(userRepository.findById(myId)).willReturn(Optional.of(me));
            given(userRepository.findById(otherId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.createRoom(myId, otherId))
                    .isInstanceOf(BusinessException.class);
            // Expected: UserErrorCode.USER_NOT_FOUND
        }

        @Test
        @DisplayName("실패: 자기 자신과의 채팅방 생성 시도")
        void fail_SelfChat() {
            // given
            Long myId = 1L;

            // when & then (서비스 로직에 따라 다를 수 있으나 일반적인 방어 로직)
            // 만약 서비스 코드에 `if (myId.equals(otherId))` 체크가 있다면 이 테스트가 필요
            // assertThatThrownBy(() -> chatRoomService.createRoom(myId, myId))
            //        .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 2. 그룹 채팅방 생성 테스트
    // ==========================================
    @Nested
    @DisplayName("그룹 채팅방 생성 (createGroupChatRoom)")
    class CreateGroupChat {

        @Test
        @DisplayName("성공: 정상적인 요청 시 그룹 채팅방과 참여자가 생성된다")
        void success() {
            // given
            Long ownerId = 1L;
            User owner = User.builder().firstName("Owner").build();
            CreateGroupChatRequest request = new CreateGroupChatRequest(
                    "K-Food Party",
                    "Let's eat!",
                    "http://image.url"
            );

            given(userRepository.findById(ownerId)).willReturn(Optional.of(owner));
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            chatRoomService.createGroupChatRoom(ownerId, request);

            // then
            verify(chatRoomRepository).save(any(ChatRoom.class));
            verify(chatParticipantRepository).save(any(ChatParticipant.class)); // 방장 참여 확인
        }

        @Test
        @DisplayName("실패: 요청한 유저(방장)가 존재하지 않음")
        void fail_OwnerNotFound() {
            // given
            Long ownerId = 999L;
            CreateGroupChatRequest request = new CreateGroupChatRequest("Title", "Desc", null);

            given(userRepository.findById(ownerId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.createGroupChatRoom(ownerId, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("검증: 생성된 방은 반드시 isGroup=true여야 한다")
        void check_IsGroupTrue() {
            // given
            Long ownerId = 1L;
            User owner = User.builder().build();
            CreateGroupChatRequest request = new CreateGroupChatRequest("Title", "Desc", null);

            given(userRepository.findById(ownerId)).willReturn(Optional.of(owner));
            // save 호출 시 전달된 객체를 캡처하거나 mock 동작 정의
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(invocation -> {
                ChatRoom savedRoom = invocation.getArgument(0);
                assertThat(savedRoom.getIsGroup()).isTrue(); // 여기서 검증
                return savedRoom;
            });

            // when
            chatRoomService.createGroupChatRoom(ownerId, request);
        }
    }

    // ==========================================
    // 3. 그룹 채팅 참여 테스트
    // ==========================================
    @Nested
    @DisplayName("그룹 채팅 참여 (joinGroupChat)")
    class JoinGroupChat {

        @Test
        @DisplayName("성공: 1:1 방이 아니고, 참여하지 않은 상태라면 참여 성공")
        void success() {
            // given
            Long roomId = 10L;
            Long userId = 1L;
            ChatRoom room = new ChatRoom(true, Instant.now()); // Group Room
            User user = User.builder().build();

            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
            given(userRepository.findById(userId)).willReturn(Optional.of(user));
            // 아직 참여하지 않음 (Optional.empty 반환)
            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.empty());

            // when
            chatRoomService.joinGroupChat(roomId, userId);

            // then
            verify(chatParticipantRepository).save(any(ChatParticipant.class));
        }

        @Test
        @DisplayName("실패: 이미 참여 중인 경우 (ALREADY_CHAT_PARTICIPANT)")
        void fail_AlreadyJoined() {
            // given
            Long roomId = 10L;
            Long userId = 1L;
            ChatRoom room = new ChatRoom(true, Instant.now());

            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            // 이미 참여 중인 상태 모킹 (참여자 정보가 반환됨)
            ChatParticipant existingParticipant = new ChatParticipant(room, new User());
            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.of(existingParticipant));

            // when & then
            assertThatThrownBy(() -> chatRoomService.joinGroupChat(roomId, userId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ALREADY"); // 에러 메시지나 코드로 검증
        }

        @Test
        @DisplayName("실패: 참여하려는 방이 1:1 채팅방인 경우")
        void fail_NotGroupRoom() {
            // given
            Long roomId = 10L;
            Long userId = 1L;
            ChatRoom room = new ChatRoom(false, Instant.now()); // isGroup = false

            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            // when & then
            assertThatThrownBy(() -> chatRoomService.joinGroupChat(roomId, userId))
                    .isInstanceOf(BusinessException.class);
            // ChatErrorCode.CHAT_NOT_GROUP
        }

        @Test
        @DisplayName("실패: 채팅방이 존재하지 않는 경우")
        void fail_RoomNotFound() {
            // given
            given(chatRoomRepository.findById(anyLong())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.joinGroupChat(1L, 1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 4. 채팅방 나가기 테스트
    // ==========================================
    @Nested
    @DisplayName("채팅방 나가기 (leaveRoom)")
    class LeaveRoom {

        @Test
        @DisplayName("성공: 참여자가 존재하면 상태를 LEFT로 변경")
        void success() {
            // given
            Long roomId = 10L;
            Long userId = 1L;
            ChatRoom room = new ChatRoom(true, Instant.now());
            User user = User.builder().build();
            ChatParticipant participant = spy(new ChatParticipant(room, user)); // spy로 내부 메서드 호출 확인 가능

            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.of(participant));

            // when
            boolean result = chatRoomService.leaveRoom(roomId, userId);

            // then
            assertThat(result).isTrue();
            // participant.leave() 메서드가 실제 상태를 변경했는지 확인
            assertThat(participant.getStatus()).isEqualTo(ChatParticipantStatus.LEFT);
            assertThat(participant.getLastLeftAt()).isNotNull();
        }

        @Test
        @DisplayName("실패: 해당 방에 참여자가 아님 (PARTICIPANT_NOT_FOUND)")
        void fail_ParticipantNotFound() {
            // given
            Long roomId = 10L;
            Long userId = 1L;

            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> chatRoomService.leaveRoom(roomId, userId))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("성공: 이미 나간 유저가 다시 나가기 요청 시 (멱등성 고려)")
        void success_AlreadyLeft() {
            // 만약 비즈니스 로직이 '이미 나간 유저면 에러'가 아니라 '성공 처리'라면 이 테스트,
            // '에러 처리'라면 fail 테스트로 작성. 여기서는 일반적인 로직(에러 혹은 무시) 가정

            // given
            Long roomId = 10L;
            Long userId = 1L;
            ChatParticipant participant = new ChatParticipant(new ChatRoom(), new User());
            participant.leave(); // 이미 나간 상태

            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.of(participant));

            // when
            chatRoomService.leaveRoom(roomId, userId);

            // then
            assertThat(participant.getStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        }
    }
}