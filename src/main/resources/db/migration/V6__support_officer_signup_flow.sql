ALTER TABLE users MODIFY COLUMN student_id VARCHAR(255) NULL;
ALTER TABLE users MODIFY COLUMN password VARCHAR(255) NULL;
ALTER TABLE member_applications MODIFY COLUMN student_id VARCHAR(255) NULL;

UPDATE users
SET kingo_id = NULL,
    password = '',
    status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
WHERE student_id = '2007310005';

INSERT INTO officer_histories (
    user_id, officer_term_id, officer_role_id, started_at, payment_status, created_at, updated_at
)
SELECT u.id, ot.id, 4, ot.started_at, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u
JOIN officer_terms ot ON ot.current_term = TRUE
WHERE u.student_id = '2007310005'
  AND NOT EXISTS (
      SELECT existing.id
      FROM officer_histories existing
      WHERE existing.user_id = u.id
        AND existing.officer_term_id = ot.id
  );

INSERT INTO payment_records (
    user_id, officer_term_id, amount, status, created_at, updated_at
)
SELECT u.id, ot.id, 300000, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u
JOIN officer_terms ot ON ot.current_term = TRUE
WHERE u.student_id = '2007310005'
  AND NOT EXISTS (
      SELECT existing.id
      FROM payment_records existing
      WHERE existing.user_id = u.id
        AND existing.officer_term_id = ot.id
  );
