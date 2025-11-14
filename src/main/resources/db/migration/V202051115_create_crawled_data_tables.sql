-- V2__create_crawled_data_tables.sql

-- 1. crawled_data 테이블 생성
CREATE TABLE crawled_data
(
    id                 BIGSERIAL PRIMARY KEY,
    title              VARCHAR(500) NOT NULL,
    content_snippet    TEXT,
    original_url       VARCHAR(1024) NOT NULL,
    source_site        VARCHAR(100),
    crawled_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    status             VARCHAR(50) NOT NULL,
    published_post_id  BIGINT,

    -- Unique Constraint: originalUrl은 유일해야 함
    CONSTRAINT uk_crawled_data_original_url
        UNIQUE (original_url)
);

-- Index 생성: status 컬럼에 인덱스 추가 (조회 성능 개선)
CREATE INDEX idx_crawled_data_status ON crawled_data (status);

---

-- 2. crawled_image_urls 테이블 생성 (ElementCollection 매핑)
CREATE TABLE crawled_image_urls
(
    crawled_data_id    BIGINT NOT NULL,
    image_url          VARCHAR(1024),
    image_order        INT    NOT NULL,

    -- Foreign Key: crawled_data 테이블의 id를 참조
    CONSTRAINT fk_crawled_image_urls_data
        FOREIGN KEY (crawled_data_id)
            REFERENCES crawled_data (id)
            ON DELETE CASCADE, -- 부모 레코드 삭제 시 자식 레코드도 삭제

    -- Primary Key: 부모 ID와 순서를 조합하여 복합 키 생성
    PRIMARY KEY (crawled_data_id, image_order)
);

-- Note: image_url이 List<String>이므로, 순서(image_order)를 보장하기 위해 @OrderColumn이 매핑됩니다.