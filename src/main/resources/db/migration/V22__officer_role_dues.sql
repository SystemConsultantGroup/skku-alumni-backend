-- 직책별 임원 기여금을 데이터로 옮긴다.
--
-- 지금까지 금액은 코드에 박혀 있어서 회비가 조정될 때마다 배포가 필요했다.
-- 총동창회 회비안내의 금액은 임기와 회칙에 따라 바뀌는 값이라 사무처가 직접
-- 고칠 수 있어야 한다.
--
-- 예외 문구도 함께 둔다. "공직자·교육자·은퇴동문은 50만원" 같은 조건은 회원
-- 정보만으로 판정할 수 없어 사람이 보고 금액을 지정해야 하는데, 그 판단 근거를
-- 화면에 띄워주려면 어딘가 적혀 있어야 한다.

ALTER TABLE officer_roles ADD COLUMN dues_amount INT NOT NULL DEFAULT 0;

ALTER TABLE officer_roles ADD COLUMN dues_note VARCHAR(255) NULL;

-- 초기값은 총동창회 회비안내(alumni.skku.edu) 기준이다.
-- 회장의 "1억원 이상"은 하한이라 정해진 금액이 아니다. 0으로 두어 사무처가
-- 실제 약정 금액을 넣도록 한다. 임의의 값을 채우면 틀린 금액이 조용히 부과된다.
--
-- dues_note 는 회원 앱 소개 화면에도 그대로 노출된다. 관리자에게 시키는 말이
-- 아니라 회원이 읽을 문장으로 적는다.
UPDATE officer_roles SET dues_amount = 0, dues_note = '약정에 따릅니다. 사무처로 문의해 주세요.' WHERE name = '회장';
UPDATE officer_roles SET dues_amount = 1000000, dues_note = '공직자·교육자·은퇴동문은 50만원' WHERE name = '부회장';
UPDATE officer_roles SET dues_amount = 300000, dues_note = '공직자는 20만원' WHERE name = '상임이사';
UPDATE officer_roles SET dues_amount = 100000 WHERE name = '이사';
