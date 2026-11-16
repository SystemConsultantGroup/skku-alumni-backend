-- Preserve the previous recorded role when a term's legacy row is changed.
-- Existing rows and the legacy unique constraint remain untouched for rollback.
CREATE TABLE officer_role_change_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    officer_history_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    officer_term_id BIGINT NOT NULL,
    officer_role_id BIGINT NOT NULL,
    started_at DATE NOT NULL,
    ended_at DATE NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_role_change_history FOREIGN KEY (officer_history_id) REFERENCES officer_histories (id),
    CONSTRAINT fk_role_change_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_role_change_term FOREIGN KEY (officer_term_id) REFERENCES officer_terms (id),
    CONSTRAINT fk_role_change_role FOREIGN KEY (officer_role_id) REFERENCES officer_roles (id)
);
CREATE INDEX idx_role_change_user ON officer_role_change_history (user_id, recorded_at, id);
