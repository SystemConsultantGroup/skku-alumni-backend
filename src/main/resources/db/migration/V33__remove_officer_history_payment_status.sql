-- Preserve the pre-transition values for reconciliation and a reviewed rollback.
CREATE TABLE legacy_officer_payment_status_archive (
    officer_history_id BIGINT PRIMARY KEY,
    legacy_status VARCHAR(50) NOT NULL
);

INSERT INTO legacy_officer_payment_status_archive (officer_history_id, legacy_status)
SELECT id, payment_status FROM officer_histories;

ALTER TABLE officer_histories DROP COLUMN payment_status;
