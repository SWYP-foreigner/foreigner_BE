package core.domain.chat.entity;

import core.domain.user.entity.User;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDomainTest {

    @Test
    @DisplayName("채팅방 생성 및 참여자 추가 테스트")
    void createChatRoomAndAddParticipant() {
        // given
        User owner = User.builder()
                .firstName("GilDong")
                .email("test@test.com")
                .build();

        ChatRoom chatRoom = new ChatRoom(true, Instant.now(), "K-Pop Talk", "Let's talk!", owner);
        User participantUser = User.builder().firstName("ChulSoo").build();

        // when
        ChatParticipant participant = new ChatParticipant(chatRoom, participantUser);
        chatRoom.addParticipant(participant);

        // then
        assertThat(chatRoom.getRoomName()).isEqualTo("K-Pop Talk");
        assertThat(chatRoom.getOwner()).isEqualTo(owner);
        assertThat(chatRoom.getParticipants()).hasSize(1);
        assertThat(chatRoom.getParticipants().get(0).getUser()).isEqualTo(participantUser);
    }

    @Test
    @DisplayName("참여자 상태 변경(나가기/재참여) 테스트")
    void participantStatusChange() {
        // given
        ChatRoom chatRoom = new ChatRoom(false, Instant.now());
        User user = User.builder().firstName("TestUser").build();
        ChatParticipant participant = new ChatParticipant(chatRoom, user);

        // when (나가기)
        participant.leave();

        // then
        assertThat(participant.getStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        assertThat(participant.getLastLeftAt()).isNotNull();

        // when (재참여)
        participant.reJoin();

        // then
        assertThat(participant.getStatus()).isEqualTo(ChatParticipantStatus.ACTIVE);
        assertThat(participant.getLastLeftAt()).isNull();
    }

    @Test
    @DisplayName("알림 및 번역 설정 토글 테스트")
    void toggleSettings() {
        // given
        ChatParticipant participant = new ChatParticipant(new ChatRoom(), new User());

        // 초기값 확인
        assertThat(participant.isNotificationsEnabled()).isTrue();
        assertThat(participant.isTranslateEnabled()).isTrue();

        // when
        participant.setNotificationsEnabled(false);
        participant.toggleTranslation(false);

        // then
        assertThat(participant.isNotificationsEnabled()).isFalse();
        assertThat(participant.isTranslateEnabled()).isFalse();
    }
}