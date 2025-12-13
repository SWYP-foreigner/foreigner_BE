-- post.post_id 에 PRIMARY KEY 추가

ALTER TABLE post
    ADD CONSTRAINT pk_post PRIMARY KEY (post_id);
