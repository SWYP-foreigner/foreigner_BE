package core.domain.user.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.user.dto.UserSearchRequest;
import core.domain.user.entity.User;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.time.*;
import java.util.List;

import static core.domain.user.entity.QUser.user;

@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<User> searchUsers(UserSearchRequest condition, Pageable pageable) {
        List<User> content = queryFactory
                .selectFrom(user)
                .where(
                        emailContains(condition.email()),
                        nameContains(condition.name()),
                        createdAtGoe(condition.startDate()),
                        createdAtLoe(condition.endDate()),

                        user.userRole.ne(Role.VISITOR)
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(user.createdAt.desc(), user.id.desc())
                .fetch();

        Long total = queryFactory
                .select(user.count())
                .from(user)
                .where(
                        emailContains(condition.email()),
                        nameContains(condition.name()),
                        createdAtGoe(condition.startDate()),
                        createdAtLoe(condition.endDate()),

                        user.userRole.ne(Role.VISITOR)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    private BooleanExpression emailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression nameContains(String name) {
        if (!StringUtils.hasText(name)) return null;
        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
        return fullName.containsIgnoreCase(name);
    }

    private BooleanExpression createdAtGoe(LocalDate startDate) {
        if (startDate == null) {
            return null;
        }

        Instant startInstant = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        return user.createdAt.goe(startInstant);
    }

    private BooleanExpression createdAtLoe(LocalDate endDate) {
        if (endDate == null) {
            return null;
        }

        Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return user.createdAt.loe(endInstant);
    }
}