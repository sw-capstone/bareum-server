# 개발 서버 배포 구성과 운영

저장소 코드 기준의 개발 EC2 배포 구성, 설정 관리 범위, 상태 확인 방법과 실패 시 대응을 정리한다.

## 1. 배포 흐름

```text
develop에 병합
→ SSM으로 마지막 서버 배포 성공 SHA 조회
→ 마지막 배포 이후 변경에 필요한 CI 검사
→ ARM64 앱 이미지 빌드·GHCR 게시
→ SSM으로 EC2에 배포 명령 전달
→ 해당 커밋의 준비 스크립트 다운로드·해시 확인
→ 설정 다운로드·백업·사전 검증
→ 설정 설치·Server/Nginx 재생성
→ 이미지와 내부 health 응답 확인
→ 배포 성공 SHA 갱신
```

- [배포 워크플로](../../.github/workflows/deploy-dev.yml)는 `develop`의 push와 수동 실행을 지원한다. 수동 실행도 `develop`을 선택해야 한다. 작업 브랜치 푸시나 PR 생성만으로는 배포되지 않는다.
- 마지막 서버 배포 성공 SHA부터 현재 SHA까지 등록된 문서 경로만 변경됐다면 공통 검사만 수행하고 이미지 빌드, GHCR 게시와 서버 배포를 생략한다. 직전 push가 문서 전용이어도, 그전에 실패하거나 대기 중 취소된 서버 변경이 남아 있으면 필요한 CI 검사와 배포를 다시 수행한다. 문서 경로와 검사 선택 기준은 [개발 가이드](./development-guide.md#12-github-actions와-리뷰)를 따른다.
- 통합 CI의 선택된 검사가 모두 성공하고 `deploy_required=true`인 경우에만 이미지를 게시한다. 이미지 게시가 생략되거나 실패하면 서버 배포도 실행하지 않는다.
- 수동 실행은 문서 전용 여부와 관계없이 전체 검증 후 이미지 게시와 재배포를 수행한다. 변경 범위를 판별하지 못한 경우도 전체 검증 후 배포한다.
- 배포 기준 조회는 기존 Actions 배포 잠금과 EC2 배포 잠금이 해제될 때까지 기다린 뒤 성공 기록을 읽는다. 기록이 없거나 잘못됐거나 Git에서 해당 커밋을 찾지 못하면 전체 검사로 대체한다. SSM 조회 실패나 미완료 복구 상태는 배포를 중단하며, 성공한 조회에서 기록이 없는 경우와 구분한다.
- 앱 이미지는 게시 결과의 digest로 고정하고, 설정은 이미지를 빌드한 커밋 SHA로 받는다.
- [SSM 실행 스크립트](../../.github/scripts/deploy-via-ssm.sh)는 EC2에 준비 스크립트를 내려받는다. EC2의 Git 저장소나 준비 스크립트 사전 설치는 필요하지 않다.
- 배포 중 Server와 Nginx를 재생성하므로 잠깐 요청이 실패할 수 있다. 무중단 배포 구성은 아니다.

## 2. 자동 반영 범위와 운영 설정

### 반영하는 파일

| 저장소 파일 | EC2 설치 위치 |
| --- | --- |
| `infra/docker-compose.yml` | `/opt/bareum/infra/docker-compose.yml` |
| `infra/nginx/default.conf` | `/opt/bareum/infra/nginx/default.conf` |
| `infra/scripts/deploy.sh` | `/opt/bareum/infra/scripts/deploy.sh` |
| `infra/scripts/prepare-server-env.sh` | `/opt/bareum/infra/scripts/prepare-server-env.sh` |
| `infra/scripts/prepare-ai-env.sh` | `/opt/bareum/infra/scripts/prepare-ai-env.sh` |
| `infra/scripts/reload-nginx-after-cert-renewal.sh` | `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh` |

`infra/` 전체를 복사하지 않는다. 반영 대상을 추가할 때는 준비·설치·백업·복구 목록과 테스트도 함께 수정한다.

### 배포가 사용하는 설정과 권한

| 구분 | 설정과 역할 |
| --- | --- |
| 배포 연결 | GitHub 변수 `EC2_INSTANCE_ID`, `AWS_ROLE_ARN`으로 대상 EC2와 OIDC 역할을 지정한다. Actions는 SSM 명령 전송·결과 조회 권한을 사용한다. |
| 이미지·파일 접근 | EC2는 GHCR 이미지 읽기 인증과 GitHub 원본 파일·AWS 접근, Docker·배포 파일 접근 권한을 사용한다. |
| 배포 환경변수 | `/opt/bareum/infra/env/.env.deploy`에 이미지와 경로를 관리한다. `SERVER_ENV_FILE=./env/.env.server`를 사용하며, 형식은 [예시 파일](../../infra/env/.env.deploy.example)을 참고한다. |
| 서버 환경변수 | Parameter Store의 `/bareum/dev/server/env` 하나에 일반 설정과 비밀값을 포함한 env 전체를 `SecureString`으로 저장한다. 조회와 검증 후 `.env.server`를 교체한다. |
| AI 환경변수 | Parameter Store의 `/bareum/dev/ai/env`에 완성된 AI env 파일 전체를 `SecureString`으로 저장한다. 배포 시 조회·검증한 뒤 `.env.ai`를 생성한다. |
| HTTPS 인증서 | `/etc/letsencrypt/live/api.bareum.site/`에 인증서·개인 키를 보관하고, `/var/www/certbot`을 도메인 인증 경로로 사용한다. |
| RDS 인증서 | `.env.deploy`의 `RDS_CA_FILE`이 EC2의 CA 파일 경로를 지정한다. |

배포 스크립트는 Linux의 Bash·Docker Compose·AWS CLI와 `curl`, `jq`, `flock`, GNU coreutils를 사용하며, 기존 Server·Nginx가 정상 동작하는 상태에서 실행한다.

앱 비밀값은 Git에 넣지 않는다. Server 배포 시 [서버 환경변수 생성 스크립트](../../infra/scripts/prepare-server-env.sh)가 단일 SSM env를 검증한 뒤 원문 그대로 `.env.server`에 저장한다. 파일을 실행하거나 변수를 치환하지 않는다. EC2에서 직접 추가한 값은 다음 배포에 덮어쓰므로 실제 설정은 SSM 원본에서 변경한다. `.env.deploy`는 EC2에 미리 준비하는 별도 파일이며 Parameter Store에서 자동 생성하지 않는다. 배포 스크립트는 대상 서비스의 이미지 주소만 갱신한다. 인증서, 개인 키와 RDS CA는 배포 파일로 덮어쓰지 않는다.

### AI 환경파일 자동 생성

[AI 환경변수 생성 스크립트](../../infra/scripts/prepare-ai-env.sh)는 `ap-northeast-2`의 `/bareum/dev/ai/env` 하나를 `ssm:GetParameter`로 조회·복호화한다. 이 파라미터의 등록과 EC2 역할의 조회·복호화 권한은 최초 배포 전에 운영자가 준비해야 한다. 고객 관리형 KMS 키를 사용하면 해당 키의 복호화 권한도 필요하다. 파라미터에는 키 이름별 JSON이 아닌 `KEY=value` 형식의 env 파일 전체를 넣는다. 서버 설정과 AI 설정은 별도 파일로 관리한다.

아래 세 항목은 기본값이 없으며 실제 배포 값이 필요하다. 토큰은 서버가 AI 요청에 사용하는 인증 토큰과 일치해야 한다.

| 필수 항목 | 검증 기준 |
| --- | --- |
| `BAREUM_AI_INTERNAL_API_TOKEN` | 공백만 있는 값이나 `replace-me`를 허용하지 않음 |
| `BAREUM_AI_S3_ALLOWED_HOST` | 프로토콜·경로가 없는 소문자 DNS 호스트 이름 |
| `BAREUM_AI_S3_ALLOWED_KEY_PREFIX` | 비어 있지 않고 `/`로 시작하지 않으며 `/`로 끝나는 원본 키 접두사 |

`bareum-ai/.env.example`에 있는 파일 크기·페이지 수·동시 실행 수·타임아웃 설정도 같은 파일에 기록할 수 있다. 생략 시 AI 코드의 기본값이 적용된다. 명시한 값은 양수여야 하며 최대 파일 크기는 20,000,000바이트, 최대 페이지 수는 60이다. Compose는 `format: raw`를 사용하므로 값에 따옴표를 덧붙이거나 변수 치환을 기대하지 않는다. 중복 키, 잘못된 env 구문, CR·널 문자와 필수값·숫자 형식 오류는 배포를 차단한다. 값은 출력하지 않는다.

Server 릴리스 준비 시 AI env를 권한이 제한된 릴리스 폴더에 먼저 생성하고, 후보 Compose 검증에는 이 파일을 사용한다. 기존 EC2에 `.env.ai`가 없어도 이 순서로 검증할 수 있다. 검증만 하는 실행은 실제 `.env.ai`를 변경하지 않는다. 적용할 때만 `/opt/bareum/infra/env/.env.ai`를 권한 `600`으로 교체하며 기존 파일 또는 파일이 없던 상태를 복구 자료에 기록한다. `AI_ENV_FILE`은 생략하거나 `./env/.env.ai`로 지정한다. 다른 출력 경로는 지원하지 않는다.

AI 단독 배포도 동일하게 AI env를 먼저 준비하고 검증한 뒤 컨테이너를 재생성한다. 실패하면 기존 환경파일을 복원하며, 원래 파일이 없었다면 실행 중이던 AI 컨테이너의 환경변수를 복구에 사용하고 파일이 없던 상태로 돌린다. Server 배포는 AI 환경파일을 준비하지만 실행 중인 AI 컨테이너는 재생성하지 않는다. AI 설정 변경의 실제 반영은 AI 배포 후 확인한다. 조회·검증 실패 시에는 기존 환경파일과 실행 중인 컨테이너를 변경하지 않는다.

### AI 환경파일·배포 회귀 테스트

```bash
bash infra/tests/prepare-ai-env-test.sh
bash infra/tests/deploy-test.sh
bash infra/tests/prepare-infra-release-test.sh
TEST_REAL_VALIDATION=1 bash infra/tests/prepare-infra-release-test.sh
bash infra/tests/apply-infra-release-integration-test.sh
```

CI는 AI env가 없는 초기 상태, 필수값·저장 유형·구문 오류, 조회 실패 시 기존 파일 보존, 값과 파일 권한, 배포 실패 시 복구를 검사한다. 실제 Compose·컨테이너 검사도 처음에는 `.env.ai`가 없는 상태에서 릴리스를 적용하고, AI 단독 배포의 환경변수 주입과 파일이 없던 상태의 복구를 확인한다. AWS 응답은 모의하며 컨테이너는 Nginx 테스트 이미지를 사용한다. 실제 AWS 권한·AI 이미지의 파싱 동작·S3 접근은 별도 배포 확인 대상이다.

### 서버 환경파일 자동 생성과 검증

`ap-northeast-2`의 `/bareum/dev/server/env`에 `KEY=value` 형식의 서버 env 전체를 하나의 `SecureString`으로 등록한다. JSON이나 키별 파라미터가 아니다. 서버 포트, SMTP 보안 옵션, 타임아웃, 발송 제한과 로그인 정책 등 일반 설정도 같은 파일에 넣는다. `env/local`의 로컬용 파일과는 별도로 관리한다.

EC2 역할에는 이 경로에 대한 `ssm:GetParameter` 권한이 필요하다. 고객 관리형 KMS 키를 사용한다면 해당 키의 `kms:Decrypt` 권한과 키 정책도 확인한다. GitHub Actions의 배포 역할은 EC2에서 실제 파라미터를 읽는 역할과 구분한다.

기존 중요 키의 검증은 유지한다. 아래 항목은 모두 존재해야 하며 공백만 있는 값을 허용하지 않는다. 파일 전체가 `SecureString`이므로 개별 키의 SSM 유형을 따로 검사하지 않는다.

| 필수 키 | 용도와 검증 기준 |
| --- | --- |
| `SERVER_PORT` | Nginx upstream과 헬스체크에 맞춰 `8080` 사용 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | DB 연결 정보. RDS URL은 기존 `sslmode=verify-full&sslrootcert=/etc/ssl/certs/rds-ca.pem` TLS 설정을 유지 |
| `EMAIL_VERIFICATION_HASH_SECRET` | 최소 32바이트 난수의 Base64 문자열 |
| `EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY` | 정확히 32바이트 난수의 Base64 문자열 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `EMAIL_VERIFICATION_MAIL_FROM` | SMTP 연결, 인증과 발신 정보 |
| `EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS` | 양의 정수이며 Java Integer 범위 |
| `EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS` | 양의 정수이며 Java Long 범위 |
| `LOGIN_JWT_SIGNING_KEY` | 최소 32바이트 난수의 Base64 문자열 |
| `LOGIN_ALLOWED_ORIGINS` | 정확한 HTTPS Origin을 쉼표로 구분. 경로, 와일드카드, 중복과 잘못된 포트를 거부 |

목록에 없는 새 키도 허용하고 값은 그대로 전달한다. 일반 환경변수 추가나 값 변경에는 조회 목록이나 검증 코드 수정이 필요하지 않다. 새 항목을 배포 단계의 필수 검증 대상으로 삼으려는 경우에만 검증 규칙과 테스트를 함께 바꾼다. 애플리케이션의 기존 설정 검사와 오류 처리 시점은 변경하지 않는다.

빈 줄과 주석은 허용하지만 중복 키, 잘못된 `KEY=value` 구문, CR과 NUL 문자는 거부한다. Compose의 `format: raw`로 전달하므로 값을 감싸는 따옴표를 덧붙이거나 변수 치환을 기대하지 않는다. 조회나 검증 실패 시 기존 `.env.server`를 보존하며, 성공했을 때만 권한 `600`인 임시 파일을 같은 파일시스템에서 교체한다. 값과 AWS 오류 원문은 출력하지 않고 임시 파일은 정리한다. 출력 경로를 명시해 후보 파일만 생성할 수도 있다.

### 환경변수 생성·바인딩 테스트

CI는 아래 환경변수 생성 테스트를 실행한다. 실제 생성 스크립트를 임시 디렉터리에서 사용하고 AWS CLI 응답은 가짜 env로 모의하므로 실제 AWS 자격증명은 필요하지 않다.

```bash
bash infra/tests/prepare-server-env-test.sh
./gradlew --no-daemon test --tests com.bareum.server.domain.auth.config.SmtpConfigurationTests --tests com.bareum.server.domain.auth.config.EmailVerificationPropertiesTests
```

생성 테스트는 단일 조회, 원문과 특수문자 보존, 새 키 허용, 중복 키 거부, 필수값과 값 형식 검사, 실패 시 기존 파일 보존, 권한과 임시 파일 정리를 검사한다. SMTP 테스트는 가짜 설정을 실제 환경변수 이름으로 공급해 연결, 인증, TLS와 타임아웃 속성 바인딩을 확인한다. 실제 메일 제공자 연결과 수신 검증은 별도로 수행한다.

## 3. 자동 배포 결과 확인

GitHub Actions의 **Deploy development → Deploy through SSM**에서 SSM command ID와 다음 로그를 확인한다.

```text
Release prepared: /opt/bareum/infra/.deploy-state/releases/<커밋 SHA>-<식별자>
Deployment completed: server (...)
Deployment status: Success
```

마지막으로 외부에서 HTTPS 응답과 HTTP 리다이렉트를 확인한다. 스크립트 내부 health 검증만으로 외부 DNS·HTTPS 경로까지 검증되지는 않는다.

```bash
curl -fsS https://api.bareum.site/health
curl -sS -o /dev/null -D - http://api.bareum.site/health
```

예상 결과는 각각 `{"status":"ok"}`, HTTP `308` 및 `Location: https://api.bareum.site/health`다.

`/health` 정상 응답은 SMTP 설정·메일 수신 성공을 보장하지 않는다. CI 이미지 기동 검사도 Outbox를 비활성화하므로, 이메일 인증 기능 배포 후에는 테스트 수신 주소로 API를 호출해 `202` 접수와 `requestId`를 확인하고 발송 상태가 `SENT`로 바뀌는지 조회한다. 실제 수신함 도착과 같은 요청의 DB 발송 이력·만료 시각을 대조한다. 인증번호 원문이나 자격증명을 로그·공유 자료에 남기지 않는다.

EC2에서는 아래 명령으로 컨테이너·Nginx·갱신 훅을 확인한다.

```bash
sudo docker compose --project-name bareum -f /opt/bareum/infra/docker-compose.yml --env-file /opt/bareum/infra/env/.env.deploy ps
sudo docker compose --project-name bareum -f /opt/bareum/infra/docker-compose.yml --env-file /opt/bareum/infra/env/.env.deploy exec -T nginx nginx -t
sudo test -x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh && echo '갱신 훅 설치 확인'
```

로그에 표시된 릴리스 경로를 넣어 적용 버전과 상태를 확인한다. 최신 폴더라는 이유만으로 성공한 배포라고 판단하지 않는다.

```bash
RELEASE_DIR='/opt/bareum/infra/.deploy-state/releases/실제-릴리스-이름'
sudo cat "$RELEASE_DIR/commit-sha" "$RELEASE_DIR/server-image" "$RELEASE_DIR/status"
```

## 4. 잠금·상태·백업

- `/opt/bareum/.actions-deploy.lock`: Server와 AI의 Actions 배포를 직렬화한다. `--apply`는 최대 600초 기다린 뒤 준비부터 적용·복구 종료까지 잠금을 유지한다.
- `/opt/bareum/infra/.deploy-state/lock`: 설정 준비 또는 적용 중 다른 실행을 막는다. 잠금 파일의 존재만으로 실행 중이라고 판단하지 않는다.
- `.deploy-state/releases/<SHA>-<식별자>/`: 받은 파일, 체크섬, 기존 설정·이미지 백업, 검증 결과를 보관한다.
- `.deploy-state/pending/`: 진행 중이거나 복구가 필요한 배포의 복구 자료다. 다음 배포를 차단하므로 임의로 삭제하지 않는다.
- `.deploy-state/server-deployed-sha`: 마지막으로 검증된 서버 릴리스의 배포 성공 SHA다. Server/Nginx 이미지와 health 확인 후에만 권한 `600`으로 원자적으로 갱신한다. 실패나 자동 복구 시에는 이전 기록을 유지한다. 커밋을 확인할 수 없는 이미지 단독 서버 배포가 성공하면 기록을 제거해 다음 CI에서 전체 검사하게 하며, AI 배포는 이 기록을 바꾸지 않는다.

| 릴리스 상태 | 의미 |
| --- | --- |
| `validated` | 사전 검증 완료. 아직 설치되지 않았으며, 이후 초기 검사에서 중단됐을 수도 있음 |
| `applying` | 설정 적용 시작. 프로세스가 중단되면 이 상태가 남을 수 있음 |
| `applied` | 배포 검증 완료 |
| `failed` | 적용 단계가 실패함. 로그에서 기존 상태 유지 또는 자동 복구 성공 여부를 확인 |
| `recovery-required` | 자동 복구 실패. 복구 자료를 보존하고 수동 조치 필요 |

백업에는 DB 접속 정보를 포함한 환경변수가 들어갈 수 있다. 내용을 PR·채팅에 붙이지 않는다. 릴리스 백업의 자동 정리 기능은 없으므로 보존 기간과 삭제 시점은 별도로 관리하며, 복구에 필요한 릴리스와 기존 이미지는 정리하지 않는다.

## 5. 실패 시 대응

| 상황 | 대응 |
| --- | --- |
| 다운로드·해시·사전 검증 실패 | 설치 전 중단이다. 커밋·파일·인증서·마운트 경로와 로그를 확인하고 원인을 수정한 뒤 재실행 |
| `Rollback completed` | 배포는 실패로 보고되지만 복구는 완료됐다. 컨테이너·외부 HTTPS를 확인한 뒤 실패 원인을 수정 |
| `Rollback failed` 또는 `pending` 잔존 | 실행 중인 배포와 복구 자료를 확인하고, 자동 복구 실패 시 아래 기준으로 대응 |
| SSM 조회 대기 시간 초과 | 로그의 command ID로 Systems Manager Run Command에서 실제 실행 상태 확인. 아직 실행 중이면 새 배포를 시작하지 않음 |
| 다른 배포 잠금 또는 준비 이후 상태 변경 | 진행 중인 배포를 확인. 종료 후 새 릴리스를 준비하며 잠금·상태 검사를 우회하지 않음 |

### 자동 복구가 실패한 경우

자동 복구가 실패하면 백업을 `infra/.deploy-state/pending/`에 보존하고 다음 배포를 차단한다. 담당자는 SSM 명령 종료 여부와 실패 원인을 확인한 뒤, 백업을 기준으로 수동 복구한다. 정상 동작을 확인하기 전에는 `pending`이나 잠금 파일을 임의로 삭제하지 않는다.

현재는 별도 `recover` 명령이 없다. 추후 장애 원인을 해결한 뒤 명령 하나로 백업 복원·컨테이너 재생성·정상 동작 확인을 재시도할 수 있도록 개선할 예정이다.

DB 마이그레이션은 이미지·설정 복구로 되돌아가지 않는다. DB 변경이 포함된 배포는 이전 앱과 스키마가 호환되는지 확인해야 한다.

## 6. 인증서 갱신

배포는 Certbot의 갱신 후 훅을 설치한다. 인증서 최초 발급과 Certbot 갱신 예약 자체를 설치하지는 않는다. 갱신 후 훅은 Nginx 설정 검사에 성공하면 reload한다.

EC2에서 갱신 구성을 변경했을 때 확인한다.

```bash
systemctl list-timers --all | grep -i certbot
sudo /snap/bin/certbot renew --dry-run --run-deploy-hooks
```
