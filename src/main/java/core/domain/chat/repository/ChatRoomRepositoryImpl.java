package core.domain.chat.repository;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import core.domain.chat.entity.QChatMessage;
import core.domain.chat.entity.QChatParticipant;
import core.domain.user.entity.QUser;
import core.global.enums.chat.ChatParticipantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
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
        QChatMessage messageCountAlias = new QChatMessage("messageCountAlias");

        var participantCountSubQuery = JPAExpressions.select(chatParticipant.count())
                .from(chatParticipant)
                .where(chatParticipant.chatRoom.id.eq(chatRoom.id)
                        .and(chatParticipant.status.eq(ChatParticipantStatus.ACTIVE)));

        Expression<Long> messageCountSubQuery = JPAExpressions.select(messageCountAlias.count())
                .from(messageCountAlias)
                .where(messageCountAlias.chatRoom.id.eq(chatRoom.id));

        List<ChatRoomListResponse> content = query
                .select(Projections.constructor(ChatRoomListResponse.class,
                        chatRoom.id,
                        chatRoom.roomName,
                        chatRoom.isGroup,
                        participantCountSubQuery,
                        chatRoom.createdAt,
                        messageCountSubQuery
                ))
                .from(chatRoom)
                .leftJoin(chatRoom.participants, searchParticipant)
                .leftJoin(searchParticipant.user, searchUser)
                .where(
                        keywordContains(condition.keyword(), searchUser),
                        filterByType(condition.type())
                )
                .groupBy(chatRoom.id)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(
                        getOrderSpecifier(pageable.getSort()),
                        chatRoom.createdAt.desc(),
                        chatRoom.id.desc()
                )
                .fetch();

        Long total = query
                .select(chatRoom.countDistinct())
                .from(chatRoom)
                .leftJoin(chatRoom.participants, searchParticipant)
                .leftJoin(searchParticipant.user, searchUser)
                .where(
                        keywordContains(condition.keyword(), searchUser),
                        filterByType(condition.type())
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

    private BooleanExpression filterByType(String type) {
        if ("GROUP".equals(type)) {
            return chatRoom.isGroup.isTrue();
        }
        if ("PAIR".equals(type)) {
            return chatRoom.isGroup.isFalse();
        }
        return null;
    }

    private OrderSpecifier<?> getOrderSpecifier(Sort sort) {
        if (sort.isEmpty()) {
            return chatRoom.createdAt.desc();
        }

        for (Sort.Order order : sort) {
            Order direction = order.isAscending() ? Order.ASC : Order.DESC;

            if ("messageCount".equals(order.getProperty())) {
                QChatMessage sortMessage = new QChatMessage("sortMessage");

                var messageCountExpression = JPAExpressions
                        .select(sortMessage.count())
                        .from(sortMessage)
                        .where(sortMessage.chatRoom.id.eq(chatRoom.id));

                return new OrderSpecifier<>(direction, messageCountExpression);
            }

            if ("updatedAt".equals(order.getProperty())) {
                return new OrderSpecifier<>(
                    direction,
                    new CaseBuilder()
                        .when(chatRoom.lastMessageSentAt.isNull())
                        .then(chatRoom.createdAt)
                        .otherwise(chatRoom.lastMessageSentAt)
                );
            }

            if ("createdAt".equals(order.getProperty())) {
                return new OrderSpecifier<>(direction, chatRoom.createdAt);
            }
        }

        return chatRoom.createdAt.desc();
    }
}
