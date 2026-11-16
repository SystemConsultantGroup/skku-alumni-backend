-- Existing PENDING histories are deliberately not inferred to be approved.
CREATE TABLE officer_history_approvals (
    officer_history_id BIGINT PRIMARY KEY,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_officer_history_approval FOREIGN KEY (officer_history_id) REFERENCES officer_histories (id)
);
