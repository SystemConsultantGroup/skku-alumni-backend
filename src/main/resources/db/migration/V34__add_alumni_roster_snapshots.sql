ALTER TABLE alumni_organizations ADD COLUMN roster_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE alumni_roster_snapshots (
    audit_log_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    before_snapshot LONGTEXT NOT NULL,
    after_snapshot LONGTEXT NOT NULL,
    after_hash VARCHAR(64) NOT NULL,
    added_count INT NOT NULL,
    ended_count INT NOT NULL,
    rollback_audit_log_id BIGINT NULL,
    rolled_back_at TIMESTAMP NULL,
    PRIMARY KEY (audit_log_id, organization_id),
    CONSTRAINT fk_roster_snapshot_audit FOREIGN KEY (audit_log_id) REFERENCES admin_audit_logs(id),
    CONSTRAINT fk_roster_snapshot_org FOREIGN KEY (organization_id) REFERENCES alumni_organizations(id),
    CONSTRAINT fk_roster_snapshot_rollback FOREIGN KEY (rollback_audit_log_id) REFERENCES admin_audit_logs(id)
);
CREATE INDEX idx_roster_snapshot_org ON alumni_roster_snapshots (organization_id, audit_log_id);
