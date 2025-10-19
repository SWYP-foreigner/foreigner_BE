
CREATE TABLE follow_activity_log
(
    -- 기본 키
    log_id                BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- --- 기본 이벤트 정보 ---
    follower_id           BIGINT       NOT NULL COMMENT '행동을 한 사용자(팔로워)의 ID',
    following_id          BIGINT       NOT NULL COMMENT '행동의 대상이 된 사용자(팔로잉)의 ID',
    action_type           VARCHAR(255) NOT NULL COMMENT '활동 유형 (e.g., FOLLOW, UNFOLLOW)',
    created_at            TIMESTAMP    NOT NULL COMMENT '활동이 발생한 시간',
    source                VARCHAR(255) NULL COMMENT '활동이 발생한 소스 (e.g., PROFILE, RECOMMEND)',

    -- --- 팔로워(Follower)의 스냅샷 정보 ---
    follower_is_in_korea  BOOLEAN      NULL COMMENT '팔로워의 한국 거주 여부 (이벤트 시점)',
    follower_nationality  VARCHAR(255) NULL COMMENT '팔로워의 국적 (이벤트 시점)',
    follower_sex          VARCHAR(255) NULL COMMENT '팔로워의 성별 (이벤트 시점)',
    follower_birth_date   VARCHAR(255) NULL COMMENT '팔로워의 생년월일 (이벤트 시점)',
    follower_language     VARCHAR(500) NULL COMMENT '팔로워가 사용하는 언어 (이벤트 시점)',

    -- --- 팔로잉(Following)의 스냅샷 정보 ---
    following_is_in_korea BOOLEAN      NULL COMMENT '팔로잉의 한국 거주 여부 (이벤트 시점)',
    following_nationality VARCHAR(255) NULL COMMENT '팔로잉의 국적 (이벤트 시점)',
    following_sex         VARCHAR(255) NULL COMMENT '팔로잉의 성별 (이벤트 시점)',
    following_birth_date  VARCHAR(255) NULL COMMENT '팔로잉의 생년월일 (이벤트 시점)',
    following_language    VARCHAR(500) NULL COMMENT '팔로잉이 사용하는 언어 (이벤트 시점)'

) COMMENT ='사용자 팔로우 활동에 대한 상세 로그 테이블';