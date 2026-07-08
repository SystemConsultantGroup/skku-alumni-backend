# SKKU Alumni Backend

성균관대학교 동문 서비스의 Spring Boot 백엔드입니다.

## 기술 스택

- Java 25 LTS
- Spring Boot 4.1.0
- Gradle 9.6.1
- Spring MVC, Spring Data JPA, Validation, Actuator
- MySQL 8.4 LTS, Flyway
- MinIO Java SDK 9.0.3

Spring Boot가 관리하는 라이브러리는 `build.gradle`에서 버전을 별도로 고정하지 않습니다.

## 실행 환경

| Profile | API | Database | Database name |
| --- | --- | --- | --- |
| `local` | `http://localhost:8000` | `localhost:3306` | `skku_alumni` |
| `dev` | `https://test.api.alumni.scg.skku.ac.kr` | `mysql-scg.scg.skku.ac.kr` | `dev_skku_alumni` |
| `prod` | `https://api.alumni.scg.skku.ac.kr` | `mysql-scg.scg.skku.ac.kr` | `prod_skku_alumni` |

웹 클라이언트 도메인은 다음과 같습니다.

- 개발: `test.alumni.scg.skku.ac.kr`, `test.admin.alumni.scg.skku.ac.kr`
- 운영: `alumni.scg.skku.ac.kr`, `admin.alumni.scg.skku.ac.kr`

## 로컬 실행

Java 17 이상과 Docker가 필요합니다. Java 25 Corretto toolchain이 없으면 Gradle이 자동으로 내려받습니다.

```shell
cp .env.example .env
docker compose up -d
./gradlew bootRun
```

- API: `http://localhost:8000`
- Health check: `http://localhost:8000/actuator/health`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

로컬 인프라를 종료하려면 다음 명령을 사용합니다.

```shell
docker compose down
```

데이터까지 삭제하려면 `docker compose down -v`를 사용합니다.

## 환경 변수

`dev`, `prod` 실행 시 아래 값은 런타임 환경에서 주입해야 합니다. 비밀값이 포함된 설정 파일은 저장소에 커밋하지 않습니다.

| Variable | Description |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local`, `dev`, `prod` 중 하나 |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL 접속 정보 |
| `DB_USERNAME`, `DB_PASSWORD` | MySQL 인증 정보 |
| `MINIO_ENDPOINT` | MinIO API endpoint |
| `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | MinIO 인증 정보 |
| `MINIO_BUCKET` | 사용할 bucket 이름 |
| `SERVER_PORT` | 애플리케이션 포트, 기본값 `8000` |

## 데이터베이스 마이그레이션

스키마 변경은 `src/main/resources/db/migration` 아래 Flyway migration으로 관리합니다. Hibernate는 모든 환경에서 `ddl-auto=validate`만 수행합니다.

```text
V1__create_user_table.sql
V2__add_user_status.sql
```

현재 초기 구현은 다음 마이그레이션으로 시작합니다.

```text
V1__create_core_schema.sql
V2__seed_core_data.sql
V3__add_operational_features.sql
```

## 초기 API와 운영 API

첫 구현 범위는 임원 주소록, 커뮤니티 피드, 사용자 운영 기능, 관리자 운영 기능입니다.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/v1/members` | 현행 임기 회비 납부 임원만 조회합니다. `keyword`, `majorId`, `industryId`, `companyName`, `hobbyId`, `cursor`, `size`를 지원합니다. |
| `GET` | `/api/v1/community/posts` | 공개 상태의 커뮤니티 게시글 피드를 커서 기반으로 조회합니다. `cursor`, `size`를 지원합니다. |
| `GET` | `/api/v1/reference-data` | 학과, 산업, 취미, 임원 임기/직책 기준 데이터를 조회합니다. |
| `GET/PATCH` | `/api/v1/me` | 샘플 로그인 사용자 기준 내정보 조회·수정과 ASIS 변경 로그 생성을 지원합니다. |
| `POST` | `/api/v1/member-applications` | 임원 등록 신청을 접수합니다. |
| `GET` | `/api/v1/payments/me` | 샘플 로그인 사용자 기준 회비 상태를 조회합니다. |
| `GET` | `/api/v1/clubs` | 취미동호회와 발전연구회를 조회합니다. |
| `GET` | `/api/v1/notices` | 공지/뉴스 글을 조회합니다. |
| `GET` | `/api/v1/business-posts` | 비즈니스 글을 조회합니다. |
| `POST` | `/api/v1/reports` | 게시글 신고를 접수합니다. |
| `GET/POST/DELETE` | `/api/v1/blocked-users` | 차단 사용자 목록을 관리합니다. |
| `GET` | `/api/v1/admin/dashboard` | 관리자 대시보드 통계를 조회합니다. |
| `GET` | `/api/v1/admin/members` | 관리자 회원 목록을 조회합니다. |
| `GET/PATCH` | `/api/v1/admin/applications` | 신규 신청 목록과 승인/반려 처리를 지원합니다. |
| `GET/PATCH` | `/api/v1/admin/payments` | 회비 목록과 납부/미납 처리를 지원합니다. |
| `GET/PATCH` | `/api/v1/admin/asis-sync` | ASIS 최신화 대상 조회와 최신 표시를 지원합니다. |
| `GET/PATCH` | `/api/v1/admin/reports` | 신고 목록과 처리/반려를 지원합니다. |
| `GET` | `/api/v1/admin/managers` | 관리자 계정 목록을 조회합니다. |
| `GET` | `/api/v1/admin/audit-logs` | 관리자 감사 로그를 조회합니다. |

도메인 enum은 DB에서 `varchar`로 저장하고, Java 엔티티에서 `@Enumerated(EnumType.STRING)`으로 매핑합니다.

## 빌드

```shell
./gradlew clean build
docker build -t skku-alumni-backend:local .
```
