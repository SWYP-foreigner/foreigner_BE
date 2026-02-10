-- IAP 상품 정보 테이블
CREATE TABLE iap_product (
                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                             platform VARCHAR(16) NOT NULL,
                             store_product_id VARCHAR(128) NOT NULL,
                             type VARCHAR(16) NOT NULL,
                             base_plan VARCHAR(64),
                             offer_id VARCHAR(64),
                             feature VARCHAR(64) NOT NULL,
                             tier VARCHAR(64) NOT NULL,
                             created_at DATETIME(6) NOT NULL,
                             updated_at DATETIME(6) NOT NULL,
                             CONSTRAINT ux_iap_product_store UNIQUE (platform, store_product_id)
);

-- IAP 결제 이력 테이블
CREATE TABLE iap_purchase (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id BIGINT NOT NULL,
                              platform VARCHAR(16) NOT NULL,
                              product_id BIGINT,
                              store_tx_id VARCHAR(256) NOT NULL,
                              original_tx_id VARCHAR(256),
                              status VARCHAR(24) NOT NULL,
                              purchased_at DATETIME(6),
                              expires_at DATETIME(6),
                              is_trial BOOLEAN NOT NULL DEFAULT FALSE,
                              is_intro BOOLEAN NOT NULL DEFAULT FALSE,
                              raw_json LONGTEXT,
                              CONSTRAINT ux_iap_purchase_dedup UNIQUE (platform, store_tx_id)
);

CREATE INDEX ix_iap_purchase_user ON iap_purchase (user_id);

-- IAP 권한 관리 테이블
CREATE TABLE iap_entitlement (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 user_id BIGINT NOT NULL,
                                 source_purchase_id BIGINT,
                                 feature VARCHAR(64) NOT NULL,
                                 tier VARCHAR(64) NOT NULL,
                                 active BOOLEAN NOT NULL,
                                 status VARCHAR(24) NOT NULL,
                                 expires_at DATETIME(6),
                                 source VARCHAR(16) NOT NULL,
                                 updated_at DATETIME(6) NOT NULL
);

CREATE INDEX ix_iap_entitlement_user ON iap_entitlement (user_id);

-- 보너스 지급 내역 테이블
CREATE TABLE iap_bonus_grant (
                                 iap_bonus_grant_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 user_id BIGINT NOT NULL,
                                 bonus_code VARCHAR(64) NOT NULL,
                                 dedup_key VARCHAR(256),
                                 source_purchase_id BIGINT,
                                 created_at DATETIME(6) NOT NULL,
                                 CONSTRAINT ux_iap_bonus_user_bonus UNIQUE (user_id, bonus_code)
);

-- 스토어 웹훅 이벤트 로그 테이블
CREATE TABLE iap_webhook_event (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   platform VARCHAR(16) NOT NULL,
                                   event_type VARCHAR(64) NOT NULL,
                                   dedup_key VARCHAR(256) NOT NULL,
                                   received_at DATETIME(6) NOT NULL,
                                   processed_at DATETIME(6),
                                   process_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                                   raw_json LONGTEXT,
                                   CONSTRAINT ux_iap_webhook_dedup UNIQUE (platform, dedup_key)
);

-- 유저 아이템 수량 관리 테이블
CREATE TABLE user_item (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           item_code VARCHAR(64) NOT NULL,
                           quantity INT NOT NULL DEFAULT 0,
                           updated_at DATETIME(6) NOT NULL,
                           CONSTRAINT ux_user_item UNIQUE (user_id, item_code)
);