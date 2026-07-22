ALTER TABLE users ADD COLUMN work_zipcode VARCHAR(50);
ALTER TABLE users ADD COLUMN work_address1 VARCHAR(255);
ALTER TABLE users ADD COLUMN work_address2 VARCHAR(255);

UPDATE users
SET work_zipcode = (SELECT c.work_zipcode FROM companies c WHERE c.id = users.company_id),
    work_address1 = (SELECT c.work_address1 FROM companies c WHERE c.id = users.company_id),
    work_address2 = (SELECT c.work_address2 FROM companies c WHERE c.id = users.company_id)
WHERE company_id IS NOT NULL;
