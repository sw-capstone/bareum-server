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
| 앱 환경변수 | Parameter Store의 `/bareum/dev/server/` 아래 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 읽어 `.env.server`를 생성한다. EC2에 조회·복호화 권한이 필요하며, `DB_PASSWORD`는 `SecureString`이다. |
| HTTPS 인증서 | `/etc/letsencrypt/live/api.bareum.site/`에 인증서·개인 키를 보관하고, `/var/www/certbot`을 도메인 인증 경로로 사용한다. |
| RDS 인증서 | `.env.deploy`의 `RDS_CA_FILE`이 EC2의 CA 파일 경로를 지정한다. |

배포 스크립트는 Linux의 Bash·Docker Compose·AWS CLI와 `curl`, `jq`, `flock`, GNU coreutils를 사용하며, 기존 Server·Nginx가 정상 동작하는 상태에서 실행한다.

Auth·Term 설정을 함께 적용하려면 [Auth·Term 배포 설정 준비](auth-term-deployment.md)의 파라미터·권한을 준비하고 `.env.deploy`에서 `AUTH_ENV_ENABLED=true`를 지정한다. 누락·false는 기존 DB 설정만 조회한다.

앱 비밀값은 Git에 넣지 않는다. Server 배포 시 [환경변수 생성 스크립트](../../infra/scripts/prepare-server-env.sh)가 템플릿과 Parameter Store 값으로 `.env.server`를 다시 생성한다. 템플릿에 없는 수동 추가 값은 유지되지 않는다. `.env.ai`, 인증서·개인 키, RDS CA는 배포 파일로 덮어쓰지 않는다.

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
