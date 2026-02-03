package core.domain.post.repository.impl;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.*;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.board.dto.BoardItem;
import core.domain.board.entity.QBoard;
import core.domain.comment.entity.QComment;
import core.domain.poll.entity.QPoll;
import core.domain.poll.entity.QPollOption;
import core.domain.poll.entity.QVoteRecord;
import core.domain.post.dto.admin.PostListForAdminResponse;
import core.domain.post.dto.admin.PostSearchForAdminRequest;
import core.domain.post.dto.comunity.PostDetailResponse;
import core.domain.post.dto.comunity.UserPostItem;
import core.domain.post.entity.Post;
import core.domain.post.entity.QBlockPost;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostRepositoryCustom;
import core.domain.user.entity.QBlockUser;
import core.domain.user.entity.QUser;
import core.global.entity.image.entity.QImage;
import core.global.entity.like.entity.QLike;
import core.global.enums.BoardCategory;
import core.global.enums.ImageType;
import core.global.enums.LikeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static core.domain.bookmark.entity.QBookmark.bookmark;
import static core.domain.post.entity.QBlockPost.blockPost;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepositoryCustom {
    private static final QPost post = QPost.post;
    private static final QUser user = QUser.user;
    private static final QLike like = QLike.like;
    private static final QBoard board = QBoard.board;
    private static final QComment comment = QComment.comment;
    private static final ImageType IMAGE_TYPE_POST = ImageType.POST;
    private static final ImageType IMAGE_TYPE_USER = ImageType.USER;
    private static final LikeType LIKE_TYPE_POST = LikeType.POST;
    private static final QPoll poll = QPoll.poll;
    private static final QVoteRecord voteRecord = QVoteRecord.voteRecord;
    private static final QPollOption pollOption = QPollOption.pollOption;

    private final JPAQueryFactory query;

    @Override
    public List<BoardItem> findLatestPosts(Long userId, Long boardId,
                                           Instant cursorCreatedAt,
                                           Long cursorId,
                                           int size) {

        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);

        BooleanExpression ltCursor = (cursorCreatedAt == null)
                ? null
                : post.createdAt.lt(cursorCreatedAt)
                .or(post.createdAt.eq(cursorCreatedAt)
                        .and(cursorId != null ? post.id.lt(cursorId) : Expressions.TRUE.isFalse())
                );

        BooleanExpression visibleToMe = visibleTo(userId);
        BooleanExpression notBlocked = notBlockedByViewerId(userId);

        return query
                .select(Projections.constructor(
                        BoardItem.class,
                        post.id,             // 1. postId
                        preview200(),        // 2. contentPreview
                        authorIdExpr(),      // 3. authorId
                        getAuthorName(),     // 4. authorName
                        board.category,      // 5. boardCategory
                        post.createdAt,      // 6. createdAt
                        post.anonymous,      // 7. isAnonymous
                        likedByViewerId(userId), // 8. isLiked
                        bookmarkedByViewerId(userId), // 9. isBookmarked
                        likeCountExpr(),     // 10. likeCount
                        commentCountExpr(),  // 11. commentCount
                        post.checkCount,     // 12. viewCount
                        nullIfAnonymous(userImageUrlExpr()), // 13. userImageUrl
                        Expressions.asNumber(0L), // 14. score (최신순은 0)
                        firstPostImageUrlExpr(),
                        postImageCountExpr(),

                        // 15. PostInfo 생성
                        Projections.constructor(BoardItem.PostInfo.class,
                                firstPostImageUrlExpr(),
                                postImageCountExpr()
                        ),

                        // 16. PollInfo 생성 (투표 정보가 없으면 null)
                        Projections.constructor(BoardItem.PollInfo.class,
                                poll.title,
                                poll.description,
                                poll.closeAt,
                                poll.totalVoteCount.coalesce(0L),
                                Expressions.constant(new ArrayList<BoardItem.OptionItem>()),
                                selectedOptionIdSubQuery(userId),
                                correctOptionIdIfVoted(userId)
                        )
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(post.poll, poll) // 중요: 투표 정보 조인
                .where(allOf(boardFilter, ltCursor, visibleToMe, notBlocked))
                .orderBy(post.createdAt.desc(), post.id.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();
    }

    @Override
    public List<BoardItem> findPopularPosts(Long userId, Long boardId, Instant since, Long cursorScore, Long cursorId, int size) {
        // ── 필터
        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);

        // ── 집계
        Expression<Long> likeCountSub = likeCountExpr();

        Expression<Long> commentCountSub = commentCountExpr();

        NumberExpression<Long> likes = Expressions.numberTemplate(Long.class, "({0})", likeCountSub);
        NumberExpression<Long> comments = Expressions.numberTemplate(Long.class, "({0})", commentCountSub);
        NumberExpression<Long> views = post.checkCount;

        // ── 최신성(나이 시간)
        NumberExpression<Double> ageHours =
                Expressions.numberTemplate(
                        Double.class,
                        "(extract(epoch from current_timestamp) - extract(epoch from {0})) / 3600.0",
                        post.createdAt
                );

        double TAU_HOURS = 24.0;   // 최신성 시간 스케일(클수록 감쇠 느림)

        int wR = 2;  // 최신성
        int wL = 3;  // 좋아요
        int wC = 4;  // 댓글
        int wV = 1;  // 조회

        NumberExpression<Double> recency_base =
                Expressions.numberTemplate(Double.class, "exp(-(({0}) / {1}))", ageHours, TAU_HOURS);

        NumberExpression<Double> likes_base =
                Expressions.numberTemplate(Double.class, "ln(1 + {0})", likes);

        NumberExpression<Double> comments_base =
                Expressions.numberTemplate(Double.class, "ln(1 + {0})", comments);

        NumberExpression<Double> views_base =
                Expressions.numberTemplate(Double.class, "ln(1 + {0})", views);

        // ====== 가중합 → 최종 Long 점수 ======
        NumberExpression<Double> scoreDouble =
                recency_base.multiply(wR)
                        .add(likes_base.multiply(wL))
                        .add(comments_base.multiply(wC))
                        .add(views_base.multiply(wV));

        NumberExpression<Long> score =
                Expressions.numberTemplate(Long.class, "cast(round({0}, 0) as long)", scoreDouble);


        // ── 커서 조건(무한스크롤)
        BooleanExpression ltCursor = null;
        if (cursorScore != null) {
            BooleanExpression tieBreaker =
                    (cursorId != null)
                            ? post.id.lt(cursorId)
                            : Expressions.FALSE;

            ltCursor = score.lt(cursorScore)
                    .or(score.eq(cursorScore).and(tieBreaker));
        }

        BooleanExpression visibleToMe = visibleTo(userId);
        BooleanExpression notBlocked = notBlockedByViewerId(userId);

        return query
                .select(Projections.constructor(
                        BoardItem.class,
                        post.id,                        // 1. postId
                        preview200(),                   // 2. contentPreview
                        authorIdExpr(),                 // 3. authorId
                        getAuthorName(),                // 4. authorName
                        board.category,                 // 5. boardCategory
                        post.createdAt,                 // 6. createdAt
                        post.anonymous,                 // 7. isAnonymous
                        likedByViewerId(userId),        // 8. isLiked
                        bookmarkedByViewerId(userId),   // 9. isBookmarked
                        likeCountExpr(),                // 10. likeCount
                        commentCountExpr(),             // 11. commentCount
                        post.checkCount,                // 12. viewCount
                        nullIfAnonymous(userImageUrlExpr()), // 13. userImageUrl
                        score,                          // 14. score (계산된 점수)
                        firstPostImageUrlExpr(),
                        postImageCountExpr(),

                        // 15. PostInfo (이미지 정보)
                        Projections.constructor(BoardItem.PostInfo.class,
                                firstPostImageUrlExpr(),
                                postImageCountExpr()
                        ),

                        // 16. PollInfo (투표 정보)
                        Projections.constructor(BoardItem.PollInfo.class,
                                poll.title,
                                poll.description,
                                poll.closeAt,
                                poll.totalVoteCount.coalesce(0L),
                                Expressions.constant(new ArrayList<BoardItem.OptionItem>()), // 서비스에서 채우기 위한 가변 리스트
                                selectedOptionIdSubQuery(userId),
                                correctOptionIdIfVoted(userId)
                        )
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(post.poll, poll) // 투표 조인 필수
                .where(allOf(boardFilter, ltCursor, visibleToMe, notBlocked))
                .orderBy(score.desc(), post.id.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();

    }

    @Override
    public PostDetailResponse findPostDetail(Long userId, Long postId) {
        QImage userImage = new QImage("u");
        QImage subUserImage = new QImage("subUserImage");

        Expression<Long> likeCountExpr = likeCountExpr();
        Expression<Long> commentCountExpr = commentCountExpr();
        Expression<Long> authorIdExpr = authorIdExpr();
        StringExpression userNameExpr = getAuthorName();

        // 1. 먼저 가장 최근의 이미지 ID를 찾는 서브쿼리 정의

        Expression<String> userImageUrlExpr = new CaseBuilder()
                .when(post.anonymous.isTrue()).then(Expressions.nullExpression(String.class))
                .otherwise(
                        JPAExpressions.select(userImage.url)
                                .from(userImage)
                                .where(
                                        userImage.id.eq(
                                                JPAExpressions.select(subUserImage.id.max())
                                                        .from(subUserImage)
                                                        .where(
                                                                subUserImage.imageType.eq(IMAGE_TYPE_USER),
                                                                subUserImage.relatedId.eq(user.id)
                                                        )
                                        )
                                )
                );

        QImage image = QImage.image;
        QImage image2 = new QImage("image2");

        Expression<Integer> imageCountExpr =
                JPAExpressions.select(image2.id.count().intValue())
                        .from(image2)
                        .where(
                                image2.imageType.eq(IMAGE_TYPE_POST)
                                        .and(image2.relatedId.eq(post.id))
                        );

        Expression<String> linkExpr = Expressions.constant("CHAT LINK");
        Expression<Boolean> bookmarkedByMe = bookmarkedByViewerId(userId);
        Expression<Boolean> likedByMe = likedByViewerId(userId);
        BooleanExpression notBlocked = notBlockedByViewerId(userId);

        Expression<Long> selectedOptionIdExpr = selectedOptionIdSubQuery(userId);
        Expression<Long> correctOptionIdExpr = correctOptionIdIfVoted(userId);


        List<Tuple> rows = query
                .select(
                        post.id,
                        post.content,
                        authorIdExpr,
                        userNameExpr,
                        board.category,
                        post.createdAt,
                        linkExpr,
                        post.anonymous,
                        likedByMe,
                        bookmarkedByMe,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        image.url,
                        imageCountExpr,

                        poll.title,
                        poll.description,
                        poll.closeAt,
                        poll.totalVoteCount.coalesce(0L),
                        selectedOptionIdExpr,
                        correctOptionIdExpr
                )
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(post.poll, poll)
                .leftJoin(image).on(
                        image.imageType.eq(IMAGE_TYPE_POST)
                                .and(image.relatedId.eq(post.id))
                )
                .where(allOf(post.id.eq(postId), notBlocked))
                .orderBy(image.orderIndex.asc().nullsLast())
                .fetch();

        if (rows.isEmpty()) {
            return null;
        }

        // 첫 행에서 단건 필드 뽑기
        Tuple t0 = rows.get(0);

        Long id = t0.get(post.id);
        String content = t0.get(post.content);
        Long authorId = t0.get(authorIdExpr);
        String userName = t0.get(userNameExpr);
        BoardCategory cat = t0.get(board.category);
        Instant createdAt = t0.get(post.createdAt);
        String link = t0.get(linkExpr);
        Boolean anonymous = t0.get(post.anonymous);
        Boolean liked = t0.get(likedByMe);
        Boolean bookmarked = t0.get(bookmarkedByMe);
        Long likeCount = t0.get(likeCountExpr);
        Long commentCount = t0.get(commentCountExpr);
        Long viewCount = t0.get(post.checkCount);
        String userImageUrl = t0.get(userImageUrlExpr);
        Integer imageCount = t0.get(imageCountExpr);

        String pollTitle = t0.get(poll.title);
        String pollDescription = t0.get(poll.description);
        Instant pollCloseAt = t0.get(poll.closeAt);
        Long pollTotalVote = t0.get(poll.totalVoteCount.coalesce(0L));
        Long mySelectedOption = t0.get(selectedOptionIdExpr);
        Long correctAnswer = t0.get(correctOptionIdExpr);

        List<String> contentImageUrls = rows.stream()
                .map(r -> r.get(image.url))
                .filter(Objects::nonNull)
                .toList();

        List<PostDetailResponse.OptionItem> pollOptions = new ArrayList<>();

        if (pollTitle != null) {
            // Poll 엔티티가 @MapsId로 Post와 ID를 공유하므로 postId로 조회 가능
            pollOptions = query
                    .select(Projections.constructor(PostDetailResponse.OptionItem.class,
                            pollOption.id,
                            pollOption.content,
                            pollOption.voteCount
                    ))
                    .from(pollOption)
                    .where(pollOption.poll.id.eq(postId)) // PostID == PollID
                    .orderBy(pollOption.id.asc())
                    .fetch();
        }


        return new PostDetailResponse(
                id,
                content,
                authorId,
                userName,
                cat,
                createdAt,
                link,
                anonymous,
                liked,
                bookmarked,
                likeCount,
                commentCount,
                viewCount,
                userImageUrl,
                contentImageUrls,
                imageCount,
                new PostDetailResponse.PostInfo(
                        contentImageUrls,
                        imageCount
                ),

                // 16. PollInfo 생성 (투표 정보가 없으면 null)
                (pollTitle != null) ? new PostDetailResponse.PollInfo(
                        pollTitle,
                        pollDescription,
                        pollCloseAt,
                        pollTotalVote,
                        pollOptions,
                        mySelectedOption,
                        correctAnswer
                ) : null
        );
    }

    @Override
    public List<UserPostItem> findMyPostsFirstByEmail(String email, int limitPlusOne) {
        Expression<Boolean> likedByMe = likedByViewerEmail(email);

        return query
                .select(Projections.constructor(
                        UserPostItem.class,
                        post.id,                    // 1. postId
                        preview200(),               // 2. content (Post의 본문 미리보기 사용)
                        post.createdAt,             // 3. createdAt
                        likedByMe,                  // 4. isLiked
                        likeCountExpr(),            // 5. likeCount
                        commentCountExpr(),         // 6. commentCount
                        post.checkCount,            // 7. viewCount
                        firstPostImageUrlExpr(),    // 8. imageUrl
                        postImageCountExpr()        // 9. imageCount
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(post.poll, poll)         // 엔티티 통합 구조에 맞춰 조인은 유지
                .where(user.email.eq(email))
                .orderBy(post.createdAt.desc(), post.id.desc())
                .limit(limitPlusOne)
                .fetch();
    }

    @Override
    public List<UserPostItem> findMyPostsNextByEmail(String email, Instant cursorCreatedAt, Long cursorId, int limitPlusOne) {
        BooleanExpression ltCursor = post.createdAt.lt(cursorCreatedAt)
                .or(post.createdAt.eq(cursorCreatedAt).and(post.id.lt(cursorId)));

        Expression<Boolean> likedByMe = likedByViewerEmail(email);

        return query
                .select(Projections.constructor(
                        UserPostItem.class,
                        post.id,                    // 1. postId
                        preview200(),               // 2. content (Post의 본문 미리보기 사용)
                        post.createdAt,             // 3. createdAt
                        likedByMe,                  // 4. isLiked
                        likeCountExpr(),            // 5. likeCount
                        commentCountExpr(),         // 6. commentCount
                        post.checkCount,            // 7. viewCount
                        firstPostImageUrlExpr(),    // 8. imageUrl
                        postImageCountExpr()        // 9. imageCount
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(post.poll, poll)         // 엔티티 통합 구조에 맞춰 조인은 유지
                .where(user.email.eq(email).and(ltCursor))
                .orderBy(post.createdAt.desc(), post.id.desc())
                .limit(limitPlusOne)
                .fetch();
    }


    @Override
    public Page<PostListForAdminResponse> searchPostsByAdmin(PostSearchForAdminRequest condition, Pageable pageable) {
        List<PostListForAdminResponse> content = query
                .select(Projections.constructor(PostListForAdminResponse.class,
                        post.id,
                        Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName),
                        user.email,
                        post.content,
                        post.createdAt,
                        JPAExpressions.select(blockPost.count())
                                .from(blockPost)
                                .where(blockPost.post.id.eq(post.id))
                ))
                .from(post)
                .join(post.author, user)
                .where(
                        authorEmailContains(condition.authorEmail()),
                        authorNameContains(condition.authorName()),
                        contentContains(condition.content()),
                        createdAtBetween(condition.startDate(), condition.endDate())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(getOrderSpecifiers(pageable.getSort()).toArray(OrderSpecifier[]::new))
                .fetch();

        long total = query
                .select(post.id)
                .from(post)
                .join(post.author, user)
                .where(
                        authorEmailContains(condition.authorEmail()),
                        authorNameContains(condition.authorName()),
                        contentContains(condition.content()),
                        createdAtBetween(condition.startDate(), condition.endDate())
                )
                .fetchCount();

        return new PageImpl<>(content, pageable, total);
    }

    private Expression<Long> selectedOptionIdSubQuery(Long userId) {
        if (userId == null) return Expressions.asNumber((Long) null);
        return JPAExpressions.select(voteRecord.pollOption.id)
                .from(voteRecord)
                .where(voteRecord.user.id.eq(userId)
                        .and(voteRecord.poll.id.eq(poll.id)));
    }

    private BooleanExpression authorEmailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression authorNameContains(String name) {
        if (!StringUtils.hasText(name)) return null;

        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
        return fullName.containsIgnoreCase(name);
    }

    private BooleanExpression contentContains(String content) {
        return StringUtils.hasText(content) ? post.content.containsIgnoreCase(content) : null;
    }

    private BooleanExpression createdAtBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return null;
        }

        Instant startInstant = startDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant();
        Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant();

        return post.createdAt.between(startInstant, endInstant);
    }

    private List<OrderSpecifier> getOrderSpecifiers(Sort sort) {
        List<OrderSpecifier> orders = new ArrayList<>();
        if (sort != null && !sort.isEmpty()) {
            sort.forEach(order -> {
                Order direction = order.isAscending() ? Order.ASC : Order.DESC;
                PathBuilder<Post> pathBuilder = new PathBuilder<>(Post.class, "post");

                switch (order.getProperty()) {
                    case "authorName":
                        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
                        orders.add(new OrderSpecifier<>(direction, fullName));
                        break;

                    case "reportCount":
                        orders.add(new OrderSpecifier<>(direction,
                                JPAExpressions.select(blockPost.count())
                                        .from(blockPost)
                                        .where(blockPost.post.id.eq(post.id))
                        ));
                        break;

                    default:
                        orders.add(new OrderSpecifier(direction, pathBuilder.get(order.getProperty(), Comparable.class)));
                        break;
                }
            });
        }

        orders.add(new OrderSpecifier(Order.DESC, post.id));
        return orders;
    }

    private StringExpression makeGetName() {
        return user.firstName.coalesce("")
                .concat(" ")
                .concat(user.lastName.coalesce(""));
    }

    private BooleanExpression notBlockedByViewerId(Long userId) {
        if (userId == null) return null; // 비로그인 때는 필터 생략
        QBlockPost bp = blockPost;
        return JPAExpressions
                .selectOne()
                .from(bp)
                .where(
                        bp.user.id.eq(userId)
                                .and(bp.post.id.eq(post.id))
                )
                .notExists();
    }


    private BooleanExpression visibleTo(Long userId) {
        if (userId == null) return null; // 비로그인

        QBlockUser bu = QBlockUser.blockUser;

        BooleanExpression notMyBlocking = JPAExpressions
                .selectOne()
                .from(bu)
                .where(bu.user.id.eq(userId)
                        .and(bu.blocked.id.eq(user.id))
                )
                .notExists();

        BooleanExpression notTheirBlocking = JPAExpressions
                .selectOne()
                .from(bu)
                .where(bu.user.id.eq(user.id)
                        .and(bu.blocked.id.eq(userId))
                )
                .notExists();

        return notMyBlocking.and(notTheirBlocking);
    }

    private Expression<Integer> postImageCountExpr() {
        QImage i = new QImage("i");
        return JPAExpressions
                .select(i.id.countDistinct().intValue())
                .from(i)
                .where(
                        i.imageType.eq(IMAGE_TYPE_POST)
                                .and(i.relatedId.eq(post.id))
                );
    }

    private Expression<Long> authorIdExpr() {
        return user.id;
    }

    private StringExpression getAuthorName() {
        return new CaseBuilder()
                .when(post.anonymous.isTrue()).then("Anonymity")
                .otherwise(makeGetName());
    }

    private Expression<String> preview200() {
        return Expressions.stringTemplate("substring({0}, 1, 200)", post.content);
    }

    // 익명일 때 null 로 바꿔주는 CASE 식
    private Expression<String> nullIfAnonymous(Expression<String> expr) {
        return new CaseBuilder()
                .when(post.anonymous.isTrue())
                .then(Expressions.nullExpression(String.class))
                .otherwise(expr);
    }

    private Expression<String> userImageUrlExpr() {
        QImage u = new QImage("u");
        QImage uSub = new QImage("uSub");

        return JPAExpressions
                .select(u.url.min())
                .from(u)
                .where(
                        u.imageType.eq(IMAGE_TYPE_USER),
                        u.relatedId.eq(user.id),
                        u.id.eq(
                                JPAExpressions
                                        .select(uSub.id.max()) // 가장 최근에 등록된 이미지 ID
                                        .from(uSub)
                                        .where(
                                                uSub.imageType.eq(IMAGE_TYPE_USER),
                                                uSub.relatedId.eq(user.id)
                                        )
                        )
                );
    }


    private Expression<String> firstPostImageUrlExpr() {
        QImage pi = new QImage("pi");
        QImage piSub = new QImage("piSub");

        return JPAExpressions
                .select(pi.url.min()) // 최소 order_index가 중복될 경우를 대비한 안전장치(Scalar 보장)
                .from(pi)
                .where(
                        pi.imageType.eq(IMAGE_TYPE_POST),
                        pi.relatedId.eq(post.id),
                        pi.orderIndex.eq(
                                JPAExpressions
                                        .select(piSub.orderIndex.min()) // 가장 작은 순서 번호를 찾음
                                        .from(piSub)
                                        .where(
                                                piSub.imageType.eq(IMAGE_TYPE_POST),
                                                piSub.relatedId.eq(post.id)
                                        )
                        )
                );
    }

    private Expression<Long> commentCountExpr() {
        return JPAExpressions.select(comment.count())
                .from(comment)
                .where(comment.post.eq(post));
    }

    private Expression<Long> likeCountExpr() {
        return JPAExpressions.select(like.count())
                .from(like)
                .where(like.type.eq(LIKE_TYPE_POST)
                        .and(like.relatedId.eq(post.id)));
    }

    // 🔹 viewerId(로그인 유저 id)로 좋아요 여부
    private Expression<Boolean> likedByViewerId(Long viewerId) {
        if (viewerId == null) return Expressions.FALSE; // 비로그인
        return JPAExpressions
                .selectOne()
                .from(like)
                .where(
                        like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id))
                                .and(like.user.id.eq(viewerId))
                )
                .exists();
    }

    // 🔹 viewerEmail(로그인 유저 email)로 좋아요 여부 (내 게시글 목록 API에서 사용)
    private Expression<Boolean> likedByViewerEmail(String email) {
        if (email == null || email.isBlank()) return Expressions.FALSE;
        return JPAExpressions
                .selectOne()
                .from(like)
                .where(
                        like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id))
                                .and(like.user.email.eq(email))
                )
                .exists();
    }

    private Expression<Boolean> bookmarkedByViewerId(Long viewerId) {
        if (viewerId == null) return Expressions.FALSE; // 비로그인
        return JPAExpressions
                .selectOne()
                .from(bookmark)
                .where(
                        bookmark.user.id.eq(viewerId)
                                .and(bookmark.post.id.eq(post.id))
                )

                .exists();
    }

    private Expression<Boolean> bookmarkedByViewerEmail(String  email) {
        if (email == null) return Expressions.FALSE; // 비로그인
        return JPAExpressions
                .selectOne()
                .from(bookmark)
                .where(
                        bookmark.user.email.eq(email)
                                .and(bookmark.post.id.eq(post.id))
                )
                .exists();
    }


    private BooleanExpression allOf(BooleanExpression... exps) {
        BooleanExpression result = null;
        for (BooleanExpression e : exps) {
            if (e == null) continue;
            result = (result == null) ? e : result.and(e);
        }
        return result;
    }

    private Expression<Long> correctOptionIdIfVoted(Long userId) {
        QPollOption qOption = new QPollOption("correctOpt");
        Instant now = Instant.now();

        // 1. 해당 투표의 정답 ID를 찾는 서브쿼리
        Expression<Long> correctAnswerIdSubQuery = JPAExpressions
                .select(qOption.id)
                .from(qOption)
                .where(qOption.poll.id.eq(poll.id)
                        .and(qOption.isCorrect.isTrue()));

        // 2. 투표가 마감되었는지 확인하는 조건 (closeAt < now)
        BooleanExpression isClosed = poll.closeAt.before(now);

        // 3. 사용자가 투표를 했는지 확인하는 조건
        BooleanExpression hasVoted = (userId == null)
                ? Expressions.FALSE
                : JPAExpressions.selectOne()
                .from(voteRecord)
                .where(voteRecord.user.id.eq(userId)
                        .and(voteRecord.poll.id.eq(poll.id)))
                .exists();

        // 4. CaseBuilder: (마감됨 OR 투표함) 이면 정답 ID 반환, 아니면 null
        return new CaseBuilder()
                .when(isClosed.or(hasVoted))
                .then(correctAnswerIdSubQuery)
                .otherwise(Expressions.nullExpression(Long.class));
    }
}