-- 이미지 주소를 호스트에서 떼어낸다.
--
-- 업로드 응답이 요청 호스트로 절대 주소를 만들고, 그 값을 그대로 저장해 왔다.
-- 그래서 사진이 "올릴 때 쓴 주소"에 묶였다 — 사무처가 내부 주소로 한 번 올리거나
-- 운영 도메인이 바뀌면 다른 사람 화면에서는 깨진 채로 남는다. 게시글은 본문
-- 마크다운에 주소가 박히므로 지난 글까지 통째로 영향을 받는다.
--
-- 앞으로는 경로(/api/v1/...)만 저장하고 주소는 화면이 볼 때 붙인다.
-- 이미 저장된 값도 같은 형태로 맞춘다.

UPDATE users
SET profile_image_url = SUBSTRING(profile_image_url, LOCATE('/api/v1/profile-images/', profile_image_url)),
    updated_at = updated_at
WHERE profile_image_url LIKE 'http%'
  AND LOCATE('/api/v1/profile-images/', profile_image_url) > 0;

UPDATE posts
SET thumbnail_url = SUBSTRING(thumbnail_url, LOCATE('/api/v1/post-images/', thumbnail_url)),
    updated_at = updated_at
WHERE thumbnail_url LIKE 'http%'
  AND LOCATE('/api/v1/post-images/', thumbnail_url) > 0;

UPDATE posts
SET thumbnail_url = SUBSTRING(thumbnail_url, LOCATE('/api/v1/profile-images/', thumbnail_url)),
    updated_at = updated_at
WHERE thumbnail_url LIKE 'http%'
  AND LOCATE('/api/v1/profile-images/', thumbnail_url) > 0;

-- 본문은 한 글에 이미지가 여럿일 수 있다. REGEXP_REPLACE 로 모두 바꾼다.
-- (MySQL 8.0+ 필요. 이 프로젝트는 8.4 를 쓴다.)
UPDATE posts
SET body = REGEXP_REPLACE(body, 'https?://[^/[:space:])]+(/api/v1/(profile-images|post-images)/)', '$1'),
    updated_at = updated_at
WHERE body REGEXP 'https?://[^/[:space:])]+/api/v1/(profile-images|post-images)/';
