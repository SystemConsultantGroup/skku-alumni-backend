-- 알림을 종류별로 끄고 켤 수 있게 나눈다.
--
-- 총동창회 회의에서 "동호회 알림은 너무 잦아 안 받고 싶은데 공지·뉴스는 받고 싶다"는
-- 요구가 나왔다. notification_enabled 하나로는 전부 받거나 전부 끄는 것밖에 안 된다.
-- 기존 컬럼은 전체 스위치로 남기고, 그 아래에 종류별 스위치를 둔다.

ALTER TABLE users
    ADD COLUMN notice_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER notification_enabled,
    ADD COLUMN club_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER notice_notification_enabled;

-- 지금까지 알림을 꺼둔 회원은 종류별 스위치도 꺼진 상태로 시작한다.
UPDATE users
SET notice_notification_enabled = notification_enabled,
    club_notification_enabled = notification_enabled;
