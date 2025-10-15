package core.domain.user.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.user.dto.UserSearchRequest;
import core.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
                        createdAtBetween(condition.startDate(), condition.endDate())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(user.createdAt.desc(), user.id.desc())
                .fetch();

        long total = queryFactory
                .selectFrom(user)
                .where(
                        emailContains(condition.email()),
                        nameContains(condition.name()),
                        createdAtBetween(condition.startDate(), condition.endDate())
                )
                .fetchCount();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression emailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression nameContains(String name) {
        return StringUtils.hasText(name) ? user.name.containsIgnoreCase(name) : null;
    }

    private BooleanExpression createdAtBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return null;
        }
        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant = endDate.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);

        return user.createdAt.between(startInstant, endInstant);
    }
}