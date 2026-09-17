# 새 SCG 클러스터 배포

기존 `cd-dev` / `cd-prod`는 유지합니다. `platform.yaml`은 별도의 GHCR
이미지와 `kubernetes` 저장소를 사용하며 main push마다 자동 배포를 시도합니다.

## 최초 등록

1. 이 변경을 main에 머지합니다. 기존 main 배포도 기존 설정대로 실행됩니다.
2. DB 계정은 나중에 받아도 됩니다. 이미지 빌드에는 DB 접속 정보가 필요하지 않습니다.
3. main에서 `Platform deployment` 워크플로를 수동 실행합니다.
4. 생성된 `platform-lock-be` 아티팩트를 내려받아 인프라 담당자에게 전달합니다.
5. GHCR 패키지 `alumni-platform-be`를 클러스터가 인증 없이 가져올 수 있도록 public으로 설정합니다.
6. 인프라 레포의 `onboarding/alumni/README.md`에 따라 최초 배포 정보를 등록합니다.

수동 실행은 이미지만 발행하고 배포를 요청하지 않습니다.
앱 이미지에는 비밀값이나 불필요한 파일이 포함되지 않도록 리뷰해야 합니다.

## 이후 자동 배포

자동 배포에 필요한 Actions Secrets:

- Actions Secrets: `KUBERNETES_APP_ID`, `KUBERNETES_APP_PRIVATE_KEY`

main push 시 공통 워크플로가 새 이미지와 배포 lock을 갱신합니다.
최초 `applications/alumni/instances/production.yaml` 등록이나 인증정보 준비가
끝나기 전에는 자동 배포가 실패할 수 있습니다. 준비 후 실패한 Actions 실행을 재실행합니다.
develop, testing, PR preview는 이번 변경에서 새 플랫폼 배포 대상으로 등록하지 않습니다.
기존 CD는 변경하지 않습니다. 백엔드 테스트 통과 조건은 유지합니다.

이미지 빌드나 배포 lock 갱신 실패는 GitHub Actions에서 재실행해야 합니다.
Argo CD Sync는 Git에 이미 기록된 상태만 적용하며, 이미지를 빌드하거나 lock을
갱신하지 않습니다. lock 반영 후 클러스터 적용 실패는 원인을 해결하고 Sync로 재시도합니다.

## 런타임 설정

DB 비밀번호 등은 이미지에 넣지 않고 Vault로 주입합니다.
실제 JWT 환경변수는 `AUTH_JWT_SECRET`이며 Redis 인증에는
Spring Boot 기본 환경변수 `SPRING_DATA_REDIS_PASSWORD`를 사용합니다.
DB 계정이 준비되지 않으면 인프라 설정이 컨테이너 시작을 차단합니다.

Dockerfile은 Java 25 빌더에서 bootJar를 생성한 뒤 숫자 UID 1001로 실행합니다.
기존 CD에서 실행하는 Gradle 테스트는 유지합니다. Docker 빌드 자체는 테스트를
대체하지 않습니다. 새 클러스터는 기존 production 데이터를 공유하지 않습니다.
