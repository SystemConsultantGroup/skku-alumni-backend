ALTER TABLE clubs ADD COLUMN manager_user_id BIGINT;
ALTER TABLE clubs ADD CONSTRAINT fk_clubs_manager FOREIGN KEY (manager_user_id) REFERENCES users (id);

UPDATE clubs c
SET manager_user_id = (
    SELECT MIN(cm.user_id)
    FROM club_members cm
    WHERE cm.club_id = c.id
      AND cm.club_role = 'MANAGER'
      AND cm.left_at IS NULL
      AND cm.user_id <> c.president_user_id
);

INSERT INTO club_members (club_id, user_id, club_role, joined_at)
SELECT c.id, c.president_user_id, 'PRESIDENT', CURRENT_TIMESTAMP
FROM clubs c
WHERE c.president_user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM club_members cm
      WHERE cm.club_id = c.id AND cm.user_id = c.president_user_id
  );

UPDATE club_members
SET club_role = 'MEMBER'
WHERE left_at IS NULL;

UPDATE club_members cm
SET club_role = 'PRESIDENT'
WHERE cm.left_at IS NULL
  AND EXISTS (
      SELECT 1 FROM clubs c
      WHERE c.id = cm.club_id AND c.president_user_id = cm.user_id
  );

UPDATE club_members cm
SET club_role = 'MANAGER'
WHERE cm.left_at IS NULL
  AND EXISTS (
      SELECT 1 FROM clubs c
      WHERE c.id = cm.club_id AND c.manager_user_id = cm.user_id
  );

CREATE TABLE club_join_applications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    club_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    reviewed_by_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_club_join_applications_club_user UNIQUE (club_id, user_id),
    CONSTRAINT fk_club_join_applications_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_club_join_applications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_club_join_applications_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_club_join_applications_club_status_applied
    ON club_join_applications (club_id, status, applied_at);
