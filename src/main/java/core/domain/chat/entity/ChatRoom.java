package core.domain.chat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import core.domain.user.entity.User;
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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;
    public ChatRoom(Boolean group, Instant createdAt) {
        this.group = group;
        this.createdAt = createdAt;
    }
    public void addParticipant(ChatParticipant participant) {
        participants.add(participant);
    }
    public void changeToGroupChat() {
        this.group = true;
    }
}
