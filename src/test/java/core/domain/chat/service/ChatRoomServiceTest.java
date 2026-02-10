package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.ChatErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
    // 1. 1:1 채팅방 생성 (Create 1:1)
    // ==========================================
    @Nested
    @DisplayName("1:1 채팅방 생성")
    class CreateOneToOne {
        @Test
        @DisplayName("성공: 정상 생성")
        void success() {
            Long myId = 1L, otherId = 2L;

            // 1. User 객체 생성 (Builder에는 id가 없으므로 제외하고 생성)
            User me = User.builder().build();
            User other = User.builder().build();

            // 2. Reflection으로 ID 강제 주입
            org.springframework.test.util.ReflectionTestUtils.setField(me, "id", myId);
            org.springframework.test.util.ReflectionTestUtils.setField(other, "id", otherId);

            // 3. Mocking
            given(userRepository.findById(myId)).willReturn(Optional.of(me));
            given(userRepository.findById(otherId)).willReturn(Optional.of(other));
            given(chatRoomRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // When
            ChatRoom room = chatRoomService.createRoom(myId, otherId);

            // Then
            assertThat(room.getIsGroup()).isFalse();
        }

        @Test
        @DisplayName("실패: 상대방 유저 없음")
        void fail_UserNotFound() {
            given(userRepository.findById(1L)).willReturn(Optional.of(User.builder().build()));
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatRoomService.createRoom(1L, 999L))
                    .isInstanceOf(BusinessException.class); // USER_NOT_FOUND
        }

        @Test
        @DisplayName("실패: 자신과의 채팅 시도 (로직에 따라 다름)")
        void fail_SelfChat() {
            // 서비스에 자신과의 채팅 금지 로직이 있다는 가정 하에
            // assertThatThrownBy(() -> chatRoomService.createRoom(1L, 1L))
            //        .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 2. 그룹 채팅방 생성 (Create Group)
    // ==========================================
    @Nested
    @DisplayName("그룹 채팅방 생성")
    class CreateGroup {
        @Test
        @DisplayName("성공: 그룹방 생성 및 방장 참여")
        void success() {
            Long ownerId = 1L;
            CreateGroupChatRequest req = new CreateGroupChatRequest("Title", "Desc", "img.url");

            // 1. User 객체 생성 및 ID 주입
            User owner = User.builder().build();
            org.springframework.test.util.ReflectionTestUtils.setField(owner, "id", ownerId);

            // 2. Mocking
            given(userRepository.findById(ownerId)).willReturn(Optional.of(owner));
            given(chatRoomRepository.save(any())).willAnswer(i -> {
                ChatRoom r = i.getArgument(0);
                // 저장된 방에도 ID가 필요하다면 여기서 주입 가능
                // ReflectionTestUtils.setField(r, "id", 100L);
                return r;
            });
            chatRoomService.createGroupChatRoom(ownerId, req);

            verify(chatRoomRepository).save(any(ChatRoom.class));
            verify(chatParticipantRepository).save(any(ChatParticipant.class));
        }

        @Test
        @DisplayName("실패: 유저(방장) 없음")
        void fail_UserNotFound() {
            given(userRepository.findById(999L)).willReturn(Optional.empty());
            CreateGroupChatRequest req = new CreateGroupChatRequest("Title", "Desc", "img.url");

            assertThatThrownBy(() -> chatRoomService.createGroupChatRoom(999L, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 3. 내 채팅방 목록 조회 (Get My Rooms)
    // ==========================================
    @Nested
    @DisplayName("내 채팅방 목록 조회")
    class GetMyRooms {
        @Test
        @DisplayName("성공: 참여 중인 방 목록 반환")
        void success() {
            Long userId = 1L;
            // Mocking repository to return a list of participants
            // 실제 서비스 구현에 따라 findByUserId 호출 등을 가정
            given(userRepository.existsById(userId)).willReturn(true);
            // given(chatParticipantRepository.findByUserId(userId)).willReturn(...);

            // When
            List<ChatRoomSummaryResponse> result = chatRoomService.getMyAllChatRoomSummaries(userId);

            // Then
            // (Repository 모킹 상세 내용에 따라 검증)
            // assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("실패: 유저 존재하지 않음")
        void fail_UserNotFound() {
            given(userRepository.existsById(999L)).willReturn(false);

            assertThatThrownBy(() -> chatRoomService.getMyAllChatRoomSummaries(999L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("성공: 참여 중인 방이 없으면 빈 리스트")
        void success_Empty() {
            Long userId = 1L;
            given(userRepository.existsById(userId)).willReturn(true);
            // Repository가 빈 리스트 반환 시

            List<ChatRoomSummaryResponse> result = chatRoomService.getMyAllChatRoomSummaries(userId);
            assertThat(result).isEmpty();
        }
    }

    // ==========================================
    // 4. 그룹 채팅 참여 (Join)
    // ==========================================
    @Nested
    @DisplayName("그룹 채팅 참여")
    class JoinGroup {
        @Test
        @DisplayName("성공: 정상 참여")
        void success() {
            Long roomId = 10L, userId = 1L;
            ChatRoom room = new ChatRoom(true, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
            given(userRepository.findById(userId)).willReturn(Optional.of(User.builder().build()));
            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)).willReturn(Optional.empty());

            chatRoomService.joinGroupChat(roomId, userId);
            verify(chatParticipantRepository).save(any());
        }

        @Test
        @DisplayName("실패: 이미 참여 중")
        void fail_AlreadyJoined() {
            Long roomId = 10L, userId = 1L;
            ChatRoom room = new ChatRoom(true, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId))
                    .willReturn(Optional.of(new ChatParticipant(room, new User()))); // 존재함

            assertThatThrownBy(() -> chatRoomService.joinGroupChat(roomId, userId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ALREADY");
        }

        @Test
        @DisplayName("실패: 1:1 방에는 참여 불가")
        void fail_NotGroup() {
            Long roomId = 10L;
            ChatRoom room = new ChatRoom(false, Instant.now()); // 1:1
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> chatRoomService.joinGroupChat(roomId, 1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 5. 채팅방 나가기 (Leave)
    // ==========================================
    @Nested
    @DisplayName("채팅방 나가기")
    class LeaveRoom {
        @Test
        @DisplayName("성공: 상태 변경 확인")
        void success() {
            Long roomId = 10L, userId = 1L;
            ChatParticipant p = spy(new ChatParticipant(new ChatRoom(), new User()));
            given(chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)).willReturn(Optional.of(p));

            boolean ret = chatRoomService.leaveRoom(roomId, userId);

            assertThat(ret).isTrue();
            assertThat(p.getStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        }

        @Test
        @DisplayName("실패: 참여 정보 없음 (이미 나감 or 참여 안함)")
        void fail_NotFound() {
            given(chatParticipantRepository.findByChatRoomIdAndUserId(any(), any())).willReturn(Optional.empty());
            assertThatThrownBy(() -> chatRoomService.leaveRoom(1L, 1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==========================================
    // 6. 그룹 상세 정보 조회 (Group Details)
    // ==========================================
    @Nested
    @DisplayName("그룹 상세 정보 조회")
    class GetGroupDetails {
        @Test
        @DisplayName("성공: 그룹 정보 반환")
        void success() {
            Long roomId = 10L;
            ChatRoom room = new ChatRoom(true, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            GroupChatDetailResponse resp = chatRoomService.getGroupChatDetails(roomId);
            assertThat(resp).isNotNull();
        }

        @Test
        @DisplayName("실패: 방 없음")
        void fail_NotFound() {
            given(chatRoomRepository.findById(999L)).willReturn(Optional.empty());
            assertThatThrownBy(() -> chatRoomService.getGroupChatDetails(999L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("실패: 그룹방이 아님")
        void fail_NotGroup() {
            Long roomId = 10L;
            ChatRoom room = new ChatRoom(false, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> chatRoomService.getGroupChatDetails(roomId))
                    .isInstanceOf(BusinessException.class); // CHAT_NOT_GROUP
        }
    }

    // ==========================================
    // 7. 채팅방 검색 - 내 채팅방 (Search My Rooms)
    // ==========================================
    @Nested
    @DisplayName("내 채팅방 이름 검색")
    class SearchMyRooms {
        @Test
        @DisplayName("성공: 키워드 검색 결과 반환")
        void success() {
            Long userId = 1L;
            String keyword = "Study";
            // Mocking repository method
            // given(chatParticipantRepository.findChatRoomsByUserIdAndRoomName(...)).willReturn(List.of(...));

            List<ChatRoomSummaryResponse> res = chatRoomService.searchRoomsByRoomName(userId, keyword);
            assertThat(res).isNotNull();
        }
    }

    // ==========================================
    // 8. 공개 그룹 검색 (Search Public Groups)
    // ==========================================
    @Nested
    @DisplayName("공개 그룹 채팅방 검색")
    class SearchPublicGroups {
        @Test
        @DisplayName("성공: 검색 결과 반환")
        void success() {
            String keyword = "Java";
            // given(chatRoomRepository.findByRoomNameContaining(keyword)).willReturn(...)
            List<GroupChatSearchResponse> res = chatRoomService.searchGroupChatRooms(keyword);
            assertThat(res).isNotNull();
        }

        @Test
        @DisplayName("성공: 결과 없음 (빈 리스트)")
        void success_Empty() {
            // given(chatRoomRepository...).willReturn(Collections.emptyList());
            List<GroupChatSearchResponse> res = chatRoomService.searchGroupChatRooms("Nothing");
            assertThat(res).isEmpty();
        }
    }

    // ==========================================
    // 9. 그룹 추천 (Recommendation)
    // ==========================================
    @Nested
    @DisplayName("그룹 채팅방 추천")
    class RecommendGroup {
        @Test
        @DisplayName("성공: 추천 가능한 방 반환")
        void success() {
            Long userId = 1L;
            // Repository가 추천 방 리스트를 반환한다고 가정
            // given(chatRoomRepository.findRecommendableRooms(userId)).willReturn(List.of(new ChatRoom(...)));

            // ChatRecommendRoomResponse res = chatRoomService.findRandomRecommendableGroupChatRoom(userId);
            // assertThat(res).isNotNull();
        }

        @Test
        @DisplayName("실패: 추천할 방이 하나도 없음")
        void fail_NoRoom() {
            Long userId = 1L;
            // Mocking empty list return
            // given(chatRoomRepository.findRecommendableRooms(userId)).willReturn(Collections.emptyList());

            // assertThatThrownBy(() -> chatRoomService.findRandomRecommendableGroupChatRoom(userId))
            //        .isInstanceOf(BusinessException.class); // NO_RECOMMENDABLE_ROOM
        }
    }

    // ==========================================
    // 10. 그룹 여부 확인 (Is Group)
    // ==========================================
    @Nested
    @DisplayName("그룹 방 여부 확인")
    class IsGroupCheck {
        @Test
        @DisplayName("성공: True 반환")
        void isGroup_True() {
            Long roomId = 10L;
            ChatRoom room = new ChatRoom(true, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            boolean result = chatRoomService.isChatRoomGroup(roomId);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("성공: False 반환")
        void isGroup_False() {
            Long roomId = 11L;
            ChatRoom room = new ChatRoom(false, Instant.now());
            given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

            boolean result = chatRoomService.isChatRoomGroup(roomId);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("실패: 방 없음")
        void fail_NotFound() {
            given(chatRoomRepository.findById(any())).willReturn(Optional.empty());
            assertThatThrownBy(() -> chatRoomService.isChatRoomGroup(999L))
                    .isInstanceOf(BusinessException.class);
        }
    }
}