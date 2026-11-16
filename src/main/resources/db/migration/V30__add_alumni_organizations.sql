-- Additive only: existing user, officer and payment rows are untouched.
ALTER TABLE users ADD COLUMN account_blocked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE administrative_person_lock (
    id INT PRIMARY KEY
);
INSERT INTO administrative_person_lock (id) VALUES (1);

CREATE TABLE officer_rollover_lock (
    id INT PRIMARY KEY
);
INSERT INTO officer_rollover_lock (id) VALUES (1);

CREATE TABLE alumni_organizations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    dissolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE alumni_organization_officers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    president BOOLEAN NOT NULL DEFAULT FALSE,
    known_started_at DATE NULL,
    effective_started_at DATE NOT NULL,
    scheduled_ended_at DATE NULL,
    ended_at DATE NULL,
    ended_at_recorded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_alumni_officer_organization FOREIGN KEY (organization_id) REFERENCES alumni_organizations (id),
    CONSTRAINT fk_alumni_officer_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_alumni_officer_organization ON alumni_organization_officers (organization_id, ended_at);
CREATE INDEX idx_alumni_officer_user ON alumni_organization_officers (user_id, ended_at);

CREATE TABLE alumni_roster_syncs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_roster_sync_organization FOREIGN KEY (organization_id) REFERENCES alumni_organizations (id)
);
CREATE INDEX idx_roster_sync_latest ON alumni_roster_syncs (organization_id, id);

CREATE TABLE vip_qualifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT fk_vip_qualification_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_vip_qualification_user ON vip_qualifications (user_id, revoked_at);
