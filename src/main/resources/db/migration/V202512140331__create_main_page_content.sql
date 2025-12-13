CREATE TABLE main_page_content (
    content_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    html_content LONGTEXT NOT NULL,
    original_url VARCHAR(255),
    publisher_id BIGINT,
    created_at DATETIME(6),
    updated_at DATETIME(6)
) ENGINE=InnoDB;

ALTER TABLE main_page_content
    ADD CONSTRAINT FK_main_page_content_publisher
    FOREIGN KEY (publisher_id)
    REFERENCES users (user_id);