package core.domain.chat.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.chat.dto.ChatMessageSearchRequest;
import core.domain.chat.dto.ChatMessageSearchResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

import static core.domain.chat.entity.QChatMessage.chatMessage;
import static core.domain.chat.entity.QChatRoom.chatRoom;
import static core.domain.user.entity.QUser.user;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepositoryCustom {

    private final JPAQueryFactory query;

    @Override
    public Page<ChatMessageSearchResultDto> searchMessages(ChatMessageSearchRequest condition, Pageable pageable) {

        List<ChatMessageSearchResultDto> content = query
                .select(Projections.constructor(ChatMessageSearchResultDto.class,
                        chatMessage.id,
                        chatRoom.id,
                        chatRoom.roomName,
                        user.id,
                        Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName),
                        user.email,
                        chatMessage.content,
                        chatMessage.sentAt
                ))
                .from(chatMessage)
                .join(chatMessage.chatRoom, chatRoom)
                .join(chatMessage.sender, user)
                .where(
                        keywordContains(condition.keyword()),
                        senderEmailContains(condition.senderEmail()),
                        senderNameContains(condition.senderName())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(chatMessage.sentAt.desc())
                .fetch();

        long total = query.select(chatMessage.id).from(chatMessage)
                .join(chatMessage.chatRoom, chatRoom)
                .join(chatMessage.sender, user)
                .where(
                        keywordContains(condition.keyword()),
                        senderEmailContains(condition.senderEmail()),
                        senderNameContains(condition.senderName())
                )
                .fetchCount();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression keywordContains(String keyword) {
        return StringUtils.hasText(keyword) ? chatMessage.content.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression senderEmailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression senderNameContains(String name) {
        return StringUtils.hasText(name) ? user.firstName.containsIgnoreCase(name) : null;
    }
}
