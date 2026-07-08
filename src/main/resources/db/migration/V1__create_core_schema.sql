CREATE TABLE colleges (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_colleges_name UNIQUE (name)
);

CREATE TABLE majors (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    college_id BIGINT,
    display_major_id BIGINT,
    parent_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_majors_college FOREIGN KEY (college_id) REFERENCES colleges (id),
    CONSTRAINT fk_majors_display_major FOREIGN KEY (display_major_id) REFERENCES majors (id),
    CONSTRAINT fk_majors_parent FOREIGN KEY (parent_id) REFERENCES majors (id)
);

CREATE INDEX idx_majors_normalized_name ON majors (normalized_name);
CREATE INDEX idx_majors_display_major_id ON majors (display_major_id);

CREATE TABLE industries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_industries_name UNIQUE (name)
);

CREATE TABLE companies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    work_zipcode VARCHAR(50),
    work_address1 VARCHAR(255),
    work_address2 VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_companies_name UNIQUE (name)
);

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id VARCHAR(255) NOT NULL,
    kingo_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    birth_date DATE,
    gender VARCHAR(20),
    category VARCHAR(50) NOT NULL,
    degree VARCHAR(50),
    major_id BIGINT NOT NULL,
    admission_year INT NOT NULL,
    graduation_year INT,
    nationality VARCHAR(255),
    home_zipcode VARCHAR(50),
    home_address1 VARCHAR(255),
    home_address2 VARCHAR(255),
    phone VARCHAR(50),
    email VARCHAR(255),
    email_receive BOOLEAN NOT NULL DEFAULT TRUE,
    industry_id BIGINT,
    company_id BIGINT,
    job_title VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    pr_text TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_student_id UNIQUE (student_id),
    CONSTRAINT uk_users_kingo_id UNIQUE (kingo_id),
    CONSTRAINT fk_users_major FOREIGN KEY (major_id) REFERENCES majors (id),
    CONSTRAINT fk_users_industry FOREIGN KEY (industry_id) REFERENCES industries (id),
    CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id)
);

CREATE INDEX idx_users_status_id ON users (status, id);
CREATE INDEX idx_users_major_id ON users (major_id);
CREATE INDEX idx_users_industry_id ON users (industry_id);
CREATE INDEX idx_users_company_id ON users (company_id);

CREATE TABLE hobbies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_hobbies_name UNIQUE (name)
);

CREATE TABLE user_hobbies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    hobby_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_hobbies_user_hobby UNIQUE (user_id, hobby_id),
    CONSTRAINT fk_user_hobbies_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_hobbies_hobby FOREIGN KEY (hobby_id) REFERENCES hobbies (id)
);

CREATE INDEX idx_user_hobbies_hobby_id ON user_hobbies (hobby_id);

CREATE TABLE officer_roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_officer_roles_name UNIQUE (name)
);

CREATE TABLE officer_terms (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    generation INT NOT NULL,
    phase INT NOT NULL,
    started_at DATE NOT NULL,
    ended_at DATE NOT NULL,
    current_term BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_officer_terms_generation_phase UNIQUE (generation, phase)
);

CREATE INDEX idx_officer_terms_current_term ON officer_terms (current_term);

CREATE TABLE officer_histories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    officer_term_id BIGINT NOT NULL,
    officer_role_id BIGINT NOT NULL,
    started_at DATE NOT NULL,
    ended_at DATE,
    payment_status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_officer_histories_user_term UNIQUE (user_id, officer_term_id),
    CONSTRAINT fk_officer_histories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_officer_histories_term FOREIGN KEY (officer_term_id) REFERENCES officer_terms (id),
    CONSTRAINT fk_officer_histories_role FOREIGN KEY (officer_role_id) REFERENCES officer_roles (id)
);

CREATE INDEX idx_officer_histories_payment ON officer_histories (payment_status);
CREATE INDEX idx_officer_histories_term ON officer_histories (officer_term_id);

CREATE TABLE posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    status VARCHAR(50) NOT NULL,
    thumbnail_url VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_posts_status_id ON posts (status, id);
