# Auth·Term 배포 설정 준비

Auth·Term의 서버 설정을 기존 개발 EC2 배포 경로에 연결한다. 코드·설정 준비와 운영 정책 승인·실제 환경 적용은 구분한다. [배포 구성](deployment-guide.md)과 [Auth·Term 연동 안내](../integration/auth-term.md)를 함께 따른다.

## 적용 방식

- 기존 `/bareum/dev/server/` Parameter Store 경로를 사용한다. EC2의 조회·복호화 권한과 KMS 권한에 아래 파라미터를 포함한다.
- `.env.deploy`의 `AUTH_ENV_ENABLED`가 없거나 `false`이면 기존 DB 세 항목만 조회한다. Auth 기능이 포함된 이미지를 배포하기 전에 필요한 설정을 준비하고 `true`로 변경한다.
- 환경 파일 생성 스크립트는 `AUTH_ENV_ENABLED`를 파일에서 읽으며 셸에서 `source`하지 않는다. 단독 생성 명령의 `--with-auth`도 같은 검증을 사용한다. 생성만으로 실행 중인 컨테이너에 설정이 반영되지 않는다.
- `true`이면 필수 Auth·Term 설정을 함께 조회한다. 필요한 값이 없거나 잘못되면 실패하며 기존 `.env.server`를 교체하지 않는다. `false` 모드는 새 인증 코드의 필수 HMAC 키를 공급하지 않으므로 인증 이미지의 기동 준비를 대신하지 않는다.
- 값을 출력하지 않고, 권한을 제한한 임시 파일에서 검증한 뒤 `.env.server`를 권한 600으로 교체한다. AWS·JSON 오류의 원문은 배포 로그에 출력하지 않는다.
- 허용한 파라미터 이름만 최대 10개씩 조회한다. 경로 아래의 모든 값을 읽지 않으며, 템플릿에 중복된 관리 대상 키는 제거한다. `raw` 환경 파일이므로 값의 `$`, 따옴표, `=`를 셸에서 실행하거나 치환하지 않는다. 개행·NUL·빈 값은 거부한다.

## 파라미터 목록

이름 앞에 `/bareum/dev/server/`를 붙인다. 아래 필수 설정은 활성화 전에 담당자가 승인된 값으로 준비한다. 수치·발신 계정·약관 버전을 이 문서에서 정하지 않는다.

| 이름 | 형식·필수 여부 | 기준 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME` | String 또는 SecureString · 항상 필수 | 기존 RDS 연결 기준 |
| `DB_PASSWORD` | SecureString · 항상 필수 | 기존 DB 자격증명 |
| `EMAIL_VERIFICATION_HASH_SECRET` | SecureString · Auth 필수 | Base64로 인코딩한 최소 32바이트 HMAC 키 |
| `EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY` | SecureString · Auth 필수 | Base64로 인코딩한 정확히 32바이트 암호화 키 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | String · Auth 필수 | 승인된 SMTP 호스트·1~65535 포트 |
| `SPRING_MAIL_USERNAME` | String 또는 SecureString · Auth 필수 | 승인된 발신 인증 계정 |
| `SPRING_MAIL_PASSWORD` | SecureString · Auth 필수 | SMTP 자격증명 |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | String · Auth 필수 | 인증 SMTP 경로는 `true` |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE` | String · Auth 필수 | implicit TLS이면 `true`, STARTTLS이면 `false` |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED` | String · Auth 필수 | STARTTLS이면 모두 `true`, implicit TLS이면 모두 `false` |
| `EMAIL_VERIFICATION_MAIL_FROM` | String · Auth 필수 | 승인된 발신 이메일 주소 |
| `LOGIN_ALLOWED_ORIGINS` | String · Auth 필수 | 쉼표로 구분한 정확한 HTTPS Origin. 와일드카드·경로·HTTP를 허용하지 않음 |
| `EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS` | String · Auth 필수 | 승인된 양의 발송 간격(초) |
| `EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR`, `EMAIL_VERIFICATION_MAX_SENDS_PER_DAY` | String · Auth 필수 | 승인된 양의 발송 횟수 |
| `EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS` | String · Auth 필수 | 승인된 양의 인증 실패 제한 |
| `SIGNUP_TERMS_AGE_OVER_14_VERSION`, `SIGNUP_TERMS_TERMS_OF_SERVICE_VERSION`, `SIGNUP_TERMS_PRIVACY_POLICY_VERSION`, `SIGNUP_TERMS_MARKETING_CONSENT_VERSION` | String · Auth 필수 | 약관 DB·프론트 원문과 대응하는 승인 버전. 30자 이내이며 공백·자리표시자로 대체하지 않음 |
| `GOOGLE_CLIENT_IDS` | String · 선택 | 승인된 웹 OAuth Client ID 목록. 미설정 시 Google 로그인 차단 |
| `GOOGLE_SIGNUP_TTL_SECONDS` | String · 선택 | 기존 구현의 1~600초 범위. 미설정 시 기존 600초 초안 유지 |
| `EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS`, `EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS` | String · 선택 | 승인 시 두 값을 함께 설정. 둘 다 없으면 재설정 발송·확인은 차단 |

SMTP 제공자에 따라 인증 SMTP와 implicit TLS·필수 STARTTLS 중 하나를 선택한다. 서버 이름 검증은 켜고 기존 연결·읽기·쓰기 5초 기본 타임아웃을 유지한다. 제공자가 다른 인증 방식을 요구하면 자격증명을 우회하지 않고 주입 경로와 검증 기준을 재검토한다.

Access 15분·세션 최대 8시간, Secure·SameSite=None 쿠키, Outbox 재시도·대기 기준 등 기존 구현값을 바꾸지 않는다. 비밀번호 재설정 기간의 설정은 MVP 배포 승인을 의미하지 않는다.

## 적용 전후 확인

1. 관련 인증 PR의 리뷰·병합과 배포할 이미지의 범위를 확인한다.
2. 운영 발신 계정·발송 제한·약관 원문과 버전을 승인하고, 약관 DB의 코드·필수 여부·버전을 맞춘다. 초기 데이터를 임의로 생성하지 않는다.
3. 파라미터와 조회·복호화 권한을 준비한다. 여러 조회 배치가 하나의 스냅샷은 아니므로 설정 변경을 마친 뒤 배포하며, 키 변경 중에는 배포를 실행하지 않는다.
4. `.env.deploy`에서 `AUTH_ENV_ENABLED=true`를 지정하고 기존 배포 경로로 적용한다. 새 설정 생성 실패 시 기존 환경은 유지되며 배포는 실패로 보고된다.
5. 배포 이미지·DB migration·공개 OpenAPI 경로를 확인하고 이메일 발송·상태 조회·가입·로그인·갱신·로그아웃을 검증한다. `/health` 성공만으로 인증 기능 준비를 판정하지 않는다.

실제 Parameter Store·EC2 적용, 운영 SMTP 수신, 승인 약관 DB와 실제 Google 계정·브라우저 연동은 별도 환경 검증 대상이다. 배포 실패 시 기존 이미지·환경 복구는 기존 배포 스크립트를 따르며 DB migration은 자동으로 되돌아가지 않는다.

## 임시 구현 기준과 팀 협의 사항

아래는 기존 명세에 구체적인 기준이 없어 구현을 위해 임의로 선택한 내용이다. 팀 확정 정책이 아니며, 승인 시 설정·문서·검증을 함께 갱신한다. 상세 쟁점·선택지·영향·재검토 조건은 [배포 협의 문서](../decisions/auth-term-deployment-draft.md)에 기록한다.

| 항목 | 임시 구현 기준 | 영향·팀 확인 및 재검토 조건 |
| --- | --- | --- |
| 적용 시점 | `AUTH_ENV_ENABLED` 누락·false는 DB만 조회하고 true는 Auth·Term 설정도 조회 | 인증 이미지 배포 전에 필수 값을 준비하고 활성화해야 한다. 단계별 배포 범위를 확정할 때 재검토한다. |
| 설정 관리·필수 범위 | 기존 Parameter Store 경로에서 허용한 이름만 조회. 이메일 가입·로그인의 핵심 설정은 함께 필수로 검증하고 Google·재설정 기간은 선택 | 수동으로 추가한 관리 대상 값은 다음 생성 때 보존하지 않는다. 기능별 활성화·설정 소유 기준을 정할 때 재검토한다. |
| 비밀값·파일 보호 | DB 비밀번호·HMAC 키·Outbox 키·SMTP 비밀번호는 SecureString. 검증 후 환경 파일을 권한 600으로 교체 | EC2 조회·KMS 복호화 권한, 키 교체와 환경 백업 보관 기준은 인프라 담당자와 확인한다. |
| SMTP 연결 | 인증 SMTP와 implicit TLS 또는 필수 STARTTLS만 허용. 서버 이름 검증, 연결·읽기·쓰기 기본 5초 | 제공자와 인증 방식·허용 지연을 확정할 때 재검토한다. 실제 운영 발신 계정은 선택하지 않았다. |
| 설정 조회 일관성 | 이름을 최대 10개씩 조회하고 전체 검증 후 파일 교체. 조회 배치 전체의 스냅샷은 보장하지 않음 | 설정 변경을 마친 뒤 배포한다. 키 교체·동시 설정 변경을 운영할 때 버전 묶음 방식을 검토한다. |
| 약관 초기 데이터 | 환경변수로 승인 버전만 연결하고 미승인 원문·버전·DB 데이터를 생성하지 않음 | 네 약관의 공식 원문·버전·필수 여부 승인 후 DB와 프론트 원문을 맞춘다. |

아직 값을 정하지 않은 항목은 운영 메일 제공자·발신 계정, 메일 발송 간격·시간당/일당 제한·인증 실패 제한과 복구, 약관 원문·승인 버전, 실제 배포 Origin, Google Client ID·지원 계정, 비밀번호 찾기 배포 범위·재설정 유효기간이다. 테스트 값을 운영 값으로 사용하지 않는다. 회원가입 인증번호 5분·인증 완료 후 가입 10분은 최신 기술 정책의 기준이며 이 미정 항목에 포함하지 않는다.
