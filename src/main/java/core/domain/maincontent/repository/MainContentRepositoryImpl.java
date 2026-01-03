package core.domain.maincontent.repository;

import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.domain.maincontent.entity.KNewsContentType;
import core.domain.maincontent.entity.QMainPageContent;
import core.global.entity.image.entity.QImage;
import core.global.enums.ImageType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MainContentRepositoryImpl implements MainContentRepositoryCustom {
    private final JPAQueryFactory query;

    private static final QMainPageContent mainContent = QMainPageContent.mainPageContent;
    private static final QImage image = QImage.image;

    @Override
    public List<MainContentNewsListResponse> findLatestNews(KNewsContentType type,
                                                            Instant cursorCreatedAt,
                                                            Long cursorId,
                                                            int size) {
        return query
                .select(contentProjection(Expressions.numberTemplate(Long.class, "NULL")))
                .from(mainContent)
                .leftJoin(image).on(isLatestThumbnail())
                .where(
                        typeEq(type),
                        ltCursor(cursorCreatedAt, cursorId)
                )
                .orderBy(mainContent.createdAt.desc(), mainContent.id.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();
    }

    @Override
    public List<MainContentNewsListResponse> findPopularNews(KNewsContentType type,
                                                             Instant since,
                                                             Long cursorScore,
                                                             Long cursorId,
                                                             int size) {
        return query
                .select(contentProjection(mainContent.viewCount))
                .from(mainContent)
                .leftJoin(image).on(isLatestThumbnail())
                .where(
                        typeEq(type),
                        ltScoreCursor(mainContent.viewCount, cursorScore, cursorId)
                )
                .orderBy(mainContent.viewCount.desc(), mainContent.id.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();
    }

    // --- 공통 로직 추출 (Helper Methods) ---

    // 공통 Select 절 (반환 타입 불일치 해결)
    private ConstructorExpression<MainContentNewsListResponse> contentProjection(Expression<Long> scoreExpr) {
        return Projections.constructor(
                MainContentNewsListResponse.class,
                mainContent.id,
                mainContent.title,
                mainContent.type,
                Expressions.nullExpression(Long.class),
                mainContent.createdAt,
                image.url,
                scoreExpr
        );
    }

    // 최신순 커서 조건
    private BooleanExpression ltCursor(Instant createdAt, Long id) {
        if (createdAt == null) return null;
        return mainContent.createdAt.lt(createdAt)
                .or(mainContent.createdAt.eq(createdAt)
                        .and(id != null ? mainContent.id.lt(id) : Expressions.FALSE));
    }

    // 인기순(점수) 커서 조건
    private BooleanExpression ltScoreCursor(NumberExpression<Long> score, Long cursorScore, Long cursorId) {
        if (cursorScore == null) return null;
        return score.lt(cursorScore)
                .or(score.eq(cursorScore)
                        .and(cursorId != null ? mainContent.id.lt(cursorId) : Expressions.FALSE));
    }

    private BooleanExpression typeEq(KNewsContentType type) {
        return type != null ? mainContent.type.eq(type) : null;
    }


    private BooleanExpression isLatestThumbnail() {
        // 서브쿼리 전용 QImage (메인 쿼리의 image와 충돌 방지)
        QImage subImage = new QImage("subImage");

        return image.relatedId.eq(mainContent.id)
                .and(image.imageType.eq(ImageType.MAIN_PAGE_THUMBNAIL))
                .and(image.id.eq(
                        JPAExpressions
                                .select(subImage.id.max())
                                .from(subImage)
                                .where(subImage.relatedId.eq(mainContent.id)
                                        .and(subImage.imageType.eq(ImageType.MAIN_PAGE_THUMBNAIL)))
                ));
    }

}