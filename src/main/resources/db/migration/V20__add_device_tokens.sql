-- 푸시 알림을 보낼 기기 토큰.
--
-- 앱은 이미 FCM 토큰을 발급받고 있었지만 서버에 보관할 곳이 없어 실제 발송이
-- 불가능했다. 회의에서 "공지를 올리면 알림이 뜬다"고 설명한 동작을 위해 필요하다.
--
-- 같은 회원이 여러 기기를 쓸 수 있으므로 회원당 여러 행을 허용하고, 토큰 자체는
-- 유일하다. 기기를 물려주면 같은 토큰이 다른 회원에게 붙을 수 있어 갱신으로 처리한다.
--
-- 토큰 길이를 512로 잡고 접두사 색인(token(255)) 대신 전체 컬럼에 유일 제약을 건다.
-- 접두사 색인은 MySQL 전용이라 H2로 도는 테스트에서 깨지고, utf8mb4 기준 512자는
-- 2048바이트라 InnoDB 색인 한도(3072바이트) 안에 들어온다.

CREATE TABLE device_tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_device_tokens_token UNIQUE (token),
    CONSTRAINT fk_device_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);
