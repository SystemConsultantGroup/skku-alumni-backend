-- 총동창회 사무처 문의하기.
--
-- 채팅이 아니라 "회원이 한 번 묻고 사무처가 한 번 답하면 끝"인 구조다. 그래서 답변을
-- 별도 테이블로 빼지 않고 문의 행에 둔다. 답은 고칠 수 있지만 대화가 이어지지는 않는다.

CREATE TABLE inquiries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    -- ACCOUNT / DUES / MEMBER_INFO / COMMUNITY / APP_ERROR / OTHER
    category VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    -- DRAFT: 첨부를 올리는 중이라 아직 접수되지 않았다. 사무처 화면과 알림 메일에 나오지 않는다.
    -- OPEN: 접수됨, ANSWERED: 답변함.
    -- 첨부를 문의와 같은 요청으로 보내면 요청이 최대 250MB 가 되어 중간 프록시에서 끊긴다.
    -- 문의를 먼저 만들고 첨부를 하나씩 올린 뒤 접수하는 순서로 나눈 이유다.
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    answer_body TEXT NULL,
    answered_by BIGINT NULL,
    answered_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_inquiries_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_inquiries_answered_by FOREIGN KEY (answered_by) REFERENCES admins (id)
);

CREATE INDEX idx_inquiries_user_id ON inquiries (user_id, id);
CREATE INDEX idx_inquiries_status ON inquiries (status, id);

CREATE TABLE inquiry_attachments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inquiry_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    -- 저장소 안의 이름. 원래 이름은 겹치거나 경로 문자를 품을 수 있어 쓰지 않는다.
    object_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inquiry_attachments_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiries (id)
);

CREATE INDEX idx_inquiry_attachments_inquiry ON inquiry_attachments (inquiry_id);

-- 문의가 들어오면 알려 줄 메일 주소. 사무처가 관리자 화면에서 여러 개를 넣고 뺀다.
CREATE TABLE inquiry_notify_emails (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inquiry_notify_emails_email UNIQUE (email)
);
