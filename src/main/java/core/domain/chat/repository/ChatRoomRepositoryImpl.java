package core.domain.chat.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import core.global.enums.chat.ChatParticipantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

import static core.domain.chat.entity.QChatParticipant.chatParticipant;
import static core.domain.chat.entity.QChatRoom.chatRoom;

@Repository
@RequiredArgsConstructor
public class ChatRoomRepositoryImpl implements ChatRoomRepositoryCustom {

    private final JPAQueryFactory query;

    @Override
    public Page<ChatRoomListResponse> searchChatRooms(ChatRoomSearchRequest condition, Pageable pageable) {
        var participantCountSubQuery = JPAExpressions.select(chatParticipant.count())
                .from(chatParticipant)
                .where(chatParticipant.chatRoom.id.eq(chatRoom.id)
                        .and(chatParticipant.status.eq(ChatParticipantStatus.ACTIVE)));

        List<ChatRoomListResponse> content = query
                .select(Projections.constructor(ChatRoomListResponse.class,
                        chatRoom.id,
                        chatRoom.roomName,
                        chatRoom.group,
                        participantCountSubQuery,
                        chatRoom.createdAt
                ))
                .from(chatRoom)
                .where(
                        roomNameContains(condition.roomName())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(chatRoom.createdAt.desc())
                .fetch();

        long total = query.select(chatRoom.id).from(chatRoom)
                .where(
                        roomNameContains(condition.roomName())
                )
                .fetchCount();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression roomNameContains(String roomName) {
        return StringUtils.hasText(roomName) ? chatRoom.roomName.containsIgnoreCase(roomName) : null;
    }
}
