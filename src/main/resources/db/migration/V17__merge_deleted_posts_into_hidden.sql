UPDATE posts
SET status = 'HIDDEN',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'DELETED';
