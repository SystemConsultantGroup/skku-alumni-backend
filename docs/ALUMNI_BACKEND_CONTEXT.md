# Alumni Backend Renewal — 프로젝트 컨텍스트 (단일 기준 문서)

> 갱신 기준: 2026-11-16. 이 문서는 요구사항·기존 구현·운영 호환성·검증 현황을 보관한다. **실제 실행 지시는 별도 Codex 메시지에서 한다.** 과거 진행 보고를 현재 코드의 사실로 단정하지 않는다. 충돌 시 사용자 최신 지시 → 현재 로컬 코드/테스트 → 이 문서의 순서로 확인한다.

## 1. 작업 성격과 경계

이미 운영 중인 성균관대학교 총동창회 앱의 **백엔드 개편**이다. 기존 회원·현직 임원·납부·이력·게시물·이미지·관리자 데이터를 보존하면서 신규 정책을 반영한다. 신규 구축이 아니다.

- 백엔드 저장소: `SystemConsultantGroup/skku-alumni-backend`. 기존 인계 당시 Windows 로컬 경로 `C:\Users\scg\Desktop\alumni\skku-alumni-backend`, 브랜치 `feature/alumni-backend-renewal`, 기준 `origin/develop` (`c8b28d4b3b55d8d834a2cefe9f243a59d3c2f6d0`). **시작 시 현재 상태를 직접 확인**한다. 원격 브랜치는 `dev`가 아닌 `develop`.
- 참고용 저장소: `SystemConsultantGroup/skku-alumni-frontend`, `SystemConsultantGroup/skku-alumni-config`, `SystemConsultantGroup/kubernetes`. **프런트엔드·인프라 저장소는 읽기 전용**으로 취급한다. 백엔드 이외의 변경은 새 사용자 지시 없이는 수행하지 않는다.
- 모든 **기존 로컬 수정·untracked 파일을 보존**한다. `git status`, diff 및 새 파일을 먼저 점검한다. `git diff --stat`만으로 untracked 작업을 판단하지 않는다.
- **Git commit/push/merge/PR 생성 금지.** 운영 DB 변경, 운영/공유 dev DB에 Flyway 실행, 실제 Kubernetes/ArgoCD/Vault 변경·배포 금지. 사용자가 별도로 명시적으로 승인할 때까지 금지.
- 로컬 격리 DB, 테스트, 빌드, 안전한 읽기 전용 코드 분석은 허용. 운영에서 검증하지 못한 부분은 미검증으로 표시한다.
- 프로젝트의 `AGENTS.md` 지침과 현재 실제 코드·스키마를 확인한다. Flyway가 DDL을 소유하고 JPA `ddl-auto: validate`를 유지한다. 기존 코드와 API 계약의 호환성을 우선한다.

## 2. 시스템/배포 구조

- 백엔드: Java 25, Spring Boot 4.1, JPA + JdbcTemplate, Spring Security, Flyway, MySQL/PXC, Redis, JWT, Apache POI, MinIO, Firebase FCM.
- 기존 배포 경로: `.github/workflows/cd-prod.yml` → Docker Hub → `skku-alumni-config`의 Kustomize + Vault placeholder.
- 신규 플랫폼 경로: `.github/workflows/platform.yaml` → GHCR digest → `kubernetes/applications/alumni/instances/production.yaml` → ArgoCD ApplicationSet/Helm + Vault/External Secrets Operator.
- 새 플랫폼의 `applications/alumni/meta.yaml`에서 BE `deploymentStrategy: Recreate`, readiness `/actuator/health/readiness`, `SPRING_PROFILES_ACTIVE=prod,platform` 확인 기록이 있다. **현재 실제 클러스터와 트래픽 경로는 미확인**이다.
- 새 플랫폼의 테스트 도메인이 실제 운영 DB를 공유할 수 있다. 독립된 테스트 DB라고 가정하지 않는다.
- 저장소 YAML에 PXC 백업/PITR 설정이 있어도 실제 성공한 백업·복구나 정상 rollout은 확인된 사실이 아니다.
- 설정 추가 시 `application*.yml`의 기본값, DB/Redis/JWT/MinIO/FCM 환경변수, 기존 Config Repo와 신규 Vault/ExternalSecret 양쪽과의 호환성을 검토한다. 불필요한 infra 변경은 하지 않는다.

## 3. 핵심 도메인 정책 (확정)

### 3.1 자격 및 접근

- **동일인 = `users` 계정 한 개**. 총동창회 임원·여러 단위동문회 간부·VIP 겸직 가능.
- 총동창회 임원 자격은 **해당 임기에 대한 관리자 승인**으로 발생. 회비 납부와 무관하게 현직 임원정보에 표시된다. 별도 '활성화' 관리자 단계는 제거한다.
- 승인 직후 **LIMITED**; 해당 임기의 납부 확인 후 **FULL**. 회비 여부는 `payment_records`를 기준으로 한다.
- LIMITED: 동문 신분증, 공지 조회 및 필수 계정·비밀번호 관리. FULL: 기존 임원정보, 병원/경조사/주차 혜택, 커뮤니티, 비즈니스, 통합검색 등 모든 정상 회원 기능.
- 권한 우선순위 **총동창회 임원 > 단위동문회 현직 회장 > VIP**. 총동창회 자격의 회비로 FULL/LIMITED를 정하며 회장/VIP만으로 FULL이 되지 않는다. 자격 하나 종료 시 다른 자격은 유지한다.
- 단위동문회의 **일반 간부**는 단위 소속만으로 로그인할 수 없다. **현직 회장**은 LIMITED. **관리자 등록 VIP**도 LIMITED이며 자격의 부여·회수 및 이력을 관리한다.
- 신청 거절/승인 대기, 탈퇴, 관리자 차단, 삭제를 구별해야 한다. 기존 `PENDING`의 의미가 혼재할 수 있으므로 일괄 활성화하지 않는다. 향후 더 세분화된 Permission에 대응 가능한 인증/인가 구조를 사용한다. 관리자 MASTER/STAFF/ASIS_OPERATOR 제한은 보존한다.
- 로그인 JWT와 Refresh Token, 계정 접근 권한은 서로 다른 개념이다. 보호 대상 API에는 서버 측 인가가 필수다.

### 3.2 총동창회 공식 임기

- 원칙적으로 매년 5월 1일 시작~다음 해 4월 30일 종료, 한 대 2기, Asia/Seoul.
- 종료 후 **7일은 이전 서비스 이용 권한만 유예**. 다음 날부터 '현직 임원정보' 목록에서는 빠진다. 예: 4/30 종료 → 5/1~5/7 유예 → 5/8 종료.
- **새 임기 승인 시 새 임기 미납 상태가 기존 임기 유예·납부보다 우선**. 납부 상태를 새 임기로 승계하지 않는다.
- 임기 시작 월/일, 대당 기수, 유예일, 시간대는 YAML 설정으로 외부화. `officer_terms`의 이미 기록된 실제 임기를 소급 변경하지 않는다. 기존 `OfficerTermRolloverService`의 다중 기동·날짜 경계·잠금 동작도 고려한다.

### 3.3 납부 데이터 정규화

- `payment_records`가 납부 **유일 원본**. `officer_histories`는 직책·임기 이력만 관리. 기존 `officer_histories.payment_status`는 단계적 제거 대상.
- 운영 데이터에 중복·불일치·누락·논리 삭제·금액이 있을 수 있다. 불일치 시 확인 없이 어느 한쪽 값으로 덮어쓰지 않는다.
- 스키마 확장 → 실제 데이터 확인/필요시 보정 → 코드 기준 전환 → 구버전 호환 불필요 시 컬럼 제거. 당장 컬럼이 남아 있다고 무조건 실패가 아니다. 기존 JDBC 쓰기 경로에서 호환용 중복 쓰기를 하고 있을 수 있으므로 단일 원본의 의미와 전환 단계를 구별한다.
- 과거 같은 임기의 직책 변경도 덮어쓰지 않고 이력 보존. 직책 변경의 실제 과거 발생일을 모르면 추정하지 않는다.

### 3.4 단위동문회

- 지역·취미·학과·산업 등을 포함하는 **동적 조직**, 직책명은 자유 텍스트. 총동창회 공식 대/기 구조를 강요하지 않는다.
- 조직 생성/수정/현직 간부 조회/과거 이력/폐지 지원.
- 현직 간부의 시작일이 불명확하면 등록일을 유효 시작일로 사용할 수 있으나 실제 취임일 미상이라는 사실은 구분할 것. 과거 재직 기간도 등록 가능. 과거 등록자는 현직 목록과 회장 권한에 들어가지 않는다.
- **한 조직에 현직 회장 최대 한 명**. 개별 회장 등록은 기존 회장을 수동 임기만료해야 가능; 회장 외 동일 직책 중복 허용. DB 동시성도 방어.
- 임기만료는 현직에서 제외하고 종료일을 남기되 이력은 보존. 조직 폐지 시 현직 간부 전원 임기 종료, 회장 자격 회수, 조직·이력 보존.
- 한 사람은 여러 단위동문회 및 총동창회 직책을 동시에 보유할 수 있다.

### 3.5 관리자 등록 및 계정 발급

- 관리자가 단위동문회 간부/VIP 인물을 등록할 때 `users`에 아직 없을 수 있다. 학번·전화번호 등으로 기존 후보를 검색하고 신중히 재사용하며, 신규일 때 새 계정 생성. 동명이인 자동 병합 금지. 학번 없는 인물도 허용.
- 초기 로그인 ID: 이름+전화 끝 4자리 → 전화 없으면 학번 → 둘 다 없으면 생성 시간 기반; 충돌시 고유 suffix. **초기 비밀번호 `1111`(고객 요구)**, 해시 저장, 로그인 시도 제한 및 변경 허용. 기존 계정의 ID/비밀번호는 초기화하지 않는다.
- VIP 부여/회수는 계정 삭제와 별개로 처리. 과거 VIP 이력 보존.

### 3.6 단위동문회 Excel

- 기존 `AdminMemberExcelController`의 Apache POI, 템플릿, 검사, 오류, 트랜잭션을 참고하되 **학번 필수**를 복사하지 않는다.
- 개별 등록과 달리 **Excel 업로드는 해당 단위동문회의 현직 명단 전체 교체/동기화**. 사전 미리보기·확인 권장.
- 엑셀에 없는 현직자 종료; 동일 사용자+조직+직책이면 현재 이력 유지/연장; 직책이 바뀌면 이전 종료+새 이력; 신규 인물은 계정 및 직책 생성; 회장 교체 허용, 단일회장 보장.
- 이전 과거 이력 보존, 반복 업로드 멱등성, 동일인 충돌/복수 회장/날짜 오류 검증, 트랜잭션 원자성. 영향받은 사용자 권한 캐시 갱신.

### 3.7 임원정보, 신분증, 통합검색

- '임원정보' **목록은 현직 총동창회 임원만**, 납부 여부와 무관. 임기 종료 다음 날부터 제외; 단위 전용/VIP 전용 제외.
- 목록의 인물 상세에는 총동창회와 단위동문회 **현직·과거 모든 직책을 최신순으로 통합**. FULL 사용자만 열람.
- 중앙 임원 직책이 없는 회장/VIP의 신분증은 **'성균관대학교 동문'** 등 일반 동문으로 표시.
- FULL 전용 통합검색: **서비스 바로가기 → 커뮤니티/비즈니스 게시물 → 인물**.
- 게시물: 제목/본문/작성자, 요약 미리보기 및 더보기, 기존 삭제·숨김·차단 정책 준수.
- 인물: 현직 총동창회 임원 + **각 단위동문회 현직 간부 전체**, 과거 전용/VIP 전용 제외. 중복 인물은 하나만 표시하고 총동창회 소속을 우선 분류, 단위 소속은 부가 표시.
- 각 범주별 안정적 더보기/커서, DB 인덱스·한국어 검색 품질·관련도/우선순위 확인. MySQL 중심 구현을 우선하고 필요 없는 외부 검색 엔진 추가 금지. 검색 저장소와 API를 분리해 향후 교체 가능하게 설계한다.

## 4. Redis 권한 캐시 — 최신 확정 정책 (이전 지시보다 우선)

기존 Access JWT는 신원, Redis Refresh Token은 세션 지속에 사용된다. **권한 캐시는 Refresh Token 내부가 아니라 별도 Redis 키**로 둔다. MySQL이 영속 권한 원본이다.

- **정상적인 캐시 HIT에서는 권한 판정을 위한 MySQL 재조회가 없어야 한다.** MISS 때 DB 계산 후 Redis 캐시.
- 기본 **짧은 TTL 약 30초**. Asia/Seoul 날짜가 바뀌면 새 키를 사용하거나 권한 유효 종료시점에 만료되도록 한다. Refresh Token 키는 삭제하지 않는다. `FLUSHDB` 금지.
- 관리자 임원 승인/취소·납부 변경·VIP 부여/회수·회장 등록/임기만료·단위 폐지·Excel 명단 교체·차단 등에서 **DB 커밋 이후 해당 사용자 권한 캐시 무효화**. 정상 작동에서는 빠른 반영.
- **새롭게 수용한 현실적 일관성 정책:** DB 커밋 후 Redis 무효화 실패 시 **최대 캐시 TTL 수준의 오래된 권한 잔존 위험을 명시적으로 허용**한다. 이 위험을 0으로 만들려고 분산 트랜잭션·영속 outbox 등 복잡한 시스템을 도입하지 않는다. 실패 기록 및 가능한 재시도, TTL을 통한 회복을 구현한다. 무효화 성공과 장애 시 의미를 구분하고 즉시 회수를 절대 보장한다고 주장하지 않는다.
- Redis 장애 시 오래된 미검증 값을 사용하지 말고 DB 최신 판정으로 폴백하거나 안전하게 요청을 거부한다. Refresh Token 동작은 보존.
- 테스트: HIT에서 권한용 DB 호출 0; MISS 계산/캐싱; 정상적인 권한 변경 후 즉시 반영; 무효화 실패·롤백·자정·유예 경계·Redis 장애. 캐시가 있는 경로와 로그인/Refresh 경로의 의미를 구별한다.

## 5. 이전 작업 진행 기록 — *현재 코드로 재확인할 것*

아래는 과거 인계와 최신 사용자 전달 결과를 취합한 것으로, **현재 저장소 상태를 직접 확인하지 않은 추정 완료 목록**이다.

- `V30__add_alumni_organizations.sql`: `users.account_blocked`, 인물/임기 잠금 테이블, `alumni_organizations`, `alumni_organization_officers`, `alumni_roster_syncs`, `vip_qualifications` 등 신규 구조. 기존 컬럼 삭제 없음.
- `V31`: 이전 직책 값을 `officer_role_change_history`에 보존하고 상세에서 조회하도록 변경한 기록. 실제 날짜를 추정하지 않음.
- 접근 판정 관련: `domain/access/{AccessQualificationService,AccessLevel,Permission,AccessPermissionCache}`, `global/security/MemberPermissionFilter`, `SecurityConfig`, `AuthController`.
- 신규 조직/계정: `domain/organization/{AlumniOrganizationService,VipQualificationService,AdministrativePersonService,AlumniRosterWorkbook,AlumniRosterSyncService}`. 엑셀 미리보기·일괄 교체·기본 인물 매칭 등의 구현 기록.
- 새 API 기록: `/api/v1/admin/alumni-organizations` 및 하위 officers/enroll/expire/dissolve/roster-excel, `/api/v1/admin/person-candidates`, `/api/v1/admin/vip-enrollments`, `/api/v1/admin/vips`, `/api/v1/admin/members/{memberId}/vip`, `/revoke`, `/access-block`; `/api/v1/me/access`, `PATCH /api/v1/me/login-id`; 통합검색 `/api/v1/search`. API 실제 매핑은 소스에서 재확인.
- 기존 `AdminFeatureController`, `OfficerAssignment`, `AdminMemberRelationController`, 기존 Excel의 납부·승인 쓰기 경로와 `MemberDirectoryService`/`MemberRepository`/`UserFeatureController`는 업무 변경 영향이 큼.
- 신청 승인 API에 `officerRoleId`, `existingUserId`, `confirmedNoMatch`가 들어갔다는 인계 기록이 있으며, 프런트가 기존 `{status}`만 보내면 **승인 기능 호환 문제가 있을 수 있음**. 백엔드 호환 조치 검토, 프런트 직접 수정 금지.
- **가장 최근 사용자 제공 Codex 결과:** 전체 H2 통과; 격리된 로컬 MySQL 8.4에서 **빈 DB V1–V31**, 테스트 Spring Boot 기동/Hibernate validate, **V29→V31 업그레이드 및 납부 금액 보존** 통과, ngram 테스트/FULLTEXT 실행 계획 확인. 다만 실제 PXC/dev 검증은 아니다.
- **가장 최근 Redis 결과:** 테스트 일부 추가되었지만 **캐시 HIT에서도 MySQL 재조회**, 정상적인 권한 캐시 성능 목표 미완성. 위 §4의 30초 TTL·무효화 실패 시 유예 허용 정책으로 수정해야 한다.
- 과거 인계에 있던 Excel `LocalDate` 직렬화 오류는 이후 수정되어 전체 오프라인 테스트 통과했다는 기록이 있다. 현재 코드에서 재발했는지만 확인하고 해결한 문제를 처음부터 재구현하지 않는다.
- 통합검색은 `/api/v1/search`에서 최초 LIKE 기반 구현 기록; 이후 사용자 보고에서 ngram 인덱스/검색 계획을 로컬 검증했다고 보고함. **실제 최신 구현 및 기능 완성도는 코드로 확인**.
- 기존 `PENDING` 사용자 의미 및 `account_blocked` 기본값이 운영 데이터에서 어떻게 작동하는지, 기존 납부 불일치 정정, 중복 납부 컬럼 최종 제거 등은 미해결/미검증. 특히 **PENDING 전환은 실제 dev 데이터 확인 전 배포 차단 요소**.
- `officer_histories`의 사용자/임기 유일 제약과 `OfficerAssignment.ensureHistory`는 과거 역할을 덮어쓴 이력이 있을 수 있어 V31의 별도 이력 보존 범위를 확인해야 한다.
- 새 플랫폼 실제 운영 경로/DB 스키마·Flyway 이력, PXC/Galera 분산 잠금, 백업 복원, Redis 실제 장애, frontend 연동, dev 배포는 아직 검증되지 않았다.

## 6. 로컬 검증과 운영 검증의 경계

- 로컬 격리 MySQL 8.4 시험은 운영/PXC의 DDL/잠금 인증을 증명하지 않는다.
- 기존 MySQL 테스트 환경은 Windows 임시 `127.0.0.1:33387` 인스턴스를 사용한 기록이 있지만 현재 실행 중인지 확인 필요. `connectionTimeZone=Asia/Seoul`은 로컬 DB의 시간대 테이블 부재로 실패했던 기록이 있고, 로컬 테스트는 인코딩된 `+09:00` 오프셋을 사용했었다. 운영 설정을 검증 없이 바꾸지 않는다.
- 코드에 대한 H2/MySQL 테스트 결과는 특정 시점의 결과다. 이후 코드 변경 후 필요한 테스트를 재실행한다.
- 운영 데이터의 `flyway_schema_history`, 납부 중복·불일치, `PENDING` 계정 실태, 실제 Vault/ArgoCD 동작은 아직 확인되지 않았다. 접근이 없으면 검증 쿼리와 적용 체크리스트를 남기되, 이 때문에 독립적인 구현을 멈출 필요는 없다.

## 7. 구현 및 검증 원칙

- 오래된 지시서의 상세 파일/코드 구현 강제는 하지 않는다. 현재 코드에서 **미완성 기능만 찾아 직접 완성**, 이미 끝난 기능은 다시 만들지 않는다.
- 복잡한 위험 목록을 반복 작성하기보다 실행 가능한 코드 변경과 집중 테스트를 우선한다.
- 코드 변경 시 Flyway 신규 버전의 구버전/신버전 호환을 고려한다. 이미 적용된 마이그레이션을 재작성하지 않는다. 중복 컬럼 삭제는 안전한 별도 배포 단계로 분리할 수 있다.
- 빈 MySQL DB 전체 적용, 기존 버전에서 최신 업그레이드, JPA validate, H2 회귀, 권한 테스트, Excel·검색 API 테스트를 수행할 수 있는 범위에서 확인한다.
- **현재 백엔드만 수정**, 타 저장소는 참고만 한다. 중요한 frontend 계약 문제는 어떤 API가 어떻게 바뀌는지 보고한다.
- 종료 시 실제 구현/테스트와 미해결 위험을 구분하여 짧게 보고한다. 운영 배포가 가능하다고 단정하지 않는다.
- 향후 Tauri 기반 병원·주차·경조사 화면의 캡처 제한은 **이번 백엔드 범위에서 제외**.
