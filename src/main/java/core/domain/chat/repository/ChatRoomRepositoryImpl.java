package core.domain.chat.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import core.domain.chat.entity.QChatParticipant;
import core.domain.user.entity.QUser;
import core.global.enums.ChatParticipantStatus;
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
        QChatParticipant searchParticipant = new QChatParticipant("searchParticipant");
        QUser searchUser = new QUser("searchUser");

        var participantCountSubQuery = JPAExpressions.select(chatParticipant.count())
                .from(chatParticipant)
                .where(chatParticipant.chatRoom.id.eq(chatRoom.id)
                        .and(chatParticipant.status.eq(ChatParticipantStatus.ACTIVE)));

        List<ChatRoomListResponse> content = query
                .select(Projections.constructor(ChatRoomListResponse.class,
                        chatRoom.id,
                        chatRoom.roomName,
                        chatRoom.isGroup,
                        participantCountSubQuery,
                        chatRoom.createdAt
                ))
                .from(chatRoom)
                .leftJoin(chatRoom.participants, searchParticipant)
                .leftJoin(searchParticipant.user, searchUser)
                .where(
                        keywordContains(condition.keyword(), searchUser)
                )
                .groupBy(chatRoom.id)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(chatRoom.createdAt.desc())
                .fetch();

        Long total = query
                .select(chatRoom.countDistinct())
                .from(chatRoom)
                .leftJoin(chatRoom.participants, searchParticipant)
                .leftJoin(searchParticipant.user, searchUser)
                .where(
                        keywordContains(condition.keyword(), searchUser)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0);
    }

    private BooleanExpression keywordContains(String keyword, QUser searchUser) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }

        return chatRoom.roomName.containsIgnoreCase(keyword)
                .or(searchUser.lastName.containsIgnoreCase(keyword))
                .or(searchUser.firstName.containsIgnoreCase(keyword))
                .or(searchUser.email.containsIgnoreCase(keyword));
    }
}
