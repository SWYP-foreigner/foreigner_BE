-- 1. 테이블 생성
CREATE TABLE app_version (
                             id BIGSERIAL PRIMARY KEY, -- AUTO_INCREMENT 대신 BIGSERIAL 사용
                             platform VARCHAR(20) NOT NULL,
                             minimum_version VARCHAR(20) NOT NULL,
                             latest_version VARCHAR(20) NOT NULL,
                             store_url VARCHAR(500) NOT NULL,
                             message TEXT,
                             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- DATETIME 대신 TIMESTAMP
                             updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- PostgreSQL은 'ON UPDATE' 구문을 테이블 정의에서 직접 지원하지 않음 (JPA가 처리하거나 트리거 필요)

                             CONSTRAINT uk_app_version_platform UNIQUE (platform)
);

-- 코멘트는 PostgreSQL에서 별도 명령어로 추가해야 함 (필수 아님, 생략 가능)
COMMENT ON TABLE app_version IS '앱 버전 관리';
COMMENT ON COLUMN app_version.platform IS 'OS 구분 (ANDROID, IOS)';

-- 2. 초기 데이터 삽입
INSERT INTO app_version (platform, minimum_version, latest_version, store_url, message, created_at, updated_at)
VALUES (
           'ANDROID',
           '1.2.4',
           '1.2.4',
           'market://details?id=com.SWYP.kori',
           'Please update to the latest version for more stable service.',
           NOW(),
           NOW()
       );

INSERT INTO app_version (platform, minimum_version, latest_version, store_url, message, created_at, updated_at)
VALUES (
           'IOS',
           '1.2.4',
           '1.2.4',
           'https://apps.apple.com/app/id6752611613',
           'Please update to the latest version for more stable service.',
           NOW(),
           NOW()
       );