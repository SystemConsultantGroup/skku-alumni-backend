ALTER TABLE companies ADD COLUMN industry_id BIGINT;
ALTER TABLE companies ADD COLUMN description TEXT;
ALTER TABLE companies ADD CONSTRAINT fk_companies_industry FOREIGN KEY (industry_id) REFERENCES industries (id);
CREATE INDEX idx_companies_industry_id ON companies (industry_id);

UPDATE companies SET industry_id = 2 WHERE id = 1;
UPDATE companies SET industry_id = 3 WHERE id = 2;
UPDATE companies SET industry_id = 1 WHERE id = 3;

CREATE TABLE user_web_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    url VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_web_links_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_user_web_links_user_id ON user_web_links (user_id);
