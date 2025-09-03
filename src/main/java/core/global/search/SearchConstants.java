package core.global.search;

public final class SearchConstants {
    private SearchConstants() {}
    public static final String INDEX_POSTS_SEARCH   = "posts_search";   // 검색용
    public static final String INDEX_POSTS_SUGGEST  = "posts_suggest";  // 서제스트용
    public static final String INDEX_POSTS_WRITE    = "posts_write";    // 쓰기용 (is_write_index)

    public static final int BLOCK_TERMS_LOOKUP_THRESHOLD = 800;
    public static final String USER_FILTER_INDEX = "user_filters";
    public static final String USER_FILTER_BLOCKED_PATH = "blockedUserIds";
}