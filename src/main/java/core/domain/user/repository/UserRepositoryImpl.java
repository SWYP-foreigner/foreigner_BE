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
                        createdAtBetween(condition.startDate(), condition.endDate()),

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
                        createdAtBetween(condition.startDate(), condition.endDate()),

                        user.userRole.ne(Role.VISITOR)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression emailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression nameContains(String name) {
        if (!StringUtils.hasText(name)) return null;

        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
        return fullName.containsIgnoreCase(name);
    }


    private BooleanExpression createdAtBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return null;
        }

        ZoneId zoneId = ZoneId.of("Asia/Seoul");

        if (startDate != null && endDate != null) {
            Instant startInstant = startDate.atStartOfDay(zoneId).toInstant();
            Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(zoneId).toInstant();
            return user.createdAt.between(startInstant, endInstant);
        }

        if (startDate != null) {
            Instant startInstant = startDate.atStartOfDay(zoneId).toInstant();
            return user.createdAt.goe(startInstant);
        }

        Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(zoneId).toInstant();
        return user.createdAt.loe(endInstant);
    }
}