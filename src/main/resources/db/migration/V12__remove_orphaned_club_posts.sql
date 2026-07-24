DELETE FROM reports
WHERE target_post_id IN (
    SELECT id FROM posts WHERE post_kind = 'CLUB' AND club_id IS NULL
);

DELETE FROM posts WHERE post_kind = 'CLUB' AND club_id IS NULL;
