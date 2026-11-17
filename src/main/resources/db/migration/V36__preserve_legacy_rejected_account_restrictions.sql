-- REJECTED previously denied all account access. Preserve that restriction explicitly
-- before removing application-status checks from authorization; never unblock accounts.
UPDATE users SET account_blocked = TRUE WHERE status = 'REJECTED' AND account_blocked = FALSE;
