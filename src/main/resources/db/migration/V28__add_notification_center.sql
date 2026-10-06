-- 앱 안 알림 센터.
--
-- 푸시는 기기 트레이에 한 번 떴다 사라진다. 알림을 놓치거나 지워버리면 무엇이 왔는지
-- 다시 볼 방법이 없었고, 링크가 없는 알림은 눌러도 홈만 열려 내용조차 확인할 수 없었다.
-- 보낸 알림을 회원별로 남겨 앱에서 모아 보게 한다.
--
-- 내용은 한 번만 저장하고(notifications), 누가 받았고 읽었는지는 회원별로 따로 둔다
-- (user_notifications). 공지 한 건이 전 임원에게 나가므로 제목·본문을 받는 사람 수만큼
-- 복제하지 않는다. 읽음 상태는 기기가 아니라 서버가 들고 있어야 휴대전화를 바꾸거나
-- 두 기기를 써도 같은 상태가 보인다.

CREATE TABLE notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- official-post / club-post / admin-message
    type VARCHAR(30) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    -- 눌렀을 때 앱 안에서 열 경로. 바깥 사이트면 link_url. 둘 다 없으면 알림 자체가 내용이다.
    link_path VARCHAR(1000) NULL,
    link_url VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    -- 비어 있으면 아직 확인하지 않은 알림. 빨간 점의 근거다.
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_notifications_notification FOREIGN KEY (notification_id) REFERENCES notifications (id),
    CONSTRAINT fk_user_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_user_notifications UNIQUE (notification_id, user_id)
);

-- 내 알림을 최신순으로 넘기는 조회와, 안 읽은 개수를 세는 조회가 쓴다.
CREATE INDEX idx_user_notifications_user_id ON user_notifications (user_id, id);
CREATE INDEX idx_user_notifications_unread ON user_notifications (user_id, read_at);
