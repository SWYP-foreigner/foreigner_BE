CREATE TABLE app_version (
                             id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'PK',
                             platform VARCHAR(20) NOT NULL COMMENT 'OS 구분 (ANDROID, IOS)',
                             minimum_version VARCHAR(20) NOT NULL COMMENT '최소 지원 버전 (강제 업데이트 기준)',
                             latest_version VARCHAR(20) NOT NULL COMMENT '최신 버전 (업데이트 권장 기준)',
                             store_url VARCHAR(500) NOT NULL COMMENT '스토어 URL',
                             message TEXT COMMENT '업데이트 안내 문구',
                             created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성일',
                             updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '수정일',
                             CONSTRAINT uk_app_version_platform UNIQUE (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='앱 버전 관리';

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
           'itms-apps://itunes.apple.com/app/id123456789',
           'Please update to the latest version for more stable service.',
           NOW(),
           NOW()
       );