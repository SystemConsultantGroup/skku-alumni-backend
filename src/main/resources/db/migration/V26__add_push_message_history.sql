-- 사무처가 고른 회원에게 직접 보낸 알림을 남긴다.
--
-- 지금까지 푸시는 공지·동호회 글을 올리면 따라 나가는 것뿐이어서, 보낸 내용이
-- 글로 남아 있었다. 사무처가 특정 임원 몇 명에게만 보내는 알림은 앱 어디에도
-- 남지 않는다. 무엇을 누구에게 보냈는지 남기지 않으면 "그런 안내 못 받았다"를
-- 확인할 방법이 없고, 같은 안내를 두 번 보내는 것도 막을 수 없다.
--
-- 발송은 되돌릴 수 없다. 기록은 발송 뒤에 남기므로, 기록이 없다고 해서 나가지
-- 않았다는 뜻은 아니다. 그래서 실제로 몇 대의 기기에 닿았는지(성공/실패)까지
-- 함께 적어 둔다.

CREATE TABLE push_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    -- 고른 회원 수와, 그중 실제로 발송 대상이 된 회원 수. 알림을 꺼 두었거나
    -- 기기를 등록하지 않은 회원은 골라도 나가지 않는다.
    requested_count INT NOT NULL,
    target_count INT NOT NULL,
    device_count INT NOT NULL,
    success_count INT NOT NULL,
    failure_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_push_messages_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
);

CREATE TABLE push_message_recipients (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    push_message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    device_count INT NOT NULL,
    CONSTRAINT fk_push_message_recipients_message FOREIGN KEY (push_message_id) REFERENCES push_messages (id),
    CONSTRAINT fk_push_message_recipients_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_push_message_recipients_message ON push_message_recipients (push_message_id);
CREATE INDEX idx_push_message_recipients_user ON push_message_recipients (user_id);
