UPDATE users
SET password = '$2a$10$B/FWZnKxEpNedaqTtRpNOuFUr0ybpBoOYWxRDyBbffaxYP0NGd/Ei'
WHERE kingo_id IN ('skku.park', 'skku.kim', 'skku.lee', 'skku.choi');

UPDATE admins
SET password = '$2a$10$B/FWZnKxEpNedaqTtRpNOuFUr0ybpBoOYWxRDyBbffaxYP0NGd/Ei'
WHERE email IN ('master@skku-alumni.local', 'staff@skku-alumni.local', 'asis@skku-alumni.local');
