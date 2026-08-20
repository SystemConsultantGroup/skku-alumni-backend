-- 임원 기여금 금액을 총동창회 회비안내 기준으로 정정한다.
--
-- 기존 로직이 회장 100만원, 그 외 전부 30만원으로 부과 금액을 매겨서
-- 이사(실제 10만원)와 감사(실제 100만원), 부회장(실제 100만원)이 30만원으로
-- 남아 있다. 상임이사와 자문위원은 30만원이 맞아 손대지 않는다.
--
-- 30만원짜리 기록만 고친다. 그 값이 옛 로직의 일괄 기본값이므로, 사무처가
-- 예외 금액(공직자 부회장 50만원 등)을 직접 넣어둔 기록은 그대로 살아남는다.
--
-- 회장은 건드리지 않는다. 회비안내의 "1억원 이상"은 하한이지 정해진 금액이
-- 아니라서 기계적으로 채워 넣을 수 없다. 사무처가 실제 약정 금액으로 넣어야 한다.
--
-- JOIN UPDATE 대신 EXISTS 를 쓴다. 다중 테이블 UPDATE 문법은 MySQL 전용이고
-- 테스트가 도는 H2에서 깨진다.

UPDATE payment_records
SET amount = 1000000,
    updated_at = CURRENT_TIMESTAMP
WHERE amount = 300000
  AND EXISTS (
      SELECT 1
      FROM officer_histories oh
      JOIN officer_roles r ON r.id = oh.officer_role_id
      WHERE oh.user_id = payment_records.user_id
        AND oh.officer_term_id = payment_records.officer_term_id
        AND r.name IN ('부회장', '감사')
  );

UPDATE payment_records
SET amount = 100000,
    updated_at = CURRENT_TIMESTAMP
WHERE amount = 300000
  AND EXISTS (
      SELECT 1
      FROM officer_histories oh
      JOIN officer_roles r ON r.id = oh.officer_role_id
      WHERE oh.user_id = payment_records.user_id
        AND oh.officer_term_id = payment_records.officer_term_id
        AND r.name = '이사'
  );
