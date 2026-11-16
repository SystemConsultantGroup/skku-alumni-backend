# 납부 전환 및 프런트엔드 계약

`payment_records`가 현재 납부 상태와 금액·일자의 원본이다. 기존 `officer_histories.payment_status`는 기존 스키마의 NOT NULL 요구 때문에 신규 임원 이력에 `UNPAID` 자리값을 넣지만, 납부 변경 때 갱신하지 않는다. 관리용 `/api/v1/admin/payment-transition/histories`와 `/orphan-payments`는 구 컬럼과 레코드 누락·불일치·논리 삭제를 검토하는 읽기 전용 목록이다. 이 목록의 차이는 전환 이후 정상적으로도 발생한다.

배포 전 기존 현행 임원에 대해 위 목록과 납부 금액·일자·삭제 상태를 검토하고 누락이나 불일치를 별도로 확인한다. 자동으로 어느 쪽을 덮어쓰지 않는다. 구 서버는 구 컬럼을 읽을 수 있으므로 이 버전에서 납부가 바뀐 뒤에는 구 서버로의 롤백만으로 납부 상태가 일치하지 않는다. 롤백 전 변경분을 검토하고 백업을 확보한 후 승인된 별도 정합성 복구 절차가 필요하다.

기존 신청 승인 URL과 `{status: "APPROVED"}` 요청을 유지한다. 후보가 모호하거나 식별자가 충돌하면 기존 계정을 자동 병합하지 않고 충돌 응답을 반환한다. 관리 화면은 후보를 확인하고 `existingUserId`를 지정하거나 신규 인물임을 확인하는 `confirmedNoMatch`를 전달해야 하며, 직책 이름을 해석할 수 없으면 `officerRoleId`가 필요하다.

단위동문회 Excel은 `/api/v1/admin/alumni-organizations/{organizationId}/roster-excel/preview`의 결과와 토큰을 확인한 뒤 `/apply`에 같은 파일, `token`, `confirmed=true`를 보낸다. 헤더만 있는 파일도 유효하며 미리보기의 `ending`이 전체 현직자를 보여준다. 유지 직책의 날짜 정정은 `UPDATE_DATES`로 표시되며 빈 날짜는 기존 값을 유지한다.
