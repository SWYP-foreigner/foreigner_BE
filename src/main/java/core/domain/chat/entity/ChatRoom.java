package core.domain.chat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chat_room")
@Getter
@NoArgsConstructor

public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chatroom_id")
    private Long id;

    @Column(name = "is_group", nullable = false)
    private Boolean group;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    @Column(name = "room_name")
    private String roomName;
    @Column(name = "description")
    private String description;

    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatParticipant> participants = new ArrayList<>();

    @Column(name = "owner_id")
    private Long ownerId;
    public void addParticipant(ChatParticipant participant) {
        participants.add(participant);
    }
    /**
     테스트 데이터 생성용 생성자
     */
    public ChatRoom(Boolean group, Instant createdAt) {
        this.group = group;
        this.createdAt = createdAt;
    }

    /**
     * 1:1 채팅방 생성을 위한 생성자입니다.
     * 채팅방 이름이 필수인 경우에 사용합니다.
     *
     * @param group     채팅방이 그룹인지 여부
     * @param createdAt 채팅방 생성 시각
     * @param roomName  채팅방 이름
     */
    public ChatRoom(Boolean group, Instant createdAt, String roomName) {
        this.group = group;
        this.createdAt = createdAt;
        this.roomName = roomName;
    }

    /**
     * 그룹 채팅방 생성을 위한 전체 필드 생성자입니다.
     * 채팅방 이름, 설명, 오너까지 모든 정보를 포함할 때 사용합니다.
     *
     * @param group       채팅방이 그룹인지 여부 (항상 true)
     * @param createdAt   채팅방 생성 시각
     * @param roomName    채팅방 이름
     * @param description 채팅방 설명
     * @param ownerId       채팅방 소유자 (생성한 유저)
     */
    public ChatRoom(Boolean group, Instant createdAt, String roomName, String description, Long ownerId) {
        this.group = group;
        this.createdAt = createdAt;
        this.roomName = roomName;
        this.description = description;
        this.ownerId = ownerId;
    }

    /**
     *
     * 채팅방의 방장을 새로운 사용자로 변경합니다.
     * @param newOwnerId 새로운 방장이 될 사용자의 ID
     */
    public void changeOwner(Long newOwnerId) {
        this.ownerId = newOwnerId;
    }

}
