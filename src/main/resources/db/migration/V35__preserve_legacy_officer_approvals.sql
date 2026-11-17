-- Preserve precisely the legacy ACTIVE-account approval interpretation per history.
-- PENDING accounts, deleted histories and existing approvals are left unchanged.
INSERT INTO officer_history_approvals (officer_history_id)
SELECT h.id
FROM officer_histories h
JOIN users u ON u.id = h.user_id
WHERE u.status = 'ACTIVE'
  AND h.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM officer_history_approvals a WHERE a.officer_history_id = h.id);
