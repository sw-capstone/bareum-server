# 개발 서버 배포 구성과 운영

저장소 코드 기준의 개발 EC2 배포 구성, 설정 관리 범위, 상태 확인 방법과 실패 시 대응을 정리한다.

## 1. 배포 흐름

```text
develop에 병합
→ CI 검사
→ ARM64 앱 이미지 빌드·GHCR 게시
→ SSM으로 EC2에 배포 명령 전달
→ 해당 커밋의 준비 스크립트 다운로드·해시 확인
→ 설정 다운로드·백업·사전 검증
→ 설정 설치·Server/Nginx 재생성
→ 이미지와 내부 health 응답 확인
```

- [배포 워크플로](../../.github/workflows/deploy-dev.yml)는 `develop`의 push와 수동 실행을 지원한다. 수동 실행도 `develop`을 선택해야 한다. 작업 브랜치 푸시나 PR 생성만으로는 배포되지 않는다.
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
| `infra/env/.env.server.template` | `/opt/bareum/infra/env/.env.server.template` |
| `infra/scripts/reload-nginx-after-cert-renewal.sh` | `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx-after-cert-renewal.sh` |

`infra/` 전체를 복사하지 않는다. 반영 대상을 추가할 때는 준비·설치·백업·복구 목록과 테스트도 함께 수정한다.

### 배포가 사용하는 설정과 권한

| 구분 | 설정과 역할 |
| --- | --- |
| 배포 연결 | GitHub 변수 `EC2_INSTANCE_ID`, `AWS_ROLE_ARN`으로 대상 EC2와 OIDC 역할을 지정한다. Actions는 SSM 명령 전송·결과 조회 권한을 사용한다. |
| 이미지·파일 접근 | EC2는 GHCR 이미지 읽기 인증과 GitHub 원본 파일·AWS 접근, Docker·배포 파일 접근 권한을 사용한다. |
| 배포 환경변수 | `/opt/bareum/infra/env/.env.deploy`에 이미지와 경로를 관리한다. `SERVER_ENV_FILE=./env/.env.server`를 사용하며, 형식은 [예시 파일](../../infra/env/.env.deploy.example)을 참고한다. |
| 앱 환경변수 | Parameter Store의 `/bareum/dev/server/` 아래 DB·이메일 설정 11개와 템플릿의 일반 설정을 합쳐 `.env.server`를 생성한다. 필수 파라미터와 검증 기준은 아래 표를 따른다. |
| HTTPS 인증서 | `/etc/letsencrypt/live/api.bareum.site/`에 인증서·개인 키를 보관하고, `/var/www/certbot`을 도메인 인증 경로로 사용한다. |
| RDS 인증서 | `.env.deploy`의 `RDS_CA_FILE`이 EC2의 CA 파일 경로를 지정한다. |

배포 스크립트는 Linux의 Bash·Docker Compose·AWS CLI와 `curl`, `jq`, `flock`, GNU coreutils를 사용하며, 기존 Server·Nginx가 정상 동작하는 상태에서 실행한다.

앱 비밀값은 Git에 넣지 않는다. Server 배포 시 [환경변수 생성 스크립트](../../infra/scripts/prepare-server-env.sh)가 템플릿과 Parameter Store 값으로 `.env.server`를 다시 생성한다. 템플릿에 없는 수동 추가 값은 유지되지 않는다. `.env.ai`, 인증서·개인 키, RDS CA는 배포 파일로 덮어쓰지 않는다.

### Parameter Store와 템플릿의 관리 범위

아래 13개 파라미터를 `ap-northeast-2` 리전의 `/bareum/dev/server/` 아래에 등록한다. 예를 들어 SMTP 비밀번호의 전체 경로는 `/bareum/dev/server/SPRING_MAIL_PASSWORD`다. 실제 값은 저장소·PR·로그에 남기지 않는다.

| 파라미터 이름 | 유형 | 용도·검증 기준 |
| --- | --- | --- |
| `DB_URL` | `String` | RDS JDBC URL. `sslmode=verify-full&sslrootcert=/etc/ssl/certs/rds-ca.pem`을 포함한 TLS 연결을 사용한다. |
| `DB_USERNAME` | `String` | DB 접속 계정 |
| `DB_PASSWORD` | `SecureString` | DB 접속 비밀번호 |
| `EMAIL_VERIFICATION_HASH_SECRET` | `SecureString` | 독립적으로 생성한 최소 32바이트 난수의 Base64 문자열 |
| `EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY` | `SecureString` | 해시 키와 별도로 생성한 정확히 32바이트 난수의 Base64 문자열 |
| `SPRING_MAIL_PASSWORD` | `SecureString` | SMTP 비밀번호 또는 앱 비밀번호 |
| `SPRING_MAIL_HOST` | `String` | 담당자가 확정한 SMTP 호스트 |
| `SPRING_MAIL_USERNAME` | `String` | SMTP 인증 계정 |
| `EMAIL_VERIFICATION_MAIL_FROM` | `String` | 제공자에서 승인한 발신 주소 |
| `EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS` | `String` | 인증번호 확인 실패 횟수 한도. 양의 정수이며 Java Integer 범위. 개발 적용값 5 |
| `EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS` | `String` | 재설정 완료 증명 유효기간(초). 양의 정수이며 Java Long 범위. 개발 적용값 600 |
| `LOGIN_JWT_SIGNING_KEY` | `SecureString` | 최소 32바이트 독립 난수의 Base64 문자열. Google Client ID와 별개인 서버 서명 키 |
| `LOGIN_ALLOWED_ORIGINS` | `String` | 운영·develop의 정확한 HTTPS Origin을 쉼표로 구분. 경로·와일드카드·중복 없이 지정 |

EC2 역할이 위 13개 경로에 `ssm:GetParameters`를 수행하고 `SecureString`을 복호화할 수 있어야 한다. 고객 관리형 KMS 키를 사용한다면 해당 키의 `kms:Decrypt` 권한과 키 정책도 확인한다. Actions의 배포 역할과 EC2에서 파라미터를 조회하는 역할을 구분한다.

신규 두 확인 정책값은 Parameter Store에서 관리하며 템플릿에 중복 정의하지 않는다. 조회 목록은 총 13개이므로 요청당 최대 10개씩 나누고, 각 배치의 누락·중복·예상하지 않은 이름을 검사한다. 배치 하나라도 실패하면 기존 환경 파일을 유지한다.

develop 병합은 자동 배포를 시작하므로 신규 로그인 키(SecureString)·허용 Origin(String)의 등록과 EC2 조회·복호화 권한을 #37 병합 전에 확인해야 한다. 기존 확인 정책 두 값도 유지한다. 기존 SecureString의 복호화 권한도 유지한다. 문서·모의 테스트·CI 통과만으로 실제 AWS 등록이나 배포 적용을 완료 처리하지 않는다.

스크립트는 파라미터 누락·중복·공백만 있는 값·개행·널 문자, 비밀값 5개의 `SecureString` 여부, 해시·Outbox·JWT 키의 Base64 형식과 바이트 길이, 확인 정책 두 값의 String 유형·양의 정수·Java 숫자 범위, 허용 Origin의 String 유형·HTTPS 주소 형식을 검사한다. 검증 실패 시 기존 `.env.server`를 유지하고 배포를 중단한다. 성공 시에만 권한 `600`으로 교체하며 임시 파일은 정리한다. 생성되는 파일은 Compose의 `format: raw`로 전달하므로 값에 따옴표를 덧붙이지 않는다.

[`.env.server.template`](../../infra/env/.env.server.template)은 다음 일반 설정을 관리한다. SMTP 제공자·계정의 실제 값과 비밀키는 템플릿에 넣지 않는다.

| 설정 | 현재 값 |
| --- | --- |
| Outbox 활성화·키 ID | `EMAIL_VERIFICATION_OUTBOX_ENABLED=true`, `EMAIL_VERIFICATION_OUTBOX_KEY_ID=v1` |
| SMTP 포트·보안 | 587, SMTP AUTH·STARTTLS 활성화·STARTTLS 필수·서버 인증서 호스트 검증 |
| SMTP 연결·읽기·쓰기 타임아웃 | 각각 5000ms |
| SIGNUP·PASSWORD_RESET 인증번호 유효기간 | 각각 300초 |
| 최소 발송 간격·발송 제한 | 60초, 최근 1시간 5회, 최근 24시간 20회 |
| 로그인 Access·최대 세션 | 900초, 최초 로그인부터 28800초 |
| 로그인 쿠키 | Secure=true, SameSite=None |
| 이메일별 로그인 요청 제한 | 최근 집계 창 60초당 10회 |

기간·발송 제한은 개발 서버의 팀 미확정 임시 정책이다. 팀 확정 시 템플릿과 프론트 안내를 함께 검토한다. SMTP 제공자가 587/STARTTLS 구성을 지원하는지도 확인한다. Outbox는 미처리 작업이 있는 상태의 키 교체를 지원하지 않으므로, 대기 작업이 모두 종료되고 암호문이 제거된 뒤 암호화 키와 키 ID를 함께 교체한다.

env v3는 기존 서버·메일·확인 설정에 로그인 8개를 포함한 전체본이다. 이 중 로그인 키·허용 주소는 SSM, 나머지 6개는 템플릿에서 관리한다. #63의 환경파일 통합 전까지 이 방식을 유지하며 동일 변수를 양쪽에 정의하지 않는다.

### 환경변수 생성·바인딩 테스트

CI는 아래 환경변수 생성 테스트를 실행한다. 실제 생성 스크립트와 템플릿을 임시 디렉터리에서 사용하고 AWS CLI 응답만 모의하므로 실제 AWS 자격증명은 필요하지 않다.

```bash
bash infra/tests/prepare-server-env-test.sh
./gradlew --no-daemon test --tests com.bareum.server.domain.auth.config.SmtpConfigurationTests --tests com.bareum.server.domain.auth.config.EmailVerificationPropertiesTests
```

생성 테스트는 정상 생성, 조회 대상, 특수문자 보존, 중복 제거, 필수값·키·저장 유형 검증, 실패 시 기존 파일 보존과 권한·임시 파일 정리를 검사한다. SMTP 테스트는 배포 템플릿의 실제 환경변수 이름으로 연결·인증·TLS·타임아웃 속성 바인딩을 확인한다. 실제 메일 제공자 연결과 수신 검증은 별도로 수행한다.

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
