INSERT INTO colleges (id, name, created_at, updated_at) VALUES
    (1, '법과대학', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '경영대학', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '공과대학', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO majors (id, name, normalized_name, status, college_id, display_major_id, parent_id, created_at, updated_at) VALUES
    (1, '법학과', '법학과', 'ACTIVE', 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '법률학과', '법률학과', 'RENAMED', 1, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '시스템경영공학과', '시스템경영공학과', 'ACTIVE', 3, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '산업공학과', '산업공학과', 'RENAMED', 3, 3, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, '무역학과', '무역학과', 'CLOSED', 2, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO industries (id, name, created_at, updated_at) VALUES
    (1, '금융/보험업', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'IT/소프트웨어', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '법률/공공', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '제조업', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO companies (id, name, work_zipcode, work_address1, work_address2, created_at, updated_at) VALUES
    (1, '성균테크', '06236', '서울특별시 강남구 테헤란로', '18층', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '율전법무법인', '03154', '서울특별시 종로구 종로', '7층', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '명륜은행', '04522', '서울특별시 중구 세종대로', '본점', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO hobbies (id, name, created_at, updated_at) VALUES
    (1, '골프', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '등산', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '바둑', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '독서', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO officer_roles (id, name, sort_order, created_at, updated_at) VALUES
    (1, '회장', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '부회장', 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '상임이사', 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '이사', 40, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO officer_terms (id, generation, phase, started_at, ended_at, current_term, created_at, updated_at) VALUES
    (1, 39, 2, '2024-05-01', '2025-04-30', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 40, 1, '2025-05-01', '2026-04-30', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 40, 2, '2026-05-01', '2027-04-30', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (
    id, student_id, kingo_id, name, password, birth_date, gender, category, degree, major_id,
    admission_year, graduation_year, nationality, home_zipcode, home_address1, home_address2,
    phone, email, email_receive, industry_id, company_id, job_title, status, pr_text, created_at, updated_at
) VALUES
    (1, '1998310001', 'skku.park', '박성균', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '1979-03-12', 'M', 'UNDERGRADUATE', 'BACHELOR', 2, 1998, 2004, '대한민국', '03063', '서울특별시 종로구 성균관로', '101동', '010-1000-0001', 'park@example.com', TRUE, 3, 2, '대표변호사', 'ACTIVE', '법률 자문과 동문 멘토링에 관심이 있습니다.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '2002310002', 'skku.kim', '김명륜', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '1983-09-20', 'F', 'UNDERGRADUATE', 'BACHELOR', 4, 2002, 2006, '대한민국', '16419', '경기도 수원시 장안구 서부로', '202동', '010-1000-0002', 'kim@example.com', TRUE, 2, 1, 'CTO', 'ACTIVE', '스타트업 기술 협업과 채용 네트워크를 환영합니다.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '1995310003', 'skku.lee', '이율전', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '1976-12-02', 'M', 'UNDERGRADUATE', 'BACHELOR', 1, 1995, 2001, '대한민국', NULL, NULL, NULL, '010-1000-0003', 'lee@example.com', TRUE, 1, 3, '부행장', 'ACTIVE', '금융권 동문 교류를 돕고 싶습니다.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '1990310004', 'skku.choi', '최퇴계', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhiu04QzR7n.zvJz7EYYdl6fXmb6Mt7y', '1971-07-08', 'M', 'UNDERGRADUATE', 'BACHELOR', 5, 1990, 1996, '대한민국', NULL, NULL, NULL, '010-1000-0004', 'choi@example.com', TRUE, 4, NULL, '고문', 'ACTIVE', '제조업 선후배 연결을 돕겠습니다.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO user_hobbies (id, user_id, hobby_id, created_at, updated_at) VALUES
    (1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 2, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 2, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 3, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (6, 4, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO officer_histories (
    id, user_id, officer_term_id, officer_role_id, started_at, ended_at, payment_status, created_at, updated_at
) VALUES
    (1, 1, 2, 4, '2025-05-01', '2026-04-30', 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 1, 3, 2, '2026-05-01', '2027-04-30', 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 2, 3, 4, '2026-05-01', '2027-04-30', 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 3, 3, 4, '2026-05-01', '2027-04-30', 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 4, 1, 3, '2024-05-01', '2025-04-30', 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO posts (id, user_id, title, body, status, thumbnail_url, created_at, updated_at) VALUES
    (1, 1, '40대 2기 임원 네트워킹 안내', '새 임기 임원 간 첫 네트워킹 모임을 준비하고 있습니다.', 'PUBLISHED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 2, 'IT/소프트웨어 동문 소모임 제안', '기술 창업, 채용, 멘토링을 주제로 작게 시작해보려 합니다.', 'PUBLISHED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 3, '미납 회원 테스트 게시글', '숨김 처리 검증용 데이터입니다.', 'HIDDEN', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
