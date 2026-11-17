# 총동창회 임기 등록과 적용

임기는 DB에 미리 생성하며 5월 1일부터 다음 해 4월 30일까지다. 실제 현행 임기는 서울 날짜가 임기 기간에 포함되는지로 판단하므로 관리자 활성화 없이 적용된다. 기존 임원은 자동 복사하지 않는다.

사무처는 `POST /api/v1/admin/officer-terms`에 `{"generation":41,"phase":1,"startedAt":"2027-05-01","endedAt":"2028-04-30"}`을 전송하여 차기 임기를 준비한다. 4월 1일부터 `GET /api/v1/admin/officer-terms/options`에서 현 임기와 생성된 차기 임기를 조회한다. 4월 이전에는 현 임기만 선택 가능하다. 응답 항목은 `officerTermId`, `generation`, `phase`, `startedAt`, `endedAt`, `type`, `selectable`을 포함한다.

기존 `POST /api/v1/admin/members/excel` multipart 요청에 선택한 `officerTermId`를 추가한다. 생략하면 현 임기다. 엑셀 서식은 바뀌지 않는다. 회원별 `POST /api/v1/admin/members/{memberId}/officer-histories`의 기존 `officerTermId`, 신청 승인 `PATCH /api/v1/admin/applications/{id}/status`의 선택적 `officerTermId`에도 같은 선택 규칙을 적용한다.

기존 `POST /api/v1/admin/officer-terms/{id}/activate`는 호환용으로 유지하며 현재 날짜에 해당하는 임기만 성공을 반환하고 임기 전환을 수행하지 않는다. `current_term` DB 컬럼은 더 이상 현행 임기 판단에 사용하지 않는다. 임원 이력과 회비는 보존되며 이전 임기 회비를 새 임기로 복사하지 않는다. 접근 유예는 기본 7일이다.
