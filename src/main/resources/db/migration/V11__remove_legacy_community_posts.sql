DELETE FROM reports
WHERE target_post_id IN (SELECT id FROM posts WHERE post_kind = 'COMMUNITY');

DELETE FROM posts WHERE post_kind = 'COMMUNITY';
