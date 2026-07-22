ALTER TABLE users ADD COLUMN work_zipcode VARCHAR(50);
ALTER TABLE users ADD COLUMN work_address1 VARCHAR(255);
ALTER TABLE users ADD COLUMN work_address2 VARCHAR(255);

UPDATE users u
JOIN companies c ON c.id = u.company_id
SET u.work_zipcode = c.work_zipcode,
    u.work_address1 = c.work_address1,
    u.work_address2 = c.work_address2;
