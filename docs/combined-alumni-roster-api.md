# 단위동문회 통합 명단 API

관리자 인증이 필요하며 기존 관리자 역할 제한을 따른다. 모든 경로의 접두사는 `/api/v1/admin/alumni-roster-uploads`다.

* `GET /template`: XLSX 다운로드. 컬럼 순서는 `단위동문회, 회원ID, 이름, 직책, 회장, 학번, 전화번호, 시작일, 종료일`이다. 회장은 `Y` 또는 `N`, 날짜는 `YYYY-MM-DD` 또는 Excel 날짜다.
* `POST /preview`: multipart `file`. 조직별 `existingCount`, `uploadedCount`, `rows`, `ending`과 적용 시 제출할 `token`을 반환한다. `rows.operation`은 기존 명단 동기화의 `KEEP`, `APPOINT`, `CREATE_USER_AND_APPOINT`, `UPDATE_DATES`, `EXTEND`를 사용한다. 직책 변경은 기존 직책의 퇴임과 신규 직책 등록으로 표시된다. 후보 확인과 오류는 기존 동문회별 API 정책을 따른다.
* `POST /apply`: 같은 multipart `file`, `token`, `confirmed=true`. `{jobId, organizations:[{organizationId,name,added,ended,unchanged}]}`를 반환한다. 미리보기 이후 대상 명단·조직·회원 후보가 바뀌면 409다.
* `GET ?cursor=&size=`: 관리자 감사 로그 ID를 작업 ID로 사용한 목록과 대상 수·롤백 수를 반환한다. 다음 목록은 마지막 `id`를 `cursor`로 보낸다.
* `GET /{jobId}`: 조직별 변경 건수, 변경 전후 관계 스냅샷, 롤백 시각 및 롤백 감사 로그 ID를 반환한다. 스냅샷에는 회원명·전화번호를 중복 저장하지 않는다.
* `POST /{jobId}/rollback/preview`, `POST /{jobId}/rollback`: JSON `{"organizationIds":[1,2]}`. 각 조직별 `READY`, `CONFLICT`, `ALREADY_ROLLED_BACK` 또는 실행 후 `ROLLED_BACK` 상태를 반환한다. 충돌 조직은 건드리지 않으며 가능한 조직만 같은 트랜잭션에서 복원한다.

동문회 이름은 모든 유니코드 공백을 제거해 정확히 비교한다. 일치 조직이 없거나 둘 이상이면 오류다. 기존 회원은 기존 동문회별 양식과 마찬가지로 회원ID를 명시하고 이름과 입력한 식별자를 대조한다. 여러 동문회에 같은 사람을 넣을 수 있다. 여러 조직에 반복되는 신규 인물은 먼저 회원을 등록해 회원ID를 지정해야 한다.

미리보기와 적용은 기존 회원 생성 잠금 후 조직 ID 오름차순으로 조직 잠금을 획득한다. 조직의 `roster_revision`과 소속 이력의 상태 해시로 업로드 이후 변경을 감지한다. 롤백은 원래 행을 복원하고 업로드로 생성한 관계는 물리 삭제하지 않고 퇴임 이력으로 남긴다. 사용자 계정은 변경하지 않는다. API 이외의 직접 DB 변경이 있을 때는 데이터 상태 해시로 차이를 감지하지만, 직접 변경 후 원상복귀까지 된 경우에는 감지할 수 없다.

기존 `/api/v1/admin/alumni-organizations/{organizationId}/roster-excel` API는 유지한다. 빈 명단을 통한 전체 퇴임은 차단하며 동문회 해제 기능을 이용한다.
