ALTER TABLE posts ADD COLUMN club_id BIGINT;
ALTER TABLE posts ADD CONSTRAINT fk_posts_club FOREIGN KEY (club_id) REFERENCES clubs (id);
CREATE INDEX idx_posts_club_kind_status_id ON posts (club_id, post_kind, status, id);

