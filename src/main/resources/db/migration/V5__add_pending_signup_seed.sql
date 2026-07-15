INSERT INTO users (
    student_id, kingo_id, name, password, birth_date, gender, category, degree, major_id,
    admission_year, graduation_year, nationality, home_zipcode, home_address1, home_address2,
    phone, email, email_receive, industry_id, company_id, job_title, status, pr_text, created_at, updated_at
) VALUES (
    '2007310005', NULL, '조가입', '$2a$10$B/FWZnKxEpNedaqTtRpNOuFUr0ybpBoOYWxRDyBbffaxYP0NGd/Ei',
    '1988-05-15', 'F', 'UNDERGRADUATE', 'BACHELOR', 3,
    2007, 2011, '대한민국', NULL, NULL, NULL,
    '010-3000-0005', 'signup@example.com', TRUE, 2, NULL, '서비스기획자',
    'PENDING', '가입 대기 샘플 동문입니다.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
