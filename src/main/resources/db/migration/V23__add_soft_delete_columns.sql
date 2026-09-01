-- 관리자가 지운 데이터를 되살릴 수 있게 남긴다.
--
-- 회원 상세 화면에서 사무처가 회원과 연결된 모든 데이터를 직접 고치고 지운다.
-- 잘못 지웠을 때 되돌릴 방법이 없으면 손댈 수 없는 화면이 된다. 행을 실제로
-- 지우는 대신 지운 시각을 남기고, 조회에서 걸러낸다.
--
-- users 는 status = 'WITHDRAWN' 이 이미 "보이지 않는 회원"을 뜻한다. 관리자
-- 삭제는 여기에 deleted_at 을 함께 찍는다. 본인 탈퇴(개인정보를 비우는 것)와
-- 관리자 삭제(데이터를 남겨두는 것)를 구분해야 복구가 가능하다.
--
-- 유일 제약이 걸린 테이블(user_hobbies, club_members, officer_histories,
-- payment_records, companies, hobbies, user_blocks)은 지운 행이 자리를 계속
-- 차지한다. 같은 값을 다시 넣을 때는 새 행을 만들지 말고 지운 행을 되살린다.
-- 부분 유일 색인은 MySQL 에도 H2 에도 없어서 제약을 바꿀 수단이 없다.

ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE posts ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE companies ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE hobbies ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE user_hobbies ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE user_web_links ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE club_members ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE officer_histories ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE payment_records ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE device_tokens ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE user_blocks ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE profile_change_logs ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE reports ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_users_deleted_at ON users (deleted_at);
CREATE INDEX idx_posts_deleted_at ON posts (deleted_at);
