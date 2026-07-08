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

## 빌드

```shell
./gradlew clean build
docker build -t skku-alumni-backend:local .
```
