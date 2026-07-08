ALTER TABLE posts ADD COLUMN post_kind VARCHAR(50) NOT NULL DEFAULT 'COMMUNITY';
ALTER TABLE posts ADD COLUMN industry_id BIGINT;
ALTER TABLE posts ADD CONSTRAINT fk_posts_industry FOREIGN KEY (industry_id) REFERENCES industries (id);
CREATE INDEX idx_posts_kind_status_id ON posts (post_kind, status, id);

CREATE TABLE admin_roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_roles_name UNIQUE (name)
);

CREATE TABLE admins (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    position VARCHAR(255),
    admin_role_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admins_email UNIQUE (email),
    CONSTRAINT fk_admins_role FOREIGN KEY (admin_role_id) REFERENCES admin_roles (id)
);

CREATE TABLE admin_audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id BIGINT NOT NULL,
    action VARCHAR(255) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_audit_logs_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
);

CREATE TABLE member_applications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    email VARCHAR(255),
    major_name VARCHAR(255),
    admission_year INT,
    desired_role VARCHAR(255),
    company_name VARCHAR(255),
    job_title VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_member_applications_reviewer FOREIGN KEY (reviewed_by) REFERENCES admins (id)
);

CREATE INDEX idx_member_applications_status_id ON member_applications (status, id);

CREATE TABLE payment_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    officer_term_id BIGINT NOT NULL,
    amount INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_records_user_term UNIQUE (user_id, officer_term_id),
    CONSTRAINT fk_payment_records_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_payment_records_term FOREIGN KEY (officer_term_id) REFERENCES officer_terms (id)
);

CREATE INDEX idx_payment_records_status_id ON payment_records (status, id);

CREATE TABLE profile_change_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    old_value VARCHAR(255),
    new_value VARCHAR(255),
    synced BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_change_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_profile_change_logs_synced_id ON profile_change_logs (synced, id);

CREATE TABLE reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_post_id BIGINT NOT NULL,
    reason VARCHAR(100) NOT NULL,
    reason_others VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    admin_memo VARCHAR(255),
    resolved_at TIMESTAMP,
    resolved_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_reports_target_post FOREIGN KEY (target_post_id) REFERENCES posts (id),
    CONSTRAINT fk_reports_resolver FOREIGN KEY (resolved_by) REFERENCES admins (id)
);

CREATE INDEX idx_reports_status_id ON reports (status, id);

CREATE TABLE user_blocks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    blocker_id BIGINT NOT NULL,
    blocked_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_blocks_blocker_blocked UNIQUE (blocker_id, blocked_id),
    CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id),
    CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id)
);

CREATE TABLE clubs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(50) NOT NULL,
    president_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_clubs_president FOREIGN KEY (president_user_id) REFERENCES users (id)
);

CREATE TABLE club_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    club_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    club_role VARCHAR(50) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP,
    CONSTRAINT uk_club_members_club_user UNIQUE (club_id, user_id),
    CONSTRAINT fk_club_members_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_club_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

INSERT INTO admin_roles (id, name, description, created_at, updated_at) VALUES
    (1, 'MASTER', '전체 기능과 운영자 관리 권한', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'STAFF', '회원, 회비, 신고, 콘텐츠 관리 권한', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 'ASIS_OPERATOR', 'ASIS 최신화 관리 전용 권한', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO admins (id, email, name, password, position, admin_role_id, active, created_at, updated_at) VALUES
    (1, 'master@skku-alumni.local', '안상인', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '사무총장', 1, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'staff@skku-alumni.local', '사무처 직원', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '사무처', 2, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 'asis@skku-alumni.local', 'ASIS 담당자', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '알바생', 3, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO member_applications (
    id, name, student_id, phone, email, major_name, admission_year, desired_role, company_name, job_title, status, created_at, updated_at
) VALUES
    (1, '정신청', '2004310101', '010-2000-0001', 'apply1@example.com', '경영학과', 2004, '이사', '청년벤처', '대표', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '오가입', '1999310102', '010-2000-0002', 'apply2@example.com', '법학과', 1999, '상임이사', '명륜컨설팅', '파트너', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '문승인', '2001310103', '010-2000-0003', 'apply3@example.com', '시스템경영공학과', 2001, '이사', '율전모빌리티', '본부장', 'APPROVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO payment_records (id, user_id, officer_term_id, amount, status, paid_at, created_at, updated_at) VALUES
    (1, 1, 3, 1000000, 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 2, 3, 300000, 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 3, 3, 300000, 'UNPAID', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO profile_change_logs (
    id, user_id, field_name, old_value, new_value, synced, changed_at, synced_at, created_at, updated_at
) VALUES
    (1, 1, 'phone', '010-0000-0001', '010-1000-0001', FALSE, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 2, 'company', '이전회사', '성균테크', FALSE, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 3, 'home_address1', '서울특별시 중구', '서울특별시 종로구', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO reports (
    id, reporter_id, target_type, target_post_id, reason, reason_others, status, admin_memo, created_at, updated_at
) VALUES
    (1, 2, 'post', 1, 'SPAM', NULL, 'PENDING', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 1, 'post', 2, 'OTHER', '연락처 노출 여부 확인 요청', 'DISMISSED', '운영상 문제 없음', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at, updated_at) VALUES
    (1, 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO clubs (id, name, description, category, president_user_id, created_at, updated_at) VALUES
    (1, '성균 골프회', '라운딩과 친목을 함께하는 임원 동호회입니다.', 'HOBBY', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '율전 등산회', '월 1회 산행으로 건강하게 교류합니다.', 'HOBBY', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '미래발전연구회', '총동창회 발전 방향과 신규 사업을 논의합니다.', 'RESEARCH', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO club_members (id, club_id, user_id, club_role, joined_at) VALUES
    (1, 1, 1, 'PRESIDENT', CURRENT_TIMESTAMP),
    (2, 1, 3, 'MEMBER', CURRENT_TIMESTAMP),
    (3, 2, 2, 'PRESIDENT', CURRENT_TIMESTAMP),
    (4, 3, 3, 'PRESIDENT', CURRENT_TIMESTAMP);

INSERT INTO posts (id, user_id, title, body, status, thumbnail_url, post_kind, industry_id, created_at, updated_at) VALUES
    (4, 1, '총동창회 임원 수첩 앱 오픈 안내', '회비 납부 현행 임원부터 순차적으로 이용할 수 있습니다.', 'PUBLISHED', NULL, 'NOTICE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 2, '성균테크 채용 협업 제안', 'IT/소프트웨어 산업 동문과 채용 협업을 논의하고 싶습니다.', 'PUBLISHED', NULL, 'BUSINESS', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO admin_audit_logs (id, admin_id, action, target_type, target_id, created_at) VALUES
    (1, 1, 'CREATE_MANAGER', 'admin', 2, CURRENT_TIMESTAMP),
    (2, 2, 'MARK_ASIS_SYNCED', 'profile_change_log', 3, CURRENT_TIMESTAMP);
