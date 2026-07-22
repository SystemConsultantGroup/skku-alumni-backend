ALTER TABLE posts MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE posts ADD COLUMN admin_id BIGINT NULL;
ALTER TABLE posts ADD CONSTRAINT fk_posts_admin FOREIGN KEY (admin_id) REFERENCES admins (id);
CREATE INDEX idx_posts_admin_id ON posts (admin_id);
